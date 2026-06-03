package com.dschat.app.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** How much the agent asks before running tools. */
enum class ExecutionMode(val key: String, val label: String) {
    CONFIRM_ALL("confirm_all", "全部确认"),
    CONFIRM_SIDE_EFFECTS("confirm_side", "仅副作用确认"),
    FULL_AUTO("full_auto", "完全放权");

    companion object {
        fun from(k: String?): ExecutionMode = entries.firstOrNull { it.key == k } ?: CONFIRM_SIDE_EFFECTS
    }
}

/** Web search backend. */
enum class SearchBackend(val key: String, val label: String, val needsKey: Boolean) {
    BING("bing", "Bing（免费·国内可用）", false),
    BOCHA("bocha", "博查 Bocha（需 key·国内）", true),
    TAVILY("tavily", "Tavily（需 key）", true),
    FIRECRAWL("firecrawl", "Firecrawl（需 key）", true),
    DUCKDUCKGO("duckduckgo", "DuckDuckGo（免费·国内多被墙）", false);

    companion object {
        fun from(k: String?): SearchBackend = entries.firstOrNull { it.key == k } ?: BING
    }
}

/** A single agent tool. */
interface Tool {
    val name: String
    val description: String

    /** Whether running this tool has side effects (writes/sends) → gated by confirm mode. */
    val sideEffect: Boolean

    /** JSON schema of the function's "parameters" object (OpenAI function-calling style). */
    fun parameters(): JsonObject

    /** Execute the tool. Return the textual result fed back to the model. Throwing is OK — the
     *  loop turns it into an error result the model can read. */
    suspend fun execute(args: JsonObject): String
}

/** Wraps a tool into one element of the API `tools` array. */
fun Tool.toApiSchema(): JsonObject = buildJsonObject {
    put("type", "function")
    putJsonObject("function") {
        put("name", name)
        put("description", description)
        put("parameters", parameters())
    }
}

// ---- argument extraction helpers ----
fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull ?: ""
fun JsonObject.strOrNull(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
fun JsonObject.intOr(key: String, default: Int): Int = (this[key] as? JsonPrimitive)?.intOrNull ?: default
fun JsonObject.longOr(key: String, default: Long): Long = (this[key] as? JsonPrimitive)?.longOrNull ?: default
fun JsonObject.boolOr(key: String, default: Boolean): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull ?: default
fun JsonObject.doubleOr(key: String, default: Double): Double = (this[key] as? JsonPrimitive)?.doubleOrNull ?: default
/** Nullable double — distinguishes "absent" from 0.0 (needed for lat/lon, which 0,0 is a valid value). */
fun JsonObject.doubleOrNull(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

// ---- schema DSL ----
fun objectSchema(vararg props: Pair<String, JsonObject>, required: List<String> = emptyList()): JsonObject =
    buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { props.forEach { (k, v) -> put(k, v) } }
        putJsonArray("required") { required.forEach { add(it) } }
    }

fun strProp(desc: String): JsonObject = buildJsonObject { put("type", "string"); put("description", desc) }
fun boolProp(desc: String): JsonObject = buildJsonObject { put("type", "boolean"); put("description", desc) }

/** Integer prop. Optional [min]/[max] become advisory JSON-Schema minimum/maximum (not hard-enforced
 *  client-side; the tool still clamps). Defaulted args keep all existing single-arg call sites valid. */
fun intProp(desc: String, min: Int? = null, max: Int? = null): JsonObject = buildJsonObject {
    put("type", "integer"); put("description", desc)
    min?.let { put("minimum", it) }; max?.let { put("maximum", it) }
}

/** Floating-point prop (JSON number). Use for lat/lon, epoch-ms, etc. instead of string+toDouble hacks. */
fun numberProp(desc: String, min: Double? = null, max: Double? = null): JsonObject = buildJsonObject {
    put("type", "number"); put("description", desc)
    min?.let { put("minimum", it) }; max?.let { put("maximum", it) }
}
fun doubleProp(desc: String, min: Double? = null, max: Double? = null): JsonObject = numberProp(desc, min, max)

/** Closed string set → {"type":"string","enum":[…]}. Kept advisory: an off-enum value still executes. */
fun enumProp(desc: String, values: List<String>): JsonObject = buildJsonObject {
    put("type", "string"); put("description", desc)
    putJsonArray("enum") { values.forEach { add(it) } }
}

/** Array prop; [items] is the element schema (defaults to string). */
fun arrayProp(desc: String, items: JsonObject = buildJsonObject { put("type", "string") }): JsonObject =
    buildJsonObject { put("type", "array"); put("description", desc); put("items", items) }

// ---- result-size limits + truncation/pagination helpers (so the model knows when output was capped) ----
/** Centralized caps so they're consistent + tunable in one place. */
object ToolLimits {
    const val HTTP_BODY = 8000
    const val LIST_CAP = 200
    const val FIND_SCAN_CAP = 60000
    const val CONTACTS_CAP = 30
    const val CALENDAR_CAP = 50
    const val FINDAPP_CAP = 80
}

/** Standard "content was cut" marker. */
fun truncMarker(total: Int, unit: String = "字符"): String = "\n…（已截断，共 $total $unit）"
/** Truncate a string with the standard marker appended when it overflows [limit]. */
fun String.capWithMarker(limit: Int): String = if (length > limit) take(limit) + truncMarker(length) else this
/** Note for a capped list: how many shown vs total + an optional paging hint. Empty when nothing was cut. */
fun capNote(shown: Int, total: Int, offsetHint: String = ""): String =
    if (total > shown) "\n（仅显示 $shown 条，共 $total 条$offsetHint）" else ""
