package com.dschat.app.domain

import kotlinx.serialization.Serializable

/** Chat roles as understood by the DeepSeek (OpenAI-compatible) API. */
enum class Role(val apiValue: String) {
    USER("user"),
    ASSISTANT("assistant"),
    SYSTEM("system");

    companion object {
        fun fromApi(value: String): Role = entries.firstOrNull { it.apiValue == value } ?: USER
    }
}

/** A selectable model. The list is user-editable and can be fetched from the API, so it never
 *  goes stale. `reasoning` is just a UI hint. [baseUrl]/[apiKey] are optional per-model overrides:
 *  null/blank means "use the global API 地址 / API Key" — this lets one app mix providers
 *  (DeepSeek + Kimi + Claude…), each pointed at its own OpenAI-compatible endpoint and key. */
@Serializable
data class ChatModel(
    val id: String,
    val displayName: String,
    val reasoning: Boolean = false,
    val baseUrl: String? = null,
    val apiKey: String? = null,
    /** True if the model itself accepts images (sent as multimodal); false → local OCR/labels fallback. */
    val vision: Boolean = false,
    /** Provider/supplier label for the two-level (provider → model) picker. */
    val provider: String = ""
)

/** Infer a provider label from a base URL (for grouping + migrating older flat model lists). */
fun providerFromBaseUrl(url: String?): String = when {
    url.isNullOrBlank() -> "DeepSeek"
    url.contains("deepseek") -> "DeepSeek"
    url.contains("moonshot") -> "Kimi"
    url.contains("dashscope") || url.contains("aliyun") -> "通义千问"
    url.contains("bigmodel") || url.contains("zhipu") -> "智谱"
    url.contains("openrouter") -> "OpenRouter"
    url.contains("openai.com") -> "OpenAI"
    else -> "自定义"
}

/** Built-in defaults: current DeepSeek lineup + popular multi-provider options (each needs its own
 *  key, filled in 模型管理). All endpoints are OpenAI-compatible. Users can add/remove their own. */
val DEFAULT_MODELS: List<ChatModel> = listOf(
    ChatModel("deepseek-v4-flash", "DeepSeek V4 Flash", reasoning = true, baseUrl = "https://api.deepseek.com", provider = "DeepSeek"),
    ChatModel("deepseek-v4-pro", "DeepSeek V4 Pro", reasoning = true, baseUrl = "https://api.deepseek.com", provider = "DeepSeek"),
    ChatModel("kimi-latest", "Kimi", baseUrl = "https://api.moonshot.cn/v1", vision = true, provider = "Kimi"),
    ChatModel("glm-4.5", "GLM-4.5", baseUrl = "https://open.bigmodel.cn/api/paas/v4", provider = "智谱"),
    ChatModel("anthropic/claude-sonnet-4", "Claude Sonnet 4", baseUrl = "https://openrouter.ai/api/v1", vision = true, provider = "OpenRouter")
)

/** Per-model token accounting, aggregated locally for the usage/billing screen. */
@Serializable
data class UsageStat(
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val calls: Long = 0
) {
    val totalTokens: Long get() = promptTokens + completionTokens
}

/** A named memory entry. Enabled entries are auto-injected into every conversation. */
@Serializable
data class MemoryItem(
    val id: Long,
    val title: String,
    val content: String,
    val enabled: Boolean = true,
    /** True if created automatically by the memory extractor (vs. manually by the user). */
    val auto: Boolean = false,
    /** Category label for grouping when injected, e.g. 编码偏好 / 个人信息 / 饮食. Blank → 其它. */
    val category: String = "",
    /** Epoch ms when first created; 0 for legacy/manual entries. */
    val createdAt: Long = 0L,
    /** Epoch ms of last edit (manual or auto-merge); 0 if never. */
    val updatedAt: Long = 0L,
    /** Epoch ms this memory was last judged relevant to a conversation; drives smarter pruning. */
    val lastReferencedAt: Long = 0L,
    /** Conversation this fact was captured from; 0 = unknown / manually created. Enables "查看来源". */
    val sourceConversationId: Long = 0L,
    /** Review mode: an auto-captured memory awaiting user confirmation. Pending entries are NOT injected. */
    val pending: Boolean = false,
    /** Pinned memories are always injected (skip relevance filtering) and never auto-pruned. */
    val pinned: Boolean = false
)

/** Canonical category labels — the single source shared by the extractor prompt, inferCategory,
 *  the save_memory tool, and the management UI (keeps the three in sync). */
val MEMORY_CATEGORIES = listOf(
    "个人信息", "沟通偏好", "编码偏好", "饮食", "环境设备", "目标计划", "人际关系", "其它"
)

/** One memory mutation proposed by the MemoryExtractor: op = "add" | "update" | "skip". */
@Serializable
data class MemoryOp(
    val op: String = "skip",
    val id: Long = -1L,        // target id for "update"; ignored for add/skip
    val title: String = "",
    val content: String = "",
    val category: String = ""  // e.g. 编码偏好 / 个人信息 / 饮食 / 环境设备 / 目标计划 / 人际关系
)

/** The extractor's JSON result (object root so TaskClassifier-style extractJson works). */
@Serializable
data class MemoryExtraction(
    val ops: List<MemoryOp> = emptyList(),
    /** ids of EXISTING memories the model judged relevant to this turn (bumps their lastReferencedAt). */
    val referencedIds: List<Long> = emptyList()
)

/** What applyMemoryOps actually changed — drives the in-chat "🧠 已记住…" indicator. */
data class MemoryOpResult(
    val addedTitles: List<String> = emptyList(),
    val updatedTitles: List<String> = emptyList()
)

enum class ToolStatus { RUNNING, DONE, ERROR, DENIED }

/** One tool invocation within a tool-call group. */
data class ToolRun(
    val id: Long,
    val name: String,
    val args: String,
    val result: String? = null,
    val status: ToolStatus = ToolStatus.RUNNING,
    val durationMs: Long? = null,
    /** When this call started (epoch ms) — used to tick a live elapsed timer while RUNNING. */
    val startedAt: Long = 0L
)

/** UI-facing chat message (an in-memory representation, may be mid-stream).
 *  When [tools] is non-null, this row is a collapsible group of agent tool calls instead of a bubble.
 *  [transient] marks intermediate progress narration that is shown but not sent back as history. */
data class UiMessage(
    val id: Long,
    val role: Role,
    val content: String,
    val reasoning: String? = null,
    val isStreaming: Boolean = false,
    val error: Boolean = false,
    val transient: Boolean = false,
    val tools: List<ToolRun>? = null,
    /** base64 data-URLs of attached images (in-session only; not persisted to Room). */
    val images: List<String>? = null,
    /** Text actually sent to the API instead of [content] — e.g. local OCR/labels of an image, or an attached file's text. */
    val apiText: String? = null,
    /** Name of an attached file (shown as a small chip on the user bubble); null if none. */
    val attachmentName: String? = null,
    /** Generation start (epoch ms) — drives the live elapsed timer while streaming. 0 = N/A. */
    val startedAt: Long = 0L,
    /** Total generation time once finished (ms); null while streaming or for loaded messages. */
    val genMillis: Long? = null,
    /** Time spent in the reasoning/thinking phase (ms), if the model exposed reasoning. */
    val thinkMillis: Long? = null,
    /** Exact token usage for this turn (from the API's usage event); null if unknown. */
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    /** Titles of memories auto-captured right after this assistant reply (in-session only; not
     *  persisted to Room). Non-null/non-empty → render the "🧠 已记住…" indicator under the reply. */
    val memoryCaptured: List<String>? = null
)
