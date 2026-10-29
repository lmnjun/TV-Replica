package com.gathin.tvhome;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Immutable loaded-theme defaults; menu choices are resolved from current in-memory preferences. */
final class LauncherMenuConfig {
    final int columns, rowsBelowDock;
    final boolean animations, advancedEffects, themeSaturn;
    private final Set<String> wallpapers;

    LauncherMenuConfig(int columns, int rows, boolean animations, boolean advancedEffects,
                       boolean themeSaturn, Collection<String> wallpapers) {
        this.columns = Math.max(1, columns); rowsBelowDock = Math.max(0, rows);
        this.animations = animations; this.advancedEffects = advancedEffects; this.themeSaturn = themeSaturn;
        this.wallpapers = Collections.unmodifiableSet(new HashSet<>(wallpapers));
    }

    Values resolve(Map<String, ?> choices) {
        return new Values(integer(choices.get("columns"), columns, 4, 7),
                integer(choices.get("rows_below_dock"), rowsBelowDock, 0, 3),
                bool(choices.get("animations"), animations), bool(choices.get("advanced_effects"), advancedEffects));
    }

    boolean saturnScene(Map<String, ?> choices) {
        Object choice = choices.get("wallpaper_choice");
        boolean explicit = "none".equals(choice) || wallpapers.contains(choice);
        return themeSaturn && !(explicit && Boolean.TRUE.equals(choices.get("wallpaper_scene_override")));
    }

    private static int integer(Object value, int fallback, int min, int max) {
        if (!(value instanceof Integer)) return fallback;
        int number = (Integer)value;
        return number >= min && number <= max ? number : fallback;
    }
    private static boolean bool(Object value, boolean fallback) { return value instanceof Boolean ? (Boolean)value : fallback; }

    static final class Values {
        final int columns, rowsBelowDock;
        final boolean animations, advancedEffects;
        Values(int columns, int rows, boolean animations, boolean advancedEffects) {
            this.columns = columns; rowsBelowDock = rows; this.animations = animations; this.advancedEffects = advancedEffects;
        }
    }
}
