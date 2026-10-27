package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

/** One shared profile shell; settings is a page inside its anchored control stack. */
final class HeaderPillContainer extends LinearLayout {
    private final ProfilePill profile;
    private final SettingsPill settings;
    private final View divider;
    private final float density;
    private final MenuSnapshotState snapshotState = new MenuSnapshotState();
    private Bitmap profileSnapshot;
    private long snapshotPreparedNs;
    private boolean capturing;
    private static final int MAX_PREPARED_PIXELS = 262144;

    HeaderPillContainer(Context context, ProfilePill profile, SettingsPill settings) {
        super(context);
        this.profile = profile; this.settings = settings;
        density = getResources().getDisplayMetrics().density;
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setMinimumHeight(dp(34));
        setFocusable(false);
        setAddStatesFromChildren(true);
        setClipChildren(false);
        setClipToPadding(false);
        setBackground(new HeaderPillBackground(density));
        if (profile.getParent() instanceof ViewGroup) ((ViewGroup)profile.getParent()).removeView(profile);
        if (settings.getParent() instanceof ViewGroup) ((ViewGroup)settings.getParent()).removeView(settings);
        profile.useSharedSurface(true);
        profile.showConnectionBadge(false);
        addView(profile, new LinearLayout.LayoutParams(-2, dp(34)));
        divider = new View(context);
        divider.setBackgroundColor(0x30FFFFFF);
        divider.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(divider, new LinearLayout.LayoutParams(dp(.6f), dp(14)));
        int settingsWidth = settings.getLayoutParams() == null ? dp(34) : settings.getLayoutParams().width;
        addView(settings, new LinearLayout.LayoutParams(Math.max(dp(34), settingsWidth), dp(34)));
        settings.useSharedContainer(this);
        // The account is now the sole visible top-level control; settings lives inside its stack.
        settings.setVisibility(GONE);
        divider.setVisibility(GONE);
    }

    void refresh() { profile.refresh(); }

    /** Called only when the compact badge's static drawing changes. */
    void profileContentChanged() { snapshotState.invalidate(); invalidate(); }

    void avatarBounds(Rect bounds) {
        profile.avatarBounds(bounds);
        offsetDescendantRectToMyCoords(profile, bounds);
    }

    void lockForMenu(boolean lock) { setActivated(lock && (hasFocus() || isHovered())); }

    @Override public void onDescendantInvalidated(View child, View target) {
        if (!capturing && snapshotState != null) snapshotState.invalidate();
        super.onDescendantInvalidated(child, target);
    }

    @Override protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        // Prepare the actual visible pill after its normal draw, not a disposable offscreen menu.
        if ((long)getWidth() * getHeight() <= MAX_PREPARED_PIXELS) prepareProfileLayer(false);
    }

    @Override protected void onDetachedFromWindow() {
        profileSnapshot = null; snapshotState.clear();
        // A live menu may still hold the old immutable snapshot; never recycle a published bitmap.
        super.onDetachedFromWindow();
    }

    /** Only the account content/divider is copied; the popup owns the single shared glass surface. */
    Bitmap captureProfileLayer() {
        return prepareProfileLayer(true);
    }

    private Bitmap prepareProfileLayer(boolean requestedByMenu) {
        int width = getWidth(), height = getHeight();
        if (width <= 0 || height <= 0 || capturing) return null;
        if (profileSnapshot != null && snapshotState.reusable(width, height)) {
            if (requestedByMenu) {
                long now = System.nanoTime();
                Log.i("Menu.Perf", "phase=source_snapshot cache_hit=true at_ns=" + now
                        + " prepared_age_ms=" + (now - snapshotPreparedNs) / 1_000_000.0);
            }
            return profileSnapshot;
        }
        long start = System.nanoTime(), revision = snapshotState.revision();
        Bitmap writable = null;
        capturing = true;
        try {
            writable = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(writable);
            int save = canvas.save();
            canvas.translate(profile.getLeft(), profile.getTop());
            // ProfilePill applies its explicit circular Canvas clip in both hardware and software draws.
            profile.draw(canvas);
            canvas.restoreToCount(save);
            if (divider.getVisibility() == VISIBLE) {
                save = canvas.save(); canvas.translate(divider.getLeft(), divider.getTop());
                divider.draw(canvas); canvas.restoreToCount(save);
            }
            if ((long)width * height > MAX_PREPARED_PIXELS) {
                // Preserve a handoff for unusually large layouts without retaining an oversized cache.
                Bitmap uncached = writable; writable = null;
                profileSnapshot = null; snapshotState.clear();
                uncached.prepareToDraw();
                return uncached;
            }
            Bitmap immutable = writable.copy(Bitmap.Config.ARGB_8888, false);
            if (immutable == null) return null;
            immutable.prepareToDraw();
            if (!snapshotState.publish(revision, width, height)) return null;
            profileSnapshot = immutable; snapshotPreparedNs = System.nanoTime();
            Log.i("Menu.Perf", "phase=source_snapshot cache_hit=false eager=" + !requestedByMenu
                    + " pixels=" + ((long)width * height) + " start_ns=" + start
                    + " duration_ms=" + (System.nanoTime() - start) / 1_000_000.0);
            return immutable;
        } catch (RuntimeException | OutOfMemoryError unavailable) {
            return null;
        } finally {
            capturing = false;
            // Only this private software drawing target is recycled; it was never handed to HWUI.
            if (writable != null) writable.recycle();
        }
    }

    private int dp(float value) { return Math.round(value * density); }
}
