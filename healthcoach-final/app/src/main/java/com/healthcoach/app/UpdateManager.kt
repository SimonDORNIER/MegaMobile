package com.healthcoach.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Base64
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class UpdateDescriptor(
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String?,
    val chunks: List<String>,
    val sha256: String?,
    val notes: String
)

object UpdateManager {
    private const val MANIFEST_URL =
        "https://raw.githubusercontent.com/SimonDORNIER/MegaMobile/main/healthcoach/update.json"

    private var checkRunning = false

    suspend fun checkOnLaunch(
        activity: Activity,
        onStatus: (String) -> Unit
    ): Boolean {
        if (checkRunning) return false
        checkRunning = true

        try {
            onStatus("Vérification des mises à jour…")

            val descriptor = withContext(Dispatchers.IO) {
                fetchDescriptor()
            } ?: run {
                onStatus("Mise à jour : impossible de lire le serveur GitHub.")
                return false
            }

            val currentVersion = installedVersionCode(activity)
            if (descriptor.versionCode <= currentVersion) {
                onStatus("HealthCoach " + installedVersionName(activity) + " est à jour.")
                return false
            }

            onStatus(
                "Mise à jour " + descriptor.versionName +
                    " détectée (installée : " + installedVersionName(activity) + ")."
            )

            val apk = File(
                File(activity.cacheDir, "updates"),
                "HealthCoach-" + descriptor.versionName + ".apk"
            )

            val downloaded = withContext(Dispatchers.IO) {
                when {
                    descriptor.chunks.isNotEmpty() ->
                        downloadChunkedApk(descriptor.chunks, apk)
                    !descriptor.apkUrl.isNullOrBlank() ->
                        downloadBinary(descriptor.apkUrl, apk)
                    else -> false
                }
            }

            if (!downloaded) {
                onStatus("Téléchargement de la mise à jour impossible.")
                return true
            }

            if (!descriptor.sha256.isNullOrBlank()) {
                val digest = withContext(Dispatchers.IO) { sha256(apk) }
                if (!digest.equals(descriptor.sha256, ignoreCase = true)) {
                    apk.delete()
                    onStatus("Mise à jour refusée : contrôle d'intégrité incorrect.")
                    return true
                }
            }

            onStatus("Vérification de la signature…")
            val validation = withContext(Dispatchers.IO) {
                validateApk(activity, apk, descriptor.versionCode)
            }

            if (validation != null) {
                apk.delete()
                onStatus("Mise à jour refusée : " + validation)
                return true
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                !activity.packageManager.canRequestPackageInstalls()
            ) {
                onStatus(
                    "Autorise HealthCoach à installer des applications, puis reviens dans l'app."
                )
                activity.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + activity.packageName)
                    )
                )
                return true
            }

            onStatus("Ouverture de l'installation " + descriptor.versionName + "…")
            val uri = FileProvider.getUriForFile(
                activity,
                activity.packageName + ".fileprovider",
                apk
            )

            activity.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            )
            return true
        } catch (e: Exception) {
            onStatus(
                "Mise à jour impossible : " +
                    (e.message ?: e.javaClass.simpleName)
            )
            return false
        } finally {
            checkRunning = false
        }
    }

    private fun fetchDescriptor(): UpdateDescriptor? {
        val json = fetchText(MANIFEST_URL)?.let(::JSONObject) ?: return null

        val versionCode = json.optLong("versionCode", 0L)
        val versionName = json.optString("versionName", "")
        val apkUrl = json.optString("apkUrl", "").takeIf { it.isNotBlank() }
        val sha256 = json.optString("sha256", "").takeIf { it.isNotBlank() }
        val notes = json.optString("notes", "")

        val chunksJson = json.optJSONArray("chunks")
        val chunks = buildList {
            if (chunksJson != null) {
                for (i in 0 until chunksJson.length()) {
                    chunksJson.optString(i, "").takeIf { it.isNotBlank() }?.let(::add)
                }
            }
        }

        if (versionCode <= 0L || versionName.isBlank() ||
            (apkUrl.isNullOrBlank() && chunks.isEmpty())
        ) return null

        return UpdateDescriptor(
            versionCode = versionCode,
            versionName = versionName,
            apkUrl = apkUrl,
            chunks = chunks,
            sha256 = sha256,
            notes = notes
        )
    }

    private fun downloadChunkedApk(urls: List<String>, destination: File): Boolean {
        destination.parentFile?.mkdirs()
        destination.outputStream().use { output ->
            for ((index, url) in urls.withIndex()) {
                val text = fetchText(url) ?: run {
                    destination.delete()
                    return false
                }
                val bytes = try {
                    Base64.decode(text.trim(), Base64.DEFAULT)
                } catch (_: Exception) {
                    destination.delete()
                    return false
                }
                output.write(bytes)
                output.flush()
            }
        }
        return destination.length() > 100_000L
    }

    private fun downloadBinary(url: String, destination: File): Boolean {
        destination.parentFile?.mkdirs()
        val connection = connection(url, 15_000, 45_000)

        return try {
            if (connection.responseCode !in 200..299) return false
            connection.inputStream.use { input ->
                destination.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            destination.length() > 100_000L
        } catch (_: Exception) {
            destination.delete()
            false
        } finally {
            connection.disconnect()
        }
    }

    private fun fetchText(url: String): String? {
        val connection = connection(url, 10_000, 20_000)
        return try {
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun connection(
        url: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int
    ) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = connectTimeoutMs
        readTimeout = readTimeoutMs
        requestMethod = "GET"
        instanceFollowRedirects = true
        useCaches = false
        setRequestProperty("Cache-Control", "no-cache")
        setRequestProperty("User-Agent", "HealthCoach-Android")
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count <= 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    @Suppress("DEPRECATION")
    private fun installedVersionCode(activity: Activity): Long {
        val info = activity.packageManager.getPackageInfo(activity.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            info.versionCode.toLong()
        }
    }

    private fun installedVersionName(activity: Activity): String =
        activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: "?"

    @Suppress("DEPRECATION")
    private fun validateApk(
        activity: Activity,
        apk: File,
        expectedVersionCode: Long
    ): String? {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            android.content.pm.PackageManager.GET_SIGNATURES
        }

        val archive = activity.packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
            ?: return "APK illisible"

        if (archive.packageName != activity.packageName) {
            return "nom de paquet incorrect"
        }

        val archiveVersion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            archive.longVersionCode
        } else {
            archive.versionCode.toLong()
        }

        if (archiveVersion != expectedVersionCode) {
            return "version APK différente du manifeste"
        }

        val installed = activity.packageManager.getPackageInfo(activity.packageName, flags)
        val installedDigests = signerDigests(installed)
        val archiveDigests = signerDigests(archive)

        if (installedDigests.isEmpty() || archiveDigests.isEmpty() ||
            installedDigests.intersect(archiveDigests).isEmpty()
        ) {
            return "signature différente de l'application installée"
        }

        return null
    }

    @Suppress("DEPRECATION")
    private fun signerDigests(info: android.content.pm.PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = info.signingInfo ?: return emptySet()
            if (signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners
            } else {
                signingInfo.signingCertificateHistory
            }
        } else {
            info.signatures
        }

        return signatures.orEmpty().map { signature ->
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
            digest.joinToString("") { "%02x".format(it) }
        }.toSet()
    }
}
