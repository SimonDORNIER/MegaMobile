package com.healthcoach.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class UpdateDescriptor(
    val versionName: String,
    val apkUrl: String,
    val notes: String
)

object UpdateManager {
    private const val RELEASES_URL =
        "https://api.github.com/repos/SimonDORNIER/MegaMobile/releases?per_page=30"

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
                fetchLatestHealthCoachRelease()
            } ?: run {
                onStatus("Mise à jour : aucune release HealthCoach accessible.")
                return false
            }

            val currentName = installedVersionName(activity)
            if (compareVersions(descriptor.versionName, currentName) <= 0) {
                onStatus("HealthCoach " + currentName + " est à jour.")
                return false
            }

            onStatus(
                "Mise à jour " + descriptor.versionName +
                    " détectée (installée : " + currentName + ")."
            )

            val apk = File(
                File(activity.cacheDir, "updates"),
                "HealthCoach-" + descriptor.versionName + ".apk"
            )

            val downloaded = withContext(Dispatchers.IO) {
                downloadBinary(descriptor.apkUrl, apk)
            }

            if (!downloaded) {
                onStatus("Téléchargement de la mise à jour impossible.")
                return true
            }

            onStatus("Vérification de la signature…")
            val validation = withContext(Dispatchers.IO) {
                validateApk(activity, apk)
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
            onStatus("Mise à jour impossible : " + (e.message ?: e.javaClass.simpleName))
            return false
        } finally {
            checkRunning = false
        }
    }

    private fun fetchLatestHealthCoachRelease(): UpdateDescriptor? {
        val text = fetchText(RELEASES_URL) ?: return null
        val releases = JSONArray(text)

        for (i in 0 until releases.length()) {
            val release = releases.optJSONObject(i) ?: continue
            if (release.optBoolean("draft", false) || release.optBoolean("prerelease", false)) continue

            val tag = release.optString("tag_name", "")
            if (!tag.startsWith("healthcoach-v")) continue

            val version = tag.removePrefix("healthcoach-v")
            if (version.isBlank()) continue

            val assets = release.optJSONArray("assets") ?: continue
            for (j in 0 until assets.length()) {
                val asset = assets.optJSONObject(j) ?: continue
                val name = asset.optString("name", "")
                val url = asset.optString("browser_download_url", "")
                if (name.equals("HealthCoach-" + version + ".apk", ignoreCase = true) &&
                    url.isNotBlank()
                ) {
                    return UpdateDescriptor(
                        versionName = version,
                        apkUrl = url,
                        notes = release.optString("body", "")
                    )
                }
            }
        }
        return null
    }

    private fun compareVersions(a: String, b: String): Int {
        val aa = a.split(".").map { it.toIntOrNull() ?: 0 }
        val bb = b.split(".").map { it.toIntOrNull() ?: 0 }
        val size = maxOf(aa.size, bb.size)
        for (i in 0 until size) {
            val av = aa.getOrElse(i) { 0 }
            val bv = bb.getOrElse(i) { 0 }
            if (av != bv) return av.compareTo(bv)
        }
        return 0
    }

    private fun downloadBinary(url: String, destination: File): Boolean {
        destination.parentFile?.mkdirs()
        val connection = connection(url, 15_000, 60_000)

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
        setRequestProperty("Accept", "application/vnd.github+json")
    }

    private fun installedVersionName(activity: Activity): String =
        activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: "0.0.0"

    @Suppress("DEPRECATION")
    private fun installedVersionCode(activity: Activity): Long {
        val info = activity.packageManager.getPackageInfo(activity.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            info.versionCode.toLong()
        }
    }

    @Suppress("DEPRECATION")
    private fun validateApk(
        activity: Activity,
        apk: File
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

        if (archiveVersion <= installedVersionCode(activity)) {
            return "version APK non supérieure à la version installée"
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
