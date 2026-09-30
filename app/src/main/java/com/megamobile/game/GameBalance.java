package com.megamobile.game;

/** Shared, Android-independent rules for every combat director and reward source. */
final class GameBalance {
    static final float MAX_SPEED = 650f;
    static final float MAX_RANGE = 980f;
    static final float MAX_MAGNET = 900f;
    static final float MAX_CRIT = 0.85f;
    static final float MAX_ARMOR = 190f;
    static final float MAX_REGEN = 20f;
    static final float MAX_HASTE = 8f;
    static final int MAX_MULTI = 10;
    static final int MAX_WEAPON_LEVEL = 8;

    private GameBalance() { }

    // Each minute builds up, assaults, peaks, then leaves ten seconds to collect loot.
    static int wavePhase(float elapsed) {
        float time = Math.max(0f, elapsed) % 60f;
        return time < 18f ? 0 : time < 42f ? 1 : time < 50f ? 2 : 3;
    }

    static float waveIntensity(float elapsed) {
        switch (wavePhase(elapsed)) {
            case 1: return 1.0f;
            case 2: return 1.35f;
            case 3: return 0.40f;
            default: return 0.72f;
        }
    }

    static int hordeTarget(float elapsed, int level) {
        float base = Math.min(112f, 8f + elapsed / 16f + Math.min(100, level) * 0.45f);
        return Math.max(5, Math.min(130, Math.round(base * waveIntensity(elapsed))));
    }

    static int enemyCap(float elapsed, int level, int configuredCap, boolean breathing) {
        int target = hordeTarget(elapsed, level);
        return Math.max(5, Math.min(Math.min(160, configuredCap), target + (breathing ? 0 : 18)));
    }

    static float difficulty(float elapsed, int level, float configuredSeconds) {
        return 1f + elapsed / Math.max(80f, configuredSeconds)
                + (float) Math.pow(elapsed / 480f, 1.25) * 0.45f
                + Math.max(0, level - 12) * 0.01f;
    }

    static float xpForLevel(int level) {
        if (level <= 1) return 8f;
        float normal = 11f + (float) Math.pow(level, 1.22) * 4.8f;
        float lateCap = 220f + level * 4.0f + (float) Math.sqrt(level) * 30f;
        return Math.min(normal, lateCap);
    }

    static float powerGain(float current, float percent) {
        float diminishing = 1f + (float) Math.sqrt(Math.max(0f, current / 20f - 1f)) * 0.10f;
        return current * (1f + percent / diminishing);
    }

    static boolean statUseful(String code, float value) {
        switch (code) {
            case "SPEED": return value < MAX_SPEED - 1f;
            case "RANGE": return value < MAX_RANGE - 1f;
            case "MAGNET": return value < MAX_MAGNET - 1f;
            case "CRIT": return value < MAX_CRIT - 0.001f;
            case "ARMOR": return value < MAX_ARMOR - 1f;
            case "REGEN": return value < MAX_REGEN - 0.01f;
            case "FIRE": return value < MAX_HASTE - 0.01f;
            case "MULTI": return value < MAX_MULTI;
            case "HEAL": return value < 0.90f;
            default: return true;
        }
    }

    static int rarity(int source, float roll) {
        if (source == 4) return 3; // Special artifact.
        if (source == 3) return roll < 0.18f ? 3 : roll < 0.64f ? 2 : 1;
        if (source == 2) return roll < 0.14f ? 3 : roll < 0.52f ? 2 : 1;
        if (source == 1) return roll < 0.08f ? 3 : roll < 0.33f ? 2 : roll < 0.78f ? 1 : 0;
        return roll < 0.03f ? 3 : roll < 0.15f ? 2 : roll < 0.45f ? 1 : 0;
    }

    static int chestRarity(int source, float roll, int dryChests, int minimum) {
        return Math.max(minimum, dryChests >= 5 ? 3 : rarity(source, roll));
    }

    static boolean commonChestDrop(int dryKills, float roll) {
        return dryKills >= 22 || roll < 0.04f;
    }

    static boolean specialEliteDrop(int dryElites, float roll) {
        return dryElites >= 7 || roll < Math.min(0.24f, 0.12f + dryElites * 0.015f);
    }

    static float choiceScore(String code, int rarity, float hpRatio, float haste,
                             int multi, int weaponLevel, boolean weapon, boolean evolutionReady) {
        float score;
        if (weapon) {
            score = weaponLevel == 0 ? 3.4f : 2.1f;
            if (weaponLevel == 3 && evolutionReady) score += 2f;
        } else {
            switch (code) {
                case "HEAL": score = hpRatio < 0.35f ? 6f : hpRatio < 0.60f ? 3.8f : 0.7f; break;
                case "HP": case "ART_WARD": score = hpRatio < 0.50f ? 4.2f : 2.2f; break;
                case "ARMOR": case "REGEN": score = hpRatio < 0.65f ? 3.1f : 1.7f; break;
                case "FIRE": case "ART_HOURGLASS": score = 2.8f / (float) Math.sqrt(haste); break;
                case "MULTI": score = multi <= 2 ? 3.5f : 2.2f; break;
                case "DMG": case "ARSENAL": case "ART_CROWN": score = 2.5f; break;
                case "RANGE": case "ART_COMPASS": score = 1.5f; break;
                case "MAGNET": case "SPEED": score = 1.2f; break;
                case "CRIT": score = 2.0f; break;
                default: score = 3f; break;
            }
        }
        return score + rarity * 0.32f;
    }
}
