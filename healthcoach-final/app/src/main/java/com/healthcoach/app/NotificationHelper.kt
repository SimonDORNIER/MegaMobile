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
import kotlin.math.roundToInt

class NotificationHelper(private val context: Context) {
    private val prefs = context.getSharedPreferences("healthcoach", Context.MODE_PRIVATE)

    private fun allowed(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

    private fun manager(): NotificationManager {
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
        return nm
    }

    fun maybeNotify(summary: HealthSummary) {
        if (!allowed()) return
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
                append((it / 60.0 * 10).roundToInt() / 10.0)
                append(" h")
            }
            if (summary.recoveryScore < 60) append(" • privilégie une journée plus légère")
        }

        manager().notify(
            1001,
            NotificationCompat.Builder(context, "healthcoach")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .build()
        )

        prefs.edit()
            .putString("last_notified_sleep", sleepKey)
            .putString("last_notification_at", Instant.now().toString())
            .apply()
    }

    fun routineNotify(slot: String, summary: HealthSummary) {
        if (!allowed()) return
        val today = java.time.LocalDate.now().toString()
        val key = "routine_" + slot + "_" + today
        if (prefs.getBoolean(key, false)) return

        val title: String
        val body: String
        when (slot) {
            "morning" -> { title = "Bilan santé du matin"; body = morningAdvice(summary) }
            "midday" -> { title = "Point santé de midi"; body = middayAdvice(summary) }
            else -> { title = "Bilan santé du soir"; body = eveningAdvice(summary) }
        }

        val id = when (slot) {
            "morning" -> 1101
            "midday" -> 1102
            else -> 1103
        }

        manager().notify(
            id,
            NotificationCompat.Builder(context, "healthcoach")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .build()
        )
        prefs.edit().putBoolean(key, true).apply()
    }

    private fun morningAdvice(s: HealthSummary): String {
        val parts = mutableListOf<String>()
        parts += "Récupération " + s.recoveryScore + "/100 (" + s.recoveryLabel.lowercase() + ")."
        if (s.sleepMinutes != null && s.baseline30.sleepMinutes != null) {
            val delta = s.sleepMinutes - s.baseline30.sleepMinutes
            if (delta < -45) parts += "Sommeil nettement sous ta moyenne."
            else if (delta > 30) parts += "Sommeil au-dessus de ta moyenne."
        }
        if (s.hrv != null && s.baseline30.hrv != null && s.baseline30.hrv > 0 && s.hrv < s.baseline30.hrv * 0.85) {
            parts += "VFC basse pour toi."
        }
        parts += when {
            s.recoveryScore < 60 -> "Aujourd’hui : activité légère, marche et mobilité."
            s.recoveryScore < 80 -> "Aujourd’hui : activité normale à modérée."
            else -> "Aujourd’hui : activité normale, plus soutenue si tu te sens bien."
        }
        return parts.joinToString(" ")
    }

    private fun middayAdvice(s: HealthSummary): String {
        val target = s.baseline30.steps
        val parts = mutableListOf<String>()
        parts += s.stepsToday.toString() + " pas • " + String.format(java.util.Locale.FRANCE, "%.1f km", s.distanceTodayMeters / 1000.0) + "."
        if (target != null && target > 0) {
            val ratio = s.stepsToday / target
            parts += when {
                ratio < 0.25 -> "Journée calme : 10–15 min de marche seraient utiles."
                ratio < 0.60 -> "Activité correcte pour midi : continue à bouger un peu."
                else -> "Tu es déjà bien actif aujourd’hui."
            }
        }
        if (s.recoveryScore < 60) parts += "Garde l’effort doux cet après-midi."
        return parts.joinToString(" ")
    }

    private fun eveningAdvice(s: HealthSummary): String {
        val parts = mutableListOf<String>()
        parts += s.stepsToday.toString() + " pas aujourd’hui."
        val target = s.baseline30.steps
        if (target != null && target > 0) {
            parts += if (s.stepsToday < target * 0.7)
                "Si tu as encore de l’énergie : petite marche tranquille."
            else
                "Activité du jour proche ou au-dessus de ton habitude."
        }
        if (s.recoveryScore < 70) parts += "Priorité à la récupération et à une nuit suffisamment longue."
        else parts += "Prépare une heure de coucher régulière."
        return parts.joinToString(" ")
    }
}