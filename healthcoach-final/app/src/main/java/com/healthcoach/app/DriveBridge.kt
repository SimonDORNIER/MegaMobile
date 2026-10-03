package com.healthcoach.app

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.json.JSONObject

class DriveBridge(private val context: Context) {
    private val prefs = context.getSharedPreferences("healthcoach", Context.MODE_PRIVATE)

    fun hasFolder(): Boolean = prefs.getString("drive_tree_uri", null) != null

    fun folderLabel(): String = prefs.getString("drive_folder_label", "Dossier Drive") ?: "Dossier Drive"

    fun saveFolder(uri: Uri, label: String?) {
        context.contentResolver.takePersistableUriPermission(
            uri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        prefs.edit()
            .putString("drive_tree_uri", uri.toString())
            .putString("drive_folder_label", label ?: "Dossier HealthCoach")
            .apply()
    }

    fun clearFolder() {
        prefs.edit().remove("drive_tree_uri").remove("drive_folder_label").apply()
    }

    fun writeJson(fileName: String, json: JSONObject): Boolean {
        val uri = prefs.getString("drive_tree_uri", null)?.let(Uri::parse) ?: return false
        val tree = DocumentFile.fromTreeUri(context, uri) ?: return false
        val file = tree.findFile(fileName)
            ?: tree.createFile("application/json", fileName)
            ?: return false

        context.contentResolver.openOutputStream(file.uri, "wt")?.use { stream ->
            stream.write(json.toString(2).toByteArray(Charsets.UTF_8))
            stream.flush()
        } ?: return false
        return true
    }

    fun writeAll(summary: HealthSummary, history: JSONObject): Boolean {
        val currentOk = writeJson("healthcoach_current.json", summary.toJson())
        val historyOk = writeJson("healthcoach_history.json", history)
        val status = JSONObject().apply {
            put("schemaVersion", 2)
            put("generatedAt", summary.generatedAt.toString())
            put("connected", true)
            put("recoveryScore", summary.recoveryScore)
            put("recoveryLabel", summary.recoveryLabel)
            put("recoveryReliable", summary.recoveryReliable)
            put("latestSleepDate", summary.latestSleepDate?.toString() ?: JSONObject.NULL)
            put("instructionsForChatGPT",
                "Pour un bilan, lire healthcoach_current.json en priorité puis healthcoach_history.json. Vérifier dataFreshness/recoveryReliable avant d'interpréter la dernière nuit. Ne jamais présenter une nuit ancienne comme celle d'aujourd'hui.")
        }
        val statusOk = writeJson("healthcoach_status.json", status)
        return currentOk && historyOk && statusOk
    }
}
