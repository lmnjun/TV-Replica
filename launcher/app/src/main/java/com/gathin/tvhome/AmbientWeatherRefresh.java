package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.json.JSONObject;

/** Keeps an active Weather Dream fresh, using only already-saved, validated coordinates. */
final class AmbientWeatherRefresh {
    private static final long INTERVAL = 30 * 60_000L;
    private final SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile boolean active, closed;
    private volatile int generation;
    private volatile HttpURLConnection connection;
    private Future<?> pending;
    private final Runnable tick = this::scheduleFetch;

    AmbientWeatherRefresh(Context context) {
        prefs = context.getSharedPreferences("weather_kuala_lumpur", Context.MODE_PRIVATE);
    }
    void start() { if (closed || active) return; active = true; generation++; handler.post(tick); }
    void stop() {
        active = false; generation++; handler.removeCallbacks(tick);
        if (pending != null) { pending.cancel(true); pending = null; }
        HttpURLConnection open = connection; if (open != null) open.disconnect();
    }
    void close() { if (!closed) { closed = true; stop(); worker.shutdownNow(); } }
    private boolean current(int token) { return active && !closed && token == generation && !Thread.currentThread().isInterrupted(); }
    private long age() { return System.currentTimeMillis() - prefs.getLong("fetched", 0); }
    private void scheduleFetch() {
        if (!active || closed) return;
        long age = age();
        if (age >= 0 && age < INTERVAL) { handler.postDelayed(tick, Math.max(1000, INTERVAL - age)); return; }
        final int token = generation;
        pending = worker.submit(() -> fetch(token));
        handler.postDelayed(tick, INTERVAL);
    }
    private void fetch(int token) {
        HttpURLConnection open = null;
        try {
            if (!current(token)) return;
            // WeatherWidget may have finished its own request since this task was scheduled.
            long age = age(); if (age >= 0 && age < INTERVAL) return;
            WeatherCache.Request request = WeatherCache.request(prefs);
            double latitude = AmbientMath.coordinate(request.latitude, 90);
            double longitude = AmbientMath.coordinate(request.longitude, 180);
            if (!Double.isFinite(latitude) || !Double.isFinite(longitude)) return;
            String endpoint = "https://api.open-meteo.com/v1/forecast?latitude=" + latitude + "&longitude=" + longitude
                    + "&current=temperature_2m,apparent_temperature,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1";
            open = (HttpURLConnection)new URL(endpoint).openConnection(); connection = open;
            open.setConnectTimeout(6000); open.setReadTimeout(8000);
            open.setRequestProperty("User-Agent", "CleanYourTV-Ambient/1.0");
            if (!current(token) || open.getResponseCode() != 200) return;
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream input = open.getInputStream()) {
                byte[] buffer = new byte[4096]; int count;
                while ((count = input.read(buffer)) != -1) {
                    if (!current(token)) return;
                    bytes.write(buffer, 0, count); if (bytes.size() > 128_000) return;
                }
            }
            String raw = bytes.toString("UTF-8"); JSONObject json = new JSONObject(raw);
            JSONObject conditions = json.getJSONObject("current");
            if (AmbientMath.weatherKind(conditions.getInt("weather_code")) == AmbientMath.UNKNOWN
                    || !Double.isFinite(conditions.getDouble("temperature_2m"))) return;
            int day = conditions.getInt("is_day"); if (day != 0 && day != 1) return;
            json.getJSONObject("daily").getJSONArray("temperature_2m_max").getDouble(0);
            json.getJSONObject("daily").getJSONArray("temperature_2m_min").getDouble(0);
            if (current(token)) WeatherCache.publish(prefs, request, raw);
        } catch (Exception ignored) {
            // Keep the last genuine observation. The wallpaper marks stale data rather than guessing.
        } finally {
            if (connection == open) connection = null;
            if (open != null) open.disconnect();
        }
    }
}
