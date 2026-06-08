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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
    val nameOf: (String) -> String = { id -> models.firstOrNull { it.id == id }?.displayName ?: id }
    val entries = usage.entries.sortedByDescending { it.value.totalTokens }
    val totalPrompt = usage.values.sumOf { it.promptTokens }
    val totalCompletion = usage.values.sumOf { it.completionTokens }
    val totalCalls = usage.values.sumOf { it.calls }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("用量统计") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                actions = {
                    if (usage.isNotEmpty()) TextButton(onClick = { viewModel.clear() }) { Text("清空") }
                },
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        if (usage.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "还没有用量记录。开始对话后，这里会按模型统计消耗的 token。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
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
