package com.dschat.app.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ApiMessage>,
    val stream: Boolean = true,
    val temperature: Double? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    @SerialName("stream_options") val streamOptions: StreamOptions? = null
)

@Serializable
data class StreamOptions(@SerialName("include_usage") val includeUsage: Boolean = true)

/** Token accounting returned by the API (OpenAI-compatible). */
@Serializable
data class Usage(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0
)

// content is either a JSON string (text-only) or an array of parts (text + image_url) for vision.
@Serializable
data class ApiMessage(
    val role: String,
    val content: JsonElement,
    @SerialName("reasoning_content") val reasoningContent: String? = null
)

fun textApiMessage(role: String, text: String, reasoning: String? = null): ApiMessage =
    ApiMessage(role, JsonPrimitive(text), reasoning)

/** A multimodal user message: optional text + one or more image data-URLs (OpenAI image_url form). */
fun imageApiMessage(role: String, text: String, imageUrls: List<String>): ApiMessage =
    ApiMessage(
        role = role,
        content = buildJsonArray {
            if (text.isNotBlank()) add(buildJsonObject { put("type", "text"); put("text", text) })
            imageUrls.forEach { url ->
                add(buildJsonObject {
                    put("type", "image_url")
                    putJsonObject("image_url") { put("url", url) }
                })
            }
        }
    )

@Serializable
data class StreamChunk(
    val choices: List<StreamChoice> = emptyList(),
    val usage: Usage? = null
)

@Serializable
data class StreamChoice(
    val delta: Delta = Delta(),
    @SerialName("finish_reason") val finishReason: String? = null
)

@Serializable
data class Delta(
    val content: String? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null
)

@Serializable
data class ModelsResponse(val data: List<ModelInfo> = emptyList())

@Serializable
data class ModelInfo(val id: String)

// ---- Agent (tool-calling, non-streaming) ----

@Serializable
data class FunctionCall(
    val name: String = "",
    val arguments: String = "" // JSON string
)

@Serializable
data class ToolCall(
    val id: String = "",
    val type: String = "function",
    val function: FunctionCall = FunctionCall()
)

/** A message that may carry tool_calls (assistant) or a tool result (tool). */
@Serializable
data class AgentMessage(
    val role: String,
    val content: String? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCall>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    val name: String? = null
)

@Serializable
data class AgentChatRequest(
    val model: String,
    val messages: List<AgentMessage>,
    val stream: Boolean = false,
    val temperature: Double? = null,
    val tools: List<JsonObject>? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    @SerialName("parallel_tool_calls") val parallelToolCalls: Boolean? = null
)

@Serializable
data class ChatCompletionResponse(
    val choices: List<CompletionChoice> = emptyList(),
    val usage: Usage? = null
)

@Serializable
data class CompletionChoice(
    val message: AgentMessage = AgentMessage(role = "assistant"),
    @SerialName("finish_reason") val finishReason: String? = null
)

@Serializable
data class ApiErrorEnvelope(val error: ApiError? = null)

@Serializable
data class ApiError(
    val message: String? = null,
    val type: String? = null,
    val code: String? = null
)
