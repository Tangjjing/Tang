package com.dschat.app.agent.tools

import com.dschat.app.agent.Tool
import com.dschat.app.agent.intOr
import com.dschat.app.agent.intProp
import com.dschat.app.agent.objectSchema
import com.dschat.app.agent.str
import com.dschat.app.agent.strOrNull
import com.dschat.app.agent.ToolLimits
import com.dschat.app.agent.arrayProp
import com.dschat.app.agent.capWithMarker
import com.dschat.app.agent.enumProp
import com.dschat.app.agent.strProp
import com.dschat.app.data.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

internal object ToolHttp {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .build()
    val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }
    const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 DeepSeekChat/1.0"
    const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
    // Rotated to reduce the chance of being throttled/blocked by the search HTML endpoints.
    val DESKTOP_UAS = listOf(
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.1 Safari/605.1.15",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:121.0) Gecko/20100101 Firefox/121.0"
    )
    val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
}

/** One parsed search hit, before ranking/formatting. */
internal data class SearchResult(val title: String, val url: String, val snippet: String)

// Low-value domains that pollute Chinese search results: Baidu (user asked to avoid it) and the
// 说文解字/字典/词典 sites a word query keeps getting funneled into. Results on these are skipped.
private val NOISE_HOST_PARTS = listOf(
    "baidu.com", "baidu.cn",
    "zdic.net", "guoxuedashi", "httpcn.com", "911cha", "911查询",
    "shuowen", "kxue", "hanyu", "cidian", "chazidian", "xinhuazidian", "zidiantong"
)
private val NOISE_TITLE_PARTS = listOf(
    "说文解字", "汉典", "新华字典", "康熙字典", "在线字典", "在线词典", "字典在线", "词典在线", "国学大师"
)

private fun isNoiseUrl(url: String?): Boolean {
    if (url.isNullOrBlank()) return false
    val u = url.lowercase()
    return NOISE_HOST_PARTS.any { u.contains(it) }
}

private fun isNoiseTitle(title: String?): Boolean {
    if (title.isNullOrBlank()) return false
    return NOISE_TITLE_PARTS.any { title.contains(it) }
}

// Authoritative / generally-reliable domains get a ranking boost so quality sources surface first.
private val QUALITY_HIGH = listOf(
    "wikipedia.org", "wikimedia", ".gov", "gov.cn", ".edu", "edu.cn", "github.com",
    "stackoverflow.com", "arxiv.org", "nature.com", "who.int", "mozilla.org", "ietf.org",
    "rfc-editor", "developer.", "docs.", "kernel.org", "python.org", "android.com", "apache.org"
)
private val QUALITY_MED = listOf(
    "zhihu.com", "juejin.cn", "segmentfault", "infoq", "36kr.com", "csdn.net", "jianshu.com",
    "microsoft.com", "apple.com", "google.com", "tencent.com", "aliyun.com", "huaweicloud",
    "bbc.", "reuters.com", "npr.org", "theverge.com", "techcrunch.com", "ruanyifeng.com",
    "geeksforgeeks", "medium.com", "ithome.com", "oschina", "stackexchange.com"
)

private fun hostOf(url: String): String = try {
    val u = if (url.startsWith("http")) url else "https://$url"
    URI(u).host?.lowercase() ?: url.lowercase()
} catch (e: Exception) {
    url.lowercase()
}

private fun qualityScore(url: String): Int {
    val h = url.lowercase()
    return when {
        QUALITY_HIGH.any { h.contains(it) } -> 4
        QUALITY_MED.any { h.contains(it) } -> 2
        else -> 0
    }
}

/** Query → match tokens: whitespace-split words (≥2 chars) + CJK character bigrams. */
private fun tokenize(q: String): List<String> {
    val lower = q.lowercase()
    val words = lower.split(Regex("[\\s,，。.、;；:：!！?？\"'()（）\\[\\]【】]+")).filter { it.length >= 2 }
    val grams = ArrayList<String>()
    for (m in Regex("[\\u4e00-\\u9fff]{2,}").findAll(lower)) {
        val s = m.value
        for (i in 0 until s.length - 1) grams.add(s.substring(i, i + 2))
    }
    return (words + grams).distinct()
}

