package com.dschat.app.domain

import kotlinx.serialization.Serializable

/**
 * One portfolio holding. Designed to be general: a fund now, a stock later (just a different [type]
 * + quote source). Only the cost basis is stored; market value / P&L are computed live from a [Quote].
 */
@Serializable
data class Holding(
    val code: String,           // 基金代码(6 位) / 股票代码
    val name: String,
    val shares: Double,         // 持有份额 / 股数
    val cost: Double,           // 总成本（元，含买入费用，用户的真实投入）
    val type: String = "fund",  // "fund" | "stock"（目前只接基金，股票留作扩展）
)

/** Live quote for one [Holding.code]. */
data class Quote(
    val code: String,
    val name: String,
    val nav: Double,            // 最新净值 / 价格
    val navDate: String,        // 净值/行情日期
    val changePct: Double,      // 当日涨跌幅 %（已结算）
    val estPct: Double?,        // 盘中估算涨跌幅 %（仅交易时段，可空）
)

/** A holding paired with its (possibly null) quote → all the money figures the UI/notifications need. */
data class HoldingPnl(
    val holding: Holding,
    val quote: Quote?,
) {
    val marketValue: Double? get() = quote?.let { holding.shares * it.nav }
    val profit: Double? get() = marketValue?.let { it - holding.cost }
    val profitPct: Double? get() =
        if (holding.cost > 0) profit?.div(holding.cost)?.times(100) else null

    /** 当日盈亏额：市值 − 昨日市值，由当日涨跌幅反推（市值 − 市值/(1+涨跌幅)）。 */
    val dayProfit: Double? get() {
        val mv = marketValue ?: return null
        val f = 1 + (quote?.changePct ?: return null) / 100.0
        return if (f != 0.0) mv - mv / f else null
    }
}
