@file:OptIn(ExperimentalMaterial3Api::class)

package com.dschat.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dschat.app.data.settings.ThemeMode

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenAgent: () -> Unit,
    onOpenNotify: () -> Unit,
    onOpenMemory: () -> Unit,
    onOpenUsage: () -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenChatParams: () -> Unit,
    onOpenWeather: () -> Unit
) {
    val theme by viewModel.theme.collectAsStateWithLifecycle()

    SettingsSubScreen("设置", onBack) {
        NavRow(Icons.Default.Tune, "模型管理", "增删模型，为每个模型填接口地址与 Key", onOpenModels)
        NavRow(Icons.Default.SmartToy, "Agent（工具调用）", "执行模式、搜索后端、是否用 Pro", onOpenAgent)
        NavRow(Icons.Default.Notifications, "通知助理", "把通知 / 截图自动变成待办与提醒", onOpenNotify)
        NavRow(Icons.Default.WbSunny, "天气", "实时天气工具 + 早间恶劣天气/突变提醒", onOpenWeather)
        NavRow(Icons.AutoMirrored.Filled.Chat, "对话设置", "温度、全局系统提示词", onOpenChatParams)
        NavRow(Icons.Default.Memory, "记忆", "让模型长期记住的信息", onOpenMemory)
        NavRow(Icons.Default.DataUsage, "用量统计", "各模型的 token 消耗", onOpenUsage)
        NavRow(Icons.Default.Lock, "权限", "文件 / 定位 / 日历 / 通讯录 / 通知", onOpenPermissions)

        SectionTitle("外观")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeOption("跟随系统", theme == ThemeMode.SYSTEM) { viewModel.setTheme(ThemeMode.SYSTEM) }
            ThemeOption("浅色", theme == ThemeMode.LIGHT) { viewModel.setTheme(ThemeMode.LIGHT) }
            ThemeOption("深色", theme == ThemeMode.DARK) { viewModel.setTheme(ThemeMode.DARK) }
        }

        HorizontalDivider(Modifier.padding(top = 8.dp))
        Text(
            "Tang · by Lxl · v1.12 · 多模型 AI 助手 · 数据仅存本机",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ThemeOption(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}
