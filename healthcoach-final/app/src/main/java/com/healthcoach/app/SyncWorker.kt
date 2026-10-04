package com.healthcoach.app

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class SyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val ok = HealthSyncRunner.run(applicationContext, "workmanager")
        BackgroundSyncScheduler.scheduleNextAlarm(applicationContext)
        return if (ok) Result.success() else Result.retry()
    }
}
