package com.camera.app;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

/** Saves the stack trace of a crash so the next launch can show it (copy it and send it to the developer). */
final class CrashLog {
    private CrashLog() {
    }

    private static File file(Context c) {
        return new File(c.getFilesDir(), "last_crash.txt");
    }

    static void install(final Context app) {
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                String s = "thread: " + t.getName() + "\n" + sw;
                if (s.length() > 7000) s = s.substring(0, 7000);
                try (FileOutputStream out = new FileOutputStream(file(app))) {
                    out.write(s.getBytes(StandardCharsets.UTF_8));
                }
            } catch (Throwable ignored) {
            }
            if (prev != null) prev.uncaughtException(t, e);
        });
    }

    /** The saved trace (and removes it), or null. */
    static String take(Context c) {
        File f = file(c);
        if (!f.exists()) return null;
        try {
            byte[] b = new byte[(int) Math.min(f.length(), 8000)];
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                int n = in.read(b);
                f.delete();
                return n > 0 ? new String(b, 0, n, StandardCharsets.UTF_8) : null;
            }
        } catch (Throwable e) {
            f.delete();
            return null;
        }
    }
}
