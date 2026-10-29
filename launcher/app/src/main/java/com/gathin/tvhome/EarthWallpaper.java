package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.os.SystemClock;
import android.os.Handler;
import android.os.Looper;
import android.os.Build;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.View;
import android.widget.FrameLayout;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/** NASA-textured globe. Camera offsets are relative to saved location; sunlight follows real UTC. */
final class EarthWallpaper extends FrameLayout implements AmbientScenes.Layer {
    private final GLSurfaceView surface;
    private final Globe renderer;
    private final Caption caption;
    private final SceneRenderGuard renderGuard = new SceneRenderGuard();
    private final SharedPreferences options;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable optionsUpdate = this::refreshOptions;
    private final SharedPreferences.OnSharedPreferenceChangeListener optionsChanged = (prefs, key) -> {
        if (SceneOptions.ownsKey("earth", key)) { handler.removeCallbacks(optionsUpdate); handler.post(optionsUpdate); }
    };
    private boolean running, closed, configured, rendererResumed, viewExploring;
    private Runnable failureListener;
    private boolean frameRateRequested;
    private final SurfaceHolder.Callback frameRateLifecycle = new SurfaceHolder.Callback() {
        @Override public void surfaceCreated(SurfaceHolder holder) {
            frameRateRequested = false; updateSurfaceFrameRate();
        }
        @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            updateSurfaceFrameRate();
        }
        @Override public void surfaceDestroyed(SurfaceHolder holder) {
            frameRateRequested = false;
        }
    };
    private float exploreZoom = 1, exploreYaw, explorePitch;
    private boolean hasSavedLocation;
    private double savedLatitude = 18, savedLongitude = 25;
    private int bufferWidth, bufferHeight;
    private final View.OnLayoutChangeListener sizing = (v, l, t, r, b, ol, ot, or, ob) -> resizeSurface(r - l, b - t);
    private void resizeSurface(int width, int height) {
        if (width <= 0 || height <= 0) return;
        // Earth has a fixed native-HD budget; navigation never changes its buffer or texture quality.
        float scale = Math.min(1f, Math.min(1920f / width, 1080f / height));
        int w = Math.max(1, Math.round(width * scale)), h = Math.max(1, Math.round(height * scale));
        if (w != bufferWidth || h != bufferHeight) {
            bufferWidth = w; bufferHeight = h; surface.getHolder().setFixedSize(w, h);
        }
    }

    EarthWallpaper(Context context) {
        super(context); setBackgroundColor(0xFF000000); setFocusable(false);
        options = context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        renderer = new Globe(context.getApplicationContext(), renderGuard, this::onRendererFailed);
        surface = new GLSurfaceView(context); surface.setEGLContextClientVersion(2);
        surface.setEGLConfigChooser(8, 8, 8, 0, 0, 0);
        surface.setPreserveEGLContextOnPause(true);
        surface.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        surface.addOnLayoutChangeListener(sizing);
        surface.getHolder().addCallback(frameRateLifecycle);
        addView(surface, new FrameLayout.LayoutParams(-1, -1));
        caption = new Caption(context); addView(caption, new FrameLayout.LayoutParams(-1, -1));
    }

    @Override public View view() { return this; }
    @Override public void update(AmbientSnapshot data) {
        savedLatitude = data.hasLocation ? data.latitude : 18;
        savedLongitude = data.hasLocation ? data.longitude : 25;
        hasSavedLocation = data.hasLocation;
        caption.label = data.hasLocation ? data.city.toUpperCase(java.util.Locale.getDefault()) : "EARTH";
        // Earlier WeatherWidget versions did not persist the source: never label that cache GPS.
        String method = getContext().getSharedPreferences("weather_kuala_lumpur", 0).getString("location_method", "");
        caption.detail = data.hasLocation ? ("device".equals(method) ? "SAVED DEVICE LOCATION" : "APPROXIMATE SAVED LOCATION")
                : "LOCATION NOT AVAILABLE";
        applyOptions();
    }
    private void applyOptions() {
        caption.showLocation = SceneOptions.earthShowLocation(getContext());
        renderer.marker = hasSavedLocation && caption.showLocation;
        renderer.dayNight = SceneOptions.earthDayNight(getContext());
        renderer.atmosphere = SceneOptions.earthAtmosphere(getContext());
        renderer.showStars = SceneOptions.earthStars(getContext());
        publishCamera();
        if (caption.renderUnavailable) {
            setContentDescription("Earth static background. The textured scene is unavailable.");
            return;
        }
        setContentDescription("Earth. " + (caption.showLocation ? caption.label + ". " + caption.detail + ". " : "")
                + "The saved-location marker follows the globe and is hidden on its far side. "
                + "NASA and NOAA historical day and city-light composites; approximate sunlight, not live satellite imagery.");
    }
    private void publishCamera() {
        EarthCamera.State camera = EarthCamera.create(savedLatitude, savedLongitude,
                viewExploring ? exploreZoom : SceneOptions.earthZoom(getContext()),
                viewExploring ? exploreYaw : SceneOptions.earthYaw(getContext()),
                viewExploring ? explorePitch : SceneOptions.earthPitch(getContext()));
        // The GL marker and caption always share one immutable projected camera snapshot.
        renderer.camera = camera; caption.camera = camera; caption.hasLocation = hasSavedLocation; caption.invalidate();
    }
    /** Main-thread exploration is temporary; only Main's explicit exit commits the final values. */
    boolean beginViewExplore() {
        if (closed || !running || renderGuard.failure() != 0) return false;
        if (viewExploring) return true;
        exploreZoom = SceneOptions.earthZoom(getContext()); exploreYaw = SceneOptions.earthYaw(getContext());
        explorePitch = SceneOptions.earthPitch(getContext()); viewExploring = true; publishCamera();
        return true;
    }
    void previewView(float zoom, float yaw, float pitch) {
        if (closed || !running || !viewExploring) return;
        exploreZoom = SceneOptions.normalizeEarthZoom(zoom); exploreYaw = SceneOptions.normalizeEarthYaw(yaw);
        explorePitch = SceneOptions.normalizeEarthPitch(pitch); publishCamera();
    }
    void endViewExplore() {
        if (!viewExploring) return;
        viewExploring = false; if (!closed) applyOptions();
    }
    private void refreshOptions() { if (running && !closed) applyOptions(); }
    /** A local compositor hint only; never changes texture/buffer quality or submits work per frame. */
    private void updateSurfaceFrameRate() {
        if (Build.VERSION.SDK_INT < 30 || surface == null) return;
        if (!running || closed || renderGuard.failure() != 0 || !isAttachedToWindow() || getWindowVisibility() != VISIBLE || !isShown()) {
            clearSurfaceFrameRate(); return;
        }
        Surface target = surface.getHolder().getSurface();
        if (target == null) return;
        try {
            if (!target.isValid()) return;
            if (frameRateRequested) return;
            target.setFrameRate(60f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT);
            frameRateRequested = true;
        } catch (RuntimeException unavailable) {
            frameRateRequested = false;
            Log.w("Ambient.Earth", "Surface frame-rate request unavailable", unavailable);
        }
    }
    private void clearSurfaceFrameRate() {
        if (Build.VERSION.SDK_INT < 30 || surface == null || !frameRateRequested) return;
        try {
            Surface target = surface.getHolder().getSurface();
            if (target != null && target.isValid())
                target.setFrameRate(0f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT);
        } catch (RuntimeException unavailable) {
            Log.w("Ambient.Earth", "Surface frame-rate request could not be cleared", unavailable);
        } finally { frameRateRequested = false; }
    }
    @Override public void start() {
        if (closed || running) return; running = true;
        options.registerOnSharedPreferenceChangeListener(optionsChanged); applyOptions();
        long failure = renderGuard.failure();
        if (failure != 0) { showRendererFallback(failure); return; }
        if (isAttachedToWindow()) startRenderer();
    }
    private void startRenderer() {
        if (closed || !running || renderGuard.failure() != 0 || rendererResumed) return;
        // setRenderer starts a GL thread: defer it until this selected, active view is attached.
        if (!configured) { surface.setRenderer(renderer); configured = true; }
        surface.onResume(); rendererResumed = true; surface.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        updateSurfaceFrameRate();
    }
    void setFailureListener(Runnable listener) {
        failureListener = listener;
        long failure = renderGuard.failure();
        if (failure != 0 && running && !closed) showRendererFallback(failure);
    }

    /** Called by the GL thread; stale or retired contexts may never replace a newer visible scene. */
    private void onRendererFailed(long generation, Throwable failure) {
        if (!renderGuard.fail(generation)) return;
        Log.w("Ambient.Earth", "Renderer unavailable; retiring GL for a static visible background", failure);
        handler.post(() -> showRendererFallback(generation));
    }

    private void showRendererFallback(long generation) {
        if (closed || !renderGuard.useFallback(generation)) return;
        viewExploring = false;
        // This overlay was allocated before texture decoding, so OOM recovery needs no new bitmap.
        caption.renderUnavailable = true; caption.invalidate();
        surface.setVisibility(View.GONE);
        setContentDescription("Earth static background. The textured scene is unavailable.");
        clearSurfaceFrameRate();
        try {
            surface.setPreserveEGLContextOnPause(false);
            pauseRenderer();
        } catch (RuntimeException | LinkageError unavailable) {
            Log.w("Ambient.Earth", "Failed surface could not pause normally; detaching it", unavailable);
        } finally {
            // Detach also releases partially loaded GL resources, even if a failure arrived while paused.
            if (surface.getParent() == this) removeView(surface);
            rendererResumed = false;
        }
        if (failureListener != null && renderGuard.claimFailure(generation, running)) failureListener.run();
    }

    private void pauseRenderer() {
        if (!configured || !rendererResumed) return;
        surface.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        surface.onPause(); rendererResumed = false;
    }
    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow(); if (running && !closed) startRenderer();
    }
    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility); updateSurfaceFrameRate();
    }
    @Override protected void onVisibilityChanged(View changed, int visibility) {
        super.onVisibilityChanged(changed, visibility); updateSurfaceFrameRate();
    }
    @Override protected void onDetachedFromWindow() {
        clearSurfaceFrameRate(); pauseRenderer(); super.onDetachedFromWindow();
    }
    @Override public void stop() {
        if (!running) return; running = false;
        clearSurfaceFrameRate();
        viewExploring = false; // Resume always reads the persisted normal view, never stale controls.
        options.unregisterOnSharedPreferenceChangeListener(optionsChanged); handler.removeCallbacks(optionsUpdate);
        pauseRenderer();
    }
    @Override public void close() {
        if (closed) return; closed = true; renderGuard.close(); failureListener = null; stop();
        handler.removeCallbacksAndMessages(null);
        surface.removeOnLayoutChangeListener(sizing);
        surface.getHolder().removeCallback(frameRateLifecycle);
        removeAllViews(); // Detachment terminates GLSurfaceView's thread and its EGL resources.
    }

    private final class Caption extends View {
        final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint fallback = new Paint(Paint.ANTI_ALIAS_FLAG);
        final float dp;
        String label = "EARTH", detail = "LOCATION NOT AVAILABLE";
        boolean showLocation = true, hasLocation, renderUnavailable;
        EarthCamera.State camera;
        Caption(Context context) {
            super(context); dp = getResources().getDisplayMetrics().density;
            text.setTypeface(InterTypography.face(context, 500)); text.setTextAlign(Paint.Align.CENTER);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            float radius = Math.max(1, Math.min(w, h) * .24f);
            fallback.setShader(new RadialGradient(w * .5f - radius * .35f, h * .46f - radius * .4f,
                    radius * 1.45f, new int[]{0xFF376A85, 0xFF17394D, 0xFF06121F},
                    new float[]{0, .55f, 1}, Shader.TileMode.CLAMP));
        }
        @Override protected void onDraw(Canvas canvas) {
            if (renderUnavailable) {
                canvas.drawColor(0xFF030811);
                canvas.drawCircle(getWidth() * .5f, getHeight() * .46f,
                        Math.min(getWidth(), getHeight()) * .24f, fallback);
                text.setTextSize(10 * dp); text.setColor(0xCCFFFFFF);
                canvas.drawText("EARTH", getWidth() * .5f, getHeight() * .77f, text);
                text.setTextSize(7.5f * dp); text.setColor(0x80FFFFFF);
                canvas.drawText("STATIC BACKGROUND", getWidth() * .5f, getHeight() * .77f + 18 * dp, text);
                return;
            }
            float aspect = getWidth() / (float)Math.max(1, getHeight());
            if (showLocation && (!hasLocation || camera != null && camera.markerVisible(aspect))) {
                float x = getWidth() * .5f, y = getHeight() * .46f + 22 * dp;
                if (hasLocation) {
                    x = (camera.markerX(aspect) + 1) * getWidth() * .5f;
                    y = (1 - camera.markerY()) * getHeight() * .5f + 22 * dp;
                }
                text.setTextSize(10 * dp);
                float labelHalf = text.measureText(label) * .5f;
                text.setTextSize(7.5f * dp);
                float halfWidth = Math.max(labelHalf, text.measureText(detail) * .5f) + 12 * dp;
                x = Math.max(halfWidth, Math.min(getWidth() - halfWidth, x));
                y = Math.max(24 * dp, Math.min(getHeight() - 36 * dp, y));
                text.setTextSize(10 * dp); text.setColor(0xD9FFFFFF); canvas.drawText(label, x, y, text);
                text.setTextSize(7.5f * dp); text.setColor(0x80FFFFFF); canvas.drawText(detail, x, y + 16 * dp, text);
            }
            text.setTextSize(6.5f * dp); text.setColor(0x55FFFFFF);
            canvas.drawText("NASA / NOAA · HISTORICAL COMPOSITES", getWidth() * .5f, getHeight() * .70f, text);
        }
    }

    private static final class Globe implements GLSurfaceView.Renderer {
        interface FailureListener { void failed(long generation, Throwable failure); }
        private static final int LAT = 48, LON = 96;
        private final Context context;
        private final SceneRenderGuard renderGuard;
        private final FailureListener failureListener;
        private long contextGeneration;
        private final FloatBuffer vertices, stars, markerPoint;
        private final ShortBuffer indices;
        private final int indexCount;
        private final float[] sun = new float[3];
        private int planetProgram, pointProgram;
        private Texture texture, nightTexture;
        private int maxTextureSize = 2048;
        private int pPosition, pUv, pBasis, pFocal, pDistance, pSun, pTexture, pNightTexture, pNightAvailable, pEdge, pDayNight, pAtmosphere, pPulse;
        private int sPosition, sSize, sColor;
        private float aspect = 1, bufferHeight = 1080, edgeFade = .10f;
        private long solarMinute = Long.MIN_VALUE;
        private boolean ready, nightAvailable;
        volatile EarthCamera.State camera = EarthCamera.create(18, 25, 1, 0, 0);
        volatile boolean marker;
        volatile boolean dayNight = true, atmosphere = true, showStars = true;
        private static final class Texture {
            final int id, width, height;
            Texture(int id, int width, int height) { this.id = id; this.width = width; this.height = height; }
            String dimensions() { return width + "x" + height; }
        }

        Globe(Context context, SceneRenderGuard renderGuard, FailureListener failureListener) {
            this.context = context; this.renderGuard = renderGuard; this.failureListener = failureListener;
            float[] mesh = new float[(LAT + 1) * (LON + 1) * 5]; int n = 0;
            for (int y = 0; y <= LAT; y++) {
                double lat = Math.PI * (y / (double)LAT - .5);
                for (int x = 0; x <= LON; x++) {
                    double lon = Math.PI * 2 * (x / (double)LON - .5);
                    mesh[n++] = (float)(Math.cos(lat) * Math.sin(lon));
                    mesh[n++] = (float)Math.sin(lat);
                    mesh[n++] = (float)(Math.cos(lat) * Math.cos(lon));
                    mesh[n++] = x / (float)LON; mesh[n++] = 1 - y / (float)LAT;
                }
            }
            short[] cells = new short[LAT * LON * 6]; n = 0;
            for (int y = 0; y < LAT; y++) for (int x = 0; x < LON; x++) {
                int first = y * (LON + 1) + x, next = first + LON + 1;
                cells[n++] = (short)first; cells[n++] = (short)(first + 1); cells[n++] = (short)next;
                cells[n++] = (short)(first + 1); cells[n++] = (short)(next + 1); cells[n++] = (short)next;
            }
            vertices = floats(mesh); indexCount = cells.length;
            indices = ByteBuffer.allocateDirect(cells.length * 2).order(ByteOrder.nativeOrder()).asShortBuffer();
            indices.put(cells).position(0);
            float[] sky = new float[160 * 2];
            java.util.Random random = new java.util.Random(7411);
            for (int i = 0; i < sky.length; i++) sky[i] = random.nextFloat() * 2 - 1;
            stars = floats(sky); markerPoint = floats(new float[]{0, .08f});
        }

        @Override public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            ready = false; nightAvailable = false; texture = nightTexture = null;
            contextGeneration = renderGuard.beginContext();
            if (contextGeneration == 0) return;
            try {
                int[] maximum = new int[1]; GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maximum, 0);
                if (maximum[0] <= 0) throw new IllegalStateException("Missing GL texture size capability");
                maxTextureSize = maximum[0];
                int[] range = new int[2], precision = new int[1];
                GLES20.glGetShaderPrecisionFormat(GLES20.GL_FRAGMENT_SHADER, GLES20.GL_HIGH_FLOAT, range, 0, precision, 0);
                String uvPrecision = precision[0] > 0 ? "highp" : "mediump";
                // Preserve 4K coordinates without promoting the lighting's fragment arithmetic.
                planetProgram = program(PLANET_VERTEX.replace("UV_PRECISION", uvPrecision),
                        PLANET_FRAGMENT.replace("UV_PRECISION", uvPrecision));
                pointProgram = program(POINT_VERTEX, POINT_FRAGMENT);
                pPosition = GLES20.glGetAttribLocation(planetProgram, "aPosition");
                pUv = GLES20.glGetAttribLocation(planetProgram, "aUv");
                pBasis = GLES20.glGetUniformLocation(planetProgram, "uBasis");
                pFocal = GLES20.glGetUniformLocation(planetProgram, "uFocal");
                pDistance = GLES20.glGetUniformLocation(planetProgram, "uDistance");
                pSun = GLES20.glGetUniformLocation(planetProgram, "uSun");
                pTexture = GLES20.glGetUniformLocation(planetProgram, "uTexture");
                pNightTexture = GLES20.glGetUniformLocation(planetProgram, "uNightTexture");
                pNightAvailable = GLES20.glGetUniformLocation(planetProgram, "uNightAvailable");
                pEdge = GLES20.glGetUniformLocation(planetProgram, "uEdge");
                pDayNight = GLES20.glGetUniformLocation(planetProgram, "uDayNight");
                pAtmosphere = GLES20.glGetUniformLocation(planetProgram, "uAtmosphere");
                pPulse = GLES20.glGetUniformLocation(planetProgram, "uPulse");
                sPosition = GLES20.glGetAttribLocation(pointProgram, "aPosition");
                sSize = GLES20.glGetUniformLocation(pointProgram, "uSize");
                sColor = GLES20.glGetUniformLocation(pointProgram, "uColor");
                texture = loadPreferredTexture("ambient/earth_nasa_day_4096.jpg", "ambient/earth_nasa_2002.jpg");
                try {
                    nightTexture = loadPreferredTexture("ambient/earth_nasa_night_2016_4096.jpg", "ambient/earth_nasa_night_2004.png"); nightAvailable = true;
                } catch (Exception | OutOfMemoryError unavailable) {
                    // A valid sampler and readable dimmed terrain remain even without city lights.
                    nightTexture = texture;
                    Log.w("Ambient.Earth", "Night composite unavailable; keeping visible day-map fallback", unavailable);
                }
                GLES20.glClearColor(0, 0, 0, 1);
                int error = GLES20.glGetError();
                if (error != GLES20.GL_NO_ERROR) throw new IllegalStateException("Earth init GL error 0x" + Integer.toHexString(error));
                ready = true;
                Log.i("Ambient.Earth", "ready=true day=" + texture.dimensions() + " night=" + nightAvailable
                        + " nightSize=" + nightTexture.dimensions() + " maxTexture=" + maxTextureSize
                        + " uv=" + uvPrecision + " anisotropy=1 planetPasses=1 vertexLighting=true requestedDepth=0 glError=0x0");
            } catch (Exception | OutOfMemoryError failure) {
                Log.w("Ambient.Earth", "ready=false glError=0x" + Integer.toHexString(GLES20.glGetError()), failure);
                failureListener.failed(contextGeneration, failure);
            }
        }

        @Override public void onSurfaceChanged(GL10 gl, int width, int height) {
            GLES20.glViewport(0, 0, width, height);
            aspect = width / Math.max(1f, height); bufferHeight = Math.max(1, height);
            Log.i("Ambient.Earth", "surface=" + width + "x" + height + " fixedBudget=1920x1080");
        }

        @Override public void onDrawFrame(GL10 gl) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            if (!ready || renderGuard.failed(contextGeneration)) return;
            EarthCamera.State view = camera;
            // Keep the existing ~1.25-buffer-pixel rim as the real camera distance changes.
            edgeFade = (float)Math.sqrt(2.5 / Math.max(1f, bufferHeight * .5f * view.radiusY()));
            long now = System.currentTimeMillis(), minute = now / 60_000;
            if (minute != solarMinute) { AmbientMath.sunVector(now, sun); solarMinute = minute; }
            float pulse = (float)Math.sin(SystemClock.uptimeMillis() / 4000.0);
            GLES20.glDisable(GLES20.GL_DEPTH_TEST); GLES20.glDisable(GLES20.GL_CULL_FACE);
            if (showStars) {
                GLES20.glEnable(GLES20.GL_BLEND); GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
                GLES20.glUseProgram(pointProgram); pointData(stars); GLES20.glUniform1f(sSize, 1.25f);
                GLES20.glUniform4f(sColor, .7f, .76f, .86f, .24f); GLES20.glDrawArrays(GLES20.GL_POINTS, 0, 160);
                GLES20.glDisableVertexAttribArray(sPosition);
            }
            GLES20.glDisable(GLES20.GL_BLEND);
            GLES20.glEnable(GLES20.GL_CULL_FACE); GLES20.glCullFace(GLES20.GL_BACK); GLES20.glFrontFace(GLES20.GL_CCW);
            GLES20.glUseProgram(planetProgram);
            GLES20.glUniformMatrix3fv(pBasis, 1, false, view.basis, 0);
            GLES20.glUniform2f(pFocal, EarthCamera.FOCAL_Y / aspect, EarthCamera.FOCAL_Y);
            GLES20.glUniform1f(pDistance, view.distance);
            GLES20.glUniform3fv(pSun, 1, sun, 0); GLES20.glUniform1i(pTexture, 0);
            GLES20.glUniform1i(pNightTexture, 1); GLES20.glUniform1f(pNightAvailable, nightAvailable ? 1 : 0);
            GLES20.glUniform1f(pEdge, edgeFade);
            GLES20.glUniform1f(pDayNight, dayNight ? 1 : 0); GLES20.glUniform1f(pAtmosphere, atmosphere ? 1 : 0);
            GLES20.glUniform1f(pPulse, .91f + .025f * pulse);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture.id);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, nightTexture.id);
            vertices.position(0); GLES20.glVertexAttribPointer(pPosition, 3, GLES20.GL_FLOAT, false, 20, vertices);
            vertices.position(3); GLES20.glVertexAttribPointer(pUv, 2, GLES20.GL_FLOAT, false, 20, vertices);
            GLES20.glEnableVertexAttribArray(pPosition); GLES20.glEnableVertexAttribArray(pUv);
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, indexCount, GLES20.GL_UNSIGNED_SHORT, indices);
            GLES20.glDisableVertexAttribArray(pPosition); GLES20.glDisableVertexAttribArray(pUv);
            if (marker && view.markerVisible(aspect)) {
                markerPoint.put(0, view.markerX(aspect)); markerPoint.put(1, view.markerY());
                GLES20.glDisable(GLES20.GL_CULL_FACE); GLES20.glEnable(GLES20.GL_BLEND); GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
                GLES20.glUseProgram(pointProgram); pointData(markerPoint);
                GLES20.glUniform1f(sSize, 12 + pulse); GLES20.glUniform4f(sColor, .4f, .75f, 1, .20f);
                GLES20.glDrawArrays(GLES20.GL_POINTS, 0, 1);
                GLES20.glUniform1f(sSize, 4); GLES20.glUniform4f(sColor, 1, 1, 1, 1);
                GLES20.glDrawArrays(GLES20.GL_POINTS, 0, 1); GLES20.glDisableVertexAttribArray(sPosition);
            }
            GLES20.glDisable(GLES20.GL_BLEND);
        }

        private void pointData(FloatBuffer data) {
            data.position(0); GLES20.glEnableVertexAttribArray(sPosition);
            GLES20.glVertexAttribPointer(sPosition, 2, GLES20.GL_FLOAT, false, 8, data);
        }
        private Texture loadPreferredTexture(String detailed, String fallback) throws java.io.IOException {
            if (maxTextureSize >= 4096) {
                try { return loadTexture(detailed); }
                catch (Exception | OutOfMemoryError unavailable) {
                    Log.w("Ambient.Earth", "4K composite unavailable; retrying the bounded 2K fallback", unavailable);
                }
            }
            return loadTexture(fallback);
        }
        private Texture loadTexture(String asset) throws java.io.IOException {
            Bitmap image = null; int[] ids = new int[1]; boolean uploaded = false;
            try {
                // Reject accidental source-sized assets before allocating pixels on the TV.
                BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
                try (InputStream stream = context.getAssets().open(asset)) { BitmapFactory.decodeStream(stream, null, bounds); }
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth > 4096
                        || bounds.outHeight > 2048 || bounds.outWidth != bounds.outHeight * 2)
                    throw new IllegalStateException("Earth texture missing or outside its size budget");
                BitmapFactory.Options decode = new BitmapFactory.Options();
                decode.inScaled = false; decode.inPreferredConfig = Bitmap.Config.ARGB_8888; decode.inSampleSize = 1;
                while (bounds.outWidth / decode.inSampleSize > maxTextureSize
                        || bounds.outHeight / decode.inSampleSize > maxTextureSize) decode.inSampleSize *= 2;
                try (InputStream stream = context.getAssets().open(asset)) { image = BitmapFactory.decodeStream(stream, null, decode); }
                if (image == null) throw new IllegalStateException("Earth texture decode failed");
                GLES20.glGenTextures(1, ids, 0);
                if (ids[0] == 0) throw new IllegalStateException("Earth texture allocation failed");
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0]);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, image, 0); GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D);
                int error = GLES20.glGetError();
                if (error != GLES20.GL_NO_ERROR) throw new IllegalStateException("Earth texture GL error 0x" + Integer.toHexString(error));
                Texture result = new Texture(ids[0], image.getWidth(), image.getHeight());
                uploaded = true; return result;
            } finally {
                if (image != null) image.recycle();
                if (!uploaded) {
                    if (ids[0] != 0) GLES20.glDeleteTextures(1, ids, 0);
                    // A rejected 4K allocation must not poison the following 2K upload's check.
                    GLES20.glGetError();
                }
            }
        }
        private static FloatBuffer floats(float[] data) {
            FloatBuffer buffer = ByteBuffer.allocateDirect(data.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            buffer.put(data).position(0); return buffer;
        }
        private static int program(String vertex, String fragment) {
            int vs = shader(GLES20.GL_VERTEX_SHADER, vertex), fs = 0, program = 0;
            try {
                fs = shader(GLES20.GL_FRAGMENT_SHADER, fragment); program = GLES20.glCreateProgram();
                GLES20.glAttachShader(program, vs); GLES20.glAttachShader(program, fs); GLES20.glLinkProgram(program);
                int[] status = new int[1]; GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0);
                if (status[0] == 0) throw new IllegalStateException(GLES20.glGetProgramInfoLog(program));
                return program;
            } catch (RuntimeException failed) { if (program != 0) GLES20.glDeleteProgram(program); throw failed; }
            finally { GLES20.glDeleteShader(vs); if (fs != 0) GLES20.glDeleteShader(fs); }
        }
        private static int shader(int type, String source) {
            int id = GLES20.glCreateShader(type); GLES20.glShaderSource(id, source); GLES20.glCompileShader(id);
            int[] status = new int[1]; GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, status, 0);
            if (status[0] == 0) { String log = GLES20.glGetShaderInfoLog(id); GLES20.glDeleteShader(id); throw new IllegalStateException(log); }
            return id;
        }

        private static final String PROJECT = "float w=uDistance-p.z;gl_Position=vec4(p.xy*uFocal+vec2(0.,"
                + EarthCamera.CENTER_Y + "*w)," + EarthCamera.CLIP_A + "*w-" + EarthCamera.CLIP_B + ",w);";
        // Dense unit-sphere vertices interpolate signed (sun dot, facing); clamp only per fragment.
        private static final String PLANET_VERTEX = "precision highp float;attribute vec3 aPosition;attribute vec2 aUv;uniform mat3 uBasis;uniform vec2 uFocal;uniform mediump float uDistance;uniform vec3 uSun;varying mediump vec2 vLighting;varying UV_PRECISION vec2 vUv;void main(){vec3 p=uBasis*aPosition;vLighting=vec2(dot(normalize(aPosition),uSun),dot(normalize(p),normalize(vec3(0.,0.,uDistance)-p)));vUv=aUv;" + PROJECT + "}";
        // Fold the blue atmospheric rim into the opaque planet pass; no full-globe blend overdraw.
        private static final String PLANET_FRAGMENT = "precision mediump float;uniform sampler2D uTexture;uniform sampler2D uNightTexture;uniform float uNightAvailable;uniform float uEdge;uniform float uDayNight;uniform float uAtmosphere;uniform float uPulse;varying mediump vec2 vLighting;varying UV_PRECISION vec2 vUv;void main(){vec3 day=texture2D(uTexture,vUv).rgb;vec3 night=texture2D(uNightTexture,vUv).rgb;float daylight=mix(1.,smoothstep(-.12,.22,vLighting.x),uDayNight);float facing=max(0.,vLighting.y);float rim=1.-facing;float limb=rim*rim*rim;vec3 fallback=day*.24+vec3(.004,.008,.016);vec3 city=night*vec3(1.12,1.05,.90)+day*.065;vec3 color=mix(mix(fallback,city,uNightAvailable),day,daylight);color+=(vec3(.025,.12,.21)*limb*(.2+.8*daylight)+vec3(.12,.40,.70)*(limb*rim)*.36*uPulse)*uAtmosphere;color*=smoothstep(0.,uEdge,facing);gl_FragColor=vec4(color,1.);}";
        private static final String POINT_VERTEX = "attribute vec2 aPosition;uniform float uSize;void main(){gl_Position=vec4(aPosition,0.,1.);gl_PointSize=uSize;}";
        private static final String POINT_FRAGMENT = "precision mediump float;uniform vec4 uColor;void main(){float d=length(gl_PointCoord-vec2(.5));gl_FragColor=vec4(uColor.rgb,uColor.a*(1.-smoothstep(.25,.5,d)));}";
    }
}
