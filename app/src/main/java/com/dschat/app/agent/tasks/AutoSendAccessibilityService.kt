package com.dschat.app.agent.tasks

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
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

    companion object {
        @Volatile
        var instance: AutoSendAccessibilityService? = null

        private const val JOB_TTL_MS = 20_000L
        // WeChat shows "发送" (sometimes "发送(1)"); QQ/others "发送"/"Send".
        private val SEND_LABELS = listOf("发送", "發送", "Send")
    }
}

/** A pending auto-send job: type [text] into [pkg]'s chat input and press send. */
data class AutoSendJob(val pkg: String, val text: String, val createdAt: Long)

/** Hand-off between the notification/reply layer and the Accessibility service. */
object AutoSend {
    val pending = AtomicReference<AutoSendJob?>(null)

    /** True only when the user has actually enabled the "Tang 自动发送" accessibility service. */
    fun isEnabled(): Boolean = AutoSendAccessibilityService.instance != null

    /** Queue a reply to type+send in [pkg]; the caller must then open the chat (fire contentIntent). */
    fun request(pkg: String, text: String) {
        pending.set(AutoSendJob(pkg, text, System.currentTimeMillis()))
    }
}
