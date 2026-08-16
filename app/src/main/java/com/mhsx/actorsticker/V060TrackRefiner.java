package com.mhsx.actorsticker;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.media.MediaMetadataRetriever;
import android.net.Uri;

import com.google.mlkit.vision.face.Face;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Dense v0.6 tracking pass. It re-detects the selected identity at a much
 * shorter interval inside each final segment, then TrackingPanEffect performs
 * smooth per-rendered-frame interpolation between the refined points.
 */
final class V060TrackRefiner {
    private V060TrackRefiner() {}

    static List<ActorScanStore.Hit> refine(Context context, Uri source, int videoIndex,
                                            long startMs, long endMs,
                                            ActorScanStore.Cluster actor,
                                            List<ActorScanStore.Hit> seed,
                                            SharedPreferences prefs) {
        if (actor == null || actor.centroid == null || !prefs.getBoolean("dense_tracking_v060", true)) return seed;
        int step = Math.max(70, Math.min(350, prefs.getInt("tracking_step_ms_v060", 120)));
        int maxSide = Math.max(480, Math.min(960, prefs.getInt("tracking_max_side_v060", 720)));
        float threshold = Math.max(0.34f, Math.min(0.86f, prefs.getFloat("tracking_identity_threshold_v060", 0.49f)));
        ArrayList<ActorScanStore.Hit> out = new ArrayList<>();
        if (seed != null) out.addAll(seed);

        MediaMetadataRetriever mr = new MediaMetadataRetriever();
        try (FaceEngine engine = new FaceEngine()) {
            mr.setDataSource(context, source);
            long span = Math.max(1, endMs - startMs);
            int serial = 0;
            for (long t = startMs; t <= endMs; t += step) {
                Bitmap raw = null, frame = null;
                try {
                    raw = mr.getFrameAtTime(t * 1000L, MediaMetadataRetriever.OPTION_CLOSEST);
                    if (raw == null) continue;
                    frame = scale(raw, maxSide);
                    if (frame != raw && !raw.isRecycled()) raw.recycle();
                    raw = null;
                    List<Face> faces = engine.detect(frame);
                    Face bestFace = null;
                    float best = -1f;
                    float bestQ = 0f;
                    if (faces != null) {
                        for (Face f : faces) {
                            Rect b = f.getBoundingBox();
                            if (b == null || b.width() < 24 || b.height() < 24) continue;
                            float[] d = engine.descriptor(frame, b);
                            if (d == null) continue;
                            float sim = FaceEngine.cosine(actor.centroid, d);
                            float q = engine.quality(frame, b);
                            // A high-quality face may receive only a tiny tie-break bonus.
                            float rank = sim + Math.min(0.035f, q * 0.035f);
                            if (rank > best) { best = rank; bestFace = f; bestQ = q; }
                        }
                    }
                    if (bestFace != null && best >= threshold) {
                        Rect b = bestFace.getBoundingBox();
                        float cx = b.centerX() / (float)Math.max(1, frame.getWidth());
                        float cy = b.centerY() / (float)Math.max(1, frame.getHeight());
                        out.add(new ActorScanStore.Hit(videoIndex, t, cx, cy, frame.getWidth(), frame.getHeight(), Math.min(1f, best), bestQ));
                    }
                } catch (Throwable ignored) {
                } finally {
                    if (frame != null && !frame.isRecycled()) frame.recycle();
                    if (raw != null && !raw.isRecycled()) raw.recycle();
                }
                serial++;
                int p = (int)Math.min(100, Math.round(((t - startMs) / (double)span) * 100.0));
                prefs.edit().putInt("v060_track_progress", p).putString("v060_track_status", "Dense face tracking " + p + "%").apply();
            }
        } catch (Throwable ignored) {
        } finally {
            try { mr.release(); } catch (Throwable ignored) {}
            prefs.edit().putInt("v060_track_progress", 100).apply();
        }

        out.sort(Comparator.comparingLong(h -> h.t));
        // Collapse near-duplicate timestamps, keeping the stronger observation.
        ArrayList<ActorScanStore.Hit> clean = new ArrayList<>();
        for (ActorScanStore.Hit h : out) {
            if (h.t < startMs - 900 || h.t > endMs + 900) continue;
            if (!clean.isEmpty() && Math.abs(clean.get(clean.size()-1).t - h.t) <= 35) {
                ActorScanStore.Hit old = clean.get(clean.size()-1);
                if (h.score * 0.65f + h.quality * 0.35f > old.score * 0.65f + old.quality * 0.35f) clean.set(clean.size()-1, h);
            } else clean.add(h);
        }
        return clean.size() >= 2 ? clean : seed;
    }

    private static Bitmap scale(Bitmap src, int maxSide) {
        int m = Math.max(src.getWidth(), src.getHeight());
        if (m <= maxSide) return src;
        float f = maxSide / (float)m;
        return Bitmap.createScaledBitmap(src, Math.max(2, Math.round(src.getWidth()*f)), Math.max(2, Math.round(src.getHeight()*f)), true);
    }
}
