package com.megamobile.game;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
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
    private static final int ENEMY_MINIBOSS = 5;

    protected static final int CHEST_STANDARD = 0;
    protected static final int CHEST_WEAPON = 1;
    protected static final int CHEST_ARTIFACT = 2;
    private static final int CHOICE_LEVEL = 0;
    private static final int CHOICE_STANDARD = 1;
    private static final int CHOICE_WEAPON = 2;
    private static final int CHOICE_ARTIFACT = 3;

    private static final int OBJECTIVE_CHEST = 0;
    private static final int OBJECTIVE_CAPTURE = 1;
    private static final int OBJECTIVE_HIDEOUT = 2;
    private static final int OBJECTIVE_SECRET = 3;
    private static final int OBJECTIVE_BONUS = 4;

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
    private final ArrayList<WorldObjective> worldObjectives = new ArrayList<>();
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
    private float miniBossCd;
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
    private float weaponHaste = 1f;
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
    private float invuln;

    private int artifactCrownLevel;
    private int artifactWardLevel;
    private int artifactCompassLevel;
    private int artifactHourglassLevel;

    private float moveX;
    private float moveY;
    private float lastMoveX;
    private float lastMoveY = -1f;
    private float camX;
    private float camY;

    private boolean worldReady;
    private int worldStage = 1;
    private float worldRadius;
    private float previousWorldRadius;
    private int nextObjectiveId = 1;

    private int joystickPointer = -1;
    private float joyStartX;
    private float joyStartY;
    private float joyX;
    private float joyY;
    private float joyRadius = 72f;

    private final ArrayList<Upgrade> currentChoices = new ArrayList<>();
    private boolean chestChoice;
    private int choiceSource = CHOICE_LEVEL;
    private boolean chestRevealing;
    private float chestRevealTime;
    private int chestRevealRarity;
    private int chestRevealKind;
    private boolean levelRevealing;
    private float levelRevealTime;
    private final RectF[] choiceRects = {new RectF(), new RectF(), new RectF()};
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

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        joyRadius = 68f * uiScale(w, h);
        if (!worldReady && w > 0 && h > 0) ensureWorldReady();
    }

    private float uiScale(int w, int h) {
        return Math.max(1f, Math.min(w / 420f, h / 820f));
    }

    private void resetGame() {
        enemies.clear();
        shots.clear();
        gems.clear();
        chests.clear();
        worldObjectives.clear();
        particles.clear();
        texts.clear();
        arcs.clear();
        elapsed = 0f;
        difficulty = 1f;
        spawnCd = 0.2f;
        bossCd = 34f;
        miniBossCd = 16f;
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
        weaponHaste = 1f;
        level = 1;
        xp = 0f;
        nextXp = 12f;
        auraLevel = orbitLevel = lightningLevel = rocketLevel = 0;
        auraCd = lightningCd = rocketCd = 0f;
        invuln = 0f;
        artifactCrownLevel = artifactWardLevel = artifactCompassLevel = artifactHourglassLevel = 0;
        moveX = moveY = 0f;
        lastMoveX = 0f;
        lastMoveY = -1f;
        camX = camY = 0f;
        worldReady = false;
        worldStage = 1;
        worldRadius = 0f;
        previousWorldRadius = 0f;
        nextObjectiveId = 1;
        paused = false;
        dead = false;
        choosing = false;
        chestChoice = false;
        choiceSource = CHOICE_LEVEL;
        chestRevealing = false;
        chestRevealTime = 0f;
        chestRevealRarity = 0;
        chestRevealKind = CHEST_STANDARD;
        levelRevealing = false;
        levelRevealTime = 0f;
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
        ensureWorldReady();
        elapsed += dt;
        difficulty = 1f + elapsed / Math.max(25f, remote.difficultySeconds)
                + (float) Math.pow(elapsed / 360f, 1.35) * 0.7f;
        if (bannerLife > 0f) bannerLife -= dt;
        if (invuln > 0f) invuln -= dt;
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
        clampPlayerToWorld();
        updateWorldObjectives(dt);

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

        miniBossCd -= dt;
        if (miniBossCd <= 0f) {
            spawnEnemy(ENEMY_MINIBOSS);
            miniBossCd = Math.max(18f, 31f / Math.min(2.0f, 0.9f + difficulty * 0.08f));
            showBanner("CHAMPION HÉROÏQUE — ARME À RÉCUPÉRER");
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
            fireCd = Math.max(0.055f, fireInterval / weaponHaste);
        }

        if (auraLevel > 0) {
            auraCd -= dt;
            if (auraCd <= 0f) {
                float radius = (115f + auraLevel * 16f) * effectRangeScale();
                float dmg = damage * (0.28f + auraLevel * 0.07f);
                for (int i = enemies.size() - 1; i >= 0; i--) {
                    Enemy e = enemies.get(i);
                    if (distanceSq(px, py, e.x, e.y) <= radius * radius) damageEnemy(e, dmg, false);
                }
                auraCd = Math.max(0.08f, (0.48f - auraLevel * 0.025f) / weaponHaste);
            }
        }

        if (orbitLevel > 0) {
            int blades = Math.min(8, 2 + orbitLevel);
            float orbitRadius = (88f + orbitLevel * 4f) * effectRangeScale();
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
                        e.orbitHitCd = Math.max(0.07f, 0.24f / weaponHaste);
                        break;
                    }
                }
            }
        }

        if (lightningLevel > 0) {
            lightningCd -= dt;
            if (lightningCd <= 0f) {
                fireLightning();
                lightningCd = Math.max(0.24f, (2.5f - lightningLevel * 0.22f) / weaponHaste);
            }
        }

        if (rocketLevel > 0) {
            rocketCd -= dt;
            if (rocketCd <= 0f) {
                Enemy target = nearestEnemy(px, py, 900f * effectRangeScale());
                if (target != null) {
                    float dx = target.x - px;
                    float dy = target.y - py;
                    float l = Math.max(1f, (float) Math.hypot(dx, dy));
                    float baseAngle = (float) Math.atan2(dy, dx);
                    int rocketCount = Math.min(5, 1 + Math.max(0, multi - 1) / 2);
                    for (int i = 0; i < rocketCount; i++) {
                        float offset = (i - (rocketCount - 1) * 0.5f) * 0.16f;
                        float angle = baseAngle + offset;
                        Shot s = new Shot(SHOT_ROCKET, px, py,
                                (float) Math.cos(angle) * 330f,
                                (float) Math.sin(angle) * 330f,
                                8f, 3.2f, damage * (1.8f + rocketLevel * 0.35f));
                        s.target = target;
                        shots.add(s);
                    }
                }
                rocketCd = Math.max(0.28f, (3.3f - rocketLevel * 0.28f) / weaponHaste);
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
            shots.add(new Shot(SHOT_PLAYER, px, py,
                    (float) Math.cos(a) * 650f,
                    (float) Math.sin(a) * 650f,
                    7f, 1.6f, damage));
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
            float lightningRange = range;
            if (i == 0 && distanceSq(px, py, e.x, e.y) > lightningRange * lightningRange * 1.8f) break;
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
        float radius = (105f + rocketLevel * 12f) * effectRangeScale();
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
        if (choosing || chestRevealing) return;
        for (int i = chests.size() - 1; i >= 0; i--) {
            Chest chest = chests.get(i);
            if (distanceSq(px, py, chest.x, chest.y) < 52f * 52f) {
                chests.remove(i);
                markObjectiveComplete(chest.objectiveId);
                startChestReveal(chest.kind);
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
        if (chestRevealing) {
            chestRevealTime += dt;
            if (chestRevealTime >= 2.05f) finishChestReveal();
        }
        if (levelRevealing) {
            levelRevealTime += dt;
            if (levelRevealTime >= 0.72f) finishLevelReveal();
        }
        if (bannerLife > 0f && (paused || dead || choosing)) bannerLife -= dt;
    }

    private void ensureWorldReady() {
        if (worldReady || getWidth() <= 0 || getHeight() <= 0) return;
        worldReady = true;
        worldStage = 1;
        previousWorldRadius = 0f;
        worldRadius = Math.max(getWidth(), getHeight()) * 0.88f;
        generateWorldStage();
    }

    private void generateWorldStage() {
        worldObjectives.clear();
        int[] types = {
                OBJECTIVE_CHEST,
                OBJECTIVE_CAPTURE,
                OBJECTIVE_HIDEOUT,
                OBJECTIVE_SECRET,
                OBJECTIVE_BONUS,
                OBJECTIVE_CHEST
        };
        float inner = worldStage == 1 ? worldRadius * 0.25f : previousWorldRadius + (worldRadius - previousWorldRadius) * 0.22f;
        float outer = worldRadius * 0.80f;
        float phase = random.nextFloat() * (float) (Math.PI * 2.0);
        for (int i = 0; i < types.length; i++) {
            float angle = phase + (float) (Math.PI * 2.0 * i / types.length) + (random.nextFloat() - 0.5f) * 0.28f;
            float distance = inner + (outer - inner) * (0.28f + random.nextFloat() * 0.68f);
            WorldObjective objective = new WorldObjective(nextObjectiveId++, types[i],
                    (float) Math.cos(angle) * distance,
                    (float) Math.sin(angle) * distance);
            objective.revealed = objective.type != OBJECTIVE_SECRET;
            worldObjectives.add(objective);
            if (objective.type == OBJECTIVE_CHEST) {
                addChest(objective.x, objective.y, CHEST_STANDARD, objective.id);
            }
        }
    }

    private void updateWorldObjectives(float dt) {
        if (!worldReady || worldObjectives.isEmpty()) return;
        for (WorldObjective objective : worldObjectives) {
            if (objective.complete) continue;
            float distance = (float) Math.sqrt(distanceSq(px, py, objective.x, objective.y));
            if (!objective.revealed && distance < 210f) {
                objective.revealed = true;
                showBanner("SECRET DÉCOUVERT");
            }
            if (!objective.revealed || objective.type == OBJECTIVE_CHEST) continue;

            if (objective.type == OBJECTIVE_CAPTURE) {
                if (distance <= 118f) objective.progress += dt;
                else objective.progress = Math.max(0f, objective.progress - dt * 0.35f);
                if (objective.progress >= 4f) {
                    completeWorldObjective(objective, "ZONE CAPTURÉE");
                    dropChest(objective.x, objective.y, CHEST_STANDARD);
                }
            } else if (objective.type == OBJECTIVE_HIDEOUT) {
                if (!objective.activated && distance < 260f) {
                    objective.activated = true;
                    for (int i = 0; i < 6 + worldStage; i++) spawnEnemy(false);
                    showBanner("CACHE DÉFENDUE");
                }
                if (objective.activated && distance <= 105f) objective.progress += dt;
                if (objective.progress >= 3f) {
                    completeWorldObjective(objective, "CACHE RÉCUPÉRÉE");
                    dropChest(objective.x, objective.y, random.nextFloat() < 0.35f ? CHEST_ARTIFACT : CHEST_STANDARD);
                }
            } else if (objective.type == OBJECTIVE_SECRET && distance <= 58f) {
                completeWorldObjective(objective, "SECRET RÉCUPÉRÉ");
                dropChest(objective.x, objective.y, CHEST_STANDARD);
            } else if (objective.type == OBJECTIVE_BONUS && distance <= 62f) {
                hp = Math.min(maxHp, hp + Math.max(32f, maxHp * 0.30f));
                invuln = Math.max(invuln, 1.8f);
                score += 300 * worldStage;
                completeWorldObjective(objective, "ZONE BONUS ACTIVÉE");
            }
        }
        expandIfStageComplete();
    }

    private void completeWorldObjective(WorldObjective objective, String message) {
        if (objective.complete) return;
        objective.complete = true;
        objective.progress = 4f;
        score += 180 * worldStage;
        showBanner(message);
    }

    private void markObjectiveComplete(int objectiveId) {
        if (objectiveId < 0) return;
        for (WorldObjective objective : worldObjectives) {
            if (objective.id == objectiveId) {
                completeWorldObjective(objective, "COFFRE DE ZONE RÉCUPÉRÉ");
                break;
            }
        }
        expandIfStageComplete();
    }

    private void expandIfStageComplete() {
        if (worldObjectives.isEmpty()) return;
        for (WorldObjective objective : worldObjectives) if (!objective.complete) return;
        previousWorldRadius = worldRadius;
        worldStage++;
        worldRadius += Math.max(getWidth(), getHeight()) * 0.58f;
        score += 750 * worldStage;
        generateWorldStage();
        showBanner("CARTE AGRANDIE — ZONE " + worldStage);
    }

    private void clampPlayerToWorld() {
        if (!worldReady || worldRadius <= 0f) return;
        float distance = (float) Math.hypot(px, py);
        float limit = Math.max(120f, worldRadius - 42f);
        if (distance <= limit || distance <= 0f) return;
        px = px / distance * limit;
        py = py / distance * limit;
    }

    private void addChest(float x, float y, int kind, int objectiveId) {
        chests.add(new Chest(x, y, kind, objectiveId));
    }

    protected final void dropChest(float x, float y, int kind) {
        addChest(x, y, kind, -1);
    }

    private void spawnEnemy(boolean boss) {
        spawnEnemy(boss ? ENEMY_BOSS : -1);
    }

    private void spawnEnemy(int forcedType) {
        float screenRadius = Math.max(getWidth(), getHeight()) * 0.72f + 110f;
        float angle = random.nextFloat() * (float) (Math.PI * 2.0);
        float x = px + (float) Math.cos(angle) * screenRadius;
        float y = py + (float) Math.sin(angle) * screenRadius;
        if (worldReady) {
            float distance = (float) Math.hypot(x, y);
            float limit = Math.max(140f, worldRadius - 55f);
            if (distance > limit && distance > 0f) {
                x = x / distance * limit;
                y = y / distance * limit;
            }
        }

        int type;
        if (forcedType >= 0) type = forcedType;
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
            case ENEMY_MINIBOSS:
                baseHp = 320f + elapsed * 2.5f; baseSpeed = 62f; baseDamage = 19f; radius = 34f; break;
            default:
                baseHp = 42f; baseSpeed = 79f; baseDamage = 10f; radius = 18f;
        }
        float hpScale = (float) Math.pow(difficulty, type == ENEMY_BOSS ? 1.32 : type == ENEMY_MINIBOSS ? 1.24 : 1.12);
        float speedScale = 1f + Math.min(0.85f, (difficulty - 1f) * 0.055f);
        float damageScale = 1f + (difficulty - 1f) * 0.14f;
        Enemy e = new Enemy(type, x, y, radius, baseHp * hpScale, baseSpeed * speedScale, baseDamage * damageScale);
        e.shootCd = 0.5f + random.nextFloat() * 1.6f;
        enemies.add(e);
    }

    private void damageEnemy(Enemy e, float amount, boolean lightning) {
        if (e.hp <= 0f) return;
        // Le critique est un bonus global : aura, lames, foudre, roquettes,
        // drones et armes futures passent tous par cette méthode.
        boolean critHit = random.nextFloat() < crit;
        float actual = amount * (critHit ? 1.65f : 1f);
        e.hp -= actual;
        e.flash = 0.08f;
        texts.add(new FloatingText(e.x, e.y - e.r - 10f, Math.round(actual), critHit ? Color.YELLOW : Color.WHITE));
        if (e.hp <= 0f) killEnemy(e);
    }

    private void killEnemy(Enemy e) {
        if (!enemies.remove(e)) return;
        kills++;
        int value = e.type == ENEMY_BOSS ? 28 : e.type == ENEMY_MINIBOSS ? 12 : (e.type == ENEMY_TANK ? 3 : e.type == ENEMY_SHOOTER ? 2 : 1);
        score += value * 10 + Math.round(difficulty);
        gems.add(new Gem(e.x, e.y, value));
        burst(e.x, e.y, colorForEnemy(e.type), e.type == ENEMY_BOSS ? 34 : e.type == ENEMY_MINIBOSS ? 22 : 10);
        if (e.type == ENEMY_BOSS || e.type == ENEMY_MINIBOSS) {
            dropChest(e.x, e.y, CHEST_WEAPON);
            showBanner(e.type == ENEMY_BOSS ? "ARME DU SEIGNEUR HÉROÏQUE" : "ARME DU CHAMPION");
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
            startLevelReveal();
        }
    }

    private void startChoice(boolean fromChest) {
        beginChoice(fromChest ? CHOICE_STANDARD : CHOICE_LEVEL, -1);
    }

    private void startLevelReveal() {
        choosing = true;
        chestChoice = false;
        chestRevealing = false;
        levelRevealing = true;
        levelRevealTime = 0f;
        choiceSource = CHOICE_LEVEL;
        if (!autoChoice) {
            moveX = moveY = 0f;
            joystickPointer = -1;
        }
        currentChoices.clear();
    }

    private void finishLevelReveal() {
        if (!levelRevealing) return;
        levelRevealing = false;
        beginChoice(CHOICE_LEVEL, -1);
    }

    private void startChestReveal(int chestKind) {
        choosing = true;
        chestChoice = true;
        chestRevealing = true;
        levelRevealing = false;
        chestRevealTime = 0f;
        chestRevealKind = chestKind;
        choiceSource = chestKind == CHEST_WEAPON ? CHOICE_WEAPON
                : chestKind == CHEST_ARTIFACT ? CHOICE_ARTIFACT : CHOICE_STANDARD;
        chestRevealRarity = rollRarity(choiceSource);
        // Une sélection automatique ne doit pas simuler un relâchement du doigt :
        // le joueur reprend ainsi immédiatement sa trajectoire après le choix.
        if (!autoChoice) {
            moveX = moveY = 0f;
            joystickPointer = -1;
        }
        currentChoices.clear();
    }

    private void finishChestReveal() {
        if (!chestRevealing) return;
        chestRevealing = false;
        beginChoice(choiceSource, chestRevealRarity);
    }

    private void beginChoice(int source, int forcedRarity) {
        choosing = true;
        choiceSource = source;
        chestChoice = source != CHOICE_LEVEL;
        levelRevealing = false;
        if (!autoChoice) {
            moveX = moveY = 0f;
            joystickPointer = -1;
        }
        currentChoices.clear();
        for (int i = 0; i < 3; i++) {
            Upgrade candidate;
            int guard = 0;
            do {
                candidate = randomUpgrade(source, forcedRarity);
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

    private Upgrade randomUpgrade(int source, int forcedRarity) {
        int rarity = forcedRarity >= 0 ? forcedRarity : rollRarity(source);
        String[] base = {"DMG", "FIRE", "SPEED", "HP", "MAGNET", "CRIT", "ARMOR", "REGEN", "RANGE", "MULTI"};
        ArrayList<String> codes = new ArrayList<>();
        if (source == CHOICE_WEAPON) {
            codes.add("AURA");
            codes.add("ORBIT");
            codes.add("LIGHTNING");
            codes.add("ROCKET");
        } else if (source == CHOICE_ARTIFACT) {
            codes.add("ART_CROWN");
            codes.add("ART_WARD");
            codes.add("ART_COMPASS");
            codes.add("ART_HOURGLASS");
        } else {
            codes.add("HEAL");
            Collections.addAll(codes, base);
        }
        String code = codes.get(random.nextInt(codes.size()));
        return new Upgrade(code, rarity, titleFor(code), descriptionFor(code, rarity));
    }

    private int rollRarity(int source) {
        float r = random.nextFloat();
        if (source == CHOICE_ARTIFACT) {
            if (r < 0.18f) return 3;
            if (r < 0.64f) return 2;
            return 1;
        }
        if (source == CHOICE_WEAPON) {
            if (r < 0.14f) return 3;
            if (r < 0.52f) return 2;
            return 1;
        }
        if (source == CHOICE_STANDARD) {
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
            case "ART_CROWN": return artifactCrownLevel == 0 ? "ARTEFACT : COURONNE" : "COURONNE +" + artifactCrownLevel;
            case "ART_WARD": return artifactWardLevel == 0 ? "ARTEFACT : ÉGIDE" : "ÉGIDE +" + artifactWardLevel;
            case "ART_COMPASS": return artifactCompassLevel == 0 ? "ARTEFACT : BOUSSOLE" : "BOUSSOLE +" + artifactCompassLevel;
            case "ART_HOURGLASS": return artifactHourglassLevel == 0 ? "ARTEFACT : SABLIER" : "SABLIER +" + artifactHourglassLevel;
            default: return code;
        }
    }

    private String descriptionFor(String code, int rarity) {
        float m = rarityMultiplier(rarity);
        switch (code) {
            case "DMG": return "+" + Math.round(18f * m) + "% dégâts";
            case "FIRE": return "Toutes les armes : +" + Math.round(13f * m) + "% cadence";
            case "SPEED": return "+" + Math.round(10f * m) + "% déplacement";
            case "HP": return "+" + Math.round(18f * m) + " PV max + soin";
            case "MAGNET": return "+" + Math.round(40f * m) + " portée d'aimant";
            case "CRIT": return "Toutes les armes : +" + Math.round(5f * m) + "% critique";
            case "ARMOR": return "+" + Math.round(9f * m) + " armure";
            case "REGEN": return "+" + oneDecimal(0.45f * m) + " PV/s";
            case "RANGE": return "+" + Math.round(12f * m) + "% portée et zones d'effet";
            case "MULTI": return "Balles et roquettes : +" + (rarity >= 2 ? 2 : 1) + " projectile";
            case "AURA": return "Dégâts continus autour de toi";
            case "ORBIT": return "Lames qui tournent autour de toi";
            case "LIGHTNING": return "Éclairs en chaîne automatiques";
            case "ROCKET": return "Roquettes chercheuses explosives";
            case "HEAL": return "Récupère " + Math.round(28f * m) + "% des PV";
            case "ART_CROWN": return "Toutes les armes gagnent dégâts et critique";
            case "ART_WARD": return "PV, armure et régénération augmentés";
            case "ART_COMPASS": return "Toutes les portées, zones et attraction augmentées";
            case "ART_HOURGLASS": return "Toutes les armes attaquent plus vite";
            default: return "Amélioration";
        }
    }

    private String oneDecimal(float value) {
        return String.format(Locale.US, "%.1f", value);
    }

    private float effectRangeScale() {
        return 1f + Math.max(0f, range / 420f - 1f) * 0.65f;
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
            case "FIRE": weaponHaste = Math.min(8f, weaponHaste * (1f + 0.13f * m)); break;
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
            case "ART_CROWN":
                artifactCrownLevel++;
                damage *= 1f + 0.15f * m;
                crit = Math.min(0.85f, crit + 0.045f * m);
                break;
            case "ART_WARD":
                artifactWardLevel++;
                float wardHp = 14f * m;
                maxHp += wardHp;
                hp = Math.min(maxHp, hp + wardHp);
                armor += 7f * m;
                regen += 0.28f * m;
                break;
            case "ART_COMPASS":
                artifactCompassLevel++;
                speed *= 1f + 0.065f * m;
                range *= 1f + 0.085f * m;
                magnet += 34f * m;
                break;
            case "ART_HOURGLASS":
                artifactHourglassLevel++;
                weaponHaste = Math.min(8f, weaponHaste * (1f + 0.10f * m));
                break;
        }
        choosing = false;
        chestChoice = false;
        chestRevealing = false;
        levelRevealing = false;
        choiceSource = CHOICE_LEVEL;
        currentChoices.clear();
        showBanner(u.title);
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
        c.drawColor(Color.rgb(15, 12, 22));
        // Dalles de château : le fond reste lisible sur un petit écran mais donne
        // immédiatement une identité médiévale au terrain de jeu.
        paint.setColor(Color.rgb(28, 22, 36));
        float tile = 74f;
        float ox = ((-camX + w * 0.5f) % tile + tile) % tile;
        float oy = ((-camY + h * 0.56f) % tile + tile) % tile;
        for (int row = -1; row < h / (int) tile + 2; row++) {
            float y = oy + row * tile;
            float offset = (row & 1) == 0 ? 0f : tile * 0.5f;
            for (float x = ox - tile + offset; x < w + tile; x += tile) {
                paint.setColor(Color.rgb(27 + (row & 1) * 3, 21 + (row & 1) * 2, 35 + (row & 1) * 4));
                c.drawRect(x + 2f, y + 2f, x + tile - 2f, y + tile - 2f, paint);
                paint.setColor(Color.argb(95, 8, 6, 13));
                c.drawRect(x + tile - 3f, y + 3f, x + tile, y + tile, paint);
                c.drawRect(x + 3f, y + tile - 3f, x + tile, y + tile, paint);
            }
        }
        paint.setShader(new RadialGradient(w * 0.5f, h * 0.54f, Math.max(w, h) * 0.72f,
                Color.argb(0, 0, 0, 0), Color.argb(160, 0, 0, 0), Shader.TileMode.CLAMP));
        c.drawRect(0f, 0f, w, h, paint);
        paint.setShader(null);
        paint.setColor(Color.argb(90, 205, 157, 76));
        c.drawRect(0f, h * 0.155f, w, h * 0.158f, paint);
        paint.setColor(Color.argb(70, 205, 157, 76));
        c.drawCircle(w * 0.84f, h * 0.16f, 25f, paint);
    }

    private void drawWorld(Canvas c) {
        drawWorldMap(c);
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

    private void drawWorldMap(Canvas c) {
        if (!worldReady) return;
        paint.setColor(Color.rgb(19, 34, 34));
        c.drawCircle(0f, 0f, worldRadius, paint);
        if (previousWorldRadius > 0f) {
            stroke.setColor(Color.argb(70, 105, 205, 180));
            stroke.setStrokeWidth(3f);
            c.drawCircle(0f, 0f, previousWorldRadius, stroke);
        }
        stroke.setColor(Color.rgb(85, 225, 180));
        stroke.setStrokeWidth(8f);
        c.drawCircle(0f, 0f, worldRadius, stroke);
        stroke.setColor(Color.argb(70, 85, 225, 180));
        stroke.setStrokeWidth(24f);
        c.drawCircle(0f, 0f, worldRadius - 12f, stroke);

        for (WorldObjective objective : worldObjectives) {
            if (objective.complete || !objective.revealed) continue;
            float pulse = 1f + (float) Math.sin(elapsed * 3.2f + objective.id) * 0.06f;
            if (objective.type == OBJECTIVE_CAPTURE) {
                paint.setColor(Color.argb(45, 70, 205, 255));
                c.drawCircle(objective.x, objective.y, 118f, paint);
                stroke.setColor(Color.rgb(85, 215, 255));
                stroke.setStrokeWidth(5f);
                c.drawCircle(objective.x, objective.y, 118f, stroke);
                RectF progress = new RectF(objective.x - 100f, objective.y - 100f,
                        objective.x + 100f, objective.y + 100f);
                c.drawArc(progress, -90f, Math.min(1f, objective.progress / 4f) * 360f, false, stroke);
                drawWorldLabel(c, objective.x, objective.y + 6f, "CAPTURE", Color.rgb(110, 225, 255));
            } else if (objective.type == OBJECTIVE_HIDEOUT) {
                paint.setColor(Color.argb(210, 82, 62, 42));
                c.drawRoundRect(objective.x - 54f * pulse, objective.y - 42f * pulse,
                        objective.x + 54f * pulse, objective.y + 42f * pulse, 12f, 12f, paint);
                stroke.setColor(Color.rgb(235, 178, 95));
                stroke.setStrokeWidth(5f);
                c.drawRoundRect(objective.x - 54f, objective.y - 42f,
                        objective.x + 54f, objective.y + 42f, 12f, 12f, stroke);
                drawWorldLabel(c, objective.x, objective.y + 7f, "CACHE", Color.rgb(255, 205, 120));
            } else if (objective.type == OBJECTIVE_SECRET) {
                paint.setColor(Color.argb(80, 215, 110, 255));
                c.drawCircle(objective.x, objective.y, 48f * pulse, paint);
                drawWorldLabel(c, objective.x, objective.y + 12f, "?", Color.rgb(235, 145, 255));
            } else if (objective.type == OBJECTIVE_BONUS) {
                paint.setColor(Color.argb(75, 100, 255, 145));
                c.drawCircle(objective.x, objective.y, 52f * pulse, paint);
                stroke.setColor(Color.rgb(110, 245, 150));
                stroke.setStrokeWidth(5f);
                c.drawCircle(objective.x, objective.y, 32f * pulse, stroke);
                drawWorldLabel(c, objective.x, objective.y + 8f, "+", Color.rgb(140, 255, 175));
            } else if (objective.type == OBJECTIVE_CHEST) {
                stroke.setColor(Color.argb(150, 255, 218, 90));
                stroke.setStrokeWidth(4f);
                c.drawCircle(objective.x, objective.y, 62f * pulse, stroke);
            }
        }
    }

    private void drawWorldLabel(Canvas c, float x, float y, String label, int color) {
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setFakeBoldText(true);
        paint.setTextSize(20f);
        paint.setColor(color);
        c.drawText(label, x, y, paint);
        paint.setFakeBoldText(false);
    }

    private void drawPlayer(Canvas c) {
        paint.setColor(Color.argb(95, 0, 0, 0));
        c.drawOval(px - 19f, py + 11f, px + 19f, py + 25f, paint);
        // Le joueur est le seigneur des ombres : cape, armure noire et couronne.
        Path cape = new Path();
        cape.moveTo(px - 17f, py + 4f); cape.lineTo(px - 28f, py + 25f);
        cape.lineTo(px + 28f, py + 25f); cape.lineTo(px + 17f, py + 4f); cape.close();
        paint.setColor(Color.rgb(47, 18, 55)); c.drawPath(cape, paint);
        int col = invuln > 0f && ((int) (elapsed * 24f) % 2 == 0) ? Color.WHITE : Color.rgb(43, 39, 58);
        paint.setColor(col);
        c.drawCircle(px, py, 18f, paint);
        stroke.setColor(Color.WHITE);
        stroke.setStrokeWidth(3f);
        c.drawCircle(px, py, 18f, stroke);
        paint.setColor(Color.rgb(255, 74, 74));
        float eyeX = px + lastMoveX * 7f;
        float eyeY = py + lastMoveY * 7f;
        c.drawCircle(eyeX, eyeY, 3f, paint);
        Path horns = new Path();
        horns.moveTo(px - 12f, py - 12f); horns.lineTo(px - 19f, py - 27f); horns.lineTo(px - 4f, py - 16f);
        horns.moveTo(px + 12f, py - 12f); horns.lineTo(px + 19f, py - 27f); horns.lineTo(px + 4f, py - 16f);
        paint.setColor(Color.rgb(175, 122, 66)); c.drawPath(horns, paint);
    }

    private void drawEnemy(Canvas c, Enemy e) {
        paint.setColor(Color.argb(95, 0, 0, 0));
        c.drawOval(e.x - e.r, e.y + e.r * 0.45f, e.x + e.r, e.y + e.r * 1.05f, paint);
        float bob = (float) Math.sin(elapsed * 4.5f + e.x * 0.01f) * (e.type == ENEMY_BOSS ? 2f : 1.5f);
        c.save(); c.translate(0f, bob);
        int color = e.flash > 0f ? Color.WHITE : colorForEnemy(e.type);
        paint.setColor(color);
        switch (e.type) {
            case ENEMY_FAST:
                c.save();
                c.rotate(45f, e.x, e.y);
                c.drawRect(e.x - e.r * 0.72f, e.y - e.r * 0.72f, e.x + e.r * 0.72f, e.y + e.r * 0.72f, paint);
                paint.setColor(Color.WHITE); c.drawRect(e.x - 2f, e.y - e.r, e.x + 2f, e.y + e.r, paint);
                c.restore();
                break;
            case ENEMY_TANK:
                c.drawRoundRect(e.x - e.r, e.y - e.r, e.x + e.r, e.y + e.r, 8f, 8f, paint);
                paint.setColor(Color.rgb(230, 210, 164)); c.drawRect(e.x - 3f, e.y - e.r - 8f, e.x + 3f, e.y + e.r + 8f, paint);
                break;
            case ENEMY_SHOOTER:
                c.drawCircle(e.x, e.y, e.r, paint);
                stroke.setColor(Color.rgb(255, 170, 255));
                c.drawCircle(e.x, e.y, e.r + 5f, stroke);
                break;
            case ENEMY_MINIBOSS:
                c.drawRoundRect(e.x - e.r, e.y - e.r, e.x + e.r, e.y + e.r, 12f, 12f, paint);
                paint.setColor(Color.rgb(234, 196, 112)); c.drawRect(e.x - e.r - 5f, e.y - 4f, e.x + e.r + 5f, e.y + 4f, paint);
                stroke.setColor(Color.rgb(255, 221, 117)); stroke.setStrokeWidth(5f); c.drawCircle(e.x, e.y, e.r + 7f, stroke);
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
                paint.setColor(Color.rgb(220, 215, 190)); c.drawRect(e.x - 3f, e.y - e.r - 4f, e.x + 3f, e.y + e.r + 4f, paint);
        }
        c.restore();
        if (e.type == ENEMY_TANK || e.type == ENEMY_SHOOTER || e.type == ENEMY_MINIBOSS || e.type == ENEMY_BOSS) {
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
            case ENEMY_FAST: return Color.rgb(128, 179, 223);
            case ENEMY_TANK: return Color.rgb(88, 112, 150);
            case ENEMY_SHOOTER: return Color.rgb(191, 116, 213);
            case ENEMY_MINIBOSS: return Color.rgb(177, 65, 57);
            case ENEMY_BOSS: return Color.rgb(116, 35, 43);
            default: return Color.rgb(178, 178, 158);
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
        int glow = chest.kind == CHEST_WEAPON ? Color.rgb(255, 120, 75)
                : chest.kind == CHEST_ARTIFACT ? Color.rgb(215, 105, 255)
                : Color.rgb(255, 210, 70);
        paint.setColor(withAlpha(glow, 80));
        c.drawCircle(chest.x, chest.y, 42f * pulse, paint);
        paint.setColor(chest.kind == CHEST_WEAPON ? Color.rgb(145, 55, 36)
                : chest.kind == CHEST_ARTIFACT ? Color.rgb(92, 48, 120)
                : Color.rgb(132, 75, 30));
        c.drawRoundRect(chest.x - 24f, chest.y - 18f, chest.x + 24f, chest.y + 20f, 7f, 7f, paint);
        paint.setColor(glow);
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
            float r = (115f + auraLevel * 16f) * effectRangeScale();
            stroke.setColor(Color.argb(105, 75, 220, 255));
            stroke.setStrokeWidth(5f + auraLevel);
            c.drawCircle(px, py, r, stroke);
        }
        if (orbitLevel > 0) {
            int blades = Math.min(8, 2 + orbitLevel);
            float orbitRadius = (88f + orbitLevel * 4f) * effectRangeScale();
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
        float scale = Math.min(1.22f, uiScale(w, h));
        float controlScale = uiScale(w, h);
        // Le bouton de sélection automatique n'existe que dans le menu pause.
        autoRect.setEmpty();
        float pad = 12f * scale;
        float top = 8f * scale;
        paint.setTextAlign(Paint.Align.LEFT);
        paint.setFakeBoldText(true);
        paint.setColor(Color.WHITE);
        paint.setTextSize(21f * scale);
        int minutes = (int) elapsed / 60;
        int seconds = (int) elapsed % 60;
        float firstLine = top + 22f * scale;
        c.drawText(String.format(Locale.US, "%02d:%02d", minutes, seconds), pad, firstLine, paint);
        paint.setTextSize(11f * scale);
        paint.setColor(Color.rgb(190, 210, 215));
        c.drawText("DIFF ×" + oneDecimal(difficulty), pad + 74f * scale, firstLine - 1f * scale, paint);

        float pauseSize = 44f * scale;
        pauseRect.set(w - pad - pauseSize, top, w - pad, top + pauseSize);
        paint.setColor(Color.argb(165, 20, 28, 31));
        c.drawRoundRect(pauseRect, 12f * scale, 12f * scale, paint);
        paint.setColor(Color.WHITE);
        float barW = 4f * scale;
        float barH = 18f * scale;
        c.drawRect(pauseRect.centerX() - 7f * scale, pauseRect.centerY() - barH / 2f,
                pauseRect.centerX() - 7f * scale + barW, pauseRect.centerY() + barH / 2f, paint);
        c.drawRect(pauseRect.centerX() + 3f * scale, pauseRect.centerY() - barH / 2f,
                pauseRect.centerX() + 3f * scale + barW, pauseRect.centerY() + barH / 2f, paint);

        float hpW = w - pad * 2f;
        float hpY = top + 32f * scale;
        float hpH = 11f * scale;
        paint.setColor(Color.argb(175, 0, 0, 0));
        c.drawRoundRect(pad, hpY, pad + hpW, hpY + hpH, 6f * scale, 6f * scale, paint);
        paint.setColor(Color.rgb(235, 72, 72));
        c.drawRoundRect(pad, hpY, pad + hpW * Math.max(0f, hp / maxHp), hpY + hpH, 6f * scale, 6f * scale, paint);
        paint.setTextSize(9f * scale);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setColor(Color.WHITE);
        c.drawText(Math.round(hp) + "/" + Math.round(maxHp), w / 2f, hpY + 9f * scale, paint);

        float xpY = hpY + 16f * scale;
        float xpH = 6f * scale;
        paint.setColor(Color.argb(175, 0, 0, 0));
        c.drawRoundRect(pad, xpY, pad + hpW, xpY + xpH, 3f * scale, 3f * scale, paint);
        paint.setColor(Color.rgb(65, 175, 255));
        c.drawRoundRect(pad, xpY, pad + hpW * Math.min(1f, xp / nextXp), xpY + xpH, 3f * scale, 3f * scale, paint);

        paint.setTextAlign(Paint.Align.LEFT);
        paint.setTextSize(11f * scale);
        paint.setColor(Color.WHITE);
        float statsY = xpY + 19f * scale;
        c.drawText("NIV " + level + "   ☠ " + kills + "   SCORE " + score, pad, statsY, paint);

        drawMapHud(c, w, xpY + 32f * scale, scale);

        if (joystickPointer >= 0) {
            paint.setColor(Color.argb(65, 255, 255, 255));
            c.drawCircle(joyStartX, joyStartY, joyRadius, paint);
            stroke.setColor(Color.argb(150, 255, 255, 255));
            stroke.setStrokeWidth(3f * controlScale);
            c.drawCircle(joyStartX, joyStartY, joyRadius, stroke);
            paint.setColor(Color.argb(145, 255, 255, 255));
            c.drawCircle(joyStartX + joyX * joyRadius, joyStartY + joyY * joyRadius, 24f * controlScale, paint);
        }
        paint.setFakeBoldText(false);
    }

    private void drawMapHud(Canvas c, int w, float y, float scale) {
        int complete = 0;
        for (WorldObjective objective : worldObjectives) if (objective.complete) complete++;
        float left = 12f * scale;
        float right = Math.min(w - 12f * scale, left + 158f * scale);
        RectF box = new RectF(left, y, right, y + 25f * scale);
        paint.setColor(Color.argb(155, 18, 35, 36));
        c.drawRoundRect(box, 9f * scale, 9f * scale, paint);
        paint.setTextAlign(Paint.Align.LEFT);
        paint.setFakeBoldText(true);
        paint.setTextSize(10f * scale);
        paint.setColor(Color.rgb(130, 235, 205));
        c.drawText("ZONE " + worldStage + "   •   " + complete + "/" + worldObjectives.size(),
                left + 9f * scale, y + 16f * scale, paint);
        paint.setFakeBoldText(false);

        float radius = 19f * scale;
        float centerX = w - 12f * scale - radius;
        float centerY = y + radius;
        paint.setColor(Color.argb(175, 10, 22, 24));
        c.drawCircle(centerX, centerY, radius, paint);
        stroke.setColor(Color.argb(190, 105, 225, 190));
        stroke.setStrokeWidth(2f * scale);
        c.drawCircle(centerX, centerY, radius, stroke);
        float mapScale = radius / Math.max(1f, worldRadius);
        for (WorldObjective objective : worldObjectives) {
            if (objective.complete || !objective.revealed) continue;
            paint.setColor(objective.type == OBJECTIVE_CAPTURE ? Color.rgb(90, 220, 255)
                    : objective.type == OBJECTIVE_HIDEOUT ? Color.rgb(245, 180, 95)
                    : objective.type == OBJECTIVE_BONUS ? Color.rgb(100, 245, 145)
                    : objective.type == OBJECTIVE_SECRET ? Color.rgb(225, 120, 255)
                    : Color.rgb(255, 215, 75));
            c.drawCircle(centerX + objective.x * mapScale, centerY + objective.y * mapScale,
                    2.3f * scale, paint);
        }
        paint.setColor(Color.WHITE);
        c.drawCircle(centerX + px * mapScale, centerY + py * mapScale, 2.8f * scale, paint);
    }

    private void drawChoices(Canvas c, int w, int h) {
        paint.setColor(Color.argb(levelRevealing ? 172 : 220, 7, 11, 13));
        c.drawRect(0, 0, w, h, paint);
        if (chestRevealing) {
            drawChestReveal(c, w, h);
            return;
        }
        if (levelRevealing) {
            drawLevelReveal(c, w, h);
            return;
        }
        float scale = uiScale(w, h);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setFakeBoldText(true);
        paint.setColor(Color.WHITE);
        paint.setTextSize(29f * scale);
        String heading = choiceSource == CHOICE_WEAPON ? "ARME DE BOSS"
                : choiceSource == CHOICE_ARTIFACT ? "COFFRE D'ARTEFACT"
                : chestChoice ? "COFFRE" : "NIVEAU " + level;
        c.drawText(heading, w / 2f, 74f * scale, paint);
        paint.setTextSize(16f * scale);
        paint.setColor(Color.rgb(180, 200, 205));
        c.drawText("Choisis avec le pouce", w / 2f, 101f * scale, paint);

        float margin = 12f * scale;
        float gap = 8f * scale;
        float cardBottom = h - 24f * scale;
        float cardTop = Math.max(h * 0.58f, cardBottom - 220f * scale);
        float cardW = (w - margin * 2f - gap * 2f) / 3f;
        for (int i = 0; i < currentChoices.size() && i < 3; i++) {
            Upgrade u = currentChoices.get(i);
            float left = margin + i * (cardW + gap);
            choiceRects[i].set(left, cardTop, left + cardW, cardBottom);
            paint.setColor(cardColor(u.rarity));
            c.drawRoundRect(choiceRects[i], 20f * scale, 20f * scale, paint);
            stroke.setColor(rarityColor(u.rarity));
            stroke.setStrokeWidth(4f * scale);
            c.drawRoundRect(choiceRects[i], 20f * scale, 20f * scale, stroke);
            drawUpgradeIcon(c, u, choiceRects[i].left + 29f * scale, cardTop + 29f * scale, 16f * scale);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setColor(rarityColor(u.rarity));
            paint.setTextSize(13f * scale);
            c.drawText(rarityName(u.rarity), choiceRects[i].centerX(), cardTop + 27f * scale, paint);
            paint.setColor(Color.WHITE);
            paint.setTextSize(16f * scale);
            drawWrappedText(c, u.title, choiceRects[i].centerX(), cardTop + 57f * scale,
                    cardW - 22f * scale, 19f * scale, 2);
            paint.setFakeBoldText(false);
            paint.setColor(Color.rgb(214, 224, 226));
            paint.setTextSize(13f * scale);
            drawWrappedText(c, u.description, choiceRects[i].centerX(), cardTop + 111f * scale,
                    cardW - 22f * scale, 17f * scale, 3);
            paint.setFakeBoldText(true);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setColor(Color.WHITE);
            paint.setTextSize(13f * scale);
            c.drawText("CHOISIR", choiceRects[i].centerX(), cardBottom - 17f * scale, paint);
        }
        paint.setFakeBoldText(false);
    }

    private int utilityColor(String code) {
        if (code.startsWith("ART_")) return Color.rgb(205, 115, 255);
        switch (code) {
            case "HP": case "HEAL": case "REGEN": case "ART_WARD":
                return Color.rgb(75, 220, 130);
            case "DMG": case "FIRE": case "CRIT": case "MULTI": case "ROCKET":
                return Color.rgb(245, 105, 85);
            case "ARMOR":
                return Color.rgb(90, 155, 255);
            case "SPEED": case "MAGNET": case "RANGE": case "ART_COMPASS": case "ART_HOURGLASS":
                return Color.rgb(75, 205, 235);
            case "AURA":
                return Color.rgb(220, 95, 245);
            case "ORBIT":
                return Color.rgb(100, 225, 205);
            case "LIGHTNING":
                return Color.rgb(255, 215, 75);
            default:
                return Color.rgb(185, 200, 205);
        }
    }

    private void drawUpgradeIcon(Canvas c, Upgrade u, float cx, float cy, float radius) {
        int color = utilityColor(u.code);
        paint.setColor(withAlpha(color, 205));
        c.drawCircle(cx, cy, radius, paint);
        stroke.setColor(Color.argb(235, 245, 250, 250));
        stroke.setStrokeWidth(Math.max(1.5f, radius * 0.12f));
        stroke.setStrokeCap(Paint.Cap.ROUND);
        c.drawCircle(cx, cy, radius, stroke);
        float r = radius * 0.55f;
        switch (u.code) {
            case "HP": case "HEAL": case "REGEN": case "ART_WARD":
                c.drawLine(cx - r, cy, cx + r, cy, stroke);
                c.drawLine(cx, cy - r, cx, cy + r, stroke);
                break;
            case "DMG":
                c.drawLine(cx - r, cy + r, cx, cy - r, stroke);
                c.drawLine(cx, cy - r, cx + r, cy + r, stroke);
                c.drawLine(cx - r * 0.25f, cy + r * 0.25f, cx + r * 0.55f, cy - r * 0.45f, stroke);
                break;
            case "FIRE":
                c.drawLine(cx - r, cy + r * 0.45f, cx - r * 0.25f, cy - r * 0.45f, stroke);
                c.drawLine(cx - r * 0.15f, cy + r * 0.45f, cx + r * 0.55f, cy - r * 0.45f, stroke);
                c.drawLine(cx + r * 0.7f, cy + r * 0.45f, cx + r, cy - r * 0.1f, stroke);
                break;
            case "CRIT":
                c.drawCircle(cx, cy, r * 0.55f, stroke);
                c.drawLine(cx - r, cy, cx + r, cy, stroke);
                c.drawLine(cx, cy - r, cx, cy + r, stroke);
                break;
            case "MULTI":
                c.drawCircle(cx - r * 0.55f, cy, r * 0.23f, paint);
                c.drawCircle(cx, cy, r * 0.23f, paint);
                c.drawCircle(cx + r * 0.55f, cy, r * 0.23f, paint);
                break;
            case "SPEED":
                c.drawLine(cx - r, cy + r * 0.6f, cx, cy - r * 0.6f, stroke);
                c.drawLine(cx, cy - r * 0.6f, cx + r, cy + r * 0.6f, stroke);
                break;
            case "RANGE": case "MAGNET":
                c.drawCircle(cx, cy, r * 0.55f, stroke);
                c.drawCircle(cx, cy, r, stroke);
                break;
            case "AURA":
                c.drawCircle(cx, cy, r * 0.42f, paint);
                c.drawCircle(cx, cy, r, stroke);
                break;
            case "ORBIT":
                c.drawCircle(cx, cy, r * 0.42f, stroke);
                c.drawCircle(cx + r * 0.75f, cy - r * 0.5f, r * 0.2f, paint);
                break;
            case "LIGHTNING":
                c.drawLine(cx - r * 0.1f, cy - r, cx - r * 0.65f, cy, stroke);
                c.drawLine(cx - r * 0.65f, cy, cx + r * 0.15f, cy, stroke);
                c.drawLine(cx + r * 0.15f, cy, cx - r * 0.1f, cy + r, stroke);
                break;
            case "ROCKET":
                c.drawLine(cx - r * 0.7f, cy + r * 0.65f, cx + r * 0.75f, cy, stroke);
                c.drawLine(cx + r * 0.75f, cy, cx - r * 0.7f, cy - r * 0.65f, stroke);
                c.drawLine(cx - r * 0.7f, cy - r * 0.65f, cx - r * 0.7f, cy + r * 0.65f, stroke);
                break;
            case "ART_CROWN": case "ART_COMPASS": case "ART_HOURGLASS":
                c.drawLine(cx, cy - r, cx + r, cy, stroke);
                c.drawLine(cx + r, cy, cx, cy + r, stroke);
                c.drawLine(cx, cy + r, cx - r, cy, stroke);
                c.drawLine(cx - r, cy, cx, cy - r, stroke);
                break;
            default:
                c.drawCircle(cx, cy, r * 0.45f, paint);
                break;
        }
        stroke.setStrokeCap(Paint.Cap.BUTT);
    }

    private void drawChestReveal(Canvas c, int w, int h) {
        float scale = uiScale(w, h);
        float t = chestRevealTime;
        float open = smoothStep(0.42f, 1.28f, t);
        float lock = smoothStep(1.16f, 1.48f, t);
        int shownRarity = t < 1.16f ? (int) (t * (15f - Math.min(8f, t * 5f))) % 4 : chestRevealRarity;
        int color = rarityColor(shownRarity);
        float cx = w * 0.5f;
        float cy = h * 0.56f;
        float shake = (1f - open) * (float) Math.sin(t * 52f) * 5f * scale;
        float pulse = 1f + (float) Math.sin(t * 12f) * (0.035f + lock * 0.035f);

        if (t > 0.38f) {
            float rays = smoothStep(0.38f, 1.35f, t);
            c.save();
            c.rotate(t * 24f, cx, cy);
            stroke.setStrokeWidth(3f * scale);
            for (int i = 0; i < 12; i++) {
                float a = (float) (Math.PI * 2.0 * i / 12.0);
                float inner = (74f + 16f * rays) * scale;
                float outer = (112f + 45f * rays) * scale;
                stroke.setColor(withAlpha(color, (int) (28 + 74 * rays)));
                c.drawLine(cx + (float) Math.cos(a) * inner, cy + (float) Math.sin(a) * inner,
                        cx + (float) Math.cos(a) * outer, cy + (float) Math.sin(a) * outer, stroke);
            }
            c.restore();
        }
        paint.setColor(withAlpha(color, (int) (35 + 35 * open)));
        c.drawCircle(cx, cy, (118f + 26f * lock) * scale * pulse, paint);
        paint.setColor(withAlpha(color, (int) (60 + 58 * open)));
        c.drawCircle(cx, cy, 78f * scale * pulse, paint);
        stroke.setColor(color);
        stroke.setStrokeWidth((4f + lock * 4f) * scale);
        c.drawCircle(cx, cy, (68f + lock * 18f) * scale, stroke);

        float chestX = cx + shake;
        float lidLift = open * 45f * scale;
        paint.setColor(withAlpha(color, (int) (35 + 75 * open)));
        c.drawRoundRect(chestX - 45f * scale, cy - 104f * scale,
                chestX + 45f * scale, cy + 4f * scale, 30f * scale, 30f * scale, paint);
        paint.setColor(chestRevealKind == CHEST_WEAPON ? Color.rgb(145, 55, 36)
                : chestRevealKind == CHEST_ARTIFACT ? Color.rgb(92, 48, 120)
                : Color.rgb(132, 75, 30));
        c.drawRoundRect(chestX - 63f * scale, cy - 12f * scale,
                chestX + 63f * scale, cy + 58f * scale, 14f * scale, 14f * scale, paint);
        stroke.setColor(withAlpha(color, 220));
        stroke.setStrokeWidth(3f * scale);
        c.drawRoundRect(chestX - 63f * scale, cy - 12f * scale,
                chestX + 63f * scale, cy + 58f * scale, 14f * scale, 14f * scale, stroke);
        c.save();
        c.rotate(-12f * open, chestX - 55f * scale, cy - 5f * scale - lidLift);
        paint.setColor(color);
        c.drawRoundRect(chestX - 68f * scale, cy - 40f * scale - lidLift,
                chestX + 68f * scale, cy - 5f * scale - lidLift, 12f * scale, 12f * scale, paint);
        c.restore();

        paint.setColor(Color.rgb(235, 205, 120));
        c.drawRoundRect(chestX - 9f * scale, cy + 5f * scale,
                chestX + 9f * scale, cy + 28f * scale, 4f * scale, 4f * scale, paint);

        if (open > 0.35f) {
            for (int i = 0; i < 10; i++) {
                float a = (float) (Math.PI * 2.0 * i / 10.0 + t * (i % 2 == 0 ? 0.55 : -0.42));
                float d = (52f + open * (35f + i * 4f)) * scale;
                paint.setColor(withAlpha(color, (int) (80 + 150 * lock)));
                c.drawCircle(cx + (float) Math.cos(a) * d,
                        cy + (float) Math.sin(a) * d, (2.5f + i % 3) * scale, paint);
            }
        }

        paint.setTextAlign(Paint.Align.CENTER);
        paint.setFakeBoldText(true);
        paint.setColor(Color.WHITE);
        paint.setTextSize(25f * scale);
        c.drawText(t < 1.16f ? "OUVERTURE DU COFFRE" : "BUTIN RÉVÉLÉ", cx, 82f * scale, paint);
        paint.setTextSize(16f * scale);
        paint.setColor(Color.rgb(200, 215, 218));
        c.drawText(chestRevealKind == CHEST_WEAPON ? "Récompense de boss"
                : chestRevealKind == CHEST_ARTIFACT ? "Artefact d'élite"
                : "Butin récupéré", cx, 110f * scale, paint);
        paint.setTextSize((23f + lock * 7f) * scale);
        paint.setColor(color);
        c.drawText(rarityName(shownRarity), cx, cy + 116f * scale, paint);
        paint.setFakeBoldText(false);
    }

    private void drawLevelReveal(Canvas c, int w, int h) {
        float scale = uiScale(w, h);
        float t = Math.min(1f, levelRevealTime / 0.72f);
        float eased = smoothStep(0f, 1f, t);
        float cx = w * 0.5f;
        float cy = h * 0.43f;
        int color = Color.rgb(85, 195, 255);
        float radius = (48f + eased * 26f) * scale;

        paint.setColor(withAlpha(color, (int) (42 * (1f - t) + 25)));
        c.drawCircle(cx, cy, radius * 1.28f, paint);
        stroke.setColor(withAlpha(color, (int) (210 * (1f - t * 0.35f))));
        stroke.setStrokeWidth(3f * scale);
        c.drawCircle(cx, cy, radius, stroke);
        stroke.setStrokeWidth(1.5f * scale);
        c.drawCircle(cx, cy, radius * 0.72f, stroke);

        paint.setTextAlign(Paint.Align.CENTER);
        paint.setFakeBoldText(true);
        paint.setColor(Color.WHITE);
        paint.setTextSize(14f * scale);
        c.drawText("NIVEAU", cx, cy - 7f * scale, paint);
        paint.setTextSize(31f * scale);
        c.drawText(String.valueOf(level), cx, cy + 27f * scale, paint);
        paint.setTextSize(15f * scale);
        paint.setColor(Color.rgb(190, 222, 232));
        c.drawText("NOUVELLE AMÉLIORATION", cx, cy + 104f * scale, paint);
        paint.setFakeBoldText(false);
    }

    private float smoothStep(float edge0, float edge1, float value) {
        float x = Math.max(0f, Math.min(1f, (value - edge0) / Math.max(0.0001f, edge1 - edge0)));
        return x * x * (3f - 2f * x);
    }

    private void drawWrappedText(Canvas c, String text, float centerX, float firstBaseline,
                                 float maxWidth, float lineHeight, int maxLines) {
        String[] words = text.split(" ");
        String line = "";
        int lineIndex = 0;
        for (String word : words) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (!line.isEmpty() && paint.measureText(candidate) > maxWidth) {
                c.drawText(line, centerX, firstBaseline + lineIndex * lineHeight, paint);
                lineIndex++;
                if (lineIndex >= maxLines) return;
                line = word;
            } else {
                line = candidate;
            }
        }
        if (!line.isEmpty() && lineIndex < maxLines) {
            c.drawText(line, centerX, firstBaseline + lineIndex * lineHeight, paint);
        }
    }

    private void drawPause(Canvas c, int w, int h) {
        float scale = uiScale(w, h);
        paint.setColor(Color.argb(225, 7, 11, 13));
        c.drawRect(0, 0, w, h, paint);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setFakeBoldText(true);
        paint.setColor(Color.WHITE);
        paint.setTextSize(36f * scale);
        c.drawText("PAUSE", w / 2f, h * 0.21f, paint);
        float x1 = 28f * scale;
        float x2 = w - 28f * scale;
        float y = h * 0.29f;
        float buttonH = 72f * scale;
        float gap = 18f * scale;
        resumeRect.set(x1, y, x2, y + buttonH);
        soundRect.set(x1, y + buttonH + gap, x2, y + buttonH * 2f + gap);
        autoRect.set(x1, y + (buttonH + gap) * 2f, x2, y + buttonH * 3f + gap * 2f);
        restartRect.set(x1, y + (buttonH + gap) * 3f, x2, y + buttonH * 4f + gap * 3f);
        quitRect.set(x1, y + (buttonH + gap) * 4f, x2, y + buttonH * 5f + gap * 4f);
        drawMenuButton(c, resumeRect, "REPRENDRE", Color.rgb(52, 145, 92));
        drawMenuButton(c, soundRect, soundOn ? "SON : OUI" : "SON : NON", Color.rgb(45, 77, 90));
        drawMenuButton(c, autoRect, autoChoice ? "SÉLECTION AUTO : OUI" : "SÉLECTION AUTO : NON",
                autoChoice ? Color.rgb(53, 150, 95) : Color.rgb(66, 72, 75));
        drawMenuButton(c, restartRect, "RECOMMENCER", Color.rgb(80, 70, 45));
        drawMenuButton(c, quitRect, "QUITTER", Color.rgb(105, 48, 48));
        paint.setFakeBoldText(false);
    }

    private void drawDeath(Canvas c, int w, int h) {
        float scale = uiScale(w, h);
        paint.setColor(Color.argb(230, 10, 9, 11));
        c.drawRect(0, 0, w, h, paint);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setFakeBoldText(true);
        paint.setColor(Color.rgb(255, 90, 90));
        paint.setTextSize(34f * scale);
        c.drawText("PARTIE TERMINÉE", w / 2f, h * 0.31f, paint);
        paint.setColor(Color.WHITE);
        paint.setTextSize(18f * scale);
        c.drawText("Niveau " + level + "   •   " + kills + " éliminations", w / 2f, h * 0.38f, paint);
        paint.setTextSize(23f * scale);
        c.drawText("Score " + score, w / 2f, h * 0.43f, paint);
        float margin = 30f * scale;
        float buttonH = 76f * scale;
        float gap = 22f * scale;
        restartRect.set(margin, h * 0.52f, w - margin, h * 0.52f + buttonH);
        drawMenuButton(c, restartRect, "RECOMMENCER", Color.rgb(52, 145, 92));
        quitRect.set(margin, h * 0.52f + buttonH + gap, w - margin, h * 0.52f + buttonH * 2f + gap);
        drawMenuButton(c, quitRect, "QUITTER", Color.rgb(95, 48, 48));
        paint.setFakeBoldText(false);
    }

    private void drawBanner(Canvas c, int w, int h) {
        float scale = uiScale(w, h);
        float a = Math.min(1f, bannerLife * 1.8f);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setFakeBoldText(true);
        paint.setTextSize(30f * scale);
        paint.setColor(Color.argb((int) (255f * a), 255, 235, 130));
        c.drawText(banner, w / 2f, h * 0.26f, paint);
        paint.setFakeBoldText(false);
    }

    private void drawMenuButton(Canvas c, RectF r, String text, int color) {
        float scale = uiScale(getWidth(), getHeight());
        paint.setColor(color);
        c.drawRoundRect(r, 18f * scale, 18f * scale, paint);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setColor(Color.WHITE);
        paint.setTextSize(20f * scale);
        c.drawText(text, r.centerX(), r.centerY() + 7f * scale, paint);
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
            default: return "CLASSIQUE";
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
                else if (autoRect.contains(x, y)) {
                    autoChoice = !autoChoice;
                    showBanner(autoChoice ? "SÉLECTION AUTO ACTIVÉE" : "SÉLECTION AUTO DÉSACTIVÉE");
                }
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
        final int kind;
        final int objectiveId;
        Chest(float x, float y, int kind, int objectiveId) {
            this.x = x;
            this.y = y;
            this.kind = kind;
            this.objectiveId = objectiveId;
        }
    }

    private static final class WorldObjective {
        final int id;
        final int type;
        final float x, y;
        boolean revealed;
        boolean activated;
        boolean complete;
        float progress;
        WorldObjective(int id, int type, float x, float y) {
            this.id = id;
            this.type = type;
            this.x = x;
            this.y = y;
        }
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
