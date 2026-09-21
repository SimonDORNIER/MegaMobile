package com.megamobile.game;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * V0.6 presentation/content layer.
 * Adds long-run events, rare relic pickups, haptics/lightweight SFX and persistent run stats.
 */
public class GameViewV6 extends GameViewFinal {
    private static final String PREFS_V6 = "MegaMobileV6";
    private static final String P_RUNS = "runs";
    private static final String P_TOTAL_KILLS = "totalKills";
    private static final String P_BEST_TIME = "bestTime";

    private static final int RELIC_SHIELD = 0;
    private static final int RELIC_FRENZY = 1;
    private static final int RELIC_XP = 2;

    private final Paint paintV6 = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokeV6 = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random randomV6 = new Random();
    private final ArrayList<Relic> relics = new ArrayList<>();
    private final SharedPreferences prefsV6;
    private final ToneGenerator tones;
    private final Vibrator vibrator;

    private Field fElapsed, fKills, fScore, fHp, fMaxHp, fDamage, fWeaponHaste, fSpeed, fInvuln;
    private Field fPaused, fDead, fChoosing, fEnemies, fPx, fPy, fCamX, fCamY;
    private Field fInMenu;
    private Method mSpawnEnemy, mShowBanner, mGainXp;

    private float eventTimer = 58f;
    private int eventIndex;
    private float relicTimer = 34f;
    private float frenzyTimer;
    private boolean frenzyActive;
    private boolean previousDead;
    private boolean previousMenu = true;
    private long lastLayerMs;

    private int runs;
    private int totalKills;
    private float bestTime;
    private int killsAtRunStart;

    private String eventLabel = "";
    private float eventLabelLife;

    public GameViewV6(Context context) {
        super(context);
        prefsV6 = context.getSharedPreferences(PREFS_V6, Context.MODE_PRIVATE);
        runs = prefsV6.getInt(P_RUNS, 0);
        totalKills = prefsV6.getInt(P_TOTAL_KILLS, 0);
        bestTime = prefsV6.getFloat(P_BEST_TIME, 0f);
        tones = new ToneGenerator(AudioManager.STREAM_MUSIC, 28);
        vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        strokeV6.setStyle(Paint.Style.STROKE);
        strokeV6.setStrokeWidth(3f);
        bindV6();
        lastLayerMs = SystemClock.uptimeMillis();
    }

    private void bindV6() {
        try {
            fElapsed = baseField("elapsed");
            fKills = baseField("kills");
            fScore = baseField("score");
            fHp = baseField("hp");
            fMaxHp = baseField("maxHp");
            fDamage = baseField("damage");
            fWeaponHaste = baseField("weaponHaste");
            fSpeed = baseField("speed");
            fInvuln = baseField("invuln");
            fPaused = baseField("paused");
            fDead = baseField("dead");
            fChoosing = baseField("choosing");
            fEnemies = baseField("enemies");
            fPx = baseField("px");
            fPy = baseField("py");
            fCamX = baseField("camX");
            fCamY = baseField("camY");
            fInMenu = GameViewFinal.class.getDeclaredField("inMenu");
            fInMenu.setAccessible(true);

            mSpawnEnemy = GameView.class.getDeclaredMethod("spawnEnemy", boolean.class);
            mSpawnEnemy.setAccessible(true);
            mShowBanner = GameView.class.getDeclaredMethod("showBanner", String.class);
            mShowBanner.setAccessible(true);
            mGainXp = GameView.class.getDeclaredMethod("gainXp", float.class);
            mGainXp.setAccessible(true);
        } catch (Exception ignored) { }
    }

