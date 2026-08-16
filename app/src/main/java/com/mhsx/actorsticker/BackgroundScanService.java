package com.mhsx.actorsticker;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.provider.OpenableColumns;

import com.google.mlkit.vision.face.Face;

import org.json.JSONArray;

import java.io.File;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Foreground, checkpointed Actor Scan used by v0.5. It writes the exact same
 * ActorScanStore format as the in-activity scanner, so Actor Gallery can open
 * results immediately after the service finishes or after a process restart.
 */
public class BackgroundScanService extends Service {
    public static final String ACTION_START = "com.mhsx.actorsticker.scan.START";
    public static final String ACTION_REFRESH = "com.mhsx.actorsticker.scan.REFRESH";
    public static final String ACTION_CANCEL = "com.mhsx.actorsticker.scan.CANCEL";
    private static final String CHANNEL = "actor_scan_v050";
    private static final int NOTIFICATION_ID = 501;
    private static final long MIN_SOURCE_MS = 2000L;

    private final AtomicBoolean cancel = new AtomicBoolean(false);
    private volatile Thread worker;
    private SharedPreferences prefs;
    private ActorScanStore store;

    @Override public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("actor_sticker", MODE_PRIVATE);
        store = new ActorScanStore(this);
        createChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_CANCEL.equals(action)) {
            cancel.set(true);
            updateState(false, prefs.getInt("bg_scan_progress", 0), "Cancelling… checkpoint will be kept");
            return START_NOT_STICKY;
        }
        if (worker != null && worker.isAlive()) return START_STICKY;

        boolean refresh = ACTION_REFRESH.equals(action);
        cancel.set(false);
        startForeground(NOTIFICATION_ID, notification("Preparing actor scan…", 0, true));
        worker = new Thread(() -> runScan(refresh), "actor-scan-v050");
        worker.setPriority(Thread.NORM_PRIORITY - 1);
        worker.start();
        return START_STICKY;
    }

    private void runScan(boolean refresh) {
        ArrayList<Uri> videos = readVideos();
        if (videos.isEmpty()) {
            finishWith("No videos selected", false, 0);
            return;
        }

        ActorScanStore.ScanState s = null;
        try {
            String key = computeProjectKey(videos);
            if (refresh) store.clearProject(key);
            s = refresh ? null : store.load(key);
            if (s == null) {
                s = new ActorScanStore.ScanState(key);
                s.sampleMs = clampLong(prefs.getLong("sample_ms", 1200L), 300L, 5000L);
            }
            if (s.complete && !refresh) {
                finishWith("Cached scan already complete • " + stableGroups(s) + " actor group(s)", true, 100);
                return;
            }

            final float threshold = clampFloat(prefs.getFloat("cluster_threshold", 0.58f), 0.32f, 0.90f);
            final int maxSide = Math.max(480, Math.min(1280, prefs.getInt("scan_max_side", 720)));
            final int startVideo = Math.max(0, Math.min(videos.size(), s.videoIndex));
            int checkpointCounter = 0;

            updateState(true, 0, "Actor scan starting…");
            try (FaceEngine engine = new FaceEngine()) {
                for (int vi = startVideo; vi < videos.size() && !cancel.get(); vi++) {
                    Uri video = videos.get(vi);
                    MediaMetadataRetriever mr = new MediaMetadataRetriever();
                    try {
                        mr.setDataSource(this, video);
                        long duration = parseLong(mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION), -1L);
                        if (duration < MIN_SOURCE_MS) {
                            s.videoIndex = vi + 1;
                            s.nextMs = 0;
                            store.save(s);
                            continue;
                        }
                        long begin = (vi == startVideo) ? Math.max(0, s.nextMs) : 0L;
                        for (long t = begin; t < duration && !cancel.get(); t += s.sampleMs) {
                            Bitmap raw = null;
                            Bitmap frame = null;
                            try {
                                raw = mr.getFrameAtTime(t * 1000L, MediaMetadataRetriever.OPTION_CLOSEST);
                                if (raw != null) {
                                    frame = scale(raw, maxSide);
                                    if (frame != raw && !raw.isRecycled()) raw.recycle();
                                    raw = null;
                                    if (frame != null) indexFrame(engine, frame, s, vi, t, threshold);
                                }
                            } catch (OutOfMemoryError oom) {
                                System.gc();
                            } catch (Throwable ignored) {
                            } finally {
                                if (frame != null && !frame.isRecycled()) frame.recycle();
                                if (raw != null && !raw.isRecycled()) raw.recycle();
                            }

                            s.videoIndex = vi;
                            s.nextMs = Math.min(duration, t + s.sampleMs);
                            checkpointCounter++;
                            if (checkpointCounter >= 18) {
                                store.save(s);
                                checkpointCounter = 0;
                            }
                            int progress = (int) Math.round(((vi + Math.min(1.0, (t + s.sampleMs) / (double) duration)) / videos.size()) * 100.0);
                            if ((checkpointCounter % 3) == 0) {
                                String status = "Scanning " + (vi + 1) + "/" + videos.size() + " • " + (t / 1000) + "s • " + stableGroups(s) + " actor group(s)";
                                updateState(true, progress, status);
                                notifyProgress(status, progress);
                            }
                        }
                    } finally {
                        try { mr.release(); } catch (Throwable ignored) {}
                    }
                    if (!cancel.get()) {
                        s.videoIndex = vi + 1;
                        s.nextMs = 0;
                        store.save(s);
                    }
                }
            }

            if (cancel.get()) {
                s.complete = false;
                store.save(s);
                finishWith("Scan paused • Resume available", false, prefs.getInt("bg_scan_progress", 0));
            } else {
                s.complete = true;
                s.videoIndex = videos.size();
                s.nextMs = 0;
                store.save(s);
                finishWith("Scan complete • " + stableGroups(s) + " actor group(s)", true, 100);
            }
        } catch (Throwable e) {
            if (s != null) {
                try { s.complete = false; store.save(s); } catch (Throwable ignored) {}
            }
            prefs.edit().putString("bg_scan_error", e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage())).apply();
            finishWith("Scan failed • " + e.getClass().getSimpleName(), false, prefs.getInt("bg_scan_progress", 0));
        }
    }

    private void indexFrame(FaceEngine engine, Bitmap frame, ActorScanStore.ScanState s,
                            int videoIndex, long t, float threshold) throws Exception {
        List<Face> faces = engine.detect(frame);
        if (faces == null || faces.isEmpty()) return;
        HashSet<Integer> used = new HashSet<>();
        for (Face face : faces) {
            Rect box = face.getBoundingBox();
            if (box == null || box.width() < 28 || box.height() < 28) continue;
            float[] descriptor = engine.descriptor(frame, box);
            if (descriptor == null) continue;
            float quality = engine.quality(frame, box);

            ActorScanStore.Cluster best = null;
            float bestScore = -1f;
            for (ActorScanStore.Cluster c : s.clusters) {
                if (used.contains(c.id) || c.centroid == null) continue;
                float score = FaceEngine.cosine(c.centroid, descriptor);
                // Identity lock: recently seen identities get only a small continuity
                // bonus; descriptor similarity is still required to prevent switches.
                if (c.lastSeenVideo == videoIndex && c.lastSeenMs >= 0) {
                    long dt = t - c.lastSeenMs;
                    if (dt >= 0 && dt <= Math.max(1800L, s.sampleMs * 3L)) score += 0.035f;
                }
                if (score > bestScore) { bestScore = score; best = c; }
            }
            if (best == null || bestScore < threshold) {
                best = new ActorScanStore.Cluster(nextClusterId(s));
                best.centroid = descriptor.clone();
                FaceEngine.normalize(best.centroid);
                s.clusters.add(best);
                bestScore = 1f;
            }
            used.add(best.id);
            best.count++;
            best.qualitySum += quality;
            best.videosSeen.add(videoIndex);
            best.lastSeenVideo = videoIndex;
            best.lastSeenMs = t;
            best.updateCentroid(descriptor);
            float cx = box.centerX() / (float) Math.max(1, frame.getWidth());
            float cy = box.centerY() / (float) Math.max(1, frame.getHeight());
            best.hits.add(new ActorScanStore.Hit(videoIndex, t, cx, cy,
                    frame.getWidth(), frame.getHeight(), Math.min(1f, bestScore), quality));
            saveThumb(engine, frame, box, s.projectKey, best, quality);
        }
    }

    private void saveThumb(FaceEngine engine, Bitmap frame, Rect box, String key,
                           ActorScanStore.Cluster c, float quality) {
        try {
            int replace = -1;
            if (c.thumbs.size() >= 5) {
                float min = Float.MAX_VALUE;
                for (int i = 0; i < c.thumbQualities.size(); i++) {
                    float q = c.thumbQualities.get(i);
                    if (q < min) { min = q; replace = i; }
                }
                if (replace < 0 || quality <= min + 0.03f) return;
            }
            Bitmap thumb = engine.thumbnail(frame, box, 192);
            if (thumb == null) return;
            String name = store.saveThumbnail(key, c.id, c.count, thumb);
            thumb.recycle();
            if (c.thumbs.size() < 5) {
                c.thumbs.add(name); c.thumbQualities.add(quality);
            } else {
                String old = c.thumbs.get(replace);
                c.thumbs.set(replace, name); c.thumbQualities.set(replace, quality);
                store.deleteThumbnail(key, old);
            }
        } catch (Throwable ignored) {}
    }

    private int nextClusterId(ActorScanStore.ScanState s) {
        int id = 0;
        for (ActorScanStore.Cluster c : s.clusters) id = Math.max(id, c.id + 1);
        return id;
    }

    private ArrayList<Uri> readVideos() {
        ArrayList<Uri> out = new ArrayList<>();
        String json = prefs.getString("videos_json", "[]");
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) {
                String s = a.optString(i, "");
                if (!s.isEmpty()) out.add(Uri.parse(s));
            }
        } catch (Throwable ignored) {}
        return out;
    }

    private String computeProjectKey(List<Uri> videos) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        for (int i = 0; i < videos.size(); i++) {
            Uri u = videos.get(i);
            digest(md, i + "|"); digest(md, u.toString()); digest(md, "|" + displayName(u));
            digest(md, "|" + querySize(u)); digest(md, "|" + durationMs(u)); digest(md, "\n");
        }
        byte[] d = md.digest();
        StringBuilder b = new StringBuilder();
        for (byte x : d) b.append(String.format(Locale.US, "%02x", x & 0xff));
        return b.substring(0, 32);
    }

    private void digest(MessageDigest md, String s) throws Exception { md.update(s.getBytes("UTF-8")); }

    private long querySize(Uri u) {
        try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getLong(0);
        } catch (Throwable ignored) {}
        return -1L;
    }

    private String displayName(Uri u) {
        try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) { String s = c.getString(0); if (s != null && !s.isEmpty()) return s; }
        } catch (Throwable ignored) {}
        return u.getLastPathSegment() == null ? "video" : u.getLastPathSegment();
    }

    private long durationMs(Uri u) {
        MediaMetadataRetriever mr = new MediaMetadataRetriever();
        try { mr.setDataSource(this, u); return parseLong(mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION), -1); }
        catch (Throwable e) { return -1; }
        finally { try { mr.release(); } catch (Throwable ignored) {} }
    }

    private Bitmap scale(Bitmap src, int maxSide) {
        int m = Math.max(src.getWidth(), src.getHeight());
        if (m <= maxSide) return src;
        float f = maxSide / (float) m;
        return Bitmap.createScaledBitmap(src, Math.max(2, Math.round(src.getWidth() * f)),
                Math.max(2, Math.round(src.getHeight() * f)), true);
    }

    private int stableGroups(ActorScanStore.ScanState s) {
        int n = 0;
        for (ActorScanStore.Cluster c : s.clusters) if (c.count >= 2) n++;
        return n;
    }

    private void updateState(boolean running, int progress, String status) {
        prefs.edit().putBoolean("bg_scan_running", running)
                .putInt("bg_scan_progress", Math.max(0, Math.min(100, progress)))
                .putString("bg_scan_status", status)
                .putLong("bg_scan_updated", System.currentTimeMillis()).apply();
    }

    private void finishWith(String status, boolean complete, int progress) {
        updateState(false, progress, status);
        prefs.edit().putBoolean("bg_scan_complete", complete).apply();
        notifyProgress(status, progress);
        stopForeground(false);
        stopSelf();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(CHANNEL, "Actor scan", NotificationManager.IMPORTANCE_LOW);
            c.setDescription("Background actor indexing progress");
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(c);
        }
    }

    private Notification notification(String text, int progress, boolean indeterminate) {
        Intent open = new Intent(this, MainActivityV050.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        Intent cancelIntent = new Intent(this, BackgroundScanService.class).setAction(ACTION_CANCEL);
        PendingIntent cancelPi = PendingIntent.getService(this, 1, cancelIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_search)
                .setContentTitle("Actor Sticker Cutter • Scan")
                .setContentText(text)
                .setContentIntent(pi)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setProgress(100, Math.max(0, Math.min(100, progress)), indeterminate)
                .addAction(new Notification.Action.Builder(null, "Pause", cancelPi).build())
                .build();
    }

    private void notifyProgress(String text, int progress) {
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION_ID, notification(text, progress, false));
    }

    private long parseLong(String s, long d) { try { return s == null ? d : Long.parseLong(s); } catch (Throwable e) { return d; } }
    private long clampLong(long x, long lo, long hi) { return Math.max(lo, Math.min(hi, x)); }
    private float clampFloat(float x, float lo, float hi) { return Math.max(lo, Math.min(hi, x)); }

    @Override public void onDestroy() {
        cancel.set(true);
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
