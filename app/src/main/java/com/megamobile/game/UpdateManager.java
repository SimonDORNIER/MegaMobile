package com.megamobile.game;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;
import androidx.core.content.FileProvider;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class UpdateManager {
    private static final String APP_MANIFEST_URL = "https://raw.githubusercontent.com/SimonDORNIER/MegaMobile/main/live/app.json";
    private static final String PREFS = "mega_update", PENDING_PATH = "pending_path", PENDING_NAME = "pending_name";
    private static volatile boolean checking;
    private UpdateManager() { }

    public static void checkAndUpdate(Activity activity) {
        if (checking || activity == null) return; checking = true;
        Thread t = new Thread(() -> {
            try {
                JSONObject json = new JSONObject(downloadText(APP_MANIFEST_URL + "?t=" + System.currentTimeMillis()));
                int remoteCode = json.optInt("versionCode", BuildConfig.VERSION_CODE);
                if (remoteCode <= BuildConfig.VERSION_CODE) return;
                String remoteName = json.optString("versionName", String.valueOf(remoteCode));
                String apkUrl = json.optString("apkUrl", ""), sha256 = json.optString("sha256", "");
                if (apkUrl.isEmpty()) return;
                File dir = new File(activity.getExternalFilesDir(null), "updates"); if (!dir.exists()) dir.mkdirs();
                File apk = new File(dir, "MegaMobile-" + safe(remoteName) + ".apk");
                boolean ready = apk.exists() && apk.length() > 1024;
                if (ready && !sha256.isEmpty()) ready = sha256.equalsIgnoreCase(fileSha256(apk));
                if (!ready) {
                    activity.runOnUiThread(() -> Toast.makeText(activity, "Mise à jour MegaMobile en téléchargement…", Toast.LENGTH_SHORT).show());
                    downloadToFile(apkUrl, apk);
                }
                if (!sha256.isEmpty() && !sha256.equalsIgnoreCase(fileSha256(apk))) { apk.delete(); return; }
                activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE).edit().putString(PENDING_PATH, apk.getAbsolutePath()).putString(PENDING_NAME, remoteName).apply();
                activity.runOnUiThread(() -> requestInstall(activity, apk, remoteName));
            } catch (Exception ignored) { } finally { checking = false; }
        }, "MegaMobileUpdater"); t.setDaemon(true); t.start();
    }

    public static void tryInstallPending(Activity activity) {
        if (activity == null) return;
        String path = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE).getString(PENDING_PATH, "");
        if (path == null || path.isEmpty()) return;
        File apk = new File(path); if (!apk.exists() || apk.length() < 1024) { clearPending(activity); return; }
        String name = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE).getString(PENDING_NAME, "nouvelle version");
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || activity.getPackageManager().canRequestPackageInstalls()) requestInstall(activity, apk, name);
    }

    private static void requestInstall(Activity activity, File apk, String versionName) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.getPackageManager().canRequestPackageInstalls()) {
                Toast.makeText(activity, "Autorise MegaMobile à installer ses mises à jour une seule fois.", Toast.LENGTH_LONG).show();
                activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + activity.getPackageName()))); return;
            }
            Uri uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".updates", apk);
            Intent install = new Intent(Intent.ACTION_VIEW); install.setDataAndType(uri, "application/vnd.android.package-archive");
            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            Toast.makeText(activity, "MegaMobile " + versionName + " est prête à être installée.", Toast.LENGTH_SHORT).show();
            activity.startActivity(install);
        } catch (Exception ignored) { }
    }
    public static void clearPending(Activity a) { a.getSharedPreferences(PREFS, Activity.MODE_PRIVATE).edit().remove(PENDING_PATH).remove(PENDING_NAME).apply(); }
    private static String safe(String v){return v==null?"update":v.replaceAll("[^A-Za-z0-9._-]","_");}
    private static String downloadText(String url)throws Exception{return new String(downloadBytes(url),StandardCharsets.UTF_8);}
    private static byte[] downloadBytes(String u)throws Exception{HttpURLConnection c=null;try{c=(HttpURLConnection)new URL(u).openConnection();c.setConnectTimeout(5000);c.setReadTimeout(9000);c.setUseCaches(false);c.setRequestProperty("Cache-Control","no-cache");c.setRequestProperty("User-Agent","MegaMobile-Updater/"+BuildConfig.VERSION_NAME);try(BufferedInputStream in=new BufferedInputStream(c.getInputStream());ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[]b=new byte[16384];int n;while((n=in.read(b))>=0)if(n>0)out.write(b,0,n);return out.toByteArray();}}finally{if(c!=null)c.disconnect();}}
    private static void downloadToFile(String u,File target)throws Exception{File temp=new File(target.getParentFile(),target.getName()+".part");HttpURLConnection c=null;try{c=(HttpURLConnection)new URL(u).openConnection();c.setConnectTimeout(7000);c.setReadTimeout(20000);c.setUseCaches(false);c.setRequestProperty("User-Agent","MegaMobile-Updater/"+BuildConfig.VERSION_NAME);try(BufferedInputStream in=new BufferedInputStream(c.getInputStream());FileOutputStream out=new FileOutputStream(temp)){byte[]b=new byte[32768];int n;while((n=in.read(b))>=0)if(n>0)out.write(b,0,n);out.flush();}if(target.exists())target.delete();if(!temp.renameTo(target)){try(FileInputStream in=new FileInputStream(temp);FileOutputStream out=new FileOutputStream(target)){byte[]b=new byte[32768];int n;while((n=in.read(b))>=0)if(n>0)out.write(b,0,n);}temp.delete();}}finally{if(c!=null)c.disconnect();if(temp.exists()&&(!target.exists()||target.length()==0))temp.delete();}}
    private static String fileSha256(File f)throws Exception{MessageDigest d=MessageDigest.getInstance("SHA-256");try(FileInputStream in=new FileInputStream(f)){byte[]b=new byte[32768];int n;while((n=in.read(b))>=0)if(n>0)d.update(b,0,n);}StringBuilder s=new StringBuilder();for(byte b:d.digest())s.append(String.format("%02x",b&255));return s.toString();}
}
