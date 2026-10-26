package com.gathin.tvhome;

import android.content.Context;
import android.util.AttributeSet;
import android.graphics.*;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import java.io.File;
import java.lang.ref.WeakReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

/** Static wallpaper glass. No continuously running blur or background services. */
public class FrostedPanel extends SoundKeyLayout {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService WALLPAPER_WORKER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            task.run();
        }, "FrostedPanel-wallpaper");
        thread.setDaemon(true);
        return thread;
    });
    /** The latest small, immutable blur can be shared; each panel retains its own shader matrix. */
    private static volatile CachedWallpaper cachedWallpaper;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF bounds = new RectF();
    private final Matrix textureMatrix = new Matrix();
    private final int[] screenLocation = new int[2];
    private BitmapShader texture;
    private Bitmap bitmap;
    private boolean loaded, loadRequested;
    private volatile boolean attached;
    private volatile int loadGeneration;
    private int textureX = Integer.MIN_VALUE, textureY = Integer.MIN_VALUE;
    private int textureScreenW, textureScreenH;
    private float morphW,morphH,morphRadius,material=1f;
    private final SettingsSurface surface;
    void prepareMorph(boolean focused){
        surface.setEmphasized(focused);
        material=0;requestBackgroundLoad();
    }
    void morphMaterial(float value){material=value;invalidate();}
    void morph(float w,float h,float r){morphW=w;morphH=h;morphRadius=r;invalidate();}
    private final float dp;
    public FrostedPanel(Context context, AttributeSet attrs) {
        super(context, attrs); dp = getResources().getDisplayMetrics().density; surface = new SettingsSurface(dp);
        setWillNotDraw(false);
    }
    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true; loaded = false;
        CachedWallpaper cached = cachedWallpaper;
        if (cached != null) installBitmap(cached.bitmap);
        requestBackgroundLoad();
    }
    @Override protected void onDetachedFromWindow() {
        attached = false; loadGeneration++; loadRequested = false; loaded = false;
        // Do not recycle a finished bitmap: HWUI and other attached panels may still use it.
        super.onDetachedFromWindow();
    }
    private void requestBackgroundLoad() {
        if (!attached || loaded || loadRequested) return;
        loadRequested = true;
        final int generation = ++loadGeneration;
        final Context app = getContext().getApplicationContext();
        final WeakReference<FrostedPanel> target = new WeakReference<>(this);
        WALLPAPER_WORKER.execute(() -> {
            if (!isCurrent(target, generation)) return;
            CachedWallpaper ready = loadWallpaper(app, () -> isCurrent(target, generation));
            if (ready == null || !isCurrent(target, generation)) return;
            MAIN.post(() -> {
                FrostedPanel panel = target.get();
                if (panel == null || !panel.attached || panel.loadGeneration != generation) return;
                panel.loadRequested = false; panel.loaded = true;
                panel.installBitmap(ready.bitmap);
            });
        });
    }
    private static boolean isCurrent(WeakReference<FrostedPanel> target, int generation) {
        FrostedPanel panel = target.get();
        return panel != null && panel.attached && panel.loadGeneration == generation;
    }
    /** All directory lookup, file metadata, decoding and blur work stays on the single worker. */
    private static CachedWallpaper loadWallpaper(Context app, BooleanSupplier current) {
        try {
            File dir = Config.dir(app);
            File file = dir == null ? null : new File(dir, Config.WALLPAPER_NAME);
            String path = file == null ? "" : file.getPath();
            long modified = file == null ? 0 : file.lastModified(), size = file == null ? 0 : file.length();
            CachedWallpaper cached = cachedWallpaper;
            if (cached != null && cached.matches(path, modified, size)) return cached;
            if (!current.getAsBoolean()) return null;
            Bitmap blurred = file == null ? null : blurWallpaper(file, current);
            if (!current.getAsBoolean()) return null;
            CachedWallpaper ready = new CachedWallpaper(path, modified, size, blurred);
            // A replaced wallpaper must not leave a cache entry bearing the wrong file version.
            if (file == null || file.lastModified() == modified && file.length() == size) cachedWallpaper = ready;
            return ready;
        } catch (RuntimeException | OutOfMemoryError unavailable) {
            CachedWallpaper cached = cachedWallpaper;
            return cached != null ? cached : new CachedWallpaper("", 0, 0, null);
        }
    }
    private static Bitmap blurWallpaper(File file, BooleanSupplier current) {
        BitmapFactory.Options opts = new BitmapFactory.Options(); opts.inSampleSize = 24;
        Bitmap source = BitmapFactory.decodeFile(file.getPath(), opts);
        if (source == null) return null;
        int w=source.getWidth(),h=source.getHeight();
        int[] pixels;
        try {
            pixels=new int[Math.multiplyExact(w,h)];
            source.getPixels(pixels,0,w,0,0,w,h);
        } finally {
            // This decoded source is private to the worker and has never been handed to HWUI.
            source.recycle();
        }
        int[] next=new int[pixels.length];
        for(int pass=0;pass<3;pass++) {
            if (!current.getAsBoolean()) return null;
            for(int y=0;y<h;y++) for(int x=0;x<w;x++) {
                int r=0,g=0,b=0,n=0;
                for(int dy=-2;dy<=2;dy++) for(int dx=-2;dx<=2;dx++) {
                    int c=pixels[Math.max(0,Math.min(h-1,y+dy))*w+Math.max(0,Math.min(w-1,x+dx))];
                    r+=(c>>>16)&255;g+=(c>>>8)&255;b+=c&255;n++;
                }
                next[y*w+x]=0xFF000000|((r/n)<<16)|((g/n)<<8)|(b/n);
            }
            int[] previous=pixels; pixels=next; next=previous;
        }
        return Bitmap.createBitmap(pixels,w,h,Bitmap.Config.ARGB_8888);
    }
    private void installBitmap(Bitmap next) {
        if (bitmap == next) return;
        bitmap = next;
        texture = next == null ? null : new BitmapShader(next,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP);
        textureX = Integer.MIN_VALUE; textureY = Integer.MIN_VALUE;
        invalidate();
    }
    private static final class CachedWallpaper {
        final String path; final long modified, size; final Bitmap bitmap;
        CachedWallpaper(String path, long modified, long size, Bitmap bitmap) {
            this.path=path; this.modified=modified; this.size=size; this.bitmap=bitmap;
        }
        boolean matches(String path, long modified, long size) {
            return this.path.equals(path) && this.modified == modified && this.size == size;
        }
    }
    @Override protected void onDraw(Canvas c) {
        float w=morphW>0?morphW:getWidth(),h=morphH>0?morphH:getHeight(),inset=surface.inset(material);
        bounds.set(inset,inset,w-inset,h-inset);float radius=morphRadius>0?morphRadius:23*dp;
        paint.setStyle(Paint.Style.FILL);paint.setAlpha(255);
        if(texture!=null) {
            getLocationOnScreen(screenLocation);
            int sw=getResources().getDisplayMetrics().widthPixels,sh=getResources().getDisplayMetrics().heightPixels;
            if (textureX != screenLocation[0] || textureY != screenLocation[1]
                    || textureScreenW != sw || textureScreenH != sh) {
                float scale=Math.max((float)sw/bitmap.getWidth(),(float)sh/bitmap.getHeight());
                textureMatrix.setScale(scale,scale);
                textureMatrix.postTranslate((sw-bitmap.getWidth()*scale)/2-screenLocation[0],
                        (sh-bitmap.getHeight()*scale)/2-screenLocation[1]);
                texture.setLocalMatrix(textureMatrix);
                textureX=screenLocation[0];textureY=screenLocation[1];textureScreenW=sw;textureScreenH=sh;
            }
            paint.setShader(texture);paint.setAlpha(Math.round(215*material));
            c.drawRoundRect(bounds,radius,radius,paint);
        }
        paint.setShader(null);
        surface.drawSurface(c,w,h,radius,material);
        super.onDraw(c);
    }
}
