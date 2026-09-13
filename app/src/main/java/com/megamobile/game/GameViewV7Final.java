package com.megamobile.game;

import android.content.Context;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.Map;

/** Final V0.7 wrapper that resets all director layers cleanly between runs. */
public class GameViewV7Final extends GameViewV7 {
    private Field baseElapsed, baseKills;
    private float lastElapsedSeen;

    public GameViewV7Final(Context context) {
        super(context);
        try {
            baseElapsed = GameView.class.getDeclaredField("elapsed");
            baseElapsed.setAccessible(true);
            baseKills = GameView.class.getDeclaredField("kills");
            baseKills.setAccessible(true);
        } catch (Exception ignored) { }
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        super.doFrame(frameTimeNanos);
        float elapsed = readFloat(baseElapsed);
        if (lastElapsedSeen > 5f && elapsed + 1f < lastElapsedSeen) resetRunLayers();
        lastElapsedSeen = elapsed;
    }

    private float readFloat(Field f) {
        try { return f == null ? 0f : f.getFloat(this); }
        catch (Exception ignored) { return 0f; }
    }

    private int readInt(Field f) {
        try { return f == null ? 0 : f.getInt(this); }
        catch (Exception ignored) { return 0; }
    }

    private void resetRunLayers() {
        setV7("currentAct", 1);
        setV7("eliteTimer", 18f);
        setV7("pressureTimer", 2.5f);
        setV7("meteorTimer", 42f);
        setV7("beaconTimer", 66f);
        setV7("previousKills", readInt(baseKills));
        setV7("combo", 0);
        setV7("comboTier", 0);
        setV7("comboTimer", 0f);
        setV7("directorLabel", "");
        setV7("directorLabelLife", 0f);
        setV7("beacon", null);
        clearV7("elites");
        clearV7("bossPhase2");
        clearV7("meteors");

        // V0.6 temporary effects must not leak into a freshly reset base run.
        setV6("frenzyActive", false);
        setV6("frenzyTimer", 0f);
        setV6("eventIndex", 0);
        setV6("eventTimer", 58f);
        setV6("relicTimer", 34f);
        setV6("previousDead", false);
        clearV6("relics");
    }

    private void setV7(String name, Object value) {
        setField(GameViewV7.class, name, value);
    }

    private void setV6(String name, Object value) {
        setField(GameViewV6.class, name, value);
    }

    private void setField(Class<?> owner, String name, Object value) {
        try {
            Field f = owner.getDeclaredField(name);
            f.setAccessible(true);
            if (value instanceof Integer) f.setInt(this, (Integer) value);
            else if (value instanceof Float) f.setFloat(this, (Float) value);
            else if (value instanceof Boolean) f.setBoolean(this, (Boolean) value);
            else f.set(this, value);
        } catch (Exception ignored) { }
    }

    private void clearV7(String name) { clearField(GameViewV7.class, name); }
    private void clearV6(String name) { clearField(GameViewV6.class, name); }

    private void clearField(Class<?> owner, String name) {
        try {
            Field f = owner.getDeclaredField(name);
            f.setAccessible(true);
            Object value = f.get(this);
            if (value instanceof Collection) ((Collection<?>) value).clear();
            else if (value instanceof Map) ((Map<?, ?>) value).clear();
        } catch (Exception ignored) { }
    }
}
