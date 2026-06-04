package com.dschat.app.ui.memory

import androidx.lifecycle.ViewModel
import com.dschat.app.data.settings.SettingsRepository
import com.dschat.app.domain.MemoryItem
import kotlinx.coroutines.flow.StateFlow

class MemoryViewModel(private val settings: SettingsRepository) : ViewModel() {

    val memories: StateFlow<List<MemoryItem>> = settings.memories

    val autoMemoryEnabled: StateFlow<Boolean> = settings.autoMemoryEnabled
    fun setAutoMemory(enabled: Boolean) = settings.setAutoMemoryEnabled(enabled)

    val autoMemoryReview: StateFlow<Boolean> = settings.autoMemoryReview
    fun setAutoMemoryReview(enabled: Boolean) = settings.setAutoMemoryReview(enabled)

    fun get(id: Long): MemoryItem? = settings.getMemory(id)

    /** Create a new memory, returns its id. */
    fun add(title: String, content: String, category: String = ""): Long = settings.addMemory(title, content, category = category)

    fun update(item: MemoryItem) = settings.updateMemory(item)

    fun delete(id: Long) = settings.deleteMemory(id)

    fun setEnabled(id: Long, enabled: Boolean) = settings.setMemoryEnabled(id, enabled)

    fun setPinned(id: Long, pinned: Boolean) = settings.setMemoryPinned(id, pinned)

    // Review
    fun confirm(id: Long) = settings.confirmMemory(id)
    fun reject(id: Long) = settings.rejectMemory(id)

    // Batch
    fun setEnabledBatch(ids: Set<Long>, enabled: Boolean) = settings.setMemoriesEnabled(ids, enabled)
    fun deleteBatch(ids: Set<Long>) = settings.deleteMemories(ids)
}
