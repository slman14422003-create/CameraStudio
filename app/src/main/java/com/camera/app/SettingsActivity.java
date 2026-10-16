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
                getString(R.string.set_jpeg), Store.num(this, "jpeg_q", 95) + "%", false, true).tint(ORANGE),
                v -> pickJpeg()));
        Ui.Toggle hi = Ui.toggle(this, content, R.string.set_hires, R.string.set_hires_sub,
                Store.bool(this, "hires", false), R.drawable.ic_aspect, PINK);
        hi.onChange((b, on) -> Store.setBool(this, "hires", on));
        content.addView(hi.view);
        content.addView(Ui.footer(this, getString(R.string.set_quality_note)));

        content.addView(Ui.sectionTitle(this, getString(R.string.set_view)));
        Ui.Toggle gr = Ui.toggle(this, content, R.string.cam_grid, 0, Store.bool(this, "grid", true),
                R.drawable.ic_grid, TEAL);
        gr.onChange((b, on) -> Store.setBool(this, "grid", on));
        content.addView(gr.view);

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

    private void pickJpeg() {
        final int[] v = {90, 95, 100};
        int cur = Store.num(this, "jpeg_q", 95);
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
