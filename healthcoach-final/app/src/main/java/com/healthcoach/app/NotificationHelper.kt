package com.healthcoach.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import java.time.Instant

class NotificationHelper(private val context: Context) {
    private val prefs = context.getSharedPreferences("healthcoach", Context.MODE_PRIVATE)

    fun maybeNotify(summary: HealthSummary) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        val sleepKey = summary.latestSleepEnd?.toString() ?: return
        val last = prefs.getString("last_notified_sleep", null)
        if (sleepKey == last) return

        val title = when {
            summary.recoveryScore >= 80 -> "Bonne récupération ce matin"
            summary.recoveryScore >= 60 -> "Récupération moyenne ce matin"
            else -> "Récupération faible ce matin"
        }

        val body = buildString {
            append("Score ")
            append(summary.recoveryScore)
            append("/100")
            summary.sleepMinutes?.let {
                append(" • sommeil ")
                append((it / 60.0 * 10).toInt() / 10.0)
                append(" h")
            }
            if (summary.recoveryScore < 60) append(" • privilégie une journée plus légère")
        }

        val nm = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(
                    "healthcoach",
                    "Conseils HealthCoach",
                    NotificationManager.IMPORTANCE_DEFAULT
                )
            )
        }

        val notification = NotificationCompat.Builder(context, "healthcoach")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .build()

        nm.notify(1001, notification)
        prefs.edit()
            .putString("last_notified_sleep", sleepKey)
            .putString("last_notification_at", Instant.now().toString())
            .apply()
    }
}
