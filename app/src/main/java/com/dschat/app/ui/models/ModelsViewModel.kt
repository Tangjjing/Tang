package com.dschat.app.ui.models

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dschat.app.data.repository.ChatRepository
import com.dschat.app.data.settings.SettingsRepository
import com.dschat.app.domain.ChatModel
import com.dschat.app.domain.providerFromBaseUrl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class FetchStatus(val fetching: Boolean = false, val message: String? = null)

class ModelsViewModel(
    private val settings: SettingsRepository,
    private val repo: ChatRepository
) : ViewModel() {

    val models: StateFlow<List<ChatModel>> = settings.models
    val selectedId: StateFlow<String> = settings.selectedModelId

    private val _status = MutableStateFlow(FetchStatus())
    val status: StateFlow<FetchStatus> = _status.asStateFlow()

    fun select(id: String) = settings.selectModel(id)

    fun upsert(id: String, displayName: String, reasoning: Boolean, vision: Boolean, provider: String, baseUrl: String, apiKey: String) {
        val cleanId = id.trim()
        if (cleanId.isEmpty()) return
        settings.upsertModel(
            ChatModel(
                id = cleanId,
                displayName = displayName.trim().ifBlank { cleanId },
                reasoning = reasoning,
                baseUrl = baseUrl.trim().ifBlank { null },
                apiKey = apiKey.trim().ifBlank { null },
                vision = vision,
                provider = provider.trim().ifBlank { providerFromBaseUrl(baseUrl) }
            )
        )
    }

    /** Add a supplier by URL+key and pull its full model list via /models. */
    fun addProvider(name: String, baseUrl: String, apiKey: String) {
        if (_status.value.fetching) return
        val url = baseUrl.trim()
        if (url.isEmpty()) { _status.value = FetchStatus(message = "请填写接口地址（baseURL）"); return }
        val provider = name.trim().ifBlank { providerFromBaseUrl(url) }
        viewModelScope.launch {
            _status.value = FetchStatus(fetching = true)
            try {
                val ids = repo.listModelsWith(apiKey.trim(), url)
                val added = settings.addProviderModels(provider, url, apiKey.trim(), ids)
                _status.value = FetchStatus(message = "「$provider」：接口返回 ${ids.size} 个模型，新增 $added 个")
            } catch (e: Exception) {
                _status.value = FetchStatus(message = "拉取失败：${e.message ?: "未知错误"}。可改用『添加模型』手动填该供应商的一个模型。")
            }
        }
    }

    /** Re-detect a provider's models using the creds already stored on one of its models. */
    fun refreshProvider(provider: String) {
        val m = settings.models.value.firstOrNull { it.provider.ifBlank { "其它" } == provider }
        if (m == null) { _status.value = FetchStatus(message = "该供应商暂无模型"); return }
        addProvider(provider, m.baseUrl.orEmpty(), m.apiKey.orEmpty())
    }

    fun delete(id: String) = settings.deleteModel(id)

    fun clearStatus() { _status.value = FetchStatus() }

    fun fetchFromApi() {
        if (_status.value.fetching) return
        val modelId = settings.selectedModelId.value
        if (!settings.hasKeyFor(modelId)) {
            _status.value = FetchStatus(message = "请先给当前选中的模型填写 API Key（点其 ✎ 编辑）")
            return
        }
        viewModelScope.launch {
            _status.value = FetchStatus(fetching = true)
            try {
                val ids = repo.listModels(modelId)
                val added = settings.mergeFetchedModels(ids)
                _status.value = FetchStatus(message = "拉取成功：新增 $added 个，接口共返回 ${ids.size} 个模型")
            } catch (e: Exception) {
                _status.value = FetchStatus(message = "拉取失败：${e.message ?: "未知错误"}")
            }
        }
    }
}
