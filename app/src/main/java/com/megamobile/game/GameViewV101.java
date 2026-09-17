package com.megamobile.game;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.SystemClock;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * MegaMobile V1.0.1 reliability layer.
 *
 * Keeps very long runs alive by:
 * - flattening the extreme late-game XP requirement;
 * - maintaining a visible minimum horde without uncontrolled bursts;
 * - bringing lost enemies back near the player instead of deleting them;
 * - vacuuming excessive XP gems toward the player while preserving their value;
 * - limiting purely visual object buildup that can freeze long sessions.
 */
public class GameViewV101 extends GameViewV10 {
    private final Paint patchPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private Field fElapsed, fLevel, fKills, fDifficulty, fEnemies, fGems;
    private Field fParticles, fTexts, fArcs, fShots;
    private Field fPaused, fDead, fChoosing, fPx, fPy, fNextXp, fInMenu;
    private Method mSpawnEnemy, mGainXp, mShowBanner;

    private Class<?> enemyClass;
    private Field eX, eY;
    private Class<?> gemClass;
    private Field gX, gY;

    private long lastMs;
    private int previousKills;
    private float noKillTimer;
    private float hordeTimer;
    private float recoveryTimer;
    private float cleanupTimer;
    private float versionLabelLife = 5.5f;

    public GameViewV101(Context context) {
        super(context);
        bind();
        lastMs = SystemClock.uptimeMillis();
        previousKills = integer(fKills);
    }

    private void bind() {
        try {
            fElapsed = baseField("elapsed");
            fLevel = baseField("level");
            fKills = baseField("kills");
            fDifficulty = baseField("difficulty");
            fEnemies = baseField("enemies");
            fGems = baseField("gems");
            fParticles = baseField("particles");
            fTexts = baseField("texts");
            fArcs = baseField("arcs");
            fShots = baseField("shots");
            fPaused = baseField("paused");
            fDead = baseField("dead");
            fChoosing = baseField("choosing");
            fPx = baseField("px");
            fPy = baseField("py");
            fNextXp = baseField("nextXp");

            fInMenu = GameViewFinal.class.getDeclaredField("inMenu");
            fInMenu.setAccessible(true);

            mSpawnEnemy = GameView.class.getDeclaredMethod("spawnEnemy", boolean.class);
            mSpawnEnemy.setAccessible(true);
            mGainXp = GameView.class.getDeclaredMethod("gainXp", float.class);
            mGainXp.setAccessible(true);
            mShowBanner = GameView.class.getDeclaredMethod("showBanner", String.class);
            mShowBanner.setAccessible(true);
        } catch (Exception ignored) { }
    }

    private Field baseField(String name) throws NoSuchFieldException {
        Field f = GameView.class.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        super.doFrame(frameTimeNanos);
        long now = SystemClock.uptimeMillis();
        float dt = Math.min(0.08f, Math.max(0f, (now - lastMs) / 1000f));
        lastMs = now;
        updatePatch(dt);
    }

    private void updatePatch(float dt) {
        if (versionLabelLife > 0f) versionLabelLife -= dt;
        if (inMenu() || bool(fPaused) || bool(fDead) || bool(fChoosing)) {
            previousKills = integer(fKills);
            return;
        }

        int kills = integer(fKills);
        if (kills > previousKills) {
            noKillTimer = 0f;
            previousKills = kills;
        } else {
            noKillTimer += dt;
        }

        tuneLateGameXp();
        reinforceDifficultyFloor();

        hordeTimer -= dt;
        if (hordeTimer <= 0f) {
            maintainHorde();
            hordeTimer = noKillTimer > 5f ? 0.28f : 0.55f;
        }

        recoveryTimer -= dt;
        if (recoveryTimer <= 0f) {
            recoverLostEnemies();
            recoveryTimer = noKillTimer > 6f ? 0.55f : 1.15f;
        }

        cleanupTimer -= dt;
        if (cleanupTimer <= 0f) {
            vacuumGemOverflow();
            trimVisualOverflow();
            cleanupTimer = 0.85f;
        }

        if (noKillTimer > 18f) {
            gainXp(Math.min(18f, 4f + integer(fLevel) * 0.025f));
            spawn(false, 6);
            recoverLostEnemies();
            noKillTimer = 8f;
            banner("PRESSION RELANCÉE — LA PARTIE CONTINUE");
        }
    }

    /** Prevents nextXp from exploding into tens of thousands at very high levels. */
    private void tuneLateGameXp() {
        int level = integer(fLevel);
        if (level < 35 || fNextXp == null) return;
        float current = number(fNextXp);
        float cap = 260f + level * 4.2f + (float) Math.sqrt(level) * 38f;
        if (current > cap) setFloat(fNextXp, cap);
    }

    /** Adds a small level component so a very strong build never permanently outruns the horde. */
    private void reinforceDifficultyFloor() {
        int level = integer(fLevel);
        float elapsed = number(fElapsed);
        float floor = 1f + elapsed / 70f + level * 0.035f;
        if (number(fDifficulty) < floor) setFloat(fDifficulty, floor);
    }

    private void maintainHorde() {
        List<Object> enemies = list(fEnemies);
        int count = enemies == null ? 0 : enemies.size();
        int level = integer(fLevel);
        float elapsed = number(fElapsed);

        int target = Math.min(112, 12 + level / 2 + (int) (elapsed / 28f));
        if (noKillTimer > 5f) target = Math.min(124, target + 14);
        if (count >= target) return;

        int missing = target - count;
        int batch = Math.min(noKillTimer > 5f ? 9 : 5, missing);
        spawn(false, batch);
    }

