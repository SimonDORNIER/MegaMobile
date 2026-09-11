package com.megamobile.game;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Random;

public class GameView extends View implements Choreographer.FrameCallback {
    private static final int ENEMY_GRUNT = 0;
    private static final int ENEMY_FAST = 1;
    private static final int ENEMY_TANK = 2;
    private static final int ENEMY_SHOOTER = 3;
    private static final int ENEMY_BOSS = 4;

    private static final int SHOT_PLAYER = 0;
    private static final int SHOT_ROCKET = 1;
    private static final int SHOT_ENEMY = 2;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random random = new Random();
    private final RemoteConfig remote = new RemoteConfig();

    private final ArrayList<Enemy> enemies = new ArrayList<>();
    private final ArrayList<Shot> shots = new ArrayList<>();
    private final ArrayList<Gem> gems = new ArrayList<>();
    private final ArrayList<Chest> chests = new ArrayList<>();
    private final ArrayList<Particle> particles = new ArrayList<>();
    private final ArrayList<FloatingText> texts = new ArrayList<>();
    private final ArrayList<ArcFx> arcs = new ArrayList<>();

    private boolean running;
    private boolean paused;
    private boolean dead;
    private boolean choosing;
    private boolean autoChoice;
    private boolean soundOn = true;
    private long lastFrameNanos;

    private float elapsed;
    private float difficulty = 1f;
    private float spawnCd;
    private float bossCd;
    private int kills;
    private int score;
    private String banner = "";
    private float bannerLife;

    private float px;
    private float py;
    private float hp = 100f;
    private float maxHp = 100f;
    private float speed = 290f;
    private float damage = 20f;
    private float fireInterval = 0.52f;
    private float fireCd;
    private float range = 420f;
    private int multi = 1;
    private float crit = 0.06f;
    private float armor;
    private float regen;
    private float magnet = 170f;
    private int level = 1;
    private float xp;
    private float nextXp = 12f;

    private int auraLevel;
    private int orbitLevel;
    private int lightningLevel;
    private int rocketLevel;
    private float auraCd;
    private float lightningCd;
    private float rocketCd;
    private float dashCd;
    private float invuln;

    private float moveX;
    private float moveY;
    private float lastMoveX;
    private float lastMoveY = -1f;
    private float camX;
    private float camY;

    private int joystickPointer = -1;
    private float joyStartX;
    private float joyStartY;
    private float joyX;
    private float joyY;
    private final float joyRadius = 95f;

    private final ArrayList<Upgrade> currentChoices = new ArrayList<>();
    private boolean chestChoice;
    private final RectF[] choiceRects = {new RectF(), new RectF(), new RectF()};
    private final RectF dashRect = new RectF();
    private final RectF pauseRect = new RectF();
    private final RectF autoRect = new RectF();
    private final RectF resumeRect = new RectF();
    private final RectF restartRect = new RectF();
    private final RectF quitRect = new RectF();
    private final RectF soundRect = new RectF();

    public GameView(Context context) {
        super(context);
        setFocusable(true);
        setKeepScreenOn(true);
        paint.setTypeface(android.graphics.Typeface.create("sans", android.graphics.Typeface.NORMAL));
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(3f);
        stroke.setColor(Color.WHITE);
        resetGame();
        RemoteConfig.loadAsync(config -> post(() -> {
            remote.applyFrom(config);
            speed = Math.max(speed, remote.playerBaseSpeed);
            magnet = Math.max(magnet, remote.xpMagnet);
            showBanner("Contenu " + remote.contentVersion);
        }));
    }

    public void resumeGame() {
        if (running) return;
        running = true;
        lastFrameNanos = 0L;
        Choreographer.getInstance().postFrameCallback(this);
    }

    public void pauseGame() {
        running = false;
    }

    private void resetGame() {
        enemies.clear();
        shots.clear();
        gems.clear();
        chests.clear();
        particles.clear();
        texts.clear();
        arcs.clear();
        elapsed = 0f;
        difficulty = 1f;
        spawnCd = 0.2f;
        bossCd = 34f;
        kills = 0;
        score = 0;
        px = py = 0f;
        hp = maxHp = 100f;
        speed = remote.playerBaseSpeed;
        damage = 20f;
        fireInterval = 0.52f;
        fireCd = 0f;
        range = 420f;
        multi = 1;
        crit = 0.06f;
        armor = 0f;
        regen = 0f;
        magnet = remote.xpMagnet;
        level = 1;
        xp = 0f;
        nextXp = 12f;
        auraLevel = orbitLevel = lightningLevel = rocketLevel = 0;
        auraCd = lightningCd = rocketCd = 0f;
        dashCd = 0f;
        invuln = 0f;
        moveX = moveY = 0f;
        lastMoveX = 0f;
        lastMoveY = -1f;
        camX = camY = 0f;
        paused = false;
        dead = false;
        choosing = false;
        chestChoice = false;
        currentChoices.clear();
        banner = "SURVIS";
        bannerLife = 1.8f;
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        if (!running) return;
        if (lastFrameNanos == 0L) lastFrameNanos = frameTimeNanos;
        float dt = (frameTimeNanos - lastFrameNanos) / 1_000_000_000f;
        lastFrameNanos = frameTimeNanos;
        dt = Math.min(dt, 0.04f);
        if (!paused && !dead && !choosing) update(dt);
        updateVisuals(dt);
        invalidate();
        Choreographer.getInstance().postFrameCallback(this);
    }

    private void update(float dt) {
        elapsed += dt;
        difficulty = 1f + elapsed / Math.max(25f, remote.difficultySeconds)
                + (float) Math.pow(elapsed / 360f, 1.35) * 0.7f;
        if (bannerLife > 0f) bannerLife -= dt;
        if (invuln > 0f) invuln -= dt;
        if (dashCd > 0f) dashCd -= dt;

        if (regen > 0f && hp > 0f) hp = Math.min(maxHp, hp + regen * dt);

        float len = (float) Math.hypot(moveX, moveY);
        float nx = moveX;
        float ny = moveY;
        if (len > 1f) {
            nx /= len;
            ny /= len;
        }
        if (Math.abs(nx) + Math.abs(ny) > 0.02f) {
            lastMoveX = nx;
            lastMoveY = ny;
        }
        px += nx * speed * dt;
        py += ny * speed * dt;

        float desiredCamX = px + lastMoveX * 45f;
        float desiredCamY = py + lastMoveY * 75f;
        float cameraBlend = 1f - (float) Math.pow(0.001, dt);
        camX += (desiredCamX - camX) * cameraBlend;
        camY += (desiredCamY - camY) * cameraBlend;

        spawnCd -= dt;
        int dynamicCap = Math.min(remote.enemyCap, 48 + (int) (elapsed * 0.9f));
        if (spawnCd <= 0f && enemies.size() < dynamicCap) {
            int batch = difficulty > 5f ? 2 : 1;
            for (int i = 0; i < batch && enemies.size() < dynamicCap; i++) spawnEnemy(false);
            float speedup = Math.min(3.6f, 0.9f + (float) Math.sqrt(difficulty));
            spawnCd = Math.max(0.075f, remote.spawnInterval / speedup);
        }

        bossCd -= dt;
        if (bossCd <= 0f) {
            spawnEnemy(true);
            bossCd = Math.max(24f, remote.bossInterval / Math.min(1.8f, 0.85f + difficulty * 0.06f));
            showBanner("BOSS");
        }

        updateWeapons(dt);
        updateShots(dt);
        updateEnemies(dt);
        updateGems(dt);
        updateChests();

        if (hp <= 0f && !dead) {
            hp = 0f;
            dead = true;
            moveX = moveY = 0f;
            joystickPointer = -1;
        }
    }

