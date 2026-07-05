package com.example.myapp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.SweepGradient;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

public class ColorWheelView extends View {
    private Paint paint;
    private int currentColor = Color.WHITE;
    private OnColorChangeListener listener;

    public ColorWheelView(Context context, AttributeSet attrs) {
        super(context, attrs);
        paint = new Paint();
        paint.setAntiAlias(true);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        int radius = Math.min(w, h) / 2 - 20;
        int cx = w / 2;
        int cy = h / 2;

        int[] colors = new int[361];
        for (int i = 0; i <= 360; i++) colors[i] = Color.HSVToColor(new float[]{i, 1, 1});
        SweepGradient gradient = new SweepGradient(cx, cy, colors, null);
        paint.setShader(gradient);
        canvas.drawCircle(cx, cy, radius, paint);

        paint.setShader(null);
        paint.setColor(currentColor);
        canvas.drawCircle(cx, cy, 40, paint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX() - getWidth()/2f;
        float y = event.getY() - getHeight()/2f;
        float hue = (float) (Math.atan2(y, x) * 180 / Math.PI);
        if (hue < 0) hue += 360;
        currentColor = Color.HSVToColor(new float[]{hue, 1, 1});
        if (listener != null) listener.onColorChange(currentColor);
        invalidate();
        return true;
    }

    public void setOnColorChangeListener(OnColorChangeListener l) { listener = l; }
    public interface OnColorChangeListener { void onColorChange(int color); }
}
