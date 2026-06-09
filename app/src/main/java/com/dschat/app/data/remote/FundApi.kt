package com.dschat.app.data.remote

import com.dschat.app.agent.tools.ToolHttp
import com.dschat.app.domain.Quote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import java.net.URLEncoder

/** One hit from the fund name/code search. */
data class FundSearchHit(val code: String, val name: String, val type: String, val nav: Double?)

/**
 * Market data via 天天基金 (eastmoney) public endpoints — free, no key, HTTPS, China-reachable.
 * - 批量净值：fundmobapi … FundMNFInfo（一次拿所有持仓的最新净值 + 当日涨跌幅）
 * - 按名称/代码搜索：fundsuggest … FundSearchAPI
 */
object FundApi {

    private fun get(url: String): String? = try {
        ToolHttp.client.newCall(
            Request.Builder().url(url)
                .header("User-Agent", ToolHttp.UA)
                .header("Referer", "https://fund.eastmoney.com/")
                .build()
        ).execute().use { r ->
            r.body?.string()?.takeIf { r.isSuccessful && it.isNotBlank() }
        }
    } catch (e: Exception) {
        null
    }

    /** Latest NAV + day change for each code. Returns code → Quote (missing codes simply absent). */
    suspend fun fetchQuotes(codes: List<String>): Map<String, Quote> = withContext(Dispatchers.IO) {
        val clean = codes.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (clean.isEmpty()) return@withContext emptyMap()
        val url = "https://fundmobapi.eastmoney.com/FundMNewApi/FundMNFInfo?Fcodes=" +
            clean.joinToString(",") +
            "&plat=Android&appType=ttjj&product=EFund&Version=1&deviceid=1&pageIndex=1&pageSize=${clean.size}"
        val body = get(url) ?: return@withContext emptyMap()
        val datas = runCatching {
            ToolHttp.json.parseToJsonElement(body).jsonObject["Datas"]?.jsonArray
        }.getOrNull() ?: return@withContext emptyMap()
        val out = LinkedHashMap<String, Quote>()
        for (el in datas) {
            val o = el.jsonObject
            val code = o["FCODE"]?.jsonPrimitive?.contentOrNull ?: continue
            val nav = o["NAV"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: continue
            out[code] = Quote(
                code = code,
                name = o["SHORTNAME"]?.jsonPrimitive?.contentOrNull ?: code,
                nav = nav,
                navDate = o["PDATE"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                changePct = o["NAVCHGRT"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0,
                estPct = o["GSZZL"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull(),
            )
        }
        out
    }

    /** Search funds by name or code (basics + latest NAV). Funds only (CATEGORY 700). */
    suspend fun search(key: String): List<FundSearchHit> = withContext(Dispatchers.IO) {
        if (key.isBlank()) return@withContext emptyList()
        val url = "https://fundsuggest.eastmoney.com/FundSearch/api/FundSearchAPI.ashx?m=1&key=" +
            URLEncoder.encode(key.trim(), "UTF-8")
        val body = get(url) ?: return@withContext emptyList()
        val datas = runCatching {
            ToolHttp.json.parseToJsonElement(body).jsonObject["Datas"]?.jsonArray
        }.getOrNull() ?: return@withContext emptyList()
        datas.mapNotNull { el ->
            val o = el.jsonObject
            if (o["CATEGORY"]?.jsonPrimitive?.intOrNull != 700) return@mapNotNull null // 仅基金
            val code = o["CODE"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val name = o["NAME"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val base = o["FundBaseInfo"]?.jsonObject
            FundSearchHit(
                code = code,
                name = name,
                type = base?.get("FTYPE")?.jsonPrimitive?.contentOrNull.orEmpty(),
                nav = base?.get("DWJZ")?.jsonPrimitive?.doubleOrNull,
            )
        }.take(20)
    }
}
