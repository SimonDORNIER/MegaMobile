package fr.simondornier.healthbridge

import android.content.Context
import androidx.work.*
import java.time.Instant
import java.util.concurrent.TimeUnit

class SyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val store = DriveTreeStore(applicationContext)
        if (store.getTreeUri() == null) return Result.success()

        return try {
            val exported = HealthExporter(applicationContext).exportLive(72)
            store.writeText("health_live.json", exported.liveJson)
            store.writeText("health_status.json", exported.statusJson)

            applicationContext.getSharedPreferences("health_bridge", Context.MODE_PRIVATE)
                .edit()
                .putString("last_sync", Instant.now().toString())
                .putInt("last_error_count", exported.errorCount)
                .remove("last_error")
                .apply()
            Result.success()
        } catch (e: SecurityException) {
            saveError("Autorisation manquante: ${e.message ?: e.javaClass.simpleName}")
            Result.success()
        } catch (e: Exception) {
            saveError(e.message ?: e.javaClass.simpleName)
            Result.retry()
        }
    }

    private fun saveError(message: String) {
        applicationContext.getSharedPreferences("health_bridge", Context.MODE_PRIVATE)
            .edit().putString("last_error", message).apply()
    }

    companion object {
        const val PERIODIC_NAME = "health_bridge_periodic_sync"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<SyncWorker>(30, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }

        fun syncNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
