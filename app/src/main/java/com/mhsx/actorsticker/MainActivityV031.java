package com.mhsx.actorsticker;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** v0.3.1 scan-start and live-progress compatibility layer. */
public class MainActivityV031 extends MainActivity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Set<View> wired = Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean destroyed;

    private final Runnable rewireLoop = new Runnable() {
        @Override public void run() {
            if (destroyed) return;
            try { wireScanButtons(getWindow().getDecorView()); } catch (Throwable ignored) {}
            try { refreshLiveScanStatus(); } catch (Throwable ignored) {}
            ui.postDelayed(this, 700);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            if (!getSharedPreferences("actor_sticker", MODE_PRIVATE).contains("sample_ms")) {
                getSharedPreferences("actor_sticker", MODE_PRIVATE).edit()
                        .putLong("sample_ms", 1500L)
                        .putFloat("cluster_threshold", 0.52f)
                        .putInt("scan_max_side", 640)
                        .apply();
            }
        } catch (Throwable ignored) {}

        ui.post(() -> {
            try { wireScanButtons(getWindow().getDecorView()); } catch (Throwable ignored) {}
        });
        ui.postDelayed(rewireLoop, 700);
    }

    private void wireScanButtons(View root) {
        if (root == null) return;
        if (root instanceof Button) {
            Button b = (Button) root;
            String label = String.valueOf(b.getText()).trim();
            if (("Scan Actors".equalsIgnoreCase(label) ||
                    "1. Scan actors".equalsIgnoreCase(label)) && !wired.contains(b)) {
                wired.add(b);
                b.setText("▶ Scan Actors NOW");
                b.setOnClickListener(v -> openAndStartScan());
            }
            return;
        }
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) wireScanButtons(g.getChildAt(i));
        }
    }

    private void openAndStartScan() {
        try {
            Method show = MainActivity.class.getDeclaredMethod("showScan");
            show.setAccessible(true);
            show.invoke(this);
        } catch (Throwable ignored) {}

        setBaseStatus("Starting actor scan… preparing video decoder");
        Toast.makeText(this, "Actor Scan started", Toast.LENGTH_SHORT).show();

        ui.postDelayed(() -> {
            try {
                Field running = MainActivity.class.getDeclaredField("running");
                running.setAccessible(true);
                if (running.getBoolean(this)) {
                    Toast.makeText(this, "Scan is already running", Toast.LENGTH_SHORT).show();
                    return;
                }
                Method start = MainActivity.class.getDeclaredMethod("startActorScan", boolean.class);
                start.setAccessible(true);
                start.invoke(this, false);
                ui.postDelayed(() -> {
                    try { wireScanButtons(getWindow().getDecorView()); } catch (Throwable ignored) {}
                }, 250);
            } catch (Throwable e) {
                setBaseStatus("Could not start scan: " + e.getClass().getSimpleName());
                Toast.makeText(this, "Scan start failed: " + e.getClass().getSimpleName(), Toast.LENGTH_LONG).show();
            }
        }, 120);
    }

    private void refreshLiveScanStatus() throws Exception {
        Field runningField = MainActivity.class.getDeclaredField("running");
        runningField.setAccessible(true);
        if (!runningField.getBoolean(this)) return;

        Field pageField = MainActivity.class.getDeclaredField("currentPage");
        pageField.setAccessible(true);
        Object page = pageField.get(this);
        if (!"scan".equals(String.valueOf(page))) return;

        Field stateField = MainActivity.class.getDeclaredField("scanState");
        stateField.setAccessible(true);
        Object obj = stateField.get(this);
        if (!(obj instanceof ActorScanStore.ScanState)) {
            setBaseStatus("Actor Scan running • preparing index / first frame…");
            return;
        }

        ActorScanStore.ScanState s = (ActorScanStore.ScanState) obj;
        int videoNo = Math.max(1, s.videoIndex + 1);
        int stable = 0;
        int detections = 0;
        for (ActorScanStore.Cluster c : s.clusters) {
            detections += c.count;
            if (c.count >= 2) stable++;
        }
        setBaseStatus("Actor Scan RUNNING • video " + videoNo + " • " +
                String.format(java.util.Locale.US, "%.1fs", s.nextMs / 1000f) +
                " • faces " + detections + " • groups " + stable);
    }

    private void setBaseStatus(String text) {
        try {
            Field f = MainActivity.class.getDeclaredField("statusBar");
            f.setAccessible(true);
            Object o = f.get(this);
            if (o instanceof android.widget.TextView) ((android.widget.TextView) o).setText(text);
        } catch (Throwable ignored) {}
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        ui.postDelayed(() -> {
            try { wireScanButtons(getWindow().getDecorView()); } catch (Throwable ignored) {}
        }, 150);
    }

    @Override protected void onResume() {
        super.onResume();
        ui.postDelayed(() -> {
            try { wireScanButtons(getWindow().getDecorView()); } catch (Throwable ignored) {}
        }, 120);
    }

    @Override protected void onDestroy() {
        destroyed = true;
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