    private void updateWeapons(float dt) {
        fireCd -= dt;
        if (fireCd <= 0f) {
            fireBasicWeapon();
            fireCd = fireInterval;
        }

        if (auraLevel > 0) {
            auraCd -= dt;
            if (auraCd <= 0f) {
                float radius = 115f + auraLevel * 16f;
                float dmg = damage * (0.28f + auraLevel * 0.07f);
                for (int i = enemies.size() - 1; i >= 0; i--) {
                    Enemy e = enemies.get(i);
                    if (distanceSq(px, py, e.x, e.y) <= radius * radius) damageEnemy(e, dmg, false);
                }
                auraCd = Math.max(0.18f, 0.48f - auraLevel * 0.025f);
            }
        }

        if (orbitLevel > 0) {
            int blades = Math.min(8, 2 + orbitLevel);
            float orbitRadius = 88f + orbitLevel * 4f;
            float baseAngle = elapsed * (2.3f + orbitLevel * 0.1f);
            for (Enemy e : new ArrayList<>(enemies)) {
                e.orbitHitCd -= dt;
                if (e.orbitHitCd > 0f) continue;
                for (int i = 0; i < blades; i++) {
                    float a = baseAngle + (float) (Math.PI * 2.0 * i / blades);
                    float bx = px + (float) Math.cos(a) * orbitRadius;
                    float by = py + (float) Math.sin(a) * orbitRadius;
                    float rr = e.r + 15f;
                    if (distanceSq(bx, by, e.x, e.y) < rr * rr) {
                        damageEnemy(e, damage * (0.45f + orbitLevel * 0.09f), false);
                        e.orbitHitCd = 0.24f;
                        break;
                    }
                }
            }
        }

        if (lightningLevel > 0) {
            lightningCd -= dt;
            if (lightningCd <= 0f) {
                fireLightning();
                lightningCd = Math.max(0.65f, 2.5f - lightningLevel * 0.22f);
            }
        }

        if (rocketLevel > 0) {
            rocketCd -= dt;
            if (rocketCd <= 0f) {
                Enemy target = nearestEnemy(px, py, 900f);
                if (target != null) {
                    float dx = target.x - px;
                    float dy = target.y - py;
                    float l = Math.max(1f, (float) Math.hypot(dx, dy));
                    Shot s = new Shot(SHOT_ROCKET, px, py, dx / l * 330f, dy / l * 330f,
                            8f, 3.2f, damage * (1.8f + rocketLevel * 0.35f));
                    s.target = target;
                    shots.add(s);
                }
                rocketCd = Math.max(0.75f, 3.3f - rocketLevel * 0.28f);
            }
        }
    }

    private void fireBasicWeapon() {
        Enemy target = nearestEnemy(px, py, range);
        if (target == null) return;
        float base = (float) Math.atan2(target.y - py, target.x - px);
        float spread = 0.12f;
        for (int i = 0; i < multi; i++) {
            float offset = (i - (multi - 1) * 0.5f) * spread;
            float a = base + offset;
            float dmg = damage * (random.nextFloat() < crit ? 2f : 1f);
            shots.add(new Shot(SHOT_PLAYER, px, py,
                    (float) Math.cos(a) * 650f,
                    (float) Math.sin(a) * 650f,
                    7f, 1.6f, dmg));
        }
    }

    private void fireLightning() {
        ArrayList<Enemy> candidates = new ArrayList<>(enemies);
        Collections.sort(candidates, Comparator.comparingDouble(e -> distanceSq(px, py, e.x, e.y)));
        float fromX = px;
        float fromY = py;
        int count = Math.min(candidates.size(), 2 + lightningLevel);
        float dmg = damage * (1.0f + lightningLevel * 0.18f);
        for (int i = 0; i < count; i++) {
            Enemy e = candidates.get(i);
            if (i == 0 && distanceSq(px, py, e.x, e.y) > range * range * 1.8f) break;
            arcs.add(new ArcFx(fromX, fromY, e.x, e.y, 0.13f));
            damageEnemy(e, dmg, true);
            fromX = e.x;
            fromY = e.y;
        }
    }

    private void updateShots(float dt) {
        for (int i = shots.size() - 1; i >= 0; i--) {
            Shot s = shots.get(i);
            s.life -= dt;
            if (s.type == SHOT_ROCKET && s.target != null && s.target.hp > 0f) {
                float dx = s.target.x - s.x;
                float dy = s.target.y - s.y;
                float l = Math.max(1f, (float) Math.hypot(dx, dy));
                float desiredVx = dx / l * 420f;
                float desiredVy = dy / l * 420f;
                s.vx += (desiredVx - s.vx) * Math.min(1f, dt * 5.5f);
                s.vy += (desiredVy - s.vy) * Math.min(1f, dt * 5.5f);
            }
            s.x += s.vx * dt;
            s.y += s.vy * dt;

            boolean remove = s.life <= 0f;
            if (!remove && s.type == SHOT_ENEMY) {
                float rr = s.r + 18f;
                if (distanceSq(s.x, s.y, px, py) < rr * rr) {
                    takeDamage(s.damage);
                    burst(s.x, s.y, Color.MAGENTA, 7);
                    remove = true;
                }
            } else if (!remove) {
                for (int j = enemies.size() - 1; j >= 0; j--) {
                    Enemy e = enemies.get(j);
                    float rr = s.r + e.r;
                    if (distanceSq(s.x, s.y, e.x, e.y) < rr * rr) {
                        if (s.type == SHOT_ROCKET) {
                            explodeRocket(s.x, s.y, s.damage);
                        } else {
                            damageEnemy(e, s.damage, false);
                            burst(s.x, s.y, Color.rgb(255, 220, 90), 4);
                        }
                        remove = true;
                        break;
                    }
                }
            }
            if (remove) shots.remove(i);
        }
    }

    private void explodeRocket(float x, float y, float dmg) {
        float radius = 105f + rocketLevel * 12f;
        for (Enemy e : new ArrayList<>(enemies)) {
            if (distanceSq(x, y, e.x, e.y) < radius * radius) damageEnemy(e, dmg, false);
        }
        burst(x, y, Color.rgb(255, 125, 40), 22);
    }