    /**
     * Repositions only enemies that are genuinely lost far outside the useful play area.
     * Their HP/type/reward remain untouched, so this fixes visibility without destroying progression.
     */
    private void recoverLostEnemies() {
        List<Object> enemies = snapshot(fEnemies);
        if (enemies.isEmpty()) return;

        float px = number(fPx), py = number(fPy);
        float viewport = Math.max(getWidth(), getHeight());
        float lostRadius = Math.max(1250f, viewport * 1.45f);
        float returnRadius = Math.max(560f, viewport * 0.64f + 120f);
        float lostSq = lostRadius * lostRadius;
        int moved = 0;
        float phase = number(fElapsed) * 0.37f;

        for (int i = 0; i < enemies.size() && moved < 7; i++) {
            Object enemy = enemies.get(i);
            try {
                bindEnemy(enemy);
                float ex = eX.getFloat(enemy), ey = eY.getFloat(enemy);
                float dx = ex - px, dy = ey - py;
                if (dx * dx + dy * dy <= lostSq) continue;

                float angle = phase + moved * 0.91f + i * 0.17f;
                float radius = returnRadius + (moved % 3) * 55f;
                eX.setFloat(enemy, px + (float) Math.cos(angle) * radius);
                eY.setFloat(enemy, py + (float) Math.sin(angle) * radius);
                moved++;
            } catch (Exception ignored) { }
        }
    }

    /** Converts a potential late-game gem leak into normal collection instead of deleting XP. */
    private void vacuumGemOverflow() {
        List<Object> gems = list(fGems);
        if (gems == null || gems.size() <= 220) return;

        float px = number(fPx), py = number(fPy);
        int move = Math.min(48, gems.size() - 180);
        for (int i = 0; i < move; i++) {
            Object gem = gems.get(i);
            try {
                bindGem(gem);
                float a = (i % 12) * ((float) Math.PI * 2f / 12f);
                float r = 4f + (i / 12) * 4f;
                gX.setFloat(gem, px + (float) Math.cos(a) * r);
                gY.setFloat(gem, py + (float) Math.sin(a) * r);
            } catch (Exception ignored) { }
        }
    }

    /** Removes old visual-only clutter first; projectile trimming is a last-resort safety valve. */
    private void trimVisualOverflow() {
        trim(fParticles, 240);
        trim(fTexts, 90);
        trim(fArcs, 72);
        trim(fShots, 340);
    }

    private void trim(Field field, int max) {
        List<Object> values = list(field);
        if (values == null || values.size() <= max) return;
        int remove = values.size() - max;
        for (int i = 0; i < remove && !values.isEmpty(); i++) values.remove(0);
    }

    private void spawn(boolean boss, int count) {
        if (mSpawnEnemy == null) return;
        try {
            for (int i = 0; i < count; i++) mSpawnEnemy.invoke(this, boss);
        } catch (Exception ignored) { }
    }

    private void gainXp(float amount) {
        try { if (mGainXp != null) mGainXp.invoke(this, amount); }
        catch (Exception ignored) { }
    }

    private void banner(String text) {
        try { if (mShowBanner != null) mShowBanner.invoke(this, text); }
        catch (Exception ignored) { }
    }

    private void bindEnemy(Object enemy) throws Exception {
        if (enemy == null) return;
        Class<?> c = enemy.getClass();
        if (c == enemyClass) return;
        enemyClass = c;
        eX = c.getDeclaredField("x");
        eY = c.getDeclaredField("y");
        eX.setAccessible(true);
        eY.setAccessible(true);
    }

    private void bindGem(Object gem) throws Exception {
        if (gem == null) return;
        Class<?> c = gem.getClass();
        if (c == gemClass) return;
        gemClass = c;
        gX = c.getDeclaredField("x");
        gY = c.getDeclaredField("y");
        gX.setAccessible(true);
        gY.setAccessible(true);
    }

    @SuppressWarnings("unchecked")
    private List<Object> list(Field field) {
        try { return field == null ? null : (List<Object>) field.get(this); }
        catch (Exception ignored) { return null; }
    }

    private List<Object> snapshot(Field field) {
        List<Object> source = list(field);
        return source == null ? new ArrayList<>() : new ArrayList<>(source);
    }

    private boolean inMenu() {
        try { return fInMenu != null && fInMenu.getBoolean(this); }
        catch (Exception ignored) { return false; }
    }

    private boolean bool(Field field) {
        try { return field != null && field.getBoolean(this); }
        catch (Exception ignored) { return false; }
    }

    private int integer(Field field) {
        try { return field == null ? 0 : field.getInt(this); }
        catch (Exception ignored) { return 0; }
    }

    private float number(Field field) {
        try { return field == null ? 0f : field.getFloat(this); }
        catch (Exception ignored) { return 0f; }
    }

    private void setFloat(Field field, float value) {
        try { if (field != null) field.setFloat(this, value); }
        catch (Exception ignored) { }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (versionLabelLife <= 0f || inMenu()) return;
        patchPaint.setTextAlign(Paint.Align.LEFT);
        patchPaint.setTextSize(11f);
        patchPaint.setColor(Color.argb((int) (180f * Math.min(1f, versionLabelLife)), 178, 220, 225));
        canvas.drawText("V1.0.1 • ENDLESS STABILITY", 22f, getHeight() - 18f, patchPaint);
    }
}
