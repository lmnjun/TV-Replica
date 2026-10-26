package com.gathin.tvhome;

import org.junit.Test;
import static org.junit.Assert.*;

public final class HdmiDeviceKindTest {
    @Test public void namesAreCaseInsensitive(){
        assertEquals(HdmiDeviceKind.SWITCH,HdmiDeviceKind.detect("Nintendo Switch OLED"));
        assertEquals(HdmiDeviceKind.SWITCH,HdmiDeviceKind.detect("  sWiTcH 2  "));
        assertEquals(HdmiDeviceKind.XBOX,HdmiDeviceKind.detect("xBoX Series X"));
        assertEquals(HdmiDeviceKind.PLAYSTATION,HdmiDeviceKind.detect("PLAYSTATION 5"));
        assertEquals(HdmiDeviceKind.PLAYSTATION,HdmiDeviceKind.detect("PS5 Pro"));
        assertEquals(HdmiDeviceKind.APPLE_TV,HdmiDeviceKind.detect("AppleTV4K"));
    }
    @Test public void gpuNamesAreOnlyClassifiedWhenActuallySupplied(){
        for(String name:new String[]{"NVIDIA GeForce RTX 4070","Intel(R) Graphics","AMD Radeon","PC","MacBook Pro","Mac mini"})
            assertEquals(name,HdmiDeviceKind.PC,HdmiDeviceKind.detect(name));
        assertEquals(HdmiDeviceKind.UNKNOWN,HdmiDeviceKind.detect(null));
        assertEquals(HdmiDeviceKind.UNKNOWN,HdmiDeviceKind.detect(""));
        assertEquals(HdmiDeviceKind.UNKNOWN,HdmiDeviceKind.detect("HDMI 2"));
    }
    @Test public void genericTermsAndPartialBrandsAreNotDeviceEvidence(){
        for(String name:new String[]{"HDMI Switch","Network switch","Switchboard","Nintendo Wii","NVIDIA SHIELD","SHIELD TV","Apple","Xboxy","PS50","Intelab","AMDocs","Console","Chromecast","Roku"})
            assertEquals(name,HdmiDeviceKind.UNKNOWN,HdmiDeviceKind.detect(name));
    }
    @Test public void eachConsoleHasItsOwnKind(){
        assertNotEquals(HdmiDeviceKind.detect("Nintendo Switch"),HdmiDeviceKind.detect("Xbox"));
        assertNotEquals(HdmiDeviceKind.detect("Xbox"),HdmiDeviceKind.detect("PlayStation"));
        assertNotEquals(HdmiDeviceKind.detect("Apple TV"),HdmiDeviceKind.detect("PC"));
    }
    @Test public void vendorFallbackOnlyIdentifiesGenericPlaybackSources(){
        HdmiCecMetadata nintendo=metadata(8,4,0x0009BF,"Playback_2");
        assertEquals(HdmiDeviceKind.SWITCH,HdmiDeviceKind.detect("Playback_2",nintendo));
        assertEquals(HdmiDeviceKind.SWITCH,HdmiDeviceKind.detect("",nintendo));
        assertEquals(HdmiDeviceKind.UNKNOWN,HdmiDeviceKind.detect("Nintendo Wii",nintendo));
        assertEquals(HdmiDeviceKind.UNKNOWN,HdmiDeviceKind.detect("Wii U",nintendo));
        assertEquals(HdmiDeviceKind.UNKNOWN,HdmiDeviceKind.detect("HDMI Switch",nintendo));
        assertEquals(HdmiDeviceKind.UNKNOWN,HdmiDeviceKind.detect("Unknown console",nintendo));
        assertEquals(HdmiDeviceKind.PC,HdmiDeviceKind.detect("NVIDIA GeForce",nintendo));
        assertEquals(HdmiDeviceKind.XBOX,HdmiDeviceKind.detect("Xbox",nintendo));
        assertEquals(HdmiDeviceKind.UNKNOWN,HdmiDeviceKind.detect("Playback_2",metadata(8,4,0x0009BF,"Nintendo Wii U")));
        assertEquals(HdmiDeviceKind.UNKNOWN,HdmiDeviceKind.detect("Playback_2",metadata(8,5,0x0009BF,"Playback_2")));
        assertEquals(HdmiDeviceKind.UNKNOWN,HdmiDeviceKind.detect("Playback_2",metadata(5,4,0x0009BF,"Playback_2")));
        assertEquals(HdmiDeviceKind.UNKNOWN,HdmiDeviceKind.detect("Playback_2",metadata(8,4,0xFFFFFF,"Playback_2")));
        assertEquals(HdmiDeviceKind.APPLE_TV,HdmiDeviceKind.detect("Playback_2",metadata(8,4,0x0010FA,"Playback_2")));
        assertEquals(HdmiDeviceKind.PC,HdmiDeviceKind.detect("Mac mini",metadata(8,4,0x0010FA,"Playback_2")));
        assertEquals(HdmiDeviceKind.UNKNOWN,HdmiDeviceKind.detect("Playback_2",metadata(8,5,0x0010FA,"Playback_2")));
    }
    private static HdmiCecMetadata metadata(int logical,int type,int vendor,String name){
        return HdmiCecMetadata.validated(String.format("service/HDMI2000%02X",logical),2,0x2000,2,logical,type,vendor,0,name);
    }
}
