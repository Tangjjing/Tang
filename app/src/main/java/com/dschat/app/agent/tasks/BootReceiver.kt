package com.dschat.app.agent.tasks

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.dschat.app.App
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * After reboot / app update, re-arm pending task & appointment reminders (AlarmManager alarms do NOT
 * survive reboot) and re-enqueue the weather monitor. Fixes a pre-existing "reminders lost on reboot" bug.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != "android.intent.action.QUICKBOOT_POWERON"
        ) return
        val container = (context.applicationContext as? App)?.container ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                container.taskRepository.rearmReminders()
                if (container.settings.weatherMonitorEnabled.value) WeatherScheduler.enqueue(context)
                if (container.settings.portfolioEnabled.value) PortfolioScheduler.enqueue(context)
            } catch (_: Exception) {
            } finally {
                pending.finish()
            }
        }
    }
}
