@file:OptIn(ExperimentalMaterial3Api::class)

package com.dschat.app.ui.usage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dschat.app.domain.UsageStat
import java.util.Locale

private fun fmt(n: Long): String = String.format(Locale.US, "%,d", n)

@Composable
fun UsageScreen(viewModel: UsageViewModel, onBack: () -> Unit) {
    val usage by viewModel.usage.collectAsStateWithLifecycle()
    val models by viewModel.models.collectAsStateWithLifecycle()
    val balances by viewModel.balances.collectAsStateWithLifecycle()
    val nameOf: (String) -> String = { id -> models.firstOrNull { it.id == id }?.displayName ?: id }
    val entries = usage.entries.sortedByDescending { it.value.totalTokens }
    val totalPrompt = usage.values.sumOf { it.promptTokens }
    val totalCompletion = usage.values.sumOf { it.completionTokens }
    val totalCalls = usage.values.sumOf { it.calls }
    val providers = viewModel.providersWithKey()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("用量统计") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                actions = {
                    if (providers.isNotEmpty()) IconButton(onClick = { viewModel.refreshAll() }) { Icon(Icons.Default.Refresh, "刷新余额") }
                    if (usage.isNotEmpty()) TextButton(onClick = { viewModel.clear() }) { Text("清空") }
                },
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ---- API 余额 ----
            item { SectionTitle("API 余额") }
            if (providers.isEmpty()) {
                item {
                    Text(
                        "在「模型管理」给模型填好 API Key 后，这里会显示各家的剩余额度。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(providers, key = { "bal_$it" }) { p ->
                    BalanceCard(provider = p, state = balances[p], onRefresh = { viewModel.refresh(p) })
                }
                item {
                    Text(
                        "余额为实时查询各平台返回；DeepSeek / Kimi / OpenRouter 支持查询，其它平台请在官网查看。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ---- token 用量 ----
            item { SectionTitle("Token 用量") }
            if (usage.isEmpty()) {
                item {
                    Text(
                        "还没有用量记录。开始对话后，这里会按模型统计消耗的 token。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                item { TotalCard(totalPrompt + totalCompletion, totalPrompt, totalCompletion, totalCalls) }
                items(entries.toList(), key = { it.key }) { e -> UsageRow(nameOf(e.key), e.key, e.value) }
                item {
                    Text(
                        "统计为本机累计的 token 数；实际费用请以各平台账单为准。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = 4.dp)
    )
}

@Composable
private fun BalanceCard(provider: String, state: BalanceState?, onRefresh: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(provider, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
                when (state) {
                    null, BalanceState.Loading -> Text(
                        "查询中…", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    is BalanceState.Loaded -> {
                        val r = state.result
                        when {
                            !r.supported -> Text("该平台暂不支持余额查询，请在官网查看", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            r.error != null -> Text(r.error, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                            else -> Text(
                                r.detail.ifBlank { if (r.available) "可用" else "余额不足" },
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            val loaded = state as? BalanceState.Loaded
            val amount = loaded?.result?.takeIf { it.supported && it.error == null }?.display
            if (!amount.isNullOrBlank()) {
                Text(
                    amount,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    color = if (loaded?.result?.available == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(end = 4.dp)
                )
            }
            IconButton(onClick = onRefresh, modifier = Modifier.size(36.dp)) {
                if (state == BalanceState.Loading) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Icon(Icons.Default.Refresh, "刷新", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun TotalCard(total: Long, prompt: Long, completion: Long, calls: Long) {
    Surface(
        color = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(18.dp)) {
            Text("总计 tokens", color = MaterialTheme.colorScheme.background.copy(alpha = 0.7f), fontSize = 12.sp)
            Text(fmt(total), color = MaterialTheme.colorScheme.background, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text(
                "输入 ${fmt(prompt)} · 输出 ${fmt(completion)} · 调用 ${fmt(calls)} 次",
                color = MaterialTheme.colorScheme.background.copy(alpha = 0.7f),
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun UsageRow(name: String, id: String, stat: UsageStat) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    "输入 ${fmt(stat.promptTokens)} · 输出 ${fmt(stat.completionTokens)} · ${fmt(stat.calls)} 次",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(fmt(stat.totalTokens), fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
                Text("tokens", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
