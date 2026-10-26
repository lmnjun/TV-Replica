package com.gathin.tvhome;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** One isolated layout preview for Dock and Apps. Only a successful Done commits storage. */
final class DockEditorDialog extends Dialog {
    private final Activity activity;
    private final Map<String, AppAdapter.Entry> entries = new HashMap<>();
    private final LayoutDraft draft, original;
    private final int columns;
    private final String initialKey;
    private final boolean animations, reducedMotion;
    private final Predicate<LayoutDraft> save;
    private final Consumer<Boolean> transition;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final float density;
    private final Runnable releaseMotion = this::updateBusy;
    private RecyclerView grid;
    private Cells cells;
    private LinearLayout panel;
    private TextView hint, status, cancel, done;
    private LayoutDraft pickup;
    private String movingKey;
    private int selected, revision, focusGeneration;
    private long movingUntil;
    private boolean committed, closing, dismissed, windowMoving, interrupted;
    private LauncherSounds sounds;

    DockEditorDialog(Activity activity, List<AppAdapter.Entry> source, List<String> dock, List<String> apps,
                     int columns, String initialKey, boolean animations, boolean reducedMotion,
                     Predicate<LayoutDraft> save, Consumer<Boolean> transition) {
        super(activity);
        this.activity = activity; this.columns = Math.max(1, columns); this.initialKey = initialKey;
        this.animations = animations; this.reducedMotion = reducedMotion; this.save = save; this.transition = transition;
        density = activity.getResources().getDisplayMetrics().density;
        for (AppAdapter.Entry e : source) if (e.component != null) entries.put(e.component.flattenToShortString(), e);
        draft = new LayoutDraft(dock, apps); original = draft.copy();
        setCanceledOnTouchOutside(false);
    }

