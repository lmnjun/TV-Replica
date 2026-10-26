package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/** Quiet local cues. No audio focus, volume changes, routing changes, or playback service. */
final class LauncherSounds implements AutoCloseable {
    static final String SELECTION_ENABLED_KEY="selection_sounds",POWER_ENABLED_KEY="power_sounds";
    private static final String BOOT_KEY="power_sound_last_boot_count";
    private static final int SELECT=0,START=1,STANDBY=2;
    private static final Object LOCK=new Object();
    private static final Set<View> MUTED_ROOTS=Collections.newSetFromMap(new WeakHashMap<>());
    private static final Set<View> SILENT_NAV_ROOTS=Collections.newSetFromMap(new WeakHashMap<>());
    private static Engine shared;
    private final Engine engine;
    private volatile boolean closed;
    private boolean loggedFirstKey,loggedFirstSelection;

    static LauncherSounds acquire(Context context){
        synchronized(LOCK){
            if(shared==null)shared=new Engine(context.getApplicationContext());
            shared.references++;return new LauncherSounds(shared);
        }
    }
    private LauncherSounds(Engine engine){this.engine=engine;}
    void selection(){
        if(!loggedFirstSelection){
            loggedFirstSelection=true;
            Log.i("Launcher.Sound","selection handle first id="+Integer.toHexString(System.identityHashCode(this))
                    +" closed="+closed);
        }
        if(!closed)engine.enqueueSelection(this);
    }
    void powerOn(){if(!closed)engine.power(START,-1,this);}
    void wake(int bootCount){if(!closed)engine.wake(bootCount,this);}
    void standby(){if(!closed)engine.power(STANDBY,-1,this);}
    void bootOnce(int bootCount){if(!closed)engine.bootOnce(bootCount,this);}

