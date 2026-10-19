package com.camera.app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadataRetriever;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.OrientationEventListener;
import android.view.ScaleGestureDetector;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.GestureDetector;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.video.VideoRecordEvent;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.core.splashscreen.SplashScreen;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.exifinterface.media.ExifInterface;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The camera screen: iOS-Camera layout in the Gallery's look. Photo (processed after the shot), Night (several
 * frames aligned and merged) and Pro (manual ISO / shutter / white balance / focus / exposure).
 */
public class CameraActivity extends BaseActivity implements CameraEngine.Listener {
    private static final int T_ISO = 0, T_SHUTTER = 1, T_WB = 2, T_FOCUS = 3, T_EV = 4;

    private CameraEngine engine;
    private PreviewView previewView;
    private FrameLayout holder;
    private GridView grid;
    private View focusRing;
    private TextView status, hdrBtn, timerBtn, infoPill, countView, ratioPill;
    private int vKind;
    private LinearLayout lensRow;
    private final List<TextView> lensBtns = new ArrayList<>();
    private final List<Float> lensStops = new ArrayList<>();
    private LevelView levelView;
    private LinearLayout videoRow;
    private final List<TextView> videoChips = new ArrayList<>();
    private boolean recActive, counting, lastIsVideo, multiShot;
    private float pendingStop = -1f;
    private HorizontalScrollView modeScroll;
    private final Handler liveHand = new Handler(Looper.getMainLooper());
    private boolean hdrRunning;
    private int hdrIdx, hdrPrevEv, hdrRot;
    private int[] hdrSteps;
    private float[] hdrStops;
    private List<byte[]> hdrFrames;
    private Processor.Deep deep;
    private boolean deepRunning, deepStop;
    private long deepEnd;
    private int deepFrames, deepErrors;
    private String recText = "";
    private final Handler hand = new Handler(Looper.getMainLooper());
    private ImageButton flashBtn, gridBtn, switchBtn;
    private ImageView thumb;
    private ShutterView shutter;
    private LinearLayout levelRow, proPanel, toolRow, wbRow;
    private SeekBar slider;
    private TextView sliderValue;
    private final List<TextView> modeChips = new ArrayList<>();
    private final List<TextView> levelChips = new ArrayList<>();
    private final List<LinearLayout> toolItems = new ArrayList<>();
    private final List<Integer> toolIds = new ArrayList<>();
    private LinearLayout autoItem;
    private View permView;

