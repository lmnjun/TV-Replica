package com.gathin.tvhome;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.FocusFinder;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** An anchored modal card in Home's existing window; Back traverses its local page stack. */
final class HeaderStackPopup extends PopupWindow {
    private static final MenuWarmupGate WARMUP = new MenuWarmupGate();
    private static final android.content.res.ColorStateList ICON_TINT = android.content.res.ColorStateList.valueOf(0xD9FFFFFF);
    private static final android.content.res.ColorStateList ARROW_TINT = android.content.res.ColorStateList.valueOf(0xAFFFFFFF);
    private static final android.view.animation.Interpolator MOTION_CURVE = new android.view.animation.PathInterpolator(.2f, 0, .2f, 1);
    private static final int MAX_RETAINED_PARENTS = 4;
    private static final class Page {
        final String title; final Supplier<View> build; Object focusTag;
        PageScrollView viewport; View contents; int contentHeight; boolean reusable;
        Page(String title, Supplier<View> build) { this.title = title; this.build = build; }
    }
    /** Retiring a page blocks its input, without changing every child's enabled/drawable state. */
    private static final class PageScrollView extends ScrollView {
        private final int activeDescendants, activeImportance;
        private boolean interactive = true, touchActive, dispatchingTouch;
        PageScrollView(Activity activity) {
            super(activity);
            setVerticalScrollBarEnabled(false); setClipToPadding(false); setFillViewport(false); setFocusable(false);
            activeDescendants = getDescendantFocusability(); activeImportance = getImportantForAccessibility();
        }
        void setInteractive(boolean interactive) {
            if (this.interactive == interactive) return;
            this.interactive = interactive;
            setDescendantFocusability(interactive ? activeDescendants : FOCUS_BLOCK_DESCENDANTS);
            setImportantForAccessibility(interactive ? activeImportance : IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            if (!interactive) {
                cancelTouchGesture();
                clearFocus();
            }
        }
        private void cancelTouchGesture() {
            if (!touchActive || dispatchingTouch) return;
            touchActive = false;
            long now = android.os.SystemClock.uptimeMillis();
            MotionEvent cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0, 0, 0);
            try { super.dispatchTouchEvent(cancel); }
            finally { cancel.recycle(); }
        }
        @Override public boolean dispatchKeyEvent(KeyEvent event) { return !interactive || super.dispatchKeyEvent(event); }
        @Override public boolean dispatchTouchEvent(MotionEvent event) {
            if (!interactive) return false;
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) touchActive = true;
            else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) touchActive = false;
            dispatchingTouch = true;
            try { return super.dispatchTouchEvent(event); }
            finally { dispatchingTouch = false; if (!interactive) cancelTouchGesture(); }
        }
        @Override public boolean dispatchGenericMotionEvent(MotionEvent event) { return interactive && super.dispatchGenericMotionEvent(event); }
        @Override public boolean dispatchHoverEvent(MotionEvent event) { return interactive && super.dispatchHoverEvent(event); }
        @Override public boolean performAccessibilityAction(int action, Bundle arguments) {
            return interactive && super.performAccessibilityAction(action, arguments);
        }
    }
    private static final View.AccessibilityDelegate PAGE_ACTIONS = new View.AccessibilityDelegate() {
        @Override public boolean performAccessibilityAction(View host, int action, Bundle arguments) {
            for (View view = host; view != null; ) {
                if (view instanceof PageScrollView && !((PageScrollView)view).interactive) return false;
                android.view.ViewParent parent = view.getParent();
                view = parent instanceof View ? (View)parent : null;
            }
            return super.performAccessibilityAction(host, action, arguments);
        }
    };
    private static final class FocusState {
        final View view; final int focusable, descendants, accessibilityImportance;
        FocusState(View view) {
            this.view = view; focusable = view.getFocusable();
            descendants = view instanceof ViewGroup ? ((ViewGroup)view).getDescendantFocusability() : 0;
            accessibilityImportance = view.getImportantForAccessibility();
        }
    }
    private final Activity activity;
    private final FrameLayout root;
    private final HeaderPillContainer source;
    private final float density;
    private final boolean animations, reducedMotion;
    private final boolean warmupOnly;
    private final SoundKeyLayout windowRoot;
    private final FrameLayout chrome;
    private final LinearLayout panel;
    private final FrameLayout body;
    private final TextView footer;
    private final Surface surface;
    private final List<Page> pages = new ArrayList<>();
    private final List<FocusState> underlayFocus = new ArrayList<>();
    private final LauncherSounds sounds;
    private final Typeface regularFont, mediumFont, semiboldFont;
    private final MenuKeyLatch actionKeys = new MenuKeyLatch();
    private View confirmTarget;
    private int confirmGeneration;
    private final MenuTrace timing = new MenuTrace();
    private View currentBody, outgoingBody;
    private FrameLayout modalShield;
    private View previousFocus;
    private BitmapDrawable profileGhost;
    private ValueAnimator animator, pageAnimator;
    private ViewTreeObserver openingObserver;
    private ViewTreeObserver.OnPreDrawListener openingPreDraw;
    private ViewTreeObserver underlayObserver;
    private ViewTreeObserver.OnGlobalLayoutListener underlayLayout;
    private Consumer<Boolean> transition;
    private DoubleConsumer motion;
    private OnDismissListener onClosed;
    private Runnable pendingAction;
    private int fullWidth, fullHeight, maximumHeight, windowHeight, startWidth, pageGeneration;
    private float shape, expandedHeight, ghostScale = 1, ghostX, ghostY;
    private float appliedWidth = Float.NaN, appliedHeight = Float.NaN, appliedRadius = Float.NaN;
    private float appliedMaterial = Float.NaN, appliedAlpha = Float.NaN, publishedMotion = Float.NaN;
    private int outlineWidth = Integer.MIN_VALUE, outlineHeight = Integer.MIN_VALUE;
    private float outlineRadius = Float.NaN;
    private final Rect sourceAvatar = new Rect(), targetAvatar = new Rect();
    private boolean showing, closing, finished, confirmingKey, dirty, interrupted, shapeMotion, pageMotion;

    HeaderStackPopup(Activity activity, FrameLayout root, HeaderPillContainer source,
                     boolean animations, boolean reducedMotion) {
        this(activity, root, source, animations, reducedMotion, false, null);
    }

    private HeaderStackPopup(Activity activity, FrameLayout root, HeaderPillContainer source,
                             boolean animations, boolean reducedMotion, boolean warmupOnly,
                             LauncherSounds warmupSounds) {
        super(); this.activity = activity; this.root = root; this.source = source;
        this.warmupOnly = warmupOnly;
        if (!warmupOnly) WARMUP.menuRequested();
        long construct = timing.begin("construct");
        this.animations = animations; this.reducedMotion = reducedMotion;
        density = activity.getResources().getDisplayMetrics().density;
        sounds = warmupSounds != null ? warmupSounds : LauncherSounds.acquire(activity);
        regularFont = InterTypography.face(activity, 400);
        mediumFont = InterTypography.face(activity, 500);
        semiboldFont = InterTypography.face(activity, 600);
        int safe = dp(24);
        fullWidth = Math.min(dp(280), root.getWidth() - 2 * safe);
        maximumHeight = Math.min(dp(330), root.getHeight() - 2 * safe);
        windowRoot = new SoundKeyLayout(activity) {
            @Override protected void dispatchDraw(Canvas canvas) {
                long draw = timing.beginDraw();
                try { super.dispatchDraw(canvas); }
                finally { timing.endDraw(draw); }
            }
            @Override public boolean dispatchKeyEvent(KeyEvent event) {
                int key = event.getKeyCode();
                boolean confirm = key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER
                        || key == KeyEvent.KEYCODE_NUMPAD_ENTER || key == KeyEvent.KEYCODE_DPAD_RIGHT;
                boolean back = key == KeyEvent.KEYCODE_BACK || key == KeyEvent.KEYCODE_MENU
                        || key == KeyEvent.KEYCODE_ESCAPE || key == KeyEvent.KEYCODE_DPAD_LEFT;
                if (confirm || back) {
                    MenuKeyLatch.Result result = actionKeys.event(key, event.getDeviceId(), event.getDownTime(),
                            event.getAction() == KeyEvent.ACTION_DOWN, event.getAction() == KeyEvent.ACTION_UP,
                            event.getRepeatCount(), event.isCanceled());
                    if (result == MenuKeyLatch.Result.PRESS) {
                        clearConfirmTarget();
                        if (confirm) {
                            View focused = findFocus();
                            if (isCurrentControl(focused)) confirmTarget = focused;
                            confirmGeneration = pageGeneration;
                        }
                        // Remap before children: their old Left/Right listeners cannot swallow
                        // these actions. SoundKeyLayout emits the normal soft cue once on DOWN.
                        dispatchAction(event, confirm ? KeyEvent.KEYCODE_DPAD_CENTER : KeyEvent.KEYCODE_BACK);
                    } else if (result == MenuKeyLatch.Result.RELEASE) {
                        if (confirm) {
                            View selected = confirmTarget;
                            boolean activate = confirmGeneration == pageGeneration && selected == findFocus()
                                    && isCurrentControl(selected);
                            // Finish native DOWN feedback before clicking synchronously. A normal
                            // View key-UP may post its click; teardown must not cancel that action
                            // or let its later pointer cue run after confirmingKey has been cleared.
                            clearConfirmTarget();
                            if (activate) {
                                confirmingKey = true;
                                try { selected.performClick(); }
                                finally { confirmingKey = false; }
                            }
                        } else navigateBack();
                    } else if (result == MenuKeyLatch.Result.CANCEL) clearConfirmTarget();
                    return true;
                }
                if (event.getAction() == KeyEvent.ACTION_DOWN && (key == KeyEvent.KEYCODE_DPAD_UP
                        || key == KeyEvent.KEYCODE_DPAD_DOWN || key == KeyEvent.KEYCODE_TAB)) cancelMenuAction();
                return super.dispatchKeyEvent(event);
            }
            private void dispatchAction(KeyEvent original, int mappedCode) {
                KeyEvent mapped = original.getKeyCode() == mappedCode ? original : new KeyEvent(
                        original.getDownTime(), original.getEventTime(), original.getAction(), mappedCode,
                        0, original.getMetaState(), original.getDeviceId(), original.getScanCode(),
                        original.getFlags(), original.getSource());
                confirmingKey = mappedCode == KeyEvent.KEYCODE_DPAD_CENTER;
                try { super.dispatchKeyEvent(mapped); }
                finally { confirmingKey = false; }
            }
            @Override public View focusSearch(View focused, int direction) {
                View next = FocusFinder.getInstance().findNextFocus(this, focused, direction);
                return next == null ? focused : next;
            }
            @Override public View focusSearch(int direction) {
                View focused = findFocus();
                return focusSearch(focused == null ? this : focused, direction);
            }
        };
        windowRoot.setId(R.id.headerMenuCard);
        // Compose the small card in one reusable surface. Crossfading pages then uses the
        // same offscreen pipeline as preparation, instead of a new window shader on Back.
        windowRoot.setLayerType(animations || warmupOnly ? View.LAYER_TYPE_HARDWARE : View.LAYER_TYPE_NONE, null);
        windowRoot.setOrientation(LinearLayout.VERTICAL);
        chrome = new FrameLayout(activity);
        windowRoot.addView(chrome, new LinearLayout.LayoutParams(-1, -1));
        panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL); panel.setPadding(dp(14), dp(8), dp(14), dp(8));
        chrome.addView(panel, new FrameLayout.LayoutParams(-1, -1));
        surface = new Surface(); panel.setBackground(surface);
        windowRoot.setClipToOutline(true);
        windowRoot.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, Math.round(width()), Math.round(height()), dp(17 + 6 * shape));
            }
        });
        body = new FrameLayout(activity); body.setClipChildren(false);
        panel.addView(body, new LinearLayout.LayoutParams(-1, 0));
        footer = text("Saved", 9, 500, 0x80FFFFFF); footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.setPaddingRelative(textStartInset(), 0, textEndInset(), 0);
        footer.setVisibility(View.GONE);
        panel.addView(footer, new LinearLayout.LayoutParams(-1, dp(20)));
        // Keep PopupWindow's public type for existing callers without creating a second HWUI window.
        setContentView(windowRoot);
        timing.end("construct", construct);
    }

    /** Keep optional preparation's sound ownership recoverable even if view construction fails. */
    static HeaderStackPopup createPrepared(Activity activity, FrameLayout root, HeaderPillContainer source,
                                           boolean animations, boolean reducedMotion) {
        LauncherSounds owned = LauncherSounds.acquire(activity);
        try { return new HeaderStackPopup(activity, root, source, animations, reducedMotion, false, owned); }
        catch (RuntimeException | LinkageError | OutOfMemoryError unavailable) {
            owned.close(); throw unavailable;
        }
    }

    /**
     * Optional startup work on Home's existing, initialized HWUI renderer. Call from the UI
     * thread after its first committed frame, before accepting a user menu request. No views
     * or profile data survive this call; only the renderer's own resource caches may benefit.
     * A true result means two layer builds were submitted, not a guarantee of GPU cache coverage.
     */
    static boolean warmUpRenderer(Activity activity, FrameLayout root, HeaderPillContainer source,
                                  Function<HeaderStackPopup, View> home,
                                  Function<HeaderStackPopup, View> settings) {
        boolean ready = Build.VERSION.SDK_INT >= 29 && Looper.myLooper() == Looper.getMainLooper()
                && activity != null && !activity.isFinishing() && !activity.isDestroyed()
                && root != null && root.isAttachedToWindow() && root.isHardwareAccelerated()
                && root.hasWindowFocus()
                && root.getWidth() > 0 && root.getHeight() > 0
                && source != null && source.isAttachedToWindow() && source.getWidth() > 0
                && source.getHeight() > 0 && root.getWindowToken() == source.getWindowToken()
                && home != null && settings != null;
        if (!WARMUP.tryStart(ready)) return false;

        long started = System.nanoTime();
        HeaderStackPopup prepared = null;
        FrameLayout hidden = null, target = null;
        LauncherSounds ownedSound = null;
        boolean completed = false;
        int builds = 0;
        try {
            Log.i("Menu.Perf", "phase=renderer_warmup_begin at_ns=" + started);
            // Keep this handle outside construction so even a constructor failure releases it.
            ownedSound = LauncherSounds.acquire(activity);
            prepared = new HeaderStackPopup(activity, root, source, false, false, true, ownedSound);
            if (prepared.fullWidth <= 0 || prepared.maximumHeight <= 0
                    || (long)prepared.fullWidth * prepared.maximumHeight > 1_048_576L) return false;
            final HeaderStackPopup menu = prepared;
            prepared.startWidth = Math.min(source.getWidth(), prepared.fullWidth);
            prepared.windowRoot.setAlpha(1);
            prepared.setRoot("Home", () -> home.apply(menu));
            final View homeViewport = prepared.currentBody;
            final int homeHeight = prepared.fullHeight;
            final int homeBodyHeight = homeViewport.getLayoutParams().height;
            source.avatarBounds(prepared.sourceAvatar);
            Bitmap profile = source.captureProfileLayer();
            if (profile != null) {
                prepared.profileGhost = new BitmapDrawable(root.getResources(), profile);
                prepared.profileGhost.setBounds(0, 0, source.getWidth(), source.getHeight());
                prepared.panel.getOverlay().add(prepared.profileGhost);
            }
            prepared.surface.setEmphasized(source.hasFocus() || source.isActivated());

            // The alpha-zero ancestor prevents any visible pixels. buildLayer() is called on
            // its alpha-one child directly, using that child's attached ThreadedRenderer.
            hidden = new FrameLayout(activity);
            hidden.setAlpha(0);
            hidden.setFocusable(false);
            hidden.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            hidden.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            target = new FrameLayout(activity);
            target.setFocusable(false);
            target.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            target.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            hidden.addView(target, new FrameLayout.LayoutParams(prepared.fullWidth, prepared.maximumHeight));
            target.addView(prepared.windowRoot,
                    new FrameLayout.LayoutParams(prepared.fullWidth, prepared.fullHeight));
            root.addView(hidden, new FrameLayout.LayoutParams(prepared.fullWidth, prepared.maximumHeight));
            if (!target.isAttachedToWindow() || !target.isHardwareAccelerated()) return false;
            // Match the real page and card layers; the wrapper only submits their hidden draw.
            // The card's normal outline, body alpha and ghost remain in the same hierarchy.
            target.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            prepared.windowRoot.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            for (int page = 0; page < 2; page++) {
                if (page == 1) prepared.push("Settings", () -> settings.apply(menu));
                int reservedHeight = prepared.fullHeight;
                if (page == 1) {
                    // Match Back's two endpoints without navigating or requesting real focus.
                    reservedHeight = Math.max(homeHeight, prepared.fullHeight);
                    prepared.setBodyHeight(Math.max(homeBodyHeight, prepared.currentBody.getLayoutParams().height));
                    prepared.expandedHeight = prepared.fullHeight + (homeHeight - prepared.fullHeight) * .35f;
                }
                ViewGroup.LayoutParams card = prepared.windowRoot.getLayoutParams();
                card.height = reservedHeight;
                prepared.windowRoot.setLayoutParams(card);
                int w = prepared.fullWidth, h = reservedHeight;
                hidden.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
                hidden.layout(0, 0, w, h);
                prepared.updateGhostTarget();
                View first = prepared.firstFocusable(homeViewport);
                Drawable background = first == null ? null : first.getBackground();
                if (background != null) {
                    // Exercise the private page's focused paint without moving focus or
                    // changing any View/source state in Home's actual navigation tree.
                    background.setState(new int[]{android.R.attr.state_enabled, android.R.attr.state_focused});
                    background.invalidateSelf(); first.invalidate();
                }
                if (page == 0) {
                    // Opening combines the real Home page layer with grouped body alpha and ghost.
                    prepared.apply(.7f);
                } else {
                    // At Back, body is opaque/NONE while two page layers overlap. Exercise that
                    // blend separately; the extra wrapper remains private and fully invisible.
                    prepared.apply(1);
                    prepared.currentBody.setAlpha(.65f); prepared.currentBody.setTranslationY(0);
                    homeViewport.setAlpha(.35f); homeViewport.setTranslationY(prepared.dp(2) * .65f);
                    homeViewport.bringToFront();
                }
                // apply() invalidates the actual material and outline, not just node alpha.
                prepared.windowRoot.invalidate();
                target.invalidate();
                target.buildLayer();
                builds++;
            }
            completed = true;
            return true;
        } catch (RuntimeException | LinkageError | OutOfMemoryError unavailable) {
            Log.w("Menu.Perf", "phase=renderer_warmup unavailable="
                    + unavailable.getClass().getSimpleName());
            return false;
        } finally {
            // Each cleanup step stands alone: an optional layer release must not prevent
            // detachment of the hidden tree or release of our independently owned sound handle.
            final HeaderStackPopup cleanupMenu = prepared;
            final FrameLayout cleanupTarget = target, cleanupHost = hidden;
            final LauncherSounds cleanupSound = ownedSound;
            warmupCleanup(() -> { if (cleanupMenu != null) cleanupMenu.disposeWarmup(); });
            warmupCleanup(() -> { if (cleanupTarget != null) cleanupTarget.setLayerType(View.LAYER_TYPE_NONE, null); });
            warmupCleanup(() -> { if (cleanupHost != null && cleanupHost.getParent() == root) root.removeView(cleanupHost); });
            warmupCleanup(() -> { if (cleanupSound != null) cleanupSound.close(); });
            Log.i("Menu.Perf", "phase=renderer_warmup completed=" + completed + " builds=" + builds
                    + " start_ns=" + started + " duration_ms="
                    + (System.nanoTime() - started) / 1_000_000.0);
        }
    }

    private static void warmupCleanup(Runnable cleanup) {
        try { cleanup.run(); }
        catch (RuntimeException | LinkageError | OutOfMemoryError unavailable) {
            Log.w("Menu.Perf", "phase=renderer_warmup_cleanup unavailable="
                    + unavailable.getClass().getSimpleName());
        }
    }

    private void disposeWarmup() {
        // Deliberately not normal cleanup(): it changes the real pill, focus and callbacks.
        finished = true; pageGeneration++;
        clearOpeningPreDraw(); cancelPageAnimator();
        if (profileGhost != null) { panel.getOverlay().remove(profileGhost); profileGhost = null; }
        windowRoot.setLayerType(View.LAYER_TYPE_NONE, null);
        body.removeAllViews(); currentBody = null; outgoingBody = null; pages.clear();
    }

    /** Abandon a never-shown, detached menu without changing Home or firing dismissal actions. */
    void disposePrepared() {
        if (showing || finished || windowRoot.isAttachedToWindow()) return;
        finished = true; pageGeneration++;
        try {
            cancelMenuAction(); clearOpeningPreDraw(); cancelPageAnimator();
            body.removeAllViews(); currentBody = null; pages.clear();
            windowRoot.setLayerType(View.LAYER_TYPE_NONE, null);
            pendingAction = null; onClosed = null; transition = null; motion = null;
        } finally { sounds.close(); }
    }

    @Override public boolean isShowing() { return showing; }
    @Override public void setOnDismissListener(OnDismissListener listener) { onClosed = listener; }
    boolean dispatchMenuKeyEvent(KeyEvent event) {
        if (!showing) return false;
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_BACK: case KeyEvent.KEYCODE_MENU: case KeyEvent.KEYCODE_ESCAPE:
            case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER: case KeyEvent.KEYCODE_TAB:
                if (!closing) {
                    if (!windowRoot.hasFocus() && currentBody != null && !pages.isEmpty())
                        restorePageFocus(pages.get(pages.size() - 1), currentBody, pageGeneration, 0);
                    windowRoot.dispatchKeyEvent(event);
                }
                return true;
            default: return false;
        }
    }
    void setTransitionListener(Consumer<Boolean> listener) { transition = listener; }
    void setMotionListener(DoubleConsumer listener) { motion = listener; publishedMotion = Float.NaN; }
    boolean changed() { return dirty; }
    boolean hasPendingAction() { return pendingAction != null; }
    boolean wasInterrupted() { return interrupted; }
    String pageTitle() { return pages.isEmpty() ? "" : pages.get(pages.size() - 1).title; }
    void markChanged() { dirty = true; invalidateCachedPages(); updateFooter(); }
    void invalidateCachedPages() {
        for (Page page : pages) page.reusable = false;
        trimRetainedParents();
    }
    void invalidateCachedPage(String title) {
        for (Page page : pages) if (page.title.equals(title)) page.reusable = false;
        trimRetainedParents();
    }
    void setRootMetadata(String title, Supplier<View> build) { pages.clear(); pages.add(new Page(title, build)); }
    void setRoot(String title, Supplier<View> build) { pages.clear(); pages.add(new Page(title, build)); renderPage(false); }
    void push(String title, Supplier<View> build) { rememberFocus(); pages.add(new Page(title, build)); renderPage(true); }
    void refreshPage() { rememberFocus(); invalidateCachedPages(); renderPage(true); }
    void backPage() { navigateBack(); }

    LinearLayout list() {
        LinearLayout rows = new LinearLayout(activity); rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(0, dp(3), 0, dp(6)); return rows;
    }
    TextView text(String value, int size, int weight, int color) {
        TextView text = new TextView(activity); text.setText(value); text.setTextSize(size);
        text.setTextColor(color); text.setIncludeFontPadding(false);
        text.setTypeface(weight == 600 ? semiboldFont : weight == 500 ? mediumFont
                : weight == 400 ? regularFont : InterTypography.face(activity, weight));
        return text;
    }
    View row(LinearLayout rows, int icon, String label, String detail, Runnable action) {
        // One row owns its two native labels; icons need no extra Views or layout passes.
        // Font fallback, bidi, ellipsizing and accessibility remain platform-owned.
        TextView name = text(label, 13, 500, 0xF2FFFFFF); name.setSingleLine(true); name.setEllipsize(TextUtils.TruncateAt.END);
        TextView note = null;
        if (detail != null && !detail.isEmpty()) {
            note = text(detail, 10, 400, 0xAFFFFFFF);
            note.setSingleLine(true); note.setEllipsize(TextUtils.TruncateAt.END);
        }
        MenuRow row = new MenuRow(activity, name, note, icon == 0 ? null : activity.getDrawable(icon),
                activity.getDrawable(R.drawable.ic_chevron_right), ICON_TINT, ARROW_TINT);
        row.enableGlyphBitmapCache(icon, R.drawable.ic_chevron_right);
        row.setTag(label); control(row);
        row.setOnKeyListener((v, key, event) -> key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT);
        row.setContentDescription(label + (detail == null || detail.isEmpty() ? "" : ", " + detail));
        row.setOnClickListener(v -> { if (isCurrentControl(v)) { pointerSound(); if (action != null) action.run(); } });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(detail == null || detail.isEmpty() ? 40 : 48));
        lp.bottomMargin = dp(4); rows.addView(row, lp); return row;
    }

    void control(View view) {
        view.setBackground(new MenuControlBackground(density));
        view.setAccessibilityDelegate(PAGE_ACTIONS);
        view.setFocusable(true); view.setClickable(true); view.setSoundEffectsEnabled(false);
    }

    private void pointerSound() { if (!confirmingKey) sounds.selection(); }
    private boolean isCurrentControl(View view) {
        if (view == null || !view.isClickable() || !view.isEnabled() || !view.isShown()) return false;
        for (View node = view; node != null; ) {
            if (node == currentBody) return true;
            android.view.ViewParent parent = node.getParent();
            node = parent instanceof View ? (View)parent : null;
        }
        return false;
    }
    private void clearConfirmTarget() {
        if (confirmTarget != null) {
            confirmTarget.cancelPendingInputEvents();
            confirmTarget.setPressed(false);
            confirmTarget = null;
        }
    }
    private void cancelMenuAction() { actionKeys.clear(); clearConfirmTarget(); }
    private void rememberFocus() {
        if (pages.isEmpty() || currentBody == null) return;
        View focus = currentBody.findFocus();
        if (focus != null) pages.get(pages.size() - 1).focusTag = focus.getTag();
    }
    private void renderPage(boolean animate) {
        if (pages.isEmpty() || closing) return;
        cancelMenuAction();
        final int generation = ++pageGeneration;
        cancelPageAnimator();
        Page page = pages.get(pages.size() - 1);
        timing.point("page_request:" + page.title);
        // The surface itself is a focus fallback only for a page with no controls.
        windowRoot.setFocusableInTouchMode(false); windowRoot.setFocusable(false);
        windowRoot.setContentDescription(null);
        updateFooter();
        boolean reuse = page.reusable && page.viewport != null && page.viewport != currentBody;
        final PageScrollView scroll;
        final View contents;
        if (reuse) {
            scroll = page.viewport; contents = page.contents;
            // Attached ancestors no longer get onAttachedToWindow again on Back.
            View identity = contents.findViewWithTag("Current profile");
            if (identity instanceof ProfilePill) ((ProfilePill)identity).refresh();
            timing.point("page_reuse:" + page.title);
        } else {
            scroll = new PageScrollView(activity);
            long build = timing.begin("page_build:" + page.title);
            try { contents = page.build.get(); }
            finally { timing.end("page_build:" + page.title, build); }
            scroll.addView(contents);
            page.viewport = scroll; page.contents = contents;
        }
        // Keep this small layer for the current page and up to four valid ancestors. Hidden
        // ancestors stay in the same window tree, so Back need not allocate their layer again.
        scroll.setLayerType(animations || warmupOnly ? View.LAYER_TYPE_HARDWARE : View.LAYER_TYPE_NONE, null);
        scroll.setInteractive(true); scroll.setAlpha(1); scroll.setTranslationY(0);
        int contentWidth = fullWidth - panel.getPaddingLeft() - panel.getPaddingRight();
        int exactWidth = View.MeasureSpec.makeMeasureSpec(contentWidth, View.MeasureSpec.EXACTLY);
        int naturalHeight = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        if (!reuse || contents.isLayoutRequested()) {
            long measure = timing.begin("page_measure:" + page.title);
            try { contents.measure(exactWidth, naturalHeight); page.contentHeight = contents.getMeasuredHeight(); }
            finally { timing.end("page_measure:" + page.title, measure); }
        }
        page.reusable = true;
        int chromeHeight = panel.getPaddingTop() + panel.getPaddingBottom()
                + (dirty ? dp(20) : 0);
        fullHeight = Math.min(maximumHeight, Math.max(dp(34), chromeHeight + page.contentHeight));
        final int bodyHeight = Math.max(0, fullHeight - chromeHeight);
        View old = currentBody;
        boolean transitionPages = animate && isShowing() && animations && windowRoot.getAlpha() > 0;
        int oldHeight = old == null ? 0 : old.getLayoutParams().height;
        if (oldHeight < 0) oldHeight = old.getHeight();
        // Each page keeps its own viewport while fading. Shrinking a shared MATCH_PARENT
        // viewport first would cut off the outgoing page before the card has contracted.
        int reservedBodyHeight = transitionPages ? Math.max(bodyHeight,
                Math.max(oldHeight, Math.max(0, Math.round(expandedHeight) - chromeHeight))) : bodyHeight;
        setBodyHeight(reservedBodyHeight);
        if (old != null) retirePage(old);
        currentBody = scroll;
        outgoingBody = transitionPages ? old : null;
        if (scroll.getParent() == body) {
            FrameLayout.LayoutParams layout = (FrameLayout.LayoutParams)scroll.getLayoutParams();
            if (layout.width != -1 || layout.height != bodyHeight) {
                layout.width = -1; layout.height = bodyHeight; scroll.setLayoutParams(layout);
            }
        } else body.addView(scroll, new FrameLayout.LayoutParams(-1, bodyHeight));
        scroll.bringToFront();
        trimRetainedParents();
        // Reserve only the larger endpoint once, then resize the actual window at rest.
        if (isShowing()) resizeWindow(Math.max(windowHeight, reservedBodyHeight + chromeHeight));
        if (transitionPages) {
            timing.operation("page:" + page.title);
            // Only card geometry changes per frame; the upper-left window anchor stays fixed.
            final float fromHeight = expandedHeight, oldAlpha = old == null ? 0 : old.getAlpha();
            pageMotion = true; publishTransition();
            scroll.setAlpha(0); scroll.setTranslationY(reducedMotion ? 0 : dp(2));
            pageAnimator = ValueAnimator.ofFloat(0, 1);
            pageAnimator.setDuration(reducedMotion ? 120 : 200);
            pageAnimator.setInterpolator(MOTION_CURVE);
            pageAnimator.addUpdateListener(value -> {
                float t = (float)value.getAnimatedValue();
                expandedHeight = fromHeight + (fullHeight - fromHeight) * t;
                scroll.setAlpha(t); scroll.setTranslationY(reducedMotion ? 0 : dp(2) * (1 - t));
                if (old != null) old.setAlpha(oldAlpha * (1 - t));
                apply(shape);
            });
            pageAnimator.addListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator animation) {
                    if (generation != pageGeneration) return;
                    outgoingBody = null;
                    trimRetainedParents();
                    setBodyHeight(bodyHeight);
                    expandedHeight = fullHeight; pageMotion = false; pageAnimator = null;
                    apply(shape); publishTransition(); settleWindow();
                }
            });
            pageAnimator.start();
        } else {
            expandedHeight = fullHeight; pageMotion = false; apply(shape); publishTransition(); settleWindow();
        }
        if (!reuse) LauncherSounds.muteSystemEffects(scroll);
        if (!warmupOnly) scroll.postOnAnimation(() -> {
            if (generation != pageGeneration || closing) return;
            updateGhostTarget();
            restorePageFocus(page, contents, generation, 0);
        });
    }

    private void cancelPageAnimator() {
        if (pageAnimator != null) {
            pageAnimator.removeAllListeners(); pageAnimator.cancel(); pageAnimator = null;
        }
        pageMotion = false;
        outgoingBody = null;
    }

    private void setBodyHeight(int height) {
        LinearLayout.LayoutParams layout = (LinearLayout.LayoutParams)body.getLayoutParams();
        if (layout.height != height) { layout.height = height; body.setLayoutParams(layout); }
    }

    private void resizeWindow(int height) {
        int next = Math.max(dp(34), Math.min(maximumHeight, height));
        if (windowHeight == next) return;
        windowHeight = next;
        ViewGroup.LayoutParams layout = windowRoot.getLayoutParams();
        if (layout != null) {
            layout.width = fullWidth; layout.height = next;
            windowRoot.setLayoutParams(layout);
        }
    }

    private void settleWindow() {
        if (!shapeMotion && !pageMotion && !closing && isShowing()) resizeWindow(Math.round(expandedHeight));
    }

    private void updateGhostTarget() {
        ghostScale = 1; ghostX = 0; ghostY = dp(5);
        if (pages.size() != 1 || currentBody == null || sourceAvatar.width() <= 0) return;
        View identity = currentBody.findViewWithTag("Current profile");
        if (!(identity instanceof ProfilePill)) return;
        ((ProfilePill)identity).avatarBounds(targetAvatar);
        windowRoot.offsetDescendantRectToMyCoords(identity, targetAvatar);
        if (targetAvatar.width() <= 0) return;
        ghostScale = (float)targetAvatar.width() / sourceAvatar.width();
        ghostX = targetAvatar.exactCenterX() - sourceAvatar.exactCenterX() * ghostScale;
        ghostY = targetAvatar.exactCenterY() - sourceAvatar.exactCenterY() * ghostScale;
    }

    private void retirePage(View view) {
        if (view instanceof PageScrollView) {
            ((PageScrollView)view).setInteractive(false);
            // Cancel already-posted clicks too; a dispatch gate alone cannot retract those.
            view.cancelPendingInputEvents();
        }
    }

    private void trimRetainedParents() {
        int retained = 0;
        for (int i = pages.size() - 2; i >= 0; i--) {
            Page page = pages.get(i);
            boolean keep = page.reusable && page.viewport != null && ++retained <= MAX_RETAINED_PARENTS;
            if (!keep && page.viewport != currentBody && page.viewport != outgoingBody) {
                if (page.viewport != null && page.viewport.getParent() == body) body.removeView(page.viewport);
                page.viewport = null; page.contents = null; page.reusable = false;
            }
        }
        for (int i = body.getChildCount() - 1; i >= 0; i--) {
            View child = body.getChildAt(i);
            if (child == currentBody || child == outgoingBody) continue;
            if (isRetainedParent(child)) {
                if (((PageScrollView)child).interactive) retirePage(child);
                child.setAlpha(0); child.setTranslationY(0);
            } else body.removeViewAt(i);
        }
    }

    private boolean isRetainedParent(View view) {
        int retained = 0;
        for (int i = pages.size() - 2; i >= 0; i--) {
            Page page = pages.get(i);
            if (!page.reusable || page.viewport == null) continue;
            if (++retained > MAX_RETAINED_PARENTS) return false;
            if (page.viewport == view) return true;
        }
        return false;
    }

    private View firstFocusable(View view) {
        if (view.getVisibility() != View.VISIBLE || !view.isEnabled()) return null;
        if (view.isFocusable()) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup)view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View first = firstFocusable(group.getChildAt(i));
                if (first != null) return first;
            }
        }
        return null;
    }
    private void restorePageFocus(Page page, View contents, int generation, int attempt) {
        if (generation != pageGeneration || closing || finished) return;
        View restore = page.focusTag == null ? null : contents.findViewWithTag(page.focusTag);
        if (restore == null || !restore.isFocusable() || !restore.isEnabled() || restore.getVisibility() != View.VISIBLE)
            restore = firstFocusable(contents);
        if (restore != null) {
            // A pointer-opened card must focus a real control even while Android is in touch mode.
            windowRoot.setFocusableInTouchMode(false); windowRoot.setFocusable(false);
            windowRoot.setContentDescription(null);
            if (!restore.requestFocus() && !restore.requestFocusFromTouch() && attempt < 3)
                windowRoot.postOnAnimation(() -> restorePageFocus(page, contents, generation, attempt + 1));
        } else {
            // Only a genuinely empty page uses the visible card surface as its focus target.
            windowRoot.setContentDescription(page.title);
            windowRoot.setFocusableInTouchMode(true); windowRoot.setFocusable(true);
            windowRoot.requestFocus();
        }
    }
    private void updateFooter() {
        footer.setVisibility(dirty ? View.VISIBLE : View.GONE);
    }
    private void navigateBack() {
        if (closing) return;
        if (pages.size() > 1) { pages.remove(pages.size() - 1); renderPage(true); }
        else close();
    }
    @Override public void dismiss() { sounds.selection(); navigateBack(); }
    void close() { dismissThen(null); }
    void dismissThen(Runnable action) {
        if (closing || finished) return;
        cancelMenuAction();
        closing = true; pendingAction = action;
        View closingPage = outgoingBody;
        cancelPageAnimator();
        // Freeze both cross-fading pages until close finishes; the incoming page may be alpha zero.
        outgoingBody = closingPage;
        trimRetainedParents();
        // No menu pixels have been revealed yet. Do not wait for an invisible close animation.
        if (openingPreDraw != null) finishDismiss(); else animate(true);
    }
    void dismissImmediately() {
        if (finished) return; interrupted = true; closing = true; pendingAction = null;
        if (animator != null) { animator.removeAllListeners(); animator.cancel(); }
        finishDismiss();
    }

    void show() {
        if (showing || finished) return;
        timing.point("show_request");
        shapeMotion = animations; publishTransition();
        int safe = dp(24), rootW = root.getWidth(), rootH = root.getHeight();
        startWidth = Math.min(source.getWidth(), fullWidth);
        int[] location = new int[2], rootLocation = new int[2];
        source.getLocationInWindow(location); root.getLocationInWindow(rootLocation);
        int x = Math.max(safe, Math.min(location[0] - rootLocation[0], rootW - safe - fullWidth));
        int y = Math.max(safe, Math.min(location[1] - rootLocation[1], rootH - safe - maximumHeight));
        previousFocus = root.findFocus();
        modalShield = new FrameLayout(activity);
        modalShield.setClickable(true); modalShield.setFocusable(false);
        modalShield.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        FrameLayout.LayoutParams card = new FrameLayout.LayoutParams(fullWidth, fullHeight, Gravity.TOP | Gravity.LEFT);
        card.leftMargin = x; card.topMargin = y;
        windowHeight = fullHeight;
        modalShield.addView(windowRoot, card);
        source.avatarBounds(sourceAvatar);
        Bitmap profile;
        long snapshot = timing.begin("profile_snapshot");
        try { profile = source.captureProfileLayer(); }
        finally { timing.end("profile_snapshot", snapshot); }
        if (profile != null) {
            profileGhost = new BitmapDrawable(root.getResources(), profile);
            profileGhost.setBounds(0, 0, source.getWidth(), source.getHeight());
            panel.getOverlay().add(profileGhost);
        }
        source.lockForMenu(true);
        surface.setEmphasized(source.hasFocus() || source.isActivated());
        apply(0); windowRoot.setAlpha(0);
        blockUnderlayFocus();
        showing = true;
        long attach = timing.begin("attach");
        try { root.addView(modalShield, new FrameLayout.LayoutParams(-1, -1)); }
        finally { timing.end("attach", attach); }
        LauncherSounds.muteSystemEffects(windowRoot);
        openingObserver = windowRoot.getViewTreeObserver();
        openingPreDraw = new ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                clearOpeningPreDraw();
                timing.point("first_pre_draw");
                if (showing && !closing && !finished) {
                    updateGhostTarget(); apply(shape);
                    windowRoot.setAlpha(1); source.setAlpha(0);
                }
                return true;
            }
        };
        openingObserver.addOnPreDrawListener(openingPreDraw);
        // Start before traversal: its first animation tick and reveal can share one vsync.
        animate(false);
    }
    private float width() { return startWidth + (fullWidth - startWidth) * shape; }
    private float height() { return dp(34) + (expandedHeight - dp(34)) * shape; }
    private static float smooth(float t) { t = Math.max(0, Math.min(1, t)); return t * t * (3 - 2 * t); }
    private void apply(float value) {
        shape = value;
        float w = width(), h = height(), radius = dp(17 + 6 * value);
        float alpha = smooth(value);
        if (appliedWidth != w || appliedHeight != h || appliedRadius != radius || appliedMaterial != alpha) {
            appliedWidth = w; appliedHeight = h; appliedRadius = radius; appliedMaterial = alpha;
            surface.invalidateSelf();
        }
        int clipW = Math.round(w), clipH = Math.round(h);
        if (outlineWidth != clipW || outlineHeight != clipH || outlineRadius != radius) {
            outlineWidth = clipW; outlineHeight = clipH; outlineRadius = radius;
            windowRoot.invalidateOutline();
        }
        if (appliedAlpha != alpha) {
            appliedAlpha = alpha;
            body.setAlpha(alpha); footer.setAlpha(alpha);
        }
        if (profileGhost != null) {
            int ghostAlpha = Math.round(255 * (1 - smooth(value / .78f)));
            if (ghostAlpha != 0 || profileGhost.getAlpha() != 0) {
                float scale = 1 + (ghostScale - 1) * alpha;
                int left = Math.round(ghostX * alpha), top = Math.round(ghostY * alpha);
                int right = left + Math.round(source.getWidth() * scale), bottom = top + Math.round(source.getHeight() * scale);
                Rect bounds = profileGhost.getBounds();
                if (bounds.left != left || bounds.top != top || bounds.right != right || bounds.bottom != bottom)
                    profileGhost.setBounds(left, top, right, bottom);
            }
            if (profileGhost.getAlpha() != ghostAlpha) profileGhost.setAlpha(ghostAlpha);
        }
        if (motion != null && publishedMotion != value) {
            publishedMotion = value;
            motion.accept(value);
        }
    }
    private void animate(boolean close) {
        timing.operation(close ? "close" : "open:" + pageTitle());
        if (animator != null) { animator.removeAllListeners(); animator.cancel(); }
        if (!animations) {
            shapeMotion = false; publishTransition();
            if (close) finishDismiss(); else apply(1); return;
        }
        shapeMotion = true; publishTransition();
        float from = shape;
        if (close && from < .001f) { finishDismiss(); return; }
        animator = ValueAnimator.ofFloat(0, 1); animator.setDuration(reducedMotion ? 140 : close ? 220 : 280);
        animator.setInterpolator(MOTION_CURVE);
        animator.addUpdateListener(value -> {
            float t = (float)value.getAnimatedValue();
            apply(close ? from * (1 - t) : from + (1 - from) * t);
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator a) {
                if (close) finishDismiss();
                else { apply(1); shapeMotion = false; publishTransition(); settleWindow(); }
            }
        });
        animator.start();
    }
    private void finishDismiss() {
        if (finished) return;
        cleanup();
        timing.point("closed");
        try { if (onClosed != null) onClosed.onDismiss(); }
        finally {
            Runnable action = pendingAction; pendingAction = null;
            if (action != null) action.run();
        }
    }
    private void blockUnderlayFocus() {
        isolateUnderlay();
        // Scene changes can attach a new background layer while this same-window menu is open.
        // Observe layouts, not animation frames, and preserve every newly attached layer's state.
        underlayObserver = root.getViewTreeObserver();
        underlayLayout = () -> { if (showing && !finished) isolateUnderlay(); };
        underlayObserver.addOnGlobalLayoutListener(underlayLayout);
    }
    private void isolateUnderlay() {
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            if (child == modalShield) continue;
            boolean saved = false;
            for (FocusState state : underlayFocus) if (state.view == child) { saved = true; break; }
            if (saved) continue;
            underlayFocus.add(new FocusState(child));
            if (child instanceof ViewGroup)
                ((ViewGroup)child).setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            if (child.isFocusable()) child.setFocusable(View.NOT_FOCUSABLE);
            child.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        }
    }
    private void restoreUnderlayFocus() {
        if (underlayObserver != null && underlayLayout != null && underlayObserver.isAlive())
            underlayObserver.removeOnGlobalLayoutListener(underlayLayout);
        underlayObserver = null; underlayLayout = null;
        for (FocusState saved : underlayFocus) {
            if (saved.view instanceof ViewGroup)
                ((ViewGroup)saved.view).setDescendantFocusability(saved.descendants);
            if (saved.view.getFocusable() != saved.focusable) saved.view.setFocusable(saved.focusable);
            if (saved.view.getImportantForAccessibility() != saved.accessibilityImportance)
                saved.view.setImportantForAccessibility(saved.accessibilityImportance);
        }
        underlayFocus.clear();
    }
    private void clearOpeningPreDraw() {
        if (openingObserver != null && openingPreDraw != null && openingObserver.isAlive())
            openingObserver.removeOnPreDrawListener(openingPreDraw);
        openingObserver = null; openingPreDraw = null;
    }
    private void cleanup() {
        if (finished) return; finished = true; showing = false; pageGeneration++;
        cancelMenuAction();
        clearOpeningPreDraw();
        if (animator != null) { animator.removeAllListeners(); animator.cancel(); }
        cancelPageAnimator();
        for (int i = 0; i < body.getChildCount(); i++) body.getChildAt(i).animate().cancel();
        source.setAlpha(1); source.lockForMenu(false);
        if (profileGhost != null) { panel.getOverlay().remove(profileGhost); profileGhost = null; }
        windowRoot.clearFocus();
        if (modalShield != null) {
            root.removeView(modalShield); modalShield.removeAllViews(); modalShield = null;
        }
        body.removeAllViews(); currentBody = null; pages.clear();
        windowRoot.setLayerType(View.LAYER_TYPE_NONE, null);
        restoreUnderlayFocus();
        shapeMotion = false; pageMotion = false; publishTransition();
        if (motion != null) motion.accept(0);
        sounds.close();
        View restore = previousFocus; previousFocus = null;
        // Pausing/Home already cancelled its scroll and hero work; do not restart it during teardown.
        if (interrupted) return;
        if (restore == null || restore.getWindowToken() == null || !restore.isShown() || !restore.isEnabled()
                || (!restore.requestFocus() && !restore.requestFocusFromTouch()))
            source.requestFocusFromTouch();
    }
    private void publishTransition() { if (transition != null) transition.accept(shapeMotion || pageMotion); }
    int dp(float value) { return Math.round(value * density); }
    /** Match row text after its start padding, icon and icon-to-label gap, including pixel rounding. */
    int textStartInset() { return dp(12) + dp(21) + dp(12); }
    /** Match row text before its trailing arrow and spacing. */
    int textEndInset() { return dp(12) + dp(15) + dp(8); }

    private final class Surface extends Drawable {
        final SettingsSurface material = new SettingsSurface(density);
        void setEmphasized(boolean value) { material.setEmphasized(value); invalidateSelf(); }
        @Override public void draw(Canvas canvas) { material.drawSurface(canvas, width(), height(), dp(17 + 6*shape), smooth(shape)); }
        @Override public void setAlpha(int alpha) { material.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter filter) { material.setColorFilter(filter); invalidateSelf(); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
