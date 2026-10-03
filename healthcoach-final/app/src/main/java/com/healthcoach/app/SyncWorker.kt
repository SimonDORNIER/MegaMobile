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
        val source = "background"
        SyncState.recordAttempt(applicationContext, source)

        return try {
            if (HealthConnectClient.getSdkStatus(applicationContext) != HealthConnectClient.SDK_AVAILABLE) {
                SyncState.recordFailure(applicationContext, "Santé Connect indisponible", source)
                return Result.success()
            }

            val client = HealthConnectClient.getOrCreate(applicationContext)
            val granted = client.permissionController.getGrantedPermissions()

            if (!HealthPermissions.records.all { it in granted }) {
                SyncState.recordFailure(
                    applicationContext,
                    "Autorisations Santé Connect de lecture manquantes",
                    source
                )
                return Result.success()
            }

            if (HealthPermissions.BACKGROUND !in granted) {
                SyncState.recordFailure(
                    applicationContext,
                    "Lecture Santé Connect en arrière-plan non autorisée",
                    source
                )
                return Result.success()
            }

            val repo = HealthRepository(applicationContext)
            val summary = repo.load(client)
            val drive = DriveBridge(applicationContext)
            val driveWritten = if (drive.hasFolder()) {
                drive.writeAll(summary, repo.localHistoryJson())
            } else false

            if (drive.hasFolder() && !driveWritten) {
                SyncState.recordFailure(
                    applicationContext,
                    "Écriture Drive impossible",
                    source
                )
                return Result.retry()
            }

            SyncState.recordSuccess(applicationContext, driveWritten, source)
            NotificationHelper(applicationContext).maybeNotify(summary)
            Result.success()
        } catch (_: SecurityException) {
            SyncState.recordFailure(
                applicationContext,
                "Accès Santé Connect refusé en arrière-plan",
                source
            )
            Result.success()
        } catch (e: Exception) {
            SyncState.recordFailure(
                applicationContext,
                e.message ?: e.javaClass.simpleName,
                source
            )
            Result.retry()
        }
    }
}
