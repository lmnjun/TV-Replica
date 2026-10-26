package com.gathin.tvhome;

/** Shared geometry for an app row's immediate and smooth reveal paths. No Dock parking policy. */
final class HeaderSafeViewport {
    static int scrollDelta(int top, int bottom, int safeTop, int height, int bottomPadding) {
        int start = Math.max(0, Math.min(height, safeTop));
        if (top < start) return top - start;
        // Preserve already-visible rows inside the list's bottom padding.
        if (bottom <= height) return 0;
        int end = Math.max(start, height - Math.max(0, bottomPadding));
        return Math.max(0, Math.min(top - start, bottom - end));
    }

    static int visibleHeight(int top, int bottom, int safeTop, int viewportBottom) {
        return Math.max(0, Math.min(bottom, viewportBottom) - Math.max(top, safeTop));
    }
}
