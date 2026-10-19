package com.camera.app;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import androidx.exifinterface.media.ExifInterface;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Writes a JPEG into DCIM/Camera (so the Gallery app sees it at once) and keeps the camera's EXIF data. */
final class Saver {
    private Saver() {
    }

    /** EXIF tags carried over from the camera's own JPEG (names are the standard Exif tag names). */
    private static final String[] TAGS = {
            "Make", "Model", "LensMake", "LensModel", "ExposureTime", "FNumber", "PhotographicSensitivity",
            "ExposureBiasValue", "FocalLength", "FocalLengthIn35mmFilm", "Flash", "WhiteBalance", "ExposureMode",
            "ExposureProgram", "MeteringMode", "ApertureValue", "ShutterSpeedValue", "BrightnessValue",
            "DateTime", "DateTimeOriginal", "DateTimeDigitized", "SubSecTime", "SubSecTimeOriginal",
            "SubSecTimeDigitized", "SceneCaptureType", "DigitalZoomRatio", "OffsetTime", "OffsetTimeOriginal",
            "OffsetTimeDigitized", "LensSpecification", "MaxApertureValue", "SensingMethod", "CustomRendered",
            "GainControl", "Contrast", "Saturation", "Sharpness", "SubjectDistanceRange", "GPSLatitude", "GPSLatitudeRef",
            "GPSLongitude", "GPSLongitudeRef", "GPSAltitude", "GPSAltitudeRef", "GPSTimeStamp", "GPSDateStamp"
    };

    static int orientation(int rotationDegrees) {
        switch (((rotationDegrees % 360) + 360) % 360) {
            case 90:
                return ExifInterface.ORIENTATION_ROTATE_90;
            case 180:
                return ExifInterface.ORIENTATION_ROTATE_180;
            case 270:
                return ExifInterface.ORIENTATION_ROTATE_270;
            default:
                return ExifInterface.ORIENTATION_NORMAL;
        }
    }

    /**
     * @param jpeg       the file content
     * @param exifSource JPEG whose EXIF is copied into the result (the camera's original); may be the same array
     * @param rotation   clockwise degrees the picture must be turned to look upright (stored as EXIF orientation)
     * @param w          pixel size to record (0 = leave as is)
     */
    static Uri saveJpeg(Context c, byte[] jpeg, byte[] exifSource, int rotation, int w, int h) throws IOException {
        File tmp = File.createTempFile("shot", ".jpg", c.getCacheDir());
        try {
            try (FileOutputStream o = new FileOutputStream(tmp)) {
                o.write(jpeg);
            }
            try {
                ExifInterface dst = new ExifInterface(tmp.getAbsolutePath());
                if (exifSource != null && exifSource != jpeg) {
                    ExifInterface src = new ExifInterface(new ByteArrayInputStream(exifSource));
                    for (String t : TAGS) {
                        String v = src.getAttribute(t);
                        if (v != null) dst.setAttribute(t, v);
                    }
                }
                dst.setAttribute(ExifInterface.TAG_ORIENTATION, String.valueOf(orientation(rotation)));
                dst.setAttribute(ExifInterface.TAG_SOFTWARE, "Camera Studio");
                if (dst.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) == null) {
                    dst.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL,
                            new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(new Date()));
                }
                if (w > 0 && h > 0) {
                    dst.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, String.valueOf(w));
                    dst.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, String.valueOf(h));
                }
                dst.saveAttributes();
            } catch (Throwable ignored) {
                // a photo without perfect metadata is better than no photo
            }
            return insert(c, tmp);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    private static Uri insert(Context c, File src) throws IOException {
        String name = "IMG_" + new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date()) + ".jpg";
        ContentResolver cr = c.getContentResolver();
        ContentValues v = new ContentValues();
        v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        v.put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis());
        Uri uri;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/Camera");
            v.put(MediaStore.Images.Media.IS_PENDING, 1);
            uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new IOException("insert failed");
            try (OutputStream out = cr.openOutputStream(uri); InputStream in = new FileInputStream(src)) {
                if (out == null) throw new IOException("no stream");
                copy(in, out);
            } catch (IOException e) {
                cr.delete(uri, null, null);
                throw e;
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.Images.Media.IS_PENDING, 0);
            cr.update(uri, done, null, null);
        } else {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Camera");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File f = new File(dir, name);
            try (OutputStream out = new FileOutputStream(f); InputStream in = new FileInputStream(src)) {
                copy(in, out);
            }
            v.put(MediaStore.Images.Media.DATA, f.getAbsolutePath());
            uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new IOException("insert failed");
        }
        return uri;
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[256 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }
}
