package com.gathin.tvhome;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class PowerCueSchedulerTest {
    private static final class MemoryStore implements PowerCueState.Store {
        PowerCueState.Record token=PowerCueState.Record.EMPTY;
        PowerCueScheduler.Window.Record window=PowerCueScheduler.Window.Record.EMPTY;
        @Override public PowerCueState.Record read(){return token;}
        @Override public boolean write(PowerCueState.Record record){token=record;return true;}
        PowerCueScheduler.Window.Store windowStore(){return new PowerCueScheduler.Window.Store(){
            @Override public PowerCueScheduler.Window.Record read(){return window;}
            @Override public void write(PowerCueScheduler.Window.Record record){window=record;}
        };}
    }
    private static final class Call {
        final Runnable callback;final long due;
        Call(Runnable callback,long due){this.callback=callback;this.due=due;}
    }
    private static final class Host implements PowerCueScheduler.Host {
        final MemoryStore store;
        final PowerCueState state;
        final PowerCueScheduler.Window window;
        final List<Call> callbacks=new ArrayList<>();
        boolean interactive=true,enabled=true,bootPlayed=true;
        long now=100_000;
        int attempts,wakes,boots,expirations,disabled;
        Host(MemoryStore store){this.store=store;state=new PowerCueState(store);window=new PowerCueScheduler.Window(store.windowStore());}
        @Override public void postDelayed(Runnable callback,long delay){callbacks.add(new Call(callback,now+delay));}
        @Override public void remove(Runnable callback){callbacks.removeIf(call->call.callback==callback);}
        @Override public long now(){return now;}
        @Override public boolean interactive(){return interactive;}
        @Override public boolean enabled(){return enabled;}
        @Override public void skipDisabled(){disabled++;window.finish();state.discardWake();}
        @Override public void expired(){expirations++;window.finish();state.discardWake();}
        @Override public void attempt(){
            attempts++;window.finish();
            if(state.consumeWake(42,now,true))wakes++;
            else if(!bootPlayed){bootPlayed=true;boots++;}
        }
        long deadline(){return window.startOrReuse(42,now);}
        void advance(long delta){
            long target=now+delta;
            while(true){
                Call next=null;
                for(Call call:callbacks)if(call.due<=target&&(next==null||call.due<next.due))next=call;
                if(next==null)break;
                callbacks.remove(next);now=next.due;next.callback.run();
            }
            now=target;
        }
    }
    private static MemoryStore sleepingToken(){
        MemoryStore store=new MemoryStore();new PowerCueState(store).screenOff(42,90_000);return store;
    }
    @Test public void stalePreSleepReadinessCannotPlayWithoutANewSnapshot(){
        Host host=new Host(sleepingToken());PowerCueScheduler scheduler=new PowerCueScheduler(host);
        scheduler.routeSnapshot(true,true);assertTrue(scheduler.request(true,host.deadline()));
        host.advance(1000);assertEquals(0,host.attempts);assertTrue(host.store.token.pending);
        scheduler.routeSnapshot(true,true);host.advance(199);assertEquals(0,host.attempts);
        host.advance(1);assertEquals(1,host.wakes);
    }
    @Test public void missingExternalOutputNeverFallsBackToTheVisibleSpeaker(){
        Host host=new Host(sleepingToken());PowerCueScheduler scheduler=new PowerCueScheduler(host);
        scheduler.request(true,host.deadline());scheduler.routeSnapshot(false,true);host.advance(700);
        assertEquals(0,host.attempts);assertTrue(host.store.token.pending);
        scheduler.routeSnapshot(true,true);host.advance(200);assertEquals(1,host.wakes);
    }
    @Test public void externalRemovalRestartsTheWholeStableGrace(){
        Host host=new Host(sleepingToken());PowerCueScheduler scheduler=new PowerCueScheduler(host);
        scheduler.request(true,host.deadline());scheduler.routeSnapshot(true,true);host.advance(150);
        scheduler.routeSnapshot(false,true);host.advance(100);assertEquals(0,host.wakes);
        scheduler.routeSnapshot(true,true);host.advance(199);assertEquals(0,host.wakes);
        host.advance(1);assertEquals(1,host.wakes);
    }
    @Test public void duplicateRequestsAndUnchangedCallbacksDoNotPostponeTheCue(){
        Host host=new Host(sleepingToken());PowerCueScheduler scheduler=new PowerCueScheduler(host);
        scheduler.request(true,host.deadline());scheduler.routeSnapshot(true,true);host.advance(70);
        assertFalse(scheduler.request(true,host.deadline()));scheduler.routeSnapshot(true,true);
        host.advance(129);assertEquals(0,host.wakes);host.advance(1);assertEquals(1,host.wakes);
        scheduler.routeSnapshot(true,true);host.advance(1000);assertEquals(1,host.wakes);
    }
    @Test public void speakerUsesSixHundredMillisecondsAfterAFreshSnapshot(){
        Host host=new Host(new MemoryStore());host.bootPlayed=false;
        PowerCueScheduler scheduler=new PowerCueScheduler(host);scheduler.request(false,host.deadline());
        scheduler.routeSnapshot(false,true);host.advance(599);assertEquals(0,host.boots);
        host.advance(1);assertEquals(1,host.boots);
    }
    @Test public void externalDiscoverySupersedesAnUnfinishedSpeakerGrace(){
        Host host=new Host(sleepingToken());PowerCueScheduler scheduler=new PowerCueScheduler(host);
        scheduler.request(false,host.deadline());scheduler.routeSnapshot(false,true);host.advance(400);
        scheduler.routeSnapshot(true,true);host.advance(199);assertEquals(0,host.wakes);
        host.advance(1);assertEquals(1,host.wakes);
    }
    @Test public void sleepCancellationRetainsTokenForTheNextRealWake(){
        Host host=new Host(sleepingToken());PowerCueScheduler scheduler=new PowerCueScheduler(host);
        scheduler.request(true,host.deadline());scheduler.routeSnapshot(true,true);host.advance(100);
        host.interactive=false;scheduler.cancel();host.window.sleep();host.advance(20_000);
        assertEquals(0,host.attempts);assertTrue(host.store.token.pending);
        host.interactive=true;scheduler.request(true,host.deadline());scheduler.routeSnapshot(true,true);
        host.advance(200);assertEquals(1,host.wakes);
    }
    @Test public void teardownRebindRetainsTokenAndDoesNotRestartTheTenSecondWindow(){
        MemoryStore store=sleepingToken();Host old=new Host(store);PowerCueScheduler previous=new PowerCueScheduler(old);
        long deadline=old.deadline();previous.request(true,deadline);old.advance(6000);previous.cancel();
        Host rebound=new Host(store);rebound.now=old.now;assertEquals(deadline,rebound.deadline());
        PowerCueScheduler current=new PowerCueScheduler(rebound);current.request(true,rebound.deadline());
        assertTrue(store.token.pending);rebound.advance(3999);assertEquals(0,rebound.attempts);
        rebound.advance(1);assertEquals(1,rebound.expirations);assertFalse(store.token.pending);
        assertEquals(0,rebound.deadline());current.routeSnapshot(true,true);assertEquals(0,rebound.attempts);
    }
    @Test public void disabledAtRequestDiscardsWakeWithoutQueueing(){
        Host host=new Host(sleepingToken());host.enabled=false;
        PowerCueScheduler scheduler=new PowerCueScheduler(host);assertFalse(scheduler.request(true,host.deadline()));
        assertFalse(host.store.token.pending);assertEquals(0,host.deadline());assertTrue(host.callbacks.isEmpty());
    }
    @Test public void disabledDuringStableGraceDiscardsWakeWithoutReplaying(){
        Host host=new Host(sleepingToken());PowerCueScheduler scheduler=new PowerCueScheduler(host);
        scheduler.request(true,host.deadline());scheduler.routeSnapshot(true,true);host.enabled=false;host.advance(200);
        assertEquals(0,host.attempts);assertEquals(1,host.disabled);assertFalse(host.store.token.pending);
        host.enabled=true;assertEquals(0,host.deadline());
    }
    @Test public void expiredOutputWaitIgnoresALateRouteAndDoesNotReplayAfterRebind(){
        Host host=new Host(sleepingToken());PowerCueScheduler scheduler=new PowerCueScheduler(host);
        scheduler.request(true,host.deadline());host.advance(10_000);assertEquals(1,host.expirations);
        scheduler.routeSnapshot(true,true);host.advance(200);assertEquals(0,host.attempts);
        Host rebound=new Host(host.store);rebound.now=host.now;assertEquals(0,rebound.deadline());
        assertFalse(host.store.token.pending);
    }
    @Test public void stalePlaybackCallbackCannotReleaseTheNextGeneration(){
        Host host=new Host(sleepingToken());PowerCueScheduler scheduler=new PowerCueScheduler(host);
        scheduler.request(true,host.deadline());scheduler.routeSnapshot(true,true);
        Runnable stale=host.callbacks.get(host.callbacks.size()-1).callback;scheduler.cancel();
        scheduler.request(true,host.deadline());stale.run();assertEquals(0,host.attempts);
        scheduler.routeSnapshot(true,true);host.advance(200);assertEquals(1,host.wakes);
    }
    @Test public void noLongerInteractiveAtPlaybackRetainsToken(){
        Host host=new Host(sleepingToken());PowerCueScheduler scheduler=new PowerCueScheduler(host);
        scheduler.request(true,host.deadline());scheduler.routeSnapshot(true,true);host.interactive=false;host.advance(200);
        assertEquals(0,host.attempts);assertTrue(host.store.token.pending);
    }
    @Test public void finishedWindowOnlyResetsForNewSleepOrNewBoot(){
        MemoryStore store=new MemoryStore();PowerCueScheduler.Window window=new PowerCueScheduler.Window(store.windowStore());
        assertEquals(11_000,window.startOrReuse(42,1000));window.finish();
        assertEquals(0,window.startOrReuse(42,2000));
        window.sleep();assertEquals(13_000,window.startOrReuse(42,3000));
        assertEquals(10_010,window.startOrReuse(43,10));
    }
    @Test public void clockRollbackCannotReuseAFutureWakeWindow(){
        PowerCueScheduler.Window window=new PowerCueScheduler.Window(new MemoryStore().windowStore());
        window.startOrReuse(42,1000);assertEquals(0,window.startOrReuse(42,900));
    }
}
