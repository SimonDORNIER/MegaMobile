package fr.simondornier.healthbridge

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.Instant

class MainActivity : ComponentActivity() {
    private lateinit var statusText: TextView
    private lateinit var filesText: TextView
    private val driveStore by lazy { DriveTreeStore(this) }
    private val healthClient by lazy { HealthConnectClient.getOrCreate(this) }
    private var pendingFileKey: String = DriveTreeStore.LIVE

    private val permissionLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) {
        refreshStatus()
        SyncWorker.schedule(this)
    }

    private val fileLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            driveStore.saveUri(
                pendingFileKey,
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            initializeFile(pendingFileKey)
            SyncWorker.schedule(this)
            CommandWorker.checkNow(this)
            Toast.makeText(this, "Fichier Drive configuré", Toast.LENGTH_SHORT).show()
            refreshStatus()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (HealthConnectClient.getSdkStatus(this) != HealthConnectClient.SDK_AVAILABLE) {
            setContentView(TextView(this).apply {
                text = "Santé Connect n est pas disponible ou doit être mis à jour sur ce téléphone."
                textSize = 18f
                setPadding(48, 48, 48, 48)
            })
            return
        }

        setContentView(buildUi())
        if (driveStore.isConfigured(DriveTreeStore.LIVE)) SyncWorker.schedule(this)
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        if (::statusText.isInitialized) refreshStatus()
    }

    private fun buildUi(): ScrollView {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(32))
        }

        content.addView(TextView(this).apply {
            text = "Health Bridge"
            textSize = 30f
            setTypeface(typeface, Typeface.BOLD)
        })
        content.addView(TextView(this).apply {
            text = "Santé Connect → Google Drive → analyses ChatGPT"
            textSize = 16f
            setPadding(0, dp(6), 0, dp(18))
        })

        statusText = TextView(this).apply { textSize = 16f; setPadding(dp(14), dp(14), dp(14), dp(14)) }
        content.addView(statusText, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        filesText = TextView(this).apply { textSize = 14f; setPadding(0, dp(10), 0, dp(10)) }
        content.addView(filesText)

        content.addView(button("1. Autoriser Santé Connect") {
            permissionLauncher.launch(HealthConfig.requestedPermissions(healthClient))
        })
        content.addView(button("2. Configurer health_live.json") { configureFile(DriveTreeStore.LIVE, "health_live.json") })
        content.addView(button("3. Configurer health_status.json") { configureFile(DriveTreeStore.STATUS, "health_status.json") })
        content.addView(button("4. Configurer health_command.json") { configureFile(DriveTreeStore.COMMAND, "health_command.json") })
        content.addView(button("5. Configurer health_history.json") { configureFile(DriveTreeStore.HISTORY, "health_history.json") })
        content.addView(button("Synchroniser maintenant") {
            SyncWorker.syncNow(this)
            Toast.makeText(this, "Synchronisation demandée", Toast.LENGTH_SHORT).show()
        })
        content.addView(button("Vérifier une commande distante") {
            CommandWorker.checkNow(this)
            Toast.makeText(this, "Vérification demandée", Toast.LENGTH_SHORT).show()
        })
        content.addView(button("Ouvrir les paramètres Santé Connect") {
            runCatching { startActivity(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)) }
                .onFailure { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        })

        content.addView(TextView(this).apply {
            text = """
                Fonctionnement

                • Exports planifiés : ${SyncWorker.displaySchedule()}.
                • 08:20 prépare le bilan santé de 08:30 ; 18:30 prépare celui de 19:00.
                • health_command.json permet à ChatGPT de demander une nouvelle synchro à distance.
                • Le téléphone vérifie les commandes environ toutes les 15 minutes.
                • health_history.json conserve un résumé quotidien compact qui grandit avec le temps.
                • health_live.json garde les 72 dernières heures en détail pour les analyses fines.
                • Les exports peuvent être décalés par Android si le téléphone est hors ligne ou en veille profonde.
            """.trimIndent()
            textSize = 14f
            setPadding(0, dp(20), 0, 0)
        })

        return ScrollView(this).apply { addView(content) }
    }

    private fun configureFile(key: String, name: String) {
        pendingFileKey = key
        fileLauncher.launch(name)
    }

    private fun initializeFile(key: String) {
        runCatching {
            when (key) {
                DriveTreeStore.COMMAND -> driveStore.writeText(key, JSONObject().apply {
                    put("schemaVersion", 1)
                    put("requestId", "")
                    put("action", "sync")
                    put("requestedAt", JSONObject.NULL)
                    put("reason", "ready")
                }.toString(2))
                DriveTreeStore.HISTORY -> driveStore.writeText(key, JSONObject().apply {
                    put("schemaVersion", 1)
                    put("createdAt", Instant.now().toString())
                    put("days", JSONObject())
                }.toString(2))
                DriveTreeStore.STATUS -> driveStore.writeText(key, JSONObject().apply {
                    put("schemaVersion", 1)
                    put("state", "configured")
                    put("configuredAt", Instant.now().toString())
                }.toString(2))
            }
        }
    }

    private fun button(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun refreshStatus() {
        lifecycleScope.launch {
            val requested = HealthConfig.requestedPermissions(healthClient)
            val granted = runCatching { healthClient.permissionController.getGrantedPermissions() }.getOrDefault(emptySet())
            val prefs = getSharedPreferences("health_bridge", MODE_PRIVATE)
            val last = prefs.getString("last_sync", null)
            val trigger = prefs.getString("last_sync_trigger", null)
            val error = prefs.getString("last_error", null)
            val commandError = prefs.getString("last_command_error", null)

            statusText.text = buildString {
                append("Autorisations : ${requested.count { it in granted }}/${requested.size}\n")
                append("Dernière synchro : ${last ?: "aucune"}")
                if (!trigger.isNullOrBlank()) append(" ($trigger)")
                append("\nCommande distante : ${if (driveStore.isConfigured(DriveTreeStore.COMMAND)) "active" else "à configurer"}\n")
                append("Historique continu : ${if (driveStore.isConfigured(DriveTreeStore.HISTORY)) "actif" else "à configurer"}\n")
                if (!error.isNullOrBlank()) append("Erreur synchro : $error\n")
                if (!commandError.isNullOrBlank()) append("Erreur commande : $commandError")
            }

            filesText.text = buildString {
                append("Drive\n")
                append("• live : ${driveStore.displayName(DriveTreeStore.LIVE) ?: "non configuré"}\n")
                append("• status : ${driveStore.displayName(DriveTreeStore.STATUS) ?: "non configuré"}\n")
                append("• command : ${driveStore.displayName(DriveTreeStore.COMMAND) ?: "non configuré"}\n")
                append("• history : ${driveStore.displayName(DriveTreeStore.HISTORY) ?: "non configuré"}")
            }
        }
    }
}
