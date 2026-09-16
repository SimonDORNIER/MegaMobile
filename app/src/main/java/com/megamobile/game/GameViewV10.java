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
import java.util.List;

/**
 * MegaMobile V1.0 final gameplay layer.
 *
 * Finishes the endless loop with anti-stall pressure, late-game Void Pulse,
 * cataclysms, level milestones and one controlled last-stand per run.
 */
public class GameViewV10 extends GameViewV8 {
    private final Paint p10 = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint s10 = new Paint(Paint.ANTI_ALIAS_FLAG);

    private Field fElapsed, fKills, fScore, fLevel, fHp, fMaxHp, fDamage, fInvuln;
    private Field fPaused, fDead, fChoosing, fEnemies, fInMenu;
    private Method mSpawnEnemy, mGainXp, mDamageEnemy, mShowBanner;

    private Class<?> enemyClass;
    private Field eX, eY, eHp, eMaxHp;

    private long lastMs10;
    private boolean previousMenu10 = true;
    private float lastElapsed10;
    private int lastKills10;
    private float noKillTimer;
    private float voidCd = 7f;
    private float voidFx;
    private float cataclysmCd = 145f;
    private int milestoneRank;
    private boolean lastStandUsed;
    private float statusLife;
    private String statusText = "";

    public GameViewV10(Context context) {
        super(context);
        s10.setStyle(Paint.Style.STROKE);
        s10.setStrokeWidth(5f);
        bindV10();
        lastMs10 = SystemClock.uptimeMillis();
        lastKills10 = integer(fKills);
    }

