package com.dschat.app.agent.tools

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.dschat.app.agent.Tool
import com.dschat.app.agent.boolOr
import com.dschat.app.agent.boolProp
import com.dschat.app.agent.objectSchema
import com.dschat.app.agent.str
import com.dschat.app.agent.strProp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

private fun Context.granted(p: String): Boolean =
    ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

/** 拨号 / 打电话。默认打开拨号盘（无需权限）；direct=true 且已授予 CALL_PHONE 时直接拨出。 */
class MakeCallTool(private val context: Context) : Tool {
    override val name = "make_call"
    override val description =
        "拨打电话。默认打开系统拨号盘并填好号码（用户再按拨号键）；direct=true 时若已授予打电话权限会直接拨出。"
    override val sideEffect = true
    override fun parameters() = objectSchema(
        "number" to strProp("电话号码（可含 +、区号；空格/横线会被自动去掉）"),
        "direct" to boolProp("是否直接拨出（默认 false=只打开拨号盘填好号码）"),
        required = listOf("number")
    )

    override suspend fun execute(args: JsonObject): String {
        val number = args.str("number").filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
        if (number.isBlank()) return "错误：号码为空"
        val requestedDirect = args.boolOr("direct", false)
        val direct = requestedDirect && context.granted(Manifest.permission.CALL_PHONE)
        val action = if (direct) Intent.ACTION_CALL else Intent.ACTION_DIAL
        val intent = Intent(action, Uri.parse("tel:" + Uri.encode(number))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(context.packageManager) == null) return "你的设备没有可拨号的应用。"
        return try {
            withContext(Dispatchers.Main) { context.startActivity(intent) }
            if (direct) "已拨出：$number"
            else "已在拨号盘填入 $number（请按拨号键拨出）" +
                if (requestedDirect) "。注：未授予『打电话』权限，已改为打开拨号盘；如需直接拨出请到 设置→权限 开启。" else ""
        } catch (e: Exception) {
            "拨号失败：${e.message}"
        }
    }
}

/** 发短信。默认打开短信 App 预填收件人+正文（无需权限）；direct=true 且已授予 SEND_SMS 时直接发送。 */
class SendSmsTool(private val context: Context) : Tool {
    override val name = "send_sms"
    override val description =
        "发送短信。默认打开短信应用并预填收件人和正文（用户再按发送）；direct=true 时若已授予发送短信权限会直接发出（不可撤回，请谨慎）。"
    override val sideEffect = true
    override fun parameters() = objectSchema(
        "number" to strProp("收件人手机号"),
        "text" to strProp("短信正文"),
        "direct" to boolProp("是否直接发送（默认 false=只打开短信应用预填）"),
        required = listOf("number", "text")
    )

    override suspend fun execute(args: JsonObject): String {
        val number = args.str("number").filter { it.isDigit() || it == '+' }
        val text = args.str("text")
        if (number.isBlank()) return "错误：号码为空"
        if (text.isBlank()) return "错误：短信正文为空"
        val requestedDirect = args.boolOr("direct", false)
        val direct = requestedDirect && context.granted(Manifest.permission.SEND_SMS)
        if (direct) {
            return try {
                val sms = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                    context.getSystemService(SmsManager::class.java)
                else @Suppress("DEPRECATION") SmsManager.getDefault())
                    ?: return "无法获取短信服务，请改用 direct=false 打开短信应用发送。"
                val parts = sms.divideMessage(text)
                if (parts.size > 1) sms.sendMultipartTextMessage(number, null, parts, null, null)
                else sms.sendTextMessage(number, null, text, null, null)
                "已请求发送短信给 $number（fire-and-forget：若无 SIM / 信号 / 被运营商拦截可能未真正送达）"
            } catch (e: Exception) {
                "直接发送失败：${e.message}。可改用 direct=false 打开短信应用发送。"
            }
        }
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number)))
            .putExtra("sms_body", text)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(context.packageManager) == null) return "你的设备没有可发短信的应用。"
        return try {
            withContext(Dispatchers.Main) { context.startActivity(intent) }
            "已打开短信应用，收件人 $number、正文已填好（请按发送）" +
                if (requestedDirect) "。注：未授予『发送短信』权限，已改为打开短信应用；如需直接发送请到 设置→权限 开启。" else ""
        } catch (e: Exception) {
            "打开短信应用失败：${e.message}"
        }
    }
}
