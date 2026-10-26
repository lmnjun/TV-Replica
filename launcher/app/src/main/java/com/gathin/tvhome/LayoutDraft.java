package com.gathin.tvhome;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** An isolated layout preview. It has no Android dependencies and never writes storage. */
final class LayoutDraft {
    private final List<String> slots = new ArrayList<>();
    private final List<String> appKeys = new ArrayList<>();

    LayoutDraft(List<String> dockSlots, List<String> apps) { reset(dockSlots, apps); }

    /** Every getter returns a copy, so focus previews cannot mutate a saved snapshot. */
    List<String> dockSlots() { return new ArrayList<>(slots); }
    List<String> apps() { return new ArrayList<>(appKeys); }
    List<String> keys() {
        List<String> result = new ArrayList<>();
        for (String key : slots) if (key != null) result.add(key);
        result.addAll(appKeys);
        return result;
    }
    int dockSize() { return slots.size(); }
    int appCount() { return appKeys.size(); }
    int size() { return slots.size() + appKeys.size(); }

    String keyAt(int position) {
        if (position < 0 || position >= size()) return null;
        return position < slots.size() ? slots.get(position) : appKeys.get(position - slots.size());
    }

    int positionOf(String key) {
        if (key == null) return -1;
        int slot = slots.indexOf(key);
        if (slot >= 0) return slot;
        int app = appKeys.indexOf(key);
        return app < 0 ? -1 : slots.size() + app;
    }

    LayoutDraft copy() { return new LayoutDraft(slots, appKeys); }
    LayoutDraft snapshot() { return copy(); }
    void reset(LayoutDraft snapshot) {
        if (snapshot == null) throw new IllegalArgumentException("Missing layout snapshot");
        reset(snapshot.slots, snapshot.appKeys);
    }

    /**
     * Keep each key once. The first Dock occurrence wins; repeated slots become empty. Apps already
     * present in the Dock or earlier Apps are omitted. Empty Apps are never turned into grid tiles.
     */
    void reset(List<String> dockSlots, List<String> apps) {
        List<String> nextSlots = new ArrayList<>(), nextApps = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (dockSlots != null) for (String key : dockSlots) {
            if (key != null) requireKey(key);
            nextSlots.add(key != null && seen.add(key) ? key : null);
        }
        if (apps != null) for (String key : apps) {
            if (key == null) continue;
            requireKey(key);
            if (seen.add(key)) nextApps.add(key);
        }
        // Validate first, then replace both halves together, including when reset(this) is used.
        slots.clear(); slots.addAll(nextSlots);
        appKeys.clear(); appKeys.addAll(nextApps);
    }

    /**
     * Global indices include every fixed Dock slot followed by Apps. Dock moves swap; Apps moves
     * insert without changing other Apps' relative order. Cross-area moves swap, and an empty Dock
     * slot absorbs its chosen App without leaving a null in the Apps grid.
     */
    boolean move(int from, int to) {
        if (from == to || from < 0 || to < 0 || from >= size() || to >= size()) return false;
        int dockCount = slots.size();
        boolean fromDock = from < dockCount, toDock = to < dockCount;
        if (fromDock && toDock) {
            String old = slots.get(from);
            if (old == null && slots.get(to) == null) return false;
            slots.set(from, slots.get(to)); slots.set(to, old);
        } else if (!fromDock && !toDock) {
            appKeys.add(to - dockCount, appKeys.remove(from - dockCount));
        } else {
            int slot = fromDock ? from : to;
            int app = (fromDock ? to : from) - dockCount;
            String old = slots.set(slot, appKeys.get(app));
            if (old == null) appKeys.remove(app);
            else appKeys.set(app, old);
        }
        return true;
    }

    /** Unpin to the end of Apps while retaining the empty Dock position. */
    boolean removeFromDock(int slot) {
        if (slot < 0 || slot >= slots.size() || slots.get(slot) == null) return false;
        appKeys.add(slots.set(slot, null));
        return true;
    }

    private static void requireKey(String key) {
        if (key.trim().isEmpty() || !key.equals(key.trim()) || key.indexOf('\n') >= 0 || key.indexOf('\r') >= 0)
            throw new IllegalArgumentException("Invalid tile key");
    }
}