    private String level;
    private int tool = T_ISO;
    private int flashState;          // 0 off, 1 auto, 2 on
    private int rotation = Surface.ROTATION_0;
    private boolean busy, started;
    private LinearLayout bottomBar;
    private final List<Integer> modeIds = new ArrayList<>();
    private String nightText = "";
    private Uri lastUri;
    private OrientationEventListener orient;
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private final ActivityResultLauncher<String> audioLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), r -> { });

    private final ActivityResultLauncher<String[]> permLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(), r -> afterPermission());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SplashScreen splash = SplashScreen.installSplashScreen(this);
        splash.setOnExitAnimationListener(provider -> {
            try {
                final View sv = provider.getView();
                View ic = provider.getIconView();
                if (ic != null) {
                    ic.animate().scaleX(1.7f).scaleY(1.7f).alpha(0f).setDuration(360)
                            .setInterpolator(new android.view.animation.AccelerateInterpolator(1.2f)).start();
                }
                if (sv != null) {
                    sv.animate().alpha(0f).setStartDelay(140).setDuration(320).withEndAction(provider::remove).start();
                } else {
                    provider.remove();
                }
            } catch (Throwable t) {
                try {
                    provider.remove();
                } catch (Throwable ignored) {
                }
            }
        });
        // a camera is always dark (like the iPhone camera), whatever the app appearance is
        getDelegate().setLocalNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        super.onCreate(savedInstanceState);

        level = Store.str(this, "level", Levels.MEDIUM);
        buildUi();

        engine = new CameraEngine(this, previewView, this);

        orient = new OrientationEventListener(this) {
            @Override
            public void onOrientationChanged(int o) {
                if (o == ORIENTATION_UNKNOWN) return;
                int r = (o >= 315 || o < 45) ? Surface.ROTATION_0
                        : o < 135 ? Surface.ROTATION_270
                        : o < 225 ? Surface.ROTATION_180 : Surface.ROTATION_90;
                if (r != rotation) {
                    rotation = r;
                    engine.setTargetRotation(r);
                }
            }
        };
    }

    @Override
    protected void onPostCreate(Bundle b) {
        super.onPostCreate(b);
        try {
            WindowInsetsControllerCompat c = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
            c.setAppearanceLightStatusBars(false);
            c.setAppearanceLightNavigationBars(false);
        } catch (Throwable ignored) {
        }
    }

    // ================================================================== UI

    private int dp(int v) {
        return Ui.dp(this, v);
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));

        // ---- top bar
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(8), dp(4), dp(8), dp(4));
        flashBtn = Ios.navIcon(this, R.drawable.ic_flash_off, R.string.cam_flash, v -> cycleFlash());
        top.addView(flashBtn, new LinearLayout.LayoutParams(dp(46), dp(46)));
        hdrBtn = new TextView(this);
        hdrBtn.setText("HDR");
        hdrBtn.setTextSize(13);
        hdrBtn.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        hdrBtn.setGravity(Gravity.CENTER);
        hdrBtn.setPadding(dp(12), dp(5), dp(12), dp(5));
        hdrBtn.setOnClickListener(v -> toggleHdr());
        Ui.press(this, hdrBtn);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-2, -2);
        hp.setMarginStart(dp(6));
        top.addView(hdrBtn, hp);
        timerBtn = new TextView(this);
        timerBtn.setTextSize(13);
        timerBtn.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        timerBtn.setGravity(Gravity.CENTER);
        timerBtn.setContentDescription(getString(R.string.cam_timer));
        timerBtn.setPadding(dp(12), dp(5), dp(12), dp(5));
        timerBtn.setOnClickListener(v -> cycleTimer());
        Ui.press(this, timerBtn);
        LinearLayout.LayoutParams tmp = new LinearLayout.LayoutParams(-2, -2);
        tmp.setMarginStart(dp(6));
        top.addView(timerBtn, tmp);
        top.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        gridBtn = Ios.navIcon(this, R.drawable.ic_grid, R.string.cam_grid, v -> {
            Store.setBool(this, "grid", !Store.bool(this, "grid", true));
            refreshTop();
        });
        top.addView(gridBtn, new LinearLayout.LayoutParams(dp(46), dp(46)));
        top.addView(Ios.navIcon(this, R.drawable.ic_settings, R.string.settings,
                v -> startActivity(new Intent(this, SettingsActivity.class))), new LinearLayout.LayoutParams(dp(46), dp(46)));
        col.addView(top, new LinearLayout.LayoutParams(-1, -2));

        // ---- preview (3:4)
        holder = new Ratio34(this);
        holder.setBackgroundColor(Color.BLACK);
        previewView = new PreviewView(this);
        previewView.setScaleType(PreviewView.ScaleType.FIT_CENTER);
        previewView.setImplementationMode(PreviewView.ImplementationMode.PERFORMANCE);
        holder.addView(previewView, new FrameLayout.LayoutParams(-1, -1));
        grid = new GridView(this);
        holder.addView(grid, new FrameLayout.LayoutParams(-1, -1));
        levelView = new LevelView(this);
        holder.addView(levelView, new FrameLayout.LayoutParams(-1, -1));

        focusRing = new View(this);
        GradientDrawable ring = new GradientDrawable();
        ring.setStroke(dp(2), Ios.YELLOW);
        ring.setCornerRadius(dp(10));
        focusRing.setBackground(ring);
        focusRing.setVisibility(View.INVISIBLE);
        holder.addView(focusRing, new FrameLayout.LayoutParams(dp(72), dp(72)));

        status = pill();
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        sp.topMargin = dp(10);
        status.setVisibility(View.GONE);
        holder.addView(status, sp);

        infoPill = pill();
        infoPill.setTextSize(12);
        infoPill.setContentDescription(getString(R.string.cam_info));
        infoPill.setOnClickListener(v -> CamInfo.show(this));
        Ui.press(this, infoPill);
        FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        ip.topMargin = dp(10);
        ip.setMarginStart(dp(10));
        infoPill.setVisibility(View.GONE);
        holder.addView(infoPill, ip);

        vKind = Store.num(this, "vratio", 0);
        ratioPill = pill();
        ratioPill.setTextSize(12);
        ratioPill.setOnClickListener(v -> cycleVideoRatio());
        Ui.press(this, ratioPill);
        FrameLayout.LayoutParams rp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        rp.topMargin = dp(10);
        rp.setMarginEnd(dp(10));
        ratioPill.setVisibility(View.GONE);
        holder.addView(ratioPill, rp);

        countView = new TextView(this);
        countView.setTextColor(Color.WHITE);
        countView.setTextSize(96);
        countView.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        countView.setShadowLayer(dp(8), 0, 0, 0xAA000000);
        countView.setGravity(Gravity.CENTER);
        countView.setVisibility(View.GONE);
        holder.addView(countView, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));

        lensRow = new LinearLayout(this);
        lensRow.setOrientation(LinearLayout.HORIZONTAL);
        lensRow.setGravity(Gravity.CENTER);
        GradientDrawable lg = new GradientDrawable();
        lg.setCornerRadius(dp(100));
        lg.setColor(0x73000000);
        lensRow.setBackground(lg);
        lensRow.setPadding(dp(5), dp(5), dp(5), dp(5));
        FrameLayout.LayoutParams zp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        zp.bottomMargin = dp(10);
        holder.addView(lensRow, zp);

        buildProPanel();
        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        pp.bottomMargin = dp(46);
        holder.addView(proPanel, pp);

        final ScaleGestureDetector sg = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                engine.setZoom(engine.zoom() * d.getScaleFactor());
                return true;
            }
        });
        final GestureDetector gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                engine.tapFocus(e.getX(), e.getY());
                showFocus(e.getX(), e.getY());
                return true;
            }
        });
        holder.setOnTouchListener((v, e) -> {
            sg.onTouchEvent(e);
            if (!sg.isInProgress()) gd.onTouchEvent(e);
            return true;
        });
        col.addView(holder, new LinearLayout.LayoutParams(-1, -2));

        // ---- bottom controls
        LinearLayout bottom = new LinearLayout(this);
        bottomBar = bottom;
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setGravity(Gravity.CENTER);
        col.addView(bottom, new LinearLayout.LayoutParams(-1, 0, 1f));

        levelRow = new LinearLayout(this);
        levelRow.setOrientation(LinearLayout.HORIZONTAL);
        levelRow.setGravity(Gravity.CENTER);
        TextView lbl = new TextView(this);
        lbl.setText(R.string.cam_processing);
        lbl.setTextSize(13);
        lbl.setTextColor(Ui.color(this, R.color.text_secondary));
        lbl.setPaddingRelative(0, 0, dp(10), 0);
        levelRow.addView(lbl);
        for (final String l : Levels.ALL) {
            TextView ch = Ui.chip(this, getString(Levels.name(l)), l.equals(level));
            ch.setTag(l);
            ch.setOnClickListener(v -> setLevel(l));
            levelChips.add(ch);
            levelRow.addView(ch);
        }
        videoRow = new LinearLayout(this);
        videoRow.setOrientation(LinearLayout.HORIZONTAL);
        videoRow.setGravity(Gravity.CENTER);
        videoRow.setVisibility(View.GONE);
        FrameLayout rowWrap = new FrameLayout(this);
        rowWrap.addView(levelRow, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        rowWrap.addView(videoRow, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        bottom.addView(rowWrap, new LinearLayout.LayoutParams(-2, -2));

        LinearLayout modes = new LinearLayout(this);
        modes.setOrientation(LinearLayout.HORIZONTAL);
        modes.setGravity(Gravity.CENTER);
        final int[] order = {CameraEngine.PHOTO, CameraEngine.HDR, CameraEngine.HIRES, CameraEngine.NIGHT, CameraEngine.DEEP, CameraEngine.PRO, CameraEngine.VIDEO};
        final int[] names = {R.string.cam_mode_photo, R.string.cam_mode_hdr, R.string.cam_mode_photo, R.string.cam_mode_night,
                R.string.cam_mode_deep, R.string.cam_mode_pro, R.string.cam_mode_video};
        for (int i = 0; i < order.length; i++) {
            final int m = order[i];
            TextView t = new TextView(this);
            t.setText(m == CameraEngine.HIRES ? "50 MP" : getString(names[i]).toUpperCase(Locale.getDefault()));
            t.setTextSize(13.5f);
            t.setLetterSpacing(0.05f);
            t.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            t.setPadding(dp(14), dp(8), dp(14), dp(8));
            t.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                setMode(m);
            });
            Ui.press(this, t);
            styleMode(t, m == CameraEngine.PHOTO);
            if (m == CameraEngine.HIRES || m == CameraEngine.DEEP) t.setVisibility(View.GONE);
            modeChips.add(t);
            modeIds.add(m);
            modes.addView(t);
        }
        modeScroll = new HorizontalScrollView(this);
        modeScroll.setHorizontalScrollBarEnabled(false);
        modeScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        modeScroll.setFillViewport(true);
        modeScroll.addView(modes, new FrameLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-1, -2);
        mp.topMargin = dp(6);
        bottom.addView(modeScroll, mp);

        LinearLayout shutterRow = new LinearLayout(this);
        shutterRow.setOrientation(LinearLayout.HORIZONTAL);
        shutterRow.setGravity(Gravity.CENTER_VERTICAL);
        shutterRow.setPadding(dp(30), 0, dp(30), 0);

        thumb = new ImageView(this);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable tb = new GradientDrawable();
        tb.setCornerRadius(dp(12));
        tb.setColor(Ui.color(this, R.color.neutral_soft));
        thumb.setBackground(tb);
        thumb.setClipToOutline(true);
        thumb.setImageResource(R.drawable.ic_image);
        thumb.setImageTintList(android.content.res.ColorStateList.valueOf(Ui.color(this, R.color.text_secondary)));
        thumb.setPadding(dp(12), dp(12), dp(12), dp(12));
        thumb.setContentDescription(getString(R.string.cam_open_gallery));
        thumb.setOnClickListener(v -> openLast());
        Ui.press(this, thumb);
        shutterRow.addView(thumb, new LinearLayout.LayoutParams(dp(52), dp(52)));

        shutterRow.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        shutter = new ShutterView(this);
        shutter.setContentDescription(getString(R.string.cam_shutter));
        shutter.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            shoot();
        });
        shutterRow.addView(shutter, new LinearLayout.LayoutParams(dp(78), dp(78)));
        shutterRow.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));

        switchBtn = Ios.navIcon(this, R.drawable.ic_refresh, R.string.cam_switch, v -> switchCamera());
        GradientDrawable sb = new GradientDrawable();
        sb.setShape(GradientDrawable.OVAL);
        sb.setColor(Ui.color(this, R.color.neutral_soft));
        switchBtn.setBackground(sb);
        shutterRow.addView(switchBtn, new LinearLayout.LayoutParams(dp(52), dp(52)));
        LinearLayout.LayoutParams srp = new LinearLayout.LayoutParams(-1, -2);
        srp.topMargin = dp(14);
        bottom.addView(shutterRow, srp);

        permView = buildPermView();
        permView.setVisibility(View.GONE);
        root.addView(permView, new FrameLayout.LayoutParams(-1, -1));

        refreshTop();
        refreshModeUi();
    }

    private TextView pill() {
        TextView t = new TextView(this);
        t.setTextColor(Color.WHITE);
        t.setTextSize(13);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(14), dp(6), dp(14), dp(6));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(100));
        g.setColor(0x99000000);
        t.setBackground(g);
        return t;
    }

    private View buildPermView() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundColor(Ui.color(this, R.color.bg));
        box.setPadding(dp(32), dp(32), dp(32), dp(32));
        ImageView ic = new ImageView(this);
        ic.setImageResource(R.drawable.ic_brand_mark);
        box.addView(ic, new LinearLayout.LayoutParams(dp(84), dp(84)));
        TextView t = new TextView(this);
        t.setText(R.string.cam_perm_title);
        t.setTextColor(Ui.color(this, R.color.text_primary));
        t.setTextSize(22);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
        tp.topMargin = dp(16);
        box.addView(t, tp);
        TextView m = new TextView(this);
        m.setText(R.string.cam_perm_msg);
        m.setTextColor(Ui.color(this, R.color.text_secondary));
        m.setTextSize(15);
        m.setGravity(Gravity.CENTER);
        m.setLineSpacing(0, 1.15f);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-2, -2);
        mp.topMargin = dp(8);
        box.addView(m, mp);
        android.widget.Button b = Ui.button(this, R.string.cam_grant, true);
        b.setOnClickListener(v -> {
            if (shouldOpenSettings()) {
                try {
                    startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + getPackageName())));
                } catch (Exception ignored) {
                }
            } else {
                permLauncher.launch(neededPerms());
            }
        });
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(dp(220), -2);
        bp.topMargin = dp(24);
        box.addView(b, bp);
        return box;
    }

    // ---- Pro panel ----------------------------------------------------

    private void buildProPanel() {
        proPanel = new LinearLayout(this);
        proPanel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xCC000000, 0x00000000});
        proPanel.setBackground(bg);
        proPanel.setPadding(0, dp(18), 0, dp(4));
        proPanel.setVisibility(View.GONE);

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        toolRow = new LinearLayout(this);
        toolRow.setOrientation(LinearLayout.HORIZONTAL);
        toolRow.setGravity(Gravity.CENTER);
        toolRow.setPadding(dp(10), 0, dp(10), 0);
        hs.addView(toolRow, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL));
        hs.setFillViewport(true);
        proPanel.addView(hs, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout sRow = new LinearLayout(this);
        sRow.setOrientation(LinearLayout.HORIZONTAL);
        sRow.setGravity(Gravity.CENTER_VERTICAL);
        sRow.setPadding(dp(10), 0, dp(18), 0);
        slider = new SeekBar(this);
        slider.setMax(100);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser) onSlider(p);
            }

            @Override public void onStartTrackingTouch(SeekBar s) { }

            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        sRow.addView(slider, new LinearLayout.LayoutParams(0, dp(40), 1f));
        sliderValue = new TextView(this);
        sliderValue.setTextColor(Color.WHITE);
        sliderValue.setTextSize(14);
        sliderValue.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        sliderValue.setMinWidth(dp(64));
        sliderValue.setGravity(Gravity.END);
        sRow.addView(sliderValue, new LinearLayout.LayoutParams(-2, -2));
        proPanel.addView(sRow, new LinearLayout.LayoutParams(-1, -2));

        HorizontalScrollView wbs = new HorizontalScrollView(this);
        wbs.setHorizontalScrollBarEnabled(false);
        wbRow = new LinearLayout(this);
        wbRow.setOrientation(LinearLayout.HORIZONTAL);
        wbRow.setGravity(Gravity.CENTER);
        wbRow.setPadding(dp(10), dp(4), dp(10), dp(8));
        wbs.addView(wbRow, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL));
        wbs.setFillViewport(true);
        wbs.setTag("wbscroll");
        proPanel.addView(wbs, new LinearLayout.LayoutParams(-1, -2));
    }

    /** Rebuilds the tool row and the white-balance chips from what this phone's camera supports. */
    private void rebuildPro() {
        toolRow.removeAllViews();
        toolItems.clear();
        toolIds.clear();
        autoItem = Ios.toolItem(this, R.drawable.ic_wand, getString(R.string.pro_auto));
        autoItem.setOnClickListener(v -> {
            engine.manual = false;
            engine.focus = -1f;
            engine.awbMode = android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_AUTO;
            engine.applyOptions();
            refreshPro();
        });
        toolRow.addView(autoItem, toolParams());
        if (engine.manualSensor) {
            addTool(T_ISO, R.drawable.ic_adjust, R.string.pro_iso);
            addTool(T_SHUTTER, R.drawable.ic_speed, R.string.pro_shutter);
        }
        if (engine.awbModes.size() > 1) addTool(T_WB, R.drawable.ic_sun, R.string.pro_wb);
        if (engine.manualFocus) addTool(T_FOCUS, R.drawable.ic_focus, R.string.pro_focus);
        if (engine.evSupported) addTool(T_EV, R.drawable.ic_ev, R.string.pro_ev);
        if (!toolIds.contains(tool) && !toolIds.isEmpty()) tool = toolIds.get(0);

        wbRow.removeAllViews();
        int[] modes = {1, 2, 3, 5, 6, 8};
        int[] names = {R.string.wb_auto, R.string.wb_incandescent, R.string.wb_fluorescent, R.string.wb_daylight,
                R.string.wb_cloudy, R.string.wb_shade};
        for (int i = 0; i < modes.length; i++) {
            final int m = modes[i];
            if (!engine.awbModes.contains(m)) continue;
            TextView ch = Ui.chip(this, getString(names[i]), engine.awbMode == m);
            ch.setTag(m);
            ch.setOnClickListener(v -> {
                engine.awbMode = m;
                engine.applyOptions();
                refreshPro();
            });
            wbRow.addView(ch);
        }
        refreshPro();
    }

    private LinearLayout.LayoutParams toolParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(64), -2);
        lp.setMargins(dp(2), 0, dp(2), 0);
        return lp;
    }

    private void addTool(final int id, int icon, int label) {
        LinearLayout it = Ios.toolItem(this, icon, getString(label));
        it.setOnClickListener(v -> {
            tool = id;
            refreshPro();
        });
        toolRow.addView(it, toolParams());
        toolItems.add(it);
        toolIds.add(id);
    }

    /** Highlights the chosen tool and shows the matching slider or chips. */
    private void refreshPro() {
        if (proPanel == null) return;
        boolean any = !toolIds.isEmpty();
        boolean autoOnly = !engine.manual && engine.awbMode == 1 && engine.focus < 0f;
        Ios.setToolSelected(this, autoItem, autoOnly);
        for (int i = 0; i < toolItems.size(); i++) Ios.setToolSelected(this, toolItems.get(i), toolIds.get(i) == tool);
        boolean wb = tool == T_WB;
        slider.setVisibility(any && !wb ? View.VISIBLE : View.GONE);
        sliderValue.setVisibility(any && !wb ? View.VISIBLE : View.GONE);
        View wbScroll = proPanel.findViewWithTag("wbscroll");
        if (wbScroll != null) wbScroll.setVisibility(any && wb ? View.VISIBLE : View.GONE);
        for (int i = 0; i < wbRow.getChildCount(); i++) {
            View c = wbRow.getChildAt(i);
            Ui.setChip(this, (TextView) c, ((Integer) c.getTag()) == engine.awbMode);
        }
        if (!any || wb) return;
        slider.setMax(tool == T_EV ? Math.max(1, engine.evMax - engine.evMin) : 100);
        slider.setEnabled(!(tool == T_EV && engine.manualAeActive()));
        switch (tool) {
            case T_ISO:
                slider.setProgress(logToProgress(engine.iso, engine.isoMin, engine.isoMax));
                sliderValue.setText(engine.manual ? ("ISO " + engine.iso) : getString(R.string.pro_auto));
                break;
            case T_SHUTTER:
                slider.setProgress(logToProgress(engine.expNs, engine.expMin, engine.expMax));
                sliderValue.setText(engine.manual ? shutterText(engine.expNs) : getString(R.string.pro_auto));
                break;
            case T_FOCUS:
                slider.setProgress(engine.focus < 0 ? 0 : focusToProgress(engine.focus));
                sliderValue.setText(focusText(engine.focus));
                break;
            default:
                slider.setProgress(engine.evIndex() - engine.evMin);
                sliderValue.setText(evText(engine.evIndex()));
                break;
        }
    }

    private void onSlider(int p) {
        switch (tool) {
            case T_ISO:
            case T_SHUTTER:
                if (!engine.manual) {
                    engine.manual = true;
                    engine.iso = Math.max(engine.isoMin, Math.min(engine.isoMax, 200));
                    engine.expNs = Math.max(engine.expMin, Math.min(engine.expMax, 16_666_666L));
                }
                if (tool == T_ISO) engine.iso = (int) progressToLog(p, engine.isoMin, engine.isoMax);
                else engine.expNs = progressToLog(p, engine.expMin, engine.expMax);
                engine.applyOptions();
                sliderValue.setText(tool == T_ISO ? ("ISO " + engine.iso) : shutterText(engine.expNs));
                Ios.setToolSelected(this, autoItem, false);
                break;
            case T_FOCUS:
                engine.focus = p == 0 ? -1f : progressToFocus(p);
                engine.applyOptions();
                sliderValue.setText(focusText(engine.focus));
                Ios.setToolSelected(this, autoItem, false);
                break;
            case T_EV:
                int idx = engine.evMin + p;
                engine.setEv(idx);
                sliderValue.setText(evText(idx));
                break;
            default:
                break;
        }
    }

    private static int logToProgress(double v, double lo, double hi) {
        if (hi <= lo) return 0;
        double t = Math.log(Math.max(lo, Math.min(hi, v)) / lo) / Math.log(hi / lo);
        return (int) Math.round(t * 100);
    }

    private static long progressToLog(int p, double lo, double hi) {
        return Math.round(lo * Math.pow(hi / lo, p / 100.0));
    }

    private float progressToFocus(int p) {
        // 1 = nearest focus (largest diopter value), 100 = infinity (0 diopters)
        return engine.minFocus * (1f - (p - 1) / 99f);
    }

    private int focusToProgress(float d) {
        if (engine.minFocus <= 0f) return 0;
        return Math.max(1, Math.min(100, 1 + Math.round((1f - d / engine.minFocus) * 99f)));
    }

    private String focusText(float d) {
        if (d < 0f) return "AF";
        if (d < 0.05f) return "∞";
        return String.format(Locale.US, "%d cm", Math.round(100f / d));
    }

    private String evText(int idx) {
        return String.format(Locale.US, "%+.1f EV", idx * engine.evStep);
    }

    private static String shutterText(long ns) {
        double s = ns / 1e9;
        if (s >= 0.95) return String.format(Locale.US, "%.1f s", s);
        return "1/" + Math.round(1.0 / s);
    }

    // ================================================================== state

    private void setMode(int m) {
        if (busy || recActive || counting) return;
        if (m == CameraEngine.VIDEO) presetRatio();
        if (m == CameraEngine.DEEP) engine.setDeepHires(deepWantsHires());
        engine.setMode(m);
        if (m == CameraEngine.VIDEO && Store.bool(this, "vaudio", true)
                && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED && !Store.bool(this, "asked_audio", false)) {
            Store.setBool(this, "asked_audio", true);
            audioLauncher.launch(Manifest.permission.RECORD_AUDIO);
        }
        refreshModeUi();
    }

    private int[] ratioDims() {
        int w = holder.getWidth() > 0 ? holder.getWidth() : getResources().getDisplayMetrics().widthPixels;
        int h;
        if (vKind == 0) h = w * 16 / 9;
        else if (vKind == 1) h = w;
        else {
            View par = (View) holder.getParent();
            h = par.getHeight() > 0 ? par.getHeight() - holder.getTop() : getResources().getDisplayMetrics().heightPixels;
        }
        return new int[]{w, Math.max(1, h)};
    }

    private void presetRatio() {
        int[] d = ratioDims();
        engine.presetVideoRatio(vKind, d[0], d[1]);
    }

    private void cycleVideoRatio() {
        if (recActive || counting || busy) return;
        vKind = (vKind + 1) % 3;
        Store.setNum(this, "vratio", vKind);
        refreshModeUi();
        int[] d = ratioDims();
        engine.setVideoRatio(vKind, d[0], d[1]);
    }

    private void refreshRatioPill() {
        ratioPill.setText(vKind == 0 ? "9:16" : vKind == 1 ? "1:1" : getString(R.string.cam_ratio_full));
    }

    private boolean deepWantsHires() {
        if (Store.num(this, "deep_res", 0) != 1) return false;
        try {
            android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
            android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            return !mi.lowMemory && mi.availMem > 2000L * 1024 * 1024;   // 50 MP frames need about 350 MB to merge
        } catch (Throwable t) {
            return false;
        }
    }

    private void styleMode(TextView t, boolean sel) {
        t.setTextColor(sel ? Ios.YELLOW : 0xB3FFFFFF);
    }

    /** The lens buttons (0.7 / 1 / 2 / 4 …) under the preview, iOS style: the active one is yellow and shows the exact zoom. */
    private void rebuildLensRow() {
        lensRow.removeAllViews();
        lensBtns.clear();
        lensStops.clear();
        if (engine == null) return;
        if (!engine.isFront() && engine.lenses.size() > 1) {
            for (CameraEngine.Lens l : engine.lenses) if (!lensStops.contains(l.factor)) lensStops.add(l.factor);
            for (float st : new float[]{1f, 2f, 4f}) if (!lensStops.contains(st)) lensStops.add(st);
        } else {
            float max = engine.zoomMax() * engine.lensFactor();
            for (float st : new float[]{1f, 2f, 4f}) if (st <= max + 0.01f) lensStops.add(st);
            if (lensStops.isEmpty()) lensStops.add(1f);
        }
        java.util.Collections.sort(lensStops);
        for (final float st : lensStops) {
            TextView b = new TextView(this);
            b.setGravity(Gravity.CENTER);
            b.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            b.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                if (engine.isFront() || engine.lenses.size() < 2) engine.setZoom(st);
                else goToStop(st);
            });
            Ui.press(this, b);
            lensBtns.add(b);
            lensRow.addView(b, new LinearLayout.LayoutParams(-2, dp(34)));
        }
        updateLens(engine.zoom() * engine.lensFactor());
    }

    private void updateLens(float display) {
        if (lensStops.isEmpty()) return;
        int near = 0;
        for (int i = 0; i < lensStops.size(); i++) {
            if (Math.abs(lensStops.get(i) - display) < Math.abs(lensStops.get(near) - display)) near = i;
        }
        for (int i = 0; i < lensBtns.size(); i++) {
            TextView b = lensBtns.get(i);
            boolean sel = i == near;
            float st = lensStops.get(i);
            String plain = Math.abs(st - Math.round(st)) < 0.05f ? String.valueOf(Math.round(st))
                    : String.format(Locale.US, "%.1f", st);
            b.setText(sel ? zoomText(display) : plain);
            b.setTextSize(sel ? 13f : 12f);
            b.setTextColor(sel ? Ios.YELLOW : 0xFFFFFFFF);
            b.setMinWidth(dp(sel ? 46 : 34));
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(100));
            g.setColor(sel ? 0xB3000000 : 0x00000000);
            b.setBackground(g);
        }
    }

    private void cycleTimer() {
        if (counting || recActive) return;
        int t = Store.num(this, "timer", 0);
        Store.setNum(this, "timer", t == 0 ? 3 : t == 3 ? 10 : 0);
        refreshTop();
    }

    private void rebuildVideoRow() {
        videoRow.removeAllViews();
        videoChips.clear();
        String cur = engine.videoQuality == null ? "" : CameraEngine.qCode(engine.videoQuality);
        List<androidx.camera.video.Quality> qs = new ArrayList<>(engine.videoQualities);
        for (int i = qs.size() - 1; i >= 0; i--) {
            final androidx.camera.video.Quality q = qs.get(i);
            TextView ch = Ui.chip(this, CameraEngine.qName(q), CameraEngine.qCode(q).equals(cur));
            ch.setTag(CameraEngine.qCode(q));
            ch.setOnClickListener(v -> {
                if (recActive || counting) return;
                Store.setStr(this, "video_q", CameraEngine.qCode(q));
                engine.bind();
            });
            videoChips.add(ch);
            videoRow.addView(ch);
        }
    }

    private final Runnable liveInfo = new Runnable() {
        @Override
        public void run() {
            if (started && engine != null && !recActive && !counting) refreshInfoPill();
            liveHand.postDelayed(this, 700);
        }
    };

    private void refreshInfoPill() {
        String t = "";
        if (engine != null && started) {
            if (engine.mode() == CameraEngine.VIDEO) {
                if (engine.videoQuality != null) {
                    t = CameraEngine.qName(engine.videoQuality);
                    if (engine.videoSize != null) {
                        t = CamInfo.videoName(engine.videoSize.getWidth(), engine.videoSize.getHeight()) + " · 30";
                    }
                }
            } else if (engine.photoSize != null) {
                t = CamInfo.mpText(engine.photoSize.getWidth(), engine.photoSize.getHeight());
                int iso = engine.liveIso();
                long ns = engine.liveExpNs();
                if (iso > 0 && ns > 0) t += " · ISO " + iso + " · " + shutterText(ns);
            }
        }
        infoPill.setText(t);
        infoPill.setVisibility(t.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void refreshModeUi() {
        int m = engine == null ? CameraEngine.PHOTO : engine.mode();
        boolean hiOk = engine != null && engine.hiresAvailable;
        for (int i = 0; i < modeChips.size(); i++) {
            int id = modeIds.get(i);
            styleMode(modeChips.get(i), id == m);
            if (id == CameraEngine.DEEP) {
                modeChips.get(i).setVisibility(Store.bool(this, "deep_on", false) ? View.VISIBLE : View.GONE);
            }
            if (id == CameraEngine.HIRES) {
                modeChips.get(i).setVisibility(hiOk ? View.VISIBLE : View.GONE);
                if (hiOk && engine.maxSize != null) {
                    modeChips.get(i).setText(CamInfo.mpText(engine.maxSize.getWidth(), engine.maxSize.getHeight()));
                }
            }
        }
        for (int i = 0; i < modeChips.size(); i++) {
            if (modeIds.get(i) == m) {
                final View chip = modeChips.get(i);
                modeScroll.post(() -> modeScroll.smoothScrollTo(chip.getLeft() - (modeScroll.getWidth() - chip.getWidth()) / 2, 0));
            }
        }
        boolean vid = m == CameraEngine.VIDEO;
        if (holder instanceof Ratio34) ((Ratio34) holder).setVideo(vid, vKind);
        ratioPill.setVisibility(vid ? View.VISIBLE : View.GONE);
        refreshRatioPill();
        LinearLayout.LayoutParams hl = (LinearLayout.LayoutParams) holder.getLayoutParams();
        boolean overlap = vid && vKind != 1;
        int wantMargin = overlap ? -dp(130) : 0;
        if (hl.bottomMargin != wantMargin) {
            hl.bottomMargin = wantMargin;
            holder.setLayoutParams(hl);
        }
        FrameLayout.LayoutParams zl = (FrameLayout.LayoutParams) lensRow.getLayoutParams();
        int lensMargin = dp(10) + (overlap ? dp(130) : 0);
        if (zl.bottomMargin != lensMargin) {
            zl.bottomMargin = lensMargin;
            lensRow.setLayoutParams(zl);
        }
        if (overlap) {
            GradientDrawable scrim = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{0x00000000, 0xB3000000, 0xF2000000});
            bottomBar.setBackground(scrim);
        } else {
            bottomBar.setBackground(null);
        }
        levelRow.setVisibility(vid ? View.GONE : (m == CameraEngine.NIGHT || m == CameraEngine.DEEP || m == CameraEngine.HDR) ? View.INVISIBLE : View.VISIBLE);
        videoRow.setVisibility(vid ? View.VISIBLE : View.GONE);
        if (vid && engine != null) rebuildVideoRow();
        proPanel.setVisibility(m == CameraEngine.PRO ? View.VISIBLE : View.GONE);
        shutter.setNight(m == CameraEngine.NIGHT || m == CameraEngine.DEEP);
        shutter.setVideo(vid);
        refreshInfoPill();
        if (m == CameraEngine.PRO && engine != null) rebuildPro();
        refreshTop();
    }

    private void setLevel(String l) {
        level = l;
        Store.setStr(this, "level", l);
        for (TextView c : levelChips) Ui.setChip(this, c, l.equals(c.getTag()));
    }

    private void refreshTop() {
        boolean photo = engine == null || engine.mode() == CameraEngine.PHOTO || engine.mode() == CameraEngine.HIRES;   // deep: no flash
        boolean video = engine != null && engine.mode() == CameraEngine.VIDEO;
        boolean canFlash = engine != null && engine.hasFlash && !engine.isFront() && (photo || video);
        flashBtn.setVisibility(canFlash ? View.VISIBLE : View.INVISIBLE);
        int[] icons = {R.drawable.ic_flash_off, R.drawable.ic_flash_auto, R.drawable.ic_flash};
        flashBtn.setImageResource(video ? (engine.torchOn() ? icons[2] : icons[0]) : icons[flashState]);
        if (levelView != null) levelView.setEnabledLine(Store.bool(this, "level_line", true));
        int tsec = Store.num(this, "timer", 0);
        timerBtn.setText(tsec == 0 ? "⏱" : "⏱ " + tsec + "s");
        GradientDrawable tg = new GradientDrawable();
        tg.setCornerRadius(dp(100));
        tg.setColor(tsec > 0 ? Ui.color(this, R.color.accent) : Ui.color(this, R.color.neutral_soft));
        timerBtn.setBackground(tg);
        timerBtn.setTextColor(Color.WHITE);
        boolean hdrOk = engine != null && engine.hdrAvailable && photo && !engine.isFront();
        hdrBtn.setVisibility(hdrOk ? View.VISIBLE : View.GONE);
        boolean hdrOn = Store.bool(this, "hdr", false);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(100));
        g.setColor(hdrOn ? Ui.color(this, R.color.accent) : Ui.color(this, R.color.neutral_soft));
        hdrBtn.setBackground(g);
        hdrBtn.setTextColor(Color.WHITE);
        boolean gridOn = Store.bool(this, "grid", true);
        grid.setVisibility(gridOn ? View.VISIBLE : View.GONE);
        gridBtn.setImageTintList(android.content.res.ColorStateList.valueOf(
                Ui.color(this, gridOn ? R.color.accent : R.color.text_primary)));
    }

    private void cycleFlash() {
        if (engine.mode() == CameraEngine.VIDEO) {
            engine.setTorch(!engine.torchOn());
            refreshTop();
            return;
        }
        flashState = (flashState + 1) % 3;
        engine.setFlash(flashState == 0 ? ImageCapture.FLASH_MODE_OFF
                : flashState == 1 ? ImageCapture.FLASH_MODE_AUTO : ImageCapture.FLASH_MODE_ON);
        refreshTop();
    }

    private void toggleHdr() {
        boolean on = !Store.bool(this, "hdr", false);
        Store.setBool(this, "hdr", on);
        engine.setHdr(on);
        refreshTop();
    }

    private void switchCamera() {
        if (busy || recActive || counting) return;
        engine.setFront(!engine.isFront());
        switchBtn.animate().rotationBy(180f).setDuration(280).start();
    }

    private void cycleZoom() {
        if (engine.isFront() || engine.lenses.size() < 2) {
            float min = engine.zoomMin(), max = engine.zoomMax(), cur = engine.zoom();
            float[] steps = {0.6f, 1f, 2f, 5f};
            float next = -1f;
            for (float s : steps) {
                if (s >= min - 0.01f && s <= max + 0.01f && s > cur + 0.05f) {
                    next = s;
                    break;
                }
            }
            if (next < 0f) next = Math.max(min, 1f);
            engine.setZoom(next);
            return;
        }
        // several back lenses: stops on the "1x = main lens" scale, each one served by the best lens for it
        List<Float> stops = new ArrayList<>();
        for (CameraEngine.Lens l : engine.lenses) if (!stops.contains(l.factor)) stops.add(l.factor);
        for (float s : new float[]{1f, 2f, 4f}) if (!stops.contains(s)) stops.add(s);
        java.util.Collections.sort(stops);
        float cur = engine.zoom() * engine.lensFactor();
        float next = stops.get(0);
        for (float s : stops) {
            if (s > cur + 0.08f) {
                next = s;
                break;
            }
        }
        goToStop(next);
    }

    private void goToStop(float stop) {
        CameraEngine.Lens best = engine.lenses.get(0);
        for (CameraEngine.Lens l : engine.lenses) if (l.factor <= stop * 1.05f && l.factor >= best.factor) best = l;
        if (stop < best.factor) {
            for (CameraEngine.Lens l : engine.lenses) if (l.factor < best.factor) best = l;
        }
        float ratio = Math.max(1f, stop / best.factor);
        boolean sameLens = best.id.equals(engine.lensId()) || (engine.lensId() == null && best == engine.lenses.get(0));
        if (sameLens) {
            engine.setZoom(ratio);
        } else {
            pendingStop = ratio;
            engine.setLens(best == engine.lenses.get(0) ? null : best.id);
        }
    }

    private void showFocus(float x, float y) {
        if (engine.mode() == CameraEngine.PRO && engine.manualFocus && engine.focus >= 0f) return;
        focusRing.animate().cancel();
        focusRing.setX(x - dp(36));
        focusRing.setY(y - dp(36));
        focusRing.setAlpha(1f);
        focusRing.setScaleX(1.35f);
        focusRing.setScaleY(1.35f);
        focusRing.setVisibility(View.VISIBLE);
        focusRing.animate().scaleX(1f).scaleY(1f).setDuration(180).withEndAction(() ->
                focusRing.animate().alpha(0f).setStartDelay(900).setDuration(300)
                        .withEndAction(() -> focusRing.setVisibility(View.INVISIBLE)).start()).start();
    }

    private void updateStatus() {
        String t = recActive ? recText : nightText;
        status.setTextColor(recActive ? 0xFFFF453A : Color.WHITE);
        if (t.isEmpty() && Processor.pending() > 0) t = getString(R.string.cam_processing_now);
        status.setText(t);
        status.setVisibility(t.isEmpty() ? View.GONE : View.VISIBLE);
    }

    // ================================================================== capture

    private static byte[] jpegBytes(ImageProxy image) {
        if (image.getFormat() != android.graphics.ImageFormat.JPEG) return null;
        ByteBuffer buf = image.getPlanes()[0].getBuffer();
        byte[] b = new byte[buf.remaining()];
        buf.get(b);
        return b;
    }

    private void flashEffect() {
        final View v = new View(this);
        v.setBackgroundColor(Color.BLACK);
        holder.addView(v, new FrameLayout.LayoutParams(-1, -1));
        v.setAlpha(0.85f);
        v.animate().alpha(0f).setDuration(220).withEndAction(() -> holder.removeView(v)).start();
    }

    private void shoot() {
        if (!started) return;
        if (recActive) {
            engine.stopRecording();
            return;
        }
        if (deepRunning) {
            deepStop = true;   // finish early with the frames taken so far
            return;
        }
        if (busy) return;
        if (counting) {
            cancelCountdown();
            return;
        }
        Runnable go = engine.mode() == CameraEngine.VIDEO ? this::startVideo : this::shootNow;
        int t = Store.num(this, "timer", 0);
        if (t <= 0) {
            go.run();
            return;
        }
        counting = true;
        shutter.setBusy(true);
        tick(t, go);
    }

    private void tick(final int left, final Runnable go) {
        if (!counting) return;
        if (left <= 0) {
            counting = false;
            countView.setVisibility(View.GONE);
            shutter.setBusy(false);
            go.run();
            return;
        }
        countView.setText(String.valueOf(left));
        countView.setVisibility(View.VISIBLE);
        hand.postDelayed(() -> tick(left - 1, go), 1000);
    }

    private void cancelCountdown() {
        counting = false;
        hand.removeCallbacksAndMessages(null);
        countView.setVisibility(View.GONE);
        shutter.setBusy(false);
    }

    // ---- video

    private void startVideo() {
        if (recActive || engine.mode() != CameraEngine.VIDEO) return;
        boolean wantAudio = Store.bool(this, "vaudio", true);
        boolean audio = wantAudio && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
        if (wantAudio && !audio) Toast.makeText(this, R.string.cam_no_audio, Toast.LENGTH_SHORT).show();
        if (!engine.startRecording(this, audio, this::onRecEvent)) {
            Toast.makeText(this, R.string.cam_video_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        recActive = true;
        recText = "● 00:00";
        shutter.setRecording(true);
        updateStatus();
    }

    private static String clock(long nanos) {
        long s = nanos / 1_000_000_000L;
        return String.format(Locale.US, "%02d:%02d", s / 60, s % 60);
    }

    private void onRecEvent(VideoRecordEvent e) {
        if (e instanceof VideoRecordEvent.Status) {
            recText = "● " + clock(((VideoRecordEvent.Status) e).getRecordingStats().getRecordedDurationNanos());
            updateStatus();
        } else if (e instanceof VideoRecordEvent.Finalize) {
            VideoRecordEvent.Finalize f = (VideoRecordEvent.Finalize) e;
            recActive = false;
            recText = "";
            shutter.setRecording(false);
            updateStatus();
            if (f.hasError() && f.getError() != VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED
                    && f.getError() != VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED) {
                Toast.makeText(this, f.getError() == VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA
                        ? R.string.cam_video_short : R.string.cam_video_failed, Toast.LENGTH_SHORT).show();
                return;
            }
            final Uri u = f.getOutputResults().getOutputUri();
            if (u == null) return;
            if ("file".equals(u.getScheme()) && u.getPath() != null) {
                MediaScannerConnection.scanFile(this, new String[]{u.getPath()}, new String[]{"video/mp4"},
                        (path, uri) -> runOnUiThread(() -> videoSaved(uri != null ? uri : u)));
            } else {
                videoSaved(u);
            }
        }
    }

    private void videoSaved(Uri u) {
        lastUri = u;
        lastIsVideo = true;
        setThumb(u);
        Toast.makeText(this, R.string.cam_video_saved, Toast.LENGTH_SHORT).show();
    }

    /** How many frames to merge, from the light the camera measures: bright 1, dim 3, dark 5. */
    private int adaptiveFrames() {
        int iso = engine.liveIso();
        long ns = engine.liveExpNs();
        if (iso <= 0 || ns <= 0) return 3;
        if (iso >= 1000 || ns >= 66_000_000L) return 5;
        if (iso >= 400 || ns >= 25_000_000L) return 3;
        return 1;
    }

    // ---- HDR: three exposures (normal, darker, brighter) fused into one

    private void startHdr() {
        hdrFrames = new ArrayList<>();
        hdrPrevEv = engine.evIndex();
        hdrIdx = 0;
        hdrStops = new float[]{0f, -2f, 2f};
        hdrSteps = new int[]{hdrPrevEv, engine.evFrom(hdrPrevEv, -2f), engine.evFrom(hdrPrevEv, 2f)};
        hdrRunning = true;
        busy = true;
        shutter.setBusy(true);
        nightText = getString(R.string.hdr_status);
        updateStatus();
        hand.postDelayed(this::hdrNext, 120);
    }

    private void hdrNext() {
        if (!hdrRunning) return;
        if (hdrIdx >= hdrSteps.length) {
            finishHdr();
            return;
        }
        if (hdrIdx == 0) {
            hdrCapture();
            return;
        }
        final double e0 = engine.liveIso() * (double) engine.liveExpNs();
        engine.setEv(hdrSteps[hdrIdx]);
        hdrSettle(hdrStops[hdrIdx], SystemClock.uptimeMillis(), e0);
    }

    private void hdrSettle(final float stops, final long t0, final double e0) {
        if (!hdrRunning) return;
        long el = SystemClock.uptimeMillis() - t0;
        boolean ok;
        if (e0 <= 0) {
            ok = el >= 800;
        } else {
            double cur = engine.liveIso() * (double) engine.liveExpNs();
            double st = cur > 0 ? Math.log(cur / e0) / Math.log(2) : 0;
            ok = stops < 0 ? st <= stops * 0.6 : st >= stops * 0.6;
            if (el >= 1600) ok = true;
        }
        if (!ok) {
            hand.postDelayed(() -> hdrSettle(stops, t0, e0), 70);
            return;
        }
        hand.postDelayed(this::hdrCapture, 130);
    }

    private void hdrCapture() {
        if (!hdrRunning) return;
        engine.capture(new ImageCapture.OnImageCapturedCallback() {
            @Override
            public void onCaptureSuccess(ImageProxy image) {
                byte[] b = jpegBytes(image);
                int rot = image.getImageInfo().getRotationDegrees();
                image.close();
                if (!hdrRunning || hdrFrames == null) return;
                if (b != null) {
                    if (hdrFrames.isEmpty()) {
                        hdrRot = rot;
                        float[] st = Processor.exposureStats(b);
                        if (st != null) {
                            float under = st[0] > 0.10f ? 3f : st[0] > 0.02f ? 2f : st[0] > 0.004f ? 1.5f : 1f;
                            float over = st[1] > 0.25f ? 3f : st[1] > 0.08f ? 2f : st[1] > 0.02f ? 1.5f : 1f;
                            hdrStops[1] = -under;
                            hdrStops[2] = over;
                        }
                        hdrSteps[1] = engine.evFrom(hdrPrevEv, hdrStops[1]);
                        hdrSteps[2] = engine.evFrom(hdrPrevEv, hdrStops[2]);
                    }
                    hdrFrames.add(b);
                }
                hdrIdx++;
                hand.post(CameraActivity.this::hdrNext);
            }

            @Override
            public void onError(ImageCaptureException e) {
                if (!hdrRunning) return;
                hdrIdx++;
                hand.post(CameraActivity.this::hdrNext);
            }
        });
    }

    private void endHdrState() {
        hdrRunning = false;
        engine.setEv(hdrPrevEv);
        busy = false;
        shutter.setBusy(false);
        nightText = "";
    }

    private void abortHdr() {
        endHdrState();
        updateStatus();
    }

    private void finishHdr() {
        endHdrState();
        List<byte[]> frames = hdrFrames;
        hdrFrames = null;
        if (frames == null || frames.isEmpty()) {
            Toast.makeText(this, R.string.cam_error, Toast.LENGTH_SHORT).show();
            updateStatus();
            return;
        }
        Processor.hdr(this, frames, hdrRot, quality(), done);
        updateStatus();
    }

    // ---- deep capture: hold still, many frames are merged into one

    private final Runnable deepTick = new Runnable() {
        @Override
        public void run() {
            if (!deepRunning) return;
            long left = Math.max(0, deepEnd - SystemClock.uptimeMillis());
            countView.setText(String.valueOf((left + 999) / 1000));
            countView.setVisibility(View.VISIBLE);
            nightText = getString(R.string.deep_status, deepFrames);
            updateStatus();
            hand.postDelayed(this, 250);
        }
    };

    private void startDeep() {
        int secs = Store.num(this, "deep_secs", 15);
        deep = Processor.deepBegin();
        deepRunning = true;
        deepStop = false;
        deepFrames = 0;
        deepErrors = 0;
        deepEnd = SystemClock.uptimeMillis() + secs * 1000L;
        busy = true;
        shutter.setRecording(true);
        shutter.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        Toast.makeText(this, R.string.deep_hold, Toast.LENGTH_SHORT).show();
        hand.post(deepTick);
        deepNext();
    }

    private void deepNext() {
        if (!deepRunning) return;
        int cap = engine.hiresActive() ? 10 : 60;   // 50 MP frames are big: fewer, the merge buffer grows with the size
        if (deepStop || SystemClock.uptimeMillis() >= deepEnd || deepFrames >= cap || deepErrors >= 5) {
            finishDeep();
            return;
        }
        if (Processor.deepPending(deep) > 2) {
            hand.postDelayed(this::deepNext, 80);   // the merge is slower than the camera: wait for it
            return;
        }
        engine.capture(new ImageCapture.OnImageCapturedCallback() {
            @Override
            public void onCaptureSuccess(ImageProxy image) {
                byte[] b = jpegBytes(image);
                int rot = image.getImageInfo().getRotationDegrees();
                image.close();
                if (b == null || deep == null) {
                    deepErrors++;
                } else {
                    if (deepFrames == 0) deep.rotation = rot;
                    Processor.deepAdd(deep, b);
                    deepFrames++;
                }
                hand.post(CameraActivity.this::deepNext);
            }

            @Override
            public void onError(ImageCaptureException e) {
                deepErrors++;
                hand.postDelayed(CameraActivity.this::deepNext, 150);
            }
        });
    }

    private void finishDeep() {
        if (!deepRunning) return;
        deepRunning = false;
        hand.removeCallbacks(deepTick);
        countView.setVisibility(View.GONE);
        nightText = "";
        busy = false;
        shutter.setRecording(false);
        shutter.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        Processor.Deep d = deep;
        deep = null;
        if (d == null || deepFrames == 0) {
            Toast.makeText(this, R.string.cam_error, Toast.LENGTH_SHORT).show();
            updateStatus();
            return;
        }
        Processor.deepFinish(this, d, quality(), done);
        updateStatus();
    }

    private void shootNow() {
        if (busy) return;
        if (engine.mode() == CameraEngine.DEEP) {
            startDeep();
            return;
        }
        if (engine.mode() == CameraEngine.HDR && engine.evSupported && Native.LOADED) {
            startHdr();
            return;
        }
        int frames = adaptiveFrames();
        if (engine.mode() == CameraEngine.PHOTO && Store.bool(this, "multi_photo", true) && flashState == 0
                && !Store.bool(this, "hdr", false) && !engine.hiresActive() && Native.LOADED && !Levels.OFF.equals(level)
                && frames > 1) {
            multiShot = true;
            busy = true;
            shutter.setBusy(true);
            flashEffect();
            nextFrame(0, frames, new ArrayList<>(), new int[]{0});
            return;
        }
        multiShot = false;
        if (engine.mode() == CameraEngine.NIGHT) {
            shootNight();
            return;
        }
        busy = true;
        shutter.setBusy(true);
        flashEffect();
        engine.capture(new ImageCapture.OnImageCapturedCallback() {
            @Override
            public void onCaptureSuccess(ImageProxy image) {
                byte[] b = jpegBytes(image);
                int rot = image.getImageInfo().getRotationDegrees();
                image.close();
                busy = false;
                shutter.setBusy(false);
                if (b == null) {
                    Toast.makeText(CameraActivity.this, R.string.cam_error, Toast.LENGTH_SHORT).show();
                    return;
                }
                Processor.shot(CameraActivity.this, b, rot, level, quality(), done);
                updateStatus();
            }

            @Override
            public void onError(ImageCaptureException e) {
                busy = false;
                shutter.setBusy(false);
                Toast.makeText(CameraActivity.this, R.string.cam_error, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private int quality() {
        return Store.num(this, "jpeg_q", 100);
    }

    private final Processor.Done done = (uri, enhanced) -> {
        updateStatus();
        if (uri != null) {
            lastUri = uri;
            lastIsVideo = false;
            setThumb(uri);
        } else {
            Toast.makeText(this, R.string.cam_save_failed, Toast.LENGTH_LONG).show();
        }
    };

    private void shootNight() {
        final int n = Math.max(3, Math.min(10, Store.num(this, "night_frames", 6)));
        busy = true;
        shutter.setBusy(true);
        nextFrame(0, n, new ArrayList<>(), new int[]{0});
    }

    private void nextFrame(final int i, final int n, final List<byte[]> frames, final int[] rot) {
        nightText = getString(R.string.cam_night_hold, i + 1, n);
        updateStatus();
        engine.capture(new ImageCapture.OnImageCapturedCallback() {
            @Override
            public void onCaptureSuccess(ImageProxy image) {
                byte[] b = jpegBytes(image);
                if (i == 0) rot[0] = image.getImageInfo().getRotationDegrees();
                image.close();
                if (b != null) frames.add(b);
                if (i + 1 < n) nextFrame(i + 1, n, frames, rot);
                else endNight(frames, rot[0]);
            }

            @Override
            public void onError(ImageCaptureException e) {
                if (frames.isEmpty()) {
                    nightText = "";
                    busy = false;
                    shutter.setBusy(false);
                    updateStatus();
                    Toast.makeText(CameraActivity.this, R.string.cam_error, Toast.LENGTH_SHORT).show();
                } else {
                    endNight(frames, rot[0]);   // keep the frames that did arrive
                }
            }
        });
    }

    private void endNight(List<byte[]> frames, int rot) {
        nightText = "";
        busy = false;
        shutter.setBusy(false);
        if (multiShot) {
            multiShot = false;
            Processor.night(this, frames, rot, quality(), Levels.params(level), done);
        } else {
            Processor.night(this, frames, rot, quality(), done);
        }
        updateStatus();
    }

    // ================================================================== thumbnail + gallery link

    private void setThumb(final Uri uri) {
        io.execute(() -> {
            final Bitmap bmp = loadThumb(uri, 240);
            runOnUiThread(() -> {
                if (bmp == null || isDestroyed()) return;
                thumb.setImageTintList(null);
                thumb.setPadding(0, 0, 0, 0);
                thumb.setImageBitmap(bmp);
            });
        });
    }

    private Bitmap loadVideoThumb(Uri uri, int target) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(this, uri);
            Bitmap b = r.getFrameAtTime(0);
            if (b == null) return null;
            float k = Math.max(1f, Math.min(b.getWidth(), b.getHeight()) / (float) target);
            return Bitmap.createScaledBitmap(b, Math.round(b.getWidth() / k), Math.round(b.getHeight() / k), true);
        } catch (Throwable t) {
            return null;
        } finally {
            try {
                r.release();
            } catch (Throwable ignored) {
            }
        }
    }

    private Bitmap loadThumb(Uri uri, int target) {
        if (lastIsVideo) return loadVideoThumb(uri, target);
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, o);
            }
            int s = 1;
            while (o.outWidth / (s * 2) >= target && o.outHeight / (s * 2) >= target) s *= 2;
            BitmapFactory.Options d = new BitmapFactory.Options();
            d.inSampleSize = s;
            Bitmap b;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                b = BitmapFactory.decodeStream(in, null, d);
            }
            if (b == null) return null;
            int orientation = ExifInterface.ORIENTATION_NORMAL;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in != null) orientation = new ExifInterface(in).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            } catch (Throwable ignored) {
            }
            int deg = orientation == ExifInterface.ORIENTATION_ROTATE_90 ? 90
                    : orientation == ExifInterface.ORIENTATION_ROTATE_180 ? 180
                    : orientation == ExifInterface.ORIENTATION_ROTATE_270 ? 270 : 0;
            if (deg == 0) return b;
            Matrix m = new Matrix();
            m.postRotate(deg);
            return Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Finds the newest photo or video in DCIM/Camera so the thumbnail is right after a restart. */
    private void loadLast() {
        io.execute(() -> {
            Uri found = null;
            boolean isVid = false;
            try {
                String sel;
                String[] args;
                String media = "(" + MediaStore.Files.FileColumns.MEDIA_TYPE + "=1 OR "
                        + MediaStore.Files.FileColumns.MEDIA_TYPE + "=3)";
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    sel = media + " AND " + MediaStore.Images.Media.RELATIVE_PATH + " LIKE ?";
                    args = new String[]{"DCIM/Camera%"};
                } else {
                    sel = media + " AND " + MediaStore.Images.Media.DATA + " LIKE ?";
                    args = new String[]{"%/DCIM/Camera/%"};
                }
                try (Cursor c = getContentResolver().query(MediaStore.Files.getContentUri("external"),
                        new String[]{MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.MEDIA_TYPE},
                        sel, args, MediaStore.Files.FileColumns.DATE_ADDED + " DESC")) {
                    if (c != null && c.moveToFirst()) {
                        isVid = c.getInt(1) == 3;
                        found = android.content.ContentUris.withAppendedId(isVid
                                ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                                : MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0));
                    }
                }
            } catch (Throwable ignored) {
            }
            final Uri u = found;
            final boolean vid = isVid;
            if (u != null) runOnUiThread(() -> {
                lastUri = u;
                lastIsVideo = vid;
                setThumb(u);
            });
        });
    }

    private void openLast() {
        if (lastUri == null) {
            if (!Semo.open(this, getSiblingGallery())) {
                Toast.makeText(this, R.string.cam_no_photo, Toast.LENGTH_SHORT).show();
            }
            return;
        }
        String mime = lastIsVideo ? "video/mp4" : "image/jpeg";
        if (Semo.view(this, lastUri, mime, Semo.ROLE_GALLERY)) return;   // the Gallery app of the same family
        try {
            startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(lastUri, mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
        } catch (Exception e) {
            Toast.makeText(this, R.string.cam_no_viewer, Toast.LENGTH_SHORT).show();
        }
    }

    private String getSiblingGallery() {
        Semo.Sibling s = Semo.byRole(this, Semo.ROLE_GALLERY);
        return s == null ? "" : s.pkg;
    }

    // ================================================================== permissions + lifecycle

    private String[] neededPerms() {
        if (Build.VERSION.SDK_INT <= 28) {
            return new String[]{Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE};
        }
        return new String[]{Manifest.permission.CAMERA};
    }

    private boolean hasPerms() {
        for (String p : neededPerms()) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    private boolean shouldOpenSettings() {
        for (String p : neededPerms()) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED
                    && !androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(this, p)
                    && Store.bool(this, "asked", false)) {
                return true;
            }
        }
        return false;
    }

    private void afterPermission() {
        Store.setBool(this, "asked", true);
        refreshAccess();
    }

    private void refreshAccess() {
        boolean ok = hasPerms();
        permView.setVisibility(ok ? View.GONE : View.VISIBLE);
        if (ok && !started) {
            started = true;
            engine.start();
            loadLast();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!hasPerms() && !Store.bool(this, "asked", false)) {
            permLauncher.launch(neededPerms());
        }
        refreshAccess();
        final String crash = CrashLog.take(this);
        if (crash != null) {
            android.widget.ScrollView sv = new android.widget.ScrollView(this);
            TextView t = new TextView(this);
            t.setText(crash);
            t.setTextSize(11);
            t.setTextIsSelectable(true);
            t.setTextColor(Ui.color(this, R.color.text_secondary));
            t.setPadding(dp(20), dp(8), dp(20), dp(8));
            sv.addView(t);
            new Dlg(this).setTitle(R.string.crash_title).setView(sv)
                    .setPositiveButton(R.string.crash_copy, (d, w) -> {
                        android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                        if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("crash", crash));
                    })
                    .setNegativeButton(R.string.close, null).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (orient != null && orient.canDetectOrientation()) orient.enable();

        engine.checkPrefs();
        liveHand.removeCallbacks(liveInfo);
        liveHand.postDelayed(liveInfo, 700);
        if (levelView != null) levelView.start();
        if (engine.mode() == CameraEngine.DEEP) {
            if (!Store.bool(this, "deep_on", false)) engine.setMode(CameraEngine.PHOTO);
            else engine.setDeepHires(deepWantsHires());
        }
        refreshModeUi();
        level = Store.str(this, "level", level);
        for (TextView c : levelChips) Ui.setChip(this, c, level.equals(c.getTag()));
        refreshTop();
        updateStatus();
        if (started) loadLast();
    }

    @Override
    protected void onPause() {
        if (levelView != null) levelView.stop();
        liveHand.removeCallbacks(liveInfo);
        if (hdrRunning) abortHdr();
        if (deepRunning) {
            deepStop = true;
            finishDeep();
        }
        if (counting) cancelCountdown();
        if (recActive) engine.stopRecording();
        if (orient != null) orient.disable();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        io.shutdown();
        super.onDestroy();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) && started
                && permView.getVisibility() != View.VISIBLE) {
            if (event.getRepeatCount() == 0) shoot();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ================================================================== Listener

    @Override
    public void onBound() {
        boolean m = engine.mode() == CameraEngine.PRO;
        if (m) rebuildPro();
        refreshModeUi();
        refreshTop();
        rebuildLensRow();
        refreshInfoPill();
        if (engine.mode() == CameraEngine.VIDEO && engine.fxActive()) {
            final int gen = engine.fxGen();
            previewView.postDelayed(() -> {
                if (engine.fxActive() && engine.fxGen() == gen && engine.mode() == CameraEngine.VIDEO && !recActive
                        && (engine.fxFailed() || engine.fxFrames() == 0)) {
                    Store.setNum(this, "vfx", 0);
                    Toast.makeText(this, R.string.cam_vfx_failed, Toast.LENGTH_LONG).show();
                    engine.bind();
                }
            }, 3500);
            previewView.postDelayed(() -> {
                if (engine.fxActive() && engine.fxGen() == gen && engine.mode() == CameraEngine.VIDEO && !recActive
                        && engine.fxFps() > 0f && engine.fxFps() < 22f) {
                    Store.setNum(this, "vfx", 0);
                    Toast.makeText(this, R.string.cam_vfx_failed, Toast.LENGTH_LONG).show();
                    engine.bind();
                }
            }, 7000);
        }
        if (pendingStop > 0f) {
            float z = pendingStop;
            pendingStop = -1f;
            engine.setZoom(z);
        }
    }

    private static String zoomText(float z) {
        return (Math.abs(z - Math.round(z)) < 0.05f ? String.valueOf(Math.round(z))
                : String.format(Locale.US, "%.1f", z)) + "×";
    }

    @Override
    public void onZoom(float ratio) {
        updateLens(ratio * engine.lensFactor());
    }

    @Override
    public void onError(String message) {
        if ("video".equals(message)) {
            Toast.makeText(this, getString(R.string.cam_no_video_support), Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, getString(R.string.cam_open_failed), Toast.LENGTH_LONG).show();
    }

    // ================================================================== small views

    /** Keeps the preview 3:4 (the photo's shape) whatever the screen is. */
    private static final class Ratio34 extends FrameLayout {
        private boolean video;
        private int kind;

        Ratio34(android.content.Context c) {
            super(c);
        }

        /** Video is recorded 9:16, so its preview is 9:16 too (the controls float over its lower part). */
        void setVideo(boolean v, int k) {
            if (video == v && kind == k) return;
            video = v;
            kind = k;
            requestLayout();
        }

        @Override
        protected void onMeasure(int w, int h) {
            int width = MeasureSpec.getSize(w);
            int height = width * 4 / 3;
            if (video) {
                int avail = MeasureSpec.getSize(h);
                height = kind == 1 ? width : width * 16 / 9;
                if (kind == 2 && avail > 0) height = avail;
                else if (avail > 0) height = Math.min(height, avail);
            }
            super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
        }
    }

    /** Horizon line: a short bar that turns yellow when the phone is perfectly level (uses the accelerometer). */
    private static final class LevelView extends View implements SensorEventListener {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final SensorManager sm;
        private float roll;
        private boolean show, on = true, running;

        LevelView(android.content.Context c) {
            super(c);
            sm = (SensorManager) c.getSystemService(android.content.Context.SENSOR_SERVICE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(Ui.dpf(c, 2f));
            setWillNotDraw(false);
        }

        void setEnabledLine(boolean e) {
            on = e;
            invalidate();
        }

        void start() {
            if (running || sm == null) return;
            Sensor a = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            if (a != null) {
                sm.registerListener(this, a, SensorManager.SENSOR_DELAY_UI);
                running = true;
            }
        }

        void stop() {
            if (sm != null) sm.unregisterListener(this);
            running = false;
        }

        @Override
        public void onSensorChanged(SensorEvent e) {
            float ax = e.values[0], ay = e.values[1], az = e.values[2];
            boolean upright = Math.abs(az) < 6f && ay > 4f;
            float r = (float) Math.toDegrees(Math.atan2(ax, ay));
            roll = roll * 0.75f + r * 0.25f;
            show = upright && Math.abs(roll) < 25f;
            if (on) invalidate();
        }

        @Override
        public void onAccuracyChanged(Sensor s, int a) {
        }

        @Override
        protected void onDraw(Canvas c) {
            if (!on || !show) return;
            float w = getWidth(), h = getHeight(), cx = w / 2f, cy = h / 2f;
            float len = Ui.dpf(getContext(), 34f), gap = Ui.dpf(getContext(), 14f);
            boolean level = Math.abs(roll) < 1.2f;
            p.setColor(0xB3FFFFFF);
            c.drawLine(cx - gap - len * 1.6f, cy, cx - gap - len * 0.6f, cy, p);
            c.drawLine(cx + gap + len * 0.6f, cy, cx + gap + len * 1.6f, cy, p);
            p.setColor(level ? Ios.YELLOW : 0xE6FFFFFF);
            c.save();
            c.rotate(-roll, cx, cy);
            c.drawLine(cx - len, cy, cx + len, cy, p);
            c.restore();
        }
    }

    /** Rule-of-thirds lines. */
    private static final class GridView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        GridView(android.content.Context c) {
            super(c);
            p.setColor(0x55FFFFFF);
            p.setStrokeWidth(Math.max(1f, c.getResources().getDisplayMetrics().density * 0.75f));
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            c.drawLine(w / 3f, 0, w / 3f, h, p);
            c.drawLine(2 * w / 3f, 0, 2 * w / 3f, h, p);
            c.drawLine(0, h / 3f, w, h / 3f, p);
            c.drawLine(0, 2 * h / 3f, w, 2 * h / 3f, p);
        }
    }

    /** The round shutter: white ring and disc, blue ring in night mode, a smaller disc while a shot is being taken. */
    private static final class ShutterView extends View {
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint disc = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean night, busy, video, rec;
        private float scale = 1f;

        ShutterView(android.content.Context c) {
            super(c);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(Ui.dpf(c, 4f));
            disc.setStyle(Paint.Style.FILL);
            Ui.press(c, this);
        }

        void setNight(boolean n) {
            night = n;
            invalidate();
        }

        void setVideo(boolean v) {
            video = v;
            invalidate();
        }

        void setRecording(boolean r) {
            rec = r;
            invalidate();
        }

        void setBusy(boolean b) {
            busy = b;
            animate().cancel();
            ValueAnimatorHelper.scale(this, b ? 0.82f : 1f);
        }

        void setInner(float s) {
            scale = s;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            float r = Math.min(cx, cy);
            int accent = Ui.color(getContext(), R.color.accent);
            ring.setColor(night ? accent : Color.WHITE);
            c.drawCircle(cx, cy, r - ring.getStrokeWidth() / 2f, ring);
            if (rec && !video) {   // deep capture running: a square means "tap to finish"
                disc.setColor(night ? accent : Color.WHITE);
                float hs = (r - Ui.dpf(getContext(), 9f)) * 0.5f;
                c.drawRoundRect(cx - hs, cy - hs, cx + hs, cy + hs, hs * 0.3f, hs * 0.3f, disc);
                return;
            }
            if (video) {
                disc.setColor(0xFFFF3B30);
                if (rec) {
                    float h = (r - Ui.dpf(getContext(), 9f)) * 0.55f;
                    c.drawRoundRect(cx - h, cy - h, cx + h, cy + h, h * 0.3f, h * 0.3f, disc);
                } else {
                    c.drawCircle(cx, cy, (r - Ui.dpf(getContext(), 9f)) * scale, disc);
                }
                return;
            }
            disc.setColor(busy ? (night ? accent : 0xFFD1D1D6) : Color.WHITE);
            c.drawCircle(cx, cy, (r - Ui.dpf(getContext(), 9f)) * scale, disc);
        }
    }

    /** Tiny animation helper for the shutter's inner disc. */
    private static final class ValueAnimatorHelper {
        static void scale(final ShutterView v, float to) {
            android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(v.scale, to);
            a.setDuration(140);
            a.addUpdateListener(x -> v.setInner((Float) x.getAnimatedValue()));
            a.start();
        }
    }
}
