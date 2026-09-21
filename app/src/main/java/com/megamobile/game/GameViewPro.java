package com.megamobile.game;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;

import java.lang.reflect.Field;
import java.util.List;

public class GameViewPro extends GameView {
    private final Paint skinPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint badgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private RemoteSkin skin = RemoteSkin.empty();
    private String skinBadge = "";
    private long skinBadgeUntil = 0L;

    private Field fJoystickPointer, fJoyStartX, fJoyStartY, fJoyX, fJoyY, fJoyRadius, fMoveX, fMoveY;
    private Field fPaused, fDead, fChoosing, fDashRect, fPauseRect, fAutoRect;
    private Field fCamX, fCamY, fPx, fPy, fEnemies, fGems, fChests;
    private Field enemyType, enemyX, enemyY, enemyR;
    private Class<?> enemyClass;
    private Field gemX, gemY, gemValue;
    private Class<?> gemClass;
    private Field chestX, chestY;
    private Class<?> chestClass;

    public GameViewPro(Context context) {
        super(context);
        bindReflection();
        badgePaint.setTextSize(13f);
        badgePaint.setColor(Color.argb(190, 220, 245, 235));
        badgePaint.setFakeBoldText(true);
        RemoteSkin.loadAsync(context, loaded -> post(() -> {
            if (loaded != null) {
                skin = loaded;
                skinBadge = "VISUELS LIVE " + loaded.version;
                skinBadgeUntil = System.currentTimeMillis() + 4500L;
                invalidate();
            }
        }));
    }

    private void bindReflection() {
        try {
            fJoystickPointer = field("joystickPointer");
            fJoyStartX = field("joyStartX");
            fJoyStartY = field("joyStartY");
            fJoyX = field("joyX");
            fJoyY = field("joyY");
            fJoyRadius = field("joyRadius");
            fMoveX = field("moveX");
            fMoveY = field("moveY");
            fPaused = field("paused");
            fDead = field("dead");
            fChoosing = field("choosing");
            fDashRect = field("dashRect");
            fPauseRect = field("pauseRect");
            fAutoRect = field("autoRect");
            fCamX = field("camX");
            fCamY = field("camY");
            fPx = field("px");
            fPy = field("py");
            fEnemies = field("enemies");
            fGems = field("gems");
            fChests = field("chests");
        } catch (Exception ignored) { }
    }

    private Field field(String name) throws NoSuchFieldException {
        Field f = GameView.class.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    private boolean modalOpen() {
        try {
            return (fPaused != null && fPaused.getBoolean(this))
                    || (fDead != null && fDead.getBoolean(this))
                    || (fChoosing != null && fChoosing.getBoolean(this));
        } catch (Exception ignored) { return false; }
    }

    private RectF rect(Field f) {
        try { return f == null ? null : (RectF) f.get(this); }
        catch (Exception ignored) { return null; }
    }

    private boolean isControlTap(float x, float y) {
        RectF r = rect(fPauseRect);
        if (r != null && r.contains(x, y)) return true;
        r = rect(fAutoRect);
        if (r != null && r.contains(x, y)) return true;
        r = rect(fDashRect);
        if (r != null && r.contains(x, y)) return true;
        int w = getWidth(), h = getHeight();
        if (x > w - 100f && y < 100f) return true;
        if (x > w - 155f && y > 105f && y < 205f) return true;
        float dx = x - (w - 90f), dy = y - (h - 100f);
        return dx * dx + dy * dy < 105f * 105f;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (fJoystickPointer == null || modalOpen()) return super.onTouchEvent(event);
        int action = event.getActionMasked();
        int index = event.getActionIndex();
        float x = event.getX(index), y = event.getY(index);
        try {
            int activePointer = fJoystickPointer.getInt(this);
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
                if (isControlTap(x, y)) return super.onTouchEvent(event);
                if (activePointer < 0) {
                    int pointerId = event.getPointerId(index);
                    fJoystickPointer.setInt(this, pointerId);
                    fJoyStartX.setFloat(this, x);
                    fJoyStartY.setFloat(this, y);
                    fJoyX.setFloat(this, 0f);
                    fJoyY.setFloat(this, 0f);
                    fMoveX.setFloat(this, 0f);
                    fMoveY.setFloat(this, 0f);
                    invalidate();
                    return true;
                }
                return true;
            }
            if (action == MotionEvent.ACTION_MOVE) {
                activePointer = fJoystickPointer.getInt(this);
                if (activePointer >= 0) {
                    int pIndex = event.findPointerIndex(activePointer);
                    if (pIndex >= 0) updateAnywhereJoystick(event.getX(pIndex), event.getY(pIndex));
                }
                return true;
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP || action == MotionEvent.ACTION_CANCEL) {
                int pointerId = action == MotionEvent.ACTION_CANCEL ? activePointer : event.getPointerId(index);
                if (action == MotionEvent.ACTION_CANCEL || pointerId == activePointer) {
                    releaseAnywhereJoystick();
                    return true;
                }
                return super.onTouchEvent(event);
            }
        } catch (Exception ignored) { return super.onTouchEvent(event); }
        return true;
    }

