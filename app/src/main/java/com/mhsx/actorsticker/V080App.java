package com.mhsx.actorsticker;

import android.app.Application;
import android.content.Context;

/** Process-wide app context plus v0.9 crash/memory recovery hooks. */
public final class V080App extends Application {
    private static volatile Context app;
    @Override public void onCreate() {
        super.onCreate();
        app = getApplicationContext();
        V090Diagnostics.install(this);
        try { V090RuntimeGuard.check(this); } catch (Throwable ignored) {}
    }
    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        V070ThumbnailLoader.trimMemory(level);
        if (level >= TRIM_MEMORY_RUNNING_LOW) getSharedPreferences("actor_sticker", MODE_PRIVATE).edit().putBoolean("v090_low_memory", true).apply();
    }
    @Override public void onLowMemory() {
        super.onLowMemory();
        V070ThumbnailLoader.clear();
        getSharedPreferences("actor_sticker", MODE_PRIVATE).edit().putBoolean("v090_low_memory", true).apply();
    }
    static Context context() { return app; }
}