    private Field baseField(String name) throws NoSuchFieldException {
        Field f = GameView.class.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    private boolean bool(Field f) {
        try { return f != null && f.getBoolean(this); }
        catch (Exception ignored) { return false; }
    }

    private float number(Field f) {
        try { return f == null ? 0f : f.getFloat(this); }
        catch (Exception ignored) { return 0f; }
    }

    private int integer(Field f) {
        try { return f == null ? 0 : f.getInt(this); }
        catch (Exception ignored) { return 0; }
    }

    private boolean inMenu() {
        try { return fInMenu != null && fInMenu.getBoolean(this); }
        catch (Exception ignored) { return false; }
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        super.doFrame(frameTimeNanos);
        long now = SystemClock.uptimeMillis();
        float dt = Math.min(0.08f, Math.max(0f, (now - lastLayerMs) / 1000f));
        lastLayerMs = now;
        updateV6(dt);
    }

    private void updateV6(float dt) {
        boolean menu = inMenu();
        boolean dead = bool(fDead);

        if (previousMenu && !menu) startRunStats();
        if (previousDead && !dead && !menu) startRunStats();
        if (!previousDead && dead) finishRunStats();
        previousMenu = menu;
        previousDead = dead;

        if (menu || dead || bool(fPaused) || bool(fChoosing)) return;

        if (eventLabelLife > 0f) eventLabelLife -= dt;
        if (frenzyActive) {
            frenzyTimer -= dt;
            if (frenzyTimer <= 0f) stopFrenzy();
        }

        eventTimer -= dt;
        if (eventTimer <= 0f) {
            triggerEvent();
            eventTimer = 66f + randomV6.nextFloat() * 30f;
        }

        relicTimer -= dt;
        if (relicTimer <= 0f && relics.size() < 2) {
            spawnRelic();
            relicTimer = 42f + randomV6.nextFloat() * 34f;
        }
        updateRelics(dt);
    }

    private void startRunStats() {
        runs++;
        killsAtRunStart = integer(fKills);
        eventTimer = 52f;
        relicTimer = 30f;
        relics.clear();
        saveStats();
    }

    private void finishRunStats() {
        float elapsed = number(fElapsed);
        int runKills = Math.max(0, integer(fKills) - killsAtRunStart);
        totalKills += runKills;
        if (elapsed > bestTime) bestTime = elapsed;
        saveStats();
        pulse(70);
        tone(ToneGenerator.TONE_PROP_NACK, 180);
    }

    private void saveStats() {
        prefsV6.edit()
                .putInt(P_RUNS, runs)
                .putInt(P_TOTAL_KILLS, totalKills)
                .putFloat(P_BEST_TIME, bestTime)
                .apply();
    }

    private void triggerEvent() {
        int kind = eventIndex++ % 4;
        try {
            if (kind == 0) {
                label("VAGUE MASSIVE");
                for (int i = 0; i < 16; i++) mSpawnEnemy.invoke(this, false);
                if (fScore != null) fScore.setInt(this, integer(fScore) + 120);
                tone(ToneGenerator.TONE_PROP_BEEP2, 180);
            } else if (kind == 1) {
                label("DOUBLE BOSS");
                mSpawnEnemy.invoke(this, true);
                mSpawnEnemy.invoke(this, true);
                tone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 260);
                pulse(65);
            } else if (kind == 2) {
                label("SURCHARGE 12s");
                startFrenzy(12f);
                tone(ToneGenerator.TONE_PROP_ACK, 160);
            } else {
                label("SECONDE CHANCE");
                float max = number(fMaxHp);
                if (fHp != null) fHp.setFloat(this, Math.min(max, number(fHp) + Math.max(22f, max * 0.22f)));
                if (mGainXp != null) mGainXp.invoke(this, 10f + Math.min(25f, number(fElapsed) / 30f));
                tone(ToneGenerator.TONE_PROP_PROMPT, 160);
            }
        } catch (Exception ignored) { }
    }

    private void startFrenzy(float seconds) {
        try {
            if (!frenzyActive) {
                if (fDamage != null) fDamage.setFloat(this, number(fDamage) * 1.32f);
                if (fWeaponHaste != null) fWeaponHaste.setFloat(this, Math.min(8f, number(fWeaponHaste) * 1.28f));
                if (fSpeed != null) fSpeed.setFloat(this, number(fSpeed) * 1.10f);
                frenzyActive = true;
            }
            frenzyTimer = Math.max(frenzyTimer, seconds);
        } catch (Exception ignored) { }
    }

    private void stopFrenzy() {
        try {
            if (!frenzyActive) return;
            if (fDamage != null) fDamage.setFloat(this, number(fDamage) / 1.32f);
            if (fWeaponHaste != null) fWeaponHaste.setFloat(this, Math.max(1f, number(fWeaponHaste) / 1.28f));
            if (fSpeed != null) fSpeed.setFloat(this, number(fSpeed) / 1.10f);
            frenzyActive = false;
            frenzyTimer = 0f;
            label("SURCHARGE TERMINÉE");
        } catch (Exception ignored) { }
    }

    private void spawnRelic() {
        float px = number(fPx), py = number(fPy);
        float a = randomV6.nextFloat() * (float) (Math.PI * 2.0);
        float d = 290f + randomV6.nextFloat() * 270f;
        float r = randomV6.nextFloat();
        int type = r < 0.38f ? RELIC_SHIELD : r < 0.73f ? RELIC_FRENZY : RELIC_XP;
        relics.add(new Relic(type,
                px + (float) Math.cos(a) * d,
                py + (float) Math.sin(a) * d,
                28f));
    }

    private void updateRelics(float dt) {
        float px = number(fPx), py = number(fPy);
        for (int i = relics.size() - 1; i >= 0; i--) {
            Relic relic = relics.get(i);
            relic.life -= dt;
            if (relic.life <= 0f) {
                relics.remove(i);
                continue;
            }
            float dx = relic.x - px, dy = relic.y - py;
            if (dx * dx + dy * dy < 52f * 52f) {
                collectRelic(relic.type);
                relics.remove(i);
            }
        }
    }

