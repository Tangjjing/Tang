package com.dschat.app.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
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

// ---- schema DSL ----
fun objectSchema(vararg props: Pair<String, JsonObject>, required: List<String> = emptyList()): JsonObject =
    buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { props.forEach { (k, v) -> put(k, v) } }
        putJsonArray("required") { required.forEach { add(it) } }
    }

fun strProp(desc: String): JsonObject = buildJsonObject { put("type", "string"); put("description", desc) }
fun intProp(desc: String): JsonObject = buildJsonObject { put("type", "integer"); put("description", desc) }
fun boolProp(desc: String): JsonObject = buildJsonObject { put("type", "boolean"); put("description", desc) }
