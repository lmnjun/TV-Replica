package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** Static compact connection circle; existing Google Material Sharp Wi-Fi/Ethernet geometry. */
final class ConnectionBadge extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path wifi = new Path(), ethernet = new Path();
    private final float density;
    private ConnectionMonitor.Transport transport = ConnectionMonitor.Transport.OTHER;
    private boolean known;
    private float centerX, centerY, radius, iconLeft, iconTop, iconScale;

    ConnectionBadge(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        setFocusable(false); setClickable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        // Same Apache-2.0 Material Symbols paths as ic_wifi/ic_ethernet, in a 960-unit viewport.
        // Building the paths in code keeps construction independent of drawable/XML file loading.
        wifi.moveTo(254, 614); wifi.lineTo(170, 528); wifi.quadTo(232, 466, 312, 433);
        wifi.quadTo(392, 400, 480, 400); wifi.quadTo(568, 400, 648.5f, 433.5f);
        wifi.quadTo(729, 467, 790, 530); wifi.lineTo(706, 614);
        wifi.quadTo(661, 569, 602.5f, 544.5f); wifi.quadTo(544, 520, 480, 520);
        wifi.quadTo(416, 520, 357.5f, 544.5f); wifi.quadTo(299, 569, 254, 614); wifi.close();
        wifi.moveTo(84, 444); wifi.lineTo(0, 360); wifi.quadTo(95, 263, 219.5f, 211.5f);
        wifi.quadTo(344, 160, 480, 160); wifi.quadTo(616, 160, 740.5f, 211.5f);
        wifi.quadTo(865, 263, 960, 360); wifi.lineTo(876, 444);
        wifi.quadTo(797, 365, 694.5f, 322.5f); wifi.quadTo(592, 280, 480, 280);
        wifi.quadTo(368, 280, 265.5f, 322.5f); wifi.quadTo(163, 365, 84, 444); wifi.close();
        wifi.moveTo(480, 840); wifi.lineTo(339, 698); wifi.quadTo(367, 670, 403.5f, 655);
        wifi.quadTo(440, 640, 480, 640); wifi.quadTo(520, 640, 556.5f, 655);
        wifi.quadTo(593, 670, 621, 698); wifi.close();
        ethernet.moveTo(680, 720); ethernet.lineTo(624, 664); ethernet.lineTo(806, 480);
        ethernet.lineTo(624, 296); ethernet.lineTo(680, 240); ethernet.lineTo(920, 480); ethernet.close();
        ethernet.moveTo(280, 720); ethernet.lineTo(40, 480); ethernet.lineTo(280, 240);
        ethernet.lineTo(336, 296); ethernet.lineTo(154, 480); ethernet.lineTo(336, 664); ethernet.close();
        ethernet.addCircle(320, 480, 40, Path.Direction.CW);
        ethernet.addCircle(480, 480, 40, Path.Direction.CW);
        ethernet.addCircle(640, 480, 40, Path.Direction.CW);
    }

    boolean setSnapshot(ConnectionMonitor.Snapshot snapshot) {
        if (transport == snapshot.transport && known == snapshot.known) return false;
        transport = snapshot.transport; known = snapshot.known; invalidate(); return true;
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        centerX = width * .5f; centerY = height * .5f;
        radius = Math.max(0, Math.min(width, height) * .5f - .5f * density);
        float iconEdge = Math.min(width, height) * .64f;
        iconLeft = centerX - iconEdge * .5f; iconTop = centerY - iconEdge * .5f; iconScale = iconEdge / 960f;
    }

    @Override protected void onDraw(Canvas canvas) {
        paint.setStyle(Paint.Style.FILL); paint.setColor(0x40252525);
        canvas.drawCircle(centerX, centerY, radius, paint);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(Math.max(1, .5f * density)); paint.setColor(0x36FFFFFF);
        canvas.drawCircle(centerX, centerY, radius, paint);
        paint.setStyle(Paint.Style.FILL); paint.setColor(0xE0FFFFFF);
        if (!known) {
            float dot = .75f * density, gap = 3f * density;
            canvas.drawCircle(centerX - gap, centerY, dot, paint);
            canvas.drawCircle(centerX, centerY, dot, paint);
            canvas.drawCircle(centerX + gap, centerY, dot, paint);
            return;
        }
        int saved = canvas.save(); canvas.translate(iconLeft, iconTop); canvas.scale(iconScale, iconScale);
        if (transport == ConnectionMonitor.Transport.ETHERNET) canvas.drawPath(ethernet, paint);
        else if (transport == ConnectionMonitor.Transport.OTHER) {
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(65);
            canvas.drawCircle(480, 480, 320, paint); canvas.drawOval(320, 160, 640, 800, paint);
            canvas.drawLine(160, 480, 800, 480, paint);
        }
        else {
            if (transport == ConnectionMonitor.Transport.OFFLINE) paint.setColor(0x88FFFFFF);
            canvas.drawPath(wifi, paint);
            if (transport == ConnectionMonitor.Transport.OFFLINE) {
                // A static hard slash makes offline distinct without colour or a looping animation.
                paint.setStyle(Paint.Style.STROKE); paint.setStrokeCap(Paint.Cap.SQUARE);
                paint.setStrokeWidth(145); paint.setColor(0xFF202020); canvas.drawLine(120, 150, 840, 870, paint);
                paint.setStrokeWidth(70); paint.setColor(0xE6FFFFFF); canvas.drawLine(120, 150, 840, 870, paint);
            }
        }
        canvas.restoreToCount(saved);
    }
}
