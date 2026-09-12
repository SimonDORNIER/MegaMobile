package com.megamobile.game;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.MotionEvent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Couche "jeu présentable" au-dessus du moteur : écran d'accueil, record local,
 * pickups spéciaux et petites finitions. Le gameplay principal reste dans GameView.
 */
public class GameViewFinal extends GameViewPro {
    private static final String PREFS = "MegaMobileProgress";
    private static final String PREF_BEST = "bestScore";
    private static final int PICKUP_MAGNET = 0;
    private static final int PICKUP_NUKE = 1;
    private static final int PICKUP_HEAL = 2;
    private static final int PICKUP_ULTRA = 3;

    private final Paint ui = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random random = new Random();
    private final ArrayList<SpecialPickup> pickups = new ArrayList<>();
    private final RectF playRect = new RectF();
    private final RectF speedRect = new RectF();
    private final SharedPreferences prefs;

    private boolean inMenu = true;
    private int bestScore;
    private float pickupTimer = 22f;
    private long lastExtraMs;
    private boolean deathStored;
    private final int[] speedModes = {1, 2, 4, 10, 100};
    private int speedModeIndex = 0;

    private Field fPaused, fDead, fChoosing, fPx, fPy, fCamX, fCamY, fElapsed;
    private Field fEnemies, fGems, fKills, fScore, fHp, fMaxHp, fDamage, fFireInterval, fSpeed, fCrit;
    private Method mShowBanner, mGainXp, mUpdate, mUpdateVisuals;
    private Class<?> gemClass;
    private Field gemX, gemY;

    public GameViewFinal(Context context) {
        super(context);
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        bestScore = prefs.getInt(PREF_BEST, 0);
        bindFinalReflection();
        setBasePaused(true);
        ui.setTypeface(android.graphics.Typeface.create("sans", android.graphics.Typeface.NORMAL));
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(3f);
        lastExtraMs = SystemClock.uptimeMillis();
    }

    private void bindFinalReflection() {
        try {
            fPaused = baseField("paused");
            fDead = baseField("dead");
            fChoosing = baseField("choosing");
            fPx = baseField("px");
            fPy = baseField("py");
            fCamX = baseField("camX");
            fCamY = baseField("camY");
            fElapsed = baseField("elapsed");
            fEnemies = baseField("enemies");
            fGems = baseField("gems");
            fKills = baseField("kills");
            fScore = baseField("score");
            fHp = baseField("hp");
            fMaxHp = baseField("maxHp");
            fDamage = baseField("damage");
            fFireInterval = baseField("fireInterval");
            fSpeed = baseField("speed");
            fCrit = baseField("crit");
            mShowBanner = GameView.class.getDeclaredMethod("showBanner", String.class);
            mShowBanner.setAccessible(true);
            mGainXp = GameView.class.getDeclaredMethod("gainXp", float.class);
            mGainXp.setAccessible(true);
            mUpdate = GameView.class.getDeclaredMethod("update", float.class);
            mUpdate.setAccessible(true);
            mUpdateVisuals = GameView.class.getDeclaredMethod("updateVisuals", float.class);
            mUpdateVisuals.setAccessible(true);
        } catch (Exception ignored) { }
    }

