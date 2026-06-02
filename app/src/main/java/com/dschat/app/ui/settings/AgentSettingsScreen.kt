@file:OptIn(ExperimentalMaterial3Api::class)

package com.dschat.app.ui.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dschat.app.agent.ExecutionMode

@Composable
fun AgentSettingsScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val agentEnabled by viewModel.agentEnabled.collectAsStateWithLifecycle()
    val agentUseProModel by viewModel.agentUseProModel.collectAsStateWithLifecycle()
    val executionMode by viewModel.executionMode.collectAsStateWithLifecycle()
    val searchKeyBaidu by viewModel.searchKeyBaidu.collectAsStateWithLifecycle()
    val searchKeyBocha by viewModel.searchKeyBocha.collectAsStateWithLifecycle()
    val searchKeyMetaso by viewModel.searchKeyMetaso.collectAsStateWithLifecycle()
    val searchPrimary by viewModel.searchPrimary.collectAsStateWithLifecycle()

    SettingsSubScreen("Agent（工具调用）", onBack) {
        ToggleRow(
            "开启 Agent",
            "让模型能调用工具：联网搜索、读写文件、运行 JS、剪贴板、定位等",
            agentEnabled
        ) { viewModel.setAgentEnabled(it) }

        if (agentEnabled) {
            ToggleRow(
                "Agent 使用 V4 Pro 模型",
                "DeepSeek 用 Flash 时，工具循环自动升级到 Pro（更强、更慢、更费）；其它模型保持你的选择",
                agentUseProModel
            ) { viewModel.setAgentUseProModel(it) }

            SectionTitle("执行模式（调用有副作用的工具前是否先问你）")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val labels = mapOf(
                    ExecutionMode.CONFIRM_ALL to "全部确认",
                    ExecutionMode.CONFIRM_SIDE_EFFECTS to "仅副作用确认",
                    ExecutionMode.FULL_AUTO to "完全放权"
                )
                ExecutionMode.entries.forEach { m ->
                    FilterChip(selected = executionMode == m, onClick = { viewModel.setExecutionMode(m) }, label = { Text(labels[m] ?: m.label) })
                }
            }

            SectionTitle("AI 搜索后端（用于联网查资料，非聊天模型）")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("优先用 ", modifier = Modifier.align(Alignment.CenterVertically))
                listOf("baidu" to "百度", "bocha" to "博查", "metaso" to "秘塔").forEach { (k, label) ->
                    FilterChip(selected = searchPrimary == k, onClick = { viewModel.setSearchPrimary(k) }, label = { Text(label) })
                }
            }
            OutlinedTextField(
                value = searchKeyBaidu,
                onValueChange = viewModel::setSearchKeyBaidu,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("百度千帆 AI 搜索 key（优先 · 每日额度大）") },
                placeholder = { Text("bce-v3/...") }
            )
            OutlinedTextField(
                value = searchKeyBocha,
                onValueChange = viewModel::setSearchKeyBocha,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                singleLine = true,
                label = { Text("博查 Bocha key（补充 · 总量有限）") },
                placeholder = { Text("sk-...") }
            )
            OutlinedTextField(
                value = searchKeyMetaso,
                onValueChange = viewModel::setSearchKeyMetaso,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                singleLine = true,
                label = { Text("秘塔 Metaso key（学术 / 备用）") },
                placeholder = { Text("mk-...") }
            )
            Hint("调用优先级：百度 → 博查 → 秘塔 →（都没配或失败时）免费 Bing。每次搜索只命中一个、按顺序回退，最省额度。学术/论文类问题模型会自动改用秘塔。这三者是“AI 搜索”服务，只用来联网查资料，不是聊天大模型。")
            Hint("⚠️ 完全放权模式下，模型可不经确认直接读写文件、发请求等，请谨慎使用。")
        }
    }
}
