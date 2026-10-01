package fr.simondornier.healthbridge

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.IOException

class DriveTreeStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("health_bridge", Context.MODE_PRIVATE)

    fun saveTreeUri(uri: Uri, flags: Int) {
        val takeFlags = flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        context.contentResolver.takePersistableUriPermission(uri, takeFlags)
        prefs.edit().putString("tree_uri", uri.toString()).apply()
    }

    fun getTreeUri(): Uri? = prefs.getString("tree_uri", null)?.let(Uri::parse)

    fun displayName(): String? {
        val uri = getTreeUri() ?: return null
        return DocumentFile.fromTreeUri(context, uri)?.name
    }

    fun writeText(filename: String, text: String) {
        val treeUri = getTreeUri() ?: throw IllegalStateException("Aucun dossier d'export sélectionné")
        val folder = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IOException("Dossier d'export inaccessible")

        var file = folder.findFile(filename)
        if (file == null) {
            file = folder.createFile("application/json", filename)
                ?: throw IOException("Impossible de créer $filename")
        }

        try {
            context.contentResolver.openOutputStream(file.uri, "rwt")?.bufferedWriter()?.use { writer ->
                writer.write(text)
            } ?: throw IOException("Impossible d'ouvrir $filename")
        } catch (first: Exception) {
            runCatching { file.delete() }
            val recreated = folder.createFile("application/json", filename)
                ?: throw IOException("Impossible de recréer $filename", first)
            context.contentResolver.openOutputStream(recreated.uri, "w")?.bufferedWriter()?.use { writer ->
                writer.write(text)
            } ?: throw IOException("Impossible d'écrire $filename", first)
        }
    }
}