private fun relevance(tokens: List<String>, title: String, snippet: String): Double {
    if (tokens.isEmpty()) return 0.0
    val t = title.lowercase()
    val s = snippet.lowercase()
    var hit = 0.0
    for (tok in tokens) {
        if (t.contains(tok)) hit += 3.0 else if (s.contains(tok)) hit += 1.0
    }
    return hit / tokens.size
}

/** Filter noise → score (relevance + source quality) → dedup → cap 2/host → top [n], formatted. */
private fun rankAndFormat(query: String, results: List<SearchResult>, n: Int): String {
    val tokens = tokenize(query)
    val seenUrl = HashSet<String>()
    val scored = results
        .asSequence()
        .filter { it.title.isNotBlank() && it.url.isNotBlank() }
        .filter { !isNoiseUrl(it.url) && !isNoiseTitle(it.title) }
        .filter { seenUrl.add(it.url) }
        .map { it to (relevance(tokens, it.title, it.snippet) + qualityScore(it.url) * 0.5) }
        .sortedByDescending { it.second }
        .toList()
    val hostCount = HashMap<String, Int>()
    val sb = StringBuilder()
    var i = 0
    for ((r, _) in scored) {
        val h = hostOf(r.url)
        val c = hostCount.getOrDefault(h, 0)
        if (c >= 2) continue
        hostCount[h] = c + 1
        val tag = when {
            qualityScore(r.url) >= 4 -> "（权威来源）"
            qualityScore(r.url) >= 2 -> "（较可信）"
            else -> ""
        }
        sb.append(++i).append(". ").append(r.title).append(tag).append('\n')
            .append(r.url).append('\n').append(r.snippet).append("\n\n")
        if (i >= n) break
    }
    return if (i == 0) "没有找到结果。" else sb.toString().trim()
}

/** Small time-bounded LRU so the same query isn't re-fetched repeatedly within a few minutes. */
private object SearchCache {
    private const val TTL = 5 * 60 * 1000L
    private const val CAP = 32
    private val map = object : LinkedHashMap<String, Pair<Long, String>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, String>>): Boolean = size > CAP
    }

    @Synchronized
    fun get(k: String): String? {
        val e = map[k] ?: return null
        if (System.currentTimeMillis() - e.first > TTL) {
            map.remove(k); return null
        }
        return e.second
    }

    @Synchronized
    fun put(k: String, v: String) {
        map[k] = System.currentTimeMillis() to v
    }
}

/** Backends that reported out-of-quota / auth failure → skipped until this epoch-ms time. */
private val exhausted = ConcurrentHashMap<String, Long>()
private const val QUOTA_COOLDOWN_MS = 2 * 60 * 60 * 1000L // 2 hours

private class SearchQuotaException(message: String) : Exception(message)
/** A 2xx response whose body couldn't be parsed — surfaced to the model instead of looking like "no results". */
private class SearchParseException(message: String) : Exception(message)

private fun beName(be: String): String = when (be) {
    "baidu" -> "百度"
    "bocha" -> "博查"
    "metaso" -> "秘塔"
    else -> be
}

/** Heuristic: does this HTTP status / body indicate a quota / rate / billing / auth limit
 *  (as opposed to a transient network error)? Used to flag a backend exhausted, not just failed. */
private fun isQuotaError(code: Int, body: String): Boolean {
    if (code == 429 || code == 402 || code == 401 || code == 403) return true
    val b = body.lowercase()
    return b.contains("quota") || b.contains("额度") || b.contains("余额") || b.contains("欠费") ||
        b.contains("超限") || b.contains("insufficient") || b.contains("rate limit") ||
        (b.contains("limit") && b.contains("exceed"))
}

private fun originOf(url: String): String = try {
    val u = URI(if (url.startsWith("http")) url else "https://$url")
    "${u.scheme}://${u.host}/"
} catch (e: Exception) {
    "https://www.bing.com/"
}

/** Shared page-body fetcher used by fetch_url + fetch_urls. Sends a full browser-like header set
 *  and ignores HTTP errors / content-type so weak anti-bot pages still return their body (we never
 *  consulted robots.txt — Jsoup doesn't — and this is single-user, low-volume personal use). */
