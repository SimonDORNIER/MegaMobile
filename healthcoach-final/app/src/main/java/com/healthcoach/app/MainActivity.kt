package com.healthcoach.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var client: HealthConnectClient? = null
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var setupStatus: TextView
    private var lastSummary: HealthSummary? = null

    private val dataPermissions = setOf(
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(RestingHeartRateRecord::class),
        HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class),
        HealthPermission.getReadPermission(RespiratoryRateRecord::class),
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(WeightRecord::class),
        "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND",
        "android.permission.health.READ_HEALTH_DATA_HISTORY"
    )

    private val healthPermissionLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) {
        refreshSetupStatus()
        syncNow()
    }

    private val folderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                val label = androidx.documentfile.provider.DocumentFile
                    .fromTreeUri(this, uri)?.name
                DriveBridge(this).saveFolder(uri, label)
                refreshSetupStatus()
                scheduleBackgroundSync()
                syncNow()
            } catch (e: Exception) {
                setStatus("Impossible de conserver l'accès au dossier : " + (e.message ?: "erreur"))
            }
        }
    }

    private val notificationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshSetupStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()

        when (HealthConnectClient.getSdkStatus(this)) {
            HealthConnectClient.SDK_AVAILABLE -> {
                client = HealthConnectClient.getOrCreate(this)
                scope.launch {
                    val granted = client!!.permissionController.getGrantedPermissions()
                    if (!granted.containsAll(dataPermissions)) {
                        healthPermissionLauncher.launch(dataPermissions)
                    } else {
                        refreshSetupStatus()
                        syncNow()
                    }
                }
            }
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ->
                setStatus("Santé Connect doit être mis à jour.")
            else -> setStatus("Santé Connect n'est pas disponible.")
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        scheduleBackgroundSync()
    }

    override fun onResume() {
        super.onResume()
        refreshSetupStatus()
    }

    private fun buildUi() {
        window.statusBarColor = Color.rgb(16, 19, 26)
        window.navigationBarColor = Color.rgb(16, 19, 26)

        val scroll = ScrollView(this).apply { setBackgroundColor(Color.rgb(16, 19, 26)) }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(40))
        }
        scroll.addView(root)

        root.addView(text("HealthCoach", 30f, true, Color.WHITE))
        root.addView(text("Santé Connect + historique + pont ChatGPT", 15f, false, Color.rgb(174,184,199)).apply {
            setPadding(0, dp(2), 0, dp(14))
        })

        status = text("Initialisation…", 14f, false, Color.rgb(138,180,248))
        root.addView(status)

        val setup = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = panelDrawable(Color.rgb(27,31,41))
        }
        setup.addView(text("Connexion automatique", 18f, true, Color.WHITE))
        setupStatus = text("", 14f, false, Color.rgb(205,211,221)).apply {
            setPadding(0, dp(8), 0, dp(8))
        }
        setup.addView(setupStatus)
        setup.addView(button("Choisir le dossier Google Drive") { folderLauncher.launch(null) })
        setup.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(14) }
        root.addView(setup)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, dp(4))
        }
        actions.addView(button("Synchroniser") { syncNow() },
            LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginEnd = dp(6) })
        actions.addView(button("Analyser avec ChatGPT") { shareToChatGpt() },
            LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginStart = dp(6) })
        root.addView(actions)

        refreshSetupStatus()
        setContentView(scroll)
    }

    private fun refreshSetupStatus() {
        if (!::setupStatus.isInitialized) return
        val drive = DriveBridge(this)
        val driveText = if (drive.hasFolder()) "✅ Drive : " + drive.folderLabel() else "⚠️ Drive : dossier à choisir"
        val notifText = if (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
            "✅ Notifications autorisées" else "⚠️ Notifications non autorisées"

        setupStatus.text = driveText + "\n" + notifText +
            "\n🔄 Mise à jour en arrière-plan planifiée chaque heure quand Android l'autorise."
    }

    private fun syncNow() {
        val hc = client ?: return
        setStatus("Synchronisation Santé Connect…")
        scope.launch {
            try {
                val repo = HealthRepository(this@MainActivity)
                val summary = withContext(Dispatchers.IO) { repo.load(hc) }
                lastSummary = summary

                val drive = DriveBridge(this@MainActivity)
                val driveOk = if (drive.hasFolder()) {
                    withContext(Dispatchers.IO) {
                        drive.writeAll(summary, repo.localHistoryJson())
                    }
                } else false

                renderSummary(summary)

                val t = DateTimeFormatter.ofPattern("HH:mm", Locale.FRANCE)
                    .format(java.time.ZonedDateTime.now())
                setStatus(
                    if (drive.hasFolder() && driveOk) "À jour • " + t + " • Drive synchronisé"
                    else if (drive.hasFolder()) "À jour • " + t + " • Drive à resynchroniser"
                    else "À jour • " + t + " • choisis ton dossier Drive une fois"
                )
                NotificationHelper(this@MainActivity).maybeNotify(summary)
            } catch (e: SecurityException) {
                setStatus("Il manque une autorisation Santé Connect.")
            } catch (e: Exception) {
                setStatus("Erreur de synchronisation : " + (e.message ?: e.javaClass.simpleName))
            }
        }
    }

    private fun renderSummary(s: HealthSummary) {
        while (root.childCount > 5) root.removeViewAt(5)

        val icon = when {
            s.recoveryScore >= 80 -> "🟢"
            s.recoveryScore >= 60 -> "🟠"
            else -> "🔴"
        }

        root.addView(card("⚡ " + icon + " Récupération", s.recoveryScore.toString() + "/100 • " + s.recoveryLabel))
        root.addView(card("🌙 Sommeil",
            formatMinutes(s.sleepMinutes) +
                "\nLéger " + formatMinutes(s.lightMinutes) +
                " • Profond " + formatMinutes(s.deepMinutes) +
                " • REM " + formatMinutes(s.remMinutes) +
                "\nMoy. 7 j " + formatMinutes(s.baseline7.sleepMinutes) +
                " • 30 j " + formatMinutes(s.baseline30.sleepMinutes) +
                " • 90 j " + formatMinutes(s.baseline90.sleepMinutes)))
        root.addView(card("❤️ Cœur",
            "FC repos " + formatNumber(s.restingHr, " bpm") +
                "\nFC moyenne nuit " + formatNumber(s.overnightHr, " bpm") +
                "\nRéf. 30 j " + formatNumber(s.baseline30.restingHr, " bpm")))
        root.addView(card("〰️ VFC / HRV",
            formatNumber(s.hrv, " ms") +
                "\nRéf. 7 j " + formatNumber(s.baseline7.hrv, " ms") +
                " • 30 j " + formatNumber(s.baseline30.hrv, " ms") +
                " • 90 j " + formatNumber(s.baseline90.hrv, " ms")))
        root.addView(card("🫁 Respiration",
            formatNumber(s.respiratory, "/min") +
                "\nRéf. 30 j " + formatNumber(s.baseline30.respiratory, "/min")))
        root.addView(card("👣 Activité aujourd'hui",
            s.stepsToday.toString() + " pas • " +
                String.format(Locale.FRANCE, "%.2f km", s.distanceTodayMeters / 1000.0) +
                "\n7 derniers jours : " + s.exerciseSessions7d + " séance(s), " +
                s.exerciseMinutes7d + " min"))
        if (s.weightKg != null) root.addView(card("⚖️ Poids", formatNumber(s.weightKg, " kg")))
        root.addView(card("🤖 ChatGPT",
            if (DriveBridge(this).hasFolder())
                "Les fichiers d'analyse sont automatiquement mis à jour dans Drive. Dans ChatGPT, tu peux simplement écrire : « Fais mon bilan santé »."
            else
                "Choisis ton dossier Drive une fois. Ensuite ChatGPT pourra récupérer le résumé sans export manuel."))
    }

    private fun shareToChatGpt() {
        val s = lastSummary ?: run {
            setStatus("Synchronise d'abord les données.")
            return
        }
        val text = "Analyse ce résumé HealthCoach et donne-moi un bilan court de récupération, les variations importantes et 2 à 4 conseils pour aujourd'hui.\n\n" +
            s.toJson().toString(2)

        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }, "Envoyer à ChatGPT"))
    }

    private fun scheduleBackgroundSync() {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "healthcoach_background_sync",
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    private fun card(title: String, body: String): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(15), dp(16), dp(15))
            background = panelDrawable(Color.rgb(27,31,41))
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

    private fun panelDrawable(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(18).toFloat()
        setStroke(dp(1), Color.rgb(46,53,68))
    }

    private fun button(label: String, click: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 13.5f
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

    private fun formatMinutes(value: Double?): String {
        if (value == null || value.isNaN()) return "—"
        val m = value.roundToInt()
        return (m / 60).toString() + " h " + (m % 60).toString().padStart(2, '0')
    }

    private fun formatNumber(value: Double?, suffix: String): String =
        if (value == null || value.isNaN()) "—"
        else String.format(Locale.FRANCE, "%.1f%s", value, suffix)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
}