    void onKeyEvent(KeyEvent event){
        if(!loggedFirstKey){
            loggedFirstKey=true;
            Log.i("Launcher.Sound","dispatch first key="+event.getKeyCode()+" action="+event.getAction()
                    +" flags="+event.getFlags()+" canceled="+event.isCanceled()+" closed="+closed);
        }
        if(event.getAction()!=KeyEvent.ACTION_DOWN||event.isCanceled())return;
        switch(event.getKeyCode()){
            case KeyEvent.KEYCODE_DPAD_UP:case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER:case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_BACK:case KeyEvent.KEYCODE_ESCAPE:selection();break;
            default:break;
        }
    }
    static void muteSystemEffects(View view){
        if(view==null)return;view.setSoundEffectsEnabled(false);
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)muteSystemEffects(group.getChildAt(i));}
    }
    /** Mutes click effects requested by this window's own views, including newly attached controls. */
    static void muteWindowRoot(View view){
        if(view==null)return;
        View root=view.getRootView();muteSystemEffects(root);
        if(MUTED_ROOTS.add(root)){
            // RecyclerView and menus may attach controls after this initial pass. Mute the newly
            // focused subtree before an OK/Enter click can request its own default effect.
            root.getViewTreeObserver().addOnGlobalFocusChangeListener((oldFocus,newFocus)->muteSystemEffects(newFocus));
            Log.i("Launcher.Sound","soundMutedRoot class="+root.getClass().getName()
                    +" id="+Integer.toHexString(System.identityHashCode(root))+" enabled="+root.isSoundEffectsEnabled());
        }
    }
    /**
     * Call only after the normal key dispatch returns false. Android 11's ViewRootImpl then moves
     * focus and calls AudioManager directly, bypassing every View's soundEffectsEnabled flag.
     * Keep that focus behavior inside this window, without requesting its system navigation cue.
     */
    static boolean handleUnhandledNavigation(View view,KeyEvent event){
        if(view==null||event==null)return false;
        int direction=navigationDirection(event.getKeyCode(),event.hasNoModifiers(),event.hasModifiers(KeyEvent.META_SHIFT_ON));
        if(direction==0)return false;
        if(event.getAction()!=KeyEvent.ACTION_DOWN)return event.getAction()==KeyEvent.ACTION_UP;
        if(event.isCanceled())return true;
        View root=view.getRootView(),focused=root.findFocus();
        if(SILENT_NAV_ROOTS.add(root))Log.i("Launcher.Sound","silentFocusFallback root="+root.getClass().getName());
        if(focused==null){root.restoreDefaultFocus();return true;}
        View next=focused.focusSearch(direction);
        if(next!=null&&next!=focused&&next.getRootView()==root){
            Rect rect=new Rect();focused.getFocusedRect(rect);
            if(root instanceof ViewGroup){
                ViewGroup group=(ViewGroup)root;
                group.offsetDescendantRectToMyCoords(focused,rect);
                group.offsetRectIntoDescendantCoords(next,rect);
            }
            if(next.requestFocus(direction,rect))return true;
        }
        root.dispatchUnhandledMove(focused,direction);
        // A boundary must also be handled, or ViewRootImpl will repeat its audible fallback.
        return true;
    }
    static int navigationDirection(int key,boolean noModifiers,boolean shiftOnly){
        switch(key){
            case KeyEvent.KEYCODE_DPAD_LEFT:return noModifiers?View.FOCUS_LEFT:0;
            case KeyEvent.KEYCODE_DPAD_RIGHT:return noModifiers?View.FOCUS_RIGHT:0;
            case KeyEvent.KEYCODE_DPAD_UP:return noModifiers?View.FOCUS_UP:0;
            case KeyEvent.KEYCODE_DPAD_DOWN:return noModifiers?View.FOCUS_DOWN:0;
            case KeyEvent.KEYCODE_TAB:return noModifiers?View.FOCUS_FORWARD:shiftOnly?View.FOCUS_BACKWARD:0;
            default:return 0;
        }
    }
    @Override public void close(){
        synchronized(LOCK){if(closed)return;closed=true;engine.cancelPendingPower(this);if(--engine.references==0){engine.release();if(shared==engine)shared=null;}}
    }

    /** A sample-loading queue belongs to the requesting handle, even while the Engine is shared. */
    static final class OwnedPowerQueue<T>{
        private Object owner;private T value;
        synchronized void set(Object owner,T value){this.owner=owner;this.value=value;}
        synchronized T peek(){return value;}
        synchronized T take(){T result=value;clear();return result;}
        synchronized T takeIf(T expected){return value==expected?take():null;}
        synchronized void cancel(Object closingOwner){if(owner==closingOwner)clear();}
        synchronized void clear(){owner=null;value=null;}
    }

    /** Monotonic gating is independent of Android audio and can be tested on the JVM. */
    static final class Gate{
        private long lastSelection=Long.MIN_VALUE,powerUntil;
        boolean selection(long now){
            if(now<powerUntil||lastSelection!=Long.MIN_VALUE&&now-lastSelection<85)return false;
            lastSelection=now;return true;
        }
        void power(long now,int duration){powerUntil=now+duration;}
        static boolean boot(int current,int previous,long uptime){return current>=0&&current!=previous&&uptime>=0&&uptime<90_000;}
    }
    private static final class Request{
        final int kind,boot;final long at;
        Request(int kind,int boot){this.kind=kind;this.boot=boot;at=SystemClock.uptimeMillis();}
    }
    private static final class SelectionRequest{
        final LauncherSounds owner;final long at;
        SelectionRequest(LauncherSounds owner){this.owner=owner;at=SystemClock.uptimeMillis();}
    }
    private static final class Engine{
        final SharedPreferences choices;
        final SoundPool pool;
        final Handler handler=new Handler(Looper.getMainLooper());
        final HandlerThread selectionThread;
        final Handler selectionHandler;
        final Object selectionRequestLock=new Object();
        SelectionRequest latestSelection;
        final Runnable selectionPlayback=this::playLatestSelection;
        final int[] samples=new int[3];final boolean[] loaded=new boolean[3];
        final Gate gate=new Gate();
        volatile int references;
        int selectionStream,powerStream,lastPowerKind=-1,selectionCount,selectionRequests,selectionAttempts,selectionRejected;
        boolean loggedSelectionRejected,loggedPowerRequest;
        long lastPowerAt=Long.MIN_VALUE;
        volatile boolean released;final OwnedPowerQueue<Request> pending=new OwnedPowerQueue<>();
        Engine(Context context){
            choices=context.getSharedPreferences(Config.SETTINGS_NAME,Context.MODE_PRIVATE);
            SoundPool available;
            try{available=new SoundPool.Builder().setMaxStreams(2).setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();}
            catch(RuntimeException unavailable){available=null;Log.w("Launcher.Sound","Sound cues unavailable; UI remains usable",unavailable);}
            pool=available;
            if(pool==null){selectionThread=null;selectionHandler=null;return;}
            selectionThread=new HandlerThread("launcher-selection");selectionThread.start();
            selectionHandler=new Handler(selectionThread.getLooper());
            pool.setOnLoadCompleteListener((p,id,status)->{
                if(!released)handler.post(()->{if(!released)loaded(id,status);});
            });
            int[] resources={R.raw.launcher_select,R.raw.launcher_start,R.raw.launcher_standby};
            for(int i=0;i<samples.length;i++)samples[i]=pool.load(context,resources[i],1);
        }
        synchronized void loaded(int id,int status){
            if(released)return;
            for(int i=0;i<samples.length;i++)if(samples[i]==id){loaded[i]=status==0;Log.i("Launcher.Sound","loaded kind="+i+" status="+status);}
            Request waiting=pending.peek();
            if(waiting!=null&&loaded[waiting.kind]){
                Request request=pending.takeIf(waiting);
                if(request!=null&&SystemClock.uptimeMillis()-request.at<1200)playPower(request);
            }
        }
        // The UI only touches this short request lock. Native stop/play never runs under it.
        void enqueueSelection(LauncherSounds owner){
            if(released||owner.closed||selectionHandler==null)return;
            synchronized(selectionRequestLock){
                if(released||owner.closed)return;
                latestSelection=new SelectionRequest(owner);
                selectionHandler.removeCallbacks(selectionPlayback);
                selectionHandler.post(selectionPlayback);
            }
        }
        private void playLatestSelection(){
            final SelectionRequest request;
            synchronized(selectionRequestLock){request=latestSelection;latestSelection=null;}
            if(request==null||released||request.owner.closed
                    ||SystemClock.uptimeMillis()-request.at>150)return;
            playSelection(request);
        }
        // Existing gating and native playback are serialized here, on the selection worker only.
        private synchronized void playSelection(SelectionRequest request){
            LauncherSounds owner=request.owner;
            if(released||owner.closed||SystemClock.uptimeMillis()-request.at>150)return;
            boolean first=++selectionRequests==1;
            if(first)Log.i("Launcher.Sound","selection engine first "+snapshot());
            String skipped=null;
            if(released)skipped="released";
            else if(pool==null)skipped="pool-null";
            else if(!choices.getBoolean(SELECTION_ENABLED_KEY,true))skipped="disabled";
            else if(!loaded[SELECT])skipped="sample-not-loaded";
            else if(!gate.selection(SystemClock.uptimeMillis()))skipped="throttled-or-power-tail";
            if(skipped!=null){if(first)Log.i("Launcher.Sound","selection skipped reason="+skipped);return;}
            if(selectionStream!=0)pool.stop(selectionStream);
            if(released||owner.closed||SystemClock.uptimeMillis()-request.at>150)return;
            selectionAttempts++;
            selectionStream=pool.play(samples[SELECT],.28f,.28f,1,0,1);
            if(released||owner.closed){if(selectionStream!=0)pool.stop(selectionStream);selectionStream=0;return;}
            if(selectionStream==0){
                selectionRejected++;
                if(!loggedSelectionRejected){loggedSelectionRejected=true;Log.w("Launcher.Sound","selection play returned 0 "+snapshot());}
            }
            if(selectionStream!=0&&(++selectionCount==1||selectionCount%50==0))Log.i("Launcher.Sound","selection played count="+selectionCount);
        }
        synchronized String snapshot(){
            long now=SystemClock.uptimeMillis();
            return "engine="+Integer.toHexString(System.identityHashCode(this))+" refs="+references
                    +" released="+released+" pool="+(pool!=null)+" selectionEnabled="+choices.getBoolean(SELECTION_ENABLED_KEY,true)
                    +" powerEnabled="+choices.getBoolean(POWER_ENABLED_KEY,true)
                    +" samples="+java.util.Arrays.toString(samples)+" loaded="+java.util.Arrays.toString(loaded)
                    +" requests="+selectionRequests+" attempts="+selectionAttempts+" played="+selectionCount+" rejected="+selectionRejected
                    +" powerTailMs="+Math.max(0,gate.powerUntil-now)
                    +" lastSelectionAgeMs="+(gate.lastSelection==Long.MIN_VALUE?-1:now-gate.lastSelection);
        }
        synchronized void bootOnce(int count,Object owner){
            if(!Gate.boot(count,choices.getInt(BOOT_KEY,-1),SystemClock.elapsedRealtime()))return;
            power(START,count,owner);
        }
        synchronized void wake(int count,Object owner){
            power(START,Gate.boot(count,choices.getInt(BOOT_KEY,-1),SystemClock.elapsedRealtime())?count:-1,owner);
        }
        synchronized void power(int kind,int boot,Object owner){
            if(!loggedPowerRequest){loggedPowerRequest=true;Log.i("Launcher.Sound","power first kind="+kind+" boot="+boot+" "+snapshot());}
            if(released||pool==null||!choices.getBoolean(POWER_ENABLED_KEY,true))return;
            if(boot>=0&&choices.getInt(BOOT_KEY,-1)==boot)return;
            Request request=new Request(kind,boot);
            if(!loaded[kind]){
                pending.set(owner,request);Log.i("Launcher.Sound","power queued kind="+kind+" boot="+boot);
                handler.postDelayed(()->{synchronized(Engine.this){if(pending.takeIf(request)!=null)Log.i("Launcher.Sound","power expired before sample ready");}},1200);
                return;
            }
            pending.clear();playPower(request);
        }
        void cancelPendingPower(Object owner){pending.cancel(owner);}
        private void playPower(Request request){
            if(released||!choices.getBoolean(POWER_ENABLED_KEY,true))return;
            long now=SystemClock.uptimeMillis();
            if(lastPowerKind==request.kind&&lastPowerAt!=Long.MIN_VALUE&&now-lastPowerAt<1000)return;
            if(request.boot>=0&&choices.getInt(BOOT_KEY,-1)==request.boot)return;
            if(selectionStream!=0)pool.stop(selectionStream);if(powerStream!=0)pool.stop(powerStream);
            powerStream=pool.play(samples[request.kind],.32f,.32f,2,0,1);
            if(powerStream==0){Log.w("Launcher.Sound","power play rejected kind="+request.kind);return;}
            lastPowerKind=request.kind;lastPowerAt=now;gate.power(now,request.kind==START?470:240);
            if(request.boot>=0)choices.edit().putInt(BOOT_KEY,request.boot).apply();
            Log.i("Launcher.Sound","power played kind="+request.kind+" boot="+request.boot+" (system mute/routing respected)");
        }
        void release(){
            released=true;pending.clear();handler.removeCallbacksAndMessages(null);
            if(selectionHandler==null)return;
            synchronized(selectionRequestLock){
                latestSelection=null;selectionHandler.removeCallbacks(selectionPlayback);
                // Runs after an in-flight selection. The UI never joins a native play or release.
                selectionHandler.post(()->{
                    try{synchronized(Engine.this){if(pool!=null)pool.release();}}
                    finally{selectionThread.quitSafely();}
                });
            }
        }
    }
}
