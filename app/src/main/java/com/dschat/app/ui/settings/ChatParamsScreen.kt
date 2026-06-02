@file:OptIn(ExperimentalMaterial3Api::class)

package com.dschat.app.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale

@Composable
fun ChatParamsScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val temperature by viewModel.temperature.collectAsStateWithLifecycle()
    val systemPrompt by viewModel.systemPrompt.collectAsStateWithLifecycle()

    SettingsSubScreen("对话设置", onBack) {
        SectionTitle("温度  ${String.format(Locale.US, "%.1f", temperature)}")
        Slider(
            value = temperature,
            onValueChange = viewModel::setTemperature,
            valueRange = 0f..2f,
            steps = 19
        )
        Hint("数值越高回答越发散有创意，越低越严谨确定。日常对话建议 1.0 左右，写代码可调低。")

        SectionTitle("全局系统提示词")
        OutlinedTextField(
            value = systemPrompt,
            onValueChange = viewModel::setSystemPrompt,
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            maxLines = 8,
            placeholder = { Text("可选。例如：你是一名资深 Android 工程师，回答尽量简洁。") }
        )
        Hint("作为所有对话的默认提示词；单个对话可在聊天页顶部的 ✎ 里单独覆盖。")
    }
}
