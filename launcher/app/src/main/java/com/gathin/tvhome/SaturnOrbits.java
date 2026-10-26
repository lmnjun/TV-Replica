package com.gathin.tvhome;

/** Two-body display model, JPL mean elements at J2000; not a precision ephemeris.
 * Local Laplace planes are approximated by Saturn's equatorial plane. Distances are compressed in the renderer.
 * Source: https://ssd.jpl.nasa.gov/sats/elem/sep.html (SAT441 / SAT415, accessed 2026-10-23).
 */
final class SaturnOrbits {
    static final String[] NAMES={"Mimas","Enceladus","Tethys","Dione","Rhea","Titan","Hyperion","Iapetus","Phoebe","Janus","Epimetheus","Helene","Telesto","Calypso","Atlas","Prometheus","Pandora"};
    // a km, e, argument of periapsis deg, mean anomaly deg, inclination deg, node deg, period days.
    static final double[][] ELEMENTS={
        {186000,.020,160.4,275.3,1.6,66.2,.942422},
        {238400,.005,119.5,57,0,0,1.370218},
        {295000,.001,335.3,0,1.1,273,1.887802},
        {377700,.002,116,212,0,0,2.736916},
        {527200,.001,44.3,31.5,.3,133.7,4.517503},
        {1221900,.029,78.3,11.7,.3,78.6,15.945448},
        {1481500,.105,214,122.9,.6,87.1,21.276658},
        {3561700,.028,254.5,74.8,7.6,86.5,79.331002},
        {12929400,.164,240.3,308,175.2,192.7,550.303910},
        {151500,.007,11.1,111.7,.2,159.9,.697353},
        {151400,.020,96.3,197.2,.3,189.8,.697012},
        {377600,.007,194.3,-64.5,.2,260.8,2.736916},
        {295000,.001,66.2,360,1.2,242.1,1.887802},
        {295000,.001,221.6,0,1.5,326.6,1.887803},
        {137700,.001,82.3,289.1,0,0,.604602},
        {139400,.002,341.9,135.4,0,0,.615878},
        {141700,.004,217.9,123.9,0,0,.631369}
    };
    static final double[] RADII={198.2,252.1,531.1,561.4,763.5,2574.8,135,734.5,106.5,89.5,58.1,17.6,12.4,10.7,15.1,43.1,40.6};
    static double eccentricAnomaly(double mean, double e) {
        double m=Math.atan2(Math.sin(mean),Math.cos(mean)), x=m;
        for(int n=0;n<12;n++){double d=(x-e*Math.sin(x)-m)/(1-e*Math.cos(x));x-=d;if(Math.abs(d)<1e-12)break;}
        return x;
    }
    static double[] position(int moon, double days, boolean compressed) {
        double[] p=ELEMENTS[moon];
        double a=compressed ? 2.45+1.35*Math.log(p[0]/133600.0) : p[0];
        double e=p[1], E=eccentricAnomaly(Math.toRadians(p[3])+2*Math.PI*days/p[6],e);
        double x=a*(Math.cos(E)-e),z=a*Math.sqrt(1-e*e)*Math.sin(E);
        double w=Math.toRadians(p[2]),i=Math.toRadians(p[4]),o=Math.toRadians(p[5]);
        double u=x*Math.cos(w)-z*Math.sin(w),v=x*Math.sin(w)+z*Math.cos(w);
        return new double[]{u*Math.cos(o)-v*Math.cos(i)*Math.sin(o),v*Math.sin(i),u*Math.sin(o)+v*Math.cos(i)*Math.cos(o)};
    }
}
