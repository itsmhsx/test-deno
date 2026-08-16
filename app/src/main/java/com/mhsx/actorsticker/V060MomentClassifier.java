package com.mhsx.actorsticker;

import java.util.List;

/**
 * Lightweight on-device moment classifier. This is intentionally heuristic:
 * it uses face motion, confidence and quality and does not pretend to perform
 * semantic speech recognition. It is useful for sorting/filtering review clips.
 */
final class V060MomentClassifier {
    private V060MomentClassifier() {}

    static String classify(List<ActorScanStore.Hit> hits) {
        if (hits == null || hits.isEmpty()) return "Unknown";
        float q=0f, conf=0f, motion=0f;
        int n=0;
        ActorScanStore.Hit prev=null;
        for (ActorScanStore.Hit h : hits) {
            q += h.quality; conf += h.score; n++;
            if (prev != null) {
                float dx=h.cx-prev.cx, dy=h.cy-prev.cy;
                motion += (float)Math.sqrt(dx*dx+dy*dy);
            }
            prev=h;
        }
        q/=Math.max(1,n); conf/=Math.max(1,n);
        motion/=Math.max(1,n-1);
        if (q > 0.74f && motion < 0.025f) return "Close-up";
        if (motion > 0.115f) return "Action";
        if (motion < 0.040f && conf > 0.66f) return "Reaction";
        if (motion < 0.080f && conf > 0.52f) return "Talking";
        return "General";
    }

    static boolean accept(List<ActorScanStore.Hit> hits, String preference) {
        if (preference == null || preference.isEmpty() || "All".equalsIgnoreCase(preference)) return true;
        String c = classify(hits);
        if (preference.equalsIgnoreCase(c)) return true;
        // General clips are allowed only in All mode to keep filters meaningful.
        return false;
    }

    static float rankingBonus(List<ActorScanStore.Hit> hits, String preference) {
        if (preference == null || "All".equalsIgnoreCase(preference)) return 0f;
        return accept(hits, preference) ? 8f : -12f;
    }
}
