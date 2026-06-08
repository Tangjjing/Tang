package com.dschat.app.agent.tasks

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.dschat.app.App

/** Posts a one-tap "发送" draft reply notification (used when a contact isn't on the auto-reply list). */
object ReplyNotifier {
    const val CHANNEL_ID = "auto_reply"
    const val ACTION_SEND = "com.dschat.app.REPLY_SEND"
    const val ACTION_AUTO = "com.dschat.app.REPLY_AUTO"
    const val EXTRA_NOTIF_KEY = "notif_key"
    const val EXTRA_TEXT = "reply_text"
    const val EXTRA_CONTACT_KEY = "contact_key"
    const val EXTRA_NOTIF_ID = "notif_id"

    fun postDraft(context: Context, contactKey: String, contact: String, reply: String, notifKey: String?) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val notifId = contactKey.hashCode()

        fun pi(action: String, req: Int): PendingIntent {
            val i = Intent(context, ReplyReceiver::class.java).apply {
                this.action = action
                putExtra(EXTRA_NOTIF_KEY, notifKey)
                putExtra(EXTRA_TEXT, reply)
                putExtra(EXTRA_CONTACT_KEY, contactKey)
                putExtra(EXTRA_NOTIF_ID, notifId)
            }
            return PendingIntent.getBroadcast(
                context, req, i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle("给 $contact 的回复（草稿）")
            .setContentText(reply)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reply))
            .setAutoCancel(true)
        if (notifKey != null) {
            builder.addAction(0, "发送", pi(ACTION_SEND, notifId))
            builder.addAction(0, "以后自动回TA", pi(ACTION_AUTO, notifId xor 0x7000))
        }
        nm.notify(notifId, builder.build())
    }
}

class ReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val notifKey = intent.getStringExtra(ReplyNotifier.EXTRA_NOTIF_KEY)
        val text = intent.getStringExtra(ReplyNotifier.EXTRA_TEXT) ?: return
        val contactKey = intent.getStringExtra(ReplyNotifier.EXTRA_CONTACT_KEY)
        val notifId = intent.getIntExtra(ReplyNotifier.EXTRA_NOTIF_ID, 0)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        when (intent.action) {
            ReplyNotifier.ACTION_AUTO -> {
                val app = context.applicationContext as? App
                if (contactKey != null) app?.container?.settings?.setContactAuto(contactKey, true)
                if (notifKey != null) sendNow(context, notifKey, text, contactKey?.substringAfter('|') ?: "")
                nm?.cancel(notifId)
            }
            ReplyNotifier.ACTION_SEND -> {
                if (notifKey != null) sendNow(context, notifKey, text, contactKey?.substringAfter('|') ?: "")
                nm?.cancel(notifId)
            }
        }
    }

    /** RemoteInput first (SMS/Telegram); fall back to the Accessibility auto-typer (WeChat/QQ). */
    private fun sendNow(context: Context, notifKey: String, text: String, contact: String) {
        if (NotificationCaptureService.trySendReply(context, notifKey, text)) return
        if (NotificationCaptureService.autoSendViaAccessibility(context, notifKey, text, contact)) return
        // Both failed (typically WeChat with the accessibility service still OFF): copy + guide.
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("draft", text))
        Toast.makeText(context, "未能直接发送，已复制到剪贴板。请到『通知助理』开启「Tang 自动发送」无障碍后重试", Toast.LENGTH_LONG).show()
    }
}
