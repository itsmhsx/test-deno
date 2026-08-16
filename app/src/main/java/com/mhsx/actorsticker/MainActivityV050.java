package com.mhsx.actorsticker;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.*;

/**
 * v0.5 parity shell: dark mode, app dashboard, foreground/resumable scanning,
 * advanced tracking controls, multi-actor export queue, before/after preview,
 * cache limits, device benchmark, codec/quality profiles and crash-retry state.
 */
public class MainActivityV050 extends MainActivityV040Final {
    private static final String TAG_DASH = "v050_dashboard_tab";
    private static final String TAG_OUT = "v050_output_controls";
    private static final String TAG_SET = "v050_settings_controls";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService helperWorker = Executors.newSingleThreadExecutor();
    private final Set<View> exportWired = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<View> scanWired = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<View> multiWired = Collections.newSetFromMap(new IdentityHashMap<>());
    private volatile boolean destroyed;
    private boolean lastBgRunning;

    private final Runnable loop = new Runnable() {
        @Override public void run() {
            if (destroyed) return;
            try {
                View root = getWindow().getDecorView();
                fixV050Labels(root);
                applyTheme(root);
                injectDashboardTab(root);
                injectOutputControls();
                injectSettingsControls();
                wireExportButtons(root);
                wireBackgroundScan(root);
                wireMultiActorCards(root);
                pollBackgroundScan();
            } catch (Throwable ignored) {}
            ui.postDelayed(this, 650L);
        }
    };

    @Override public void onCreate(Bundle state) {
        SharedPreferences p = getSharedPreferences("actor_sticker", MODE_PRIVATE);
        boolean dark = p.getBoolean("dark_mode_v050", false);
        setTheme(dark ? android.R.style.Theme_Material_NoActionBar : android.R.style.Theme_Material_Light_NoActionBar);
        super.onCreate(state);

        SharedPreferences.Editor e = p.edit();
        if (!p.contains("background_scan_v050")) e.putBoolean("background_scan_v050", true);
        if (!p.contains("dynamic_zoom_v050")) e.putBoolean("dynamic_zoom_v050", true);
        if (!p.contains("lost_recovery_v050")) e.putBoolean("lost_recovery_v050", true);
        if (!p.contains("identity_lock_v050")) e.putBoolean("identity_lock_v050", true);
        if (!p.contains("max_zoom_v050")) e.putFloat("max_zoom_v050", 1.14f);
        if (!p.contains("exact_duration_v050")) e.putBoolean("exact_duration_v050", false);
        if (!p.contains("quality_profile_v050")) e.putString("quality_profile_v050", "Maximum");
        if (!p.contains("video_codec_v050")) e.putString("video_codec_v050", "Auto");
        if (!p.contains("scan_profile_v050")) e.putString("scan_profile_v050", "Balanced");
        if (!p.contains("cache_limit_mb_v050")) e.putInt("cache_limit_mb_v050", 2048);
        if (!p.contains("multi_actor_v050")) e.putBoolean("multi_actor_v050", false);
        e.apply();
        applyScanProfile(p.getString("scan_profile_v050", "Balanced"), false);
        configureTracking();
        ui.post(loop);
    }

