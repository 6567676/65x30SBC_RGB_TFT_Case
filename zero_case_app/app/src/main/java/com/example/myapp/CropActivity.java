package com.example.myapp;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import android.os.Handler;
import android.os.Looper;

public class CropActivity extends AppCompatActivity {

    private ImageView ivCrop;
    private View cropBox;
    private Bitmap srcBitmap;
    private Matrix matrix = new Matrix();
    private ScaleGestureDetector scaleDetector;

    private float lastX, lastY;
    private static final int OUTPUT_W = 320;
    private static final int OUTPUT_H = 172;

    private boolean isVideo = false;
    private Uri mediaUri = null;
    private MediaMetadataRetriever retriever;
    private int videoDurationMs = 0;

    private RangeSeekBar rangeSeekBar;
    private TextView tvTimeline;

    private long selectedStartUs = 0;
    private long selectedEndUs = 0;
    private volatile long pendingFrameUs = -1;
    private volatile boolean decoderAlive = true;
    private Thread frameThread;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_crop);

        ivCrop = findViewById(R.id.ivCrop);
        cropBox = findViewById(R.id.crop_box);
        rangeSeekBar = findViewById(R.id.rangeSeekBar);
        tvTimeline = findViewById(R.id.tvTimeline);
        ivCrop.setScaleType(ImageView.ScaleType.MATRIX);
        scaleDetector = new ScaleGestureDetector(this, new ScaleListener());

        mediaUri = getIntent().getParcelableExtra("IMAGE_URI");
        isVideo = getIntent().getBooleanExtra("IS_VIDEO", false);

        try {
            if (isVideo) {
                retriever = new MediaMetadataRetriever();
                retriever.setDataSource(this, mediaUri);

                String durationStr = retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_DURATION);
                videoDurationMs = Integer.parseInt(durationStr);

                View timelineArea = findViewById(R.id.timelineArea);
                timelineArea.setVisibility(View.VISIBLE);

                rangeSeekBar.setDurationMs(videoDurationMs);
                selectedStartUs = 0;
                selectedEndUs = (long) videoDurationMs * 1000;

                rangeSeekBar.setOnRangeChangeListener(new RangeSeekBar.OnRangeChangeListener() {
                    @Override
                    public void onRangeChanged(float startFrac, float endFrac) {
                        selectedStartUs = (long)(startFrac * videoDurationMs * 1000);
                        selectedEndUs = (long)(endFrac * videoDurationMs * 1000);
                        showFrameAt(selectedStartUs);
                        updateLabels(startFrac, endFrac);
                    }

                    @Override
                    public void onDraggingEnd(boolean isDragging, long endUs) {
                        if (isDragging) {
                            showFrameAt(endUs);
                        } else {
                            showFrameAt(selectedStartUs);
                        }
                    }
                });

                updateLabels(0f, 1f);
                srcBitmap = retriever.getFrameAtTime(0,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            } else {
                srcBitmap = MediaStore.Images.Media.getBitmap(
                        getContentResolver(), mediaUri);
            }

            if (srcBitmap != null) {
                ivCrop.setImageBitmap(srcBitmap);
            } else {
                finish();
            }
        } catch (Exception e) {
            finish();
        }

        findViewById(R.id.btnConfirm).setOnClickListener(v -> {
            CropParams params = getCropParams();
            Intent intent = new Intent();
            intent.putExtra("IS_VIDEO", isVideo);

            if (isVideo) {
                intent.putExtra("VIDEO_URI", mediaUri);
                intent.putExtra("CROP_LEFT", params.srcLeft);
                intent.putExtra("CROP_TOP", params.srcTop);
                intent.putExtra("CROP_RIGHT", params.srcRight);
                intent.putExtra("CROP_BOTTOM", params.srcBottom);
                intent.putExtra("START_TIME_US", selectedStartUs);
                intent.putExtra("END_TIME_US", selectedEndUs);
            } else {
                Bitmap result = cropExactly(params);
                intent.putExtra("BITMAP", result);
            }
            setResult(RESULT_OK, intent);
            finish();
        });
    }

    private void updateLabels(float startFrac, float endFrac) {
        float startMs = startFrac * videoDurationMs;
        float endMs = endFrac * videoDurationMs;
        float durMs = endMs - startMs;
        int frames = (int)(durMs / 1000f * 22);

        String label = String.format("起始 %.1f秒  结束 %.1f秒  时长 %.1f秒  约%d帧",
                startMs / 1000f, endMs / 1000f, durMs / 1000f, frames);
        tvTimeline.setText(label);
    }

    private void showFrameAt(long timeUs) {
        pendingFrameUs = timeUs;
        if (frameThread == null || !frameThread.isAlive()) {
            frameThread = new Thread(() -> {
                long lastDecoded = -2;
                while (decoderAlive && !isFinishing()) {
                    long target = pendingFrameUs;
                    if (target == lastDecoded) {
                        try { Thread.sleep(16); } catch (Exception ignored) {}
                        continue;
                    }
                    try {
                        Bitmap frame = retriever.getFrameAtTime(target,
                                MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                        if (frame != null) {
                            runOnUiThread(() -> {
                                Bitmap old = srcBitmap;
                                srcBitmap = frame;
                                ivCrop.setImageBitmap(srcBitmap);
                                if (old != null) old.recycle();
                            });
                            lastDecoded = target;
                        }
                    } catch (Exception ignored) {}
                }
            }, "FrameDecoder");
            frameThread.start();
        }
    }

    private static class CropParams {
        int srcLeft, srcTop, srcRight, srcBottom;
    }

    private CropParams getCropParams() {
        int[] boxPos = new int[2];
        cropBox.getLocationInWindow(boxPos);
        int boxLeft = boxPos[0];
        int boxTop = boxPos[1];
        int boxRight = boxLeft + cropBox.getWidth();
        int boxBottom = boxTop + cropBox.getHeight();

        float[] values = new float[9];
        matrix.getValues(values);
        float transX = values[Matrix.MTRANS_X];
        float transY = values[Matrix.MTRANS_Y];
        float scale = values[Matrix.MSCALE_X];

        CropParams p = new CropParams();
        p.srcLeft = Math.max((int) ((boxLeft - transX) / scale), 0);
        p.srcTop = Math.max((int) ((boxTop - transY) / scale), 0);
        p.srcRight = Math.min((int) ((boxRight - transX) / scale), srcBitmap.getWidth());
        p.srcBottom = Math.min((int) ((boxBottom - transY) / scale), srcBitmap.getHeight());
        return p;
    }

    private Bitmap cropExactly(CropParams p) {
        int srcW = p.srcRight - p.srcLeft;
        int srcH = p.srcBottom - p.srcTop;
        Bitmap part = Bitmap.createBitmap(srcBitmap, p.srcLeft, p.srcTop, srcW, srcH);
        return Bitmap.createScaledBitmap(part, OUTPUT_W, OUTPUT_H, true);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        float x = event.getX();
        float y = event.getY();

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                lastX = x;
                lastY = y;
                break;
            case MotionEvent.ACTION_MOVE:
                matrix.postTranslate(x - lastX, y - lastY);
                ivCrop.setImageMatrix(matrix);
                lastX = x;
                lastY = y;
                break;
        }
        return true;
    }

    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            matrix.postScale(
                    detector.getScaleFactor(),
                    detector.getScaleFactor(),
                    detector.getFocusX(),
                    detector.getFocusY()
            );
            ivCrop.setImageMatrix(matrix);
            return true;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        decoderAlive = false;
        if (retriever != null) {
            try { retriever.release(); } catch (Exception ignored) {}
        }
    }
}