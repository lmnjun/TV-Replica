package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** AppAdapter.tileWidthPx with rows_below_dock: the dock, its title and the rows must fit the grid's height. */
public class TileFitTest {

    private static final float D = 2f;              // 1080p TV: 2 px per dp
    private static final int INNER = 1696;          // grid width minus padding at 1920 px
    private static final int GRID_H = 846;          // grid height under the top bar
    private static final int CONSTRAINED_GRID_H = 650; // forces height fitting without hitting the minimum floor

    private static int height(int tileW, int rows, boolean title) {
        int itemH = AppAdapter.itemHeightPx(AppAdapter.tileHeightPx(tileW), D);
        return itemH * (rows + 1) + Math.round((AppAdapter.DOCK_EXTRA_DP + AppAdapter.BOTTOM_ROOM_DP
                + (title ? AppAdapter.TITLE_ROOM_DP : 0)) * D);
    }

    @Test
    public void noRowsBelowDockKeepsTheWidthOnlySize() {
        int widthOnly = AppAdapter.tileWidthPx(INNER, 4, D);
        assertEquals(widthOnly, AppAdapter.tileWidthPx(INNER, GRID_H, 4, 0, true, true, D));
        assertEquals(widthOnly, AppAdapter.tileWidthPx(INNER, 0, 4, 3, true, true, D));   // not laid out yet
    }

    @Test
    public void dockTitleAndRowsFitTheHeightUnlessThatMeansTinyTiles() {
        int floor = Math.round(AppAdapter.tileWidthPx(INNER, 4, D) * AppAdapter.MIN_HEIGHT_FIT);
        for (int rows = 1; rows <= 3; rows++) {
            for (boolean title : new boolean[]{true, false}) {
                int w = AppAdapter.tileWidthPx(INNER, GRID_H, 4, rows, title, true, D);
                assertTrue("rows " + rows + " title " + title + " below the floor", w >= floor);
                if (w > floor) assertTrue("rows " + rows + " title " + title, height(w, rows, title) <= GRID_H + 2);
            }
        }
    }

    @Test
    public void oneRowUnderATitledDockFits() {
        // At 846px the current spacing fits width-capped tiles; use a genuinely constrained viewport.
        int w = AppAdapter.tileWidthPx(INNER, CONSTRAINED_GRID_H, 4, 1, true, true, D);
        assertTrue(w < AppAdapter.tileWidthPx(INNER, 4, D));
        assertTrue(w > Math.round(AppAdapter.tileWidthPx(INNER, 4, D) * AppAdapter.MIN_HEIGHT_FIT));
        assertTrue(height(w, 1, true) <= CONSTRAINED_GRID_H + 2);
    }

    @Test
    public void moreRowsStopAtTheFloorInsteadOfShrinkingTheTiles() {
        // Two or three rows under a titled dock would need tiles under half their size: they stop at 85% and
        // the extra rows run off the bottom instead.
        int floor = Math.round(AppAdapter.tileWidthPx(INNER, 4, D) * AppAdapter.MIN_HEIGHT_FIT);
        assertEquals(floor, AppAdapter.tileWidthPx(INNER, GRID_H, 4, 2, true, true, D));
        assertEquals(floor, AppAdapter.tileWidthPx(INNER, GRID_H, 4, 3, true, true, D));
    }

    @Test
    public void plainGridFitsFourRowsAtSixColumns() {
        // dock_row false, rows_below_dock 3: the first row and 3 more under a 12dp top margin.
        int w = AppAdapter.tileWidthPx(INNER, GRID_H, 6, 3, true, false, D);
        int itemH = AppAdapter.itemHeightPx(AppAdapter.tileHeightPx(w), false, D);
        int needed = itemH * 4 + Math.round((AppAdapter.TOP_ROOM_DP + AppAdapter.COMPACT_BOTTOM_ROOM_DP) * D);
        assertTrue(needed + " > " + GRID_H, needed <= GRID_H + 2);
        // Height can trim a few pixels from the width cap. Allow rounding, but not undersized tiles:
        // adding two pixels of width already exceeds the same height budget and rounding allowance.
        assertTrue(w <= AppAdapter.tileWidthPx(INNER, 6, D));
        assertTrue(w >= Math.round(AppAdapter.tileWidthPx(INNER, 6, D) * AppAdapter.MIN_HEIGHT_FIT));
        int largerItemH = AppAdapter.itemHeightPx(AppAdapter.tileHeightPx(w + 2), false, D);
        int largerNeeded = largerItemH * 4 + Math.round((AppAdapter.TOP_ROOM_DP + AppAdapter.COMPACT_BOTTOM_ROOM_DP) * D);
        assertTrue("larger tiles should exceed the available height", largerNeeded > GRID_H + 2);
    }

    @Test
    public void droppingTheTitleGivesBiggerTiles() {
        // Both variants hit the width cap at 846px, so that old fixture cannot exercise title budgeting.
        assertTrue(AppAdapter.tileWidthPx(INNER, CONSTRAINED_GRID_H, 4, 1, false, true, D)
                > AppAdapter.tileWidthPx(INNER, CONSTRAINED_GRID_H, 4, 1, true, true, D));
    }
}