internal fun fetchPageText(url: String, limit: Int): String = try {
    val doc = Jsoup.connect(url)
        .userAgent(ToolHttp.DESKTOP_UA)
        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
        .header("Referer", originOf(url))
        .header("Upgrade-Insecure-Requests", "1")
        .timeout(20000)
        .followRedirects(true)
        .maxBodySize(6 * 1024 * 1024)
        .ignoreHttpErrors(true)
        .ignoreContentType(true)
        .get()
    val title = doc.title()
    val text = doc.body()?.text().orEmpty().ifBlank { doc.text() }
    val out = if (text.length > limit) text.take(limit) + "…（已截断）" else text
    if (out.isBlank()) "（该页面没有可提取的正文，可能是纯前端渲染或需要登录）" else "标题：$title\n$out"
} catch (e: Exception) {
    "（抓取失败：${e.message}）"
}

class WebSearchTool(private val settings: SettingsRepository) : Tool {
    override val name = "web_search"
    override val description = "搜索互联网，返回若干已【按来源质量与相关性自动重排序】的网页（权威/可信来源排在前）。" +
        "⚠️ 返回的只是标题+链接+短摘要，摘要常不完整或含噪音，不能仅凭它下结论：对重要问题，请挑前面 2~3 个结果用 fetch_urls 一次性并行读全文再作答。" +
        "可用 lang 控制检索语言；技术/学术问题建议中英文各搜一次（英文资料更全），中国本土问题只用中文。"
    override val sideEffect = false
    override fun parameters() = objectSchema(
        "query" to strProp("搜索关键词。构造精确、具体的检索词；可用 site:/intitle: 等限定符。不要照搬用户原话。"),
        "lang" to enumProp("检索语言：zh=只中文(中国本土话题)，en=只英文(技术/学术，英文资料更全)，both=中英都搜，auto=按 query 语言自动(默认)。", listOf("zh", "en", "both", "auto")),
        "scope" to enumProp("检索范围：web=综合(默认)；academic=学术/论文(走秘塔学术搜索，适合查文献/研究)。", listOf("web", "academic")),
        "max_results" to intProp("返回结果数量，默认 6，最多 12", 3, 12),
        required = listOf("query")
    )

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        val q = args.str("query")
        if (q.isBlank()) return@withContext "错误：query 不能为空"
        val n = args.intOr("max_results", 6).coerceIn(3, 12)
        val lang = (args.strOrNull("lang") ?: "auto").lowercase()
        val scope = (args.strOrNull("scope") ?: "web").lowercase()
        val cacheKey = "$scope|$lang|$n|$q"
        SearchCache.get(cacheKey)?.let { return@withContext it }

        val keyFor = mapOf(
            "baidu" to settings.searchKeyBaidu.value,
            "bocha" to settings.searchKeyBocha.value,
            "metaso" to settings.searchKeyMetaso.value
        )
        val now = System.currentTimeMillis()
        val pool = 20
        val failures = ArrayList<String>()

        // Backend order: 学术问题优先秘塔；否则以用户自定义的"优先后端"打头，其余按默认顺序补齐。
        val order = if (scope == "academic") listOf("metaso", "baidu", "bocha")
        else (listOf(settings.searchPrimary.value) + listOf("baidu", "bocha", "metaso")).distinct()

        // Sequential, first non-empty wins → 一次搜索只命中一个付费后端，省额度。
        // 额度/鉴权出错的后端会被记下冷却时间，期间直接跳过，避免盲目重试。
        var results: List<SearchResult> = emptyList()
        var used = ""
        for (be in order) {
            val key = keyFor[be].orEmpty()
            if (key.isBlank()) continue
            if ((exhausted[be] ?: 0L) > now) { failures += "${beName(be)}：额度暂不可用，已跳过"; continue }
            try {
                results = when (be) {
                    "baidu" -> baiduSearch(q, pool, key)
                    "bocha" -> bocha(q, pool, key)
                    "metaso" -> metaso(q, pool, key, if (scope == "academic") "scholar" else "webpage")
                    else -> emptyList()
                }
                if (results.isNotEmpty()) { used = beName(be); break }
            } catch (e: SearchQuotaException) {
                failures += "${beName(be)}：${e.message}（额度/鉴权问题，已暂停使用一段时间）"
                exhausted[be] = now + QUOTA_COOLDOWN_MS
            } catch (e: Exception) {
                failures += "${beName(be)}：${e.message}"
            }
        }
        // Free fallbacks only if no keyed backend produced results.
        if (results.isEmpty()) {
            results = runCatching { bingSmart(q, lang, pool) }.getOrElse { failures += "Bing：${it.message}"; emptyList() }
            if (results.isNotEmpty()) used = "Bing"
        }
        if (results.isEmpty()) {
            results = runCatching { duckduckgo(q, pool) }.getOrElse { failures += "DuckDuckGo：${it.message}"; emptyList() }
            if (results.isNotEmpty()) used = "DuckDuckGo"
        }
        android.util.Log.d("WebSearch", "scope=$scope lang=$lang used=$used results=${results.size} q=$q")

