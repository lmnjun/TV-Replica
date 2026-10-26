package com.gathin.tvhome;

import org.junit.Test;
import static org.junit.Assert.*;

public class EarthCameraTest {
    @Test public void defaultDistanceKeepsTheExistingSilhouetteAndFacesTheSavedLocation() {
        EarthCamera.State view = EarthCamera.create(3.139, 101.6869, 1, 0, 0);
        assertEquals(6, view.distance, 0);
        assertEquals(.66, view.radiusY(), .000001);
        assertEquals(0, view.markerX(16f / 9), .000001);
        assertEquals(EarthCamera.CENTER_Y, view.markerY(), .000001);
        assertTrue(view.markerVisible(16f / 9));
    }

    @Test public void zoomChangesRealCameraDistanceWithAPerspectiveSilhouette() {
        EarthCamera.State far = EarthCamera.create(0, 0, .65f, 0, 0);
        EarthCamera.State standard = EarthCamera.create(0, 0, 1, 0, 0);
        EarthCamera.State near = EarthCamera.create(0, 0, 1.6f, 0, 0);
        assertEquals(6f / .65f, far.distance, .000001);
        assertEquals(6f / 1.6f, near.distance, .000001);
        assertTrue(far.radiusY() < standard.radiusY());
        assertTrue(standard.radiusY() < near.radiusY());
        // A distance camera differs from enlarging an orthographic image by a constant scale.
        assertNotEquals(standard.radiusY() * 1.6f, near.radiusY(), .001);
        assertTrue(near.distance - 1.022f > EarthCamera.NEAR);
        assertTrue(far.distance + 1.022f < EarthCamera.FAR);
    }

    @Test public void turningTheCameraProjectsTheSameGeographicPointOffCentre() {
        EarthCamera.State view = EarthCamera.create(0, 0, 1, 45, 0);
        double diagonal = Math.sqrt(.5);
        assertEquals(-diagonal, view.markerViewX, .000001);
        assertEquals(0, view.markerViewY, .000001);
        assertEquals(diagonal, view.markerViewZ, .000001);
        assertEquals(-diagonal * EarthCamera.FOCAL_Y / ((16.0 / 9) * (6 - diagonal)),
                view.markerX(16f / 9), .000001);
        assertTrue(view.markerVisible(16f / 9));
    }

    @Test public void aLocationBeyondThePerspectiveHorizonIsHiddenEvenWithPositiveViewZ() {
        EarthCamera.State horizon = EarthCamera.create(0, 0, 1, 81, 0);
        assertTrue(horizon.markerViewZ > 0);
        assertFalse(horizon.markerVisible(16f / 9));
        EarthCamera.State rear = EarthCamera.create(0, 0, 1, 180, 0);
        assertFalse(rear.markerVisible(16f / 9));
        assertTrue(EarthCamera.create(0, 0, 1, 79, 0).markerVisible(16f / 9));
    }

    @Test public void visibleLocationIsTheNearRaySphereIntersection() {
        for (float yaw : new float[]{0, 30, 79, 81, 120, 180}) {
            EarthCamera.State view = EarthCamera.create(0, 0, 1, yaw, 0);
            double x = view.markerViewX, y = view.markerViewY, z = view.markerViewZ;
            double length = Math.sqrt(x * x + y * y + (z - view.distance) * (z - view.distance));
            double directionZ = (z - view.distance) / length;
            double b = view.distance * directionZ;
            double nearIntersection = -b - Math.sqrt(Math.max(0, b * b - (view.distance * view.distance - 1)));
            if (view.markerVisible(16f / 9)) assertEquals(length, nearIntersection, .00001);
            else assertTrue(length > nearIntersection + .001);
        }
    }

    @Test public void pitchIsRelativeToLocationAndPolarCamerasStayFinite() {
        EarthCamera.State view = EarthCamera.create(30, 170, 1, 45, 70);
        assertEquals(85, view.latitude, 0);
        assertEquals(-145, view.longitude, 0);
        EarthCamera.State pole = EarthCamera.create(90, 180, Float.NaN, Float.POSITIVE_INFINITY, Float.NaN);
        assertEquals(6, pole.distance, 0);
        assertEquals(85, pole.latitude, 0);
        assertEquals(-180, pole.longitude, 0);
        assertTrue(pole.markerVisible(16f / 9));
        for (float value : pole.basis) assertTrue(Float.isFinite(value));
    }

    @Test public void invalidOrOffscreenProjectionNeverCreatesAnAnnotation() {
        EarthCamera.State view = EarthCamera.create(0, 0, 1.6f, 0, 60);
        assertFalse(view.markerVisible(Float.NaN));
        assertFalse(view.markerVisible(0));
        // Narrow aspect puts an otherwise front-facing point outside the viewport.
        EarthCamera.State side = EarthCamera.create(0, 0, 1, 45, 0);
        assertFalse(side.markerVisible(.1f));
    }
}
