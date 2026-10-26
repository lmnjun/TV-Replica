package com.gathin.tvhome;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/** A quiet, translucent Dock surface: two cached round-rect draws, no live blur or glow. */
public class DockDecoration extends RecyclerView.ItemDecoration {
    private static final int[] FILL_COLORS = {0x0C9A9A9A, 0x0B202020, 0x17101010};
    private final int dockSize;
    private final float density;
    private final float namesRoom;
    private float radiusX, radiusY;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final RectF materialRect = new RectF();
    private float materialWidth = -1, materialHeight = -1;

    DockDecoration(int dockSize, float density, boolean translucent, boolean names) {
        this.dockSize = dockSize;
        this.density = density;
        namesRoom = names ? 22 * density : 0;
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(.55f * density);
        rim.setColor(0x2DD6D6D6);
    }

    @Override public void getItemOffsets(@NonNull Rect out, @NonNull View view,
                                         @NonNull RecyclerView parent,
                                         @NonNull RecyclerView.State state) {
        out.set(0, 0, 0, 0);
    }

    private void updateMaterial(float width, float height) {
        if (materialWidth == width && materialHeight == height) return;
        materialWidth = width;
        materialHeight = height;
        materialRect.set(0, 0, width, height);
        // The material moves with the Dock. Scrolling only translates the canvas and reuses this shader.
        fill.setShader(new LinearGradient(0, 0, 0, height, FILL_COLORS, null, Shader.TileMode.CLAMP));
    }

    @Override public void onDraw(@NonNull Canvas canvas, @NonNull RecyclerView parent,
                                  @NonNull RecyclerView.State state) {
        boolean any = false;
        float tileRadius = 0;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            int position = parent.getChildAdapterPosition(child);
            if (position < 0 || position >= dockSize) continue;
            View card = child.findViewById(R.id.card);
            if (card == null) continue;
            float left = child.getLeft() + card.getLeft();
            float top = child.getTop() + card.getTop() + child.getTranslationY();
            if (!any) {
                rect.set(left, top, left + card.getWidth(), top + card.getHeight());
                tileRadius = Math.min(AppAdapter.cornerRadius(card.getWidth(), density),
                        Math.min(card.getWidth(), card.getHeight()) * .5f);
                any = true;
            } else {
                rect.union(left, top, left + card.getWidth(), top + card.getHeight());
            }
        }
        if (!any || rect.width() <= 0 || rect.height() <= 0) return;

        // Preserve the existing band's size, 10dp vertical padding, side clearance and name room.
        float pad = 10 * density;
        float side = Math.max(0f, Math.min(16 * density,
                Math.min(rect.left, parent.getWidth() - rect.right) - density));
        rect.inset(-side, -pad);
        rect.bottom += namesRoom;
        // Different horizontal/vertical gutters need matching elliptical outer corners: their
        // centres then line up with the card corners instead of pinching the wider side gutter.
        radiusX = Math.min(tileRadius + side, rect.width() * .5f);
        radiusY = Math.min(tileRadius + pad, rect.height() * .5f);
        updateMaterial(rect.width(), rect.height());

        int save = canvas.save();
        canvas.translate(rect.left, rect.top);
        // Native round-rect primitives avoid rebuilding/tessellating a Path during navigation.
        canvas.drawRoundRect(materialRect, radiusX, radiusY, fill);
        canvas.drawRoundRect(materialRect, radiusX, radiusY, rim);
        canvas.restoreToCount(save);
    }
}
