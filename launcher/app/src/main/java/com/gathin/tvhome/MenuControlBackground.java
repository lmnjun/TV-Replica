package com.gathin.tvhome;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;

/** Independent solid control material matching the existing three-state rounded backgrounds. */
final class MenuControlBackground extends Drawable implements Drawable.Callback {
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF shape = new RectF();
    private final float cornerRadius;
    private final int focusedWidth, selectedWidth;
    private int style = -1, fillColor, strokeColor, strokeWidth, alpha = 255;
    private float drawRadius;
    private boolean drawStroke;
    private ColorFilter filter;
    private StateListDrawable fallback;

    MenuControlBackground(float density) {
        cornerRadius = Math.round(12 * density);
        focusedWidth = Math.max(1, Math.round(1.2f * density));
        selectedWidth = Math.max(1, Math.round(.9f * density));
        stroke.setStyle(Paint.Style.STROKE);
        selectStyle(0);
    }

    @Override public boolean isStateful() { return true; }

    @Override protected boolean onStateChange(int[] state) {
        boolean selected = false; int next = 0;
        for (int attribute : state) {
            if (attribute == android.R.attr.state_focused) { next = 1; break; }
            if (attribute == android.R.attr.state_selected) selected = true;
        }
        if (next == 0 && selected) next = 2;
        boolean changed = selectStyle(next);
        return fallback != null ? fallback.setState(state) || changed : changed;
    }

    private boolean selectStyle(int next) {
        if (style == next) return false;
        style = next;
        if (next == 1) { fillColor = 0x295D5D5D; strokeColor = 0xC9FFFFFF; strokeWidth = focusedWidth; }
        else if (next == 2) { fillColor = 0x25484848; strokeColor = 0x80FFFFFF; strokeWidth = selectedWidth; }
        else { fillColor = 0x09181818; strokeColor = 0; strokeWidth = 0; }
        updateGeometry(); updatePaints(); invalidateSelf(); return true;
    }

    @Override protected void onBoundsChange(Rect bounds) {
        updateGeometry(); if (fallback != null) fallback.setBounds(bounds);
    }

    private void updateGeometry() {
        Rect bounds = getBounds();
        // GradientDrawable insets the fill and the stroke's centerline, not just the stroke.
        float inset = strokeWidth * .5f;
        shape.set(bounds.left + inset, bounds.top + inset, bounds.right - inset, bounds.bottom - inset);
        drawRadius = Math.min(cornerRadius, Math.min(shape.width(), shape.height()) * .5f);
    }

    private void updatePaints() {
        drawStroke = strokeWidth > 0;
        fill.setColor(fillColor); stroke.setColor(strokeColor); stroke.setStrokeWidth(strokeWidth);
    }

    private boolean useFallback() { return alpha != 255 || filter != null; }

    /** Preserve the platform's explicit-alpha/filter composition; normal UI never allocates this. */
    private StateListDrawable platformFallback() {
        if (fallback == null) {
            StateListDrawable states = new StateListDrawable();
            GradientDrawable active = new GradientDrawable();
            active.setColor(0x295D5D5D); active.setCornerRadius(cornerRadius);
            active.setStroke(focusedWidth, 0xC9FFFFFF);
            GradientDrawable selected = new GradientDrawable();
            selected.setColor(0x25484848); selected.setCornerRadius(cornerRadius);
            selected.setStroke(selectedWidth, 0x80FFFFFF);
            GradientDrawable normal = new GradientDrawable();
            normal.setColor(0x09181818); normal.setCornerRadius(cornerRadius);
            states.addState(new int[]{android.R.attr.state_focused}, active);
            states.addState(new int[]{android.R.attr.state_selected}, selected);
            states.addState(new int[]{}, normal);
            states.setCallback(this); states.setBounds(getBounds());
            states.setAlpha(alpha); states.setColorFilter(filter); states.setState(getState());
            fallback = states;
        }
        return fallback;
    }

    @Override public void draw(Canvas canvas) {
        if (useFallback()) { platformFallback().draw(canvas); return; }
        if (shape.isEmpty()) return;
        canvas.drawRoundRect(shape, drawRadius, drawRadius, fill);
        if (drawStroke) canvas.drawRoundRect(shape, drawRadius, drawRadius, stroke);
    }

    @Override public void setAlpha(int alpha) {
        int next = Math.max(0, Math.min(255, alpha));
        if (this.alpha != next) {
            this.alpha = next;
            if (useFallback()) platformFallback().setAlpha(next);
            else if (fallback != null) fallback.setAlpha(next);
            invalidateSelf();
        }
    }
    @Override public int getAlpha() { return alpha; }
    @Override public void setColorFilter(ColorFilter filter) {
        if (this.filter != filter) {
            this.filter = filter;
            if (useFallback()) platformFallback().setColorFilter(filter);
            else if (fallback != null) fallback.setColorFilter(filter);
            invalidateSelf();
        }
    }
    @Override public ColorFilter getColorFilter() { return filter; }
    @Override public int getOpacity() { return useFallback() ? platformFallback().getOpacity() : PixelFormat.TRANSLUCENT; }
    @Override public boolean getPadding(Rect padding) {
        if (useFallback()) return platformFallback().getPadding(padding);
        padding.set(0, 0, 0, 0); return false;
    }
    @Override public void getOutline(Outline outline) {
        if (useFallback()) { platformFallback().getOutline(outline); return; }
        Rect bounds = getBounds();
        outline.setRoundRect(bounds, Math.min(cornerRadius, Math.min(bounds.width(), bounds.height()) * .5f));
        // A uniform fill has a valid outline opacity; unlike the two differently opaque strokes.
        outline.setAlpha(strokeWidth == 0 ? (fillColor >>> 24) / 255f : 0);
    }
    @Override public void invalidateDrawable(Drawable who) { invalidateSelf(); }
    @Override public void scheduleDrawable(Drawable who, Runnable work, long when) { scheduleSelf(work, when); }
    @Override public void unscheduleDrawable(Drawable who, Runnable work) { unscheduleSelf(work); }
}
