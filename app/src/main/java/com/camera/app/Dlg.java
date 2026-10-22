package com.camera.app;

import android.content.Context;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Dialog builder used everywhere in the app. It behaves like AlertDialog.Builder but styles the
 * dialog to match the app (rounded surface, pill buttons, bold title) once it is shown.
 */
public class Dlg extends AlertDialog.Builder {
    public Dlg(Context context) {
        super(context);
    }

    @Override
    public AlertDialog create() {
        final AlertDialog d = super.create();
        dlg = d;
        try {
            d.getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        } catch (Throwable ignored) {
        }
        d.setOnShowListener(dialog -> {
            style(d);
            Bars.hideStatus(d.getWindow());
        });
        return d;
    }

    private AlertDialog dlg;

    /**
     * Option lists are drawn as an iOS-style list inside the dialog (own view, so a message above it works):
     * 17sp rows, hairline separators, a blue check on the current choice. A label starting with "● " or "✓" marks the
     * current choice, "○ " a normal one; the marker itself is not shown.
     */
    @Override
    public AlertDialog.Builder setItems(CharSequence[] items, final DialogInterface.OnClickListener listener) {
        final Context c = getContext();
        ScrollView sv = new ScrollView(c);
        sv.setVerticalScrollBarEnabled(false);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout list = new LinearLayout(c);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, Ui.dp(c, 4), 0, Ui.dp(c, 10));
        sv.addView(list, new ViewGroup.LayoutParams(-1, -2));
        for (int i = 0; i < items.length; i++) {
            final int which = i;
            String label = String.valueOf(items[i]);
            boolean on = false;
            if (label.startsWith("● ") || label.startsWith("✓")) {
                on = true;
                label = label.substring(label.startsWith("✓") ? 1 : 2);
            } else if (label.startsWith("○ ")) {
                label = label.substring(2);
            }
            label = label.trim();
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(Ui.dp(c, 54));
            row.setPaddingRelative(Ui.dp(c, 22), Ui.dp(c, 8), Ui.dp(c, 22), Ui.dp(c, 8));
            TextView t = new TextView(c);
            t.setText(label);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
            t.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
            t.setTextColor(Ui.color(c, destructive(c, label) ? R.color.bad : R.color.text_primary));
            row.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
            if (on) {
                ImageView ck = new ImageView(c);
                ck.setImageResource(R.drawable.ic_check);
                ck.setImageTintList(ColorStateList.valueOf(Ui.color(c, R.color.accent)));
                row.addView(ck, new LinearLayout.LayoutParams(Ui.dp(c, 22), Ui.dp(c, 22)));
            }
            if (i < items.length - 1) {
                GradientDrawable line = new GradientDrawable();
                line.setColor(Ui.color(c, R.color.stroke_soft));
                android.graphics.drawable.LayerDrawable ld = new android.graphics.drawable.LayerDrawable(
                        new android.graphics.drawable.Drawable[]{line});
                ld.setLayerGravity(0, Gravity.BOTTOM | Gravity.FILL_HORIZONTAL);
                ld.setLayerHeight(0, Math.max(1, Math.round(c.getResources().getDisplayMetrics().density * 0.5f)));
                ld.setLayerInsetStart(0, Ui.dp(c, 22));
                row.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.color(c, R.color.ripple)), ld, null));
            } else {
                row.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.color(c, R.color.ripple)), null,
                        new ColorDrawable(0xFFFFFFFF)));
            }
            row.setOnClickListener(v -> {
                if (dlg != null) dlg.dismiss();
                if (listener != null) listener.onClick(dlg, which);
            });
            list.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
        return setView(sv);
    }

    private static final int[] DESTRUCTIVE = {R.string.delete};

    /** True for labels of actions that remove or abort something (shown in red). */
    static boolean destructive(Context c, CharSequence label) {
        if (label == null) return false;
        Set<String> set = new HashSet<>();
        for (int id : DESTRUCTIVE) set.add(c.getString(id));
        return set.contains(label.toString());
    }

    /** A centered result dialog with a big status icon (success or error). */
    public static AlertDialog result(Context c, boolean ok, CharSequence title, CharSequence message) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(Ui.dp(c, 24), Ui.dp(c, 28), Ui.dp(c, 24), Ui.dp(c, 4));

        int col = Ui.color(c, ok ? R.color.ok : R.color.bad);
        ImageView icon = new ImageView(c);
        icon.setImageResource(ok ? R.drawable.ic_check_circle : R.drawable.ic_cancel);
        icon.setImageTintList(ColorStateList.valueOf(col));
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int p = Ui.dp(c, 18);
        icon.setPadding(p, p, p, p);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor((col & 0x00FFFFFF) | 0x26000000);
        icon.setBackground(circle);
        box.addView(icon, new LinearLayout.LayoutParams(Ui.dp(c, 76), Ui.dp(c, 76)));

        TextView t = new TextView(c);
        t.setText(title);
        t.setTextColor(Ui.color(c, R.color.text_primary));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tl.topMargin = Ui.dp(c, 16);
        box.addView(t, tl);

        TextView m = new TextView(c);
        m.setText(message);
        m.setTextColor(Ui.color(c, R.color.text_secondary));
        m.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        m.setLineSpacing(0, 1.2f);
        m.setGravity(Gravity.CENTER);
        m.setTextIsSelectable(true);
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ml.topMargin = Ui.dp(c, 8);
        ml.bottomMargin = Ui.dp(c, 8);
        box.addView(m, ml);

        return new Dlg(c).setView(box).setPositiveButton(android.R.string.ok, null).show();
    }

    private static void style(AlertDialog d) {
        Context c = d.getContext();
        if (d.getWindow() != null) {
            d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        TextView title = d.findViewById(androidx.appcompat.R.id.alertTitle);
        if (title != null) {
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            title.setTextColor(Ui.color(c, R.color.text_primary));
            title.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
            title.setGravity(Gravity.CENTER_HORIZONTAL);
        }
        TextView msg = d.findViewById(android.R.id.message);
        if (msg != null) {
            msg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            msg.setTextColor(Ui.color(c, R.color.text_secondary));
            msg.setLineSpacing(0, 1.2f);
            msg.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
            msg.setGravity(Gravity.CENTER_HORIZONTAL);
        }
        Button pos = d.getButton(AlertDialog.BUTTON_POSITIVE);
        Button neg = d.getButton(AlertDialog.BUTTON_NEGATIVE);
        Button neu = d.getButton(AlertDialog.BUTTON_NEUTRAL);
        List<Button> order = new ArrayList<>();
        if (shown(pos)) order.add(pos);
        if (shown(neg)) order.add(neg);
        if (shown(neu)) order.add(neu);
        if (order.isEmpty()) return;

        // iOS alert: text buttons under a hairline; two buttons sit side by side, three or more are stacked.
        ViewParent parent = order.get(0).getParent();
        boolean side = order.size() == 2;
        if (parent instanceof LinearLayout) {
            LinearLayout bar = (LinearLayout) parent;
            bar.setOrientation(side ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
            View spacer = bar.findViewById(androidx.appcompat.R.id.spacer);
            if (spacer != null) spacer.setVisibility(View.GONE);
            List<Button> ordered = new ArrayList<>(order);
            if (side) {
                ordered.clear();
                ordered.add(neg != null && shown(neg) ? neg : order.get(1));
                ordered.add(pos != null && shown(pos) ? pos : order.get(0));
            }
            for (Button b : ordered) bar.removeView(b);
            for (Button b : ordered) bar.addView(b);
            bar.setPadding(0, Ui.dp(c, 8), 0, 0);
            for (int i = 0; i < ordered.size(); i++) {
                Button b = ordered.get(i);
                iosButton(c, b, b == pos, side);
            }
        }
    }

    private static void iosButton(Context c, Button b, boolean main, boolean side) {
        b.setAllCaps(false);
        b.setTypeface(Typeface.create("sans-serif-medium", main ? Typeface.BOLD : Typeface.NORMAL));
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        b.setMinHeight(Ui.dp(c, 48));
        b.setMinWidth(0);
        b.setStateListAnimator(null);
        boolean bad = destructive(c, b.getText());
        b.setTextColor(Ui.color(c, bad ? R.color.bad : R.color.accent));
        GradientDrawable line = new GradientDrawable();
        line.setColor(Ui.color(c, R.color.stroke));
        android.graphics.drawable.LayerDrawable ld = new android.graphics.drawable.LayerDrawable(
                new android.graphics.drawable.Drawable[]{line, new ColorDrawable(0x00000000)});
        int h = Ios.hairPx(c);
        ld.setLayerGravity(0, Gravity.TOP | Gravity.FILL_HORIZONTAL);
        ld.setLayerHeight(0, h);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.color(c, R.color.ripple)), ld, new ColorDrawable(0xFFFFFFFF)));
        ViewGroup.LayoutParams lp = b.getLayoutParams();
        if (lp instanceof LinearLayout.LayoutParams) {
            LinearLayout.LayoutParams m = (LinearLayout.LayoutParams) lp;
            m.width = side ? 0 : ViewGroup.LayoutParams.MATCH_PARENT;
            m.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            m.weight = side ? 1f : 0f;
            m.setMargins(0, 0, 0, 0);
            b.setLayoutParams(m);
        }
    }

    private static boolean shown(Button b) {
        return b != null && b.getVisibility() != View.GONE;
    }
}
