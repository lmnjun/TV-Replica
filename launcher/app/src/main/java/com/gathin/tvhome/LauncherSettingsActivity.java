package com.gathin.tvhome;

import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The launcher's own settings, with a stable remote focus path and immediate saving. */
public final class LauncherSettingsActivity extends Activity {
    private SharedPreferences choices;
    private final TextView[] columnChoices = new TextView[4];
    private final TextView[] rowChoices = new TextView[4];
    private final View[] navigation = new View[3];
    private final View[] pages = new View[3];
    private final ExecutorService thumbnails = Executors.newSingleThreadExecutor();
    private TextView wallpaperValue, animationsValue, advancedValue, themeValue;
    private TextView selectionSoundsValue,powerSoundsValue;
    private LauncherSounds sounds;
    private View advancedChoice, content;
    private Dialog openDialog;
    private int columns, rowsBelowDock, selectedPage = -1;
    private boolean animations, advancedEffects, reducedMotion, dirty;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_launcher_settings);
        choices = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        sounds=LauncherSounds.acquire(this);
        dirty = state != null && state.getBoolean("settings_page_dirty", false);
        if (dirty) setResult(RESULT_OK);
        readConfig();
        content = findViewById(R.id.settings_page_content);
        int[] navIds = {R.id.settings_page_nav_appearance, R.id.settings_page_nav_motion,
                R.id.settings_page_nav_system};
        int[] pageIds = {R.id.settings_page_appearance, R.id.settings_page_motion,
                R.id.settings_page_system};
        for (int i = 0; i < navigation.length; i++) {
            final int page = i;
            navigation[i] = findViewById(navIds[i]);
            pages[i] = findViewById(pageIds[i]);
            navigation[i].setOnFocusChangeListener((v, focused) -> {
                if (focused) showPage(page, true);
            });
            navigation[i].setOnClickListener(v -> {
                showPage(page, true);
                firstControl(page).requestFocus();
            });
            navigation[i].setOnKeyListener((v, key, event) -> {
                if (event.getAction() == KeyEvent.ACTION_DOWN
                        && key == (rtl() ? KeyEvent.KEYCODE_DPAD_LEFT : KeyEvent.KEYCODE_DPAD_RIGHT)) {
                    showPage(page, true);
                    firstControl(page).requestFocus();
                    return true;
                }
                return false;
            });
        }
        findViewById(R.id.settings_page_back).setOnClickListener(v -> finish());
        int[] ids = {R.id.columns4, R.id.columns5, R.id.columns6, R.id.columns7};
        for (int i = 0; i < ids.length; i++) {
            final int value = i + 4;
            columnChoices[i] = findViewById(ids[i]);
            columnChoices[i].setOnClickListener(v -> {
                if (columns == value) return;
                columns = value;
                choices.edit().putInt("columns", value).apply();
                changed();
            });
            configureChoice(columnChoices[i], i == (rtl() ? 3 : 0));
        }
        int[] rowIds = {R.id.rows0, R.id.rows1, R.id.rows2, R.id.rows3};
        for (int i = 0; i < rowIds.length; i++) {
            final int value = i;
            rowChoices[i] = findViewById(rowIds[i]);
            rowChoices[i].setOnClickListener(v -> {
                if (rowsBelowDock == value) return;
                rowsBelowDock = value;
                choices.edit().putInt(Config.ROWS_BELOW_DOCK_KEY, value).apply();
                changed();
            });
            configureChoice(rowChoices[i], i == (rtl() ? 3 : 0));
        }
        if (rtl()) {
            reverseRow(columnChoices);
            reverseRow(rowChoices);
        }
        wallpaperValue = findViewById(R.id.settings_page_wallpaper_value);
        animationsValue = findViewById(R.id.settings_page_animations_value);
        advancedValue = findViewById(R.id.settings_page_advanced_value);
        themeValue = findViewById(R.id.settings_page_theme_value);
        bindRow(R.id.wallpaperChoice, this::showWallpaperPicker);
        bindRow(R.id.animationsChoice, () -> {
            animations = !animations;
            if (!animations) advancedEffects = false;
            choices.edit().putBoolean("animations", animations)
                    .putBoolean("advanced_effects", advancedEffects).apply();
            changed();
        });
        advancedChoice = findViewById(R.id.advancedChoice);
        bindRow(R.id.advancedChoice, () -> {
            if (advancedEffects) setAdvanced(false);
            else showAdvancedConfirmation();
        });
        bindRow(R.id.followThemeChoice, () -> {
            if (!hasOnTvChoices()) return;
            choices.edit().remove("columns").remove(Config.ROWS_BELOW_DOCK_KEY)
                    .remove("animations").remove("advanced_effects")
                    .remove(Config.WALLPAPER_CHOICE_KEY)
                    .remove(Config.WALLPAPER_SCENE_OVERRIDE_KEY).commit();
            readConfig();
            changed();
        });
        bindRow(R.id.defaultHomeChoice, () -> {
            Toast.makeText(this, R.string.settings_page_home_hint, Toast.LENGTH_LONG).show();
            open(new Intent(Settings.ACTION_HOME_SETTINGS));
        });
        bindRow(R.id.tvSettingsChoice, () -> open(new Intent(Settings.ACTION_SETTINGS)));
        selectionSoundsValue=addSoundToggle(R.string.launcher_selection_sounds,R.string.launcher_selection_sounds_hint,
                LauncherSounds.SELECTION_ENABLED_KEY);
        powerSoundsValue=addSoundToggle(R.string.launcher_power_sounds,R.string.launcher_power_sounds_hint,
                LauncherSounds.POWER_ENABLED_KEY);
        LauncherSounds.muteWindowRoot(getWindow().getDecorView());
        refresh();
        int page = state == null ? 0 : Math.max(0, Math.min(2, state.getInt("settings_page", 0)));
        showPage(page, false);
        firstControl(page).requestFocus();
        if (animations && !reducedMotion) {
            content.setAlpha(0f);
            content.setTranslationX(dp(8));
            content.animate().alpha(1f).translationX(0).setDuration(220).start();
        }
    }

    private void readConfig() {
        Config config = Config.load(this);
        columns = config.columns;
        rowsBelowDock = config.rowsBelowDock;
        animations = config.animations;
        advancedEffects = config.animations && config.advancedEffects;
        reducedMotion = config.reducedMotion;
    }

    private void bindRow(int id, Runnable action) {
        View row = findViewById(id);
        row.setOnClickListener(v -> action.run());
        row.setOnKeyListener((v, key, event) -> returnToNavigation(key, event));
    }

    private TextView addSoundToggle(int title,int description,String key){
        LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);row.setGravity(Gravity.CENTER_VERTICAL);
        row.setId(View.generateViewId());styleControl(row);
        ImageView icon=new ImageView(this);icon.setImageResource(R.drawable.ic_launcher_sound);
        icon.setImageTintList(getResources().getColorStateList(R.color.settings_page_ink,getTheme()));
        icon.setDuplicateParentStateEnabled(true);icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(icon,new LinearLayout.LayoutParams(dp(22),dp(22)));
        LinearLayout copy=new LinearLayout(this);copy.setOrientation(LinearLayout.VERTICAL);copy.setDuplicateParentStateEnabled(true);
        TextView name=dialogText(getString(title),13,R.color.settings_page_ink);InterTypography.apply(name,600);copy.addView(name);
        TextView detail=dialogText(getString(description),10,R.color.settings_page_secondary);detail.setSingleLine(true);detail.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams detailLp=new LinearLayout.LayoutParams(-1,-2);detailLp.topMargin=dp(5);copy.addView(detail,detailLp);
        LinearLayout.LayoutParams copyLp=new LinearLayout.LayoutParams(0,-2,1);copyLp.setMarginStart(dp(14));row.addView(copy,copyLp);
        TextView value=dialogText("",11,R.color.settings_page_secondary);InterTypography.apply(value,600);
        LinearLayout.LayoutParams valueLp=new LinearLayout.LayoutParams(-2,-2);valueLp.setMarginStart(dp(12));row.addView(value,valueLp);
        LinearLayout.LayoutParams rowLp=new LinearLayout.LayoutParams(-1,dp(72));rowLp.topMargin=dp(10);
        ((LinearLayout)pages[1]).addView(row,rowLp);
        row.setOnKeyListener((v,code,event)->returnToNavigation(code,event));
        row.setOnClickListener(v->{choices.edit().putBoolean(key,!choices.getBoolean(key,true)).apply();changed();});
        return value;
    }

    private void configureChoice(TextView choice, boolean atStart) {
        choice.setOnKeyListener((v, key, event) -> atStart && returnToNavigation(key, event));
    }

    private boolean returnToNavigation(int key, KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN
                && key == (rtl() ? KeyEvent.KEYCODE_DPAD_RIGHT : KeyEvent.KEYCODE_DPAD_LEFT)) {
            navigation[selectedPage].requestFocus();
            return true;
        }
        return false;
    }

    private boolean rtl() { return getResources().getBoolean(R.bool.rtl_app_grid); }

    private View firstControl(int page) {
        if (page == 1) return findViewById(R.id.animationsChoice);
        if (page == 2) return findViewById(R.id.defaultHomeChoice);
        return columnChoices[Math.max(0, Math.min(columns - 4, 3))];
    }

    private void showPage(int page, boolean animate) {
        if (pages[page].getVisibility() == View.VISIBLE && selectedPage == page) return;
        selectedPage = page;
        for (int i = 0; i < pages.length; i++) {
            pages[i].animate().cancel();
            pages[i].setVisibility(i == page ? View.VISIBLE : View.GONE);
            pages[i].setAlpha(1f);
            pages[i].setTranslationY(0);
            navigation[i].setSelected(i == page);
            InterTypography.apply((TextView)navigation[i], i == page ? 600 : 500);
        }
        ((ScrollView)findViewById(R.id.settings_page_scroll)).scrollTo(0, 0);
        if (animate && animations && !reducedMotion) {
            pages[page].setAlpha(0f);
            pages[page].setTranslationY(dp(4));
            pages[page].animate().alpha(1).translationY(0).setDuration(170).start();
        }
    }

    private void reverseRow(TextView[] views) {
        LinearLayout row = (LinearLayout)views[0].getParent();
        row.removeAllViews();
        for (int i = views.length - 1; i >= 0; i--) row.addView(views[i]);
    }

    private void changed() { dirty = true; setResult(RESULT_OK); refresh(); }

    private void refresh() {
        for (int i = 0; i < columnChoices.length; i++) {
            boolean selected = columns == i + 4;
            columnChoices[i].setSelected(selected);
            InterTypography.apply(columnChoices[i], selected ? 600 : 500);
            columnChoices[i].setContentDescription((i + 4) + " " + getString(R.string.columns_label)
                    + (selected ? ", " + getString(R.string.settings_page_selected) : ""));
        }
        for (int i = 0; i < rowChoices.length; i++) {
            boolean selected = rowsBelowDock == i;
            rowChoices[i].setSelected(selected);
            InterTypography.apply(rowChoices[i], selected ? 600 : 500);
            rowChoices[i].setContentDescription(i + " " + getString(R.string.rows_below_dock_label)
                    + (selected ? ", " + getString(R.string.settings_page_selected) : ""));
        }
        animationsValue.setText(animations ? R.string.on : R.string.off);
        advancedValue.setText(advancedEffects ? R.string.on : R.string.off);
        selectionSoundsValue.setText(choices.getBoolean(LauncherSounds.SELECTION_ENABLED_KEY,true)?R.string.on:R.string.off);
        powerSoundsValue.setText(choices.getBoolean(LauncherSounds.POWER_ENABLED_KEY,true)?R.string.on:R.string.off);
        advancedChoice.setEnabled(animations);
        advancedChoice.setFocusable(animations);
        advancedChoice.setAlpha(animations ? 1f : .42f);
        String wallpaper = choices.getString(Config.WALLPAPER_CHOICE_KEY, null);
        wallpaperValue.setText("none".equals(wallpaper) ? getString(R.string.wallpaper_none)
                : wallpaper != null ? wallpaper : getString(R.string.wallpaper_theme_default));
        themeValue.setText(hasOnTvChoices() ? R.string.settings_page_custom : R.string.settings_page_following);
        findViewById(R.id.followThemeChoice).setSelected(!hasOnTvChoices());
    }

    private boolean hasOnTvChoices() {
        return choices.contains("columns") || choices.contains(Config.ROWS_BELOW_DOCK_KEY)
                || choices.contains("animations") || choices.contains("advanced_effects")
                || choices.contains(Config.WALLPAPER_CHOICE_KEY)
                || choices.contains(Config.WALLPAPER_SCENE_OVERRIDE_KEY);
    }

    private void showWallpaperPicker() {
        File dir = Config.dir(this);
        List<File> images = new ArrayList<>(WallpaperRotation.images(
                dir == null ? null : new File(dir, "wallpapers")));
        File single = dir == null ? null : new File(dir, Config.WALLPAPER_NAME);
        if (single != null && single.isFile()) images.add(0, single);
        String current = choices.getString(Config.WALLPAPER_CHOICE_KEY, null);
        Dialog dialog = newDialog(getString(R.string.wallpaper_label),
                getString(R.string.settings_page_wallpaper_hint));
        java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        dialog.setOnDismissListener(d -> {
            cancelled.set(true);
            if (openDialog == dialog) openDialog = null;
        });
        LinearLayout shell = dialog.findViewById(R.id.settings_page_dialog_shell);
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, dp(2), 0, dp(2));
        scroll.addView(list);
        LinearLayout.LayoutParams sc = new LinearLayout.LayoutParams(-1,
                Math.min(dp(278), dp(62) * (images.size() + 2)));
        sc.topMargin = dp(16);
        shell.addView(scroll, sc);
        List<View> optionRows = new ArrayList<>();
        optionRows.add(addWallpaperOption(list, dialog, getString(R.string.wallpaper_theme_default),
                getString(R.string.settings_page_wallpaper_theme), null, current == null,
                () -> choices.edit().remove(Config.WALLPAPER_CHOICE_KEY)
                        .remove(Config.WALLPAPER_SCENE_OVERRIDE_KEY).apply(), cancelled));
        for (File image : images) {
            optionRows.add(addWallpaperOption(list, dialog, image.getName(),
                    getString(R.string.settings_page_wallpaper_image), image,
                    image.getName().equals(current), () -> choices.edit()
                            .putString(Config.WALLPAPER_CHOICE_KEY, image.getName())
                            .putBoolean(Config.WALLPAPER_SCENE_OVERRIDE_KEY, true).apply(), cancelled));
        }
        optionRows.add(addWallpaperOption(list, dialog, getString(R.string.wallpaper_none),
                getString(R.string.settings_page_wallpaper_plain), null, "none".equals(current),
                () -> choices.edit().putString(Config.WALLPAPER_CHOICE_KEY, "none")
                        .putBoolean(Config.WALLPAPER_SCENE_OVERRIDE_KEY, true).apply(), cancelled));
        addDialogAction(shell, getString(android.R.string.cancel), dialog::dismiss);
        fitWallpaperList(shell, scroll);
        showDialog(dialog);
        int selected = current == null ? 0 : "none".equals(current) ? optionRows.size() - 1
                : Math.max(0, indexOf(images, current) + 1);
        optionRows.get(selected).requestFocus();
    }

    private View addWallpaperOption(LinearLayout list, Dialog dialog, String title, String subtitle,
                                    File image, boolean selected, Runnable write,
                                    java.util.concurrent.atomic.AtomicBoolean cancelled) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        styleControl(row);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(58));
        lp.bottomMargin = dp(4);
        list.addView(row, lp);
        row.setSelected(selected);
        ImageView preview = new ImageView(this);
        preview.setScaleType(image == null ? ImageView.ScaleType.CENTER_INSIDE
                : ImageView.ScaleType.CENTER_CROP);
        preview.setBackgroundResource(R.drawable.settings_page_thumbnail);
        preview.setClipToOutline(true);
        preview.setImageResource(image == null ? R.drawable.ic_picture : 0);
        preview.setImageTintList(image == null ? android.content.res.ColorStateList.valueOf(0xB3FFFFFF) : null);
        row.addView(preview, new LinearLayout.LayoutParams(dp(66), dp(40)));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setDuplicateParentStateEnabled(true);
        TextView name = dialogText(title, 12, R.color.settings_page_ink);
        InterTypography.apply(name, selected ? 600 : 500);
        name.setSingleLine(true); name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(name);
        TextView detail = dialogText(subtitle, 10, R.color.settings_page_secondary);
        detail.setSingleLine(true);
        LinearLayout.LayoutParams dt = new LinearLayout.LayoutParams(-1, -2);
        dt.topMargin = dp(3); texts.addView(detail, dt);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(0, -2, 1);
        textLp.setMarginStart(dp(12)); row.addView(texts, textLp);
        TextView marker = dialogText(selected ? "✓" : "", 16, R.color.settings_page_ink);
        InterTypography.apply(marker, 600);
        marker.setGravity(Gravity.CENTER); row.addView(marker, new LinearLayout.LayoutParams(dp(28), -1));
        row.setContentDescription(title + (selected ? ", " + getString(R.string.settings_page_selected) : ""));
        row.setOnClickListener(v -> {
            write.run(); changed(); dialog.dismiss();
            Toast.makeText(this, R.string.wallpaper_applied, Toast.LENGTH_SHORT).show();
        });
        if (image != null) thumbnails.execute(() -> {
            if (cancelled.get() || Thread.currentThread().isInterrupted()) return;
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true; BitmapFactory.decodeFile(image.getPath(), bounds);
            BitmapFactory.Options sample = new BitmapFactory.Options();
            sample.inPreferredConfig = Bitmap.Config.RGB_565;
            sample.inSampleSize = Math.max(1, Math.max(bounds.outWidth / 200, bounds.outHeight / 120));
            Bitmap bitmap = BitmapFactory.decodeFile(image.getPath(), sample);
            if (bitmap != null) runOnUiThread(() -> {
                if (!cancelled.get() && !isFinishing() && dialog.isShowing()) preview.setImageBitmap(bitmap);
                else bitmap.recycle();
            });
        });
        return row;
    }

    private int dialogWidth() {
        android.graphics.Rect visible = new android.graphics.Rect();
        getWindow().getDecorView().getWindowVisibleDisplayFrame(visible);
        int width = visible.width() > 0 ? visible.width() : getResources().getDisplayMetrics().widthPixels;
        return Math.min(dp(550), Math.max(dp(240), width - dp(96)));
    }

    /** Reserve the title, description and Cancel first; only the image list may scroll. */
    private void fitWallpaperList(LinearLayout shell, ScrollView scroll) {
        android.graphics.Rect visible = new android.graphics.Rect();
        getWindow().getDecorView().getWindowVisibleDisplayFrame(visible);
        int height = visible.height() > 0 ? visible.height() : getResources().getDisplayMetrics().heightPixels;
        int available = Math.max(dp(160), height - dp(64));
        shell.measure(View.MeasureSpec.makeMeasureSpec(dialogWidth(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        LinearLayout.LayoutParams listParams = (LinearLayout.LayoutParams)scroll.getLayoutParams();
        int chrome = shell.getMeasuredHeight() - listParams.height;
        listParams.height = Math.max(0, Math.min(listParams.height, available - chrome));
        scroll.setLayoutParams(listParams);
    }

    private void showAdvancedConfirmation() {
        Dialog dialog = newDialog(getString(R.string.advanced_effects),
                getString(R.string.advanced_effects_warning));
        LinearLayout shell = dialog.findViewById(R.id.settings_page_dialog_shell);
        View cancel = addDialogAction(shell, getString(android.R.string.cancel), dialog::dismiss);
        addDialogAction(shell, getString(R.string.enable), () -> { setAdvanced(true); dialog.dismiss(); }, true);
        showDialog(dialog);
        cancel.requestFocus();
    }

    private Dialog newDialog(String title, String description) {
        if (openDialog != null) openDialog.dismiss();
        Dialog dialog = new Dialog(this, R.style.settings_page_dialog) {
            @Override public boolean dispatchKeyEvent(KeyEvent event) {
                if (sounds != null) sounds.onKeyEvent(event);
                boolean handled = super.dispatchKeyEvent(event);
                return handled || LauncherSounds.handleUnhandledNavigation(getWindow().getDecorView(), event);
            }
        };
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout shell = new LinearLayout(this);
        shell.setId(R.id.settings_page_dialog_shell);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setPadding(dp(24), dp(22), dp(24), dp(20));
        shell.setBackgroundResource(R.drawable.settings_page_dialog_surface);
        TextView heading = dialogText(title, 22, R.color.settings_page_ink);
        InterTypography.apply(heading, 700);
        shell.addView(heading);
        TextView hint = dialogText(description, 12, R.color.settings_page_secondary);
        hint.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, -2);
        hp.topMargin = dp(8); shell.addView(hint, hp);
        dialog.setContentView(shell);
        dialog.setOnDismissListener(d -> { if (openDialog == dialog) openDialog = null; });
        return dialog;
    }

    private TextView dialogText(String text, float size, int color) {
        TextView view = new TextView(this);
        view.setText(text); view.setTextSize(size);
        InterTypography.apply(view, 400);
        view.setTextColor(getResources().getColorStateList(color, getTheme()));
        view.setIncludeFontPadding(false);
        view.setDuplicateParentStateEnabled(true);
        return view;
    }

    private View addDialogAction(LinearLayout shell, String label, Runnable action) {
        return addDialogAction(shell, label, action, false);
    }

    private View addDialogAction(LinearLayout shell, String label, Runnable action, boolean primary) {
        TextView button = dialogText(label, 13, R.color.settings_page_ink);
        InterTypography.apply(button, primary ? 600 : 500);
        button.setDuplicateParentStateEnabled(false);
        button.setGravity(Gravity.CENTER);
        styleControl(button);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(42));
        lp.topMargin = dp(10); shell.addView(button, lp);
        button.setOnClickListener(v -> action.run());
        return button;
    }

    private void styleControl(View control) {
        control.setBackgroundResource(R.drawable.settings_page_control);
        control.setFocusable(true); control.setClickable(true);
        control.setSoundEffectsEnabled(false);
        control.setPadding(dp(12), 0, dp(12), 0);
    }

    private void showDialog(Dialog dialog) {
        openDialog = dialog;
        dialog.show();
        LauncherSounds.muteWindowRoot(dialog.getWindow().getDecorView());
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(dialogWidth(), -2);
            window.setDimAmount(.38f);
            if (!animations || reducedMotion) window.setWindowAnimations(0);
        }
    }

    private static int indexOf(List<File> images, String name) {
        if (name == null) return -1;
        for (int i = 0; i < images.size(); i++) if (images.get(i).getName().equals(name)) return i;
        return -1;
    }

    private void setAdvanced(boolean enabled) {
        advancedEffects = enabled;
        choices.edit().putBoolean("advanced_effects", enabled).apply();
        changed();
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private void open(Intent intent) {
        try { startActivity(intent); }
        catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("settings_page", selectedPage);
        state.putBoolean("settings_page_dirty", dirty);
        super.onSaveInstanceState(state);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (sounds != null) sounds.onKeyEvent(event);
        boolean handled = super.dispatchKeyEvent(event);
        return handled || LauncherSounds.handleUnhandledNavigation(getWindow().getDecorView(), event);
    }

    @Override protected void onDestroy() {
        if (openDialog != null) openDialog.dismiss();
        thumbnails.shutdownNow();
        if(sounds!=null){sounds.close();sounds=null;}
        super.onDestroy();
    }
}
