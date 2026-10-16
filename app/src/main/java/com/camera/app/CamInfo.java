package com.camera.app;

import android.content.Context;
import android.graphics.Typeface;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaRecorder;
import android.os.Build;
import android.util.Range;
import android.util.Size;
import android.util.SizeF;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Reads every camera of the phone (type, megapixels, sensor, lens, video) and shows it in a dialog. */
final class CamInfo {
    private CamInfo() {
    }

    private static boolean has(int[] a, int v) {
        if (a == null) return false;
        for (int x : a) if (x == v) return true;
        return false;
    }

    static String mpText(int w, int h) {
        double mp = w * (double) h / 1_000_000d;
        return mp >= 9.5 ? String.format(Locale.US, "%.0f MP", mp) : String.format(Locale.US, "%.1f MP", mp);
    }

    static String videoName(int w, int h) {
        int s = Math.max(w, h);
        if (s >= 7600) return "8K";
        if (s >= 3800) return "4K";
        if (s >= 2500) return "2K";
        if (s >= 1900) return "FHD";
        if (s >= 1200) return "HD";
        return "SD";
    }

    static String build(Context c) {
        StringBuilder sb = new StringBuilder();
        try {
            CameraManager cm = (CameraManager) c.getSystemService(Context.CAMERA_SERVICE);
            Set<String> ids = new LinkedHashSet<>();
            for (String id : cm.getCameraIdList()) ids.add(id);
            if (Build.VERSION.SDK_INT >= 28) {
                for (String id : new ArrayList<>(ids)) {
                    try {
                        ids.addAll(cm.getCameraCharacteristics(id).getPhysicalCameraIds());
                    } catch (Throwable ignored) {
                    }
                }
            }
            for (String id : ids) {
                try {
                    CameraCharacteristics ch = cm.getCameraCharacteristics(id);
                    String part = one(c, id, ch);
                    if (part != null) {
                        if (sb.length() > 0) sb.append("\n\n");
                        sb.append(part);
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return sb.length() == 0 ? c.getString(R.string.info_none) : sb.toString();
    }

    private static String yn(Context c, boolean b) {
        return c.getString(b ? R.string.info_yes : R.string.info_no);
    }

    private static String one(Context c, String id, CameraCharacteristics ch) {
        int[] caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        if (caps != null && !has(caps, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE)) return null;
        Integer facing = ch.get(CameraCharacteristics.LENS_FACING);
        Size px = ch.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
        SizeF mm = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
        float[] focals = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        float[] aps = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES);
        int[] ois = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION);
        Boolean flash = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
        Integer level = ch.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
        Range<Integer> iso = ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);

        float eq = 0f;
        if (focals != null && focals.length > 0 && mm != null && mm.getWidth() > 0) {
            double diag = Math.hypot(mm.getWidth(), mm.getHeight());
            eq = (float) (focals[0] * 43.2666 / diag);
        }
        int typeRes;
        if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) typeRes = R.string.info_t_front;
        else if (facing != null && facing == CameraCharacteristics.LENS_FACING_EXTERNAL) typeRes = R.string.info_t_ext;
        else if (eq > 0 && eq < 22f) typeRes = R.string.info_t_ultra;
        else if (eq >= 50f) typeRes = R.string.info_t_tele;
        else typeRes = R.string.info_t_main;

        StringBuilder b = new StringBuilder();
        b.append("● ");
        if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
            b.append(c.getString(R.string.info_back)).append(" · ");
        }
        b.append(c.getString(typeRes)).append("  (").append(id).append(")\n");
        if (px != null) {
            b.append(c.getString(R.string.info_mp)).append(": ").append(mpText(px.getWidth(), px.getHeight()))
                    .append(" (").append(px.getWidth()).append("×").append(px.getHeight()).append(")\n");
        }
        if (mm != null) {
            b.append(c.getString(R.string.info_sensor)).append(": ")
                    .append(String.format(Locale.US, "%.1f×%.1f ", mm.getWidth(), mm.getHeight()))
                    .append(c.getString(R.string.info_mm)).append("\n");
        }
        if (focals != null && focals.length > 0) {
            b.append(c.getString(R.string.info_focal)).append(": ")
                    .append(String.format(Locale.US, "%.1f ", focals[0])).append(c.getString(R.string.info_mm));
            if (eq > 0) {
                b.append(String.format(Locale.US, " (≈ %.0f ", eq)).append(c.getString(R.string.info_mm))
                        .append(" ").append(c.getString(R.string.info_eq)).append(")");
            }
            b.append("\n");
        }
        if (aps != null && aps.length > 0) {
            b.append(c.getString(R.string.info_aperture)).append(String.format(Locale.US, ": f/%.1f\n", aps[0]));
        }
        b.append(c.getString(R.string.info_ois)).append(": ")
                .append(yn(c, has(ois, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON))).append("\n");
        b.append(c.getString(R.string.info_flash)).append(": ").append(yn(c, flash != null && flash)).append("\n");
        if (Build.VERSION.SDK_INT >= 30) {
            Range<Float> z = ch.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
            if (z != null) {
                b.append(c.getString(R.string.info_zoom)).append(String.format(Locale.US, ": %.1f×\n", z.getUpper()));
            }
        }
        if (iso != null) {
            b.append(c.getString(R.string.info_iso)).append(": ").append(iso.getLower()).append(" – ")
                    .append(iso.getUpper()).append("\n");
        }
        try {
            StreamConfigurationMap map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size[] vs = map == null ? null : map.getOutputSizes(MediaRecorder.class);
            if (vs != null && vs.length > 0) {
                Size best = vs[0];
                for (Size s : vs) if ((long) s.getWidth() * s.getHeight() > (long) best.getWidth() * best.getHeight()) best = s;
                b.append(c.getString(R.string.info_video)).append(": ").append(best.getWidth()).append("×")
                        .append(best.getHeight()).append(" (").append(videoName(best.getWidth(), best.getHeight()))
                        .append(")\n");
            }
        } catch (Throwable ignored) {
        }
        b.append(c.getString(R.string.info_raw)).append(": ")
                .append(yn(c, has(caps, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW))).append("\n");
        String lv = "";
        if (level != null) {
            switch (level) {
                case CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY: lv = "LEGACY"; break;
                case CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED: lv = "LIMITED"; break;
                case CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL: lv = "FULL"; break;
                case CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3: lv = "LEVEL 3"; break;
                default: lv = "EXTERNAL"; break;
            }
        }
        b.append(c.getString(R.string.info_level)).append(": ").append(lv);
        return b.toString();
    }

    static void show(Context c) {
        ScrollView sv = new ScrollView(c);
        TextView t = new TextView(c);
        t.setText(build(c));
        t.setTextSize(13.5f);
        t.setLineSpacing(0, 1.2f);
        t.setTextIsSelectable(true);
        t.setTypeface(Typeface.DEFAULT);
        t.setTextColor(Ui.color(c, R.color.text_primary));
        t.setPadding(Ui.dp(c, 20), Ui.dp(c, 8), Ui.dp(c, 20), Ui.dp(c, 8));
        sv.addView(t);
        new Dlg(c).setTitle(R.string.info_title).setView(sv).setNegativeButton(R.string.close, null).show();
    }
}
