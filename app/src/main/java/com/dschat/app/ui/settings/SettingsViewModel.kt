package com.dschat.app.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dschat.app.agent.ExecutionMode
import com.dschat.app.agent.SearchBackend
import com.dschat.app.agent.tasks.WeatherApi
import com.dschat.app.data.settings.SettingsRepository
import com.dschat.app.data.settings.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsViewModel(private val settings: SettingsRepository) : ViewModel() {

    val apiKey: StateFlow<String> = settings.apiKey
    val baseUrl: StateFlow<String> = settings.baseUrl
    val temperature: StateFlow<Float> = settings.temperature
    val systemPrompt: StateFlow<String> = settings.systemPrompt
    val theme: StateFlow<ThemeMode> = settings.theme
    val keystoreFailed: StateFlow<Boolean> = settings.keystoreFailed

    val agentEnabled: StateFlow<Boolean> = settings.agentEnabled
    val executionMode: StateFlow<ExecutionMode> = settings.executionMode
    val agentUseProModel: StateFlow<Boolean> = settings.agentUseProModel
    val searchBackend: StateFlow<SearchBackend> = settings.searchBackend
    val searchApiKey: StateFlow<String> = settings.searchApiKey
    val searchKeyBaidu: StateFlow<String> = settings.searchKeyBaidu
    val searchKeyBocha: StateFlow<String> = settings.searchKeyBocha
    val searchKeyMetaso: StateFlow<String> = settings.searchKeyMetaso
    val searchPrimary: StateFlow<String> = settings.searchPrimary
    val searchUrlBaidu: StateFlow<String> = settings.searchUrlBaidu
    val searchUrlBocha: StateFlow<String> = settings.searchUrlBocha
    val searchUrlMetaso: StateFlow<String> = settings.searchUrlMetaso
    val qweatherKey: StateFlow<String> = settings.qweatherKey
    val qweatherHost: StateFlow<String> = settings.qweatherHost
    val weatherCity: StateFlow<String> = settings.weatherCity
    val weatherMonitorEnabled: StateFlow<Boolean> = settings.weatherMonitorEnabled
    val weatherMorningHour: StateFlow<Int> = settings.weatherMorningHour
    val ambientEnabled: StateFlow<Boolean> = settings.ambientEnabled
    val autoScheduleEnabled: StateFlow<Boolean> = settings.autoScheduleEnabled
    val watchedApps: StateFlow<Set<String>> = settings.watchedApps
    val watchScreenshots: StateFlow<Boolean> = settings.watchScreenshots
    val autoReplyEnabled: StateFlow<Boolean> = settings.autoReplyEnabled
    val autoReplyContacts: StateFlow<Set<String>> = settings.autoReplyContacts

    fun setApiKey(value: String) = settings.setApiKey(value)
    fun setBaseUrl(value: String) = settings.setBaseUrl(value)
    fun setTemperature(value: Float) = settings.setTemperature(value)
    fun setSystemPrompt(value: String) = settings.setSystemPrompt(value)
    fun setTheme(mode: ThemeMode) = settings.setTheme(mode)

    fun setAgentEnabled(enabled: Boolean) = settings.setAgentEnabled(enabled)
    fun setExecutionMode(mode: ExecutionMode) = settings.setExecutionMode(mode)
    fun setAgentUseProModel(enabled: Boolean) = settings.setAgentUseProModel(enabled)
    fun setAmbientEnabled(enabled: Boolean) = settings.setAmbientEnabled(enabled)
    fun setAutoScheduleEnabled(enabled: Boolean) = settings.setAutoScheduleEnabled(enabled)
    fun setAppWatched(pkg: String, watched: Boolean) = settings.setAppWatched(pkg, watched)
    fun setWatchScreenshots(enabled: Boolean) = settings.setWatchScreenshots(enabled)
    fun setAutoReplyEnabled(enabled: Boolean) = settings.setAutoReplyEnabled(enabled)
    fun setContactAuto(contactKey: String, auto: Boolean) = settings.setContactAuto(contactKey, auto)
    fun setSearchBackend(backend: SearchBackend) = settings.setSearchBackend(backend)
    fun setSearchApiKey(value: String) = settings.setSearchApiKey(value)
    fun setSearchKeyBaidu(v: String) = settings.setSearchKeyBaidu(v)
    fun setSearchKeyBocha(v: String) = settings.setSearchKeyBocha(v)
    fun setSearchKeyMetaso(v: String) = settings.setSearchKeyMetaso(v)
    fun setSearchPrimary(v: String) = settings.setSearchPrimary(v)
    fun setSearchUrlBaidu(v: String) = settings.setSearchUrlBaidu(v)
    fun setSearchUrlBocha(v: String) = settings.setSearchUrlBocha(v)
    fun setSearchUrlMetaso(v: String) = settings.setSearchUrlMetaso(v)
    fun setQweatherKey(v: String) = settings.setQweatherKey(v)
    fun setQweatherHost(v: String) = settings.setQweatherHost(v)
    fun setWeatherCity(v: String) = settings.setWeatherCity(v)
    fun setWeatherMonitorEnabled(v: Boolean) = settings.setWeatherMonitorEnabled(v)
    fun setWeatherMorningHour(v: Int) = settings.setWeatherMorningHour(v)

    // One-shot "check weather now" so the user can verify their key/city/location actually work
    // (and that monitoring would have data), instead of waiting on the uncertain ~15min background job.
    private val _weatherChecking = MutableStateFlow(false)
    val weatherChecking: StateFlow<Boolean> = _weatherChecking.asStateFlow()
    private val _weatherCheckResult = MutableStateFlow<String?>(null)
    val weatherCheckResult: StateFlow<String?> = _weatherCheckResult.asStateFlow()

    fun checkWeatherNow(ctx: Context) {
        if (_weatherChecking.value) return
        _weatherChecking.value = true
        val appCtx = ctx.applicationContext // avoid holding the Activity in the VM-scoped coroutine
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) {
                try { WeatherApi.fetch(appCtx, settings, settings.weatherCity.value.ifBlank { null }, null, null, 1) }
                catch (e: Exception) { null }
            }
            val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            _weatherCheckResult.value = if (r != null)
                "上次检查 $time：${r.place} ${r.nowText} ${r.nowTemp}°（${r.todayMin}~${r.todayMax}°）" +
                    (if (r.aqi >= 0) " · AQI ${r.aqi}" else "") + (r.warning?.let { " · ⚠️$it" } ?: "")
            else "上次检查 $time：未取到天气（请检查城市 / 定位 / 网络，或填和风 Key）"
            _weatherChecking.value = false
        }
    }
}
