package com.camera.app;

/** The processing presets (what the native pipeline does after the shot). */
final class Levels {
    private Levels() {
    }

    static final String OFF = "off", LIGHT = "light", MEDIUM = "medium", STRONG = "strong";
    static final String[] ALL = {OFF, LIGHT, MEDIUM, STRONG};

    /** denoise, clahe, sharpen, awb, gamma */
    static float[] params(String level) {
        switch (level == null ? MEDIUM : level) {
            case LIGHT:
                return new float[]{0.30f, 0.15f, 0.30f, 0.00f, 0.15f};
            case STRONG:
                return new float[]{0.75f, 0.50f, 0.70f, 0.40f, 0.60f};
            case OFF:
                return null;
            default:
                return new float[]{0.50f, 0.30f, 0.50f, 0.25f, 0.35f};
        }
    }

    /** After a multi-frame merge the noise is already low, so mostly tone and detail. */
    static float[] night() {
        return new float[]{0.55f, 0.35f, 0.55f, 0.30f, 0.85f};
    }

    /** Deep capture: tens of frames are merged, so the noise is tiny and the detail can be pushed harder. */
    static float[] deep() {
        return new float[]{0.28f, 0.45f, 0.80f, 0.30f, 0.55f};
    }

    static int name(String level) {
        switch (level == null ? MEDIUM : level) {
            case OFF:
                return R.string.lvl_off;
            case LIGHT:
                return R.string.lvl_light;
            case STRONG:
                return R.string.lvl_strong;
            default:
                return R.string.lvl_medium;
        }
    }
}
