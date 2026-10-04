package com.healthcoach.app

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object HealthSyncRunner {
    private val mutex = Mutex()

    suspend fun run(context: Context, source: String): Boolean = mutex.withLock {
        val previous = SyncState.read(context)
        val isAutomatic = source != "foreground"
        if (isAutomatic &&
            previous.lastDriveSuccessAt > 0L &&
            System.currentTimeMillis() - previous.lastDriveSuccessAt < 120_000L
        ) {
            context.getSharedPreferences("healthcoach", Context.MODE_PRIVATE)
                .edit()
                .putLong("last_worker_started_at", System.currentTimeMillis())
                .putLong("last_worker_finished_at", System.currentTimeMillis())
                .putString("last_worker_result", "skipped_recent_success")
                .apply()
            return@withLock true
        }

        SyncState.recordAttempt(context, source)
        context.getSharedPreferences("healthcoach", Context.MODE_PRIVATE)
            .edit()
            .putLong("last_worker_started_at", System.currentTimeMillis())
            .apply()

        try {
            if (HealthConnectClient.getSdkStatus(context) != HealthConnectClient.SDK_AVAILABLE) {
                SyncState.recordFailure(context, "Santé Connect indisponible", source)
                recordEnd(context, "health_connect_unavailable")
                false
            } else {
                val client = HealthConnectClient.getOrCreate(context)
                val granted = client.permissionController.getGrantedPermissions()

                when {
                    !HealthPermissions.records.all { it in granted } -> {
                        SyncState.recordFailure(
                            context,
                            "Autorisations Santé Connect de lecture manquantes",
                            source
                        )
                        recordEnd(context, "permissions_missing")
                        false
                    }
                    HealthPermissions.BACKGROUND !in granted -> {
                        SyncState.recordFailure(
                            context,
                            "Lecture Santé Connect en arrière-plan non autorisée",
                            source
                        )
                        recordEnd(context, "background_permission_missing")
                        false
                    }
                    else -> {
                        val repo = HealthRepository(context)
                        val summary = repo.load(client)
                        val drive = DriveBridge(context)
                        val driveWritten = if (drive.hasFolder()) {
                            drive.writeAll(summary, repo.localHistoryJson())
                        } else false

                        if (drive.hasFolder() && !driveWritten) {
                            SyncState.recordFailure(context, "Écriture Drive impossible", source)
                            recordEnd(context, "drive_error")
                            false
                        } else {
                            SyncState.recordSuccess(context, driveWritten, source)
                            recordEnd(context, "success")
                            if (driveWritten) {
                                drive.writeSyncDiagnostics()
                            }
                            NotificationHelper(context).maybeNotify(summary)
                            true
                        }
                    }
                }
            }
        } catch (_: SecurityException) {
            SyncState.recordFailure(
                context,
                "Accès Santé Connect refusé en arrière-plan",
                source
            )
            recordEnd(context, "security_error")
            false
        } catch (e: Exception) {
            SyncState.recordFailure(
                context,
                e.message ?: e.javaClass.simpleName,
                source
            )
            recordEnd(context, "error")
            false
        }
    }

    private fun recordEnd(context: Context, result: String) {
        context.getSharedPreferences("healthcoach", Context.MODE_PRIVATE)
            .edit()
            .putLong("last_worker_finished_at", System.currentTimeMillis())
            .putString("last_worker_result", result)
            .apply()
    }
}
