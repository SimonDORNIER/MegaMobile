package com.healthcoach.app

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class SyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            if (HealthConnectClient.getSdkStatus(applicationContext) != HealthConnectClient.SDK_AVAILABLE) {
                return Result.success()
            }
            val client = HealthConnectClient.getOrCreate(applicationContext)
            val repo = HealthRepository(applicationContext)
            val summary = repo.load(client)
            val drive = DriveBridge(applicationContext)
            if (drive.hasFolder()) {
                drive.writeAll(summary, repo.localHistoryJson())
            }
            NotificationHelper(applicationContext).maybeNotify(summary)
            Result.success()
        } catch (_: SecurityException) {
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
