package com.mhsx.actorsticker;

import android.graphics.Matrix;

import androidx.media3.effect.MatrixTransformation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Timestamp-aware pan transformation used before the static aspect-ratio crop.
 *
 * ActorScanStore hit coordinates are normalized in display coordinates. The effect
 * shifts the decoded frame under a centered crop window. Translation is clamped to
 * the available crop margin, so the output never reveals black outside the source.
 */
final class TrackingPanEffect implements MatrixTransformation {
    private final ArrayList<ActorScanStore.Hit> hits;
    private final long segmentStartMs;
    private final float inputAspect;
    private final float targetAspect;
    private final boolean tracking;
    private final boolean safeZone;

    TrackingPanEffect(List<ActorScanStore.Hit> sourceHits,
                      long segmentStartMs,
                      float inputAspect,
                      float targetAspect,
                      boolean tracking,
                      boolean safeZone) {
        this.hits = new ArrayList<>(sourceHits == null ? java.util.Collections.emptyList() : sourceHits);
        this.hits.sort(Comparator.comparingLong(h -> h.t));
        this.segmentStartMs = segmentStartMs;
        this.inputAspect = Math.max(0.05f, inputAspect);
        this.targetAspect = Math.max(0.05f, targetAspect);
        this.tracking = tracking;
        this.safeZone = safeZone;
    }

    @Override
    public Matrix getMatrix(long presentationTimeUs) {
        Matrix matrix = new Matrix();
        if (hits.isEmpty()) return matrix;

        long presentedMs = Math.max(0L, presentationTimeUs / 1000L);
        long relativeCandidate = segmentStartMs + presentedMs;
        long absoluteCandidate = presentedMs;
        long globalMs = nearestHitDistance(relativeCandidate) <= nearestHitDistance(absoluteCandidate)
                ? relativeCandidate : absoluteCandidate;

        float[] center = tracking ? smoothCenter(globalMs) : wholeSegmentCenter();
        float cx = center[0];
        float cy = center[1];

        if (inputAspect > targetAspect + 0.001f) {
            float visibleWidthFraction = targetAspect / inputAspect;
            float maxShift = Math.max(0f, 1f - visibleWidthFraction);
            float error = cx - 0.5f;
            if (Math.abs(error) < 0.035f) error = 0f;
            float dx = clamp(-2f * error, -maxShift, maxShift);
            matrix.postTranslate(dx, 0f);
        } else if (inputAspect < targetAspect - 0.001f) {
            float visibleHeightFraction = inputAspect / targetAspect;
            float maxShift = Math.max(0f, 1f - visibleHeightFraction);
            float targetY = safeZone ? 0.42f : 0.50f;
            float error = cy - targetY;
            if (Math.abs(error) < 0.04f) error = 0f;
            float dy = clamp(2f * error, -maxShift, maxShift);
            matrix.postTranslate(0f, dy);
        }
        return matrix;
    }

    private long nearestHitDistance(long timeMs) {
        long best = Long.MAX_VALUE;
        for (ActorScanStore.Hit h : hits) {
            long d = Math.abs(h.t - timeMs);
            if (d < best) best = d;
            if (h.t > timeMs && d > best) break;
        }
        return best;
    }

    private float[] smoothCenter(long globalMs) {
        double sx = 0, sy = 0, sw = 0;
        final long radius = 2200L;
        for (ActorScanStore.Hit h : hits) {
            long dt = Math.abs(h.t - globalMs);
            if (dt > radius) continue;
            double temporal = Math.exp(-0.5 * Math.pow(dt / 850.0, 2.0));
            double confidence = Math.max(0.15, h.quality + 0.30) * Math.max(0.25, h.score + 0.20);
            double w = temporal * confidence;
            sx += h.cx * w;
            sy += h.cy * w;
            sw += w;
        }
        if (sw > 1e-6) return new float[]{(float) (sx / sw), (float) (sy / sw)};

        ActorScanStore.Hit before = null, after = null;
        for (ActorScanStore.Hit h : hits) {
            if (h.t <= globalMs) before = h;
            if (h.t >= globalMs) { after = h; break; }
        }
        if (before == null) before = hits.get(0);
        if (after == null) after = hits.get(hits.size() - 1);
        if (before == after || after.t <= before.t) return new float[]{before.cx, before.cy};
        float p = clamp((globalMs - before.t) / (float) (after.t - before.t), 0f, 1f);
        p = p * p * (3f - 2f * p);
        return new float[]{lerp(before.cx, after.cx, p), lerp(before.cy, after.cy, p)};
    }

    private float[] wholeSegmentCenter() {
        double sx = 0, sy = 0, sw = 0;
        for (ActorScanStore.Hit h : hits) {
            double w = Math.max(0.15, h.quality + 0.25);
            sx += h.cx * w;
            sy += h.cy * w;
            sw += w;
        }
        if (sw <= 0) return new float[]{0.5f, 0.5f};
        return new float[]{(float) (sx / sw), (float) (sy / sw)};
    }

    static androidx.media3.effect.Crop centeredCrop(float inputAspect, float targetAspect) {
        inputAspect = Math.max(0.05f, inputAspect);
        targetAspect = Math.max(0.05f, targetAspect);
        if (inputAspect > targetAspect + 0.001f) {
            float f = targetAspect / inputAspect;
            return new androidx.media3.effect.Crop(-f, f, -1f, 1f);
        }
        if (inputAspect < targetAspect - 0.001f) {
            float f = inputAspect / targetAspect;
            return new androidx.media3.effect.Crop(-1f, 1f, -f, f);
        }
        return new androidx.media3.effect.Crop(-1f, 1f, -1f, 1f);
    }

    private static float lerp(float a, float b, float p) { return a + (b - a) * p; }
    private static float clamp(float x, float lo, float hi) { return Math.max(lo, Math.min(hi, x)); }
}
