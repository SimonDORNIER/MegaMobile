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
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

object RoutineScheduler {
    private val slots = listOf(
        Triple("morning", 8, 50),
        Triple("midday", 11, 50),
        Triple("evening", 19, 50)
    )

    fun scheduleAll(context: Context) {
        slots.forEach { (name, hour, minute) -> scheduleOne(context, name, hour, minute) }
    }

    fun scheduleOne(context: Context, name: String, hour: Int, minute: Int) {
        val now = ZonedDateTime.now()
        var target = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        if (!target.isAfter(now)) target = target.plusDays(1)
        val delayMs = java.time.Duration.between(now, target).toMillis().coerceAtLeast(0L)
        val input = Data.Builder()
            .putString("slot_name", name)
            .putInt("target_hour", hour)
            .putInt("target_minute", minute)
            .build()
        val request = OneTimeWorkRequestBuilder<RoutineSyncWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(input)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "healthcoach_routine_" + name, ExistingWorkPolicy.REPLACE, request
        )
    }
}

class RoutineSyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val name = inputData.getString("slot_name") ?: "routine"
        val hour = inputData.getInt("target_hour", 8)
        val minute = inputData.getInt("target_minute", 50)
        return try {
            if (HealthConnectClient.getSdkStatus(applicationContext) == HealthConnectClient.SDK_AVAILABLE) {
                val client = HealthConnectClient.getOrCreate(applicationContext)
                val repo = HealthRepository(applicationContext)
                val summary = repo.load(client)
                val drive = DriveBridge(applicationContext)
                if (drive.hasFolder()) drive.writeAll(summary, repo.localHistoryJson())
            }
            RoutineScheduler.scheduleOne(applicationContext, name, hour, minute)
            Result.success()
        } catch (_: SecurityException) {
            RoutineScheduler.scheduleOne(applicationContext, name, hour, minute)
            Result.success()
        } catch (_: Exception) {
            RoutineScheduler.scheduleOne(applicationContext, name, hour, minute)
            Result.retry()
        }
    }
}