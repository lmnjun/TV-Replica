package com.gathin.tvhome;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/** Flat menu-row layout. Typography, content description and interaction stay with its caller. */
final class MenuRow extends ViewGroup {
    private final TextView title, detail;
    private final Drawable leading, chevron;
    private final ColorStateList leadingTint, chevronTint;
    private Drawable cachedLeading, cachedChevron;
    private int leadingResource, chevronResource;
    private boolean glyphCacheEnabled;
    private final int leadingSize, chevronSize, leadingGap, chevronGap, detailGap, rowHeight;
    private final IconPlacement leadingPlacement = new IconPlacement();
    private final IconPlacement chevronPlacement = new IconPlacement();
    private int textHeight;

    /** Takes ownership of per-row drawables; pass fresh/mutated instances, not shared live drawables. */
    MenuRow(Context context, TextView title, TextView detail, Drawable leading, Drawable chevron,
            ColorStateList leadingTint, ColorStateList chevronTint) {
        super(context);
        if (title == null || chevron == null) throw new IllegalArgumentException("Title and chevron required");
        this.title = title; this.detail = detail;
        this.leadingTint = leadingTint; this.chevronTint = chevronTint;
        this.leading = leading == null ? null : leading.mutate();
        this.chevron = chevron.mutate();
        float density = getResources().getDisplayMetrics().density;
        int padding = Math.round(12 * density);
        leadingSize = Math.round(21 * density); chevronSize = Math.round(15 * density);
        leadingGap = Math.round(12 * density); chevronGap = Math.round(8 * density);
        detailGap = Math.round(4 * density); rowHeight = Math.round((detail == null ? 40 : 48) * density);
        setPadding(padding, 0, padding, 0);
        setWillNotDraw(false);
        // These are the same native TextViews used by Header.text: keep their gravity,
        // font padding, bidi/fallback shaping, SP sizes and ellipsizing untouched.
        addView(title, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        if (detail != null) addView(detail, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        prepareDrawable(this.leading, leadingTint);
        prepareDrawable(this.chevron, chevronTint);
    }

    /** Reuse static icon pixels. Resource IDs identify this row's supplied source drawables. */
    boolean enableGlyphBitmapCache(int leadingResource, int chevronResource) {
        this.leadingResource = leadingResource; this.chevronResource = chevronResource;
        glyphCacheEnabled = true; refreshGlyphBitmaps(); requestLayout(); invalidate();
        return cachedChevron != null && (leading == null || cachedLeading != null);
    }

    private void refreshGlyphBitmaps() {
        if (!glyphCacheEnabled) return;
        releaseCachedDrawable(cachedLeading); releaseCachedDrawable(cachedChevron);
        cachedLeading = MenuGlyphCache.acquire(getContext(), leadingResource, leading, leadingTint,
                leadingSize, getLayoutDirection());
        cachedChevron = MenuGlyphCache.acquire(getContext(), chevronResource, chevron, chevronTint,
                chevronSize, getLayoutDirection());
        prepareDrawable(cachedLeading, null); prepareDrawable(cachedChevron, null);
    }

    private void releaseCachedDrawable(Drawable drawable) {
        if (drawable != null) { unscheduleDrawable(drawable); drawable.setCallback(null); }
    }

    private Drawable leadingToDraw() { return cachedLeading == null ? leading : cachedLeading; }
    private Drawable chevronToDraw() { return cachedChevron == null ? chevron : cachedChevron; }

    private void prepareDrawable(Drawable drawable, ColorStateList tint) {
        if (drawable == null) return;
        drawable.setCallback(this);
        if (tint != null) drawable.setTintList(tint);
        drawable.setLayoutDirection(getLayoutDirection());
        if (drawable.isStateful()) drawable.setState(getDrawableState());
    }

    private int startReserve() { return leading == null ? 0 : leadingSize + leadingGap; }
    private int endReserve() { return chevronSize + chevronGap; }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int height = resolveSizeAndState(Math.max(rowHeight, getSuggestedMinimumHeight()), heightSpec, 0);
        int verticalPadding = getPaddingTop() + getPaddingBottom();
        int reservedWidth = getPaddingLeft() + getPaddingRight() + startReserve() + endReserve();
        int width;
        if (MeasureSpec.getMode(widthSpec) == MeasureSpec.UNSPECIFIED) {
            // Normal menu rows receive an exact full width. Retain useful wrap measurement
            // for isolated previews without adding a second measure to that normal path.
            measureCopy(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), height, verticalPadding);
            int natural = Math.max(title.getMeasuredWidth(), detail == null ? 0 : detail.getMeasuredWidth());
            width = Math.max(getSuggestedMinimumWidth(), reservedWidth + natural);
        } else width = MeasureSpec.getSize(widthSpec);
        int textWidth = Math.max(0, width - reservedWidth);
        measureCopy(MeasureSpec.makeMeasureSpec(textWidth, MeasureSpec.EXACTLY), height, verticalPadding);
        int state = title.getMeasuredState();
        if (detail != null) state = combineMeasuredStates(state, detail.getMeasuredState());
        setMeasuredDimension(resolveSizeAndState(width, widthSpec, state), height);
    }

