package com.dschat.app

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Tiny foreground service that runs ONLY while a chat/agent turn is in flight.
 *
 * Why it exists: on aggressive ROMs (MIUI/HyperOS) a backgrounded process gets frozen within
 * seconds, which tears down the in-flight OkHttp socket → the streaming/agent request fails with a
 * red error the moment the user switches away mid-answer. A running foreground service keeps the
 * whole process at foreground importance, so the request survives backgrounding.
 *
 * [ChatViewModel] calls [start] when a turn begins and [stop] in its finally block.
 */
class TurnForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notif: Notification = NotificationCompat.Builder(this, App.CHANNEL_BUSY)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Tang 正在处理…")
            .setContentText("正在生成回复 / 联网查询，请保持后台运行")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        // Android 14+ requires a declared foreground-service type; dataSync fits ongoing network work.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notif)
        }
        // Closes the start→stop race: if stop() was already requested before this delivery ran, we
        // still must call startForeground (5-second rule), then immediately tear down.
        if (stopRequested) stopSelf()
        return START_NOT_STICKY
    }

    companion object {
        private const val NOTIF_ID = 4711
        @Volatile private var stopRequested = false

        fun start(ctx: Context) {
            stopRequested = false
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, TurnForegroundService::class.java))
            } catch (_: Exception) {
                // e.g. background-start restrictions — keep-alive is best-effort, the turn still runs.
            }
        }

        fun stop(ctx: Context) {
            stopRequested = true
            try {
                ctx.stopService(Intent(ctx, TurnForegroundService::class.java))
            } catch (_: Exception) {
            }
        }
    }
}
