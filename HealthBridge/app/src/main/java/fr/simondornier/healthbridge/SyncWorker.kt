package fr.simondornier.healthbridge

import android.content.Context
import androidx.work.*
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

class SyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val store = DriveTreeStore(applicationContext)
        if (store.getFileUri() == null) return Result.success()

        return try {
            val exported = HealthExporter(applicationContext).exportLive(72)
            store.writeText(exported.liveJson)

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

        private val fixedTimes = listOf(
            7 to 45,
            8 to 20,
            11 to 0,
            13 to 30,
            16 to 0,
            18 to 30,
            21 to 0,
            23 to 30,
        )

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val now = ZonedDateTime.now()

            fixedTimes.forEach { (hour, minute) ->
                var next = now.toLocalDate().atTime(hour, minute).atZone(now.zone)
                if (!next.isAfter(now)) next = next.plusDays(1)
                val delayMinutes = Duration.between(now, next).toMinutes().coerceAtLeast(0)

                val request = PeriodicWorkRequestBuilder<SyncWorker>(24, TimeUnit.HOURS)
                    .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
                    .setConstraints(constraints)
                    .build()

                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    "health_bridge_fixed_%02d%02d".format(hour, minute),
                    ExistingPeriodicWorkPolicy.UPDATE,
                    request,
                )
            }
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