        if (results.isEmpty()) {
            return@withContext "网络搜索未能成功：\n" + failures.joinToString("\n") +
                "\n\n说明：可在『设置→Agent→搜索后端』确认 百度/博查/秘塔 的 key 是否有效或额度已用尽；免费 Bing/DuckDuckGo 靠抓 HTML，常因反爬或被墙而失败。"
        }
        val ranked = rankAndFormat(q, results, n)
        if (failures.isEmpty()) SearchCache.put(cacheKey, ranked)
        // Tell the model which backends are down so it doesn't assume everything is fine.
        val note = if (used.isNotBlank() && failures.isNotEmpty())
            "（搜索后端状态：${failures.joinToString("；")}；本次使用 $used）\n\n" else ""
        note + ranked
    }

    /** Bing across the right market(s) for [lang], in parallel, merged. */
    private suspend fun bingSmart(q: String, lang: String, n: Int): List<SearchResult> {
        val cjk = q.count { it.code in 0x4e00..0x9fff }
        val nonSpace = q.count { !it.isWhitespace() }.coerceAtLeast(1)
        val mostlyCjk = cjk * 2 >= nonSpace
        // (market, useInternationalBing)
        val markets: List<Pair<String, Boolean>> = when (lang) {
            "zh" -> listOf("zh-CN" to false)
            "en" -> listOf("en-US" to true)
            "both" -> listOf("zh-CN" to false, "en-US" to true)
            else -> if (mostlyCjk) listOf("zh-CN" to false) else listOf("en-US" to true)
        }
        val lists = coroutineScope {
            markets.map { (mkt, intl) ->
                async { runCatching { bingFetch(q, mkt, intl, n) }.getOrDefault(emptyList()) }
            }.awaitAll()
        }
        return lists.flatten()
    }

    private fun bingFetch(q: String, mkt: String, intl: Boolean, n: Int): List<SearchResult> {
        val base = if (intl) "https://www.bing.com/search" else "https://cn.bing.com/search"
        val conn = Jsoup.connect(base)
            .data("q", q)
            .data("setlang", if (mkt == "en-US") "en" else "zh-CN")
            .data("mkt", mkt)
        if (intl) conn.data("ensearch", "1")
        val doc = conn
            .userAgent(ToolHttp.DESKTOP_UAS[q.length % ToolHttp.DESKTOP_UAS.size])
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", if (mkt == "en-US") "en-US,en;q=0.9" else "zh-CN,zh;q=0.9,en;q=0.8")
            .followRedirects(true).ignoreHttpErrors(true).timeout(15000).get()
        val out = ArrayList<SearchResult>()
        for (r in doc.select("li.b_algo")) {
            val a = r.selectFirst("h2 a") ?: continue
            val title = a.text()
            if (title.isBlank()) continue
            val href = a.attr("href")
            val url = r.selectFirst("cite")?.text()?.ifBlank { null } ?: href
            val snippet = (r.selectFirst("div.b_caption p") ?: r.selectFirst("p"))?.text().orEmpty()
            out.add(SearchResult(title, url, snippet))
            if (out.size >= n) break
        }
        return out
    }

    private fun bocha(q: String, n: Int, key: String): List<SearchResult> {
        if (key.isBlank()) return emptyList()
        val payload = buildJsonObject { put("query", q); put("count", n); put("summary", true) }
        val req = Request.Builder().url(settings.searchUrlBocha.value)
            .addHeader("Authorization", "Bearer $key")
            .post(payload.toString().toRequestBody(ToolHttp.JSON_MEDIA)).build()
        ToolHttp.client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                if (isQuotaError(resp.code, body)) throw SearchQuotaException("HTTP ${resp.code}")
                return emptyList()
            }
            val root = runCatching { ToolHttp.json.parseToJsonElement(body).jsonObject }.getOrNull()
                ?: throw SearchParseException("响应不是合法 JSON")
            val arr = root["data"]?.jsonObject?.get("webPages")?.jsonObject?.get("value")?.jsonArray ?: return emptyList()
            return arr.mapNotNull { e ->
                val o = e.jsonObject
                val url = o["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                SearchResult(
                    o["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    url,
                    (o["summary"] ?: o["snippet"])?.jsonPrimitive?.contentOrNull?.take(400).orEmpty()
                )
            }
        }
    }

    /** Baidu (Qianfan) AI Search — returns pure references (token-free). Daily-quota priority backend. */
    private fun baiduSearch(q: String, n: Int, key: String): List<SearchResult> {
        if (key.isBlank()) return emptyList()
        val payload = buildJsonObject {
            putJsonArray("messages") { add(buildJsonObject { put("role", "user"); put("content", q) }) }
            putJsonArray("resource_type_filter") { add(buildJsonObject { put("type", "web"); put("top_k", n) }) }
        }
        val req = Request.Builder().url(settings.searchUrlBaidu.value)
            .addHeader("Authorization", "Bearer $key")
            .post(payload.toString().toRequestBody(ToolHttp.JSON_MEDIA)).build()
        ToolHttp.client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                if (isQuotaError(resp.code, body)) throw SearchQuotaException("HTTP ${resp.code}")
                return emptyList()
            }
            val root = runCatching { ToolHttp.json.parseToJsonElement(body).jsonObject }.getOrNull()
                ?: throw SearchParseException("响应不是合法 JSON")
            val arr = root["references"]?.jsonArray ?: return emptyList()
            return arr.mapNotNull { e ->
                val o = e.jsonObject
                val url = o["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                SearchResult(
                    o["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    url,
                    o["content"]?.jsonPrimitive?.contentOrNull?.take(400).orEmpty()
                )
            }
        }
    }

    /** Metaso (秘塔) search — scope "webpage" (general) or "scholar" (academic/papers). */
    private fun metaso(q: String, n: Int, key: String, scope: String): List<SearchResult> {
        if (key.isBlank()) return emptyList()
        val payload = buildJsonObject {
            put("q", q); put("scope", scope); put("size", n.toString()); put("includeSummary", true)
        }
        val req = Request.Builder().url(settings.searchUrlMetaso.value)
            .addHeader("Authorization", "Bearer $key")
            .post(payload.toString().toRequestBody(ToolHttp.JSON_MEDIA)).build()
        ToolHttp.client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                if (isQuotaError(resp.code, body)) throw SearchQuotaException("HTTP ${resp.code}")
                return emptyList()
            }
            val root = runCatching { ToolHttp.json.parseToJsonElement(body).jsonObject }.getOrNull()
                ?: throw SearchParseException("响应不是合法 JSON")
            val arr = (root["webpages"] ?: root["scholar"] ?: root["documents"])?.jsonArray ?: return emptyList()
            return arr.mapNotNull { e ->
                val o = e.jsonObject
                val url = (o["link"] ?: o["url"])?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                SearchResult(
                    o["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    url,
                    (o["summary"] ?: o["snippet"] ?: o["content"])?.jsonPrimitive?.contentOrNull?.take(400).orEmpty()
                )
            }
        }
    }

    private fun duckduckgo(q: String, n: Int): List<SearchResult> {
        val doc = Jsoup.connect("https://html.duckduckgo.com/html/")
            .data("q", q).userAgent(ToolHttp.UA).timeout(15000).get()
        val out = ArrayList<SearchResult>()
        for (r in doc.select("div.result")) {
            val a = r.selectFirst("a.result__a") ?: continue
            val url = decodeDdg(a.attr("href"))
            val snippet = r.selectFirst(".result__snippet")?.text().orEmpty()
            out.add(SearchResult(a.text(), url, snippet))
            if (out.size >= n) break
        }
        return out
    }

    private fun decodeDdg(href: String): String {
        val idx = href.indexOf("uddg=")
        if (idx < 0) return if (href.startsWith("//")) "https:$href" else href
        val enc = href.substring(idx + 5).substringBefore("&")
        return try {
            URLDecoder.decode(enc, "UTF-8")
        } catch (e: Exception) {
            href
        }
    }
}

