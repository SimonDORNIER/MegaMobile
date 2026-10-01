package fr.simondornier.healthbridge

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import java.io.IOException

class DriveTreeStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("health_bridge", Context.MODE_PRIVATE)

    fun saveFileUri(uri: Uri, flags: Int) {
        val takeFlags = flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        context.contentResolver.takePersistableUriPermission(uri, takeFlags)
        prefs.edit().putString("file_uri", uri.toString()).apply()
    }

    fun getFileUri(): Uri? = prefs.getString("file_uri", null)?.let(Uri::parse)

    fun displayName(): String? {
        val uri = getFileUri() ?: return null
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
    }

    fun writeText(text: String) {
        val uri = getFileUri() ?: throw IllegalStateException("Aucun fichier d\'export sélectionné")
        context.contentResolver.openOutputStream(uri, "rwt")?.bufferedWriter()?.use { writer ->
            writer.write(text)
        } ?: throw IOException("Impossible d\'ouvrir le fichier Google Drive")
    }
}
