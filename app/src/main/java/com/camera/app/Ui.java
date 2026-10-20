package com.camera.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;

import java.util.List;

public final class Ui {
    private Ui() {
    }

    /** Gives a view a soft "press in, spring back" scale animation. */
    public static void press(Context c, View v) {
        if (v == null || v.getStateListAnimator() != null) return;   // inflate once per (recycled) view
        v.setStateListAnimator(android.animation.AnimatorInflater.loadStateListAnimator(c, R.animator.press_scale));
    }

    /** Fades and slides a freshly shown item up into place; index staggers neighbouring items. */
    public static void enter(View v, int index) {
        if (v == null) return;
        v.animate().cancel();
        v.setAlpha(0f);
        v.setTranslationY(dp(v.getContext(), 14));
        v.animate().alpha(1f).translationY(0f)
                .setStartDelay(Math.min(Math.max(index, 0), 9) * 28L)
                .setDuration(300)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f))
                .start();
    }

    public static int dp(Context c, int v) {
        return (int) (v * c.getResources().getDisplayMetrics().density);
    }

    /** Same as {@link #dp(Context, int)} but keeps fractions (hairlines, 3.5dp strokes). */
    public static float dpf(Context c, float v) {
        return v * c.getResources().getDisplayMetrics().density;
    }

    /**
     * Colour by resource id. The accent family follows the user's colour theme (resolved through the
     * context's theme); every other colour comes from the day / night resources.
     */
    public static int color(Context c, int res) {
        int attr = 0;
        if (res == R.color.accent) attr = R.attr.cAccent;
        else if (res == R.color.accent_text) attr = R.attr.cAccentText;
        else if (res == R.color.accent_soft) attr = R.attr.cAccentSoft;
        else if (res == R.color.brand_start) attr = R.attr.cBrandStart;
        else if (res == R.color.brand_mid) attr = R.attr.cBrandMid;
        else if (res == R.color.brand_end) attr = R.attr.cBrandEnd;
        else if (res == R.color.bg) attr = R.attr.cBg;
        else if (res == R.color.bg_group) attr = R.attr.cGroupBg;
        else if (res == R.color.surface) attr = R.attr.cSurface;
        else if (res == R.color.surface_high) attr = R.attr.cSurfaceHigh;
        else if (res == R.color.field) attr = R.attr.cField;
        else if (res == R.color.stroke) attr = R.attr.cStroke;
        else if (res == R.color.stroke_soft) attr = R.attr.cStrokeSoft;
        else if (res == R.color.text_primary) attr = R.attr.cTextPrimary;
        else if (res == R.color.text_secondary) attr = R.attr.cTextSecondary;
        else if (res == R.color.text_hint) attr = R.attr.cTextHint;
        else if (res == R.color.neutral_soft) attr = R.attr.cNeutralSoft;
        if (attr != 0) {
            android.util.TypedValue tv = new android.util.TypedValue();
            if (c.getTheme().resolveAttribute(attr, tv, true)
                    && tv.type >= android.util.TypedValue.TYPE_FIRST_COLOR_INT
                    && tv.type <= android.util.TypedValue.TYPE_LAST_COLOR_INT) {
                return tv.data;
            }
        }
        return ContextCompat.getColor(c, res);
    }

    public static EditText edit(Context c, CharSequence hint, CharSequence text) {
        EditText e = new EditText(c);
        e.setHint(hint);
        if (text != null) e.setText(text);
        e.setBackgroundResource(R.drawable.bg_input);
        e.setTextColor(color(c, R.color.text_primary));
        e.setHintTextColor(color(c, R.color.text_hint));
        e.setTextSize(15);
        e.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        e.setMinHeight(dp(c, 52));
        int p = dp(c, 14);
        e.setPaddingRelative(dp(c, 18), p, dp(c, 18), p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(c, 10);
        e.setLayoutParams(lp);
        return e;
    }

    public static CheckBox check(Context c, int textRes, boolean checked) {
        CheckBox cb = new CheckBox(c);
        cb.setText(textRes);
        cb.setChecked(checked);
        cb.setTextColor(color(c, R.color.text_primary));
        cb.setTextSize(15);
        cb.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        cb.setButtonTintList(ContextCompat.getColorStateList(c, R.color.check_tint));
        cb.setMinHeight(dp(c, 48));
        cb.setPaddingRelative(dp(c, 10), 0, 0, 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(c, 2);
        cb.setLayoutParams(lp);
        return cb;
    }

    public static LinearLayout box(Context c) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(c, 22), dp(c, 12), dp(c, 22), 0);
        return box;
    }

    public static void tint(Context c, ProgressBar bar) {
        ColorStateList accent = ColorStateList.valueOf(color(c, R.color.accent));
        bar.setProgressTintList(accent);
        bar.setIndeterminateTintList(accent);
    }

    public static TextView label(Context c, CharSequence text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextColor(color(c, R.color.text_secondary));
        t.setTextSize(13);
        t.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        t.setPaddingRelative(dp(c, 6), dp(c, 6), dp(c, 6), dp(c, 4));
        return t;
    }

    public static Spinner spinner(Context c, List<String> items, int selected) {
        Spinner sp = new Spinner(c);
        ArrayAdapter<String> a = new ArrayAdapter<>(c, R.layout.spinner_item, items);
        a.setDropDownViewResource(R.layout.spinner_dropdown_item);
        sp.setAdapter(a);
        if (selected >= 0 && selected < items.size()) sp.setSelection(selected);
        sp.setBackgroundResource(R.drawable.bg_spinner);
        sp.setPaddingRelative(dp(c, 16), dp(c, 6), dp(c, 44), dp(c, 6));
        sp.setPopupBackgroundResource(R.drawable.bg_popup);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 52));
        lp.bottomMargin = dp(c, 10);
        sp.setLayoutParams(lp);
        return sp;
    }

    /** A selectable filter chip: grey capsule, iOS blue when selected. */
    public static TextView chip(Context c, CharSequence text, boolean selected) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(13);
        t.setSingleLine(true);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        int ph = dp(c, 14);
        int pv = dp(c, 6);
        t.setPadding(ph, pv, ph, pv);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(c, 8));
        t.setLayoutParams(lp);
        setChip(c, t, selected);
        press(c, t);
        return t;
    }

    public static void setChip(Context c, TextView t, boolean selected) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(c, 100));
        g.setColor(selected ? 0xFFFFFFFF : 0x4D3A3A3C);
        t.setBackground(g);
        t.setTextColor(selected ? 0xFF000000 : 0xFFFFFFFF);
    }

    public static TextView sectionTitle(Context c, CharSequence text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextColor(color(c, R.color.text_secondary));
        t.setTextSize(13);
        t.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        t.setPaddingRelative(dp(c, 32), dp(c, 24), dp(c, 32), dp(c, 7));
        return t;
    }

    /** Body text block with horizontal page padding. */
    public static TextView body(Context c, CharSequence text, int sizeSp, int colorRes) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextColor(color(c, colorRes));
        t.setTextSize(sizeSp);
        t.setLineSpacing(0, 1.15f);
        t.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        t.setPaddingRelative(dp(c, 32), dp(c, 6), dp(c, 32), dp(c, 6));
        t.setTextIsSelectable(true);
        return t;
    }

    public static Button button(Context c, int textRes, boolean primary) {
        Button b = new Button(c);
        b.setText(textRes);
        b.setAllCaps(false);
        b.setBackgroundResource(primary ? R.drawable.btn_primary : R.drawable.btn_secondary);
        b.setTextColor(color(c, primary ? R.color.on_accent : R.color.text_primary));
        b.setTextSize(17);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setMinHeight(dp(c, 50));
        press(c, b);
        return b;
    }

    /** A horizontal progress bar made of two weighted views (0..100). */
    public static View bar(Context c, double percent, int colorRes) {
        return bar(c, percent, colorRes, 26, 8);
    }

    /** Same bar with custom side margin and bottom margin (dp). */
    public static View bar(Context c, double percent, int colorRes, int sideDp, int bottomDp) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 6));
        lp.setMargins(dp(c, sideDp), dp(c, 2), dp(c, sideDp), dp(c, bottomDp));
        l.setLayoutParams(lp);
        float pct = (float) Math.max(1, Math.min(100, percent));
        View fill = new View(c);
        GradientDrawable g1 = new GradientDrawable();
        g1.setCornerRadius(dp(c, 6));
        if (colorRes == R.color.accent) {   // the brand gradient (same colours as the app icon)
            g1.setOrientation(GradientDrawable.Orientation.LEFT_RIGHT);
            g1.setColors(new int[]{color(c, R.color.brand_start), color(c, R.color.brand_mid), color(c, R.color.brand_end)});
        } else {
            g1.setColor(color(c, colorRes));
        }
        fill.setBackground(g1);
        fill.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, pct));
        View rest = new View(c);
        GradientDrawable g2 = new GradientDrawable();
        g2.setCornerRadius(dp(c, 6));
        g2.setColor(color(c, R.color.neutral_soft));
        rest.setBackground(g2);
        rest.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 100f - pct));
        l.addView(fill);
        l.addView(rest);
        return l;
    }

    /** Shapes a row card so rows of one section read as a single rounded group (ChatGPT style). */
    public static void shapeRow(Context c, View v, boolean first, boolean last) {
        shapeRow(c, v, first, last, R.color.surface);
    }

    /** Same as above with a custom fill (used to highlight selected rows). */
    public static void shapeRow(Context c, View v, boolean first, boolean last, int fillRes) {
        View card = v.findViewById(R.id.card);
        if (card == null) return;
        // recycled rows usually keep the same shape: skip rebuilding three drawables on every bind
        final Integer shapeKey = fillRes * 4 + (first ? 1 : 0) + (last ? 2 : 0);
        if (shapeKey.equals(card.getTag(R.id.tag_shape))) return;
        card.setTag(R.id.tag_shape, shapeKey);
        float big = dp(c, 12);
        float top = first ? big : 0;
        float bottom = last ? big : 0;
        float[] radii = {top, top, top, top, bottom, bottom, bottom, bottom};
        GradientDrawable fill = new GradientDrawable();
        fill.setColor(color(c, fillRes));
        fill.setCornerRadii(radii);
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(0xFFFFFFFF);
        mask.setCornerRadii(radii);
        android.graphics.drawable.Drawable base = fill;
        if (!last) {
            // hairline separator, inset past the icon like iOS lists
            GradientDrawable line = new GradientDrawable();
            line.setColor(color(c, R.color.stroke));
            android.graphics.drawable.LayerDrawable ld = new android.graphics.drawable.LayerDrawable(
                    new android.graphics.drawable.Drawable[]{fill, line});
            ld.setLayerGravity(1, Gravity.BOTTOM | Gravity.FILL_HORIZONTAL);
            ld.setLayerHeight(1, Math.max(1, Math.round(c.getResources().getDisplayMetrics().density * 0.5f)));
            View ic = card.findViewById(R.id.icon);
            ld.setLayerInsetStart(1, dp(c, ic != null && ic.getVisibility() == View.VISIBLE ? 58 : 16));
            base = ld;
        }
        card.setBackground(new RippleDrawable(
                ColorStateList.valueOf(color(c, R.color.ripple)), base, mask));
    }

    /** Groups every run of consecutive row cards inside a container. */
    public static void group(Context c, ViewGroup g) {
        int n = g.getChildCount();
        for (int i = 0; i < n; i++) {
            View v = g.getChildAt(i);
            if (v.findViewById(R.id.card) == null) continue;
            boolean first = i == 0 || g.getChildAt(i - 1).findViewById(R.id.card) == null;
            boolean last = i == n - 1 || g.getChildAt(i + 1).findViewById(R.id.card) == null;
            shapeRow(c, v, first, last);
        }
    }

    /** Keeps row groups shaped automatically while a screen adds or removes rows. */
    public static void autoGroup(final Context c, final ViewGroup g) {
        final Runnable r = () -> group(c, g);
        g.setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener() {
            @Override
            public void onChildViewAdded(View parent, View child) {
                g.removeCallbacks(r);
                g.post(r);
            }

            @Override
            public void onChildViewRemoved(View parent, View child) {
                g.removeCallbacks(r);
                g.post(r);
            }
        });
    }

    /** Inflates a row and binds it. */
    public static View rowView(Context c, ViewGroup parent, Row row, View.OnClickListener click) {
        View v = LayoutInflater.from(c).inflate(R.layout.item_row, parent, false);
        RowAdapter.bind(c, v, row);
        if (click != null) v.setOnClickListener(click);
        return v;
    }

    // ------------------------------------------------------------------ settings building blocks

    /** Gives a view full-width layout params with the page margins used by every row card. */
    public static <T extends View> T block(Context c, T v) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(c, 14), dp(c, 10), dp(c, 14), dp(c, 6));
        v.setLayoutParams(lp);
        return v;
    }

    /** A rounded note / status card with wrapped text (release notes, hints, errors). */
    public static TextView noteCard(Context c, CharSequence text, int colorRes) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextColor(color(c, colorRes));
        t.setTextSize(14);
        t.setLineSpacing(0, 1.2f);
        t.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        t.setBackgroundResource(R.drawable.bg_note);
        t.setPaddingRelative(dp(c, 18), dp(c, 14), dp(c, 18), dp(c, 14));
        t.setTextIsSelectable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(c, 14), dp(c, 4), dp(c, 14), dp(c, 6));
        t.setLayoutParams(lp);
        return t;
    }

    /** A settings row with a title, optional description and an on/off switch. */
    public static final class Toggle {
        public final View view;
        private final SwitchCompat sw;

        Toggle(View view, SwitchCompat sw) {
            this.view = view;
            this.sw = sw;
        }

        public boolean isChecked() {
            return sw.isChecked();
        }

        public void setChecked(boolean on) {
            sw.setChecked(on);
        }

        public void onChange(CompoundButton.OnCheckedChangeListener l) {
            sw.setOnCheckedChangeListener(l);
        }
    }

    /** Big centred app card at the top of Settings (icon, name, version), like the profile header in iOS Settings. */
    public static View hero(Context c, CharSequence name, CharSequence sub) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(c, 16), dp(c, 12), dp(c, 16), dp(c, 6));
        ImageView icon = new ImageView(c);
        icon.setImageResource(R.drawable.ic_brand_mark);   // the blue flower, same as the launcher icon
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFFFFFFFF, 0xFFEAF3FF});
        g.setCornerRadius(dp(c, 20));
        g.setStroke(1, 0x14000000);
        icon.setBackground(g);
        icon.setClipToOutline(true);
        box.addView(icon, new LinearLayout.LayoutParams(dp(c, 84), dp(c, 84)));
        TextView n = new TextView(c);
        n.setText(name);
        n.setTextColor(color(c, R.color.text_primary));
        n.setTextSize(22);
        n.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        n.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-2, -2);
        np.topMargin = dp(c, 12);
        box.addView(n, np);
        TextView v = new TextView(c);
        v.setText(sub);
        v.setTextColor(color(c, R.color.text_secondary));
        v.setTextSize(13);
        v.setGravity(Gravity.CENTER);
        box.addView(v, new LinearLayout.LayoutParams(-2, -2));
        return box;
    }

    /** Small grey explanation under a group of rows (iOS list footer). */
    public static TextView footer(Context c, CharSequence text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextColor(color(c, R.color.text_secondary));
        t.setTextSize(13);
        t.setLineSpacing(0, 1.15f);
        t.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        t.setPaddingRelative(dp(c, 32), dp(c, 8), dp(c, 32), dp(c, 4));
        return t;
    }

    /** Switch row with a coloured icon square. */
    public static Toggle toggle(Context c, ViewGroup parent, int titleRes, int subRes, boolean checked, int iconRes, int iconColor) {
        Toggle t = toggle(c, parent, titleRes, subRes, checked);
        ImageView icon = t.view.findViewById(R.id.icon);
        if (icon != null) {
            icon.setImageResource(iconRes);
            icon.setImageTintList(ColorStateList.valueOf(0xFFFFFFFF));
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(c, 8));
            g.setColor(0xFF000000 | (iconColor & 0x00FFFFFF));
            icon.setBackground(g);
            icon.setVisibility(View.VISIBLE);
        }
        return t;
    }

    /** Inflates a switch row. Add {@code result.view} to a container; consecutive rows group into one card. */
    public static Toggle toggle(Context c, ViewGroup parent, int titleRes, int subRes, boolean checked) {
        View v = LayoutInflater.from(c).inflate(R.layout.item_toggle, parent, false);
        ((TextView) v.findViewById(R.id.title)).setText(titleRes);
        TextView sub = v.findViewById(R.id.sub);
        if (subRes != 0) {
            sub.setText(subRes);
            sub.setVisibility(View.VISIBLE);
        }
        final SwitchCompat sw = v.findViewById(R.id.sw);
        // set in code: SwitchCompat's thumb/track attributes are not reliably exposed to XML
        sw.setThumbDrawable(ContextCompat.getDrawable(c, R.drawable.switch_thumb));
        sw.setTrackDrawable(ContextCompat.getDrawable(c, R.drawable.switch_track));
        sw.setShowText(false);
        sw.setChecked(checked);
        v.setOnClickListener(x -> sw.toggle());
        return new Toggle(v, sw);
    }
}
