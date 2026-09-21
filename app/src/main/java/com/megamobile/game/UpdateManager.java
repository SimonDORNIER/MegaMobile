package com.megamobile.game;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Base64;
import android.widget.Toast;

import org.json.JSONArray;
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
    private static final String PENDING_REQUESTED_AT = "pending_requested_at";
    private static final long INSTALL_RETRY_DELAY_MS = 30_000L;
    private static volatile boolean checking;

    private UpdateManager() { }

    public static void checkAndUpdate(Activity activity) {
        if (checking || activity == null) return;
        checking = true;
        Thread thread = new Thread(() -> {
            boolean foundUpdate = false;
            try {
                int currentCode = localVersionCode(activity);
                int pendingCode = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
                        .getInt(PENDING_CODE, 0);
                // Une installation Android est interactive. Tant qu'elle n'est pas
                // terminée, ne relance pas une seconde demande par-dessus la première.
                if (pendingCode > currentCode) return;
                JSONObject manifest = new JSONObject(downloadText(
                        APP_MANIFEST_URL + "?t=" + System.currentTimeMillis()));
                int remoteCode = manifest.optInt("versionCode", currentCode);
                if (remoteCode <= currentCode) {
                    clearPendingIfInstalled(activity, currentCode);
                    return;
                }
                foundUpdate = true;

                String remoteName = manifest.optString("versionName", String.valueOf(remoteCode));
                String apkUrl = manifest.optString("apkUrl", "").trim();
                String sha256 = manifest.optString("sha256", "").trim();
                JSONArray apkParts = manifest.optJSONArray("apkParts");
                boolean hasParts = apkParts != null && apkParts.length() > 0;
                if (!hasParts && apkUrl.isEmpty()) throw new IllegalStateException("aucune source APK");

                File root = activity.getExternalFilesDir(null);
                if (root == null) throw new IllegalStateException("stockage indisponible");
                File dir = new File(root, "updates");
                if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("dossier update indisponible");
                File apk = new File(dir, "MegaMobile-" + safe(remoteName) + ".apk");

                boolean ready = apk.exists() && apk.length() > 1024;
                if (ready && !sha256.isEmpty()) ready = sha256.equalsIgnoreCase(fileSha256(apk));

                if (!ready) {
                    activity.runOnUiThread(() -> toast(activity,
                            "Mise à jour détectée — téléchargement…", Toast.LENGTH_SHORT));
                    if (hasParts) downloadBase64Parts(apkParts, apk);
                    else downloadToFile(apkUrl, apk);
                }

                activity.runOnUiThread(() -> toast(activity,
                        "Téléchargement terminé — vérification…", Toast.LENGTH_SHORT));

                if (!sha256.isEmpty() && !sha256.equalsIgnoreCase(fileSha256(apk))) {
                    apk.delete();
                    throw new IllegalStateException("contrôle de sécurité invalide");
                }

                activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE).edit()
                        .putString(PENDING_PATH, apk.getAbsolutePath())
                        .putString(PENDING_NAME, remoteName)
                        .putInt(PENDING_CODE, remoteCode)
                        .putLong(PENDING_REQUESTED_AT, System.currentTimeMillis())
                        .apply();

                activity.runOnUiThread(() -> {
                    toast(activity, "Mise à jour vérifiée — ouverture de l'installation…", Toast.LENGTH_LONG);
                    requestInstall(activity, apk, remoteName);
                });
            } catch (Exception e) {
                if (foundUpdate) {
                    final String reason = e.getMessage() == null ? "erreur inconnue" : e.getMessage();
                    activity.runOnUiThread(() -> toast(activity,
                            "Échec de la mise à jour : " + reason, Toast.LENGTH_LONG));
                }
            } finally {
                checking = false;
            }
        }, "MegaMobileUpdater");
        thread.setDaemon(true);
        thread.start();
    }

    public static void tryInstallPending(Activity activity) {
        if (activity == null) return;
        int currentCode = localVersionCode(activity);
        int pendingCode = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
                .getInt(PENDING_CODE, 0);
        if (pendingCode <= currentCode) {
            clearPending(activity);
            return;
        }

        String path = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
                .getString(PENDING_PATH, "");
        if (path == null || path.isEmpty()) return;
        long requestedAt = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
                .getLong(PENDING_REQUESTED_AT, 0L);
        if (requestedAt > 0L && System.currentTimeMillis() - requestedAt < INSTALL_RETRY_DELAY_MS) return;
        File apk = new File(path);
        if (!apk.exists() || apk.length() < 1024) {
            clearPending(activity);
            return;
        }

        String name = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
                .getString(PENDING_NAME, "nouvelle version");
        activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE).edit()
                .putLong(PENDING_REQUESTED_AT, System.currentTimeMillis())
                .apply();
        requestInstall(activity, apk, name);
    }

    private static void clearPendingIfInstalled(Activity activity, int currentCode) {
        int pendingCode = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
                .getInt(PENDING_CODE, 0);
        if (pendingCode <= currentCode) clearPending(activity);
    }

    private static void requestInstall(Activity activity, File apk, String versionName) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    && !activity.getPackageManager().canRequestPackageInstalls()) {
                toast(activity,
                        "Autorise MegaMobile à installer ses mises à jour, puis reviens au jeu.",
                        Toast.LENGTH_LONG);
                Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + activity.getPackageName()));
                activity.startActivity(settings);
                return;
            }

            Uri uri = new Uri.Builder()
                    .scheme("content")
                    .authority(activity.getPackageName() + ".updates")
                    .appendPath(apk.getName())
                    .build();

            Intent install = new Intent(Intent.ACTION_INSTALL_PACKAGE);
            install.setData(uri);
            install.setClipData(ClipData.newRawUri("MegaMobile update", uri));
            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);

            if (install.resolveActivity(activity.getPackageManager()) == null) {
                install = new Intent(Intent.ACTION_VIEW);
                install.setDataAndType(uri, "application/vnd.android.package-archive");
                install.setClipData(ClipData.newRawUri("MegaMobile update", uri));
                install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            }

            toast(activity,
                    "MegaMobile " + versionName + " prête — confirme la mise à jour Android.",
                    Toast.LENGTH_LONG);
            activity.startActivity(install);
        } catch (Exception e) {
            toast(activity,
                    "Impossible d'ouvrir l'installation Android : "
                            + (e.getMessage() == null ? "réessaie depuis le jeu" : e.getMessage()),
                    Toast.LENGTH_LONG);
        }
    }

    private static void toast(Activity activity, String text, int length) {
        try { Toast.makeText(activity, text, length).show(); }
        catch (Exception ignored) { }
    }

    public static void clearPending(Activity activity) {
        activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE).edit()
                .remove(PENDING_PATH)
                .remove(PENDING_NAME)
                .remove(PENDING_CODE)
                .remove(PENDING_REQUESTED_AT)
                .apply();
    }

    private static int localVersionCode(Activity activity) {
        try {
            PackageInfo info = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return (int) Math.min(Integer.MAX_VALUE, info.getLongVersionCode());
            }
            return info.versionCode;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static void downloadBase64Parts(JSONArray parts, File target) throws Exception {
        File temp = new File(target.getParentFile(), target.getName() + ".part");
        if (temp.exists()) temp.delete();
        try (FileOutputStream out = new FileOutputStream(temp)) {
            for (int i = 0; i < parts.length(); i++) {
                String partUrl = parts.getString(i);
                String encoded = downloadText(partUrl + (partUrl.contains("?") ? "&" : "?")
                        + "t=" + System.currentTimeMillis());
                byte[] decoded = Base64.decode(encoded, Base64.DEFAULT);
                out.write(decoded);
            }
            out.flush();
        }
        replaceFile(temp, target);
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
            connection.setReadTimeout(10000);
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
            connection.setReadTimeout(25000);
            connection.setUseCaches(false);
            connection.setRequestProperty("User-Agent", "MegaMobile-Updater");
            try (BufferedInputStream in = new BufferedInputStream(connection.getInputStream());
                 FileOutputStream out = new FileOutputStream(temp)) {
                byte[] buffer = new byte[32768];
                int n;
                while ((n = in.read(buffer)) >= 0) if (n > 0) out.write(buffer, 0, n);
                out.flush();
            }
            replaceFile(temp, target);
        } finally {
            if (connection != null) connection.disconnect();
            if (temp.exists() && (!target.exists() || target.length() == 0)) temp.delete();
        }
    }

    private static void replaceFile(File temp, File target) throws Exception {
        if (target.exists() && !target.delete()) throw new IllegalStateException("old update locked");
        if (temp.renameTo(target)) return;
        try (FileInputStream in = new FileInputStream(temp);
             FileOutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[32768];
            int n;
            while ((n = in.read(buffer)) >= 0) if (n > 0) out.write(buffer, 0, n);
            out.flush();
        }
        temp.delete();
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