    private SharedPreferences prefs() { return getSharedPreferences("actor_sticker", MODE_PRIVATE); }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private void fixV050Labels(View v) {
        if (v == null) return;
        if (v instanceof TextView) {
            TextView t = (TextView) v;
            String s = String.valueOf(t.getText());
            if (s.startsWith("v0.4 •")) t.setText("v0.5 • Scan → Actor Gallery → Ultra Tracking • Dynamic Zoom • Max Quality • Background Resume");
            else if (s.equals("App version: 0.4.0") || s.equals("App version: 0.3.0") || s.equals("App version: 0.3.1")) t.setText("App version: 0.5.0");
            else if (s.equals("Face detector: ML Kit bundled/offline detector")) t.setText("Face detector: ML Kit bundled/offline + tracking");
            else if (s.startsWith("Actor grouping:")) t.setText("Actor grouping: Identity Embedding v2 • HOG/LBP/chroma • persistent cluster index");
            return;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) fixV050Labels(g.getChildAt(i));
        }
    }

    private void applyTheme(View root) {
        boolean dark = prefs().getBoolean("dark_mode_v050", false);
        int bg = dark ? 0xff10141d : 0xfff4f6fa;
        int card = dark ? 0xff1a2230 : 0xffffffff;
        int fg = dark ? 0xfff2f5fb : 0xff172033;
        int secondary = dark ? 0xffb9c4d6 : 0xff475569;
        getWindow().setStatusBarColor(dark ? 0xff0b0f16 : 0xffe8edf6);
        getWindow().setNavigationBarColor(dark ? 0xff0b0f16 : 0xffeef2f7);
        if (Build.VERSION.SDK_INT >= 23) getWindow().getDecorView().setSystemUiVisibility(dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        styleNode(root, dark, bg, card, fg, secondary, 0);
    }

    private void styleNode(View v, boolean dark, int bg, int card, int fg, int secondary, int depth) {
        if (v == null) return;
        if (v instanceof TextView && !(v instanceof Button)) {
            ((TextView) v).setTextColor(fg);
        }
        if (v instanceof EditText) {
            ((EditText) v).setTextColor(fg);
            ((EditText) v).setHintTextColor(secondary);
        }
        if (v instanceof CheckBox) ((CheckBox) v).setTextColor(fg);
        if (v instanceof LinearLayout) {
            Object tag = v.getTag();
            if (TAG_OUT.equals(tag) || TAG_SET.equals(tag)) v.setBackgroundColor(card);
            else if (depth <= 2) v.setBackgroundColor(bg);
        }
        if (v instanceof ScrollView || v instanceof HorizontalScrollView) v.setBackgroundColor(bg);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) styleNode(g.getChildAt(i), dark, bg, card, fg, secondary, depth + 1);
        }
    }

    private TextView label(String s, int size, boolean bold) {
        TextView t = new TextView(this); t.setText(s); t.setTextSize(size);
        t.setTextColor(prefs().getBoolean("dark_mode_v050", false) ? 0xfff2f5fb : 0xff172033);
        if (bold) t.setTypeface(null, 1); t.setPadding(dp(4), dp(5), dp(4), dp(5)); return t;
    }
    private CheckBox check(String s, boolean value) { CheckBox c = new CheckBox(this); c.setText(s); c.setChecked(value); return c; }

    private LinearLayout content() {
        try { Field f = MainActivity.class.getDeclaredField("content"); f.setAccessible(true); return (LinearLayout) f.get(this); }
        catch (Throwable ignored) { return null; }
    }

    private void injectDashboardTab(View root) {
        if (!(root instanceof ViewGroup)) return;
        ViewGroup g = (ViewGroup) root;
        if (g instanceof LinearLayout) {
            boolean hasVideos = false, already = false;
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = g.getChildAt(i);
                if (c instanceof Button && "Videos".equals(String.valueOf(((Button) c).getText()))) hasVideos = true;
                if (TAG_DASH.equals(c.getTag())) already = true;
            }
            if (hasVideos && !already) {
                Button b = new Button(this); b.setTag(TAG_DASH); b.setAllCaps(false); b.setText("Dashboard"); b.setOnClickListener(v -> showDashboard());
                g.addView(b, 0);
                return;
            }
        }
        for (int i = 0; i < g.getChildCount(); i++) injectDashboardTab(g.getChildAt(i));
    }

    @SuppressWarnings("unchecked")
    private void showDashboard() {
        LinearLayout c = content(); if (c == null) return;
        c.removeAllViews(); c.addView(label("Project Dashboard • v0.5", 20, true));
        try {
            Field vf = MainActivity.class.getDeclaredField("videos"); vf.setAccessible(true);
            Field sf = MainActivity.class.getDeclaredField("scanState"); sf.setAccessible(true);
            Field cf = MainActivity.class.getDeclaredField("selectedClusterId"); cf.setAccessible(true);
            ArrayList<Uri> videos = (ArrayList<Uri>) vf.get(this);
            ActorScanStore.ScanState scan = (ActorScanStore.ScanState) sf.get(this);
            int selected = cf.getInt(this);
            c.addView(label("Videos: " + videos.size(), 15, true));
            c.addView(label("Actor index: " + (scan == null ? "Not scanned" : (scan.complete ? "Complete" : "Partial / resumable")), 14, false));
            if (scan != null) {
                int stable = 0; for (ActorScanStore.Cluster x : scan.clusters) if (x.count >= 2) stable++;
                c.addView(label("Actor groups: " + stable + " stable / " + scan.clusters.size() + " raw", 14, false));
                ActorScanStore.Cluster actor = scan.byId(selected);
                c.addView(label("Selected actor: " + (actor == null ? "None" : "Group " + (actor.id + 1) + " • " + actor.count + " detections"), 14, false));
            }
        } catch (Throwable e) { c.addView(label("Project state unavailable: " + e.getClass().getSimpleName(), 13, false)); }

        ActorScanStore store = new ActorScanStore(this);
        c.addView(label(String.format(Locale.US, "Private actor cache: %.1f MB / %d MB limit", store.cacheBytes() / 1048576.0,
                prefs().getInt("cache_limit_mb_v050", 2048)), 14, false));
        c.addView(label("Scan: " + prefs().getString("bg_scan_status", "Idle"), 14, false));
        c.addView(label("Quality profile: " + prefs().getString("quality_profile_v050", "Maximum") + " • Codec: " + prefs().getString("video_codec_v050", "Auto"), 14, false));
        c.addView(label("Tracking: Identity Lock " + onOff("identity_lock_v050") + " • Lost Recovery " + onOff("lost_recovery_v050") + " • Dynamic Zoom " + onOff("dynamic_zoom_v050"), 14, false));

        Button resume = new Button(this); resume.setAllCaps(false);
        resume.setText(prefs().getBoolean("bg_scan_running", false) ? "Open live scan status" : "Resume / Start Actor Scan");
        resume.setOnClickListener(v -> startBackgroundScan(false)); c.addView(resume);
        Button gallery = new Button(this); gallery.setAllCaps(false); gallery.setText("Open Actor Gallery"); gallery.setOnClickListener(v -> invokeBase("showGallery")); c.addView(gallery);
        Button output = new Button(this); output.setAllCaps(false); output.setText("Open Output / Review"); output.setOnClickListener(v -> invokeBase("showOutput")); c.addView(output);
        Button clean = new Button(this); clean.setAllCaps(false); clean.setText("Smart cache cleanup"); clean.setOnClickListener(v -> trimCache()); c.addView(clean);
        if (prefs().getBoolean("export_pending_v050", false)) {
            TextView warn = label("Interrupted export detected. Use Output → Retry interrupted export.", 13, true); c.addView(warn);
        }
    }

    private String onOff(String key) { return prefs().getBoolean(key, true) ? "ON" : "OFF"; }

    private void injectOutputControls() {
        LinearLayout c = content(); if (c == null || c.getChildCount() == 0) return;
        View first = c.getChildAt(0);
        if (!(first instanceof TextView) || !String.valueOf(((TextView) first).getText()).contains("Smart 1:1 Output")) return;
        for (int i = 0; i < c.getChildCount(); i++) if (TAG_OUT.equals(c.getChildAt(i).getTag())) return;
        SharedPreferences p = prefs();
        LinearLayout box = new LinearLayout(this); box.setTag(TAG_OUT); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(10), dp(10), dp(10), dp(10));
        box.setBackgroundColor(p.getBoolean("dark_mode_v050", false) ? 0xff1a2230 : 0xfff0f7ff);
        box.addView(label("v0.5 Ultra Tracking / Export", 16, true));

        CheckBox lock = check("Actor Identity Lock • reject sudden face switches", p.getBoolean("identity_lock_v050", true));
        CheckBox recovery = check("Face Lost Recovery • predict short occlusions", p.getBoolean("lost_recovery_v050", true));
        CheckBox zoom = check("Dynamic Zoom • smooth cinematic auto-reframe", p.getBoolean("dynamic_zoom_v050", true));
        CheckBox exact = check("Exact duration mode • reject clips shorter than selected duration", p.getBoolean("exact_duration_v050", false));
        CheckBox multi = check("Multi-Actor export • long-press actors in Gallery to add/remove", p.getBoolean("multi_actor_v050", false));
        box.addView(lock); box.addView(recovery); box.addView(zoom); box.addView(exact); box.addView(multi);
        View.OnClickListener flagSave = v -> {
            p.edit().putBoolean("identity_lock_v050", lock.isChecked()).putBoolean("lost_recovery_v050", recovery.isChecked())
                    .putBoolean("dynamic_zoom_v050", zoom.isChecked()).putBoolean("exact_duration_v050", exact.isChecked())
                    .putBoolean("multi_actor_v050", multi.isChecked()).apply(); configureTracking();
        };
        lock.setOnClickListener(flagSave); recovery.setOnClickListener(flagSave); zoom.setOnClickListener(flagSave); exact.setOnClickListener(flagSave); multi.setOnClickListener(flagSave);

        box.addView(label("Tracking zoom limit", 13, true));
        Spinner z = spinner(new String[]{"1.08x", "1.12x", "1.14x", "1.18x", "1.22x"});
        float savedZoom = p.getFloat("max_zoom_v050", 1.14f); String zs = String.format(Locale.US, "%.2fx", savedZoom);
        setSpinner(z, zs); z.setOnItemSelectedListener(simpleSelected((parent, pos) -> {
            String s = String.valueOf(parent.getItemAtPosition(pos)).replace("x", "");
            try { p.edit().putFloat("max_zoom_v050", Float.parseFloat(s)).apply(); configureTracking(); } catch (Throwable ignored) {}
        })); box.addView(z);

        box.addView(label("Quality profile", 13, true));
        Spinner quality = spinner(new String[]{"Fast", "Balanced", "Maximum"}); setSpinner(quality, p.getString("quality_profile_v050", "Maximum"));
        quality.setOnItemSelectedListener(simpleSelected((parent, pos) -> {
            String q = String.valueOf(parent.getItemAtPosition(pos));
            p.edit().putString("quality_profile_v050", q).putBoolean("max_quality", !"Fast".equals(q)).apply();
        })); box.addView(quality);

        box.addView(label("Video codec", 13, true));
        Spinner codec = spinner(new String[]{"Auto", "H.264/AVC", "H.265/HEVC"}); setSpinner(codec, p.getString("video_codec_v050", "Auto"));
        codec.setOnItemSelectedListener(simpleSelected((parent, pos) -> p.edit().putString("video_codec_v050", String.valueOf(parent.getItemAtPosition(pos))).apply())); box.addView(codec);

        Button preview = new Button(this); preview.setAllCaps(false); preview.setText("Before / After Tracking Preview"); preview.setOnClickListener(v -> showBeforeAfterPreview()); box.addView(preview);
        Button retry = new Button(this); retry.setAllCaps(false); retry.setText("Retry interrupted export"); retry.setVisibility(p.getBoolean("export_pending_v050", false) ? View.VISIBLE : View.GONE); retry.setOnClickListener(v -> startConfiguredExport()); box.addView(retry);
        box.addView(label("Tip: in Actor Gallery, long-press “Select this actor” to build a multi-actor export set. Normal tap still chooses the primary actor.", 12, false));
        c.addView(box, Math.min(2, c.getChildCount()));
    }

    private void injectSettingsControls() {
        LinearLayout c = content(); if (c == null || c.getChildCount() == 0) return;
        View first = c.getChildAt(0);
        if (!(first instanceof TextView) || !"Settings".equals(String.valueOf(((TextView) first).getText()))) return;
        for (int i = 0; i < c.getChildCount(); i++) if (TAG_SET.equals(c.getChildAt(i).getTag())) return;
        SharedPreferences p = prefs();
        LinearLayout box = new LinearLayout(this); box.setTag(TAG_SET); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(10), dp(10), dp(10), dp(10));
        box.setBackgroundColor(p.getBoolean("dark_mode_v050", false) ? 0xff1a2230 : 0xfff8fafc);
        box.addView(label("v0.5 Advanced", 17, true));

        CheckBox dark = check("Dark Mode", p.getBoolean("dark_mode_v050", false));
        dark.setOnClickListener(v -> { p.edit().putBoolean("dark_mode_v050", dark.isChecked()).apply(); recreate(); }); box.addView(dark);
        CheckBox bg = check("Background Actor Scan + Notification + Resume", p.getBoolean("background_scan_v050", true));
        bg.setOnClickListener(v -> p.edit().putBoolean("background_scan_v050", bg.isChecked()).apply()); box.addView(bg);

        box.addView(label("Scan profile", 13, true));
        Spinner scanProfile = spinner(new String[]{"Fast", "Balanced", "Accurate", "Auto"}); setSpinner(scanProfile, p.getString("scan_profile_v050", "Balanced"));
        scanProfile.setOnItemSelectedListener(simpleSelected((parent, pos) -> {
            String profile = String.valueOf(parent.getItemAtPosition(pos)); p.edit().putString("scan_profile_v050", profile).apply(); applyScanProfile(profile, true);
        })); box.addView(scanProfile);

        EditText cache = new EditText(this); cache.setInputType(2); cache.setHint("Cache limit MB"); cache.setText(String.valueOf(p.getInt("cache_limit_mb_v050", 2048))); box.addView(cache);
        Button cacheSave = new Button(this); cacheSave.setAllCaps(false); cacheSave.setText("Save cache limit + clean old projects"); cacheSave.setOnClickListener(v -> {
            try { int mb = Math.max(256, Math.min(16384, Integer.parseInt(cache.getText().toString().trim()))); p.edit().putInt("cache_limit_mb_v050", mb).apply(); cache.setText(String.valueOf(mb)); trimCache(); }
            catch (Throwable e) { Toast.makeText(this, "Enter 256–16384 MB", Toast.LENGTH_SHORT).show(); }
        }); box.addView(cacheSave);

        Button bench = new Button(this); bench.setAllCaps(false); bench.setText("Benchmark this phone + Recommend settings"); bench.setOnClickListener(v -> benchmarkPhone()); box.addView(bench);
        box.addView(label("Background scan saves checkpoints inside private app storage. v0.5 cache format is new, so the first scan after upgrading rebuilds the actor index once.", 12, false));
        c.addView(box);
    }

    private Spinner spinner(String[] items) {
        Spinner s = new Spinner(this); s.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, items)); return s;
    }
    private void setSpinner(Spinner s, String value) {
        for (int i = 0; i < s.getCount(); i++) if (value.equals(String.valueOf(s.getItemAtPosition(i)))) { s.setSelection(i); return; }
    }
    private interface Selected { void call(AdapterView<?> parent, int position); }
    private AdapterView.OnItemSelectedListener simpleSelected(Selected f) {
        return new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { f.call(parent, position); }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        };
    }

    private void configureTracking() {
        SharedPreferences p = prefs();
        TrackingPanEffect.configure(p.getBoolean("dynamic_zoom_v050", true), p.getBoolean("lost_recovery_v050", true),
                p.getBoolean("identity_lock_v050", true), p.getFloat("max_zoom_v050", 1.14f));
    }

    private void wireExportButtons(View root) {
        if (root == null) return;
        if (root instanceof Button) {
            Button b = (Button) root; String s = String.valueOf(b.getText());
            if (s.contains("Export Smart Tracking") && !exportWired.contains(b)) {
                exportWired.add(b); b.setText("▶ Export v0.5 Ultra Tracking"); b.setOnClickListener(v -> startConfiguredExport());
            }
            return;
        }
        if (root instanceof ViewGroup) { ViewGroup g = (ViewGroup) root; for (int i = 0; i < g.getChildCount(); i++) wireExportButtons(g.getChildAt(i)); }
    }

    private void startConfiguredExport() {
        configureTracking();
        SharedPreferences p = prefs();
        String q = p.getString("quality_profile_v050", "Maximum");
        p.edit().putBoolean("max_quality", !"Fast".equals(q)).putBoolean("export_pending_v050", true).putLong("export_pending_time_v050", System.currentTimeMillis()).apply();
        Set<String> selected = p.getStringSet("multi_actor_ids_v050", Collections.emptySet());
        if (p.getBoolean("multi_actor_v050", false) && selected.size() > 1) startMultiActorQueue(new LinkedHashSet<>(selected));
        else invokeV040ExportAndWatch();
    }

    private void invokeV040ExportAndWatch() {
        try {
            Method m = MainActivityV040.class.getDeclaredMethod("startProExport", boolean.class); m.setAccessible(true); m.invoke(this, false);
            watchExportCompletion();
        } catch (Throwable e) { prefs().edit().putBoolean("export_pending_v050", false).apply(); Toast.makeText(this, "Export start failed: " + e.getClass().getSimpleName(), Toast.LENGTH_LONG).show(); }
    }

    private void watchExportCompletion() {
        ui.postDelayed(new Runnable() {
            int idleTicks;
            @Override public void run() {
                if (destroyed) return;
                boolean running = reflectBoolean(MainActivityV040.class, "proRunning", false);
                if (!running) idleTicks++; else idleTicks = 0;
                if (idleTicks >= 3) { prefs().edit().putBoolean("export_pending_v050", false).apply(); return; }
                ui.postDelayed(this, 900L);
            }
        }, 800L);
    }

    private void startMultiActorQueue(Set<String> ids) {
        helperWorker.execute(() -> {
            int original = selectedClusterId();
            try {
                ArrayList<Integer> actorIds = new ArrayList<>();
                for (String s : ids) try { actorIds.add(Integer.parseInt(s)); } catch (Throwable ignored) {}
                Collections.sort(actorIds);
                for (int id : actorIds) {
                    if (destroyed) break;
                    setSelectedClusterId(id);
                    setStatus("Multi-Actor export • Group " + (id + 1));
                    runOnUiThread(this::invokeV040ExportNoWatcher);
                    long deadline = System.currentTimeMillis() + 45L * 60_000L;
                    while (!reflectBoolean(MainActivityV040.class, "proRunning", false) && System.currentTimeMillis() < deadline) Thread.sleep(180L);
                    while (reflectBoolean(MainActivityV040.class, "proRunning", false) && System.currentTimeMillis() < deadline) Thread.sleep(700L);
                }
            } catch (Throwable e) { setStatus("Multi-Actor export stopped: " + e.getClass().getSimpleName()); }
            finally { setSelectedClusterId(original); prefs().edit().putBoolean("export_pending_v050", false).apply(); }
        });
    }
    private void invokeV040ExportNoWatcher() {
        try { Method m = MainActivityV040.class.getDeclaredMethod("startProExport", boolean.class); m.setAccessible(true); m.invoke(this, false); }
        catch (Throwable ignored) {}
    }

    private void wireMultiActorCards(View root) {
        if (root == null) return;
        if (root instanceof Button) {
            Button b = (Button) root; String s = String.valueOf(b.getText());
            if ((s.contains("Select this actor") || s.contains("Selected actor")) && !multiWired.contains(b)) {
                multiWired.add(b); b.setOnLongClickListener(v -> { Integer id = actorIdFromCard(b); if (id == null) return false; toggleMultiActor(id); return true; });
            }
            return;
        }
        if (root instanceof ViewGroup) { ViewGroup g = (ViewGroup) root; for (int i = 0; i < g.getChildCount(); i++) wireMultiActorCards(g.getChildAt(i)); }
    }

    private Integer actorIdFromCard(Button b) {
        ViewParent p = b.getParent();
        if (!(p instanceof ViewGroup)) return null;
        ViewGroup g = (ViewGroup) p;
        for (int i = 0; i < g.getChildCount(); i++) if (g.getChildAt(i) instanceof TextView) {
            String s = String.valueOf(((TextView) g.getChildAt(i)).getText());
            int at = s.indexOf("Group ");
            if (at >= 0) {
                String x = s.substring(at + 6).trim().split("[^0-9]")[0];
                try { return Integer.parseInt(x) - 1; } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    private void toggleMultiActor(int id) {
        Set<String> old = prefs().getStringSet("multi_actor_ids_v050", Collections.emptySet());
        LinkedHashSet<String> n = new LinkedHashSet<>(old); String key = String.valueOf(id);
        boolean added; if (n.contains(key)) { n.remove(key); added = false; } else { n.add(key); added = true; }
        prefs().edit().putStringSet("multi_actor_ids_v050", n).apply();
        Toast.makeText(this, (added ? "Added Group " : "Removed Group ") + (id + 1) + " • Multi set: " + n.size(), Toast.LENGTH_SHORT).show();
    }

    private void wireBackgroundScan(View root) {
        if (!prefs().getBoolean("background_scan_v050", true) || root == null) return;
        if (root instanceof Button) {
            Button b = (Button) root; String s = String.valueOf(b.getText());
            boolean scan = s.contains("Scan Actors NOW") || s.equals("Start / Resume Scan") || s.equals("Resume Scan");
            if (scan && !scanWired.contains(b)) { scanWired.add(b); b.setText("▶ Background Scan / Resume"); b.setOnClickListener(v -> startBackgroundScan(false)); }
            else if (s.equals("Refresh full scan") && !scanWired.contains(b)) { scanWired.add(b); b.setOnClickListener(v -> confirmBackgroundRefresh()); }
            return;
        }
        if (root instanceof ViewGroup) { ViewGroup g = (ViewGroup) root; for (int i = 0; i < g.getChildCount(); i++) wireBackgroundScan(g.getChildAt(i)); }
    }

    private void startBackgroundScan(boolean refresh) {
        Intent i = new Intent(this, BackgroundScanService.class).setAction(refresh ? BackgroundScanService.ACTION_REFRESH : BackgroundScanService.ACTION_START);
        try { if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i); }
        catch (Throwable e) { Toast.makeText(this, "Background scan unavailable; using foreground scan", Toast.LENGTH_LONG).show(); invokeStartBaseScan(refresh); return; }
        prefs().edit().putBoolean("bg_scan_running", true).putString("bg_scan_status", "Starting…").apply();
        setStatus("Background actor scan started • you can leave the app");
        Toast.makeText(this, "Actor Scan running in background", Toast.LENGTH_SHORT).show();
    }

    private void confirmBackgroundRefresh() {
        new AlertDialog.Builder(this).setTitle("Refresh actor index?").setMessage("v0.5 will delete this project's old index and rebuild it with Identity Embedding v2.")
                .setNegativeButton("Cancel", null).setPositiveButton("Refresh", (d,w) -> startBackgroundScan(true)).show();
    }

    private void pollBackgroundScan() {
        boolean running = prefs().getBoolean("bg_scan_running", false);
        if (running) setProgress(prefs().getInt("bg_scan_progress", 0));
        if (lastBgRunning && !running) { reloadScanState(); setProgress(0); setStatus(prefs().getString("bg_scan_status", "Scan finished")); }
        lastBgRunning = running;
    }

    private void reloadScanState() {
        try {
            Field f = MainActivity.class.getDeclaredField("scanState"); f.setAccessible(true); f.set(this, null);
            Method m = MainActivity.class.getDeclaredMethod("loadScanStateAsync", Runnable.class); m.setAccessible(true);
            m.invoke(this, (Runnable) () -> {});
        } catch (Throwable ignored) {}
    }
    private void invokeStartBaseScan(boolean refresh) {
        try { Method m = MainActivity.class.getDeclaredMethod("startActorScan", boolean.class); m.setAccessible(true); m.invoke(this, refresh); }
        catch (Throwable ignored) {}
    }

    private void applyScanProfile(String profile, boolean toast) {
        SharedPreferences.Editor e = prefs().edit();
        if ("Fast".equals(profile)) { e.putLong("sample_ms", 2200L).putInt("scan_max_side", 560).putFloat("cluster_threshold", 0.55f); }
        else if ("Accurate".equals(profile)) { e.putLong("sample_ms", 650L).putInt("scan_max_side", 960).putFloat("cluster_threshold", 0.60f); }
        else if ("Auto".equals(profile)) {
            int cores = Runtime.getRuntime().availableProcessors();
            if (cores >= 8) e.putLong("sample_ms", 850L).putInt("scan_max_side", 800).putFloat("cluster_threshold", 0.59f);
            else e.putLong("sample_ms", 1400L).putInt("scan_max_side", 640).putFloat("cluster_threshold", 0.57f);
        } else { e.putLong("sample_ms", 1200L).putInt("scan_max_side", 720).putFloat("cluster_threshold", 0.58f); }
        e.apply(); if (toast) Toast.makeText(this, profile + " scan profile saved • Refresh Scan to rebuild existing index", Toast.LENGTH_SHORT).show();
    }

    private void trimCache() {
        helperWorker.execute(() -> {
            int mb = prefs().getInt("cache_limit_mb_v050", 2048);
            ActorScanStore store = new ActorScanStore(this); long after = store.trimToBytes(mb * 1024L * 1024L);
            runOnUiThread(() -> Toast.makeText(this, String.format(Locale.US, "Cache now %.1f MB", after / 1048576.0), Toast.LENGTH_SHORT).show());
        });
    }

    private void benchmarkPhone() {
        Toast.makeText(this, "Benchmark running…", Toast.LENGTH_SHORT).show();
        helperWorker.execute(() -> {
            long start = System.nanoTime(); int loops = 0;
            Bitmap b = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888); Canvas canvas = new Canvas(b); Paint paint = new Paint();
            paint.setColor(Color.rgb(160, 135, 120)); canvas.drawRect(0,0,640,640,paint);
            Rect face = new Rect(170, 120, 470, 500);
            try (FaceEngine engine = new FaceEngine()) {
                for (int i = 0; i < 18; i++) { engine.descriptor(b, face); engine.quality(b, face); loops++; }
            } catch (Throwable ignored) {}
            b.recycle();
            double ms = (System.nanoTime() - start) / 1_000_000.0 / Math.max(1, loops);
            int cores = Runtime.getRuntime().availableProcessors();
            String rec = (ms < 18 && cores >= 8) ? "Accurate" : (ms < 35 ? "Balanced" : "Fast");
            applyScanProfile(rec, false); prefs().edit().putString("scan_profile_v050", rec).putFloat("benchmark_ms_v050", (float) ms).apply();
            String codecs = supportsHevcEncoder() ? "HEVC available" : "AVC recommended";
            runOnUiThread(() -> new AlertDialog.Builder(this).setTitle("Phone benchmark")
                    .setMessage(String.format(Locale.US, "Identity embedding: %.1f ms/face\nCPU cores: %d\n%s\nRecommended scan profile: %s", ms, cores, codecs, rec))
                    .setPositiveButton("Use recommendation", null).show());
        });
    }

    private boolean supportsHevcEncoder() {
        try {
            for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos()) if (info.isEncoder())
                for (String t : info.getSupportedTypes()) if ("video/hevc".equalsIgnoreCase(t)) return true;
        } catch (Throwable ignored) {}
        return false;
    }

    private void showBeforeAfterPreview() {
        helperWorker.execute(() -> {
            try {
                ActorScanStore.Cluster actor = selectedActor(); ArrayList<Uri> videos = videos();
                if (actor == null || videos.isEmpty() || actor.hits.isEmpty()) throw new IllegalStateException("Scan and select an actor first");
                ActorScanStore.Hit hit = actor.hits.get(actor.hits.size() / 2);
                if (hit.videoIndex < 0 || hit.videoIndex >= videos.size()) hit = actor.hits.get(0);
                Uri uri = videos.get(Math.max(0, Math.min(videos.size()-1, hit.videoIndex)));
                MediaMetadataRetriever mr = new MediaMetadataRetriever(); mr.setDataSource(this, uri);
                Bitmap original = mr.getFrameAtTime(hit.t * 1000L, MediaMetadataRetriever.OPTION_CLOSEST); mr.release();
                if (original == null) throw new IllegalStateException("Preview frame unavailable");
                Bitmap after = cropPreview(original, hit.cx, hit.cy, prefs().getString("output_aspect", "1:1"));
                Bitmap originalSmall = fit(original, 900); if (originalSmall != original) original.recycle();
                runOnUiThread(() -> showPreviewDialog(originalSmall, after));
            } catch (Throwable e) { runOnUiThread(() -> Toast.makeText(this, "Preview failed: " + e.getMessage(), Toast.LENGTH_LONG).show()); }
        });
    }

    private Bitmap cropPreview(Bitmap src, float cx, float cy, String aspect) {
        float target = "9:16".equals(aspect) ? 9f/16f : ("4:5".equals(aspect) ? 4f/5f : ("16:9".equals(aspect) ? 16f/9f : 1f));
        int w = src.getWidth(), h = src.getHeight(); int cw = w, ch = h;
        if (w / (float) h > target) cw = Math.max(2, Math.round(h * target)); else ch = Math.max(2, Math.round(w / target));
        int x = Math.round(cx * w - cw/2f), y = Math.round(cy * h - ch * 0.42f);
        x = Math.max(0, Math.min(w - cw, x)); y = Math.max(0, Math.min(h - ch, y));
        Bitmap crop = Bitmap.createBitmap(src, x, y, cw, ch); Bitmap fit = fit(crop, 900); if (fit != crop) crop.recycle(); return fit;
    }
    private Bitmap fit(Bitmap src, int maxSide) {
        int m = Math.max(src.getWidth(), src.getHeight()); if (m <= maxSide) return src;
        float s = maxSide/(float)m; return Bitmap.createScaledBitmap(src, Math.max(2,Math.round(src.getWidth()*s)), Math.max(2,Math.round(src.getHeight()*s)), true);
    }
    private void showPreviewDialog(Bitmap original, Bitmap after) {
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(8),dp(8),dp(8),dp(8));
        box.addView(label("Original", 14, true)); ImageView a = new ImageView(this); a.setAdjustViewBounds(true); a.setImageBitmap(original); box.addView(a);
        box.addView(label("Smart crop preview", 14, true)); ImageView b = new ImageView(this); b.setAdjustViewBounds(true); b.setImageBitmap(after); box.addView(b);
        ScrollView sv = new ScrollView(this); sv.addView(box);
        new AlertDialog.Builder(this).setTitle("Before / After").setView(sv).setPositiveButton("Close", (d,w) -> { try { original.recycle(); after.recycle(); } catch(Throwable ignored){} }).show();
    }

    @SuppressWarnings("unchecked") private ArrayList<Uri> videos() throws Exception { Field f=MainActivity.class.getDeclaredField("videos"); f.setAccessible(true); return new ArrayList<>((ArrayList<Uri>)f.get(this)); }
    private ActorScanStore.Cluster selectedActor() throws Exception { Field s=MainActivity.class.getDeclaredField("scanState"); s.setAccessible(true); ActorScanStore.ScanState st=(ActorScanStore.ScanState)s.get(this); return st==null?null:st.byId(selectedClusterId()); }
    private int selectedClusterId() { try { Field f=MainActivity.class.getDeclaredField("selectedClusterId"); f.setAccessible(true); return f.getInt(this); } catch(Throwable e){return -1;} }
    private void setSelectedClusterId(int id) { try { Field f=MainActivity.class.getDeclaredField("selectedClusterId"); f.setAccessible(true); f.setInt(this,id); } catch(Throwable ignored){} }
    private boolean reflectBoolean(Class<?> type,String name,boolean def){try{Field f=type.getDeclaredField(name);f.setAccessible(true);return f.getBoolean(this);}catch(Throwable e){return def;}}
    private void invokeBase(String name) { try { Method m=MainActivity.class.getDeclaredMethod(name);m.setAccessible(true);m.invoke(this);}catch(Throwable ignored){} }
    private void setStatus(String s) { runOnUiThread(() -> { try {Field f=MainActivity.class.getDeclaredField("statusBar");f.setAccessible(true);((TextView)f.get(this)).setText(s);}catch(Throwable ignored){} }); }
    private void setProgress(int p) { runOnUiThread(() -> { try {Field f=MainActivity.class.getDeclaredField("progress");f.setAccessible(true);((ProgressBar)f.get(this)).setProgress(Math.max(0,Math.min(100,p)));}catch(Throwable ignored){} }); }

    @Override protected void onDestroy() {
        destroyed = true; ui.removeCallbacksAndMessages(null); helperWorker.shutdownNow(); super.onDestroy();
    }
}
