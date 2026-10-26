package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * A cached, high-contrast selection outline without a travelling halo or scale change.
 */
public class GlassView extends View {

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint specular = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint innerRim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final RectF innerRect = new RectF();
    private final float density;
    private float radius;

    public GlassView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        radius = 20f * density;
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(1.5f * density);
        rim.setColor(0xF2F5F6F7);
        innerRim.setStyle(Paint.Style.STROKE);
        innerRim.setStrokeWidth(1f * density);
        innerRim.setColor(0xCC0D1117);
        // Cache the small outline so short opacity transitions need only composite its texture.
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    /** Keep the focused edge neutral and equally readable over every app's artwork. */
    void setAccent(int argb) {
        // Callers can retain their accent API; focus uses one consistent white-gray rim.
    }

    void setRadius(float px) {
        radius = px;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        fill.setShader(new LinearGradient(0, 0, 0, h, 0x04FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        specular.setShader(new LinearGradient(0, 0, 0, h * 0.45f, 0x08FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float inset = 6f * density;   // keep the existing alignment envelope
        rect.set(inset, inset, getWidth() - inset, getHeight() - inset);

        canvas.drawRoundRect(rect, radius, radius, fill);

        // Sheen across the top 45%: clipRect stays on the GPU (clipPath did not on the MT5895) and
        // the round rect under it keeps the top corners rounded with a flat bottom edge.
        canvas.save();
        canvas.clipRect(rect.left, rect.top, rect.right, rect.top + rect.height() * 0.45f);
        canvas.drawRoundRect(rect, radius, radius, specular);
        canvas.restore();

        canvas.drawRoundRect(rect, radius, radius, rim);
        float innerInset = 1.25f * density;
        innerRect.set(rect); innerRect.inset(innerInset, innerInset);
        float innerRadius = Math.max(0, radius - innerInset);
        canvas.drawRoundRect(innerRect, innerRadius, innerRadius, innerRim);
    }
}
