package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class MenuKeyLatchTest {
    private static final int RIGHT = 22, ENTER = 66;

    @Test public void completePressHasOnePressAndOneRelease() {
        MenuKeyLatch latch = new MenuKeyLatch();
        assertEquals(MenuKeyLatch.Result.PRESS, latch.event(RIGHT, 1, 100, true, false, 0, false));
        assertEquals(MenuKeyLatch.Result.RELEASE, latch.event(RIGHT, 1, 100, false, true, 0, false));
        assertEquals(MenuKeyLatch.Result.IGNORE, latch.event(RIGHT, 1, 100, false, true, 0, false));
    }

    @Test public void holdingRightDoesNotRearmOrGenerateRepeatedActions() {
        MenuKeyLatch latch = new MenuKeyLatch();
        latch.event(RIGHT, 1, 100, true, false, 0, false);
        assertEquals(MenuKeyLatch.Result.IGNORE, latch.event(RIGHT, 1, 100, true, false, 0, false));
        for (int repeat = 1; repeat <= 50; repeat++)
            assertEquals(MenuKeyLatch.Result.IGNORE, latch.event(RIGHT, 1, 100, true, false, repeat, false));
        assertEquals(MenuKeyLatch.Result.RELEASE, latch.event(RIGHT, 1, 100, false, true, 0, false));
    }

    @Test public void orphanReleaseCannotConfirmTheNewPage() {
        MenuKeyLatch latch = new MenuKeyLatch();
        assertEquals(MenuKeyLatch.Result.IGNORE, latch.event(ENTER, 1, 100, false, true, 0, false));
        latch.event(ENTER, 1, 200, true, false, 0, false);
        latch.clear(); // Page/focus changed or the menu closed.
        assertEquals(MenuKeyLatch.Result.IGNORE, latch.event(ENTER, 1, 200, false, true, 0, false));
    }

    @Test public void canceledReleaseAndCanceledDownNeverActivate() {
        MenuKeyLatch latch = new MenuKeyLatch();
        latch.event(RIGHT, 1, 100, true, false, 0, false);
        assertEquals(MenuKeyLatch.Result.CANCEL, latch.event(RIGHT, 1, 100, false, true, 0, true));
        assertEquals(MenuKeyLatch.Result.IGNORE, latch.event(RIGHT, 1, 100, false, true, 0, false));
        assertEquals(MenuKeyLatch.Result.CANCEL, latch.event(RIGHT, 1, 200, true, false, 0, true));
        assertEquals(MenuKeyLatch.Result.IGNORE, latch.event(RIGHT, 1, 200, false, true, 0, false));
    }

    @Test public void anotherDeviceKeyOrPressCannotReleaseThisPress() {
        MenuKeyLatch latch = new MenuKeyLatch();
        latch.event(RIGHT, 1, 100, true, false, 0, false);
        assertEquals(MenuKeyLatch.Result.IGNORE, latch.event(RIGHT, 2, 100, false, true, 0, false));
        assertEquals(MenuKeyLatch.Result.IGNORE, latch.event(ENTER, 1, 100, false, true, 0, false));
        assertEquals(MenuKeyLatch.Result.IGNORE, latch.event(RIGHT, 1, 99, false, true, 0, false));
        assertEquals(MenuKeyLatch.Result.RELEASE, latch.event(RIGHT, 1, 100, false, true, 0, false));
    }

    @Test public void freshPressRecoversFromLostUpWithoutAcceptingTheOldUp() {
        MenuKeyLatch latch = new MenuKeyLatch();
        latch.event(RIGHT, 1, 100, true, false, 0, false);
        assertEquals(MenuKeyLatch.Result.PRESS, latch.event(ENTER, 1, 200, true, false, 0, false));
        assertEquals(MenuKeyLatch.Result.IGNORE, latch.event(RIGHT, 1, 100, false, true, 0, false));
        assertEquals(MenuKeyLatch.Result.RELEASE, latch.event(ENTER, 1, 200, false, true, 0, false));
    }

    @Test public void repeatOrMultipleWithoutInitialDownNeverArms() {
        MenuKeyLatch latch = new MenuKeyLatch();
        assertEquals(MenuKeyLatch.Result.IGNORE, latch.event(RIGHT, 1, 100, true, false, 3, false));
        assertEquals(MenuKeyLatch.Result.IGNORE, latch.event(RIGHT, 1, 100, false, false, 0, false));
        assertEquals(MenuKeyLatch.Result.IGNORE, latch.event(RIGHT, 1, 100, false, true, 0, false));
    }
}
