package com.megamobile.game;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class UpdateManager {
    private static final String APP_MANIFEST_URL = "https://raw.githubusercontent.com/SimonDORNIER/MegaMobile/main/live/app.json";
    private static final String PREFS = "mega_update";
    private static final String PENDING_PATH = "pending_path";
    private static final String PENDING_NAME = "pending_name";
    private static final String PENDING_CODE = "pending_code";
    private static volatile boolean checking;

    private UpdateManager() { }

    public static void checkAndUpdate(Activity activity) {
        if (checking || activity == null) return;
        checking = true;
        Thread t = new Thread(() -> {
            try {
                int currentCode = localVersionCode(activity);
                JSONObject json = new JSONObject(downloadText(APP_MANIFEST_URL + "?t=" + System.currentTimeMillis()));
                int remoteCode = json.optInt("versionCode", currentCode);
                if (remoteCode <= currentCode) {
                    clearPendingIfInstalled(activity, currentCode);
                    return;
                }

                String remoteName = json.optString("versionName", String.valueOf(remoteCode));
                String apkUrl = json.optString("apkUrl", "");
                String sha256 = json.optString("sha256", "").trim();
                if (apkUrl.isEmpty()) return;

                File root = activity.getExternalFilesDir(null);
                if (root == null) return;
                File dir = new File(root, "updates");
                if (!dir.exists() && !dir.mkdirs()) return;
                File apk = new File(dir, "MegaMobile-" + safe(remoteName) + ".apk");

                boolean ready = apk.exists() && apk.length() > 1024;
                if (ready && !sha256.isEmpty()) ready = sha256.equalsIgnoreCase(fileSha256(apk));
                if (!ready) {
                    activity.runOnUiThread(() -> Toast.makeText(activity,
                            "Mise à jour MegaMobile en téléchargement…", Toast.LENGTH_SHORT).show());
                    downloadToFile(apkUrl, apk);
                }

                if (!sha256.isEmpty() && !sha256.equalsIgnoreCase(fileSha256(apk))) {
                    apk.delete();
                    return;
                }

                activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE).edit()
                        .putString(PENDING_PATH, apk.getAbsolutePath())
                        .putString(PENDING_NAME, remoteName)
                        .putInt(PENDING_CODE, remoteCode)
                        .apply();
                activity.runOnUiThread(() -> requestInstall(activity, apk, remoteName));
            } catch (Exception ignored) {
                // Pas de réseau : le jeu reste entièrement jouable hors-ligne.
            } finally {
                checking = false;
            }
        }, "MegaMobileUpdater");
        t.setDaemon(true);
        t.start();
    }

    public static void tryInstallPending(Activity activity) {
        if (activity == null) return;
        int currentCode = localVersionCode(activity);
        int pendingCode = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE).getInt(PENDING_CODE, 0);
        if (pendingCode <= currentCode) {
            clearPending(activity);
            return;
        }

        String path = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE).getString(PENDING_PATH, "");
        if (path == null || path.isEmpty()) return;
        File apk = new File(path);
        if (!apk.exists() || apk.length() < 1024) {
            clearPending(activity);
            return;
        }
        String name = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
                .getString(PENDING_NAME, "nouvelle version");
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || activity.getPackageManager().canRequestPackageInstalls()) {
            requestInstall(activity, apk, name);
        }
    }

    private static void clearPendingIfInstalled(Activity activity, int currentCode) {
        int pendingCode = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE).getInt(PENDING_CODE, 0);
        if (pendingCode <= currentCode) clearPending(activity);
    }

    private static void requestInstall(Activity activity, File apk, String versionName) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.getPackageManager().canRequestPackageInstalls()) {
                Toast.makeText(activity,
                        "Autorise MegaMobile à installer ses mises à jour une seule fois.", Toast.LENGTH_LONG).show();
                activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + activity.getPackageName())));
                return;
            }
            Uri uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".updates", apk);
            Intent install = new Intent(Intent.ACTION_VIEW);
            install.setDataAndType(uri, "application/vnd.android.package-archive");
            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            Toast.makeText(activity, "MegaMobile " + versionName + " est prête à être installée.",
                    Toast.LENGTH_SHORT).show();
            activity.startActivity(install);
        } catch (Exception ignored) { }
    }

    public static void clearPending(Activity activity) {
        activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE).edit()
                .remove(PENDING_PATH).remove(PENDING_NAME).remove(PENDING_CODE).apply();
    }

    private static int localVersionCode(Activity activity) {
        try {
            PackageInfo info = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) return (int) Math.min(Integer.MAX_VALUE, info.getLongVersionCode());
            return info.versionCode;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static String safe(String value) {
        return value == null ? "update" : value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String downloadText(String url) throws Exception {
        return new String(downloadBytes(url), StandardCharsets.UTF_8);
    }

    private static byte[] downloadBytes(String urlText) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(urlText).openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(9000);
            connection.setUseCaches(false);
            connection.setRequestProperty("Cache-Control", "no-cache");
            connection.setRequestProperty("User-Agent", "MegaMobile-Updater");
            try (BufferedInputStream in = new BufferedInputStream(connection.getInputStream());
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[16384];
                int n;
                while ((n = in.read(buffer)) >= 0) if (n > 0) out.write(buffer, 0, n);
                return out.toByteArray();
            }
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static void downloadToFile(String urlText, File target) throws Exception {
        File temp = new File(target.getParentFile(), target.getName() + ".part");
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(urlText).openConnection();
            connection.setConnectTimeout(7000);
            connection.setReadTimeout(20000);
            connection.setUseCaches(false);
            connection.setRequestProperty("User-Agent", "MegaMobile-Updater");
            try (BufferedInputStream in = new BufferedInputStream(connection.getInputStream());
                 FileOutputStream out = new FileOutputStream(temp)) {
                byte[] buffer = new byte[32768];
                int n;
                while ((n = in.read(buffer)) >= 0) if (n > 0) out.write(buffer, 0, n);
                out.flush();
            }
            if (target.exists()) target.delete();
            if (!temp.renameTo(target)) {
                try (FileInputStream in = new FileInputStream(temp);
                     FileOutputStream out = new FileOutputStream(target)) {
                    byte[] buffer = new byte[32768];
                    int n;
                    while ((n = in.read(buffer)) >= 0) if (n > 0) out.write(buffer, 0, n);
                }
                temp.delete();
            }
        } finally {
            if (connection != null) connection.disconnect();
            if (temp.exists() && (!target.exists() || target.length() == 0)) temp.delete();
        }
    }

    private static String fileSha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[32768];
            int n;
            while ((n = in.read(buffer)) >= 0) if (n > 0) digest.update(buffer, 0, n);
        }
        StringBuilder result = new StringBuilder();
        for (byte b : digest.digest()) result.append(String.format("%02x", b & 255));
        return result.toString();
    }
}
