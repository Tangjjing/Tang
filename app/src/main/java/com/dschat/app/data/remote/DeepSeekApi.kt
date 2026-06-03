package com.dschat.app.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.TimeUnit

/** Events emitted while streaming a chat completion. */
sealed interface StreamEvent {
    data class Reasoning(val text: String) : StreamEvent
    data class Content(val text: String) : StreamEvent
    data class Usage(val promptTokens: Int, val completionTokens: Int) : StreamEvent
    data object Done : StreamEvent
    data class Error(val message: String) : StreamEvent
}

/**
 * Thin DeepSeek client. Uses OkHttp SSE for streaming chat completions.
 * The API is OpenAI-compatible: POST {baseUrl}/chat/completions with Bearer auth.
 */
class DeepSeekApi {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // unlimited: streaming response stays open
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun streamChat(
        apiKey: String,
        baseUrl: String,
        model: String,
        messages: List<ApiMessage>,
        temperature: Double?
    ): Flow<StreamEvent> = callbackFlow {
        val url = baseUrl.trimEnd('/') + "/chat/completions"
        val payload = ChatRequest(
            model = model,
            messages = messages,
            stream = true,
            temperature = temperature,
            streamOptions = StreamOptions(includeUsage = true)
        )
        val requestBody = json.encodeToString(ChatRequest.serializer(), payload)
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Accept", "text/event-stream")
            .post(requestBody)
            .build()

        val listener = object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                if (data == "[DONE]") {
                    trySend(StreamEvent.Done)
                    return
                }
                try {
                    val chunk = json.decodeFromString(StreamChunk.serializer(), data)
                    chunk.usage?.let { trySend(StreamEvent.Usage(it.promptTokens, it.completionTokens)) }
                    val delta = chunk.choices.firstOrNull()?.delta ?: return
                    delta.reasoningContent?.let {
                        if (it.isNotEmpty()) trySend(StreamEvent.Reasoning(it))
                    }
                    delta.content?.let {
                        if (it.isNotEmpty()) trySend(StreamEvent.Content(it))
                    }
                } catch (_: Exception) {
                    // Ignore keep-alive / non-JSON lines.
                }
            }

            override fun onClosed(eventSource: EventSource) {
                trySend(StreamEvent.Done)
                close()
            }

            override fun onFailure(
                eventSource: EventSource,
                t: Throwable?,
                response: Response?
            ) {
                val msg = buildString {
                    if (response != null) {
                        append("HTTP ").append(response.code)
                        val body = try {
                            response.body?.string()
                        } catch (_: Exception) {
                            null
                        }
                        if (!body.isNullOrBlank()) {
                            val parsed = try {
                                json.decodeFromString(ApiErrorEnvelope.serializer(), body).error?.message
                            } catch (_: Exception) {
                                null
                            }
                            append(": ").append(parsed ?: body.take(300))
                        }
                    } else {
                        append(t?.message ?: "网络连接失败")
                    }
                }
                trySend(StreamEvent.Error(msg))
                close()
            }
        }

        val factory = EventSources.createFactory(client)
        val eventSource = factory.newEventSource(request, listener)
        awaitClose { eventSource.cancel() }
    }.buffer(Channel.UNLIMITED)

    /** Fetches the available model ids from {baseUrl}/models (OpenAI-compatible). */
    suspend fun listModels(apiKey: String, baseUrl: String): List<String> = withContext(Dispatchers.IO) {
        val url = baseUrl.trimEnd('/') + "/models"
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .get()
            .build()
        client.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}: ${body.take(200)}")
            json.decodeFromString(ModelsResponse.serializer(), body).data.map { it.id }
        }
    }

    /** Non-streaming chat completion with optional tools — used by the agent loop. */
    suspend fun chatCompletion(
        apiKey: String,
        baseUrl: String,
        model: String,
        messages: List<AgentMessage>,
        temperature: Double?,
        tools: List<JsonObject>?,
        onUsage: ((Int, Int) -> Unit)? = null,
        maxTokens: Int? = null,
        parallelToolCalls: Boolean? = null,
        onMeta: ((finishReason: String?) -> Unit)? = null
    ): AgentMessage = withContext(Dispatchers.IO) {
        val url = baseUrl.trimEnd('/') + "/chat/completions"
        val payload = AgentChatRequest(
            model = model,
            messages = messages,
            stream = false,
            temperature = temperature,
            tools = tools,
            maxTokens = maxTokens,
            parallelToolCalls = parallelToolCalls
        )
        val requestBody = json.encodeToString(AgentChatRequest.serializer(), payload)
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()
        // Retry transient failures (network aborts/timeouts, HTTP 429/5xx); never retry other 4xx.
        var lastError: Exception? = null
        var retryDelayMs = 0L
        repeat(MAX_RETRIES) { attempt ->
            val resp = try {
                client.newCall(request).execute()
            } catch (e: IOException) {
                lastError = e
                if (attempt == MAX_RETRIES - 1) throw e
                delay(600L * (attempt + 1))
                return@repeat
            }
            val shouldRetry = resp.use {
                val str = it.body?.string().orEmpty()
                when {
                    it.isSuccessful -> {
                        val parsed = json.decodeFromString(ChatCompletionResponse.serializer(), str)
                        parsed.usage?.let { u -> onUsage?.invoke(u.promptTokens, u.completionTokens) }
                        val choice = parsed.choices.firstOrNull()
                        onMeta?.invoke(choice?.finishReason)
                        return@withContext choice?.message ?: AgentMessage(role = "assistant", content = "")
                    }
                    (it.code == 429 || it.code in 500..599) && attempt < MAX_RETRIES - 1 -> {
                        lastError = IOException("HTTP ${it.code}")
                        // honor Retry-After (seconds, capped) on 429, else exponential-ish backoff
                        retryDelayMs = it.header("Retry-After")?.toLongOrNull()?.coerceIn(0, 10)?.times(1000L)
                            ?: (600L * (attempt + 1))
                        true
                    }
                    else -> {
                        val parsed = try {
                            json.decodeFromString(ApiErrorEnvelope.serializer(), str).error?.message
                        } catch (_: Exception) {
                            null
                        }
                        throw IOException("HTTP ${it.code}: ${parsed ?: str.take(300)}")
                    }
                }
            }
            if (shouldRetry) delay(retryDelayMs)
        }
        throw lastError ?: IOException("请求失败")
    }

    companion object {
        private const val MAX_RETRIES = 3
        /** Generous cap so normal answers aren't truncated, but runaway generation is bounded. */
        const val DEFAULT_AGENT_MAX_TOKENS = 4096
    }
}
