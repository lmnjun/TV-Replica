package com.gathin.tvhome;

import org.junit.Test;
import static org.junit.Assert.*;

public class AmbientMathTest {
    @Test public void wmoConditionsChooseMatchingVisuals() {
        assertEquals(AmbientMath.CLEAR, AmbientMath.weatherKind(2));
        assertEquals(AmbientMath.CLOUD, AmbientMath.weatherKind(3));
        assertEquals(AmbientMath.FOG, AmbientMath.weatherKind(48));
        assertEquals(AmbientMath.RAIN, AmbientMath.weatherKind(57));
        assertEquals(AmbientMath.RAIN, AmbientMath.weatherKind(82));
        assertEquals(AmbientMath.SNOW, AmbientMath.weatherKind(86));
        assertEquals(AmbientMath.STORM, AmbientMath.weatherKind(99));
    }
    @Test public void unknownCodesDoNotInventConditions() {
        assertEquals(AmbientMath.UNKNOWN, AmbientMath.weatherKind(-1));
        assertEquals(AmbientMath.UNKNOWN, AmbientMath.weatherKind(13));
        assertEquals(AmbientMath.UNKNOWN, AmbientMath.weatherKind(100));
    }
    @Test public void invalidCoordinatesDoNotCreateLocationMarker() {
        assertTrue(Double.isNaN(AmbientMath.coordinate("NaN", 90)));
        assertTrue(Double.isNaN(AmbientMath.coordinate("Infinity", 90)));
        assertTrue(Double.isNaN(AmbientMath.coordinate("90.1", 90)));
        assertTrue(Double.isNaN(AmbientMath.coordinate("", 180)));
        assertEquals(-180, AmbientMath.coordinate("-180", 180), 0);
    }
    @Test public void savedLocationProjectsToGlobeCentre() {
        double latitude = 3.139, longitude = 101.6869;
        float[] basis = new float[9]; AmbientMath.cameraBasis(latitude, longitude, basis);
        double lat = Math.toRadians(latitude), lon = Math.toRadians(longitude);
        double x = Math.cos(lat) * Math.sin(lon), y = Math.sin(lat), z = Math.cos(lat) * Math.cos(lon);
        assertEquals(0, basis[0] * x + basis[3] * y + basis[6] * z, .000001);
        assertEquals(0, basis[1] * x + basis[4] * y + basis[7] * z, .000001);
        assertEquals(1, basis[2] * x + basis[5] * y + basis[8] * z, .000001);
    }
    @Test public void poleCameraStillHasOrthonormalAxes() {
        float[] b = new float[9]; AmbientMath.cameraBasis(90, -180, b);
        for (int axis = 0; axis < 3; axis++) {
            double length = 0; for (int component = 0; component < 3; component++) length += b[axis + component * 3] * b[axis + component * 3];
            assertEquals(1, length, .000001);
        }
    }
    @Test public void solarVectorIsFiniteAndNormalised() {
        float[] sun = new float[3]; AmbientMath.sunVector(1_798_502_400_000L, sun);
        assertEquals(1, sun[0] * sun[0] + sun[1] * sun[1] + sun[2] * sun[2], .000001);
        for (float component : sun) assertTrue(Float.isFinite(component));
    }
}
