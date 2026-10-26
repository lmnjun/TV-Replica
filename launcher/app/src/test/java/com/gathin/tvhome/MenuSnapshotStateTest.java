package com.gathin.tvhome;

import org.junit.Test;
import static org.junit.Assert.*;

public class MenuSnapshotStateTest {
    @Test public void stableContentCanReuseTheSamePixelDimensions() {
        MenuSnapshotState state = new MenuSnapshotState();
        assertFalse(state.reusable(320, 68));
        assertTrue(state.publish(state.revision(), 320, 68));
        assertTrue(state.reusable(320, 68));
        assertFalse(state.reusable(321, 68));
        assertFalse(state.reusable(320, 70));
    }

    @Test public void NamePhotoOrDrawingStateChangeRequiresFreshPixels() {
        MenuSnapshotState state = new MenuSnapshotState();
        state.publish(state.revision(), 320, 68);
        state.invalidate();
        assertFalse(state.reusable(320, 68));
        assertTrue(state.publish(state.revision(), 320, 68));
        assertTrue(state.reusable(320, 68));
    }

    @Test public void ContentInvalidatedDuringCaptureCannotBecomeTheCurrentCache() {
        MenuSnapshotState state = new MenuSnapshotState();
        long old = state.revision();
        state.invalidate();
        assertFalse(state.publish(old, 320, 68));
        assertFalse(state.reusable(320, 68));
        assertTrue(state.publish(state.revision(), 320, 68));
    }

    @Test public void DetachAndEmptyDimensionsCannotReuseOldPixels() {
        MenuSnapshotState state = new MenuSnapshotState();
        state.publish(state.revision(), 320, 68);
        state.clear();
        assertFalse(state.reusable(320, 68));
        assertFalse(state.publish(state.revision(), 0, 68));
        assertFalse(state.publish(state.revision(), 320, 0));
        assertFalse(state.reusable(0, 0));
    }
}
