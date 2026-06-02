package com.dschat.app.ui.usage

import androidx.lifecycle.ViewModel
import com.dschat.app.data.settings.SettingsRepository
import com.dschat.app.domain.ChatModel
import com.dschat.app.domain.UsageStat
import kotlinx.coroutines.flow.StateFlow

class UsageViewModel(private val settings: SettingsRepository) : ViewModel() {
    val usage: StateFlow<Map<String, UsageStat>> = settings.usageStats
    val models: StateFlow<List<ChatModel>> = settings.models
    fun clear() = settings.clearUsage()
}
