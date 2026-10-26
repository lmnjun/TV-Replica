package com.gathin.tvhome;

import android.os.Trace;
import android.util.Log;
import java.util.concurrent.atomic.AtomicInteger;

/** Sparse UI-thread phase markers, using the same monotonic clock as the key/present capture. */
final class MenuTrace {
    private static final AtomicInteger NEXT = new AtomicInteger();
    private final int id = NEXT.incrementAndGet();
    private String operation = "prepare";
    private long operationStart;
    private int draws;

    long begin(String phase) {
        String section = "Menu/" + phase;
        Trace.beginSection(section.length() > 120 ? section.substring(0, 120) : section);
        return System.nanoTime();
    }

    void end(String phase, long start) {
        long end = System.nanoTime();
        Trace.endSection();
        Log.i("Menu.Perf", "id=" + id + " phase=" + phase + " start_ns=" + start
                + " end_ns=" + end + " duration_ms=" + (end - start) / 1_000_000.0);
    }

    void point(String phase) {
        Log.i("Menu.Perf", "id=" + id + " phase=" + phase + " at_ns=" + System.nanoTime());
    }

    void operation(String value) {
        operation = value; operationStart = System.nanoTime(); draws = 0;
        point(value);
    }

    long beginDraw() { Trace.beginSection("Menu/cardRecord"); return System.nanoTime(); }

    void endDraw(long start) {
        long end = System.nanoTime();
        Trace.endSection();
        int frame = ++draws;
        if (frame <= 3 || end - start > 8_000_000L)
            Log.i("Menu.Perf", "id=" + id + " phase=card_record operation=" + operation
                    + " frame=" + frame + " start_ns=" + start + " end_ns=" + end
                    + " duration_ms=" + (end - start) / 1_000_000.0
                    + " since_operation_ms=" + (operationStart == 0 ? 0 : (start - operationStart) / 1_000_000.0));
    }
}
