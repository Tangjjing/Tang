@file:OptIn(ExperimentalMaterial3Api::class)

package com.dschat.app.ui.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun NotifyAssistantScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val ambientEnabled by viewModel.ambientEnabled.collectAsStateWithLifecycle()
    val watchedApps by viewModel.watchedApps.collectAsStateWithLifecycle()
    val watchScreenshots by viewModel.watchScreenshots.collectAsStateWithLifecycle()
    val autoReplyEnabled by viewModel.autoReplyEnabled.collectAsStateWithLifecycle()
    val autoReplyContacts by viewModel.autoReplyContacts.collectAsStateWithLifecycle()
    val autoScheduleEnabled by viewModel.autoScheduleEnabled.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val candidates = remember {
        val pm = context.packageManager
        val out = mutableListOf<Pair<String, String>>()
        android.provider.Telephony.Sms.getDefaultSmsPackage(context)?.let { out.add("短信" to it) }
        listOf(
            "微信" to "com.tencent.mm",
            "QQ" to "com.tencent.mobileqq",
            "钉钉" to "com.alibaba.android.rimet",
            "企业微信" to "com.tencent.wework",
            "Telegram" to "org.telegram.messenger"
        ).forEach { (label, pkg) -> if (pm.getLaunchIntentForPackage(pkg) != null) out.add(label to pkg) }
        out
    }

    SettingsSubScreen("通知助理（实验）", onBack) {
        ToggleRow(
            "把通知自动变成待办",
            "监听白名单 App 的通知，AI 判断是否待办、抽取时间和优先级，到点提醒（可回复的消息还会给草稿）",
            ambientEnabled
        ) { viewModel.setAmbientEnabled(it) }

        if (ambientEnabled) {
            OutlinedButton(
                onClick = { try { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) } catch (_: Exception) {} },
                modifier = Modifier.fillMaxWidth()
            ) { Text("① 打开「通知使用权」授权页") }
            Hint("在系统页面里找到「DeepSeek 通知助理」并允许，否则读不到通知。小米/HyperOS 还需关闭本应用电池优化、允许自启动，否则后台会被杀。")

            SectionTitle("② 选择要监听的应用")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                candidates.forEach { (label, pkg) ->
                    FilterChip(
                        selected = pkg in watchedApps,
                        onClick = { viewModel.setAppWatched(pkg, pkg !in watchedApps) },
                        label = { Text(label) }
                    )
                }
            }
            Hint("⚠️ 开启后，被监听 App 的通知文本会发送给所选模型用于判断；每条通知会消耗少量 token。")

            ToggleRow(
                "也分析截图（OCR）",
                "新截图用本地离线 OCR 识别文字后再判断是否待办（需已授予『所有文件访问』）",
                watchScreenshots
            ) { viewModel.setWatchScreenshots(it) }

            SectionTitle("③ 自动加入日程")
            ToggleRow(
                "把约定自动加进日历",
                "消息里识别到带具体时间的约定（如『下午两点开会』）时，静默写入系统日历并设提醒；会发一条可【撤销】的通知。",
                autoScheduleEnabled
            ) { viewModel.setAutoScheduleEnabled(it) }
            if (autoScheduleEnabled) {
                Hint("• 只处理含明确时间的真实安排（会议/约会/截止等），模糊或无时间的不动。\n• 直接写入系统日历，需到『设置→权限』授予「日历（读写）」。\n• 建错了在通知里点「撤销」即可删除事件并取消提醒。\n• 即使同时开了下方『消息自动回复』，命令型消息（如『下午两点开会』）也会照常建日程——回复和建日程互不影响。")
            }

            SectionTitle("④ 消息自动回复")
            ToggleRow(
                "替我回消息",
                "对方发来消息时，AI 模仿你的口吻拟一条回复。白名单联系人直接自动发出，其他人弹出草稿、一键发送。",
                autoReplyEnabled
            ) { viewModel.setAutoReplyEnabled(it) }
            if (autoReplyEnabled) {
                Hint("• 同一个人短时间连发多条，会合并成一条回复。\n• 每个联系人单独记忆，隔几天再聊也能接上之前的上下文。\n• 想让某人「全自动」：在它的草稿通知上点「以后自动回TA」即可。\n⚠️ 自动发出的消息无法撤回，请先用少数联系人试用。")
                OutlinedButton(
                    onClick = { try { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } catch (_: Exception) {} },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("开启「Tang 自动发送」无障碍服务") }
                Hint("微信/QQ 不支持通知里的快捷回复，必须开启此无障碍服务，Tang 才能自动打开对话、输入并按下发送（发送瞬间会短暂跳到微信界面）。短信、Telegram 等支持快捷回复的应用无需开启。")

                if (autoReplyContacts.isNotEmpty()) {
                    SectionTitle("已设为「全自动回复」的联系人")
                    autoReplyContacts.forEach { key ->
                        val name = key.substringAfter('|', key)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(name, modifier = Modifier.weight(1f), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                            TextButton(onClick = { viewModel.setContactAuto(key, false) }) { Text("取消自动") }
                        }
                    }
                    Hint("这些人发来消息时 Tang 会【直接自动发送】回复、不再询问。点「取消自动」改回草稿模式（仍会拟好回复，但要你一键确认发送）。")
                }
            }
        }
    }
}