    private void updateEnemies(float dt) {
        for (int i = enemies.size() - 1; i >= 0; i--) {
            Enemy e = enemies.get(i);
            if (e.hp <= 0f) continue;
            e.flash = Math.max(0f, e.flash - dt);
            float dx = px - e.x;
            float dy = py - e.y;
            float l = Math.max(1f, (float) Math.hypot(dx, dy));

            if (e.type == ENEMY_SHOOTER) {
                float desired = 360f;
                float dir = l > desired ? 1f : (l < 250f ? -0.6f : 0f);
                e.x += dx / l * e.speed * dir * dt;
                e.y += dy / l * e.speed * dir * dt;
                e.shootCd -= dt;
                if (e.shootCd <= 0f && l < 780f) {
                    float projectileSpeed = 250f + Math.min(180f, difficulty * 12f);
                    shots.add(new Shot(SHOT_ENEMY, e.x, e.y, dx / l * projectileSpeed, dy / l * projectileSpeed,
                            8f, 4f, e.damage));
                    e.shootCd = Math.max(0.85f, 2.6f - difficulty * 0.08f);
                }
            } else {
                e.x += dx / l * e.speed * dt;
                e.y += dy / l * e.speed * dt;
            }

            float rr = e.r + 17f;
            if (l < rr && invuln <= 0f) {
                takeDamage(e.damage * dt * 1.9f);
                float push = 28f * dt;
                e.x -= dx / l * push;
                e.y -= dy / l * push;
            }
        }
    }

    private void updateGems(float dt) {
        for (int i = gems.size() - 1; i >= 0; i--) {
            Gem g = gems.get(i);
            float dx = px - g.x;
            float dy = py - g.y;
            float l = Math.max(1f, (float) Math.hypot(dx, dy));
            if (l < magnet) {
                float pull = 360f + (magnet - l) * 3.5f;
                g.x += dx / l * pull * dt;
                g.y += dy / l * pull * dt;
            }
            if (l < 26f) {
                gainXp(g.value);
                gems.remove(i);
            }
        }
    }

    private void updateChests() {
        if (choosing) return;
        for (int i = chests.size() - 1; i >= 0; i--) {
            Chest chest = chests.get(i);
            if (distanceSq(px, py, chest.x, chest.y) < 52f * 52f) {
                chests.remove(i);
                startChoice(true);
                break;
            }
        }
    }

    private void updateVisuals(float dt) {
        for (int i = particles.size() - 1; i >= 0; i--) {
            Particle p = particles.get(i);
            p.life -= dt;
            p.x += p.vx * dt;
            p.y += p.vy * dt;
            p.vx *= Math.pow(0.05, dt);
            p.vy *= Math.pow(0.05, dt);
            if (p.life <= 0f) particles.remove(i);
        }
        for (int i = texts.size() - 1; i >= 0; i--) {
            FloatingText t = texts.get(i);
            t.life -= dt;
            t.y -= 42f * dt;
            if (t.life <= 0f) texts.remove(i);
        }
        for (int i = arcs.size() - 1; i >= 0; i--) {
            ArcFx a = arcs.get(i);
            a.life -= dt;
            if (a.life <= 0f) arcs.remove(i);
        }
        if (bannerLife > 0f && (paused || dead || choosing)) bannerLife -= dt;
    }

    private void spawnEnemy(boolean boss) {
        float screenRadius = Math.max(getWidth(), getHeight()) * 0.72f + 110f;
        float angle = random.nextFloat() * (float) (Math.PI * 2.0);
        float x = px + (float) Math.cos(angle) * screenRadius;
        float y = py + (float) Math.sin(angle) * screenRadius;

        int type;
        if (boss) type = ENEMY_BOSS;
        else {
            float r = random.nextFloat();
            if (elapsed > 95f && r < 0.13f) type = ENEMY_SHOOTER;
            else if (elapsed > 55f && r < 0.28f) type = ENEMY_TANK;
            else if (elapsed > 20f && r < 0.49f) type = ENEMY_FAST;
            else type = ENEMY_GRUNT;
        }

        float baseHp;
        float baseSpeed;
        float baseDamage;
        float radius;
        switch (type) {
            case ENEMY_FAST:
                baseHp = 24f; baseSpeed = 132f; baseDamage = 9f; radius = 13f; break;
            case ENEMY_TANK:
                baseHp = 105f; baseSpeed = 50f; baseDamage = 14f; radius = 25f; break;
            case ENEMY_SHOOTER:
                baseHp = 58f; baseSpeed = 70f; baseDamage = 11f; radius = 18f; break;
            case ENEMY_BOSS:
                baseHp = 800f + elapsed * 4f; baseSpeed = 48f; baseDamage = 25f; radius = 48f; break;
            default:
                baseHp = 42f; baseSpeed = 79f; baseDamage = 10f; radius = 18f;
        }
        float hpScale = (float) Math.pow(difficulty, type == ENEMY_BOSS ? 1.32 : 1.12);
        float speedScale = 1f + Math.min(0.85f, (difficulty - 1f) * 0.055f);
        float damageScale = 1f + (difficulty - 1f) * 0.14f;
        Enemy e = new Enemy(type, x, y, radius, baseHp * hpScale, baseSpeed * speedScale, baseDamage * damageScale);
        e.shootCd = 0.5f + random.nextFloat() * 1.6f;
        enemies.add(e);
    }

    private void damageEnemy(Enemy e, float amount, boolean lightning) {
        if (e.hp <= 0f) return;
        boolean critHit = !lightning && random.nextFloat() < crit * 0.15f;
        float actual = amount * (critHit ? 1.65f : 1f);
        e.hp -= actual;
        e.flash = 0.08f;
        texts.add(new FloatingText(e.x, e.y - e.r - 10f, Math.round(actual), critHit ? Color.YELLOW : Color.WHITE));
        if (e.hp <= 0f) killEnemy(e);
    }

    private void killEnemy(Enemy e) {
        if (!enemies.remove(e)) return;
        kills++;
        int value = e.type == ENEMY_BOSS ? 28 : (e.type == ENEMY_TANK ? 3 : e.type == ENEMY_SHOOTER ? 2 : 1);
        score += value * 10 + Math.round(difficulty);
        gems.add(new Gem(e.x, e.y, value));
        burst(e.x, e.y, colorForEnemy(e.type), e.type == ENEMY_BOSS ? 34 : 10);
        if (e.type == ENEMY_BOSS) {
            chests.add(new Chest(e.x, e.y));
            showBanner("COFFRE !");
        }
    }

    private void takeDamage(float amount) {
        if (invuln > 0f) return;
        float reduction = Math.min(0.72f, armor / (armor + 75f));
        hp -= amount * (1f - reduction);
        if (amount > 2f) invuln = 0.08f;
    }

    private void gainXp(float amount) {
        xp += amount;
        while (xp >= nextXp && !choosing) {
            xp -= nextXp;
            level++;
            nextXp = 12f + (float) Math.pow(level, 1.32) * 6.4f;
            startChoice(false);
        }
    }

