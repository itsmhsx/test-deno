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

/**
 * v0.3.1 launcher/fix layer.
 *
 * v0.3.0 intentionally separated navigation from execution, but that made the
 * Scan Actors buttons look like they did nothing: the first tap only opened the
 * scan page and a second tap on Start / Resume Scan was required. This launcher
 * keeps the tested v0.3 scan/export engine intact and rewires the obvious Scan
 * entry points so one tap both opens the scan page and starts/resumes scanning.
 *
 * It also provides immediate visible feedback before the worker has decoded its
 * first frame, which is important on long phone videos where MediaMetadataRetriever
 * may take several seconds to seek the first frame.
 */
public class MainActivityV031 extends MainActivity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Set<View> wired = Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean destroyed;

    private final Runnable rewireLoop = new Runnable() {
        @Override public void run() {
            if (destroyed) return;
            try { wireScanButtons(getWindow().getDecorView()); } catch (Throwable ignored) {}
            ui.postDelayed(this, 700);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);

        // Better discovery defaults for a phone, but never overwrite a user's
        // existing choices.
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
            // Open the status page first so the user immediately sees the
            // progress bar/status area instead of wondering if the tap worked.
            Method show = MainActivity.class.getDeclaredMethod("showScan");
            show.setAccessible(true);
            show.invoke(this);
        } catch (Throwable ignored) {}

        setBaseStatus("Starting actor scan… please wait for the first frame");
        Toast.makeText(this, "Actor Scan started", Toast.LENGTH_SHORT).show();

        ui.postDelayed(() -> {
            try {
                Field running = MainActivity.class.getDeclaredField("running");
                running.setAccessible(true);
                boolean isRunning = running.getBoolean(this);
                if (isRunning) {
                    Toast.makeText(this, "Scan is already running", Toast.LENGTH_SHORT).show();
                    return;
                }

                Method start = MainActivity.class.getDeclaredMethod("startActorScan", boolean.class);
                start.setAccessible(true);
                start.invoke(this, false);

                // The original screen can be rebuilt by cache loading; rewire any
                // newly-created Scan button immediately.
                ui.postDelayed(() -> {
                    try { wireScanButtons(getWindow().getDecorView()); } catch (Throwable ignored) {}
                }, 250);
            } catch (Throwable e) {
                setBaseStatus("Could not start scan: " + e.getClass().getSimpleName());
                Toast.makeText(this, "Scan start failed: " + e.getClass().getSimpleName(), Toast.LENGTH_LONG).show();
            }
        }, 120);
    }

    private void setBaseStatus(String text) {
        try {
            Field f = MainActivity.class.getDeclaredField("statusBar");
            f.setAccessible(true);
            Object o = f.get(this);
            if (o instanceof android.widget.TextView) {
                ((android.widget.TextView) o).setText(text);
            }
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
