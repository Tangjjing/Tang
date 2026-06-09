package com.dschat.app.agent.tasks

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Schedules the periodic portfolio P&L check via WorkManager (same approach as the weather monitor). */
object PortfolioScheduler {
    const val CHANNEL_ID = "portfolio"
    private const val WORK = "portfolio_monitor"

    fun enqueue(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<PortfolioWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    fun cancel(ctx: Context) {
        WorkManager.getInstance(ctx).cancelUniqueWork(WORK)
    }
}

class PortfolioWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = try {
        PortfolioMonitor.runCheck(applicationContext)
        Result.success()
    } catch (e: Exception) {
        Result.retry()
    }
}