    private void startChoice(boolean fromChest) {
        choosing = true;
        chestChoice = fromChest;
        moveX = moveY = 0f;
        joystickPointer = -1;
        currentChoices.clear();
        for (int i = 0; i < 3; i++) {
            Upgrade candidate;
            int guard = 0;
            do {
                candidate = randomUpgrade(fromChest);
                guard++;
            } while (containsCode(candidate.code) && guard < 30);
            currentChoices.add(candidate);
        }
        if (autoChoice) {
            postDelayed(() -> {
                if (choosing && !currentChoices.isEmpty()) {
                    Upgrade best = currentChoices.get(0);
                    for (Upgrade u : currentChoices) if (u.rarity > best.rarity) best = u;
                    applyUpgrade(best);
                }
            }, 220L);
        }
    }

    private boolean containsCode(String code) {
        for (Upgrade u : currentChoices) if (u.code.equals(code)) return true;
        return false;
    }

    private Upgrade randomUpgrade(boolean chest) {
        int rarity = rollRarity(chest);
        String[] base = {"DMG", "FIRE", "SPEED", "HP", "MAGNET", "CRIT", "ARMOR", "REGEN", "RANGE", "MULTI"};
        ArrayList<String> codes = new ArrayList<>();
        Collections.addAll(codes, base);
        codes.add("AURA");
        codes.add("ORBIT");
        codes.add("LIGHTNING");
        codes.add("ROCKET");
        codes.add("HEAL");
        if (chest) {
            codes.add("MULTI");
            codes.add("AURA");
            codes.add("ORBIT");
            codes.add("LIGHTNING");
            codes.add("ROCKET");
        }
        String code = codes.get(random.nextInt(codes.size()));
        return new Upgrade(code, rarity, titleFor(code), descriptionFor(code, rarity));
    }

    private int rollRarity(boolean chest) {
        float r = random.nextFloat();
        if (chest) {
            if (r < 0.08f) return 3;
            if (r < 0.33f) return 2;
            if (r < 0.78f) return 1;
            return 0;
        }
        if (r < 0.025f) return 3;
        if (r < 0.12f) return 2;
        if (r < 0.39f) return 1;
        return 0;
    }

    private String titleFor(String code) {
        switch (code) {
            case "DMG": return "PUISSANCE";
            case "FIRE": return "FRÉNÉSIE";
            case "SPEED": return "VÉLOCITÉ";
            case "HP": return "VITALITÉ";
            case "MAGNET": return "ATTRACTION";
            case "CRIT": return "PRÉCISION";
            case "ARMOR": return "ARMURE";
            case "REGEN": return "RÉGÉNÉRATION";
            case "RANGE": return "PORTÉE";
            case "MULTI": return "TIR MULTIPLE";
            case "AURA": return auraLevel == 0 ? "NOUVELLE ARME : AURA" : "AURA +";
            case "ORBIT": return orbitLevel == 0 ? "NOUVELLE ARME : ORBITALES" : "ORBITALES +";
            case "LIGHTNING": return lightningLevel == 0 ? "NOUVELLE ARME : FOUDRE" : "FOUDRE +";
            case "ROCKET": return rocketLevel == 0 ? "NOUVELLE ARME : ROQUETTES" : "ROQUETTES +";
            case "HEAL": return "SOINS";
            default: return code;
        }
    }

    private String descriptionFor(String code, int rarity) {
        float m = rarityMultiplier(rarity);
        switch (code) {
            case "DMG": return "+" + Math.round(18f * m) + "% dégâts";
            case "FIRE": return "+" + Math.round(13f * m) + "% cadence";
            case "SPEED": return "+" + Math.round(10f * m) + "% déplacement";
            case "HP": return "+" + Math.round(18f * m) + " PV max + soin";
            case "MAGNET": return "+" + Math.round(40f * m) + " portée d'aimant";
            case "CRIT": return "+" + Math.round(5f * m) + "% critique";
            case "ARMOR": return "+" + Math.round(9f * m) + " armure";
            case "REGEN": return "+" + oneDecimal(0.45f * m) + " PV/s";
            case "RANGE": return "+" + Math.round(12f * m) + "% portée";
            case "MULTI": return "+" + (rarity >= 2 ? 2 : 1) + " projectile";
            case "AURA": return "Dégâts continus autour de toi";
            case "ORBIT": return "Lames qui tournent autour de toi";
            case "LIGHTNING": return "Éclairs en chaîne automatiques";
            case "ROCKET": return "Roquettes chercheuses explosives";
            case "HEAL": return "Récupère " + Math.round(28f * m) + "% des PV";
            default: return "Amélioration";
        }
    }

    private String oneDecimal(float value) {
        return String.format(Locale.US, "%.1f", value);
    }

    private float rarityMultiplier(int rarity) {
        switch (rarity) {
            case 1: return 1.35f;
            case 2: return 1.85f;
            case 3: return 2.65f;
            default: return 1f;
        }
    }

    private void applyUpgrade(Upgrade u) {
        float m = rarityMultiplier(u.rarity);
        switch (u.code) {
            case "DMG": damage *= 1f + 0.18f * m; break;
            case "FIRE": fireInterval = Math.max(0.095f, fireInterval / (1f + 0.13f * m)); break;
            case "SPEED": speed *= 1f + 0.10f * m; break;
            case "HP":
                float add = 18f * m;
                maxHp += add;
                hp = Math.min(maxHp, hp + add);
                break;
            case "MAGNET": magnet += 40f * m; break;
            case "CRIT": crit = Math.min(0.78f, crit + 0.05f * m); break;
            case "ARMOR": armor += 9f * m; break;
            case "REGEN": regen += 0.45f * m; break;
            case "RANGE": range *= 1f + 0.12f * m; break;
            case "MULTI": multi = Math.min(10, multi + (u.rarity >= 2 ? 2 : 1)); break;
            case "AURA": auraLevel += 1 + (u.rarity >= 3 ? 1 : 0); break;
            case "ORBIT": orbitLevel += 1 + (u.rarity >= 3 ? 1 : 0); break;
            case "LIGHTNING": lightningLevel += 1 + (u.rarity >= 3 ? 1 : 0); break;
            case "ROCKET": rocketLevel += 1 + (u.rarity >= 3 ? 1 : 0); break;
            case "HEAL": hp = Math.min(maxHp, hp + maxHp * Math.min(1f, 0.28f * m)); break;
        }
        choosing = false;
        chestChoice = false;
        currentChoices.clear();
        showBanner(u.title);
    }

