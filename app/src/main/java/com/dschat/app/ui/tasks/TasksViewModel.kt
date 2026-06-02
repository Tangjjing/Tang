package com.dschat.app.ui.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dschat.app.agent.tasks.TaskRepository
import com.dschat.app.data.local.TaskEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TasksViewModel(private val repo: TaskRepository) : ViewModel() {

    val tasks: StateFlow<List<TaskEntity>> = repo.observeTasks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()
    fun clearToast() { _toast.value = null }

    fun complete(id: Long) = viewModelScope.launch { repo.complete(id) }
    fun dismiss(id: Long) = viewModelScope.launch { repo.dismiss(id) }
    fun snooze(id: Long, minutes: Int) = viewModelScope.launch { repo.snooze(id, minutes) }
    fun clearFinished() = viewModelScope.launch { repo.clearFinished() }

    fun sendReply(task: TaskEntity) {
        val text = task.suggestedReply ?: return
        viewModelScope.launch {
            val ok = repo.sendReply(task, text)
            if (ok) {
                repo.complete(task.id)
                _toast.value = "已发送"
            } else {
                _toast.value = "无法直接发送（通知已失效或该应用不支持快捷回复），草稿已复制到剪贴板"
            }
        }
    }
}
