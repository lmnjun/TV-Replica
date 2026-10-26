package com.gathin.tvhome;

import org.junit.Test;
import static org.junit.Assert.*;

public final class HdmiInputsTest {
    @Test public void portLabelsDoNotConfusePortOneWithPortTen(){
        assertEquals(1,HdmiInputs.portNumber("HDMI1"));
        assertEquals(2,HdmiInputs.portNumber("HDMI 2"));
        assertEquals(3,HdmiInputs.portNumber("HDMI 3 (eARC/ARC)"));
        assertEquals(0,HdmiInputs.portNumber("HDMI 10"));
        assertEquals(0,HdmiInputs.portNumber("Apple TV"));
    }
    @Test public void frameworkStandbyAndDisconnectedAreDistinct(){
        assertEquals("Connected",HdmiInputs.stateLabel(0));
        assertEquals("Standby",HdmiInputs.stateLabel(1));
        assertEquals("Not connected",HdmiInputs.stateLabel(2));
        assertEquals("Unknown",HdmiInputs.stateLabel(HdmiInputs.UNKNOWN));
    }
    @Test public void activeCecDeviceCanCorrectAnOutdatedParentState(){
        assertEquals(0,HdmiInputs.effectiveState(2,0));
        assertEquals(1,HdmiInputs.effectiveState(2,1));
        assertEquals(0,HdmiInputs.effectiveState(-1,0));
    }
    @Test public void missingCecOrDisconnectedCecDoesNotEraseHardwareState(){
        assertEquals(0,HdmiInputs.effectiveState(0,-1));
        assertEquals(0,HdmiInputs.effectiveState(0,2));
        assertEquals(1,HdmiInputs.effectiveState(1,2));
        assertEquals(2,HdmiInputs.effectiveState(2,-1));
        assertEquals(-1,HdmiInputs.effectiveState(-1,-1));
    }
}
