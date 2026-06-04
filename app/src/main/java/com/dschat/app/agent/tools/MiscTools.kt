package com.dschat.app.agent.tools

import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import com.dschat.app.agent.Tool
import com.dschat.app.agent.intOr
import com.dschat.app.agent.intProp
import com.dschat.app.agent.objectSchema
import com.dschat.app.agent.str
import com.dschat.app.agent.strOrNull
import com.dschat.app.agent.ToolLimits
import com.dschat.app.agent.enumProp
import com.dschat.app.agent.strProp
import com.dschat.app.data.settings.SettingsRepository
import com.dschat.app.domain.MEMORY_CATEGORIES
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val JS_MAX_CODE = 4000
private const val JS_TIMEOUT_MS = 3000L
private const val JS_MAX_RESULT = 8000

/** Rhino context factory that aborts a script once it overruns the wall-clock deadline — this is
 *  how `while(true){}` gets killed (coroutine cancellation can't interrupt a tight interpreter loop). */
private class SafeJsFactory : org.mozilla.javascript.ContextFactory() {
    @Volatile
    var deadline: Long = 0L

    override fun makeContext(): org.mozilla.javascript.Context {
        val cx = super.makeContext()
        cx.optimizationLevel = -1 // interpreted mode (required on Android) — enables instruction observing
        cx.instructionObserverThreshold = 20000
        cx.maximumInterpreterStackDepth = 1000 // guard runaway recursion
        return cx
    }

    override fun observeInstructionCount(cx: org.mozilla.javascript.Context, instructionCount: Int) {
        if (System.currentTimeMillis() > deadline) {
            throw org.mozilla.javascript.EvaluatorException("执行超时（疑似死循环，已中断）")
        }
    }
}

private val jsFactory = SafeJsFactory()

internal fun evalJs(code: String): String {
    if (code.length > JS_MAX_CODE) return "错误：代码过长（超过 $JS_MAX_CODE 字符），请精简。"
    jsFactory.deadline = System.currentTimeMillis() + JS_TIMEOUT_MS
    val ctx = jsFactory.enterContext()
    return try {
        val scope = ctx.initStandardObjects()
        val result = ctx.evaluateString(scope, code, "tool", 1, null)
        val s = org.mozilla.javascript.Context.toString(result)
        if (s.length > JS_MAX_RESULT) s.take(JS_MAX_RESULT) + "…（结果已截断）" else s
    } catch (e: StackOverflowError) {
        "JS 错误：递归过深"
    } catch (e: OutOfMemoryError) {
        "JS 错误：内存占用过大（已中断）"
    } catch (e: Exception) {
        "JS 错误：${e.message}"
    } finally {
        org.mozilla.javascript.Context.exit()
    }
}

class RunJavascriptTool : Tool {
    override val name = "run_javascript"
    override val description = "在沙盒里执行一段 JavaScript 并返回结果（最后一个表达式的值）。也用来做数学计算（如 (123*45.6)/7、Math.sqrt(2)）。无法访问网络或文件；有 3 秒超时与长度限制。"
    override val sideEffect = false
    override fun parameters() = objectSchema(
        "code" to strProp("要执行的 JavaScript 代码（也可写数学表达式）"),
        required = listOf("code")
    )

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.Default) {
        val code = args.str("code")
        if (code.isBlank()) "错误：code 不能为空" else evalJs(code)
    }
}

class DateTimeTool : Tool {
    override val name = "current_datetime"
    override val description = "返回当前的日期、时间、星期与时间戳。"
    override val sideEffect = false
    override fun parameters() = objectSchema(required = emptyList())

    override suspend fun execute(args: JsonObject): String {
        val now = Date()
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss EEEE", Locale.getDefault())
        return "${fmt.format(now)}\n时间戳(ms)：${now.time}"
    }
}

class DeviceInfoTool(private val context: Context) : Tool {
    override val name = "device_info"
    override val description = "返回设备信息：型号、安卓版本、电量、是否充电、网络类型、可用存储空间、运行内存(RAM)。"
    override val sideEffect = false
    override fun parameters() = objectSchema(required = emptyList())

