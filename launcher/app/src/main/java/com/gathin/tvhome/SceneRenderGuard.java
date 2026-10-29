package com.gathin.tvhome;

/** Rejects late GL-context results and preserves an inactive host's failure until it resumes. */
final class SceneRenderGuard {
    private volatile long generation, failedGeneration;
    private boolean closed, notified, fallback;

    synchronized long beginContext() {
        if (closed || fallback) return 0;
        failedGeneration = 0; notified = false;
        return ++generation;
    }

    synchronized boolean fail(long ticket) {
        if (closed || ticket == 0 || ticket != generation || failedGeneration == ticket) return false;
        failedGeneration = ticket;
        return true;
    }

    boolean failed(long ticket) { return ticket != 0 && failedGeneration == ticket && generation == ticket; }
    long failure() { long ticket = failedGeneration; return failed(ticket) ? ticket : 0; }

    synchronized boolean useFallback(long ticket) {
        if (closed || !failed(ticket)) return false;
        // Once the UI retires this surface, only a new scene instance may retry GL.
        // A context recreated concurrently with UI pause must not undo that decision.
        fallback = true;
        return true;
    }

    synchronized boolean claimFailure(long ticket, boolean active) {
        if (closed || !fallback || !active || notified || !failed(ticket)) return false;
        notified = true;
        return true;
    }

    synchronized void close() { closed = true; generation++; failedGeneration = 0; }
}