    private void performDash() {
        if (dashCd > 0f || paused || dead || choosing) return;
        float dx = moveX;
        float dy = moveY;
        float l = (float) Math.hypot(dx, dy);
        if (l < 0.1f) {
            dx = lastMoveX;
            dy = lastMoveY;
            l = Math.max(0.1f, (float) Math.hypot(dx, dy));
        }
        dx /= l;
        dy /= l;
        float dist = remote.dashDistance;
        float startX = px;
        float startY = py;
        px += dx * dist;
        py += dy * dist;
        invuln = 0.38f;
        dashCd = remote.dashCooldown;
        arcs.add(new ArcFx(startX, startY, px, py, 0.18f));
        burst(px, py, Color.CYAN, 14);
    }

    private Enemy nearestEnemy(float x, float y, float maxDistance) {
        Enemy best = null;
        float bestD = maxDistance * maxDistance;
        for (Enemy e : enemies) {
            if (e.hp <= 0f) continue;
            float d = distanceSq(x, y, e.x, e.y);
            if (d < bestD) {
                bestD = d;
                best = e;
            }
        }
        return best;
    }

    private void burst(float x, float y, int color, int count) {
        for (int i = 0; i < count; i++) {
            float a = random.nextFloat() * (float) (Math.PI * 2.0);
            float sp = 55f + random.nextFloat() * 180f;
            particles.add(new Particle(x, y, (float) Math.cos(a) * sp, (float) Math.sin(a) * sp,
                    0.25f + random.nextFloat() * 0.45f, color, 3f + random.nextFloat() * 8f));
        }
    }

    private void showBanner(String text) {
        banner = text;
        bannerLife = 1.4f;
    }

    private float distanceSq(float ax, float ay, float bx, float by) {
        float dx = ax - bx;
        float dy = ay - by;
        return dx * dx + dy * dy;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        drawBackground(canvas, w, h);
        canvas.save();
        float anchorX = w * 0.5f;
        float anchorY = h * 0.56f;
        canvas.translate(anchorX - camX, anchorY - camY);
        drawWorld(canvas);
        canvas.restore();
        drawHud(canvas, w, h);
        if (choosing) drawChoices(canvas, w, h);
        if (paused && !dead && !choosing) drawPause(canvas, w, h);
        if (dead) drawDeath(canvas, w, h);
        if (bannerLife > 0f && !dead && !choosing) drawBanner(canvas, w, h);
    }

    private void drawBackground(Canvas c, int w, int h) {
        c.drawColor(Color.rgb(17, 26, 29));
        paint.setColor(Color.rgb(22, 34, 36));
        float tile = 88f;
        float ox = ((-camX + w * 0.5f) % tile + tile) % tile;
        float oy = ((-camY + h * 0.56f) % tile + tile) % tile;
        for (float x = ox; x < w; x += tile) c.drawRect(x, 0, x + 1.5f, h, paint);
        for (float y = oy; y < h; y += tile) c.drawRect(0, y, w, y + 1.5f, paint);

        paint.setColor(Color.rgb(30, 46, 47));
        for (int i = 0; i < 15; i++) {
            float x = (i * 137f + ox * 0.31f) % Math.max(1, w);
            float y = (i * 233f + oy * 0.47f) % Math.max(1, h);
            c.drawCircle(x, y, 3f + i % 4, paint);
        }
    }

    private void drawWorld(Canvas c) {
        for (Gem g : gems) drawGem(c, g);
        for (Chest chest : chests) drawChest(c, chest);
        for (Shot s : shots) drawShot(c, s);
        for (Enemy e : enemies) drawEnemy(c, e);
        drawWeapons(c);
        drawPlayer(c);
        for (Particle p : particles) {
            paint.setColor(withAlpha(p.color, (int) (255f * Math.max(0f, p.life / p.maxLife))));
            c.drawCircle(p.x, p.y, p.size, paint);
        }
        for (ArcFx a : arcs) drawArc(c, a);
        for (FloatingText t : texts) {
            paint.setColor(withAlpha(t.color, (int) (255f * Math.max(0f, t.life / 0.75f))));
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(24f);
            paint.setFakeBoldText(true);
            c.drawText(String.valueOf(t.value), t.x, t.y, paint);
            paint.setFakeBoldText(false);
        }
    }

    private void drawPlayer(Canvas c) {
        paint.setColor(Color.argb(95, 0, 0, 0));
        c.drawOval(px - 19f, py + 11f, px + 19f, py + 25f, paint);
        int col = invuln > 0f && ((int) (elapsed * 24f) % 2 == 0) ? Color.WHITE : Color.rgb(80, 225, 155);
        paint.setColor(col);
        c.drawCircle(px, py, 18f, paint);
        stroke.setColor(Color.WHITE);
        stroke.setStrokeWidth(3f);
        c.drawCircle(px, py, 18f, stroke);
        paint.setColor(Color.rgb(12, 39, 34));
        float eyeX = px + lastMoveX * 7f;
        float eyeY = py + lastMoveY * 7f;
        c.drawCircle(eyeX, eyeY, 4f, paint);
    }

    private void drawEnemy(Canvas c, Enemy e) {
        paint.setColor(Color.argb(95, 0, 0, 0));
        c.drawOval(e.x - e.r, e.y + e.r * 0.45f, e.x + e.r, e.y + e.r * 1.05f, paint);
        int color = e.flash > 0f ? Color.WHITE : colorForEnemy(e.type);
        paint.setColor(color);
        switch (e.type) {
            case ENEMY_FAST:
                c.save();
                c.rotate(45f, e.x, e.y);
                c.drawRect(e.x - e.r * 0.72f, e.y - e.r * 0.72f, e.x + e.r * 0.72f, e.y + e.r * 0.72f, paint);
                c.restore();
                break;
            case ENEMY_TANK:
                c.drawRoundRect(e.x - e.r, e.y - e.r, e.x + e.r, e.y + e.r, 8f, 8f, paint);
                break;
            case ENEMY_SHOOTER:
                c.drawCircle(e.x, e.y, e.r, paint);
                stroke.setColor(Color.rgb(255, 170, 255));
                c.drawCircle(e.x, e.y, e.r + 5f, stroke);
                break;
            case ENEMY_BOSS:
                c.drawCircle(e.x, e.y, e.r, paint);
                stroke.setColor(Color.rgb(255, 215, 70));
                stroke.setStrokeWidth(5f);
                c.drawCircle(e.x, e.y, e.r + 6f, stroke);
                paint.setColor(Color.rgb(255, 215, 70));
                for (int i = 0; i < 6; i++) {
                    float a = (float) (i * Math.PI / 3.0 + elapsed * 0.4);
                    c.drawCircle(e.x + (float) Math.cos(a) * (e.r + 12f), e.y + (float) Math.sin(a) * (e.r + 12f), 5f, paint);
                }
                break;
            default:
                c.drawCircle(e.x, e.y, e.r, paint);
        }
        if (e.type == ENEMY_TANK || e.type == ENEMY_SHOOTER || e.type == ENEMY_BOSS) {
            float bw = e.type == ENEMY_BOSS ? 100f : e.r * 2f;
            float ratio = Math.max(0f, e.hp / e.maxHp);
            paint.setColor(Color.argb(150, 0, 0, 0));
            c.drawRoundRect(e.x - bw / 2f, e.y - e.r - 14f, e.x + bw / 2f, e.y - e.r - 8f, 3f, 3f, paint);
            paint.setColor(Color.rgb(100, 230, 100));
            c.drawRoundRect(e.x - bw / 2f, e.y - e.r - 14f, e.x - bw / 2f + bw * ratio, e.y - e.r - 8f, 3f, 3f, paint);
        }
    }

