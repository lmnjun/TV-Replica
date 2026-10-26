package com.gathin.tvhome;

import java.util.List;

/** A reversible, in-memory arranging session. The caller decides when to save its preview. */
final class InlineArrangeSession {
    private final LayoutDraft original;
    private final LayoutDraft preview;
    private String movingKey;

    InlineArrangeSession(List<String> dock, List<String> apps) {
        original = new LayoutDraft(dock, apps);
        preview = original.copy();
    }

    String movingKey() { return movingKey; }

    /** Resolve by identity after every move: filling an empty Dock slot can shorten the Apps area. */
    int movingPosition() { return preview.positionOf(movingKey); }

    /** Empty Dock positions and out-of-range positions cannot be picked up. */
    boolean pickUp(int globalPosition) {
        String key = preview.keyAt(globalPosition);
        if (key == null) return false;
        movingKey = key;
        return true;
    }

    /** Keep the current preview but stop carrying its tile. This does not commit anything. */
    void drop() { movingKey = null; }

    boolean moveTo(int targetGlobalIndex) {
        int from = movingPosition();
        return from >= 0 && preview.move(from, targetGlobalIndex);
    }

    /** The UI receives an isolated snapshot rather than the mutable session state. */
    LayoutDraft layout() { return preview.copy(); }

    boolean changed() {
        return !original.dockSlots().equals(preview.dockSlots())
                || !original.apps().equals(preview.apps());
    }

    /** Discard every preview move and stop carrying a tile, without touching persistent storage. */
    LayoutDraft cancel() {
        drop();
        preview.reset(original);
        return layout();
    }
}
