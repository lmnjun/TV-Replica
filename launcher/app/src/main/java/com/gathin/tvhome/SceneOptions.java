package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.Locale;

/** Independent scene choices. Reset never touches the selected scene, clock format or Saturn state. */
final class SceneOptions {
    static final String EARTH_SHOW_LOCATION = "earth_show_location";
    static final String EARTH_DAY_NIGHT = "earth_day_night";
    static final String EARTH_ATMOSPHERE = "earth_atmosphere";
    static final String EARTH_STARS = "earth_stars";
    static final String EARTH_VIEW_ZOOM = "earth_view_zoom";
    static final String EARTH_VIEW_YAW = "earth_view_yaw";
    static final String EARTH_VIEW_PITCH = "earth_view_pitch";
    static final String WEATHER_MOTION = "weather_motion";
    static final String WEATHER_EFFECTS = "weather_effects";
    static final String WEATHER_RAIN_VISIBILITY = "weather_rain_visibility";
    static final String FLIP_SIZE = "flip_size";
    static final String FLIP_SHOW_DATE = "flip_show_date";
    static final String FLIP_ANIMATION = "flip_animation";
    static final String STILL = "still", CALM = "calm", NORMAL = "normal";
    static final String SUBTLE = "subtle", CLEAR = "clear";
    static final String SMALL = "small", STANDARD = "standard", LARGE = "large";

    private static final String[] EARTH_VIEW_KEYS = {EARTH_VIEW_ZOOM, EARTH_VIEW_YAW, EARTH_VIEW_PITCH};
    private static final String[] EARTH_FACING_KEYS = {EARTH_VIEW_YAW, EARTH_VIEW_PITCH};
    private static final String[] EARTH_KEYS = {EARTH_SHOW_LOCATION, EARTH_DAY_NIGHT, EARTH_ATMOSPHERE, EARTH_STARS,
            EARTH_VIEW_ZOOM, EARTH_VIEW_YAW, EARTH_VIEW_PITCH};
    private static final String[] WEATHER_KEYS = {WEATHER_MOTION, WEATHER_EFFECTS, WEATHER_RAIN_VISIBILITY};
    private static final String[] FLIP_KEYS = {FLIP_SIZE, FLIP_SHOW_DATE, FLIP_ANIMATION};
    private static final String[] NO_KEYS = {};

    private SceneOptions() { }

