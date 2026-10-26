package com.gathin.tvhome;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Outline;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.LinearInterpolator;
import android.widget.PopupWindow;
import android.widget.TextView;
import java.util.function.DoubleConsumer;

/** A single glass surface grows from the pill and returns to the same pixels. */
final class SettingsMorphPopup extends PopupWindow {
    private final View panel, anchor, root, header, rows, divider;
    private final HeaderPillContainer sharedContainer;
    private final View sourceView;
    private final TextView title;
    private final float dp;
    private int fullWidth, fullHeight, startWidth;
    private float sourceHeaderX;
    private BitmapDrawable profileGhost;
    private float shape, currentMaterial, sourceLabelAlpha;
    private ValueAnimator animator;
    private boolean closing, sourceFocused, started, finished, restoreFocus = true;
    private Runnable pendingAction;
    private DoubleConsumer motion;
    private java.util.function.Consumer<Boolean> transition;
    private boolean animations = true, reducedMotion, revealingRows;
    private boolean[] revealed;

    void setMotionOptions(boolean animations, boolean reduced) {
        this.animations = animations; reducedMotion = reduced;
    }

    SettingsMorphPopup(View panel, View anchor, View root, float dp) {
        super(panel, Math.round(280 * dp), -2, true);
        this.panel = panel; this.anchor = anchor; this.root = root; this.dp = dp;
        sharedContainer = ((SettingsPill)anchor).sharedContainer();
        sourceView = sharedContainer == null ? anchor : sharedContainer;
        header = panel.findViewById(R.id.menuHeader);
        rows = panel.findViewById(R.id.rows);
        title = panel.findViewById(R.id.menuTitle);
        divider = panel.findViewById(R.id.menuDivider);
        divider.setVisibility(View.VISIBLE);
        android.widget.LinearLayout.LayoutParams p = (android.widget.LinearLayout.LayoutParams) title.getLayoutParams();
        p.setMarginStart(0); title.setLayoutParams(p);
        setBackgroundDrawable(new ColorDrawable(android.graphics.Color.TRANSPARENT));
        setOutsideTouchable(true); setAnimationStyle(0); setElevation(0);
    }

    void setMotionListener(DoubleConsumer listener) { motion = listener; }
    void setTransitionListener(java.util.function.Consumer<Boolean> listener) { transition = listener; }

