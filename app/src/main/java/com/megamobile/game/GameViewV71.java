package com.megamobile.game;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

import java.lang.reflect.Field;

/** V0.7.1: explicit installed-version marker so update state is unambiguous. */
public class GameViewV71 extends GameViewV7Final {
    private final Paint versionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Field fInMenu;

    public GameViewV71(Context context) {
        super(context);
        versionPaint.setTextAlign(Paint.Align.CENTER);
        try {
            fInMenu = GameViewFinal.class.getDeclaredField("inMenu");
            fInMenu.setAccessible(true);
        } catch (Exception ignored) { }
    }

    private boolean inMenu71() {
        try { return fInMenu != null && fInMenu.getBoolean(this); }
        catch (Exception ignored) { return false; }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!inMenu71()) return;

        versionPaint.setFakeBoldText(true);
        versionPaint.setTextSize(17f);
        versionPaint.setColor(Color.rgb(115, 235, 185));
        canvas.drawText("MEGAMOBILE V0.7.1", getWidth() * 0.5f, getHeight() - 76f, versionPaint);

        versionPaint.setFakeBoldText(false);
        versionPaint.setTextSize(12f);
        versionPaint.setColor(Color.rgb(165, 195, 200));
        canvas.drawText("mise à jour automatique active", getWidth() * 0.5f, getHeight() - 58f, versionPaint);
    }
}
