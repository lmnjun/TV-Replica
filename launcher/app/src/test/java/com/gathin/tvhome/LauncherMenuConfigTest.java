package com.gathin.tvhome;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class LauncherMenuConfigTest {
    private static LauncherMenuConfig theme() {
        return new LauncherMenuConfig(8, 1, false, true, true, Arrays.asList("wallpaper.jpg", "dusk.png"));
    }

    @Test public void absenceUsesLoadedThemeRatherThanTvPickerDefaults() {
        LauncherMenuConfig.Values value = theme().resolve(new HashMap<>());
        assertEquals(8, value.columns); assertEquals(1, value.rowsBelowDock);
        assertFalse(value.animations); assertTrue(value.advancedEffects);
    }

    @Test public void eachResolveReadsCurrentOverridesWithoutMutatingThem() {
        Map<String, Object> prefs = new HashMap<>();
        prefs.put("columns", 4); prefs.put("rows_below_dock", 0);
        prefs.put("animations", true); prefs.put("advanced_effects", false);
        LauncherMenuConfig defaults = theme();
        LauncherMenuConfig.Values first = defaults.resolve(prefs);
        assertEquals(4, first.columns); assertEquals(0, first.rowsBelowDock);
        assertTrue(first.animations); assertFalse(first.advancedEffects);
        prefs.put("columns", 7); prefs.put("rows_below_dock", 3);
        LauncherMenuConfig.Values next = defaults.resolve(prefs);
        assertEquals(7, next.columns); assertEquals(3, next.rowsBelowDock);
        assertEquals(4, first.columns); assertEquals(4, prefs.size());
    }

    @Test public void malformedAndOutOfRangeOverridesFallBackToTheTheme() {
        for (Object invalid : new Object[]{3, 8, "6", 6L, 6.0f}) {
            Map<String, Object> prefs = new HashMap<>(); prefs.put("columns", invalid);
            assertEquals(8, theme().resolve(prefs).columns);
        }
        for (Object invalid : new Object[]{-1, 4, "2", 2L}) {
            Map<String, Object> prefs = new HashMap<>(); prefs.put("rows_below_dock", invalid);
            assertEquals(1, theme().resolve(prefs).rowsBelowDock);
        }
        Map<String, Object> prefs = new HashMap<>();
        prefs.put("animations", "true"); prefs.put("advanced_effects", 0);
        assertFalse(theme().resolve(prefs).animations); assertTrue(theme().resolve(prefs).advancedEffects);
    }

    @Test public void followThemeRestoresRawDefaultsAndPreservesIndependentSettings() {
        Map<String, Object> prefs = new HashMap<>();
        prefs.put("columns", 6); prefs.put("animations", true);
        prefs.put("wallpaper_choice", "none"); prefs.put("wallpaper_scene_override", true);
        prefs.put("clock_format", "12"); prefs.put("screensaver_scene", "flip");
        LauncherMenuConfig defaults = theme(); assertFalse(defaults.saturnScene(prefs));
        for (String key : new String[]{"columns", "animations", "wallpaper_choice", "wallpaper_scene_override"}) prefs.remove(key);
        assertEquals(8, defaults.resolve(prefs).columns); assertFalse(defaults.resolve(prefs).animations);
        assertTrue(defaults.saturnScene(prefs));
        assertEquals("12", prefs.get("clock_format")); assertEquals("flip", prefs.get("screensaver_scene"));
    }

    @Test public void onlyExplicitKnownWallpaperCanSuppressSaturnFallback() {
        Map<String, Object> prefs = new HashMap<>(); prefs.put("wallpaper_scene_override", true);
        LauncherMenuConfig defaults = theme();
        for (String name : new String[]{"none", "wallpaper.jpg", "dusk.png"}) {
            prefs.put("wallpaper_choice", name); assertFalse(defaults.saturnScene(prefs));
        }
        prefs.put("wallpaper_choice", "missing.jpg"); assertTrue(defaults.saturnScene(prefs));
        prefs.put("wallpaper_choice", "dusk.png"); prefs.put("wallpaper_scene_override", false);
        assertTrue(defaults.saturnScene(prefs));
        assertFalse(new LauncherMenuConfig(6, 2, true, false, false, Arrays.asList("dusk.png")).saturnScene(prefs));
    }

    @Test public void loadedWallpaperInventoryCannotBeChangedByItsCaller() {
        List<String> names = new ArrayList<>(); names.add("dusk.png");
        LauncherMenuConfig defaults = new LauncherMenuConfig(6, 2, true, false, true, names);
        names.clear(); names.add("other.jpg");
        Map<String, Object> prefs = new HashMap<>(); prefs.put("wallpaper_scene_override", true);
        prefs.put("wallpaper_choice", "dusk.png"); assertFalse(defaults.saturnScene(prefs));
        prefs.put("wallpaper_choice", "other.jpg"); assertTrue(defaults.saturnScene(prefs));
    }
}
