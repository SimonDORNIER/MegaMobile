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
    private Field fPaused, fDead, fChoosing, fAutoChoice, fPauseRect, fAutoRect;
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
            fAutoChoice = field("autoChoice");
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
            boolean choosingManually = fChoosing != null && fChoosing.getBoolean(this)
                    && (fAutoChoice == null || !fAutoChoice.getBoolean(this));
            return (fPaused != null && fPaused.getBoolean(this))
                    || (fDead != null && fDead.getBoolean(this))
                    || choosingManually;
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
        int w = getWidth(), h = getHeight();
        if (x > w - 100f && y < 100f) return true;
        return false;
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
    }

    @SuppressWarnings("unchecked")
    private void drawRemoteSprites(Canvas canvas) throws Exception {
        if (skin == null || !skin.hasAnyBitmap() || fCamX == null || fEnemies == null) return;
        float scale = Math.max(1f, Math.min(getWidth() / 420f, getHeight() / 820f));
        float camX = fCamX.getFloat(this), camY = fCamY.getFloat(this);
        float playerX = fPx.getFloat(this), playerY = fPy.getFloat(this);
        float anchorX = getWidth() * 0.5f, anchorY = getHeight() * 0.56f;
        float zoom = getCameraZoomScale();
        float time = (System.currentTimeMillis() % 120000L) / 1000f;
        canvas.save();
        canvas.clipRect(0f, 250f * scale, getWidth(), getHeight());
        List<Object> gems = (List<Object>) fGems.get(this);
        if (gems != null && skin.gem != null) for (Object obj : gems) {
            bindGem(obj);
            float wx = gemX.getFloat(obj), wy = gemY.getFloat(obj), value = gemValue.getFloat(obj);
            float sx = (wx - camX) * zoom + anchorX;
            float sy = (wy - camY) * zoom + anchorY;
            if (sx < -50f || sx > getWidth() + 50f || sy < 200f || sy > getHeight() + 50f) continue;
            float pulse = 1f + (float) Math.sin(time * 5f + wx * 0.01f) * 0.10f;
            float size = (value >= 20f ? 34f : value >= 3f ? 29f : 24f) * pulse * zoom;
            drawBitmapCenteredTransformed(canvas, skin.gem, sx, sy, size, size, time * 28f);
        }
        List<Object> chests = (List<Object>) fChests.get(this);
        if (chests != null && skin.chest != null) for (Object obj : chests) {
            bindChest(obj);
            float wx = chestX.getFloat(obj), wy = chestY.getFloat(obj);
            float sx = (wx - camX) * zoom + anchorX;
            float sy = (wy - camY) * zoom + anchorY;
            if (sx < -90f || sx > getWidth() + 90f || sy < 170f || sy > getHeight() + 90f) continue;
            float lift = (float) Math.sin(time * 2.8f + wx * 0.008f) * 3f * zoom;
            drawBitmapCenteredTransformed(canvas, skin.chest, sx, sy + lift, 76f * zoom, 76f * zoom, 0f);
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
            if (type == 5) size = Math.max(size, 104f);
            float wx = enemyX.getFloat(obj), wy = enemyY.getFloat(obj);
            float sx = (wx - camX) * zoom + anchorX;
            float sy = (wy - camY) * zoom + anchorY;
            size *= zoom;
            if (sx < -size || sx > getWidth() + size || sy < 190f - size || sy > getHeight() + size) continue;
            float bob = (float) Math.sin(time * 4.2f + wx * 0.012f) * (type == 4 ? 4f : 2.2f) * zoom;
            float angle = type == 1 ? (float) Math.sin(time * 7f + wy * 0.01f) * 5f : 0f;
            drawBitmapCenteredTransformed(canvas, bmp, sx, sy + bob, size, size, angle);
        }
        if (skin.player != null) {
            float tilt = fJoyX == null ? 0f : fJoyX.getFloat(this) * 5f;
            float bob = (float) Math.sin(time * 5.2f) * 2f * zoom;
            drawBitmapCenteredTransformed(canvas, skin.player,
                    (playerX - camX) * zoom + anchorX,
                    (playerY - camY) * zoom + anchorY - 5f * zoom + bob,
                    66f * zoom, 66f * zoom, tilt);
        }
        canvas.restore();
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
        drawBitmapCenteredTransformed(canvas, bitmap, cx, cy, width, height, 0f);
    }

    private void drawBitmapCenteredTransformed(Canvas canvas, Bitmap bitmap, float cx, float cy,
                                               float width, float height, float angle) {
        if (bitmap == null || bitmap.isRecycled()) return;
        RectF dst = new RectF(cx - width / 2f, cy - height / 2f, cx + width / 2f, cy + height / 2f);
        canvas.save();
        canvas.rotate(angle, cx, cy);
        canvas.drawBitmap(bitmap, null, dst, skinPaint);
        canvas.restore();
    }
}
