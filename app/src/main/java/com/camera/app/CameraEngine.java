package com.camera.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.util.Range;
import android.util.Size;

import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.camera2.interop.Camera2CameraControl;
import androidx.camera.camera2.interop.Camera2CameraInfo;
import androidx.camera.camera2.interop.Camera2Interop;
import androidx.camera.camera2.interop.CaptureRequestOptions;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExposureState;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.Preview;
import androidx.camera.core.ZoomState;
import androidx.camera.core.resolutionselector.AspectRatioStrategy;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.extensions.ExtensionMode;
import androidx.camera.extensions.ExtensionsManager;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * CameraX does the preview and the capture; Camera2 interop adds what CameraX hides: manual ISO / shutter /
 * white balance / focus, maximum-quality JPEG and noise reduction, and a long-exposure frame rate for night mode.
 * Everything that a particular phone does not support is detected first and simply left out.
 */
@SuppressLint("UnsafeOptInUsageError")
final class CameraEngine {
    static final int PHOTO = 0, NIGHT = 1, PRO = 2;

    interface Listener {
        /** The camera was (re)bound: capabilities below are up to date. */
        void onBound();

        void onZoom(float ratio);

        void onError(String message);
    }

    private final AppCompatActivity act;
    private final PreviewView view;
    private final Listener listener;
    private final Executor main;

    private ProcessCameraProvider provider;
    private ExtensionsManager ext;
    private Camera camera;
    private ImageCapture capture;
    private boolean extActive;
    private LiveData<ZoomState> zoomData;
    private Observer<ZoomState> zoomObs;

    // ---- settings
    private boolean front;
    private boolean hires;
    private boolean hdr;
    private int mode = PHOTO;
    private int flash = ImageCapture.FLASH_MODE_OFF;

    // ---- manual state (Pro mode)
    boolean manual;
    int iso = 200;
    long expNs = 16_666_666L;
    int awbMode = CameraMetadata.CONTROL_AWB_MODE_AUTO;
    float focus = -1f;      // diopters, < 0 means autofocus

    // ---- capabilities of the bound camera
    boolean manualSensor, manualFocus, hasFlash, hdrAvailable, rawSupported;
    int isoMin = 100, isoMax = 3200;
    long expMin = 250_000L, expMax = 1_000_000_000L;
    float minFocus;
    final List<Integer> awbModes = new ArrayList<>();
    boolean evSupported;
    int evMin, evMax;
    float evStep = 1f;
    private Range<Integer> nightFps;
    private boolean nrHq, edgeHq, abHq;

    CameraEngine(AppCompatActivity act, PreviewView view, Listener l) {
        this.act = act;
        this.view = view;
        this.listener = l;
        this.main = ContextCompat.getMainExecutor(act);
    }

    // ------------------------------------------------------------------ start

    void start() {
        if (provider != null) {
            bind();
            return;
        }
        final Context c = act.getApplicationContext();
        final ListenableFuture<ProcessCameraProvider> f = ProcessCameraProvider.getInstance(c);
        f.addListener(() -> {
            try {
                provider = f.get();
            } catch (Exception e) {
                listener.onError(String.valueOf(e.getMessage()));
                return;
            }
            try {
                final ListenableFuture<ExtensionsManager> ef = ExtensionsManager.getInstanceAsync(c, provider);
                ef.addListener(() -> {
                    try {
                        ext = ef.get();
                    } catch (Exception e) {
                        ext = null;
                    }
                    bind();
                }, main);
            } catch (Throwable t) {
                ext = null;
                bind();
            }
        }, main);
    }

    // ------------------------------------------------------------------ settings

    boolean isFront() {
        return front;
    }

    void setFront(boolean f) {
        if (front == f) return;
        front = f;
        resetManual();
        bind();
    }

    void setHires(boolean h) {
        if (hires == h) return;
        hires = h;
        bind();
    }

    void setHdr(boolean h) {
        if (hdr == h) return;
        hdr = h;
        if (wantExtension() != extActive) bind();
    }

    int mode() {
        return mode;
    }

    void setMode(int m) {
        if (mode == m) return;
        mode = m;
        if (wantExtension() != extActive) {
            bind();
        } else {
            applyFlash();
            applyOptions();
        }
    }

    void setFlash(int f) {
        flash = f;
        applyFlash();
    }

    void setTargetRotation(int surfaceRotation) {
        if (capture != null) capture.setTargetRotation(surfaceRotation);
    }

    private void resetManual() {
        manual = false;
        awbMode = CameraMetadata.CONTROL_AWB_MODE_AUTO;
        focus = -1f;
    }

    private boolean wantExtension() {
        return hdr && mode == PHOTO && ext != null && hdrAvailable && !front;
    }

