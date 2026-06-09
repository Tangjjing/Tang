package com.dschat.app.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException
import java.util.concurrent.TimeUnit

// ---- response DTOs ----
@Serializable
private data class AnthropicResp(
    val content: List<AnthropicBlock> = emptyList(),
    @SerialName("stop_reason") val stopReason: String? = null,
    val usage: AnthropicUsage? = null
)

@Serializable
private data class AnthropicBlock(
    val type: String = "",
    val text: String? = null,
    val id: String? = null,
    val name: String? = null,
    val input: JsonElement? = null
)

@Serializable
private data class AnthropicUsage(
    @SerialName("input_tokens") val inputTokens: Int = 0,
    @SerialName("output_tokens") val outputTokens: Int = 0
)

@Serializable
private data class AnthropicErrEnvelope(val error: AnthropicErr? = null)

@Serializable
private data class AnthropicErr(val type: String? = null, val message: String? = null)

/**
 * Client for the **Anthropic Messages API** (`POST {baseUrl}/v1/messages`) — used by providers that only
 * speak Anthropic's protocol (e.g. Kimi 的 api.kimi.com/coding，普通 OpenAI 格式会被 403)。
 *
 * Differences from OpenAI handled here so the rest of the app is unchanged: system prompt is a top-level
 * field (not a message), `max_tokens` is required, content/tool blocks differ, and the SSE event shape is
 * message_start / content_block_delta / message_delta / message_stop. Inputs come in as the app's existing
 * OpenAI-shaped [ApiMessage]/[AgentMessage]; outputs are emitted as the same [StreamEvent]/[AgentMessage]
 * so [com.dschat.app.ui.chat.ChatViewModel] needs no protocol awareness.
 */
class AnthropicApi {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private fun messagesUrl(baseUrl: String) = baseUrl.trimEnd('/') + "/v1/messages"

    private fun Request.Builder.anthropicHeaders(apiKey: String) = this
        .addHeader("Authorization", "Bearer $apiKey")
        .addHeader("anthropic-version", ANTHROPIC_VERSION)
        .addHeader("content-type", "application/json")

    // ---- streaming chat (plain, no tools) ----

