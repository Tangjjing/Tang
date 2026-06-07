package com.dschat.app.data.repository

import com.dschat.app.data.local.ChatDao
import com.dschat.app.data.local.ConversationEntity
import com.dschat.app.data.local.MessageEntity
import com.dschat.app.data.remote.AgentMessage
import com.dschat.app.data.remote.ApiMessage
import com.dschat.app.data.remote.DeepSeekApi
import com.dschat.app.data.remote.StreamEvent
import com.dschat.app.data.settings.SettingsRepository
import com.dschat.app.domain.Role
import com.dschat.app.domain.providerFromBaseUrl
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

class ChatRepository(
    private val dao: ChatDao,
    private val api: DeepSeekApi,
    private val settings: SettingsRepository
) {
    fun observeConversations(): Flow<List<ConversationEntity>> = dao.observeConversations()

    suspend fun getConversation(id: Long): ConversationEntity? = dao.getConversation(id)

    suspend fun getMessages(conversationId: Long): List<MessageEntity> =
        dao.getMessages(conversationId)

    suspend fun createConversation(modelId: String, title: String, systemPrompt: String? = null): Long {
        val now = System.currentTimeMillis()
        return dao.insertConversation(
            ConversationEntity(
                title = title,
                model = modelId,
                systemPrompt = systemPrompt,
                createdAt = now,
                updatedAt = now
            )
        )
    }

    suspend fun updateConversationSystemPrompt(id: Long, prompt: String?) =
        dao.updateSystemPrompt(id, prompt)

    suspend fun updateConversationModel(id: Long, modelId: String) =
        dao.updateConversationModel(id, modelId)

    /** Fetches available model ids from the given model's endpoint (per-model creds). */
    suspend fun listModels(modelId: String): List<String> {
        val (key, url) = settings.credsFor(modelId)
        return api.listModels(key, url)
    }

    /** Fetches model ids using explicit creds — for adding a brand-new provider. */
    suspend fun listModelsWith(apiKey: String, baseUrl: String): List<String> =
        api.listModels(apiKey, baseUrl)

    /** Non-streaming completion with tools — used by the agent loop. */
    suspend fun chatCompletion(
        modelId: String,
        messages: List<AgentMessage>,
        tools: List<JsonObject>?,
        onMeta: ((finishReason: String?) -> Unit)? = null
    ): AgentMessage {
        val (key, url) = settings.credsFor(modelId)
        // Only hint parallel tool calls to providers we've verified accept it; client parallelizes regardless.
        val parallel = if (tools != null && providerFromBaseUrl(url) in setOf("DeepSeek", "智谱")) true else null
        return api.chatCompletion(
            apiKey = key,
            baseUrl = url,
            model = modelId,
            messages = messages,
            temperature = settings.temperature.value.toDouble(),
            tools = tools,
            onUsage = { p, c -> settings.recordUsage(modelId, p, c) },
            maxTokens = DeepSeekApi.DEFAULT_AGENT_MAX_TOKENS,
            parallelToolCalls = parallel,
            onMeta = onMeta
        )
    }

    suspend fun addMessage(
        conversationId: Long,
        role: Role,
        content: String,
        reasoning: String? = null
    ): Long = dao.insertMessage(
        MessageEntity(
            conversationId = conversationId,
            role = role.apiValue,
            content = content,
            reasoning = reasoning,
            createdAt = System.currentTimeMillis()
        )
    )

    suspend fun renameConversation(id: Long, title: String) =
        dao.updateConversationMeta(id, title, System.currentTimeMillis())

    suspend fun touchConversation(id: Long) =
        dao.touchConversation(id, System.currentTimeMillis())

    suspend fun deleteConversation(id: Long) = dao.deleteConversation(id)

    /** Drop every message after [afterId] (keeps it) — used by 重新生成. */
    suspend fun deleteMessagesAfter(conversationId: Long, afterId: Long) =
        dao.deleteMessagesAfter(conversationId, afterId)

    /** Drop [fromId] and everything after it — used by 编辑后重发. */
    suspend fun deleteMessagesFrom(conversationId: Long, fromId: Long) =
        dao.deleteMessagesFrom(conversationId, fromId)

    /** Streams a chat completion. Resolves per-model key/base-URL (falls back to global). */
    fun stream(model: String, messages: List<ApiMessage>): Flow<StreamEvent> {
        val (key, url) = settings.credsFor(model)
        return api.streamChat(
            apiKey = key,
            baseUrl = url,
            model = model,
            messages = messages,
            temperature = settings.temperature.value.toDouble()
        )
    }
}
