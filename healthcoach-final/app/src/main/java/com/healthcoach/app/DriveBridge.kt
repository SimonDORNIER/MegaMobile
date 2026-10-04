package com.healthcoach.app

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.json.JSONObject
import java.io.File

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

    fun readJson(fileName: String): JSONObject? {
        val uri = prefs.getString("drive_tree_uri", null)?.let(Uri::parse) ?: return null
        val tree = DocumentFile.fromTreeUri(context, uri) ?: return null
        val file = tree.findFile(fileName) ?: return null

        return try {
            val text = context.contentResolver.openInputStream(file.uri)
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
                ?: return null
            JSONObject(text)
        } catch (_: Exception) {
            null
        }
    }

    fun copyFileTo(fileName: String, destination: File): Boolean {
        val cachedKey = "cached_uri_" + fileName
        val cached = prefs.getString(cachedKey, null)?.let(Uri::parse)

        fun copyFrom(sourceUri: Uri): Boolean {
            return try {
                destination.parentFile?.mkdirs()
                val inputStream = context.contentResolver.openInputStream(sourceUri) ?: return false
                inputStream.use { input ->
                    destination.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                true
            } catch (_: Exception) {
                false
            }
        }

        if (cached != null && copyFrom(cached)) return true

        val treeUri = prefs.getString("drive_tree_uri", null)?.let(Uri::parse) ?: return false
        val tree = DocumentFile.fromTreeUri(context, treeUri) ?: return false

        val source = tree.listFiles().firstOrNull {
            it.name.equals(fileName, ignoreCase = true)
        } ?: tree.findFile(fileName) ?: return false

        prefs.edit().putString(cachedKey, source.uri.toString()).apply()
        return copyFrom(source.uri)
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

    fun writeSyncDiagnostics(): Boolean {
        val state = SyncState.read(context)
        val p = context.getSharedPreferences("healthcoach", Context.MODE_PRIVATE)

        val status = readJson("healthcoach_status.json") ?: JSONObject().apply {
            put("schemaVersion", 4)
            put("connected", true)
        }

        status.put("sync", JSONObject().apply {
            put("generatedAt", java.time.Instant.now().toString())
            put("lastAttemptAt", state.lastAttemptAt)
            put("lastHealthSuccessAt", state.lastHealthSuccessAt)
            put("lastDriveSuccessAt", state.lastDriveSuccessAt)
            put("lastSource", state.lastSource ?: JSONObject.NULL)
            put("lastError", state.lastError ?: JSONObject.NULL)
            put("lastAlarmFiredAt", p.getLong("last_alarm_fired_at", 0L))
            put("lastServiceStartedAt", p.getLong("last_sync_service_started_at", 0L))
            put("foregroundServiceAliveAt", p.getLong("foreground_service_alive_at", 0L))
            put("lastWorkerStartedAt", p.getLong("last_worker_started_at", 0L))
            put("lastWorkerFinishedAt", p.getLong("last_worker_finished_at", 0L))
            put("lastWorkerResult", p.getString("last_worker_result", null) ?: JSONObject.NULL)
            put("nextIntervalAlarmAt", p.getLong("next_interval_alarm_at", 0L))
            put("syncMinutes", p.getInt("sync_minutes", 60))
            put("exactAlarmAllowed", BackgroundSyncScheduler.canScheduleExact(context))
            put("batteryOptimizationIgnored", BackgroundSyncScheduler.batteryOptimizationIgnored(context))
        })

        return writeJson("healthcoach_status.json", status)
    }

    fun writeAll(summary: HealthSummary, history: JSONObject): Boolean {
        val currentOk = writeJson("healthcoach_current.json", summary.toJson())
        val historyOk = writeJson("healthcoach_history.json", history)
        val previousUpdate = readJson("healthcoach_status.json")?.optJSONObject("update")
        val status = JSONObject().apply {
            put("schemaVersion", 4)
            put("generatedAt", summary.generatedAt.toString())
            put("connected", true)
            put("recoveryScore", summary.recoveryScore)
            put("recoveryLabel", summary.recoveryLabel)
            put("recoveryReliable", summary.recoveryReliable)
            put("latestSleepDate", summary.latestSleepDate?.toString() ?: JSONObject.NULL)
            if (previousUpdate != null) put("update", previousUpdate)
            put("instructionsForChatGPT",
                "Pour un bilan, lire healthcoach_current.json en priorité puis healthcoach_history.json. Vérifier dataFreshness/recoveryReliable avant d'interpréter la dernière nuit. Ne jamais présenter une nuit ancienne comme celle d'aujourd'hui.")
        }
        val statusOk = writeJson("healthcoach_status.json", status)
        return currentOk && historyOk && statusOk
    }
}
