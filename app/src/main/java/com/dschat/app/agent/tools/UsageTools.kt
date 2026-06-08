package com.dschat.app.agent.tools

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import com.dschat.app.agent.Tool
import com.dschat.app.agent.intOr
import com.dschat.app.agent.intProp
import com.dschat.app.agent.objectSchema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** 手机使用统计：最近 N 天各应用前台使用时长 + 合计。需「使用情况访问」特殊授权（非普通运行时权限）。 */
class AppUsageTool(private val context: Context) : Tool {
    override val name = "app_usage"
    override val description =
        "查询本机最近 N 天各应用的前台使用时长与合计（如『我今天/这周用了多久手机、哪个 app 用得最多』）。需要用户在 设置→使用情况访问 为 Tang 开启权限。"
    override val sideEffect = false
    override fun parameters() = objectSchema(
        "days" to intProp("统计最近多少天（默认 1 = 最近 24 小时）", min = 1, max = 90),
        "top" to intProp("返回前几名应用（默认 10）", min = 1, max = 30),
        required = emptyList()
    )

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        if (!hasUsageAccess()) {
            return@withContext "未授予『使用情况访问』权限，读不到使用统计。请到 设置→权限→使用情况访问（或系统 设置→应用→特殊权限→使用情况访问）为 Tang 开启后重试。"
        }
        val days = args.intOr("days", 1).coerceIn(1, 90)
        val top = args.intOr("top", 10).coerceIn(1, 30)
        val end = System.currentTimeMillis()
        val start = end - days * 24L * 60 * 60 * 1000
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return@withContext "无法获取使用统计服务。"
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, start, end)
        if (stats.isNullOrEmpty()) return@withContext "这段时间没有可用的使用数据（可能刚授权，或时间范围太短）。"
        // 同一包可能返回多条（按天分桶），合并前台总时长。
        val byPkg = HashMap<String, Long>()
        for (s in stats) {
            val t = s.totalTimeInForeground
            if (t > 0) byPkg[s.packageName] = (byPkg[s.packageName] ?: 0L) + t
        }
        if (byPkg.isEmpty()) return@withContext "这段时间没有应用前台使用记录。"
        val pm = context.packageManager
        val totalMs = byPkg.values.sum()
        val rows = byPkg.entries.sortedByDescending { it.value }.take(top).joinToString("\n") { (pkg, ms) ->
            // 查询单个包的 label 在 MIUI/HyperOS 上可用（被屏蔽的是枚举全部应用，不是按包查）。
            val label = try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { pkg }
            "· $label：${fmt(ms)}"
        }
        "最近 $days 天 应用使用时长（前 $top 名）：\n$rows\n\n合计前台时间约 ${fmt(totalMs)}。\n（注：基于系统使用情况统计的前台时长，未必等于亮屏时间。）"
    }

    private fun hasUsageAccess(): Boolean = try {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        else @Suppress("DEPRECATION") ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        mode == AppOpsManager.MODE_ALLOWED
    } catch (_: Exception) {
        false
    }

    private fun fmt(ms: Long): String {
        val min = ms / 60000
        return if (min >= 60) "${min / 60} 小时 ${min % 60} 分" else "$min 分钟"
    }
}
