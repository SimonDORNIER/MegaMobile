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
        val source = "arrière-plan"
        SyncState.attempt(applicationContext, source)

        return try {
            if (HealthConnectClient.getSdkStatus(applicationContext) != HealthConnectClient.SDK_AVAILABLE) {
                SyncState.failure(applicationContext, source, "Santé Connect indisponible")
                return Result.success()
            }

            val client = HealthConnectClient.getOrCreate(applicationContext)
            val granted = client.permissionController.getGrantedPermissions()

            val missingRecords = HealthPermissions.records.filterNot { it in granted }
            if (missingRecords.isNotEmpty()) {
                SyncState.failure(
                    applicationContext,
                    source,
                    "Autorisations Santé Connect manquantes"
                )
                return Result.success()
            }

            if (HealthPermissions.BACKGROUND !in granted) {
                SyncState.failure(
                    applicationContext,
                    source,
                    "Lecture Santé Connect en arrière-plan non autorisée"
                )
                return Result.success()
            }

            val repo = HealthRepository(applicationContext)
            val summary = repo.load(client)
            val drive = DriveBridge(applicationContext)

            val driveUpdated = if (drive.hasFolder()) {
                drive.writeAll(summary, repo.localHistoryJson())
            } else false

            if (drive.hasFolder() && !driveUpdated) {
                SyncState.failure(
                    applicationContext,
                    source,
                    "Écriture du dossier Drive impossible"
                )
                return Result.retry()
            }

            SyncState.success(applicationContext, source, driveUpdated)
            NotificationHelper(applicationContext).maybeNotify(summary)
            Result.success()
        } catch (e: SecurityException) {
            SyncState.failure(
                applicationContext,
                source,
                "Accès Santé Connect refusé en arrière-plan"
            )
            Result.success()
        } catch (e: Exception) {
            SyncState.failure(
                applicationContext,
                source,
                e.message ?: e.javaClass.simpleName
            )
            Result.retry()
        }
    }
}
