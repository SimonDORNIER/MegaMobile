package com.healthcoach.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

data class UpdateDescriptor(
    val versionCode: Long,
    val versionName: String,
    val apkFileName: String,
    val notes: String
)

object UpdateManager {
    private const val MANIFEST_FILE = "healthcoach_update.json"
    private var checkRunning = false
    private var installerOpenedForVersion: Long? = null

    suspend fun checkOnLaunch(
        activity: Activity,
        onStatus: (String) -> Unit
    ) {
        if (checkRunning) return
        checkRunning = true

        try {
            val drive = DriveBridge(activity)
            if (!drive.hasFolder()) return

            val descriptor = withContext(Dispatchers.IO) {
                readDescriptor(drive)
            } ?: return

            val currentVersion = installedVersionCode(activity)
            if (descriptor.versionCode <= currentVersion) return
            if (installerOpenedForVersion == descriptor.versionCode) return

            onStatus("Mise à jour " + descriptor.versionName + " détectée…")

            val apk = File(
                File(activity.cacheDir, "updates"),
                "HealthCoach-" + descriptor.versionName + ".apk"
            )

            val copied = withContext(Dispatchers.IO) {
                drive.copyFileTo(descriptor.apkFileName, apk)
            }

            if (!copied) {
                onStatus("Mise à jour trouvée mais APK indisponible dans Drive.")
                return
            }

            val validation = withContext(Dispatchers.IO) {
                validateApk(activity, apk, descriptor.versionCode)
            }

            if (validation != null) {
                apk.delete()
                onStatus("Mise à jour refusée : " + validation)
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                !activity.packageManager.canRequestPackageInstalls()
            ) {
                onStatus("Autorise HealthCoach à installer ses mises à jour.")
                activity.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + activity.packageName)
                    )
                )
                return
            }

            installerOpenedForVersion = descriptor.versionCode
            val uri = FileProvider.getUriForFile(
                activity,
                activity.packageName + ".fileprovider",
                apk
            )

            activity.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (e: Exception) {
            onStatus("Vérification de mise à jour impossible : " +
                (e.message ?: e.javaClass.simpleName))
        } finally {
            checkRunning = false
        }
    }

    private fun readDescriptor(drive: DriveBridge): UpdateDescriptor? {
        val json = drive.readJson(MANIFEST_FILE) ?: return null
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
