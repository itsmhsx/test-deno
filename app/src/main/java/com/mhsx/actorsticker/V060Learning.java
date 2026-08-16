package com.mhsx.actorsticker;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Project-local correction learning for v0.6. Positive/negative actor examples
 * stay in SharedPreferences and are never uploaded. The learned prototype is
 * used for learned Smart Select and for future threshold guidance.
 */
final class V060Learning {
    private static final String PREF = "actor_sticker";
    private static final String POS = "v060_positive_embedding";
    private static final String NEG = "v060_negative_embeddings";
    private static final String POS_COUNT = "v060_positive_count";
    private static final String NEG_COUNT = "v060_negative_count";

    private V060Learning() {}

    static void markPositive(Context c, ActorScanStore.Cluster cluster) {
        if (cluster == null || cluster.centroid == null) return;
        SharedPreferences p = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        float[] current = decode(p.getString(POS, ""));
        int n = Math.max(0, p.getInt(POS_COUNT, 0));
        float[] merged = cluster.centroid.clone();
        if (current != null && current.length == merged.length && n > 0) {
            float a = 1f / Math.min(12f, n + 1f);
            for (int i = 0; i < merged.length; i++) merged[i] = current[i] * (1f - a) + merged[i] * a;
            FaceEngine.normalize(merged);
        }
        p.edit().putString(POS, encode(merged)).putInt(POS_COUNT, n + 1).apply();
    }

    static void markNegative(Context c, ActorScanStore.Cluster cluster) {
        if (cluster == null || cluster.centroid == null) return;
        SharedPreferences p = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        ArrayList<float[]> xs = negatives(p.getString(NEG, ""));
        xs.add(cluster.centroid.clone());
        while (xs.size() > 6) xs.remove(0);
        StringBuilder b = new StringBuilder();
        for (float[] x : xs) {
            if (b.length() > 0) b.append('|');
            b.append(encode(x));
        }
        p.edit().putString(NEG, b.toString()).putInt(NEG_COUNT, p.getInt(NEG_COUNT, 0) + 1).apply();
    }

    static ActorScanStore.Cluster best(Context c, ActorScanStore.ScanState s) {
        if (s == null || s.clusters.isEmpty()) return null;
        SharedPreferences p = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        float[] pos = decode(p.getString(POS, ""));
        ArrayList<float[]> neg = negatives(p.getString(NEG, ""));
        ActorScanStore.Cluster best = null;
        double bestScore = -Double.MAX_VALUE;
        for (ActorScanStore.Cluster x : s.clusters) {
            double score = Math.log1p(Math.max(0, x.count)) * 0.32 + x.avgQuality() * 0.42;
            if (pos != null && x.centroid != null && pos.length == x.centroid.length) {
                score += FaceEngine.cosine(pos, x.centroid) * 1.35;
            } else {
                score += Math.min(1.0, x.smartScore() / 40.0) * 0.35;
            }
            float worstNeg = -1f;
            for (float[] n : neg) if (x.centroid != null && n.length == x.centroid.length) worstNeg = Math.max(worstNeg, FaceEngine.cosine(n, x.centroid));
            if (worstNeg > 0.60f) score -= (worstNeg - 0.60f) * 2.4;
            if (score > bestScore) { bestScore = score; best = x; }
        }
        return best;
    }

    static float learnedConfidence(Context c, ActorScanStore.Cluster cluster) {
        if (cluster == null || cluster.centroid == null) return 0f;
        SharedPreferences p = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        float[] pos = decode(p.getString(POS, ""));
        if (pos == null || pos.length != cluster.centroid.length) return Math.min(1f, cluster.avgQuality() * 0.7f + Math.min(1f, cluster.count / 12f) * 0.3f);
        float positive = FaceEngine.cosine(pos, cluster.centroid);
        float negative = -1f;
        for (float[] n : negatives(p.getString(NEG, ""))) if (n.length == cluster.centroid.length) negative = Math.max(negative, FaceEngine.cosine(n, cluster.centroid));
        float v = 0.5f + (positive - 0.55f) * 1.45f - Math.max(0f, negative - 0.58f) * 0.9f;
        return Math.max(0f, Math.min(1f, v));
    }

    static void clear(Context c) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().remove(POS).remove(NEG).remove(POS_COUNT).remove(NEG_COUNT).apply();
    }

    static String summary(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        return p.getInt(POS_COUNT, 0) + " positive • " + p.getInt(NEG_COUNT, 0) + " negative corrections";
    }

    private static ArrayList<float[]> negatives(String s) {
        ArrayList<float[]> out = new ArrayList<>();
        if (s == null || s.isEmpty()) return out;
        for (String part : s.split("\\|")) {
            float[] x = decode(part);
            if (x != null) out.add(x);
        }
        return out;
    }

    private static String encode(float[] a) {
        if (a == null) return "";
        ByteBuffer b = ByteBuffer.allocate(a.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float v : a) b.putFloat(v);
        return Base64.encodeToString(b.array(), Base64.NO_WRAP);
    }

    private static float[] decode(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            byte[] raw = Base64.decode(s, Base64.NO_WRAP);
            if (raw.length == 0 || raw.length % 4 != 0) return null;
            ByteBuffer b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
            float[] a = new float[raw.length / 4];
            for (int i = 0; i < a.length; i++) a[i] = b.getFloat();
            FaceEngine.normalize(a);
            return a;
        } catch (Throwable ignored) { return null; }
    }
}