    override suspend fun execute(args: JsonObject): String {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val battery = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val charging = bm?.isCharging ?: false
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        val net = when {
            caps == null -> "无网络"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "移动数据"
            else -> "其它"
        }
        val stat = StatFs(Environment.getExternalStorageDirectory().path)
        val freeGb = stat.availableBytes / (1024.0 * 1024 * 1024)
        val totalStoreGb = stat.totalBytes / (1024.0 * 1024 * 1024)
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        val ramTotalGb = mi.totalMem / (1024.0 * 1024 * 1024)
        val ramAvailGb = mi.availMem / (1024.0 * 1024 * 1024)
        return buildString {
            append("型号：${Build.MANUFACTURER} ${Build.MODEL}\n")
            append("系统：Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
            append("电量：${if (battery in 0..100) "$battery%" else "未知"}${if (charging) "（充电中）" else ""}\n")
            append("网络：$net\n")
            append("存储：可用 ${String.format(Locale.US, "%.1f", freeGb)} GB / 共 ${String.format(Locale.US, "%.1f", totalStoreGb)} GB\n")
            append("运行内存(RAM)：可用 ${String.format(Locale.US, "%.1f", ramAvailGb)} GB / 共 ${String.format(Locale.US, "%.1f", ramTotalGb)} GB${if (mi.lowMemory) "（内存紧张）" else ""}")
        }
    }
}

class GetClipboardTool(private val context: Context) : Tool {
    override val name = "get_clipboard"
    override val description = "读取系统剪贴板里的当前文本。"
    override val sideEffect = false
    override fun parameters() = objectSchema(required = emptyList())

    override suspend fun execute(args: JsonObject): String {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return "错误：无法访问剪贴板服务"
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
        return text?.takeIf { it.isNotBlank() } ?: "（剪贴板为空）"
    }
}

class SetClipboardTool(private val context: Context) : Tool {
    override val name = "set_clipboard"
    override val description = "把文本写入系统剪贴板。"
    override val sideEffect = true
    override fun parameters() = objectSchema(
        "text" to strProp("要复制的文本"),
        required = listOf("text")
    )

    override suspend fun execute(args: JsonObject): String {
        val text = args.str("text")
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return "错误：无法访问剪贴板"
        cm.setPrimaryClip(ClipData.newPlainText("DeepSeek", text))
        return "已复制到剪贴板（${text.length} 字符）"
    }
}

class ShareTextTool(private val context: Context) : Tool {
    override val name = "share_text"
    override val description = "弹出系统分享菜单，把一段文本分享到其它 App（微信/备忘录等）。"
    override val sideEffect = true
    override fun parameters() = objectSchema(
        "text" to strProp("要分享的文本"),
        required = listOf("text")
    )

    override suspend fun execute(args: JsonObject): String {
        val text = args.str("text")
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        val chooser = Intent.createChooser(send, "分享").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(chooser)
            "已弹出分享菜单"
        } catch (e: Exception) {
            "分享失败：${e.message}"
        }
    }
}

class OpenUrlTool(private val context: Context) : Tool {
    override val name = "open_url"
    override val description = "用系统默认浏览器打开一个网址。"
    override val sideEffect = true
    override fun parameters() = objectSchema(
        "url" to strProp("要打开的网址"),
        required = listOf("url")
    )

    override suspend fun execute(args: JsonObject): String {
        var url = args.str("url")
        if (url.isBlank()) return "错误：url 不能为空"
        if (!url.startsWith("http")) url = "https://$url"
        return try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "已打开：$url"
        } catch (e: Exception) {
            "打开失败：${e.message}"
        }
    }
}

/**
 * Common app name -> package map. On MIUI/HyperOS, getInstalledApplications() is blocked
 * (returns empty) even with QUERY_ALL_PACKAGES, so enumerating by label fails. Resolving a
 * KNOWN package via getLaunchIntentForPackage() still works, so this map lets us open the
 * popular apps reliably without enumeration. Keys are lowercased.
 */
