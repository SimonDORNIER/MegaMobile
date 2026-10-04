package com.healthcoach.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Architecture de synchro automatique :
 * 1. AlarmManager réveille HealthCoach à l'intervalle choisi.
 * 2. L'alarme lance un service de premier plan très court qui effectue UNE synchro.
 * 3. WorkManager reste un filet de sécurité si Android refuse le service.
 * 4. À l'ouverture de l'app, un rattrapage est lancé si nécessaire.
 */
object BackgroundSyncScheduler {
    private const val PERIODIC_NAME = "healthcoach_background_sync"
    private const val IMMEDIATE_WORK_NAME = "healthcoach_immediate_sync"
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

    fun enqueueImmediate(context: Context) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            IMMEDIATE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    fun launchAlarmSync(context: Context) {
        val prefs = context.getSharedPreferences("healthcoach", Context.MODE_PRIVATE)

        val started = runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, HealthSyncService::class.java)
                    .putExtra(HealthSyncService.EXTRA_SOURCE, "alarm")
            )
        }

        if (started.isSuccess) {
            prefs.edit()
                .putLong("last_sync_service_started_at", System.currentTimeMillis())
                .remove("foreground_service_start_error")
                .apply()
        } else {
            prefs.edit()
                .putString(
                    "foreground_service_start_error",
                    started.exceptionOrNull()?.message
                        ?: started.exceptionOrNull()?.javaClass?.simpleName
                        ?: "démarrage refusé"
                )
                .apply()
            enqueueImmediate(context)
        }
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

        // launchAlarmSync utilise WorkManager en secours uniquement si Android
        // refuse le service de premier plan.
        BackgroundSyncScheduler.launchAlarmSync(context)
        BackgroundSyncScheduler.scheduleNextAlarm(context)
    }
}

class HealthCoachBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        BackgroundSyncScheduler.apply(context)
        FixedTimeSyncScheduler.apply(context)

        context.getSharedPreferences("healthcoach", Context.MODE_PRIVATE)
            .edit()
            .putLong("last_boot_restore_at", System.currentTimeMillis())
            .apply()
    }
}