class FetchUrlTool : Tool {
    override val name = "fetch_url"
    override val description = "抓取一个网页并返回其正文文本（已去除 HTML 标签）。用于深入阅读搜索结果里的具体页面，提取数字、日期、结论等关键事实——重要信息应当用它读全文，而不是只看搜索摘要。要读多个页面时改用 fetch_urls（并行更快）。"
    override val sideEffect = false
    override fun parameters() = objectSchema(
        "url" to strProp("要抓取的网页 URL"),
        "max_chars" to intProp("返回正文最大字符数，默认 6000", 500, 20000),
        required = listOf("url")
    )

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        val url = args.str("url")
        if (url.isBlank()) return@withContext "错误：url 不能为空"
        val limit = args.intOr("max_chars", 6000).coerceIn(500, 20000)
        "【以下是网页正文，请从中提取与用户问题相关的关键信息（数字、日期、结论、来源），忽略导航/广告/无关内容】\n" +
            fetchPageText(url, limit)
    }
}

class FetchUrlsTool : Tool {
    override val name = "fetch_urls"
    override val description = "并行抓取多个网页的正文文本（一次传入多个 URL），用于同时读取搜索排名靠前的几篇资料，避免逐个抓取的长时间等待。最多 5 个。"
    override val sideEffect = false
    override fun parameters() = objectSchema(
        "urls" to arrayProp("要抓取的网页 URL 列表（最多 5 个，通常取搜索结果里排名靠前的 2~3 个）"),
        "max_chars" to intProp("每个网页返回正文最大字符数，默认 4000", 500, 12000),
        required = listOf("urls")
    )

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        val urls = parseUrls(args["urls"]).take(5)
        if (urls.isEmpty()) return@withContext "错误：urls 不能为空"
        val limit = args.intOr("max_chars", 4000).coerceIn(500, 12000)
        val pages = coroutineScope {
            urls.map { u -> async { u to fetchPageText(u, limit) } }.awaitAll()
        }
        "【以下是多篇网页的正文，请综合提取与用户问题相关的关键信息】\n\n" +
            pages.joinToString("\n\n———\n\n") { (u, t) -> "【来源：$u】\n$t" }
    }

    private fun parseUrls(el: kotlinx.serialization.json.JsonElement?): List<String> = when (el) {
        is JsonArray -> el.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.ifBlank { null } }
        is JsonPrimitive -> {
            val s = el.contentOrNull.orEmpty()
            runCatching {
                ToolHttp.json.parseToJsonElement(s).jsonArray.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            }.getOrElse {
                s.split(",", "\n", " ").map { it.trim() }.filter { it.startsWith("http") }
            }
        }
        else -> emptyList()
    }
}

