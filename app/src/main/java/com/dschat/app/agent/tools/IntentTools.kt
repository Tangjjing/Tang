package com.dschat.app.agent.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings
import com.dschat.app.agent.Tool
import com.dschat.app.agent.intOr
import com.dschat.app.agent.intProp
import com.dschat.app.agent.objectSchema
import com.dschat.app.agent.str
import com.dschat.app.agent.strOrNull
import com.dschat.app.agent.strProp
import kotlinx.serialization.json.JsonObject

/** Start an activity from a non-Activity (application) context, guarding for "no app handles this". */
private fun Context.launch(intent: Intent, okMsg: String, noHandlerMsg: String): String {
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (intent.resolveActivity(packageManager) == null) return noHandlerMsg
    return try {
        startActivity(intent); okMsg
    } catch (e: Exception) {
        "操作失败：${e.message}"
    }
}

// ---- 发邮件 ----

class SendEmailTool(private val context: Context) : Tool {
    override val name = "send_email"
    override val description =
        "调起邮件 App 写一封邮件（收件人/主题/正文都已填好），用户点发送即可。用于『发邮件给…』。"
    override val sideEffect = true
    override fun parameters() = objectSchema(
        "to" to strProp("收件人邮箱；多个用逗号分隔（可空，留空则进草稿由用户填）"),
        "subject" to strProp("邮件主题（可选）"),
        "body" to strProp("邮件正文（可选）"),
        "cc" to strProp("抄送邮箱，多个用逗号分隔（可选）"),
        required = emptyList()
    )

    override suspend fun execute(args: JsonObject): String {
        fun split(s: String?) = s?.split(',', ';', ' ')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toTypedArray()
            ?: emptyArray()
        val to = split(args.strOrNull("to"))
        val cc = split(args.strOrNull("cc"))
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).apply {
            if (to.isNotEmpty()) putExtra(Intent.EXTRA_EMAIL, to)
            if (cc.isNotEmpty()) putExtra(Intent.EXTRA_CC, cc)
            args.strOrNull("subject")?.takeIf { it.isNotBlank() }?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
            args.strOrNull("body")?.takeIf { it.isNotBlank() }?.let { putExtra(Intent.EXTRA_TEXT, it) }
        }
        val who = if (to.isNotEmpty()) "给 ${to.joinToString("、")} " else ""
        return context.launch(
            intent,
            "已打开邮件草稿$who（收件人/主题/正文已填好），请确认后发送。",
            "没有可用的邮件 App。请先装一个邮箱客户端，或用 share_text 把内容分享出去。"
        )
    }
}

// ---- 导航 / 地图查地点 ----

class NavigateTool(private val context: Context) : Tool {
    override val name = "navigate"
    override val description =
        "用手机的地图 App 打开一个地点/地址（高德/百度/谷歌地图等，由系统默认地图处理），用户可一键导航。" +
        "用于『导航去…』『带我去…』『附近的…』『查一下…在哪』。"
    override val sideEffect = true
    override fun parameters() = objectSchema(
        "destination" to strProp("目的地名称或地址，如『北京西站』『朝阳区望京 SOHO』『附近的咖啡店』"),
        required = listOf("destination")
    )

    override suspend fun execute(args: JsonObject): String {
        val dest = args.str("destination").trim()
        if (dest.isBlank()) return "错误：destination 不能为空"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(dest)))
        return context.launch(
            intent,
            "已在地图里打开「$dest」，可点导航前往。",
            "没有可用的地图 App。可装一个高德/百度地图后重试。"
        )
    }
}

// ---- 新建联系人 ----

class CreateContactTool(private val context: Context) : Tool {
    override val name = "create_contact"
    override val description =
        "打开系统通讯录的『新建联系人』界面，姓名/电话/邮箱已预填好，用户保存即可（无需通讯录写入权限）。用于『把这个号码存成…』『加个联系人』。"
    override val sideEffect = true
    override fun parameters() = objectSchema(
        "name" to strProp("联系人姓名"),
        "phone" to strProp("电话号码（可选）"),
        "email" to strProp("邮箱（可选）"),
        "company" to strProp("公司/组织（可选）"),
        required = listOf("name")
    )

    override suspend fun execute(args: JsonObject): String {
        val name = args.str("name").trim()
        if (name.isBlank()) return "错误：name 不能为空"
        val intent = Intent(Intent.ACTION_INSERT).apply {
            type = ContactsContract.Contacts.CONTENT_TYPE
            putExtra(ContactsContract.Intents.Insert.NAME, name)
            args.strOrNull("phone")?.takeIf { it.isNotBlank() }
                ?.let { putExtra(ContactsContract.Intents.Insert.PHONE, it) }
            args.strOrNull("email")?.takeIf { it.isNotBlank() }
                ?.let { putExtra(ContactsContract.Intents.Insert.EMAIL, it) }
            args.strOrNull("company")?.takeIf { it.isNotBlank() }
                ?.let { putExtra(ContactsContract.Intents.Insert.COMPANY, it) }
        }
        return context.launch(
            intent,
            "已打开新建联系人界面：「$name」，请确认后保存。",
            "没有可用的通讯录 App。"
        )
    }
}

