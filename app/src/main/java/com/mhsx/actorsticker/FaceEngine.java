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

/**
 * Offline mobile face engine. ML Kit performs face detection/tracking; identity
 * grouping uses a compact, model-free identity embedding built from normalized
 * appearance, HOG-like gradients, LBP texture and coarse chroma. It is designed
 * to be substantially more pose/lighting tolerant than v0.4's raw-pixel vector
 * while keeping the APK fully offline and architecture-neutral.
 */
final class FaceEngine implements AutoCloseable {
    private final FaceDetector detector;

    FaceEngine() {
        FaceDetectorOptions options = new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                .enableTracking()
                .setMinFaceSize(0.055f)
                .build();
        detector = FaceDetection.getClient(options);
    }

    List<Face> detect(Bitmap bitmap) throws Exception {
        return Tasks.await(detector.process(InputImage.fromBitmap(bitmap, 0)), 20, TimeUnit.SECONDS);
    }

    float[] descriptor(Bitmap src, Rect box) {
        Rect r = padded(box, src.getWidth(), src.getHeight(), 0.15f);
        if (r.width() < 24 || r.height() < 24) return null;
        Bitmap crop = null, small = null, tiny = null;
        try {
            crop = Bitmap.createBitmap(src, r.left, r.top, r.width(), r.height());
            small = Bitmap.createScaledBitmap(crop, 32, 32, true);
            tiny = Bitmap.createScaledBitmap(crop, 12, 12, true);

            float[] gray = new float[32 * 32];
            float[] rr = new float[32 * 32], gg = new float[32 * 32], bb = new float[32 * 32];
            double mean = 0;
            int k = 0;
            for (int y = 0; y < 32; y++) for (int x = 0; x < 32; x++) {
                int c = small.getPixel(x, y);
                float rv = Color.red(c) / 255f, gv = Color.green(c) / 255f, bv = Color.blue(c) / 255f;
                rr[k] = rv; gg[k] = gv; bb[k] = bv;
                float g = 0.299f * rv + 0.587f * gv + 0.114f * bv;
                gray[k++] = g; mean += g;
            }
            mean /= gray.length;
            double var = 0;
            for (float g : gray) { double d = g - mean; var += d * d; }
            float std = (float) Math.sqrt(var / gray.length + 1e-5);

            // 16 cells * (8 gradient + 8 LBP + 3 chroma moments) = 304
            // plus 12x12 normalized appearance = 144 => 448D embedding.
            float[] out = new float[448];
            k = 0;
            for (int cy = 0; cy < 4; cy++) {
                for (int cx = 0; cx < 4; cx++) {
                    float[] hog = new float[8];
                    float[] lbp = new float[8];
                    double sr = 0, sg = 0, sbv = 0; int n = 0;
                    int x0 = cx * 8, y0 = cy * 8;
                    for (int y = y0 + 1; y < Math.min(31, y0 + 7); y++) {
                        for (int x = x0 + 1; x < Math.min(31, x0 + 7); x++) {
                            int i = y * 32 + x;
                            float gx = gray[i + 1] - gray[i - 1];
                            float gy = gray[i + 32] - gray[i - 32];
                            float mag = (float) Math.sqrt(gx * gx + gy * gy) + 1e-4f;
                            double angle = Math.atan2(gy, gx) + Math.PI;
                            int bin = Math.min(7, (int) (angle / (2.0 * Math.PI) * 8.0));
                            hog[bin] += mag;

                            float center = gray[i]; int code = 0;
                            if (gray[i - 33] > center) code |= 1;
                            if (gray[i - 32] > center) code |= 2;
                            if (gray[i - 31] > center) code |= 4;
                            if (gray[i + 1] > center) code |= 8;
                            if (gray[i + 33] > center) code |= 16;
                            if (gray[i + 32] > center) code |= 32;
                            if (gray[i + 31] > center) code |= 64;
                            if (gray[i - 1] > center) code |= 128;
                            lbp[(Integer.bitCount(code) * 7) / 8] += 1f;
                            sr += rr[i]; sg += gg[i]; sbv += bb[i]; n++;
                        }
                    }
                    normalizeBlock(hog); normalizeBlock(lbp);
                    for (float v : hog) out[k++] = v;
                    for (float v : lbp) out[k++] = v;
                    double l = (sr + sg + sbv) / Math.max(1, n * 3.0);
                    out[k++] = (float) ((sr / Math.max(1, n)) - l);
                    out[k++] = (float) ((sg / Math.max(1, n)) - l);
                    out[k++] = (float) ((sbv / Math.max(1, n)) - l);
                }
            }

            for (int y = 0; y < 12; y++) for (int x = 0; x < 12; x++) {
                int c = tiny.getPixel(x, y);
                float g = (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f;
                out[k++] = clamp((g - (float) mean) / Math.max(0.08f, std), -3f, 3f) / 3f;
            }
            normalize(out);
            return out;
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (tiny != null && tiny != crop && tiny != small && !tiny.isRecycled()) tiny.recycle();
            if (small != null && small != crop && !small.isRecycled()) small.recycle();
            if (crop != null && !crop.isRecycled()) crop.recycle();
        }
    }

    float quality(Bitmap frame, Rect box) {
        float frameArea = Math.max(1f, frame.getWidth() * (float) frame.getHeight());
        float area = Math.max(1f, box.width() * (float) box.height());
        float sizeScore = Math.min(1f, (float) Math.sqrt(area / frameArea) / 0.27f);
        Rect r = padded(box, frame.getWidth(), frame.getHeight(), 0f);
        if (r.width() < 8 || r.height() < 8) return 0f;
        Bitmap crop = null, small = null;
        try {
            crop = Bitmap.createBitmap(frame, r.left, r.top, r.width(), r.height());
            small = Bitmap.createScaledBitmap(crop, 32, 32, true);
            double edge = 0, lum = 0, lum2 = 0; int n = 0;
            for (int y = 1; y < 31; y += 2) for (int x = 1; x < 31; x += 2) {
                float a = lum(small.getPixel(x - 1, y)), b = lum(small.getPixel(x + 1, y));
                float c = lum(small.getPixel(x, y - 1)), d = lum(small.getPixel(x, y + 1));
                float m = lum(small.getPixel(x, y));
                edge += Math.abs(b - a) + Math.abs(d - c);
                lum += m; lum2 += m * m; n++;
            }
            float sharp = (float) Math.min(1.0, (edge / Math.max(1, n)) / 76.0);
            float avg = (float) (lum / Math.max(1, n));
            float brightness = 1f - Math.min(1f, Math.abs(avg - 132f) / 132f);
            double variance = lum2 / Math.max(1, n) - avg * avg;
            float contrast = (float) Math.min(1.0, Math.sqrt(Math.max(0, variance)) / 55.0);
            return clamp01(0.42f * sizeScore + 0.36f * sharp + 0.12f * brightness + 0.10f * contrast);
        } catch (Throwable ignored) {
            return sizeScore * 0.55f;
        } finally {
            if (small != null && small != crop && !small.isRecycled()) small.recycle();
            if (crop != null && !crop.isRecycled()) crop.recycle();
        }
    }

    Bitmap thumbnail(Bitmap src, Rect box, int side) {
        Rect r = padded(box, src.getWidth(), src.getHeight(), 0.26f);
        if (r.width() < 8 || r.height() < 8) return null;
        Bitmap crop = null;
        try {
            crop = Bitmap.createBitmap(src, r.left, r.top, r.width(), r.height());
            int s = Math.min(crop.getWidth(), crop.getHeight());
            int x = Math.max(0, (crop.getWidth() - s) / 2), y = Math.max(0, (crop.getHeight() - s) / 2);
            Bitmap square = Bitmap.createBitmap(crop, x, y, s, s);
            Bitmap thumb = Bitmap.createScaledBitmap(square, side, side, true);
            if (square != crop && square != thumb && !square.isRecycled()) square.recycle();
            return thumb;
        } catch (Throwable ignored) { return null; }
        finally { if (crop != null && !crop.isRecycled()) crop.recycle(); }
    }

    static float cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return -1f;
        double s = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) { s += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i]; }
        if (na <= 0 || nb <= 0) return -1f;
        return (float) (s / Math.sqrt(na * nb));
    }

    static void normalize(float[] a) {
        double n = 0; for (float v : a) n += v * v;
        n = Math.sqrt(n) + 1e-9; for (int i = 0; i < a.length; i++) a[i] /= (float) n;
    }
    private static void normalizeBlock(float[] a) {
        double n = 0; for (float v : a) n += v * v;
        n = Math.sqrt(n) + 1e-6; for (int i = 0; i < a.length; i++) a[i] /= (float) n;
    }

    static Rect padded(Rect b, int w, int h, float pad) {
        int px = Math.round(b.width() * pad), py = Math.round(b.height() * pad);
        int l = Math.max(0, b.left - px), t = Math.max(0, b.top - py);
        int r = Math.min(w, b.right + px), bot = Math.min(h, b.bottom + py);
        return new Rect(l, t, Math.max(l + 1, r), Math.max(t + 1, bot));
    }

    private static float lum(int c) { return 0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c); }
    private static float clamp01(float x) { return Math.max(0f, Math.min(1f, x)); }
    private static float clamp(float x, float lo, float hi) { return Math.max(lo, Math.min(hi, x)); }
    @Override public void close() { detector.close(); }
}
