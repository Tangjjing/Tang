package com.dschat.app.agent.tasks

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.util.Log
import android.service.notification.StatusBarNotification
import com.dschat.app.App
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Reads every posted notification. For whitelisted apps it extracts the text and hands it to the
 * TaskRepository for AI classification. Also caches any inline-reply action so a drafted reply can
 * be sent later in one tap (works without input-injection — the messaging app's own reply intent).
 */
class NotificationCaptureService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var screenshotWatcher: ScreenshotWatcher? = null

    override fun onListenerConnected() {
        val container = (applicationContext as? App)?.container ?: return
        if (screenshotWatcher == null) {
            screenshotWatcher = ScreenshotWatcher(
                applicationContext,
                shouldProcess = {
                    container.settings.ambientEnabled.value && container.settings.watchScreenshots.value
                },
                onText = { text ->
                    scope.launch {
                        try { container.taskRepository.ingestScreenshot(text) } catch (_: Exception) {}
                    }
                }
            ).also { it.start() }
        }
    }

    override fun onListenerDisconnected() {
        screenshotWatcher?.stop()
        screenshotWatcher = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val app = applicationContext as? App ?: return
        val container = app.container
        if (!container.settings.ambientEnabled.value) return

        val pkg = sbn.packageName
        if (pkg == packageName) return
        if (pkg !in container.settings.watchedApps.value) return

        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val big = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        val body = big.ifBlank { text }
        if (title.isBlank() && body.isBlank()) return

        val hadReply = cacheReplyAction(sbn.key, n)
        n.contentIntent?.let { ci ->
            openCache[sbn.key] = pkg to ci
            if (openCache.size > 120) openCache.keys.firstOrNull()?.let { openCache.remove(it) }
        }
        val label = appLabel(pkg)

        // Diagnostic: does this app's notification carry an inline-reply RemoteInput? (Read via:
        // adb logcat -s ReplyDiag). Tells us whether one-tap send is even possible for e.g. WeChat.
        run {
            val acts = n.actions?.joinToString("; ") { a -> "${a.title}(ri=${a.remoteInputs?.size ?: 0})" } ?: "none"
            Log.d("ReplyDiag", "pkg=$pkg label=$label title=\"$title\" hadReply=$hadReply actions=[$acts]")
        }

        // A chat message looks like: title = sender (not just the app name) + a non-blank body.
        // (WeChat carries NO inline-reply action, so we must NOT gate on hadReply — the auto-send
        // path drives the app's UI via Accessibility using the cached contentIntent instead.)
        val looksLikeMessage = title.isNotBlank() && body.isNotBlank() && title != label
        val autoReplying = container.settings.autoReplyEnabled.value && looksLikeMessage
        if (autoReplying) {
            // Per-contact auto-reply pipeline (handles the conversational reply).
            container.replyEngine.onIncoming(label, title, body, sbn.key)
        }
        // Auto-reply and the ambient task/auto-schedule pipeline are INDEPENDENT — run ingest too,
        // so a command-type message ("下午两点开会") still becomes a 日程/提醒 while auto-reply
        // handles the chat. Skip it only when we're purely auto-replying AND the user hasn't opted
        // into 自动加入日程 (avoids creating tasks for people who enabled auto-reply alone).
        if (!autoReplying || container.settings.autoScheduleEnabled.value) {
            scope.launch {
                try {
                    container.taskRepository.ingest(pkg, label, title, body, sbn.key)
                } catch (_: Exception) {
                }
            }
        }
    }

    // NOTE: intentionally do NOT drop the reply action when the notification is dismissed —
    // the messaging app's inline-reply PendingIntent usually stays valid, so the user can still
    // send a drafted reply from the 任务 page after the notification popup is gone.

    override fun onDestroy() {
        super.onDestroy()
        screenshotWatcher?.stop()
        screenshotWatcher = null
        scope.cancel()
    }

    private fun cacheReplyAction(key: String, n: Notification): Boolean {
        val actions = n.actions ?: return false
        for (a in actions) {
            val ris = a.remoteInputs
            if (ris != null && ris.isNotEmpty()) {
                replyCache[key] = a
                if (replyCache.size > 120) replyCache.keys.firstOrNull()?.let { replyCache.remove(it) }
                return true
            }
        }
        return false
    }

    private fun appLabel(pkg: String): String = try {
        val pm = packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }

    companion object {
        private val replyCache = ConcurrentHashMap<String, Notification.Action>()
        private val openCache = ConcurrentHashMap<String, Pair<String, PendingIntent>>() // key -> (pkg, contentIntent)

        /** Fire a captured inline-reply action with [text]. Returns true if dispatched. */
        fun trySendReply(context: Context, key: String, text: String): Boolean {
            val action = replyCache[key] ?: return false
            val remoteInputs = action.remoteInputs ?: return false
            return try {
                val intent = Intent()
                val results = Bundle()
                for (ri in remoteInputs) results.putCharSequence(ri.resultKey, text)
                RemoteInput.addResultsToIntent(remoteInputs, intent, results)
                action.actionIntent.send(context, 0, intent)
                true
            } catch (e: Exception) {
                false
            }
        }

        /**
         * Fallback for apps without notification quick-reply (WeChat/QQ): queue the text for the
         * Accessibility service, then open the exact chat via the cached contentIntent so the
         * service can type + press send. Returns false if accessibility isn't enabled / no intent.
         */
        fun autoSendViaAccessibility(context: Context, key: String, text: String): Boolean {
            if (!AutoSend.isEnabled()) return false
            val (pkg, ci) = openCache[key] ?: return false
            return try {
                AutoSend.request(pkg, text)
                ci.send()
                true
            } catch (e: Exception) {
                AutoSend.pending.set(null)
                false
            }
        }

        /** True if either send path is available for [key]. */
        fun canSend(key: String): Boolean =
            replyCache[key] != null || (AutoSend.isEnabled() && openCache[key] != null)
    }
}
