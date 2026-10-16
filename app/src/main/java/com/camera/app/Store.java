package com.camera.app;

import android.content.Context;
import android.content.SharedPreferences;

/** Tiny preference store shared by every screen. */
public final class Store {
    private Store() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("cam", Context.MODE_PRIVATE);
    }

    public static String str(Context c, String k, String d) {
        return sp(c).getString(k, d);
    }

    public static void setStr(Context c, String k, String v) {
        sp(c).edit().putString(k, v).apply();
    }

    public static boolean bool(Context c, String k, boolean d) {
        return sp(c).getBoolean(k, d);
    }

    public static void setBool(Context c, String k, boolean v) {
        sp(c).edit().putBoolean(k, v).apply();
    }

    public static int num(Context c, String k, int d) {
        return sp(c).getInt(k, d);
    }

    public static void setNum(Context c, String k, int v) {
        sp(c).edit().putInt(k, v).apply();
    }

    /** "system", "ar" or "en". */
    public static String language(Context c) {
        return str(c, "lang", "system");
    }

    public static void setLanguage(Context c, String v) {
        setStr(c, "lang", v == null ? "system" : v);
    }

    /** "auto" (follows the system), "light" or "dark". The camera screen itself is always dark. */
    public static String themeMode(Context c) {
        return str(c, "theme_mode", "auto");
    }

    public static void setThemeMode(Context c, String v) {
        setStr(c, "theme_mode", v == null ? "auto" : v);
    }
}
