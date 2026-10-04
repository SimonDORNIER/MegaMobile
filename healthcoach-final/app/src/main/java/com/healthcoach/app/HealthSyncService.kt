package com.healthcoach.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * Mode fiable : service au premier plan silencieux.
 * Android garde le processus prioritaire et HealthCoach déclenche une synchro
 * toutes les X minutes, même lorsque l'écran est éteint.
 */
class HealthSyncService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startAsForeground("Synchronisation automatique active")
        startLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground("Synchronisation automatique active")
        if (loopJob?.isActive != true) startLoop()
        return START_STICKY
    }

    override fun onDestroy() {
        loopJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startLoop() {
        loopJob?.cancel()
        loopJob = scope.launch {
            while (isActive) {
                getSharedPreferences("healthcoach", Context.MODE_PRIVATE)
                    .edit()
                    .putLong("foreground_service_alive_at", System.currentTimeMillis())
                    .apply()

                val minutes = BackgroundSyncScheduler.intervalMinutes(this@HealthSyncService)
                val intervalMs = TimeUnit.MINUTES.toMillis(minutes.toLong())
                val lastDrive = SyncState.read(this@HealthSyncService).lastDriveSuccessAt
                val due = lastDrive <= 0L ||
                    System.currentTimeMillis() - lastDrive >= intervalMs - TimeUnit.MINUTES.toMillis(1)

                val ok = if (due) {
                    HealthSyncRunner.run(this@HealthSyncService, "foreground-service")
                } else {
                    true
                }

                val message = if (ok) {
                    "Synchro auto active"
                } else {
                    "Synchro active • nouvelle tentative prévue"
                }

                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, buildNotification(message))

                BackgroundSyncScheduler.scheduleNextAlarm(this@HealthSyncService, minutes)

                delay(intervalMs)
            }
        }
    }

    private fun startAsForeground(text: String) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
        } else {
            0
        }

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(text),
            type
        )
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Synchronisation HealthCoach",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Maintient la synchronisation Santé Connect vers Drive active."
                setShowBadge(false)
            }
        )
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            2200,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val minutes = BackgroundSyncScheduler.intervalMinutes(this)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("HealthCoach")
            .setContentText(text + " • toutes les " + minutes + " min")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "healthcoach_sync"
        private const val NOTIFICATION_ID = 2200
    }
}
