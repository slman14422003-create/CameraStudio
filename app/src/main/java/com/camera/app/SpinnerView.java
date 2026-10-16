package com.camera.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * iOS-style activity indicator: twelve rounded spokes, the leading one fully opaque and the rest fading behind
 * it, stepping around the circle. Draws in the app accent colour unless told otherwise.
 */
public class SpinnerView extends View {
    private static final int SPOKES = 12;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int color;
    private int step = 0;
    private ValueAnimator anim;

    public SpinnerView(Context c) {
        this(c, null);
    }

    public SpinnerView(Context c, AttributeSet a) {
        super(c, a);
        color = Ui.color(c, R.color.accent);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
    }

    public void setColor(int color) {
        this.color = color;
        invalidate();
    }

    private void start() {
        if (anim != null || getVisibility() != VISIBLE || !isAttachedToWindow()) return;
        anim = ValueAnimator.ofInt(0, SPOKES);
        anim.setDuration(900);
        anim.setRepeatCount(ValueAnimator.INFINITE);
        anim.setInterpolator(new LinearInterpolator());
        anim.addUpdateListener(a -> {
            int v = ((int) a.getAnimatedValue()) % SPOKES;
            if (v != step) {
                step = v;
                invalidate();
            }
        });
        anim.start();
    }

    private void stop() {
        if (anim != null) {
            anim.cancel();
            anim = null;
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        start();
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(View changed, int visibility) {
        super.onVisibilityChanged(changed, visibility);
        if (visibility == VISIBLE) start();
        else stop();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float size = Math.min(getWidth(), getHeight());
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float outer = size * 0.46f;
        float inner = size * 0.25f;
        paint.setStrokeWidth(size * 0.1f);
        int base = color & 0x00FFFFFF;
        for (int i = 0; i < SPOKES; i++) {
            int k = (step - i + SPOKES) % SPOKES;          // 0 = the leading spoke
            int alpha = 255 - Math.round(k * (200f / (SPOKES - 1)));
            paint.setColor(base | (alpha << 24));
            canvas.save();
            canvas.rotate(i * 30f, cx, cy);
            canvas.drawLine(cx, cy - outer, cx, cy - inner, paint);
            canvas.restore();
        }
    }
}
