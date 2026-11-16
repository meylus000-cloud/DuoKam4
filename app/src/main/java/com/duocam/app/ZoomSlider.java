package com.duocam.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

/**
 * Вертикальний повзунок швидкості зуму з пружиною: після відпускання повертається в центр.
 * Вгору - плавне наближення, вниз - плавне віддалення; чим далі від центру, тим швидше.
 */
final class ZoomSlider extends View {

    interface Listener {
        void onValue(float v);
    }

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumb = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float value;
    private Listener listener;
    private ValueAnimator anim;

    ZoomSlider(Context c) {
        super(c);
        float d = c.getResources().getDisplayMetrics().density;
        track.setColor(Color.argb(140, 255, 255, 255));
        track.setStrokeWidth(4f * d);
        track.setStrokeCap(Paint.Cap.ROUND);
        thumb.setColor(Color.argb(235, 255, 255, 255));
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(3f * d);
        ring.setColor(Color.rgb(255, 214, 10));
        text.setColor(Color.WHITE);
        text.setTextSize(20f * d);
        text.setTextAlign(Paint.Align.CENTER);
        text.setFakeBoldText(true);
    }

    void setListener(Listener l) { listener = l; }

    float getValue() { return value; }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        float cx = w / 2f;
        float pad = w * 0.5f;
        float top = pad;
        float bottom = h - pad;
        float cy = (top + bottom) / 2f;

        canvas.drawLine(cx, top, cx, bottom, track);
        canvas.drawLine(cx - w * 0.18f, cy, cx + w * 0.18f, cy, track);
        canvas.drawText("+", cx, top - w * 0.12f, text);
        canvas.drawText("−", cx, bottom + w * 0.42f, text);

        float ty = cy - value * (bottom - top) / 2f;
        float r = w * 0.34f;
        canvas.drawCircle(cx, ty, r, thumb);
        if (Math.abs(value) > 0.02f) canvas.drawCircle(cx, ty, r, ring);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (anim != null) anim.cancel();
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                updateFromY(e.getY());
                return true;
            case MotionEvent.ACTION_MOVE:
                updateFromY(e.getY());
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                springBack();
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }

    private void updateFromY(float y) {
        float pad = getWidth() * 0.5f;
        float top = pad;
        float bottom = getHeight() - pad;
        float cy = (top + bottom) / 2f;
        float half = (bottom - top) / 2f;
        setValue(Math.max(-1f, Math.min(1f, (cy - y) / half)));
    }

    private void setValue(float v) {
        value = v;
        invalidate();
        if (listener != null) listener.onValue(v);
    }

    private void springBack() {
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofFloat(value, 0f);
        anim.setDuration(180);
        anim.addUpdateListener(a -> setValue((float) a.getAnimatedValue()));
        anim.start();
    }
}
