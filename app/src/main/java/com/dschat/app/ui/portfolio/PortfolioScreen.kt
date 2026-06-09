@file:OptIn(ExperimentalMaterial3Api::class)

package com.dschat.app.ui.portfolio

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dschat.app.agent.tasks.PortfolioMonitor
import com.dschat.app.agent.tasks.PortfolioReport
import com.dschat.app.agent.tasks.PortfolioScheduler
import com.dschat.app.data.remote.FundSearchHit
import com.dschat.app.domain.HoldingPnl
import com.dschat.app.ui.settings.Hint
import com.dschat.app.ui.settings.SectionTitle
import com.dschat.app.ui.settings.SettingsSubScreen
import com.dschat.app.ui.settings.ToggleRow
import kotlinx.coroutines.launch
import java.util.Locale

// 涨红跌绿（A股习惯）：盈/涨为红，亏/跌为绿，持平为中性灰。
private val UpRed = Color(0xFFD32F2F)
private val DownGreen = Color(0xFF2E7D32)

@Composable
private fun pnlColor(v: Double?): Color = when {
    v == null -> MaterialTheme.colorScheme.onSurfaceVariant
    v > 0.0001 -> UpRed
    v < -0.0001 -> DownGreen
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun money(v: Double) = "¥" + String.format(Locale.CHINA, "%,.2f", v)
private fun signedMoney(v: Double) = (if (v >= 0) "+" else "-") + money(kotlin.math.abs(v))
private fun pct(v: Double) = String.format(Locale.CHINA, "%+.2f%%", v)

@Composable
fun PortfolioScreen(viewModel: PortfolioViewModel, onBack: () -> Unit) {
    val holdings by viewModel.holdings.collectAsStateWithLifecycle()
    val quotes by viewModel.quotes.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val results by viewModel.searchResults.collectAsStateWithLifecycle()
    val searching by viewModel.searching.collectAsStateWithLifecycle()
    val enabled by viewModel.enabled.collectAsStateWithLifecycle()
    val morningHour by viewModel.morningHour.collectAsStateWithLifecycle()
    val eveningOn by viewModel.eveningEnabled.collectAsStateWithLifecycle()
    val eveningHour by viewModel.eveningHour.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var query by remember { mutableStateOf("") }
    var pendingAdd by remember { mutableStateOf<FundSearchHit?>(null) }

    val rows = remember(holdings, quotes) { PortfolioReport.rows(holdings, quotes) }
    val totals = remember(rows) { PortfolioReport.totals(rows) }

    SettingsSubScreen("我的持仓", onBack) {
        // ---- 总览卡片 ----
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                if (holdings.isEmpty()) {
                    Text("还没有持仓", fontWeight = FontWeight.Medium)
                    Text("在下方搜索基金名称或代码添加，我会每天早晚帮你盯着涨跌盈亏 🤍",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else if (totals.quoted == 0) {
                    Text("总成本 ${money(totals.cost)}", fontWeight = FontWeight.Medium)
                    Text(if (loading) "正在获取最新净值…" else "暂时取不到净值，下拉刷新或稍后再试",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text("当前总市值", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(money(totals.value), fontWeight = FontWeight.Bold, fontSize = 26.sp)
                    Row(Modifier.padding(top = 4.dp)) {
                        Text("${if (totals.profit >= 0) "浮盈" else "浮亏"} ${signedMoney(totals.profit)}（${pct(totals.profitPct)}）",
                            color = pnlColor(totals.profit), fontWeight = FontWeight.Medium, fontSize = 14.sp)
                    }
                    Text("成本 ${money(totals.cost)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        OutlinedButton(onClick = { viewModel.refresh() }, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
            Text(if (loading) "刷新中…" else "刷新净值")
        }

        // ---- 持仓列表 ----
        if (holdings.isNotEmpty()) {
            SectionTitle("持仓明细")
            rows.forEach { r -> HoldingCard(r) { viewModel.remove(r.holding.code) } }
        }

        // ---- 添加持仓 ----
        SectionTitle("添加持仓")
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("基金名称或 6 位代码") },
            placeholder = { Text("如 华安黄金 / 000217") }
        )
        OutlinedButton(onClick = { viewModel.search(query) }, enabled = !searching && query.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            Text(if (searching) "搜索中…" else "搜索基金")
        }
        results.forEach { hit ->
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(hit.name, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
                        Text("${hit.code}${if (hit.type.isNotBlank()) " · ${hit.type}" else ""}${hit.nav?.let { " · 净值 $it" } ?: ""}",
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { pendingAdd = hit }) { Text("添加") }
                }
            }
        }

        // ---- 通知设置 ----
        SectionTitle("每日盈亏早晚报")
        ToggleRow("开启每日通知", "基金净值前一晚结算；早报是昨日收盘盈亏，晚报是当晚最新净值。", enabled) {
            viewModel.setEnabled(it)
            if (it) PortfolioScheduler.enqueue(context) else PortfolioScheduler.cancel(context)
        }
        if (enabled) {
            SectionTitle("早报时间")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(7, 8, 9, 10).forEach { h ->
                    FilterChip(selected = morningHour == h, onClick = { viewModel.setMorningHour(h) }, label = { Text("%02d:00".format(h)) })
                }
            }
            ToggleRow("晚上也来一条", "约定时间推一条当晚最新净值的盈亏（数据最准）。", eveningOn) { viewModel.setEveningEnabled(it) }
            if (eveningOn) {
                SectionTitle("晚报时间")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(20, 21, 22, 23).forEach { h ->
                        FilterChip(selected = eveningHour == h, onClick = { viewModel.setEveningHour(h) }, label = { Text("%02d:00".format(h)) })
                    }
                }
            }
            OutlinedButton(
                onClick = { scope.launch { viewModel.postMessage(PortfolioMonitor.pushNow(context)) } },
                modifier = Modifier.fillMaxWidth()
            ) { Text("立即推送一条测试通知") }
            Hint("⚠️ 后台约每 15 分钟检查一次（系统按省电策略放宽间隔）。小米/华为等需把本应用电池设为『无限制』并允许『自启动』，否则后台会被杀、收不到。")
        }

        message?.let {
            Hint(it)
        }
    }

    // ---- 添加时填成本/份额的对话框 ----
    pendingAdd?.let { hit ->
        AddHoldingDialog(
            hit = hit,
            onConfirm = { cost, shares -> viewModel.add(hit, cost, shares); pendingAdd = null },
            onDismiss = { pendingAdd = null }
        )
    }
}

@Composable
private fun HoldingCard(r: HoldingPnl, onDelete: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(r.holding.name, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                val q = r.quote
                if (q == null) {
                    Text("暂取不到净值", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("净值 ${q.nav}", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("当日 ${pct(q.changePct)}", fontSize = 11.5.sp, color = pnlColor(q.changePct))
                    }
                    val mv = r.marketValue ?: 0.0
                    val profit = r.profit ?: 0.0
                    Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("市值 ${money(mv)}", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${if (profit >= 0) "浮盈" else "浮亏"} ${signedMoney(profit)}${r.profitPct?.let { "（${pct(it)}）" } ?: ""}",
                            fontSize = 11.5.sp, color = pnlColor(profit))
                    }
                }
            }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Close, contentDescription = "移除") }
        }
    }
}

@Composable
private fun AddHoldingDialog(hit: FundSearchHit, onConfirm: (Double, Double?) -> Unit, onDismiss: () -> Unit) {
    var cost by remember { mutableStateOf("") }
    var shares by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加 ${hit.name}", fontSize = 16.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${hit.code}${hit.nav?.let { " · 当前净值 $it" } ?: ""}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(value = cost, onValueChange = { cost = it }, singleLine = true,
                    label = { Text("总投入成本（元）") }, placeholder = { Text("如 1000") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = shares, onValueChange = { shares = it }, singleLine = true,
                    label = { Text("持有份额（可选）") }, placeholder = { Text("不填则按净值估算") }, modifier = Modifier.fillMaxWidth())
                Text("已持有的老仓建议填份额，盈亏才准；刚买入的可只填成本。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val c = cost.trim().toDoubleOrNull()
                if (c != null && c > 0) onConfirm(c, shares.trim().toDoubleOrNull())
            }) { Text("添加") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
