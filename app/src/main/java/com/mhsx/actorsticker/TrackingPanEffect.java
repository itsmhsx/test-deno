package com.mhsx.actorsticker;

import android.graphics.Matrix;

import androidx.media3.effect.MatrixTransformation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * v0.6 timestamp-aware actor camera. It smooths actor centers, rejects obvious
 * identity-switch outliers, predicts short face-loss gaps, applies conservative
 * dynamic zoom and supports face-center/rule-of-thirds/look-room composition.
 */
final class TrackingPanEffect implements MatrixTransformation {
    private static volatile boolean CFG_DYNAMIC_ZOOM = true;
    private static volatile boolean CFG_LOST_RECOVERY = true;
    private static volatile boolean CFG_IDENTITY_LOCK = true;
    private static volatile float CFG_MAX_ZOOM = 1.16f;
    private static volatile String CFG_COMPOSITION = "Look room";

    static void configure(boolean dynamicZoom, boolean lostRecovery, boolean identityLock, float maxZoom) {
        CFG_DYNAMIC_ZOOM = dynamicZoom;
        CFG_LOST_RECOVERY = lostRecovery;
        CFG_IDENTITY_LOCK = identityLock;
        CFG_MAX_ZOOM = clamp(maxZoom, 1.0f, 1.28f);
    }

    static void configureV060(String composition) {
        if (composition == null) composition = "Look room";
        if (!"Face center".equals(composition) && !"Rule of thirds".equals(composition) && !"Look room".equals(composition)) composition = "Look room";
        CFG_COMPOSITION = composition;
    }

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

    @Override public Matrix getMatrix(long presentationTimeUs) {
        Matrix matrix = new Matrix();
        if (hits.isEmpty()) return matrix;

        long localMs = normalizePresentationMs(presentationTimeUs);
        long globalMs = segmentStartMs + localMs;
        Center center = tracking ? smoothCenter(globalMs) : wholeSegmentCenter();
        float cx = center.x;
        float cy = center.y;

        float zoom = 1f;
        if (CFG_DYNAMIC_ZOOM && tracking) {
            long nearest = nearestHitDistance(globalMs);
            float confidence = clamp(center.confidence, 0f, 1f);
            float stableBonus = nearest <= 700L ? 0.045f : (nearest <= 1300L ? 0.025f : 0f);
            float confidenceBonus = 0.055f * confidence;
            zoom = clamp(1f + stableBonus + confidenceBonus, 1f, CFG_MAX_ZOOM);
            if (nearest > 1800L) zoom = 1f;
            matrix.postScale(zoom, zoom);
        }

        if (inputAspect > targetAspect + 0.001f) {
            float visibleWidthFraction = targetAspect / inputAspect;
            float maxShift = Math.max(0f, 1f - visibleWidthFraction);
            float targetX = compositionTargetX(globalMs, cx);
            float error = cx - targetX;
            if (Math.abs(error) < 0.026f) error = 0f;
            float dx = clamp(-2f * error * zoom, -maxShift * zoom, maxShift * zoom);
            matrix.postTranslate(dx, 0f);
        } else if (inputAspect < targetAspect - 0.001f) {
            float visibleHeightFraction = inputAspect / targetAspect;
            float maxShift = Math.max(0f, 1f - visibleHeightFraction);
            float targetY = safeZone ? 0.42f : 0.50f;
            float error = cy - targetY;
            if (Math.abs(error) < 0.035f) error = 0f;
            float dy = clamp(2f * error * zoom, -maxShift * zoom, maxShift * zoom);
            matrix.postTranslate(0f, dy);
        }
        return matrix;
    }

    private float compositionTargetX(long globalMs, float cx) {
        String mode = CFG_COMPOSITION;
        if ("Face center".equals(mode)) return 0.5f;
        if ("Rule of thirds".equals(mode)) {
            if (cx < 0.44f) return 0.40f;
            if (cx > 0.56f) return 0.60f;
            return 0.5f;
        }
        // Look-room approximation: use short-term movement direction. A subject
        // moving right is held slightly left in the crop, leaving room ahead.
        ActorScanStore.Hit before = null, after = null;
        for (ActorScanStore.Hit h : hits) {
            if (h.t <= globalMs) before = h;
            if (h.t > globalMs) { after = h; break; }
        }
        float vx = 0f;
        if (before != null && after != null && after.t > before.t) vx = (after.cx - before.cx) / (after.t - before.t);
        else if (before != null) {
            ActorScanStore.Hit prev = null;
            for (ActorScanStore.Hit h : hits) {
                if (h.t >= before.t) break;
                prev = h;
            }
            if (prev != null && before.t > prev.t) vx = (before.cx - prev.cx) / (before.t - prev.t);
        }
        if (vx > 0.000015f) return 0.43f;
        if (vx < -0.000015f) return 0.57f;
        if (cx < 0.30f) return 0.43f;
        if (cx > 0.70f) return 0.57f;
        return 0.5f;
    }

