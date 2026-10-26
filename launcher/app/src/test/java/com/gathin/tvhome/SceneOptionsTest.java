package com.gathin.tvhome;

import org.junit.Test;
import java.util.HashSet;
import java.util.Set;
import java.util.Arrays;
import static org.junit.Assert.*;

public class SceneOptionsTest {
    @Test public void absentOptionsKeepThePreviouslyVisibleSceneDefaults() {
        for (String scene : new String[]{"earth", "weather", "flip"}) {
            for (String key : SceneOptions.keysForScene(scene)) {
                Object value = SceneOptions.normalizeValue(key, null);
                if (key.equals(SceneOptions.WEATHER_MOTION) || key.equals(SceneOptions.WEATHER_RAIN_VISIBILITY)) assertEquals("normal", value);
                else if (key.equals(SceneOptions.FLIP_SIZE)) assertEquals("standard", value);
                else if (key.equals(SceneOptions.EARTH_VIEW_ZOOM)) assertEquals(1f, value);
                else if (key.equals(SceneOptions.EARTH_VIEW_YAW) || key.equals(SceneOptions.EARTH_VIEW_PITCH)) assertEquals(0f, value);
                else assertEquals(Boolean.TRUE, value);
            }
        }
    }

    @Test public void weatherMotionAcceptsOnlyTheThreeSupportedModes() {
        assertEquals("still", SceneOptions.normalizeWeatherMotion(" STILL "));
        assertEquals("calm", SceneOptions.normalizeWeatherMotion("Calm"));
        assertEquals("normal", SceneOptions.normalizeWeatherMotion("normal"));
        assertEquals("normal", SceneOptions.normalizeWeatherMotion("fast"));
        assertEquals("normal", SceneOptions.normalizeWeatherMotion(null));
    }

    @Test public void flipSizeAcceptsOnlyTheThreeSupportedSizes() {
        assertEquals("small", SceneOptions.normalizeFlipSize("SMALL"));
        assertEquals("large", SceneOptions.normalizeFlipSize(" large "));
        assertEquals("standard", SceneOptions.normalizeFlipSize("standard"));
        assertEquals("standard", SceneOptions.normalizeFlipSize("huge"));
        assertEquals("standard", SceneOptions.normalizeFlipSize(null));
    }

    @Test public void wrongStoredTypesFallBackWithoutInterpretingStringsAsSwitches() {
        assertEquals(Boolean.FALSE, SceneOptions.normalizeValue(SceneOptions.EARTH_STARS, false));
        assertEquals(Boolean.TRUE, SceneOptions.normalizeValue(SceneOptions.EARTH_STARS, "false"));
        assertEquals(Boolean.TRUE, SceneOptions.normalizeValue(SceneOptions.FLIP_ANIMATION, 0));
        assertEquals("normal", SceneOptions.normalizeValue(SceneOptions.WEATHER_MOTION, false));
        assertEquals("standard", SceneOptions.normalizeValue(SceneOptions.FLIP_SIZE, 2));
    }

    @Test public void resetPlansAreDisjointAndExcludeGlobalOrSaturnState() {
        Set<String> all = new HashSet<>();
        String[] protectedKeys = {"clock_format", "ambient_scene", "screensaver_scene", "yaw", "pitch", "distance",
                "speed", "simulation_time", "latitude", "longitude", "json", "fetched"};
        for (String scene : new String[]{"earth", "weather", "flip"}) {
            for (String key : SceneOptions.keysForScene(scene)) {
                assertTrue(all.add(key));
                assertTrue(SceneOptions.ownsKey(scene, key));
            }
            for (String key : protectedKeys) assertFalse(SceneOptions.ownsKey(scene, key));
        }
        assertEquals(13, all.size());
        assertEquals(7, SceneOptions.keysForScene("earth").length);
        assertEquals(3, SceneOptions.keysForScene("weather").length);
        assertEquals(3, SceneOptions.keysForScene("flip").length);
        assertFalse(SceneOptions.ownsKey("earth", SceneOptions.WEATHER_EFFECTS));
        assertFalse(SceneOptions.ownsKey("weather", SceneOptions.FLIP_SIZE));
        assertTrue(SceneOptions.ownsKey("weather", SceneOptions.WEATHER_RAIN_VISIBILITY));
        assertFalse(SceneOptions.ownsKey("earth", SceneOptions.WEATHER_RAIN_VISIBILITY));
        assertFalse(SceneOptions.ownsKey("flip", SceneOptions.WEATHER_RAIN_VISIBILITY));
    }

    @Test public void callersCannotMutateTheOwnedResetPlan() {
        String[] copy = SceneOptions.keysForScene("earth");
        copy[0] = "clock_format";
        assertFalse(SceneOptions.ownsKey("earth", "clock_format"));
        assertEquals(SceneOptions.EARTH_SHOW_LOCATION, SceneOptions.keysForScene("earth")[0]);
    }

    @Test public void unsupportedScenesNeverBorrowAnotherScenesSettings() {
        for (String scene : new String[]{"saturn", "clock", "theme", "unknown"}) {
            assertFalse(SceneOptions.hasOptions(scene));
            assertEquals(0, SceneOptions.keysForScene(scene).length);
        }
        assertNull(SceneOptions.normalizeValue("clock_format", "12h"));
        assertFalse(SceneOptions.ownsKey("earth", null));
        assertFalse(SceneOptions.hasOptions(null));
    }

