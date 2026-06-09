package com.dschat.app.agent.tasks

import com.dschat.app.domain.Holding
import com.dschat.app.domain.HoldingPnl
import com.dschat.app.domain.Quote
import java.util.Locale

/**
 * Turns holdings + live quotes into the human-facing P&L summary. Shared by the daily notification,
 * the `portfolio_status` agent tool, and the 持仓 screen so the wording & math stay consistent.
 * Tone is intentionally gentle — the owner is anxious about losses.
 */
object PortfolioReport {

    data class Totals(val cost: Double, val value: Double, val profit: Double, val profitPct: Double, val quoted: Int)

    fun rows(holdings: List<Holding>, quotes: Map<String, Quote>): List<HoldingPnl> =
        holdings.map { HoldingPnl(it, quotes[it.code]) }

    fun totals(rows: List<HoldingPnl>): Totals {
        val quoted = rows.filter { it.quote != null }
        val cost = quoted.sumOf { it.holding.cost }
        val value = quoted.sumOf { it.marketValue ?: 0.0 }
        val profit = value - cost
        val pct = if (cost > 0) profit / cost * 100 else 0.0
        return Totals(cost, value, profit, pct, quoted.size)
    }

    private fun money(v: Double): String = "¥" + String.format(Locale.CHINA, "%,.2f", v)
    private fun signedMoney(v: Double): String = (if (v >= 0) "+" else "-") + money(kotlin.math.abs(v))
    private fun pct(v: Double): String = String.format(Locale.CHINA, "%+.2f%%", v)

    /** A holding row: "· 名称  昨日±x%  浮盈/浮亏±y%". */
    private fun rowLine(r: HoldingPnl): String {
        val q = r.quote ?: return "· ${r.holding.name}  （暂时取不到净值）"
        val day = pct(q.changePct)
        val pp = r.profitPct
        val total = when {
            pp == null -> "成本未知"
            kotlin.math.abs(pp) < 0.05 -> "约持平"
            pp >= 0 -> "浮盈 ${pct(pp)}"
            else -> "浮亏 ${pct(pp)}"
        }
        return "· ${r.holding.name}  昨日$day · $total"
    }

    /** Encouraging closing line, tuned to the overall result. Kept short so the notification fits. */
    private fun closing(profit: Double, profitPct: Double): String = when {
        profit >= 0 && profitPct >= 3 -> "今天开局不错，保持平常心 ☀️"
        profit >= 0 -> "稳稳的，挺好 🌿"
        profitPct > -5 -> "小波动很正常，别太放心上 🌱"
        profitPct > -15 -> "账面浮亏是暂时的，慢慢来 🤍"
        else -> "浮亏没兑现就还有时间，照顾好自己 🤍"
    }

    /** One concise line for the notification's collapsed view — the bottom line at a glance. */
    fun headline(rows: List<HoldingPnl>): String {
        if (rows.isEmpty()) return "还没有持仓"
        val t = totals(rows)
        if (t.quoted == 0) return "暂取不到最新净值"
        val word = if (t.profit >= 0) "浮盈" else "浮亏"
        return "$word ${signedMoney(t.profit)}（${pct(t.profitPct)}）· 市值 ${money(t.value)}"
    }

    /**
     * Full summary text. [header] is the opening line (e.g. 早安/晚间). When [quotesMissing] all
     * quotes failed, returns a soft "拿不到数据" note instead.
     */
    fun summary(header: String, rows: List<HoldingPnl>): String {
        if (rows.isEmpty()) return "你还没有添加任何持仓～在「我的持仓」里加一只基金，我就能每天帮你盯着啦。"
        val t = totals(rows)
        if (t.quoted == 0) return "$header\n暂时没能取到最新净值（可能是网络或非交易日），等会儿我再帮你看看 🤍"
        val navDate = rows.firstNotNullOfOrNull { it.quote?.navDate }?.takeIf { it.isNotBlank() }
        return buildString {
            append(header).append('\n')
            val word = if (t.profit >= 0) "浮盈" else "浮亏"
            append("$word ${signedMoney(t.profit)}（${pct(t.profitPct)}）")
            if (navDate != null) append("｜截至 ${navDate.takeLast(5)}")
            append('\n')
            append("市值 ${money(t.value)} · 成本 ${money(t.cost)}\n")
            rows.forEach { append(rowLine(it)).append('\n') }
            append(closing(t.profit, t.profitPct))
        }.trim()
    }
}
