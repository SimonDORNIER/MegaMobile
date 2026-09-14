package com.megamobile.game;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

import java.lang.reflect.Field;

/** Tiny V0.7.2 update used to validate the automatic updater end-to-end. */
public class GameViewV72 extends GameViewV71 {
    private final Paint testPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Field fInMenu;

    public GameViewV72(Context context) {
        super(context);
        testPaint.setTextAlign(Paint.Align.CENTER);
        try {
            fInMenu = GameViewFinal.class.getDeclaredField("inMenu");
            fInMenu.setAccessible(true);
        } catch (Exception ignored) { }
    }

    private boolean inMenu72() {
        try { return fInMenu != null && fInMenu.getBoolean(this); }
        catch (Exception ignored) { return false; }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!inMenu72()) return;

        testPaint.setFakeBoldText(true);
        testPaint.setTextSize(15f);
        testPaint.setColor(Color.rgb(255, 215, 90));
        canvas.drawText("V0.7.2 — TEST MAJ AUTO OK", getWidth() * 0.5f, getHeight() - 38f, testPaint);
    }
}
