package com.megamobile.game;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public final class RemoteConfig {
    public static final String CONFIG_URL = "https://raw.githubusercontent.com/SimonDORNIER/MegaMobile/main/live/config.json";

    public String contentVersion = "0.3.0";
    public float playerBaseSpeed = 290f;
    public float spawnInterval = 0.62f;
    public float difficultySeconds = 65f;
    public int enemyCap = 180;
    public float bossInterval = 45f;
    public float xpMagnet = 170f;
    public float dashCooldown = 2.6f;
    public float dashDistance = 230f;

    public interface Callback {
        void onLoaded(RemoteConfig config);
    }

    public static void loadAsync(Callback callback) {
        Thread thread = new Thread(() -> {
            RemoteConfig result = new RemoteConfig();
            HttpURLConnection connection = null;
            try {
                URL url = new URL(CONFIG_URL + "?t=" + System.currentTimeMillis());
                connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(2500);
                connection.setReadTimeout(2500);
                connection.setUseCaches(false);
                connection.setRequestProperty("Cache-Control", "no-cache");
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder text = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) text.append(line);
                    JSONObject json = new JSONObject(text.toString());
                    result.contentVersion = json.optString("contentVersion", result.contentVersion);
                    result.playerBaseSpeed = (float) json.optDouble("playerBaseSpeed", result.playerBaseSpeed);
                    result.spawnInterval = (float) json.optDouble("spawnInterval", result.spawnInterval);
                    result.difficultySeconds = (float) json.optDouble("difficultySeconds", result.difficultySeconds);
                    result.enemyCap = json.optInt("enemyCap", result.enemyCap);
                    result.bossInterval = (float) json.optDouble("bossInterval", result.bossInterval);
                    result.xpMagnet = (float) json.optDouble("xpMagnet", result.xpMagnet);
                    result.dashCooldown = (float) json.optDouble("dashCooldown", result.dashCooldown);
                    result.dashDistance = (float) json.optDouble("dashDistance", result.dashDistance);
                }
            } catch (Exception ignored) {
                // Les valeurs embarquées restent utilisables hors-ligne.
            } finally {
                if (connection != null) connection.disconnect();
            }
            if (callback != null) callback.onLoaded(result);
        }, "MegaMobileConfig");
        thread.setDaemon(true);
        thread.start();
    }

    public void applyFrom(RemoteConfig other) {
        if (other == null) return;
        contentVersion = other.contentVersion;
        playerBaseSpeed = other.playerBaseSpeed;
        spawnInterval = other.spawnInterval;
        difficultySeconds = other.difficultySeconds;
        enemyCap = other.enemyCap;
        bossInterval = other.bossInterval;
        xpMagnet = other.xpMagnet;
        dashCooldown = other.dashCooldown;
        dashDistance = other.dashDistance;
    }
}
