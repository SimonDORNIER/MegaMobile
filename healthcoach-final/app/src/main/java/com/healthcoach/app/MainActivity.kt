package com.healthcoach.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.ArrayAdapter
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var client: HealthConnectClient? = null
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var setupStatus: TextView
    private lateinit var summaryContainer: LinearLayout
    private var lastSummary: HealthSummary? = null

    private val dataPermissions = HealthPermissions.all

    private val healthPermissionLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) {
        refreshSetupStatus()
        scheduleBackgroundSync()
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
        FixedTimeSyncScheduler.apply(this)
    }

    override fun onResume() {
        super.onResume()
        scheduleBackgroundSync()
        refreshSetupStatus()
        requestReliableBackgroundPermissionOnce()
        scope.launch {
            val updating = UpdateManager.checkOnLaunch(this@MainActivity) { message ->
                setStatus(message)
            }
            if (!updating && client != null && BackgroundSyncScheduler.shouldCatchUp(this@MainActivity)) {
                syncNow()
            }
        }
    }

    private fun checkForAppUpdate() {
        scope.launch {
            UpdateManager.checkOnLaunch(this@MainActivity) { message ->
                setStatus(message)
            }
        }
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
            setPadding(0, dp(2), 0, dp(4))
        })
        root.addView(text("v2.2.1 • synchro automatique renforcée", 12.5f, false, Color.rgb(138,180,248)).apply {
            setPadding(0, 0, 0, dp(10))
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
        setup.addView(button("Autorisations Santé Connect") {
            checkHealthPermissionsFromButton()
        })
        setup.addView(button("Fiabiliser la synchro automatique") {
            requestReliableBackgroundSync()
        })
        setup.addView(button("Vérifier la mise à jour") {
            checkForAppUpdate()
        })
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

        val syncPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = panelDrawable(Color.rgb(27,31,41))
        }
        syncPanel.addView(text("Fréquence de synchronisation", 18f, true, Color.WHITE))
        val choices = listOf("15 min", "30 min", "1 h", "2 h", "4 h")
        val spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, choices)
        val prefs = getSharedPreferences("healthcoach", MODE_PRIVATE)
        val saved = prefs.getInt("sync_minutes", 60)
        spinner.setSelection(listOf(15,30,60,120,240).indexOf(saved).coerceAtLeast(0))
        syncPanel.addView(spinner)

        syncPanel.addView(text("Heures fixes facultatives", 15f, true, Color.WHITE).apply {
            setPadding(0, dp(12), 0, dp(4))
        })
        val fixedTimes = EditText(this).apply {
            setSingleLine(true)
            hint = "08:00, 13:00, 22:00"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(130, 140, 158))
            setText(prefs.getString("sync_fixed_times", "") ?: "")
        }
        syncPanel.addView(fixedTimes)
        syncPanel.addView(text(
            "Ces heures servent uniquement à mettre Drive à jour. Aucun bilan automatique n'est déclenché par l'app.",
            12.5f,
            false,
            Color.rgb(174,184,199)
        ).apply { setPadding(0, dp(2), 0, dp(8)) })

        syncPanel.addView(button("Enregistrer la planification") {
            val minutes = listOf(15,30,60,120,240)[spinner.selectedItemPosition]
            val normalized = FixedTimeSyncScheduler.normalize(fixedTimes.text.toString())
            fixedTimes.setText(normalized)
            prefs.edit()
                .putInt("sync_minutes", minutes)
                .putString("sync_fixed_times", normalized)
                .apply()
            scheduleBackgroundSync()
            FixedTimeSyncScheduler.apply(this)
            refreshSetupStatus()
            setStatus(
                if (normalized.isBlank())
                    "Planification enregistrée : " + choices[spinner.selectedItemPosition]
                else
                    "Planification enregistrée : " + choices[spinner.selectedItemPosition] + " + " + normalized
            )
        })
        syncPanel.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) }
        root.addView(syncPanel)

        summaryContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(summaryContainer)

        refreshSetupStatus()
        setContentView(scroll)
    }

    private fun refreshSetupStatus() {
        if (!::setupStatus.isInitialized) return

        val drive = DriveBridge(this)
        val driveText = if (drive.hasFolder()) {
            "✅ Drive : " + drive.folderLabel()
        } else {
            "⚠️ Drive : dossier à choisir"
        }

        val notifText = if (
            Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            "✅ Notifications autorisées"
        } else {
            "⚠️ Notifications non autorisées"
        }

        val prefs = getSharedPreferences("healthcoach", MODE_PRIVATE)
        val fixed = prefs.getString("sync_fixed_times", "")?.takeIf { it.isNotBlank() }
        val state = SyncState.read(this)
        val nextAlarm = prefs.getLong("next_interval_alarm_at", 0L)

        fun render(healthText: String) {
            setupStatus.text = healthText + "\n" +
                driveText + "\n" +
                notifText + "\n" +
                "🔄 Synchro : " + prefs.getInt("sync_minutes", 60) + " min." +
                "\n" + (if (BackgroundSyncScheduler.canScheduleExact(this@MainActivity))
                    "✅ Alarmes exactes autorisées"
                else
                    "⚠️ Alarmes exactes non autorisées") +
                "\n" + (if (BackgroundSyncScheduler.batteryOptimizationIgnored(this@MainActivity))
                    "✅ Batterie : HealthCoach sans restriction"
                else
                    "⚠️ Batterie : Android peut retarder la synchro") +
                (fixed?.let { "\n🕒 Heures fixes : " + it } ?: "") +
                "\n✅ Dernière synchro Drive : " + formatClock(state.lastDriveSuccessAt) +
                "\n⏰ Dernière alarme : " + formatClock(prefs.getLong("last_alarm_fired_at", 0L)) +
                "\n⚙️ Dernier worker : " + formatClock(prefs.getLong("last_worker_started_at", 0L)) +
                "\n🔎 Dernière tentative : " + formatClock(state.lastAttemptAt) +
                (state.lastSource?.let { " (" + it + ")" } ?: "") +
                "\n⏱️ Prochaine relance : ~" + formatClock(nextAlarm) +
                (state.lastError?.let { "\n⚠️ Dernière erreur : " + it } ?: "")
        }

        val hc = client
        if (hc == null) {
            render("⏳ Santé Connect : vérification…")
            return
        }

        scope.launch {
            val granted = runCatching {
                hc.permissionController.getGrantedPermissions()
            }.getOrDefault(emptySet())

            val healthText = when {
                !HealthPermissions.records.all { it in granted } ->
                    "⚠️ Santé Connect : autorisations de lecture manquantes"
                HealthPermissions.BACKGROUND !in granted ->
                    "⚠️ Santé Connect : arrière-plan non autorisé"
                else ->
                    "✅ Santé Connect : arrière-plan autorisé"
            }

            render(healthText)
        }
    }

    private fun requestReliableBackgroundPermissionOnce() {
        val prefs = getSharedPreferences("healthcoach", MODE_PRIVATE)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !BackgroundSyncScheduler.canScheduleExact(this)
        ) {
            if (!prefs.getBoolean("exact_alarm_prompted_v221", false)) {
                prefs.edit().putBoolean("exact_alarm_prompted_v221", true).apply()
                requestExactAlarmPermission()
            }
            return
        }

        if (!BackgroundSyncScheduler.batteryOptimizationIgnored(this) &&
            !prefs.getBoolean("battery_prompted_v221", false)
        ) {
            prefs.edit().putBoolean("battery_prompted_v221", true).apply()
            requestBatteryOptimizationExemption()
        }
    }

    private fun requestReliableBackgroundSync() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !BackgroundSyncScheduler.canScheduleExact(this)
        ) {
            requestExactAlarmPermission()
            return
        }

        if (!BackgroundSyncScheduler.batteryOptimizationIgnored(this)) {
            requestBatteryOptimizationExemption()
            return
        }

        scheduleBackgroundSync()
        BackgroundSyncScheduler.enqueueImmediate(this)
        refreshSetupStatus()
        setStatus("Synchronisation automatique fiabilisée.")
    }

    private fun requestExactAlarmPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            BackgroundSyncScheduler.canScheduleExact(this)
        ) {
            requestReliableBackgroundSync()
            return
        }

        try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:" + packageName)
                )
            )
            setStatus("Active « Alarmes et rappels », puis reviens dans HealthCoach.")
        } catch (_: Exception) {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + packageName)
                )
            )
        }
    }

    private fun requestBatteryOptimizationExemption() {
        if (BackgroundSyncScheduler.batteryOptimizationIgnored(this)) {
            scheduleBackgroundSync()
            BackgroundSyncScheduler.enqueueImmediate(this)
            refreshSetupStatus()
            setStatus("HealthCoach est autorisé à fonctionner sans restriction.")
            return
        }

        try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + packageName)
                )
            )
            setStatus("Autorise HealthCoach à fonctionner sans restriction en arrière-plan.")
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun checkHealthPermissionsFromButton() {
        val hc = client ?: run {
            setStatus("Santé Connect n'est pas prêt.")
            return
        }

        scope.launch {
            val granted = runCatching {
                hc.permissionController.getGrantedPermissions()
            }.getOrDefault(emptySet())

            val missing = dataPermissions.filterNot { it in granted }.toSet()

            if (missing.isEmpty()) {
                setStatus("Santé Connect : toutes les autorisations sont déjà accordées.")
                refreshSetupStatus()
            } else {
                setStatus("Santé Connect : " + missing.size + " autorisation(s) à accorder.")
                healthPermissionLauncher.launch(missing)
            }
        }
    }

    private fun syncNow() {
        val hc = client ?: return
        SyncState.recordAttempt(this, "foreground")
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

                if (drive.hasFolder() && !driveOk) {
                    SyncState.recordFailure(
                        this@MainActivity,
                        "Écriture Drive impossible",
                        "foreground"
                    )
                } else {
                    SyncState.recordSuccess(
                        this@MainActivity,
                        driveWritten = driveOk,
                        source = "foreground"
                    )
                }
                if (driveOk) {
                    withContext(Dispatchers.IO) { drive.writeSyncDiagnostics() }
                }
                BackgroundSyncScheduler.scheduleNextAlarm(this@MainActivity)
                refreshSetupStatus()

                val t = DateTimeFormatter.ofPattern("HH:mm", Locale.FRANCE)
                    .format(java.time.ZonedDateTime.now())
                setStatus(
                    if (drive.hasFolder() && driveOk) "À jour • " + t + " • Drive synchronisé"
                    else if (drive.hasFolder()) "À jour • " + t + " • Drive à resynchroniser"
                    else "À jour • " + t + " • choisis ton dossier Drive une fois"
                )
                NotificationHelper(this@MainActivity).maybeNotify(summary)
            } catch (e: SecurityException) {
                SyncState.recordFailure(
                    this@MainActivity,
                    "Autorisation Santé Connect manquante",
                    "foreground"
                )
                refreshSetupStatus()
                setStatus("Il manque une autorisation Santé Connect.")
            } catch (e: Exception) {
                SyncState.recordFailure(
                    this@MainActivity,
                    e.message ?: e.javaClass.simpleName,
                    "foreground"
                )
                refreshSetupStatus()
                setStatus("Erreur de synchronisation : " + (e.message ?: e.javaClass.simpleName))
            }
        }
    }

    private fun renderSummary(s: HealthSummary) {
        summaryContainer.removeAllViews()

        val icon = when {
            s.recoveryScore >= 80 -> "🟢"
            s.recoveryScore >= 60 -> "🟠"
            else -> "🔴"
        }

        val recoveryBody = if (s.recoveryReliable) {
            s.recoveryScore.toString() + "/100 • " + s.recoveryLabel
        } else {
            s.recoveryScore.toString() + "/100 • À actualiser" +
                "\nDernière nuit disponible : " + (s.latestSleepDate?.toString() ?: "—")
        }
        summaryContainer.addView(card("⚡ " + icon + " Récupération", recoveryBody))
        summaryContainer.addView(card("🌙 Sommeil",
            (if (!s.recoveryReliable) "⚠️ Données les plus récentes : " + (s.latestSleepDate?.toString() ?: "date inconnue") + "\n" else "") +
            formatMinutes(s.sleepMinutes) +
                "\nLéger " + formatMinutes(s.lightMinutes) +
                " • Profond " + formatMinutes(s.deepMinutes) +
                " • REM " + formatMinutes(s.remMinutes) +
                "\nMoy. 7 j " + formatMinutes(s.baseline7.sleepMinutes) +
                " • 30 j " + formatMinutes(s.baseline30.sleepMinutes) +
                " • 90 j " + formatMinutes(s.baseline90.sleepMinutes)))
        summaryContainer.addView(card("❤️ Cœur",
            "FC repos " + formatNumber(s.restingHr, " bpm") +
                "\nFC moyenne nuit " + formatNumber(s.overnightHr, " bpm") +
                "\nRéf. 30 j " + formatNumber(s.baseline30.restingHr, " bpm")))
        summaryContainer.addView(card("〰️ VFC / HRV",
            formatNumber(s.hrv, " ms") +
                "\nRéf. 7 j " + formatNumber(s.baseline7.hrv, " ms") +
                " • 30 j " + formatNumber(s.baseline30.hrv, " ms") +
                " • 90 j " + formatNumber(s.baseline90.hrv, " ms")))
        summaryContainer.addView(card("🫁 Respiration",
            formatNumber(s.respiratory, "/min") +
                "\nRéf. 30 j " + formatNumber(s.baseline30.respiratory, "/min")))
        summaryContainer.addView(card("👣 Activité",
            "Aujourd’hui : " + s.stepsToday + " pas • " +
                String.format(Locale.FRANCE, "%.2f km", s.distanceTodayMeters / 1000.0) +
                "\nHier : " + s.stepsYesterday + " pas • " +
                String.format(Locale.FRANCE, "%.2f km", s.distanceYesterdayMeters / 1000.0) +
                "\nRéf. 30 j : " + formatNumber(s.baseline30.steps, " pas/j") +
                "\n7 derniers jours : " + s.exerciseSessions7d + " séance(s), " +
                s.exerciseMinutes7d + " min"))
        if (s.weightKg != null) summaryContainer.addView(card("⚖️ Poids", formatNumber(s.weightKg, " kg")))
        summaryContainer.addView(card("🤖 ChatGPT",
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
        BackgroundSyncScheduler.apply(this)
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

    private fun formatClock(timestamp: Long): String {
        if (timestamp <= 0L) return "jamais"
        return DateTimeFormatter.ofPattern("HH:mm", Locale.FRANCE)
            .format(
                java.time.Instant.ofEpochMilli(timestamp)
                    .atZone(java.time.ZoneId.systemDefault())
            )
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
}
