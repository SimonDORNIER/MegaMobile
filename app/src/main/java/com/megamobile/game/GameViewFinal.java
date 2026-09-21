package com.megamobile.game;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
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
    private float extraSimulationDebt;
    private long lastExtraMs;
    private boolean deathStored;
    private final int[] speedModes = {1, 2, 4, 10, 100};
    private int speedModeIndex = 0;

    private Field fPaused, fDead, fChoosing, fPx, fPy, fCamX, fCamY, fElapsed;
    private Field fEnemies, fGems, fKills, fScore, fHp, fMaxHp, fDamage, fFireInterval, fWeaponHaste, fSpeed, fCrit;
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
            fWeaponHaste = baseField("weaponHaste");
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
        if (multiplier <= 1 || realDt <= 0f || bool(fPaused) || bool(fDead) || bool(fChoosing)) {
            extraSimulationDebt = 0f;
            return;
        }
        if (mUpdate == null || mUpdateVisuals == null) return;
        // Le mode ×100 utilisait auparavant de très grands pas de temps : les
        // collisions étaient sautées et la partie pouvait se figer. On borne la
        // dette et on la découpe en pas assez petits pour rester déterministe.
        extraSimulationDebt = Math.min(1.8f, extraSimulationDebt + realDt * (multiplier - 1f));
        int steps = Math.min(24, Math.max(1, (int) Math.ceil(extraSimulationDebt / 0.075f)));
        float step = Math.min(0.075f, extraSimulationDebt / steps);
        try {
            for (int i = 0; i < steps; i++) {
                if (bool(fDead) || bool(fPaused) || bool(fChoosing)) break;
                mUpdate.invoke(this, step);
                mUpdateVisuals.invoke(this, step);
                extraSimulationDebt = Math.max(0f, extraSimulationDebt - step);
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
                int count = destroyAllEnemies();
                banner("NUKE ! " + count + " ennemis");
            } else if (type == PICKUP_ULTRA) {
                if (fDamage != null) fDamage.setFloat(this, number(fDamage) * 1.22f);
                if (fWeaponHaste != null) fWeaponHaste.setFloat(this, Math.min(8f, number(fWeaponHaste) * 1.14f));
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
        if (!inMenu && !bool(fDead) && !bool(fChoosing) && !bool(fPaused)) drawPickups(canvas);
        if (!inMenu && !bool(fDead) && !bool(fChoosing) && !bool(fPaused)) {
            drawSpeedControl(canvas);
        }
        if (inMenu) drawMainMenu(canvas);
    }

    private void drawPickups(Canvas canvas) {
        float t = number(fElapsed);
        float zoom = getCameraZoom();
        for (SpecialPickup p : pickups) {
            float sx = worldToScreenX(p.x);
            float sy = worldToScreenY(p.y);
            float pulse = 1f + (float) Math.sin(t * 5.5f + p.x * 0.01f) * 0.08f;
            int color = p.type == PICKUP_MAGNET ? Color.rgb(80, 210, 255)
                    : p.type == PICKUP_NUKE ? Color.rgb(255, 105, 65)
                    : p.type == PICKUP_ULTRA ? Color.rgb(245, 90, 255)
                    : Color.rgb(95, 235, 130);
            ui.setColor(Color.argb(50, Color.red(color), Color.green(color), Color.blue(color)));
            canvas.drawCircle(sx, sy, 42f * pulse * zoom, ui);
            stroke.setColor(color); stroke.setStrokeWidth(4f);
            canvas.drawCircle(sx, sy, 25f * pulse * zoom, stroke);
            ui.setColor(color); ui.setTextAlign(Paint.Align.CENTER); ui.setFakeBoldText(true); ui.setTextSize(17f);
            canvas.drawText(p.type == PICKUP_MAGNET ? "M" : p.type == PICKUP_NUKE ? "N" : p.type == PICKUP_ULTRA ? "U" : "+", sx, sy + 6f, ui);
            ui.setFakeBoldText(false);
        }
    }

    private void drawBestScore(Canvas canvas) {
        float scale = uiScale();
        ui.setTextAlign(Paint.Align.RIGHT);
        ui.setTextSize(12f * scale);
        ui.setColor(Color.argb(150, 215, 230, 232));
        canvas.drawText("RECORD " + bestScore, getWidth() - 18f * scale, 190f * scale, ui);
    }

    private void drawSpeedControl(Canvas canvas) {
        float scale = Math.min(1.22f, uiScale());
        float w = 58f * scale, h = 30f * scale;
        float pad = 12f * scale;
        float pauseSize = 44f * scale;
        float left = getWidth() - pad - pauseSize - 6f * scale - w;
        float top = 8f * scale;
        speedRect.set(left, top, left + w, top + h);
        ui.setColor(Color.argb(170, 24, 35, 40));
        canvas.drawRoundRect(speedRect, 9f * scale, 9f * scale, ui);
        stroke.setColor(Color.argb(150, 120, 225, 240));
        stroke.setStrokeWidth(2f * scale);
        canvas.drawRoundRect(speedRect, 9f * scale, 9f * scale, stroke);
        ui.setTextAlign(Paint.Align.CENTER);
        ui.setFakeBoldText(true);
        ui.setTextSize(12f * scale);
        ui.setColor(Color.WHITE);
        canvas.drawText("×" + speedModes[speedModeIndex], speedRect.centerX(), speedRect.centerY() + 4f * scale, ui);
        ui.setFakeBoldText(false);
    }

    private void drawMainMenu(Canvas c) {
        int w = getWidth(), h = getHeight();
        float scale = uiScale();
        ui.setShader(new LinearGradient(0f, 0f, 0f, h,
                Color.rgb(29, 13, 40), Color.rgb(7, 7, 13), Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, ui);
        ui.setShader(new RadialGradient(w * 0.5f, h * 0.30f, w * 0.55f,
                Color.argb(105, 123, 45, 146), Color.TRANSPARENT, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h * 0.7f, ui);
        ui.setShader(null);

        // Lune, remparts et sigil du royaume : identité visuelle sans image
        // externe, donc nette sur toutes les résolutions.
        ui.setColor(Color.argb(160, 224, 203, 155));
        c.drawCircle(w * 0.79f, h * 0.18f, 37f * scale, ui);
        ui.setColor(Color.rgb(8, 8, 14));
        c.drawCircle(w * 0.81f, h * 0.17f, 34f * scale, ui);
        stroke.setColor(Color.argb(115, 204, 123, 226));
        stroke.setStrokeWidth(2f * scale);
        c.drawCircle(w * 0.5f, h * 0.30f, 74f * scale, stroke);
        c.drawCircle(w * 0.5f, h * 0.30f, 57f * scale, stroke);
        c.save();
        c.rotate(45f, w * 0.5f, h * 0.30f);
        c.drawRect(w * 0.5f - 38f * scale, h * 0.30f - 38f * scale,
                w * 0.5f + 38f * scale, h * 0.30f + 38f * scale, stroke);
        c.restore();
        drawMenuShadowLord(c, w * 0.5f, h * 0.30f, scale);

        ui.setColor(Color.rgb(10, 9, 15));
        float castleTop = h * 0.81f;
        c.drawRect(0f, castleTop, w, h, ui);
        for (int i = 0; i < 7; i++) {
            float x = i * w / 6f - 18f * scale;
            float towerH = (i % 2 == 0 ? 74f : 48f) * scale;
            c.drawRect(x, castleTop - towerH, x + 42f * scale, castleTop, ui);
            for (int j = 0; j < 3; j++) {
                c.drawRect(x + j * 15f * scale, castleTop - towerH - 10f * scale,
                        x + (j * 15f + 9f) * scale, castleTop - towerH, ui);
            }
        }

        ui.setTextAlign(Paint.Align.CENTER);
        ui.setFakeBoldText(true);
        ui.setColor(Color.rgb(243, 222, 183));
        ui.setTextSize(Math.min(42f * scale, w * 0.11f));
        c.drawText("MEGAMOBILE", w / 2f, h * 0.115f, ui);
        ui.setTextSize(13f * scale);
        ui.setColor(Color.rgb(221, 112, 104));
        c.drawText("LE RÈGNE DES OMBRES", w / 2f, h * 0.15f, ui);

        ui.setFakeBoldText(false);
        ui.setColor(Color.rgb(213, 216, 224));
        ui.setTextSize(14f * scale);
        c.drawText("Tu es le boss. Les héros viennent te renverser.", w / 2f, h * 0.395f, ui);

        float margin = 30f * scale;
        float top = h * 0.51f;
        playRect.set(margin, top, w - margin, top + 88f * scale);
        ui.setShader(new LinearGradient(playRect.left, playRect.top, playRect.right, playRect.bottom,
                Color.rgb(118, 42, 132), Color.rgb(63, 23, 78), Shader.TileMode.CLAMP));
        c.drawRoundRect(playRect, 24f * scale, 24f * scale, ui);
        ui.setShader(null);
        stroke.setColor(Color.rgb(220, 145, 76)); stroke.setStrokeWidth(3f * scale);
        c.drawRoundRect(playRect, 24f * scale, 24f * scale, stroke);
        ui.setFakeBoldText(true); ui.setColor(Color.WHITE); ui.setTextSize(25f * scale);
        c.drawText("COMMENCER LA CONQUÊTE", w / 2f, playRect.centerY() + 9f * scale, ui);

        ui.setTextSize(11f * scale);
        ui.setColor(Color.rgb(194, 181, 205));
        c.drawText("PARTIE INFINIE   •   AUCUN ACHAT   •   HORS-LIGNE",
                w / 2f, playRect.bottom + 31f * scale, ui);
        ui.setTextSize(16f * scale); ui.setColor(Color.rgb(235, 225, 211));
        c.drawText("Record : " + bestScore, w / 2f, playRect.bottom + 63f * scale, ui);
        ui.setFakeBoldText(false); ui.setTextSize(12f * scale); ui.setColor(Color.rgb(151, 143, 164));
        c.drawText("V1.4 • CONQUÊTE", w / 2f, h - 36f * scale, ui);
        ui.setFakeBoldText(false);
    }

    private void drawMenuShadowLord(Canvas c, float cx, float cy, float scale) {
        ui.setColor(Color.argb(130, 0, 0, 0));
        c.drawOval(cx - 37f * scale, cy + 42f * scale, cx + 37f * scale, cy + 58f * scale, ui);
        Path cape = new Path();
        cape.moveTo(cx - 22f * scale, cy - 3f * scale);
        cape.lineTo(cx - 48f * scale, cy + 53f * scale);
        cape.lineTo(cx + 48f * scale, cy + 53f * scale);
        cape.lineTo(cx + 22f * scale, cy - 3f * scale);
        cape.close();
        ui.setColor(Color.rgb(64, 19, 78));
        c.drawPath(cape, ui);
        ui.setColor(Color.rgb(29, 27, 39));
        c.drawRoundRect(cx - 25f * scale, cy - 10f * scale, cx + 25f * scale,
                cy + 39f * scale, 13f * scale, 13f * scale, ui);
        ui.setColor(Color.rgb(16, 14, 23));
        c.drawCircle(cx, cy - 20f * scale, 27f * scale, ui);
        ui.setColor(Color.rgb(255, 61, 76));
        c.drawCircle(cx - 8f * scale, cy - 20f * scale, 3.5f * scale, ui);
        c.drawCircle(cx + 8f * scale, cy - 20f * scale, 3.5f * scale, ui);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeWidth(7f * scale);
        stroke.setColor(Color.rgb(215, 157, 76));
        c.drawLine(cx - 18f * scale, cy - 38f * scale, cx - 34f * scale, cy - 58f * scale, stroke);
        c.drawLine(cx + 18f * scale, cy - 38f * scale, cx + 34f * scale, cy - 58f * scale, stroke);
        stroke.setStrokeCap(Paint.Cap.BUTT);
    }

    private float uiScale() {
        return Math.max(1f, Math.min(getWidth() / 420f, getHeight() / 820f));
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

    @Override
    public boolean handleBackPressed() {
        if (inMenu) return false;
        return super.handleBackPressed();
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