    private int colorForEnemy(int type) {
        switch (type) {
            case ENEMY_FAST: return Color.rgb(255, 174, 70);
            case ENEMY_TANK: return Color.rgb(93, 127, 170);
            case ENEMY_SHOOTER: return Color.rgb(210, 85, 220);
            case ENEMY_BOSS: return Color.rgb(186, 70, 78);
            default: return Color.rgb(235, 82, 82);
        }
    }

    private void drawGem(Canvas c, Gem g) {
        paint.setColor(g.value >= 20 ? Color.rgb(255, 210, 65) : (g.value >= 3 ? Color.rgb(160, 100, 255) : Color.rgb(70, 180, 255)));
        c.save();
        c.rotate(45f + elapsed * 35f, g.x, g.y);
        float s = g.value >= 20 ? 10f : 7f;
        c.drawRect(g.x - s, g.y - s, g.x + s, g.y + s, paint);
        c.restore();
    }

    private void drawChest(Canvas c, Chest chest) {
        float pulse = 1f + (float) Math.sin(elapsed * 5f) * 0.06f;
        paint.setColor(Color.argb(80, 255, 210, 40));
        c.drawCircle(chest.x, chest.y, 42f * pulse, paint);
        paint.setColor(Color.rgb(132, 75, 30));
        c.drawRoundRect(chest.x - 24f, chest.y - 18f, chest.x + 24f, chest.y + 20f, 7f, 7f, paint);
        paint.setColor(Color.rgb(255, 205, 55));
        c.drawRect(chest.x - 24f, chest.y - 5f, chest.x + 24f, chest.y + 3f, paint);
        c.drawCircle(chest.x, chest.y + 5f, 5f, paint);
    }

    private void drawShot(Canvas c, Shot s) {
        if (s.type == SHOT_ENEMY) {
            paint.setColor(Color.rgb(245, 90, 255));
            c.drawCircle(s.x, s.y, s.r + 3f, paint);
            paint.setColor(Color.WHITE);
            c.drawCircle(s.x, s.y, s.r * 0.45f, paint);
        } else if (s.type == SHOT_ROCKET) {
            paint.setColor(Color.rgb(255, 120, 45));
            c.drawCircle(s.x, s.y, s.r + 3f, paint);
            paint.setColor(Color.rgb(255, 225, 150));
            c.drawCircle(s.x, s.y, s.r * 0.5f, paint);
        } else {
            paint.setColor(Color.rgb(255, 231, 90));
            c.drawCircle(s.x, s.y, s.r, paint);
        }
    }

    private void drawWeapons(Canvas c) {
        if (auraLevel > 0) {
            float r = 115f + auraLevel * 16f;
            stroke.setColor(Color.argb(105, 75, 220, 255));
            stroke.setStrokeWidth(5f + auraLevel);
            c.drawCircle(px, py, r, stroke);
        }
        if (orbitLevel > 0) {
            int blades = Math.min(8, 2 + orbitLevel);
            float orbitRadius = 88f + orbitLevel * 4f;
            float baseAngle = elapsed * (2.3f + orbitLevel * 0.1f);
            paint.setColor(Color.rgb(205, 235, 255));
            for (int i = 0; i < blades; i++) {
                float a = baseAngle + (float) (Math.PI * 2.0 * i / blades);
                float bx = px + (float) Math.cos(a) * orbitRadius;
                float by = py + (float) Math.sin(a) * orbitRadius;
                c.save();
                c.rotate((float) Math.toDegrees(a) + 45f, bx, by);
                c.drawRoundRect(bx - 5f, by - 17f, bx + 5f, by + 17f, 4f, 4f, paint);
                c.restore();
            }
        }
    }

    private void drawArc(Canvas c, ArcFx a) {
        float alpha = Math.max(0f, a.life / a.maxLife);
        stroke.setColor(Color.argb((int) (220f * alpha), 120, 230, 255));
        stroke.setStrokeWidth(4f);
        c.drawLine(a.x1, a.y1, a.x2, a.y2, stroke);
        paint.setColor(Color.argb((int) (100f * alpha), 150, 235, 255));
        c.drawCircle(a.x2, a.y2, 13f, paint);
    }

