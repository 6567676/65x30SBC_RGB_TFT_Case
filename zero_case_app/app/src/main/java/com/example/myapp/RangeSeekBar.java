package com.example.myapp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

public class RangeSeekBar extends View {

    public interface OnRangeChangeListener {
        void onRangeChanged(float startFraction, float endFraction);
        void onDraggingEnd(boolean isDragging, long endUs);
    }

    private float startFraction = 0f;
    private float endFraction = 1f;
    private OnRangeChangeListener listener;

    private Paint trackPaint, selectedPaint, thumbPaint, textPaint;
    private float thumbRadius = 24f;
    private int activeThumb = -1;
    private long durationMs = 0;

    public RangeSeekBar(Context context) { super(context); init(); }
    public RangeSeekBar(Context context, AttributeSet attrs) { super(context, attrs); init(); }

    private void init() {
        trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        trackPaint.setColor(0xFF555555);
        trackPaint.setStrokeWidth(6f);

        selectedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        selectedPaint.setColor(0xFFC9BAFF);
        selectedPaint.setStrokeWidth(8f);

        thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        thumbPaint.setColor(0xFFFFFFFF);

        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(0xFFFFFFFF);
        textPaint.setTextSize(28f);
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    public void setDurationMs(long ms) { durationMs = ms; invalidate(); }

    public void setOnRangeChangeListener(OnRangeChangeListener l) { listener = l; }

    public float getStartFraction() { return startFraction; }
    public float getEndFraction() { return endFraction; }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float pad = thumbRadius + 12;
        float y = getHeight() / 2f;
        float left = pad;
        float right = getWidth() - pad;
        float trackW = right - left;
        float trackY = y + 5;
        canvas.drawLine(left, y, right, y, trackPaint);

        float sx = left + startFraction * trackW;
        float ex = left + endFraction * trackW;
        canvas.drawLine(sx, y, ex, y, selectedPaint);

        canvas.drawCircle(sx, y, thumbRadius, thumbPaint);
        canvas.drawCircle(ex, y, thumbRadius, thumbPaint);

        if (durationMs > 0) {
            float startMs = startFraction * durationMs;
            float endMs = endFraction * durationMs;
            float durMs = endMs - startMs;
            int frames = (int)(durMs / 1000f * 22);

            canvas.drawText(formatTime(startMs), sx, y - thumbRadius - 8, textPaint);
            canvas.drawText(formatTime(endMs), ex, y - thumbRadius - 8, textPaint);


        }
    }

    private String formatTime(float ms) {
        int totalSec = (int)(ms / 1000f);
        int tenth = (int)((ms / 100f)) % 10;
        return String.format("%d.%ds", totalSec, tenth);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float pad = thumbRadius + 12;
        float left = pad;
        float right = getWidth() - pad;
        float trackW = right - left;

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                float x = event.getX();
                float sx = left + startFraction * trackW;
                float ex = left + endFraction * trackW;
                if (Math.abs(x - sx) < Math.abs(x - ex)) activeThumb = 0;
                else activeThumb = 1;
                updateThumb(event.getX(), left, trackW);
                break;
            case MotionEvent.ACTION_MOVE:
                if (activeThumb >= 0) updateThumb(event.getX(), left, trackW);
                break;
            case MotionEvent.ACTION_UP:
                if (activeThumb == 1 && listener != null) {
                    listener.onDraggingEnd(false, 0);
                }
                activeThumb = -1;
                break;
        }
        return true;
    }

    private void updateThumb(float x, float left, float trackW) {
        float frac = (x - left) / trackW;
        frac = Math.max(0f, Math.min(1f, frac));

        if (activeThumb == 0) {
            startFraction = Math.min(frac, endFraction - 0.01f);
        } else if (activeThumb == 1) {
            endFraction = Math.max(frac, startFraction + 0.01f);
        }

        if (listener != null) listener.onRangeChanged(startFraction, endFraction);
        if (activeThumb == 1 && listener != null) {
            long endUs = (long)(endFraction * durationMs * 1000);
            listener.onDraggingEnd(true, endUs);
        }
        invalidate();
    }
}