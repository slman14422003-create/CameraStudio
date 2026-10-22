package com.camera.app;

import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraMetadata;
import android.graphics.ImageFormat;
import android.util.Rational;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.util.Range;
import android.util.Size;

import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.camera2.interop.Camera2CameraControl;
import androidx.camera.camera2.interop.Camera2CameraInfo;
import androidx.camera.camera2.interop.Camera2Interop;
import androidx.camera.camera2.interop.CaptureRequestOptions;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraFilter;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExposureState;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.Preview;
import androidx.camera.core.UseCaseGroup;
import androidx.camera.core.ViewPort;
import androidx.camera.core.ZoomState;
import androidx.camera.core.resolutionselector.AspectRatioStrategy;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.extensions.ExtensionMode;
import androidx.camera.extensions.ExtensionsManager;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.FileOutputOptions;
import androidx.camera.video.MediaStoreOutputOptions;
import androidx.camera.video.PendingRecording;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.core.util.Consumer;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
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
    static final int PHOTO = 0, NIGHT = 1, PRO = 2, VIDEO = 3, HIRES = 4, DEEP = 5, HDR = 6;

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
    private final Executor capExec = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(() -> {
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_FOREGROUND);
            } catch (Throwable ignored) {
            }
            r.run();
        }, "cam-capture");
        t.setDaemon(true);
        return t;
    });

    private ProcessCameraProvider provider;
    private ExtensionsManager ext;
    private Camera camera;
    private ImageCapture capture;
    private VideoCapture<Recorder> videoCapture;
    private Recorder recorder;
    private Recording recording;
    private boolean torch;
    private boolean lastStab = true;
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
    // video: aspect (0 = 9:16, 1 = 1:1, 2 = full screen) and the live GPU enhancement
    private boolean deepHires;
    private int bindFail;
    private boolean plainRes;
    private VideoFx fx;
    private int fxGen;
    private int vRatio;
    private int pRatio;
    private int pRatioW = 9, pRatioH = 16;
    private int vRatioW = 9, vRatioH = 16;

    // back lenses (ultra wide / main / tele) that CameraX exposes as separate cameras
    static final class Lens {
        final String id;
        final float eq;       // 35 mm equivalent focal length
        float factor = 1f;    // zoom factor relative to the main lens (0.6 = ultra wide)
        float diop;

        Lens(String id, float eq, float diop) {
            this.id = id;
            this.eq = eq;
            this.diop = diop;
        }
    }

    final List<Lens> lenses = new ArrayList<>();
    private String lensId;

    // video
    final List<Quality> videoQualities = new ArrayList<>();
    boolean stabSupported;
    Quality videoQuality;
    Size videoSize;       // chosen recording size (landscape, as the camera reports it)
    Size photoSize;       // size of the still the bound camera will give
    Size stdSize, maxSize;   // biggest normal 4:3 JPEG (binned, e.g. 12 MP) and biggest overall (e.g. 50 MP)
    boolean hiresAvailable;
    private Range<Integer> nightFps;
    private volatile boolean burstLock;
    private boolean nrHq, edgeHq, abHq, shadeHq, hotHq, toneHq;
    private volatile int liveIso;
    private volatile long liveExpNs;

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

    /** Deep capture on the full-resolution sensor mode (50 MP) instead of the binned one (12 MP). */
    void setDeepHires(boolean h) {
        if (deepHires == h) return;
        boolean before = hiresActive();
        deepHires = h;
        if (mode == DEEP && before != hiresActive()) bind();
    }

    int liveIso() {
        return liveIso;
    }

    long liveExpNs() {
        return liveExpNs;
    }

    /** Exposure compensation index for a number of stops (clamped to what the camera offers). */
    int evForStops(float stops) {
        if (evStep <= 0f) return 0;
        return Math.max(evMin, Math.min(evMax, Math.round(stops / evStep)));
    }

    int evFrom(int base, float stops) {
        if (evStep <= 0f) return base;
        return Math.max(evMin, Math.min(evMax, base + Math.round(stops / evStep)));
    }

    boolean isHires() {
        return hires;
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
        boolean wasVideo = mode == VIDEO;
        boolean hiBefore = hiresActive();
        mode = m;
        if (wasVideo != (m == VIDEO)) torch = false;
        if (wasVideo != (m == VIDEO) || hiBefore != hiresActive() || wantExtension() != extActive) {
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
        if (videoCapture != null) videoCapture.setTargetRotation(surfaceRotation);
    }

    // ------------------------------------------------------------------ video

    private static Quality qOf(String c) {
        if ("uhd".equals(c)) return Quality.UHD;
        if ("fhd".equals(c)) return Quality.FHD;
        if ("hd".equals(c)) return Quality.HD;
        if ("sd".equals(c)) return Quality.SD;
        return null;
    }

    static String qCode(Quality q) {
        if (q == Quality.UHD) return "uhd";
        if (q == Quality.FHD) return "fhd";
        if (q == Quality.HD) return "hd";
        return "sd";
    }

    static String qName(Quality q) {
        if (q == Quality.UHD) return "4K";
        if (q == Quality.FHD) return "FHD";
        if (q == Quality.HD) return "HD";
        return "SD";
    }

    /** The wanted quality if the camera has it, otherwise the nearest lower one, otherwise the best it has. */
    private Quality pickQuality() {
        if (videoQualities.isEmpty()) return null;
        Quality want = qOf(Store.str(act, "video_q", "fhd"));
        Quality[] order = {Quality.UHD, Quality.FHD, Quality.HD, Quality.SD};
        if (want != null) {
            boolean from = false;
            for (Quality q : order) {
                if (q == want) from = true;
                if (from && videoQualities.contains(q)) return q;
            }
        }
        return videoQualities.get(0);
    }

    /** Sets the video aspect: 0 = 9:16, 1 = 1:1, 2 = full screen (w and h give the screen shape). */
    void setVideoRatio(int kind, int w, int h) {
        if (recording != null) return;
        boolean changed = vRatio != kind || (kind == 2 && (vRatioW != w || vRatioH != h));
        vRatio = kind;
        vRatioW = Math.max(1, w);
        vRatioH = Math.max(1, h);
        if (changed && mode == VIDEO) bind();
    }

    /** Remembers the video aspect without re-binding (used right before entering the video mode). */
    void presetVideoRatio(int kind, int w, int h) {
        vRatio = kind;
        vRatioW = Math.max(1, w);
        vRatioH = Math.max(1, h);
    }

    void presetPhotoRatio(int kind, int w, int h) {
        pRatio = kind;
        pRatioW = Math.max(1, w);
        pRatioH = Math.max(1, h);
    }

    void setPhotoRatio(int kind, int w, int h) {
        boolean changed = pRatio != kind || (kind == 2 && (pRatioW != w || pRatioH != h));
        presetPhotoRatio(kind, w, h);
        if (changed && mode != VIDEO) bind();
    }

    int fxGen() {
        return fxGen;
    }

    boolean fxActive() {
        return fx != null;
    }

    int fxFrames() {
        return fx == null ? 0 : fx.frames();
    }

    float fxFps() {
        return fx == null ? 0f : fx.fps();
    }

    boolean fxFailed() {
        return fx != null && fx.failed();
    }

    boolean isRecording() {
        return recording != null;
    }

    boolean videoSupported() {
        return !videoQualities.isEmpty();
    }

    /** Starts a clip into DCIM/Camera. The callback gets every recording event on the main thread. */
    boolean startRecording(Context c, boolean audio, Consumer<VideoRecordEvent> cb) {
        if (recorder == null || recording != null) return false;
        try {
            String name = "VID_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".mp4";
            PendingRecording pr;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Video.Media.DISPLAY_NAME, name);
                v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
                v.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/Camera");
                MediaStoreOutputOptions o = new MediaStoreOutputOptions.Builder(c.getContentResolver(),
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI).setContentValues(v).build();
                pr = recorder.prepareRecording(c, o);
            } else {
                File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Camera");
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
                FileOutputOptions o = new FileOutputOptions.Builder(new File(dir, name)).build();
                pr = recorder.prepareRecording(c, o);
            }
            if (audio) pr = pr.withAudioEnabled();
            recording = pr.start(main, cb);
            return true;
        } catch (Throwable t) {
            recording = null;
            return false;
        }
    }

    void stopRecording() {
        Recording r = recording;
        recording = null;
        if (r != null) {
            try {
                r.stop();
            } catch (Throwable ignored) {
            }
        }
    }

    boolean torchOn() {
        return torch;
    }

    void setTorch(boolean on) {
        torch = on;
        if (camera == null) return;
        try {
            camera.getCameraControl().enableTorch(on);
        } catch (Throwable ignored) {
        }
    }

    /** Re-binds when a video preference changed in Settings while the video mode is open. */
    void checkPrefs() {
        boolean stab = Store.bool(act, "vstab", true);
        if (stab != lastStab && mode == VIDEO && recording == null) bind();
        lastStab = stab;
    }

    private void resetManual() {
        manual = false;
        awbMode = CameraMetadata.CONTROL_AWB_MODE_AUTO;
        focus = -1f;
    }

    private boolean wantExtension() {
        return hdr && mode == PHOTO && ext != null && hdrAvailable && !front && isMainLens();
    }

    // ------------------------------------------------------------------ lenses

    private boolean isMainLens() {
        if (macro != null && macro.id.equals(lensId)) return false;
        return lensId == null || lenses.isEmpty() || Math.abs(currentFactor() - 1f) < 0.05f;
    }

    private float currentFactor() {
        for (Lens l : lenses) if (l.id.equals(lensId == null ? lenses.get(0).id : lensId)) return l.factor;
        return 1f;
    }

    /** Zoom factor of the lens in use relative to the main lens (so 0.6 shows for the ultra wide camera). */
    float lensFactor() {
        return front ? 1f : currentFactor();
    }

    String lensId() {
        return lensId;
    }

    void setLens(String id) {
        if (id == null ? lensId == null : id.equals(lensId)) return;
        lensId = id;
        resetManual();
        bind();
    }

    private void findLenses(CameraSelector base) {
        lenses.clear();
        macro = null;
        macroFallback = null;
        if (front) return;
        try {
            for (CameraInfo ci : base.filter(provider.getAvailableCameraInfos())) {
                Camera2CameraInfo c2 = Camera2CameraInfo.from(ci);
                float[] fl = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                android.util.SizeF mm = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
                if (fl == null || fl.length == 0 || mm == null || mm.getWidth() <= 0) continue;
                float eq = (float) (fl[0] * 43.2666 / Math.hypot(mm.getWidth(), mm.getHeight()));
                Float md = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
                lenses.add(new Lens(c2.getCameraId(), eq, md == null ? 0f : md));
            }
        } catch (Throwable ignored) {
        }
        if (lenses.isEmpty()) return;
        float ref = lenses.get(0).eq;
        for (Lens l : lenses) l.factor = Math.round(l.eq / ref * 10f) / 10f;
        macro = null;
        if (lenses.size() > 1) {
            float mainD = lenses.get(0).diop;
            Lens m = null;
            for (int i = 1; i < lenses.size(); i++) {
                Lens l = lenses.get(i);
                if (l.factor >= 0.85f && l.diop >= 20f && l.diop >= mainD * 1.8f && (m == null || l.diop > m.diop)) m = l;
            }
            if (m != null) {
                macro = m;
                lenses.remove(m);
            } else {
                for (int i = 1; i < lenses.size(); i++) {
                    Lens l = lenses.get(i);
                    if (l.factor < 0.85f && l.diop >= 20f && l.diop >= mainD * 1.8f && (macroFallback == null || l.diop > macroFallback.diop)) macroFallback = l;
                }
            }
        }
        boolean ok = false;
        for (Lens l : lenses) if (l.id.equals(lensId)) ok = true;
        if (macro != null && macro.id.equals(lensId)) ok = true;
        if (!ok) lensId = null;
    }

    Lens macro;
    Lens macroFallback;

    String macroLensId() {
        if (macro != null) return macro.id;
        if (macroFallback != null) return macroFallback.id;
        return null;
    }

    boolean macroAvailable() {
        return !front && macroLensId() != null;
    }

    private CameraSelector lensSelector(CameraSelector base) {
        if (front || lensId == null || (lenses.size() < 2 && macro == null)) return base;
        final String id = lensId;
        return new CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .addCameraFilter((CameraFilter) infos -> {
                    List<CameraInfo> out = new ArrayList<>();
                    for (CameraInfo i : infos) {
                        try {
                            if (id.equals(Camera2CameraInfo.from(i).getCameraId())) out.add(i);
                        } catch (Throwable ignored) {
                        }
                    }
                    return out.isEmpty() ? infos : out;
                }).build();
    }

    // ------------------------------------------------------------------ binding

    private static Size best43(Size[] a, Size cur) {
        Size best = cur;
        if (a == null) return best;
        for (Size z : a) {
            if (z.getWidth() * 3 != z.getHeight() * 4 && z.getHeight() * 3 != z.getWidth() * 4) continue;
            if (best == null || (long) z.getWidth() * z.getHeight() > (long) best.getWidth() * best.getHeight()) best = z;
        }
        return best;
    }

    /** True when the full-resolution (e.g. 50 MP) mode is on and usable in the current mode. */
    boolean hiresActive() {
        return hiresAvailable && (mode == HIRES || (mode == DEEP && deepHires));
    }

    private static boolean has(int[] a, int v) {
        if (a == null) return false;
        for (int x : a) if (x == v) return true;
        return false;
    }

    private void readCaps(CameraSelector sel) {
        manualSensor = false;
        manualFocus = false;
        nrHq = edgeHq = abHq = shadeHq = hotHq = toneHq = false;
        rawSupported = false;
        nightFps = null;
        videoQualities.clear();
        stabSupported = false;
        stdSize = null;
        maxSize = null;
        hiresAvailable = false;
        awbModes.clear();
        try {
            List<CameraInfo> infos = sel.filter(provider.getAvailableCameraInfos());
            if (infos.isEmpty()) return;
            Camera2CameraInfo c2 = Camera2CameraInfo.from(infos.get(0));
            hasFlash = infos.get(0).hasFlashUnit();
            try {
                StreamConfigurationMap map = c2.getCameraCharacteristic(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (map != null) {
                    stdSize = best43(map.getOutputSizes(ImageFormat.JPEG), null);
                    maxSize = best43(map.getHighResolutionOutputSizes(ImageFormat.JPEG), stdSize);
                    hiresAvailable = stdSize != null && maxSize != null
                            && (long) maxSize.getWidth() * maxSize.getHeight()
                            > (long) stdSize.getWidth() * stdSize.getHeight() * 3 / 2;
                }
            } catch (Throwable ignored) {
            }
            try {
                videoQualities.addAll(QualitySelector.getSupportedQualities(infos.get(0)));
            } catch (Throwable ignored) {
            }
            try {
                stabSupported = Recorder.getVideoCapabilities(infos.get(0)).isStabilizationSupported();
            } catch (Throwable ignored) {
            }
            int[] caps = c2.getCameraCharacteristic(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
            Range<Integer> isoR = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
            Range<Long> expR = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
            manualSensor = isoR != null && expR != null
                    && has(caps, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR);
            rawSupported = has(caps, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW);
            if (isoR != null) {
                isoMin = isoR.getLower();
                isoMax = Math.min(isoR.getUpper(), 6400);
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
            shadeHq = has(c2.getCameraCharacteristic(CameraCharacteristics.SHADING_AVAILABLE_MODES),
                    CameraMetadata.SHADING_MODE_HIGH_QUALITY);
            hotHq = has(c2.getCameraCharacteristic(CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES),
                    CameraMetadata.HOT_PIXEL_MODE_HIGH_QUALITY);
            toneHq = has(c2.getCameraCharacteristic(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES),
                    CameraMetadata.TONEMAP_MODE_HIGH_QUALITY);
            edgeHq = has(c2.getCameraCharacteristic(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES),
                    CameraMetadata.EDGE_MODE_HIGH_QUALITY);
            abHq = has(c2.getCameraCharacteristic(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES),
                    CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY);
            // night mode: the widest frame-rate range that still reaches 24 fps lets auto-exposure expose longer
            Range<Integer>[] fps = c2.getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            if (fps != null) {
                for (Range<Integer> r : fps) {
                    if (r.getUpper() >= 24 && r.getLower() >= 12 && (nightFps == null || r.getLower() < nightFps.getLower())) nightFps = r;
                }
                if (nightFps == null) {
                    for (Range<Integer> r : fps) {
                        if (r.getUpper() >= 24 && (nightFps == null || r.getLower() > nightFps.getLower())) nightFps = r;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    void bind() {
        if (provider == null) return;
        if (recording != null) stopRecording();
        try {
            provider.unbindAll();
            if (fx != null) {
                fx.release();
                fx = null;
            }
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
            findLenses(base);
            CameraSelector lens = lensSelector(base);
            readCaps(lens);
            if (mode == HIRES && !hiresAvailable) mode = PHOTO;   // this camera has no full-resolution mode
            hdrAvailable = false;
            try {
                hdrAvailable = ext != null && ext.isExtensionAvailable(base, ExtensionMode.HDR);
            } catch (Throwable ignored) {
            }
            boolean wantExt = wantExtension();
            CameraSelector sel = wantExt ? ext.getExtensionEnabledCameraSelector(base, ExtensionMode.HDR) : lens;
            extActive = wantExt;

            photoSize = null;
            videoSize = null;
            videoQuality = null;
            capture = null;
            videoCapture = null;
            recorder = null;
            recording = null;

            if (mode == VIDEO) {
                videoQuality = pickQuality();
                // the preview gets the same 1080p source as the recording (not a small default size)
                ResolutionSelector previewRes = new ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(new ResolutionStrategy(new Size(1920, 1080),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                        .build();
                Preview preview = new Preview.Builder().setResolutionSelector(previewRes).build();
                preview.setSurfaceProvider(view.getSurfaceProvider());
                if (videoQuality == null) {
                    mode = PHOTO;
                    listener.onError("video");
                    bind();
                    return;
                }
                int bitrate = videoQuality == Quality.UHD ? 30_000_000 : videoQuality == Quality.FHD ? 12_000_000
                        : videoQuality == Quality.HD ? 9_000_000 : 4_000_000;
                recorder = new Recorder.Builder().setQualitySelector(QualitySelector.from(videoQuality))
                        .setTargetVideoEncodingBitRate(bitrate).build();
                lastStab = Store.bool(act, "vstab", true);
                VideoCapture.Builder<Recorder> vb = new VideoCapture.Builder<>(recorder);
                if (stabSupported) vb.setVideoStabilizationEnabled(lastStab);
                videoCapture = vb.build();
                try {
                    List<CameraInfo> infos = lens.filter(provider.getAvailableCameraInfos());
                    videoSize = QualitySelector.getResolution(infos.get(0), videoQuality);
                } catch (Throwable ignored) {
                }
                float fxStrength = Store.num(act, "vfx", 0) == 1 ? 0.35f : Store.num(act, "vfx", 0) == 3 ? 0.9f : 0.6f;
                if (Store.num(act, "vfx", 0) > 0 && Build.VERSION.SDK_INT >= 23) {
                    try {
                        fx = new VideoFx(fxStrength);
                        fxGen++;
                    } catch (Throwable t) {
                        fx = null;
                    }
                }
                UseCaseGroup.Builder gb = new UseCaseGroup.Builder().addUseCase(preview).addUseCase(videoCapture);
                if (fx != null) gb.addEffect(fx.effect());
                Rational ratio = vRatio == 1 ? new Rational(1, 1) : vRatio == 2 ? new Rational(vRatioW, vRatioH) : new Rational(9, 16);
                if (ratio != null) {
                    gb.setViewPort(new ViewPort.Builder(ratio, act.getWindowManager().getDefaultDisplay().getRotation()).build());
                }
                camera = provider.bindToLifecycle(act, sel, gb.build());
            } else {
                // photos: 4:3, about 12 MP (the A16 bins its 50 MP sensor to 12.5 MP: sharper and much faster);
                // "high resolution" asks CameraX for the full sensor size instead
                // the exact biggest 4:3 size the camera reports: the normal one (binned, sharper in low light) or,
                // with the full-resolution switch on, the biggest of all (50 MP on the Galaxy A16)
                Size target = plainRes ? null : (hiresActive() ? maxSize : stdSize);
                final boolean wide = false;
                ResolutionSelector.Builder rb = new ResolutionSelector.Builder()
                        .setAspectRatioStrategy(wide ? AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY : AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setAllowedResolutionMode(ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE);
                if (target != null) {
                    rb.setResolutionStrategy(new ResolutionStrategy(target,
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER));
                } else if (plainRes) {
                    rb.setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY);
                } else {
                    rb.setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY);
                }
                ResolutionSelector shotRes = rb.build();
                ResolutionSelector previewRes = new ResolutionSelector.Builder()
                        .setAspectRatioStrategy(wide ? AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY : AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build();

                Preview.Builder pb = new Preview.Builder().setResolutionSelector(previewRes);
                new Camera2Interop.Extender<>(pb).setSessionCaptureCallback(new CameraCaptureSession.CaptureCallback() {
                    @Override
                    public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request,
                                                   TotalCaptureResult result) {
                        Integer iso = result.get(CaptureResult.SENSOR_SENSITIVITY);
                        Long ex = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
                        if (iso != null) liveIso = iso;
                        if (ex != null) liveExpNs = ex;
                    }
                });
                Preview preview = pb.build();
                preview.setSurfaceProvider(view.getSurfaceProvider());

                ImageCapture.Builder ib = new ImageCapture.Builder()
                        .setCaptureMode((mode == DEEP || mode == HDR) ? ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY : ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .setJpegQuality(100)
                        .setResolutionSelector(shotRes)
                        .setFlashMode(flashForMode());
                if (!wantExt) {
                    Camera2Interop.Extender<ImageCapture> ex = new Camera2Interop.Extender<>(ib);
                    ex.setCaptureRequestOption(CaptureRequest.JPEG_QUALITY, (byte) 100);
                    if (nrHq) ex.setCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY);
                    if (edgeHq) ex.setCaptureRequestOption(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_HIGH_QUALITY);
                    if (abHq) ex.setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE,
                            CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY);
                    if (Store.bool(act, "isp_hq", true)) {
                        // the sensor pipeline at its best for stills: lens shading, hot pixels, tone map
                        if (shadeHq) ex.setCaptureRequestOption(CaptureRequest.SHADING_MODE, CameraMetadata.SHADING_MODE_HIGH_QUALITY);
                        if (hotHq) ex.setCaptureRequestOption(CaptureRequest.HOT_PIXEL_MODE, CameraMetadata.HOT_PIXEL_MODE_HIGH_QUALITY);
                        if (toneHq) ex.setCaptureRequestOption(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_HIGH_QUALITY);
                    }
                }
                capture = ib.build();

                if (!wantExt && pRatio != 3) {
                    Rational pr = pRatio == 1 ? new Rational(1, 1) : pRatio == 2 ? new Rational(pRatioW, pRatioH) : new Rational(9, 16);
                    UseCaseGroup pg = new UseCaseGroup.Builder().addUseCase(preview).addUseCase(capture)
                            .setViewPort(new ViewPort.Builder(pr, act.getWindowManager().getDefaultDisplay().getRotation()).build()).build();
                    camera = provider.bindToLifecycle(act, sel, pg);
                } else {
                    camera = provider.bindToLifecycle(act, sel, preview, capture);
                }
                try {
                    if (capture.getResolutionInfo() != null) photoSize = capture.getResolutionInfo().getResolution();
                } catch (Throwable ignored) {
                }
            }

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

            burstLock = false;
            applyOptions();
            if (torch && mode == VIDEO) {
                try {
                    camera.getCameraControl().enableTorch(true);
                } catch (Throwable ignored) {
                }
            }
            bindFail = 0;
            listener.onBound();
        } catch (Throwable t) {
            bindFail++;
            if (bindFail == 1 && extActive) {
                hdr = false;
                Store.setBool(act, "hdr", false);
                bind();
                return;
            }
            if (bindFail <= 2 && (hiresActive() || mode == HIRES || deepHires)) {
                if (mode == HIRES) mode = PHOTO;
                deepHires = false;
                hiresAvailable = false;
                bind();
                return;
            }
            if (bindFail <= 3 && !plainRes && mode != VIDEO) {
                plainRes = true;
                bind();
                return;
            }
            listener.onError(String.valueOf(t.getMessage()));
        }
    }

    // ------------------------------------------------------------------ controls

    private int flashForMode() {
        if ((mode != PHOTO && mode != HIRES) || front || !hasFlash) return ImageCapture.FLASH_MODE_OFF;
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
            if (burstLock && !manualAe) {
                b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, true);
                b.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, true);
            }
            if (manualAe && capture != null) capture.setFlashMode(ImageCapture.FLASH_MODE_OFF);
            Camera2CameraControl.from(camera.getCameraControl()).setCaptureRequestOptions(b.build());
        } catch (Throwable ignored) {
        }
    }

    void lockBurst(boolean on) {
        if (burstLock == on) return;
        burstLock = on;
        applyOptions();
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

    void capture(ImageCapture.OnImageCapturedCallback cb) {
        if (capture == null) {
            cb.onError(new androidx.camera.core.ImageCaptureException(ImageCapture.ERROR_CAMERA_CLOSED, "no camera", null));
            return;
        }
        capture.takePicture(capExec, cb);
    }
}
