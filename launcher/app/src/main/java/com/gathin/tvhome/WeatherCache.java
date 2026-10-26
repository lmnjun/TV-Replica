package com.gathin.tvhome;

import android.content.SharedPreferences;

/** Coordinates and observation revisions move together across Home and screensaver requests. */
final class WeatherCache {
    static final Object LOCK = new Object();
    static final class Request {
        final String latitude, longitude;
        final long fetched;
        Request(SharedPreferences prefs) {
            latitude = prefs.getString("latitude", ""); longitude = prefs.getString("longitude", "");
            fetched = prefs.getLong("fetched", 0);
        }
    }
    static Request request(SharedPreferences prefs) {
        synchronized (LOCK) { return new Request(prefs); }
    }
    static boolean publish(SharedPreferences prefs, Request request, String raw) {
        synchronized (LOCK) {
            if (!request.latitude.equals(prefs.getString("latitude", ""))
                    || !request.longitude.equals(prefs.getString("longitude", ""))
                    || request.fetched != prefs.getLong("fetched", 0)) return false;
            prefs.edit().putString("json", raw).putLong("fetched", System.currentTimeMillis()).apply();
            return true;
        }
    }
    private WeatherCache() { }
}
