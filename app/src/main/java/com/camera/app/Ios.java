package com.camera.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small builders for the iOS Photos look shared by both editors: nav bar text buttons, hairlines, round tool buttons. */
final class Ios {
    static final int YELLOW = 0xFFFFD60A;

    private Ios() {
    }

    static int hairPx(Context c) {
        return Math.max(1, Math.round(c.getResources().getDisplayMetrics().density * 0.5f));
    }

    static View hairline(Context c) {
        View v = new View(c);
        v.setBackgroundColor(Ui.color(c, R.color.stroke));
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, hairPx(c)));
        return v;
    }

    /** Blue text button of an iOS navigation bar. */
    static TextView navText(Context c, CharSequence text, boolean semibold) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(17);
        t.setTextColor(Ui.color(c, R.color.accent));
        t.setTypeface(semibold ? Typeface.create("sans-serif-medium", Typeface.BOLD) : Typeface.DEFAULT);
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(Ui.dp(c, 44));
        t.setSingleLine(true);
        t.setPadding(Ui.dp(c, 12), 0, Ui.dp(c, 12), 0);
        Ui.press(c, t);
        return t;
    }

    /** Plain icon button of a navigation bar (no fill, round ripple). */
    static ImageButton navIcon(Context c, int icon, int desc, View.OnClickListener click) {
        ImageButton b = new ImageButton(c);
        b.setImageResource(icon);
        b.setImageTintList(ColorStateList.valueOf(Ui.color(c, R.color.text_primary)));
        b.setScaleType(ImageView.ScaleType.CENTER);
        b.setBackgroundResource(R.drawable.bg_tile_round);
        b.setContentDescription(c.getString(desc));
        b.setOnClickListener(click);
        Ui.press(c, b);
        return b;
    }

    /** A round icon with its name underneath, like the tool row of the iOS Photos editor. */
    static LinearLayout toolItem(Context c, int icon, CharSequence label) {
        LinearLayout item = new LinearLayout(c);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER_HORIZONTAL);
        item.setPadding(0, Ui.dp(c, 6), 0, Ui.dp(c, 4));
        ImageView iv = new ImageView(c);
        iv.setImageResource(icon);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int p = Ui.dp(c, 10);
        iv.setPadding(p, p, p, p);
        item.addView(iv, new LinearLayout.LayoutParams(Ui.dp(c, 44), Ui.dp(c, 44)));
        TextView t = new TextView(c);
        t.setText(label);
        t.setTextSize(10.5f);
        t.setSingleLine(true);
        t.setGravity(Gravity.CENTER);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = Ui.dp(c, 3);
        item.addView(t, lp);
        item.setContentDescription(label);
        Ui.press(c, item);
        setToolSelected(c, item, false);
        return item;
    }

    static void setToolSelected(Context c, LinearLayout item, boolean on) {
        ImageView iv = (ImageView) item.getChildAt(0);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(Ui.color(c, on ? R.color.accent : R.color.neutral_soft));
        iv.setBackground(g);
        iv.setImageTintList(ColorStateList.valueOf(Ui.color(c, on ? R.color.on_accent : R.color.text_primary)));
        ((TextView) item.getChildAt(1)).setTextColor(Ui.color(c, on ? R.color.accent : R.color.text_secondary));
    }

    /** Small round icon button used inside tool panels (rotate, flip). */
    static ImageButton panelIcon(Context c, int icon, int desc, View.OnClickListener click) {
        ImageButton b = new ImageButton(c);
        b.setImageResource(icon);
        b.setImageTintList(ColorStateList.valueOf(Ui.color(c, R.color.text_primary)));
        b.setScaleType(ImageView.ScaleType.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(Ui.color(c, R.color.neutral_soft));
        b.setBackground(g);
        b.setContentDescription(c.getString(desc));
        b.setOnClickListener(click);
        Ui.press(c, b);
        return b;
    }

    /** Rounded square that holds a filter thumbnail; the selected one gets a ring. */
    static void thumbFrame(Context c, View v, boolean selected) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(Ui.dp(c, 10));
        g.setColor(Ui.color(c, R.color.neutral_soft));
        if (selected) g.setStroke(Ui.dp(c, 2), Ui.color(c, R.color.accent));
        v.setBackground(g);
        v.setClipToOutline(true);
    }
}
