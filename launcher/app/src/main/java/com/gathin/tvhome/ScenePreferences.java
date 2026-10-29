package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

/** Independent wallpaper and saver choices; selecting a scene never resets its camera or layout. */
final class ScenePreferences {
    static final String SCENE_KEY = "ambient_scene";
    static final String SAVER_KEY = "screensaver_scene";
    static final String SATURN = "saturn", EARTH = "earth", WEATHER = "weather", FLIP = "flip";
    static final String THEME = "theme", CLOCK = "clock";

    private ScenePreferences() { }
    static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
    }
    static String current(Context context) {
        return current(context, null);
    }
    /** Main/menu callers reuse the defaults of the theme which this Activity actually loaded. */
    static String current(Context context, LauncherMenuConfig defaults) {
        String saved = preferences(context).getString(SCENE_KEY, "");
        if (SATURN.equals(saved) || EARTH.equals(saved) || WEATHER.equals(saved)
                || FLIP.equals(saved) || THEME.equals(saved)) return saved;
        boolean saturn = defaults == null ? Config.load(context).saturnScene
                : defaults.saturnScene(preferences(context).getAll());
        return saturn ? SATURN : THEME;
    }
    static String saver(Context context) {
        String saved = preferences(context).getString(SAVER_KEY, FLIP);
        return EARTH.equals(saved) || WEATHER.equals(saved) || FLIP.equals(saved) || CLOCK.equals(saved)
                ? saved : FLIP;
    }
    static String title(String scene) {
        if (SATURN.equals(scene)) return "Saturn";
        if (EARTH.equals(scene)) return "Earth";
        if (WEATHER.equals(scene)) return "Weather";
        if (FLIP.equals(scene)) return "Flip Clock";
        if (CLOCK.equals(scene)) return "Ambient clock";
        return "Theme wallpaper";
    }
    static int icon(String scene) {
        if (SATURN.equals(scene)) return R.drawable.ic_saturn;
        if (EARTH.equals(scene)) return R.drawable.ic_scene_earth;
        if (WEATHER.equals(scene)) return R.drawable.ic_scene_weather;
        if (FLIP.equals(scene)) return R.drawable.ic_scene_flip;
        if (CLOCK.equals(scene)) return R.drawable.ic_scene_clock;
        return R.drawable.ic_picture;
    }
}
