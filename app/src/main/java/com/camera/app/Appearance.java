package com.camera.app;

import android.content.Context;
import android.content.res.Configuration;

import androidx.appcompat.app.AppCompatDelegate;

/**
 * App appearance, iPhone style: only two looks, <b>Light</b> (white, grouped grey) and <b>Dark</b> (true AMOLED black),
 * plus <b>Auto</b> which follows the system like iOS does. There are no colour themes any more; the accent is
 * always iOS blue. Implemented with the normal resource qualifiers (values / values-night) and
 * {@link AppCompatDelegate#setDefaultNightMode}.
 */
public final class Appearance {
    private Appearance() {
    }

    public static final String LIGHT = "light", DARK = "dark", AUTO = "auto";

    /** Pushes the saved mode to AppCompat. Open screens recreate themselves when the effective mode changes. */
    public static void applyMode(Context c) {
        String m = Store.themeMode(c);
        int mode = DARK.equals(m) ? AppCompatDelegate.MODE_NIGHT_YES
                : LIGHT.equals(m) ? AppCompatDelegate.MODE_NIGHT_NO
                : AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
        AppCompatDelegate.setDefaultNightMode(mode);
    }

    public static int modeName(String mode) {
        if (DARK.equals(mode)) return R.string.theme_dark;
        if (LIGHT.equals(mode)) return R.string.theme_light;
        return R.string.theme_auto;
    }

    /** True when this context is currently drawn with the night resources. */
    public static boolean isNight(Context c) {
        return (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }
}
