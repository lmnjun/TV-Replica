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
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.FocusFinder;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** An anchored modal card in Home's existing window; Back traverses its local page stack. */
final class HeaderStackPopup extends PopupWindow {
    private static final MenuWarmupGate WARMUP = new MenuWarmupGate();
    private static final android.content.res.ColorStateList ICON_TINT = android.content.res.ColorStateList.valueOf(0xD9FFFFFF);
    private static final android.content.res.ColorStateList ARROW_TINT = android.content.res.ColorStateList.valueOf(0xAFFFFFFF);
    private static final android.view.animation.Interpolator MOTION_CURVE = new android.view.animation.PathInterpolator(.22f, .8f, .24f, 1);
    private static final class Page {
        final String title; final Supplier<View> build; Object focusTag;
        Page(String title, Supplier<View> build) { this.title = title; this.build = build; }
    }
    private static final class FocusState {
        final View view; final int focusable, descendants;
        FocusState(View view) {
            this.view = view; focusable = view.getFocusable();
            descendants = view instanceof ViewGroup ? ((ViewGroup)view).getDescendantFocusability() : 0;
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
    private final LinearLayout panel, heading;
    private final FrameLayout body;
    private final TextView title, footer;
    private final View headingDivider;
    private final Surface surface;
    private final List<Page> pages = new ArrayList<>();
    private final List<FocusState> underlayFocus = new ArrayList<>();
    private final LauncherSounds sounds;
    private final Typeface regularFont, mediumFont, semiboldFont;
    private final Map<String, Integer> headingHeights = new HashMap<>();
    private int headingWidth = -1;
    private final MenuTrace timing = new MenuTrace();
    private View currentBody;
    private FrameLayout modalShield;
    private View previousFocus;
    private BitmapDrawable profileGhost;
    private ValueAnimator animator, pageAnimator;
    private ViewTreeObserver openingObserver;
    private ViewTreeObserver.OnPreDrawListener openingPreDraw;
    private Consumer<Boolean> transition;
    private DoubleConsumer motion;
    private OnDismissListener onClosed;
    private Runnable pendingAction;
    private int fullWidth, fullHeight, maximumHeight, windowHeight, startWidth, pageGeneration;
    private float shape, expandedHeight, ghostScale = 1, ghostX, ghostY;
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
                boolean confirm = event.getKeyCode() == KeyEvent.KEYCODE_DPAD_CENTER
                        || event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                        || event.getKeyCode() == KeyEvent.KEYCODE_NUMPAD_ENTER;
                if (confirm && event.getAction() == KeyEvent.ACTION_DOWN) confirmingKey = true;
                boolean handled = super.dispatchKeyEvent(event);
                if (confirm && event.getAction() == KeyEvent.ACTION_UP) confirmingKey = false;
                int key = event.getKeyCode();
                if (key == KeyEvent.KEYCODE_BACK || key == KeyEvent.KEYCODE_MENU || key == KeyEvent.KEYCODE_ESCAPE) {
                    if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) navigateBack();
                    return true;
                }
                return handled;
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
        heading = new LinearLayout(activity); heading.setOrientation(LinearLayout.VERTICAL);
        heading.setGravity(Gravity.START); heading.setBaselineAligned(false);
        int dividerHeight = Math.max(1, dp(.5f)), dividerGap = dp(5);
        // Keep the separator inside the existing title-to-content space, preserving page height.
        heading.setPadding(0, dp(4), 0, Math.max(0, dp(10) - dividerGap - dividerHeight));
        title = text("", 16, 600, 0xFFFFFFFF);
        title.setSingleLine(true); title.setEllipsize(TextUtils.TruncateAt.END);
        title.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams titleLayout = new LinearLayout.LayoutParams(-1, -2);
        titleLayout.setMarginStart(textStartInset()); titleLayout.setMarginEnd(textEndInset());
        heading.addView(title, titleLayout);
        headingDivider = new View(activity); headingDivider.setBackgroundColor(0x4D808080);
        headingDivider.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams dividerLayout = new LinearLayout.LayoutParams(-1, dividerHeight);
        dividerLayout.setMarginStart(dp(12)); dividerLayout.setMarginEnd(dp(12));
        dividerLayout.topMargin = dividerGap; heading.addView(headingDivider, dividerLayout);
        panel.addView(heading, new LinearLayout.LayoutParams(-1, -2));
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
            source.avatarBounds(prepared.sourceAvatar);
            Bitmap profile = source.captureProfileLayer();
            if (profile != null) {
                prepared.profileGhost = new BitmapDrawable(root.getResources(), profile);
                prepared.profileGhost.setBounds(0, 0, source.getWidth(), source.getHeight());
                prepared.panel.getOverlay().add(prepared.profileGhost);
            }
            prepared.surface.material.setEmphasized(source.hasFocus() || source.isActivated());

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
            // Only the wrapper is a forced layer. Keep the card's normal NONE layer type so
            // its real outline, body alpha and profile ghost draw together into this target.
            target.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            prepared.windowRoot.setLayerType(View.LAYER_TYPE_NONE, null);
            for (int page = 0; page < 2; page++) {
                if (page == 1) prepared.push("Settings", () -> settings.apply(menu));
                ViewGroup.LayoutParams card = prepared.windowRoot.getLayoutParams();
                card.height = prepared.fullHeight;
                prepared.windowRoot.setLayoutParams(card);
                int w = prepared.fullWidth, h = prepared.fullHeight;
                hidden.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
                hidden.layout(0, 0, w, h);
                prepared.updateGhostTarget();
                View first = prepared.firstFocusable(prepared.currentBody);
                Drawable background = first == null ? null : first.getBackground();
                if (background != null) {
                    // Exercise the private page's focused paint without moving focus or
                    // changing any View/source state in Home's actual navigation tree.
                    background.setState(new int[]{android.R.attr.state_enabled, android.R.attr.state_focused});
                    background.invalidateSelf(); first.invalidate();
                }
                // One .7 translucent state per page bounds synchronous startup work while exercising
                // the grouped body alpha and visible profile ghost; cache coverage is not guaranteed.
                prepared.apply(.7f);
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
        pages.clear();
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
    void setMotionListener(DoubleConsumer listener) { motion = listener; }
    boolean changed() { return dirty; }
    boolean hasPendingAction() { return pendingAction != null; }
    boolean wasInterrupted() { return interrupted; }
    String pageTitle() { return pages.isEmpty() ? "" : pages.get(pages.size() - 1).title; }
    void markChanged() { dirty = true; updateFooter(); }
    void setRootMetadata(String title, Supplier<View> build) { pages.clear(); pages.add(new Page(title, build)); }
    void setRoot(String title, Supplier<View> build) { pages.clear(); pages.add(new Page(title, build)); renderPage(false); }
    void push(String title, Supplier<View> build) { rememberFocus(); pages.add(new Page(title, build)); renderPage(true); }
    void refreshPage() { rememberFocus(); renderPage(true); }
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
        // Exact overlay bounds avoid LinearLayout's weighted second measure pass. Keep real
        // TextViews so font fallback, bidi, ellipsizing and accessibility remain platform-owned.
        FrameLayout row = new FrameLayout(activity);
        row.setPadding(dp(12), 0, dp(12), 0); row.setTag(label); control(row);
        if (icon != 0) {
            ImageView image = new ImageView(activity); image.setImageResource(icon);
            image.setImageTintList(ICON_TINT);
            image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            row.addView(image, new FrameLayout.LayoutParams(dp(21), dp(21), Gravity.START | Gravity.CENTER_VERTICAL));
        }
        LinearLayout copy = new LinearLayout(activity); copy.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(label, 13, 500, 0xF2FFFFFF); name.setSingleLine(true); name.setEllipsize(TextUtils.TruncateAt.END);
        copy.addView(name);
        if (detail != null && !detail.isEmpty()) {
            TextView note = text(detail, 10, 400, 0xAFFFFFFF);
            note.setSingleLine(true); note.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams noteLp = new LinearLayout.LayoutParams(-1, -2); noteLp.topMargin = dp(4);
            copy.addView(note, noteLp);
        }
        FrameLayout.LayoutParams copyLp = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL);
        copyLp.setMarginStart(icon == 0 ? 0 : dp(21) + dp(12));
        copyLp.setMarginEnd(dp(15) + dp(8)); row.addView(copy, copyLp);
        ImageView arrow = new ImageView(activity); arrow.setImageResource(R.drawable.ic_chevron_right);
        arrow.setImageTintList(ARROW_TINT);
        arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        FrameLayout.LayoutParams arrowLp = new FrameLayout.LayoutParams(dp(15), dp(15), Gravity.END | Gravity.CENTER_VERTICAL);
        row.addView(arrow, arrowLp);
        row.setOnKeyListener((v, key, event) -> key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT);
        row.setContentDescription(label + (detail == null || detail.isEmpty() ? "" : ", " + detail));
        row.setOnClickListener(v -> { pointerSound(); if (action != null) action.run(); });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(detail == null || detail.isEmpty() ? 40 : 48));
        lp.bottomMargin = dp(4); rows.addView(row, lp); return row;
    }

    void control(View view) {
        android.graphics.drawable.StateListDrawable states = new android.graphics.drawable.StateListDrawable();
        android.graphics.drawable.GradientDrawable active = new android.graphics.drawable.GradientDrawable();
        active.setColor(0x295D5D5D); active.setCornerRadius(dp(12));
        active.setStroke(Math.max(1, dp(1.2f)), 0xC9FFFFFF);
        android.graphics.drawable.GradientDrawable normal = new android.graphics.drawable.GradientDrawable();
        normal.setColor(0x09181818); normal.setCornerRadius(dp(12));
        android.graphics.drawable.GradientDrawable selected = new android.graphics.drawable.GradientDrawable();
        selected.setColor(0x25484848); selected.setCornerRadius(dp(12));
        selected.setStroke(Math.max(1, dp(.9f)), 0x80FFFFFF);
        states.addState(new int[]{android.R.attr.state_focused}, active);
        states.addState(new int[]{android.R.attr.state_selected}, selected); states.addState(new int[]{}, normal);
        view.setBackground(states); view.setFocusable(true); view.setClickable(true); view.setSoundEffectsEnabled(false);
    }

    private void pointerSound() { if (!confirmingKey) sounds.selection(); }
    private void rememberFocus() {
        if (pages.isEmpty() || currentBody == null) return;
        View focus = currentBody.findFocus();
        if (focus != null) pages.get(pages.size() - 1).focusTag = focus.getTag();
    }
    private void renderPage(boolean animate) {
        if (pages.isEmpty() || closing) return;
        final int generation = ++pageGeneration;
        cancelPageAnimator();
        for (int i = body.getChildCount() - 1; i >= 0; i--) {
            View child = body.getChildAt(i);
            if (child != currentBody) body.removeViewAt(i);
        }
        Page page = pages.get(pages.size() - 1);
        timing.point("page_request:" + page.title);
        boolean rootPage = pages.size() == 1;
        // The surface itself is a focus fallback only for a page with no controls.
        windowRoot.setFocusableInTouchMode(false); windowRoot.setFocusable(false);
        windowRoot.setContentDescription(null);
        heading.setVisibility(rootPage ? View.GONE : View.VISIBLE);
        String headingText = rootPage ? "" : page.title;
        if (!TextUtils.equals(title.getText(), headingText)) title.setText(headingText);
        updateFooter();
        ScrollView scroll = new ScrollView(activity); scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false); scroll.setFillViewport(false); scroll.setFocusable(false);
        View contents;
        long build = timing.begin("page_build:" + page.title);
        try { contents = page.build.get(); }
        finally { timing.end("page_build:" + page.title, build); }
        scroll.addView(contents);
        int contentWidth = fullWidth - panel.getPaddingLeft() - panel.getPaddingRight();
        int exactWidth = View.MeasureSpec.makeMeasureSpec(contentWidth, View.MeasureSpec.EXACTLY);
        int naturalHeight = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        long measure = timing.begin("page_measure:" + page.title);
        int headingHeight = 0;
        try {
            if (!rootPage) {
                if (headingWidth != contentWidth) { headingWidth = contentWidth; headingHeights.clear(); }
                Integer savedHeight = headingHeights.get(page.title);
                if (savedHeight == null) {
                    heading.measure(exactWidth, naturalHeight);
                    savedHeight = heading.getMeasuredHeight(); headingHeights.put(page.title, savedHeight);
                }
                headingHeight = savedHeight;
            }
            contents.measure(exactWidth, naturalHeight);
        } finally { timing.end("page_measure:" + page.title, measure); }
        int chromeHeight = panel.getPaddingTop() + panel.getPaddingBottom()
                + headingHeight + (dirty ? dp(20) : 0);
        fullHeight = Math.min(maximumHeight, Math.max(dp(34), chromeHeight + contents.getMeasuredHeight()));
        LinearLayout.LayoutParams bodyLp = (LinearLayout.LayoutParams)body.getLayoutParams();
        int bodyHeight = Math.max(0, fullHeight - chromeHeight);
        if (bodyLp.height != bodyHeight) { bodyLp.height = bodyHeight; body.setLayoutParams(bodyLp); }
        View old = currentBody;
        if (old != null) retirePage(old);
        currentBody = scroll;
        body.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        // Reserve only the larger endpoint once, then resize the actual window at rest.
        if (isShowing()) resizeWindow(Math.max(windowHeight, fullHeight));
        if (animate && isShowing() && animations && windowRoot.getAlpha() > 0) {
            timing.operation("page:" + page.title);
            // Only card geometry changes per frame; the upper-left window anchor stays fixed.
            final float fromHeight = expandedHeight, oldAlpha = old == null ? 0 : old.getAlpha();
            pageMotion = true; publishTransition();
            scroll.setAlpha(0); scroll.setTranslationY(reducedMotion ? 0 : dp(2));
            pageAnimator = ValueAnimator.ofFloat(0, 1);
            pageAnimator.setDuration(reducedMotion ? 120 : 220);
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
                    if (old != null) body.removeView(old);
                    expandedHeight = fullHeight; pageMotion = false; pageAnimator = null;
                    apply(shape); publishTransition(); settleWindow();
                }
            });
            pageAnimator.start();
        } else {
            if (old != null) body.removeView(old);
            expandedHeight = fullHeight; pageMotion = false; apply(shape); publishTransition(); settleWindow();
        }
        LauncherSounds.muteSystemEffects(scroll);
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
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup)view;
            group.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            for (int i = 0; i < group.getChildCount(); i++) retirePage(group.getChildAt(i));
        }
        view.clearFocus(); view.setFocusable(false); view.setEnabled(false);
        view.setClickable(false); view.setLongClickable(false);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
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
        closing = true; pendingAction = action;
        cancelPageAnimator(); animate(true);
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
        surface.material.setEmphasized(source.hasFocus() || source.isActivated());
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
                windowRoot.postOnAnimation(() -> {
                    if (!closing) {
                        updateGhostTarget(); apply(0);
                        windowRoot.setAlpha(1); source.setAlpha(0); animate(false);
                    }
                });
                return true;
            }
        };
        openingObserver.addOnPreDrawListener(openingPreDraw);
    }
    private float width() { return startWidth + (fullWidth - startWidth) * shape; }
    private float height() { return dp(34) + (expandedHeight - dp(34)) * shape; }
    private static float smooth(float t) { t = Math.max(0, Math.min(1, t)); return t * t * (3 - 2 * t); }
    private void apply(float value) {
        shape = value; surface.invalidateSelf(); windowRoot.invalidateOutline();
        float alpha = smooth(value);
        body.setAlpha(alpha); title.setAlpha(alpha); footer.setAlpha(alpha);
        headingDivider.setAlpha(alpha);
        if (profileGhost != null) {
            float blend = smooth(value);
            float scale = 1 + (ghostScale - 1) * blend;
            int left = Math.round(ghostX * blend), top = Math.round(ghostY * blend);
            profileGhost.setBounds(left, top, left + Math.round(source.getWidth() * scale), top + Math.round(source.getHeight() * scale));
            profileGhost.setAlpha(Math.round(255 * (1 - smooth(value / .78f))));
        }
        if (motion != null) motion.accept(value);
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
        animator = ValueAnimator.ofFloat(0, 1); animator.setDuration(reducedMotion ? 140 : close ? 230 : 310);
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
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            underlayFocus.add(new FocusState(child));
            if (child instanceof ViewGroup)
                ((ViewGroup)child).setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            if (child.isFocusable()) child.setFocusable(View.NOT_FOCUSABLE);
        }
    }
    private void restoreUnderlayFocus() {
        for (FocusState saved : underlayFocus) {
            if (saved.view instanceof ViewGroup)
                ((ViewGroup)saved.view).setDescendantFocusability(saved.descendants);
            if (saved.view.getFocusable() != saved.focusable) saved.view.setFocusable(saved.focusable);
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
        @Override public void draw(Canvas canvas) { material.drawSurface(canvas, width(), height(), dp(17 + 6*shape), smooth(shape)); }
        @Override public void setAlpha(int alpha) { material.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter filter) { material.setColorFilter(filter); invalidateSelf(); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