    private void collectRelic(int type) {
        try {
            if (type == RELIC_SHIELD) {
                if (fInvuln != null) fInvuln.setFloat(this, Math.max(number(fInvuln), 8f));
                label("BOUCLIER 8s");
                tone(ToneGenerator.TONE_PROP_ACK, 130);
            } else if (type == RELIC_FRENZY) {
                startFrenzy(10f);
                label("FRÉNÉSIE 10s");
                tone(ToneGenerator.TONE_CDMA_PIP, 130);
            } else {
                if (mGainXp != null) mGainXp.invoke(this, 22f + Math.min(45f, number(fElapsed) / 18f));
                if (fScore != null) fScore.setInt(this, integer(fScore) + 350);
                label("CRISTAL D'EXPÉRIENCE");
                tone(ToneGenerator.TONE_PROP_PROMPT, 140);
            }
            pulse(38);
        } catch (Exception ignored) { }
    }

    private void label(String text) {
        eventLabel = text;
        eventLabelLife = 2.2f;
        try { if (mShowBanner != null) mShowBanner.invoke(this, text); }
        catch (Exception ignored) { }
    }

    private void tone(int tone, int ms) {
        try { if (tones != null) tones.startTone(tone, ms); }
        catch (Exception ignored) { }
    }

    private void pulse(long ms) {
        try {
            if (vibrator == null || !vibrator.hasVibrator()) return;
            if (Build.VERSION.SDK_INT >= 26) vibrator.vibrate(VibrationEffect.createOneShot(ms, 80));
            else vibrator.vibrate(ms);
        } catch (Exception ignored) { }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (inMenu()) {
            drawMenuStats(canvas);
            return;
        }
        if (!bool(fDead) && !bool(fChoosing) && !bool(fPaused)) {
            drawRelics(canvas);
            drawV6Status(canvas);
        }
    }

    private void drawRelics(Canvas canvas) {
        float camX = number(fCamX), camY = number(fCamY);
        float anchorX = getWidth() * 0.5f, anchorY = getHeight() * 0.56f;
        float t = number(fElapsed);
        for (Relic relic : relics) {
            float sx = relic.x - camX + anchorX;
            float sy = relic.y - camY + anchorY;
            float pulse = 1f + (float) Math.sin(t * 6f + relic.x * 0.01f) * 0.10f;
            int color = relic.type == RELIC_SHIELD ? Color.rgb(80, 170, 255)
                    : relic.type == RELIC_FRENZY ? Color.rgb(255, 95, 70)
                    : Color.rgb(215, 90, 255);
            paintV6.setColor(Color.argb(46, Color.red(color), Color.green(color), Color.blue(color)));
            canvas.drawCircle(sx, sy, 46f * pulse, paintV6);
            strokeV6.setColor(color);
            strokeV6.setStrokeWidth(4f);
            canvas.drawCircle(sx, sy, 27f * pulse, strokeV6);
            paintV6.setColor(Color.WHITE);
            paintV6.setTextAlign(Paint.Align.CENTER);
            paintV6.setFakeBoldText(true);
            paintV6.setTextSize(15f);
            String icon = relic.type == RELIC_SHIELD ? "S" : relic.type == RELIC_FRENZY ? "F" : "XP";
            canvas.drawText(icon, sx, sy + 5f, paintV6);
            paintV6.setFakeBoldText(false);
        }
    }

    private void drawV6Status(Canvas canvas) {
        if (eventLabelLife > 0f) {
            float scale = Math.min(1.22f, Math.max(1f, Math.min(getWidth() / 420f, getHeight() / 820f)));
            float alpha = Math.min(1f, eventLabelLife * 1.4f);
            paintV6.setTextAlign(Paint.Align.CENTER);
            paintV6.setFakeBoldText(true);
            paintV6.setTextSize(10f * scale);
            paintV6.setColor(Color.argb((int) (220f * alpha), 235, 245, 248));
            canvas.drawText(eventLabel, getWidth() * 0.5f, 192f * scale, paintV6);
            paintV6.setFakeBoldText(false);
        }
        if (frenzyActive) {
            paintV6.setTextAlign(Paint.Align.LEFT);
            paintV6.setTextSize(13f);
            paintV6.setColor(Color.rgb(255, 140, 95));
            canvas.drawText("SURCHARGE " + Math.max(1, Math.round(frenzyTimer)) + "s", 20f, getHeight() - 20f, paintV6);
        }
    }

    private void drawMenuStats(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        float scale = Math.max(1f, Math.min(w / 420f, h / 820f));
        paintV6.setTextAlign(Paint.Align.CENTER);
        paintV6.setTextSize(14f * scale);
        paintV6.setColor(Color.rgb(190, 208, 212));
        canvas.drawText("Parties " + runs + "   •   Éliminations " + totalKills + "   •   Record temps " + formatTime(bestTime),
                w * 0.5f, h * 0.43f, paintV6);
    }

    private String formatTime(float seconds) {
        int s = Math.max(0, (int) seconds);
        return String.format(java.util.Locale.US, "%02d:%02d", s / 60, s % 60);
    }

    private static final class Relic {
        final int type;
        final float x, y;
        float life;
        Relic(int type, float x, float y, float life) {
            this.type = type;
            this.x = x;
            this.y = y;
            this.life = life;
        }
    }
}
