package com.gathin.tvhome;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextClock;
import java.util.Random;

/** Local visible clock fallback and a Context-only selected screensaver background. */
final class ClockScene implements AutoCloseable {
    private static final long MOVE_MS=60_000L;
    private static final int[] PALETTE={0xFF2B5CFF,0xFF00A39A,0xFF8A3FFC,0xFFE0661B,0xFFD6336C};
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Random random=new Random();
    private final Context context;
    private final View root,content,ambient;
    private final TextClock time,period;
    private final FrameLayout sceneLayer;
    private final SharedPreferences preferences;
    private final GradientDrawable glow=new GradientDrawable();
    private AmbientScenes scenes;
    private ValueAnimator glowAnimator;
    private Runnable placement;
    private String selected=ScenePreferences.CLOCK;
    private boolean running,closed,pendingPlacement,receiverRegistered,preferencesRegistered;
    private int generation,colorIndex,glowColor;
    private final View.OnLayoutChangeListener layout=this::onLayout;
    private final Runnable refresh=this::refreshSettings;
    private final SharedPreferences.OnSharedPreferenceChangeListener changes=(prefs,key)->{
        if(ClockFormat.KEY.equals(key)||ScenePreferences.SAVER_KEY.equals(key)){
            handler.removeCallbacks(refresh);handler.post(refresh);
        }
    };
    private final BroadcastReceiver timeChanges=new BroadcastReceiver(){
        @Override public void onReceive(Context ignored,Intent intent){
            if(running&&!closed)ClockFormat.apply(time,period);
        }
    };
    private final Runnable tick=new Runnable(){@Override public void run(){
        if(!running||closed)return;
        if(content.getVisibility()==View.VISIBLE)moveClock();
        if(ambient.getVisibility()==View.VISIBLE)shiftGlow();
        if(sceneLayer.getVisibility()==View.VISIBLE&&scenes!=null){
            float drift=8*root.getResources().getDisplayMetrics().density;
            try{scenes.setBurnInOffset((random.nextFloat()-.5f)*2*drift,(random.nextFloat()-.5f)*2*drift);}
            catch(RuntimeException|LinkageError unavailable){showFallback();}
        }
        handler.postDelayed(this,MOVE_MS);
    }};

    /** Root is the actual dream_clock content, never the outer DecorView. */
    ClockScene(View view){
        View actual=view.findViewById(R.id.dreamRoot);root=actual==null?view:actual;
        context=root.getContext();content=root.findViewById(R.id.dreamContent);ambient=root.findViewById(R.id.dreamAmbient);
        time=root.findViewById(R.id.dreamTime);period=root.findViewById(R.id.dreamPeriod);
        if(!(root instanceof FrameLayout)||content==null||ambient==null||time==null)
            throw new IllegalArgumentException("ClockScene requires dream_clock content");
        preferences=context.getSharedPreferences(Config.SETTINGS_NAME,Context.MODE_PRIVATE);
        sceneLayer=new FrameLayout(context);sceneLayer.setVisibility(View.GONE);
        sceneLayer.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        ((FrameLayout)root).addView(sceneLayer,0,new FrameLayout.LayoutParams(-1,-1));
        glow.setGradientType(GradientDrawable.RADIAL_GRADIENT);glow.setGradientRadius(1);ambient.setBackground(glow);
        root.addOnLayoutChangeListener(layout);content.addOnLayoutChangeListener(layout);ambient.addOnLayoutChangeListener(layout);
        colorIndex=random.nextInt(PALETTE.length);setGlow(PALETTE[colorIndex]);
        ClockFormat.apply(time,period);
    }

    void start(){
        if(closed)return;
        if(running){handler.removeCallbacks(refresh);refresh.run();return;}
        running=true;generation++;cancelMotion();
        // Immediately visible even before attachment/layout or a scene renderer exists.
        content.setAlpha(1);ClockFormat.apply(time,period);registerUpdates();selectScene();
        schedulePlacement();handler.removeCallbacks(tick);handler.postDelayed(tick,MOVE_MS);
    }

    void stop(){
        running=false;generation++;pendingPlacement=false;
        if(placement!=null){content.removeCallbacks(placement);placement=null;}
        handler.removeCallbacksAndMessages(null);cancelMotion();content.setAlpha(1);
        if(scenes!=null)try{scenes.stop();}catch(RuntimeException|LinkageError|OutOfMemoryError ignored){}
        if(preferencesRegistered){preferences.unregisterOnSharedPreferenceChangeListener(changes);preferencesRegistered=false;}
        if(receiverRegistered){try{context.unregisterReceiver(timeChanges);}catch(IllegalArgumentException ignored){}receiverRegistered=false;}
    }

    @Override public void close(){
        if(closed)return;closed=true;stop();
        if(scenes!=null){try{scenes.close();}catch(RuntimeException|LinkageError|OutOfMemoryError ignored){}scenes=null;}
        root.removeOnLayoutChangeListener(layout);content.removeOnLayoutChangeListener(layout);ambient.removeOnLayoutChangeListener(layout);
        ((FrameLayout)root).removeView(sceneLayer);
    }

