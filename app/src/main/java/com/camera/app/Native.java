package com.camera.app;

import android.graphics.Bitmap;

/** JNI bridge to libcamstudio (image_processing.cpp). Bitmaps must be mutable ARGB_8888. */
final class Native {
    private Native() {
    }

    static final boolean LOADED;

    static {
        boolean ok;
        try {
            System.loadLibrary("camstudio");
            ok = true;
        } catch (Throwable t) {
            ok = false;
        }
        LOADED = ok;
    }

    /** In-place enhancement. Every strength is 0..1. */
    static native boolean enhance(Bitmap bmp, float denoise, float clahe, float sharpen, float awb, float gamma);

    /** Multi-frame merge: begin, add the first frame (the reference) and the next ones, end into a bitmap, free. */
    static native long mergeBegin(int w, int h);

    static native boolean mergeAdd(long handle, Bitmap frame);

    static native boolean mergeEnd(long handle, Bitmap out);

    static native void mergeFree(long handle);
}
