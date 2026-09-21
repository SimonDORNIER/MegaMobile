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
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * MegaMobile V0.8 "ASCENSION".
 * Big gameplay layer built on the proven V0.7 engine.
 */
public class GameViewV8 extends GameViewV7Final {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random rng = new Random();

    private Field fElapsed, fKills, fScore, fLevel, fHp, fMaxHp, fDamage, fSpeed;
    private Field fRegen, fCrit, fMulti, fAura, fOrbit, fLightning, fRocket, fEnemies;
    private Field fPx, fPy, fPaused, fDead, fChoosing, fInvuln, fInMenu;
    private Method mSpawnEnemy, mGainXp, mDamageEnemy, mShowBanner;

    private Class<?> enemyClass;
    private Field eType, eX, eY, eR, eHp, eMaxHp, eSpeed, eDamage;

    private long lastMs;
    private boolean previousMenu = true;
    private float lastElapsed;
    private int lastKills;
    private int lastBossCount;

    private float fury;
    private float overdrive;
    private float overdrivePulse;

    private boolean contractActive;
    private int contractStartKills;
    private int contractTarget;
    private float contractTimer;
    private float nextContract = 42f;

    private int droneTier;
    private float droneCd;
    private float novaCd = 5.5f;
    private float evolutionCdA, evolutionCdB, evolutionCdC, evolutionCdD;

    private boolean solarHalo;
    private boolean bladeVortex;
    private boolean stormCore;
    private boolean siegeSwarm;

    private Object nemesis;
    private float nemesisTimer = 52f;

    private int bossSouls;
    private int ascensionRank;
    private float surgeTimer = 24f;

    private final ArrayList<BeamFx> beams = new ArrayList<>();
    private final ArrayList<PulseFx> pulses = new ArrayList<>();

    public GameViewV8(Context context) {
        super(context);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(3f);
        bind();
        lastMs = SystemClock.uptimeMillis();
        lastKills = integer(fKills);
        lastBossCount = countBosses();
    }