    // ------------------------------------------------------------------ binding

    private static boolean has(int[] a, int v) {
        if (a == null) return false;
        for (int x : a) if (x == v) return true;
        return false;
    }

    private void readCaps(CameraSelector sel) {
        manualSensor = false;
        manualFocus = false;
        nrHq = edgeHq = abHq = false;
        rawSupported = false;
        nightFps = null;
        awbModes.clear();
        try {
            List<CameraInfo> infos = sel.filter(provider.getAvailableCameraInfos());
            if (infos.isEmpty()) return;
            Camera2CameraInfo c2 = Camera2CameraInfo.from(infos.get(0));
            hasFlash = infos.get(0).hasFlashUnit();
            int[] caps = c2.getCameraCharacteristic(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
            Range<Integer> isoR = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
            Range<Long> expR = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
            manualSensor = isoR != null && expR != null
                    && has(caps, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR);
            rawSupported = has(caps, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW);
            if (isoR != null) {
                isoMin = isoR.getLower();
                isoMax = Math.min(isoR.getUpper(), 3200);
            }
            if (expR != null) {
                expMin = Math.max(expR.getLower(), 250_000L);          // 1/4000 s
                expMax = Math.min(expR.getUpper(), 10_000_000_000L);   // 10 s
            }
            Float minF = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
            minFocus = minF == null ? 0f : minF;
            manualFocus = minFocus > 0f
                    && has(c2.getCameraCharacteristic(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES),
                    CameraMetadata.CONTROL_AF_MODE_OFF);
            int[] awb = c2.getCameraCharacteristic(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES);
            if (awb != null) for (int m : awb) awbModes.add(m);
            nrHq = has(c2.getCameraCharacteristic(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES),
                    CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY);
            edgeHq = has(c2.getCameraCharacteristic(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES),
                    CameraMetadata.EDGE_MODE_HIGH_QUALITY);
            abHq = has(c2.getCameraCharacteristic(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES),
                    CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY);
            // night mode: the widest frame-rate range that still reaches 24 fps lets auto-exposure expose longer
            Range<Integer>[] fps = c2.getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            if (fps != null) {
                for (Range<Integer> r : fps) {
                    if (r.getUpper() >= 24 && (nightFps == null || r.getLower() < nightFps.getLower())) nightFps = r;
                }
            }
        } catch (Throwable ignored) {
        }
    }

    void bind() {
        if (provider == null) return;
        try {
            provider.unbindAll();
            if (zoomData != null && zoomObs != null) zoomData.removeObserver(zoomObs);
            CameraSelector base = new CameraSelector.Builder()
                    .requireLensFacing(front ? CameraSelector.LENS_FACING_FRONT : CameraSelector.LENS_FACING_BACK).build();
            if (!provider.hasCamera(base)) {
                front = !front;
                base = new CameraSelector.Builder()
                        .requireLensFacing(front ? CameraSelector.LENS_FACING_FRONT : CameraSelector.LENS_FACING_BACK).build();
                if (!provider.hasCamera(base)) {
                    listener.onError("no camera");
                    return;
                }
            }
            readCaps(base);
            hdrAvailable = false;
            try {
                hdrAvailable = ext != null && ext.isExtensionAvailable(base, ExtensionMode.HDR);
            } catch (Throwable ignored) {
            }
            boolean wantExt = wantExtension();
            CameraSelector sel = wantExt ? ext.getExtensionEnabledCameraSelector(base, ExtensionMode.HDR) : base;
            extActive = wantExt;

            // photos: 4:3, about 12 MP (the A16 bins its 50 MP sensor to 12.5 MP: sharper and much faster);
            // "high resolution" asks CameraX for the full sensor size instead
            ResolutionStrategy strategy = new ResolutionStrategy(
                    hires ? new Size(8160, 6120) : new Size(4000, 3000),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER);
            ResolutionSelector shotRes = new ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(strategy)
                    .setAllowedResolutionMode(hires ? ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE
                            : ResolutionSelector.PREFER_CAPTURE_RATE_OVER_HIGHER_RESOLUTION)
                    .build();
            ResolutionSelector previewRes = new ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build();

            Preview preview = new Preview.Builder().setResolutionSelector(previewRes).build();
            preview.setSurfaceProvider(view.getSurfaceProvider());

            ImageCapture.Builder ib = new ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setResolutionSelector(shotRes)
                    .setFlashMode(flashForMode());
            if (!wantExt) {
                Camera2Interop.Extender<ImageCapture> ex = new Camera2Interop.Extender<>(ib);
                ex.setCaptureRequestOption(CaptureRequest.JPEG_QUALITY, (byte) 100);
                if (nrHq) ex.setCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY);
                if (edgeHq) ex.setCaptureRequestOption(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_HIGH_QUALITY);
                if (abHq) ex.setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE,
                        CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY);
            }
            capture = ib.build();

            camera = provider.bindToLifecycle(act, sel, preview, capture);

            ExposureState es = camera.getCameraInfo().getExposureState();
            evSupported = es.isExposureCompensationSupported();
            evMin = es.getExposureCompensationRange().getLower();
            evMax = es.getExposureCompensationRange().getUpper();
            evStep = es.getExposureCompensationStep().floatValue();

            zoomData = camera.getCameraInfo().getZoomState();
            zoomObs = z -> {
                if (z != null) listener.onZoom(z.getZoomRatio());
            };
            zoomData.observe(act, zoomObs);

            applyOptions();
            listener.onBound();
        } catch (Throwable t) {
            listener.onError(String.valueOf(t.getMessage()));
        }
    }

    // ------------------------------------------------------------------ controls

    private int flashForMode() {
        if (mode != PHOTO || front || !hasFlash) return ImageCapture.FLASH_MODE_OFF;
        return flash;
    }

    private void applyFlash() {
        if (capture != null) capture.setFlashMode(flashForMode());
    }

    /** Pushes the manual / night settings to the camera. Called after every change; cheap. */
    void applyOptions() {
        if (camera == null || extActive) return;
        try {
            CaptureRequestOptions.Builder b = new CaptureRequestOptions.Builder();
            boolean manualAe = mode == PRO && manual && manualSensor;
            if (manualAe) {
                b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF);
                b.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso);
                b.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, expNs);
                b.setCaptureRequestOption(CaptureRequest.SENSOR_FRAME_DURATION, Math.max(expNs, 33_333_333L));
            }
            if (mode == PRO && awbMode != CameraMetadata.CONTROL_AWB_MODE_AUTO && awbModes.contains(awbMode)) {
                b.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, awbMode);
            }
            if (mode == PRO && manualFocus && focus >= 0f) {
                b.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF);
                b.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, Math.min(focus, minFocus));
            }
            if (mode == NIGHT && nightFps != null) {
                b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, nightFps);
            }
            if (manualAe && capture != null) capture.setFlashMode(ImageCapture.FLASH_MODE_OFF);
            Camera2CameraControl.from(camera.getCameraControl()).setCaptureRequestOptions(b.build());
        } catch (Throwable ignored) {
        }
    }

    boolean manualAeActive() {
        return mode == PRO && manual && manualSensor;
    }

    void setEv(int index) {
        if (camera == null || !evSupported) return;
        try {
            camera.getCameraControl().setExposureCompensationIndex(Math.max(evMin, Math.min(evMax, index)));
        } catch (Throwable ignored) {
        }
    }

    int evIndex() {
        try {
            return camera == null ? 0 : camera.getCameraInfo().getExposureState().getExposureCompensationIndex();
        } catch (Throwable t) {
            return 0;
        }
    }

    void tapFocus(float x, float y) {
        if (camera == null) return;
        if (mode == PRO && manualFocus && focus >= 0f) return;   // manual focus: nothing to focus
        try {
            MeteringPoint p = view.getMeteringPointFactory().createPoint(x, y);
            int flags = FocusMeteringAction.FLAG_AF;
            if (!manualAeActive()) flags |= FocusMeteringAction.FLAG_AE | FocusMeteringAction.FLAG_AWB;
            camera.getCameraControl().startFocusAndMetering(
                    new FocusMeteringAction.Builder(p, flags).setAutoCancelDuration(4, TimeUnit.SECONDS).build());
        } catch (Throwable ignored) {
        }
    }

    float zoomMin() {
        ZoomState z = zoomData == null ? null : zoomData.getValue();
        return z == null ? 1f : z.getMinZoomRatio();
    }

    float zoomMax() {
        ZoomState z = zoomData == null ? null : zoomData.getValue();
        return z == null ? 1f : z.getMaxZoomRatio();
    }

    float zoom() {
        ZoomState z = zoomData == null ? null : zoomData.getValue();
        return z == null ? 1f : z.getZoomRatio();
    }

    void setZoom(float ratio) {
        if (camera == null) return;
        try {
            camera.getCameraControl().setZoomRatio(Math.max(zoomMin(), Math.min(zoomMax(), ratio)));
        } catch (Throwable ignored) {
        }
    }

    /** Takes one JPEG. Must be called on the main thread; the callback runs on the main thread. */
    void capture(ImageCapture.OnImageCapturedCallback cb) {
        if (capture == null) {
            cb.onError(new androidx.camera.core.ImageCaptureException(ImageCapture.ERROR_CAMERA_CLOSED, "no camera", null));
            return;
        }
        capture.takePicture(main, cb);
    }
}
