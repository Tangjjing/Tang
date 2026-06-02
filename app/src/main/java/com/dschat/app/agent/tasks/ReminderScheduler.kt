package com.dschat.app.agent.tasks

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.dschat.app.App
import com.dschat.app.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Schedules exact-ish alarms that fire a reminder notification with 完成/推迟 actions. */
object ReminderScheduler {
    const val CHANNEL_ID = "task_reminders"
    const val ACTION_FIRE = "com.dschat.app.REMINDER_FIRE"
    const val ACTION_COMPLETE = "com.dschat.app.TASK_COMPLETE"
    const val ACTION_SNOOZE = "com.dschat.app.TASK_SNOOZE"
    const val EXTRA_TASK_ID = "task_id"
    const val EXTRA_TITLE = "task_title"

    private fun broadcast(context: Context, action: String, id: Long, title: String?, req: Int): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            this.action = action
            putExtra(EXTRA_TASK_ID, id)
            if (title != null) putExtra(EXTRA_TITLE, title)
        }
        return PendingIntent.getBroadcast(
            context, req, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun schedule(context: Context, id: Long, title: String, dueAt: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val p = broadcast(context, ACTION_FIRE, id, title, id.toInt())
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                am.set(AlarmManager.RTC_WAKEUP, dueAt, p)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, dueAt, p)
            }
        } catch (e: SecurityException) {
            am.set(AlarmManager.RTC_WAKEUP, dueAt, p)
        }
    }

    fun cancel(context: Context, id: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        am.cancel(broadcast(context, ACTION_FIRE, id, null, id.toInt()))
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)?.cancel(id.toInt())
    }

    fun completePi(context: Context, id: Long): PendingIntent =
        broadcast(context, ACTION_COMPLETE, id, null, id.toInt() xor 0x5000)

    fun snoozePi(context: Context, id: Long): PendingIntent =
        broadcast(context, ACTION_SNOOZE, id, null, id.toInt() xor 0x6000)
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(ReminderScheduler.EXTRA_TASK_ID, -1L)
        if (id < 0) return
        val app = context.applicationContext as? App ?: return
        val repo = app.container.taskRepository
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        when (intent.action) {
            ReminderScheduler.ACTION_FIRE -> {
                val title = intent.getStringExtra(ReminderScheduler.EXTRA_TITLE) ?: "待办提醒"
                postReminder(context, nm, id, title)
            }
            ReminderScheduler.ACTION_COMPLETE -> {
                nm?.cancel(id.toInt())
                runAsync { repo.complete(id) }
            }
            ReminderScheduler.ACTION_SNOOZE -> {
                nm?.cancel(id.toInt())
                runAsync { repo.snooze(id, 60) }
            }
        }
    }

    private fun postReminder(context: Context, nm: NotificationManager?, id: Long, title: String) {
        if (nm == null) return
        val open = PendingIntent.getActivity(
            context, id.toInt(),
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(context, ReminderScheduler.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("待办提醒")
            .setContentText(title)
            .setAutoCancel(true)
            .setContentIntent(open)
            .addAction(0, "完成", ReminderScheduler.completePi(context, id))
            .addAction(0, "推迟1小时", ReminderScheduler.snoozePi(context, id))
            .build()
        nm.notify(id.toInt(), n)
    }

    private fun runAsync(block: suspend () -> Unit) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                block()
            } finally {
                pending.finish()
            }
        }
    }
}
