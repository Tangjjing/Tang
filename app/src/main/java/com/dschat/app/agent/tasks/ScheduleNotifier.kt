package com.dschat.app.agent.tasks

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.dschat.app.App
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Posts an "已加入日程" notification with a one-tap 撤销 (undo) — used by the auto-schedule pipeline,
 *  so events get created with no upfront confirm but remain reversible. */
object ScheduleNotifier {
    const val CHANNEL_ID = "schedule"
    const val ACTION_UNDO = "com.dschat.app.SCHEDULE_UNDO"
    const val EXTRA_TASK_ID = "task_id"
    const val EXTRA_EVENT_ID = "event_id"
    const val EXTRA_NOTIF_ID = "notif_id"

    fun postScheduled(context: Context, taskId: Long, eventId: Long?, title: String, whenText: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val notifId = taskId.toInt() xor 0x8000 // avoid colliding with ReminderScheduler's taskId.toInt()
        val undo = Intent(context, ScheduleReceiver::class.java).apply {
            action = ACTION_UNDO
            putExtra(EXTRA_TASK_ID, taskId)
            putExtra(EXTRA_EVENT_ID, eventId ?: -1L)
            putExtra(EXTRA_NOTIF_ID, notifId)
        }
        val undoPi = PendingIntent.getBroadcast(
            context, notifId, undo,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val body = "「$title」 $whenText"
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_my_calendar)
            .setContentTitle(if (eventId != null) "已加入日程" else "已设提醒")
            .setContentText("$body · 点此撤销")
            .setStyle(NotificationCompat.BigTextStyle().bigText("$body\n（自动识别消息创建，若有误点「撤销」即可删除）"))
            .setAutoCancel(true)
            .addAction(0, "撤销", undoPi)
            .build()
        nm.notify(notifId, n)
    }
}

class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ScheduleNotifier.ACTION_UNDO) return
        val taskId = intent.getLongExtra(ScheduleNotifier.EXTRA_TASK_ID, -1L)
        val eventId = intent.getLongExtra(ScheduleNotifier.EXTRA_EVENT_ID, -1L)
        val notifId = intent.getIntExtra(ScheduleNotifier.EXTRA_NOTIF_ID, 0)
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)?.cancel(notifId)
        val repo = (context.applicationContext as? App)?.container?.taskRepository ?: return
        if (taskId < 0) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                repo.undoSchedule(taskId, eventId)
            } finally {
                pending.finish()
            }
        }
    }
}
