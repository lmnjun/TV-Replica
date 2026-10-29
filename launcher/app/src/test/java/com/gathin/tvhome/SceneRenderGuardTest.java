package com.gathin.tvhome;

import org.junit.Test;
import static org.junit.Assert.*;

public class SceneRenderGuardTest {
    @Test public void olderContextCannotReplaceANewerSuccessfulContext() {
        SceneRenderGuard guard = new SceneRenderGuard();
        long old = guard.beginContext(), current = guard.beginContext();
        assertFalse(guard.fail(old)); assertEquals(0, guard.failure());
        assertTrue(guard.fail(current)); assertEquals(current, guard.failure());
    }

    @Test public void failureWhileStoppedIsDeliveredOnceAfterResume() {
        SceneRenderGuard guard = new SceneRenderGuard(); long ticket = guard.beginContext();
        assertTrue(guard.fail(ticket));
        assertTrue(guard.useFallback(ticket));
        assertFalse(guard.claimFailure(ticket, false));
        assertEquals(ticket, guard.failure());
        assertTrue(guard.claimFailure(ticket, true));
        assertFalse(guard.claimFailure(ticket, true));
        assertFalse(guard.fail(ticket));
    }

    @Test public void newContextInvalidatesAnAlreadyQueuedFailureDelivery() {
        SceneRenderGuard guard = new SceneRenderGuard(); long old = guard.beginContext();
        assertTrue(guard.fail(old)); long current = guard.beginContext();
        assertFalse(guard.failed(old)); assertFalse(guard.claimFailure(old, true));
        assertFalse(guard.useFallback(old));
        assertTrue(guard.fail(current)); assertTrue(guard.useFallback(current));
        assertTrue(guard.claimFailure(current, true));
    }

    @Test public void closingRejectsLateFailureAndCannotStartAnotherContext() {
        SceneRenderGuard guard = new SceneRenderGuard(); long ticket = guard.beginContext();
        guard.close(); assertFalse(guard.fail(ticket));
        assertFalse(guard.claimFailure(ticket, true)); assertEquals(0, guard.failure());
        assertEquals(0, guard.beginContext());
    }

    @Test public void retiredFailedSurfaceCannotRestartAfterPauseOrReattachment() {
        SceneRenderGuard guard = new SceneRenderGuard(); long ticket = guard.beginContext();
        assertTrue(guard.fail(ticket)); assertTrue(guard.useFallback(ticket));
        assertEquals(0, guard.beginContext()); assertEquals(ticket, guard.failure());
        assertTrue(guard.claimFailure(ticket, true));
        assertFalse(guard.claimFailure(ticket, true));
    }
}
