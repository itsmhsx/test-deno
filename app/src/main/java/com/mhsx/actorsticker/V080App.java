package com.mhsx.actorsticker;

import android.app.Application;
import android.content.Context;

/** Process-wide application context used by the bundled offline v0.8 ML runtime. */
public final class V080App extends Application {
    private static volatile Context app;
    @Override public void onCreate() {
        super.onCreate();
        app = getApplicationContext();
    }
    static Context context() { return app; }
}
