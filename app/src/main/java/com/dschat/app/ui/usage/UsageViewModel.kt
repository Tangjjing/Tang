package com.dschat.app.ui.usage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dschat.app.data.repository.ChatRepository
import com.dschat.app.data.settings.SettingsRepository
import com.dschat.app.domain.BalanceResult
import com.dschat.app.domain.ChatModel
import com.dschat.app.domain.UsageStat
import com.dschat.app.domain.providerFromBaseUrl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Per-provider balance fetch state for the 用量统计 page. */
sealed interface BalanceState {
    data object Loading : BalanceState
    data class Loaded(val result: BalanceResult) : BalanceState
}

class UsageViewModel(
    private val settings: SettingsRepository,
    private val repo: ChatRepository
) : ViewModel() {
    val usage: StateFlow<Map<String, UsageStat>> = settings.usageStats
    val models: StateFlow<List<ChatModel>> = settings.models

    private val _balances = MutableStateFlow<Map<String, BalanceState>>(emptyMap())
    /** provider → its balance fetch state. */
    val balances: StateFlow<Map<String, BalanceState>> = _balances.asStateFlow()

    /** Distinct providers among the user's models that have an API key configured. */
    fun providersWithKey(): List<String> =
        models.value
            .filter { !it.apiKey.isNullOrBlank() }
            .map { it.provider.ifBlank { providerFromBaseUrl(it.baseUrl) } }
            .distinct()

    init {
        refreshAll()
    }

    /** (Re)query every key-bearing provider's balance. */
    fun refreshAll() {
        providersWithKey().forEach { refresh(it) }
    }

    fun refresh(provider: String) {
        _balances.value = _balances.value + (provider to BalanceState.Loading)
        viewModelScope.launch {
            val result = repo.fetchBalanceForProvider(provider)
            _balances.value = _balances.value + (provider to BalanceState.Loaded(result))
        }
    }

    fun clear() = settings.clearUsage()
}
