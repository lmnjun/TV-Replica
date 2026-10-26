package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Camera;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Choreographer;
import android.view.View;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** Original split-flap clock: real Inter glyphs and hinged halves, no borrowed Fliqlo assets. */
final class FlipWallpaper extends View implements AmbientScenes.Layer, Choreographer.FrameCallback {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SharedPreferences preferences;
    private final Paint tile = new Paint(Paint.ANTI_ALIAS_FLAG), digits = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint detail = new Paint(Paint.ANTI_ALIAS_FLAG), shade = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();
    private final Path rounded = new Path();
    private final Camera camera = new Camera();
    private final Matrix fold = new Matrix();
    private final Date date = new Date();
    private SimpleDateFormat dateFormat;
    private final float dp;
    private Shader tileGradient, foldShadow;
    private float cardWidth, cardHeight, gap, left, top, baseline, radius, progress = 1;
    private float sizeScale = 1;
    private boolean showDate = true, animationEnabled = true;
    private String hour = "00", minute = "00", oldHour = "00", oldMinute = "00", amPm = "", day = "";
    private boolean active, closed, initialized, flipHour, flipMinute, receiverRegistered;
    private long animationStart;
    private final Runnable tick = () -> { if (active) { updateTime(true); scheduleMinute(); } };
    private final Runnable optionsUpdate = this::refreshOptions;
    private final SharedPreferences.OnSharedPreferenceChangeListener formatChanged = (prefs, key) -> {
        if (ClockFormat.KEY.equals(key) || SceneOptions.ownsKey("flip", key)) {
            handler.removeCallbacks(optionsUpdate); handler.post(optionsUpdate);
        }
    };
    private final BroadcastReceiver clockChanged = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!active) return; rebuildDateFormat(); updateTime(false); scheduleMinute();
        }
    };

    FlipWallpaper(Context context) {
        super(context); setFocusable(false); setClickable(false); setBackgroundColor(0xFF000000);
        dp = getResources().getDisplayMetrics().density;
        preferences = context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        digits.setTypeface(InterTypography.face(context, 700)); digits.setColor(0xFFF0F0F0);
        digits.setTextAlign(Paint.Align.CENTER); digits.setFontFeatureSettings("'tnum'");
        detail.setTypeface(InterTypography.face(context, 500)); detail.setTextAlign(Paint.Align.CENTER);
        detail.setTextSize(10 * dp); detail.setColor(0x88FFFFFF);
        rebuildDateFormat(); updateTime(false);
    }

    @Override public View view() { return this; }
    @Override public void update(AmbientSnapshot snapshot) { }
    @Override public void start() {
        if (closed || active) return; active = true;
        preferences.registerOnSharedPreferenceChangeListener(formatChanged);
        applyOptions();
        IntentFilter filter = new IntentFilter(); filter.addAction(Intent.ACTION_TIME_CHANGED);
        filter.addAction(Intent.ACTION_TIMEZONE_CHANGED); filter.addAction(Intent.ACTION_LOCALE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) getContext().registerReceiver(clockChanged, filter, Context.RECEIVER_NOT_EXPORTED);
        else getContext().registerReceiver(clockChanged, filter);
        receiverRegistered = true; rebuildDateFormat(); updateTime(false); scheduleMinute();
    }
    @Override public void stop() {
        active = false; handler.removeCallbacksAndMessages(null); preferences.unregisterOnSharedPreferenceChangeListener(formatChanged);
        Choreographer.getInstance().removeFrameCallback(this); progress = 1; flipHour = flipMinute = false;
        if (receiverRegistered) { getContext().unregisterReceiver(clockChanged); receiverRegistered = false; }
    }
    @Override public void close() { if (!closed) { stop(); closed = true; } }

    private void rebuildDateFormat() {
        dateFormat = new SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEE d MMM"), Locale.getDefault());
        dateFormat.setTimeZone(TimeZone.getDefault());
    }
    private void scheduleMinute() {
        handler.removeCallbacks(tick);
        if (active) handler.postDelayed(tick, 60_000 - System.currentTimeMillis() % 60_000 + 8);
    }
    private void updateTime(boolean animate) {
        long now = System.currentTimeMillis(); ClockFormat.Parts parts = ClockFormat.parts(getContext(), now);
        oldHour = hour; oldMinute = minute; hour = parts.hourText; minute = parts.minuteText; amPm = parts.amPm;
        date.setTime(now); day = dateFormat.format(date).toUpperCase(Locale.getDefault());
        flipHour = initialized && !hour.equals(oldHour); flipMinute = initialized && !minute.equals(oldMinute);
        initialized = true; Choreographer.getInstance().removeFrameCallback(this);
        if (animate && animationEnabled && active && (flipHour || flipMinute)) { progress = 0; animationStart = 0; Choreographer.getInstance().postFrameCallback(this); }
        else { progress = 1; flipHour = flipMinute = false; }
        setContentDescription(parts.hourText + ":" + parts.minuteText + (amPm.isEmpty() ? "" : " " + amPm) + (showDate ? ", " + day : ""));
        invalidate();
    }
    @Override public void doFrame(long frameTimeNanos) {
        if (!active || closed) return;
        if (animationStart == 0) animationStart = frameTimeNanos;
        progress = Math.min(1, (frameTimeNanos - animationStart) / 680_000_000f);
        invalidate();
        if (progress < 1) Choreographer.getInstance().postFrameCallback(this);
        else flipHour = flipMinute = false;
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        updateGeometry(width, height);
    }
    private void refreshOptions() { if (active && !closed) { applyOptions(); updateTime(false); } }
    private void applyOptions() {
        String size = SceneOptions.flipSize(getContext());
        float scale = "small".equals(size) ? .8f : "large".equals(size) ? 1.15f : 1;
        if (scale != sizeScale) { sizeScale = scale; updateGeometry(getWidth(), getHeight()); }
        showDate = SceneOptions.flipShowDate(getContext()); animationEnabled = SceneOptions.flipAnimation(getContext());
        if (!animationEnabled) {
            Choreographer.getInstance().removeFrameCallback(this); progress = 1; flipHour = flipMinute = false;
        }
        invalidate();
    }
    private void updateGeometry(int width, int height) {
        if (width <= 0 || height <= 0) return;
        cardHeight = Math.min(height * .37f, width * .205f) * sizeScale; cardWidth = cardHeight * 1.16f;
        gap = Math.max(12 * dp, cardHeight * .065f); radius = cardHeight * .047f;
        left = (width - cardWidth * 2 - gap) * .5f; top = height * .465f - cardHeight * .5f;
        bounds.set(0, 0, cardWidth, cardHeight); rounded.rewind(); rounded.addRoundRect(bounds, radius, radius, Path.Direction.CW);
        digits.setTextSize(cardHeight * .73f); Paint.FontMetrics metrics = digits.getFontMetrics();
        baseline = cardHeight * .5f - (metrics.ascent + metrics.descent) * .5f;
        tileGradient = new LinearGradient(0, 0, 0, cardHeight, 0xFF242424, 0xFF141414, Shader.TileMode.CLAMP);
        foldShadow = new LinearGradient(0, 0, 0, cardHeight, 0x00000000, 0xFF000000, Shader.TileMode.CLAMP);
        camera.setLocation(0, 0, -Math.max(8, cardHeight / (dp * 24)));
    }
    @Override protected void onDraw(Canvas canvas) {
        canvas.drawColor(0xFF000000);
        if (cardHeight <= 0) return;
        drawCard(canvas, left, top, oldHour, hour, flipHour);
        drawCard(canvas, left + cardWidth + gap, top, oldMinute, minute, flipMinute);
        float y = top + cardHeight + 28 * dp;
        detail.setTextAlign(Paint.Align.CENTER); detail.setTextSize(10 * dp); detail.setColor(0x88FFFFFF);
        if (showDate) canvas.drawText(day, getWidth() * .5f, y, detail);
        if (!amPm.isEmpty()) {
            detail.setTextAlign(Paint.Align.RIGHT); detail.setTextSize(8 * dp); detail.setColor(0x99FFFFFF);
            canvas.drawText(amPm, left + cardWidth * 2 + gap - 7 * dp, top + cardHeight + 15 * dp, detail);
        }
    }
    private void drawCard(Canvas canvas, float x, float y, String previous, String current, boolean animate) {
        int save = canvas.save(); canvas.translate(x, y); canvas.clipPath(rounded);
        if (!animate || progress >= 1) drawFace(canvas, current);
        else {
            drawHalf(canvas, current, true); drawHalf(canvas, previous, false);
            float phase = progress < .5f ? progress * 2 : (progress - .5f) * 2;
            phase = phase * phase * (3 - 2 * phase);
            float angle = progress < .5f ? -phase * 90 : (1 - phase) * 90;
            int moving = canvas.save(); camera.save(); camera.rotateX(angle); camera.getMatrix(fold); camera.restore();
            fold.preTranslate(-cardWidth * .5f, -cardHeight * .5f); fold.postTranslate(cardWidth * .5f, cardHeight * .5f);
            canvas.concat(fold);
            drawHalf(canvas, progress < .5f ? previous : current, progress < .5f);
            shade.setShader(foldShadow); shade.setAlpha(Math.round((float)Math.sin(Math.toRadians(Math.abs(angle))) * 145));
            canvas.drawRect(0, progress < .5f ? 0 : cardHeight * .5f, cardWidth, progress < .5f ? cardHeight * .5f : cardHeight, shade);
            shade.setShader(null); canvas.restoreToCount(moving);
        }
        tile.setShader(null); tile.setColor(0xD9000000); tile.setStrokeWidth(Math.max(1, dp));
        canvas.drawLine(0, cardHeight * .5f, cardWidth, cardHeight * .5f, tile);
        tile.setColor(0xFF080808);
        canvas.drawRect(0, cardHeight * .5f - 4 * dp, 3 * dp, cardHeight * .5f + 4 * dp, tile);
        canvas.drawRect(cardWidth - 3 * dp, cardHeight * .5f - 4 * dp, cardWidth, cardHeight * .5f + 4 * dp, tile);
        canvas.restoreToCount(save);
    }
    private void drawHalf(Canvas canvas, String value, boolean upper) {
        int save = canvas.save(); canvas.clipRect(0, upper ? 0 : cardHeight * .5f, cardWidth, upper ? cardHeight * .5f : cardHeight);
        drawFace(canvas, value); canvas.restoreToCount(save);
    }
    private void drawFace(Canvas canvas, String value) {
        tile.setShader(tileGradient); tile.setAlpha(255); canvas.drawRoundRect(bounds, radius, radius, tile); tile.setShader(null);
        canvas.drawText(value, cardWidth * .5f, baseline, digits);
    }
}
