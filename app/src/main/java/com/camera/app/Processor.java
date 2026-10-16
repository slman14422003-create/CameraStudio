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

    /** Photos above this size (the 50 MP mode) are saved untouched: processing them would need > 500 MB. */
    static final long MAX_PIXELS = 20_000_000L;

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
                if (p == null || pixels(jpeg) > MAX_PIXELS) {
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