    private void bind() {
        try {
            fElapsed = baseField("elapsed");
            fKills = baseField("kills");
            fScore = baseField("score");
            fLevel = baseField("level");
            fHp = baseField("hp");
            fMaxHp = baseField("maxHp");
            fDamage = baseField("damage");
            fSpeed = baseField("speed");
            fRegen = baseField("regen");
            fCrit = baseField("crit");
            fMulti = baseField("multi");
            fAura = baseField("auraLevel");
            fOrbit = baseField("orbitLevel");
            fLightning = baseField("lightningLevel");
            fRocket = baseField("rocketLevel");
            fEnemies = baseField("enemies");
            fPx = baseField("px");
            fPy = baseField("py");
            fPaused = baseField("paused");
            fDead = baseField("dead");
            fChoosing = baseField("choosing");
            fInvuln = baseField("invuln");
            fInMenu = GameViewFinal.class.getDeclaredField("inMenu");
            fInMenu.setAccessible(true);

            mSpawnEnemy = GameView.class.getDeclaredMethod("spawnEnemy", boolean.class);
            mSpawnEnemy.setAccessible(true);
            mGainXp = GameView.class.getDeclaredMethod("gainXp", float.class);
            mGainXp.setAccessible(true);
            mShowBanner = GameView.class.getDeclaredMethod("showBanner", String.class);
            mShowBanner.setAccessible(true);
            for (Method m : GameView.class.getDeclaredMethods()) {
                if ("damageEnemy".equals(m.getName())) {
                    mDamageEnemy = m;
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

    private void setFloat(Field f, float v) {
        try { if (f != null) f.setFloat(this, v); }
        catch (Exception ignored) { }
    }

    private void setInt(Field f, int v) {
        try { if (f != null) f.setInt(this, v); }
        catch (Exception ignored) { }
    }

    private boolean inMenu() {
        try { return fInMenu != null && fInMenu.getBoolean(this); }
        catch (Exception ignored) { return false; }
    }

    @SuppressWarnings("unchecked")
    private List<Object> enemies() {
        try { return fEnemies == null ? null : (List<Object>) fEnemies.get(this); }
        catch (Exception ignored) { return null; }
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        super.doFrame(frameTimeNanos);
        long now = SystemClock.uptimeMillis();
        float dt = Math.min(0.08f, Math.max(0f, (now - lastMs) / 1000f));
        lastMs = now;
        updateV8(dt);
    }

    private void updateV8(float dt) {
        boolean menu = inMenu();
        float elapsed = number(fElapsed);
        if ((previousMenu && !menu) || (lastElapsed > 5f && elapsed + 1f < lastElapsed)) resetRunV8();
        previousMenu = menu;
        lastElapsed = elapsed;

        updateFx(dt);
        if (menu || bool(fPaused) || bool(fDead) || bool(fChoosing)) return;

        int kills = integer(fKills);
        int killDelta = Math.max(0, kills - lastKills);
        lastKills = kills;
        if (killDelta > 0) fury = Math.min(100f, fury + killDelta * (3.0f + integer(fLevel) * 0.025f));

        if (overdrive > 0f) {
            overdrive -= dt;
            overdrivePulse -= dt;
            setFloat(fInvuln, Math.max(number(fInvuln), 0.07f));
            if (overdrivePulse <= 0f) {
                overdrivePulse = 0.36f;
                overdriveStrike();
            }
        } else if (fury >= 100f) startOverdrive();

        updateDrones(dt);
        updateEvolutions(dt);
        updateContract(dt);
        updateNemesis(dt);
        updateBossSouls();
        updateSurges(dt);
    }

    private void resetRunV8() {
        fury = 0f;
        overdrive = overdrivePulse = 0f;
        contractActive = false;
        contractStartKills = integer(fKills);
        contractTarget = 0;
        contractTimer = 0f;
        nextContract = 34f;
        droneTier = 0;
        droneCd = 0.7f;
        novaCd = 5.5f;
        evolutionCdA = evolutionCdB = evolutionCdC = evolutionCdD = 0f;
        solarHalo = bladeVortex = stormCore = siegeSwarm = false;
        nemesis = null;
        nemesisTimer = 48f;
        bossSouls = ascensionRank = 0;
        surgeTimer = 22f;
        lastKills = integer(fKills);
        lastBossCount = countBosses();
        beams.clear();
        pulses.clear();
    }

    private void startOverdrive() {
        fury = 0f;
        overdrive = 10f;
        overdrivePulse = 0f;
        float max = number(fMaxHp);
        setFloat(fHp, Math.min(max, number(fHp) + max * 0.12f));
        banner("SURCHARGE ASCENSION — 10s");
        pulseFx(number(fPx), number(fPy), 70f, Color.rgb(255, 206, 76));
    }

    private void overdriveStrike() {
        List<Object> list = snapshotEnemies();
        if (list.isEmpty()) return;
        final float px = number(fPx), py = number(fPy);
        list.sort(Comparator.comparingDouble(e -> distanceSq(px, py, enemyX(e), enemyY(e))));
        int hits = Math.min(list.size(), 4 + Math.max(1, integer(fLevel) / 8));
        float dmg = Math.max(12f, number(fDamage) * 0.70f);
        for (int i = 0; i < hits; i++) {
            Object e = list.get(i);
            hit(e, dmg, true);
            beams.add(new BeamFx(px, py, enemyX(e), enemyY(e), 0.10f, Color.rgb(255, 214, 84)));
        }
    }

    private void updateDrones(float dt) {
        int level = integer(fLevel);
        int wanted = level >= 34 ? 4 : level >= 24 ? 3 : level >= 14 ? 2 : level >= 7 ? 1 : 0;
        if (wanted > droneTier) {
            droneTier = wanted;
            banner(droneTier == 1 ? "NOUVELLE ARME : DRONE" : "DRONES ×" + droneTier);
        }
        if (droneTier <= 0) return;
        droneCd -= dt;
        if (droneCd > 0f) return;
        droneCd = Math.max(0.22f, 0.92f - droneTier * 0.13f);

        List<Object> list = snapshotEnemies();
        if (list.isEmpty()) return;
        final float px = number(fPx), py = number(fPy);
        list.sort(Comparator.comparingDouble(e -> distanceSq(px, py, enemyX(e), enemyY(e))));
        float dmg = number(fDamage) * (0.62f + droneTier * 0.11f);
        int count = Math.min(droneTier, list.size());
        float t = number(fElapsed) * 1.9f;
        for (int i = 0; i < count; i++) {
            Object target = list.get(i);
            float a = t + (float) (Math.PI * 2.0 * i / Math.max(1, droneTier));
            float sx = px + (float) Math.cos(a) * 52f;
            float sy = py + (float) Math.sin(a) * 52f;
            hit(target, dmg, false);
            beams.add(new BeamFx(sx, sy, enemyX(target), enemyY(target), 0.11f, Color.rgb(90, 235, 255)));
        }
    }

    private void updateEvolutions(float dt) {
        int aura = integer(fAura), orbit = integer(fOrbit), lightning = integer(fLightning), rocket = integer(fRocket);
        if (!solarHalo && aura >= 4 && number(fRegen) >= 0.8f) {
            solarHalo = true;
            banner("ÉVOLUTION : HALO SOLAIRE");
        }
        if (!bladeVortex && orbit >= 4 && number(fSpeed) >= 330f) {
            bladeVortex = true;
            banner("ÉVOLUTION : VORTEX DE LAMES");
        }
        if (!stormCore && lightning >= 4 && number(fCrit) >= 0.14f) {
            stormCore = true;
            banner("ÉVOLUTION : CŒUR D'ORAGE");
        }
        if (!siegeSwarm && rocket >= 4 && integer(fMulti) >= 3) {
            siegeSwarm = true;
            banner("ÉVOLUTION : ESSAIM DE SIÈGE");
        }

        if (integer(fLevel) >= 12) {
            novaCd -= dt;
            if (novaCd <= 0f) {
                novaCd = Math.max(2.7f, 6.0f - integer(fLevel) * 0.055f);
                novaBlast(145f + integer(fLevel) * 2.2f, number(fDamage) * 0.72f, Color.rgb(116, 227, 170));
            }
        }

        if (solarHalo) {
            evolutionCdA -= dt;
            if (evolutionCdA <= 0f) {
                evolutionCdA = 1.25f;
                int hits = novaBlast(205f, number(fDamage) * (0.52f + aura * 0.06f), Color.rgb(255, 194, 68));
                if (hits > 0) {
                    float max = number(fMaxHp);
                    setFloat(fHp, Math.min(max, number(fHp) + max * 0.0045f));
                }
            }
        }

        if (bladeVortex) {
            evolutionCdB -= dt;
            if (evolutionCdB <= 0f) {
                evolutionCdB = 2.65f;
                novaBlast(275f, number(fDamage) * (1.25f + orbit * 0.10f), Color.rgb(230, 238, 250));
            }
        }

        if (stormCore) {
            evolutionCdC -= dt;
            if (evolutionCdC <= 0f) {
                evolutionCdC = 2.10f;
                chainStrike(7 + lightning, number(fDamage) * (1.10f + lightning * 0.11f), 720f);
            }
        }

        if (siegeSwarm) {
            evolutionCdD -= dt;
            if (evolutionCdD <= 0f) {
                evolutionCdD = 4.2f;
                artilleryStrike(Math.min(8, 3 + rocket), number(fDamage) * (1.85f + rocket * 0.17f));
            }
        }
    }

    private int novaBlast(float radius, float dmg, int color) {
        float px = number(fPx), py = number(fPy);
        int hits = 0;
        for (Object e : snapshotEnemies()) {
            if (distanceSq(px, py, enemyX(e), enemyY(e)) <= radius * radius) {
                hit(e, dmg, false);
                hits++;
            }
        }
        pulses.add(new PulseFx(px, py, radius, 0.28f, color));
        return hits;
    }

    private void chainStrike(int maxHits, float dmg, float maxRange) {
        List<Object> list = snapshotEnemies();
        if (list.isEmpty()) return;
        final float px = number(fPx), py = number(fPy);
        list.removeIf(e -> distanceSq(px, py, enemyX(e), enemyY(e)) > maxRange * maxRange);
        list.sort(Comparator.comparingDouble(e -> distanceSq(px, py, enemyX(e), enemyY(e))));
        float fromX = px, fromY = py;
        for (int i = 0; i < Math.min(maxHits, list.size()); i++) {
            Object e = list.get(i);
            float ex = enemyX(e), ey = enemyY(e);
            hit(e, dmg, true);
            beams.add(new BeamFx(fromX, fromY, ex, ey, 0.13f, Color.rgb(155, 120, 255)));
            fromX = ex;
            fromY = ey;
        }
    }

    private void artilleryStrike(int count, float dmg) {
        List<Object> list = snapshotEnemies();
        if (list.isEmpty()) return;
        for (int i = 0; i < Math.min(count, list.size()); i++) {
            Object e = list.get(rng.nextInt(list.size()));
            float ex = enemyX(e), ey = enemyY(e);
            hit(e, dmg, false);
            pulses.add(new PulseFx(ex, ey, 62f, 0.24f, Color.rgb(255, 118, 52)));
        }
    }

    private void updateContract(float dt) {
        if (contractActive) {
            contractTimer -= dt;
            int progress = integer(fKills) - contractStartKills;
            if (progress >= contractTarget) {
                contractActive = false;
                contractSuccess();
                nextContract = 46f + rng.nextFloat() * 18f;
            } else if (contractTimer <= 0f) {
                contractActive = false;
                contractFailure();
                nextContract = 42f + rng.nextFloat() * 15f;
            }
            return;
        }
        nextContract -= dt;
        if (nextContract <= 0f) startContract();
    }

    private void startContract() {
        contractActive = true;
        contractStartKills = integer(fKills);
        int level = integer(fLevel);
        int act = 1 + (int) (number(fElapsed) / 120f);
        contractTarget = 12 + Math.min(40, level + act * 3);
        contractTimer = 26f;
        banner("CONTRAT : " + contractTarget + " ÉLIMINATIONS");
    }

    private void contractSuccess() {
        int level = integer(fLevel);
        setInt(fScore, integer(fScore) + 850 + level * 45);
        try { if (mGainXp != null) mGainXp.invoke(this, 16f + level * 0.7f); }
        catch (Exception ignored) { }
        float max = number(fMaxHp);
        setFloat(fHp, Math.min(max, number(fHp) + max * 0.16f));
        fury = Math.min(100f, fury + 38f);
        banner("CONTRAT RÉUSSI — RÉCOMPENSE");
    }

    private void contractFailure() {
        banner("CONTRAT ÉCHOUÉ — CHASSEUR ENVOYÉ");
        try {
            if (mSpawnEnemy != null) {
                mSpawnEnemy.invoke(this, true);
                for (int i = 0; i < 6; i++) mSpawnEnemy.invoke(this, false);
            }
        } catch (Exception ignored) { }
    }

    private void updateNemesis(float dt) {
        List<Object> list = enemies();
        if (nemesis != null && (list == null || !list.contains(nemesis))) {
            nemesis = null;
            nemesisReward();
            nemesisTimer = 58f + rng.nextFloat() * 26f;
        }
        if (nemesis != null) return;
        nemesisTimer -= dt;
        if (nemesisTimer <= 0f) createNemesis();
    }

    private void createNemesis() {
        List<Object> list = snapshotEnemies();
        if (list.isEmpty()) {
            nemesisTimer = 8f;
            return;
        }
        for (int tries = 0; tries < 24; tries++) {
            Object e = list.get(rng.nextInt(list.size()));
            try {
                bindEnemy(e);
                if (eType.getInt(e) == 4) continue;
                float hp = eHp.getFloat(e);
                float max = eMaxHp.getFloat(e);
                eHp.setFloat(e, hp * 7.5f);
                eMaxHp.setFloat(e, max * 7.5f);
                eSpeed.setFloat(e, eSpeed.getFloat(e) * 1.16f);
                eDamage.setFloat(e, eDamage.getFloat(e) * 2.0f);
                eR.setFloat(e, eR.getFloat(e) * 1.42f);
                nemesis = e;
                banner("NÉMÉSIS — PRIME MAJEURE");
                return;
            } catch (Exception ignored) { }
        }
        nemesisTimer = 12f;
    }

    private void nemesisReward() {
        setInt(fScore, integer(fScore) + 1200 + integer(fLevel) * 35);
        try { if (mGainXp != null) mGainXp.invoke(this, 20f + integer(fLevel)); }
        catch (Exception ignored) { }
        float max = number(fMaxHp);
        setFloat(fHp, Math.min(max, number(fHp) + max * 0.22f));
        fury = Math.min(100f, fury + 55f);
        banner("NÉMÉSIS ABATTUE — PRIME !");
    }

    private void updateBossSouls() {
        int bosses = countBosses();
        if (lastBossCount > bosses) {
            int deadBosses = lastBossCount - bosses;
            bossSouls += deadBosses;
            fury = Math.min(100f, fury + deadBosses * 22f);
            if (bossSouls / 3 > ascensionRank) {
                ascensionRank = bossSouls / 3;
                applyAscension();
            }
        }
        lastBossCount = bosses;
    }

    private void applyAscension() {
        float dmg = number(fDamage);
        float max = number(fMaxHp);
        setFloat(fDamage, dmg * 1.12f);
        setFloat(fMaxHp, max * 1.07f);
        setFloat(fHp, Math.min(number(fMaxHp), number(fHp) + max * 0.18f));
        banner("ASCENSION " + ascensionRank + " — PUISSANCE PERMANENTE");
    }

    private void updateSurges(float dt) {
        surgeTimer -= dt;
        if (surgeTimer > 0f) return;
        int act = 1 + (int) (number(fElapsed) / 120f);
        int wave = Math.min(20, 5 + act * 2 + integer(fLevel) / 7);
        try {
            for (int i = 0; i < wave; i++) mSpawnEnemy.invoke(this, false);
            if (act >= 5 && rng.nextFloat() < 0.30f) mSpawnEnemy.invoke(this, true);
        } catch (Exception ignored) { }
        surgeTimer = Math.max(18f, 37f - act * 1.7f);
    }

    private int countBosses() {
        int count = 0;
        for (Object e : snapshotEnemies()) {
            try {
                bindEnemy(e);
                if (eType.getInt(e) == 4) count++;
            } catch (Exception ignored) { }
        }
        return count;
    }

    private List<Object> snapshotEnemies() {
        List<Object> list = enemies();
        return list == null ? new ArrayList<>() : new ArrayList<>(list);
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

    private float enemyX(Object e) {
        try { bindEnemy(e); return eX.getFloat(e); }
        catch (Exception ignored) { return 0f; }
    }

    private float enemyY(Object e) {
        try { bindEnemy(e); return eY.getFloat(e); }
        catch (Exception ignored) { return 0f; }
    }

    private float enemyR(Object e) {
        try { bindEnemy(e); return eR.getFloat(e); }
        catch (Exception ignored) { return 20f; }
    }

    private void hit(Object enemy, float damage, boolean lightning) {
        try { if (mDamageEnemy != null && enemy != null) mDamageEnemy.invoke(this, enemy, damage, lightning); }
        catch (Exception ignored) { }
    }

    private void banner(String text) {
        try { if (mShowBanner != null) mShowBanner.invoke(this, text); }
        catch (Exception ignored) { }
    }

    private float distanceSq(float ax, float ay, float bx, float by) {
        float dx = ax - bx, dy = ay - by;
        return dx * dx + dy * dy;
    }

    private void pulseFx(float x, float y, float radius, int color) {
        pulses.add(new PulseFx(x, y, radius, 0.34f, color));
    }

    private void updateFx(float dt) {
        for (int i = beams.size() - 1; i >= 0; i--) {
            BeamFx b = beams.get(i);
            b.life -= dt;
            if (b.life <= 0f) beams.remove(i);
        }
        for (int i = pulses.size() - 1; i >= 0; i--) {
            PulseFx fx = pulses.get(i);
            fx.life -= dt;
            if (fx.life <= 0f) pulses.remove(i);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        if (inMenu()) {
            return;
        }

        if (bool(fDead) || bool(fChoosing) || bool(fPaused)) return;

        drawWorldFx(canvas, w, h);
        drawV8Hud(canvas, w, h);
    }

    private void drawWorldFx(Canvas c, int w, int h) {
        float camX = readBase("camX"), camY = readBase("camY");
        float anchorX = w * 0.5f, anchorY = h * 0.56f;
        c.save();
        c.translate(anchorX - camX, anchorY - camY);

        for (PulseFx fx : pulses) {
            float alpha = Math.max(0f, fx.life / fx.maxLife);
            stroke.setColor(withAlpha(fx.color, (int) (210 * alpha)));
            stroke.setStrokeWidth(5f);
            float radius = fx.radius * (1.20f - 0.20f * alpha);
            c.drawCircle(fx.x, fx.y, radius, stroke);
        }
        for (BeamFx b : beams) {
            float alpha = Math.max(0f, b.life / b.maxLife);
            stroke.setColor(withAlpha(b.color, (int) (235 * alpha)));
            stroke.setStrokeWidth(4f + alpha * 3f);
            c.drawLine(b.x1, b.y1, b.x2, b.y2, stroke);
        }

        if (nemesis != null) {
            List<Object> list = enemies();
            if (list != null && list.contains(nemesis)) {
                float x = enemyX(nemesis), y = enemyY(nemesis), r = enemyR(nemesis);
                stroke.setColor(Color.rgb(255, 211, 70));
                stroke.setStrokeWidth(5f);
                float pulse = 5f + (float) Math.sin(number(fElapsed) * 6f) * 4f;
                c.drawCircle(x, y, r + 13f + pulse, stroke);
                p.setColor(Color.rgb(255, 222, 90));
                p.setTextAlign(Paint.Align.CENTER);
                p.setFakeBoldText(true);
                p.setTextSize(15f);
                c.drawText("NÉMÉSIS", x, y - r - 24f, p);
                p.setFakeBoldText(false);
            }
        }

        if (droneTier > 0) {
            float px = number(fPx), py = number(fPy);
            float t = number(fElapsed) * 1.9f;
            for (int i = 0; i < droneTier; i++) {
                float a = t + (float) (Math.PI * 2.0 * i / droneTier);
                float x = px + (float) Math.cos(a) * 52f;
                float y = py + (float) Math.sin(a) * 52f;
                p.setColor(Color.rgb(65, 211, 235));
                c.drawCircle(x, y, 9f, p);
                stroke.setColor(Color.WHITE);
                stroke.setStrokeWidth(2f);
                c.drawCircle(x, y, 9f, stroke);
            }
        }
        c.restore();
    }

    private void drawV8Hud(Canvas c, int w, int h) {
        float x = 22f, y = 132f, bw = Math.min(260f, w - 44f), bh = 12f;
        p.setColor(Color.argb(160, 8, 14, 16));
        c.drawRoundRect(new RectF(x, y, x + bw, y + bh), 6f, 6f, p);
        float ratio = overdrive > 0f ? Math.max(0f, overdrive / 10f) : fury / 100f;
        p.setColor(overdrive > 0f ? Color.rgb(255, 207, 65) : Color.rgb(70, 195, 230));
        c.drawRoundRect(new RectF(x, y, x + bw * ratio, y + bh), 6f, 6f, p);
        p.setColor(Color.WHITE);
        p.setTextAlign(Paint.Align.LEFT);
        p.setTextSize(12f);
        p.setFakeBoldText(true);
        c.drawText(overdrive > 0f ? "SURCHARGE " + Math.max(1, (int) Math.ceil(overdrive)) + "s" : "FUREUR " + Math.round(fury) + "%", x, y - 4f, p);
        p.setFakeBoldText(false);

        float textY = y + 34f;
        if (contractActive) {
            int progress = Math.max(0, integer(fKills) - contractStartKills);
            p.setColor(Color.rgb(255, 226, 145));
            p.setTextSize(13f);
            c.drawText("CONTRAT  " + progress + "/" + contractTarget + "  •  " + Math.max(0, (int) Math.ceil(contractTimer)) + "s", x, textY, p);
            textY += 20f;
        }

        StringBuilder evolved = new StringBuilder();
        if (solarHalo) evolved.append("☀ ");
        if (bladeVortex) evolved.append("✦ ");
        if (stormCore) evolved.append("⚡ ");
        if (siegeSwarm) evolved.append("✹ ");
        if (evolved.length() > 0 || droneTier > 0 || ascensionRank > 0) {
            p.setColor(Color.rgb(182, 211, 216));
            p.setTextSize(12f);
            String extra = (droneTier > 0 ? "DRONES×" + droneTier + "  " : "")
                    + (ascensionRank > 0 ? "ASCENSION " + ascensionRank + "  " : "") + evolved;
            c.drawText(extra.trim(), x, textY, p);
        }
    }

    private float readBase(String name) {
        try {
            Field f = GameView.class.getDeclaredField(name);
            f.setAccessible(true);
            return f.getFloat(this);
        } catch (Exception ignored) { return 0f; }
    }

    private int withAlpha(int color, int alpha) {
        return Color.argb(Math.max(0, Math.min(255, alpha)), Color.red(color), Color.green(color), Color.blue(color));
    }

    private static final class BeamFx {
        final float x1, y1, x2, y2, maxLife;
        final int color;
        float life;
        BeamFx(float x1, float y1, float x2, float y2, float life, int color) {
            this.x1 = x1; this.y1 = y1; this.x2 = x2; this.y2 = y2;
            this.life = this.maxLife = life; this.color = color;
        }
    }

    private static final class PulseFx {
        final float x, y, radius, maxLife;
        final int color;
        float life;
        PulseFx(float x, float y, float radius, float life, int color) {
            this.x = x; this.y = y; this.radius = radius;
            this.life = this.maxLife = life; this.color = color;
        }
    }
}
