package com.healthcoach.app

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var healthClient: HealthConnectClient? = null
    private lateinit var status: TextView
    private lateinit var content: LinearLayout
    private var lastSummary: Summary? = null

    private val permissions = setOf(
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(RestingHeartRateRecord::class),
        HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class),
        HealthPermission.getReadPermission(RespiratoryRateRecord::class),
        HealthPermission.getReadPermission(StepsRecord::class)
    )

    private val permissionLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        if (granted.containsAll(permissions)) sync()
        else setStatus("Autorisations incomplètes — tu peux les modifier dans Santé Connect.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        when (HealthConnectClient.getSdkStatus(this)) {
            HealthConnectClient.SDK_AVAILABLE -> {
                healthClient = HealthConnectClient.getOrCreate(this)
                scope.launch { checkPermissionsAndSync() }
            }
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ->
                setStatus("Santé Connect doit être mis à jour.")
            else -> setStatus("Santé Connect n'est pas disponible sur cet appareil.")
        }
    }

    private fun buildUi() {
        window.statusBarColor = Color.rgb(16, 19, 26)
        window.navigationBarColor = Color.rgb(16, 19, 26)
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.rgb(16, 19, 26)) }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(32))
        }
        scroll.addView(content)
        content.addView(text("HealthCoach", 30f, true, Color.WHITE))
        content.addView(text("Ton tableau de bord Santé Connect", 15f, false, Color.rgb(174,184,199)).apply {
            setPadding(0, dp(2), 0, dp(16))
        })
        status = text("Initialisation…", 14f, false, Color.rgb(138,180,248))
        content.addView(status)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(14), 0, dp(10))
        }
        actions.addView(button("Synchroniser") { sync() },
            LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginEnd = dp(6) })
        actions.addView(button("Partager à ChatGPT") { shareSummary() },
            LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginStart = dp(6) })
        content.addView(actions)
        setContentView(scroll)
    }

    private suspend fun checkPermissionsAndSync() {
        val client = healthClient ?: return
        val granted = client.permissionController.getGrantedPermissions()
        if (granted.containsAll(permissions)) sync() else permissionLauncher.launch(permissions)
    }

    private fun sync() {
        val client = healthClient ?: return
        setStatus("Synchronisation Santé Connect…")
        scope.launch {
            try {
                val summary = withContext(Dispatchers.IO) { loadSummary(client) }
                lastSummary = summary
                render(summary)
                val now = DateTimeFormatter.ofPattern("HH:mm").withLocale(Locale.FRANCE)
                    .format(java.time.ZonedDateTime.now())
                setStatus("À jour • " + now)
            } catch (e: Exception) {
                setStatus("Lecture impossible : " + (e.message ?: e.javaClass.simpleName))
            }
        }
    }

    private suspend fun loadSummary(client: HealthConnectClient): Summary {
        val now = Instant.now()
        val filter30 = TimeRangeFilter.between(now.minus(Duration.ofDays(30)), now)

        val sleeps = client.readRecords(
            ReadRecordsRequest(SleepSessionRecord::class, filter30)
        ).records.sortedBy { it.endTime }

        val rhr = client.readRecords(
            ReadRecordsRequest(RestingHeartRateRecord::class, filter30)
        ).records.sortedBy { it.time }

        val hrv = client.readRecords(
            ReadRecordsRequest(HeartRateVariabilityRmssdRecord::class, filter30)
        ).records.sortedBy { it.time }

        val resp = client.readRecords(
            ReadRecordsRequest(RespiratoryRateRecord::class, filter30)
        ).records.sortedBy { it.time }

        val zone = ZoneId.systemDefault()
        val todayStart = LocalDate.now(zone).atStartOfDay(zone).toInstant()
        val steps = client.readRecords(
            ReadRecordsRequest(StepsRecord::class, TimeRangeFilter.between(todayStart, now))
        ).records.sumOf { it.count }

        val latestSleep = sleeps.lastOrNull()
        val sleepMin = latestSleep?.let {
            Duration.between(it.startTime, it.endTime).toMinutes().toDouble()
        }
        val sleepAvg = sleeps.map {
            Duration.between(it.startTime, it.endTime).toMinutes().toDouble()
        }.averageOrNull()

        val latestRhr = rhr.lastOrNull()?.beatsPerMinute?.toDouble()
        val avgRhr = rhr.map { it.beatsPerMinute.toDouble() }.averageOrNull()
        val latestHrv = hrv.lastOrNull()?.heartRateVariabilityMillis
        val avgHrv = hrv.map { it.heartRateVariabilityMillis }.averageOrNull()
        val latestResp = resp.lastOrNull()?.rate
        val avgResp = resp.map { it.rate }.averageOrNull()

        var score = 80.0
        if (sleepMin != null && sleepAvg != null && sleepAvg > 0)
            score += ((sleepMin / sleepAvg) - 1.0) * 35.0
        if (latestHrv != null && avgHrv != null && avgHrv > 0)
            score += ((latestHrv / avgHrv) - 1.0) * 25.0
        if (latestRhr != null && avgRhr != null)
            score -= (latestRhr - avgRhr) * 2.0
        score = score.coerceIn(20.0, 100.0)

        return Summary(
            sleepMin, sleepAvg, latestSleep?.startTime, latestSleep?.endTime,
            latestRhr, avgRhr, latestHrv, avgHrv, latestResp, avgResp,
            steps, score.roundToInt(), sleeps.size
        )
    }

    private fun render(s: Summary) {
        while (content.childCount > 4) content.removeViewAt(4)
        val level = when {
            s.recovery >= 80 -> "🟢 Bonne récupération"
            s.recovery >= 60 -> "🟠 Récupération moyenne"
            else -> "🔴 Récupération faible"
        }
        content.addView(card("⚡  " + level, s.recovery.toString() + "/100 • référence sur " + s.nights + " nuits disponibles"))
        content.addView(card("🌙  Sommeil", formatMinutes(s.sleepMinutes) + "\nMoyenne 30 j : " + formatMinutes(s.sleepAvgMinutes) + "\n" + formatSleepWindow(s)))
        content.addView(card("❤️  Fréquence au repos", formatNumber(s.restingHr, " bpm") + "\nMoyenne : " + formatNumber(s.restingHrAvg, " bpm")))
        content.addView(card("〰️  VFC / HRV", formatNumber(s.hrv, " ms") + "\nMoyenne : " + formatNumber(s.hrvAvg, " ms")))
        content.addView(card("🫁  Respiration", formatNumber(s.respiratory, "/min") + "\nMoyenne : " + formatNumber(s.respiratoryAvg, "/min")))
        content.addView(card("👣  Aujourd'hui", s.steps.toString() + " pas"))
        content.addView(card("🧠  Lecture rapide", advice(s)))
    }

    private fun advice(s: Summary): String {
        val notes = mutableListOf<String>()
        if (s.sleepMinutes != null && s.sleepAvgMinutes != null) {
            val delta = s.sleepMinutes - s.sleepAvgMinutes
            if (delta < -45) notes += "Sommeil nettement sous ta moyenne : privilégie une journée modérée."
            else if (delta > 30) notes += "Tu as davantage dormi que d'habitude."
        }
        if (s.hrv != null && s.hrvAvg != null && s.hrvAvg > 0 && s.hrv < s.hrvAvg * 0.85)
            notes += "VFC plus basse que ta référence : récupération probablement réduite."
        if (s.restingHr != null && s.restingHrAvg != null && s.restingHr > s.restingHrAvg + 5)
            notes += "FC au repos au-dessus de ton niveau habituel."
        if (notes.isEmpty()) notes += "Pas de variation majeure détectée avec les données disponibles."
        notes += "Le bouton ChatGPT prépare un résumé structuré pour une analyse plus poussée."
        return notes.joinToString("\n\n")
    }

    private fun shareSummary() {
        val s = lastSummary ?: run {
            setStatus("Synchronise d'abord les données.")
            return
        }
        val payload =
            "Analyse mes données HealthCoach du jour en les comparant à mes références personnelles.\n\n" +
            "Récupération locale: " + s.recovery + "/100\n" +
            "Sommeil dernière nuit: " + formatMinutes(s.sleepMinutes) + "\n" +
            "Sommeil moyen 30 j: " + formatMinutes(s.sleepAvgMinutes) + "\n" +
            "FC repos: " + formatNumber(s.restingHr, " bpm") + "\n" +
            "FC repos moyenne: " + formatNumber(s.restingHrAvg, " bpm") + "\n" +
            "VFC: " + formatNumber(s.hrv, " ms") + "\n" +
            "VFC moyenne: " + formatNumber(s.hrvAvg, " ms") + "\n" +
            "Respiration: " + formatNumber(s.respiratory, "/min") + "\n" +
            "Respiration moyenne: " + formatNumber(s.respiratoryAvg, "/min") + "\n" +
            "Pas aujourd'hui: " + s.steps + "\n" +
            "Nuits disponibles: " + s.nights + "\n\n" +
            "Donne-moi un bilan très court : récupération, points inhabituels, activité conseillée aujourd'hui et priorité pour ce soir."

        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, payload)
        }, "Analyser avec ChatGPT"))
    }

    private fun card(title: String, body: String): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(15), dp(16), dp(15))
            background = GradientDrawable().apply {
                setColor(Color.rgb(27,31,41))
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), Color.rgb(46,53,68))
            }
        }
        box.addView(text(title, 18f, true, Color.WHITE))
        box.addView(text(body, 15f, false, Color.rgb(205,211,221)).apply {
            setPadding(0, dp(8), 0, 0)
            setLineSpacing(0f, 1.15f)
        })
        box.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) }
        return box
    }

    private fun button(label: String, click: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 14f
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            setColor(Color.rgb(46,85,145))
            cornerRadius = dp(14).toFloat()
        }
        setOnClickListener { click() }
    }

    private fun text(value: String, size: Float, bold: Boolean, color: Int) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun setStatus(value: String) { status.text = value }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    private fun formatMinutes(v: Double?): String {
        if (v == null || v.isNaN()) return "—"
        val m = v.roundToInt()
        return (m / 60).toString() + " h " + (m % 60).toString().padStart(2, '0')
    }

    private fun formatNumber(v: Double?, suffix: String): String =
        if (v == null || v.isNaN()) "—"
        else String.format(Locale.FRANCE, "%.1f%s", v, suffix)

    private fun formatSleepWindow(s: Summary): String {
        if (s.sleepStart == null || s.sleepEnd == null) return ""
        val f = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
        return f.format(s.sleepStart) + " → " + f.format(s.sleepEnd)
    }

    data class Summary(
        val sleepMinutes: Double?,
        val sleepAvgMinutes: Double?,
        val sleepStart: Instant?,
        val sleepEnd: Instant?,
        val restingHr: Double?,
        val restingHrAvg: Double?,
        val hrv: Double?,
        val hrvAvg: Double?,
        val respiratory: Double?,
        val respiratoryAvg: Double?,
        val steps: Long,
        val recovery: Int,
        val nights: Int
    )
}

private fun List<Double>.averageOrNull(): Double? = if (isEmpty()) null else average()
