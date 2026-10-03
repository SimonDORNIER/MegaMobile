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
                SyncState.recordFailure(
                    applicationContext,
                    "Santé Connect indisponible",
                    "background"
                )
                return Result.success()
            }

            val client = HealthConnectClient.getOrCreate(applicationContext)
            val repo = HealthRepository(applicationContext)
            val summary = repo.load(client)
            val drive = DriveBridge(applicationContext)

            val driveWritten = if (drive.hasFolder()) {
                drive.writeAll(summary, repo.localHistoryJson())
            } else {
                false
            }

            if (drive.hasFolder() && !driveWritten) {
                SyncState.recordFailure(
                    applicationContext,
                    "Écriture Drive impossible",
                    "background"
                )
                return Result.retry()
            }

            SyncState.recordSuccess(
                applicationContext,
                driveWritten = driveWritten,
                source = "background"
            )

            NotificationHelper(applicationContext).maybeNotify(summary)
            Result.success()
        } catch (_: SecurityException) {
            SyncState.recordFailure(
                applicationContext,
                "Autorisation Santé Connect manquante",
                "background"
            )
            Result.success()
        } catch (e: Exception) {
            SyncState.recordFailure(
                applicationContext,
                e.message ?: e.javaClass.simpleName,
                "background"
            )
            Result.retry()
        }
    }
}
