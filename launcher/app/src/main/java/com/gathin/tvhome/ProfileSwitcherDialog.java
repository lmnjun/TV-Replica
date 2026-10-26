package com.gathin.tvhome;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Display-only profile card. Real account selection and sign-in stay in Android's own UI. */
final class ProfileSwitcherDialog extends Dialog {
    private final Activity activity;
    private final Runnable chooseGoogleAccount, manageAccounts;
    private final float density;
    private final List<View> actions = new ArrayList<>();
    private final ExecutorService photos = Executors.newSingleThreadExecutor();
    private LauncherSounds sounds;
    private LinearLayout panel;
    private TextView close;
    private OnDismissListener parentDismissed;
    private Consumer<Boolean> transition;
    private boolean animations, reducedMotion, closing, finished, confirmingKey, transitionActive;
    private Runnable pendingAction;

    static ProfileSwitcherDialog show(Activity activity, Runnable chooseGoogleAccount, Runnable manageAccounts) {
        ProfileSwitcherDialog dialog = new ProfileSwitcherDialog(activity, chooseGoogleAccount, manageAccounts);
        dialog.show();
        return dialog;
    }

    private ProfileSwitcherDialog(Activity activity, Runnable chooseGoogleAccount, Runnable manageAccounts) {
        super(activity, R.style.settings_page_dialog);
        this.activity = activity; this.chooseGoogleAccount = chooseGoogleAccount; this.manageAccounts = manageAccounts;
        density = activity.getResources().getDisplayMetrics().density;
        setCanceledOnTouchOutside(true);
        super.setOnDismissListener(dialog -> {
            finished = true; photos.shutdownNow(); transition(false);
            if (sounds != null) { sounds.close(); sounds = null; }
            if (parentDismissed != null) parentDismissed.onDismiss(this);
            Runnable action = pendingAction; pendingAction = null;
            if (action != null) action.run();
        });
    }

    @Override public void setOnDismissListener(OnDismissListener listener) { parentDismissed = listener; }

    void setTransitionListener(Consumer<Boolean> listener) {
        transition = listener;
        if (listener != null && transitionActive) listener.accept(true);
    }

