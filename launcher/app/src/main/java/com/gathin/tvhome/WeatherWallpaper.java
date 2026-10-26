package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.view.Choreographer;
import android.view.View;
import java.io.InputStream;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Cached current WMO conditions drive the sky, cloud cover, fog and precipitation. */
final class WeatherWallpaper extends View implements AmbientScenes.Layer, Choreographer.FrameCallback {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG), cloudPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint[] rainPaints = new Paint[WeatherPrecipitation.LAYERS];
    private final Paint snowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final WeatherPrecipitation rain = new WeatherPrecipitation();
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF cloudRect = new RectF();
    private final Path lightning = new Path();
    private final float[] seedX = new float[240], seedY = new float[240], depth = new float[240];
    private final float[] starPoints = new float[100 * 2], stars = new float[100 * 2];
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService decode = Executors.newSingleThreadExecutor();
    private final SharedPreferences options;
    private final Runnable optionsUpdate = this::refreshOptions;
    private final SharedPreferences.OnSharedPreferenceChangeListener optionsChanged = (prefs, key) -> {
        if (SceneOptions.ownsKey("weather", key)) { handler.removeCallbacks(optionsUpdate); handler.post(optionsUpdate); }
    };
    private final float dp;
    private Bitmap clouds;
    private Shader sky, sun, fog;
    private boolean active, closed, available, day;
    private int code = -1, kind = AmbientMath.UNKNOWN, cloudAlpha;
    private double seconds;
    private long lastFrameNanos;
    private float width, height;
    private float motionSpeed = 1, intensity = 1;
    private float rainVisibilityAlpha = 2, rainVisibilityLength = 1.35f;
    private boolean effects = true;
    private String state = "WAITING FOR CURRENT WEATHER";

    WeatherWallpaper(Context context) {
        super(context); setFocusable(false); setClickable(false);
        options = context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        seconds = System.nanoTime() / 1_000_000_000.0;
        dp = getResources().getDisplayMetrics().density;
        labelPaint.setTypeface(InterTypography.face(context, 500)); labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setTextSize(9 * dp); labelPaint.setColor(0x99FFFFFF);
        for (int i = 0; i < rainPaints.length; i++) {
            rainPaints[i] = new Paint(Paint.ANTI_ALIAS_FLAG); rainPaints[i].setStrokeCap(Paint.Cap.BUTT);
        }
        snowPaint.setColor(0xC7EAF0F6);
        Random random = new Random(72491);
        for (int i = 0; i < seedX.length; i++) { seedX[i] = random.nextFloat(); seedY[i] = random.nextFloat(); depth[i] = random.nextFloat(); }
        for (int i = 0; i < stars.length; i++) stars[i] = random.nextFloat();
        Context app = context.getApplicationContext();
        decode.execute(() -> {
            Bitmap image = null;
            try (InputStream input = app.getAssets().open("ambient/weather_clouds.png")) { image = BitmapFactory.decodeStream(input); }
            catch (Exception | OutOfMemoryError ignored) { }
            final Bitmap finished = image;
            handler.post(() -> {
                if (closed) { if (finished != null) finished.recycle(); return; }
                clouds = finished; invalidate();
            });
        });
    }

    @Override public View view() { return this; }
    @Override public void update(AmbientSnapshot snapshot) {
        available = snapshot.weatherAvailable; day = snapshot.day; code = snapshot.code; kind = snapshot.kind;
        cloudAlpha = code == 0 ? 0 : code == 1 ? 36 : code == 2 ? 112 : kind == AmbientMath.UNKNOWN ? 30 : 200;
        rain.setCode(code); updateRainPaints();
        String age = snapshot.fetched <= 0 || System.currentTimeMillis() - snapshot.fetched > 3 * 60 * 60_000L ? " · CACHED" : "";
        state = available ? (age.isEmpty() ? "" : "CACHED WEATHER") : "CURRENT WEATHER UNAVAILABLE";
        setContentDescription(available ? "Weather wallpaper for " + snapshot.city + ", " + (day ? "day" : "night")
                + ", WMO condition " + code + age + ". Based on cached Open-Meteo observations." : state);
        rebuildPaints(); invalidate();
    }
    @Override public void start() {
        if (closed || active) return; active = true;
        options.registerOnSharedPreferenceChangeListener(optionsChanged); refreshOptions();
    }
    @Override public void stop() {
        active = false; options.unregisterOnSharedPreferenceChangeListener(optionsChanged);
        handler.removeCallbacks(optionsUpdate); lastFrameNanos = 0;
        Choreographer.getInstance().removeFrameCallback(this);
    }
    @Override public void close() {
        if (closed) return; stop(); closed = true; decode.shutdownNow();
        if (clouds != null) { clouds.recycle(); clouds = null; }
    }
    @Override public void doFrame(long frameTimeNanos) {
        if (!active || closed || motionSpeed == 0) return;
        if (lastFrameNanos != 0) seconds += Math.min(.1, (frameTimeNanos - lastFrameNanos) / 1_000_000_000.0) * motionSpeed;
        lastFrameNanos = frameTimeNanos;
        invalidate(); Choreographer.getInstance().postFrameCallback(this);
    }
    private void refreshOptions() {
        if (!active || closed) return;
        String motion = SceneOptions.weatherMotion(getContext());
        motionSpeed = "still".equals(motion) ? 0 : "calm".equals(motion) ? .38f : 1;
        intensity = "calm".equals(motion) ? .55f : 1; // Existing snow/lightning treatment; rain strength is independent of motion.
        effects = SceneOptions.weatherEffects(getContext());
        String visibility = SceneOptions.weatherRainVisibility(getContext());
        rainVisibilityAlpha = WeatherPrecipitation.visibilityAlpha(visibility);
        rainVisibilityLength = WeatherPrecipitation.visibilityLength(visibility);
        updateRainPaints();
        Choreographer.getInstance().removeFrameCallback(this); lastFrameNanos = 0;
        if (motionSpeed > 0) Choreographer.getInstance().postFrameCallback(this);
        invalidate();
    }
    private void updateRainPaints() {
        WeatherPrecipitation.Profile profile = rain.profile();
        boolean drizzle = code >= 51 && code <= 57;
        for (int i = 0; i < rainPaints.length; i++) {
            Paint drop = rainPaints[i];
            drop.setColor(day ? 0xFFB5C8D8 : 0xFF9AAFC0);
            float depthAlpha = i == 0 ? .38f : i == 1 ? .65f : 1;
            int naturalAlpha = Math.round((day ? profile.dayAlpha : profile.nightAlpha) * depthAlpha);
            drop.setAlpha(WeatherPrecipitation.visibleAlpha(naturalAlpha, rainVisibilityAlpha));
            float widthDp = i == 0 ? .35f : i == 1 ? .50f : .70f;
            drop.setStrokeWidth(Math.max(.5f, widthDp * dp * (drizzle ? .8f : 1)));
        }
    }
    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        width = w; height = h;
        lightning.rewind(); lightning.moveTo(w * .69f, h * .12f); lightning.lineTo(w * .65f, h * .27f);
        lightning.lineTo(w * .69f, h * .26f); lightning.lineTo(w * .61f, h * .46f);
        rebuildPaints();
    }
    private void rebuildPaints() {
        if (width <= 0 || height <= 0) return;
        int top, bottom;
        if (!available) { top = 0xFF080D14; bottom = 0xFF1D2934; }
        else if (kind == AmbientMath.STORM) { top = 0xFF10151C; bottom = 0xFF303B47; }
        else if (kind == AmbientMath.RAIN || kind == AmbientMath.SNOW || kind == AmbientMath.FOG || kind == AmbientMath.CLOUD) {
            top = day ? 0xFF1F2E3C : 0xFF090F18; bottom = day ? 0xFF63717B : 0xFF26313F;
        } else { top = day ? 0xFF0B2B4D : 0xFF020711; bottom = day ? 0xFF547FA2 : 0xFF11253B; }
        sky = new LinearGradient(0, 0, 0, height, top, bottom, Shader.TileMode.CLAMP);
        sun = new RadialGradient(width * .69f, height * .26f, height * .34f,
                new int[]{day ? 0x89FFF2D2 : 0x2A9EBDDB, day ? 0x23E8D6B6 : 0x12738CAE, 0},
                new float[]{0, .28f, 1}, Shader.TileMode.CLAMP);
        fog = new LinearGradient(0, height * .22f, 0, height, 0, day ? 0x779BA7AC : 0x55414E5E, Shader.TileMode.CLAMP);
        cloudPaint.setColorFilter(new PorterDuffColorFilter(day ? 0xFFE5E9EA : 0xFF64758B, PorterDuff.Mode.MULTIPLY));
    }

    @Override protected void onDraw(Canvas canvas) {
        if (sky == null) { canvas.drawColor(0xFF080D14); return; }
        paint.setStyle(Paint.Style.FILL); paint.setShader(sky); paint.setAlpha(255); canvas.drawRect(0, 0, width, height, paint); paint.setShader(null);
        if (available && !day && kind == AmbientMath.CLEAR) {
            paint.setColor(0x66B9CADB); paint.setStrokeWidth(Math.max(1, .75f * dp));
            for (int i = 0; i < 100; i++) { starPoints[i * 2] = stars[i * 2] * width; starPoints[i * 2 + 1] = stars[i * 2 + 1] * height * .65f; }
            canvas.drawPoints(starPoints, 0, 200, paint);
        }
        if (available && (kind == AmbientMath.CLEAR || code == 3)) {
            paint.setAlpha(255); paint.setShader(sun); canvas.drawRect(0, 0, width, height * .8f, paint); paint.setShader(null);
            if (cloudAlpha < 180) {
                paint.setColor(day ? 0xD5F5ECD9 : 0xC3CCD8E2);
                canvas.drawCircle(width * .69f, height * .26f, height * (day ? .031f : .022f), paint);
            }
        }
        if (clouds != null && cloudAlpha > 0) {
            drawCloudBand(canvas, .03f, .48f, .008f, cloudAlpha);
            drawCloudBand(canvas, .16f, .43f, -.004f, Math.round(cloudAlpha * .58f));
        }
        if (kind == AmbientMath.FOG) { paint.setShader(fog); paint.setAlpha(255); canvas.drawRect(0, 0, width, height, paint); paint.setShader(null); }
        if (effects && rain.profile().count > 0) {
            rain.fill(width, height, dp, seconds, 1f, rainVisibilityLength);
            for (int i = 0; i < rainPaints.length; i++)
                if (rain.counts[i] > 0) canvas.drawLines(rain.lines[i], 0, rain.counts[i] * 4, rainPaints[i]);
        }
        if (effects && kind == AmbientMath.SNOW) {
            for (int i = 0; i < Math.round(100 * intensity); i++) {
                float z = .35f + depth[i] * .65f;
                float y = fraction(seedY[i] + seconds * .045f * z) * (height + 12 * dp) - 6 * dp;
                float x = fraction(seedX[i] + seconds * .007f + (float)Math.sin(seconds * .27f + seedY[i] * 20) * .012f) * width;
                snowPaint.setAlpha(Math.round((90 + 140 * z) * intensity)); canvas.drawCircle(x, y, (1 + z) * dp, snowPaint);
            }
        }
        if (effects && motionSpeed > 0 && kind == AmbientMath.STORM) {
            float beat = (float)(seconds % 13.5);
            if (beat < .09f || beat > .20f && beat < .25f) {
                paint.setColor(0x77C2D1DE); paint.setAlpha(Math.round(119 * intensity)); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp * 1.1f);
                canvas.drawPath(lightning, paint); paint.setStyle(Paint.Style.FILL);
                paint.setColor(0x07DFE7EF); paint.setAlpha(Math.round(7 * intensity)); canvas.drawRect(0, 0, width, height, paint);
            }
        }
        if (!state.isEmpty()) canvas.drawText(state, width * .5f, height * .67f, labelPaint);
    }

    private void drawCloudBand(Canvas canvas, float y, float bandHeight, float speed, int alpha) {
        float tileWidth = width * 1.35f, shift = fraction(seconds * speed) * tileWidth;
        cloudPaint.setAlpha(alpha);
        cloudRect.set(-shift, height * y, tileWidth - shift, height * (y + bandHeight));
        canvas.drawBitmap(clouds, null, cloudRect, cloudPaint);
        cloudRect.offset(tileWidth, 0); canvas.drawBitmap(clouds, null, cloudRect, cloudPaint);
    }
    private static float fraction(double value) { return (float)(value - Math.floor(value)); }
}
