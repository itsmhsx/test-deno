package com.mhsx.actorsticker;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.graphics.*;
import android.media.*;
import android.net.Uri;
import android.os.*;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.view.*;
import android.widget.*;

import androidx.media3.common.MediaItem;
import androidx.media3.effect.Crop;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.Transformer;

import com.google.mlkit.vision.face.Face;

import org.json.JSONArray;

import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

public class MainActivity extends Activity {
    private static final int REQ_VIDEOS = 1001;
    private static final int REQ_OUTPUT = 1003;
    private static final long MIN_CLIP_MS = 2000L;
    private static final long DEFAULT_SAMPLE_MS = 1000L;
    private static final long DEFAULT_MAX_CLIP_MS = 8000L;
    private static final long DEFAULT_SCENE_GAP_MS = 2400L;
    private static final int DEFAULT_SCAN_MAX_SIDE = 720;

    private final ArrayList<Uri> videos = new ArrayList<>();
    private Uri outputTree;
    private LinearLayout content;
    private TextView statusBar;
    private ProgressBar progress;
    private SharedPreferences prefs;
    private ActorScanStore scanStore;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private volatile boolean cancelRequested = false;
    private volatile boolean running = false;
    private volatile boolean cacheLoadInFlight = false;
    private volatile Transformer activeTransformer;
    private volatile ActorScanStore.ScanState scanState;
    private volatile String currentProjectKey;
    private volatile int selectedClusterId = -1;
    private String currentPage = "videos";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("actor_sticker", MODE_PRIVATE);
        scanStore = new ActorScanStore(this);
        restoreState();
        buildShell();
        showVideos();
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private TextView text(String s, int sp, boolean bold) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(sp);
        v.setTextColor(0xff172033);
        if (bold) v.setTypeface(null, 1);
        v.setPadding(dp(4), dp(6), dp(4), dp(6));
        return v;
    }

    private Button button(String s, View.OnClickListener c) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setOnClickListener(c);
        return b;
    }

    private EditText edit(String hint, String value) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setSingleLine(true);
        return e;
    }

    private void buildShell() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xfff4f6fa);

        TextView title = text("Actor Sticker Cutter • Android", 22, true);
        title.setPadding(dp(14), dp(14), dp(14), dp(2));
        root.addView(title);

        TextView sub = text("v0.3 • Scan first → Actor Gallery → Smart 1:1 export • private cache • 2s hard minimum", 12, false);
        sub.setPadding(dp(14), 0, dp(14), dp(8));
        root.addView(sub);

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setPadding(dp(8), 0, dp(8), dp(4));
        tabs.addView(button("Videos", v -> showVideos()));
        tabs.addView(button("Scan Actors", v -> showScan()));
        tabs.addView(button("Actor Gallery", v -> showGallery()));
        tabs.addView(button("Output", v -> showOutput()));
        tabs.addView(button("System Info", v -> showSystem()));
        tabs.addView(button("Settings", v -> showSettings()));
        hs.addView(tabs);
        root.addView(hs);

        ScrollView scroll = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(12), dp(8), dp(12), dp(20));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgress(0);
        root.addView(progress, new LinearLayout.LayoutParams(-1, dp(10)));

        statusBar = text("Ready", 12, false);
        statusBar.setBackgroundColor(0xffe8edf6);
        statusBar.setPadding(dp(12), dp(8), dp(12), dp(8));
        root.addView(statusBar);
        setContentView(root);
    }

    private void clear(String heading) {
        content.removeAllViews();
        content.addView(text(heading, 19, true));
    }

    private void line(String s) { content.addView(text(s, 14, false)); }

    private void infoBox(String s) {
        TextView t = text(s, 12, false);
        t.setBackgroundColor(0xffe8f5e9);
        t.setPadding(dp(10), dp(10), dp(10), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(8), 0, dp(8));
        content.addView(t, lp);
    }

    private void setStatus(String s) { runOnUiThread(() -> statusBar.setText(s)); }

    private void setJobProgress(int p) {
        runOnUiThread(() -> progress.setProgress(Math.max(0, Math.min(100, p))));
    }

    private void showVideos() {
        currentPage = "videos";
        clear("Videos");
        content.addView(button("+ Add videos", v -> chooseVideos()));
        content.addView(button("Choose output folder", v -> chooseOutput()));
        line("Videos in project: " + videos.size());
        line("Output: " + (outputTree == null ? "Not selected" : outputTree.toString()));
        if (videos.isEmpty()) {
            line("No videos selected.");
        } else {
            for (int i = 0; i < videos.size(); i++) {
                long d = durationMs(videos.get(i));
                String ds = d > 0 ? String.format(Locale.US, " • %.1f min", d / 60000.0) : "";
                line(String.format(Locale.US, "%02d  %s%s", i + 1, displayName(videos.get(i)), ds));
            }
        }
        content.addView(button("1. Scan actors", v -> showScan()));
        content.addView(button("2. Open Actor Gallery", v -> showGallery()));
        content.addView(button("Clear video queue", v -> {
            if (running) return;
            videos.clear();
            invalidateLoadedProject();
            saveState();
            showVideos();
        }));
        infoBox("Windows-style workflow: choose movie/episodes first, run Actor Scan, then select the actor from the app's own gallery. Extracted face thumbnails are stored only in the app-private actor_cache folder and are never added to Android Photos/Gallery.");
    }

    private void showScan() {
        currentPage = "scan";
        clear("Actor Scan / Index");
        line("Videos: " + videos.size());
        if (videos.isEmpty()) {
            line("Add one or more videos first.");
            content.addView(button("Add videos", v -> chooseVideos()));
            return;
        }

        if (scanState == null) {
            line(cacheLoadInFlight ? "Checking private scan cache…" : "Scan cache not loaded yet.");
            content.addView(button("Start / Resume Scan", v -> startActorScan(false)));
            content.addView(button("Refresh full scan", v -> confirmRefreshScan()));
            if (!cacheLoadInFlight) loadScanStateAsync(() -> { if ("scan".equals(currentPage)) showScan(); });
        } else {
            String state = scanState.complete ? "Complete" : "Partial / resumable";
            line("Index status: " + state);
            line("Detected actor groups: " + displayableClusterCount(scanState));
            line("Raw face groups: " + scanState.clusters.size());
            line("Scan interval: " + scanState.sampleMs + " ms");
            if (!scanState.complete) {
                line("Resume point: video " + Math.min(videos.size(), scanState.videoIndex + 1) + " • " + String.format(Locale.US, "%.1fs", scanState.nextMs / 1000f));
            }
            content.addView(button(scanState.complete ? "Use cached scan (no rescan)" : "Resume Scan", v -> startActorScan(false)));
            content.addView(button("Refresh full scan", v -> confirmRefreshScan()));
            content.addView(button("Open Actor Gallery", v -> showGallery()));
            content.addView(button("Clear this project's scan cache", v -> confirmClearCurrentCache()));
        }
        content.addView(button("Cancel current scan/export", v -> cancelJob()));
        line(String.format(Locale.US, "Private actor cache size: %.1f MB", scanStore.cacheBytes() / 1048576.0));
        infoBox("Scan results are checkpointed while indexing. If Android closes the app during a long movie, reopen the same project and use Resume Scan. Refresh Scan is the only action that intentionally discards the existing index.");
    }

    private void showGallery() {
        currentPage = "gallery";
        clear("Actor Gallery");
        if (videos.isEmpty()) {
            line("Add videos and scan actors first.");
            return;
        }
        if (scanState == null) {
            line("Loading private actor index…");
            if (!cacheLoadInFlight) loadScanStateAsync(() -> { if ("gallery".equals(currentPage)) showGallery(); });
            return;
        }
        if (scanState.clusters.isEmpty()) {
            line("No faces are indexed yet.");
            content.addView(button("Scan Actors", v -> startActorScan(false)));
            return;
        }

        ArrayList<ActorScanStore.Cluster> sorted = sortedClusters(scanState);
        line("Tap Select on the person you want. Groups are ranked by detections, face quality and coverage across videos.");
        line("Selected: " + selectedActorLabel());
        content.addView(button("Smart Select main actor", v -> smartSelectActor()));
        content.addView(button("Continue to 1:1 Output", v -> showOutput()));

        int shown = 0;
        for (ActorScanStore.Cluster c : sorted) {
            if (c.count < 2 && sorted.size() > 8) continue;
            addActorCard(c);
            shown++;
            if (shown >= 30) break;
        }
        if (shown == 0) line("No stable actor groups yet. Run a longer scan or lower the clustering similarity slightly in Settings.");
        infoBox("The thumbnails above are private cache files. They do not use MediaStore, do not request photo-write permission and do not appear in the phone Gallery.");
    }

    private void addActorCard(ActorScanStore.Cluster c) {
        boolean selected = c.id == selectedClusterId;
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(10), dp(10), dp(10), dp(10));
        card.setBackgroundColor(selected ? 0xffd9f4df : 0xffffffff);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
        cp.setMargins(0, dp(7), 0, dp(7));

        String header = (selected ? "✓ " : "") + String.format(Locale.US,
                "Group %02d • %d detections • %d video(s) • quality %.0f%%",
                c.id + 1, c.count, c.videosSeen.size(), c.avgQuality() * 100f);
        TextView h = text(header, 15, true);
        card.addView(h);

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (String rel : c.thumbs) {
            File f = scanStore.thumbnailFile(scanState.projectKey, rel);
            if (!f.isFile()) continue;
            Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath());
            if (b == null) continue;
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageBitmap(b);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(104), dp(104));
            ip.setMargins(0, 0, dp(7), 0);
            row.addView(iv, ip);
        }
        hs.addView(row);
        card.addView(hs);

        card.addView(text(String.format(Locale.US, "Smart score %.1f • cache references %d", c.smartScore(), c.thumbs.size()), 12, false));
        card.addView(button(selected ? "Selected actor" : "Select this actor", v -> selectCluster(c.id)));
        content.addView(card, cp);
    }

    private void showOutput() {
        currentPage = "output";
        clear("Smart 1:1 Output");
        line("Actor: " + selectedActorLabel());
        line("Output folder: " + (outputTree == null ? "Not selected" : outputTree.toString()));
        line("Aspect ratio: 1:1 square (automatic actor-centered crop)");
        line("Hard minimum clip duration: 2.0 seconds");
        line("Original videos: never modified");
        content.addView(button("Choose output folder", v -> chooseOutput()));
        content.addView(button("Open Actor Gallery", v -> showGallery()));
        content.addView(button("Analyze cached actor hits + Export 1:1 MP4", v -> startSquareExport()));
        content.addView(button("Cancel current job", v -> cancelJob()));
        infoBox("Export uses the actor positions already saved by Actor Scan. Landscape footage is cropped horizontally into a square around the selected actor; portrait footage is cropped vertically. The crop is clamped to source boundaries so the square never goes outside the original frame.");
    }

    private void showSettings() {
        currentPage = "settings";
        clear("Settings");

        EditText sample = edit("Actor scan interval ms", String.valueOf(prefs.getLong("sample_ms", DEFAULT_SAMPLE_MS)));
        sample.setInputType(2);
        content.addView(sample);

        EditText cluster = edit("Actor grouping similarity", String.format(Locale.US, "%.2f", prefs.getFloat("cluster_threshold", 0.58f)));
        cluster.setInputType(0x2002);
        content.addView(cluster);

        EditText maxClip = edit("Maximum clip seconds", String.format(Locale.US, "%.1f", prefs.getLong("max_clip_ms", DEFAULT_MAX_CLIP_MS) / 1000f));
        maxClip.setInputType(0x2002);
        content.addView(maxClip);

        EditText gap = edit("Scene merge gap ms", String.valueOf(prefs.getLong("scene_gap_ms", DEFAULT_SCENE_GAP_MS)));
        gap.setInputType(2);
        content.addView(gap);

        EditText scanSide = edit("Analysis max side px", String.valueOf(prefs.getInt("scan_max_side", DEFAULT_SCAN_MAX_SIDE)));
        scanSide.setInputType(2);
        content.addView(scanSide);

        CheckBox keep = new CheckBox(this);
        keep.setText("Keep screen awake while scanning/exporting");
        keep.setChecked(prefs.getBoolean("keep_awake", true));
        content.addView(keep);

        content.addView(button("Save settings", v -> {
            try {
                long sm = Long.parseLong(sample.getText().toString().trim());
                float ct = Float.parseFloat(cluster.getText().toString().trim());
                float sec = Float.parseFloat(maxClip.getText().toString().trim());
                long gp = Long.parseLong(gap.getText().toString().trim());
                int side = Integer.parseInt(scanSide.getText().toString().trim());
                sm = Math.max(350, Math.min(5000, sm));
                ct = Math.max(0.30f, Math.min(0.90f, ct));
                long mm = Math.max(MIN_CLIP_MS, Math.min(30000L, (long) (sec * 1000f)));
                gp = Math.max(800L, Math.min(8000L, gp));
                side = Math.max(480, Math.min(1280, side));
                prefs.edit()
                        .putLong("sample_ms", sm)
                        .putFloat("cluster_threshold", ct)
                        .putLong("max_clip_ms", mm)
                        .putLong("scene_gap_ms", gp)
                        .putInt("scan_max_side", side)
                        .putBoolean("keep_awake", keep.isChecked())
                        .apply();
                setStatus("Settings saved. Refresh Scan to apply scan-setting changes to an existing index.");
                Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "Invalid settings", Toast.LENGTH_SHORT).show();
            }
        }));

        content.addView(button("Clear ALL private actor cache", v -> confirmClearAllCache()));
        line("Minimum clip duration is permanently locked to 2.0 seconds.");
        line("Default output aspect ratio is permanently 1:1 in this build.");
        line("Network Tools are removed. This app does not intentionally use network features.");
        line(String.format(Locale.US, "Current private cache: %.1f MB", scanStore.cacheBytes() / 1048576.0));
    }

    private void showSystem() {
        currentPage = "system";
        clear("System Information");
        content.addView(button("Refresh", v -> showSystem()));
        line("Manufacturer: " + Build.MANUFACTURER);
        line("Brand / Model: " + Build.BRAND + " / " + Build.MODEL);
        line("Device / Product: " + Build.DEVICE + " / " + Build.PRODUCT);
        line("Hardware / Board: " + Build.HARDWARE + " / " + Build.BOARD);
        line("Android: " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")");
        line("Security patch: " + Build.VERSION.SECURITY_PATCH);
        line("ABIs: " + Arrays.toString(Build.SUPPORTED_ABIS));
        line("CPU cores: " + Runtime.getRuntime().availableProcessors());

        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        ((ActivityManager) getSystemService(ACTIVITY_SERVICE)).getMemoryInfo(mi);
        line(String.format(Locale.US, "RAM: %.2f GB total • %.2f GB available • low=%s",
                mi.totalMem / 1073741824.0, mi.availMem / 1073741824.0, mi.lowMemory));

        Runtime rt = Runtime.getRuntime();
        line(String.format(Locale.US, "Java heap: %.1f MB used / %.1f MB max",
                (rt.totalMemory() - rt.freeMemory()) / 1048576.0, rt.maxMemory() / 1048576.0));

        StatFs sf = new StatFs(getFilesDir().getAbsolutePath());
        line(String.format(Locale.US, "App storage: %.2f GB free / %.2f GB",
                sf.getAvailableBytes() / 1073741824.0, sf.getTotalBytes() / 1073741824.0));
        line(String.format(Locale.US, "Actor private cache: %.1f MB", scanStore.cacheBytes() / 1048576.0));
        line("Private cache root: " + new File(getFilesDir(), "actor_cache").getAbsolutePath());

        Intent bat = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (bat != null) {
            int level = bat.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = bat.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int temp = bat.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
            line("Battery: " + Math.round(level * 100f / Math.max(1, scale)) + "% • " + String.format(Locale.US, "%.1f°C", temp / 10f));
        }
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                line("Thermal status: " + pm.getCurrentThermalStatus());
            } catch (Throwable ignored) {}
        }

        line("Video codecs (first 24):");
        try {
            MediaCodecInfo[] infos = new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos();
            int shown = 0;
            for (MediaCodecInfo info : infos) {
                if (shown >= 24) break;
                for (String t : info.getSupportedTypes()) {
                    if (t.startsWith("video/")) {
                        line("  " + (info.isEncoder() ? "ENC " : "DEC ") + info.getName() + " • " + t);
                        shown++;
                        break;
                    }
                }
            }
        } catch (Throwable e) {
            line("Codec query error: " + e.getClass().getSimpleName());
        }

        line("Face detector: ML Kit bundled/offline detector");
        line("Actor grouping: local appearance/edge descriptor + persistent cluster index");
        line("Video exporter: AndroidX Media3 Transformer 1.6.1");
        line("App version: 0.3.0");
    }

    private void chooseVideos() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.setType("video/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, REQ_VIDEOS);
    }

    private void chooseOutput() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, REQ_OUTPUT);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try {
            if (requestCode == REQ_OUTPUT) {
                outputTree = data.getData();
                if (outputTree != null) {
                    getContentResolver().takePersistableUriPermission(outputTree,
                            flags & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION));
                }
            } else if (requestCode == REQ_VIDEOS) {
                boolean changed = false;
                if (data.getClipData() != null) {
                    for (int x = 0; x < data.getClipData().getItemCount(); x++) {
                        Uri u = data.getClipData().getItemAt(x).getUri();
                        if (addUnique(videos, u)) changed = true;
                        persistRead(u);
                    }
                } else if (data.getData() != null) {
                    Uri u = data.getData();
                    if (addUnique(videos, u)) changed = true;
                    persistRead(u);
                }
                if (changed) invalidateLoadedProject();
            }
        } catch (Throwable ignored) {}
        saveState();
        if (requestCode == REQ_OUTPUT && "output".equals(currentPage)) showOutput();
        else showVideos();
    }

    private void persistRead(Uri u) {
        try { getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
        catch (Throwable ignored) {}
    }

    private boolean addUnique(ArrayList<Uri> list, Uri u) {
        if (list.contains(u)) return false;
        list.add(u);
        return true;
    }

    private void invalidateLoadedProject() {
        scanState = null;
        currentProjectKey = null;
        selectedClusterId = -1;
    }

    private void loadScanStateAsync(Runnable after) {
        if (videos.isEmpty() || cacheLoadInFlight) return;
        cacheLoadInFlight = true;
        setStatus("Checking private actor index…");
        worker.execute(() -> {
            try {
                String key = computeProjectKey();
                ActorScanStore.ScanState s = scanStore.load(key);
                currentProjectKey = key;
                scanState = s;
                selectedClusterId = s == null ? -1 : s.selectedClusterId;
                setStatus(s == null ? "No cached scan for this video set" : (s.complete ? "Cached actor scan loaded" : "Partial actor scan loaded • Resume available"));
            } catch (Throwable e) {
                setStatus("Could not load scan cache: " + e.getClass().getSimpleName());
            } finally {
                cacheLoadInFlight = false;
                if (after != null) runOnUiThread(after);
            }
        });
    }

    private void startActorScan(boolean refresh) {
        if (running) {
            Toast.makeText(this, "A job is already running", Toast.LENGTH_SHORT).show();
            return;
        }
        if (videos.isEmpty()) {
            Toast.makeText(this, "Add videos first", Toast.LENGTH_SHORT).show();
            return;
        }

        running = true;
        cancelRequested = false;
        setJobProgress(0);
        setKeepAwake(true);

        worker.execute(() -> {
            ActorScanStore.ScanState s = null;
            try {
                String key = computeProjectKey();
                currentProjectKey = key;
                if (refresh) scanStore.clearProject(key);
                s = refresh ? null : scanStore.load(key);
                if (s == null) {
                    s = new ActorScanStore.ScanState(key);
                    s.sampleMs = clampLong(prefs.getLong("sample_ms", DEFAULT_SAMPLE_MS), 350, 5000);
                }
                scanState = s;
                selectedClusterId = s.selectedClusterId;

                if (s.complete && !refresh) {
                    setStatus("Scan already complete • using cached actor index");
                    setJobProgress(100);
                    ActorScanStore.ScanState done = s;
                    runOnUiThread(() -> {
                        scanState = done;
                        showGallery();
                    });
                    return;
                }

                float clusterThreshold = clampFloat(prefs.getFloat("cluster_threshold", 0.58f), 0.30f, 0.90f);
                int maxSide = Math.max(480, Math.min(1280, prefs.getInt("scan_max_side", DEFAULT_SCAN_MAX_SIDE)));
                int checkpointCounter = 0;
                ArrayList<String> warnings = new ArrayList<>();

                try (FaceEngine engine = new FaceEngine()) {
                    int startVideo = Math.max(0, Math.min(videos.size(), s.videoIndex));
                    for (int vi = startVideo; vi < videos.size() && !cancelRequested; vi++) {
                        Uri video = videos.get(vi);
                        MediaMetadataRetriever mr = new MediaMetadataRetriever();
                        try {
                            mr.setDataSource(this, video);
                            long duration = parseLong(mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION), -1L);
                            if (duration <= 0) {
                                warnings.add(displayName(video) + ": duration unavailable");
                                s.videoIndex = vi + 1;
                                s.nextMs = 0;
                                scanStore.save(s);
                                continue;
                            }
                            if (duration < MIN_CLIP_MS) {
                                warnings.add(displayName(video) + ": skipped because source <2s");
                                s.videoIndex = vi + 1;
                                s.nextMs = 0;
                                scanStore.save(s);
                                continue;
                            }

                            long begin = (vi == startVideo) ? Math.max(0, s.nextMs) : 0L;
                            setStatus("Scanning " + (vi + 1) + "/" + videos.size() + " • " + displayName(video));

                            for (long t = begin; t < duration && !cancelRequested; t += s.sampleMs) {
                                Bitmap raw = null;
                                Bitmap frame = null;
                                try {
                                    raw = mr.getFrameAtTime(t * 1000L, MediaMetadataRetriever.OPTION_CLOSEST);
                                    if (raw != null) {
                                        frame = scaleForAnalysis(raw, maxSide);
                                        if (frame != raw && raw != null && !raw.isRecycled()) raw.recycle();
                                        raw = null;
                                        if (frame != null) indexFrame(engine, frame, s, key, vi, t, clusterThreshold);
                                    }
                                } catch (OutOfMemoryError oom) {
                                    warnings.add("Low memory frame skip at " + displayName(video) + " " + (t / 1000) + "s");
                                    System.gc();
                                } catch (Throwable frameError) {
                                    // A corrupt frame must not kill an overnight scan.
                                } finally {
                                    if (frame != null && !frame.isRecycled()) frame.recycle();
                                    if (raw != null && !raw.isRecycled()) raw.recycle();
                                }

                                s.videoIndex = vi;
                                s.nextMs = Math.min(duration, t + s.sampleMs);
                                checkpointCounter++;
                                if (checkpointCounter >= 25) {
                                    scanStore.save(s);
                                    checkpointCounter = 0;
                                }

                                double local = Math.min(1.0, (t + s.sampleMs) / (double) Math.max(1L, duration));
                                int overall = (int) Math.round(((vi + local) / Math.max(1, videos.size())) * 100.0);
                                setJobProgress(overall);
                            }
                        } catch (Throwable videoError) {
                            warnings.add(displayName(video) + ": " + videoError.getClass().getSimpleName());
                        } finally {
                            try { mr.release(); } catch (Throwable ignored) {}
                        }

                        if (!cancelRequested) {
                            s.videoIndex = vi + 1;
                            s.nextMs = 0;
                            scanStore.save(s);
                        }
                    }
                }

                if (cancelRequested) {
                    s.complete = false;
                    scanStore.save(s);
                    setStatus("Scan cancelled • checkpoint saved • Resume available");
                } else {
                    s.complete = true;
                    s.videoIndex = videos.size();
                    s.nextMs = 0;
                    scanStore.save(s);
                    setJobProgress(100);
                    setStatus("Actor scan complete • " + displayableClusterCount(s) + " stable group(s)");
                    ActorScanStore.ScanState completed = s;
                    runOnUiThread(() -> {
                        scanState = completed;
                        showGallery();
                        if (!warnings.isEmpty()) Toast.makeText(this, warnings.size() + " scan warning(s); index still completed", Toast.LENGTH_LONG).show();
                    });
                }
            } catch (Throwable e) {
                if (s != null) {
                    try { s.complete = false; scanStore.save(s); } catch (Throwable ignored) {}
                }
                showError("Actor scan failed", e);
            } finally {
                running = false;
                setKeepAwake(false);
                if (cancelRequested) setJobProgress(0);
            }
        });
    }

    private void indexFrame(FaceEngine engine, Bitmap frame, ActorScanStore.ScanState s, String key,
                            int videoIndex, long t, float threshold) throws Exception {
        List<Face> faces = engine.detect(frame);
        if (faces == null || faces.isEmpty()) return;
        HashSet<Integer> usedClusters = new HashSet<>();

        for (Face f : faces) {
            Rect box = f.getBoundingBox();
            if (box == null || box.width() < 30 || box.height() < 30) continue;
            float[] d = engine.descriptor(frame, box);
            if (d == null) continue;
            float q = engine.quality(frame, box);

            Match match = bestCluster(s, d, threshold, usedClusters);
            ActorScanStore.Cluster c;
            float similarity;
            if (match == null) {
                c = new ActorScanStore.Cluster(nextClusterId(s));
                c.centroid = d.clone();
                FaceEngine.normalize(c.centroid);
                s.clusters.add(c);
                similarity = 1f;
            } else {
                c = match.cluster;
                similarity = match.score;
            }
            usedClusters.add(c.id);

            c.count++;
            c.qualitySum += q;
            c.videosSeen.add(videoIndex);
            c.lastSeenVideo = videoIndex;
            c.lastSeenMs = t;
            c.updateCentroid(d);

            float cx = box.centerX() / (float) Math.max(1, frame.getWidth());
            float cy = box.centerY() / (float) Math.max(1, frame.getHeight());
            c.hits.add(new ActorScanStore.Hit(videoIndex, t, cx, cy,
                    frame.getWidth(), frame.getHeight(), similarity, q));
            maybeSaveThumbnail(engine, frame, box, s.projectKey, c, q);
        }
    }

    private Match bestCluster(ActorScanStore.ScanState s, float[] d, float threshold, Set<Integer> used) {
        ActorScanStore.Cluster best = null;
        float bestScore = -1f;
        for (ActorScanStore.Cluster c : s.clusters) {
            if (used.contains(c.id) || c.centroid == null) continue;
            float sc = FaceEngine.cosine(c.centroid, d);
            if (sc > bestScore) {
                bestScore = sc;
                best = c;
            }
        }
        if (best == null || bestScore < threshold) return null;
        return new Match(best, bestScore);
    }

    private void maybeSaveThumbnail(FaceEngine engine, Bitmap frame, Rect box, String key,
                                    ActorScanStore.Cluster c, float quality) {
        try {
            int replace = -1;
            if (c.thumbs.size() >= 4) {
                float minQ = Float.MAX_VALUE;
                for (int i = 0; i < c.thumbQualities.size(); i++) {
                    float q = c.thumbQualities.get(i);
                    if (q < minQ) { minQ = q; replace = i; }
                }
                if (replace < 0 || quality <= minQ + 0.05f) return;
            }
            Bitmap thumb = engine.thumbnail(frame, box, 160);
            if (thumb == null) return;
            String name = scanStore.saveThumbnail(key, c.id, c.count, thumb);
            thumb.recycle();
            if (c.thumbs.size() < 4) {
                c.thumbs.add(name);
                c.thumbQualities.add(quality);
            } else {
                String old = c.thumbs.get(replace);
                c.thumbs.set(replace, name);
                c.thumbQualities.set(replace, quality);
                scanStore.deleteThumbnail(key, old);
            }
        } catch (Throwable ignored) {}
    }

    private int nextClusterId(ActorScanStore.ScanState s) {
        int id = 0;
        for (ActorScanStore.Cluster c : s.clusters) id = Math.max(id, c.id + 1);
        return id;
    }

    private Bitmap scaleForAnalysis(Bitmap src, int maxSide) {
        int w = src.getWidth(), h = src.getHeight();
        int m = Math.max(w, h);
        if (m <= maxSide) return src;
        float scale = maxSide / (float) m;
        int nw = Math.max(2, Math.round(w * scale));
        int nh = Math.max(2, Math.round(h * scale));
        return Bitmap.createScaledBitmap(src, nw, nh, true);
    }

    private void selectCluster(int id) {
        if (scanState == null) return;
        ActorScanStore.Cluster c = scanState.byId(id);
        if (c == null) return;
        selectedClusterId = id;
        scanState.selectedClusterId = id;
        worker.execute(() -> {
            try { scanStore.save(scanState); } catch (Throwable ignored) {}
        });
        setStatus("Selected Actor Group " + (id + 1));
        showGallery();
    }

    private void smartSelectActor() {
        if (scanState == null || scanState.clusters.isEmpty()) return;
        ActorScanStore.Cluster best = null;
        for (ActorScanStore.Cluster c : scanState.clusters) {
            if (c.count < 2) continue;
            if (best == null || c.smartScore() > best.smartScore()) best = c;
        }
        if (best == null) {
            for (ActorScanStore.Cluster c : scanState.clusters) {
                if (best == null || c.smartScore() > best.smartScore()) best = c;
            }
        }
        if (best != null) selectCluster(best.id);
    }

    private String selectedActorLabel() {
        if (scanState == null || selectedClusterId < 0) return "None selected";
        ActorScanStore.Cluster c = scanState.byId(selectedClusterId);
        if (c == null) return "None selected";
        return String.format(Locale.US, "Group %02d • %d detections • quality %.0f%%",
                c.id + 1, c.count, c.avgQuality() * 100f);
    }

    private ArrayList<ActorScanStore.Cluster> sortedClusters(ActorScanStore.ScanState s) {
        ArrayList<ActorScanStore.Cluster> xs = new ArrayList<>(s.clusters);
        xs.sort((a, b) -> Double.compare(b.smartScore(), a.smartScore()));
        return xs;
    }

    private int displayableClusterCount(ActorScanStore.ScanState s) {
        int n = 0;
        for (ActorScanStore.Cluster c : s.clusters) if (c.count >= 2) n++;
        return n;
    }

    private void startSquareExport() {
        if (running) {
            Toast.makeText(this, "A job is already running", Toast.LENGTH_SHORT).show();
            return;
        }
        if (videos.isEmpty()) {
            Toast.makeText(this, "Add videos first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (outputTree == null) {
            Toast.makeText(this, "Choose output folder", Toast.LENGTH_SHORT).show();
            return;
        }
        if (scanState == null) {
            Toast.makeText(this, "Run Actor Scan first", Toast.LENGTH_SHORT).show();
            return;
        }
        ActorScanStore.Cluster actor = scanState.byId(selectedClusterId);
        if (actor == null) {
            Toast.makeText(this, "Select an actor in Actor Gallery", Toast.LENGTH_SHORT).show();
            return;
        }

        running = true;
        cancelRequested = false;
        setKeepAwake(true);
        setJobProgress(0);

        worker.execute(() -> {
            int totalClips = 0;
            ArrayList<String> report = new ArrayList<>();
            try {
                long maxClipMs = Math.max(MIN_CLIP_MS, prefs.getLong("max_clip_ms", DEFAULT_MAX_CLIP_MS));
                long sceneGapMs = Math.max(800L, prefs.getLong("scene_gap_ms", DEFAULT_SCENE_GAP_MS));

                for (int vi = 0; vi < videos.size() && !cancelRequested; vi++) {
                    Uri video = videos.get(vi);
                    long duration = durationMs(video);
                    if (duration < MIN_CLIP_MS) {
                        report.add(displayName(video) + ": skipped source <2s/unreadable");
                        continue;
                    }
                    ArrayList<ActorScanStore.Hit> hits = new ArrayList<>();
                    for (ActorScanStore.Hit h : actor.hits) if (h.videoIndex == vi) hits.add(h);
                    hits.sort(Comparator.comparingLong(h -> h.t));
                    List<Segment> segments = buildSegments(hits, duration, scanState.sampleMs, maxClipMs, sceneGapMs);
                    report.add(displayName(video) + ": " + segments.size() + " actor scene(s)");

                    int clipIndex = 0;
                    for (Segment s : segments) {
                        if (cancelRequested) break;
                        long requested = s.endMs - s.startMs;
                        if (requested < MIN_CLIP_MS) {
                            report.add("  guard rejected <2s");
                            continue;
                        }
                        clipIndex++;
                        String name = safeBase(displayName(video)) + String.format(Locale.US,
                                "_actor_%02d_1x1_%dms.mp4", clipIndex, s.startMs);
                        setStatus("Exporting 1:1 • " + name);
                        File temp = exportSegmentBlocking(video, s, name);
                        if (temp == null) continue;
                        long actual = durationMs(temp);
                        if (actual > 0 && actual < 1970L) {
                            temp.delete();
                            report.add("  rejected actual output <2s: " + name);
                            continue;
                        }
                        copyToOutputTree(temp, name);
                        temp.delete();
                        totalClips++;
                        report.add(String.format(Locale.US, "  OK %s • %.1fs • actor %.2f", name, requested / 1000f, s.score));
                    }
                    setJobProgress((vi + 1) * 100 / Math.max(1, videos.size()));
                }

                final int clips = totalClips;
                final String msg = (cancelRequested ? "Cancelled\n\n" : "Completed\n\n")
                        + "1:1 clips exported: " + clips + "\n\n" + joinLines(report, 100);
                runOnUiThread(() -> new AlertDialog.Builder(this)
                        .setTitle("1:1 export result")
                        .setMessage(msg)
                        .setPositiveButton("OK", null)
                        .show());
                setStatus(cancelRequested ? "Export cancelled" : "Finished • " + clips + " square clip(s)");
            } catch (Throwable e) {
                showError("1:1 export failed", e);
            } finally {
                running = false;
                activeTransformer = null;
                setKeepAwake(false);
                if (cancelRequested) setJobProgress(0);
            }
        });
    }

    private List<Segment> buildSegments(List<ActorScanStore.Hit> hits, long duration,
                                        long sampleMs, long maxClipMs, long sceneGapMs) {
        ArrayList<Segment> out = new ArrayList<>();
        if (hits == null || hits.isEmpty()) return out;
        long gap = Math.max(sceneGapMs, (long) (sampleMs * 2.2));
        int i = 0;
        while (i < hits.size()) {
            int j = i;
            while (j + 1 < hits.size() && hits.get(j + 1).t - hits.get(j).t <= gap) j++;

            long rawStart = Math.max(0, hits.get(i).t - 650L);
            long rawEnd = Math.min(duration, hits.get(j).t + 1250L);
            if (rawEnd - rawStart >= MIN_CLIP_MS) {
                long chunkStart = rawStart;
                while (chunkStart < rawEnd) {
                    long chunkEnd = Math.min(rawEnd, chunkStart + maxClipMs);
                    if (rawEnd - chunkEnd > 0 && rawEnd - chunkEnd < MIN_CLIP_MS) chunkEnd = rawEnd;
                    if (chunkEnd - chunkStart >= MIN_CLIP_MS) {
                        double sx = 0, sy = 0, sw = 0, score = 0;
                        int n = 0, fw = 0, fh = 0;
                        for (int k = i; k <= j; k++) {
                            ActorScanStore.Hit h = hits.get(k);
                            if (h.t < chunkStart || h.t > chunkEnd) continue;
                            float weight = Math.max(0.10f, h.quality + 0.25f) * Math.max(0.15f, h.score + 0.2f);
                            sx += h.cx * weight;
                            sy += h.cy * weight;
                            sw += weight;
                            score += h.score;
                            fw = h.fw;
                            fh = h.fh;
                            n++;
                        }
                        if (n == 0) {
                            ActorScanStore.Hit h = hits.get(i);
                            sx = h.cx; sy = h.cy; sw = 1; score = h.score; fw = h.fw; fh = h.fh; n = 1;
                        }
                        out.add(new Segment(chunkStart, chunkEnd,
                                (float) (sx / Math.max(1e-6, sw)),
                                (float) (sy / Math.max(1e-6, sw)),
                                fw, fh, (float) (score / n)));
                    }
                    chunkStart = chunkEnd;
                }
            }
            i = j + 1;
        }
        return out;
    }

    private File exportSegmentBlocking(Uri source, Segment s, String name) throws Exception {
        if (s.endMs - s.startMs < MIN_CLIP_MS) throw new IllegalArgumentException("Hard 2-second guard rejected export");
        File root = getExternalFilesDir(null);
        if (root == null) root = getFilesDir();
        File dir = new File(root, "exports");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("Cannot create temp export directory");
        File out = new File(dir, name);
        if (out.exists() && !out.delete()) throw new IOException("Cannot replace temp export");

        MediaItem.ClippingConfiguration clip = new MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(s.startMs)
                .setEndPositionMs(s.endMs)
                .build();
        MediaItem item = new MediaItem.Builder().setUri(source).setClippingConfiguration(clip).build();
        Crop crop = createSquareCrop(s);
        Effects effects = new Effects(Collections.emptyList(), Collections.singletonList(crop));
        EditedMediaItem edited = new EditedMediaItem.Builder(item).setEffects(effects).build();

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Exception> error = new AtomicReference<>();
        runOnUiThread(() -> {
            try {
                Transformer tr = new Transformer.Builder(this)
                        .addListener(new Transformer.Listener() {
                            @Override public void onCompleted(Composition composition, ExportResult result) {
                                activeTransformer = null;
                                latch.countDown();
                            }

                            @Override public void onError(Composition composition, ExportResult result, ExportException ex) {
                                activeTransformer = null;
                                error.set(ex);
                                latch.countDown();
                            }
                        })
                        .build();
                activeTransformer = tr;
                tr.start(edited, out.getAbsolutePath());
            } catch (Exception e) {
                error.set(e);
                latch.countDown();
            }
        });

        if (!latch.await(25, TimeUnit.MINUTES)) {
            runOnUiThread(() -> { try { if (activeTransformer != null) activeTransformer.cancel(); } catch (Throwable ignored) {} });
            throw new TimeoutException("Media3 export timeout");
        }
        if (cancelRequested) {
            if (out.exists()) out.delete();
            return null;
        }
        if (error.get() != null) throw error.get();
        if (!out.isFile() || out.length() <= 0) throw new IOException("Empty export file");
        return out;
    }

    private Crop createSquareCrop(Segment s) {
        float w = Math.max(1f, s.fw);
        float h = Math.max(1f, s.fh);
        float left = 0f, top = 0f, right = w, bottom = h;

        if (w > h) {
            float square = h;
            left = s.cx * w - square / 2f;
            left = Math.max(0f, Math.min(w - square, left));
            right = left + square;
        } else if (h > w) {
            float square = w;
            top = s.cy * h - square / 2f;
            top = Math.max(0f, Math.min(h - square, top));
            bottom = top + square;
        }

        float l = -1f + 2f * (left / w);
        float r = -1f + 2f * (right / w);
        float t = 1f - 2f * (top / h);
        float b = 1f - 2f * (bottom / h);
        return new Crop(l, r, b, t);
    }

    private void copyToOutputTree(File src, String name) throws Exception {
        if (outputTree == null) throw new IOException("Output folder not selected");
        String treeId = DocumentsContract.getTreeDocumentId(outputTree);
        Uri parent = DocumentsContract.buildDocumentUriUsingTree(outputTree, treeId);
        Uri dest = DocumentsContract.createDocument(getContentResolver(), parent, "video/mp4", name);
        if (dest == null) throw new IOException("Cannot create output document");

        try (InputStream in = new BufferedInputStream(new FileInputStream(src));
             OutputStream out = new BufferedOutputStream(getContentResolver().openOutputStream(dest, "w"))) {
            if (out == null) throw new IOException("Cannot open output stream");
            byte[] buf = new byte[1024 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (cancelRequested) break;
                out.write(buf, 0, n);
            }
            out.flush();
        }
    }

    private void confirmRefreshScan() {
        if (running) return;
        new AlertDialog.Builder(this)
                .setTitle("Refresh actor scan?")
                .setMessage("This deletes the current private index for this exact video set and scans from the beginning. Other project caches are not touched.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Refresh", (d, w) -> startActorScan(true))
                .show();
    }

    private void confirmClearCurrentCache() {
        if (running || videos.isEmpty()) return;
        new AlertDialog.Builder(this)
                .setTitle("Clear current actor cache?")
                .setMessage("Only the index and thumbnails for this exact video set will be deleted.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear", (d, w) -> worker.execute(() -> {
                    try {
                        String key = currentProjectKey != null ? currentProjectKey : computeProjectKey();
                        scanStore.clearProject(key);
                        scanState = null;
                        selectedClusterId = -1;
                        currentProjectKey = key;
                        setStatus("Current actor cache cleared");
                        runOnUiThread(() -> { if ("scan".equals(currentPage)) showScan(); });
                    } catch (Throwable e) {
                        showError("Cache clear failed", e);
                    }
                }))
                .show();
    }

    private void confirmClearAllCache() {
        if (running) return;
        new AlertDialog.Builder(this)
                .setTitle("Clear ALL actor cache?")
                .setMessage("This removes private face thumbnails and scan indexes for every project. Original videos and exported clips are not affected.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear all", (d, w) -> {
                    scanStore.clearAll();
                    invalidateLoadedProject();
                    setStatus("All private actor cache cleared");
                    showSettings();
                })
                .show();
    }

    private void cancelJob() {
        cancelRequested = true;
        setStatus("Cancelling… current scan checkpoint will be kept");
        runOnUiThread(() -> {
            try { if (activeTransformer != null) activeTransformer.cancel(); } catch (Throwable ignored) {}
        });
    }

    private void setKeepAwake(boolean on) {
        runOnUiThread(() -> {
            if (on && prefs.getBoolean("keep_awake", true)) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        });
    }

    private String computeProjectKey() throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        for (int i = 0; i < videos.size(); i++) {
            Uri u = videos.get(i);
            updateDigest(md, i + "|");
            updateDigest(md, u.toString());
            updateDigest(md, "|" + displayName(u));
            updateDigest(md, "|" + querySize(u));
            updateDigest(md, "|" + durationMs(u));
            updateDigest(md, "\n");
        }
        byte[] d = md.digest();
        StringBuilder b = new StringBuilder();
        for (byte x : d) b.append(String.format(Locale.US, "%02x", x & 0xff));
        return b.substring(0, 32);
    }

    private void updateDigest(MessageDigest md, String s) throws Exception {
        md.update(s.getBytes("UTF-8"));
    }

    private long querySize(Uri u) {
        try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getLong(0);
        } catch (Throwable ignored) {}
        return -1L;
    }

    private long durationMs(Uri u) {
        MediaMetadataRetriever m = new MediaMetadataRetriever();
        try {
            m.setDataSource(this, u);
            return parseLong(m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION), -1L);
        } catch (Throwable e) {
            return -1L;
        } finally {
            try { m.release(); } catch (Throwable ignored) {}
        }
    }

    private long durationMs(File f) {
        MediaMetadataRetriever m = new MediaMetadataRetriever();
        try {
            m.setDataSource(f.getAbsolutePath());
            return parseLong(m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION), -1L);
        } catch (Throwable e) {
            return -1L;
        } finally {
            try { m.release(); } catch (Throwable ignored) {}
        }
    }

    private long parseLong(String s, long def) {
        try { return s == null ? def : Long.parseLong(s); } catch (Throwable e) { return def; }
    }

    private long clampLong(long x, long lo, long hi) { return Math.max(lo, Math.min(hi, x)); }
    private float clampFloat(float x, float lo, float hi) { return Math.max(lo, Math.min(hi, x)); }

    private String safeBase(String n) {
        int dot = n.lastIndexOf('.');
        if (dot > 0) n = n.substring(0, dot);
        return n.replaceAll("[^A-Za-z0-9._-]+", "_");
    }

    private String joinLines(List<String> xs, int max) {
        StringBuilder b = new StringBuilder();
        int n = Math.min(max, xs.size());
        for (int i = 0; i < n; i++) b.append(xs.get(i)).append('\n');
        if (xs.size() > max) b.append("… ").append(xs.size() - max).append(" more\n");
        return b.toString();
    }

    private String displayName(Uri u) {
        try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String s = c.getString(0);
                if (s != null && !s.isEmpty()) return s;
            }
        } catch (Throwable ignored) {}
        return u.getLastPathSegment() == null ? u.toString() : u.getLastPathSegment();
    }

    private void saveState() {
        JSONArray a = new JSONArray();
        for (Uri u : videos) a.put(u.toString());
        SharedPreferences.Editor e = prefs.edit().putString("videos_json", a.toString());
        if (outputTree != null) e.putString("output", outputTree.toString());
        else e.remove("output");
        e.apply();
    }

    private void restoreState() {
        String json = prefs.getString("videos_json", null);
        if (json != null) {
            try {
                JSONArray a = new JSONArray(json);
                for (int i = 0; i < a.length(); i++) {
                    String s = a.optString(i, "");
                    if (!s.isEmpty()) videos.add(Uri.parse(s));
                }
            } catch (Throwable ignored) {}
        } else {
            // Import v0.2 state once. The new ordered JSON state is written on the next change.
            for (String s : prefs.getStringSet("videos", Collections.emptySet())) {
                try { videos.add(Uri.parse(s)); } catch (Throwable ignored) {}
            }
        }
        String out = prefs.getString("output", null);
        if (out != null) {
            try { outputTree = Uri.parse(out); } catch (Throwable ignored) {}
        }
    }

    private void showError(String title, Throwable e) {
        String detail = e == null ? "Unknown error" : e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage());
        setStatus(title + " • " + detail);
        runOnUiThread(() -> new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(detail)
                .setPositiveButton("OK", null)
                .show());
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_RUNNING_LOW) System.gc();
    }

    @Override protected void onDestroy() {
        cancelRequested = true;
        try { if (activeTransformer != null) activeTransformer.cancel(); } catch (Throwable ignored) {}
        worker.shutdownNow();
        super.onDestroy();
    }

    private static final class Match {
        final ActorScanStore.Cluster cluster;
        final float score;
        Match(ActorScanStore.Cluster c, float s) { cluster = c; score = s; }
    }

    private static final class Segment {
        final long startMs, endMs;
        final float cx, cy;
        final int fw, fh;
        final float score;
        Segment(long s, long e, float cx, float cy, int fw, int fh, float score) {
            startMs = s;
            endMs = e;
            this.cx = cx;
            this.cy = cy;
            this.fw = fw;
            this.fh = fh;
            this.score = score;
        }
    }
}
