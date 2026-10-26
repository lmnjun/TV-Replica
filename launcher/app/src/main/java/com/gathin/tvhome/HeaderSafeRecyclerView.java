package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.MotionEvent;
import androidx.recyclerview.widget.RecyclerView;

/** Render clipping must also prevent a hidden app from accepting a new pointer gesture. */
public final class HeaderSafeRecyclerView extends RecyclerView {
    private final Rect touchClip = new Rect();
    private boolean ignoredGesture;

    public HeaderSafeRecyclerView(Context context) { super(context); }
    public HeaderSafeRecyclerView(Context context, AttributeSet attrs) { super(context, attrs); }
    public HeaderSafeRecyclerView(Context context, AttributeSet attrs, int style) { super(context, attrs, style); }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            ignoredGesture = getClipBounds(touchClip) && (event.getX() < touchClip.left
                    || event.getX() >= touchClip.right || event.getY() < touchClip.top || event.getY() >= touchClip.bottom);
        }
        if (ignoredGesture) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) ignoredGesture = false;
            return false;
        }
        return super.dispatchTouchEvent(event);
    }
}
