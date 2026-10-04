package com.healthcoach.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Service court : une synchro puis arrêt.
 * Il est lancé par l'alarme périodique afin qu'Android exécute réellement
 * la synchro même lorsque l'application n'est pas ouverte.
 */
class HealthSyncService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var running = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(
            NOTIFICATION_ID,
            buildNotification("Synchronisation en cours…")
        )

        if (!running) {
            running = true
            scope.launch {
                val source = intent?.getStringExtra(EXTRA_SOURCE) ?: "alarm-service"
                val ok = HealthSyncRunner.run(this@HealthSyncService, source)

                getSystemService(NotificationManager::class.java).notify(
                    NOTIFICATION_ID,
                    buildNotification(
                        if (ok) "Synchronisation terminée"
                        else "Synchronisation échouée • nouvelle tentative prévue"
                    )
                )

                BackgroundSyncScheduler.scheduleNextAlarm(this@HealthSyncService)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Synchronisation HealthCoach",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Synchronisation automatique Santé Connect vers Drive."
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

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("HealthCoach")
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        const val EXTRA_SOURCE = "sync_source"
        private const val CHANNEL_ID = "healthcoach_sync"
        private const val NOTIFICATION_ID = 2200
    }
}