    fun streamChat(
        apiKey: String,
        baseUrl: String,
        model: String,
        messages: List<ApiMessage>,
        temperature: Double?,
        maxTokens: Int = DEFAULT_MAX_TOKENS
    ): Flow<StreamEvent> = callbackFlow {
        val (system, msgs) = splitSystem(messages)
        val payload = buildJsonObject {
            put("model", model)
            put("max_tokens", maxTokens)
            put("stream", true)
            if (system.isNotBlank()) put("system", system)
            temperature?.let { put("temperature", it.coerceIn(0.0, 1.0)) }
            put("messages", JsonArray(msgs))
        }
        val request = Request.Builder()
            .url(messagesUrl(baseUrl))
            .anthropicHeaders(apiKey)
            .addHeader("Accept", "text/event-stream")
            .post(json.encodeToString(JsonObject.serializer(), payload).toRequestBody(JSON_MEDIA))
            .build()

        var inputTokens = 0
        var usageSent = false
        val listener = object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                try {
                    val obj = json.parseToJsonElement(data).jsonObject
                    when (type) {
                        "message_start" ->
                            inputTokens = obj["message"]?.jsonObject?.get("usage")?.jsonObject?.get("input_tokens")?.jsonPrimitive?.intOrNull ?: 0
                        "content_block_delta" -> {
                            val delta = obj["delta"]?.jsonObject ?: return
                            when (delta["type"]?.jsonPrimitive?.contentOrNull) {
                                "text_delta" -> delta["text"]?.jsonPrimitive?.contentOrNull?.let { if (it.isNotEmpty()) trySend(StreamEvent.Content(it)) }
                                "thinking_delta" -> delta["thinking"]?.jsonPrimitive?.contentOrNull?.let { if (it.isNotEmpty()) trySend(StreamEvent.Reasoning(it)) }
                            }
                        }
                        "message_delta" -> {
                            val out = obj["usage"]?.jsonObject?.get("output_tokens")?.jsonPrimitive?.intOrNull
                            if (out != null && !usageSent) { usageSent = true; trySend(StreamEvent.Usage(inputTokens, out)) }
                        }
                        "message_stop" -> { trySend(StreamEvent.Done); close() }
                        "error" -> {
                            val m = obj["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull ?: "请求出错"
                            trySend(StreamEvent.Error(m)); close()
                        }
                    }
                } catch (_: Exception) {
                    // ignore keep-alive / non-JSON
                }
            }

            override fun onClosed(eventSource: EventSource) {
                trySend(StreamEvent.Done); close()
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                trySend(StreamEvent.Error(errorMessage(t, response)))
                close()
            }
        }
        val source = EventSources.createFactory(client).newEventSource(request, listener)
        awaitClose { source.cancel() }
    }.buffer(Channel.UNLIMITED)

    // ---- non-streaming chat with tools (agent loop) ----

    suspend fun chatCompletion(
        apiKey: String,
        baseUrl: String,
        model: String,
        messages: List<AgentMessage>,
        tools: List<JsonObject>?,
        temperature: Double?,
        maxTokens: Int,
        onUsage: ((Int, Int) -> Unit)? = null,
        onMeta: ((finishReason: String?) -> Unit)? = null
    ): AgentMessage = withContext(Dispatchers.IO) {
        val (system, msgs) = splitSystemAgent(messages)
        val payload = buildJsonObject {
            put("model", model)
            put("max_tokens", maxTokens)
            if (system.isNotBlank()) put("system", system)
            temperature?.let { put("temperature", it.coerceIn(0.0, 1.0)) }
            put("messages", JsonArray(msgs))
            if (!tools.isNullOrEmpty()) put("tools", JsonArray(tools.map { toAnthropicTool(it) }))
        }
        val request = Request.Builder()
            .url(messagesUrl(baseUrl))
            .anthropicHeaders(apiKey)
            .post(json.encodeToString(JsonObject.serializer(), payload).toRequestBody(JSON_MEDIA))
            .build()

        var lastError: Exception? = null
        repeat(MAX_RETRIES) { attempt ->
            val resp = try {
                client.newCall(request).execute()
            } catch (e: IOException) {
                lastError = e
                if (attempt == MAX_RETRIES - 1) throw e
                kotlinx.coroutines.delay(600L * (attempt + 1)); return@repeat
            }
            val retry = resp.use {
                val body = it.body?.string().orEmpty()
                when {
                    it.isSuccessful -> {
                        val parsed = json.decodeFromString(AnthropicResp.serializer(), body)
                        parsed.usage?.let { u -> onUsage?.invoke(u.inputTokens, u.outputTokens) }
                        onMeta?.invoke(parsed.stopReason)
                        return@withContext toAgentMessage(parsed)
                    }
                    (it.code == 429 || it.code in 500..599) && attempt < MAX_RETRIES - 1 -> {
                        lastError = IOException("HTTP ${it.code}")
                        true
                    }
                    else -> {
                        val msg = try { json.decodeFromString(AnthropicErrEnvelope.serializer(), body).error?.message } catch (_: Exception) { null }
                        throw IOException("HTTP ${it.code}: ${msg ?: body.take(300)}")
                    }
                }
            }
            if (retry) kotlinx.coroutines.delay(600L * (attempt + 1))
        }
        throw lastError ?: IOException("请求失败")
    }

    /** Fetch model ids from {baseUrl}/v1/models (Anthropic-compatible providers expose the same shape). */
    suspend fun listModels(apiKey: String, baseUrl: String): List<String> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/v1/models")
            .anthropicHeaders(apiKey)
            .get()
            .build()
        client.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}: ${body.take(200)}")
            json.decodeFromString(ModelsResponse.serializer(), body).data.map { it.id }
        }
    }

    // ---- conversions ----

    /** OpenAI-style messages → (system text, anthropic message JSON list). */
    private fun splitSystem(messages: List<ApiMessage>): Pair<String, List<JsonObject>> {
        val sys = StringBuilder()
        val out = mutableListOf<JsonObject>()
        for (m in messages) {
            if (m.role == "system") {
                (m.content as? JsonPrimitive)?.contentOrNull?.let { if (sys.isNotEmpty()) sys.append("\n\n"); sys.append(it) }
                continue
            }
            out += buildJsonObject {
                put("role", if (m.role == "assistant") "assistant" else "user")
                put("content", anthropicContent(m.content))
            }
        }
        return sys.toString() to out
    }

    /** Agent messages (may carry tool_calls / tool results) → (system, anthropic message JSON list). */
    private fun splitSystemAgent(messages: List<AgentMessage>): Pair<String, List<JsonObject>> {
        val sys = StringBuilder()
        val out = mutableListOf<JsonObject>()
        var pendingToolResults = mutableListOf<JsonObject>() // consecutive tool results fold into one user msg

        fun flushToolResults() {
            if (pendingToolResults.isNotEmpty()) {
                val blocks = pendingToolResults.toList()
                out += buildJsonObject { put("role", "user"); put("content", JsonArray(blocks)) }
                pendingToolResults = mutableListOf()
            }
        }

        for (m in messages) {
            when (m.role) {
                "system" -> { if (sys.isNotEmpty()) sys.append("\n\n"); sys.append(m.content.orEmpty()) }
                "tool" -> pendingToolResults += buildJsonObject {
                    put("type", "tool_result")
                    put("tool_use_id", m.toolCallId ?: "")
                    put("content", m.content.orEmpty())
                }
                "assistant" -> {
                    flushToolResults()
                    if (!m.toolCalls.isNullOrEmpty()) {
                        out += buildJsonObject {
                            put("role", "assistant")
                            put("content", buildJsonArray {
                                if (!m.content.isNullOrBlank()) add(buildJsonObject { put("type", "text"); put("text", m.content!!) })
                                for (tc in m.toolCalls!!) add(buildJsonObject {
                                    put("type", "tool_use")
                                    put("id", tc.id)
                                    put("name", tc.function.name)
                                    put("input", parseArgs(tc.function.arguments))
                                })
                            })
                        }
                    } else if (!m.content.isNullOrBlank()) {
                        out += buildJsonObject { put("role", "assistant"); put("content", m.content!!) }
                    }
                }
                else -> { // user
                    flushToolResults()
                    if (!m.content.isNullOrBlank()) out += buildJsonObject { put("role", "user"); put("content", m.content!!) }
                }
            }
        }
        flushToolResults()
        return sys.toString() to out
    }

    /** Anthropic response → an OpenAI-shaped AgentMessage (text + tool_calls) the agent loop understands. */
    private fun toAgentMessage(resp: AnthropicResp): AgentMessage {
        val text = StringBuilder()
        val toolCalls = mutableListOf<ToolCall>()
        for (b in resp.content) {
            when (b.type) {
                "text" -> b.text?.let { text.append(it) }
                "tool_use" -> toolCalls += ToolCall(
                    id = b.id ?: "",
                    type = "function",
                    function = FunctionCall(name = b.name ?: "", arguments = (b.input ?: buildJsonObject {}).toString())
                )
            }
        }
        return AgentMessage(
            role = "assistant",
            content = text.toString().ifEmpty { null },
            toolCalls = toolCalls.ifEmpty { null }
        )
    }

    /** OpenAI tool schema {type:function,function:{name,description,parameters}} → Anthropic {name,description,input_schema}. */
    private fun toAnthropicTool(t: JsonObject): JsonObject {
        val fn = t["function"]?.jsonObject ?: t
        return buildJsonObject {
            put("name", fn["name"]?.jsonPrimitive?.contentOrNull ?: "")
            fn["description"]?.let { put("description", it) }
            put("input_schema", fn["parameters"] ?: buildJsonObject { put("type", "object") })
        }
    }

    /** Convert an OpenAI message content (string or [text/image_url] parts) to Anthropic content. */
    private fun anthropicContent(content: JsonElement): JsonElement = when (content) {
        is JsonPrimitive -> content // plain text string
        is JsonArray -> buildJsonArray {
            for (part in content) {
                val p = part as? JsonObject ?: continue
                when (p["type"]?.jsonPrimitive?.contentOrNull) {
                    "text" -> add(buildJsonObject { put("type", "text"); put("text", p["text"]?.jsonPrimitive?.contentOrNull ?: "") })
                    "image_url" -> {
                        val url = p["image_url"]?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull ?: ""
                        if (url.startsWith("data:")) {
                            val media = url.substringAfter("data:").substringBefore(";").ifBlank { "image/jpeg" }
                            val b64 = url.substringAfter("base64,", "")
                            if (b64.isNotEmpty()) add(buildJsonObject {
                                put("type", "image")
                                putJsonObject("source") { put("type", "base64"); put("media_type", media); put("data", b64) }
                            })
                        }
                    }
                }
            }
        }
        else -> JsonPrimitive(content.toString())
    }

    private fun parseArgs(arguments: String): JsonElement = try {
        if (arguments.isBlank()) buildJsonObject {} else json.parseToJsonElement(arguments)
    } catch (_: Exception) {
        buildJsonObject {}
    }

    private fun errorMessage(t: Throwable?, response: Response?): String = buildString {
        if (response != null) {
            append("HTTP ").append(response.code)
            val body = try { response.body?.string() } catch (_: Exception) { null }
            if (!body.isNullOrBlank()) {
                val parsed = try { json.decodeFromString(AnthropicErrEnvelope.serializer(), body).error?.message } catch (_: Exception) { null }
                append(": ").append(parsed ?: body.take(300))
            }
        } else append(t?.message ?: "网络连接失败")
    }

    companion object {
        private const val ANTHROPIC_VERSION = "2023-06-01"
        private const val MAX_RETRIES = 3
        const val DEFAULT_MAX_TOKENS = 8192
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
