package com.gathin.tvhome;

import org.junit.Test;
import static org.junit.Assert.*;

/** App focus may not switch a second, visibly softer material into the running wallpaper. */
public class SaturnMaterialStabilityTest {
    @Test public void opaqueVariantKeepsExactPlanetMoonAndShadowMathWithoutDiscard() {
        for (boolean derivatives : new boolean[]{false, true}) {
            String mixed = SaturnScene.fragment(derivatives), opaque = SaturnScene.opaqueFragment(derivatives);
            assertFalse("Opaque coverage must not contain a discard path", opaque.contains("discard"));
            assertFalse(opaque.contains("if(uKind==1)"));
            assertFalse(opaque.contains("if(uKind==3"));
            assertTrue("Blended ring keeps its original cutout", mixed.contains("if(density<.02)discard"));
            assertEquals("All filtering and ring-shadow functions are shared exactly",
                    mixed.substring(0, mixed.indexOf("void main()")), opaque.substring(0, opaque.indexOf("void main()")));
            assertEquals("Planet and moon material expressions must remain byte-identical",
                    mixed.substring(mixed.indexOf("if(uKind==2)")), opaque.substring(opaque.indexOf("if(uKind==2)")));
            assertTrue("Opaque planet still receives detailed ring shadows", opaque.contains("shadow=1.-rings(r)*.72"));
        }
    }

    @Test public void navigationCannotChangeEitherShaderVariant(){
        for(boolean derivatives:new boolean[]{false,true}){
            String material=SaturnScene.fragment(derivatives);
            assertFalse("Navigation must never select a softer shader",material.contains("uNavigationMotion"));
            assertTrue("Fine ring structure remains present",material.contains("sin(r*490.)"));
            assertTrue("Planet's middle-frequency bands remain present",material.contains("wave(lat*120."));
            assertTrue("Planet's fine bands remain present",material.contains("wave(lat*300."));
            assertTrue("Moon surface detail remains present",material.contains("wave(vLocal.x*53.,53.)"));
            assertTrue("Ring divisions remain pixel-filtered",material.contains("sqrt(width*width+px*px*.25)"));
        }
    }

    @Test public void bothDerivativeAndViewportFilteringRemainAvailable(){
        assertTrue(SaturnScene.fragment(true).contains("GL_OES_standard_derivatives"));
        assertTrue(SaturnScene.fragment(true).contains("fwidth(value)"));
        assertFalse(SaturnScene.fragment(false).contains("fwidth(value)"));
        assertTrue(SaturnScene.fragment(false).contains("uPixelFootprint*frequency"));
    }
}
