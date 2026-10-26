package com.gathin.tvhome;

import org.junit.Test;
import static org.junit.Assert.*;

public class SaturnSurfaceSizeTest {
    @Test public void fullHdAnd4kWallpaperUseSameBudgetWithoutChangingViewSize() {
        assertSize(1280,720,1920,1080);
        assertSize(1280,720,3840,2160);
        assertSize(1280,720,1600,900);
    }
    @Test public void smallDisplaysAreNeverUpscaledAndOtherAspectsStayBounded() {
        assertSize(1280,720,1280,720);
        assertSize(640,480,640,480);
        assertSize(1280,540,2560,1080);
        assertSize(720,720,1000,1000);
        assertSize(405,720,1080,1920);
        assertSize(1,1,1,1);
    }
    @Test public void anyPositiveViewFitsBothLimitsWithAtMostOnePixelAspectRounding() {
        for(int width:new int[]{1,320,853,1280,1600,1920,2560,3840,7680})
            for(int height:new int[]{1,240,480,720,900,1080,1440,2160,4320}){
                SaturnSurfaceSize.Size size=SaturnSurfaceSize.fit(width,height);
                assertTrue(size.width>0&&size.width<=Math.min(width,1280));
                assertTrue(size.height>0&&size.height<=Math.min(height,720));
                // Extreme one-pixel views have an unavoidable minimum-size rounding.
                assertTrue(Math.abs(size.width*(long)height-size.height*(long)width)<=Math.max(width,height));
            }
    }
    @Test public void unmeasuredOrDetachedViewDoesNotRequestABuffer(){
        assertNull(SaturnSurfaceSize.fit(0,1080));assertNull(SaturnSurfaceSize.fit(1920,0));
        assertNull(SaturnSurfaceSize.fit(-1,1080));assertNull(SaturnSurfaceSize.fit(1920,-1));
    }
    private static void assertSize(int expectedWidth,int expectedHeight,int viewWidth,int viewHeight){
        SaturnSurfaceSize.Size actual=SaturnSurfaceSize.fit(viewWidth,viewHeight);
        assertEquals(expectedWidth,actual.width);assertEquals(expectedHeight,actual.height);
    }
}
