package com.dschat.app.agent.tasks

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.dschat.app.data.local.TaskDao
import com.dschat.app.data.local.TaskEntity
import com.dschat.app.data.local.TaskStatus
import com.dschat.app.data.settings.SettingsRepository
import kotlinx.coroutines.flow.Flow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TaskRepository(
    private val appContext: Context,
    private val dao: TaskDao,
    private val classifier: TaskClassifier,
    private val settings: SettingsRepository
) {
    fun observeTasks(): Flow<List<TaskEntity>> = dao.observeTasks()
    fun pendingCount(): Flow<Int> = dao.pendingCount()
    suspend fun get(id: Long): TaskEntity? = dao.get(id)

    /** Called by the notification listener for each whitelisted notification. */
    suspend fun ingest(pkg: String, label: String, title: String, text: String, notifKey: String?) {
        val body = listOf(title, text).filter { it.isNotBlank() }.joinToString(" — ")
        if (body.isBlank()) return
        // de-dup: same text within the last 10 minutes
        if (dao.countRecentSame(body, System.currentTimeMillis() - 10 * 60_000L) > 0) return
        val j = classifier.classify(label, title, text)
        if (j == null || !j.isTask) return
        val now = System.currentTimeMillis()
        val due = if (j.dueInMinutes in 0..(60 * 24 * 30)) now + j.dueInMinutes * 60_000L else null
        val task = TaskEntity(
            title = j.title.ifBlank { body.take(20) },
            detail = j.detail,
            sourceApp = pkg,
            sourceLabel = label,
            originalText = body,
            dueAt = due,
            priority = when (j.priority.lowercase()) {
                "high" -> 2
                "low" -> 0
                else -> 1
            },
            status = TaskStatus.PENDING,
            suggestedReply = j.suggestedReply.ifBlank { null },
            notificationKey = notifKey,
            createdAt = now
        )
        val id = dao.insert(task)
        if (due != null) ReminderScheduler.schedule(appContext, id, task.title, due)
        // Auto-schedule: concrete future appointment + opt-in → silently add to system calendar + undoable notice.
        if (settings.autoScheduleEnabled.value && j.appointment && due != null && j.dueInMinutes in 1..43200) {
            autoSchedule(id, task.title, task.detail, due, j.durationMinutes.coerceIn(15, 1440))
        }
    }

    /** Silently write an auto-detected appointment to the calendar (best-effort) and post an
     *  undoable confirmation notification. The AlarmManager reminder was already scheduled in ingest. */
    private suspend fun autoSchedule(taskId: Long, title: String, detail: String, beginMs: Long, durationMin: Int) {
        var eventId: Long? = null
        if (CalendarWriter.canWrite(appContext)) {
            val recentDup = dao.countAutoEvent(title, beginMs, System.currentTimeMillis() - 6 * 3_600_000L) > 0
            if (!recentDup) {
                val calId = CalendarWriter.pickWritableCalendarId(appContext)
                if (calId != null && !CalendarWriter.findDuplicate(appContext, title, beginMs)) {
                    eventId = CalendarWriter.insertEvent(
                        appContext, title, detail.ifBlank { null },
                        beginMs, beginMs + durationMin * 60_000L, false, calId
                    )
                    // NOTE: intentionally NO calendar Reminders row — the app's own AlarmManager reminder
                    // already fires; avoids double-buzz and ROM-killed calendar alerts.
                    if (eventId != null) dao.setCalendarEvent(taskId, eventId)
                }
            }
        }
        val whenText = SimpleDateFormat("M月d日 HH:mm", Locale.getDefault()).format(Date(beginMs))
        ScheduleNotifier.postScheduled(appContext, taskId, eventId, title, whenText)
    }

    /** Called by the screenshot watcher with OCR'd text from a new screenshot. */
    suspend fun ingestScreenshot(ocrText: String) {
        val text = ocrText.trim().take(1500)
        if (text.length < 6) return
        if (dao.countRecentSame(text, System.currentTimeMillis() - 10 * 60_000L) > 0) return
        val j = classifier.classify("截图", "", text)
        if (j == null || !j.isTask) return
        val now = System.currentTimeMillis()
        val due = if (j.dueInMinutes in 0..(60 * 24 * 30)) now + j.dueInMinutes * 60_000L else null
        val task = TaskEntity(
            title = j.title.ifBlank { "截图待办" },
            detail = j.detail,
            sourceApp = "screenshot",
            sourceLabel = "截图",
            originalText = text,
            dueAt = due,
            priority = when (j.priority.lowercase()) { "high" -> 2; "low" -> 0; else -> 1 },
            status = TaskStatus.PENDING,
            suggestedReply = j.suggestedReply.ifBlank { null },
            notificationKey = null,
            createdAt = now
        )
        val id = dao.insert(task)
        if (due != null) ReminderScheduler.schedule(appContext, id, task.title, due)
    }

    suspend fun complete(id: Long) {
        dao.setStatus(id, TaskStatus.DONE)
        ReminderScheduler.cancel(appContext, id)
    }

    suspend fun dismiss(id: Long) {
        dao.setStatus(id, TaskStatus.DISMISSED)
        ReminderScheduler.cancel(appContext, id)
    }

    suspend fun snooze(id: Long, minutes: Int) {
        val due = System.currentTimeMillis() + minutes * 60_000L
        dao.snooze(id, due)
        ReminderScheduler.schedule(appContext, id, dao.get(id)?.title ?: "提醒", due)
    }

    /** Undo an auto-scheduled appointment: delete the calendar event, cancel the reminder, dismiss the task. */
    suspend fun undoSchedule(taskId: Long, eventId: Long) {
        if (eventId > 0) CalendarWriter.deleteEvent(appContext, eventId)
        ReminderScheduler.cancel(appContext, taskId)
        dao.setCalendarEvent(taskId, null)
        dao.setStatus(taskId, TaskStatus.DISMISSED)
    }

    /** Re-arm AlarmManager reminders for still-pending future tasks (after reboot / app update). */
    suspend fun rearmReminders() {
        val now = System.currentTimeMillis()
        dao.dueAfter(now).forEach { t -> t.dueAt?.let { ReminderScheduler.schedule(appContext, t.id, t.title, it) } }
    }

    suspend fun clearFinished() = dao.clearFinished()
    suspend fun clearAll() = dao.clearAll()

    /**
     * Try to send [text] as a reply through the captured notification inline-reply action.
     * Returns true if sent; on failure copies the draft to the clipboard and returns false.
     */
    fun sendReply(task: TaskEntity, text: String): Boolean {
        val key = task.notificationKey
        if (key != null && NotificationCaptureService.trySendReply(appContext, key, text)) return true
        // WeChat/QQ have no notification quick-reply → try the Accessibility auto-typer.
        if (key != null && NotificationCaptureService.autoSendViaAccessibility(appContext, key, text)) return true
        val cm = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("draft", text))
        return false
    }
}
