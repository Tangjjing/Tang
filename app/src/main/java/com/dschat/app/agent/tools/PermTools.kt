package com.dschat.app.agent.tools

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Criteria
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.dschat.app.agent.Tool
import com.dschat.app.agent.boolOr
import com.dschat.app.agent.boolProp
import com.dschat.app.agent.intOr
import com.dschat.app.agent.intProp
import com.dschat.app.agent.longOr
import com.dschat.app.agent.objectSchema
import com.dschat.app.agent.tasks.CalendarWriter
import com.dschat.app.agent.str
import com.dschat.app.agent.strOrNull
import com.dschat.app.agent.ToolLimits
import com.dschat.app.agent.capNote
import com.dschat.app.agent.strProp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlin.coroutines.resume
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun Context.hasPerm(p: String): Boolean =
    ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

class LocationTool(private val context: Context) : Tool {
    override val name = "get_location"
    override val description = "获取设备当前的大致位置（经纬度）。可用于『附近…』『我在哪』类问题。"
    override val sideEffect = false
    override fun parameters() = objectSchema(required = emptyList())

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        if (!context.hasPerm(Manifest.permission.ACCESS_FINE_LOCATION) &&
            !context.hasPerm(Manifest.permission.ACCESS_COARSE_LOCATION)
        ) {
            return@withContext "错误：未授予定位权限。请在 设置→权限 里开启定位。"
        }
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return@withContext "错误：无法访问定位服务"
        try {
            // Last-known is often null on Android; fall back to a single live fix (≤10s).
            val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
                ?: requestSingle(lm, 10_000L)
            if (loc == null) "暂时拿不到位置（系统定位可能已关闭，或当前信号弱；可到户外或打开定位后重试）。"
            else "纬度 ${loc.latitude}，经度 ${loc.longitude}（精度约 ${loc.accuracy.toInt()} 米）"
        } catch (e: SecurityException) {
            "错误：定位权限被拒绝"
        }
    }

    /** One-shot live location fix using the lowest-power provider, bounded by [timeoutMs]. */
    private suspend fun requestSingle(lm: LocationManager, timeoutMs: Long): Location? =
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val provider = runCatching {
                    lm.getBestProvider(Criteria().apply {
                        accuracy = Criteria.ACCURACY_COARSE
                        powerRequirement = Criteria.POWER_LOW
                    }, true)
                }.getOrNull() ?: LocationManager.NETWORK_PROVIDER
                val listener = object : LocationListener {
                    override fun onLocationChanged(l: Location) { if (cont.isActive) cont.resume(l) }
                    override fun onProviderDisabled(p: String) {}
                    override fun onProviderEnabled(p: String) {}
                    @Deprecated("required by older API levels")
                    override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
                }
                try {
                    @Suppress("DEPRECATION")
                    lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                } catch (e: Exception) {
                    if (cont.isActive) cont.resume(null)
                }
                cont.invokeOnCancellation { runCatching { lm.removeUpdates(listener) } }
            }
        }
}

class CalendarReadTool(private val context: Context) : Tool {
    override val name = "read_calendar"
    override val description = "读取接下来若干天的日历事件。"
    override val sideEffect = false
    override fun parameters() = objectSchema(
        "days" to intProp("查询未来多少天，默认 7", 1, 60),
        "offset" to intProp("从第几条开始返回（翻页用，默认 0）", 0, null),
        required = emptyList()
    )

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        if (!context.hasPerm(Manifest.permission.READ_CALENDAR)) {
            return@withContext "错误：未授予日历权限。请在 设置→权限 里开启。"
        }
        val days = args.intOr("days", 7).coerceIn(1, 60)
        val offset = args.intOr("offset", 0).coerceAtLeast(0)
        val now = System.currentTimeMillis()
        val end = now + days * 86_400_000L
        val proj = arrayOf(
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.EVENT_LOCATION
        )
        val sel = "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ?"
        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        try {
            context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI, proj,
                sel, arrayOf(now.toString(), end.toString()),
                "${CalendarContract.Events.DTSTART} ASC"
            )?.use { c ->
                if (c.count == 0) return@withContext "未来 $days 天没有日历事件。"
                val sb = StringBuilder("未来 $days 天的事件（共 ${c.count} 条）：\n")
                var shown = 0
                if (offset > 0) c.moveToPosition(offset - 1)
                while (c.moveToNext() && shown < ToolLimits.CALENDAR_CAP) {
                    val title = c.getString(0) ?: "(无标题)"
                    val start = c.getLong(1)
                    val loc = c.getString(2).orEmpty()
                    sb.append("• ").append(fmt.format(Date(start))).append("  ").append(title)
                    if (loc.isNotBlank()) sb.append("  @").append(loc)
                    sb.append('\n')
                    shown++
                }
                val reached = offset + shown
                sb.append(capNote(reached, c.count, "，用 offset=$reached 继续翻页"))
                sb.toString().trim()
            } ?: "无法读取日历"
        } catch (e: Exception) {
            "读取日历失败：${e.message}"
        }
    }
}