internal val COMMON_APPS: Map<String, String> = mapOf(
    "微信" to "com.tencent.mm", "wechat" to "com.tencent.mm",
    "qq" to "com.tencent.mobileqq",
    "支付宝" to "com.eg.android.AlipayGphone", "alipay" to "com.eg.android.AlipayGphone",
    "淘宝" to "com.taobao.taobao", "taobao" to "com.taobao.taobao",
    "京东" to "com.jingdong.app.mall",
    "抖音" to "com.ss.android.ugc.aweme", "douyin" to "com.ss.android.ugc.aweme",
    "tiktok" to "com.zhiliaoapp.musically",
    "快手" to "com.smile.gifmaker",
    "微博" to "com.sina.weibo", "weibo" to "com.sina.weibo",
    "哔哩哔哩" to "tv.danmaku.bili", "bilibili" to "tv.danmaku.bili", "b站" to "tv.danmaku.bili",
    "高德地图" to "com.autonavi.minimap", "高德" to "com.autonavi.minimap",
    "百度地图" to "com.baidu.BaiduMap",
    "美团" to "com.sankuai.meituan",
    "饿了么" to "me.ele",
    "拼多多" to "com.xunmeng.pinduoduo",
    "网易云音乐" to "com.netease.cloudmusic", "网易云" to "com.netease.cloudmusic",
    "qq音乐" to "com.tencent.qqmusic",
    "酷狗音乐" to "com.kugou.android", "酷狗" to "com.kugou.android",
    "钉钉" to "com.alibaba.android.rimet", "dingtalk" to "com.alibaba.android.rimet",
    "知乎" to "com.zhihu.android",
    "小红书" to "com.xingin.xhs",
    "微信读书" to "com.tencent.weread",
    "腾讯会议" to "com.tencent.wemeet.app",
    "企业微信" to "com.tencent.wework",
    "百度" to "com.baidu.searchbox",
    "腾讯视频" to "com.tencent.qqlive",
    "爱奇艺" to "com.qiyi.video",
    "优酷" to "com.youku.phone",
    "qq邮箱" to "com.tencent.androidqqmail",
    "qq浏览器" to "com.tencent.mtt",
    "uc浏览器" to "com.UCMobile",
    "王者荣耀" to "com.tencent.tmgp.sgame",
    "和平精英" to "com.tencent.tmgp.pubgmhd",
    "chrome" to "com.android.chrome",
    "youtube" to "com.google.android.youtube",
    "gmail" to "com.google.android.gm",
    "微信支付" to "com.tencent.mm",
    "telegram" to "org.telegram.messenger",
    "x" to "com.twitter.android", "twitter" to "com.twitter.android",
    "instagram" to "com.instagram.android",
    "whatsapp" to "com.whatsapp",
    "spotify" to "com.spotify.music",
    "chatgpt" to "com.openai.chatgpt",
    "claude" to "com.anthropic.claude",
    "豆包" to "com.larus.nova", "doubao" to "com.larus.nova",
    "kimi" to "com.moonshot.kimichat",
    "夸克" to "com.quark.browser", "quark" to "com.quark.browser",
    "番茄小说" to "com.dragon.read", "番茄免费小说" to "com.dragon.read",
    "七猫小说" to "com.kmxs.reader", "七猫" to "com.kmxs.reader",
    "起点读书" to "com.qidian.QDReader", "起点" to "com.qidian.QDReader",
    "boss直聘" to "com.hpbr.bosszhipin", "boss" to "com.hpbr.bosszhipin",
    "12306" to "com.MobileTicket",
    "滴滴" to "com.sdu.didi.psnger", "滴滴出行" to "com.sdu.didi.psnger",
    "豆瓣" to "com.douban.frodo",
    "得到" to "com.luojilab.player",
    "keep" to "com.gotokeep.keep",
    "百度网盘" to "com.baidu.netdisk",
    "夸克网盘" to "com.quark.browser",
    "今日头条" to "com.ss.android.article.news", "头条" to "com.ss.android.article.news"
)

class OpenAppTool(private val context: Context) : Tool {
    override val name = "open_app"
    override val description = "【执行】打开/启动手机上的 App（会真的把它打开），按名称或包名，如『微信』或 com.tencent.mm。只想确认某 App 是否安装、或查它的包名，请改用 find_app（只查不打开）。"
    override val sideEffect = true
    override fun parameters() = objectSchema(
        "app" to strProp("应用名称或包名，如 微信 / com.tencent.mm"),
        required = listOf("app")
    )

