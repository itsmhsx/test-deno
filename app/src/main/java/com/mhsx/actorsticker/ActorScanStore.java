package com.mhsx.actorsticker;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;

final class ActorScanStore {
    static final int FORMAT_VERSION = 3;
    private final File root;

    ActorScanStore(Context context) {
        root = new File(context.getFilesDir(), "actor_cache");
        if (!root.exists()) root.mkdirs();
    }

    File projectDir(String key) {
        File d = new File(root, key);
        if (!d.exists()) d.mkdirs();
        return d;
    }

    ScanState load(String key) {
        File index = new File(projectDir(key), "index.json");
        if (!index.isFile()) return null;
        try {
            String json = readText(index);
            JSONObject o = new JSONObject(json);
            if (o.optInt("format", 0) != FORMAT_VERSION) return null;
            if (!key.equals(o.optString("projectKey", ""))) return null;
            ScanState s = new ScanState(key);
            s.complete = o.optBoolean("complete", false);
            s.videoIndex = o.optInt("videoIndex", 0);
            s.nextMs = o.optLong("nextMs", 0L);
            s.sampleMs = o.optLong("sampleMs", 900L);
            s.selectedClusterId = o.optInt("selectedClusterId", -1);
            JSONArray cs = o.optJSONArray("clusters");
            if (cs != null) {
                for (int i = 0; i < cs.length(); i++) {
                    JSONObject c = cs.optJSONObject(i);
                    if (c == null) continue;
                    Cluster x = new Cluster(c.optInt("id", i));
                    x.count = c.optInt("count", 0);
                    x.qualitySum = c.optDouble("qualitySum", 0.0);
                    x.centroid = decodeFloats(c.optString("centroid", ""));
                    x.lastSeenVideo = c.optInt("lastSeenVideo", -1);
                    x.lastSeenMs = c.optLong("lastSeenMs", -1L);
                    JSONArray ts = c.optJSONArray("thumbs");
                    if (ts != null) for (int j = 0; j < ts.length(); j++) x.thumbs.add(ts.optString(j));
                    JSONArray tq = c.optJSONArray("thumbQualities");
                    if (tq != null) for (int j = 0; j < tq.length(); j++) x.thumbQualities.add((float) tq.optDouble(j, 0));
                    JSONArray vs = c.optJSONArray("videosSeen");
                    if (vs != null) for (int j = 0; j < vs.length(); j++) x.videosSeen.add(vs.optInt(j));
                    JSONArray hs = c.optJSONArray("hits");
                    if (hs != null) {
                        for (int j = 0; j < hs.length(); j++) {
                            JSONArray h = hs.optJSONArray(j);
                            if (h == null || h.length() < 8) continue;
                            x.hits.add(new Hit(
                                    h.optInt(0), h.optLong(1),
                                    (float) h.optDouble(2), (float) h.optDouble(3),
                                    h.optInt(4), h.optInt(5),
                                    (float) h.optDouble(6), (float) h.optDouble(7)));
                        }
                    }
                    if (x.centroid != null && x.count > 0) s.clusters.add(x);
                }
            }
            return s;
        } catch (Throwable ignored) {
            return null;
        }
    }