    private void registerUpdates(){
        if(!preferencesRegistered){preferences.registerOnSharedPreferenceChangeListener(changes);preferencesRegistered=true;}
        if(!receiverRegistered){
            IntentFilter filter=new IntentFilter(Intent.ACTION_TIME_CHANGED);filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);filter.addAction(Intent.ACTION_LOCALE_CHANGED);
            try{
                if(Build.VERSION.SDK_INT>=33)context.registerReceiver(timeChanges,filter,Context.RECEIVER_NOT_EXPORTED);
                else context.registerReceiver(timeChanges,filter);
                receiverRegistered=true;
            }catch(RuntimeException unavailable){Log.w("Clock.Scene","Clock broadcasts unavailable; local clock remains visible",unavailable);}
        }
    }

    private void selectScene(){
        if(!running||closed)return;
        generation++;cancelMotion();
        String requested=ScenePreferences.saver(context);
        if(ScenePreferences.CLOCK.equals(requested)||!AmbientScenes.supports(requested)){showFallback();return;}
        // Keep the real clock visible until construction and start both succeeded.
        showClockContent();sceneLayer.setVisibility(View.GONE);
        try{
            if(scenes==null)scenes=new AmbientScenes(context,sceneLayer);
            else scenes.stop();
            if(!scenes.select(requested)){showFallback();return;}
            scenes.start();selected=requested;sceneLayer.setVisibility(View.VISIBLE);ambient.setVisibility(View.GONE);
            content.setVisibility(ScenePreferences.FLIP.equals(requested)?View.GONE:View.VISIBLE);content.setAlpha(1);
            if(content.getVisibility()==View.VISIBLE)schedulePlacement();
            Log.i("Clock.Scene","screensaver="+selected+" visible=true");
        }catch(RuntimeException|LinkageError|OutOfMemoryError unavailable){
            Log.w("Clock.Scene","Selected scene unavailable; showing local clock",unavailable);showFallback();
        }
    }

    private void showClockContent(){
        content.setVisibility(View.VISIBLE);content.setAlpha(1);ambient.setVisibility(View.VISIBLE);
        if(root.getWidth()>0)glow.setGradientRadius(root.getWidth()*.55f);
    }
    private void showFallback(){
        if(scenes!=null)try{scenes.stop();}catch(RuntimeException|LinkageError|OutOfMemoryError ignored){}
        selected=ScenePreferences.CLOCK;sceneLayer.setVisibility(View.GONE);showClockContent();
        if(running&&!closed)schedulePlacement();
    }

    private void schedulePlacement(){
        pendingPlacement=true;
        if(placement!=null)content.removeCallbacks(placement);
        final int ticket=generation;
        placement=()->{if(valid(ticket))placeClockWhenReady();};content.post(placement);
    }
    private void onLayout(View view,int left,int top,int right,int bottom,int oldLeft,int oldTop,int oldRight,int oldBottom){
        if(view==ambient&&right>left)glow.setGradientRadius((right-left)*.55f);
        if(pendingPlacement)placeClockWhenReady();
    }
    private void refreshSettings(){
        if(!running||closed)return;
        ClockFormat.apply(time,period);
        if(!ScenePreferences.saver(context).equals(selected))selectScene();
        else if(content.getVisibility()==View.VISIBLE)schedulePlacement();
    }
    private boolean valid(int ticket){return running&&!closed&&ticket==generation;}
    private void placeClockWhenReady(){
        if(!running||closed||!pendingPlacement||!root.isAttachedToWindow()||content.getVisibility()!=View.VISIBLE
                ||root.getWidth()<=0||root.getHeight()<=0||content.getWidth()<=0||content.getHeight()<=0)return;
        pendingPlacement=false;placeClock();
    }
    private void moveClock(){
        cancelContentAnimation();final int ticket=generation;
        // A cancelled fade can never leave the only clock at alpha zero.
        content.animate().alpha(.25f).setDuration(900).withEndAction(()->{
            if(!valid(ticket)||content.getVisibility()!=View.VISIBLE)return;
            placeClock();content.animate().alpha(1).setDuration(1200).start();
        }).start();
    }
    private void placeClock(){
        if(!running||closed||!root.isAttachedToWindow()||root.getWidth()<=0||root.getHeight()<=0)return;
        int maxX=Math.max(0,root.getWidth()-content.getWidth()),maxY=Math.max(0,root.getHeight()-content.getHeight());
        float x=maxX*(.1f+.8f*random.nextFloat()),y=maxY*(.1f+.8f*random.nextFloat());
        content.setX(x);content.setY(y);
        glow.setGradientCenter((x+content.getWidth()/2f)/root.getWidth(),(y+content.getHeight()/2f)/root.getHeight());
    }
    private void shiftGlow(){
        if(glowAnimator!=null){glowAnimator.removeAllUpdateListeners();glowAnimator.cancel();}
        final int ticket=generation;colorIndex=(colorIndex+1)%PALETTE.length;
        glowAnimator=ValueAnimator.ofObject(new ArgbEvaluator(),glowColor,PALETTE[colorIndex]);glowAnimator.setDuration(8000);
        glowAnimator.addUpdateListener(a->{if(valid(ticket))setGlow((int)a.getAnimatedValue());});glowAnimator.start();
    }
    private void cancelContentAnimation(){content.animate().withEndAction(null).setListener(null).cancel();}
    private void cancelMotion(){
        cancelContentAnimation();
        if(glowAnimator!=null){glowAnimator.removeAllUpdateListeners();glowAnimator.removeAllListeners();glowAnimator.cancel();glowAnimator=null;}
    }
    private void setGlow(int color){
        glowColor=color;int rgb=color&0x00FFFFFF;glow.setColors(new int[]{0x40000000|rgb,0x14000000|rgb,rgb});
    }
}
