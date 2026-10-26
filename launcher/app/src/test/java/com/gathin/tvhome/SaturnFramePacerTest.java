package com.gathin.tvhome;

import org.junit.Test;
import static org.junit.Assert.*;

public class SaturnFramePacerTest {
    @Test public void slowRendererDoesNotBuildAVsyncRequestQueue() {
        SaturnFramePacer pacer = new SaturnFramePacer();
        long lifecycle = pacer.start(), first = pacer.request(lifecycle);
        assertTrue(first > 0);
        for (int vsync = 0; vsync < 120; vsync++) assertEquals(0, pacer.request(lifecycle));
        assertEquals(first, pacer.drawingTicket());
        pacer.complete(first);
        assertTrue(pacer.request(lifecycle) > first);
    }

    @Test public void pauseRejectsQueuedCallbacksAndLateOldDrawCannotUnlockNewFrame() {
        SaturnFramePacer pacer = new SaturnFramePacer();
        long oldLifecycle = pacer.start(), oldTicket = pacer.request(oldLifecycle);
        pacer.stop();
        assertEquals(0, pacer.request(oldLifecycle));
        long currentLifecycle = pacer.start(), currentTicket = pacer.request(currentLifecycle);
        assertTrue(currentTicket > oldTicket);
        pacer.complete(oldTicket);
        assertEquals(currentTicket, pacer.drawingTicket());
        assertEquals(0, pacer.request(currentLifecycle));
        assertEquals(0, pacer.request(oldLifecycle));
        pacer.complete(currentTicket);
        assertTrue(pacer.request(currentLifecycle) > currentTicket);
    }

    @Test public void unsolicitedDrawCompletionDoesNotUnlockAnOutstandingRequest() {
        SaturnFramePacer pacer = new SaturnFramePacer();
        long lifecycle = pacer.start(), ticket = pacer.request(lifecycle);
        pacer.complete(0);
        assertEquals(ticket, pacer.drawingTicket());
        assertEquals(0, pacer.request(lifecycle));
    }

    @Test public void cameraResponseIsEqualOverSameTimeAt30_60And120Hz() {
        for (float blend : new float[]{.22f, .16f}) {
            double reference = response(blend, 30, 33_333_333L);
            assertEquals(reference, response(blend, 60, 16_666_667L), 0.0000001);
            assertEquals(reference, response(blend, 120, 8_333_333L), 0.0000001);
        }
    }

    @Test public void delayedFramesAdvanceCameraByElapsedTimeWithoutOvershoot() {
        float turn = .22f;
        assertEquals(turn, SaturnFramePacer.cameraBlend(turn, 0), 0);
        double remaining = Math.pow(1 - turn, 3);
        assertEquals(1 - remaining, SaturnFramePacer.cameraBlend(turn, 100_000_000L), 0.0000001);
        float stalled = SaturnFramePacer.cameraBlend(turn, 5_000_000_000L);
        assertTrue(stalled >= turn && stalled <= 1);
    }

    private static double response(float blend, int frames, long frameNanos) {
        double remaining = 1;
        for (int i = 0; i < frames; i++) remaining *= 1 - SaturnFramePacer.cameraBlend(blend, frameNanos);
        return 1 - remaining;
    }
}
