package com.mhsx.actorsticker;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.*;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.view.*;
import android.widget.*;

import androidx.media3.common.Effect;
import androidx.media3.common.MediaItem;
import androidx.media3.effect.Crop;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.DefaultEncoderFactory;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.Transformer;
import androidx.media3.transformer.VideoEncoderSettings;

import java.io.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * v0.4 export/parity layer.
 *
 * Keeps the scan-first Actor Gallery engine from v0.3.1, but replaces the old
 * static crop export button with timestamp-aware actor tracking. It also adds a
 * strict user-selectable maximum clip duration, output aspect presets, review /
 * ranking, conservative weak-scene filtering, thermal guard, and max-quality
 * source-resolution export with an automatic safe encoder fallback.
 */
public class MainActivityV040 extends MainActivityV031 {
    private static final long MIN_CLIP_MS = 2000L;
    private static final String TAG_CONTROLS = "v040_export_controls";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Set<View> wired = Collections.newSetFromMap(new IdentityHashMap<>());
    private final ExecutorService proWorker = Executors.newSingleThreadExecutor();
    private volatile boolean proRunning;
    private volatile boolean proCancel;
    private volatile Transformer proTransformer;
    private boolean destroyed;

    private final Runnable uiLoop = new Runnable() {
        @Override public void run() {
            if (destroyed) return;
            try {
                wireButtons(getWindow().getDecorView());
                injectOutputControls();
            } catch (Throwable ignored) {}
            ui.postDelayed(this, 650);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        SharedPreferences p = getSharedPreferences("actor_sticker", MODE_PRIVATE);
        SharedPreferences.Editor e = p.edit();
        if (!p.contains("clip_seconds")) e.putInt("clip_seconds", 5);
        if (!p.contains("output_aspect")) e.putString("output_aspect", "1:1");
        if (!p.contains("smart_tracking")) e.putBoolean("smart_tracking", true);
        if (!p.contains("safe_zone")) e.putBoolean("safe_zone", true);
        if (!p.contains("max_quality")) e.putBoolean("max_quality", true);
        if (!p.contains("weak_filter")) e.putBoolean("weak_filter", false);
        if (!p.contains("duplicate_filter")) e.putBoolean("duplicate_filter", true);
        e.apply();
        ui.post(uiLoop);
    }

    private void wireButtons(View root) {
        if (root == null) return;
        if (root instanceof Button) {
            Button b = (Button) root;
            String label = String.valueOf(b.getText());
            if (label.contains("Analyze cached actor hits") && !wired.contains(b)) {
                wired.add(b);
                b.setText("▶ Export Smart Tracking");
                b.setOnClickListener(v -> startProExport(false));
            } else if (label.equalsIgnoreCase("Cancel current job") && !wired.contains(b)) {
                wired.add(b);
                b.setOnClickListener(v -> cancelEverything());
            }
            return;
        }
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) wireButtons(g.getChildAt(i));
        }
    }

    private void injectOutputControls() {
        LinearLayout content = baseContent();
        if (content == null || content.getChildCount() == 0) return;
        View first = content.getChildAt(0);
        if (!(first instanceof TextView) || !String.valueOf(((TextView) first).getText()).contains("Smart 1:1 Output")) return;
        for (int i = 0; i < content.getChildCount(); i++) {
            if (TAG_CONTROLS.equals(content.getChildAt(i).getTag())) return;
        }

        SharedPreferences p = getSharedPreferences("actor_sticker", MODE_PRIVATE);
        LinearLayout box = new LinearLayout(this);
        box.setTag(TAG_CONTROLS);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(10), dp(10), dp(10), dp(10));
        box.setBackgroundColor(0xffeef4ff);

        TextView title = tv("v0.4 Smart Tracking Export", 16, true);
        box.addView(title);
        TextView clipLabel = tv("Maximum clip length: " + p.getInt("clip_seconds", 5) + " seconds", 14, true);
        box.addView(clipLabel);

        HorizontalScrollView hs = new HorizontalScrollView(this);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        int[] presets = {2, 3, 5, 6, 8, 10, 15, 20, 30};
        for (int sec : presets) {
            Button b = new Button(this);
            b.setAllCaps(false);
            b.setText(sec + "s");
            b.setOnClickListener(v -> {
                p.edit().putInt("clip_seconds", sec).apply();
                clipLabel.setText("Maximum clip length: " + sec + " seconds");
                Toast.makeText(this, "Clip limit set to " + sec + "s", Toast.LENGTH_SHORT).show();
            });
            row.addView(b);
        }
        hs.addView(row);
        box.addView(hs);

        LinearLayout customRow = new LinearLayout(this);
        customRow.setOrientation(LinearLayout.HORIZONTAL);
        EditText custom = new EditText(this);
        custom.setHint("Custom seconds");
        custom.setInputType(2);
        custom.setText(String.valueOf(p.getInt("clip_seconds", 5)));
        customRow.addView(custom, new LinearLayout.LayoutParams(0, -2, 1));
        Button save = new Button(this);
        save.setAllCaps(false);
        save.setText("Set");
        save.setOnClickListener(v -> {
            try {
                int sec = Integer.parseInt(custom.getText().toString().trim());
                sec = Math.max(2, Math.min(60, sec));
                p.edit().putInt("clip_seconds", sec).apply();
                custom.setText(String.valueOf(sec));
                clipLabel.setText("Maximum clip length: " + sec + " seconds");
            } catch (Throwable x) {
                Toast.makeText(this, "Enter 2–60 seconds", Toast.LENGTH_SHORT).show();
            }
        });
        customRow.addView(save);
        box.addView(customRow);

        TextView aspectLabel = tv("Output aspect ratio", 14, true);
        box.addView(aspectLabel);
        Spinner aspect = new Spinner(this);
        String[] aspectNames = {"1:1", "9:16", "4:5", "16:9"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, aspectNames);
        aspect.setAdapter(adapter);
        String savedAspect = p.getString("output_aspect", "1:1");
        int selected = 0;
        for (int i = 0; i < aspectNames.length; i++) if (aspectNames[i].equals(savedAspect)) selected = i;
        aspect.setSelection(selected);
        aspect.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                p.edit().putString("output_aspect", aspectNames[position]).apply();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        box.addView(aspect);

        CheckBox tracking = checkbox("Smart face-following pan (smooth)", p.getBoolean("smart_tracking", true));
        CheckBox safe = checkbox("Head / safe-zone protection", p.getBoolean("safe_zone", true));
        CheckBox maxQuality = checkbox("Max Quality • preserve maximum source crop resolution", p.getBoolean("max_quality", true));
        CheckBox weak = checkbox("Reject very weak actor scenes", p.getBoolean("weak_filter", false));
        CheckBox dup = checkbox("Remove overlapping/duplicate scene windows", p.getBoolean("duplicate_filter", true));
        box.addView(tracking); box.addView(safe); box.addView(maxQuality); box.addView(weak); box.addView(dup);

        View.OnClickListener saveFlags = v -> p.edit()
                .putBoolean("smart_tracking", tracking.isChecked())
                .putBoolean("safe_zone", safe.isChecked())
                .putBoolean("max_quality", maxQuality.isChecked())
                .putBoolean("weak_filter", weak.isChecked())
                .putBoolean("duplicate_filter", dup.isChecked())
                .apply();
        tracking.setOnClickListener(saveFlags); safe.setOnClickListener(saveFlags); maxQuality.setOnClickListener(saveFlags);
        weak.setOnClickListener(saveFlags); dup.setOnClickListener(saveFlags);

        Button review = new Button(this);
        review.setAllCaps(false);
        review.setText("Review detected clips before export");
        review.setOnClickListener(v -> previewSegments());
        box.addView(review);

        TextView note = tv("Tracking uses the face positions saved during Actor Scan and updates the pan on every rendered frame. Max Quality does not downscale the crop; a high-bitrate hardware encoder is attempted first and automatically falls back to the phone's safe default if unsupported.", 12, false);
        box.addView(note);
        content.addView(box, 1);
    }

    private CheckBox checkbox(String label, boolean checked) {
        CheckBox c = new CheckBox(this);
        c.setText(label);
        c.setChecked(checked);
        return c;
    }

    private TextView tv(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(0xff172033);
        if (bold) t.setTypeface(null, 1);
        t.setPadding(dp(4), dp(5), dp(4), dp(5));
        return t;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private void previewSegments() {
        if (proRunning) return;
        proWorker.execute(() -> {
            try {
                ProjectAccess a = access();
                if (a.actor == null) throw new IllegalStateException("Select an actor in Actor Gallery first");
                int clipSec = prefs().getInt("clip_seconds", 5);
                StringBuilder out = new StringBuilder();
                int total = 0;
                for (int vi = 0; vi < a.videos.size(); vi++) {
                    long duration = durationMs(a.videos.get(vi));
                    List<ActorScanStore.Hit> hits = hitsForVideo(a.actor, vi);
                    List<ProSegment> segments = buildProSegments(hits, duration, clipSec * 1000L, a.sampleMs);
                    out.append(displayName(a.videos.get(vi))).append(": ").append(segments.size()).append(" clips\n");
                    int shown = 0;
                    for (ProSegment s : segments) {
                        if (shown++ >= 12) { out.append("  …\n"); break; }
                        out.append(String.format(Locale.US, "  %s  %.1f–%.1fs  %.1fs  quality %.0f%%\n",
                                s.rank, s.startMs / 1000.0, s.endMs / 1000.0,
                                (s.endMs - s.startMs) / 1000.0, s.quality));
                    }
                    total += segments.size();
                }
                final String msg = "Clip limit: " + clipSec + "s\nTotal candidates: " + total + "\n\n" + out;
                runOnUiThread(() -> new AlertDialog.Builder(this)
                        .setTitle("Review clips")
                        .setMessage(msg)
                        .setNegativeButton("Close", null)
                        .setPositiveButton("Export", (d, w) -> startProExport(false))
                        .show());
            } catch (Throwable e) {
                showProError("Review failed", e);
            }
        });
    }

    private void startProExport(boolean retrySafe) {
        if (proRunning || baseRunning()) {
            Toast.makeText(this, "A scan/export job is already running", Toast.LENGTH_SHORT).show();
            return;
        }
        ProjectAccess a;
        try {
            a = access();
            if (a.videos.isEmpty()) throw new IllegalStateException("Add videos first");
            if (a.outputTree == null) throw new IllegalStateException("Choose output folder first");
            if (a.actor == null) throw new IllegalStateException("Select an actor in Actor Gallery first");
        } catch (Throwable e) {
            showProError("Cannot start export", e);
            return;
        }

        proRunning = true;
        proCancel = false;
        setStatusText("Preparing Smart Tracking export…");
        setProgressValue(0);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        proWorker.execute(() -> {
            int exported = 0;
            ArrayList<String> report = new ArrayList<>();
            try {
                SharedPreferences p = prefs();
                int clipSec = Math.max(2, Math.min(60, p.getInt("clip_seconds", 5)));
                boolean tracking = p.getBoolean("smart_tracking", true);
                boolean safe = p.getBoolean("safe_zone", true);
                boolean maxQuality = p.getBoolean("max_quality", true);
                boolean weakFilter = p.getBoolean("weak_filter", false);
                boolean duplicateFilter = p.getBoolean("duplicate_filter", true);
                String aspectName = p.getString("output_aspect", "1:1");
                float targetAspect = aspectValue(aspectName);

                for (int vi = 0; vi < a.videos.size() && !proCancel; vi++) {
                    waitForThermalIfNeeded();
                    Uri video = a.videos.get(vi);
                    long duration = durationMs(video);
                    if (duration < MIN_CLIP_MS) {
                        report.add(displayName(video) + ": unreadable/<2s");
                        continue;
                    }
                    List<ActorScanStore.Hit> hits = hitsForVideo(a.actor, vi);
                    List<ProSegment> segments = buildProSegments(hits, duration, clipSec * 1000L, a.sampleMs);
                    if (duplicateFilter) segments = removeOverlaps(segments);
                    int clipNo = 0;
                    for (ProSegment s : segments) {
                        if (proCancel) break;
                        if (s.endMs - s.startMs < MIN_CLIP_MS) continue;
                        if (s.endMs - s.startMs > clipSec * 1000L + 25) {
                            throw new IllegalStateException("Clip-duration guard exceeded " + clipSec + "s");
                        }
                        if (weakFilter && s.quality < 25f) {
                            report.add("SKIP weak " + displayName(video) + " @" + s.startMs);
                            continue;
                        }
                        clipNo++;
                        String suffix = aspectName.replace(':', 'x');
                        String name = s.rank + "_" + safeBase(displayName(video)) + String.format(Locale.US,
                                "_actor_%02d_%s_%ds_%dms.mp4", clipNo, suffix, clipSec, s.startMs);
                        setStatusText("Tracking + exporting • " + name);
                        File temp;
                        try {
                            temp = exportTracked(video, s, name, targetAspect, tracking, safe, maxQuality);
                        } catch (Throwable first) {
                            if (!maxQuality) throw first;
                            setStatusText("Max Quality encoder unsupported • safe quality fallback…");
                            temp = exportTracked(video, s, name, targetAspect, tracking, safe, false);
                        }
                        if (temp == null) continue;
                        long actual = durationMs(temp);
                        if (actual > clipSec * 1000L + 120L) {
                            temp.delete();
                            throw new IllegalStateException("Encoded clip exceeded selected duration: " + actual + "ms");
                        }
                        if (actual < 1970L) {
                            temp.delete();
                            report.add("REJECT actual <2s " + name);
                            continue;
                        }
                        copyToTree(temp, a.outputTree, name);
                        temp.delete();
                        exported++;
                        report.add(String.format(Locale.US, "%s %.1fs Q%.0f %s",
                                name, actual / 1000.0, s.quality, maxQuality ? "MAX" : "SAFE"));
                    }
                    setProgressValue((vi + 1) * 100 / Math.max(1, a.videos.size()));
                }

                int count = exported;
                String msg = (proCancel ? "Cancelled" : "Completed") + "\nExported: " + count +
                        "\nClip limit: " + clipSec + " seconds\nAspect: " + aspectName +
                        "\nSmart tracking: " + tracking + "\n\n" + join(report, 80);
                runOnUiThread(() -> new AlertDialog.Builder(this)
                        .setTitle("v0.4 Export result")
                        .setMessage(msg)
                        .setPositiveButton("OK", null)
                        .show());
                setStatusText(proCancel ? "Export cancelled" : "Finished • " + exported + " clip(s)");
            } catch (Throwable e) {
                writeCrashReport(e);
                showProError("Smart Tracking export failed", e);
            } finally {
                proTransformer = null;
                proRunning = false;
                setProgressValue(0);
                runOnUiThread(() -> getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
            }
        });
    }

    private File exportTracked(Uri source, ProSegment s, String name, float targetAspect,
                               boolean tracking, boolean safeZone, boolean maxQuality) throws Exception {
        File root = getExternalFilesDir(null);
        if (root == null) root = getFilesDir();
        File dir = new File(root, "exports_v040");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("Cannot create export temp folder");
        File out = new File(dir, name);
        if (out.exists()) out.delete();

        float inputAspect = s.inputAspect();
        TrackingPanEffect pan = new TrackingPanEffect(s.hits, s.startMs, inputAspect, targetAspect, tracking, safeZone);
        Crop crop = TrackingPanEffect.centeredCrop(inputAspect, targetAspect);
        ArrayList<Effect> videoEffects = new ArrayList<>();
        videoEffects.add(pan);
        videoEffects.add(crop);

        MediaItem.ClippingConfiguration clip = new MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(s.startMs)
                .setEndPositionMs(s.endMs)
                .build();
        MediaItem media = new MediaItem.Builder().setUri(source).setClippingConfiguration(clip).build();
        EditedMediaItem edited = new EditedMediaItem.Builder(media)
                .setEffects(new Effects(Collections.emptyList(), videoEffects))
                .build();

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Exception> error = new AtomicReference<>();
        runOnUiThread(() -> {
            try {
                Transformer.Builder builder = new Transformer.Builder(this);
                if (maxQuality) {
                    int shortSide = Math.max(360, Math.min(s.sourceWidth(), s.sourceHeight()));
                    int bitrate = recommendedBitrate(shortSide);
                    DefaultEncoderFactory enc = new DefaultEncoderFactory.Builder(this)
                            .setRequestedVideoEncoderSettings(new VideoEncoderSettings.Builder().setBitrate(bitrate).build())
                            .build();
                    builder.setEncoderFactory(enc);
                }
                Transformer tr = builder.addListener(new Transformer.Listener() {
                    @Override public void onCompleted(Composition composition, ExportResult result) {
                        proTransformer = null;
                        latch.countDown();
                    }
                    @Override public void onError(Composition composition, ExportResult result, ExportException ex) {
                        proTransformer = null;
                        error.set(ex);
                        latch.countDown();
                    }
                }).build();
                proTransformer = tr;
                tr.start(edited, out.getAbsolutePath());
            } catch (Exception e) {
                error.set(e);
                latch.countDown();
            }
        });

        if (!latch.await(30, TimeUnit.MINUTES)) {
            runOnUiThread(() -> { try { if (proTransformer != null) proTransformer.cancel(); } catch (Throwable ignored) {} });
            throw new TimeoutException("Export timed out");
        }
        if (proCancel) {
            if (out.exists()) out.delete();
            return null;
        }
        if (error.get() != null) {
            if (out.exists()) out.delete();
            throw error.get();
        }
        if (!out.isFile() || out.length() == 0) throw new IOException("Empty export file");
        return out;
    }

    private int recommendedBitrate(int shortSide) {
        // High but still realistic hardware-encoder requests. If the phone rejects
        // this, exportTracked is retried with Media3 defaults automatically.
        if (shortSide >= 2000) return 42_000_000;
        if (shortSide >= 1400) return 28_000_000;
        if (shortSide >= 1000) return 16_000_000;
        if (shortSide >= 700) return 9_000_000;
        return 5_000_000;
    }

    private List<ProSegment> buildProSegments(List<ActorScanStore.Hit> hits, long duration,
                                              long clipMs, long sampleMs) {
        ArrayList<ProSegment> out = new ArrayList<>();
        if (hits == null || hits.isEmpty() || duration < MIN_CLIP_MS) return out;
        ArrayList<ActorScanStore.Hit> sorted = new ArrayList<>(hits);
        sorted.sort(Comparator.comparingLong(h -> h.t));
        long sceneGap = Math.max(1200L, Math.max(sampleMs * 3L, prefs().getLong("scene_gap_ms", 2400L)));
        int i = 0;
        while (i < sorted.size()) {
            int j = i;
            while (j + 1 < sorted.size() && sorted.get(j + 1).t - sorted.get(j).t <= sceneGap) j++;
            long rawStart = Math.max(0L, sorted.get(i).t - 450L);
            long rawEnd = Math.min(duration, sorted.get(j).t + 850L);

            // Extend short actor appearances to the selected duration when the
            // source has enough room. This gives consistent sticker lengths.
            if (rawEnd - rawStart < clipMs && duration >= clipMs) {
                long center = (rawStart + rawEnd) / 2L;
                rawStart = Math.max(0L, center - clipMs / 2L);
                rawEnd = rawStart + clipMs;
                if (rawEnd > duration) {
                    rawEnd = duration;
                    rawStart = Math.max(0L, rawEnd - clipMs);
                }
            }

            long pos = rawStart;
            while (pos < rawEnd) {
                long end = Math.min(rawEnd, pos + clipMs);
                if (end - pos < MIN_CLIP_MS) break; // never exceed user's selected max to absorb a tail
                ArrayList<ActorScanStore.Hit> windowHits = new ArrayList<>();
                for (int k = i; k <= j; k++) {
                    ActorScanStore.Hit h = sorted.get(k);
                    if (h.t >= pos - 1200L && h.t <= end + 1200L) windowHits.add(h);
                }
                if (!windowHits.isEmpty()) out.add(new ProSegment(pos, end, windowHits, score(windowHits, end - pos, sampleMs)));
                pos = end;
            }
            i = j + 1;
        }
        return out;
    }

    private float score(List<ActorScanStore.Hit> hs, long duration, long sampleMs) {
        if (hs.isEmpty()) return 0f;
        double q = 0, sim = 0;
        for (ActorScanStore.Hit h : hs) { q += h.quality; sim += Math.max(0, Math.min(1, h.score)); }
        q /= hs.size(); sim /= hs.size();
        double expected = Math.max(1.0, duration / (double) Math.max(350L, sampleMs));
        double density = Math.min(1.0, hs.size() / expected);
        return (float) Math.max(0, Math.min(100, 100.0 * (0.62 * q + 0.20 * sim + 0.18 * density)));
    }

    private List<ProSegment> removeOverlaps(List<ProSegment> in) {
        ArrayList<ProSegment> xs = new ArrayList<>(in);
        xs.sort(Comparator.comparingLong(s -> s.startMs));
        ArrayList<ProSegment> out = new ArrayList<>();
        for (ProSegment s : xs) {
            if (out.isEmpty()) { out.add(s); continue; }
            ProSegment p = out.get(out.size() - 1);
            long overlap = Math.max(0L, Math.min(p.endMs, s.endMs) - Math.max(p.startMs, s.startMs));
            long shorter = Math.min(p.endMs - p.startMs, s.endMs - s.startMs);
            if (shorter > 0 && overlap > shorter * 0.65) {
                if (s.quality > p.quality) out.set(out.size() - 1, s);
            } else out.add(s);
        }
        return out;
    }

    private void waitForThermalIfNeeded() {
        if (Build.VERSION.SDK_INT < 29) return;
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            int loops = 0;
            while (!proCancel && pm.getCurrentThermalStatus() >= PowerManager.THERMAL_STATUS_SEVERE && loops < 8) {
                setStatusText("Phone is hot • cooling for stability…");
                Thread.sleep(5000L);
                loops++;
            }
        } catch (Throwable ignored) {}
    }

    private void cancelEverything() {
        proCancel = true;
        setStatusText("Cancelling…");
        try {
            Field f = MainActivity.class.getDeclaredField("cancelRequested");
            f.setAccessible(true);
            f.setBoolean(this, true);
        } catch (Throwable ignored) {}
        runOnUiThread(() -> {
            try { if (proTransformer != null) proTransformer.cancel(); } catch (Throwable ignored) {}
            try {
                Method m = MainActivity.class.getDeclaredMethod("cancelJob");
                m.setAccessible(true);
                m.invoke(this);
            } catch (Throwable ignored) {}
        });
    }

    @SuppressWarnings("unchecked")
    private ProjectAccess access() throws Exception {
        Field vf = MainActivity.class.getDeclaredField("videos"); vf.setAccessible(true);
        Field of = MainActivity.class.getDeclaredField("outputTree"); of.setAccessible(true);
        Field sf = MainActivity.class.getDeclaredField("scanState"); sf.setAccessible(true);
        Field cf = MainActivity.class.getDeclaredField("selectedClusterId"); cf.setAccessible(true);
        ArrayList<Uri> videos = new ArrayList<>((ArrayList<Uri>) vf.get(this));
        Uri output = (Uri) of.get(this);
        ActorScanStore.ScanState scan = (ActorScanStore.ScanState) sf.get(this);
        int selected = cf.getInt(this);
        ActorScanStore.Cluster actor = scan == null ? null : scan.byId(selected);
        long sample = scan == null ? prefs().getLong("sample_ms", 1500L) : scan.sampleMs;
        return new ProjectAccess(videos, output, scan, actor, sample);
    }

    private boolean baseRunning() {
        try { Field f = MainActivity.class.getDeclaredField("running"); f.setAccessible(true); return f.getBoolean(this); }
        catch (Throwable ignored) { return false; }
    }

    private SharedPreferences prefs() { return getSharedPreferences("actor_sticker", MODE_PRIVATE); }

    private LinearLayout baseContent() {
        try { Field f = MainActivity.class.getDeclaredField("content"); f.setAccessible(true); return (LinearLayout) f.get(this); }
        catch (Throwable ignored) { return null; }
    }

    private void setStatusText(String s) {
        runOnUiThread(() -> {
            try { Field f = MainActivity.class.getDeclaredField("statusBar"); f.setAccessible(true); ((TextView) f.get(this)).setText(s); }
            catch (Throwable ignored) {}
        });
    }

    private void setProgressValue(int x) {
        runOnUiThread(() -> {
            try { Field f = MainActivity.class.getDeclaredField("progress"); f.setAccessible(true); ((ProgressBar) f.get(this)).setProgress(Math.max(0, Math.min(100, x))); }
            catch (Throwable ignored) {}
        });
    }

    private List<ActorScanStore.Hit> hitsForVideo(ActorScanStore.Cluster actor, int videoIndex) {
        ArrayList<ActorScanStore.Hit> out = new ArrayList<>();
        if (actor != null) for (ActorScanStore.Hit h : actor.hits) if (h.videoIndex == videoIndex) out.add(h);
        out.sort(Comparator.comparingLong(h -> h.t));
        return out;
    }

    private float aspectValue(String s) {
        if ("9:16".equals(s)) return 9f / 16f;
        if ("4:5".equals(s)) return 4f / 5f;
        if ("16:9".equals(s)) return 16f / 9f;
        return 1f;
    }

    private void copyToTree(File src, Uri tree, String name) throws Exception {
        String treeId = DocumentsContract.getTreeDocumentId(tree);
        Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree, treeId);
        Uri dest = DocumentsContract.createDocument(getContentResolver(), parent, "video/mp4", name);
        if (dest == null) throw new IOException("Cannot create output file");
        try (InputStream in = new BufferedInputStream(new FileInputStream(src));
             OutputStream out = new BufferedOutputStream(getContentResolver().openOutputStream(dest, "w"))) {
            if (out == null) throw new IOException("Cannot open output stream");
            byte[] buf = new byte[1024 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (proCancel) break;
                out.write(buf, 0, n);
            }
            out.flush();
        }
    }

    private long durationMs(Uri u) {
        MediaMetadataRetriever m = new MediaMetadataRetriever();
        try { m.setDataSource(this, u); return parseLong(m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION), -1L); }
        catch (Throwable e) { return -1L; }
        finally { try { m.release(); } catch (Throwable ignored) {} }
    }

    private long durationMs(File f) {
        MediaMetadataRetriever m = new MediaMetadataRetriever();
        try { m.setDataSource(f.getAbsolutePath()); return parseLong(m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION), -1L); }
        catch (Throwable e) { return -1L; }
        finally { try { m.release(); } catch (Throwable ignored) {} }
    }

    private long parseLong(String s, long d) { try { return s == null ? d : Long.parseLong(s); } catch (Throwable e) { return d; } }

    private String displayName(Uri u) {
        try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) { String x = c.getString(0); if (x != null && !x.isEmpty()) return x; }
        } catch (Throwable ignored) {}
        return u.getLastPathSegment() == null ? "video" : u.getLastPathSegment();
    }

    private String safeBase(String n) {
        int dot = n.lastIndexOf('.'); if (dot > 0) n = n.substring(0, dot);
        return n.replaceAll("[^A-Za-z0-9._-]+", "_");
    }

    private String join(List<String> xs, int max) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < Math.min(max, xs.size()); i++) b.append(xs.get(i)).append('\n');
        if (xs.size() > max) b.append("… ").append(xs.size() - max).append(" more");
        return b.toString();
    }

    private void writeCrashReport(Throwable e) {
        try {
            File d = new File(getFilesDir(), "diagnostics"); d.mkdirs();
            File f = new File(d, "last_export_error.txt");
            try (PrintWriter w = new PrintWriter(new OutputStreamWriter(new FileOutputStream(f), "UTF-8"))) {
                w.println(new Date()); w.println(e.toString()); e.printStackTrace(w);
            }
        } catch (Throwable ignored) {}
    }

    private void showProError(String title, Throwable e) {
        String msg = e == null ? "Unknown error" : e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage());
        setStatusText(title + " • " + msg);
        runOnUiThread(() -> new AlertDialog.Builder(this).setTitle(title).setMessage(msg).setPositiveButton("OK", null).show());
    }

    @Override protected void onDestroy() {
        destroyed = true;
        proCancel = true;
        ui.removeCallbacksAndMessages(null);
        try { if (proTransformer != null) proTransformer.cancel(); } catch (Throwable ignored) {}
        proWorker.shutdownNow();
        super.onDestroy();
    }

    private static final class ProjectAccess {
        final ArrayList<Uri> videos;
        final Uri outputTree;
        final ActorScanStore.ScanState scan;
        final ActorScanStore.Cluster actor;
        final long sampleMs;
        ProjectAccess(ArrayList<Uri> v, Uri o, ActorScanStore.ScanState s, ActorScanStore.Cluster a, long sample) {
            videos = v; outputTree = o; scan = s; actor = a; sampleMs = sample;
        }
    }

    private static final class ProSegment {
        final long startMs, endMs;
        final ArrayList<ActorScanStore.Hit> hits;
        final float quality;
        final String rank;
        ProSegment(long s, long e, List<ActorScanStore.Hit> hs, float q) {
            startMs = s; endMs = e; hits = new ArrayList<>(hs); quality = q;
            rank = q >= 70f ? "BEST" : (q >= 45f ? "GOOD" : "REVIEW");
        }
        float inputAspect() {
            if (hits.isEmpty()) return 1f;
            ActorScanStore.Hit h = hits.get(0);
            return h.fh <= 0 ? 1f : h.fw / (float) h.fh;
        }
        int sourceWidth() { return hits.isEmpty() ? 1080 : hits.get(0).fw; }
        int sourceHeight() { return hits.isEmpty() ? 1080 : hits.get(0).fh; }
    }
}
