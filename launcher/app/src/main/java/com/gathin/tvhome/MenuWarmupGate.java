package com.gathin.tvhome;

/** A startup-only optimization must never turn into deferred work during menu interaction. */
final class MenuWarmupGate {
    private boolean attempted, menuRequested;

    synchronized boolean tryStart(boolean rendererReady) {
        if (!rendererReady || attempted || menuRequested) return false;
        attempted = true;
        return true;
    }

    synchronized void menuRequested() { menuRequested = true; }
}
