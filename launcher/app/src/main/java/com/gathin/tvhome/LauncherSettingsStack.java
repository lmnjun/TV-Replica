package com.gathin.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/** Compact launcher choices inside the same header card; preferences match the full settings page. */
final class LauncherSettingsStack {
    private final Activity activity;
    private final HeaderStackPopup popup;
    private final SharedPreferences choices;
    private final Consumer<Intent> external;
    private final SceneSettingsPages sceneSettings;
    LauncherSettingsStack(Activity activity, HeaderStackPopup popup, Consumer<Intent> external) {
        this(activity, popup, external, null);
    }
    LauncherSettingsStack(Activity activity, HeaderStackPopup popup, Consumer<Intent> external, Runnable saturnSettings) {
        this.activity = activity; this.popup = popup; this.external = external;
        choices = activity.getSharedPreferences(Config.SETTINGS_NAME, Activity.MODE_PRIVATE);
        sceneSettings = new SceneSettingsPages(activity, popup, saturnSettings);
    }
    void show() { popup.push("Launcher settings", this::root); }
    private View root() {
        LinearLayout rows = popup.list();
        popup.row(rows, R.drawable.ic_picture, "Appearance", "Layout and wallpaper", () -> popup.push("Appearance", this::appearance));
        popup.row(rows, R.drawable.ic_scene_speed, "Motion & sound", "Transitions and quiet cues", () -> popup.push("Motion & sound", this::motion));
        popup.row(rows, R.drawable.ic_settings_outline, "System", "Home app and TV preferences", () -> popup.push("System", this::system));
        return rows;
    }
    private View appearance() {
        Config config = Config.load(activity);
        LinearLayout rows = popup.list();
        choices(rows, "Apps per row", config.columns, 4, 4, value -> choices.edit().putInt("columns", value).apply());
        choices(rows, "Rows below dock", config.rowsBelowDock, 0, 4,
                value -> choices.edit().putInt(Config.ROWS_BELOW_DOCK_KEY, value).apply());
        popup.row(rows, R.drawable.ic_picture, "Wallpaper", ScenePreferences.title(ScenePreferences.current(activity)),
                () -> popup.push("Wallpaper", this::wallpaper));
        popup.row(rows, R.drawable.ic_scene_clock, "Time format", ClockFormat.is24Hour(activity) ? "24-hour · 23:08" : "12-hour · 11:08 PM",
                () -> popup.push("Time format", this::timeFormat));
        popup.row(rows, R.drawable.ic_scene_clock, "Screen saver", ScenePreferences.title(ScenePreferences.saver(activity)),
                () -> popup.push("Screen saver", this::screensaver));
        popup.row(rows, R.drawable.ic_scene_reset, "Follow theme", "Reset layout, motion and wallpaper", () -> {
            choices.edit().remove("columns").remove(Config.ROWS_BELOW_DOCK_KEY).remove("animations")
                    .remove("advanced_effects").remove(Config.WALLPAPER_CHOICE_KEY)
                    .remove(Config.WALLPAPER_SCENE_OVERRIDE_KEY).remove(ScenePreferences.SCENE_KEY).apply();
            changed();
        });
        return rows;
    }
    private void choices(LinearLayout rows, String title, int current, int first, int count, IntConsumer save) {
        TextView label = popup.text(title, 12, 600, 0xDEFFFFFF);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(-1, -2); labelLp.bottomMargin = popup.dp(8);
        rows.addView(label, labelLp);
        LinearLayout numbers = new LinearLayout(activity);
        for (int i = 0; i < count; i++) {
            int value = first + i;
            TextView number = popup.text(Integer.toString(value), 14, current == value ? 600 : 500, 0xF2FFFFFF);
            number.setGravity(Gravity.CENTER); number.setTag(title + ":" + value); popup.control(number);
            number.setSelected(current == value);
            number.setOnClickListener(v -> { save.accept(value); changed(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, popup.dp(40), 1);
            if (i < count - 1) lp.setMarginEnd(popup.dp(6)); numbers.addView(number, lp);
        }
        LinearLayout.LayoutParams numberLp = new LinearLayout.LayoutParams(-1, -2);
        numberLp.bottomMargin = popup.dp(18); rows.addView(numbers, numberLp);
    }
    private View motion() {
        Config config = Config.load(activity);
        LinearLayout rows = popup.list();
        toggle(rows, R.drawable.ic_scene_speed, "Animations", config.animations, () -> {
            boolean enable = !Config.load(activity).animations;
            choices.edit().putBoolean("animations", enable)
                    .putBoolean("advanced_effects", enable && Config.load(activity).advancedEffects).apply();
        });
        View advanced = popup.row(rows, R.drawable.ic_scene_orbit, "Advanced effects",
                !config.animations ? "Available when animations are on" : config.advancedEffects ? "On" : "Off", () -> {
                    if (Config.load(activity).advancedEffects) {
                        choices.edit().putBoolean("advanced_effects", false).apply(); changed();
                    } else popup.push("Advanced effects", this::confirmAdvanced);
                });
        advanced.setEnabled(config.animations); advanced.setFocusable(config.animations);
        advanced.setAlpha(config.animations ? 1 : .42f);
        toggle(rows, R.drawable.ic_launcher_sound, "Selection sounds",
                choices.getBoolean(LauncherSounds.SELECTION_ENABLED_KEY, true),
                () -> choices.edit().putBoolean(LauncherSounds.SELECTION_ENABLED_KEY,
                        !choices.getBoolean(LauncherSounds.SELECTION_ENABLED_KEY, true)).apply());
        toggle(rows, R.drawable.ic_launcher_sound, "Power sounds",
                choices.getBoolean(LauncherSounds.POWER_ENABLED_KEY, true),
                () -> choices.edit().putBoolean(LauncherSounds.POWER_ENABLED_KEY,
                        !choices.getBoolean(LauncherSounds.POWER_ENABLED_KEY, true)).apply());
        return rows;
    }
    private View confirmAdvanced() {
        LinearLayout rows = popup.list();
        TextView warning = popup.text(activity.getString(R.string.advanced_effects_warning), 11, 400, 0xBFFFFFFF);
        warning.setLineSpacing(popup.dp(3), 1);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.bottomMargin = popup.dp(18);
        rows.addView(warning, lp);
        popup.row(rows, R.drawable.ic_scene_play, "Enable", null, () -> {
            choices.edit().putBoolean("advanced_effects", true).apply(); popup.markChanged(); popup.backPage();
        });
        popup.row(rows, R.drawable.ic_scene_reset, "Cancel", null, popup::backPage);
        return rows;
    }
    private void toggle(LinearLayout rows, int icon, String title, boolean enabled, Runnable save) {
        popup.row(rows, icon, title, enabled ? "On" : "Off", () -> { save.run(); changed(); });
    }
    private View system() {
        LinearLayout rows = popup.list();
        popup.row(rows, R.drawable.ic_home_settings, "Choose default home", "Select your preferred home app",
                () -> external.accept(new Intent(Settings.ACTION_HOME_SETTINGS)));
        popup.row(rows, R.drawable.ic_settings_outline, "TV system settings", "Display, sound, network and accounts",
                () -> external.accept(new Intent(Settings.ACTION_SETTINGS)));
        return rows;
    }
    private View wallpaper() {
        LinearLayout rows = popup.list();
        String scene = ScenePreferences.current(activity);
        popup.row(rows, ScenePreferences.icon(scene), "Scenes", ScenePreferences.title(scene),
                () -> popup.push("Wallpaper scenes", () -> sceneChoices(false)));
        sceneSettings.addEntry(rows, scene);
        String current = choices.getString(Config.WALLPAPER_CHOICE_KEY, null);
        popup.row(rows, R.drawable.ic_picture, "Theme default", current == null ? "Selected" : "Use the theme's wallpaper",
                () -> { choices.edit().remove(Config.WALLPAPER_CHOICE_KEY).remove(Config.WALLPAPER_SCENE_OVERRIDE_KEY)
                        .remove(ScenePreferences.SCENE_KEY).apply(); changed(); });
        File dir = Config.dir(activity);
        List<File> files = new ArrayList<>(WallpaperRotation.images(dir == null ? null : new File(dir, "wallpapers")));
        File single = dir == null ? null : new File(dir, Config.WALLPAPER_NAME);
        if (single != null && single.isFile()) files.add(0, single);
        for (File file : files) popup.row(rows, R.drawable.ic_picture, file.getName(),
                file.getName().equals(current) ? "Selected" : "Still image", () -> {
                    choices.edit().putString(Config.WALLPAPER_CHOICE_KEY, file.getName())
                            .putBoolean(Config.WALLPAPER_SCENE_OVERRIDE_KEY, true)
                            .putString(ScenePreferences.SCENE_KEY, ScenePreferences.THEME).apply(); changed();
                });
        popup.row(rows, R.drawable.ic_scene_reset, "None", "none".equals(current) ? "Selected" : "Plain background", () -> {
            choices.edit().putString(Config.WALLPAPER_CHOICE_KEY, "none")
                    .putBoolean(Config.WALLPAPER_SCENE_OVERRIDE_KEY, true)
                    .putString(ScenePreferences.SCENE_KEY, ScenePreferences.THEME).apply(); changed();
        });
        return rows;
    }
    private View timeFormat() {
        LinearLayout rows = popup.list();
        boolean h24 = ClockFormat.is24Hour(activity);
        for (String mode : new String[]{ClockFormat.H24, ClockFormat.H12}) {
            boolean is24 = ClockFormat.H24.equals(mode);
            boolean selected = h24 == is24;
            View row = popup.row(rows, R.drawable.ic_scene_clock, is24 ? "24-hour" : "12-hour AM / PM",
                    selected ? "Selected" : is24 ? "23:08" : "11:08 PM", () -> {
                        choices.edit().putString(ClockFormat.KEY, mode).apply();
                        popup.refreshPage();
                    });
            row.setSelected(selected);
        }
        return rows;
    }
    private View screensaver() {
        LinearLayout rows = popup.list();
        String scene = ScenePreferences.saver(activity);
        popup.row(rows, ScenePreferences.icon(scene), "Scene", ScenePreferences.title(scene),
                () -> popup.push("Screen saver scene", () -> sceneChoices(true)));
        sceneSettings.addEntry(rows, scene);
        popup.row(rows, R.drawable.ic_scene_play, "Preview", "Press any remote key to return",
                () -> external.accept(new Intent(activity, ClockActivity.class)));
        popup.row(rows, R.drawable.ic_settings_outline, "Screen saver settings", "Start time and system options",
                () -> {
                    Intent settings = new Intent(Settings.ACTION_DREAM_SETTINGS);
                    if (settings.resolveActivity(activity.getPackageManager()) == null) settings = new Intent(Settings.ACTION_SETTINGS);
                    external.accept(settings);
                });
        return rows;
    }
    private View sceneChoices(boolean saver) {
        LinearLayout rows = popup.list();
        String current = saver ? ScenePreferences.saver(activity) : ScenePreferences.current(activity);
        String[] ids = saver ? new String[]{ScenePreferences.FLIP, ScenePreferences.EARTH, ScenePreferences.WEATHER, ScenePreferences.CLOCK}
                : new String[]{ScenePreferences.SATURN, ScenePreferences.EARTH, ScenePreferences.WEATHER, ScenePreferences.FLIP, ScenePreferences.THEME};
        for (String id : ids) {
            View row = popup.row(rows, ScenePreferences.icon(id), ScenePreferences.title(id),
                    id.equals(current) ? "Selected" : saver ? "Screen saver" : "Wallpaper scene", () -> {
                        choices.edit().putString(saver ? ScenePreferences.SAVER_KEY : ScenePreferences.SCENE_KEY, id).apply();
                        popup.refreshPage();
                    });
            row.setSelected(id.equals(current));
            row.setTag((saver ? "saver:" : "wallpaper:") + id);
            if (id.equals(current)) sceneSettings.addEntry(rows, id);
        }
        return rows;
    }
    private void changed() { popup.markChanged(); popup.refreshPage(); }
}
