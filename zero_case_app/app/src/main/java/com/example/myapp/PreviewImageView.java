package com.example.myapp;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.util.AttributeSet;
import android.view.View;
import java.util.List;

public class PreviewImageView extends View {

    private List<Bitmap> frames;
    private int currentIndex = 0;
    private boolean playing = false;
    private Matrix drawMatrix = new Matrix();

    public PreviewImageView(Context context) {
        super(context);
    }

    public PreviewImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setFrames(List<Bitmap> newFrames) {
        stop();
        this.frames = newFrames;
        this.currentIndex = 0;
        if (frames != null && frames.size() > 0) {
            start();
        }
    }

    public void setStaticBitmap(Bitmap bmp) {
        stop();
        this.frames = null;
        this.currentIndex = 0;
        drawMatrix.reset();
        if (bmp != null) {
            float sx = (float) getWidth() / bmp.getWidth();
            float sy = (float) getHeight() / bmp.getHeight();
            float scale = Math.max(sx, sy);
            drawMatrix.setScale(scale, scale);
            drawMatrix.postTranslate(
                    (getWidth() - bmp.getWidth() * scale) / 2,
                    (getHeight() - bmp.getHeight() * scale) / 2);
        }
        staticBmp = bmp;
        invalidate();
    }

    private Bitmap staticBmp = null;

    public void start() {
        if (playing || frames == null || frames.size() == 0) return;
        playing = true;
        currentIndex = 0;
        post(frameRunnable);
    }

    public void stop() {
        playing = false;
        removeCallbacks(frameRunnable);
    }

    private final Runnable frameRunnable = new Runnable() {
        @Override
        public void run() {
            if (!playing || frames == null) return;
            invalidate();
            currentIndex++;
            if (currentIndex >= frames.size()) {
                currentIndex = 0;
            }
            postDelayed(this, 33);
        }
    };

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (frames != null && frames.size() > 0 && currentIndex < frames.size()) {
            Bitmap frame = frames.get(currentIndex);
            if (frame != null && !frame.isRecycled()) {
                updateMatrix(frame);
                canvas.drawBitmap(frame, drawMatrix, null);
            }
        } else if (staticBmp != null && !staticBmp.isRecycled()) {
            updateMatrix(staticBmp);
            canvas.drawBitmap(staticBmp, drawMatrix, null);
        }
    }

    private void updateMatrix(Bitmap bmp) {
        if (getWidth() == 0 || getHeight() == 0) return;
        float sx = (float) getWidth() / bmp.getWidth();
        float sy = (float) getHeight() / bmp.getHeight();
        float scale = Math.max(sx, sy);
        drawMatrix.setScale(scale, scale);
        drawMatrix.postTranslate(
                (getWidth() - bmp.getWidth() * scale) / 2,
                (getHeight() - bmp.getHeight() * scale) / 2);
    }

    public boolean isPlaying() {
        return playing;
    }
}