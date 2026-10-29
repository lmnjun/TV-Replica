package com.gathin.tvhome;

import java.util.function.BooleanSupplier;

/** Serializes request replacement/closure with publication into the shared weather cache. */
final class WeatherRequestGuard {
    private final Object lock;
    private long generation;
    private boolean closed;

    WeatherRequestGuard(Object publicationLock) { lock = publicationLock; }

    long begin() {
        synchronized (lock) { return closed ? 0 : ++generation; }
    }

    boolean current(long token) {
        synchronized (lock) { return !closed && token != 0 && token == generation; }
    }

    /** The check and write must share the cache lock; a check before acquiring it is insufficient. */
    boolean runIfCurrent(long token, BooleanSupplier publication) {
        synchronized (lock) {
            return !closed && token != 0 && token == generation && publication.getAsBoolean();
        }
    }

    void close() {
        synchronized (lock) { closed = true; ++generation; }
    }
}
