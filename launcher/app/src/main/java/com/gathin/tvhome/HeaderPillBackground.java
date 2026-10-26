package com.gathin.tvhome;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/** One shared material and rim for the account and settings controls. */
final class HeaderPillBackground extends Drawable {
    private final SettingsSurface surface;
    private final float density;

    HeaderPillBackground(float density) {
        this.density = density;
        surface = new SettingsSurface(density);
    }

    @Override public boolean isStateful() { return true; }
    @Override protected boolean onStateChange(int[] states) {
        boolean changed = surface.setState(states);
        if (changed) invalidateSelf();
        return changed;
    }
    @Override public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        int save = canvas.save();
        canvas.translate(bounds.left, bounds.top);
        surface.drawSurface(canvas, bounds.width(), bounds.height(), 17 * density, 0);
        canvas.restoreToCount(save);
    }
    @Override public void setAlpha(int alpha) { surface.setAlpha(alpha); invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) { surface.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
