package com.megamobile.game;

import android.content.Context;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Random;

/** Behavioral checks against production code; Android calls are stubbed by the Python runner. */
public final class GameViewChecks {
    private static void require(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }

    private static Field field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static Object read(Object target, String name) throws Exception { return field(target, name).get(target); }
    private static float number(Object target, String name) throws Exception { return ((Number) read(target, name)).floatValue(); }
    private static void set(Object target, String name, Object value) throws Exception { field(target, name).set(target, value); }

    @SuppressWarnings("unchecked")
    private static List<Object> list(GameView game, String name) throws Exception { return (List<Object>) read(game, name); }

    private static Object call(GameView game, String name, Object... args) throws Exception {
        Class<?>[] types = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) {
            types[i] = args[i] instanceof Float ? float.class : args[i] instanceof Integer ? int.class
                    : args[i] instanceof Boolean ? boolean.class : args[i].getClass();
        }
        Method method = GameView.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(game, args);
    }

    private static GameView game() throws Exception {
        GameView game = new GameView(new Context());
        ((Random) read(game, "random")).setSeed(42);
        set(game, "worldReady", true);
        set(game, "worldRadius", 100000f);
        return game;
    }

    private static Object enemy(GameView game, int type, float x, float y) throws Exception {
        Class<?> enemyType = Class.forName("com.megamobile.game.GameView$Enemy");
        Constructor<?> constructor = enemyType.getDeclaredConstructor(int.class, float.class, float.class,
                float.class, float.class, float.class, float.class);
        constructor.setAccessible(true);
        Object enemy = constructor.newInstance(type, x, y, type == 4 ? 48f : 18f, 100f, 0f, 20f);
        list(game, "enemies").add(enemy);
        return enemy;
    }

    private static Object upgrade(String code, int rarity) throws Exception {
        Class<?> type = Class.forName("com.megamobile.game.GameView$Upgrade");
        Constructor<?> constructor = type.getDeclaredConstructor(String.class, int.class, String.class, String.class);
        constructor.setAccessible(true);
        return constructor.newInstance(code, rarity, code, code);
    }

    private static void waves() throws Exception {
        require(GameBalance.wavePhase(0) == 0 && GameBalance.wavePhase(44) == 2 && GameBalance.wavePhase(55) == 3,
                "Opening, peak and recovery must alternate");
        require(GameBalance.hordeTarget(55, 10) < GameBalance.hordeTarget(44, 10), "Recovery must reduce reinforcements");
        float previous = 0;
        for (int second = 0; second <= 7200; second++) {
            float difficulty = GameBalance.difficulty(second, 1 + second / 30, 58);
            require(Float.isFinite(difficulty) && difficulty >= previous, "Difficulty must keep increasing smoothly");
            require(GameBalance.enemyCap(second, 1000, 240, false) <= 160, "Directors must share a population ceiling");
            previous = difficulty;
        }
        GameView game = game();
        for (int i = 0; i < 400; i++) call(game, "spawnEnemy", false);
        require(list(game, "enemies").size() <= 25, "Opening cannot be flooded by cumulative directors");
        set(game, "elapsed", 55f);
        list(game, "enemies").clear();
        for (int i = 0; i < 400; i++) call(game, "spawnEnemy", false);
        require(list(game, "enemies").size() == game.combatHordeTarget(), "All spawn paths must respect recovery");
        list(game, "enemies").clear();
        set(game, "elapsed", 300f);
        for (int i = 0; i < 20; i++) call(game, "spawnEnemy", true);
        require(list(game, "enemies").size() == 2, "Overlapping events cannot stack unlimited bosses");
        float power = 20;
        for (int i = 0; i < 10000; i++) power = GameBalance.powerGain(power, 0.18f);
        require(Float.isFinite(power) && power > 20, "Long-run damage must grow without exponential overflow");
    }

    private static void loot() throws Exception {
        for (int dry = 0; dry < 22; dry++) require(!GameBalance.commonChestDrop(dry, 0.999f), "No early forced common drop");
        require(GameBalance.commonChestDrop(22, 0.999f), "A common chest cannot be withheld beyond 22 kills");
        require(GameBalance.specialEliteDrop(7, 0.999f), "Special artifacts cannot disappear in a long unlucky streak");
        require(GameBalance.chestRarity(1, 0.999f, 5, 0) == 3, "Sixth dry chest must be legendary");
        GameView game = game();
        Object boss = enemy(game, 4, 200, 0);
        call(game, "killEnemy", boss);
        Object chest = list(game, "chests").get(0);
        require(number(chest, "kind") == 1 && number(chest, "minimumRarity") == 2, "Boss must drop an epic-or-better weapon chest");
        require(game.getBossKills() == 1, "Ascension must count actual boss eliminations");
        call(game, "startChestReveal", 1, 2);
        require(number(game, "chestRevealRarity") >= 2, "Boss rarity floor must survive the opening animation");
    }

    private static void choices() throws Exception {
        GameView game = game();
        String[] fields = {"speed","range","magnet","crit","armor","regen","weaponHaste"};
        float[] limits = {650,980,900,0.85f,190,20,8};
        for (int i = 0; i < fields.length; i++) set(game, fields[i], limits[i]);
        set(game, "multi", 10);
        @SuppressWarnings("unchecked") List<String> pool = (List<String>) call(game, "availableUpgrades", 0);
        require(pool.size() == 2 && pool.contains("HP") && pool.contains("DMG"), "Healthy capped builds must receive useful stats only");
        call(game, "beginChoice", 0, -1);
        List<Object> choices = list(game, "currentChoices");
        require(choices.size() == 2 && !read(choices.get(0), "code").equals(read(choices.get(1), "code")), "Small pools must never duplicate choices");
        for (String weapon : new String[]{"auraLevel","orbitLevel","lightningLevel","rocketLevel","voidLevel"}) set(game, weapon, 8);
        set(game, "droneLevel", 4);
        pool = (List<String>) call(game, "availableUpgrades", 2);
        require(pool.size() == 1 && pool.contains("ARSENAL"), "Maxed weapons must offer mastery rather than useless levels");
        set(game, "auraLevel", 0);
        call(game, "beginChoice", 2, 2);
        require(read(list(game, "currentChoices").get(0), "code").equals("AURA"), "Incomplete arsenal must offer a new weapon");
        for (String artifact : new String[]{"artifactSoulReaperLevel","artifactBloodPactLevel","artifactBlackMirrorLevel","artifactVoidHeartLevel"}) set(game, artifact, 1);
        pool = (List<String>) call(game, "availableUpgrades", 4);
        require(pool.stream().noneMatch(code -> code.equals("ART_SOUL_REAPER") || code.equals("ART_BLOOD_PACT")
                || code.equals("ART_BLACK_MIRROR") || code.equals("ART_VOID_HEART")), "Unique artifacts cannot be obtained twice");
    }

    private static void auto() throws Exception {
        GameView game = game();
        set(game, "autoChoice", true);
        set(game, "hp", 25f);
        set(game, "moveX", 1f);
        set(game, "joystickPointer", 7);
        call(game, "beginChoice", 0, -1);
        List<Object> choices = list(game, "currentChoices");
        choices.clear();
        choices.add(upgrade("DMG", 3));
        choices.add(upgrade("HEAL", 0));
        require(!game.isChoiceBlockingGameplay(), "Automatic choice must never block combat");
        game.resumeGame();
        game.doFrame(1_000_000_000L);
        game.doFrame(1_040_000_000L);
        require(number(game, "px") > 0 && number(game, "elapsed") > 0, "Movement and simulation must continue during choice");
        call(game, "selectBestUpgrade");
        require(number(game, "hp") > 25 && number(game, "damage") == 20, "Critical health must outweigh a legendary offensive pick");
        require(number(game, "joystickPointer") == 7 && number(game, "moveX") == 1, "Auto selection must preserve the held joystick");
        set(game, "autoChoice", false);
        call(game, "beginChoice", 0, -1);
        require(game.isChoiceBlockingGameplay(), "Manual choice must remain paused");
        float before = number(game, "elapsed");
        game.doFrame(1_080_000_000L);
        require(number(game, "elapsed") == before, "Manual choice must pause the frame loop");
    }

    private static void xp() throws Exception {
        GameView game = game();
        set(game, "autoChoice", true);
        call(game, "gainXp", 500f);
        for (int i = 0; i < 80; i++) {
            call(game, "processPendingLevel");
            call(game, "updateVisuals", 0.5f);
        }
        require(number(game, "level") > 5, "XP earned during choices must finish processing without a new pickup");
        require(number(game, "xp") < number(game, "nextXp") && !(Boolean) read(game, "choosing"), "Queued levels must drain cleanly");
        require(GameBalance.xpForLevel(1000) < 6000, "XP must remain obtainable during endless runs");
        int generation = game.getRunGeneration();
        call(game, "resetGame");
        require(game.getRunGeneration() != generation && number(game, "level") == 1, "Restart must create a clean run");
    }

    private static void boss() throws Exception {
        GameView dodge = game();
        Object boss = enemy(dodge, 4, 200, 0);
        call(dodge, "updateEnemies", 0.02f);
        require(number(boss, "warning") > 0 && number(dodge, "hp") == 100, "Boss must warn before damaging the locked area");
        set(dodge, "px", 400f);
        for (int i = 0; i < 60; i++) call(dodge, "updateEnemies", 0.02f);
        require(number(dodge, "hp") == 100 && number(boss, "aimX") == 0, "Leaving the warning must dodge the attack");
        GameView stay = game();
        enemy(stay, 4, 200, 0);
        for (int i = 0; i < 60; i++) call(stay, "updateEnemies", 0.02f);
        require(number(stay, "hp") < 100 && number(stay, "hp") > 0, "Remaining in the warning must take a survivable hit");
    }

    private static void explosion() throws Exception {
        GameView game = game();
        set(game, "crit", 0f);
        enemy(game, 0, 100, 0);
        Object boss = enemy(game, 4, 200, 0);
        game.detonatePickup();
        require(number(game, "kills") == 1 && list(game, "gems").size() == 1, "Explosion must preserve normal kill XP");
        require(number(boss, "hp") == 50 && list(game, "enemies").contains(boss), "Explosion must damage rather than erase bosses");
        call(game, "damageEnemy", boss, 200f, false);
        require(list(game, "chests").stream().anyMatch(chest -> {
            try { return number(chest, "kind") == 1; } catch (Exception e) { throw new RuntimeException(e); }
        }), "Finishing the damaged boss must still award its weapon");
    }

    private static void lifesteal() throws Exception {
        GameView game = game();
        set(game, "hp", 10f);
        set(game, "artifactSoulReaperLevel", 1);
        set(game, "soulHealBudget", 4f);
        for (int i = 0; i < 100; i++) call(game, "killEnemy", enemy(game, 0, 200, 0));
        require(number(game, "hp") <= 14.001f, "High kill rates cannot turn lifesteal into permanent invincibility");
    }

    private static void frenzy() throws Exception {
        GameView game = game();
        set(game, "speed", 650f);
        set(game, "weaponHaste", 8f);
        game.setCombatFrenzy(true);
        require(game.globalWeaponHaste() > 8f, "Frenzy must provide a real temporary cadence boost");
        call(game, "applyUpgrade", upgrade("ART_HOURGLASS", 3));
        call(game, "applyUpgrade", upgrade("ART_COMPASS", 3));
        game.setCombatFrenzy(false);
        require(number(game, "weaponHaste") == 8 && number(game, "speed") == 650,
                "Ending a temporary boost must not divide away permanent capped stats");
    }

    private static void run() throws Exception {
        GameView game = game();
        set(game, "autoChoice", true);
        // Protect the stationary test player; this scenario checks progression and population,
        // not whether a human can survive ten minutes without moving.
        set(game, "invuln", 100000f);
        for (int i = 0; i < 15000; i++) {
            call(game, "update", 0.04f);
            call(game, "updateVisuals", 0.04f);
            require(list(game, "enemies").size() <= 165, "Population must stay bounded through overlapping boss waves");
        }
        require(number(game, "elapsed") > 590 && number(game, "kills") > 100 && number(game, "level") > 5,
                "Combat, kills and queued automatic progression must continue across many wave cycles");
        require(Float.isFinite(number(game, "damage")), "Long-run power must remain finite");
    }

    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "waves": waves(); break;
            case "loot": loot(); break;
            case "choices": choices(); break;
            case "auto": auto(); break;
            case "xp": xp(); break;
            case "boss": boss(); break;
            case "explosion": explosion(); break;
            case "lifesteal": lifesteal(); break;
            case "frenzy": frenzy(); break;
            case "run": run(); break;
            default: throw new IllegalArgumentException(args[0]);
        }
    }
}
