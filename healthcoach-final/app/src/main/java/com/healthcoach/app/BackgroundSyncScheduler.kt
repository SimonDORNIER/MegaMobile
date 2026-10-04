package com.healthcoach.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Synchronisation automatique :
 * - AlarmManager exact toutes les N minutes quand Android l'autorise ;
 * - WorkManager périodique conservé comme filet de sécurité ;
 * - rattrapage à l'ouverture de l'app.
 */
object BackgroundSyncScheduler {
    private const val PERIODIC_NAME = "healthcoach_background_sync"
    private const val ALARM_WORK_NAME = "healthcoach_alarm_sync"
    private const val ALARM_REQUEST_CODE = 9201
    private const val ACTION_INTERVAL_SYNC = "com.healthcoach.app.INTERVAL_SYNC"

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

    fun enqueueImmediate(context: Context, source: String = "alarm") {
        context.getSharedPreferences("healthcoach", Context.MODE_PRIVATE)
            .edit()
            .putLong("last_alarm_fired_at", System.currentTimeMillis())
            .putString("last_alarm_source", source)
            .apply()

        val request = OneTimeWorkRequestBuilder<SyncWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            ALARM_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        return alarmManager.canScheduleExactAlarms()
    }

    fun scheduleNextAlarm(
        context: Context,
        minutes: Int = intervalMinutes(context)
    ) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val pending = PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST_CODE,
            Intent(context, IntervalSyncReceiver::class.java)
                .setAction(ACTION_INTERVAL_SYNC),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        alarmManager.cancel(pending)

        val delayMs = TimeUnit.MINUTES.toMillis(minutes.toLong())
        val triggerAt = SystemClock.elapsedRealtime() + delayMs
        val exact = canScheduleExact(context)

        if (exact) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAt,
                pending
            )
        } else {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAt,
                pending
            )
        }

        context.getSharedPreferences("healthcoach", Context.MODE_PRIVATE)
            .edit()
            .putLong("next_interval_alarm_at", System.currentTimeMillis() + delayMs)
            .putString("alarm_mode", if (exact) "exact" else "inexact")
            .apply()
    }

    fun shouldCatchUp(context: Context): Boolean {
        val lastDrive = SyncState.read(context).lastDriveSuccessAt
        if (lastDrive <= 0L) return true

        val intervalMs = TimeUnit.MINUTES.toMillis(intervalMinutes(context).toLong())
        val graceMs = TimeUnit.MINUTES.toMillis(5)
        return System.currentTimeMillis() - lastDrive > intervalMs + graceMs
    }

    private fun intervalMinutes(context: Context): Int =
        context.getSharedPreferences("healthcoach", Context.MODE_PRIVATE)
            .getInt("sync_minutes", 60)
            .coerceIn(15, 240)
}

class IntervalSyncReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        BackgroundSyncScheduler.enqueueImmediate(context, "alarm")
        BackgroundSyncScheduler.scheduleNextAlarm(context)
    }
}

class HealthCoachBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        BackgroundSyncScheduler.apply(context)
        FixedTimeSyncScheduler.apply(context)
    }
}
