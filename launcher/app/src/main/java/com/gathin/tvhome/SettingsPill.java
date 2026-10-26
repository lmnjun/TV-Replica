package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.widget.TextView;

/** Expands sideways on remote focus or pointer hover, keeping the gear stationary. */
public class SettingsPill extends TextView {
    static final float CORNER_RADIUS_DP = 17f;
    private final Paint divider = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float dp;
    private float progress, expandedWidth;
    private boolean hovering, menuOpen;
    private ValueAnimator animator;
    private HeaderPillContainer headerContainer;

    public SettingsPill(Context c, AttributeSet a) {
        super(c, a);
        dp = getResources().getDisplayMetrics().density;
        setWillNotDraw(false);
    }

    public void configure() {
        setIncludeFontPadding(false);
        setLetterSpacing(0);
        setText("");
        setContentDescription("Settings");
        setSingleLine(true);
        setHorizontallyScrolling(true);
        setEllipsize(null);
        setTextSize(12);
        setTextColor(0xFFFFFFFF);
        InterTypography.apply(this, 600);
        setGravity(android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.START);
        setPaddingRelative(Math.round(8 * dp), 0, Math.round(8 * dp), 0);
        // Gear ends at 26dp, the hairline sits at 34dp, and the label starts at 45dp.
        setCompoundDrawablePadding(Math.round(19 * dp));
        android.graphics.drawable.Drawable gear = getResources().getDrawable(
                R.drawable.ic_settings_outline, getContext().getTheme()).mutate();
        gear.setTint(0xE6FFFFFF);
        gear.setBounds(0, 0, Math.round(18 * dp), Math.round(18 * dp));
        setCompoundDrawablesRelative(gear, null, null, null);
        setBackground(headerContainer == null ? new SettingsSurface(dp) : null);
        expandedWidth = 59 * dp + getPaint().measureText("Settings");
        getLayoutParams().width = Math.round(34 * dp);
        getLayoutParams().height = Math.round(34 * dp);
        requestLayout();
    }

    float labelReveal() {
        return progress * progress * (3 - 2 * progress);
    }

    void useSharedContainer(HeaderPillContainer container) {
        headerContainer = container;
        setBackground(null);
    }

    HeaderPillContainer sharedContainer() { return headerContainer; }

    void lockForMenu(boolean value) {
        menuOpen = value;
        // Keep the starting material stable while the popup receives window focus.
        setActivated(value && (hasFocus() || isHovered() || hovering));
        if (value && animator != null) {
            animator.removeAllListeners();
            animator.cancel();
        }
    }

    public void refresh() {
        if (menuOpen) return;
        boolean expand = hasFocus() || hovering;
        float target = expand ? 1 : 0;
        if (animator != null) {
            animator.removeAllListeners();
            animator.cancel();
        }
        if (expand) setText("Settings");
        animator = ValueAnimator.ofFloat(progress, target);
        animator.setDuration(190);
        animator.setInterpolator(new android.view.animation.DecelerateInterpolator());
        animator.addUpdateListener(v -> {
            progress = (float) v.getAnimatedValue();
            getLayoutParams().width = Math.round(34 * dp + (expandedWidth - 34 * dp) * progress);
            setTextColor((Math.round(255 * labelReveal()) << 24) | 0x00FFFFFF);
            requestLayout();
            invalidate();
        });
        animator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) {
                if (!expand) setText("");
            }
        });
        animator.start();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float reveal = labelReveal();
        if (reveal <= 0) return;
        divider.setColor((Math.round(64 * reveal) << 24) | 0x00FFFFFF);
        float x = getLayoutDirection() == LAYOUT_DIRECTION_RTL ? getWidth() - 34.6f * dp : 34 * dp;
        float cy = getHeight() / 2f;
        canvas.drawRoundRect(x, cy - 6 * dp, x + .6f * dp, cy + 6 * dp,
                .3f * dp, .3f * dp, divider);
    }

    @Override public boolean onHoverEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_HOVER_ENTER || e.getAction() == MotionEvent.ACTION_HOVER_MOVE) {
            if (!hovering) { hovering = true; refresh(); }
        } else if (e.getAction() == MotionEvent.ACTION_HOVER_EXIT) {
            hovering = false;
            refresh();
        }
        super.onHoverEvent(e);
        return true;
    }
}