    private void drawHud(Canvas c, int w, int h) {
        float top = 28f;
        paint.setTextAlign(Paint.Align.LEFT);
        paint.setFakeBoldText(true);
        paint.setColor(Color.WHITE);
        paint.setTextSize(29f);
        int minutes = (int) elapsed / 60;
        int seconds = (int) elapsed % 60;
        c.drawText(String.format(Locale.US, "%02d:%02d", minutes, seconds), 24f, top + 28f, paint);
        paint.setTextSize(17f);
        paint.setColor(Color.rgb(190, 210, 215));
        c.drawText("Difficulté ×" + oneDecimal(difficulty), 25f, top + 52f, paint);

        pauseRect.set(w - 70f, 22f, w - 16f, 76f);
        paint.setColor(Color.argb(165, 20, 28, 31));
        c.drawRoundRect(pauseRect, 16f, 16f, paint);
        paint.setColor(Color.WHITE);
        c.drawRect(w - 54f, 37f, w - 48f, 61f, paint);
        c.drawRect(w - 39f, 37f, w - 33f, 61f, paint);

        float hpW = w - 48f;
        float hpY = 92f;
        paint.setColor(Color.argb(175, 0, 0, 0));
        c.drawRoundRect(24f, hpY, 24f + hpW, hpY + 18f, 9f, 9f, paint);
        paint.setColor(Color.rgb(235, 72, 72));
        c.drawRoundRect(24f, hpY, 24f + hpW * Math.max(0f, hp / maxHp), hpY + 18f, 9f, 9f, paint);
        paint.setTextSize(14f);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setColor(Color.WHITE);
        c.drawText(Math.round(hp) + " / " + Math.round(maxHp), w / 2f, hpY + 15f, paint);

        float xpY = hpY + 26f;
        paint.setColor(Color.argb(175, 0, 0, 0));
        c.drawRoundRect(24f, xpY, 24f + hpW, xpY + 11f, 6f, 6f, paint);
        paint.setColor(Color.rgb(65, 175, 255));
        c.drawRoundRect(24f, xpY, 24f + hpW * Math.min(1f, xp / nextXp), xpY + 11f, 6f, 6f, paint);

        paint.setTextAlign(Paint.Align.LEFT);
        paint.setTextSize(17f);
        paint.setColor(Color.WHITE);
        c.drawText("Niv. " + level, 25f, xpY + 35f, paint);
        c.drawText("☠ " + kills, 112f, xpY + 35f, paint);
        c.drawText("Score " + score, 195f, xpY + 35f, paint);

        autoRect.set(w - 112f, xpY + 16f, w - 18f, xpY + 48f);
        paint.setColor(autoChoice ? Color.rgb(53, 150, 95) : Color.argb(160, 30, 40, 43));
        c.drawRoundRect(autoRect, 10f, 10f, paint);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(14f);
        c.drawText(autoChoice ? "AUTO ✓" : "AUTO", autoRect.centerX(), autoRect.centerY() + 5f, paint);

        float buttonR = Math.min(64f, w * 0.12f);
        float bx = w - buttonR - 26f;
        float by = h - buttonR - 38f;
        dashRect.set(bx - buttonR, by - buttonR, bx + buttonR, by + buttonR);
        float ready = remote.dashCooldown <= 0f ? 1f : 1f - Math.max(0f, dashCd) / Math.max(0.1f, remote.dashCooldown);
        paint.setColor(dashCd <= 0f ? Color.argb(210, 55, 170, 210) : Color.argb(170, 38, 65, 74));
        c.drawCircle(bx, by, buttonR, paint);
        stroke.setColor(Color.WHITE);
        stroke.setStrokeWidth(3f);
        c.drawCircle(bx, by, buttonR, stroke);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(20f);
        paint.setColor(Color.WHITE);
        c.drawText(dashCd <= 0f ? "DASH" : oneDecimal(Math.max(0f, dashCd)), bx, by + 7f, paint);
        if (dashCd > 0f) {
            stroke.setColor(Color.argb(160, 120, 220, 255));
            stroke.setStrokeWidth(7f);
            RectF arc = new RectF(bx - buttonR + 7f, by - buttonR + 7f, bx + buttonR - 7f, by + buttonR - 7f);
            c.drawArc(arc, -90f, 360f * ready, false, stroke);
        }

        if (joystickPointer >= 0) {
            paint.setColor(Color.argb(65, 255, 255, 255));
            c.drawCircle(joyStartX, joyStartY, joyRadius, paint);
            stroke.setColor(Color.argb(150, 255, 255, 255));
            stroke.setStrokeWidth(3f);
            c.drawCircle(joyStartX, joyStartY, joyRadius, stroke);
            paint.setColor(Color.argb(145, 255, 255, 255));
            c.drawCircle(joyStartX + joyX * joyRadius, joyStartY + joyY * joyRadius, 36f, paint);
        } else {
            float jx = 94f;
            float jy = h - 104f;
            paint.setColor(Color.argb(24, 255, 255, 255));
            c.drawCircle(jx, jy, 66f, paint);
            stroke.setColor(Color.argb(70, 255, 255, 255));
            stroke.setStrokeWidth(2f);
            c.drawCircle(jx, jy, 66f, stroke);
        }
        paint.setFakeBoldText(false);
    }

    private void drawChoices(Canvas c, int w, int h) {
        paint.setColor(Color.argb(220, 7, 11, 13));
        c.drawRect(0, 0, w, h, paint);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setFakeBoldText(true);
        paint.setColor(Color.WHITE);
        paint.setTextSize(31f);
        c.drawText(chestChoice ? "COFFRE" : "NIVEAU " + level, w / 2f, 90f, paint);
        paint.setTextSize(17f);
        paint.setColor(Color.rgb(180, 200, 205));
        c.drawText("Choisis une amélioration", w / 2f, 120f, paint);

        float margin = 22f;
        float cardH = Math.min(165f, (h - 205f) / 3f - 10f);
        float startY = 155f;
        for (int i = 0; i < currentChoices.size() && i < 3; i++) {
            Upgrade u = currentChoices.get(i);
            float y = startY + i * (cardH + 12f);
            choiceRects[i].set(margin, y, w - margin, y + cardH);
            paint.setColor(cardColor(u.rarity));
            c.drawRoundRect(choiceRects[i], 24f, 24f, paint);
            stroke.setColor(rarityColor(u.rarity));
            stroke.setStrokeWidth(4f);
            c.drawRoundRect(choiceRects[i], 24f, 24f, stroke);
            paint.setTextAlign(Paint.Align.LEFT);
            paint.setColor(rarityColor(u.rarity));
            paint.setTextSize(14f);
            c.drawText(rarityName(u.rarity), margin + 20f, y + 28f, paint);
            paint.setColor(Color.WHITE);
            paint.setTextSize(22f);
            c.drawText(u.title, margin + 20f, y + 61f, paint);
            paint.setFakeBoldText(false);
            paint.setColor(Color.rgb(214, 224, 226));
            paint.setTextSize(16f);
            c.drawText(u.description, margin + 20f, y + 91f, paint);
            paint.setFakeBoldText(true);
            paint.setTextAlign(Paint.Align.RIGHT);
            paint.setColor(Color.WHITE);
            paint.setTextSize(15f);
            c.drawText("TOUCHER", w - margin - 20f, y + cardH - 18f, paint);
        }
        paint.setFakeBoldText(false);
    }

    private void drawPause(Canvas c, int w, int h) {
        paint.setColor(Color.argb(225, 7, 11, 13));
        c.drawRect(0, 0, w, h, paint);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setFakeBoldText(true);
        paint.setColor(Color.WHITE);
        paint.setTextSize(35f);
        c.drawText("PAUSE", w / 2f, h * 0.25f, paint);
        float bw = w - 80f;
        float x1 = 40f;
        float x2 = 40f + bw;
        float y = h * 0.34f;
        resumeRect.set(x1, y, x2, y + 64f);
        soundRect.set(x1, y + 80f, x2, y + 144f);
        restartRect.set(x1, y + 160f, x2, y + 224f);
        quitRect.set(x1, y + 240f, x2, y + 304f);
        drawMenuButton(c, resumeRect, "REPRENDRE", Color.rgb(52, 145, 92));
        drawMenuButton(c, soundRect, soundOn ? "SON : OUI" : "SON : NON", Color.rgb(45, 77, 90));
        drawMenuButton(c, restartRect, "RECOMMENCER", Color.rgb(80, 70, 45));
        drawMenuButton(c, quitRect, "QUITTER", Color.rgb(105, 48, 48));
        paint.setFakeBoldText(false);
    }

    private void drawDeath(Canvas c, int w, int h) {
        paint.setColor(Color.argb(230, 10, 9, 11));
        c.drawRect(0, 0, w, h, paint);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setFakeBoldText(true);
        paint.setColor(Color.rgb(255, 90, 90));
        paint.setTextSize(36f);
        c.drawText("PARTIE TERMINÉE", w / 2f, h * 0.31f, paint);
        paint.setColor(Color.WHITE);
        paint.setTextSize(20f);
        c.drawText("Niveau " + level + "   •   " + kills + " éliminations", w / 2f, h * 0.38f, paint);
        paint.setTextSize(24f);
        c.drawText("Score " + score, w / 2f, h * 0.43f, paint);
        restartRect.set(42f, h * 0.52f, w - 42f, h * 0.52f + 70f);
        drawMenuButton(c, restartRect, "RECOMMENCER", Color.rgb(52, 145, 92));
        quitRect.set(42f, h * 0.52f + 90f, w - 42f, h * 0.52f + 160f);
        drawMenuButton(c, quitRect, "QUITTER", Color.rgb(95, 48, 48));
        paint.setFakeBoldText(false);
    }

