package com.gathin.tvhome;

/** In-memory remote camera controls. The owner saves its final values once on exit. */
final class EarthExploreState {
    // Stable public Android KeyEvent values, kept here without an Android runtime dependency.
    static final int DPAD_UP = 19, DPAD_DOWN = 20, DPAD_LEFT = 21, DPAD_RIGHT = 22, DPAD_CENTER = 23;
    static final int ENTER = 66, MEDIA_REWIND = 89, MEDIA_FAST_FORWARD = 90, NUMPAD_ENTER = 160;
    static final int CHANNEL_UP = 166, CHANNEL_DOWN = 167;
    private static final float MIN_ZOOM = .65f, MAX_ZOOM = 1.6f;
    private float zoom, yaw, pitch;

    EarthExploreState(float zoom, float yaw, float pitch) {
        this.zoom = normalizeZoom(zoom); this.yaw = normalizeYaw(yaw); this.pitch = normalizePitch(pitch);
    }

    float zoom() { return zoom; }
    float yaw() { return yaw; }
    float pitch() { return pitch; }

    /** Also use this on key-up, without calling handle() a second time. */
    static boolean accepts(int keyCode) {
        switch (keyCode) {
            case DPAD_UP: case DPAD_DOWN: case DPAD_LEFT: case DPAD_RIGHT:
            case DPAD_CENTER: case ENTER: case NUMPAD_ENTER:
            case CHANNEL_UP: case CHANNEL_DOWN: case MEDIA_FAST_FORWARD: case MEDIA_REWIND:
                return true;
            default: return false;
        }
    }

    /** Call only for key-down. True means consumed, including a clamped or held OK key. */
    boolean handle(int keyCode, int repeatCount) {
        boolean repeated = repeatCount > 0;
        float zoomStep = repeated ? 1.04f : 1.08f;
        switch (keyCode) {
            case DPAD_LEFT: yaw = normalizeYaw(yaw - (repeated ? 4 : 8)); break;
            case DPAD_RIGHT: yaw = normalizeYaw(yaw + (repeated ? 4 : 8)); break;
            case DPAD_UP: pitch = normalizePitch(pitch + (repeated ? 3 : 5)); break;
            case DPAD_DOWN: pitch = normalizePitch(pitch - (repeated ? 3 : 5)); break;
            case CHANNEL_UP: case MEDIA_FAST_FORWARD: zoom = normalizeZoom(zoom * zoomStep); break;
            case CHANNEL_DOWN: case MEDIA_REWIND: zoom = normalizeZoom(zoom / zoomStep); break;
            case DPAD_CENTER: case ENTER: case NUMPAD_ENTER:
                if (!repeated) zoom = zoom >= 1.25f ? 1 : 1.5f;
                break;
            default: return false;
        }
        return true;
    }

    private static float normalizeZoom(float value) {
        return Float.isFinite(value) ? Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, value)) : 1;
    }
    private static float normalizeYaw(float value) {
        if (!Float.isFinite(value)) return 0;
        float result = value % 360;
        if (result < -180) result += 360;
        else if (result >= 180) result -= 360;
        return result == 0 ? 0 : result;
    }
    private static float normalizePitch(float value) {
        if (!Float.isFinite(value)) return 0;
        float result = Math.max(-70, Math.min(70, value));
        return result == 0 ? 0 : result;
    }
}
