package com.camera.app;

import android.app.Activity;
import android.graphics.Color;
import android.os.Build;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import androidx.core.graphics.Insets;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

final class Bars {
    private Bars() {
    }

    static void edgeToEdge(Activity a) {
        try {
            Window w = a.getWindow();
            WindowCompat.setDecorFitsSystemWindows(w, false);
            w.setNavigationBarColor(Color.TRANSPARENT);
            if (Build.VERSION.SDK_INT >= 29) w.setNavigationBarContrastEnforced(false);
            if (Build.VERSION.SDK_INT >= 28) {
                w.getAttributes().layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            }
        } catch (Throwable ignored) {
        }
    }

    static void hideStatus(Activity a) {
        hideStatus(a.getWindow());
    }

    static void hideStatus(Window w) {
        try {
            if (w == null) return;
            View decor = w.getDecorView();
            WindowInsetsControllerCompat c = WindowCompat.getInsetsController(w, decor);
            c.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            c.hide(WindowInsetsCompat.Type.statusBars());
        } catch (Throwable ignored) {
        }
    }

    static Insets safe(Activity a, WindowInsetsCompat w) {
        Insets sides = w.getInsets(WindowInsetsCompat.Type.navigationBars()
                | WindowInsetsCompat.Type.displayCutout() | WindowInsetsCompat.Type.ime());
        int top = w.getInsets(WindowInsetsCompat.Type.displayCutout()).top;
        if (Build.VERSION.SDK_INT >= 24 && a.isInMultiWindowMode()) {
            top = Math.max(top, w.getInsets(WindowInsetsCompat.Type.statusBars()).top);
        }
        return Insets.of(sides.left, top, sides.right, sides.bottom);
    }
}
