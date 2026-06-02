package com.dschat.app.ui.memory

import androidx.lifecycle.ViewModel
import com.dschat.app.data.settings.SettingsRepository
import com.dschat.app.domain.MemoryItem
import kotlinx.coroutines.flow.StateFlow

class MemoryViewModel(private val settings: SettingsRepository) : ViewModel() {

    val memories: StateFlow<List<MemoryItem>> = settings.memories

    val autoMemoryEnabled: StateFlow<Boolean> = settings.autoMemoryEnabled
    fun setAutoMemory(enabled: Boolean) = settings.setAutoMemoryEnabled(enabled)

    fun get(id: Long): MemoryItem? = settings.getMemory(id)

    /** Create a new memory, returns its id. */
    fun add(title: String, content: String, category: String = ""): Long = settings.addMemory(title, content, category = category)

    fun update(item: MemoryItem) = settings.updateMemory(item)

    fun delete(id: Long) = settings.deleteMemory(id)

    fun setEnabled(id: Long, enabled: Boolean) = settings.setMemoryEnabled(id, enabled)
}
