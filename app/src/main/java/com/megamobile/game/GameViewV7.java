package com.megamobile.game;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * V0.7 gameplay/content director.
 *
 * Adds elite variants, boss phases, kill-combo rewards, act transitions,
 * meteor strikes, capture beacons and long-run pressure without replacing the
 * proven base survivor engine.
 */
public class GameViewV7 extends GameViewV6 {
    private static final int ELITE_BERSERKER = 0;
    private static final int ELITE_COLOSSUS = 1;
    private static final int ELITE_PHANTOM = 2;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint s = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random rng = new Random();

    private Field fElapsed, fKills, fScore, fLevel, fHp, fMaxHp, fInvuln;
    private Field fPaused, fDead, fChoosing, fEnemies, fPx, fPy, fCamX, fCamY;
    private Field fInMenu;
    private Method mSpawnEnemy, mShowBanner, mGainXp, mDamageEnemy;

    private Class<?> enemyClass;
    private Field eType, eX, eY, eR, eHp, eMaxHp, eSpeed, eDamage;

    private final IdentityHashMap<Object, EliteInfo> elites = new IdentityHashMap<>();
    private final IdentityHashMap<Object, Boolean> bossPhase2 = new IdentityHashMap<>();
    private final ArrayList<Meteor> meteors = new ArrayList<>();
    private Beacon beacon;

    private long lastV7Ms;
    private float eliteTimer = 18f;
    private float pressureTimer = 2.5f;
    private float meteorTimer = 42f;
    private float beaconTimer = 66f;
    private int currentAct = 1;
    private int previousKills;
    private int combo;
    private int comboTier;
    private float comboTimer;
    private String directorLabel = "";
    private float directorLabelLife;

    public GameViewV7(Context context) {
        super(context);
        s.setStyle(Paint.Style.STROKE);
        s.setStrokeWidth(3f);
        bindV7();
        lastV7Ms = SystemClock.uptimeMillis();
        previousKills = integer(fKills);
    }

