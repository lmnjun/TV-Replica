package com.gathin.tvhome;

/** Bounds CPU render requests; completion is submission, not a GPU/presentation measurement. */
final class SaturnFramePacer {
    private long generation, nextTicket, pending;
    private boolean active;

    synchronized long start() {
        active = true;
        pending = 0;
        return ++generation;
    }

    synchronized void stop() {
        active = false;
        pending = 0;
        generation++;
    }

    /** A stale vsync callback or a busy renderer cannot enqueue another request. */
    synchronized long request(long lifecycle) {
        if (!active || lifecycle != generation || pending != 0) return 0;
        pending = ++nextTicket;
        return pending;
    }

    synchronized long drawingTicket() { return pending; }

    /** A frame left over from an old lifecycle must not acknowledge a newer request. */
    synchronized void complete(long ticket) {
        if (ticket != 0 && ticket == pending) pending = 0;
    }

    /** Preserve the old 30 Hz camera response at any actual draw cadence. */
    static float cameraBlend(float blendAt30Hz, long elapsedNanos) {
        if (elapsedNanos <= 0) return blendAt30Hz;
        double frames = elapsedNanos * (30.0 / 1_000_000_000.0);
        return (float) -Math.expm1(Math.log1p(-blendAt30Hz) * frames);
    }
}
