package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.media.AudioDeviceInfo;
import android.media.AudioDeviceCallback;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import java.util.Collections;
import java.util.Map;

/**
 * Sends the remote's Home button to this launcher on remotes that emit KEYCODE_ALL_APPS for Home
 * (Xiaomi sticks and boxes). Android TV 9 routes that key only to the system-image launcher, so once
 * the stock launcher is disabled the button does nothing. Every other key passes through untouched.
 *
 * Enable over adb:
 *   settings put secure enabled_accessibility_services com.gathin.tvhome/.HomeKeyService
 *   settings put secure accessibility_enabled 1
 */
public class HomeKeyService extends AccessibilityService {

    /** Remote buttons that should open Settings (config.xml, settings_key_scancodes and settings_key_keycodes). */
    private int[] settingsScancodes = new int[0];
    private int[] settingsKeycodes = new int[0];
    private boolean settingsPanel;
    /** app_keys, the same map InputKeyService reads, for setups that run only this service. */
    private Map<Integer, String> appKeys = Collections.emptyMap();
    private Map<Integer, String> appScancodes = Collections.emptyMap();
    private LauncherSounds sounds;
    private boolean powerReceiverRegistered;
    private PowerCueState powerState;
    private PowerCueScheduler wakeScheduler;
    private PowerCueScheduler.Window wakeWindow;
    private SharedPreferences powerChoices;
    private boolean externalRouteExpected,routeKnown,routeExternalAvailable;
    private AudioManager routeAudio;
    private AudioDeviceCallback routeCallback;
    private HandlerThread routeThread;
    private Handler routeHandler;
    private volatile int routeLifeGeneration,routeWakeGeneration;
    private final Handler powerHandler=new Handler(Looper.getMainLooper());
    private final BroadcastReceiver powerReceiver=new BroadcastReceiver(){
        @Override public void onReceive(Context context,Intent intent){
            Log.i("Launcher.Sound","Power receiver action="+intent.getAction()+" uptimeMs="+SystemClock.uptimeMillis()
                    +" elapsedMs="+SystemClock.elapsedRealtime()+" externalExpected="+externalRouteExpected
                    +" cachedKnown="+routeKnown+" cachedExternal="+routeExternalAvailable);
            if(sounds==null)return;
            if(Intent.ACTION_SCREEN_OFF.equals(intent.getAction())){
                if(wakeScheduler!=null)wakeScheduler.cancel();
                routeWakeGeneration++;routeKnown=false;routeExternalAvailable=false;
                sounds.standby();powerState.screenOff(bootCount(),SystemClock.elapsedRealtime());
                if(wakeWindow!=null)wakeWindow.sleep();
                Log.i("Launcher.Sound","SCREEN_OFF cue requested; TV audio may already be muted");
            }else if(Intent.ACTION_SCREEN_ON.equals(intent.getAction())){
                scheduleInteractiveCue("screen-on");
            }
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        settingsScancodes = getResources().getIntArray(R.array.settings_key_scancodes);
        // Keycodes and the panel choice can also come from a theme; theme.sh restarts this service.
        Config config = Config.load(this);
        settingsKeycodes = config.settingsKeycodes;
        settingsPanel = config.settingsKeyPanel;
        appKeys = RemoteKeys.parseAppKeys(config.appKeys);
        appScancodes = RemoteKeys.parseAppScancodes(config.appKeys);
        // Initial boot handover only; later service rebinds must not interrupt another app.
        if (android.os.SystemClock.elapsedRealtime() < 90_000L) openCleanHome();
        startPowerSounds();
    }

    private int bootCount(){
        try{return Settings.Global.getInt(getContentResolver(),Settings.Global.BOOT_COUNT,-1);}
        catch(RuntimeException unavailable){return -1;}
    }
    private void startPowerSounds(){
        try{
            if(sounds==null)sounds=LauncherSounds.acquire(this);
            if(powerState==null){
                SharedPreferences state=getSharedPreferences("launcher_power_sound_state",MODE_PRIVATE);
                powerChoices=state;externalRouteExpected=state.getBoolean("external_route_seen",false);
                powerState=new PowerCueState(new PowerCueState.Store(){
                    @Override public PowerCueState.Record read(){
                        return new PowerCueState.Record(state.getBoolean("wake_pending",false),
                                state.getInt("off_boot",-1),state.getLong("off_elapsed",-1));
                    }
                    @Override public boolean write(PowerCueState.Record record){
                        boolean saved=state.edit().putBoolean("wake_pending",record.pending)
                                .putInt("off_boot",record.boot).putLong("off_elapsed",record.offElapsed).commit();
                        if(!saved)Log.w("Launcher.Sound","Power cue state could not be persisted");
                        return saved;
                    }
                });
                wakeWindow=new PowerCueScheduler.Window(new PowerCueScheduler.Window.Store(){
                    @Override public PowerCueScheduler.Window.Record read(){
                        return new PowerCueScheduler.Window.Record(state.getInt("window_boot",-1),
                                state.getLong("window_started",-1),state.getLong("window_deadline",-1));
                    }
                    @Override public void write(PowerCueScheduler.Window.Record record){
                        if(!state.edit().putInt("window_boot",record.boot).putLong("window_started",record.started)
                                .putLong("window_deadline",record.deadline).commit())
                            Log.w("Launcher.Sound","Wake window could not be persisted");
                    }
                });
            }
            if(wakeScheduler==null)wakeScheduler=new PowerCueScheduler(new PowerCueScheduler.Host(){
                @Override public void postDelayed(Runnable callback,long delay){powerHandler.postDelayed(callback,delay);}
                @Override public void remove(Runnable callback){powerHandler.removeCallbacks(callback);}
                @Override public long now(){return SystemClock.elapsedRealtime();}
                @Override public boolean interactive(){return isInteractiveNow();}
                @Override public boolean enabled(){
                    return sounds!=null&&getSharedPreferences(Config.SETTINGS_NAME,MODE_PRIVATE)
                            .getBoolean(LauncherSounds.POWER_ENABLED_KEY,true);
                }
                @Override public void skipDisabled(){wakeWindow.finish();powerState.discardWake();}
                @Override public void expired(){
                    wakeWindow.finish();powerState.discardWake();
                    Log.i("Launcher.Sound","Wake cue expired without a stable output availability proxy");
                }
                @Override public void attempt(){wakeWindow.finish();requestInteractiveCue();}
            });
            startRouteWatcher();
            if(!powerReceiverRegistered){
                IntentFilter filter=new IntentFilter(Intent.ACTION_SCREEN_ON);filter.addAction(Intent.ACTION_SCREEN_OFF);
                if(Build.VERSION.SDK_INT>=33)registerReceiver(powerReceiver,filter,Context.RECEIVER_NOT_EXPORTED);
                else registerReceiver(powerReceiver,filter);
                powerReceiverRegistered=true;
            }
            PowerManager power=(PowerManager)getSystemService(POWER_SERVICE);
            if(power!=null){
                if(power.isInteractive())scheduleInteractiveCue("service-rebind");
                else {powerState.screenOff(bootCount(),SystemClock.elapsedRealtime());wakeWindow.sleep();}
            }
        }catch(RuntimeException unavailable){Log.w("Launcher.Sound","Power cues unavailable; Home handling unchanged",unavailable);}
    }
    private boolean isInteractiveNow(){
        try{
            PowerManager power=(PowerManager)getSystemService(POWER_SERVICE);
            return power!=null&&power.isInteractive();
        }catch(RuntimeException unavailable){return false;}
    }
    private static boolean externalOutput(AudioDeviceInfo device){
        if(!device.isSink())return false;
        switch(device.getType()){
                    case AudioDeviceInfo.TYPE_HDMI:case AudioDeviceInfo.TYPE_HDMI_ARC:
                    case AudioDeviceInfo.TYPE_HDMI_EARC:case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
                    case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:case AudioDeviceInfo.TYPE_BLE_HEADSET:
                    case AudioDeviceInfo.TYPE_BLE_SPEAKER:case AudioDeviceInfo.TYPE_HEARING_AID:
                return true;
            default:return false;
        }
    }
    private void startRouteWatcher(){
        if(routeThread!=null)return;
        final AudioManager audio=(AudioManager)getSystemService(AUDIO_SERVICE);
        if(audio==null)return;
        final int life=++routeLifeGeneration;
        HandlerThread thread=new HandlerThread("LauncherAudioAvailability");thread.start();
        Handler background=new Handler(thread.getLooper());
        AudioDeviceCallback callback=new AudioDeviceCallback(){
            @Override public void onAudioDevicesAdded(AudioDeviceInfo[] devices){readRouteSnapshot(audio,life,routeWakeGeneration);}
            @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] devices){
                final int wake=routeWakeGeneration;
                for(AudioDeviceInfo device:devices)if(externalOutput(device)){
                    powerHandler.post(()->{
                        if(life!=routeLifeGeneration||wake!=routeWakeGeneration)return;
                        routeKnown=false;routeExternalAvailable=false;
                        if(wakeScheduler!=null)wakeScheduler.routeSnapshot(false,false);
                    });
                    break;
                }
                readRouteSnapshot(audio,life,wake);
            }
        };
        routeAudio=audio;routeCallback=callback;routeThread=thread;routeHandler=background;
        background.post(()->{
            if(life!=routeLifeGeneration)return;
            try{audio.registerAudioDeviceCallback(callback,background);}
            catch(RuntimeException unavailable){Log.w("Launcher.Sound","Output availability callbacks unavailable");}
            if(life!=routeLifeGeneration){try{audio.unregisterAudioDeviceCallback(callback);}catch(RuntimeException ignored){}return;}
            readRouteSnapshot(audio,life,routeWakeGeneration);
        });
    }
    /** This method runs only on the availability thread. Never query audio ports in SCREEN_ON. */
    private void readRouteSnapshot(AudioManager audio,int life,int wake){
        if(life!=routeLifeGeneration)return;
        boolean external=false,any=false;
        try{
            for(AudioDeviceInfo device:audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)){
                any|=device.isSink();external|=externalOutput(device);
            }
        }catch(RuntimeException unavailable){Log.w("Launcher.Sound","Fresh output availability snapshot unavailable");return;}
        final boolean hasExternal=external,hasOutput=any;
        powerHandler.post(()->{
            if(life!=routeLifeGeneration||wake!=routeWakeGeneration)return;
            routeKnown=true;routeExternalAvailable=hasExternal;
            if(hasExternal&&isInteractiveNow()&&!externalRouteExpected){
                externalRouteExpected=true;powerChoices.edit().putBoolean("external_route_seen",true).apply();
            }
            Log.i("Launcher.Sound","Fresh output availability elapsedMs="+SystemClock.elapsedRealtime()
                    +" externalExpected="+externalRouteExpected+" external="+hasExternal+" any="+hasOutput);
            if(wakeScheduler!=null)wakeScheduler.routeSnapshot(hasExternal,hasOutput);
        });
    }
    private void stopRouteWatcher(){
        routeLifeGeneration++;routeWakeGeneration++;routeKnown=false;routeExternalAvailable=false;
        Handler background=routeHandler;HandlerThread thread=routeThread;
        AudioManager audio=routeAudio;AudioDeviceCallback callback=routeCallback;
        routeHandler=null;routeThread=null;routeAudio=null;routeCallback=null;
        if(background!=null){
            background.removeCallbacksAndMessages(null);
            background.post(()->{if(audio!=null&&callback!=null)try{audio.unregisterAudioDeviceCallback(callback);}catch(RuntimeException ignored){}});
        }
        if(thread!=null)thread.quitSafely();
    }
    private void scheduleInteractiveCue(String source){
        if(wakeScheduler==null||wakeWindow==null)return;
        long now=SystemClock.elapsedRealtime(),deadline=wakeWindow.startOrReuse(bootCount(),now);
        if(deadline==0)return;
        if(wakeScheduler.request(externalRouteExpected,deadline)){
            routeWakeGeneration++;routeKnown=false;routeExternalAvailable=false;
            Handler background=routeHandler;AudioManager audio=routeAudio;
            int life=routeLifeGeneration,wake=routeWakeGeneration;
            if(background!=null&&audio!=null)background.post(()->readRouteSnapshot(audio,life,wake));
            Log.i("Launcher.Sound","Interactive cue waiting source="+source+" uptimeMs="+SystemClock.uptimeMillis()
                    +" elapsedMs="+now+" externalExpected="+externalRouteExpected+" remainingMs="+(deadline-now));
        }
    }
    private void requestInteractiveCue(){
        if(sounds==null||powerState==null)return;
        int boot=bootCount();
        if(powerState.consumeWake(boot,SystemClock.elapsedRealtime(),true)){
            sounds.wake(boot);Log.i("Launcher.Sound","Wake cue consumed after output grace");
        }else sounds.bootOnce(boot);
    }
    private void stopPowerSounds(){
        if(wakeScheduler!=null)wakeScheduler.cancel();
        stopRouteWatcher();
        // Some vendor builds tear down AccessibilityService before delivering SCREEN_OFF.
        // Preserve that observed state too; never force audio, wake a screen, or delay standby.
        try{
            PowerManager power=(PowerManager)getSystemService(POWER_SERVICE);
            if(powerState!=null&&power!=null&&!power.isInteractive()){
                powerState.screenOff(bootCount(),SystemClock.elapsedRealtime());
                if(wakeWindow!=null)wakeWindow.sleep();
            }
        }catch(RuntimeException unavailable){Log.w("Launcher.Sound","Standby state unavailable during service teardown");}
        if(powerReceiverRegistered){try{unregisterReceiver(powerReceiver);}catch(IllegalArgumentException ignored){}powerReceiverRegistered=false;}
        if(sounds!=null){sounds.close();sounds=null;}
    }
    @Override public boolean onUnbind(Intent intent){stopPowerSounds();return super.onUnbind(intent);}
    @Override public void onDestroy(){stopPowerSounds();super.onDestroy();}

    private boolean isSettingsKey(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_SETTINGS) return true;
        for (int code : settingsScancodes) if (event.getScanCode() == code) return true;
        for (int code : settingsKeycodes) if (event.getKeyCode() == code) return true;
        return false;
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        // A scancode entry is more specific than any keycode, so it goes first.
        String scanApp = appScancodes.get(event.getScanCode());
        if (scanApp != null) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) RemoteKeys.launch(this, scanApp);
            return true;
        }
        // The stock launcher handled the remote's Settings button; with it disabled the button does nothing.
        if (isSettingsKey(event)) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                Intent open = settingsPanel ? new Intent(this, QuickSettingsActivity.class)
                        : new Intent(Settings.ACTION_SETTINGS);
                startActivity(open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
            return true;
        }
        String app = appKeys.get(event.getKeyCode());
        if (app != null) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) RemoteKeys.launch(this, app);
            return true;
        }
        if (event.getKeyCode() != KeyEvent.KEYCODE_ALL_APPS
                && event.getKeyCode() != KeyEvent.KEYCODE_HOME) return false;
        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
            Intent home = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .setPackage(getPackageName())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(home);
        }
        return true;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        if (HomeWindowTarget.shouldRedirect(event.getPackageName(),event.getClassName())) openCleanHome();
    }

    /** Only actual Home entry windows are intercepted. The same Google package also owns account
     * selection, settings and authentication activities that must remain usable. Kept Android-free
     * so the allowlist can be tested without constructing an AccessibilityService. */
    static final class HomeWindowTarget {
        static boolean shouldRedirect(CharSequence packageName,CharSequence className){
            if(packageName==null||className==null)return false;
            String pkg=packageName.toString(),cls=className.toString();
            if(cls.startsWith("."))cls=pkg+cls;
            return ("com.google.android.apps.tv.launcherx".equals(pkg)
                    &&"com.google.android.apps.tv.launcherx.home.HomeActivity".equals(cls))
                    ||("com.google.android.tungsten.setupwraith".equals(pkg)
                    &&"com.google.android.tungsten.setupwraith.RecoveryActivity".equals(cls));
        }
    }

    private void openCleanHome() {
        startActivity(new Intent(this, MainActivity.class)
                .setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
    }

    @Override
    public void onInterrupt() {
    }
}
