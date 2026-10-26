package com.gathin.tvhome;

import java.util.Random;
import org.junit.Test;
import static org.junit.Assert.*;

public class SaturnRingLutTest {
    @Test public void packedChannelsAndEndpointTexelCentersAreExact() {
        for(int value=0;value<=65535;value++)
            assertEquals(value/65535.0,SaturnRingLut.decode(value>>>8,value&255),0);
        for(int size:new int[]{SaturnRingLut.WIDTH,SaturnRingLut.HEIGHT}){
            assertEquals(.5/size,SaturnRingLut.textureCoordinate(0,size),0);
            assertEquals((size-.5)/size,SaturnRingLut.textureCoordinate(1,size),0);
        }
    }

    @Test public void bilinearLookupStaysUnderOne8BitStepIncludingConservativeChannelRounding() {
        int[] nodes=nodes();double maxIdeal=0,maxConservative=0;
        Random random=new Random(531960);
        // Every cell center plus stratified positions checks the whole radial/footprint domain.
        for(int y=0;y<SaturnRingLut.HEIGHT-1;y++)for(int x=0;x<SaturnRingLut.WIDTH-1;x++){
            for(int sample=0;sample<2;sample++){
                double fx=sample==0?.5:random.nextDouble(),fy=sample==0?.5:random.nextDouble();
                double r=SaturnRingLut.MIN_R+(x+fx)*(SaturnRingLut.MAX_R-SaturnRingLut.MIN_R)/(SaturnRingLut.WIDTH-1);
                double px=(y+fy)*SaturnRingLut.MAX_PX/(SaturnRingLut.HEIGHT-1);
                double exact=SaturnRingLut.analytic(r,px),ideal=sample(nodes,x,y,fx,fy,false),conservative=sample(nodes,x,y,fx,fy,true);
                maxIdeal=Math.max(maxIdeal,Math.abs(ideal-exact));maxConservative=Math.max(maxConservative,Math.abs(conservative-exact));
                if(!SaturnRingLut.useAnalytic(r,px,conservative))
                    assertEquals("The guarded cutoff cannot change coverage",exact<.02,conservative<.02);
            }
        }
        assertTrue("Ideal interpolation error="+maxIdeal,maxIdeal<1.0/255);
        assertTrue("Conservative filtered channel error="+maxConservative,maxConservative<1.0/255);
    }

    @Test public void allOutOfDomainAndUncertainCutoffSamplesRetainAnalyticMaterial() {
        assertTrue(SaturnRingLut.useAnalytic(1.23,.01,.5));
        assertTrue(SaturnRingLut.useAnalytic(2.31,.01,.5));
        assertTrue(SaturnRingLut.useAnalytic(1.8,-.001,.5));
        assertTrue(SaturnRingLut.useAnalytic(1.8,.129,.5));
        assertTrue(SaturnRingLut.useAnalytic(1.8,.01,.017));
        assertTrue(SaturnRingLut.useAnalytic(1.8,.01,.023));
        assertFalse(SaturnRingLut.useAnalytic(1.8,.01,.5));
    }

    @Test public void bakeAndBothLiveProgramsShareAnalyticSourceAndHighPrecisionDecode() {
        String analytic=SaturnRingLut.analyticGlsl();
        assertTrue(SaturnRingLut.bakeFragment().contains(analytic));
        assertFalse("Raw px=0 must remain a bake node",SaturnRingLut.bakeFragment().contains(".0002"));
        for(boolean derivatives:new boolean[]{false,true})for(boolean opaque:new boolean[]{false,true}){
            String shader=SaturnScene.lookupFragment(derivatives,opaque);
            assertTrue(shader.contains(analytic));
            assertTrue(shader.contains("uniform highp sampler2D uRingLut"));
            assertTrue(shader.contains("(channels.r*256.+channels.g)/257."));
            assertTrue(shader.contains("abs(density-.02)<.004"));
            assertEquals(!opaque,shader.contains("discard"));
        }
    }

    private static int[] nodes(){
        int[] nodes=new int[SaturnRingLut.WIDTH*SaturnRingLut.HEIGHT];
        for(int y=0;y<SaturnRingLut.HEIGHT;y++)for(int x=0;x<SaturnRingLut.WIDTH;x++)
            nodes[y*SaturnRingLut.WIDTH+x]=SaturnRingLut.encode(SaturnRingLut.analytic(
                    SaturnRingLut.MIN_R+x*(SaturnRingLut.MAX_R-SaturnRingLut.MIN_R)/(SaturnRingLut.WIDTH-1),
                    y*SaturnRingLut.MAX_PX/(SaturnRingLut.HEIGHT-1)));
        return nodes;
    }
    private static double sample(int[] nodes,int x,int y,double fx,double fy,boolean conservative){
        if(conservative){fx=Math.floor(fx*256+.5)/256;fy=Math.floor(fy*256+.5)/256;}
        int a=nodes[y*SaturnRingLut.WIDTH+x],b=nodes[y*SaturnRingLut.WIDTH+x+1];
        int c=nodes[(y+1)*SaturnRingLut.WIDTH+x],d=nodes[(y+1)*SaturnRingLut.WIDTH+x+1];
        double high=mix(mix(a>>>8,b>>>8,fx),mix(c>>>8,d>>>8,fx),fy);
        double low=mix(mix(a&255,b&255,fx),mix(c&255,d&255,fx),fy);
        if(conservative){high=Math.floor(high+.5);low=Math.floor(low+.5);}
        return (high*256+low)/65535;
    }
    private static double mix(double a,double b,double t){return a+(b-a)*t;}
}
