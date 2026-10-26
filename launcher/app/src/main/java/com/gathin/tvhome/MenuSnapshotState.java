package com.gathin.tvhome;

/** Main-thread revision contract for immutable snapshots of the currently visible profile pill. */
final class MenuSnapshotState {
    private long revision;
    private long capturedRevision = -1;
    private int width, height;

    void invalidate() { revision++; }
    long revision() { return revision; }
    boolean reusable(int width, int height) {
        return width > 0 && height > 0 && this.width == width && this.height == height
                && capturedRevision == revision;
    }
    boolean publish(long captureRevision, int width, int height) {
        if (captureRevision != revision || width <= 0 || height <= 0) return false;
        capturedRevision = captureRevision; this.width = width; this.height = height;
        return true;
    }
    void clear() { capturedRevision = -1; width = 0; height = 0; }
}
