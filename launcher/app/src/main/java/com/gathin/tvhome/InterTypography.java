package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Typeface;
import android.widget.TextView;

/** Explicit Inter variable-font instances; no platform font fallback or synthetic bold. */
final class InterTypography {
    private InterTypography() {}

    static Typeface face(Context context, int weight) {
        int resource = weight == 700 ? R.font.inter_bold
                : weight == 600 ? R.font.inter_semibold
                : weight == 500 ? R.font.inter_medium : R.font.inter_regular;
        return context.getResources().getFont(resource);
    }

    static void apply(TextView view, int weight) {
        view.setTypeface(face(view.getContext(), weight));
    }
}
