package com.megamobile.game;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public final class RemoteSkin {
    public static final String SKIN_URL = "https://raw.githubusercontent.com/SimonDORNIER/MegaMobile/main/live/skin.json";
    public String version = "embedded";
    public Bitmap player, grunt, fast, tank, shooter, boss, chest, gem, rocket;
    public interface Callback { void onLoaded(RemoteSkin skin); }
    public static RemoteSkin empty() { return new RemoteSkin(); }
    public boolean hasAnyBitmap() { return player != null || grunt != null || fast != null || tank != null || shooter != null || boss != null || chest != null || gem != null; }
    public Bitmap enemyFor(int type) { switch (type) { case 1:return fast; case 2:return tank; case 3:return shooter; case 4:return boss; default:return grunt; } }

    public static void loadAsync(Context context, Callback callback) {
        Thread t = new Thread(() -> {
            RemoteSkin skin = new RemoteSkin();
            try {
                File dir = new File(context.getCacheDir(), "megamobile-live"); if (!dir.exists()) dir.mkdirs();
                File cachedJson = new File(dir, "skin.json"); String jsonText = null;
                try { jsonText = downloadText(SKIN_URL + "?t=" + System.currentTimeMillis()); writeBytes(cachedJson, jsonText.getBytes(StandardCharsets.UTF_8)); }
                catch (Exception e) { if (cachedJson.exists()) jsonText = readText(cachedJson); }
                if (jsonText == null || jsonText.trim().isEmpty()) { if (callback != null) callback.onLoaded(skin); return; }
                JSONObject json = new JSONObject(jsonText);
                skin.version = json.optString("skinVersion", "1");
                String base = json.optString("assetsBase", "https://raw.githubusercontent.com/SimonDORNIER/MegaMobile/main/live/assets/");
                skin.player = loadBitmap(dir, skin.version, "player", resolve(base, json.optString("player", "player.png")));
                skin.grunt = loadBitmap(dir, skin.version, "grunt", resolve(base, json.optString("grunt", "grunt.png")));
                skin.fast = loadBitmap(dir, skin.version, "fast", resolve(base, json.optString("fast", "fast.png")));
                skin.tank = loadBitmap(dir, skin.version, "tank", resolve(base, json.optString("tank", "tank.png")));
                skin.shooter = loadBitmap(dir, skin.version, "shooter", resolve(base, json.optString("shooter", "shooter.png")));
                skin.boss = loadBitmap(dir, skin.version, "boss", resolve(base, json.optString("boss", "boss.png")));
                skin.chest = loadBitmap(dir, skin.version, "chest", resolve(base, json.optString("chest", "chest.png")));
                skin.gem = loadBitmap(dir, skin.version, "gem", resolve(base, json.optString("gem", "gem.png")));
                skin.rocket = loadBitmap(dir, skin.version, "rocket", resolve(base, json.optString("rocket", "rocket.png")));
            } catch (Exception ignored) { }
            if (callback != null) callback.onLoaded(skin);
        }, "MegaMobileSkin"); t.setDaemon(true); t.start();
    }

    private static String resolve(String base, String path) { if (path.startsWith("http://") || path.startsWith("https://")) return path; if (!base.endsWith("/")) base += "/"; return base + path; }
    private static Bitmap loadBitmap(File dir, String version, String key, String url) {
        File file = new File(dir, "skin_" + safe(version) + "_" + key + ".png");
        try { if (!file.exists() || file.length() < 32) writeBytes(file, downloadBytes(url + (url.contains("?") ? "&" : "?") + "v=" + safe(version))); return BitmapFactory.decodeFile(file.getAbsolutePath()); }
        catch (Exception e) { try { if (file.exists()) return BitmapFactory.decodeFile(file.getAbsolutePath()); } catch (Exception ignored) { } return null; }
    }
    private static String safe(String v) { return v == null ? "0" : v.replaceAll("[^A-Za-z0-9._-]", "_"); }
    private static String downloadText(String url) throws Exception { return new String(downloadBytes(url), StandardCharsets.UTF_8); }
    private static byte[] downloadBytes(String urlText) throws Exception {
        HttpURLConnection c = null; try { c = (HttpURLConnection)new URL(urlText).openConnection(); c.setConnectTimeout(4500); c.setReadTimeout(6500); c.setUseCaches(false); c.setRequestProperty("Cache-Control","no-cache"); c.setRequestProperty("User-Agent","MegaMobile/0.4");
            try (BufferedInputStream in = new BufferedInputStream(c.getInputStream()); ByteArrayOutputStream out = new ByteArrayOutputStream()) { byte[] b = new byte[8192]; int n; while ((n=in.read(b))>=0) if(n>0) out.write(b,0,n); return out.toByteArray(); }
        } finally { if (c != null) c.disconnect(); }
    }
    private static void writeBytes(File f, byte[] bytes) throws Exception { File p=f.getParentFile(); if(p!=null&&!p.exists())p.mkdirs(); try(FileOutputStream out=new FileOutputStream(f)){out.write(bytes);out.flush();} }
    private static String readText(File f) throws Exception { try(FileInputStream in=new FileInputStream(f);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[4096];int n;while((n=in.read(b))>=0)if(n>0)out.write(b,0,n);return out.toString(StandardCharsets.UTF_8.name());} }
}
