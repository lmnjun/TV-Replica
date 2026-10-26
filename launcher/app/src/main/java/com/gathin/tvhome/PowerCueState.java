package com.gathin.tvhome;

/** A persisted, one-use wake token; no wall clock, timers, or dependency on a living Service. */
final class PowerCueState {
    interface Store {
        Record read();
        /** Persist before returning true, so a killed/rebound service cannot replay a consumed cue. */
        boolean write(Record record);
    }
    static final class Record {
        static final Record EMPTY=new Record(false,-1,-1);
        final boolean pending;
        final int boot;
        final long offElapsed;
        Record(boolean pending,int boot,long offElapsed){this.pending=pending;this.boot=boot;this.offElapsed=offElapsed;}
    }
    private final Store store;
    private boolean observedOff;
    PowerCueState(Store store){this.store=store;}
    synchronized void screenOff(int boot,long elapsed){
        observedOff=true;
        store.write(new Record(true,boot,elapsed));
    }
    synchronized boolean consumeWake(int boot,long elapsed,boolean interactive){
        if(!interactive)return false;
        boolean direct=observedOff;observedOff=false;
        Record previous=store.read();
        if(!previous.pending)return direct;
        boolean sameBoot=boot>=0&&previous.boot==boot&&previous.offElapsed>=0&&elapsed>=previous.offElapsed;
        // Clear invalid tokens too. A new boot (or unavailable BOOT_COUNT after a rebind) must not
        // turn a stale standby token into an extra startup sound.
        return store.write(Record.EMPTY)&&(direct||sameBoot);
    }
    synchronized void discardWake(){
        observedOff=false;
        if(store.read().pending)store.write(Record.EMPTY);
    }
}