class CalendarCreateTool(private val context: Context) : Tool {
    override val name = "create_calendar_event"
    override val description = "在系统日历里创建事件。已授予日历写入权限时【直接静默写入，不弹界面、无需确认】；否则回退到打开日历新建界面让用户保存。时间用 epoch 毫秒（可先用 current_datetime 推算）。"
    override val sideEffect = true
    override fun parameters() = objectSchema(
        "title" to strProp("事件标题"),
        "begin_time_ms" to strProp("开始时间（epoch 毫秒）"),
        "end_time_ms" to strProp("结束时间（epoch 毫秒，可选；默认开始后 1 小时）"),
        "all_day" to boolProp("是否全天事件，默认 false"),
        "detail" to strProp("事件备注（可选）"),
        "reminder_minutes_before" to intProp("提前几分钟提醒，默认 10"),
        required = listOf("title")
    )

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        val title = args.str("title")
        if (title.isBlank()) return@withContext "错误：title 不能为空"
        val begin = args.longOr("begin_time_ms", 0L)
        val end = args.longOr("end_time_ms", 0L)
        val allDay = args.boolOr("all_day", false)
        val detail = args.strOrNull("detail")
        val remind = args.intOr("reminder_minutes_before", 10).coerceIn(0, 1440)

        // Silent direct insert when we have write permission + a writable calendar.
        if (begin > 0 && CalendarWriter.canWrite(context)) {
            val calId = CalendarWriter.pickWritableCalendarId(context)
            if (calId != null) {
                if (CalendarWriter.findDuplicate(context, title, begin)) {
                    return@withContext "日历里已有同名同时间的事件「$title」，未重复创建。"
                }
                val eid = CalendarWriter.insertEvent(context, title, detail, begin, end, allDay, calId)
                if (eid != null) {
                    if (remind > 0) CalendarWriter.addReminderMinutes(context, eid, remind)
                    val fmt = SimpleDateFormat(if (allDay) "yyyy-MM-dd" else "yyyy-MM-dd HH:mm", Locale.getDefault())
                    return@withContext "已加入系统日历：「$title」 ${fmt.format(Date(begin))}"
                }
            }
        }
        // Fallback: open the Calendar UI (no write perm / no writable calendar / insert failed / no time).
        val intent = Intent(Intent.ACTION_INSERT)
            .setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, title)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!detail.isNullOrBlank()) intent.putExtra(CalendarContract.Events.DESCRIPTION, detail)
        if (begin > 0) intent.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
        if (end > 0) intent.putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
        if (allDay) intent.putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, true)
        try {
            withContext(Dispatchers.Main) { context.startActivity(intent) }
            "已打开日历新建界面：「$title」（未获日历写入权限，需手动保存；可到 设置→权限 开启日历后实现静默创建）"
        } catch (e: Exception) {
            "创建日历事件失败：${e.message}"
        }
    }
}

class ContactsTool(private val context: Context) : Tool {
    override val name = "search_contacts"
    override val description = "按姓名搜索通讯录，返回匹配的联系人姓名与电话。"
    override val sideEffect = false
    override fun parameters() = objectSchema(
        "query" to strProp("姓名关键词"),
        "offset" to intProp("从第几条开始返回（翻页用，默认 0）", 0, null),
        required = listOf("query")
    )

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        if (!context.hasPerm(Manifest.permission.READ_CONTACTS)) {
            return@withContext "错误：未授予通讯录权限。请在 设置→权限 里开启。"
        }
        val q = args.str("query")
        if (q.isBlank()) return@withContext "错误：query 不能为空"
        val offset = args.intOr("offset", 0).coerceAtLeast(0)
        val proj = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI, proj,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$q%"), null
            )?.use { c ->
                if (c.count == 0) return@withContext "没有找到包含「$q」的联系人。"
                val sb = StringBuilder("包含「$q」的联系人（共 ${c.count} 条）：\n")
                var shown = 0
                if (offset > 0) c.moveToPosition(offset - 1)
                while (c.moveToNext() && shown < ToolLimits.CONTACTS_CAP) {
                    sb.append(c.getString(0)).append("：").append(c.getString(1)).append('\n')
                    shown++
                }
                val reached = offset + shown
                sb.append(capNote(reached, c.count, "，用 offset=$reached 继续翻页"))
                sb.toString().trim()
            } ?: "无法读取通讯录"
        } catch (e: Exception) {
            "读取通讯录失败：${e.message}"
        }
    }
}

class SetAlarmTool(private val context: Context) : Tool {
    override val name = "set_alarm"
    override val description = "设置系统闹钟（指定时和分，调起系统时钟 App）。仅适合“每天某点”这类固定时刻；若是“X 分钟后/明天提醒”，请改用 set_reminder。"
    override val sideEffect = true
    override fun parameters() = objectSchema(
        "hour" to intProp("小时 0-23"),
        "minute" to intProp("分钟 0-59"),
        "message" to strProp("闹钟标签（可选）"),
        required = listOf("hour", "minute")
    )

    override suspend fun execute(args: JsonObject): String {
        val hour = args.intOr("hour", -1)
        val minute = args.intOr("minute", -1)
        if (hour !in 0..23 || minute !in 0..59) return "错误：hour 需 0-23，minute 需 0-59"
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_MESSAGE, args.strOrNull("message") ?: "Tang 提醒")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true) // create silently on AOSP/Pixel (MIUI often ignores)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Many Chinese ROMs have no AOSP clock app handling ACTION_SET_ALARM → would crash. Check first.
        if (intent.resolveActivity(context.packageManager) == null) {
            return "你的系统没有可处理闹钟的时钟应用。可改用 set_reminder（由本应用到点弹通知提醒），或手动到时钟里设置。"
        }
        return try {
            context.startActivity(intent)
            "已设置闹钟：%02d:%02d".format(hour, minute)
        } catch (e: Exception) {
            "设置闹钟失败：${e.message}"
        }
    }
}
