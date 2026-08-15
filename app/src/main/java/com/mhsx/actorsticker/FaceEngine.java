package com.mhsx.actorsticker;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;

import java.util.List;
import java.util.concurrent.TimeUnit;

final class FaceEngine implements AutoCloseable {
    private final FaceDetector detector;

    FaceEngine() {
        FaceDetectorOptions options = new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                .enableTracking()
                .setMinFaceSize(0.06f)
                .build();
        detector = FaceDetection.getClient(options);
    }

    List<Face> detect(Bitmap bitmap) throws Exception {
        return Tasks.await(detector.process(InputImage.fromBitmap(bitmap, 0)), 20, TimeUnit.SECONDS);
    }

    float[] descriptor(Bitmap src, Rect box) {
        Rect r = padded(box, src.getWidth(), src.getHeight(), 0.12f);
        if (r.width() < 24 || r.height() < 24) return null;

        Bitmap crop = null;
        Bitmap small = null;
        try {
            crop = Bitmap.createBitmap(src, r.left, r.top, r.width(), r.height());
            small = Bitmap.createScaledBitmap(crop, 24, 24, true);
            float[] gray = new float[24 * 24];
            double mean = 0.0;
            int k = 0;
            for (int y = 0; y < 24; y++) {
                for (int x = 0; x < 24; x++) {
                    int c = small.getPixel(x, y);
                    float g = (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f;
                    gray[k++] = g;
                    mean += g;
                }
            }
            mean /= gray.length;
            double var = 0.0;
            for (float g : gray) {
                double d = g - mean;
                var += d * d;
            }
            float std = (float) Math.sqrt(var / gray.length + 1e-6);

            // Appearance + local edge structure. This stays fully offline and requires no network/model download.
            float[] out = new float[24 * 24 + 23 * 24 + 24 * 23];
            k = 0;
            for (float g : gray) out[k++] = (g - (float) mean) / std;
            for (int y = 0; y < 24; y++) {
                for (int x = 0; x < 23; x++) {
                    out[k++] = (gray[y * 24 + x + 1] - gray[y * 24 + x]) / std;
                }
            }
            for (int y = 0; y < 23; y++) {
                for (int x = 0; x < 24; x++) {
                    out[k++] = (gray[(y + 1) * 24 + x] - gray[y * 24 + x]) / std;
                }
            }
            normalize(out);
            return out;
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (small != null && small != crop && !small.isRecycled()) small.recycle();
            if (crop != null && !crop.isRecycled()) crop.recycle();
        }
    }

    float quality(Bitmap frame, Rect box) {
        float frameArea = Math.max(1f, frame.getWidth() * (float) frame.getHeight());
        float area = Math.max(1f, box.width() * (float) box.height());
        float sizeScore = Math.min(1f, (float) Math.sqrt(area / frameArea) / 0.28f);
        Rect r = padded(box, frame.getWidth(), frame.getHeight(), 0f);
        if (r.width() < 8 || r.height() < 8) return 0f;
        Bitmap crop = null;
        Bitmap small = null;
        try {
            crop = Bitmap.createBitmap(frame, r.left, r.top, r.width(), r.height());
            small = Bitmap.createScaledBitmap(crop, 32, 32, true);
            double edge = 0;
            int n = 0;
            for (int y = 1; y < 31; y += 2) {
                for (int x = 1; x < 31; x += 2) {
                    float a = lum(small.getPixel(x - 1, y));
                    float b = lum(small.getPixel(x + 1, y));
                    float c = lum(small.getPixel(x, y - 1));
                    float d = lum(small.getPixel(x, y + 1));
                    edge += Math.abs(b - a) + Math.abs(d - c);
                    n++;
                }
            }
            float sharp = (float) Math.min(1.0, (edge / Math.max(1, n)) / 80.0);
            return clamp01(0.55f * sizeScore + 0.45f * sharp);
        } catch (Throwable ignored) {
            return sizeScore * 0.6f;
        } finally {
            if (small != null && small != crop && !small.isRecycled()) small.recycle();
            if (crop != null && !crop.isRecycled()) crop.recycle();
        }
    }

    Bitmap thumbnail(Bitmap src, Rect box, int side) {
        Rect r = padded(box, src.getWidth(), src.getHeight(), 0.24f);
        if (r.width() < 8 || r.height() < 8) return null;
        Bitmap crop = null;
        try {
            crop = Bitmap.createBitmap(src, r.left, r.top, r.width(), r.height());
            int s = Math.min(crop.getWidth(), crop.getHeight());
            int x = Math.max(0, (crop.getWidth() - s) / 2);
            int y = Math.max(0, (crop.getHeight() - s) / 2);
            Bitmap square = Bitmap.createBitmap(crop, x, y, s, s);
            Bitmap thumb = Bitmap.createScaledBitmap(square, side, side, true);
            if (square != crop && square != thumb && !square.isRecycled()) square.recycle();
            return thumb;
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (crop != null && !crop.isRecycled()) crop.recycle();
        }
    }

    static float cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return -1f;
        double s = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            s += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na <= 0 || nb <= 0) return -1f;
        return (float) (s / Math.sqrt(na * nb));
    }

    static void normalize(float[] a) {
        double n = 0;
        for (float v : a) n += v * v;
        n = Math.sqrt(n) + 1e-9;
        for (int i = 0; i < a.length; i++) a[i] /= (float) n;
    }

    static Rect padded(Rect b, int w, int h, float pad) {
        int px = Math.round(b.width() * pad);
        int py = Math.round(b.height() * pad);
        int l = Math.max(0, b.left - px);
        int t = Math.max(0, b.top - py);
        int r = Math.min(w, b.right + px);
        int bot = Math.min(h, b.bottom + py);
        return new Rect(l, t, Math.max(l + 1, r), Math.max(t + 1, bot));
    }

    private static float lum(int c) {
        return 0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c);
    }

    private static float clamp01(float x) {
        return Math.max(0f, Math.min(1f, x));
    }

    @Override public void close() {
        detector.close();
    }
}
