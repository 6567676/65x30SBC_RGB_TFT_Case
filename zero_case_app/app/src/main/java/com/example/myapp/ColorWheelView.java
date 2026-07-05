package com.example.myapp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.SweepGradient;
import android.graphics.RadialGradient;
import android.graphics.Shader;
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

        int[] hueColors = new int[361];
        for (int i = 0; i <= 360; i++) {
            hueColors[i] = Color.HSVToColor(new float[]{i, 1.0f, 1.0f});
        }
        SweepGradient sweepGradient = new SweepGradient(cx, cy, hueColors, null);
        paint.setShader(sweepGradient);
        canvas.drawCircle(cx, cy, radius, paint);

        Paint whitePaint = new Paint();
        whitePaint.setAntiAlias(true);
        float deadZoneDisplay = 100;
        RadialGradient radialGradient = new RadialGradient(
                cx, cy, radius,
                new int[] { Color.WHITE, Color.WHITE, Color.TRANSPARENT },
                new float[] { 0f, deadZoneDisplay / radius, 1f },
                Shader.TileMode.CLAMP
        );
        whitePaint.setShader(radialGradient);
        canvas.drawCircle(cx, cy, radius, whitePaint);

        paint.setShader(null);
        paint.setColor(currentColor);
        canvas.drawCircle(cx, cy, 100, paint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX() - getWidth()/2f;
        float y = event.getY() - getHeight()/2f;
        float angle = (float) Math.atan2(y, x);
        float hue = (float) Math.toDegrees(angle);
        if (hue < 0) hue += 360;

        float distance = (float)Math.sqrt(x*x + y*y);
        float radius = Math.min(getWidth(), getHeight())/2f - 20;

        float deadZone = 100;
        float t;

        if (distance < deadZone) {
            t = 0.0f;
        } else {
            t = Math.min((distance - deadZone) / (radius - deadZone), 1.0f);
        }

        float saturation = t;
        float value      = 1.0f;

        currentColor = Color.HSVToColor(new float[]{hue, saturation, value});

        if (listener != null) {
            listener.onColorChange(currentColor);
        }

        invalidate();
        return true;
    }

    // ← 新增：恢复颜色用，不触发回调
    public void setColor(int color) {
        this.currentColor = color;
        invalidate();
    }

    public void setOnColorChangeListener(OnColorChangeListener listener) {
        this.listener = listener;
    }

    public interface OnColorChangeListener {
        void onColorChange(int color);
    }
}