class HttpRequestTool : Tool {
    override val name = "http_request"
    override val description = "向任意 URL 发送 HTTP 请求，返回状态码与响应正文（截断到 8000 字）。method 支持 GET/POST/PUT/PATCH/DELETE，默认 GET；约 40 秒超时。可用于调用公开 API。"
    override val sideEffect = true
    override fun parameters() = objectSchema(
        "url" to strProp("请求 URL"),
        "method" to enumProp("HTTP 方法，默认 GET", listOf("GET", "POST", "PUT", "PATCH", "DELETE")),
        "headers" to strProp("可选，请求头的 JSON 字符串，如 {\"X-Key\":\"v\"}"),
        "body" to strProp("可选，请求体（字符串）"),
        required = listOf("url")
    )

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        val url = args.str("url")
        if (url.isBlank()) return@withContext "错误：url 不能为空"
        val method = args.strOrNull("method")?.uppercase() ?: "GET"
        val bodyStr = args.strOrNull("body")
        try {
            val builder = Request.Builder().url(url)
            args.strOrNull("headers")?.let { h ->
                try {
                    ToolHttp.json.parseToJsonElement(h).jsonObject.forEach { (k, v) ->
                        builder.addHeader(k, v.jsonPrimitive.contentOrNull ?: "")
                    }
                } catch (_: Exception) { /* ignore bad headers */ }
            }
            val reqBody = bodyStr?.toRequestBody(ToolHttp.JSON_MEDIA)
            builder.method(method, reqBody ?: if (method in setOf("POST", "PUT", "PATCH", "DELETE")) "".toRequestBody() else null)
            ToolHttp.client.newCall(builder.build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                "HTTP ${resp.code}\n" + text.capWithMarker(ToolLimits.HTTP_BODY)
            }
        } catch (e: Exception) {
            "请求失败：${e.message}"
        }
    }
}
