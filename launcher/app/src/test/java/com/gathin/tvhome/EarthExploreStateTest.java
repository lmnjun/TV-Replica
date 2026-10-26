package com.gathin.tvhome;

import org.junit.Test;
import static org.junit.Assert.*;

public class EarthExploreStateTest {
    @Test public void openingNormalizesSavedValuesWithoutChangingOtherState() {
        EarthExploreState view = new EarthExploreState(3, 540, -100);
        assertEquals(1.6f, view.zoom(), 0);
        assertEquals(-180, view.yaw(), 0);
        assertEquals(-70, view.pitch(), 0);
        view = new EarthExploreState(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY);
        assertEquals(1, view.zoom(), 0);
        assertEquals(0, view.yaw(), 0);
        assertEquals(0, view.pitch(), 0);
    }

    @Test public void firstAndHeldArrowsHavePredictableFineSteps() {
        EarthExploreState view = new EarthExploreState(1, 0, 0);
        assertTrue(view.handle(EarthExploreState.DPAD_RIGHT, 0));
        assertEquals(8, view.yaw(), 0);
        view.handle(EarthExploreState.DPAD_RIGHT, 1);
        assertEquals(12, view.yaw(), 0);
        view.handle(EarthExploreState.DPAD_LEFT, 0);
        assertEquals(4, view.yaw(), 0);
        view.handle(EarthExploreState.DPAD_UP, 0);
        view.handle(EarthExploreState.DPAD_UP, 1);
        assertEquals(8, view.pitch(), 0);
        view.handle(EarthExploreState.DPAD_DOWN, 0);
        assertEquals(3, view.pitch(), 0);
        assertEquals(1, view.zoom(), 0);
    }

    @Test public void longArrowHoldsWrapYawAndStopAtThePoleLimits() {
        EarthExploreState view = new EarthExploreState(1, 176, 69);
        assertTrue(view.handle(EarthExploreState.DPAD_RIGHT, 0));
        assertEquals(-176, view.yaw(), 0);
        for (int i = 1; i <= 1000; i++) {
            assertTrue(view.handle(EarthExploreState.DPAD_RIGHT, i));
            assertTrue(view.yaw() >= -180 && view.yaw() < 180);
            assertTrue(view.handle(EarthExploreState.DPAD_UP, i));
        }
        assertEquals(70, view.pitch(), 0);
        for (int i = 0; i < 1000; i++) view.handle(EarthExploreState.DPAD_DOWN, i);
        assertEquals(-70, view.pitch(), 0);
    }

    @Test public void channelAndTransportZoomAreEquivalentAndReversibleAwayFromLimits() {
        EarthExploreState channel = new EarthExploreState(1, 30, -15);
        EarthExploreState transport = new EarthExploreState(1, 30, -15);
        channel.handle(EarthExploreState.CHANNEL_UP, 0);
        transport.handle(EarthExploreState.MEDIA_FAST_FORWARD, 0);
        assertEquals(1.08f, channel.zoom(), .000001);
        assertEquals(channel.zoom(), transport.zoom(), 0);
        channel.handle(EarthExploreState.CHANNEL_UP, 1);
        transport.handle(EarthExploreState.MEDIA_FAST_FORWARD, 1);
        assertEquals(1.08f * 1.04f, channel.zoom(), .000001);
        assertEquals(channel.zoom(), transport.zoom(), 0);
        channel.handle(EarthExploreState.CHANNEL_DOWN, 1);
        channel.handle(EarthExploreState.CHANNEL_DOWN, 0);
        assertEquals(1, channel.zoom(), .000001);
        assertEquals(30, channel.yaw(), 0);
        assertEquals(-15, channel.pitch(), 0);
    }

    @Test public void holdingZoomNeverEscapesTheSupportedDistanceRange() {
        EarthExploreState view = new EarthExploreState(1, 0, 0);
        for (int i = 0; i < 1000; i++) assertTrue(view.handle(EarthExploreState.CHANNEL_UP, i));
        assertEquals(1.6f, view.zoom(), 0);
        for (int i = 0; i < 1000; i++) assertTrue(view.handle(EarthExploreState.MEDIA_REWIND, i));
        assertEquals(.65f, view.zoom(), 0);
    }

    @Test public void okCyclesTheViewOnceAndNeverRepeatsOrResetsAngles() {
        EarthExploreState view = new EarthExploreState(.75f, 45, -20);
        assertTrue(view.handle(EarthExploreState.DPAD_CENTER, 0));
        assertEquals(1.5f, view.zoom(), 0);
        for (int i = 1; i < 20; i++) assertTrue(view.handle(EarthExploreState.DPAD_CENTER, i));
        assertEquals(1.5f, view.zoom(), 0);
        assertTrue(view.handle(EarthExploreState.ENTER, 0));
        assertEquals(1, view.zoom(), 0);
        assertTrue(view.handle(EarthExploreState.NUMPAD_ENTER, 0));
        assertEquals(1.5f, view.zoom(), 0);
        assertEquals(45, view.yaw(), 0);
        assertEquals(-20, view.pitch(), 0);
    }

    @Test public void systemAudioVoiceAndExitKeysAlwaysRemainUnconsumed() {
        EarthExploreState view = new EarthExploreState(1.25f, 35, -20);
        // Home, Back, volume up/down/mute, Menu, play/pause, Assist, Voice Assist and unknown.
        for (int code : new int[]{3, 4, 24, 25, 164, 82, 85, 219, 231, -1}) {
            assertFalse(EarthExploreState.accepts(code));
            assertFalse(view.handle(code, 0));
            assertEquals(1.25f, view.zoom(), 0);
            assertEquals(35, view.yaw(), 0);
            assertEquals(-20, view.pitch(), 0);
        }
        for (int code : new int[]{19, 20, 21, 22, 23, 66, 89, 90, 160, 166, 167})
            assertTrue(EarthExploreState.accepts(code));
    }
}
