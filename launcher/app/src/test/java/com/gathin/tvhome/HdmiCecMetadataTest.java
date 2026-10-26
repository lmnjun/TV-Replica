package com.gathin.tvhome;

import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public final class HdmiCecMetadataTest {
    private static final String PREFIX="com.tcl.tvinput/.passthroughinput.TvPassThroughService/";
    private static final String CLASS="android.hardware.hdmi.HdmiDeviceInfo";

    @Test public void observedTclSwitchAndAppleRecordsParseAndClassify(){
        // Actual TV dump: port 2, logical 8, generic Playback_2, Nintendo vendor 2495.
        HdmiCecMetadata nintendo=read(PREFIX+"HDMI200008",2,CLASS,0,0x2000,2,8,4,2495,0,"Playback_2");
        assertNotNull(nintendo);
        assertEquals(0x0009BF,nintendo.vendorId);
        assertEquals(HdmiDeviceKind.SWITCH,HdmiDeviceKind.detect("Playback_2",nintendo));
        HdmiCecMetadata apple=read(PREFIX+"HDMI300004",3,CLASS,0,0x3000,3,4,4,4346,0,"Apple TV");
        assertNotNull(apple);
        assertEquals(HdmiDeviceKind.APPLE_TV,HdmiDeviceKind.detect("Apple TV",apple));
    }

    @Test public void malformedOrTruncatedNestedParcelIsRejected(){
        Object[] good={CLASS,0,0x2000,2,8,4,2495,0,"Playback_2"};
        for(int size=0;size<good.length;size++)
            assertNull("truncated at "+size,read(PREFIX+"HDMI200008",2,Arrays.copyOf(good,size)));
        assertNull(read(PREFIX+"HDMI200008",2,"other.HiddenClass",0,0x2000,2,8,4,2495,0,"Playback_2"));
        assertNull(read(PREFIX+"HDMI200008",2,CLASS,2,0x2000,2,8,4,2495,0,"Playback_2"));
        assertNull(read(PREFIX+"HDMI200008",2,CLASS,0,"wrong primitive",2,8,4,2495,0,"Playback_2"));
        assertNull(read(PREFIX+"HDMI200008",2,CLASS,0,0x2000,2,8,4,2495,0,null));
    }

    @Test public void identityMustMatchItsActualPortAddressAndInputId(){
        assertNull(valid(PREFIX+"HDMI300008",2,0x2000,2,8,4,2495,0,"Playback_2"));
        assertNull(valid(PREFIX+"HDMI200004",2,0x2000,2,8,4,2495,0,"Playback_2"));
        assertNull(valid(PREFIX+"HW1413744384",2,0x2000,2,8,4,2495,0,"Playback_2"));
        assertNull(valid(PREFIX+"HDMI200008",3,0x2000,2,8,4,2495,0,"Playback_2"));
        assertNull(valid(PREFIX+"HDMI300008",2,0x3000,2,8,4,2495,0,"Playback_2"));
        assertNull(valid(PREFIX+"HDMI201008",2,0x2010,2,8,4,2495,0,"Playback_2"));
        assertNull(valid(PREFIX+"HDMIFFFF08",2,0xFFFF,2,8,4,2495,0,"Playback_2"));
        assertNotNull(valid(PREFIX+"HDMI210008",2,0x2100,2,8,4,2495,0,"Playback_2"));
    }

    @Test public void metadataRangesAndUnsafeNamesAreRejected(){
        assertNull(valid(PREFIX+"HDMI20000F",2,0x2000,2,15,4,2495,0,"Playback_2"));
        assertNull(valid(PREFIX+"HDMI200008",2,0x2000,2,8,8,2495,0,"Playback_2"));
        assertNull(valid(PREFIX+"HDMI200008",2,0x2000,2,8,4,-1,0,"Playback_2"));
        assertNull(valid(PREFIX+"HDMI200008",2,0x2000,2,8,4,0x1000000,0,"Playback_2"));
        assertNull(valid(PREFIX+"HDMI200008",2,0x2000,2,8,4,2495,4,"Playback_2"));
        assertNull(valid(PREFIX+"HDMI200008",2,0x2000,2,8,4,2495,-2,"Playback_2"));
        assertNull(valid(PREFIX+"HDMI200008",2,0x2000,2,8,4,2495,0,"Playback_2\n\n"));
        assertNull(valid(PREFIX+"HDMI200008",2,0x2000,2,8,4,2495,0,"Playback_2\u202E"));
        assertNull(valid(PREFIX+"HDMI200008",2,0x2000,2,8,4,2495,0,new String(new char[81])));
    }

    @Test public void outerParcelTailMustMatchParentAndKnownAndroid11Flags(){
        String parent=PREFIX+"HW1413744384";
        assertTrue(HdmiCecMetadata.validEnvelope(parent,parent,0,1));
        assertTrue(HdmiCecMetadata.validEnvelope(parent,parent,1,7));
        assertFalse(HdmiCecMetadata.validEnvelope(parent,PREFIX+"HW1413744640",0,1));
        assertFalse(HdmiCecMetadata.validEnvelope(parent,parent,2,1));
        assertFalse(HdmiCecMetadata.validEnvelope(parent,parent,0,-1));
        assertFalse(HdmiCecMetadata.validEnvelope(parent,parent,0,8));
    }

    @Test public void vendorChangeTriggersAdapterSnapshotUpdateWithoutRenamingSource(){
        HdmiCecMetadata nintendo=valid(PREFIX+"HDMI200008",2,0x2000,2,8,4,2495,0,"Playback_2");
        HdmiCecMetadata unknown=valid(PREFIX+"HDMI200008",2,0x2000,2,8,4,0xFFFFFF,0,"Playback_2");
        HdmiInputs.Port before=new HdmiInputs.Port(2,0,"Playback_2","Playback_2","root",PREFIX+"HDMI200008",unknown);
        HdmiInputs.Port after=new HdmiInputs.Port(2,0,"Playback_2","Playback_2","root",PREFIX+"HDMI200008",nintendo);
        assertNotEquals(before,after);
        assertEquals(HdmiDeviceKind.UNKNOWN,before.deviceKind);
        assertEquals(HdmiDeviceKind.SWITCH,after.deviceKind);
    }

    private static HdmiCecMetadata valid(String id,int expectedPort,int physical,int port,int logical,int type,int vendor,int power,String name){
        return HdmiCecMetadata.validated(id,expectedPort,physical,port,logical,type,vendor,power,name);
    }
    private static HdmiCecMetadata read(String id,int port,Object... fields){
        return HdmiCecMetadata.readCec(new HdmiCecMetadata.Fields(){
            int index;
            @Override public int readInt(){return (Integer)fields[index++];}
            @Override public String readString(){return (String)fields[index++];}
        },id,port);
    }
}
