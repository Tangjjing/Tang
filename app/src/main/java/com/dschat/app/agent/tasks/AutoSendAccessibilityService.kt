package com.dschat.app.agent.tasks

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import java.util.concurrent.atomic.AtomicReference

/**
 * Types and sends a queued reply INSIDE a messaging app (WeChat/QQ…) via the Accessibility APIs.
 *
 * Why: WeChat (mainland) attaches NO inline-reply RemoteInput to its notifications, and MIUI/HyperOS
 * blocks input-injection — so the "fire the notification's reply intent" trick can't send to WeChat.
 * Driving the app's own UI through Accessibility is the only reliable path.
 *
 * Flow: a caller does [AutoSend.request] (pkg + text) then opens the exact chat (fires the cached
 * notification contentIntent). When this service sees that app's window, it finds the message
 * EditText, sets the text, then finds the 发送/Send button and clicks it.
 */
class AutoSendAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private val attempt = Runnable { tryFillAndSend() }

    @Volatile private var busy = false

    override fun onServiceConnected() {
        instance = this
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val job = AutoSend.pending.get() ?: return
        // Give up on a stale job (chat never opened, wrong screen, etc.) so we don't fire later.
        if (System.currentTimeMillis() - job.createdAt > JOB_TTL_MS) {
            AutoSend.pending.set(null); busy = false; return
        }
        if (busy) return
        val pkg = event?.packageName?.toString() ?: return
        if (pkg != job.pkg) return
        handler.removeCallbacks(attempt)
        handler.postDelayed(attempt, 400)
    }

    private fun tryFillAndSend() {
        val job = AutoSend.pending.get() ?: return
        if (busy) return
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != job.pkg) return
        val edit = findEditable(root) ?: return // not on the chat input screen yet — wait for next event
        // E3: confirm the OPEN chat belongs to the intended contact before typing/sending (never risk a wrong
        // recipient). The title may not be rendered the instant the chat opens, so allow a short grace window
        // of retries; past it, treat it as the wrong chat and fall back to a manual draft (copy + toast).
        if (job.contactName.isNotBlank() && !titleMatches(root, job.contactName)) {
            if (System.currentTimeMillis() - job.createdAt < VERIFY_GRACE_MS) {
                // Keep polling on a timer within the grace window — the title may just not be rendered yet, and
                // no further accessibility event is guaranteed to fire on its own.
                handler.removeCallbacks(attempt)
                handler.postDelayed(attempt, 250)
                return
            }
            AutoSend.pending.set(null)
            onVerifyFailed(job)
            return
        }
        busy = true
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, job.text)
        }
        edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        // The 发送 button only appears once the input is non-empty — give the UI a moment, then click.
        handler.postDelayed({
            val r2 = rootInActiveWindow
            val send = if (r2 != null) findClickableByText(r2, SEND_LABELS) else null
            if (send != null) clickNode(send)
            AutoSend.pending.set(null)
            busy = false
        }, 450)
    }

    private fun findEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable && node.isVisibleToUser) return node
        for (i in 0 until node.childCount) {
            findEditable(node.getChild(i))?.let { return it }
        }
        return null
    }

    private fun findClickableByText(node: AccessibilityNodeInfo?, labels: List<String>): AccessibilityNodeInfo? {
        if (node == null) return null
        val txt = node.text?.toString()?.trim()
        if (!txt.isNullOrEmpty() && labels.any { txt == it || txt.startsWith(it) }) return node
        for (i in 0 until node.childCount) {
            findClickableByText(node.getChild(i), labels)?.let { return it }
        }
        return null
    }

    private fun clickNode(node: AccessibilityNodeInfo) {
        var n: AccessibilityNodeInfo? = node
        while (n != null) {
            if (n.isClickable) { n.performAction(AccessibilityNodeInfo.ACTION_CLICK); return }
            n = n.parent
        }
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    /** True if the active window shows a chat whose title matches [target] (i.e. the right recipient). Only the
     *  top toolbar band is searched, so the contact's name appearing inside a message bubble of a DIFFERENT
     *  (wrong) chat can't produce a false match. */
    private fun titleMatches(root: AccessibilityNodeInfo, target: String): Boolean {
        val norm = normalizeName(target)
        if (norm.isEmpty()) return true
        val screen = Rect()
        root.getBoundsInScreen(screen)
        val titleBandBottom = screen.top + (screen.height() * 0.18f).toInt()
        return nodeWithName(root, norm, titleBandBottom)
    }

    private fun nodeWithName(node: AccessibilityNodeInfo?, normTarget: String, titleBandBottom: Int): Boolean {
        if (node == null) return false
        node.text?.toString()?.let {
            if (normalizeName(it) == normTarget) {
                val r = Rect()
                node.getBoundsInScreen(r)
                if (r.top <= titleBandBottom) return true // in the toolbar band → it's the chat title
            }
        }
        for (i in 0 until node.childCount) {
            if (nodeWithName(node.getChild(i), normTarget, titleBandBottom)) return true
        }
        return false
    }

    /** Strip a trailing unread-count suffix like "(2)" / "（2）" so "张三(2)" matches "张三". */
    private fun normalizeName(s: String): String =
        s.trim().replace(Regex("[(（][^)）]*[)）]\\s*$"), "").trim()

    /** Wrong chat (or title unreadable past the grace window): don't send — copy the reply and tell the user. */
    private fun onVerifyFailed(job: AutoSendJob) {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("draft", job.text))
        } catch (_: Exception) {}
        Toast.makeText(this, "未自动发送：当前聊天不是「${job.contactName}」，回复已复制，请手动确认后发送", Toast.LENGTH_LONG).show()
    }

    companion object {
        @Volatile
        var instance: AutoSendAccessibilityService? = null

        private const val JOB_TTL_MS = 20_000L
        private const val VERIFY_GRACE_MS = 1_500L
        // WeChat shows "发送" (sometimes "发送(1)"); QQ/others "发送"/"Send".
        private val SEND_LABELS = listOf("发送", "發送", "Send")
    }
}

/** A pending auto-send job: type [text] into [pkg]'s chat input and press send, only if the open chat
 *  matches [contactName] (blank = skip the recipient check). */
data class AutoSendJob(val pkg: String, val text: String, val contactName: String, val createdAt: Long)

/** Hand-off between the notification/reply layer and the Accessibility service. */
object AutoSend {
    val pending = AtomicReference<AutoSendJob?>(null)

    /** True only when the user has actually enabled the "Tang 自动发送" accessibility service. */
    fun isEnabled(): Boolean = AutoSendAccessibilityService.instance != null

    /** Queue a reply to type+send in [pkg]; the caller must then open the chat (fire contentIntent).
     *  [contactName] gates the send to the matching chat (blank = no check). */
    fun request(pkg: String, text: String, contactName: String = "") {
        pending.set(AutoSendJob(pkg, text, contactName, System.currentTimeMillis()))
    }
}