    private void drawBanner(Canvas c, int w, int h) {
        float a = Math.min(1f, bannerLife * 1.8f);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setFakeBoldText(true);
        paint.setTextSize(34f);
        paint.setColor(Color.argb((int) (255f * a), 255, 235, 130));
        c.drawText(banner, w / 2f, h * 0.26f, paint);
        paint.setFakeBoldText(false);
    }

    private void drawMenuButton(Canvas c, RectF r, String text, int color) {
        paint.setColor(color);
        c.drawRoundRect(r, 18f, 18f, paint);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setColor(Color.WHITE);
        paint.setTextSize(19f);
        c.drawText(text, r.centerX(), r.centerY() + 7f, paint);
    }

    private int cardColor(int rarity) {
        switch (rarity) {
            case 1: return Color.rgb(31, 58, 82);
            case 2: return Color.rgb(69, 37, 95);
            case 3: return Color.rgb(93, 64, 22);
            default: return Color.rgb(35, 45, 48);
        }
    }

    private int rarityColor(int rarity) {
        switch (rarity) {
            case 1: return Color.rgb(90, 185, 255);
            case 2: return Color.rgb(202, 110, 255);
            case 3: return Color.rgb(255, 205, 70);
            default: return Color.rgb(205, 215, 218);
        }
    }

    private String rarityName(int rarity) {
        switch (rarity) {
            case 1: return "RARE";
            case 2: return "ÉPIQUE";
            case 3: return "LÉGENDAIRE";
            default: return "COMMUN";
        }
    }

    private int withAlpha(int color, int alpha) {
        return Color.argb(Math.max(0, Math.min(255, alpha)), Color.red(color), Color.green(color), Color.blue(color));
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        int index = event.getActionIndex();
        float x = event.getX(index);
        float y = event.getY(index);

        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            if (dead) {
                if (restartRect.contains(x, y)) resetGame();
                else if (quitRect.contains(x, y)) ((android.app.Activity) getContext()).finish();
                return true;
            }
            if (choosing) {
                for (int i = 0; i < currentChoices.size() && i < 3; i++) {
                    if (choiceRects[i].contains(x, y)) {
                        applyUpgrade(currentChoices.get(i));
                        return true;
                    }
                }
                return true;
            }
            if (paused) {
                if (resumeRect.contains(x, y)) paused = false;
                else if (soundRect.contains(x, y)) soundOn = !soundOn;
                else if (restartRect.contains(x, y)) resetGame();
                else if (quitRect.contains(x, y)) ((android.app.Activity) getContext()).finish();
                return true;
            }
            if (pauseRect.contains(x, y)) {
                paused = true;
                moveX = moveY = 0f;
                joystickPointer = -1;
                return true;
            }
            if (autoRect.contains(x, y)) {
                autoChoice = !autoChoice;
                showBanner(autoChoice ? "AUTO ACTIVÉ" : "AUTO DÉSACTIVÉ");
                return true;
            }
            if (dashRect.contains(x, y)) {
                performDash();
                return true;
            }
            if (x < getWidth() * 0.62f && y > getHeight() * 0.42f && joystickPointer < 0) {
                joystickPointer = event.getPointerId(index);
                joyStartX = x;
                joyStartY = y;
                joyX = joyY = 0f;
                updateJoystick(x, y);
                return true;
            }
        } else if (action == MotionEvent.ACTION_MOVE) {
            if (joystickPointer >= 0) {
                int pIndex = event.findPointerIndex(joystickPointer);
                if (pIndex >= 0) updateJoystick(event.getX(pIndex), event.getY(pIndex));
            }
            return true;
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP || action == MotionEvent.ACTION_CANCEL) {
            int pointerId = event.getPointerId(index);
            if (pointerId == joystickPointer || action == MotionEvent.ACTION_CANCEL) {
                joystickPointer = -1;
                joyX = joyY = 0f;
                moveX = moveY = 0f;
            }
            return true;
        }
        return true;
    }

    private void updateJoystick(float x, float y) {
        float dx = x - joyStartX;
        float dy = y - joyStartY;
        float l = (float) Math.hypot(dx, dy);
        if (l > joyRadius) {
            dx = dx / l * joyRadius;
            dy = dy / l * joyRadius;
        }
        joyX = dx / joyRadius;
        joyY = dy / joyRadius;
        moveX = joyX;
        moveY = joyY;
    }

    private static final class Enemy {
        final int type;
        float x, y, r, hp, maxHp, speed, damage, shootCd, flash, orbitHitCd;
        Enemy(int type, float x, float y, float r, float hp, float speed, float damage) {
            this.type = type; this.x = x; this.y = y; this.r = r; this.hp = hp; this.maxHp = hp;
            this.speed = speed; this.damage = damage;
        }
    }

    private static final class Shot {
        final int type;
        float x, y, vx, vy, r, life, damage;
        Enemy target;
        Shot(int type, float x, float y, float vx, float vy, float r, float life, float damage) {
            this.type = type; this.x = x; this.y = y; this.vx = vx; this.vy = vy; this.r = r;
            this.life = life; this.damage = damage;
        }
    }

    private static final class Gem {
        float x, y, value;
        Gem(float x, float y, float value) { this.x = x; this.y = y; this.value = value; }
    }

    private static final class Chest {
        final float x, y;
        Chest(float x, float y) { this.x = x; this.y = y; }
    }

    private static final class Particle {
        float x, y, vx, vy, life, maxLife, size;
        final int color;
        Particle(float x, float y, float vx, float vy, float life, int color, float size) {
            this.x = x; this.y = y; this.vx = vx; this.vy = vy; this.life = this.maxLife = life;
            this.color = color; this.size = size;
        }
    }

    private static final class FloatingText {
        float x, y, life = 0.75f;
        final int value, color;
        FloatingText(float x, float y, int value, int color) {
            this.x = x; this.y = y; this.value = value; this.color = color;
        }
    }

    private static final class ArcFx {
        final float x1, y1, x2, y2, maxLife;
        float life;
        ArcFx(float x1, float y1, float x2, float y2, float life) {
            this.x1 = x1; this.y1 = y1; this.x2 = x2; this.y2 = y2; this.life = this.maxLife = life;
        }
    }

    private static final class Upgrade {
        final String code;
        final int rarity;
        final String title;
        final String description;
        Upgrade(String code, int rarity, String title, String description) {
            this.code = code; this.rarity = rarity; this.title = title; this.description = description;
        }
    }
}
