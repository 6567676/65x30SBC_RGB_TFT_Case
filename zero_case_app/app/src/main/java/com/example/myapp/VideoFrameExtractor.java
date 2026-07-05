package com.example.myapp;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class VideoFrameExtractor {

    public static class FrameResult {
        public List<byte[]> jpegFrames;
        public int durationMs;

        public FrameResult(List<byte[]> jpegFrames, int durationMs) {
            this.jpegFrames = jpegFrames;
            this.durationMs = durationMs;
        }
    }

    public static FrameResult extractFrames(Context context, Uri videoUri,
                                            int cropLeft, int cropTop,
                                            int cropRight, int cropBottom,
                                            int targetW, int targetH,
                                            int maxFrames,
                                            long startUs, long endUs) throws Exception {

        MediaExtractor extractor = new MediaExtractor();
        extractor.setDataSource(context, videoUri, null);

        int videoTrack = -1;
        MediaFormat inputFormat = null;
        int durationMs = 0;

        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat fmt = extractor.getTrackFormat(i);
            String mime = fmt.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("video/")) {
                videoTrack = i;
                inputFormat = fmt;
                if (fmt.containsKey(MediaFormat.KEY_DURATION)) {
                    durationMs = (int) (fmt.getLong(MediaFormat.KEY_DURATION) / 1000);
                }
                break;
            }
        }

        if (videoTrack < 0 || inputFormat == null) {
            extractor.release();
            throw new Exception("No video track found");
        }

        extractor.selectTrack(videoTrack);
        if (startUs > 0) {
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
        }

        String mime = inputFormat.getString(MediaFormat.KEY_MIME);
        MediaCodec decoder = MediaCodec.createDecoderByType(mime);
        inputFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
        decoder.configure(inputFormat, null, null, 0);
        decoder.start();

        List<byte[]> jpegFrames = new ArrayList<>();
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long intervalUs = 1000000 / 21;
        long nextSampleUs = startUs;
        boolean inputDone = false;
        boolean eos = false;

        while (!eos && jpegFrames.size() < maxFrames) {
            // 输入
            if (!inputDone) {
                int inIdx = decoder.dequeueInputBuffer(10000);
                if (inIdx >= 0) {
                    ByteBuffer buf = decoder.getInputBuffer(inIdx);
                    int size = extractor.readSampleData(buf, 0);
                    if (size < 0) {
                        decoder.queueInputBuffer(inIdx, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                    } else {
                        decoder.queueInputBuffer(inIdx, 0, size,
                                extractor.getSampleTime(), 0);
                        extractor.advance();
                    }
                }
            }

            // 输出
            int outIdx = decoder.dequeueOutputBuffer(info, 10000);
            if (outIdx >= 0) {
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    decoder.releaseOutputBuffer(outIdx, false);
                    eos = true;
                    continue;
                }

                long pts = info.presentationTimeUs;
                if (endUs > 0 && pts > endUs) {
                    decoder.releaseOutputBuffer(outIdx, false);
                    break;
                }
                if (pts >= nextSampleUs) {
                    Image image = decoder.getOutputImage(outIdx);
                    if (image != null) {
                        byte[] jpeg = convertFrame(image, cropLeft, cropTop,
                                cropRight, cropBottom, targetW, targetH);
                        if (jpeg != null) jpegFrames.add(jpeg);
                        image.close();
                    }
                    nextSampleUs += intervalUs;
                }
                decoder.releaseOutputBuffer(outIdx, false);
            }
        }

        decoder.stop();
        decoder.release();
        extractor.release();

        return new FrameResult(jpegFrames, durationMs);
    }

    private static byte[] convertFrame(Image image, int cl, int ct,
                                       int cr, int cb,
                                       int targetW, int targetH) {
        if (image.getFormat() != ImageFormat.YUV_420_888) return null;

        int w = image.getWidth();
        int h = image.getHeight();
        Image.Plane[] planes = image.getPlanes();

        ByteBuffer yBuf = planes[0].getBuffer();
        ByteBuffer uBuf = planes[1].getBuffer();
        ByteBuffer vBuf = planes[2].getBuffer();

        int yStride = planes[0].getRowStride();
        int uvStride = planes[1].getRowStride();
        int uvPixStride = planes[1].getPixelStride();

        int left = Math.max(cl, 0);
        int top = Math.max(ct, 0);
        int right = Math.min(cr, w);
        int bottom = Math.min(cb, h);
        int cropW = right - left;
        int cropH = bottom - top;
        if (cropW <= 0 || cropH <= 0) return null;

        // 直接裁剪区域 YUV -> ARGB
        int[] argb = new int[cropW * cropH];
        for (int row = 0; row < cropH; row++) {
            for (int col = 0; col < cropW; col++) {
                int yIdx = (top + row) * yStride + (left + col);
                int yVal = yBuf.get(yIdx) & 0xFF;

                int uvRow = (top + row) / 2;
                int uvCol = (left + col) / 2;
                int uvIdx = uvRow * uvStride + uvCol * uvPixStride;
                int uVal = uBuf.get(uvIdx) & 0xFF;
                int vVal = vBuf.get(uvIdx) & 0xFF;

                int r = (int)(yVal + 1.370705f * (vVal - 128));
                int g = (int)(yVal - 0.337633f * (uVal - 128) - 0.698001f * (vVal - 128));
                int b = (int)(yVal + 1.732446f * (uVal - 128));

                r = Math.max(0, Math.min(255, r));
                g = Math.max(0, Math.min(255, g));
                b = Math.max(0, Math.min(255, b));

                argb[row * cropW + col] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }

        Bitmap cropBmp = Bitmap.createBitmap(argb, cropW, cropH, Bitmap.Config.ARGB_8888);
        Bitmap scaled = Bitmap.createScaledBitmap(cropBmp, targetW, targetH, true);
        if (scaled != cropBmp) cropBmp.recycle();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        scaled.compress(Bitmap.CompressFormat.JPEG, 40, out);
        scaled.recycle();

        return out.toByteArray();
    }
}