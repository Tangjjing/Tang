package com.dschat.app.data.remote

import com.dschat.app.domain.BalanceResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

    // Derived from the shared base so the connection pool / dispatcher / thread pools are reused.
    private val client = SharedHttp.base.newBuilder()
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

        val factory = EventSources.createFactory(client)
        // Connection-level retry: gotContent/gotReasoning are shared across attempts, so once ANY token
        // has reached the UI we never retry (streamed text is never duplicated). Failures BEFORE the first
        // token (connect refused / 429 / 5xx — common on flaky mobile networks) reconnect silently.
        var gotContent = false
        var gotReasoning = false
        var usageSent = false
        var attempt = 0
        var currentSource: EventSource? = null
        var reconnectJob: Job? = null

        fun connect() {
            currentSource?.cancel() // drop the prior (failed) source before reconnecting, so they never overlap
            // Per-attempt: whether THIS connection ended cleanly ([DONE]/finish_reason) and its finish reason.
            var properlyClosed = false
            var finishReason: String? = null
            val listener = object : EventSourceListener() {
                override fun onEvent(
                    eventSource: EventSource,
                    id: String?,
                    type: String?,
                    data: String
                ) {
                    if (data == "[DONE]") {
                        properlyClosed = true
                        trySend(StreamEvent.Done)
                        return
                    }
                    try {
                        val chunk = json.decodeFromString(StreamChunk.serializer(), data)
                        // Emit usage at most once across attempts (a reconnect could re-send it → double-count).
                        chunk.usage?.let { if (!usageSent) { usageSent = true; trySend(StreamEvent.Usage(it.promptTokens, it.completionTokens)) } }
                        val choice = chunk.choices.firstOrNull() ?: return
                        choice.finishReason?.let { finishReason = it; properlyClosed = true }
                        choice.delta.reasoningContent?.let {
                            if (it.isNotEmpty()) { gotReasoning = true; trySend(StreamEvent.Reasoning(it)) }
                        }
                        choice.delta.content?.let {
                            if (it.isNotEmpty()) { gotContent = true; trySend(StreamEvent.Content(it)) }
                        }
                    } catch (_: Exception) {
                        // Ignore keep-alive / non-JSON lines.
                    }
                }

                override fun onClosed(eventSource: EventSource) {
                    // Closed without [DONE]/finish_reason after streaming content → likely cut off.
                    if (gotContent && !properlyClosed) trySend(StreamEvent.Content(TRUNCATION_NOTE))
                    else if (finishReason == "length") trySend(StreamEvent.Content(LENGTH_NOTE))
                    trySend(StreamEvent.Done)
                    close()
                }

                override fun onFailure(
                    eventSource: EventSource,
                    t: Throwable?,
                    response: Response?
                ) {
                    // Retry only failures BEFORE any token: network drop (response==null) / 429 / 5xx.
                    val code = response?.code
                    val retryable = !gotContent && !gotReasoning && attempt < MAX_RETRIES - 1 &&
                        (response == null || code == 429 || code in 500..599)
                    if (retryable) {
                        val delayMs = response?.header("Retry-After")?.toLongOrNull()?.coerceIn(0, 10)?.times(1000L)
                            ?: (600L * (attempt + 1))
                        try { response?.close() } catch (_: Exception) {}
                        attempt++
                        reconnectJob = launch { delay(delayMs); connect() }
                        return
                    }
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
                    // Salvage a partial answer with a truncation note instead of discarding/silently keeping it.
                    if (gotContent) trySend(StreamEvent.Content(TRUNCATION_NOTE))
                    trySend(StreamEvent.Error(msg))
                    close()
                }
            }
            currentSource = factory.newEventSource(request, listener)
        }

        connect()
        awaitClose { reconnectJob?.cancel(); currentSource?.cancel() }
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

    /**
     * Queries the provider's account balance/quota. Endpoints + response shapes differ per provider,
     * so we branch on [baseUrl] and normalize into [BalanceResult]. Returns UNSUPPORTED for providers
     * without a known balance endpoint (智谱/通义/自定义…). Never throws — failures become a result.
     */
    suspend fun fetchBalance(apiKey: String, baseUrl: String): BalanceResult = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext BalanceResult.NO_KEY
        val base = baseUrl.trimEnd('/')
        val u = base.lowercase()
        val endpoint = when {
            u.contains("deepseek") -> "$base/user/balance"
            u.contains("moonshot") -> "$base/users/me/balance"
            u.contains("openrouter") -> "$base/credits"
            else -> return@withContext BalanceResult.UNSUPPORTED
        }
        try {
            val request = Request.Builder()
                .url(endpoint)
                .addHeader("Authorization", "Bearer $apiKey")
                .get()
                .build()
            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val parsed = try { json.decodeFromString(ApiErrorEnvelope.serializer(), body).error?.message } catch (_: Exception) { null }
                    return@withContext BalanceResult.failed("HTTP ${resp.code}: ${parsed ?: body.take(120)}")
                }
                when {
                    u.contains("deepseek") -> {
                        val b = json.decodeFromString(DeepSeekBalance.serializer(), body)
                        val info = b.balanceInfos.firstOrNull()
                            ?: return@withContext BalanceResult.failed("无余额信息")
                        BalanceResult(
                            supported = true,
                            available = b.isAvailable,
                            display = sym(info.currency) + info.totalBalance,
                            detail = "充值 ${info.toppedUpBalance} · 赠送 ${info.grantedBalance}（${info.currency}）"
                        )
                    }
                    u.contains("moonshot") -> {
                        val d = json.decodeFromString(MoonshotBalance.serializer(), body).data
                            ?: return@withContext BalanceResult.failed("无余额信息")
                        BalanceResult(
                            supported = true,
                            available = d.availableBalance > 0,
                            display = "¥" + money(d.availableBalance),
                            detail = "现金 ${money(d.cashBalance)} · 代金券 ${money(d.voucherBalance)}"
                        )
                    }
                    else -> { // openrouter
                        val d = json.decodeFromString(OpenRouterCredits.serializer(), body).data
                            ?: return@withContext BalanceResult.failed("无余额信息")
                        val remaining = d.totalCredits - d.totalUsage
                        BalanceResult(
                            supported = true,
                            available = remaining > 0,
                            display = "$" + money(remaining),
                            detail = "总额度 ${money(d.totalCredits)} · 已用 ${money(d.totalUsage)}（USD）"
                        )
                    }
                }
            }
        } catch (e: Exception) {
            BalanceResult.failed(e.message ?: "查询失败")
        }
    }

    private fun sym(currency: String): String = when (currency.uppercase()) {
        "CNY", "RMB" -> "¥"
        "USD" -> "$"
        else -> ""
    }

    private fun money(v: Double): String = String.format(java.util.Locale.US, "%.2f", v)

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
        private const val TRUNCATION_NOTE = "\n\n（⚠️ 连接中断，回答可能不完整，可点「重新生成」重试）"
        private const val LENGTH_NOTE = "\n\n（注：回答因达到长度上限被截断）"
    }
}
