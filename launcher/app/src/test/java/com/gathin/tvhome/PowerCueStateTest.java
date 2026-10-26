package com.gathin.tvhome;

import org.junit.Test;
import static org.junit.Assert.*;

public class PowerCueStateTest {
    private static final class MemoryStore implements PowerCueState.Store {
        PowerCueState.Record record=PowerCueState.Record.EMPTY;
        boolean saves=true;
        @Override public PowerCueState.Record read(){return record;}
        @Override public boolean write(PowerCueState.Record value){if(!saves)return false;record=value;return true;}
    }
    @Test public void wakeSurvivesServiceDeathAndIsConsumedOnlyOnce(){
        MemoryStore store=new MemoryStore();
        new PowerCueState(store).screenOff(42,100_000);
        assertTrue(new PowerCueState(store).consumeWake(42,200_000,true));
        assertFalse(new PowerCueState(store).consumeWake(42,200_001,true));
    }
    @Test public void rebindingWhileScreenIsOffDoesNotConsumeToken(){
        MemoryStore store=new MemoryStore();
        new PowerCueState(store).screenOff(42,100_000);
        PowerCueState rebound=new PowerCueState(store);
        assertFalse(rebound.consumeWake(42,200_000,false));
        assertTrue(store.record.pending);
        assertTrue(rebound.consumeWake(42,300_000,true));
    }
    @Test public void staleTokenFromAnotherBootIsClearedSilently(){
        MemoryStore store=new MemoryStore();
        new PowerCueState(store).screenOff(41,100_000);
        assertFalse(new PowerCueState(store).consumeWake(42,200_000,true));
        assertFalse(store.record.pending);
    }
    @Test public void elapsedTimeMustNotRunBackwardAfterRebind(){
        MemoryStore store=new MemoryStore();
        new PowerCueState(store).screenOff(42,100_000);
        assertFalse(new PowerCueState(store).consumeWake(42,10_000,true));
        assertFalse(store.record.pending);
    }
    @Test public void longStandbyIsNotConfusedWithColdBootOrLimitedToNinetySeconds(){
        MemoryStore store=new MemoryStore();
        new PowerCueState(store).screenOff(42,100_000);
        assertTrue(new PowerCueState(store).consumeWake(42,100_000+7L*24*60*60*1000,true));
    }
    @Test public void unavailableBootCountOnlyAllowsDirectObservedScreenCycle(){
        MemoryStore store=new MemoryStore();
        PowerCueState live=new PowerCueState(store);live.screenOff(-1,100_000);
        assertTrue(live.consumeWake(-1,200_000,true));
        assertFalse(live.consumeWake(-1,200_001,true));
        live.screenOff(-1,300_000);
        assertFalse(new PowerCueState(store).consumeWake(-1,400_000,true));
    }
    @Test public void failedConsumptionDoesNotPlayAndCanBeRetriedAfterStorageRecovers(){
        MemoryStore store=new MemoryStore();
        new PowerCueState(store).screenOff(42,100_000);store.saves=false;
        assertFalse(new PowerCueState(store).consumeWake(42,200_000,true));
        store.saves=true;
        assertTrue(new PowerCueState(store).consumeWake(42,200_001,true));
        assertFalse(new PowerCueState(store).consumeWake(42,200_002,true));
    }
    @Test public void discardingDisabledWakeClearsBothPersistentAndLiveObservation(){
        MemoryStore store=new MemoryStore();PowerCueState live=new PowerCueState(store);
        live.screenOff(42,100_000);live.discardWake();
        assertFalse(store.record.pending);
        assertFalse(live.consumeWake(42,200_000,true));
        assertFalse(new PowerCueState(store).consumeWake(42,200_001,true));
    }
}