    private void updateAnywhereJoystick(float x, float y) throws IllegalAccessException {
        float sx = fJoyStartX.getFloat(this), sy = fJoyStartY.getFloat(this);
        float radius = Math.max(55f, fJoyRadius.getFloat(this));
        float dx = x - sx, dy = y - sy;
        float len = (float) Math.hypot(dx, dy);
        if (len > radius && len > 0f) { dx = dx / len * radius; dy = dy / len * radius; }
        float nx = dx / radius, ny = dy / radius;
        fJoyX.setFloat(this, nx); fJoyY.setFloat(this, ny);
        fMoveX.setFloat(this, nx); fMoveY.setFloat(this, ny);
        invalidate();
    }

    private void releaseAnywhereJoystick() throws IllegalAccessException {
        fJoystickPointer.setInt(this, -1);
        fJoyX.setFloat(this, 0f); fJoyY.setFloat(this, 0f);
        fMoveX.setFloat(this, 0f); fMoveY.setFloat(this, 0f);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (modalOpen()) return;
        try { drawRemoteSprites(canvas); } catch (Exception ignored) { }
        if (System.currentTimeMillis() < skinBadgeUntil && skinBadge != null && !skinBadge.isEmpty()) {
            badgePaint.setTextAlign(Paint.Align.LEFT);
            canvas.drawText(skinBadge, 18f, getHeight() - 18f, badgePaint);
        }
    }

    @SuppressWarnings("unchecked")
    private void drawRemoteSprites(Canvas canvas) throws Exception {
        if (skin == null || !skin.hasAnyBitmap() || fCamX == null || fEnemies == null) return;
        float scale = Math.max(1f, Math.min(getWidth() / 420f, getHeight() / 820f));
        float camX = fCamX.getFloat(this), camY = fCamY.getFloat(this);
        float playerX = fPx.getFloat(this), playerY = fPy.getFloat(this);
        float anchorX = getWidth() * 0.5f, anchorY = getHeight() * 0.56f;
        canvas.save();
        canvas.clipRect(0f, 250f * scale, getWidth(), getHeight());
        List<Object> gems = (List<Object>) fGems.get(this);
        if (gems != null && skin.gem != null) for (Object obj : gems) {
            bindGem(obj);
            float wx = gemX.getFloat(obj), wy = gemY.getFloat(obj), value = gemValue.getFloat(obj);
            float size = value >= 20f ? 34f : value >= 3f ? 29f : 24f;
            drawBitmapCentered(canvas, skin.gem, wx - camX + anchorX, wy - camY + anchorY, size, size);
        }
        List<Object> chests = (List<Object>) fChests.get(this);
        if (chests != null && skin.chest != null) for (Object obj : chests) {
            bindChest(obj);
            drawBitmapCentered(canvas, skin.chest, chestX.getFloat(obj) - camX + anchorX,
                    chestY.getFloat(obj) - camY + anchorY, 76f, 76f);
        }
        List<Object> enemies = (List<Object>) fEnemies.get(this);
        if (enemies != null) for (Object obj : enemies) {
            bindEnemy(obj);
            int type = enemyType.getInt(obj);
            float r = enemyR.getFloat(obj);
            Bitmap bmp = skin.enemyFor(type);
            if (bmp == null) continue;
            float size = Math.max(42f, r * 2.9f);
            if (type == 4) size = Math.max(size, 142f);
            drawBitmapCentered(canvas, bmp, enemyX.getFloat(obj) - camX + anchorX,
                    enemyY.getFloat(obj) - camY + anchorY, size, size);
        }
        if (skin.player != null) drawBitmapCentered(canvas, skin.player,
                playerX - camX + anchorX, playerY - camY + anchorY - 3f, 60f, 60f);
        canvas.restore();
        RectF dash = rect(fDashRect);
        if (dash != null && !dash.isEmpty()) {
            skinPaint.setStyle(Paint.Style.STROKE); skinPaint.setStrokeWidth(3f);
            skinPaint.setColor(Color.argb(190, 210, 250, 255));
            canvas.drawOval(dash, skinPaint); skinPaint.setStyle(Paint.Style.FILL);
        }
    }

    private void bindEnemy(Object obj) throws Exception {
        if (obj == null) return;
        Class<?> c = obj.getClass(); if (c == enemyClass) return; enemyClass = c;
        enemyType = privateField(c, "type"); enemyX = privateField(c, "x");
        enemyY = privateField(c, "y"); enemyR = privateField(c, "r");
    }
    private void bindGem(Object obj) throws Exception {
        if (obj == null) return;
        Class<?> c = obj.getClass(); if (c == gemClass) return; gemClass = c;
        gemX = privateField(c, "x"); gemY = privateField(c, "y"); gemValue = privateField(c, "value");
    }
    private void bindChest(Object obj) throws Exception {
        if (obj == null) return;
        Class<?> c = obj.getClass(); if (c == chestClass) return; chestClass = c;
        chestX = privateField(c, "x"); chestY = privateField(c, "y");
    }
    private Field privateField(Class<?> c, String name) throws NoSuchFieldException {
        Field f = c.getDeclaredField(name); f.setAccessible(true); return f;
    }
    private void drawBitmapCentered(Canvas canvas, Bitmap bitmap, float cx, float cy, float width, float height) {
        if (bitmap == null || bitmap.isRecycled()) return;
        RectF dst = new RectF(cx - width / 2f, cy - height / 2f, cx + width / 2f, cy + height / 2f);
        canvas.drawBitmap(bitmap, null, dst, skinPaint);
    }
}
