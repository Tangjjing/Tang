package com.dschat.app.ui

import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dschat.app.App
import com.dschat.app.ui.chat.ChatViewModel
import com.dschat.app.ui.memory.MemoryViewModel
import com.dschat.app.ui.models.ModelsViewModel
import com.dschat.app.ui.pc.PcViewModel
import com.dschat.app.ui.settings.SettingsViewModel
import com.dschat.app.ui.tasks.TasksViewModel
import com.dschat.app.ui.usage.UsageViewModel

private fun CreationExtras.app(): App = this[APPLICATION_KEY] as App

object AppViewModelProvider {
    val Factory = viewModelFactory {
        initializer {
            ChatViewModel(
                createSavedStateHandle(),
                app(),
                app().container.chatRepository,
                app().container.settings,
                app().container.toolRegistry,
                app().container.memoryExtractor
            )
        }
        initializer {
            SettingsViewModel(app().container.settings)
        }
        initializer {
            ModelsViewModel(app().container.settings, app().container.chatRepository)
        }
        initializer {
            MemoryViewModel(app().container.settings)
        }
        initializer {
            TasksViewModel(app().container.taskRepository)
        }
        initializer {
            UsageViewModel(app().container.settings)
        }
        initializer {
            PcViewModel(app().container.settings, app().container.pcBridge)
        }
    }
}
