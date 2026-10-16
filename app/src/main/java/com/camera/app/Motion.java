package com.camera.app;

import android.app.Activity;
import android.os.Build;

final class Motion {
    private Motion() {
    }

    static void install(Activity a, int openEnter, int openExit, int closeEnter, int closeExit) {
        if (Build.VERSION.SDK_INT >= 34) {
            a.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, openEnter, openExit);
            a.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, closeEnter, closeExit);
        }
    }

    @SuppressWarnings("deprecation")
    static void legacy(Activity a, int enter, int exit) {
        if (Build.VERSION.SDK_INT < 34) a.overridePendingTransition(enter, exit);
    }
}
