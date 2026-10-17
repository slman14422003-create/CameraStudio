package com.camera.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Everything heavy happens here, on one background thread, one photo at a time (the A16 is a mid-range phone:
 * decoding two 12 MP photos in parallel would be slower and could run out of memory). The native code itself
 * uses every core. Whatever goes wrong, the camera's own JPEG is saved, a shot is never lost.
 */
final class Processor {
    private Processor() {
    }

    interface Done {
        /** Called on the main thread. {@code uri} is null only if even the fallback save failed. */
        void onDone(Uri uri, boolean enhanced);
    }

    /** Normal limit; the 50 MP mode is processed too (in bands, about +40 MB) when the phone has free memory. */
    static final long MAX_PIXELS = 20_000_000L;

    private static long limit(Context c) {
        try {
            android.app.ActivityManager am = (android.app.ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            if (!mi.lowMemory && mi.availMem > 900L * 1024 * 1024) return 60_000_000L;
        } catch (Throwable ignored) {
        }
        return MAX_PIXELS;
    }

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "cam-processing");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicInteger PENDING = new AtomicInteger();

    static int pending() {
        return PENDING.get();
    }

    private static void finish(Done d, Uri uri, boolean enhanced) {
        PENDING.decrementAndGet();
        if (d != null) MAIN.post(() -> d.onDone(uri, enhanced));
    }

    static void shot(Context ctx, byte[] jpeg, int rotation, String level, int quality, Done d) {
        final Context app = ctx.getApplicationContext();
        PENDING.incrementAndGet();
        EXEC.execute(() -> {
            Uri uri = null;
            boolean enhanced = false;
            try {
                float[] p = Native.LOADED ? Levels.params(level) : null;
                if (p == null || pixels(jpeg) > limit(app)) {
                    uri = Saver.saveJpeg(app, jpeg, jpeg, rotation, 0, 0);
                } else {
                    Bitmap bmp = decode(jpeg, 1);
                    if (bmp == null) throw new IOException("decode failed");
                    try {
                        Native.enhance(bmp, p[0], p[1], p[2], p[3], p[4]);
                        byte[] out = encode(bmp, quality);
                        uri = Saver.saveJpeg(app, out, jpeg, rotation, bmp.getWidth(), bmp.getHeight());
                        enhanced = true;
                    } finally {
                        bmp.recycle();
                    }
                }
            } catch (Throwable t) {
                enhanced = false;
                uri = fallback(app, jpeg, rotation);
            }
            finish(d, uri, enhanced);
        });
    }

    /** Night mode: align and merge the frames, then lift tones and detail. */
    static void night(Context ctx, List<byte[]> frames, int rotation, int quality, Done d) {
        night(ctx, frames, rotation, quality, null, d);
    }

    /** {@code params} (denoise, clahe, sharpen, awb, gamma) after the merge; null uses the night preset. */
    static void night(Context ctx, List<byte[]> frames, int rotation, int quality, final float[] params, Done d) {
        final Context app = ctx.getApplicationContext();
        PENDING.incrementAndGet();
        EXEC.execute(() -> {
            Uri uri = null;
            boolean enhanced = false;
            long handle = 0;
            Bitmap base = null;
            final byte[] first = frames.isEmpty() ? null : frames.get(0);
            try {
                if (first == null) throw new IOException("no frames");
                if (!Native.LOADED) throw new IOException("no native library");
                int sample = pixels(first) > MAX_PIXELS ? 2 : 1;
                base = decode(first, sample);
                if (base == null) throw new IOException("decode failed");
                final int w = base.getWidth(), h = base.getHeight();
                handle = Native.mergeBegin(w, h);
                if (handle == 0 || !Native.mergeAdd(handle, base)) throw new IOException("merge failed");
                for (int i = 1; i < frames.size(); i++) {
                    byte[] b = frames.get(i);
                    frames.set(i, null);   // let the JPEG bytes go as soon as they are decoded
                    Bitmap f = b == null ? null : decode(b, sample);
                    if (f == null) continue;
                    if (f.getWidth() == w && f.getHeight() == h) Native.mergeAdd(handle, f);
                    f.recycle();
                }
                Native.mergeEnd(handle, base);
                float[] p = params != null ? params : Levels.night();
                Native.enhance(base, p[0], p[1], p[2], p[3], p[4]);
                byte[] out = encode(base, quality);
                uri = Saver.saveJpeg(app, out, first, rotation, w, h);
                enhanced = true;
            } catch (Throwable t) {
                enhanced = false;
                if (first != null) uri = fallback(app, first, rotation);
            } finally {
                if (handle != 0) Native.mergeFree(handle);
                if (base != null) base.recycle();
            }
            finish(d, uri, enhanced);
        });
    }

    // ============================================================ deep capture

