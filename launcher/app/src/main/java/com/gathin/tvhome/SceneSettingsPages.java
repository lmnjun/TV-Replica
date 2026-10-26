package com.gathin.tvhome;

import android.app.Activity;
import android.view.View;
import android.widget.LinearLayout;

/** Per-scene pages share the current header card and apply immediately through preferences. */
final class SceneSettingsPages {
    interface ExploreHost { void requestEarthExplore(); }
    private final Activity activity;
    private final HeaderStackPopup popup;
    private final Runnable saturnSettings;

    SceneSettingsPages(Activity activity, HeaderStackPopup popup, Runnable saturnSettings) {
        this.activity = activity; this.popup = popup; this.saturnSettings = saturnSettings;
    }

    boolean supports(String scene) {
        return SceneOptions.hasOptions(scene) || ScenePreferences.SATURN.equals(scene) && saturnSettings != null;
    }

    void addEntry(LinearLayout rows, String scene) {
        if (!supports(scene)) return;
        popup.row(rows, R.drawable.ic_settings_outline, ScenePreferences.title(scene) + " settings", null,
                () -> show(scene)).setTag(scene + ":settings");
    }

    void show(String scene) {
        if (ScenePreferences.SATURN.equals(scene)) {
            if (saturnSettings != null) saturnSettings.run();
        } else if (SceneOptions.hasOptions(scene)) {
            popup.push(ScenePreferences.title(scene) + " settings", () -> options(scene));
        }
    }

    private View options(String scene) {
        LinearLayout rows = popup.list();
        if (ScenePreferences.EARTH.equals(scene)) {
            if (activity instanceof ExploreHost) popup.row(rows, R.drawable.ic_scene_earth, "Explore scene",
                    "Rotate, tilt and zoom with your remote", () -> ((ExploreHost)activity).requestEarthExplore())
                    .setTag("earth:explore");
            toggle(rows, R.drawable.ic_scene_names, "Show location", SceneOptions.EARTH_SHOW_LOCATION,
                    SceneOptions.earthShowLocation(activity));
            toggle(rows, R.drawable.ic_scene_clock, "Day & night", SceneOptions.EARTH_DAY_NIGHT,
                    SceneOptions.earthDayNight(activity));
            toggle(rows, R.drawable.ic_weather_cloud, "Atmosphere", SceneOptions.EARTH_ATMOSPHERE,
                    SceneOptions.earthAtmosphere(activity));
            toggle(rows, R.drawable.ic_scene_orbit, "Stars", SceneOptions.EARTH_STARS,
                    SceneOptions.earthStars(activity));
        } else if (ScenePreferences.WEATHER.equals(scene)) {
            popup.row(rows, R.drawable.ic_scene_speed, "Motion", motionTitle(SceneOptions.weatherMotion(activity)),
                    () -> popup.push("Weather motion", this::weatherMotion)).setTag(SceneOptions.WEATHER_MOTION);
            toggle(rows, R.drawable.ic_weather_rain, "Weather effects", SceneOptions.WEATHER_EFFECTS,
                    SceneOptions.weatherEffects(activity));
            popup.row(rows, R.drawable.ic_weather_rain, "Rain visibility", visibilityTitle(SceneOptions.weatherRainVisibility(activity)),
                    () -> popup.push("Rain visibility", this::rainVisibility)).setTag(SceneOptions.WEATHER_RAIN_VISIBILITY);
        } else if (ScenePreferences.FLIP.equals(scene)) {
            popup.row(rows, R.drawable.ic_scene_clock, "Clock size", sizeTitle(SceneOptions.flipSize(activity)),
                    () -> popup.push("Clock size", this::flipSize)).setTag(SceneOptions.FLIP_SIZE);
            toggle(rows, R.drawable.ic_scene_clock, "Show date", SceneOptions.FLIP_SHOW_DATE,
                    SceneOptions.flipShowDate(activity));
            toggle(rows, R.drawable.ic_scene_play, "Flip animation", SceneOptions.FLIP_ANIMATION,
                    SceneOptions.flipAnimation(activity));
        }
        popup.row(rows, R.drawable.ic_scene_reset, "Reset this scene", "Restore this scene's defaults", () -> {
            SceneOptions.reset(activity, scene);
            popup.refreshPage();
        }).setTag(scene + ":reset");
        return rows;
    }

    private void toggle(LinearLayout rows, int icon, String label, String key, boolean enabled) {
        popup.row(rows, icon, label, enabled ? "On" : "Off", () -> {
            SceneOptions.setBoolean(activity, key, !enabled);
            popup.refreshPage();
        }).setTag(key);
    }

    private View weatherMotion() {
        LinearLayout rows = popup.list();
        String current = SceneOptions.weatherMotion(activity);
        for (String value : new String[]{SceneOptions.STILL, SceneOptions.CALM, SceneOptions.NORMAL}) {
            boolean selected = value.equals(current);
            View row = popup.row(rows, R.drawable.ic_scene_speed, motionTitle(value),
                    selected ? "Selected" : SceneOptions.STILL.equals(value) ? "Static background"
                            : SceneOptions.CALM.equals(value) ? "Gentle movement" : "Standard movement", () -> {
                        SceneOptions.setWeatherMotion(activity, value);
                        popup.refreshPage();
                    });
            row.setSelected(selected); row.setTag(SceneOptions.WEATHER_MOTION + ":" + value);
        }
        return rows;
    }

    private View flipSize() {
        LinearLayout rows = popup.list();
        String current = SceneOptions.flipSize(activity);
        for (String value : new String[]{SceneOptions.SMALL, SceneOptions.STANDARD, SceneOptions.LARGE}) {
            boolean selected = value.equals(current);
            View row = popup.row(rows, R.drawable.ic_scene_clock, sizeTitle(value), selected ? "Selected" : null, () -> {
                SceneOptions.setFlipSize(activity, value);
                popup.refreshPage();
            });
            row.setSelected(selected); row.setTag(SceneOptions.FLIP_SIZE + ":" + value);
        }
        return rows;
    }

    private View rainVisibility() {
        LinearLayout rows = popup.list();
        String current = SceneOptions.weatherRainVisibility(activity);
        for (String value : new String[]{SceneOptions.SUBTLE, SceneOptions.NORMAL, SceneOptions.CLEAR}) {
            boolean selected = value.equals(current);
            View row = popup.row(rows, R.drawable.ic_weather_rain, visibilityTitle(value),
                    selected ? "Selected" : SceneOptions.SUBTLE.equals(value) ? "Lighter rain"
                            : SceneOptions.CLEAR.equals(value) ? "More visible rain" : "Standard visibility", () -> {
                        SceneOptions.setWeatherRainVisibility(activity, value); popup.refreshPage();
                    });
            row.setSelected(selected); row.setTag(SceneOptions.WEATHER_RAIN_VISIBILITY + ":" + value);
        }
        return rows;
    }

    private static String visibilityTitle(String value) {
        return SceneOptions.SUBTLE.equals(value) ? "Subtle" : SceneOptions.CLEAR.equals(value) ? "Clear" : "Normal";
    }

    private static String motionTitle(String value) {
        return SceneOptions.STILL.equals(value) ? "Still" : SceneOptions.CALM.equals(value) ? "Calm" : "Normal";
    }
    private static String sizeTitle(String value) {
        return SceneOptions.SMALL.equals(value) ? "Small" : SceneOptions.LARGE.equals(value) ? "Large" : "Standard";
    }
}
