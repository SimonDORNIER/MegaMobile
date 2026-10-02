package fr.simondornier.healthbridge

import android.content.Context
import androidx.work.*
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.TimeUnit

class CommandWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val store = DriveTreeStore(applicationContext)
        if (!store.isConfigured(DriveTreeStore.COMMAND) && !store.isConfigured(DriveTreeStore.LIVE)) return Result.success()

        return try {
            val prefs = applicationContext.getSharedPreferences("health_bridge", Context.MODE_PRIVATE)
            val lastHandled = prefs.getString("last_command_id", null)
            val commandName = store.displayName(DriveTreeStore.COMMAND).orEmpty()
            val liveName = store.displayName(DriveTreeStore.LIVE).orEmpty()

            var requestId: String? = null
            if (commandName.startsWith("health_command_request_")) {
                requestId = commandName
            } else if (liveName.startsWith("health_live_request_")) {
                requestId = liveName
            } else if (store.isConfigured(DriveTreeStore.COMMAND)) {
                val raw = store.readText(DriveTreeStore.COMMAND)?.trim().orEmpty()
                if (raw.isNotBlank()) {
                    val json = runCatching { JSONObject(raw) }.getOrNull()
                    val action = json?.optString("action", "sync") ?: "sync"
                    val id = json?.optString("requestId").orEmpty()
                    if (id.isNotBlank() && action in setOf("sync", "sync_live", "sync_full")) requestId = id
                }
            }

            if (!requestId.isNullOrBlank() && requestId != lastHandled) {
                prefs.edit()
                    .putString("last_command_id", requestId)
                    .putString("last_command_seen_at", Instant.now().toString())
                    .remove("last_command_error")
                    .apply()
                SyncWorker.syncNow(applicationContext, requestId)
            }
            Result.success()
        } catch (e: Exception) {
            applicationContext.getSharedPreferences("health_bridge", Context.MODE_PRIVATE)
                .edit().putString("last_command_error", e.message ?: e.javaClass.simpleName).apply()
            Result.success()
        }
    }

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<CommandWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "health_bridge_command_poll",
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }

        fun checkNow(context: Context) {
            WorkManager.getInstance(context).enqueue(
                OneTimeWorkRequestBuilder<CommandWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build()
            )
        }
    }
}
