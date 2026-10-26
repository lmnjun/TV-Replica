package com.gathin.tvhome;

import org.junit.Test;
import static org.junit.Assert.*;

public class WeatherPrecipitationTest {
    @Test public void wmoRainFamiliesIncreaseWithoutTurningDrizzleIntoDownpour() {
        increasing(51, 53, 55); increasing(61, 63, 65); increasing(80, 81, 82); increasing(95, 96, 99);
        assertTrue(WeatherPrecipitation.forCode(55).lengthDp < WeatherPrecipitation.forCode(61).lengthDp);
        assertEquals(30, WeatherPrecipitation.forCode(51).count);
        assertTrue(WeatherPrecipitation.forCode(51).nightAlpha < 30);
    }
    private void increasing(int first, int second, int third) {
        WeatherPrecipitation.Profile a = WeatherPrecipitation.forCode(first), b = WeatherPrecipitation.forCode(second), c = WeatherPrecipitation.forCode(third);
        assertTrue(a.count < b.count && b.count < c.count);
        assertTrue(a.lengthDp < b.lengthDp && b.lengthDp < c.lengthDp);
        assertTrue(a.speedDp < b.speedDp && b.speedDp < c.speedDp);
        assertTrue(a.nightAlpha < b.nightAlpha && b.nightAlpha < c.nightAlpha);
    }
    @Test public void dryFogSnowAndUnknownCodesDoNotInventRain() {
        for (int code : new int[]{-1, 0, 2, 3, 45, 48, 71, 75, 86, 100}) assertEquals(0, WeatherPrecipitation.forCode(code).count);
        assertEquals(WeatherPrecipitation.forCode(51).count, WeatherPrecipitation.forCode(56).count);
        assertEquals(WeatherPrecipitation.forCode(65).count, WeatherPrecipitation.forCode(67).count);
    }
    @Test public void seedsAndFrozenTimeProduceIdenticalRain() {
        WeatherPrecipitation first = new WeatherPrecipitation(), second = new WeatherPrecipitation();
        first.setCode(63); second.setCode(63);
        first.fill(1920, 1080, 2, 56_000.125, 1); second.fill(1920, 1080, 2, 56_000.125, 1);
        assertArrayEquals(first.counts, second.counts);
        for (int layer = 0; layer < WeatherPrecipitation.LAYERS; layer++) assertArrayEquals(first.lines[layer], second.lines[layer], 0);
    }
    @Test public void stormBuffersStayBoundedAndCalmReducesDensity() {
        WeatherPrecipitation rain = new WeatherPrecipitation(); rain.setCode(99);
        rain.fill(1920, 1080, 2, 120, 1); assertEquals(240, total(rain));
        for (int layer = 0; layer < WeatherPrecipitation.LAYERS; layer++) {
            assertTrue(rain.counts[layer] * 4 <= rain.lines[layer].length);
            for (int at = 0; at < rain.counts[layer] * 4; at++) assertTrue(Float.isFinite(rain.lines[layer][at]));
        }
        rain.fill(1920, 1080, 2, 120, .55f); assertEquals(132, total(rain));
        rain.fill(0, 1080, 2, 120, 1); assertEquals(0, total(rain));
    }
    @Test public void drizzleStreaksAreShortMostlyVerticalAndIndividuallyVaried() {
        WeatherPrecipitation rain = new WeatherPrecipitation(); rain.setCode(51); rain.fill(1920, 1080, 2, 4_200, 1);
        float shortest = Float.MAX_VALUE, longest = 0, minSlope = Float.MAX_VALUE, maxSlope = -Float.MAX_VALUE;
        for (int layer = 0; layer < WeatherPrecipitation.LAYERS; layer++) for (int i = 0; i < rain.counts[layer]; i++) {
            int at = i * 4; float dx = rain.lines[layer][at + 2] - rain.lines[layer][at], dy = rain.lines[layer][at + 3] - rain.lines[layer][at + 1];
            assertTrue(dy > 0 && dy < 7); assertTrue(Math.abs(dx / dy) < .14f);
            shortest = Math.min(shortest, dy); longest = Math.max(longest, dy);
            minSlope = Math.min(minSlope, dx / dy); maxSlope = Math.max(maxSlope, dx / dy);
        }
        assertTrue(longest > shortest * 1.5f); assertTrue(maxSlope - minSlope > .04f);
    }
    @Test public void visibilityModesKeepV7AsSubtleAndMakeNormalReadable() {
        assertEquals(1, WeatherPrecipitation.visibilityAlpha("subtle"), 0);
        assertEquals(1, WeatherPrecipitation.visibilityLength("subtle"), 0);
        assertEquals(2, WeatherPrecipitation.visibilityAlpha("normal"), 0);
        assertEquals(1.35f, WeatherPrecipitation.visibilityLength("normal"), 0);
        assertEquals(2.5f, WeatherPrecipitation.visibilityAlpha("clear"), 0);
        assertEquals(1.6f, WeatherPrecipitation.visibilityLength("clear"), 0);
        assertEquals(2, WeatherPrecipitation.visibilityAlpha(null), 0);
        assertEquals(1.35f, WeatherPrecipitation.visibilityLength("invalid"), 0);
    }
    @Test public void visibilityAlphaPreservesDepthDifferencesAndCannotBecomeSolidWhite() {
        assertEquals(16, WeatherPrecipitation.visibleAlpha(8, 2));
        assertEquals(28, WeatherPrecipitation.visibleAlpha(14, 2));
        assertEquals(44, WeatherPrecipitation.visibleAlpha(22, 2));
        assertEquals(52, WeatherPrecipitation.visibleAlpha(26, 2));
        assertEquals(160, WeatherPrecipitation.visibleAlpha(86, 2.5f));
        assertEquals(0, WeatherPrecipitation.visibleAlpha(-20, 2));
    }
    @Test public void increasedVisibilityLengthLeavesDropHeadsAndDensityUnchanged() {
        WeatherPrecipitation rain = new WeatherPrecipitation(); rain.setCode(51);
        rain.fill(1920, 1080, 2, 4200, 1, 1);
        float[][] before = new float[WeatherPrecipitation.LAYERS][];
        int[] counts = rain.counts.clone();
        for (int layer = 0; layer < before.length; layer++) before[layer] = rain.lines[layer].clone();
        rain.fill(1920, 1080, 2, 4200, 1, 1.35f);
        assertArrayEquals(counts, rain.counts);
        for (int layer = 0; layer < before.length; layer++) for (int i = 0; i < counts[layer]; i++) {
            int at = i * 4;
            assertEquals(before[layer][at], rain.lines[layer][at], 0);
            assertEquals(before[layer][at + 1], rain.lines[layer][at + 1], 0);
            float originalLength = before[layer][at + 3] - before[layer][at + 1];
            float longer = rain.lines[layer][at + 3] - rain.lines[layer][at + 1];
            assertEquals(originalLength * 1.35f, longer, .0004f);
        }
    }
    private int total(WeatherPrecipitation rain) { int count = 0; for (int value : rain.counts) count += value; return count; }
}
