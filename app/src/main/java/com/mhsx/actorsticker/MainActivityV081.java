package com.mhsx.actorsticker;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;

import androidx.media3.common.util.UnstableApi;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * v0.8.1 reliability shell.
 * - Actor Gallery is rendered from a safe snapshot and avoids the older injected
 *   confidence timeline path which can crash on malformed/partial actor data.
 * - Scan buttons start the background service directly and watchdog startup.
 */
@UnstableApi
public class MainActivityV081 extends MainActivityV080 {
    private final Handler ui81 = new Handler(Looper.getMainLooper());
    private final ExecutorService worker81 = Executors.newSingleThreadExecutor();
    private final Set<View> wired81 = Collections.newSetFromMap(new WeakHashMap<>());
    private volatile boolean dead81;
    private long scanStart81;

    private final Runnable loop81 = new Runnable() {
        @Override public void run() {
            if (dead81) return;
            try { rewire(getWindow().getDecorView()); fixVersion(getWindow().getDecorView()); }
            catch (Throwable ignored) {}
            ui81.postDelayed(this, 350L);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs().edit().putString("v081_health", "ready").apply();
        ui81.post(loop81);
    }

    @Override protected void onDestroy() {
        dead81 = true;
        ui81.removeCallbacksAndMessages(null);
        worker81.shutdownNow();
        super.onDestroy();
    }

    private SharedPreferences prefs() { return getSharedPreferences("actor_sticker", MODE_PRIVATE); }
    private int dp81(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private void fixVersion(View v) {
        if (v == null) return;
        if (v instanceof TextView) {
            TextView t = (TextView) v;
            String s = String.valueOf(t.getText());
            if (s.contains("v0.8.0")) t.setText(s.replace("v0.8.0", "v0.8.1"));
            else if (s.contains("v0.8") && !s.contains("v0.8.1")) t.setText(s.replace("v0.8", "v0.8.1"));
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) fixVersion(g.getChildAt(i));
        }
    }

    private void rewire(View v) {
        if (v == null) return;
        if (v instanceof Button) {
            Button b = (Button) v;
            if (!wired81.contains(b)) {
                String s = normalize(String.valueOf(b.getText()));
                if (isGalleryText(s)) {
                    wired81.add(b);
                    b.setOnClickListener(x -> showSafeActors());
                } else if (isScanStartText(s)) {
                    wired81.add(b);
                    b.setOnClickListener(x -> startReliableScan(false));
                } else if (isRefreshScanText(s)) {
                    wired81.add(b);
                    b.setOnClickListener(x -> new AlertDialog.Builder(this)
                            .setTitle("Refresh actor scan?")
                            .setMessage("The current actor index for this video set will be rebuilt from scratch.")
                            .setNegativeButton("Cancel", null)
                            .setPositiveButton("Refresh", (d,w) -> startReliableScan(true)).show());
                }
            }
            return;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g=(ViewGroup)v;
            for(int i=0;i<g.getChildCount();i++) rewire(g.getChildAt(i));
        }
    }

    private boolean isGalleryText(String s) {
        return s.equals("actor gallery") || s.equals("open actor gallery") || s.equals("2. open actor gallery")
                || s.contains("گالری بازیگر") || s.contains("oyuncu galer") || s.contains("галерея акт");
    }
    private boolean isScanStartText(String s) {
        return s.equals("scan actors") || s.contains("scan actors now") || s.contains("background scan / resume")
                || s.equals("start / resume scan") || s.equals("resume scan") || s.equals("1. scan actors")
                || s.contains("اسکن بازیگر") || s.contains("اسکن بازیگران") || s.contains("oyuncuları tara") || s.contains("скан акт");
    }
    private boolean isRefreshScanText(String s) {
        return s.equals("refresh full scan") || s.contains("اسکن کامل مجدد") || s.contains("tam taramayı yenile") || s.contains("полное пересканирование");
    }
    private String normalize(String s) { return s == null ? "" : s.trim().toLowerCase(Locale.ROOT); }

    private void startReliableScan(boolean refresh) {
        ArrayList<Uri> vs = videos81();
        if (vs.isEmpty()) { toast81("Add videos first"); return; }
        scanStart81 = System.currentTimeMillis();
        prefs().edit().putBoolean("bg_scan_running", true).putInt("bg_scan_progress", 1)
                .putString("bg_scan_status", "Starting Actor Scan service…")
                .putLong("bg_scan_updated", scanStart81).apply();
        try {
            Intent i = new Intent(this, BackgroundScanService.class)
                    .setAction(refresh ? BackgroundScanService.ACTION_REFRESH : BackgroundScanService.ACTION_START);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
            toast81("Actor Scan started");
            ui81.postDelayed(() -> scanWatchdog(refresh, scanStart81), 4500L);
        } catch (Throwable e) {
            prefs().edit().putBoolean("bg_scan_running", false).putString("bg_scan_status", "Background start failed • using direct scan").apply();
            invokeBase("startActorScan", new Class[]{boolean.class}, new Object[]{refresh});
        }
    }

    private void scanWatchdog(boolean refresh, long token) {
        if (dead81 || token != scanStart81) return;
        long updated = prefs().getLong("bg_scan_updated", 0L);
        boolean running = prefs().getBoolean("bg_scan_running", false);
        String status = prefs().getString("bg_scan_status", "");
        if ((!running || updated <= token) && !status.toLowerCase(Locale.ROOT).contains("complete")) {
            prefs().edit().putBoolean("bg_scan_running", false)
                    .putString("bg_scan_status", "Background scan did not start • direct fallback active").apply();
            toast81("Background scan fallback → direct scan");
            invokeBase("startActorScan", new Class[]{boolean.class}, new Object[]{refresh});
        }
    }

    private void showSafeActors() {
        setCurrentPage81("actors_safe_081");
        LinearLayout c = content81();
        if (c == null) return;
        c.removeAllViews();
        c.addView(label81("Actors • Safe Gallery v0.8.1", 20, true));
        c.addView(label81("Crash-safe actor browser • private thumbnails • learned offline identity", 12, false));
        ArrayList<Uri> vs = videos81();
        if (vs.isEmpty()) { c.addView(label81("Add videos and run Actor Scan first.", 14, false)); return; }

        ActorScanStore.ScanState state = scanState81();
        if (state == null) {
            c.addView(label81("Loading private actor index…", 14, false));
            Button scan = button81("▶ Scan Actors NOW");
            scan.setOnClickListener(v -> startReliableScan(false));
            c.addView(scan);
            loadStateForSafeGallery();
            return;
        }

        ArrayList<ActorScanStore.Cluster> actors = snapshotClusters(state);
        if (actors.isEmpty()) {
            c.addView(label81("No stable actor groups are available yet.", 14, false));
            Button scan = button81("Refresh Full Scan");
            scan.setOnClickListener(v -> startReliableScan(true));
            c.addView(scan);
            return;
        }

        actors.sort((a,b) -> Double.compare(scoreSafe(b), scoreSafe(a)));
        int selected = selectedId81();
        c.addView(label81("Groups: " + actors.size() + " • Selected: " + (selected < 0 ? "none" : "Actor " + (selected + 1)), 13, true));
        LinearLayout actions = new LinearLayout(this); actions.setOrientation(LinearLayout.HORIZONTAL);
        Button studio = button81("Open Studio"); studio.setOnClickListener(v -> invokeBase("showStudio", new Class[]{}, new Object[]{})); actions.addView(studio);
        Button reload = button81("Reload index"); reload.setOnClickListener(v -> { setScanState81(null); showSafeActors(); }); actions.addView(reload);
        c.addView(actions);

        int shown = 0;
        for (ActorScanStore.Cluster a : actors) {
            if (a == null || a.count <= 0) continue;
            try { addSafeActorCard(c, state, a, a.id == selected); }
            catch (Throwable e) { c.addView(label81("Skipped damaged Actor " + (a.id + 1), 12, false)); }
            if (++shown >= 36) break;
        }
        c.addView(label81("If an old v0.8 cache is damaged, use Refresh Full Scan once. Original videos are never changed.", 12, false));
    }

    private void loadStateForSafeGallery() {
        worker81.execute(() -> {
            try {
                String key = projectKey81();
                if (key.isEmpty()) return;
                ActorScanStore.ScanState s = new ActorScanStore(this).load(key);
                if (s != null) {
                    setScanState81(s);
                    if (selectedId81() < 0 && s.selectedClusterId >= 0) setSelectedId81(s.selectedClusterId);
                }
            } catch (Throwable ignored) {}
            runOnUiThread(() -> { if (!dead81 && "actors_safe_081".equals(currentPage81())) showSafeActors(); });
        });
    }

    private ArrayList<ActorScanStore.Cluster> snapshotClusters(ActorScanStore.ScanState s) {
        for (int attempt = 0; attempt < 3; attempt++) {
            try { return new ArrayList<>(s.clusters); }
            catch (Throwable ignored) { try { Thread.sleep(25L); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; } }
        }
        ArrayList<ActorScanStore.Cluster> out = new ArrayList<>();
        try { for (int i=0;i<s.clusters.size();i++){ActorScanStore.Cluster x=s.clusters.get(i);if(x!=null)out.add(x);} } catch (Throwable ignored) {}
        return out;
    }

    private void addSafeActorCard(LinearLayout root, ActorScanStore.ScanState state, ActorScanStore.Cluster a, boolean selected) {
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp81(10),dp81(8),dp81(10),dp81(8)); card.setBackgroundColor(selected ? 0xffdff6e5 : 0xffeef2f7);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1,-2); lp.setMargins(0,dp81(5),0,dp81(5)); card.setLayoutParams(lp);
        card.addView(label81((selected ? "✓ " : "") + "Actor " + (a.id+1) + " • " + a.count + " detections • quality " + Math.round(a.avgQuality()*100f) + "%", 14, true));

