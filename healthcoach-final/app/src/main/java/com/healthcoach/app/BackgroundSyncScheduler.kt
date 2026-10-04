package com.healthcoach.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.core.content.ContextCompat
import java.util.concurrent.TimeUnit

/**
 * Synchronisation arrière-plan renforcée :
 * - WorkManager périodique = filet de sécurité Android ;
 * - AlarmManager = réveil à l'intervalle demandé ;
 * - alarme exacte si l'utilisateur a accordé l'accès spécial ;
 * - travail expedited à chaque réveil ;
 * - rattrapage à la réouverture si nécessaire.
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

        if (shouldCatchUp(context)) {
            enqueueImmediate(context)
        }
    }

    fun startReliableService(context: Context) {
        context.getSharedPreferences("healthcoach", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("reliable_sync_enabled", true)
            .apply()

        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, HealthSyncService::class.java)
            )
        } catch (_: Exception) {
            enqueueImmediate(context)
        }
    }

    fun stopReliableService(context: Context) {
        context.getSharedPreferences("healthcoach", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("reliable_sync_enabled", false)
            .apply()
        context.stopService(Intent(context, HealthSyncService::class.java))
    }

    fun enqueueImmediate(context: Context) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            ALARM_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
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
                .setAction("com.healthcoach.app.INTERVAL_SYNC"),
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
            .putBoolean("last_alarm_was_exact", exact)
            .apply()
    }

    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return context.getSystemService(AlarmManager::class.java)
            .canScheduleExactAlarms()
    }

    fun batteryOptimizationIgnored(context: Context): Boolean {
        val pm = context.getSystemService(PowerManager::class.java)
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun shouldCatchUp(context: Context): Boolean {
        val lastDrive = SyncState.read(context).lastDriveSuccessAt
        if (lastDrive <= 0L) return true

        val intervalMs = TimeUnit.MINUTES.toMillis(intervalMinutes(context).toLong())
        val graceMs = TimeUnit.MINUTES.toMillis(5)
        return System.currentTimeMillis() - lastDrive > intervalMs + graceMs
    }

    fun intervalMinutes(context: Context): Int =
        context.getSharedPreferences("healthcoach", Context.MODE_PRIVATE)
            .getInt("sync_minutes", 60)
            .coerceIn(15, 240)
}

class IntervalSyncReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        context.getSharedPreferences("healthcoach", Context.MODE_PRIVATE)
            .edit()
            .putLong("last_alarm_fired_at", System.currentTimeMillis())
            .apply()

        val reliable = context.getSharedPreferences("healthcoach", Context.MODE_PRIVATE)
            .getBoolean("reliable_sync_enabled", true)

        if (reliable && BackgroundSyncScheduler.canScheduleExact(context)) {
            BackgroundSyncScheduler.startReliableService(context)
        } else {
            BackgroundSyncScheduler.enqueueImmediate(context)
        }
        BackgroundSyncScheduler.scheduleNextAlarm(context)
    }
}

class HealthCoachBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        BackgroundSyncScheduler.apply(context)
        FixedTimeSyncScheduler.apply(context)

        // Le service de premier plan ne peut pas toujours être relancé directement
        // au démarrage par Android. L'alarme/WorkManager le relancera ensuite.
        context.getSharedPreferences("healthcoach", Context.MODE_PRIVATE)
            .edit()
            .putLong("last_boot_restore_at", System.currentTimeMillis())
            .apply()
    }
}