    static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
    }

    static boolean earthShowLocation(Context context) { return flag(context, EARTH_SHOW_LOCATION); }
    static boolean earthDayNight(Context context) { return flag(context, EARTH_DAY_NIGHT); }
    static boolean earthAtmosphere(Context context) { return flag(context, EARTH_ATMOSPHERE); }
    static boolean earthStars(Context context) { return flag(context, EARTH_STARS); }
    static float earthZoom(Context context) { return decimal(context, EARTH_VIEW_ZOOM, 1f); }
    static float earthYaw(Context context) { return decimal(context, EARTH_VIEW_YAW, 0f); }
    static float earthPitch(Context context) { return decimal(context, EARTH_VIEW_PITCH, 0f); }
    static boolean weatherEffects(Context context) { return flag(context, WEATHER_EFFECTS); }
    static boolean flipShowDate(Context context) { return flag(context, FLIP_SHOW_DATE); }
    static boolean flipAnimation(Context context) { return flag(context, FLIP_ANIMATION); }
    static String weatherMotion(Context context) { return string(context, WEATHER_MOTION, NORMAL); }
    static String weatherRainVisibility(Context context) { return string(context, WEATHER_RAIN_VISIBILITY, NORMAL); }
    static String flipSize(Context context) { return string(context, FLIP_SIZE, STANDARD); }

    private static boolean flag(Context context, String key) {
        Object value;
        try { value = preferences(context).getBoolean(key, true); }
        catch (ClassCastException invalid) { value = null; }
        return (Boolean)normalizeValue(key, value);
    }

    private static String string(Context context, String key, String fallback) {
        Object value;
        try { value = preferences(context).getString(key, fallback); }
        catch (ClassCastException invalid) { value = null; }
        return (String)normalizeValue(key, value);
    }

    private static float decimal(Context context, String key, float fallback) {
        Object value;
        try { value = preferences(context).getFloat(key, fallback); }
        catch (ClassCastException invalid) { value = null; }
        return (Float)normalizeValue(key, value);
    }

    static float normalizeEarthZoom(float value) {
        return Float.isNaN(value) || Float.isInfinite(value) ? 1f : Math.max(.65f, Math.min(1.6f, value));
    }
    static float normalizeEarthYaw(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) return 0f;
        float wrapped = value % 360f;
        if (wrapped < -180f) wrapped += 360f;
        else if (wrapped >= 180f) wrapped -= 360f;
        return wrapped == 0f ? 0f : wrapped;
    }
    static float normalizeEarthPitch(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) return 0f;
        float clamped = Math.max(-70f, Math.min(70f, value));
        return clamped == 0f ? 0f : clamped;
    }

    static String normalizeWeatherMotion(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return STILL.equals(normalized) || CALM.equals(normalized) ? normalized : NORMAL;
    }

    static String normalizeFlipSize(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return SMALL.equals(normalized) || LARGE.equals(normalized) ? normalized : STANDARD;
    }

    static String normalizeWeatherRainVisibility(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return SUBTLE.equals(normalized) || CLEAR.equals(normalized) ? normalized : NORMAL;
    }

    /** Android-free key/type normalization; invalid saved values always return that option's default. */
    static Object normalizeValue(String key, Object value) {
        if (isBooleanKey(key)) return value instanceof Boolean ? value : Boolean.TRUE;
        if (WEATHER_MOTION.equals(key)) return normalizeWeatherMotion(value instanceof String ? (String)value : null);
        if (WEATHER_RAIN_VISIBILITY.equals(key)) return normalizeWeatherRainVisibility(value instanceof String ? (String)value : null);
        if (FLIP_SIZE.equals(key)) return normalizeFlipSize(value instanceof String ? (String)value : null);
        if (EARTH_VIEW_ZOOM.equals(key)) return normalizeEarthZoom(value instanceof Number ? ((Number)value).floatValue() : 1f);
        if (EARTH_VIEW_YAW.equals(key)) return normalizeEarthYaw(value instanceof Number ? ((Number)value).floatValue() : 0f);
        if (EARTH_VIEW_PITCH.equals(key)) return normalizeEarthPitch(value instanceof Number ? ((Number)value).floatValue() : 0f);
        return null;
    }

    private static boolean isBooleanKey(String key) {
        return EARTH_SHOW_LOCATION.equals(key) || EARTH_DAY_NIGHT.equals(key) || EARTH_ATMOSPHERE.equals(key)
                || EARTH_STARS.equals(key) || WEATHER_EFFECTS.equals(key) || FLIP_SHOW_DATE.equals(key)
                || FLIP_ANIMATION.equals(key);
    }

    static void setBoolean(Context context, String key, boolean enabled) {
        if (!isBooleanKey(key)) throw new IllegalArgumentException("Not a scene boolean: " + key);
        preferences(context).edit().putBoolean(key, enabled).apply();
    }
    static void setWeatherMotion(Context context, String value) {
        preferences(context).edit().putString(WEATHER_MOTION, normalizeWeatherMotion(value)).apply();
    }
    static void setWeatherRainVisibility(Context context, String value) {
        preferences(context).edit().putString(WEATHER_RAIN_VISIBILITY, normalizeWeatherRainVisibility(value)).apply();
    }
    static void setFlipSize(Context context, String value) {
        preferences(context).edit().putString(FLIP_SIZE, normalizeFlipSize(value)).apply();
    }

    static void setEarthView(Context context, float zoom, float yaw, float pitch) {
        preferences(context).edit().putFloat(EARTH_VIEW_ZOOM, normalizeEarthZoom(zoom))
                .putFloat(EARTH_VIEW_YAW, normalizeEarthYaw(yaw))
                .putFloat(EARTH_VIEW_PITCH, normalizeEarthPitch(pitch)).apply();
    }
    static void setEarthZoom(Context context, float zoom) {
        preferences(context).edit().putFloat(EARTH_VIEW_ZOOM, normalizeEarthZoom(zoom)).apply();
    }
    static void setEarthYaw(Context context, float yaw) {
        preferences(context).edit().putFloat(EARTH_VIEW_YAW, normalizeEarthYaw(yaw)).apply();
    }
    static void setEarthPitch(Context context, float pitch) {
        preferences(context).edit().putFloat(EARTH_VIEW_PITCH, normalizeEarthPitch(pitch)).apply();
    }
    static void resetEarthView(Context context) { remove(context, EARTH_VIEW_KEYS); }
    static void faceEarthLocation(Context context) { remove(context, EARTH_FACING_KEYS); }
    static String[] earthViewKeys() { return EARTH_VIEW_KEYS.clone(); }
    static String[] earthFacingKeys() { return EARTH_FACING_KEYS.clone(); }

    private static String[] keys(String scene) {
        if (ScenePreferences.EARTH.equals(scene)) return EARTH_KEYS;
        if (ScenePreferences.WEATHER.equals(scene)) return WEATHER_KEYS;
        if (ScenePreferences.FLIP.equals(scene)) return FLIP_KEYS;
        return NO_KEYS;
    }

    static String[] keysForScene(String scene) { return keys(scene).clone(); }
    static boolean ownsKey(String scene, String key) {
        for (String owned : keys(scene)) if (owned.equals(key)) return true;
        return false;
    }
    static boolean hasOptions(String scene) { return keys(scene).length != 0; }

    static boolean reset(Context context, String scene) {
        String[] owned = keys(scene);
        if (owned.length == 0) return false;
        remove(context, owned);
        return true;
    }

    private static void remove(Context context, String[] keys) {
        SharedPreferences.Editor editor = preferences(context).edit();
        for (String key : keys) editor.remove(key);
        editor.apply();
    }
}
