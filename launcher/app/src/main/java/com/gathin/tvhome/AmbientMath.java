package com.gathin.tvhome;

/** Scene mathematics kept independent of Android for deterministic checks. */
final class AmbientMath {
    static final int CLEAR = 0, CLOUD = 1, FOG = 2, RAIN = 3, SNOW = 4, STORM = 5, UNKNOWN = 6;
    private AmbientMath() { }

    static int weatherKind(int code) {
        if (code >= 0 && code <= 2) return CLEAR;
        if (code == 3) return CLOUD;
        if (code == 45 || code == 48) return FOG;
        if (code == 51 || code == 53 || code == 55 || code == 56 || code == 57
                || code == 61 || code == 63 || code == 65 || code == 66 || code == 67
                || code == 80 || code == 81 || code == 82) return RAIN;
        if (code == 71 || code == 73 || code == 75 || code == 77 || code == 85 || code == 86) return SNOW;
        if (code == 95 || code == 96 || code == 99) return STORM;
        return UNKNOWN;
    }

    static double coordinate(String value, double maximum) {
        try {
            double number = Double.parseDouble(value);
            return Double.isFinite(number) && Math.abs(number) <= maximum ? number : Double.NaN;
        } catch (RuntimeException invalid) { return Double.NaN; }
    }

    /** Column-major matrix: east, north and local outward normal become the camera axes. */
    static void cameraBasis(double latitude, double longitude, float[] out) {
        double lat = Math.toRadians(latitude), lon = Math.toRadians(longitude);
        float sl = (float)Math.sin(lon), cl = (float)Math.cos(lon);
        float sp = (float)Math.sin(lat), cp = (float)Math.cos(lat);
        out[0] = cl; out[1] = -sp * sl; out[2] = cp * sl;
        out[3] = 0; out[4] = cp; out[5] = sp;
        out[6] = -sl; out[7] = -sp * cl; out[8] = cp * cl;
    }

    /** Approximate solar declination/UTC subsolar longitude, adequate for a visual terminator. */
    static void sunVector(long millis, float[] out) {
        java.time.ZonedDateTime utc = java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneOffset.UTC);
        double declination = Math.toRadians(-23.44 * Math.cos(2 * Math.PI * (utc.getDayOfYear() + 10) / 365.2422));
        double hour = utc.getHour() + utc.getMinute() / 60.0 + utc.getSecond() / 3600.0;
        double lon = Math.toRadians((12 - hour) * 15);
        out[0] = (float)(Math.cos(declination) * Math.sin(lon));
        out[1] = (float)Math.sin(declination);
        out[2] = (float)(Math.cos(declination) * Math.cos(lon));
    }
}
