package com.duocam.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Кнопка запису: біле кільце + червоне коло (у запису - червоний заокруглений квадрат). */
final class RecordButton extends View {

    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private boolean recording;

    RecordButton(Context c) {
        super(c);
        ring.setStyle(Paint.Style.STROKE);
        ring.setColor(Color.WHITE);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(Color.rgb(229, 57, 53));
    }

    void setRecording(boolean r) {
        recording = r;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float r = Math.min(cx, cy);
        float stroke = r * 0.09f;
        ring.setStrokeWidth(stroke);
        canvas.drawCircle(cx, cy, r - stroke / 2f, ring);
        if (recording) {
            float half = r * 0.38f;
            rect.set(cx - half, cy - half, cx + half, cy + half);
            canvas.drawRoundRect(rect, r * 0.1f, r * 0.1f, fill);
        } else {
            canvas.drawCircle(cx, cy, r * 0.70f, fill);
        }
    }
}
