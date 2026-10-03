package com.healthcoach.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Synchronisation robuste :
 * - WorkManager périodique comme mécanisme principal ;
 * - alarme inexacte autorisée en veille comme filet de sécurité ;
 * - rattrapage à la réouverture de l'app si la dernière synchro est trop ancienne.
 */
object BackgroundSyncScheduler {
    private const val PERIODIC_NAME = "healthcoach_background_sync"
    private const val ALARM_WORK_NAME = "healthcoach_alarm_sync"
    private const val ALARM_REQUEST_CODE = 9201

    fun apply(context: Context) {
        val minutes = intervalMinutes(context)

        val periodic = PeriodicWorkRequestBuilder<SyncWorker>(
            minutes.toLong(),
            TimeUnit.MINUTES
        ).build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            periodic
        )

        scheduleNextAlarm(context, minutes)
    }

    fun enqueueImmediate(context: Context) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            ALARM_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    fun scheduleNextAlarm(context: Context, minutes: Int = intervalMinutes(context)) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val pending = PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST_CODE,
            Intent(context, IntervalSyncReceiver::class.java)
                .setAction("com.healthcoach.app.INTERVAL_SYNC"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        alarmManager.cancel(pending)

        val delayMs = TimeUnit.MINUTES.toMillis(minutes.toLong())
        val elapsedTrigger = SystemClock.elapsedRealtime() + delayMs
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            elapsedTrigger,
            pending
        )

        context.getSharedPreferences("healthcoach", Context.MODE_PRIVATE)
            .edit()
            .putLong("next_interval_alarm_at", System.currentTimeMillis() + delayMs)
            .apply()
    }

    fun shouldCatchUp(context: Context): Boolean {
        val prefs = context.getSharedPreferences("healthcoach", Context.MODE_PRIVATE)
        val lastDrive = prefs.getLong("last_drive_sync_at", 0L)
        if (lastDrive <= 0L) return true

        val intervalMs = TimeUnit.MINUTES.toMillis(intervalMinutes(context).toLong())
        val graceMs = TimeUnit.MINUTES.toMillis(10)
        return System.currentTimeMillis() - lastDrive > intervalMs + graceMs
    }

    private fun intervalMinutes(context: Context): Int =
        context.getSharedPreferences("healthcoach", Context.MODE_PRIVATE)
            .getInt("sync_minutes", 60)
            .coerceIn(15, 240)
}

class IntervalSyncReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        BackgroundSyncScheduler.enqueueImmediate(context)
        BackgroundSyncScheduler.scheduleNextAlarm(context)
    }
}

class HealthCoachBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        BackgroundSyncScheduler.apply(context)
        FixedTimeSyncScheduler.apply(context)
    }
}
