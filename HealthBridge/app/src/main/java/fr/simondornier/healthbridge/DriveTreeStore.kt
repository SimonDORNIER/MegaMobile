package fr.simondornier.healthbridge

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import java.io.IOException

class DriveTreeStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("health_bridge", Context.MODE_PRIVATE)

    fun saveUri(key: String, uri: Uri, flags: Int) {
        val takeFlags = flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        context.contentResolver.takePersistableUriPermission(uri, takeFlags)
        prefs.edit().putString("uri_$key", uri.toString()).apply()
    }

    fun getUri(key: String): Uri? = prefs.getString("uri_$key", null)?.let(Uri::parse)

    fun displayName(key: String): String? {
        val uri = getUri(key) ?: return null
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
    }

    fun readText(key: String): String? {
        val uri = getUri(key) ?: return null
        return context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
    }

    fun writeText(key: String, text: String) {
        val uri = getUri(key) ?: throw IllegalStateException("Fichier $key non configuré")
        context.contentResolver.openOutputStream(uri, "rwt")?.bufferedWriter()?.use { it.write(text) }
            ?: throw IOException("Impossible d écrire dans $key")
    }

    fun isConfigured(key: String): Boolean = getUri(key) != null

    companion object {
        const val LIVE = "live"
        const val STATUS = "status"
        const val COMMAND = "command"
        const val HISTORY = "history"
    }
}
