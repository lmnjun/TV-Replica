package com.gathin.tvhome;

import android.content.Context;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.widget.LinearLayout;

/** A popup owns a sound handle only while attached to its window. */
public class SoundKeyLayout extends LinearLayout {
    private LauncherSounds sounds;
    public SoundKeyLayout(Context context){this(context,null);}
    public SoundKeyLayout(Context context,AttributeSet attrs){super(context,attrs);}
    @Override protected void onAttachedToWindow(){
        super.onAttachedToWindow();sounds=LauncherSounds.acquire(getContext());LauncherSounds.muteWindowRoot(this);
    }
    @Override protected void onDetachedFromWindow(){
        if(sounds!=null){sounds.close();sounds=null;}super.onDetachedFromWindow();
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event){
        if(sounds!=null)sounds.onKeyEvent(event);
        return super.dispatchKeyEvent(event)||LauncherSounds.handleUnhandledNavigation(this,event);
    }
}
