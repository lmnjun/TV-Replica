package com.gathin.tvhome;

import java.util.Random;

/** Qualitative WMO rain profiles and a bounded, deterministic three-depth rain field. */
final class WeatherPrecipitation {
    static final int LAYERS = 3, CAPACITY = 240;
    static final class Profile {
        final int count, dayAlpha, nightAlpha;
        final float lengthDp, speedDp;
        Profile(int count, float lengthDp, float speedDp, int dayAlpha, int nightAlpha) {
            this.count = count; this.lengthDp = lengthDp; this.speedDp = speedDp;
            this.dayAlpha = dayAlpha; this.nightAlpha = nightAlpha;
        }
    }
    private static final Profile NONE = new Profile(0, 0, 0, 0, 0);
    private static final Profile DRIZZLE_LIGHT = new Profile(30, 2.2f, 85, 34, 22);
    private static final Profile DRIZZLE_MODERATE = new Profile(48, 3.1f, 105, 40, 26);
    private static final Profile DRIZZLE_DENSE = new Profile(68, 4.2f, 125, 46, 30);
    private static final Profile RAIN_LIGHT = new Profile(80, 5, 160, 52, 34);
    private static final Profile RAIN_MODERATE = new Profile(120, 7.5f, 220, 62, 42);
    private static final Profile RAIN_HEAVY = new Profile(172, 11.5f, 290, 72, 52);
    private static final Profile SHOWER_LIGHT = new Profile(90, 5.8f, 200, 54, 36);
    private static final Profile SHOWER_MODERATE = new Profile(142, 8.7f, 260, 66, 45);
    private static final Profile SHOWER_HEAVY = new Profile(205, 12, 330, 78, 56);
    private static final Profile STORM = new Profile(176, 10.5f, 300, 74, 54);
    private static final Profile STORM_HAIL = new Profile(210, 11.5f, 330, 78, 58);
    private static final Profile STORM_HEAVY = new Profile(240, 14.5f, 365, 86, 65);

    final float[][] lines = new float[LAYERS][CAPACITY * 4];
    final int[] counts = new int[LAYERS];
    private final float[] x = new float[CAPACITY], y = new float[CAPACITY];
    private final float[] distance = new float[CAPACITY], speed = new float[CAPACITY];
    private final float[] length = new float[CAPACITY], wind = new float[CAPACITY];
    private final int[] layer = new int[CAPACITY];
    private Profile profile = NONE;

    WeatherPrecipitation() {
        Random random = new Random(709_351);
        for (int i = 0; i < CAPACITY; i++) {
            x[i] = random.nextFloat(); y[i] = random.nextFloat(); distance[i] = random.nextFloat();
            speed[i] = .75f + random.nextFloat() * .5f;
            length[i] = .65f + random.nextFloat() * .7f;
            wind[i] = .02f + (random.nextFloat() - .5f) * .11f;
            layer[i] = Math.min(LAYERS - 1, (int)(distance[i] * LAYERS));
        }
    }
    static Profile forCode(int code) {
        switch (code) {
            case 51: case 56: return DRIZZLE_LIGHT;
            case 53: return DRIZZLE_MODERATE;
            case 55: case 57: return DRIZZLE_DENSE;
            case 61: case 66: return RAIN_LIGHT;
            case 63: return RAIN_MODERATE;
            case 65: case 67: return RAIN_HEAVY;
            case 80: return SHOWER_LIGHT;
            case 81: return SHOWER_MODERATE;
            case 82: return SHOWER_HEAVY;
            case 95: return STORM;
            case 96: return STORM_HAIL;
            case 99: return STORM_HEAVY;
            default: return NONE;
        }
    }
    void setCode(int code) { profile = forCode(code); }
    Profile profile() { return profile; }

    static float visibilityAlpha(String mode) {
        return "subtle".equals(mode) ? 1 : "clear".equals(mode) ? 2.5f : 2;
    }
    static float visibilityLength(String mode) {
        return "subtle".equals(mode) ? 1 : "clear".equals(mode) ? 1.6f : 1.35f;
    }
    static int visibleAlpha(int naturalAlpha, float multiplier) {
        return Math.max(0, Math.min(160, Math.round(naturalAlpha * multiplier)));
    }

    /** Reuses all buffers; wrapping happens outside the visible frame rather than at its edges. */
    void fill(float width, float height, float dp, double seconds, float intensity) {
        fill(width, height, dp, seconds, intensity, 1);
    }
    void fill(float width, float height, float dp, double seconds, float intensity, float lengthMultiplier) {
        for (int i = 0; i < LAYERS; i++) counts[i] = 0;
        if (width <= 0 || height <= 0 || dp <= 0 || !Double.isFinite(seconds)) return;
        float tailScale = Float.isFinite(lengthMultiplier) ? Math.max(1, Math.min(1.6f, lengthMultiplier)) : 1.35f;
        int visible = Math.round(profile.count * Math.max(0, Math.min(1, intensity)));
        float padding = Math.max(32 * dp, profile.lengthDp * dp * 2);
        float spanX = width + padding * 2, spanY = height + padding * 2;
        // Wind is slow and coherent, with a stable small deviation for each drop.
        double gust = Math.sin(seconds * .31) * .025 + Math.sin(seconds * .11) * .017;
        double gustTravel = -Math.cos(seconds * .31) * (.025 / .31) - Math.cos(seconds * .11) * (.017 / .11);
        for (int i = 0; i < visible; i++) {
            float z = distance[i];
            float velocity = profile.speedDp * dp * (.30f + z * .8f) * speed[i];
            // Keep the same wrapping field/heads when visibility changes; cap only long near tails.
            float tail = Math.min(padding * .9f, profile.lengthDp * dp * (.35f + z * .75f) * length[i] * tailScale);
            float px = fraction(x[i] + (seconds * velocity * wind[i] + velocity * gustTravel) / spanX) * spanX - padding;
            float py = fraction(y[i] + seconds * velocity / spanY) * spanY - padding;
            int group = layer[i], at = counts[group]++ * 4;
            float[] target = lines[group];
            target[at] = px; target[at + 1] = py;
            target[at + 2] = px + (float)(wind[i] + gust) * tail;
            target[at + 3] = py + tail;
        }
    }
    private static float fraction(double value) { return (float)(value - Math.floor(value)); }
}