    private void bindV10() {
        try {
            fElapsed = baseField("elapsed");
            fKills = baseField("kills");
            fScore = baseField("score");
            fLevel = baseField("level");
            fHp = baseField("hp");
            fMaxHp = baseField("maxHp");
            fDamage = baseField("damage");
            fInvuln = baseField("invuln");
            fPaused = baseField("paused");
            fDead = baseField("dead");
            fChoosing = baseField("choosing");
            fEnemies = baseField("enemies");
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

    private boolean inMenu10() {
        try { return fInMenu != null && fInMenu.getBoolean(this); }
        catch (Exception ignored) { return false; }
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

    private void setFloat(Field f, float value) {
        try { if (f != null) f.setFloat(this, value); }
        catch (Exception ignored) { }
    }

    private void setInt(Field f, int value) {
        try { if (f != null) f.setInt(this, value); }
        catch (Exception ignored) { }
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        super.doFrame(frameTimeNanos);
        long now = SystemClock.uptimeMillis();
        float dt = Math.min(0.08f, Math.max(0f, (now - lastMs10) / 1000f));
        lastMs10 = now;
        updateV10(dt);
    }

    private void updateV10(float dt) {
        boolean menu = inMenu10();
        float elapsed = number(fElapsed);
        if ((previousMenu10 && !menu) || (lastElapsed10 > 5f && elapsed + 1f < lastElapsed10)) resetV10();
        previousMenu10 = menu;
        lastElapsed10 = elapsed;
        if (statusLife > 0f) statusLife -= dt;
        if (voidFx > 0f) voidFx -= dt;

        if (menu || bool(fPaused) || bool(fDead) || bool(fChoosing)) return;

        int kills = integer(fKills);
        if (kills > lastKills10) {
            noKillTimer = 0f;
            lastKills10 = kills;
        } else {
            noKillTimer += dt;
        }

        antiStall();
        updateLastStand();
        updateMilestones();
        updateVoidPulse(dt);
        updateCataclysm(dt);
    }

    private void resetV10() {
        noKillTimer = 0f;
        voidCd = 7f;
        voidFx = 0f;
        cataclysmCd = 145f;
        milestoneRank = 0;
        lastStandUsed = false;
        lastKills10 = integer(fKills);
        statusLife = 2.6f;
        statusText = "MODE INFINI — SURVIS LE PLUS LONGTEMPS POSSIBLE";
    }

    private void antiStall() {
        List<Object> list = enemies10();
        int enemyCount = list == null ? 0 : list.size();
        if (enemyCount < 4 && noKillTimer > 4.5f) {
            spawn(false, 7 + Math.min(12, integer(fLevel) / 3));
            noKillTimer = 1f;
            return;
        }
        if (noKillTimer > 9f) {
            spawn(false, 10 + Math.min(18, integer(fLevel) / 2));
            noKillTimer = 2f;
            status("RENFORTS — LA HORDE NE S'ARRÊTE JAMAIS");
        }
    }

    private void updateLastStand() {
        if (lastStandUsed) return;
        float max = Math.max(1f, number(fMaxHp));
        float hp = number(fHp);
        if (hp > 0f && hp / max <= 0.14f) {
            lastStandUsed = true;
            setFloat(fHp, Math.max(hp, max * 0.38f));
            setFloat(fInvuln, Math.max(number(fInvuln), 3.2f));
            addScore(900);
            gainXp(12f);
            banner("DERNIER SOUFFLE — 3s D'INVULNÉRABILITÉ");
            status("SECONDE CHANCE CONSOMMÉE");
        }
    }

    private void updateMilestones() {
        int level = integer(fLevel);
        int rank = level / 10;
        if (rank <= milestoneRank || rank <= 0) return;
        milestoneRank = rank;
        float max = Math.max(1f, number(fMaxHp));
        setFloat(fHp, Math.min(max, number(fHp) + max * 0.18f));
        setFloat(fDamage, number(fDamage) * 1.035f);
        addScore(450 * rank);
        gainXp(4f + rank * 1.5f);
        banner("PALIER " + (rank * 10) + " — PUISSANCE ACCRUE");
    }

    private void updateVoidPulse(float dt) {
        int level = integer(fLevel);
        if (level < 28) return;
        voidCd -= dt;
        if (voidCd > 0f) return;
        voidCd = Math.max(3.4f, 7.2f - level * 0.045f);

        float px = baseFloat("px"), py = baseFloat("py");
        float radius = Math.min(430f, 250f + level * 3.0f);
        float dmg = number(fDamage) * (1.85f + Math.min(2.0f, level * 0.025f));
        int hits = 0;
        for (Object e : snapshotEnemies10()) {
            float ex = enemyFloat(e, "x"), ey = enemyFloat(e, "y");
            float dx = ex - px, dy = ey - py;
            if (dx * dx + dy * dy <= radius * radius) {
                hit(e, dmg, false);
                hits++;
            }
        }
        if (hits > 0) {
            voidFx = 0.42f;
            addScore(hits * 3);
            if (level == 28) banner("ARME ÉVEILLÉE : IMPULSION DU VIDE");
        }
    }

    private void updateCataclysm(float dt) {
        if (number(fElapsed) < 120f) return;
        cataclysmCd -= dt;
        if (cataclysmCd > 0f) return;

        int act = 1 + (int) (number(fElapsed) / 120f);
        int bosses = Math.min(4, 1 + act / 3);
        int horde = Math.min(42, 16 + act * 4);
        spawn(true, bosses);
        spawn(false, horde);
        addScore(700 * act);
        banner("CATACLYSME — " + bosses + (bosses > 1 ? " BOSS" : " BOSS"));
        status("SURVIS À LA VAGUE POUR CONTINUER L'ASCENSION");
        cataclysmCd = Math.max(82f, 155f - act * 5f);
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

    private void addScore(int amount) {
        setInt(fScore, integer(fScore) + Math.max(0, amount));
    }

    private void banner(String value) {
        try { if (mShowBanner != null) mShowBanner.invoke(this, value); }
        catch (Exception ignored) { }
    }

    private void status(String value) {
        statusText = value;
        statusLife = 3.4f;
    }

    @SuppressWarnings("unchecked")
    private List<Object> enemies10() {
        try { return fEnemies == null ? null : (List<Object>) fEnemies.get(this); }
        catch (Exception ignored) { return null; }
    }

    private List<Object> snapshotEnemies10() {
        List<Object> src = enemies10();
        return src == null ? new ArrayList<>() : new ArrayList<>(src);
    }

    private void bindEnemy(Object enemy) throws Exception {
        if (enemy == null) return;
        Class<?> c = enemy.getClass();
        if (c == enemyClass) return;
        enemyClass = c;
        eX = privateField(c, "x");
        eY = privateField(c, "y");
        eHp = privateField(c, "hp");
        eMaxHp = privateField(c, "maxHp");
    }

    private Field privateField(Class<?> c, String name) throws NoSuchFieldException {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    private float enemyFloat(Object enemy, String name) {
        try {
            bindEnemy(enemy);
            if ("x".equals(name)) return eX.getFloat(enemy);
            if ("y".equals(name)) return eY.getFloat(enemy);
            if ("hp".equals(name)) return eHp.getFloat(enemy);
            if ("maxHp".equals(name)) return eMaxHp.getFloat(enemy);
        } catch (Exception ignored) { }
        return 0f;
    }

    private void hit(Object enemy, float damage, boolean lightning) {
        try { if (mDamageEnemy != null && enemy != null) mDamageEnemy.invoke(this, enemy, damage, lightning); }
        catch (Exception ignored) { }
    }

    private float baseFloat(String name) {
        try {
            Field f = GameView.class.getDeclaredField(name);
            f.setAccessible(true);
            return f.getFloat(this);
        } catch (Exception ignored) { return 0f; }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        if (inMenu10()) {
            p10.setColor(Color.rgb(8, 14, 18));
            canvas.drawRect(0f, h - 104f, w, h, p10);
            p10.setTextAlign(Paint.Align.CENTER);
            p10.setFakeBoldText(true);
            p10.setTextSize(20f);
            p10.setColor(Color.rgb(255, 216, 86));
            canvas.drawText("MEGAMOBILE V1.0 • ASCENSION", w * 0.5f, h - 72f, p10);
            p10.setTextSize(12f);
            p10.setColor(Color.rgb(174, 202, 207));
            canvas.drawText("mode infini • évolutions • némésis • contrats • cataclysmes", w * 0.5f, h - 50f, p10);
            canvas.drawText("mise à jour automatique activée", w * 0.5f, h - 31f, p10);
            p10.setFakeBoldText(false);
            return;
        }

        if (voidFx > 0f) {
            float a = Math.max(0f, voidFx / 0.42f);
            float radius = (1f - a) * Math.min(w, h) * 0.48f + 55f;
            s10.setStrokeWidth(5f + a * 7f);
            s10.setColor(Color.argb((int) (210f * a), 170, 100, 255));
            canvas.drawCircle(w * 0.5f, h * 0.56f, radius, s10);
        }

        if (statusLife > 0f && !bool(fDead) && !bool(fChoosing)) {
            float alpha = Math.min(1f, statusLife * 1.4f);
            p10.setColor(Color.argb((int) (150f * alpha), 7, 11, 14));
            RectF r = new RectF(20f, h * 0.80f, w - 20f, h * 0.80f + 48f);
            canvas.drawRoundRect(r, 16f, 16f, p10);
            p10.setTextAlign(Paint.Align.CENTER);
            p10.setFakeBoldText(true);
            p10.setTextSize(13f);
            p10.setColor(Color.argb((int) (255f * alpha), 230, 240, 242));
            canvas.drawText(statusText, w * 0.5f, r.centerY() + 5f, p10);
            p10.setFakeBoldText(false);
        }
    }
}
