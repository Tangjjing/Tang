package com.dschat.app.data.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.dschat.app.agent.ExecutionMode
import com.dschat.app.agent.SearchBackend
import com.dschat.app.BuildConfig
import com.dschat.app.domain.ChatModel
import com.dschat.app.domain.DEFAULT_MODELS
import com.dschat.app.domain.MemoryItem
import com.dschat.app.domain.MemoryOp
import com.dschat.app.domain.MemoryOpResult
import com.dschat.app.domain.UsageStat
import com.dschat.app.domain.providerFromBaseUrl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Theme preference. */
enum class ThemeMode(val key: String) {
    SYSTEM("system"), LIGHT("light"), DARK("dark");

    companion object {
        fun from(key: String?): ThemeMode = entries.firstOrNull { it.key == key } ?: SYSTEM
    }
}

/**
 * All user settings, persisted with EncryptedSharedPreferences (the API key is stored encrypted
 * at rest using the Android Keystore). Reactive values are exposed as StateFlows.
 */
class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "ds_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        // Rare Keystore failures on some devices — fall back to plain prefs so the app still runs.
        context.getSharedPreferences("ds_prefs_fallback", Context.MODE_PRIVATE)
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val modelsSerializer = ListSerializer(ChatModel.serializer())
    private val memSerializer = ListSerializer(MemoryItem.serializer())
    private val usageSerializer = MapSerializer(String.serializer(), UsageStat.serializer())

    // --- API connection ---

    private val _apiKey = MutableStateFlow(prefs.getString(KEY_API, "").orEmpty())
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()

    private val _baseUrl =
        MutableStateFlow(prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL).orEmpty().ifBlank { DEFAULT_BASE_URL })
    val baseUrl: StateFlow<String> = _baseUrl.asStateFlow()

    private val _temperature = MutableStateFlow(prefs.getFloat(KEY_TEMP, 1.0f))
    val temperature: StateFlow<Float> = _temperature.asStateFlow()

    private val _systemPrompt = MutableStateFlow(prefs.getString(KEY_SYS, "").orEmpty())
    val systemPrompt: StateFlow<String> = _systemPrompt.asStateFlow()

    private val _theme = MutableStateFlow(ThemeMode.from(prefs.getString(KEY_THEME, null)))
    val theme: StateFlow<ThemeMode> = _theme.asStateFlow()

    // --- Models (editable list) ---

    private val _models = MutableStateFlow(loadModels())
    val models: StateFlow<List<ChatModel>> = _models.asStateFlow()

    private val _selectedModelId = MutableStateFlow(
        prefs.getString(KEY_SEL_MODEL, null) ?: _models.value.firstOrNull()?.id ?: DEFAULT_MODELS.first().id
    )
    val selectedModelId: StateFlow<String> = _selectedModelId.asStateFlow()

    // --- Memories ---

    private val _memories = MutableStateFlow(loadMemories())
    val memories: StateFlow<List<MemoryItem>> = _memories.asStateFlow()

    // --- Agent ---

    private val _agentEnabled = MutableStateFlow(prefs.getBoolean(KEY_AGENT, false))
    val agentEnabled: StateFlow<Boolean> = _agentEnabled.asStateFlow()

    private val _executionMode = MutableStateFlow(ExecutionMode.from(prefs.getString(KEY_EXEC, null)))
    val executionMode: StateFlow<ExecutionMode> = _executionMode.asStateFlow()

    private val _agentUseProModel = MutableStateFlow(prefs.getBoolean(KEY_AGENT_PRO, true))
    val agentUseProModel: StateFlow<Boolean> = _agentUseProModel.asStateFlow()

    // --- Ambient assistant (notification → task) ---

    private val _ambientEnabled = MutableStateFlow(prefs.getBoolean(KEY_AMBIENT, false))
    val ambientEnabled: StateFlow<Boolean> = _ambientEnabled.asStateFlow()

    // Auto-schedule: silently add calendar event + reminder for timed appointments in watched messages.
    private val _autoScheduleEnabled = MutableStateFlow(prefs.getBoolean(KEY_AUTO_SCHEDULE, false))
    val autoScheduleEnabled: StateFlow<Boolean> = _autoScheduleEnabled.asStateFlow()

    // Auto-memory: after each chat turn, a cheap model extracts durable facts about the user. Default ON.
    private val _autoMemoryEnabled = MutableStateFlow(prefs.getBoolean(KEY_AUTO_MEMORY, true))
    val autoMemoryEnabled: StateFlow<Boolean> = _autoMemoryEnabled.asStateFlow()

    private val _watchedApps = MutableStateFlow(
        prefs.getString(KEY_WATCHED, null)?.split("\n")?.filter { it.isNotBlank() }?.toSet()
            ?: setOf("com.tencent.mm")
    )
    val watchedApps: StateFlow<Set<String>> = _watchedApps.asStateFlow()

    private val _watchScreenshots = MutableStateFlow(prefs.getBoolean(KEY_SCREENSHOTS, false))
    val watchScreenshots: StateFlow<Boolean> = _watchScreenshots.asStateFlow()

    // --- Auto-reply assistant (per-contact) ---

    private val _autoReplyEnabled = MutableStateFlow(prefs.getBoolean(KEY_AUTOREPLY, false))
    val autoReplyEnabled: StateFlow<Boolean> = _autoReplyEnabled.asStateFlow()

    private val _autoReplyContacts = MutableStateFlow(
        prefs.getString(KEY_AUTOREPLY_CONTACTS, null)?.split("\n")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
    )
    val autoReplyContacts: StateFlow<Set<String>> = _autoReplyContacts.asStateFlow()

    // --- Usage / token accounting (per model) ---

    private val _usageStats = MutableStateFlow(loadUsage())
    val usageStats: StateFlow<Map<String, UsageStat>> = _usageStats.asStateFlow()

    private val _searchBackend = MutableStateFlow(SearchBackend.from(prefs.getString(KEY_SEARCH_BE, null)))
    val searchBackend: StateFlow<SearchBackend> = _searchBackend.asStateFlow()

    private val _searchApiKey = MutableStateFlow(prefs.getString(KEY_SEARCH_KEY, "").orEmpty())
    val searchApiKey: StateFlow<String> = _searchApiKey.asStateFlow()

    // Dedicated AI-search API keys (used to power web_search; NOT chat models). Priority chain:
    // 百度(每日100) → 博查(1000总) → 秘塔(学术/备用) → 免费 Bing。
    private val _searchKeyBaidu = MutableStateFlow(prefs.getString(KEY_SEARCH_BAIDU, "").orEmpty().ifBlank { BuildConfig.SEARCH_KEY_BAIDU })
    val searchKeyBaidu: StateFlow<String> = _searchKeyBaidu.asStateFlow()
    private val _searchKeyBocha = MutableStateFlow(prefs.getString(KEY_SEARCH_BOCHA, "").orEmpty().ifBlank { BuildConfig.SEARCH_KEY_BOCHA })
    val searchKeyBocha: StateFlow<String> = _searchKeyBocha.asStateFlow()
    private val _searchKeyMetaso = MutableStateFlow(prefs.getString(KEY_SEARCH_METASO, "").orEmpty().ifBlank { BuildConfig.SEARCH_KEY_METASO })
    val searchKeyMetaso: StateFlow<String> = _searchKeyMetaso.asStateFlow()

    // Editable per-backend search endpoint URLs (so providers/endpoints can be swapped later).
    private val _searchUrlBaidu = MutableStateFlow(prefs.getString(KEY_SEARCH_URL_BAIDU, "").orEmpty().ifBlank { DEFAULT_SEARCH_URL_BAIDU })
    val searchUrlBaidu: StateFlow<String> = _searchUrlBaidu.asStateFlow()
    private val _searchUrlBocha = MutableStateFlow(prefs.getString(KEY_SEARCH_URL_BOCHA, "").orEmpty().ifBlank { DEFAULT_SEARCH_URL_BOCHA })
    val searchUrlBocha: StateFlow<String> = _searchUrlBocha.asStateFlow()
    private val _searchUrlMetaso = MutableStateFlow(prefs.getString(KEY_SEARCH_URL_METASO, "").orEmpty().ifBlank { DEFAULT_SEARCH_URL_METASO })
    val searchUrlMetaso: StateFlow<String> = _searchUrlMetaso.asStateFlow()

    // Which AI-search backend to try first (baidu/bocha/metaso); the rest follow in default order.
    private val _searchPrimary = MutableStateFlow(prefs.getString(KEY_SEARCH_PRIMARY, "baidu").orEmpty().ifBlank { "baidu" })
    val searchPrimary: StateFlow<String> = _searchPrimary.asStateFlow()

    // ---- Weather (get_weather tool + proactive monitor) ----
    private val _qweatherKey = MutableStateFlow(prefs.getString(KEY_QWEATHER_KEY, "").orEmpty().ifBlank { BuildConfig.QWEATHER_KEY })
    val qweatherKey: StateFlow<String> = _qweatherKey.asStateFlow()
    private val _qweatherHost = MutableStateFlow(prefs.getString(KEY_QWEATHER_HOST, "").orEmpty().ifBlank { BuildConfig.QWEATHER_HOST }.ifBlank { "devapi.qweather.com" })
    val qweatherHost: StateFlow<String> = _qweatherHost.asStateFlow()
    private val _weatherCity = MutableStateFlow(prefs.getString(KEY_WEATHER_CITY, "").orEmpty())
    val weatherCity: StateFlow<String> = _weatherCity.asStateFlow()
    private val _weatherMonitorEnabled = MutableStateFlow(prefs.getBoolean(KEY_WEATHER_MONITOR, false))
    val weatherMonitorEnabled: StateFlow<Boolean> = _weatherMonitorEnabled.asStateFlow()
    private val _weatherMorningHour = MutableStateFlow(prefs.getInt(KEY_WEATHER_HOUR, 7))
    val weatherMorningHour: StateFlow<Int> = _weatherMorningHour.asStateFlow()

    private val _disabledTools = MutableStateFlow(
        prefs.getString(KEY_DISABLED_TOOLS, "").orEmpty()
            .split("\n").filter { it.isNotBlank() }.toSet()
    )
    val disabledTools: StateFlow<Set<String>> = _disabledTools.asStateFlow()

    // --- setters ---

    fun setApiKey(value: String) {
        _apiKey.value = value.trim()
        prefs.edit().putString(KEY_API, value.trim()).apply()
    }

    fun setBaseUrl(value: String) {
        val v = value.trim().ifBlank { DEFAULT_BASE_URL }
        _baseUrl.value = v
        prefs.edit().putString(KEY_BASE_URL, v).apply()
    }

    fun setTemperature(value: Float) {
        _temperature.value = value
        prefs.edit().putFloat(KEY_TEMP, value).apply()
    }

    fun setSystemPrompt(value: String) {
        _systemPrompt.value = value
        prefs.edit().putString(KEY_SYS, value).apply()
    }

    fun setTheme(mode: ThemeMode) {
        _theme.value = mode
        prefs.edit().putString(KEY_THEME, mode.key).apply()
    }

    fun hasApiKey(): Boolean = _apiKey.value.isNotBlank()

    // --- model ops ---

    fun selectModel(id: String) {
        _selectedModelId.value = id
        prefs.edit().putString(KEY_SEL_MODEL, id).apply()
    }

    fun currentModel(): ChatModel =
        _models.value.firstOrNull { it.id == _selectedModelId.value }
            ?: _models.value.firstOrNull()
            ?: DEFAULT_MODELS.first()

    /** Resolves (apiKey, baseUrl) for a model id: the model's own override if set, else the global. */
    fun credsFor(modelId: String): Pair<String, String> {
        val m = _models.value.firstOrNull { it.id == modelId }
        val key = m?.apiKey?.takeIf { it.isNotBlank() }.orEmpty()
        val url = m?.baseUrl?.takeIf { it.isNotBlank() } ?: DEFAULT_BASE_URL
        return key to url
    }

    fun hasKeyFor(modelId: String): Boolean = credsFor(modelId).first.isNotBlank()

    /** Adds a model (or updates display/flags if the id already exists). */
    fun upsertModel(model: ChatModel) {
        val list = _models.value.toMutableList()
        val idx = list.indexOfFirst { it.id == model.id }
        if (idx >= 0) list[idx] = model else list.add(model)
        saveModels(list)
    }

    fun deleteModel(id: String) {
        val list = _models.value.filterNot { it.id == id }
        if (list.isNotEmpty()) saveModels(list)
        if (_selectedModelId.value == id) selectModel(list.firstOrNull()?.id ?: return)
    }

    /** Merge freshly fetched model ids into the list (keeps existing display names). Returns how many were new. */
    fun mergeFetchedModels(ids: List<String>): Int {
        val existing = _models.value.associateBy { it.id }
        val merged = _models.value.toMutableList()
        var added = 0
        ids.forEach { id ->
            if (!existing.containsKey(id)) {
                merged.add(ChatModel(id, id, reasoning = id.contains("reason", true) || id.contains("think", true)))
                added++
            }
        }
        if (added > 0) saveModels(merged)
        return added
    }

    private fun saveModels(list: List<ChatModel>) {
        _models.value = list
        prefs.edit().putString(KEY_MODELS, json.encodeToString(modelsSerializer, list)).apply()
    }

    private fun loadModels(): List<ChatModel> {
        val stored = try {
            prefs.getString(KEY_MODELS, null)?.let { json.decodeFromString(modelsSerializer, it) }
        } catch (e: Exception) {
            null
        }
        // Fall back to defaults if there's nothing stored, but STILL run the flatten migration below
        // so the existing global key gets carried onto the DeepSeek models.
        var list: List<ChatModel> = if (stored.isNullOrEmpty()) DEFAULT_MODELS else stored
        var changed = stored.isNullOrEmpty()

        // v2 cleanup: drop deprecated DeepSeek ids, then add any missing new defaults.
        if (!stored.isNullOrEmpty() && !prefs.getBoolean(KEY_MODELS_MIGRATED, false)) {
            val legacy = setOf(
                "deepseek-chat", "deepseek-reasoner", "deepseek-coder",
                "deepseek-v3", "deepseek-v3.1", "deepseek-r1", "deepseek-v2", "deepseek-vl"
            )
            val cleaned = list.filterNot { it.id in legacy }.toMutableList()
            val ids = cleaned.mapTo(HashSet()) { it.id }
            DEFAULT_MODELS.forEach { if (it.id !in ids) { cleaned.add(it); ids.add(it.id) } }
            list = cleaned.ifEmpty { DEFAULT_MODELS }
            prefs.edit().putBoolean(KEY_MODELS_MIGRATED, true).apply()
            changed = true
            val sel = prefs.getString(KEY_SEL_MODEL, null)
            if (sel != null && list.none { it.id == sel }) {
                prefs.edit().putString(KEY_SEL_MODEL, list.first().id).apply()
            }
        }

        // flatten: there is no more global key — move the old global key/url onto DeepSeek models
        // (those without their own creds), so existing setups keep working after the change.
        if (!prefs.getBoolean(KEY_FLATTEN_MIGRATED, false)) {
            val gKey = _apiKey.value
            val gUrl = _baseUrl.value.ifBlank { DEFAULT_BASE_URL }
            if (gKey.isNotBlank()) {
                list = list.map { m ->
                    if (m.apiKey.isNullOrBlank() && (m.baseUrl.isNullOrBlank() || m.baseUrl.contains("deepseek"))) {
                        m.copy(apiKey = gKey, baseUrl = m.baseUrl?.takeIf { it.isNotBlank() } ?: gUrl)
                    } else m
                }
                changed = true
            }
            prefs.edit().putBoolean(KEY_FLATTEN_MIGRATED, true).apply()
        }

        // Tag each model with a provider label (for the provider → model picker).
        if (!prefs.getBoolean(KEY_PROVIDER_MIGRATED, false)) {
            list = list.map { if (it.provider.isBlank()) it.copy(provider = providerFromBaseUrl(it.baseUrl)) else it }
            prefs.edit().putBoolean(KEY_PROVIDER_MIGRATED, true).apply()
            changed = true
        }

        // One-time: the AI-search API providers (百度千帆 Qianfan / 通义千问 …) were added to the model
        // list by mistake — those keys power web_search, they are NOT chat LLMs. Keep only the chat
        // providers the user wants (DeepSeek / 智谱 / Kimi / Claude) AND make sure DeepSeek is present.
        if (!prefs.getBoolean(KEY_TRIM_PROVIDERS, false)) {
            val before = list.size
            val keepProvider = setOf("DeepSeek", "智谱", "Kimi", "OpenRouter")
            val keepDomain = listOf("deepseek", "moonshot", "bigmodel", "zhipu", "openrouter")
            val kept = list.filter { m ->
                m.provider in keepProvider || (m.baseUrl?.let { u -> keepDomain.any { u.contains(it) } } == true)
            }.toMutableList()
            // Restore the DeepSeek defaults if they were dropped, carrying the existing global key so
            // they work out of the box.
            val keptIds = kept.mapTo(HashSet()) { it.id }
            val gKey = _apiKey.value
            var readdedDeepseek = false
            DEFAULT_MODELS.filter { it.provider == "DeepSeek" && it.id !in keptIds }.forEach { d ->
                kept.add(if (gKey.isNotBlank() && d.apiKey.isNullOrBlank()) d.copy(apiKey = gKey) else d)
                keptIds.add(d.id); readdedDeepseek = true
            }
            list = kept.ifEmpty { DEFAULT_MODELS }
            prefs.edit().putBoolean(KEY_TRIM_PROVIDERS, true).apply()
            if (list.size != before || readdedDeepseek) {
                changed = true
                val sel = prefs.getString(KEY_SEL_MODEL, null)
                val newSel = when {
                    readdedDeepseek -> "deepseek-v4-flash"          // restore DeepSeek as the active pick
                    sel != null && list.none { it.id == sel } -> list.first().id
                    else -> null
                }
                if (newSel != null) prefs.edit().putString(KEY_SEL_MODEL, newSel).apply()
            }
        }

        if (changed) {
            prefs.edit().putString(KEY_MODELS, json.encodeToString(modelsSerializer, list)).apply()
        }
        return list.ifEmpty { DEFAULT_MODELS }
    }

    /** Bulk add/update models for a provider (e.g. after fetching its /models list). Returns # added. */
    fun addProviderModels(provider: String, baseUrl: String, apiKey: String, ids: List<String>): Int {
        val merged = _models.value.toMutableList()
        val byId = merged.associateBy { it.id }
        var added = 0
        ids.forEach { id ->
            val old = byId[id]
            val m = ChatModel(
                id = id,
                displayName = old?.displayName ?: id,
                reasoning = old?.reasoning ?: (id.contains("reason", true) || id.contains("think", true) || id.contains("r1", true)),
                baseUrl = baseUrl.trim().ifBlank { null },
                apiKey = apiKey.trim().ifBlank { null },
                vision = old?.vision ?: (id.contains("vl", true) || id.contains("vision", true) || id.contains("4v", true)),
                provider = provider.ifBlank { providerFromBaseUrl(baseUrl) }
            )
            val idx = merged.indexOfFirst { it.id == id }
            if (idx >= 0) merged[idx] = m else { merged.add(m); added++ }
        }
        saveModels(merged)
        return added
    }

    // --- memory ops ---

    fun addMemory(title: String, content: String, auto: Boolean = false, category: String = ""): Long {
        val id = (_memories.value.maxOfOrNull { it.id } ?: 0L) + 1L
        val now = System.currentTimeMillis()
        saveMemories(_memories.value + MemoryItem(id, title.trim(), content.trim(), enabled = true, auto = auto, category = category.trim(), createdAt = now, updatedAt = now, lastReferencedAt = now))
        return id
    }

    fun updateMemory(item: MemoryItem) {
        val now = System.currentTimeMillis()
        saveMemories(_memories.value.map { if (it.id == item.id) item.copy(title = item.title.trim(), content = item.content.trim(), updatedAt = now) else it })
    }

    fun deleteMemory(id: Long) {
        saveMemories(_memories.value.filterNot { it.id == id })
    }

    /** Delete memories matching [query]: an exact id (numeric), else a keyword in title/content.
     *  Returns the labels of deleted entries (empty if nothing matched). Used by the forget_memory tool. */
    fun forgetMemory(query: String): List<String> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        val asId = q.toLongOrNull()
        val victims = _memories.value.filter { m ->
            if (asId != null) m.id == asId
            else m.title.contains(q, ignoreCase = true) || m.content.contains(q, ignoreCase = true)
        }
        if (victims.isEmpty()) return emptyList()
        val victimIds = victims.mapTo(HashSet()) { it.id }
        saveMemories(_memories.value.filterNot { it.id in victimIds })
        return victims.map { it.title.ifBlank { it.content.take(12) } }
    }

    fun setMemoryEnabled(id: Long, enabled: Boolean) {
        saveMemories(_memories.value.map { if (it.id == id) it.copy(enabled = enabled) else it })
    }

    fun getMemory(id: Long): MemoryItem? = _memories.value.firstOrNull { it.id == id }

    /** Builds the text block of all enabled memories, grouped by [类别] tags, injected into context. */
    fun enabledMemoriesText(): String {
        val active = _memories.value.filter { it.enabled && it.content.isNotBlank() }
        if (active.isEmpty()) return ""
        val byCat = active.groupBy { it.category.trim().ifBlank { "其它" } }
        return buildString {
            append("以下是关于用户的长期记忆（按类别分组），请在回答时酌情参考（与当前问题无关时可忽略）：")
            byCat.forEach { (cat, items) ->
                append("\n\n[").append(cat).append("]")
                items.forEach { append("\n- ").append(it.content.trim()) }
            }
        }
    }

    /** Apply the memory extractor's ops (add/update/skip) atomically: dedup adds, apply updates,
     *  then enforce count/char caps by pruning the oldest AUTO entries. Returns what changed. */
    fun applyMemoryOps(ops: List<MemoryOp>): MemoryOpResult {
        val added = mutableListOf<String>()
        val updated = mutableListOf<String>()
        val now = System.currentTimeMillis()
        var list = _memories.value.toMutableList()
        var nextId = (list.maxOfOrNull { it.id } ?: 0L) + 1L

        for (op in ops) {
            val content = op.content.trim()
            if (content.isBlank()) continue
            when (op.op.lowercase()) {
                "update" -> {
                    val idx = list.indexOfFirst { it.id == op.id }
                    if (idx >= 0) {
                        list[idx] = list[idx].copy(
                            title = op.title.trim().ifBlank { list[idx].title },
                            content = content,
                            category = op.category.trim().ifBlank { list[idx].category }.ifBlank { inferCategory(op.title + " " + content) },
                            updatedAt = now, lastReferencedAt = now
                        )
                        updated += list[idx].title
                    } else if (!isDuplicate(list, content)) { // bad id from the model → treat as add
                        list.add(MemoryItem(nextId++, op.title.trim(), content, enabled = true, auto = true, category = op.category.trim().ifBlank { inferCategory(op.title + " " + content) }, createdAt = now, updatedAt = now, lastReferencedAt = now))
                        added += op.title.trim()
                    }
                }
                "add" -> {
                    if (isDuplicate(list, content)) continue
                    list.add(MemoryItem(nextId++, op.title.trim(), content, enabled = true, auto = true, createdAt = now, updatedAt = now))
                    added += op.title.trim()
                }
                else -> { /* skip */ }
            }
        }

        if (added.isEmpty() && updated.isEmpty()) return MemoryOpResult()
        list = enforceCaps(list)
        saveMemories(list)
        return MemoryOpResult(added, updated)
    }

    /** Cheap normalized-substring dedup: a safety net on top of the model's own dedup. */
    private fun isDuplicate(list: List<MemoryItem>, content: String): Boolean {
        val norm = content.normalizeForDedup()
        if (norm.isEmpty()) return true
        return list.any { existing ->
            val e = existing.content.normalizeForDedup()
            e == norm || (norm.length >= 8 && (e.contains(norm) || norm.contains(e)))
        }
    }

    private fun String.normalizeForDedup(): String =
        trim().lowercase().replace(Regex("[\\s，。,.;；！!？?、]"), "")

    /** Cap total count AND total injected chars; prune oldest AUTO entries first. Never auto-deletes
     *  a manually-created memory. */
    private fun enforceCaps(list: MutableList<MemoryItem>): MutableList<MemoryItem> {
        // Prune the least-recently-ACTIVE auto memory (created/updated/referenced), not just the oldest —
        // so a frequently-relevant fact outlives a one-off that was captured once and never used again.
        while (list.size > MAX_MEMORIES) {
            val victim = list.filter { it.auto }.minByOrNull { recencyOf(it) } ?: break
            list.remove(victim)
        }
        while (injectedChars(list) > MAX_MEMORY_CHARS) {
            val victim = list.filter { it.auto && it.enabled }.minByOrNull { recencyOf(it) } ?: break
            list.remove(victim)
        }
        return list
    }

    private fun recencyOf(m: MemoryItem): Long = maxOf(m.createdAt, m.updatedAt, m.lastReferencedAt)

    /** Best-effort category when the extractor didn't supply one (keeps auto-memories out of 其它). */
    private fun inferCategory(text: String): String {
        val t = text.lowercase()
        return when {
            listOf("过敏", "忌口", "不吃", "爱吃", "口味", "饮食", "咖啡", "辣", "甜", "食物", "喝酒", "喝茶").any { t.contains(it) } -> "饮食"
            listOf("系统", "设备", "手机", "电脑", "型号", "hyperos", "miui", "windows", "mac", "ipad", "平板").any { t.contains(it) } -> "环境设备"
            listOf("代码", "编程", "开发", "技术栈", "框架", "kotlin", "java", "python", "rust", "golang", "compose", "sql", "linux").any { t.contains(it) } -> "编码偏好"
            listOf("回答", "风格", "简洁", "详细", "语气", "称呼我", "口吻").any { t.contains(it) } -> "沟通偏好"
            listOf("计划", "目标", "在学", "健身", "减肥", "备考", "打算", "长期").any { t.contains(it) } -> "目标计划"
            listOf("老婆", "老公", "妻子", "丈夫", "女友", "男友", "家人", "父母", "孩子", "同事", "朋友").any { t.contains(it) } -> "人际关系"
            listOf("我是", "我叫", "职业", "工作", "住", "所在", "城市", "岁", "开发者", "学生", "公司").any { t.contains(it) } -> "个人信息"
            else -> "其它"
        }
    }

    private fun injectedChars(list: List<MemoryItem>): Int =
        list.filter { it.enabled }.sumOf { it.title.length + it.content.length + 6 }

    /** Bump lastReferencedAt for memories the extractor judged relevant this turn (smarter pruning). */
    fun touchReferenced(ids: List<Long>) {
        if (ids.isEmpty()) return
        val set = ids.toHashSet()
        val now = System.currentTimeMillis()
        var changed = false
        val updated = _memories.value.map { if (it.id in set) { changed = true; it.copy(lastReferencedAt = now) } else it }
        if (changed) saveMemories(updated)
    }

    private fun saveMemories(list: List<MemoryItem>) {
        _memories.value = list
        prefs.edit().putString(KEY_MEMORIES, json.encodeToString(memSerializer, list)).apply()
    }

    private fun loadMemories(): List<MemoryItem> = try {
        prefs.getString(KEY_MEMORIES, null)?.let { json.decodeFromString(memSerializer, it) } ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    // --- agent ops ---

    fun setAgentEnabled(enabled: Boolean) {
        _agentEnabled.value = enabled
        prefs.edit().putBoolean(KEY_AGENT, enabled).apply()
    }

    fun setExecutionMode(mode: ExecutionMode) {
        _executionMode.value = mode
        prefs.edit().putString(KEY_EXEC, mode.key).apply()
    }

    fun setAgentUseProModel(enabled: Boolean) {
        _agentUseProModel.value = enabled
        prefs.edit().putBoolean(KEY_AGENT_PRO, enabled).apply()
    }

    fun setAmbientEnabled(enabled: Boolean) {
        _ambientEnabled.value = enabled
        prefs.edit().putBoolean(KEY_AMBIENT, enabled).apply()
    }

    fun setAutoScheduleEnabled(enabled: Boolean) {
        _autoScheduleEnabled.value = enabled
        prefs.edit().putBoolean(KEY_AUTO_SCHEDULE, enabled).apply()
    }

    fun setAutoMemoryEnabled(enabled: Boolean) {
        _autoMemoryEnabled.value = enabled
        prefs.edit().putBoolean(KEY_AUTO_MEMORY, enabled).apply()
    }

    fun setAppWatched(pkg: String, watched: Boolean) {
        val set = _watchedApps.value.toMutableSet()
        if (watched) set.add(pkg) else set.remove(pkg)
        _watchedApps.value = set
        prefs.edit().putString(KEY_WATCHED, set.joinToString("\n")).apply()
    }

    fun setWatchScreenshots(enabled: Boolean) {
        _watchScreenshots.value = enabled
        prefs.edit().putBoolean(KEY_SCREENSHOTS, enabled).apply()
    }

    fun setAutoReplyEnabled(enabled: Boolean) {
        _autoReplyEnabled.value = enabled
        prefs.edit().putBoolean(KEY_AUTOREPLY, enabled).apply()
    }

    fun isContactAuto(contactKey: String): Boolean = contactKey in _autoReplyContacts.value

    fun setContactAuto(contactKey: String, auto: Boolean) {
        val set = _autoReplyContacts.value.toMutableSet()
        if (auto) set.add(contactKey) else set.remove(contactKey)
        _autoReplyContacts.value = set
        prefs.edit().putString(KEY_AUTOREPLY_CONTACTS, set.joinToString("\n")).apply()
    }

    fun recordUsage(modelId: String, prompt: Int, completion: Int) {
        if (prompt <= 0 && completion <= 0) return
        val cur = _usageStats.value.toMutableMap()
        val s = cur[modelId] ?: UsageStat()
        cur[modelId] = s.copy(
            promptTokens = s.promptTokens + prompt,
            completionTokens = s.completionTokens + completion,
            calls = s.calls + 1
        )
        _usageStats.value = cur
        prefs.edit().putString(KEY_USAGE, json.encodeToString(usageSerializer, cur)).apply()
    }

    fun clearUsage() {
        _usageStats.value = emptyMap()
        prefs.edit().remove(KEY_USAGE).apply()
    }

    private fun loadUsage(): Map<String, UsageStat> = try {
        prefs.getString(KEY_USAGE, null)?.let { json.decodeFromString(usageSerializer, it) } ?: emptyMap()
    } catch (e: Exception) {
        emptyMap()
    }

    /**
     * Which model the agent loop should drive. When the "use Pro for agent" toggle is on, prefer
     * V4 Pro (much better at multi-step search/tool reasoning than Flash); plain chat keeps using
     * the user's selected model. Falls back gracefully if the user customized their model list.
     */
    fun agentModelId(selectedId: String): String {
        if (!_agentUseProModel.value) return selectedId
        // Only auto-upgrade DeepSeek Flash → Pro (same provider); any other pick is kept as-is.
        if (selectedId.contains("deepseek", true) && selectedId.contains("flash", true)) {
            _models.value.firstOrNull { it.id == AGENT_PRO_MODEL }?.let { return it.id }
        }
        return selectedId
    }

    fun setSearchBackend(backend: SearchBackend) {
        _searchBackend.value = backend
        prefs.edit().putString(KEY_SEARCH_BE, backend.key).apply()
    }

    fun setSearchApiKey(value: String) {
        _searchApiKey.value = value.trim()
        prefs.edit().putString(KEY_SEARCH_KEY, value.trim()).apply()
    }

    fun setSearchKeyBaidu(v: String) { _searchKeyBaidu.value = v.trim(); prefs.edit().putString(KEY_SEARCH_BAIDU, v.trim()).apply() }
    fun setSearchKeyBocha(v: String) { _searchKeyBocha.value = v.trim(); prefs.edit().putString(KEY_SEARCH_BOCHA, v.trim()).apply() }
    fun setSearchKeyMetaso(v: String) { _searchKeyMetaso.value = v.trim(); prefs.edit().putString(KEY_SEARCH_METASO, v.trim()).apply() }
    fun setSearchPrimary(v: String) { _searchPrimary.value = v; prefs.edit().putString(KEY_SEARCH_PRIMARY, v).apply() }
    fun setSearchUrlBaidu(v: String) { val u = v.trim().ifBlank { DEFAULT_SEARCH_URL_BAIDU }; _searchUrlBaidu.value = u; prefs.edit().putString(KEY_SEARCH_URL_BAIDU, u).apply() }
    fun setSearchUrlBocha(v: String) { val u = v.trim().ifBlank { DEFAULT_SEARCH_URL_BOCHA }; _searchUrlBocha.value = u; prefs.edit().putString(KEY_SEARCH_URL_BOCHA, u).apply() }
    fun setSearchUrlMetaso(v: String) { val u = v.trim().ifBlank { DEFAULT_SEARCH_URL_METASO }; _searchUrlMetaso.value = u; prefs.edit().putString(KEY_SEARCH_URL_METASO, u).apply() }

    fun setQweatherKey(v: String) { _qweatherKey.value = v.trim(); prefs.edit().putString(KEY_QWEATHER_KEY, v.trim()).apply() }
    fun setQweatherHost(v: String) { val h = v.trim().ifBlank { "devapi.qweather.com" }; _qweatherHost.value = h; prefs.edit().putString(KEY_QWEATHER_HOST, h).apply() }
    fun setWeatherCity(v: String) { _weatherCity.value = v.trim(); prefs.edit().putString(KEY_WEATHER_CITY, v.trim()).apply() }
    fun setWeatherMonitorEnabled(v: Boolean) { _weatherMonitorEnabled.value = v; prefs.edit().putBoolean(KEY_WEATHER_MONITOR, v).apply() }
    fun setWeatherMorningHour(v: Int) { _weatherMorningHour.value = v.coerceIn(0, 23); prefs.edit().putInt(KEY_WEATHER_HOUR, v.coerceIn(0, 23)).apply() }
    // Plain (no StateFlow) state the background worker reads/writes directly.
    var weatherSnapshot: String
        get() = prefs.getString(KEY_WEATHER_SNAPSHOT, "").orEmpty()
        set(v) { prefs.edit().putString(KEY_WEATHER_SNAPSHOT, v).apply() }
    var lastMorningPushDay: Int
        get() = prefs.getInt(KEY_WEATHER_PUSH_DAY, -1)
        set(v) { prefs.edit().putInt(KEY_WEATHER_PUSH_DAY, v).apply() }
    var lastChangeNotifiedAt: Long
        get() = prefs.getLong(KEY_WEATHER_CHANGE_AT, 0L)
        set(v) { prefs.edit().putLong(KEY_WEATHER_CHANGE_AT, v).apply() }

    fun isToolEnabled(name: String): Boolean = name !in _disabledTools.value

    fun setToolEnabled(name: String, enabled: Boolean) {
        val set = _disabledTools.value.toMutableSet()
        if (enabled) set.remove(name) else set.add(name)
        _disabledTools.value = set
        prefs.edit().putString(KEY_DISABLED_TOOLS, set.joinToString("\n")).apply()
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.deepseek.com"
        const val AGENT_PRO_MODEL = "deepseek-v4-pro"
        private const val KEY_API = "api_key"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_TEMP = "temperature"
        private const val KEY_SYS = "system_prompt"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_MODELS = "models_json"
        private const val KEY_MODELS_MIGRATED = "models_migrated_v2"
        private const val KEY_FLATTEN_MIGRATED = "flatten_migrated_v1"
        private const val KEY_PROVIDER_MIGRATED = "provider_migrated_v1"
        private const val KEY_TRIM_PROVIDERS = "trim_search_providers_v2"
        private const val KEY_USAGE = "usage_stats"
        private const val KEY_SEL_MODEL = "selected_model_id"
        private const val KEY_MEMORIES = "memories_json"
        private const val KEY_AGENT = "agent_enabled"
        private const val KEY_EXEC = "execution_mode"
        private const val KEY_AGENT_PRO = "agent_use_pro"
        private const val KEY_AMBIENT = "ambient_enabled"
        private const val KEY_AUTO_SCHEDULE = "auto_schedule_enabled"
        private const val KEY_AUTO_MEMORY = "auto_memory_enabled"
        private const val MAX_MEMORIES = 40
        private const val MAX_MEMORY_CHARS = 4000
        private const val KEY_WATCHED = "watched_apps"
        private const val KEY_SCREENSHOTS = "watch_screenshots"
        private const val KEY_AUTOREPLY = "auto_reply_enabled"
        private const val KEY_AUTOREPLY_CONTACTS = "auto_reply_contacts"
        private const val KEY_SEARCH_BE = "search_backend"
        private const val KEY_SEARCH_KEY = "search_api_key"
        private const val KEY_SEARCH_BAIDU = "search_key_baidu"
        private const val KEY_SEARCH_BOCHA = "search_key_bocha"
        private const val KEY_SEARCH_METASO = "search_key_metaso"
        private const val KEY_SEARCH_PRIMARY = "search_primary"
        private const val KEY_SEARCH_URL_BAIDU = "search_url_baidu"
        private const val KEY_SEARCH_URL_BOCHA = "search_url_bocha"
        private const val KEY_SEARCH_URL_METASO = "search_url_metaso"
        const val DEFAULT_SEARCH_URL_BAIDU = "https://qianfan.baidubce.com/v2/ai_search"
        const val DEFAULT_SEARCH_URL_BOCHA = "https://api.bochaai.com/v1/web-search"
        const val DEFAULT_SEARCH_URL_METASO = "https://metaso.cn/api/v1/search"
        private const val KEY_QWEATHER_KEY = "qweather_key"
        private const val KEY_QWEATHER_HOST = "qweather_host"
        private const val KEY_WEATHER_CITY = "weather_city"
        private const val KEY_WEATHER_MONITOR = "weather_monitor"
        private const val KEY_WEATHER_HOUR = "weather_hour"
        private const val KEY_WEATHER_SNAPSHOT = "weather_snapshot"
        private const val KEY_WEATHER_PUSH_DAY = "weather_push_day"
        private const val KEY_WEATHER_CHANGE_AT = "weather_change_at"
        private const val KEY_DISABLED_TOOLS = "disabled_tools"
    }
}
