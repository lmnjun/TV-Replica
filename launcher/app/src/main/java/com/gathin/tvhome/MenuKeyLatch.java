package com.gathin.tvhome;

/** Pairs one physical menu press with its release; no View, sound, preference or Android dependency. */
final class MenuKeyLatch {
    enum Result { PRESS, RELEASE, CANCEL, IGNORE }
    private boolean armed;
    private int key, device;
    private long downTime;

    Result event(int key, int device, long downTime, boolean down, boolean up, int repeat, boolean canceled) {
        if (!down && !up) return Result.IGNORE;
        if (down) {
            if (canceled) { clear(); return Result.CANCEL; }
            if (repeat != 0) return Result.IGNORE;
            if (armed && key == this.key && device == this.device && downTime == this.downTime) return Result.IGNORE;
            // A fresh press replaces an interrupted/lost release; its older UP cannot release this one.
            this.key = key; this.device = device; this.downTime = downTime; armed = true;
            return Result.PRESS;
        }
        if (!armed || key != this.key || device != this.device || downTime != this.downTime) return Result.IGNORE;
        clear();
        return canceled ? Result.CANCEL : Result.RELEASE;
    }

    void clear() { armed = false; }
}
