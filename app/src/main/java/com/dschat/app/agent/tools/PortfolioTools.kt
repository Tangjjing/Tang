package com.dschat.app.agent.tools

import com.dschat.app.agent.Tool
import com.dschat.app.agent.doubleOrNull
import com.dschat.app.agent.numberProp
import com.dschat.app.agent.objectSchema
import com.dschat.app.agent.str
import com.dschat.app.agent.strProp
import com.dschat.app.agent.tasks.PortfolioReport
import com.dschat.app.data.remote.FundApi
import com.dschat.app.data.settings.SettingsRepository
import com.dschat.app.domain.Holding
import kotlinx.serialization.json.JsonObject
import java.util.Locale

class PortfolioStatusTool(private val settings: SettingsRepository) : Tool {
    override val name = "portfolio_status"
    override val description =
        "查看用户当前持仓（基金）的实时净值、涨跌与盈亏。当用户问『我的基金涨了吗』『现在亏多少』『看看我的持仓』时用它。"
    override val sideEffect = false
    override fun parameters() = objectSchema(required = emptyList())

    override suspend fun execute(args: JsonObject): String {
        val holdings = settings.holdings.value
        if (holdings.isEmpty()) return "你还没有添加任何持仓。可以说『帮我加一只…基金，成本…元』，或到「我的持仓」页面添加。"
        val quotes = FundApi.fetchQuotes(holdings.map { it.code })
        val rows = PortfolioReport.rows(holdings, quotes)
        return PortfolioReport.summary("你当前的持仓情况：", rows)
    }
}

class AddHoldingTool(private val settings: SettingsRepository) : Tool {
    override val name = "add_holding"
    override val description =
        "把一只基金加入用户的持仓盯盘列表（之后每天早晚通知涨跌盈亏）。name_or_code 可给基金名称或 6 位代码；" +
        "cost=总投入金额(元) 必填；shares=持有份额 可选（不填则按最新净值估算，相当于刚买入）。"
    override val sideEffect = true
    override fun parameters() = objectSchema(
        "name_or_code" to strProp("基金名称或 6 位代码，如『华安黄金ETF联接C』或 000217"),
        "cost" to numberProp("总投入成本（元）"),
        "shares" to numberProp("持有份额（可选；不填则按最新净值估算）"),
        required = listOf("name_or_code", "cost")
    )

    override suspend fun execute(args: JsonObject): String {
        val q = args.str("name_or_code").trim()
        if (q.isBlank()) return "错误：请提供基金名称或代码。"
        val cost = args.doubleOrNull("cost") ?: return "错误：请提供总投入成本 cost（元）。"
        if (cost <= 0) return "错误：成本需大于 0。"
        val code = if (q.matches(Regex("\\d{6}"))) q
        else FundApi.search(q).firstOrNull()?.code
            ?: return "没找到名为「$q」的基金，换个更准确的名称或直接给 6 位代码试试。"
        val quote = FundApi.fetchQuotes(listOf(code))[code]
            ?: return "拿不到「$code」的净值，请确认代码无误、稍后再试。"
        val shares = args.doubleOrNull("shares")?.takeIf { it > 0 } ?: (cost / quote.nav)
        settings.upsertHolding(Holding(code = code, name = quote.name, shares = shares, cost = cost))
        return "已加入持仓：${quote.name}（$code），成本 ¥${"%,.2f".format(Locale.CHINA, cost)}、" +
            "份额 ${"%.2f".format(Locale.CHINA, shares)}。以后每天早晚我都会帮你盯着涨跌盈亏 🤍"
    }
}

class RemoveHoldingTool(private val settings: SettingsRepository) : Tool {
    override val name = "remove_holding"
    override val description = "从持仓盯盘列表里移除一只基金。query 可以是名称关键词或 6 位代码。"
    override val sideEffect = true
    override fun parameters() = objectSchema(
        "query" to strProp("要移除的基金名称关键词或 6 位代码"),
        required = listOf("query")
    )

    override suspend fun execute(args: JsonObject): String {
        val q = args.str("query").trim()
        if (q.isBlank()) return "错误：query 不能为空"
        val match = settings.holdings.value.filter { it.code == q || it.name.contains(q, true) }
        if (match.isEmpty()) return "持仓里没有匹配「$q」的基金。"
        match.forEach { settings.deleteHolding(it.code) }
        return "已移除：" + match.joinToString("、") { it.name }
    }
}
