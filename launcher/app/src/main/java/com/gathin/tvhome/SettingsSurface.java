package com.gathin.tvhome;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/** Shared pixels for the pill and the starting/ending frame of its menu. */
final class SettingsSurface extends Drawable {
    private final float dp;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();
    private boolean emphasized;
    private int alpha = 255;
    private float lastRight = -1, lastBottom = -1, lastMaterial = -1;
    private int fillTopColor, fillBottomColor;
    private final int[] rimColors = new int[3];
    private final Matrix fillMatrix = new Matrix(), rimMatrix = new Matrix();
    private final float[] rimTransform = {1, 0, 0, 0, 1, 0, 0, 0, 1};
    private Shader fill, rim;

    SettingsSurface(float dp) { this.dp = dp; }

    void setEmphasized(boolean value) {
        if (emphasized == value) return;
        emphasized = value;
        lastMaterial = -1;
        invalidateSelf();
    }

    @Override public boolean isStateful() { return true; }
    @Override protected boolean onStateChange(int[] states) {
        boolean active = false;
        for (int state : states) {
            if (state == android.R.attr.state_focused || state == android.R.attr.state_hovered
                    || state == android.R.attr.state_activated) active = true;
        }
        boolean changed = active != emphasized;
        setEmphasized(active);
        return changed;
    }

    float inset(float material) { return (.25f + .75f * material) * dp; }

    private static int blend(int a, int b, float t) {
        return Color.argb(Math.round(Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t),
                Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    void drawSurface(Canvas canvas, float width, float height, float radius, float material) {
        if (width <= 0 || height <= 0) return;
        float inset = inset(material);
        bounds.set(inset, inset, width - inset, height - inset);
        boolean fillChanged = false, rimChanged = false;
        if (lastMaterial != material) {
            int top = emphasized ? 0x183D3D3D : 0x08181818;
            int bottom = emphasized ? 0x10282828 : 0x08181818;
            int edge = emphasized ? 0x36FFFFFF : 0x20FFFFFF;
            int nextTop = blend(top, 0xD51F1F1F, material);
            int nextBottom = blend(bottom, 0xE10C0C0C, material);
            if (fill == null || fillTopColor != nextTop || fillBottomColor != nextBottom) {
                fillTopColor = nextTop; fillBottomColor = nextBottom;
                fill = new LinearGradient(0, 0, 0, 1, nextTop, nextBottom, Shader.TileMode.CLAMP);
                fillChanged = true;
            }
            int first = blend(edge, 0x90FFFFFF, material);
            int middle = blend(edge, 0x19FFFFFF, material);
            int last = blend(edge, 0x50C6C6C6, material);
            if (rim == null || rimColors[0] != first || rimColors[1] != middle || rimColors[2] != last) {
                rimColors[0] = first; rimColors[1] = middle; rimColors[2] = last;
                rim = new LinearGradient(0, 0, 1, 0, rimColors, null, Shader.TileMode.CLAMP);
                rimChanged = true;
            }
            lastMaterial = material;
        }
        boolean geometryChanged = lastRight != bounds.right || lastBottom != bounds.bottom;
        if (geometryChanged) {
            fillMatrix.setScale(1, bounds.bottom);
            // Uniform scale + rotation maps the unit gradient to (right, bottom).
            // Independent X/Y scaling would change the original diagonal colour projection.
            rimTransform[0] = bounds.right; rimTransform[1] = -bounds.bottom;
            rimTransform[3] = bounds.bottom; rimTransform[4] = bounds.right;
            rimMatrix.setValues(rimTransform);
            lastRight = bounds.right; lastBottom = bounds.bottom;
        }
        if (fillChanged || geometryChanged) fill.setLocalMatrix(fillMatrix);
        if (rimChanged || geometryChanged) rim.setLocalMatrix(rimMatrix);
        paint.setAlpha(alpha);
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(fill);
        canvas.drawRoundRect(bounds, radius, radius, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth((.5f + .15f * material) * dp);
        paint.setShader(rim);
        canvas.drawRoundRect(bounds, radius, radius, paint);
        paint.setShader(null);
    }

    @Override public void draw(Canvas canvas) {
        Rect b = getBounds();
        int save = canvas.save();
        canvas.translate(b.left, b.top);
        drawSurface(canvas, b.width(), b.height(), SettingsPill.CORNER_RADIUS_DP * dp, 0);
        canvas.restoreToCount(save);
    }
    @Override public void setAlpha(int value) { alpha = value; invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter value) { paint.setColorFilter(value); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