// ---- 打开系统设置页 ----

class OpenSettingsTool(private val context: Context) : Tool {
    override val name = "open_settings"
    override val description =
        "打开手机的某个系统设置页面。注意：Android 不允许 App 直接开关 WiFi/蓝牙/飞行模式，只能帮你跳到对应设置页由你手动开关。" +
        "section 可填：wifi、蓝牙、流量、定位、显示、声音、电池、应用、通知、存储、日期、安全、飞行模式、nfc；留空打开设置主页。"
    override val sideEffect = true
    override fun parameters() = objectSchema(
        "section" to strProp("要打开的设置板块关键词，如 wifi / 蓝牙 / 定位 / 应用 / 通知。留空打开设置主页。"),
        required = emptyList()
    )

    override suspend fun execute(args: JsonObject): String {
        val s = args.str("section").lowercase().trim()
        val appUri = Uri.fromParts("package", context.packageName, null)
        fun has(vararg keys: String) = keys.any { s.contains(it) }
        val (action, label) = when {
            s.isBlank() -> Settings.ACTION_SETTINGS to "设置主页"
            has("wifi", "wlan", "无线") -> Settings.ACTION_WIFI_SETTINGS to "WiFi 设置"
            has("蓝牙", "bluetooth") -> Settings.ACTION_BLUETOOTH_SETTINGS to "蓝牙设置"
            has("飞行", "airplane") -> Settings.ACTION_AIRPLANE_MODE_SETTINGS to "飞行模式设置"
            has("流量", "数据", "data", "网络") -> Settings.ACTION_DATA_USAGE_SETTINGS to "流量/数据设置"
            has("定位", "位置", "location", "gps") -> Settings.ACTION_LOCATION_SOURCE_SETTINGS to "定位设置"
            has("亮度", "显示", "display", "屏幕") -> Settings.ACTION_DISPLAY_SETTINGS to "显示设置"
            has("声音", "音量", "sound", "volume", "铃声") -> Settings.ACTION_SOUND_SETTINGS to "声音设置"
            has("电池", "电量", "省电", "battery") -> Settings.ACTION_BATTERY_SAVER_SETTINGS to "电池设置"
            has("通知", "notification") -> Settings.ACTION_APP_NOTIFICATION_SETTINGS to "本应用通知设置"
            has("应用", "权限", "app") -> Settings.ACTION_APPLICATION_DETAILS_SETTINGS to "本应用详情/权限"
            has("存储", "storage") -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS to "存储设置"
            has("日期", "时间", "date", "time") -> Settings.ACTION_DATE_SETTINGS to "日期与时间设置"
            has("安全", "security") -> Settings.ACTION_SECURITY_SETTINGS to "安全设置"
            has("nfc") -> Settings.ACTION_NFC_SETTINGS to "NFC 设置"
            else -> Settings.ACTION_SETTINGS to "设置主页"
        }
        val intent = Intent(action)
        // App-scoped settings pages need the target package attached.
        when (action) {
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS -> intent.data = appUri
            Settings.ACTION_APP_NOTIFICATION_SETTINGS -> intent.putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        }
        // Some ROMs don't expose a given sub-page → fall back to the main settings screen.
        if (intent.resolveActivity(context.packageManager) == null && action != Settings.ACTION_SETTINGS) {
            return context.launch(
                Intent(Settings.ACTION_SETTINGS),
                "你的系统没有单独的「$label」页，已打开设置主页，请手动进入。",
                "无法打开系统设置。"
            )
        }
        return context.launch(intent, "已打开「$label」。", "无法打开「$label」。")
    }
}

// ---- 计时器 ----

class SetTimerTool(private val context: Context) : Tool {
    override val name = "set_timer"
    override val description =
        "设置一个倒计时计时器（调起系统时钟）。用于『定个 X 分钟』『煮面 10 分钟提醒我』这类短时倒计时；" +
        "若是『每天某点』请用 set_alarm，若是『某分钟后弹通知提醒』也可用 set_reminder。"
    override val sideEffect = true
    override fun parameters() = objectSchema(
        "seconds" to intProp("倒计时总秒数（如 10 分钟填 600）", 1, 86400),
        "message" to strProp("计时器标签（可选）"),
        required = listOf("seconds")
    )

    override suspend fun execute(args: JsonObject): String {
        val seconds = args.intOr("seconds", -1)
        if (seconds !in 1..86400) return "错误：seconds 需为 1~86400 之间的正整数（最长 24 小时）。"
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_MESSAGE, args.strOrNull("message")?.takeIf { it.isNotBlank() } ?: "Tang 计时")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        val m = seconds / 60
        val sec = seconds % 60
        val human = buildString { if (m > 0) append("$m 分"); if (sec > 0) append("$sec 秒") }
        return context.launch(
            intent,
            "已设置 $human 倒计时。",
            "你的系统没有可处理计时器的时钟应用。可改用 set_reminder（由本应用到点弹通知提醒）。"
        )
    }
}