        LinearLayout thumbs = new LinearLayout(this); thumbs.setOrientation(LinearLayout.HORIZONTAL);
        int n = 0;
        for (String rel : new ArrayList<>(a.thumbs)) {
            if (rel == null || rel.isEmpty() || n >= 2) continue;
            File f = new ActorScanStore(this).thumbnailFile(state.projectKey, rel);
            if (!f.isFile()) continue;
            ImageView iv = new ImageView(this); iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumbs.addView(iv, new LinearLayout.LayoutParams(dp81(96),dp81(96)));
            V070ThumbnailLoader.load(iv, f, dp81(96));
            n++;
        }
        card.addView(thumbs);
        Button select = button81(selected ? "Selected actor" : "Select this actor");
        select.setOnClickListener(v -> selectSafeActor(state, a.id));
        card.addView(select);
        root.addView(card);
    }

    private void selectSafeActor(ActorScanStore.ScanState state, int id) {
        setSelectedId81(id); state.selectedClusterId = id;
        worker81.execute(() -> { try { new ActorScanStore(this).save(state); } catch (Throwable ignored) {} });
        showSafeActors();
    }

    private double scoreSafe(ActorScanStore.Cluster c) { try { return c.smartScore(); } catch (Throwable e) { return 0; } }
    private TextView label81(String s,int sp,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(sp);t.setTextColor(Color.rgb(25,32,45));if(bold)t.setTypeface(null,1);t.setPadding(dp81(6),dp81(5),dp81(6),dp81(5));return t;}
    private Button button81(String s){Button b=new Button(this);b.setText(s);b.setAllCaps(false);return b;}
    private void toast81(String s){Toast.makeText(this,s,Toast.LENGTH_SHORT).show();}

    @SuppressWarnings("unchecked") private ArrayList<Uri> videos81(){try{Field f=MainActivity.class.getDeclaredField("videos");f.setAccessible(true);return new ArrayList<>((ArrayList<Uri>)f.get(this));}catch(Throwable e){return new ArrayList<>();}}
    private LinearLayout content81(){try{Field f=MainActivity.class.getDeclaredField("content");f.setAccessible(true);return(LinearLayout)f.get(this);}catch(Throwable e){return null;}}
    private ActorScanStore.ScanState scanState81(){try{Field f=MainActivity.class.getDeclaredField("scanState");f.setAccessible(true);return(ActorScanStore.ScanState)f.get(this);}catch(Throwable e){return null;}}
    private void setScanState81(ActorScanStore.ScanState s){try{Field f=MainActivity.class.getDeclaredField("scanState");f.setAccessible(true);f.set(this,s);}catch(Throwable ignored){}}
    private int selectedId81(){try{Field f=MainActivity.class.getDeclaredField("selectedClusterId");f.setAccessible(true);return f.getInt(this);}catch(Throwable e){return-1;}}
    private void setSelectedId81(int id){try{Field f=MainActivity.class.getDeclaredField("selectedClusterId");f.setAccessible(true);f.setInt(this,id);}catch(Throwable ignored){}}
    private void setCurrentPage81(String s){try{Field f=MainActivity.class.getDeclaredField("currentPage");f.setAccessible(true);f.set(this,s);}catch(Throwable ignored){}}
    private String currentPage81(){try{Field f=MainActivity.class.getDeclaredField("currentPage");f.setAccessible(true);return String.valueOf(f.get(this));}catch(Throwable e){return"";}}
    private String projectKey81(){try{Field f=MainActivity.class.getDeclaredField("currentProjectKey");f.setAccessible(true);String k=(String)f.get(this);if(k!=null&&!k.isEmpty())return k;}catch(Throwable ignored){}try{Method m=MainActivity.class.getDeclaredMethod("computeProjectKey");m.setAccessible(true);return(String)m.invoke(this);}catch(Throwable e){return"";}}

    private void invokeBase(String name,Class<?>[] types,Object[] args){Class<?> q=getClass().getSuperclass();while(q!=null){try{Method m=q.getDeclaredMethod(name,types);m.setAccessible(true);m.invoke(this,args);return;}catch(NoSuchMethodException e){q=q.getSuperclass();}catch(Throwable e){return;}}}
}
