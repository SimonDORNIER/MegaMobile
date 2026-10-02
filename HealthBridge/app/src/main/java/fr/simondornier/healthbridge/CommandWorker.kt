package fr.simondornier.healthbridge

import android.content.Context
import androidx.work.*
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.TimeUnit

class CommandWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val store = DriveTreeStore(applicationContext)
        if (!store.isConfigured(DriveTreeStore.COMMAND)) return Result.success()

        return try {
            val raw = store.readText(DriveTreeStore.COMMAND)?.trim().orEmpty()
            if (raw.isBlank()) return Result.success()
            val cmd = JSONObject(raw)
            val requestId = cmd.optString("requestId")
            val action = cmd.optString("action", "sync")
            val prefs = applicationContext.getSharedPreferences("health_bridge", Context.MODE_PRIVATE)
            val lastHandled = prefs.getString("last_command_id", null)

            if (requestId.isNotBlank() && requestId != lastHandled && action in setOf("sync", "sync_full", "sync_live")) {
                prefs.edit()
                    .putString("last_command_id", requestId)
                    .putString("last_command_seen_at", Instant.now().toString())
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