    void show() {
        // Free the wallpaper GPU before popup measurement, texture preparation and its first frame.
        if (animations && transition != null) transition.accept(true);
        int[] loc = new int[2]; sourceView.getLocationInWindow(loc);
        int safe = Math.round(24 * dp);
        fullWidth = Math.min(getWidth(), root.getWidth() - 2 * safe);
        startWidth = sourceView.getWidth(); sourceFocused = anchor.hasFocus();
        sourceHeaderX = (sharedContainer == null ? 0 : anchor.getLeft()) - 10 * dp;
        sourceLabelAlpha = ((SettingsPill) anchor).labelReveal();
        panel.measure(View.MeasureSpec.makeMeasureSpec(fullWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(root.getHeight() - 2 * safe, View.MeasureSpec.AT_MOST));
        fullHeight = panel.getMeasuredHeight();
        int x = Math.max(safe, Math.min(loc[0], root.getWidth() - safe - fullWidth));
        int y = Math.max(safe, Math.min(loc[1], root.getHeight() - safe - fullHeight));
        setWidth(fullWidth); setHeight(fullHeight);
        ((SettingsPill) anchor).lockForMenu(true);
        if (sharedContainer != null) {
            sharedContainer.lockForMenu(true);
            android.graphics.Bitmap profileLayer = sharedContainer.captureProfileLayer();
            if (profileLayer != null) {
                profileGhost = new BitmapDrawable(root.getResources(), profileLayer);
                profileGhost.setBounds(0, 0, startWidth, sourceView.getHeight());
                panel.getOverlay().add(profileGhost);
            }
        }
        ((FrostedPanel) panel).prepareMorph(sourceView.hasFocus() || sourceView.isActivated()
                || sourceFocused || anchor.isHovered());
        panel.setClipToOutline(true);
        panel.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, Math.round(width()), Math.round(height()), radius());
            }
        });
        // The pill and its expanded title must be the same real weight during their handoff.
        title.setTypeface(((TextView) anchor).getTypeface());
        title.setPivotX(0); title.setPivotY(26 * dp);
        android.view.ViewGroup group = (android.view.ViewGroup) rows;
        revealed = new boolean[group.getChildCount()];
        for (int i = 0; i < group.getChildCount(); i++) group.getChildAt(i).setAlpha(0);
        apply(0, 0, 0); panel.setAlpha(0);
        panel.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                panel.getViewTreeObserver().removeOnPreDrawListener(this);
                // The popup has completed layout; transfer the matching surface on the next frame.
                panel.postOnAnimation(() -> {
                    if (finished || closing) return;
                    if (!reducedMotion) { panel.setAlpha(1); sourceView.setAlpha(0); }
                    animate(false);
                });
                return true;
            }
        });
        showAtLocation(root, Gravity.TOP | Gravity.LEFT, x, y);
    }

    private float width() { return startWidth + (fullWidth - startWidth) * shape; }
    private float height() { return 34 * dp + (fullHeight - 34 * dp) * shape; }
    private float radius() { return (SettingsPill.CORNER_RADIUS_DP + (23 - SettingsPill.CORNER_RADIUS_DP) * shape) * dp; }
    private static float clamp(float t) { return Math.max(0, Math.min(1, t)); }
    private static float smooth(float t) { t = clamp(t); return t * t * (3 - 2 * t); }
    // Continuous speed and acceleration at both ends: no abrupt launch or final snap.
    private static float settle(float t) { t = clamp(t); return t * t * t * (t * (6 * t - 15) + 10); }

    private void apply(float geometry, float material, float content) {
        shape = geometry; currentMaterial = material;
        ((FrostedPanel) panel).morph(width(), height(), radius());
        ((FrostedPanel) panel).morphMaterial(material); panel.invalidateOutline();
        header.setTranslationX(sourceHeaderX * (1 - shape));
        header.setTranslationY(-9 * dp * (1 - shape));
        float scale = 2f / 3f + shape / 3f;
        title.setScaleX(scale); title.setScaleY(scale);
        float label = sourceLabelAlpha + (1 - sourceLabelAlpha) * smooth(geometry / .28f);
        title.setAlpha(label); divider.setAlpha(label);
        if (profileGhost != null) profileGhost.setAlpha(Math.round(255 * (1 - smooth(shape / .22f))));
        rows.setAlpha(content);
        if (revealingRows) {
            android.view.ViewGroup group = (android.view.ViewGroup) rows;
            for (int i = 0; i < group.getChildCount(); i++) {
                View row = group.getChildAt(i);
                if (!revealed[i] && row.getHeight() > 0 && height() >= rows.getTop() + row.getBottom() + dp) {
                    revealed[i] = true;
                    // Time-based fade remains soft even for the last row near the settled edge.
                    row.animate().alpha(1).setDuration(130)
                            .setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator()).start();
                }
            }
        }
        if (motion != null) motion.accept(material);
    }

    private void cancelRowFades() {
        android.view.ViewGroup group = (android.view.ViewGroup) rows;
        for (int i = 0; i < group.getChildCount(); i++) group.getChildAt(i).animate().cancel();
    }

    private void showAllRows() {
        cancelRowFades();
        android.view.ViewGroup group = (android.view.ViewGroup) rows;
        for (int i = 0; i < group.getChildCount(); i++) {
            group.getChildAt(i).setAlpha(1); revealed[i] = true;
        }
    }

    private float rowVisibility() {
        float visible = 0;
        android.view.ViewGroup group = (android.view.ViewGroup) rows;
        for (int i = 0; i < group.getChildCount(); i++) visible = Math.max(visible, group.getChildAt(i).getAlpha());
        return visible * rows.getAlpha();
    }

    private void animate(boolean close) {
        if (animator != null) { animator.removeAllListeners(); animator.cancel(); }
        revealingRows = !close && animations && !reducedMotion;
        if (close) cancelRowFades();
        else if (!animations || reducedMotion) showAllRows();
        if (!animations) {
            if (close) finish(); else { panel.setAlpha(1); sourceView.setAlpha(0); apply(1, 1, 1); }
            return;
        }
        float fromShape = shape, fromMaterial = currentMaterial, fromContent = rows.getAlpha();
        float fromAlpha = panel.getAlpha();
        float extent = Math.max(fromShape, Math.max(fromMaterial, rowVisibility()));
        if (close && extent < .002f && (!reducedMotion || fromAlpha < .002f)) { finish(); return; }
        long duration = reducedMotion ? (close ? 120 : 160)
                : close ? Math.max(100, Math.round(420 * Math.sqrt(extent))) : 500;
        started = true;
        if (transition != null) transition.accept(true);
        Log.i("Settings.Motion", "begin " + (close ? "close" : "open") + " durationMs=" + duration);
        long[] last = {0}, maxGap = {0}; int[] frames = {0};
        long begin = SystemClock.elapsedRealtime();
        animator = ValueAnimator.ofFloat(0, 1);
        animator.setDuration(duration); animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(v -> {
            float t = (float) v.getAnimatedValue(); long now = SystemClock.elapsedRealtime();
            if (last[0] != 0) maxGap[0] = Math.max(maxGap[0], now - last[0]);
            last[0] = now; frames[0]++;
            if (reducedMotion) {
                apply(1, close ? fromMaterial * (1 - smooth(t)) : smooth(t), 1);
                sourceView.setAlpha(1);
                panel.setAlpha(close ? fromAlpha * (1 - smooth(t)) : smooth(t));
            } else if (close) {
                apply(fromShape * (1 - settle((t - .18f) / .82f)),
                        fromMaterial * (1 - smooth((t - .08f) / .92f)),
                        fromContent * (1 - smooth(t / .30f)));
            } else {
                apply(settle(t / .80f), smooth((t - .04f) / .86f), 1);
            }
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator a) {
                Log.i("Settings.Motion", (close ? "close" : "open") + " frames=" + frames[0]
                        + " maxGapMs=" + maxGap[0] + " elapsedMs=" + (SystemClock.elapsedRealtime() - begin));
                if (close) finish(); else { apply(1, 1, 1); panel.setAlpha(1); sourceView.setAlpha(0); if (transition != null) transition.accept(false); }
            }
        });
        animator.start();
    }

    private void finish() {
        if (finished) return;
        finished = true; revealingRows = false; cancelRowFades();
        if (transition != null) transition.accept(false);
        sourceView.setAlpha(1);
        if (profileGhost != null) { panel.getOverlay().remove(profileGhost); profileGhost = null; }
        SettingsMorphPopup.super.dismiss();
        if (restoreFocus && sourceFocused && pendingAction == null) anchor.requestFocus();
        ((SettingsPill) anchor).lockForMenu(false);
        if (sharedContainer != null) sharedContainer.lockForMenu(false);
        if (restoreFocus) ((SettingsPill) anchor).refresh();
        if (motion != null) motion.accept(0);
        if (pendingAction != null) pendingAction.run();
    }

    void dismissThen(Runnable action) {
        if (closing || finished) return;
        closing = true; pendingAction = action;
        if (!started) { finish(); return; }
        animate(true);
    }

    void dismissImmediately() {
        if (finished) return;
        closing = true; restoreFocus = false; pendingAction = null;
        if (animator != null) { animator.removeAllListeners(); animator.cancel(); }
        finish();
    }
    @Override public void dismiss() { dismissThen(null); }
}
