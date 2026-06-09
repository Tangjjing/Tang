package com.dschat.app.ui.portfolio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dschat.app.data.remote.FundApi
import com.dschat.app.data.remote.FundSearchHit
import com.dschat.app.data.settings.SettingsRepository
import com.dschat.app.domain.Holding
import com.dschat.app.domain.Quote
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class PortfolioViewModel(private val settings: SettingsRepository) : ViewModel() {

    val holdings: StateFlow<List<Holding>> = settings.holdings
    val enabled: StateFlow<Boolean> = settings.portfolioEnabled
    val morningHour: StateFlow<Int> = settings.portfolioMorningHour
    val eveningEnabled: StateFlow<Boolean> = settings.portfolioEveningEnabled
    val eveningHour: StateFlow<Int> = settings.portfolioEveningHour

    private val _quotes = MutableStateFlow<Map<String, Quote>>(emptyMap())
    val quotes: StateFlow<Map<String, Quote>> = _quotes.asStateFlow()
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _searchResults = MutableStateFlow<List<FundSearchHit>>(emptyList())
    val searchResults: StateFlow<List<FundSearchHit>> = _searchResults.asStateFlow()
    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    init { refresh() }

    fun refresh() {
        val codes = settings.holdings.value.map { it.code }
        if (codes.isEmpty()) { _quotes.value = emptyMap(); return }
        viewModelScope.launch {
            _loading.value = true
            try {
                val q = FundApi.fetchQuotes(codes)
                if (q.isEmpty()) _message.value = "暂时取不到净值（网络或非交易日），稍后再试"
                else _quotes.value = q
            } catch (e: Exception) {
                _message.value = "刷新失败：${e.message}"
            } finally {
                _loading.value = false
            }
        }
    }

    fun search(q: String) {
        if (q.isBlank()) { _searchResults.value = emptyList(); return }
        viewModelScope.launch {
            _searching.value = true
            try {
                _searchResults.value = FundApi.search(q)
                if (_searchResults.value.isEmpty()) _message.value = "没搜到「$q」，换个关键词或填 6 位代码试试"
            } catch (e: Exception) {
                _message.value = "搜索失败：${e.message}"
            } finally {
                _searching.value = false
            }
        }
    }

    fun clearSearch() { _searchResults.value = emptyList() }

    /** Add a picked fund. [cost] required (元); [shares] optional — derived from NAV when blank. */
    fun add(hit: FundSearchHit, cost: Double, shares: Double?) {
        if (cost <= 0) { _message.value = "成本需大于 0"; return }
        viewModelScope.launch {
            val q = FundApi.fetchQuotes(listOf(hit.code))[hit.code]
            val nav = q?.nav ?: hit.nav
            val sh = shares?.takeIf { it > 0 } ?: nav?.let { cost / it }
            if (sh == null) { _message.value = "拿不到净值，无法估算份额，请填份额或稍后再试"; return@launch }
            settings.upsertHolding(Holding(code = hit.code, name = q?.name ?: hit.name, shares = sh, cost = cost))
            _searchResults.value = emptyList()
            _message.value = "已添加 ${hit.name}"
            refresh()
        }
    }

    fun remove(code: String) { settings.deleteHolding(code); refresh() }

    fun clearMessage() { _message.value = null }
    fun postMessage(s: String) { _message.value = s }

    fun setEnabled(v: Boolean) = settings.setPortfolioEnabled(v)
    fun setMorningHour(h: Int) = settings.setPortfolioMorningHour(h)
    fun setEveningEnabled(v: Boolean) = settings.setPortfolioEveningEnabled(v)
    fun setEveningHour(h: Int) = settings.setPortfolioEveningHour(h)
}
