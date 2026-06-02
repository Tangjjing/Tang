package com.dschat.app.agent.tools

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import com.dschat.app.agent.Tool
import com.dschat.app.agent.intOr
import com.dschat.app.agent.intProp
import com.dschat.app.agent.longOr
import com.dschat.app.agent.objectSchema
import com.dschat.app.agent.str
import com.dschat.app.agent.strProp
import com.dschat.app.agent.tasks.ReminderScheduler
import kotlinx.serialization.json.JsonObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Relative/absolute reminder via AlarmManager + a notification — works on ROMs without a clock app
 *  and for times beyond 24h (unlike set_alarm). Reuses the ambient assistant's ReminderScheduler. */
class SetReminderTool(private val context: Context) : Tool {
    override val name = "set_reminder"
    override val description = "设置到点提醒（到时弹通知，不依赖系统时钟 App，可后台触发、可设几天后）。" +
        "用 in_minutes（如 30 表示 30 分钟后）或 at_time_ms（绝对时间 epoch 毫秒）二选一。“X 分钟/小时后”“明天某点”都用它，而不是 set_alarm。"
    override val sideEffect = true
    override fun parameters() = objectSchema(
        "message" to strProp("提醒内容"),
        "in_minutes" to intProp("多少分钟后提醒（相对时间）"),
        "at_time_ms" to strProp("绝对时间 epoch 毫秒（可用 current_datetime 推算）"),
        required = listOf("message")
    )

    override suspend fun execute(args: JsonObject): String {
        val msg = args.str("message").ifBlank { "提醒" }
        val now = System.currentTimeMillis()
        val mins = args.intOr("in_minutes", 0)
        val at = args.longOr("at_time_ms", 0L)
        val dueAt = when {
            at > now -> at
            mins > 0 -> now + mins * 60_000L
            else -> return "错误：请提供 in_minutes（X 分钟后）或一个未来的 at_time_ms。"
        }
        val id = now // standalone reminder id (not a task-DB row)
        ReminderScheduler.schedule(context, id, msg, dueAt)
        val fmt = SimpleDateFormat("M月d日 HH:mm", Locale.getDefault())
        return "已设置提醒：${fmt.format(Date(dueAt))} —— $msg"
    }
}

/** Post a notification immediately (e.g. to announce a long task's result). */
class SendNotificationTool(private val context: Context) : Tool {
    override val name = "send_notification"
    override val description = "立刻在通知栏发一条通知（用于长任务完成后告知结果，或主动提示用户）。"
    override val sideEffect = true
    override fun parameters() = objectSchema(
        "title" to strProp("通知标题（可选）"),
        "content" to strProp("通知内容"),
        required = listOf("content")
    )

    override suspend fun execute(args: JsonObject): String {
        val title = args.str("title").ifBlank { "Tang" }
        val content = args.str("content")
        if (content.isBlank()) return "错误：content 不能为空"
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return "错误：无法访问通知服务"
        val n = NotificationCompat.Builder(context, ReminderScheduler.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setAutoCancel(true)
            .build()
        nm.notify((System.currentTimeMillis() % 100000).toInt(), n)
        return "已发送通知：$title"
    }
}
