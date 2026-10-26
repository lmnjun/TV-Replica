package com.gathin.tvhome;

/** A one-use wake cue waits for a fresh availability snapshot, never changes the output route. */
final class PowerCueScheduler {
    static final long WINDOW_MILLIS=10_000,EXTERNAL_STABLE_MILLIS=200,SPEAKER_GRACE_MILLIS=600;
    interface Host {
        void postDelayed(Runnable callback,long delayMillis);
        void remove(Runnable callback);
        long now();
        boolean interactive();
        boolean enabled();
        void skipDisabled();
        void expired();
        /** Consume a wake token only here, immediately before requesting its sound. */
        void attempt();
    }
    private final Host host;
    private Runnable playback,expiry;
    private boolean pending,externalExpected;
    private long deadline;
    private int generation;
    PowerCueScheduler(Host host){this.host=host;}
    boolean request(boolean expectExternal,long expiresAt){
        if(!host.enabled()){
            cancel();if(host.interactive())host.skipDisabled();return false;
        }
        if(pending)return false;
        if(!host.interactive())return false;
        if(expiresAt<=host.now()){host.expired();return false;}
        pending=true;externalExpected=expectExternal;deadline=expiresAt;
        final int ticket=++generation;
        expiry=()->expire(ticket);
        host.postDelayed(expiry,deadline-host.now());
        // No cached pre-standby readiness may schedule playback. A fresh snapshot is mandatory.
        return true;
    }
    void routeSnapshot(boolean externalAvailable,boolean anyOutputAvailable){
        if(!pending)return;
        if(!host.interactive()){cancel();return;}
        if(!host.enabled()){cancel();host.skipDisabled();return;}
        if(host.now()>=deadline){expire(generation);return;}
        boolean wasExternal=externalExpected;externalExpected|=externalAvailable;
        boolean ready=externalExpected?externalAvailable:anyOutputAvailable;
        if(!ready){removePlayback();return;}
        if(playback!=null&&wasExternal==externalExpected)return;
        removePlayback();
        long delay=externalExpected?EXTERNAL_STABLE_MILLIS:SPEAKER_GRACE_MILLIS;
        if(host.now()+delay>=deadline)return;
        final int ticket=generation;
        playback=()->{
            if(ticket!=generation||!pending)return;
            if(host.now()>=deadline){expire(ticket);return;}
            cancel();
            if(!host.interactive())return;
            if(host.enabled())host.attempt();else host.skipDisabled();
        };
        host.postDelayed(playback,delay);
    }
    private void expire(int ticket){
        if(ticket!=generation||!pending)return;
        cancel();
        if(host.interactive()){
            if(host.enabled())host.expired();else host.skipDisabled();
        }
    }
    private void removePlayback(){if(playback!=null){host.remove(playback);playback=null;}}
    void cancel(){
        generation++;pending=false;removePlayback();
        if(expiry!=null){host.remove(expiry);expiry=null;}
    }

    /** Persist the same deadline through service recreation; only a new sleep starts a new cycle. */
    static final class Window {
        interface Store { Record read();void write(Record record); }
        static final class Record {
            static final Record EMPTY=new Record(-1,-1,-1);
            final int boot;final long started,deadline;
            Record(int boot,long started,long deadline){this.boot=boot;this.started=started;this.deadline=deadline;}
        }
        private final Store store;
        Window(Store store){this.store=store;}
        long startOrReuse(int boot,long now){
            Record record=store.read();
            if(record.boot!=boot||record.started<0){
                record=new Record(boot,now,now+WINDOW_MILLIS);store.write(record);
            }else if(now<record.started){finish();return 0;}
            return record.deadline;
        }
        void finish(){Record record=store.read();store.write(new Record(record.boot,record.started,0));}
        void sleep(){store.write(Record.EMPTY);}
    }
}