    @Test public void earthZoomAndTiltClampBeforeTheRendererReceivesThem() {
        assertEquals(.65f, SceneOptions.normalizeEarthZoom(-10), 0f);
        assertEquals(1.6f, SceneOptions.normalizeEarthZoom(5), 0f);
        assertEquals(1.25f, SceneOptions.normalizeEarthZoom(1.25f), 0f);
        assertEquals(-70f, SceneOptions.normalizeEarthPitch(-100), 0f);
        assertEquals(70f, SceneOptions.normalizeEarthPitch(90), 0f);
        assertEquals(20f, SceneOptions.normalizeEarthPitch(20), 0f);
    }

    @Test public void earthYawWrapsAtTheDateLineWithoutAJumpToAnInvalidAngle() {
        assertEquals(-180f, SceneOptions.normalizeEarthYaw(180), 0f);
        assertEquals(-180f, SceneOptions.normalizeEarthYaw(-180), 0f);
        assertEquals(-180f, SceneOptions.normalizeEarthYaw(540), 0f);
        assertEquals(179f, SceneOptions.normalizeEarthYaw(-181), 0f);
        assertEquals(-135f, SceneOptions.normalizeEarthYaw(225), 0f);
        for (float angle = -1440; angle <= 1440; angle += 37.5f) {
            float normalized = SceneOptions.normalizeEarthYaw(angle);
            assertTrue(normalized >= -180 && normalized < 180);
            assertEquals(normalized, SceneOptions.normalizeEarthYaw(angle + 360), 0f);
        }
        float extreme = SceneOptions.normalizeEarthYaw(Float.MAX_VALUE);
        assertTrue(extreme >= -180 && extreme < 180);
    }

    @Test public void nonfiniteAndWrongTypeCameraValuesUseSafeDefaults() {
        for (float value : new float[]{Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            assertEquals(1f, SceneOptions.normalizeEarthZoom(value), 0f);
            assertEquals(0f, SceneOptions.normalizeEarthYaw(value), 0f);
            assertEquals(0f, SceneOptions.normalizeEarthPitch(value), 0f);
        }
        assertEquals(1f, SceneOptions.normalizeValue(SceneOptions.EARTH_VIEW_ZOOM, "1.5"));
        assertEquals(0f, SceneOptions.normalizeValue(SceneOptions.EARTH_VIEW_YAW, true));
        assertEquals(0f, SceneOptions.normalizeValue(SceneOptions.EARTH_VIEW_PITCH, null));
        assertEquals(90f, SceneOptions.normalizeValue(SceneOptions.EARTH_VIEW_YAW, 450));
    }

    @Test public void facingLocationPreservesZoomAndViewResetPreservesDisplayToggles() {
        Set<String> view = new HashSet<>(Arrays.asList(SceneOptions.earthViewKeys()));
        Set<String> facing = new HashSet<>(Arrays.asList(SceneOptions.earthFacingKeys()));
        assertEquals(new HashSet<>(Arrays.asList(SceneOptions.EARTH_VIEW_ZOOM, SceneOptions.EARTH_VIEW_YAW,
                SceneOptions.EARTH_VIEW_PITCH)), view);
        assertEquals(new HashSet<>(Arrays.asList(SceneOptions.EARTH_VIEW_YAW, SceneOptions.EARTH_VIEW_PITCH)), facing);
        assertFalse(facing.contains(SceneOptions.EARTH_VIEW_ZOOM));
        for (String key : new String[]{SceneOptions.EARTH_SHOW_LOCATION, SceneOptions.EARTH_DAY_NIGHT,
                SceneOptions.EARTH_ATMOSPHERE, SceneOptions.EARTH_STARS, "clock_format", "speed", "yaw"}) {
            assertFalse(view.contains(key)); assertFalse(facing.contains(key));
        }
        assertTrue(new HashSet<>(Arrays.asList(SceneOptions.keysForScene("earth"))).containsAll(view));
    }

    @Test public void cameraResetPlansCannotBeExpandedByTheirCallers() {
        String[] view = SceneOptions.earthViewKeys(), facing = SceneOptions.earthFacingKeys();
        view[0] = "clock_format"; facing[0] = SceneOptions.EARTH_VIEW_ZOOM;
        assertEquals(SceneOptions.EARTH_VIEW_ZOOM, SceneOptions.earthViewKeys()[0]);
        assertEquals(SceneOptions.EARTH_VIEW_YAW, SceneOptions.earthFacingKeys()[0]);
    }

    @Test public void negativeZeroDoesNotCreateADifferentCameraPresetIdentity() {
        assertEquals(Float.floatToIntBits(0f), Float.floatToIntBits(SceneOptions.normalizeEarthYaw(-0f)));
        assertEquals(Float.floatToIntBits(0f), Float.floatToIntBits(SceneOptions.normalizeEarthPitch(-0f)));
    }

    @Test public void rainVisibilityAcceptsOnlyItsOwnModesAndDefaultsInvalidValues() {
        assertEquals("subtle", SceneOptions.normalizeWeatherRainVisibility(" SUBTLE "));
        assertEquals("clear", SceneOptions.normalizeWeatherRainVisibility("Clear"));
        assertEquals("normal", SceneOptions.normalizeWeatherRainVisibility("normal"));
        assertEquals("normal", SceneOptions.normalizeWeatherRainVisibility("heavy"));
        assertEquals("normal", SceneOptions.normalizeWeatherRainVisibility(null));
        assertEquals("normal", SceneOptions.normalizeValue(SceneOptions.WEATHER_RAIN_VISIBILITY, false));
        assertEquals("normal", SceneOptions.normalizeValue(SceneOptions.WEATHER_RAIN_VISIBILITY, 2));
    }
}
