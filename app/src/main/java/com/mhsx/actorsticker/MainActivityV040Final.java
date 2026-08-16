package com.mhsx.actorsticker;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/** Final visible v0.4 shell. Keeps the tested scan/tracking engine intact. */
public class MainActivityV040Final extends MainActivityV040 {
    private final Handler versionUi = new Handler(Looper.getMainLooper());
    private boolean versionDestroyed;

    private final Runnable labelLoop = new Runnable() {
        @Override public void run() {
            if (versionDestroyed) return;
            try { fixLabels(getWindow().getDecorView()); } catch (Throwable ignored) {}
            versionUi.postDelayed(this, 800);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        versionUi.post(labelLoop);
    }

    private void fixLabels(View v) {
        if (v == null) return;
        if (v instanceof TextView) {
            TextView t = (TextView) v;
            String s = String.valueOf(t.getText());
            if (s.startsWith("v0.3 •")) {
                t.setText("v0.4 • Scan first → Actor Gallery → smooth face tracking • selectable clip length • Max Quality");
            } else if (s.equals("App version: 0.3.0") || s.equals("App version: 0.3.1")) {
                t.setText("App version: 0.4.0");
            } else if (s.equals("Aspect ratio: 1:1 square (automatic actor-centered crop)")) {
                t.setText("Default aspect: 1:1 • Smart Tracking follows the selected actor frame-by-frame");
            }
            return;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) fixLabels(g.getChildAt(i));
        }
    }

    @Override protected void onDestroy() {
        versionDestroyed = true;
        versionUi.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
