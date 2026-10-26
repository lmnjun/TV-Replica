package com.gathin.tvhome;

import android.content.SharedPreferences;
import org.json.JSONObject;

/** Read-only cached weather/location; this renderer never requests a permission or fetches data. */
final class AmbientSnapshot {
    final int code, kind;
    final boolean day, weatherAvailable, hasLocation;
    final double latitude, longitude;
    final String city;
    final long fetched;

    AmbientSnapshot(SharedPreferences prefs) {
        synchronized (WeatherCache.LOCK) {
        latitude = AmbientMath.coordinate(prefs.getString("latitude", ""), 90);
        longitude = AmbientMath.coordinate(prefs.getString("longitude", ""), 180);
        hasLocation = Double.isFinite(latitude) && Double.isFinite(longitude);
        String saved = prefs.getString("city", "");
        city = saved == null || saved.trim().isEmpty() ? "Saved location" : saved.trim();
        fetched = prefs.getLong("fetched", 0);
        int readCode = -1; boolean readDay = false;
        try {
            JSONObject current = new JSONObject(prefs.getString("json", "")).getJSONObject("current");
            readCode = current.optInt("weather_code", current.optInt("code", -1));
            readDay = current.optInt("is_day", 0) == 1;
        } catch (Exception unavailable) { }
        code = readCode; day = readDay; kind = AmbientMath.weatherKind(code);
        weatherAvailable = kind != AmbientMath.UNKNOWN;
        }
    }
}
