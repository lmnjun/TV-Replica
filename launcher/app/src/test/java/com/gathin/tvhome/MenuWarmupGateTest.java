package com.gathin.tvhome;

import org.junit.Test;
import static org.junit.Assert.*;

public class MenuWarmupGateTest {
    @Test public void anUninitializedRendererDoesNotConsumeTheStartupAttempt() {
        MenuWarmupGate gate = new MenuWarmupGate();
        assertFalse(gate.tryStart(false));
        assertTrue(gate.tryStart(true));
    }

    @Test public void aFailedOrSuccessfulAttemptCannotRunAgain() {
        MenuWarmupGate gate = new MenuWarmupGate();
        assertTrue(gate.tryStart(true));
        // Completion is deliberately irrelevant: even a failed GPU warmup gets no retry.
        assertFalse(gate.tryStart(false));
        assertFalse(gate.tryStart(true));
    }

    @Test public void aUserMenuBeforeRendererReadinessPermanentlyCancelsWarmup() {
        MenuWarmupGate gate = new MenuWarmupGate();
        assertFalse(gate.tryStart(false));
        gate.menuRequested();
        assertFalse(gate.tryStart(true));
    }

    @Test public void repeatedUserMenusCannotReopenTheWarmupWindow() {
        MenuWarmupGate gate = new MenuWarmupGate();
        gate.menuRequested();
        gate.menuRequested();
        assertFalse(gate.tryStart(true));
    }
}
