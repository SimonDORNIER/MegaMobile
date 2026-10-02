package fr.simondornier.healthbridge

import android.content.Context
import androidx.work.*
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

class SyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val store = DriveTreeStore(applicationContext)
        if (!store.isConfigured(DriveTreeStore.LIVE)) return Result.success()

        val commandId = inputData.getString(KEY_COMMAND_ID)
        val trigger = if (commandId.isNullOrBlank()) inputData.getString(KEY_TRIGGER) ?: "scheduled" else "remote"

        return try {
            val exported = HealthExporter(applicationContext).exportLive(72)
            store.writeText(DriveTreeStore.LIVE, exported.liveJson)

            if (store.isConfigured(DriveTreeStore.HISTORY)) {
                val previous = runCatching { store.readText(DriveTreeStore.HISTORY) }.getOrNull()
                val merged = HistoryManager.merge(previous, exported.liveJson)
                store.writeText(DriveTreeStore.HISTORY, merged)
            }

            val status = JSONObject(exported.statusJson).apply {
                put("trigger", trigger)
                put("appVersion", BuildConfig.VERSION_NAME)
                put("fixedSchedule", "07:45,08:20,11:00,13:30,16:00,18:30,21:00,23:30")
                put("commandPollingMinutes", 15)
                put("historyConfigured", store.isConfigured(DriveTreeStore.HISTORY))
                if (!commandId.isNullOrBlank()) {
                    put("lastCommandId", commandId)
                    put("lastCommandCompletedAt", Instant.now().toString())
                }
            }
            if (store.isConfigured(DriveTreeStore.STATUS)) {
                store.writeText(DriveTreeStore.STATUS, status.toString(2))
            }

            applicationContext.getSharedPreferences("health_bridge", Context.MODE_PRIVATE)
                .edit()
                .putString("last_sync", Instant.now().toString())
                .putString("last_sync_trigger", trigger)
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
        private const val KEY_COMMAND_ID = "command_id"
        private const val KEY_TRIGGER = "trigger"

        val fixedTimes = listOf(
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
                    .setInputData(workDataOf(KEY_TRIGGER to "fixed"))
                    .setConstraints(constraints)
                    .build()

                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    "health_bridge_fixed_%02d%02d".format(hour, minute),
                    ExistingPeriodicWorkPolicy.UPDATE,
                    request,
                )
            }

            CommandWorker.schedule(context)
        }

        fun syncNow(context: Context, commandId: String? = null) {
            val data = if (commandId.isNullOrBlank())
                workDataOf(KEY_TRIGGER to "manual")
            else
                workDataOf(KEY_TRIGGER to "remote", KEY_COMMAND_ID to commandId)

            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setInputData(data)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }

        fun displaySchedule(): String = fixedTimes.joinToString(" · ") { (h, m) -> "%02d:%02d".format(h, m) }
    }
}
