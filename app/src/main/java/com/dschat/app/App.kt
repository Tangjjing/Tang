package com.dschat.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.dschat.app.agent.ToolRegistry
import com.dschat.app.agent.tasks.MemoryExtractor
import com.dschat.app.agent.tasks.ReminderScheduler
import com.dschat.app.agent.tasks.ReplyEngine
import com.dschat.app.agent.tasks.ReplyNotifier
import com.dschat.app.agent.tasks.ScheduleNotifier
import com.dschat.app.agent.tasks.WeatherScheduler
import com.dschat.app.agent.tasks.TaskClassifier
import com.dschat.app.agent.tasks.TaskRepository
import com.dschat.app.data.local.AppDatabase
import com.dschat.app.data.remote.DeepSeekApi
import com.dschat.app.data.repository.ChatRepository
import com.dschat.app.data.settings.SettingsRepository
import com.dschat.app.pc.PcBridge

/** Manual dependency container (no Hilt — keeps the build simple and fast). */
class AppContainer(context: Context) {
    val settings = SettingsRepository(context)
    private val database = AppDatabase.get(context)
    private val api = DeepSeekApi()
    val chatRepository = ChatRepository(database.chatDao(), api, settings)
    val pcBridge = PcBridge(settings)
    val toolRegistry = ToolRegistry(context, settings, pcBridge)
    val memoryExtractor = MemoryExtractor(api, settings)
    private val taskClassifier = TaskClassifier(api, settings)
    val taskRepository = TaskRepository(context.applicationContext, database.taskDao(), taskClassifier, settings)
    val replyEngine = ReplyEngine(context.applicationContext, database.contactDao(), chatRepository, settings)
}

class App : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        createNotificationChannels()
        // PdfBox-Android needs its resource loader initialised once before any PDF text extraction.
        try {
            com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(applicationContext)
        } catch (_: Throwable) {
        }
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                ReminderScheduler.CHANNEL_ID,
                "待办提醒",
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "通知助理识别出的待办到点提醒" }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                ReplyNotifier.CHANNEL_ID,
                "消息回复草稿",
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "AI 拟好的、可一键发送的回复" }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                ScheduleNotifier.CHANNEL_ID,
                "自动日程",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "从消息里自动识别并加入日历的事项（可撤销）" }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                WeatherScheduler.CHANNEL_ID,
                "天气提醒",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "早间恶劣天气与天气突变提醒" }
        )
    }
}