    synchronized void save(ScanState s) throws Exception {
        JSONObject o = new JSONObject();
        o.put("format", FORMAT_VERSION);
        o.put("projectKey", s.projectKey);
        o.put("complete", s.complete);
        o.put("videoIndex", s.videoIndex);
        o.put("nextMs", s.nextMs);
        o.put("sampleMs", s.sampleMs);
        o.put("selectedClusterId", s.selectedClusterId);
        JSONArray cs = new JSONArray();
        for (Cluster x : s.clusters) {
            JSONObject c = new JSONObject();
            c.put("id", x.id);
            c.put("count", x.count);
            c.put("qualitySum", x.qualitySum);
            c.put("centroid", encodeFloats(x.centroid));
            c.put("lastSeenVideo", x.lastSeenVideo);
            c.put("lastSeenMs", x.lastSeenMs);
            JSONArray ts = new JSONArray();
            for (String t : x.thumbs) ts.put(t);
            c.put("thumbs", ts);
            JSONArray tq = new JSONArray();
            for (Float q : x.thumbQualities) tq.put(q);
            c.put("thumbQualities", tq);
            JSONArray vs = new JSONArray();
            for (Integer v : x.videosSeen) vs.put(v);
            c.put("videosSeen", vs);
            JSONArray hs = new JSONArray();
            for (Hit h : x.hits) {
                JSONArray a = new JSONArray();
                a.put(h.videoIndex); a.put(h.t); a.put(h.cx); a.put(h.cy);
                a.put(h.fw); a.put(h.fh); a.put(h.score); a.put(h.quality);
                hs.put(a);
            }
            c.put("hits", hs);
            cs.put(c);
        }
        o.put("clusters", cs);

        File dir = projectDir(s.projectKey);
        File temp = new File(dir, "index.json.tmp");
        File dest = new File(dir, "index.json");
        try (Writer w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(temp), "UTF-8"))) {
            w.write(o.toString());
        }
        if (dest.exists() && !dest.delete()) throw new IOException("Cannot replace scan index");
        if (!temp.renameTo(dest)) throw new IOException("Cannot commit scan index");
    }

    String saveThumbnail(String key, int clusterId, int serial, Bitmap bitmap) throws Exception {
        File dir = projectDir(key);
        String name = String.format(Locale.US, "actor_%03d_%04d.webp", clusterId, serial);
        File f = new File(dir, name);
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(f))) {
            if (!bitmap.compress(Bitmap.CompressFormat.WEBP, 84, out)) throw new IOException("Thumbnail encode failed");
        }
        return name;
    }

    File thumbnailFile(String key, String relative) {
        return new File(projectDir(key), relative);
    }

    void deleteThumbnail(String key, String relative) {
        try { new File(projectDir(key), relative).delete(); } catch (Throwable ignored) {}
    }

    void clearProject(String key) {
        deleteRecursively(new File(root, key));
    }

    void clearAll() {
        File[] fs = root.listFiles();
        if (fs != null) for (File f : fs) deleteRecursively(f);
    }

    long cacheBytes() {
        return bytes(root);
    }

    private static long bytes(File f) {
        if (f == null || !f.exists()) return 0;
        if (f.isFile()) return f.length();
        long n = 0;
        File[] xs = f.listFiles();
        if (xs != null) for (File x : xs) n += bytes(x);
        return n;
    }

    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] xs = f.listFiles();
            if (xs != null) for (File x : xs) deleteRecursively(x);
        }
        try { f.delete(); } catch (Throwable ignored) {}
    }

    private static String readText(File f) throws Exception {
        StringBuilder b = new StringBuilder((int) Math.min(Integer.MAX_VALUE, f.length() + 32));
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"))) {
            String line;
            while ((line = r.readLine()) != null) b.append(line);
        }
        return b.toString();
    }

    private static String encodeFloats(float[] a) {
        if (a == null) return "";
        ByteBuffer b = ByteBuffer.allocate(a.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float v : a) b.putFloat(v);
        return Base64.encodeToString(b.array(), Base64.NO_WRAP);
    }

    private static float[] decodeFloats(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            byte[] raw = Base64.decode(s, Base64.NO_WRAP);
            if (raw.length == 0 || raw.length % 4 != 0) return null;
            ByteBuffer b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
            float[] a = new float[raw.length / 4];
            for (int i = 0; i < a.length; i++) a[i] = b.getFloat();
            return a;
        } catch (Throwable ignored) {
            return null;
        }
    }

    static final class ScanState {
        final String projectKey;
        final ArrayList<Cluster> clusters = new ArrayList<>();
        boolean complete;
        int videoIndex;
        long nextMs;
        long sampleMs = 900L;
        int selectedClusterId = -1;

        ScanState(String projectKey) { this.projectKey = projectKey; }

        Cluster byId(int id) {
            for (Cluster c : clusters) if (c.id == id) return c;
            return null;
        }
    }

    static final class Cluster {
        final int id;
        int count;
        double qualitySum;
        float[] centroid;
        final ArrayList<String> thumbs = new ArrayList<>();
        final ArrayList<Float> thumbQualities = new ArrayList<>();
        final ArrayList<Hit> hits = new ArrayList<>();
        final LinkedHashSet<Integer> videosSeen = new LinkedHashSet<>();
        int lastSeenVideo = -1;
        long lastSeenMs = -1L;

        Cluster(int id) { this.id = id; }

        float avgQuality() { return count <= 0 ? 0f : (float) (qualitySum / count); }

        double smartScore() {
            double coverage = 1.0 + Math.min(2.0, videosSeen.size() * 0.22);
            return count * (0.45 + avgQuality()) * coverage;
        }

        void updateCentroid(float[] d) {
            if (centroid == null) {
                centroid = d.clone();
                FaceEngine.normalize(centroid);
                return;
            }
            float alpha = 1f / Math.min(30f, Math.max(2f, count));
            for (int i = 0; i < centroid.length && i < d.length; i++) {
                centroid[i] = centroid[i] * (1f - alpha) + d[i] * alpha;
            }
            FaceEngine.normalize(centroid);
        }
    }

    static final class Hit {
        final int videoIndex;
        final long t;
        final float cx, cy;
        final int fw, fh;
        final float score, quality;

        Hit(int videoIndex, long t, float cx, float cy, int fw, int fh, float score, float quality) {
            this.videoIndex = videoIndex;
            this.t = t;
            this.cx = cx;
            this.cy = cy;
            this.fw = fw;
            this.fh = fh;
            this.score = score;
            this.quality = quality;
        }
    }
}