    boolean wasCommitted() { return committed; }
    boolean wasInterrupted() { return interrupted; }
    String selectedKey() { return movingKey != null ? movingKey : draft.keyAt(selected); }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        sounds=LauncherSounds.acquire(activity);
        Window window = getWindow();
        if (window != null) { window.requestFeature(Window.FEATURE_NO_TITLE); window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT)); }
        panel = new LinearLayout(activity); panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(24), dp(18), dp(24), dp(14));
        GradientDrawable glass = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xF02C2C2C, 0xF3141414});
        glass.setCornerRadius(dp(23)); glass.setStroke(Math.max(1, dp(.6f)), 0x48FFFFFF); panel.setBackground(glass);
        LinearLayout header = new LinearLayout(activity); header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout copy = new LinearLayout(activity); copy.setOrientation(LinearLayout.VERTICAL);
        copy.addView(text("Arrange apps", 23, 0xFFFFFFFF, 700), new LinearLayout.LayoutParams(-1, dp(29)));
        status = text("", 11, 0xB9FFFFFF, 400); copy.addView(status, new LinearLayout.LayoutParams(-1, dp(19)));
        header.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        cancel = action("Cancel", false); cancel.setContentDescription("Cancel layout changes"); cancel.setOnClickListener(v -> dismiss());
        done = action("Done", true); done.setContentDescription("Done. Save Dock and Apps layout"); done.setOnClickListener(v -> commit());
        header.addView(cancel, new LinearLayout.LayoutParams(dp(76), dp(36)));
        LinearLayout.LayoutParams doneLp = new LinearLayout.LayoutParams(dp(76), dp(36)); doneLp.leftMargin = dp(8); header.addView(done, doneLp);
        panel.addView(header, new LinearLayout.LayoutParams(-1, dp(50)));
        grid = new RecyclerView(activity); grid.setClipToPadding(false); grid.setPadding(0, dp(7), 0, dp(8));
        grid.setOverScrollMode(View.OVER_SCROLL_NEVER); grid.setItemViewCacheSize(24);
        cells = new Cells();
        GridLayoutManager layout = new GridLayoutManager(activity, columns);
        layout.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override public int getSpanSize(int position) { return cells.getCurrentList().get(position).header ? columns : 1; }
        });
        grid.setLayoutManager(layout); grid.setAdapter(cells);
        if (animations && !reducedMotion) {
            DefaultItemAnimator animator = new DefaultItemAnimator(); animator.setSupportsChangeAnimations(false);
            animator.setMoveDuration(220); animator.setAddDuration(120); animator.setRemoveDuration(120); grid.setItemAnimator(animator);
        } else grid.setItemAnimator(null);
        grid.addItemDecoration(new RecyclerView.ItemDecoration() {
            @Override public void getItemOffsets(@NonNull android.graphics.Rect out, @NonNull View view, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
                int position = parent.getChildAdapterPosition(view);
                if (position < 0 || position >= cells.getCurrentList().size()) return;
                Cell c = cells.getCurrentList().get(position);
                if (!c.header) { int local = c.dock ? c.index : c.index - draft.dockSize(); out.right = local % columns == columns - 1 ? 0 : dp(8); out.bottom = dp(8); }
            }
        });
        panel.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1));
        View line = new View(activity); line.setBackgroundColor(0x20FFFFFF); panel.addView(line, new LinearLayout.LayoutParams(-1, Math.max(1, dp(.5f))));
        hint = text("", 11, 0xD9FFFFFF, 400); hint.setGravity(Gravity.CENTER_VERTICAL); hint.setSingleLine(true); hint.setEllipsize(TextUtils.TruncateAt.END);
        panel.addView(hint, new LinearLayout.LayoutParams(-1, dp(32)));
        View.OnKeyListener actionKeys = (v, key, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (key == KeyEvent.KEYCODE_DPAD_DOWN) { focus(selected); return true; }
            if (key == KeyEvent.KEYCODE_DPAD_UP) return true;
            return key == KeyEvent.KEYCODE_DPAD_LEFT && v == cancel || key == KeyEvent.KEYCODE_DPAD_RIGHT && v == done;
        };
        cancel.setOnKeyListener(actionKeys); done.setOnKeyListener(actionKeys);
        setContentView(panel);
        selected = Math.max(0, draft.positionOf(initialKey));
        submit(initialKey, selected); updateHint(null);
        LauncherSounds.muteSystemEffects(panel);
    }

    @Override public void show() {
        super.show();
        Window w = getWindow();
        if (w != null) {
            w.getDecorView().setPadding(0, 0, 0, 0);
            w.setLayout(Math.min(dp(864), activity.getResources().getDisplayMetrics().widthPixels - dp(72)),
                    Math.min(dp(478), activity.getResources().getDisplayMetrics().heightPixels - dp(48)));
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND); w.setDimAmount(.46f);
            LauncherSounds.muteWindowRoot(w.getDecorView());
        }
        panel.postOnAnimation(() -> { if (!closing && isShowing()) focus(selected); });
        if (animations) {
            windowMoving = true; updateBusy(); panel.setAlpha(0); panel.setTranslationY(reducedMotion ? 0 : dp(12));
            panel.animate().alpha(1).translationY(0).setDuration(reducedMotion ? 130 : 260)
                    .setInterpolator(new AccelerateDecelerateInterpolator()).setListener(new AnimatorListenerAdapter() {
                        @Override public void onAnimationEnd(Animator a) { windowMoving = false; updateBusy(); }
                    }).start();
        }
    }

    private void activate(Cell cell) {
        if (closing || cell.header) return;
        String key = cell.key; int at = key == null ? cell.index : draft.positionOf(key);
        if (movingKey != null) { movingKey = null; pickup = null; submit(key, at); updateHint("Position previewed. Done saves your layout."); return; }
        if (key == null) { updateHint("Pick up an app below, then move it into this empty Dock position."); return; }
        pickup = draft.copy(); movingKey = key; selected = at;
        submit(key, at); updateHint(null);
    }

    private int destination(int from, int key) {
        boolean dock = from < draft.dockSize();
        int base = dock ? 0 : draft.dockSize(), length = dock ? draft.dockSize() : draft.appCount(), local = from - base;
        if (key == KeyEvent.KEYCODE_DPAD_LEFT) return local % columns == 0 ? from : from - 1;
        if (key == KeyEvent.KEYCODE_DPAD_RIGHT) return local % columns == columns - 1 || local == length - 1 ? from : from + 1;
        if (key == KeyEvent.KEYCODE_DPAD_UP) {
            if (local >= columns) return from - columns;
            return !dock && draft.dockSize() > 0 ? Math.min(local, draft.dockSize() - 1) : -1;
        }
        if (local + columns < length) return from + columns;
        if (dock && draft.appCount() > 0) return draft.dockSize() + Math.min(local % columns, draft.appCount() - 1);
        if (!dock && local / columns < (length - 1) / columns) return base + length - 1;
        return -2;
    }

    private boolean navigate(Cell cell, int key, KeyEvent event) {
        if (key != KeyEvent.KEYCODE_DPAD_LEFT && key != KeyEvent.KEYCODE_DPAD_RIGHT && key != KeyEvent.KEYCODE_DPAD_UP && key != KeyEvent.KEYCODE_DPAD_DOWN) return false;
        if (event.getAction() != KeyEvent.ACTION_DOWN) return true;
        if (closing) return true;
        int from = movingKey != null ? draft.positionOf(movingKey) : cell.key == null ? cell.index : draft.positionOf(cell.key);
        if (from < 0) return true;
        int to = destination(from, key);
        if (to < 0) { if (movingKey == null) { if (to == -1) cancel.requestFocus(); else done.requestFocus(); } return true; }
        if (to == from) return true;
        if (movingKey != null) {
            if (draft.move(from, to)) {
                selected = draft.positionOf(movingKey); holdMove(); submit(movingKey, selected); updateHint(null);
            }
        } else { selected = to; focus(to); }
        return true;
    }

    private void submit(String focusKey, int fallback) {
        List<Cell> next = new ArrayList<>();
        if (draft.dockSize() > 0) {
            next.add(Cell.header("dock", "DOCK", "Favourite apps · " + draft.dockSize() + " positions"));
            for (int i = 0; i < draft.dockSize(); i++) next.add(new Cell(draft.keyAt(i), i, true, movingKey));
        }
        next.add(Cell.header("apps", "APPS", "Move freely between Dock and Apps"));
        for (int i = draft.dockSize(); i < draft.size(); i++) next.add(new Cell(draft.keyAt(i), i, false, movingKey));
        if (draft.appCount() == 0) next.add(Cell.header("empty", "", "All available apps are in the Dock"));
        int current = ++revision;
        cells.submitList(next, () -> { if (isShowing() && !closing && current == revision) focus(focusKey == null ? fallback : Math.max(0, draft.positionOf(focusKey))); });
        boolean modified = !draft.keys().equals(original.keys()) || !draft.dockSlots().equals(original.dockSlots());
        status.setText(modified ? "Unsaved layout · Done to apply" : "Dock and Apps · Preview changes, then Done");
        InterTypography.apply(status, modified ? 500 : 400);
    }

    private void focus(int index) {
        if (draft.size() == 0) { cancel.requestFocus(); return; }
        selected = Math.max(0, Math.min(index, draft.size() - 1));
        int target = -1;
        for (int i = 0; i < cells.getCurrentList().size(); i++) if (!cells.getCurrentList().get(i).header && cells.getCurrentList().get(i).index == selected) { target = i; break; }
        if (target < 0) return;
        requestTileFocus(target, ++focusGeneration, revision, 0);
    }

    private void requestTileFocus(int target, int generation, int version, int attempt) {
        if (closing || dismissed || !isShowing() || generation != focusGeneration || version != revision) return;
        RecyclerView.ViewHolder holder = grid.findViewHolderForAdapterPosition(target);
        if (holder != null && holder.itemView.isLaidOut()) { holder.itemView.requestFocus(); return; }
        if (attempt >= 6) return;
        grid.scrollToPosition(target);
        grid.postOnAnimation(() -> requestTileFocus(target, generation, version, attempt + 1));
    }

    private void updateHint(String message) {
        if (message != null) { hint.setText(message); return; }
        AppAdapter.Entry e = entries.get(movingKey);
        hint.setText(movingKey == null ? "OK picks up an app  ·  Arrow keys move  ·  Done saves  ·  Back cancels"
                : "Moving " + (e == null ? "app" : e.label) + "  ·  Arrows move  ·  OK places  ·  Back undoes this move");
    }

    private void commit() {
        if (closing || committed) return;
        if (movingKey != null) { movingKey = null; pickup = null; submit(draft.keyAt(selected), selected); }
        if (!save.test(draft.copy())) { updateHint("Couldn't save the layout. Try Done again."); return; }
        committed = true; dismiss();
    }

    @Override public void onBackPressed() {
        if (closing) return;
        if (movingKey != null && pickup != null) {
            String key = movingKey; draft.reset(pickup); movingKey = null; pickup = null;
            selected = Math.max(0, draft.positionOf(key)); holdMove(); submit(key, selected); updateHint("Move undone. Your other previews are unchanged.");
        } else dismiss();
    }

    @Override public boolean dispatchKeyEvent(@NonNull KeyEvent event) {
        if(!closing&&sounds!=null)sounds.onKeyEvent(event);
        if (event.getKeyCode() == KeyEvent.KEYCODE_MENU || event.getKeyCode() == KeyEvent.KEYCODE_ESCAPE) {
            if (event.getAction() == KeyEvent.ACTION_UP) onBackPressed(); return true;
        }
        return super.dispatchKeyEvent(event)||LauncherSounds.handleUnhandledNavigation(panel,event);
    }

    private void holdMove() {
        if (!animations || reducedMotion) return;
        movingUntil = SystemClock.uptimeMillis() + 250; updateBusy();
        handler.removeCallbacks(releaseMotion); handler.postDelayed(releaseMotion, 255);
    }
    private void updateBusy() { if (transition != null) transition.accept(!dismissed && (windowMoving || SystemClock.uptimeMillis() < movingUntil)); }

    @Override public void dismiss() {
        if (closing || dismissed) return;
        closing = true; movingUntil = 0; handler.removeCallbacks(releaseMotion);
        if (panel == null || !animations || !isShowing()) { finishDismiss(); return; }
        panel.animate().setListener(null); panel.animate().cancel(); windowMoving = true; updateBusy();
        panel.animate().alpha(0).translationY(reducedMotion ? 0 : dp(10)).setDuration(reducedMotion ? 110 : 190)
                .setInterpolator(new AccelerateDecelerateInterpolator()).setListener(new AnimatorListenerAdapter() {
                    @Override public void onAnimationEnd(Animator a) { finishDismiss(); }
                }).start();
    }
    void dismissImmediately() {
        interrupted = true;
        if (dismissed) return; closing = true;
        if (panel != null) { panel.animate().setListener(null); panel.animate().cancel(); }
        finishDismiss();
    }
    private void finishDismiss() {
        if (dismissed) return; dismissed = true; windowMoving = false; movingUntil = 0; handler.removeCallbacksAndMessages(null);
        if (grid != null && grid.getItemAnimator() != null) grid.getItemAnimator().endAnimations();
        if(sounds!=null){sounds.close();sounds=null;}
        updateBusy(); super.dismiss();
    }

    private TextView text(String value, int size, int color, int weight) {
        TextView t = new TextView(activity); t.setText(value); t.setTextSize(size); t.setTextColor(color); t.setIncludeFontPadding(false);
        InterTypography.apply(t, weight); return t;
    }
    private TextView action(String value, boolean primary) {
        TextView t = text(value, 12, 0xFFFFFFFF, primary ? 600 : 500); t.setGravity(Gravity.CENTER); t.setBackground(surface(primary)); t.setFocusable(true); t.setClickable(true);t.setSoundEffectsEnabled(false); return t;
    }
    private StateListDrawable surface(boolean selected) {
        StateListDrawable result = new StateListDrawable();
        result.addState(new int[]{android.R.attr.state_focused}, shape(selected ? 0x607A7A7A : 0x366F6F6F, selected ? 0xFFE6E6E6 : 0xD0FFFFFF));
        result.addState(new int[]{}, shape(selected ? 0x30555555 : 0x0CFFFFFF, selected ? 0x90DADADA : 0x25FFFFFF)); return result;
    }
    private GradientDrawable shape(int fill, int edge) {
        GradientDrawable d = new GradientDrawable(); d.setColor(fill); d.setCornerRadius(dp(13)); d.setStroke(Math.max(1, dp(.6f)), edge); return d;
    }
    private int dp(float n) { return Math.round(n * density); }

    private static final class Cell {
        final String id, key, title, subtitle; final int index; final boolean header, dock, picked;
        Cell(String key, int index, boolean dock, String moving) {
            this.key = key; this.index = index; this.dock = dock; picked = key != null && key.equals(moving); header = false;
            id = key == null ? "empty-slot:" + index : key; title = ""; subtitle = "";
        }
        private Cell(String id, String title, String subtitle) { this.id = "section:" + id; this.title = title; this.subtitle = subtitle; key = null; index = -1; header = true; dock = false; picked = false; }
        static Cell header(String id, String title, String subtitle) { return new Cell(id, title, subtitle); }
    }
    private final class Cells extends ListAdapter<Cell, RecyclerView.ViewHolder> {
        Cells() {
            super(new DiffUtil.ItemCallback<Cell>() {
                @Override public boolean areItemsTheSame(@NonNull Cell a, @NonNull Cell b) { return a.id.equals(b.id); }
                @Override public boolean areContentsTheSame(@NonNull Cell a, @NonNull Cell b) { return a.index == b.index && a.picked == b.picked && a.dock == b.dock && a.title.equals(b.title) && a.subtitle.equals(b.subtitle); }
            }); setHasStableIds(true);
        }
        @Override public long getItemId(int p) { String key = getItem(p).id; long h = 1125899906842597L; for (int i = 0; i < key.length(); i++) h = 31 * h + key.charAt(i); return h; }
        @Override public int getItemViewType(int p) { return getItem(p).header ? 0 : 1; }
        @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            if (type == 0) {
                LinearLayout row = new LinearLayout(activity); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(dp(2), dp(4), dp(2), dp(2));
                TextView title = text("", 11, 0xDEFFFFFF, 600); title.setLetterSpacing(.10f); row.addView(title, new LinearLayout.LayoutParams(dp(64), -2));
                TextView caption = text("", 10, 0x9FFFFFFF, 400); row.addView(caption, new LinearLayout.LayoutParams(0, -2, 1));
                row.setLayoutParams(new RecyclerView.LayoutParams(-1, dp(29))); return new Header(row, title, caption);
            }
            LinearLayout card = new LinearLayout(activity); card.setOrientation(LinearLayout.VERTICAL); card.setGravity(Gravity.CENTER); card.setPadding(dp(7), dp(7), dp(7), dp(5));
            card.setFocusable(true); card.setClickable(true);card.setSoundEffectsEnabled(false); card.setId(View.generateViewId());
            ImageView image = new ImageView(activity); image.setScaleType(ImageView.ScaleType.FIT_CENTER); image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            card.addView(image, new LinearLayout.LayoutParams(-1, dp(38)));
            TextView name = text("", 11, 0xF2FFFFFF, 500); name.setSingleLine(true); name.setEllipsize(TextUtils.TruncateAt.END); name.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams label = new LinearLayout.LayoutParams(-1, dp(23)); label.topMargin = dp(3); card.addView(name, label);
            card.setLayoutParams(new RecyclerView.LayoutParams(-1, dp(78))); return new Tile(card, image, name);
        }
        @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            Cell c = getItem(position);
            if (holder instanceof Header) { Header h = (Header) holder; h.title.setText(c.title); h.caption.setText(c.subtitle); return; }
            Tile h = (Tile) holder; h.cell = c; AppAdapter.Entry e = entries.get(c.key);
            h.itemView.setBackground(surface(c.picked)); h.label.setText(e == null ? "Empty" : c.picked ? "Moving · " + e.label : c.dock ? (c.index + 1) + " · " + e.label : e.label);
            h.label.setTextColor(c.picked ? 0xFFECECEC : 0xF2FFFFFF);
            InterTypography.apply(h.label, c.picked ? 600 : 500);
            int glyph = AppAdapter.nativeGlyph(e);
            Drawable source = e == null ? activity.getDrawable(R.drawable.ic_apps) : glyph != 0 ? activity.getDrawable(glyph) : e.banner != null ? e.banner : e.icon;
            h.image.setImageDrawable(source == null ? null : source.getConstantState() == null ? source : source.getConstantState().newDrawable(activity.getResources()).mutate());
            h.image.setAlpha(e == null ? .3f : 1);
            h.itemView.setContentDescription((c.dock ? "Dock slot " + (c.index + 1) + ": " : "App: ") + (e == null ? "Empty" : e.label) + (c.picked ? ". Moving" : ". Press OK to move"));
        }
    }
    private final class Header extends RecyclerView.ViewHolder {
        final TextView title, caption; Header(View root, TextView title, TextView caption) { super(root); this.title = title; this.caption = caption; }
    }
    private final class Tile extends RecyclerView.ViewHolder {
        final ImageView image; final TextView label; Cell cell;
        Tile(View view, ImageView image, TextView label) {
            super(view); this.image = image; this.label = label;
            view.setOnClickListener(v -> { if (cell != null) activate(cell); });
            view.setOnKeyListener((v, key, event) -> cell != null && navigate(cell, key, event));
            view.setOnFocusChangeListener((v, focused) -> { if (focused && cell != null) { selected = cell.key == null ? cell.index : Math.max(0, draft.positionOf(cell.key)); } });
        }
    }
}
