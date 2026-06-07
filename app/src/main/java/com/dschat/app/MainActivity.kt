package com.dschat.app

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.dschat.app.data.settings.ThemeMode
import com.dschat.app.ui.AppViewModelProvider
import com.dschat.app.ui.chat.ChatScreen
import com.dschat.app.ui.chat.ChatViewModel
import com.dschat.app.ui.memory.MemoryEditScreen
import com.dschat.app.ui.memory.MemoryListScreen
import com.dschat.app.ui.memory.MemoryViewModel
import com.dschat.app.ui.models.ModelsScreen
import com.dschat.app.ui.models.ModelsViewModel
import com.dschat.app.ui.pc.PcConnectionScreen
import com.dschat.app.ui.pc.PcViewModel
import com.dschat.app.ui.settings.AgentSettingsScreen
import com.dschat.app.ui.settings.ChatParamsScreen
import com.dschat.app.ui.settings.NotifyAssistantScreen
import com.dschat.app.ui.settings.PermissionsScreen
import com.dschat.app.ui.settings.SettingsScreen
import com.dschat.app.ui.settings.SettingsViewModel
import com.dschat.app.ui.settings.WeatherScreen
import com.dschat.app.ui.tasks.TasksScreen
import com.dschat.app.ui.tasks.TasksViewModel
import com.dschat.app.ui.usage.UsageScreen
import com.dschat.app.ui.usage.UsageViewModel
import com.dschat.app.ui.theme.DeepSeekTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        requestHighRefreshRate()
        val settings = (application as App).container.settings
        setContent {
            val themeMode by settings.theme.collectAsStateWithLifecycle()
            val dark = when (themeMode) {
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            DeepSeekTheme(darkTheme = dark) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppNavHost()
                }
            }
        }
    }

    /** Ask the system for the display's highest refresh rate (90/120Hz) at the current resolution. */
    private fun requestHighRefreshRate() {
        try {
            val disp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display
            else @Suppress("DEPRECATION") windowManager.defaultDisplay
            val current = disp?.mode ?: return
            val best = disp.supportedModes
                .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
                .maxByOrNull { it.refreshRate } ?: return
            if (best.refreshRate > current.refreshRate + 1f) {
                window.attributes = window.attributes.apply { preferredDisplayModeId = best.modeId }
            }
        } catch (_: Exception) {
        }
    }
}

@Composable
private fun AppNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "chat") {
        composable("chat") {
            val vm: ChatViewModel = viewModel(factory = AppViewModelProvider.Factory)
            ChatScreen(
                viewModel = vm,
                onOpenSettings = { navController.navigate("settings") },
                onOpenModels = { navController.navigate("models") },
                onOpenTasks = { navController.navigate("tasks") },
                onOpenMemory = { navController.navigate("memory") }
            )
        }
        composable("tasks") {
            val vm: TasksViewModel = viewModel(factory = AppViewModelProvider.Factory)
            TasksScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() },
                onOpenNotify = { navController.navigate("notifyAssistant") }
            )
        }
        composable("usage") {
            val vm: UsageViewModel = viewModel(factory = AppViewModelProvider.Factory)
            UsageScreen(viewModel = vm, onBack = { navController.popBackStack() })
        }
        composable("settings") {
            val vm: SettingsViewModel = viewModel(factory = AppViewModelProvider.Factory)
            SettingsScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() },
                onOpenModels = { navController.navigate("models") },
                onOpenAgent = { navController.navigate("agentSettings") },
                onOpenNotify = { navController.navigate("notifyAssistant") },
                onOpenMemory = { navController.navigate("memory") },
                onOpenUsage = { navController.navigate("usage") },
                onOpenPermissions = { navController.navigate("permissions") },
                onOpenChatParams = { navController.navigate("chatParams") },
                onOpenWeather = { navController.navigate("weather") },
                onOpenPc = { navController.navigate("pcConnection") }
            )
        }
        composable("agentSettings") {
            val vm: SettingsViewModel = viewModel(factory = AppViewModelProvider.Factory)
            AgentSettingsScreen(viewModel = vm, onBack = { navController.popBackStack() })
        }
        composable("notifyAssistant") {
            val vm: SettingsViewModel = viewModel(factory = AppViewModelProvider.Factory)
            NotifyAssistantScreen(viewModel = vm, onBack = { navController.popBackStack() })
        }
        composable("chatParams") {
            val vm: SettingsViewModel = viewModel(factory = AppViewModelProvider.Factory)
            ChatParamsScreen(viewModel = vm, onBack = { navController.popBackStack() })
        }
        composable("weather") {
            val vm: SettingsViewModel = viewModel(factory = AppViewModelProvider.Factory)
            WeatherScreen(viewModel = vm, onBack = { navController.popBackStack() })
        }
        composable("pcConnection") {
            val vm: PcViewModel = viewModel(factory = AppViewModelProvider.Factory)
            PcConnectionScreen(viewModel = vm, onBack = { navController.popBackStack() })
        }
        composable("permissions") {
            PermissionsScreen(onBack = { navController.popBackStack() })
        }
        composable("models") {
            val vm: ModelsViewModel = viewModel(factory = AppViewModelProvider.Factory)
            ModelsScreen(viewModel = vm, onBack = { navController.popBackStack() })
        }
        composable("memory") {
            val vm: MemoryViewModel = viewModel(factory = AppViewModelProvider.Factory)
            MemoryListScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() },
                onEdit = { id -> navController.navigate("memoryEdit/$id") },
                onAdd = { navController.navigate("memoryEdit/0") }
            )
        }
        composable(
            route = "memoryEdit/{id}",
            arguments = listOf(navArgument("id") { type = NavType.LongType })
        ) { entry ->
            val vm: MemoryViewModel = viewModel(factory = AppViewModelProvider.Factory)
            val id = entry.arguments?.getLong("id") ?: 0L
            MemoryEditScreen(viewModel = vm, memoryId = id, onBack = { navController.popBackStack() })
        }
    }
}
