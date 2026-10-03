package com.healthcoach.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

data class UpdateDescriptor(
    val versionCode: Long,
    val versionName: String,
    val apkFileName: String,
    val notes: String
)

object UpdateManager {
    private const val STATUS_FILE = "healthcoach_status.json"
    private const val LEGACY_MANIFEST_FILE = "healthcoach_update.json"
    private var checkRunning = false
    private var lastInstallerVersion: Long? = null
    private var lastInstallerAt: Long = 0L

    /**
     * @return true lorsqu'une version plus récente existe et qu'on traite cette mise à jour.
     *         Le lancement de la synchro Santé Connect peut ainsi attendre.
     */
    suspend fun checkOnLaunch(
        activity: Activity,
        onStatus: (String) -> Unit
    ): Boolean {
        if (checkRunning) return false
        checkRunning = true

        try {
            val drive = DriveBridge(activity)
            if (!drive.hasFolder()) {
                onStatus("Mise à jour : dossier Drive non configuré.")
                return false
            }

            onStatus("Vérification des mises à jour…")
            val descriptor = withContext(Dispatchers.IO) {
                readDescriptor(drive)
            } ?: run {
                onStatus("Mise à jour : métadonnées introuvables dans Drive.")
                return false
            }

            val currentVersion = installedVersionCode(activity)
            if (descriptor.versionCode <= currentVersion) {
                onStatus("HealthCoach " + descriptor.versionName + " est déjà à jour.")
                return false
            }

            onStatus(
                "Mise à jour " + descriptor.versionName +
                    " détectée (installée : " + installedVersionName(activity) + ")."
            )

            val now = System.currentTimeMillis()
            if (lastInstallerVersion == descriptor.versionCode &&
                now - lastInstallerAt < 15_000L
            ) {
                return true
            }

            val apk = File(
                File(activity.cacheDir, "updates"),
                "HealthCoach-" + descriptor.versionName + ".apk"
            )

            onStatus("Téléchargement de HealthCoach " + descriptor.versionName + "…")
            val copied = withContext(Dispatchers.IO) {
                drive.copyFileTo(descriptor.apkFileName, apk)
            }

            if (!copied) {
                onStatus(
                    "Mise à jour " + descriptor.versionName +
                        " trouvée, mais " + descriptor.apkFileName +
                        " n'est pas accessible depuis le dossier Drive Android."
                )
                return true
            }

            onStatus("Vérification de la signature de la mise à jour…")
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

            val uri = FileProvider.getUriForFile(
                activity,
                activity.packageName + ".fileprovider",
                apk
            )

            lastInstallerVersion = descriptor.versionCode
            lastInstallerAt = now
            onStatus("Ouverture de l'installation HealthCoach " + descriptor.versionName + "…")
            activity.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            )
            return true
        } catch (e: Exception) {
            onStatus(
                "Vérification de mise à jour impossible : " +
                    (e.message ?: e.javaClass.simpleName)
            )
            return false
        } finally {
            checkRunning = false
        }
    }

    private fun readDescriptor(drive: DriveBridge): UpdateDescriptor? {
        val status = drive.readJson(STATUS_FILE)
        val json = status?.optJSONObject("update")
            ?: drive.readJson(LEGACY_MANIFEST_FILE)
            ?: return null

        val versionCode = json.optLong("versionCode", 0L)
        val versionName = json.optString("versionName", "")
        val apkFileName = json.optString("apkFileName", "HealthCoach-latest.apk")
        val notes = json.optString("notes", "")

        if (versionCode <= 0L || versionName.isBlank() || apkFileName.isBlank()) return null
        return UpdateDescriptor(versionCode, versionName, apkFileName, notes)
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
