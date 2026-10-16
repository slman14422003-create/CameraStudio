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
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.OrientationEventListener;
import android.view.ScaleGestureDetector;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.GestureDetector;
import android.widget.FrameLayout;
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
    private TextView status, zoomPill, hdrBtn;
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
    private boolean busy, started, wantHires;
    private String nightText = "";
    private Uri lastUri;
    private OrientationEventListener orient;
    private final ExecutorService io = Executors.newSingleThreadExecutor();

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
        wantHires = Store.bool(this, "hires", false);
        buildUi();

        engine = new CameraEngine(this, previewView, this);
        engine.setHires(wantHires);

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
        previewView.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        holder.addView(previewView, new FrameLayout.LayoutParams(-1, -1));
        grid = new GridView(this);
        holder.addView(grid, new FrameLayout.LayoutParams(-1, -1));

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

        zoomPill = pill();
        zoomPill.setText("1×");
        zoomPill.setOnClickListener(v -> cycleZoom());
        Ui.press(this, zoomPill);
        FrameLayout.LayoutParams zp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        zp.bottomMargin = dp(10);
        holder.addView(zoomPill, zp);

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
        bottom.addView(levelRow, new LinearLayout.LayoutParams(-2, -2));

        LinearLayout modes = new LinearLayout(this);
        modes.setOrientation(LinearLayout.HORIZONTAL);
        modes.setGravity(Gravity.CENTER);
        int[] names = {R.string.cam_mode_photo, R.string.cam_mode_night, R.string.cam_mode_pro};
        for (int i = 0; i < 3; i++) {
            final int m = i;
            TextView ch = Ui.chip(this, getString(names[i]), i == 0);
            ch.setOnClickListener(v -> setMode(m));
            modeChips.add(ch);
            modes.addView(ch);
        }
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-2, -2);
        mp.topMargin = dp(12);
        bottom.addView(modes, mp);

        LinearLayout shutterRow = new LinearLayout(this);
        shutterRow.setOrientation(LinearLayout.HORIZONTAL);
        shutterRow.setGravity(Gravity.CENTER_VERTICAL);
        shutterRow.setPadding(dp(30), 0, dp(30), 0);

        thumb = new ImageView(this);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable tb = new GradientDrawable();
        tb.setShape(GradientDrawable.OVAL);
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
        if (busy) return;
        engine.setMode(m);
        refreshModeUi();
    }

    private void refreshModeUi() {
        int m = engine == null ? CameraEngine.PHOTO : engine.mode();
        for (int i = 0; i < modeChips.size(); i++) Ui.setChip(this, modeChips.get(i), i == m);
        levelRow.setVisibility(m == CameraEngine.NIGHT ? View.INVISIBLE : View.VISIBLE);
        proPanel.setVisibility(m == CameraEngine.PRO ? View.VISIBLE : View.GONE);
        shutter.setNight(m == CameraEngine.NIGHT);
        if (m == CameraEngine.PRO && engine != null) rebuildPro();
        refreshTop();
    }

    private void setLevel(String l) {
        level = l;
        Store.setStr(this, "level", l);
        for (TextView c : levelChips) Ui.setChip(this, c, l.equals(c.getTag()));
    }

    private void refreshTop() {
        boolean photo = engine == null || engine.mode() == CameraEngine.PHOTO;
        boolean canFlash = engine != null && engine.hasFlash && !engine.isFront() && photo;
        flashBtn.setVisibility(canFlash ? View.VISIBLE : View.INVISIBLE);
        int[] icons = {R.drawable.ic_flash_off, R.drawable.ic_flash_auto, R.drawable.ic_flash};
        flashBtn.setImageResource(icons[flashState]);
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
        if (busy) return;
        engine.setFront(!engine.isFront());
        switchBtn.animate().rotationBy(180f).setDuration(280).start();
    }

    private void cycleZoom() {
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
        String t = nightText;
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
        if (busy || !started) return;
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
        return Store.num(this, "jpeg_q", 95);
    }

    private final Processor.Done done = (uri, enhanced) -> {
        updateStatus();
        if (uri != null) {
            lastUri = uri;
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
        Processor.night(this, frames, rot, quality(), done);
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

    private Bitmap loadThumb(Uri uri, int target) {
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

    /** Finds the newest photo in DCIM/Camera so the thumbnail is right after a restart. */
    private void loadLast() {
        io.execute(() -> {
            Uri found = null;
            try {
                String sel;
                String[] args;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    sel = MediaStore.Images.Media.RELATIVE_PATH + " LIKE ?";
                    args = new String[]{"DCIM/Camera%"};
                } else {
                    sel = MediaStore.Images.Media.DATA + " LIKE ?";
                    args = new String[]{"%/DCIM/Camera/%"};
                }
                try (Cursor c = getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        new String[]{MediaStore.Images.Media._ID}, sel, args, MediaStore.Images.Media.DATE_ADDED + " DESC")) {
                    if (c != null && c.moveToFirst()) {
                        found = android.content.ContentUris.withAppendedId(
                                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0));
                    }
                }
            } catch (Throwable ignored) {
            }
            final Uri u = found;
            if (u != null) runOnUiThread(() -> {
                lastUri = u;
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
        if (Semo.view(this, lastUri, "image/jpeg", Semo.ROLE_GALLERY)) return;   // the Gallery app of the same family
        try {
            startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(lastUri, "image/jpeg")
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
        boolean h = Store.bool(this, "hires", false);
        if (h != wantHires) {
            wantHires = h;
            engine.setHires(h);
        }
        level = Store.str(this, "level", level);
        for (TextView c : levelChips) Ui.setChip(this, c, level.equals(c.getTag()));
        refreshTop();
        updateStatus();
        if (started) loadLast();
    }

    @Override
    protected void onPause() {
        if (orient != null) orient.disable();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        io.shutdown();
        super.onDestroy();
    }

    // ================================================================== Listener

    @Override
    public void onBound() {
        boolean m = engine.mode() == CameraEngine.PRO;
        if (m) rebuildPro();
        refreshTop();
        zoomPill.setText(zoomText(engine.zoom()));
    }

    private static String zoomText(float z) {
        return (Math.abs(z - Math.round(z)) < 0.05f ? String.valueOf(Math.round(z))
                : String.format(Locale.US, "%.1f", z)) + "×";
    }

    @Override
    public void onZoom(float ratio) {
        zoomPill.setText(zoomText(ratio));
    }

    @Override
    public void onError(String message) {
        Toast.makeText(this, getString(R.string.cam_open_failed), Toast.LENGTH_LONG).show();
    }

    // ================================================================== small views

    /** Keeps the preview 3:4 (the photo's shape) whatever the screen is. */
    private static final class Ratio34 extends FrameLayout {
        Ratio34(android.content.Context c) {
            super(c);
        }

        @Override
        protected void onMeasure(int w, int h) {
            int width = MeasureSpec.getSize(w);
            super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(width * 4 / 3, MeasureSpec.EXACTLY));
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
        private boolean night, busy;
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
