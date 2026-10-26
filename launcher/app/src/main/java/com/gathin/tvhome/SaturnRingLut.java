package com.gathin.tvhome;

/** Bounded approximation of the radial material; original analytic GLSL is also its bake source. */
final class SaturnRingLut {
    static final int WIDTH=2048,HEIGHT=512;
    static final double MIN_R=1.24,MAX_R=2.30,MAX_PX=.128,CUTOFF=.02,CUTOFF_GUARD=.004;
    private SaturnRingLut() { }

    static String analyticGlsl(){return
            "float division(float r,float center,float width,float strength,float px){float filtered=sqrt(width*width+px*px*.25);float q=(r-center)/filtered;return 1.-strength*(width/filtered)*exp(-q*q);}"
            +"float analyticRings(float r,float px){float f1=175.*px;float bands=.68+.16*sin(r*175.)*exp(-f1*f1);float f2=490.*px;bands+=.065*sin(r*490.)*exp(-f2*f2);float cassini=division(r,1.965,.027,.96,px);float encke=division(r,2.22,.006,.70,px);return bands*cassini*encke*smoothstep(1.24,1.24+max(.04,px),r)*(1.-smoothstep(2.30-max(.03,px),2.30,r));}";}

    static String ringFunction(boolean lookup){
        if(!lookup)return "float rings(float r){return analyticRings(r,footprint(r,1.));}";
        return "uniform highp sampler2D uRingLut;float rings(float r){float px=footprint(r,1.);"
                +"if(r<1.24||r>2.30||px<0.||px>.128)return analyticRings(r,px);"
                +"vec2 uv=vec2(((r-1.24)/1.06*2047.+.5)/2048.,(px/.128*511.+.5)/512.);"
                +"vec2 channels=texture2D(uRingLut,uv).rg;float density=(channels.r*256.+channels.g)/257.;"
                +"if(abs(density-.02)<.004)return analyticRings(r,px);return density;}";
    }

    static String bakeFragment(){return "precision highp float;"+analyticGlsl()
            +"void main(){vec2 cell=gl_FragCoord.xy-vec2(.5);float r=1.24+cell.x*(1.06/2047.);float px=cell.y*(.128/511.);"
            +"float value=clamp(analyticRings(r,px),0.,1.);float encoded=floor(value*65535.+.5);float hi=floor(encoded/256.);"
            +"gl_FragColor=vec4(hi,encoded-hi*256.,0.,255.)/255.;}";}

    /** Double-precision oracle for the interpolation/packing error tests, not the runtime bake. */
    static double analytic(double r,double px){
        double f1=175*px,f2=490*px;
        double bands=.68+.16*Math.sin(r*175)*Math.exp(-f1*f1)+.065*Math.sin(r*490)*Math.exp(-f2*f2);
        return bands*division(r,1.965,.027,.96,px)*division(r,2.22,.006,.70,px)
                *smooth(1.24,1.24+Math.max(.04,px),r)*(1-smooth(2.30-Math.max(.03,px),2.30,r));
    }
    private static double division(double r,double center,double width,double strength,double px){
        double filtered=Math.sqrt(width*width+px*px*.25),q=(r-center)/filtered;
        return 1-strength*(width/filtered)*Math.exp(-q*q);
    }
    private static double smooth(double low,double high,double value){
        double x=Math.max(0,Math.min(1,(value-low)/(high-low)));return x*x*(3-2*x);
    }
    static int encode(double value){return (int)Math.floor(Math.max(0,Math.min(1,value))*65535+.5);}
    static double decode(int high,int low){return (high*256+low)/65535.0;}
    static double textureCoordinate(double position,int dimension){return (position*(dimension-1)+.5)/dimension;}
    static boolean useAnalytic(double r,double px,double density){
        return r<MIN_R||r>MAX_R||px<0||px>MAX_PX||Math.abs(density-CUTOFF)<CUTOFF_GUARD;
    }
}
