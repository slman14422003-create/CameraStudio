package com.camera.app;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Photo quality, processing, appearance, language and the other Semo apps. */
public class SettingsActivity extends BaseActivity {
    private LinearLayout content;

    private static final int BLUE = 0xFF007AFF, INDIGO = 0xFF5856D6, PURPLE = 0xFFAF52DE, GREEN = 0xFF34C759,
            ORANGE = 0xFFFF9500, GRAY = 0xFF8E8E93, TEAL = 0xFF30B0C7, PINK = 0xFFFF2D55;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detail);
        useGroupedBackground();
        ((TextView) findViewById(R.id.title)).setText(R.string.settings);
        ((TextView) findViewById(R.id.subtitle)).setText(R.string.settings_sub);
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        View refresh = findViewById(R.id.btnRefresh);
        if (refresh != null) refresh.setVisibility(View.GONE);
        content = findViewById(R.id.content);
        Ui.autoGroup(this, content);
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private String levelName() {
        return getString(Levels.name(Store.str(this, "level", Levels.MEDIUM)));
    }

    private void render() {
        content.removeAllViews();
        content.addView(Ui.hero(this, getString(R.string.app_name), getString(R.string.set_version, BuildConfig.VERSION_NAME)));

        content.addView(Ui.sectionTitle(this, getString(R.string.set_quality)));
        content.addView(Ui.rowView(this, content, new Row(R.drawable.ic_wand, false,
                getString(R.string.set_level), levelName(), false, true).tint(BLUE), v -> pickLevel()));
        content.addView(Ui.rowView(this, content, new Row(R.drawable.ic_moon, false,
                getString(R.string.set_night_frames),
                getString(R.string.set_frames_n, Store.num(this, "night_frames", 6)), false, true).tint(INDIGO),
                v -> pickFrames()));
        content.addView(Ui.rowView(this, content, new Row(R.drawable.ic_image, false,
                getString(R.string.set_jpeg), Store.num(this, "jpeg_q", 100) + "%", false, true).tint(ORANGE),
                v -> pickJpeg()));
        Ui.Toggle hi = Ui.toggle(this, content, R.string.set_multi, R.string.set_multi_sub,
                Store.bool(this, "multi_photo", true), R.drawable.ic_aspect, PINK);
        hi.onChange((b, on) -> Store.setBool(this, "multi_photo", on));
        content.addView(hi.view);
        content.addView(Ui.footer(this, getString(R.string.set_quality_note)));

        content.addView(Ui.sectionTitle(this, getString(R.string.set_proc)));
        content.addView(Ui.rowView(this, content, new Row(R.drawable.ic_adjust, false,
                getString(R.string.set_style), getString(styleName(Store.num(this, "pstyle", 0))), false, true).tint(PINK),
                v -> pickStyle()));
        addTrimRow("trim_den", R.string.set_trim_den, ORANGE);
        addTrimRow("trim_sharp", R.string.set_trim_sharp, TEAL);
        addTrimRow("trim_tone", R.string.set_trim_tone, BLUE);
        addTrimRow("trim_color", R.string.set_trim_color, PINK);
        content.addView(Ui.rowView(this, content, new Row(R.drawable.ic_adjust, false,
                getString(R.string.set_dehaze), String.valueOf(Store.num(this, "dehaze", 0)), false, true).tint(ORANGE),
                v -> pickDehaze()));
        Ui.Toggle ispT = Ui.toggle(this, content, R.string.set_isp, R.string.set_isp_sub,
                Store.bool(this, "isp_hq", true), R.drawable.ic_camera, GREEN);
        ispT.onChange((b4, on) -> Store.setBool(this, "isp_hq", on));
        content.addView(ispT.view);

        content.addView(Ui.sectionTitle(this, getString(R.string.set_deep)));
        Ui.Toggle deepT = Ui.toggle(this, content, R.string.set_deep, R.string.set_deep_sub,
                Store.bool(this, "deep_on", false), R.drawable.ic_camera, TEAL);
        deepT.onChange((b3, on) -> Store.setBool(this, "deep_on", on));
        content.addView(deepT.view);
        content.addView(Ui.rowView(this, content, new Row(R.drawable.ic_speed, false,
                getString(R.string.set_deep_time), getString(R.string.deep_secs, Store.num(this, "deep_secs", 15)), false, true).tint(ORANGE),
                v -> pickDeepTime()));
        content.addView(Ui.rowView(this, content, new Row(R.drawable.ic_aspect, false,
                getString(R.string.set_deep_res), getString(Store.num(this, "deep_res", 0) == 1 ? R.string.deep_res_50 : R.string.deep_res_12), false, true).tint(PINK),
                v -> pickDeepRes()));

        content.addView(Ui.sectionTitle(this, getString(R.string.set_video)));
        content.addView(Ui.rowView(this, content, new Row(R.drawable.ic_adjust, false,
                getString(R.string.set_vfx), getString(vfxName(Store.num(this, "vfx", 2))), false, true).tint(PINK),
                v -> pickVfx()));
        Ui.Toggle vs = Ui.toggle(this, content, R.string.set_vstab, R.string.set_vstab_sub,
                Store.bool(this, "vstab", true), R.drawable.ic_focus, GREEN);
        vs.onChange((bt, on) -> Store.setBool(this, "vstab", on));
        content.addView(vs.view);
        Ui.Toggle va = Ui.toggle(this, content, R.string.set_vaudio, R.string.set_vaudio_sub,
                Store.bool(this, "vaudio", true), R.drawable.ic_speed, ORANGE);
        va.onChange((bt, on) -> Store.setBool(this, "vaudio", on));
        content.addView(va.view);
        content.addView(Ui.rowView(this, content, new Row(R.drawable.ic_camera, false,
                getString(R.string.set_cam_info), getString(R.string.set_cam_info_sub), false, true).tint(BLUE),
                v -> CamInfo.show(this)));

        content.addView(Ui.sectionTitle(this, getString(R.string.set_view)));
        Ui.Toggle gr = Ui.toggle(this, content, R.string.cam_grid, 0, Store.bool(this, "grid", true),
                R.drawable.ic_grid, TEAL);
        gr.onChange((b, on) -> Store.setBool(this, "grid", on));
        content.addView(gr.view);
        Ui.Toggle ll = Ui.toggle(this, content, R.string.set_level_line, R.string.set_level_line_sub,
                Store.bool(this, "level_line", true), R.drawable.ic_adjust, ORANGE);
        ll.onChange((b2, on) -> Store.setBool(this, "level_line", on));
        content.addView(ll.view);

        content.addView(Ui.sectionTitle(this, getString(R.string.theme_title)));
        content.addView(Ui.rowView(this, content, new Row(R.drawable.ic_palette, true,
                getString(R.string.theme_title), getString(Appearance.modeName(Store.themeMode(this))), false, true)
                .tint(INDIGO), v -> pickMode()));
        content.addView(Ui.footer(this, getString(R.string.theme_mode_note)));

        content.addView(Ui.sectionTitle(this, getString(R.string.set_general)));
        content.addView(Ui.rowView(this, content, new Row(R.drawable.ic_language, false,
                getString(R.string.set_language), languageName(), false, true).tint(BLUE), v -> pickLanguage()));
        content.addView(Ui.rowView(this, content, new Row(R.drawable.ic_shield, false,
                getString(R.string.set_permissions), null, false, true).tint(GREEN), v -> openAppSettings()));

        content.addView(Ui.sectionTitle(this, getString(R.string.eco_title)));
        LinearLayout eco = new LinearLayout(this);
        eco.setOrientation(LinearLayout.VERTICAL);
        for (final Semo.Sibling sib : Semo.siblings(this)) {
            eco.addView(Ui.rowView(this, content, new Row(R.drawable.ic_package, true,
                    sib.label.toString(), sib.version + " (" + sib.code + ")", false, true),
                    v -> Semo.open(this, sib.pkg)));
        }
        if (eco.getChildCount() == 0) {
            eco.addView(Ui.rowView(this, content, new Row(R.drawable.ic_info, false,
                    getString(R.string.eco_none), null, false, false), null));
        }
        Ui.group(this, eco);
        content.addView(eco);

        content.addView(Ui.sectionTitle(this, getString(R.string.set_about)));
        content.addView(Ui.rowView(this, content, new Row(R.drawable.ic_info, false,
                getString(R.string.app_name), getString(R.string.set_version, BuildConfig.VERSION_NAME),
                false, false).tint(GRAY), null));
        content.addView(Ui.footer(this, getString(R.string.about_text)));
    }

    private String languageName() {
        String v = Store.language(this);
        if ("ar".equals(v)) return getString(R.string.lang_ar);
        if ("en".equals(v)) return getString(R.string.lang_en);
        return getString(R.string.lang_system);
    }

    private void openAppSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
        } catch (Exception ignored) {
        }
    }

    private void pickLevel() {
        String cur = Store.str(this, "level", Levels.MEDIUM);
        String[] names = new String[Levels.ALL.length];
        for (int i = 0; i < names.length; i++) {
            names[i] = (Levels.ALL[i].equals(cur) ? "● " : "○ ") + getString(Levels.name(Levels.ALL[i]));
        }
        new Dlg(this).setTitle(R.string.set_level).setMessage(R.string.set_level_note).setItems(names, (d, which) -> {
            Store.setStr(this, "level", Levels.ALL[which]);
            render();
        }).show();
    }

    private void pickFrames() {
        final int[] v = {5, 6, 8, 10};
        int cur = Store.num(this, "night_frames", 6);
        String[] names = new String[v.length];
        for (int i = 0; i < v.length; i++) {
            names[i] = (v[i] == cur ? "● " : "○ ") + getString(R.string.set_frames_n, v[i]);
        }
        new Dlg(this).setTitle(R.string.set_night_frames).setItems(names, (d, which) -> {
            Store.setNum(this, "night_frames", v[which]);
            render();
        }).show();
    }

    private int styleName(int v) {
        switch (v) {
            case 1: return R.string.style_vivid;
            case 2: return R.string.style_cinematic;
            case 3: return R.string.style_soft;
            case 4: return R.string.style_mono;
            case 5: return R.string.style_warm;
            case 6: return R.string.style_cool;
            default: return R.string.style_natural;
        }
    }

    private void pickStyle() {
        int cur = Store.num(this, "pstyle", 0);
        String[] names = new String[7];
        for (int i = 0; i < 7; i++) names[i] = (i == cur ? "● " : "○ ") + getString(styleName(i));
        new Dlg(this).setTitle(R.string.set_style).setItems(names, (d, which) -> {
            Store.setNum(this, "pstyle", which);
            render();
        }).show();
    }

    private void addTrimRow(final String key, final int title, int tint) {
        int v = Store.num(this, key, 0);
        content.addView(Ui.rowView(this, content, new Row(R.drawable.ic_adjust, false,
                getString(title), String.format(java.util.Locale.US, "%+d", v), false, true).tint(tint),
                x -> pickTrim(key, title)));
    }

    private void pickTrim(final String key, int title) {
        int cur = Store.num(this, key, 0);
        String[] names = new String[5];
        for (int i = 0; i < 5; i++) names[i] = (i - 2 == cur ? "● " : "○ ") + String.format(java.util.Locale.US, "%+d", i - 2);
        new Dlg(this).setTitle(title).setItems(names, (d, which) -> {
            Store.setNum(this, key, which - 2);
            render();
        }).show();
    }

    private void pickDehaze() {
        int cur = Store.num(this, "dehaze", 0);
        String[] names = new String[4];
        for (int i = 0; i < 4; i++) names[i] = (i == cur ? "● " : "○ ") + i;
        new Dlg(this).setTitle(R.string.set_dehaze).setItems(names, (d, which) -> {
            Store.setNum(this, "dehaze", which);
            render();
        }).show();
    }

    private void pickDeepTime() {
        final int[] v = {10, 15, 30};
        int cur = Store.num(this, "deep_secs", 15);
        String[] names = new String[v.length];
        for (int i = 0; i < v.length; i++) names[i] = (v[i] == cur ? "● " : "○ ") + getString(R.string.deep_secs, v[i]);
        new Dlg(this).setTitle(R.string.set_deep_time).setItems(names, (d, which) -> {
            Store.setNum(this, "deep_secs", v[which]);
            render();
        }).show();
    }

    private void pickDeepRes() {
        int cur = Store.num(this, "deep_res", 0);
        String[] names = {(cur == 0 ? "● " : "○ ") + getString(R.string.deep_res_12),
                (cur == 1 ? "● " : "○ ") + getString(R.string.deep_res_50)};
        new Dlg(this).setTitle(R.string.set_deep_res).setItems(names, (d, which) -> {
            Store.setNum(this, "deep_res", which);
            render();
        }).show();
    }

    private int vfxName(int v) {
        return v == 0 ? R.string.vfx_off : v == 1 ? R.string.vfx_light : v == 3 ? R.string.vfx_strong : R.string.vfx_normal;
    }

    private void pickVfx() {
        final int[] v = {0, 1, 2, 3};
        int cur = Store.num(this, "vfx", 2);
        String[] names = new String[v.length];
        for (int i = 0; i < v.length; i++) names[i] = (v[i] == cur ? "● " : "○ ") + getString(vfxName(v[i]));
        new Dlg(this).setTitle(R.string.set_vfx).setItems(names, (d, which) -> {
            Store.setNum(this, "vfx", v[which]);
            render();
        }).show();
    }

    private void pickJpeg() {
        final int[] v = {90, 95, 100};
        int cur = Store.num(this, "jpeg_q", 100);
        String[] names = new String[v.length];
        for (int i = 0; i < v.length; i++) names[i] = (v[i] == cur ? "● " : "○ ") + v[i] + "%";
        new Dlg(this).setTitle(R.string.set_jpeg).setItems(names, (d, which) -> {
            Store.setNum(this, "jpeg_q", v[which]);
            render();
        }).show();
    }

    private void pickLanguage() {
        final String[] codes = {"system", "ar", "en"};
        String cur = Store.language(this);
        String[] names = {getString(R.string.lang_system), getString(R.string.lang_ar), getString(R.string.lang_en)};
        for (int i = 0; i < names.length; i++) names[i] = (codes[i].equals(cur) ? "● " : "○ ") + names[i];
        new Dlg(this).setTitle(R.string.set_language).setItems(names, (d, which) -> {
            Store.setLanguage(this, codes[which]);
            Lang.apply(this);
        }).show();
    }

    private void pickMode() {
        final String[] codes = {Appearance.AUTO, Appearance.LIGHT, Appearance.DARK};
        String cur = Store.themeMode(this);
        String[] names = {getString(R.string.theme_auto), getString(R.string.theme_light), getString(R.string.theme_dark)};
        for (int i = 0; i < names.length; i++) names[i] = (codes[i].equals(cur) ? "● " : "○ ") + names[i];
        new Dlg(this).setTitle(R.string.theme_title).setItems(names, (d, which) -> {
            Store.setThemeMode(this, codes[which]);
            Appearance.applyMode(this);
            render();
        }).show();
    }
}
