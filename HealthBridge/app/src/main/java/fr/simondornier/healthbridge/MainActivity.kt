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

class MainActivity : ComponentActivity() {
    private lateinit var statusText: TextView
    private lateinit var folderText: TextView
    private val treeStore by lazy { DriveTreeStore(this) }
    private val healthClient by lazy { HealthConnectClient.getOrCreate(this) }

    private val permissionLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) {
        refreshStatus()
        SyncWorker.schedule(this)
    }

    private val folderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            treeStore.saveTreeUri(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            SyncWorker.schedule(this)
            SyncWorker.syncNow(this)
            refreshStatus()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (HealthConnectClient.getSdkStatus(this) != HealthConnectClient.SDK_AVAILABLE) {
            setContentView(TextView(this).apply {
                text = "Santé Connect n'est pas disponible ou doit être mis à jour sur ce téléphone."
                textSize = 18f
                setPadding(48, 48, 48, 48)
            })
            return
        }

        setContentView(buildUi())
        if (treeStore.getTreeUri() != null) SyncWorker.schedule(this)
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
            text = "Santé Connect → Google Drive, automatiquement toutes les ~30 min"
            textSize = 16f
            setPadding(0, dp(6), 0, dp(22))
        })

        statusText = TextView(this).apply {
            textSize = 16f
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        content.addView(statusText, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        folderText = TextView(this).apply {
            textSize = 15f
            setPadding(0, dp(14), 0, dp(10))
        }
        content.addView(folderText)

        content.addView(button("1. Autoriser Santé Connect") {
            permissionLauncher.launch(HealthConfig.requestedPermissions(healthClient))
        })
        content.addView(button("2. Choisir le dossier Google Drive") {
            folderLauncher.launch(treeStore.getTreeUri())
        })
        content.addView(button("Synchroniser maintenant") {
            SyncWorker.syncNow(this)
            Toast.makeText(this, "Synchronisation demandée", Toast.LENGTH_SHORT).show()
        })
        content.addView(button("Ouvrir les paramètres Santé Connect") {
            runCatching { startActivity(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)) }
                .onFailure { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        })

        content.addView(TextView(this).apply {
            text = """
                Fonctionnement

                • health_live.json : fenêtre glissante des 72 dernières heures, mise à jour automatiquement.
                • health_status.json : état de la dernière synchronisation.
                • L'export ZIP quotidien natif Santé Connect peut rester activé comme sauvegarde complète.
                • Android peut décaler une tâche périodique de quelques minutes pour économiser la batterie.
            """.trimIndent()
            textSize = 15f
            setPadding(0, dp(20), 0, 0)
        })

        return ScrollView(this).apply { addView(content) }
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
            val baseGranted = requested.count { it in granted }
            val prefs = getSharedPreferences("health_bridge", MODE_PRIVATE)
            val last = prefs.getString("last_sync", null)
            val error = prefs.getString("last_error", null)
            val errCount = prefs.getInt("last_error_count", 0)

            statusText.text = buildString {
                append("Autorisations : $baseGranted/${requested.size}\n")
                append("Dossier : ${if (treeStore.getTreeUri() != null) "configuré" else "à choisir"}\n")
                append("Dernière synchro : ${last ?: "aucune"}\n")
                if (errCount > 0) append("Lectures avec erreur : $errCount\n")
                if (!error.isNullOrBlank()) append("Dernière erreur : $error")
            }
            folderText.text = "Destination : ${treeStore.displayName() ?: "aucun dossier"}"
        }
    }
}
