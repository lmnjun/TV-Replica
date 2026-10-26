package com.gathin.tvhome;

import android.service.dreams.DreamService;

/**
 * Context-only selected screensaver, with a visible local clock if a scene is unavailable.
 */
public class ClockDream extends DreamService {

    private ClockScene scene;
    private boolean dreaming;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);   // any key wakes the TV back to where it was
        setFullscreen(true);
        setScreenBright(false);
        if (scene != null) scene.close();
        setContentView(R.layout.dream_clock);
        scene = new ClockScene(getWindow().getDecorView());
        if (dreaming) scene.start();
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        dreaming = true;
        if (scene != null) scene.start();
    }

    @Override
    public void onDreamingStopped() {
        dreaming = false;
        if (scene != null) scene.stop();
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        if (scene != null) { scene.close(); scene = null; }
        super.onDetachedFromWindow();
    }

    @Override
    public void onDestroy() {
        if (scene != null) { scene.close(); scene = null; }
        super.onDestroy();
    }
}