    private void transition(boolean active) {
        if (transitionActive == active) return;
        transitionActive = active;
        if (transition != null) transition.accept(active);
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Config config = Config.load(activity);
        animations = config.animations; reducedMotion = config.reducedMotion;
        sounds = LauncherSounds.acquire(activity);
        panel = new LinearLayout(activity); panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(22), dp(18), dp(22), dp(16));
        GradientDrawable glass = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xF51C2936, 0xF50B131D});
        glass.setCornerRadius(dp(23)); glass.setStroke(Math.max(1, dp(.6f)), 0x38FFFFFF);
        panel.setBackground(glass);
        LinearLayout heading = new LinearLayout(activity); heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.addView(text("Google accounts", 22, 0xFFFFFFFF, 700), new LinearLayout.LayoutParams(0, -2, 1));
        close = text("Close", 10, 0xE6FFFFFF, 500); close.setGravity(Gravity.CENTER);
        control(close); heading.addView(close, new LinearLayout.LayoutParams(dp(54), dp(28)));
        close.setOnClickListener(v -> clicked(null));
        panel.addView(heading, new LinearLayout.LayoutParams(-1, dp(30)));
        TextView description = text("Use the Google accounts already signed in on this TV.", 10, 0xAFFFFFFF, 400);
        description.setSingleLine(true); description.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams note = new LinearLayout.LayoutParams(-1, -2); note.topMargin = dp(6);
        panel.addView(description, note);

        DeviceProfileStore.Profile current = DeviceProfileStore.read(activity);
        LinearLayout currentCard = new LinearLayout(activity);
        currentCard.setGravity(Gravity.CENTER_VERTICAL); currentCard.setPadding(dp(12), 0, dp(12), 0);
        GradientDrawable card = new GradientDrawable(); card.setColor(0x182D4251);
        card.setCornerRadius(dp(15)); currentCard.setBackground(card);
        ImageView avatar = new ImageView(activity); avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        avatar.setImageResource(R.drawable.profile_account_fallback);
        avatar.setImageTintList(android.content.res.ColorStateList.valueOf(0xBFFFFFFF));
        avatar.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) { outline.setOval(0, 0, view.getWidth(), view.getHeight()); }
        });
        avatar.setClipToOutline(true); avatar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        currentCard.addView(avatar, new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout identity = new LinearLayout(activity); identity.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(current.displayName(), 16, 0xF2FFFFFF, 600);
        name.setSingleLine(true); name.setEllipsize(TextUtils.TruncateAt.END); identity.addView(name);
        TextView caption = text(current.available() ? "Signed in on this TV" : "Choose a system account", 10, 0xAFFFFFFF, 400);
        LinearLayout.LayoutParams captionLp = new LinearLayout.LayoutParams(-1, -2); captionLp.topMargin = dp(4);
        identity.addView(caption, captionLp);
        LinearLayout.LayoutParams identityLp = new LinearLayout.LayoutParams(0, -2, 1); identityLp.setMarginStart(dp(12));
        currentCard.addView(identity, identityLp);
        TextView currentLabel = text(current.available() ? "CURRENT" : "", 9, 0xB6D9EBDD, 600);
        currentLabel.setLetterSpacing(.06f);
        currentCard.addView(currentLabel, new LinearLayout.LayoutParams(-2, -2));
        currentCard.setContentDescription(current.displayName() + (current.available() ? ", Current Google account" : ""));
        LinearLayout.LayoutParams currentLp = new LinearLayout.LayoutParams(-1, dp(56)); currentLp.topMargin = dp(14);
        panel.addView(currentCard, currentLp);
        loadPhoto(avatar);
        View divider = new View(activity); divider.setBackgroundColor(0x1AFFFFFF);
        LinearLayout.LayoutParams rule = new LinearLayout.LayoutParams(-1, Math.max(1, dp(.5f)));
        rule.topMargin = dp(10); rule.bottomMargin = dp(10); panel.addView(divider, rule);

        addAction("Switch Google account", "Choose with the TV’s account picker",
                R.drawable.profile_account_fallback, chooseGoogleAccount);
        addAction("Manage accounts", "Open TV settings to manage accounts", R.drawable.ic_settings_outline, manageAccounts);
        addAction("Add Google account", "Open the TV’s Google sign-in", R.drawable.profile_add_account, this::openAddAccount);
        TextView footer = text("BACK TO CLOSE", 9, 0x70FFFFFF, 500);
        footer.setLetterSpacing(.05f);
        LinearLayout.LayoutParams footerLp = new LinearLayout.LayoutParams(-1, -2); footerLp.topMargin = dp(12);
        panel.addView(footer, footerLp);
        close.setOnKeyListener((v, key, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (key == KeyEvent.KEYCODE_DPAD_DOWN) { actions.get(0).requestFocus(); return true; }
            return key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT;
        });
        setContentView(panel);
    }

    private void addAction(String title, String subtitle, int icon, Runnable action) {
        LinearLayout row = new LinearLayout(activity); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), 0, dp(12), 0); control(row);
        ImageView glyph = new ImageView(activity); glyph.setImageResource(icon);
        glyph.setImageTintList(android.content.res.ColorStateList.valueOf(0xDEFFFFFF));
        glyph.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(glyph, new LinearLayout.LayoutParams(dp(21), dp(21)));
        LinearLayout copy = new LinearLayout(activity); copy.setOrientation(LinearLayout.VERTICAL);
        copy.addView(text(title, 13, 0xF2FFFFFF, 500));
        LinearLayout.LayoutParams sub = new LinearLayout.LayoutParams(-1, -2); sub.topMargin = dp(3);
        copy.addView(text(subtitle, 10, 0x9FFFFFFF, 400), sub);
        LinearLayout.LayoutParams copyLp = new LinearLayout.LayoutParams(0, -2, 1); copyLp.setMarginStart(dp(12));
        row.addView(copy, copyLp);
        ImageView arrow = new ImageView(activity); arrow.setImageResource(R.drawable.ic_chevron_right);
        arrow.setImageTintList(android.content.res.ColorStateList.valueOf(0xBFFFFFFF));
        arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(arrow, new LinearLayout.LayoutParams(dp(16), dp(16)));
        int index = actions.size();
        row.setOnKeyListener((v, key, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (key == KeyEvent.KEYCODE_DPAD_UP && index == 0) { close.requestFocus(); return true; }
            return key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT
                    || key == KeyEvent.KEYCODE_DPAD_DOWN && index == 2;
        });
        row.setContentDescription(title + ", " + subtitle);
        row.setOnClickListener(v -> clicked(action));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(48));
        if (index > 0) lp.topMargin = dp(4);
        panel.addView(row, lp); actions.add(row);
    }

    private void clicked(Runnable action) {
        if (!confirmingKey && sounds != null) sounds.selection();
        if (closing) return;
        pendingAction = action; dismiss();
    }

    private void openAddAccount() {
        try {
            activity.startActivity(new Intent(Settings.ACTION_ADD_ACCOUNT)
                    .putExtra(Settings.EXTRA_ACCOUNT_TYPES, new String[]{"com.google"}));
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            Toast.makeText(activity, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    private void loadPhoto(ImageView target) {
        File file = DeviceProfileStore.avatarFile(activity);
        if (!file.isFile()) return;
        photos.execute(() -> {
            BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), bounds);
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = Math.max(1, Math.max(bounds.outWidth, bounds.outHeight) / 160);
            Bitmap image = BitmapFactory.decodeFile(file.getPath(), options);
            if (image != null) activity.runOnUiThread(() -> {
                if (!closing && !finished) { target.setImageTintList(null); target.setImageBitmap(image); }
            });
        });
    }

    @Override public void show() {
        super.show();
        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT)); window.setWindowAnimations(0);
            window.setLayout(Math.min(dp(420), activity.getResources().getDisplayMetrics().widthPixels - dp(96)), -2);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND); window.setDimAmount(.40f);
            LauncherSounds.muteWindowRoot(window.getDecorView());
        }
        actions.get(0).requestFocus();
        if (animations) {
            transition(true); panel.setAlpha(0); panel.setTranslationY(reducedMotion ? 0 : dp(5));
            panel.animate().alpha(1).translationY(0).setDuration(reducedMotion ? 100 : 180)
                    .withEndAction(() -> { if (!closing) transition(false); }).start();
        }
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        boolean confirm = event.getKeyCode() == KeyEvent.KEYCODE_DPAD_CENTER || event.getKeyCode() == KeyEvent.KEYCODE_ENTER;
        if (confirm && event.getAction() == KeyEvent.ACTION_DOWN) confirmingKey = true;
        if (sounds != null) sounds.onKeyEvent(event);
        boolean result = super.dispatchKeyEvent(event);
        if (confirm && event.getAction() == KeyEvent.ACTION_UP) confirmingKey = false;
        return result;
    }

    @Override public void dismiss() {
        if (closing || finished) return; closing = true;
        if (panel == null || !animations || !isShowing()) { finishDismiss(); return; }
        panel.animate().setListener(null); panel.animate().cancel(); transition(true);
        panel.animate().alpha(0).translationY(reducedMotion ? 0 : dp(4)).setDuration(reducedMotion ? 80 : 130)
                .setListener(new AnimatorListenerAdapter() {
                    @Override public void onAnimationEnd(Animator animation) { finishDismiss(); }
                }).start();
    }

    void dismissImmediately() {
        if (finished) return; pendingAction = null; closing = true;
        if (panel != null) { panel.animate().setListener(null); panel.animate().cancel(); }
        finishDismiss();
    }

    private void finishDismiss() {
        if (finished) return;
        finished = true; photos.shutdownNow(); transition(false);
        super.dismiss();
    }

    private TextView text(String value, int size, int color, int weight) {
        TextView text = new TextView(activity); text.setText(value); text.setTextSize(size); text.setTextColor(color);
        text.setIncludeFontPadding(false); InterTypography.apply(text, weight); return text;
    }

    private void control(View view) {
        StateListDrawable state = new StateListDrawable();
        GradientDrawable focus = new GradientDrawable(); focus.setColor(0x2A496276); focus.setCornerRadius(dp(12));
        focus.setStroke(Math.max(1, dp(.6f)), 0x70FFFFFF);
        GradientDrawable rest = new GradientDrawable(); rest.setColor(0x08182430); rest.setCornerRadius(dp(12));
        state.addState(new int[]{android.R.attr.state_focused}, focus); state.addState(new int[]{}, rest);
        view.setBackground(state); view.setFocusable(true); view.setClickable(true); view.setSoundEffectsEnabled(false);
    }

    private int dp(float value) { return Math.round(value * density); }
}
