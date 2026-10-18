package com.camera.app;

import android.content.Context;

/** Processing style and the user's fine tuning (Settings) turned into the values the native pipeline takes. */
final class Style {
    private Style() {
    }

    static final int NATURAL = 0, VIVID = 1, CINEMATIC = 2, SOFT = 3, MONO = 4, WARM = 5, COOL = 6;

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** base = denoise, tone, sharpen, awb, gamma. Returns denoise, tone, sharpen, awb, gamma, vibrance, contrast, warmth, mono, dehaze. */
    static float[] build(Context c, float[] base) {
        float den = base[0], tone = base[1], sharp = base[2], awb = base[3], gam = base[4];
        float vib = 0f, con = 0f, warm = 0f, mono = 0f, haze = 0f;
        switch (Store.num(c, "pstyle", NATURAL)) {
            case VIVID:
                vib = 0.35f; con = 0.25f; haze = 0.10f;
                break;
            case CINEMATIC:
                vib = -0.05f; con = 0.45f; haze = 0.25f; sharp -= 0.10f;
                break;
            case SOFT:
                sharp -= 0.25f; den += 0.15f; con = -0.10f; vib = 0.05f;
                break;
            case MONO:
                mono = 1f; con = 0.40f;
                break;
            case WARM:
                warm = 0.6f; vib = 0.10f;
                break;
            case COOL:
                warm = -0.6f;
                break;
            default:
                break;
        }
        den += 0.12f * Store.num(c, "trim_den", 0);
        sharp += 0.12f * Store.num(c, "trim_sharp", 0);
        tone += 0.12f * Store.num(c, "trim_tone", 0);
        vib += 0.10f * Store.num(c, "trim_color", 0);
        haze += 0.25f * Store.num(c, "dehaze", 0);
        return new float[]{clamp(den, 0f, 1f), clamp(tone, 0f, 1f), clamp(sharp, 0f, 1f), awb, gam,
                clamp(vib, -0.4f, 1f), clamp(con, -0.3f, 1f), warm, mono, clamp(haze, 0f, 1f)};
    }
}