    private long normalizePresentationMs(long presentationTimeUs) {
        long ms = Math.max(0L, presentationTimeUs / 1000L);
        if (segmentStartMs > 0 && ms >= segmentStartMs - 250L) return Math.max(0L, ms - segmentStartMs);
        return ms;
    }

    private Center smoothCenter(long globalMs) {
        double sx = 0, sy = 0, sw = 0, conf = 0;
        final long radius = 2300L;
        ActorScanStore.Hit previousAccepted = null;
        for (ActorScanStore.Hit h : hits) {
            long dt = Math.abs(h.t - globalMs);
            if (dt > radius) continue;
            if (CFG_IDENTITY_LOCK && previousAccepted != null && isIdentityJump(previousAccepted, h)) continue;
            double temporal = Math.exp(-0.5 * Math.pow(dt / 780.0, 2.0));
            double confidence = Math.max(0.12, h.quality + 0.28) * Math.max(0.20, h.score + 0.18);
            double w = temporal * confidence;
            sx += h.cx * w;
            sy += h.cy * w;
            conf += clamp((h.quality * 0.65f + h.score * 0.35f), 0f, 1f) * w;
            sw += w;
            previousAccepted = h;
        }
        if (sw > 1e-6) return new Center((float) (sx / sw), (float) (sy / sw), (float) (conf / sw));

        if (CFG_LOST_RECOVERY) {
            Center predicted = predictLostCenter(globalMs);
            if (predicted != null) return predicted;
        }

        ActorScanStore.Hit before = null, after = null;
        for (ActorScanStore.Hit h : hits) {
            if (h.t <= globalMs) before = h;
            if (h.t >= globalMs) { after = h; break; }
        }
        if (before == null) before = hits.get(0);
        if (after == null) after = hits.get(hits.size() - 1);
        if (before == after || after.t <= before.t) return new Center(before.cx, before.cy, confidence(before));
        float p = clamp((globalMs - before.t) / (float) (after.t - before.t), 0f, 1f);
        p = p * p * (3f - 2f * p);
        return new Center(lerp(before.cx, after.cx, p), lerp(before.cy, after.cy, p), lerp(confidence(before), confidence(after), p));
    }

    private Center predictLostCenter(long globalMs) {
        ActorScanStore.Hit b = null, a = null;
        for (ActorScanStore.Hit h : hits) {
            if (h.t <= globalMs) { b = a; a = h; }
            else break;
        }
        if (a == null) return null;
        long lost = globalMs - a.t;
        if (lost < 0 || lost > 2200L) return null;
        if (b == null || a.t <= b.t || isIdentityJump(b, a)) {
            float decay = 1f - lost / 2600f;
            return new Center(lerp(0.5f, a.cx, decay), lerp(safeZone ? 0.42f : 0.5f, a.cy, decay), confidence(a) * decay);
        }
        float dt = Math.max(1f, a.t - b.t);
        float vx = (a.cx - b.cx) / dt;
        float vy = (a.cy - b.cy) / dt;
        float predictionMs = Math.min(1200f, lost);
        float decay = 1f - Math.min(1f, lost / 2300f);
        float px = clamp(a.cx + vx * predictionMs * decay, 0.10f, 0.90f);
        float py = clamp(a.cy + vy * predictionMs * decay, 0.08f, 0.92f);
        return new Center(px, py, confidence(a) * decay);
    }

    private boolean isIdentityJump(ActorScanStore.Hit a, ActorScanStore.Hit b) {
        long dt = Math.abs(b.t - a.t);
        if (dt > 2600L) return false;
        float dx = b.cx - a.cx, dy = b.cy - a.cy;
        float dist = (float) Math.sqrt(dx * dx + dy * dy);
        float confidence = Math.min(confidence(a), confidence(b));
        return dist > 0.48f && confidence < 0.72f;
    }

    private float confidence(ActorScanStore.Hit h) { return clamp(h.quality * 0.65f + h.score * 0.35f, 0f, 1f); }
    private long nearestHitDistance(long t) { long best = Long.MAX_VALUE; for (ActorScanStore.Hit h : hits) best = Math.min(best, Math.abs(h.t - t)); return best; }

    private Center wholeSegmentCenter() {
        double sx = 0, sy = 0, sw = 0, c = 0;
        for (ActorScanStore.Hit h : hits) {
            double w = Math.max(0.15, h.quality + 0.25);
            sx += h.cx * w; sy += h.cy * w; sw += w; c += confidence(h) * w;
        }
        if (sw <= 0) return new Center(0.5f, safeZone ? 0.42f : 0.5f, 0f);
        return new Center((float) (sx / sw), (float) (sy / sw), (float) (c / sw));
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

    private static final class Center {
        final float x, y, confidence;
        Center(float x, float y, float confidence) { this.x = x; this.y = y; this.confidence = confidence; }
    }
    private static float lerp(float a, float b, float p) { return a + (b - a) * p; }
    private static float clamp(float x, float lo, float hi) { return Math.max(lo, Math.min(hi, x)); }
}
