package com.gathin.tvhome;

/** Unit-sphere camera and saved-location projection, shared by the GL and caption layers. */
final class EarthCamera {
    static final float DEFAULT_DISTANCE = 6f;
    static final float CENTER_Y = .08f;
    static final float DEFAULT_RADIUS_Y = .66f;
    // A unit sphere's perspective silhouette has radius focal / sqrt(distance^2 - 1).
    static final float FOCAL_Y = DEFAULT_RADIUS_Y * (float)Math.sqrt(DEFAULT_DISTANCE * DEFAULT_DISTANCE - 1);
    static final float NEAR = .1f, FAR = 32f;
    static final float CLIP_A = (FAR + NEAR) / (FAR - NEAR);
    static final float CLIP_B = 2 * FAR * NEAR / (FAR - NEAR);

    private EarthCamera() { }

    static State create(double latitude, double longitude, float zoom, float yaw, float pitch) {
        double savedLatitude = Double.isFinite(latitude) ? clamp(latitude, -90, 90) : 18;
        double savedLongitude = Double.isFinite(longitude) ? wrap(longitude) : 25;
        float safeZoom = Float.isFinite(zoom) ? (float)clamp(zoom, .65, 1.6) : 1;
        float safeYaw = Float.isFinite(yaw) ? (float)wrap(yaw) : 0;
        float safePitch = Float.isFinite(pitch) ? (float)clamp(pitch, -70, 70) : 0;
        double cameraLatitude = clamp(savedLatitude + safePitch, -85, 85);
        double cameraLongitude = wrap(savedLongitude + safeYaw);
        float[] basis = new float[9];
        AmbientMath.cameraBasis(cameraLatitude, cameraLongitude, basis);
        double lat = Math.toRadians(savedLatitude), lon = Math.toRadians(savedLongitude);
        double x = Math.cos(lat) * Math.sin(lon), y = Math.sin(lat), z = Math.cos(lat) * Math.cos(lon);
        return new State(DEFAULT_DISTANCE / safeZoom, cameraLatitude, cameraLongitude, basis,
                (float)(basis[0] * x + basis[3] * y + basis[6] * z),
                (float)(basis[1] * x + basis[4] * y + basis[7] * z),
                (float)(basis[2] * x + basis[5] * y + basis[8] * z));
    }

    static final class State {
        final float distance;
        private final float radius;
        final double latitude, longitude;
        final float[] basis;
        final float markerViewX, markerViewY, markerViewZ;

        private State(float distance, double latitude, double longitude, float[] basis,
                      float markerViewX, float markerViewY, float markerViewZ) {
            this.distance = distance; this.latitude = latitude; this.longitude = longitude;
            radius = FOCAL_Y / (float)Math.sqrt(distance * distance - 1);
            this.basis = basis; this.markerViewX = markerViewX;
            this.markerViewY = markerViewY; this.markerViewZ = markerViewZ;
        }

        float radiusY() { return radius; }
        float markerX(float aspect) { return markerViewX * FOCAL_Y / (aspect * (distance - markerViewZ)); }
        float markerY() { return CENTER_Y + markerViewY * FOCAL_Y / (distance - markerViewZ); }

        boolean markerVisible(float aspect) {
            // n . (eye - surfacePoint) > 0. Merely testing view-space z > 0
            // would incorrectly expose locations beyond a perspective sphere's horizon.
            if (!Float.isFinite(aspect) || aspect <= 0 || distance * markerViewZ <= 1.00001f) return false;
            return Math.abs(markerX(aspect)) <= 1 && Math.abs(markerY()) <= 1;
        }
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
    private static double wrap(double angle) {
        double normalized = angle % 360;
        if (normalized < -180) normalized += 360;
        if (normalized >= 180) normalized -= 360;
        return normalized;
    }
}
