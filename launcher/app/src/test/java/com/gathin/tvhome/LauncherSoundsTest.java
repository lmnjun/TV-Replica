package com.gathin.tvhome;

import org.junit.Test;
import android.view.KeyEvent;
import android.view.View;
import static org.junit.Assert.*;

public class LauncherSoundsTest {
    @Test public void rapidNavigationIsThrottledWithoutStackingEveryRepeat(){
        LauncherSounds.Gate gate=new LauncherSounds.Gate();int count=0;
        for(long now=0;now<1000;now+=10)if(gate.selection(now))count++;
        assertEquals(12,count);
    }
    @Test public void powerCueBlocksNavigationUntilItsTailHasFinished(){
        LauncherSounds.Gate gate=new LauncherSounds.Gate();gate.power(500,470);
        assertFalse(gate.selection(500));assertFalse(gate.selection(969));assertTrue(gate.selection(970));
        assertFalse(gate.selection(1000));assertTrue(gate.selection(1055));
    }
    @Test public void bootCueDoesNotReplayForOrdinaryHomeOrServiceRebinding(){
        assertTrue(LauncherSounds.Gate.boot(10,9,40000));
        assertFalse(LauncherSounds.Gate.boot(10,10,40001));
        assertFalse(LauncherSounds.Gate.boot(10,9,90000));
        assertFalse(LauncherSounds.Gate.boot(-1,9,10000));
    }
    @Test public void silentFallbackPreservesDpadAndTabFocusDirections(){
        assertEquals(View.FOCUS_LEFT,LauncherSounds.navigationDirection(KeyEvent.KEYCODE_DPAD_LEFT,true,false));
        assertEquals(View.FOCUS_RIGHT,LauncherSounds.navigationDirection(KeyEvent.KEYCODE_DPAD_RIGHT,true,false));
        assertEquals(View.FOCUS_UP,LauncherSounds.navigationDirection(KeyEvent.KEYCODE_DPAD_UP,true,false));
        assertEquals(View.FOCUS_DOWN,LauncherSounds.navigationDirection(KeyEvent.KEYCODE_DPAD_DOWN,true,false));
        assertEquals(View.FOCUS_FORWARD,LauncherSounds.navigationDirection(KeyEvent.KEYCODE_TAB,true,false));
        assertEquals(View.FOCUS_BACKWARD,LauncherSounds.navigationDirection(KeyEvent.KEYCODE_TAB,false,true));
    }
    @Test public void silentFallbackDoesNotStealModifiedArrowsOrSystemKeys(){
        assertEquals(0,LauncherSounds.navigationDirection(KeyEvent.KEYCODE_DPAD_LEFT,false,true));
        assertEquals(0,LauncherSounds.navigationDirection(KeyEvent.KEYCODE_TAB,false,false));
        assertEquals(0,LauncherSounds.navigationDirection(KeyEvent.KEYCODE_HOME,true,false));
        assertEquals(0,LauncherSounds.navigationDirection(KeyEvent.KEYCODE_BACK,true,false));
        assertEquals(0,LauncherSounds.navigationDirection(KeyEvent.KEYCODE_VOLUME_UP,true,false));
    }
    @Test public void closingPowerOwnerBeforeSampleLoadedPreventsDeferredPlayback(){
        LauncherSounds.OwnedPowerQueue<Object> queue=new LauncherSounds.OwnedPowerQueue<>();
        Object service=new Object(),request=new Object();queue.set(service,request);
        queue.cancel(service);
        assertNull(queue.peek());assertNull(queue.take());
    }
    @Test public void closingServiceDoesNotCancelAnotherHandlesQueuedPower(){
        LauncherSounds.OwnedPowerQueue<Object> queue=new LauncherSounds.OwnedPowerQueue<>();
        Object service=new Object(),main=new Object(),request=new Object();queue.set(main,request);
        queue.cancel(service);
        assertSame(request,queue.peek());assertSame(request,queue.take());assertNull(queue.take());
    }
    @Test public void replacingQueuedOwnerDoesNotLeaveTheOldOwnersCancellationAttached(){
        LauncherSounds.OwnedPowerQueue<Object> queue=new LauncherSounds.OwnedPowerQueue<>();
        Object oldOwner=new Object(),newOwner=new Object(),latest=new Object();
        queue.set(oldOwner,new Object());queue.set(newOwner,latest);queue.cancel(oldOwner);
        assertSame(latest,queue.take());
    }
}
