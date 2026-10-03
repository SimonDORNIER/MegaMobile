package com.healthcoach.app

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Heures fixes de synchronisation facultatives.
 *
 * Ce planificateur ne produit aucun bilan/rappel à heure fixe : il met seulement
 * les fichiers HealthCoach à jour. Les bilans et rappels restent gérés côté ChatGPT.
 */
object FixedTimeSyncScheduler {
    private const val PREF_FIXED_TIMES = "sync_fixed_times"
    private const val PREF_SCHEDULED_FIXED_TIMES = "scheduled_fixed_times"

    fun apply(context: Context) {
        val prefs = context.getSharedPreferences("healthcoach", Context.MODE_PRIVATE)
        val wm = WorkManager.getInstance(context)

        // Nettoyage des routines historiques des versions précédentes.
        listOf("morning", "midday", "evening").forEach {
            wm.cancelUniqueWork("healthcoach_routine_" + it)
        }

        parse(prefs.getString(PREF_SCHEDULED_FIXED_TIMES, "") ?: "").forEach { time ->
            wm.cancelUniqueWork(workName(time))
        }

        val times = parse(prefs.getString(PREF_FIXED_TIMES, "") ?: "")
        times.forEach { scheduleOne(context, it) }

        prefs.edit()
            .putString(PREF_SCHEDULED_FIXED_TIMES, times.joinToString(",") { format(it) })
            .apply()
    }

    fun isConfigured(context: Context, hour: Int, minute: Int): Boolean {
        val raw = context.getSharedPreferences("healthcoach", Context.MODE_PRIVATE)
            .getString(PREF_FIXED_TIMES, "") ?: ""
        return parse(raw).any { it.hour == hour && it.minute == minute }
    }

    fun scheduleOne(context: Context, time: LocalTime) {
        val now = ZonedDateTime.now()
        var target = now.withHour(time.hour).withMinute(time.minute).withSecond(0).withNano(0)
        if (!target.isAfter(now)) target = target.plusDays(1)

        val delayMs = java.time.Duration.between(now, target).toMillis().coerceAtLeast(0L)
        val input = Data.Builder()
            .putInt("target_hour", time.hour)
            .putInt("target_minute", time.minute)
            .build()

        val request = OneTimeWorkRequestBuilder<FixedTimeSyncWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(input)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            workName(time),
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    fun normalize(raw: String): String = parse(raw).joinToString(", ") { format(it) }

    private fun parse(raw: String): List<LocalTime> =
        raw.split(",")
            .map { it.trim() }
            .filter { it.matches(Regex("^([01]\\d|2[0-3]):[0-5]\\d$")) }
            .map { LocalTime.parse(it) }
            .distinct()
            .sorted()
            .take(8)

    private fun format(time: LocalTime): String =
        "%02d:%02d".format(time.hour, time.minute)

    private fun workName(time: LocalTime): String =
        "healthcoach_fixed_%02d%02d".format(time.hour, time.minute)
}

class FixedTimeSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val hour = inputData.getInt("target_hour", -1)
        val minute = inputData.getInt("target_minute", -1)
        if (hour !in 0..23 || minute !in 0..59) return Result.failure()

        return try {
            if (HealthConnectClient.getSdkStatus(applicationContext) == HealthConnectClient.SDK_AVAILABLE) {
                val client = HealthConnectClient.getOrCreate(applicationContext)
                val repo = HealthRepository(applicationContext)
                val summary = repo.load(client)
                val drive = DriveBridge(applicationContext)
                val driveWritten = if (drive.hasFolder()) {
                    drive.writeAll(summary, repo.localHistoryJson())
                } else false

                if (drive.hasFolder() && !driveWritten) {
                    SyncState.recordFailure(applicationContext, "Écriture Drive impossible", "fixed-time")
                } else {
                    SyncState.recordSuccess(applicationContext, driveWritten, "fixed-time")
                }
            }

            if (FixedTimeSyncScheduler.isConfigured(applicationContext, hour, minute)) {
                FixedTimeSyncScheduler.scheduleOne(applicationContext, LocalTime.of(hour, minute))
            }
            Result.success()
        } catch (_: SecurityException) {
            if (FixedTimeSyncScheduler.isConfigured(applicationContext, hour, minute)) {
                FixedTimeSyncScheduler.scheduleOne(applicationContext, LocalTime.of(hour, minute))
            }
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