    /** A deep capture: frames are merged one by one while the user holds the phone still (memory stays constant). */
    static final class Deep {
        final AtomicInteger pending = new AtomicInteger();
        volatile boolean failed;
        int rotation;
        byte[] first;
        long handle;
        int w, h;
        int merged;
        double best;
    }

    static Deep deepBegin() {
        return new Deep();
    }

    static int deepPending(Deep d) {
        return d.pending.get();
    }

    static int deepMerged(Deep d) {
        return d.merged;
    }

    /** Sharpness of the centre of a frame (gradient energy): blurred frames (hand shake) score low. */
    private static double sharpness(Bitmap b) {
        int cw = Math.min(512, b.getWidth()), ch = Math.min(512, b.getHeight());
        int x0 = (b.getWidth() - cw) / 2, y0 = (b.getHeight() - ch) / 2;
        int[] px = new int[cw * ch];
        b.getPixels(px, 0, cw, x0, y0, cw, ch);
        double acc = 0;
        for (int y = 0; y < ch - 1; y++) {
            for (int x = 0; x < cw - 1; x++) {
                int a = px[y * cw + x], r = px[y * cw + x + 1], d = px[(y + 1) * cw + x];
                int ga = ((a >> 16 & 255) * 3 + (a >> 8 & 255) * 6 + (a & 255)) / 10;
                int gr = ((r >> 16 & 255) * 3 + (r >> 8 & 255) * 6 + (r & 255)) / 10;
                int gd = ((d >> 16 & 255) * 3 + (d >> 8 & 255) * 6 + (d & 255)) / 10;
                acc += Math.abs(ga - gr) + Math.abs(ga - gd);
            }
        }
        return acc / (cw * ch);
    }

    /** Queues one frame; it is decoded, checked for sharpness and merged on the processing thread. */
    static void deepAdd(final Deep d, final byte[] jpeg) {
        d.pending.incrementAndGet();
        EXEC.execute(() -> {
            try {
                if (d.failed || !Native.LOADED) return;
                Bitmap b = decode(jpeg, 1);
                if (b == null) return;
                try {
                    if (d.handle == 0) {
                        d.w = b.getWidth();
                        d.h = b.getHeight();
                        d.handle = Native.mergeBegin(d.w, d.h);
                        if (d.handle == 0 || !Native.mergeAdd(d.handle, b)) {
                            d.failed = true;
                            return;
                        }
                        d.first = jpeg;
                        d.best = sharpness(b);
                        d.merged = 1;
                    } else if (b.getWidth() == d.w && b.getHeight() == d.h) {
                        double s = sharpness(b);
                        if (s >= d.best * 0.6 && Native.mergeAdd(d.handle, b)) {
                            d.merged++;
                            if (s > d.best) d.best = s;
                        }
                    }
                } finally {
                    b.recycle();
                }
            } catch (Throwable t) {
                d.failed = true;
            } finally {
                d.pending.decrementAndGet();
            }
        });
    }

    /** Runs after every queued frame: merges, enhances, saves. If anything fails the first frame is saved as it is. */
    static void deepFinish(Context ctx, final Deep d, final int quality, Done done) {
        final Context app = ctx.getApplicationContext();
        PENDING.incrementAndGet();
        EXEC.execute(() -> {
            Uri uri = null;
            boolean enhanced = false;
            Bitmap base = null;
            try {
                if (d.handle == 0 || d.first == null) throw new IOException("no frames");
                base = decode(d.first, 1);
                if (base == null) throw new IOException("decode failed");
                Native.mergeEnd(d.handle, base);
                float[] p = Levels.deep();
                Native.enhance(base, p[0], p[1], p[2], p[3], p[4]);
                byte[] out = encode(base, quality);
                uri = Saver.saveJpeg(app, out, d.first, d.rotation, base.getWidth(), base.getHeight());
                enhanced = true;
            } catch (Throwable t) {
                enhanced = false;
                if (d.first != null) uri = fallback(app, d.first, d.rotation);
            } finally {
                if (d.handle != 0) Native.mergeFree(d.handle);
                d.handle = 0;
                if (base != null) base.recycle();
            }
            finish(done, uri, enhanced);
        });
    }

    private static Uri fallback(Context app, byte[] jpeg, int rotation) {
        try {
            return Saver.saveJpeg(app, jpeg, jpeg, rotation, 0, 0);
        } catch (Throwable t) {
            return null;
        }
    }

    private static long pixels(byte[] jpeg) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, o);
        return (long) Math.max(0, o.outWidth) * Math.max(0, o.outHeight);
    }

    private static Bitmap decode(byte[] jpeg, int sample) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inMutable = true;
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        o.inSampleSize = Math.max(1, sample);
        return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, o);
    }

    private static byte[] encode(Bitmap b, int quality) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(4 * 1024 * 1024);
        if (!b.compress(Bitmap.CompressFormat.JPEG, Math.max(60, Math.min(100, quality)), out)) {
            throw new IOException("encode failed");
        }
        return out.toByteArray();
    }
}
