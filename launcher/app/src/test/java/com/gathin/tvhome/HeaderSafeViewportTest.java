package com.gathin.tvhome;

import org.junit.Test;
import static org.junit.Assert.*;

public class HeaderSafeViewportTest {
    @Test public void returningToAnAppAboveTheWeatherMovesItBelowTheHeader() {
        assertEquals(-70, HeaderSafeViewport.scrollDelta(30, 150, 100, 500, 24));
    }

    @Test public void anAlreadyVisibleRowDoesNotJumpOutOfBottomPadding() {
        assertEquals(0, HeaderSafeViewport.scrollDelta(360, 490, 100, 500, 24));
    }

    @Test public void aNewLowerRowStopsAtTheExistingBottomClearance() {
        assertEquals(124, HeaderSafeViewport.scrollDelta(470, 600, 100, 500, 24));
    }

    @Test public void aTallRowCannotBeMovedBehindTheHeaderToExposeItsBottom() {
        assertEquals(10, HeaderSafeViewport.scrollDelta(110, 650, 100, 500, 24));
    }

    @Test public void fullyHiddenAndPartiallyRevealedCardsHaveDifferentFocusEligibility() {
        assertEquals(0, HeaderSafeViewport.visibleHeight(10, 90, 100, 500));
        assertEquals(40, HeaderSafeViewport.visibleHeight(60, 140, 100, 500));
        assertEquals(80, HeaderSafeViewport.visibleHeight(100, 180, 100, 500));
    }
}