    override suspend fun execute(args: JsonObject): String {
        val input = args.str("app").ifBlank { args.strOrNull("package_name").orEmpty() }.trim()
        if (input.isBlank()) return "错误：请提供应用名称或包名"
        val pm = context.packageManager
        val pkg = withContext(Dispatchers.IO) {
            // 1) treat as package name (works even when enumeration is blocked)
            pm.getLaunchIntentForPackage(input)?.let { return@withContext input }
            // 2) known common app by name -> package (works without enumeration)
            COMMON_APPS[input.lowercase()]?.let { mapped ->
                if (pm.getLaunchIntentForPackage(mapped) != null) return@withContext mapped
            }
            // 3) fall back to enumerating installed apps by label (may be empty on MIUI)
            val launchable = runCatching {
                pm.getInstalledApplications(0).filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            }.getOrDefault(emptyList())
            (launchable.firstOrNull { pm.getApplicationLabel(it).toString().equals(input, true) }
                ?: launchable.firstOrNull { pm.getApplicationLabel(it).toString().contains(input, true) })
                ?.packageName
        } ?: return "没找到应用「$input」。可先用 find_app 查它的包名，或在 设置→Agent→权限 里授予『读取应用列表』。"
        val intent = pm.getLaunchIntentForPackage(pkg) ?: return "无法启动：$pkg"
        return try {
            withContext(Dispatchers.Main) {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            "已打开：$input"
        } catch (e: Exception) {
            "启动失败：${e.message}"
        }
    }
}

class FindAppTool(private val context: Context) : Tool {
    override val name = "find_app"
    override val description = "【查询】只查找/列出已安装的 App 及其包名，不会打开任何 App。传名称关键字筛选，不传则列出可启动的应用。要真正打开某个 App 请用 open_app。"
    override val sideEffect = false
    override fun parameters() = objectSchema(
        "query" to strProp("应用名称关键字（可选）"),
        required = emptyList()
    )

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        val raw = args.str("query").trim()
        val q = raw.lowercase()
        val pm = context.packageManager
        val out = LinkedHashSet<String>()
        // 1) common apps that are actually installed (works even when enumeration is blocked)
        for ((name, pkg) in COMMON_APPS) {
            if (out.size >= ToolLimits.FINDAPP_CAP) break
            if (q.isNotEmpty() && !name.contains(q) && !pkg.lowercase().contains(q)) continue
            if (pm.getLaunchIntentForPackage(pkg) == null) continue
            val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }
                .getOrDefault(name)
            out.add("$label  →  $pkg")
        }
        // 2) full enumeration (may be empty on MIUI/HyperOS without 读取应用列表 permission)
        runCatching {
            for (ai in pm.getInstalledApplications(0)) {
                if (out.size >= ToolLimits.FINDAPP_CAP) break
                if (pm.getLaunchIntentForPackage(ai.packageName) == null) continue
                val label = pm.getApplicationLabel(ai).toString()
                if (q.isEmpty() || label.lowercase().contains(q) || ai.packageName.lowercase().contains(q)) {
                    out.add("$label  →  ${ai.packageName}")
                }
            }
        }
        if (out.isEmpty()) {
            "没找到匹配「$raw」的应用。若在小米/HyperOS，请到 设置→Agent→权限 授予『读取应用列表』后重试。"
        } else {
            val capped = out.size >= ToolLimits.FINDAPP_CAP
            out.sorted().joinToString("\n") +
                if (capped) "\n（结果较多，已截断到前 ${ToolLimits.FINDAPP_CAP} 个，请用更精确的 query 过滤）" else ""
        }
    }
}

class SaveMemoryTool(private val settings: SettingsRepository) : Tool {
    override val name = "save_memory"
    override val description = "把一条值得长期记住的信息存入用户的「记忆」，之后每次对话都会自动带上。" +
        "只存长期有效的事实：用户的偏好、身份、环境、长期约定等。" +
        "不要存任务进度、本次会话的结果、已完成的工作记录或临时待办。" +
        "判断标准：一周后还成立、且以后多次对话都用得上，才值得记。"
    override val sideEffect = false
    override fun parameters() = objectSchema(
        "title" to strProp("记忆标题，如『关于我』"),
        "content" to strProp("记忆内容"),
        "category" to enumProp("类别标签（可空）", MEMORY_CATEGORIES),
        required = listOf("title", "content")
    )

    override suspend fun execute(args: JsonObject): String {
        val title = args.str("title")
        val content = args.str("content")
        if (content.isBlank()) return "错误：content 不能为空"
        settings.addMemory(title, content, category = args.str("category"))
        return "已保存记忆：「${title.ifBlank { "未命名" }}」"
    }
}

class ReadMemoryTool(private val settings: SettingsRepository) : Tool {
    override val name = "read_memory"
    override val description = "读取用户当前所有已启用的记忆。"
    override val sideEffect = false
    override fun parameters() = objectSchema(required = emptyList())

    override suspend fun execute(args: JsonObject): String {
        val text = settings.enabledMemoriesText()
        return text.ifBlank { "（还没有任何记忆）" }
    }
}

class ForgetMemoryTool(private val settings: SettingsRepository) : Tool {
    override val name = "forget_memory"
    override val description = "删除用户「记忆」里的某条信息。当用户说『别记得…』『忘掉…』『删掉关于…的记忆』『我不在…了』时用它。" +
        "参数 query 可以是记忆的 id 数字，或其标题/内容里的关键词（删除所有匹配项）。拿不准时先用 read_memory 看一下有哪些记忆。"
    override val sideEffect = true
    override fun parameters() = objectSchema(
        "query" to strProp("要删除的记忆 id，或其标题/内容中的关键词"),
        required = listOf("query")
    )

    override suspend fun execute(args: JsonObject): String {
        val query = args.str("query")
        if (query.isBlank()) return "错误：query 不能为空"
        val removed = settings.forgetMemory(query)
        return if (removed.isEmpty()) "没有找到匹配「$query」的记忆。"
        else "已删除 ${removed.size} 条记忆：" + removed.joinToString("、") { "「$it」" }
    }
}
