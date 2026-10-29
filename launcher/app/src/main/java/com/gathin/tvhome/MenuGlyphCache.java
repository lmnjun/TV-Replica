package com.gathin.tvhome;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.VectorDrawable;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded immutable glyph pixels shared across menu rows; each row owns its Drawable. */
final class MenuGlyphCache {
    private static final int MAX_ENTRIES = 32, MAX_BYTES = 512 * 1024, MAX_EDGE = 96;
    private static final LinkedHashMap<Key, Pixels> CACHE = new LinkedHashMap<>(32, .75f, true);
    private static Configuration configuration;
    private static String resourcePackage;
    private static int bytes;
    private static long hits, misses;

    private MenuGlyphCache() { }

    /** Static, explicitly tinted vector resources only; unsupported/dynamic sources retain vector rendering. */
    static Drawable acquire(Context context, int resource, Drawable source, ColorStateList tint,
                            int edge, int layoutDirection) {
        if (resource == 0 || !(source instanceof VectorDrawable) || source.isStateful()
                || source.canApplyTheme() || source.getAlpha() != 255 || source.getColorFilter() != null
                || tint == null || tint.isStateful() || edge <= 0 || edge > MAX_EDGE) return null;
        try {
            Resources resources = context.getResources();
            Bitmap bitmap;
            synchronized (CACHE) {
                Configuration current = resources.getConfiguration();
                String currentPackage = context.getPackageName();
                if (configuration == null || !configuration.equals(current) || !currentPackage.equals(resourcePackage)) {
                    CACHE.clear(); bytes = 0;
                    configuration = new Configuration(current); resourcePackage = currentPackage;
                }
                Key key = new Key(resource, tint.getDefaultColor(), edge, layoutDirection, source.isAutoMirrored());
                Pixels cached = CACHE.get(key);
                if (cached != null && cached.bitmap.isRecycled()) { CACHE.remove(key); bytes -= cached.bytes; cached = null; }
                bitmap = cached == null ? null : cached.bitmap;
                if (bitmap == null) {
                    misses++;
                    bitmap = rasterize(source, edge, layoutDirection, resources.getDisplayMetrics().densityDpi);
                    if (bitmap == null) return null;
                    Pixels pixels = new Pixels(bitmap); CACHE.put(key, pixels); bytes += pixels.bytes;
                    Iterator<Map.Entry<Key, Pixels>> oldest = CACHE.entrySet().iterator();
                    while ((CACHE.size() > MAX_ENTRIES || bytes > MAX_BYTES) && oldest.hasNext()) {
                        Pixels retired = oldest.next().getValue(); bytes -= retired.bytes; oldest.remove();
                        // Live rows can still draw an evicted immutable bitmap. Never recycle it.
                    }
                } else hits++;
            }
            BitmapDrawable drawable = (BitmapDrawable)new BitmapDrawable(resources, bitmap).mutate();
            drawable.setFilterBitmap(false); drawable.setDither(false);
            return drawable;
        } catch (RuntimeException | OutOfMemoryError unavailable) {
            return null;
        }
    }

    private static Bitmap rasterize(Drawable source, int edge, int direction, int density) {
        Rect originalBounds = new Rect(source.getBounds());
        int originalDirection = source.getLayoutDirection();
        Drawable.Callback callback = source.getCallback();
        Bitmap mutable = null;
        Canvas canvas = null;
        source.setCallback(null);
        try {
            mutable = Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888);
            mutable.setDensity(density); canvas = new Canvas(mutable);
            source.setLayoutDirection(direction);
            int width = source.getIntrinsicWidth(), height = source.getIntrinsicHeight();
            if (width <= 0 || height <= 0) source.setBounds(0, 0, edge, edge);
            else {
                source.setBounds(0, 0, width, height);
                float scale = Math.min(edge / (float)width, edge / (float)height);
                canvas.translate((edge - width * scale) * .5f, (edge - height * scale) * .5f);
                canvas.scale(scale, scale);
            }
            source.draw(canvas);
            Bitmap immutable = mutable.copy(Bitmap.Config.ARGB_8888, false);
            if (immutable != null) immutable.prepareToDraw();
            return immutable;
        } finally {
            source.setBounds(originalBounds); source.setLayoutDirection(originalDirection); source.setCallback(callback);
            if (canvas != null) canvas.setBitmap(null);
            if (mutable != null) mutable.recycle();
        }
    }

    static int entryCount() { synchronized (CACHE) { return CACHE.size(); } }
    static int allocationBytes() { synchronized (CACHE) { return bytes; } }
    static long hitCount() { synchronized (CACHE) { return hits; } }
    static long missCount() { synchronized (CACHE) { return misses; } }

    private static final class Pixels {
        final Bitmap bitmap; final int bytes;
        Pixels(Bitmap bitmap) { this.bitmap = bitmap; bytes = bitmap.getAllocationByteCount(); }
    }

    private static final class Key {
        final int resource, tint, edge, direction;
        final boolean mirrored;
        Key(int resource, int tint, int edge, int direction, boolean mirrored) {
            this.resource = resource; this.tint = tint; this.edge = edge; this.direction = direction; this.mirrored = mirrored;
        }
        @Override public int hashCode() {
            int hash = resource; hash = hash * 31 + tint; hash = hash * 31 + edge;
            return (hash * 31 + direction) * 31 + (mirrored ? 1 : 0);
        }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Key)) return false;
            Key key = (Key)other;
            return resource == key.resource && tint == key.tint && edge == key.edge
                    && direction == key.direction && mirrored == key.mirrored;
        }
    }
}
