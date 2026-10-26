package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;

/** Context-only Home/Dream wallpaper host. It owns no account, network or clock settings. */
final class AmbientScenes {
    interface Layer {
        View view();
        void update(AmbientSnapshot snapshot);
        void start();
        void stop();
        void close();
    }

    private final Context context;
    private final FrameLayout parent, container;
    private final SharedPreferences weather;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = this::refreshSnapshot;
    private final SharedPreferences.OnSharedPreferenceChangeListener listener = (prefs, key) -> {
        if ("json".equals(key) || "latitude".equals(key) || "longitude".equals(key) || "city".equals(key) || "location_method".equals(key)) {
            handler.removeCallbacks(refresh); handler.post(refresh);
        }
    };
    private Layer layer;
    private AmbientWeatherRefresh weatherRefresh;
    private String selected = "";
    private boolean active, closed;

    AmbientScenes(Context context, FrameLayout root) {
        this.context = context; parent = root;
        weather = context.getSharedPreferences("weather_kuala_lumpur", Context.MODE_PRIVATE);
        container = new FrameLayout(context); container.setBackgroundColor(0xFF000000);
        container.setFocusable(false); container.setClickable(false);
        container.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        container.setVisibility(View.GONE);
        root.addView(container, 0, new FrameLayout.LayoutParams(-1, -1));
    }

    static boolean supports(String id) { return "earth".equals(id) || "weather".equals(id) || "flip".equals(id); }

    boolean select(String id) {
        if (closed || Looper.myLooper() != Looper.getMainLooper() || !supports(id)) return false;
        if (id.equals(selected)) return true;
        Layer next = null;
        try {
            if ("earth".equals(id)) next = new EarthWallpaper(context);
            else if ("weather".equals(id)) next = new WeatherWallpaper(context);
            else next = new FlipWallpaper(context);
            next.update(new AmbientSnapshot(weather));
            if (layer != null) { layer.stop(); layer.close(); container.removeView(layer.view()); }
            if (weatherRefresh != null) weatherRefresh.stop();
            layer = next; selected = id;
            container.addView(next.view(), new FrameLayout.LayoutParams(-1, -1));
            if (active) { container.setVisibility(View.VISIBLE); next.start(); startWeatherRefresh(); }
            return true;
        } catch (RuntimeException | LinkageError | OutOfMemoryError unavailable) {
            if (next != null) { try { next.close(); } catch (RuntimeException ignored) { } }
            if (layer != null && layer != next) { try { layer.close(); } catch (RuntimeException ignored) { } }
            if (weatherRefresh != null) weatherRefresh.stop();
            container.removeAllViews();
            container.setVisibility(View.GONE); layer = null; selected = "";
            return false;
        }
    }

    void start() {
        if (closed || active) return;
        active = true; weather.registerOnSharedPreferenceChangeListener(listener);
        if (layer != null) { layer.update(new AmbientSnapshot(weather)); container.setVisibility(View.VISIBLE); layer.start(); startWeatherRefresh(); }
        handler.postDelayed(refresh, 60_000);
    }

    void stop() {
        if (!active) return;
        active = false; weather.unregisterOnSharedPreferenceChangeListener(listener);
        handler.removeCallbacks(refresh);
        if (layer != null) layer.stop();
        if (weatherRefresh != null) weatherRefresh.stop();
        container.setVisibility(View.GONE);
    }

    void setBurnInOffset(float xPx, float yPx) { container.setTranslationX(xPx); container.setTranslationY(yPx); }

    boolean beginEarthViewExplore() {
        if (closed || !active || !(layer instanceof EarthWallpaper)) return false;
        ((EarthWallpaper)layer).beginViewExplore();
        return true;
    }

    void previewEarthView(float zoom, float yaw, float pitch) {
        if (!closed && active && layer instanceof EarthWallpaper)
            ((EarthWallpaper)layer).previewView(zoom, yaw, pitch);
    }

    void endEarthViewExplore() {
        if (!closed && layer instanceof EarthWallpaper) ((EarthWallpaper)layer).endViewExplore();
    }

    void close() {
        if (closed) return;
        closed = true;
        try { stop(); }
        finally {
            handler.removeCallbacksAndMessages(null);
            Layer retired = layer; layer = null; selected = "";
            try { if (retired != null) retired.close(); }
            finally {
                try { if (weatherRefresh != null) weatherRefresh.close(); }
                finally {
                    weatherRefresh = null;
                    try { container.removeAllViews(); }
                    finally { parent.removeView(container); }
                }
            }
        }
    }

    private void refreshSnapshot() {
        if (closed || !active) return;
        if (layer != null) layer.update(new AmbientSnapshot(weather));
        handler.removeCallbacks(refresh); handler.postDelayed(refresh, 60_000);
    }
    private void startWeatherRefresh() {
        if (!"weather".equals(selected)) return;
        if (weatherRefresh == null) weatherRefresh = new AmbientWeatherRefresh(context);
        weatherRefresh.start();
    }
}
