package com.dschat.app.agent.tasks

import android.content.Context
import com.dschat.app.data.local.ContactDao
import com.dschat.app.data.local.ContactMessageEntity
import com.dschat.app.data.remote.AgentMessage
import com.dschat.app.data.repository.ChatRepository
import com.dschat.app.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-contact auto-reply pipeline:
 *  - stores each incoming message in that contact's persistent thread (cross-day context),
 *  - debounces so several rapid messages become ONE reply,
 *  - builds the contact's recent history into an LLM prompt and drafts a reply,
 *  - auto-sends it for whitelisted contacts, else posts a one-tap "发送" draft notification.
 */
class ReplyEngine(
    private val appContext: Context,
    private val dao: ContactDao,
    private val repo: ChatRepository,
    private val settings: SettingsRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = ConcurrentHashMap<String, Job>()
    private val lastNotifKey = ConcurrentHashMap<String, String>()

    fun onIncoming(app: String, contact: String, text: String, sbnKey: String) {
        if (contact.isBlank() || text.isBlank()) return
        val key = "$app|$contact"
        lastNotifKey[key] = sbnKey
        scope.launch {
            runCatching {
                dao.insert(ContactMessageEntity(contactKey = key, app = app, contact = contact, role = "them", text = text, createdAt = System.currentTimeMillis()))
            }
        }
        // Debounce: wait for a quiet window so multiple quick messages fold into one reply.
        pending[key]?.cancel()
        pending[key] = scope.launch {
            delay(DEBOUNCE_MS)
            pending.remove(key)
            runCatching { process(key, app, contact) }
        }
    }

    private suspend fun process(key: String, app: String, contact: String) {
        val modelId = settings.currentModel().id
        if (!settings.hasKeyFor(modelId)) return
        val history = dao.recentFor(key, HISTORY_LIMIT)
        if (history.none { it.role == "them" }) return

        val msgs = ArrayList<AgentMessage>()
        msgs += AgentMessage(role = "system", content = systemPrompt(app, contact))
        history.forEach { m ->
            msgs += AgentMessage(role = if (m.role == "me") "assistant" else "user", content = m.text)
        }
        val reply = runCatching { repo.chatCompletion(modelId, msgs, null).content?.trim() }.getOrNull()
        if (reply.isNullOrBlank()) return

        runCatching {
            dao.insert(ContactMessageEntity(contactKey = key, app = app, contact = contact, role = "me", text = reply, createdAt = System.currentTimeMillis()))
        }
        val notifKey = lastNotifKey[key]
        val autoSent = settings.isContactAuto(key) && notifKey != null && (
            NotificationCaptureService.trySendReply(appContext, notifKey, reply) ||
                NotificationCaptureService.autoSendViaAccessibility(appContext, notifKey, reply)
            )
        if (!autoSent) {
            ReplyNotifier.postDraft(appContext, key, contact, reply, notifKey)
        }
    }

    private fun systemPrompt(app: String, contact: String): String = """
        你在替我用「$app」回复好友「$contact」。模仿我的口吻，用自然、口语化、简短的中文回复对方最新的消息。
        下面是你们最近的聊天（user = 对方，assistant = 我之前发出的）。只输出这次要发出去的回复内容本身，不要加引号、不要解释、不要署名。
    """.trimIndent()

    companion object {
        private const val DEBOUNCE_MS = 12_000L
        private const val HISTORY_LIMIT = 20
    }
}