    private Field baseField(String name) throws NoSuchFieldException {
        Field f = GameView.class.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    private void setBasePaused(boolean value) {
        try { if (fPaused != null) fPaused.setBoolean(this, value); }
        catch (Exception ignored) { }
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

    @Override
    public void doFrame(long frameTimeNanos) {
        super.doFrame(frameTimeNanos);
        long now = SystemClock.uptimeMillis();
        float dt = Math.min(0.08f, Math.max(0f, (now - lastExtraMs) / 1000f));
        lastExtraMs = now;
        if (!inMenu) {
            updateFinalLayer(dt);
            runExtraSimulation(dt);
        }
    }

    private void runExtraSimulation(float realDt) {
        int multiplier = speedModes[speedModeIndex];
        if (multiplier <= 1 || realDt <= 0f || bool(fPaused) || bool(fDead) || bool(fChoosing)) return;
        if (mUpdate == null || mUpdateVisuals == null) return;
        float extra = realDt * (multiplier - 1f);
        int steps = Math.min(12, Math.max(1, (int) Math.ceil(extra / 0.04f)));
        float step = extra / steps;
        try {
            for (int i = 0; i < steps; i++) {
                if (bool(fDead) || bool(fPaused) || bool(fChoosing)) break;
                mUpdate.invoke(this, step);
                mUpdateVisuals.invoke(this, step);
            }
            invalidate();
        } catch (Exception ignored) { }
    }

    private void updateFinalLayer(float dt) {
        if (bool(fDead)) {
            if (!deathStored) {
                deathStored = true;
                int score = integer(fScore);
                if (score > bestScore) {
                    bestScore = score;
                    prefs.edit().putInt(PREF_BEST, bestScore).apply();
                }
            }
            return;
        }
        deathStored = false;
        if (bool(fPaused) || bool(fChoosing)) return;

        pickupTimer -= dt;
        if (pickupTimer <= 0f && pickups.size() < 3) {
            spawnPickup();
            pickupTimer = 32f + random.nextFloat() * 24f;
        }
        for (int i = pickups.size() - 1; i >= 0; i--) {
            SpecialPickup p = pickups.get(i);
            p.life -= dt;
            if (p.life <= 0f) {
                pickups.remove(i);
                continue;
            }
            float px = number(fPx), py = number(fPy);
            float dx = p.x - px, dy = p.y - py;
            if (dx * dx + dy * dy < 48f * 48f) {
                applyPickup(p.type);
                pickups.remove(i);
            }
        }
    }

    private void spawnPickup() {
        float px = number(fPx), py = number(fPy);
        float a = random.nextFloat() * (float) (Math.PI * 2.0);
        float d = 250f + random.nextFloat() * 300f;
        int type;
        float r = random.nextFloat();
        if (r < 0.025f) type = PICKUP_ULTRA;
        else if (r < 0.43f) type = PICKUP_MAGNET;
        else if (r < 0.76f) type = PICKUP_NUKE;
        else type = PICKUP_HEAL;
        pickups.add(new SpecialPickup(type,
                px + (float) Math.cos(a) * d,
                py + (float) Math.sin(a) * d,
                30f));
    }

    @SuppressWarnings("unchecked")
    private void applyPickup(int type) {
        try {
            if (type == PICKUP_MAGNET) {
                List<Object> gems = fGems == null ? null : (List<Object>) fGems.get(this);
                float px = number(fPx), py = number(fPy);
                int count = 0;
                if (gems != null) for (Object gem : gems) {
                    bindGem(gem);
                    gemX.setFloat(gem, px);
                    gemY.setFloat(gem, py);
                    count++;
                }
                banner("AIMANT ! " + count + " gemmes");
            } else if (type == PICKUP_NUKE) {
                List<Object> enemies = fEnemies == null ? null : (List<Object>) fEnemies.get(this);
                int count = enemies == null ? 0 : enemies.size();
                if (enemies != null) enemies.clear();
                if (fKills != null) fKills.setInt(this, integer(fKills) + count);
                if (fScore != null) fScore.setInt(this, integer(fScore) + count * 12);
                if (mGainXp != null && count > 0) mGainXp.invoke(this, Math.min(55f, count * 0.55f));
                banner("NUKE ! " + count + " ennemis");
            } else if (type == PICKUP_ULTRA) {
                if (fDamage != null) fDamage.setFloat(this, number(fDamage) * 1.22f);
                if (fFireInterval != null) fFireInterval.setFloat(this, Math.max(0.08f, number(fFireInterval) / 1.14f));
                if (fSpeed != null) fSpeed.setFloat(this, number(fSpeed) * 1.08f);
                if (fCrit != null) fCrit.setFloat(this, Math.min(0.85f, number(fCrit) + 0.08f));
                float oldMax = number(fMaxHp);
                if (fMaxHp != null) fMaxHp.setFloat(this, oldMax + 18f);
                if (fHp != null) fHp.setFloat(this, Math.min(oldMax + 18f, number(fHp) + 28f));
                if (fScore != null) fScore.setInt(this, integer(fScore) + 750);
                banner("ULTRA CORE !");
            } else {
                float maxHp = number(fMaxHp);
                float hp = number(fHp);
                if (fHp != null) fHp.setFloat(this, Math.min(maxHp, hp + Math.max(30f, maxHp * 0.35f)));
                banner("SOIN D'URGENCE");
            }
        } catch (Exception ignored) { }
    }

    private void bindGem(Object gem) throws Exception {
        if (gem == null) return;
        Class<?> c = gem.getClass();
        if (c == gemClass) return;
        gemClass = c;
        gemX = c.getDeclaredField("x"); gemX.setAccessible(true);
        gemY = c.getDeclaredField("y"); gemY.setAccessible(true);
    }

    private void banner(String value) {
        try { if (mShowBanner != null) mShowBanner.invoke(this, value); }
        catch (Exception ignored) { }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!inMenu && !bool(fDead)) drawPickups(canvas);
        if (!inMenu && !bool(fDead) && !bool(fChoosing)) {
            drawBestScore(canvas);
            drawSpeedControl(canvas);
        }
        if (inMenu) drawMainMenu(canvas);
    }

    private void drawPickups(Canvas canvas) {
        float camX = number(fCamX), camY = number(fCamY);
        float anchorX = getWidth() * 0.5f, anchorY = getHeight() * 0.56f;
        float t = number(fElapsed);
        for (SpecialPickup p : pickups) {
            float sx = p.x - camX + anchorX;
            float sy = p.y - camY + anchorY;
            float pulse = 1f + (float) Math.sin(t * 5.5f + p.x * 0.01f) * 0.08f;
            int color = p.type == PICKUP_MAGNET ? Color.rgb(80, 210, 255)
                    : p.type == PICKUP_NUKE ? Color.rgb(255, 105, 65)
                    : p.type == PICKUP_ULTRA ? Color.rgb(245, 90, 255)
                    : Color.rgb(95, 235, 130);
            ui.setColor(Color.argb(50, Color.red(color), Color.green(color), Color.blue(color)));
            canvas.drawCircle(sx, sy, 42f * pulse, ui);
            stroke.setColor(color); stroke.setStrokeWidth(4f);
            canvas.drawCircle(sx, sy, 25f * pulse, stroke);
            ui.setColor(color); ui.setTextAlign(Paint.Align.CENTER); ui.setFakeBoldText(true); ui.setTextSize(17f);
            canvas.drawText(p.type == PICKUP_MAGNET ? "M" : p.type == PICKUP_NUKE ? "N" : p.type == PICKUP_ULTRA ? "U" : "+", sx, sy + 6f, ui);
            ui.setFakeBoldText(false);
        }
    }

    private void drawBestScore(Canvas canvas) {
        ui.setTextAlign(Paint.Align.RIGHT);
        ui.setTextSize(12f);
        ui.setColor(Color.argb(150, 215, 230, 232));
        canvas.drawText("RECORD " + bestScore, getWidth() - 20f, 185f, ui);
    }

    private void drawSpeedControl(Canvas canvas) {
        float w = 84f, h = 36f;
        float left = getWidth() - w - 18f;
        float top = 205f;
        speedRect.set(left, top, left + w, top + h);
        ui.setColor(Color.argb(170, 24, 35, 40));
        canvas.drawRoundRect(speedRect, 12f, 12f, ui);
        stroke.setColor(Color.argb(150, 120, 225, 240));
        stroke.setStrokeWidth(2f);
        canvas.drawRoundRect(speedRect, 12f, 12f, stroke);
        ui.setTextAlign(Paint.Align.CENTER);
        ui.setFakeBoldText(true);
        ui.setTextSize(14f);
        ui.setColor(Color.WHITE);
        canvas.drawText("×" + speedModes[speedModeIndex], speedRect.centerX(), speedRect.centerY() + 5f, ui);
        ui.setFakeBoldText(false);
    }

    private void drawMainMenu(Canvas c) {
        int w = getWidth(), h = getHeight();
        ui.setColor(Color.rgb(8, 14, 18));
        c.drawRect(0, 0, w, h, ui);
        ui.setColor(Color.argb(36, 60, 210, 230));
        c.drawCircle(w * 0.18f, h * 0.20f, w * 0.42f, ui);
        ui.setColor(Color.argb(26, 220, 90, 130));
        c.drawCircle(w * 0.92f, h * 0.48f, w * 0.55f, ui);

        ui.setTextAlign(Paint.Align.CENTER);
        ui.setFakeBoldText(true);
        ui.setColor(Color.WHITE);
        ui.setTextSize(Math.min(48f, w * 0.115f));
        c.drawText("MEGAMOBILE", w / 2f, h * 0.23f, ui);
        ui.setTextSize(16f);
        ui.setColor(Color.rgb(130, 225, 235));
        c.drawText("SURVIS • ÉVOLUE • VA TOUJOURS PLUS LOIN", w / 2f, h * 0.27f, ui);

        ui.setFakeBoldText(false);
        ui.setColor(Color.rgb(205, 217, 220));
        ui.setTextSize(15f);
        c.drawText("Portrait • commandes tactiles • mises à jour live", w / 2f, h * 0.34f, ui);
        c.drawText("Le joystick apparaît là où tu poses le pouce.", w / 2f, h * 0.38f, ui);

        float margin = 42f;
        float top = h * 0.49f;
        playRect.set(margin, top, w - margin, top + 78f);
        ui.setColor(Color.rgb(40, 166, 116));
        c.drawRoundRect(playRect, 24f, 24f, ui);
        stroke.setColor(Color.rgb(125, 245, 195)); stroke.setStrokeWidth(3f);
        c.drawRoundRect(playRect, 24f, 24f, stroke);
        ui.setFakeBoldText(true); ui.setColor(Color.WHITE); ui.setTextSize(25f);
        c.drawText("JOUER", w / 2f, playRect.centerY() + 9f, ui);

        ui.setTextSize(17f); ui.setColor(Color.rgb(225, 230, 232));
        c.drawText("Record : " + bestScore, w / 2f, top + 132f, ui);
        ui.setFakeBoldText(false); ui.setTextSize(13f); ui.setColor(Color.rgb(150, 168, 172));
        c.drawText("V0.5 • contenu et équilibrage récupérés depuis GitHub", w / 2f, h - 64f, ui);
        c.drawText("Les mises à jour du moteur sont téléchargées automatiquement.", w / 2f, h - 40f, ui);
        ui.setFakeBoldText(false);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (inMenu) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                float x = event.getX(), y = event.getY();
                if (playRect.contains(x, y)) {
                    inMenu = false;
                    setBasePaused(false);
                    lastExtraMs = SystemClock.uptimeMillis();
                    banner("SURVIS !");
                    invalidate();
                }
            }
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN && speedRect.contains(event.getX(), event.getY())) {
            speedModeIndex = (speedModeIndex + 1) % speedModes.length;
            banner("VITESSE ×" + speedModes[speedModeIndex]);
            invalidate();
            return true;
        }
        return super.onTouchEvent(event);
    }

    private static final class SpecialPickup {
        final int type;
        final float x, y;
        float life;
        SpecialPickup(int type, float x, float y, float life) {
            this.type = type; this.x = x; this.y = y; this.life = life;
        }
    }
}