    private void bindV7() {
        try {
            fElapsed = baseField("elapsed");
            fKills = baseField("kills");
            fScore = baseField("score");
            fLevel = baseField("level");
            fHp = baseField("hp");
            fMaxHp = baseField("maxHp");
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
            for (Method method : GameView.class.getDeclaredMethods()) {
                if ("damageEnemy".equals(method.getName())) {
                    mDamageEnemy = method;
                    mDamageEnemy.setAccessible(true);
                    break;
                }
            }
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
        float dt = Math.min(0.08f, Math.max(0f, (now - lastV7Ms) / 1000f));
        lastV7Ms = now;
        updateV7(dt);
    }

    private void updateV7(float dt) {
        if (inMenu()) {
            previousKills = integer(fKills);
            return;
        }
        if (directorLabelLife > 0f) directorLabelLife -= dt;
        if (bool(fDead) || bool(fPaused) || bool(fChoosing)) return;

        float elapsed = number(fElapsed);
        int act = 1 + (int) (elapsed / 120f);
        if (act > currentAct) {
            currentAct = act;
            actTransition();
        }

        updateCombo(dt);
        updateBossPhases();
        updateElites(dt);
        updateMeteors(dt);
        updateBeacon(dt);

        eliteTimer -= dt;
        if (eliteTimer <= 0f) {
            promoteEliteWave();
            eliteTimer = Math.max(15f, 30f - currentAct * 1.7f) + rng.nextFloat() * 9f;
        }

        pressureTimer -= dt;
        if (pressureTimer <= 0f) {
            applyPressure();
            pressureTimer = Math.max(1.4f, 4.2f - currentAct * 0.28f);
        }

        meteorTimer -= dt;
        if (meteorTimer <= 0f) {
            startMeteorStrike();
            meteorTimer = Math.max(42f, 72f - currentAct * 3f) + rng.nextFloat() * 22f;
        }

        beaconTimer -= dt;
        if (beaconTimer <= 0f && beacon == null) {
            spawnBeacon();
            beaconTimer = 82f + rng.nextFloat() * 35f;
        }
    }

    private void actTransition() {
        label("ACTE " + currentAct + " — LA HORDE ÉVOLUE");
        try {
            int bosses = currentAct >= 4 ? 2 : 1;
            for (int i = 0; i < bosses; i++) mSpawnEnemy.invoke(this, true);
            for (int i = 0; i < 4 + currentAct * 2; i++) mSpawnEnemy.invoke(this, false);
            if (mGainXp != null) mGainXp.invoke(this, 6f + currentAct * 3f);
            if (fScore != null) fScore.setInt(this, integer(fScore) + 350 * currentAct);
        } catch (Exception ignored) { }
    }

    private void updateCombo(float dt) {
        int kills = integer(fKills);
        int delta = Math.max(0, kills - previousKills);
        previousKills = kills;
        if (delta > 0) {
            combo += delta;
            comboTimer = 3.2f;
            int newTier = combo >= 100 ? 4 : combo >= 50 ? 3 : combo >= 25 ? 2 : combo >= 10 ? 1 : 0;
            if (newTier > comboTier) {
                comboTier = newTier;
                comboReward(newTier);
            }
        } else if (combo > 0) {
            comboTimer -= dt;
            if (comboTimer <= 0f) {
                combo = 0;
                comboTier = 0;
            }
        }
    }

    private void comboReward(int tier) {
        try {
            int bonus = tier == 4 ? 1500 : tier == 3 ? 700 : tier == 2 ? 300 : 100;
            if (fScore != null) fScore.setInt(this, integer(fScore) + bonus);
            if (mGainXp != null) mGainXp.invoke(this, tier * 3.5f);
            if (tier >= 3 && fInvuln != null) fInvuln.setFloat(this, Math.max(number(fInvuln), 1.2f));
            label(tier == 4 ? "MASSACRE ×100" : tier == 3 ? "CARNAGE ×50" : tier == 2 ? "RAMPAGE ×25" : "COMBO ×10");
        } catch (Exception ignored) { }
    }

    @SuppressWarnings("unchecked")
    private List<Object> enemies() {
        try { return fEnemies == null ? null : (List<Object>) fEnemies.get(this); }
        catch (Exception ignored) { return null; }
    }

    private void bindEnemy(Object enemy) throws Exception {
        if (enemy == null) return;
        Class<?> c = enemy.getClass();
        if (c == enemyClass) return;
        enemyClass = c;
        eType = field(c, "type");
        eX = field(c, "x");
        eY = field(c, "y");
        eR = field(c, "r");
        eHp = field(c, "hp");
        eMaxHp = field(c, "maxHp");
        eSpeed = field(c, "speed");
        eDamage = field(c, "damage");
    }

    private Field field(Class<?> c, String name) throws NoSuchFieldException {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    private void promoteEliteWave() {
        List<Object> list = enemies();
        if (list == null || list.isEmpty()) return;
        int wanted = Math.min(1 + currentAct / 2, 4);
        int promoted = 0;
        for (int attempt = 0; attempt < 30 && promoted < wanted; attempt++) {
            Object enemy = list.get(rng.nextInt(list.size()));
            if (elites.containsKey(enemy) || bossPhase2.containsKey(enemy)) continue;
            try {
                bindEnemy(enemy);
                if (eType.getInt(enemy) == 4) continue;
                int kind = rng.nextInt(3);
                float hp = eHp.getFloat(enemy);
                float maxHp = eMaxHp.getFloat(enemy);
                float speed = eSpeed.getFloat(enemy);
                float damage = eDamage.getFloat(enemy);
                float radius = eR.getFloat(enemy);
                if (kind == ELITE_BERSERKER) {
                    eHp.setFloat(enemy, hp * 3.2f);
                    eMaxHp.setFloat(enemy, maxHp * 3.2f);
                    eSpeed.setFloat(enemy, speed * 1.48f);
                    eDamage.setFloat(enemy, damage * 1.45f);
                    eR.setFloat(enemy, radius * 1.12f);
                } else if (kind == ELITE_COLOSSUS) {
                    eHp.setFloat(enemy, hp * 5.0f);
                    eMaxHp.setFloat(enemy, maxHp * 5.0f);
                    eSpeed.setFloat(enemy, speed * 0.76f);
                    eDamage.setFloat(enemy, damage * 1.9f);
                    eR.setFloat(enemy, radius * 1.48f);
                } else {
                    eHp.setFloat(enemy, hp * 2.5f);
                    eMaxHp.setFloat(enemy, maxHp * 2.5f);
                    eSpeed.setFloat(enemy, speed * 1.20f);
                    eDamage.setFloat(enemy, damage * 1.32f);
                    eR.setFloat(enemy, radius * 0.95f);
                }
                elites.put(enemy, new EliteInfo(kind, 1.5f + rng.nextFloat() * 2f));
                promoted++;
            } catch (Exception ignored) { }
        }
        if (promoted > 0) label(promoted > 1 ? "ÉLITES EN APPROCHE ×" + promoted : "ENNEMI ÉLITE");
    }

    private void updateElites(float dt) {
        List<Object> list = enemies();
        if (list == null) return;
        Iterator<Map.Entry<Object, EliteInfo>> it = elites.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Object, EliteInfo> entry = it.next();
            Object enemy = entry.getKey();
            EliteInfo info = entry.getValue();
            if (!list.contains(enemy)) {
                try {
                    bindEnemy(enemy);
                    if (rng.nextFloat() < 0.55f) {
                        dropChest(eX.getFloat(enemy), eY.getFloat(enemy), CHEST_ARTIFACT);
                        label("COFFRE D'ARTEFACT LÂCHÉ");
                    }
                } catch (Exception ignored) { }
                eliteReward(info.kind);
                it.remove();
                continue;
            }
            if (info.kind == ELITE_PHANTOM) {
                info.cooldown -= dt;
                if (info.cooldown <= 0f) {
                    try {
                        bindEnemy(enemy);
                        float ex = eX.getFloat(enemy), ey = eY.getFloat(enemy);
                        float px = number(fPx), py = number(fPy);
                        float dx = px - ex, dy = py - ey;
                        float len = Math.max(1f, (float) Math.hypot(dx, dy));
                        float jump = Math.min(155f, Math.max(80f, len * 0.18f));
                        eX.setFloat(enemy, ex + dx / len * jump);
                        eY.setFloat(enemy, ey + dy / len * jump);
                        info.cooldown = 3.2f + rng.nextFloat() * 2.2f;
                    } catch (Exception ignored) { }
                }
            }
        }
    }

    private void eliteReward(int kind) {
        try {
            if (fScore != null) fScore.setInt(this, integer(fScore) + 180 + currentAct * 45);
            if (mGainXp != null) mGainXp.invoke(this, 5f + currentAct * 1.8f);
            if (kind == ELITE_COLOSSUS && fHp != null) {
                float max = number(fMaxHp);
                fHp.setFloat(this, Math.min(max, number(fHp) + max * 0.05f));
            }
        } catch (Exception ignored) { }
    }

    private void updateBossPhases() {
        List<Object> list = enemies();
        if (list == null) return;
        for (Object enemy : new ArrayList<>(list)) {
            try {
                bindEnemy(enemy);
                if (eType.getInt(enemy) != 4 || bossPhase2.containsKey(enemy)) continue;
                float max = Math.max(1f, eMaxHp.getFloat(enemy));
                if (eHp.getFloat(enemy) / max <= 0.50f) {
                    bossPhase2.put(enemy, Boolean.TRUE);
                    eSpeed.setFloat(enemy, eSpeed.getFloat(enemy) * 1.30f);
                    eDamage.setFloat(enemy, eDamage.getFloat(enemy) * 1.22f);
                    eR.setFloat(enemy, eR.getFloat(enemy) * 1.08f);
                    for (int i = 0; i < Math.min(10, 4 + currentAct); i++) mSpawnEnemy.invoke(this, false);
                    label("BOSS ENRAGÉ");
                }
            } catch (Exception ignored) { }
        }
        Iterator<Object> it = bossPhase2.keySet().iterator();
        while (it.hasNext()) if (!list.contains(it.next())) it.remove();
    }

    private void applyPressure() {
        List<Object> list = enemies();
        int count = list == null ? 0 : list.size();
        int target = Math.min(180, 14 + currentAct * 9 + (int) (number(fElapsed) / 24f));
        if (count >= target) return;
        int batch = Math.min(2 + currentAct / 2, 5);
        try {
            for (int i = 0; i < batch && count + i < target; i++) mSpawnEnemy.invoke(this, false);
        } catch (Exception ignored) { }
    }

    private void startMeteorStrike() {
        float px = number(fPx), py = number(fPy);
        int count = Math.min(9, 4 + currentAct);
        for (int i = 0; i < count; i++) {
            float a = rng.nextFloat() * (float) (Math.PI * 2.0);
            float d = 80f + rng.nextFloat() * 430f;
            meteors.add(new Meteor(px + (float) Math.cos(a) * d,
                    py + (float) Math.sin(a) * d,
                    0.55f + i * 0.13f, 0.48f));
        }
        label("FRAPPE ORBITALE");
    }

    private void updateMeteors(float dt) {
        for (int i = meteors.size() - 1; i >= 0; i--) {
            Meteor m = meteors.get(i);
            if (!m.exploded) {
                m.delay -= dt;
                if (m.delay <= 0f) {
                    m.exploded = true;
                    explodeMeteor(m);
                }
            } else {
                m.life -= dt;
                if (m.life <= 0f) meteors.remove(i);
            }
        }
    }

    private void explodeMeteor(Meteor meteor) {
        List<Object> list = enemies();
        if (list == null || mDamageEnemy == null) return;
        float radius = 120f + currentAct * 5f;
        float dmg = 55f + currentAct * 16f;
        for (Object enemy : new ArrayList<>(list)) {
            try {
                bindEnemy(enemy);
                float dx = eX.getFloat(enemy) - meteor.x;
                float dy = eY.getFloat(enemy) - meteor.y;
                if (dx * dx + dy * dy <= radius * radius) mDamageEnemy.invoke(this, enemy, dmg, false);
            } catch (Exception ignored) { }
        }
    }

    private void spawnBeacon() {
        float px = number(fPx), py = number(fPy);
        float a = rng.nextFloat() * (float) (Math.PI * 2.0);
        float d = 180f + rng.nextFloat() * 260f;
        beacon = new Beacon(px + (float) Math.cos(a) * d, py + (float) Math.sin(a) * d);
        label("BALISE D'ÉNERGIE DÉTECTÉE");
    }

    private void updateBeacon(float dt) {
        if (beacon == null) return;
        beacon.life -= dt;
        if (beacon.life <= 0f) {
            beacon = null;
            return;
        }
        float dx = number(fPx) - beacon.x;
        float dy = number(fPy) - beacon.y;
        if (dx * dx + dy * dy <= beacon.radius * beacon.radius) {
            beacon.progress += dt;
            if (beacon.progress >= 4.2f) {
                completeBeacon();
                beacon = null;
            }
        } else {
            beacon.progress = Math.max(0f, beacon.progress - dt * 0.45f);
        }
    }

    private void completeBeacon() {
        try {
            float max = number(fMaxHp);
            if (fHp != null) fHp.setFloat(this, Math.min(max, number(fHp) + Math.max(35f, max * 0.34f)));
            if (fInvuln != null) fInvuln.setFloat(this, Math.max(number(fInvuln), 2.2f));
            if (mGainXp != null) mGainXp.invoke(this, 18f + currentAct * 5f);
            if (fScore != null) fScore.setInt(this, integer(fScore) + 500 * currentAct);
            label("BALISE CAPTURÉE — BONUS COMPLET");
        } catch (Exception ignored) { }
    }

    private void label(String text) {
        directorLabel = text;
        directorLabelLife = 2.5f;
        try { if (mShowBanner != null) mShowBanner.invoke(this, text); }
        catch (Exception ignored) { }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (inMenu()) {
            drawMenuV7(canvas);
            return;
        }
        if (bool(fDead) || bool(fChoosing) || bool(fPaused)) return;
        drawWorldEvents(canvas);
        drawEliteOverlays(canvas);
        drawDirectorHud(canvas);
    }

    private void drawMenuV7(Canvas canvas) {
        // Le menu principal affiche désormais une seule identité de version lisible.
    }

    private float sx(float worldX) {
        return worldX - number(fCamX) + getWidth() * 0.5f;
    }

    private float sy(float worldY) {
        return worldY - number(fCamY) + getHeight() * 0.56f;
    }

    private void drawWorldEvents(Canvas canvas) {
        for (Meteor m : meteors) {
            float x = sx(m.x), y = sy(m.y);
            if (!m.exploded) {
                float pulse = 1f + (float) Math.sin(number(fElapsed) * 12f + m.x * 0.01f) * 0.08f;
                s.setColor(Color.argb(210, 255, 125, 60));
                s.setStrokeWidth(4f);
                canvas.drawCircle(x, y, (46f + m.delay * 20f) * pulse, s);
                p.setColor(Color.argb(55, 255, 80, 40));
                canvas.drawCircle(x, y, 42f, p);
            } else {
                float alpha = Math.max(0f, m.life / 0.48f);
                p.setColor(Color.argb((int) (110f * alpha), 255, 145, 60));
                canvas.drawCircle(x, y, 135f * (1.05f - alpha * 0.35f), p);
                s.setColor(Color.argb((int) (240f * alpha), 255, 225, 130));
                s.setStrokeWidth(7f);
                canvas.drawCircle(x, y, 120f * (1.05f - alpha * 0.25f), s);
            }
        }

        if (beacon != null) {
            float x = sx(beacon.x), y = sy(beacon.y);
            float ratio = Math.min(1f, beacon.progress / 4.2f);
            p.setColor(Color.argb(42, 75, 220, 255));
            canvas.drawCircle(x, y, beacon.radius, p);
            s.setStrokeWidth(4f);
            s.setColor(Color.rgb(90, 225, 255));
            canvas.drawCircle(x, y, beacon.radius, s);
            s.setStrokeWidth(8f);
            s.setColor(Color.rgb(125, 255, 185));
            RectF ring = new RectF(x - beacon.radius - 8f, y - beacon.radius - 8f,
                    x + beacon.radius + 8f, y + beacon.radius + 8f);
            canvas.drawArc(ring, -90f, ratio * 360f, false, s);
            p.setTextAlign(Paint.Align.CENTER);
            p.setFakeBoldText(true);
            p.setTextSize(14f);
            p.setColor(Color.WHITE);
            canvas.drawText(ratio > 0f ? "CAPTURE " + Math.round(ratio * 100f) + "%" : "BALISE", x, y + 5f, p);
            p.setFakeBoldText(false);
            drawEdgeMarker(canvas, beacon.x, beacon.y, Color.rgb(90, 225, 255), "B");
        }
    }

    private void drawEliteOverlays(Canvas canvas) {
        List<Object> list = enemies();
        if (list == null) return;
        for (Map.Entry<Object, EliteInfo> entry : elites.entrySet()) {
            Object enemy = entry.getKey();
            if (!list.contains(enemy)) continue;
            try {
                bindEnemy(enemy);
                float x = sx(eX.getFloat(enemy)), y = sy(eY.getFloat(enemy));
                float r = eR.getFloat(enemy) + 10f;
                int color = entry.getValue().kind == ELITE_BERSERKER ? Color.rgb(255, 90, 80)
                        : entry.getValue().kind == ELITE_COLOSSUS ? Color.rgb(255, 205, 70)
                        : Color.rgb(160, 105, 255);
                s.setStrokeWidth(4f);
                s.setColor(color);
                canvas.drawCircle(x, y, r, s);
                p.setTextAlign(Paint.Align.CENTER);
                p.setFakeBoldText(true);
                p.setTextSize(10f);
                p.setColor(color);
                canvas.drawText(entry.getValue().kind == ELITE_BERSERKER ? "RAGE"
                        : entry.getValue().kind == ELITE_COLOSSUS ? "TITAN" : "PHASE", x, y - r - 6f, p);
                p.setFakeBoldText(false);
            } catch (Exception ignored) { }
        }

        for (Object enemy : bossPhase2.keySet()) {
            if (!list.contains(enemy)) continue;
            try {
                bindEnemy(enemy);
                drawEdgeMarker(canvas, eX.getFloat(enemy), eY.getFloat(enemy), Color.rgb(255, 90, 90), "BOSS");
            } catch (Exception ignored) { }
        }
    }

    private void drawEdgeMarker(Canvas canvas, float wx, float wy, int color, String text) {
        float x = sx(wx), y = sy(wy);
        float margin = 42f;
        if (x >= margin && x <= getWidth() - margin && y >= 180f && y <= getHeight() - margin) return;
        float cx = getWidth() * 0.5f, cy = getHeight() * 0.56f;
        float dx = x - cx, dy = y - cy;
        float len = Math.max(1f, (float) Math.hypot(dx, dy));
        float maxX = Math.max(1f, getWidth() * 0.5f - margin);
        float maxY = Math.max(1f, getHeight() * 0.44f - margin);
        float scale = Math.min(maxX / Math.max(1f, Math.abs(dx)), maxY / Math.max(1f, Math.abs(dy)));
        scale = Math.min(1f, scale);
        float mx = cx + dx * scale;
        float my = cy + dy * scale;
        p.setColor(Color.argb(200, Color.red(color), Color.green(color), Color.blue(color)));
        canvas.drawCircle(mx, my, 18f, p);
        p.setColor(Color.WHITE);
        p.setFakeBoldText(true);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(text.length() > 2 ? 8f : 12f);
        canvas.drawText(text, mx, my + 4f, p);
        p.setFakeBoldText(false);
    }

    private void drawDirectorHud(Canvas canvas) {
        float y = 250f;
        p.setTextAlign(Paint.Align.LEFT);
        p.setFakeBoldText(true);
        p.setTextSize(13f);
        p.setColor(Color.rgb(170, 205, 210));
        canvas.drawText("ACTE " + currentAct + "   •   NIV " + integer(fLevel), 20f, y, p);
        if (combo > 0) {
            p.setColor(combo >= 50 ? Color.rgb(255, 190, 80) : Color.rgb(115, 230, 255));
            canvas.drawText("COMBO ×" + combo, 20f, y + 21f, p);
            float ratio = Math.max(0f, Math.min(1f, comboTimer / 3.2f));
            p.setColor(Color.argb(120, 255, 255, 255));
            canvas.drawRoundRect(20f, y + 27f, 140f, y + 32f, 3f, 3f, p);
            p.setColor(Color.rgb(100, 225, 255));
            canvas.drawRoundRect(20f, y + 27f, 20f + 120f * ratio, y + 32f, 3f, 3f, p);
        }
        if (directorLabelLife > 0f) {
            p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(13f);
            p.setColor(Color.argb((int) (220f * Math.min(1f, directorLabelLife)), 245, 245, 245));
            canvas.drawText(directorLabel, getWidth() * 0.5f, 285f, p);
        }
        p.setFakeBoldText(false);
    }

    private static final class EliteInfo {
        final int kind;
        float cooldown;
        EliteInfo(int kind, float cooldown) { this.kind = kind; this.cooldown = cooldown; }
    }

    private static final class Meteor {
        final float x, y;
        float delay, life;
        boolean exploded;
        Meteor(float x, float y, float delay, float life) {
            this.x = x; this.y = y; this.delay = delay; this.life = life;
        }
    }

    private static final class Beacon {
        final float x, y;
        final float radius = 96f;
        float progress;
        float life = 22f;
        Beacon(float x, float y) { this.x = x; this.y = y; }
    }
}