    /** Match a vertical LinearLayout's sequential height budget and its clamped viewport. */
    private void measureCopy(int textWidthSpec, int rowMeasuredHeight, int verticalPadding) {
        int rowSpec = MeasureSpec.makeMeasureSpec(rowMeasuredHeight & MEASURED_SIZE_MASK, MeasureSpec.EXACTLY);
        title.measure(textWidthSpec, getChildMeasureSpec(rowSpec, verticalPadding, LayoutParams.WRAP_CONTENT));
        int height = title.getMeasuredHeight();
        if (detail != null && detail.getVisibility() != GONE) {
            detail.measure(textWidthSpec, getChildMeasureSpec(rowSpec,
                    verticalPadding + height + detailGap, LayoutParams.WRAP_CONTENT));
            height += detailGap + detail.getMeasuredHeight();
        }
        // The old copy container is WRAP_CONTENT/AT_MOST. At large font scales it clips
        // overflow at its bottom, rather than shifting the title above the row's top.
        textHeight = Math.min(height, Math.max(0, (rowMeasuredHeight & MEASURED_SIZE_MASK) - verticalPadding));
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int width = right - left, height = bottom - top;
        boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        int textLeft = getPaddingLeft() + (rtl ? endReserve() : startReserve());
        int textRight = width - getPaddingRight() - (rtl ? startReserve() : endReserve());
        textRight = Math.max(textLeft, textRight);
        int textTop = getPaddingTop() + (height - getPaddingTop() - getPaddingBottom() - textHeight) / 2;
        title.layout(textLeft, textTop, textRight, textTop + title.getMeasuredHeight());
        if (detail != null && detail.getVisibility() != GONE) {
            int detailTop = textTop + title.getMeasuredHeight() + detailGap;
            detail.layout(textLeft, detailTop, textRight, detailTop + detail.getMeasuredHeight());
        }
        int innerHeight = height - getPaddingTop() - getPaddingBottom();
        if (leading != null) leadingPlacement.configure(leadingToDraw(),
                rtl ? width - getPaddingRight() - leadingSize : getPaddingLeft(),
                getPaddingTop() + (innerHeight - leadingSize) / 2, leadingSize);
        chevronPlacement.configure(chevronToDraw(),
                rtl ? getPaddingLeft() : width - getPaddingRight() - chevronSize,
                getPaddingTop() + (innerHeight - chevronSize) / 2, chevronSize);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (leading != null) leadingPlacement.draw(canvas, leadingToDraw());
        chevronPlacement.draw(canvas, chevronToDraw());
    }

    @Override protected boolean verifyDrawable(Drawable drawable) {
        return drawable == leading || drawable == chevron || drawable == cachedLeading || drawable == cachedChevron
                || super.verifyDrawable(drawable);
    }

    @Override protected void drawableStateChanged() {
        super.drawableStateChanged();
        if (leading != null && leading.isStateful()) leading.setState(getDrawableState());
        if (chevron != null && chevron.isStateful()) chevron.setState(getDrawableState());
    }

    @Override public void jumpDrawablesToCurrentState() {
        super.jumpDrawablesToCurrentState();
        if (leading != null) leading.jumpToCurrentState();
        if (chevron != null) chevron.jumpToCurrentState();
        if (cachedLeading != null) cachedLeading.jumpToCurrentState();
        if (cachedChevron != null) cachedChevron.jumpToCurrentState();
    }

    @Override public void onRtlPropertiesChanged(int layoutDirection) {
        super.onRtlPropertiesChanged(layoutDirection);
        if (leading != null) leading.setLayoutDirection(layoutDirection);
        if (chevron != null) chevron.setLayoutDirection(layoutDirection);
        refreshGlyphBitmaps();
        requestLayout(); invalidate();
    }

    @Override protected void onConfigurationChanged(android.content.res.Configuration configuration) {
        super.onConfigurationChanged(configuration); refreshGlyphBitmaps(); requestLayout(); invalidate();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow(); setIconsVisible(getVisibility() == VISIBLE && getWindowVisibility() == VISIBLE);
    }

    @Override protected void onDetachedFromWindow() {
        setIconsVisible(false);
        if (leading != null) unscheduleDrawable(leading);
        if (chevron != null) unscheduleDrawable(chevron);
        if (cachedLeading != null) unscheduleDrawable(cachedLeading);
        if (cachedChevron != null) unscheduleDrawable(cachedChevron);
        super.onDetachedFromWindow();
    }

    @Override protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        setIconsVisible(getVisibility() == VISIBLE && getWindowVisibility() == VISIBLE);
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        setIconsVisible(getVisibility() == VISIBLE && visibility == VISIBLE);
    }

    private void setIconsVisible(boolean visible) {
        if (leading != null) leading.setVisible(visible, false);
        if (chevron != null) chevron.setVisible(visible, false);
        if (cachedLeading != null) cachedLeading.setVisible(visible, false);
        if (cachedChevron != null) cachedChevron.setVisible(visible, false);
    }

    /** ImageView FIT_CENTER geometry, precomputed on layout; draw performs no allocation. */
    private static final class IconPlacement {
        private float x, y, scale = 1;
        void configure(Drawable drawable, int left, int top, int edge) {
            int width = drawable.getIntrinsicWidth(), height = drawable.getIntrinsicHeight();
            if (width <= 0 || height <= 0) {
                drawable.setBounds(0, 0, edge, edge); x = left; y = top; scale = 1;
            } else {
                drawable.setBounds(0, 0, width, height);
                scale = Math.min(edge / (float)width, edge / (float)height);
                x = left + (edge - width * scale) * .5f;
                y = top + (edge - height * scale) * .5f;
            }
        }
        void draw(Canvas canvas, Drawable drawable) {
            int save = canvas.save();
            canvas.translate(x, y); canvas.scale(scale, scale); drawable.draw(canvas);
            canvas.restoreToCount(save);
        }
    }
}
