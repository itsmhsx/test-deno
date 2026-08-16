package com.mhsx.actorsticker;

import android.content.*;

/** Central v0.9 state/work policy controller. New stability screens use this instead of scattered UI flags. */
final class V090AppController {
    final Context app; final SharedPreferences prefs; final V080ExportQueue queue;
    V090AppController(Context c){app=c.getApplicationContext();prefs=app.getSharedPreferences("actor_sticker",Context.MODE_PRIVATE);queue=new V080ExportQueue(app);applyUpgradeDefaults();applySafeMode();}
    private void applyUpgradeDefaults(){if(!prefs.getBoolean("v090_defaults_migrated",false)){prefs.edit()
            .putBoolean("v090_defaults_migrated",true)
            .putBoolean("v090_face_follow_enabled",false)
            .putBoolean("smart_tracking",false)
            .putBoolean("dense_tracking_v060",false)
            .putBoolean("dynamic_zoom_v050",false)
            .putBoolean("cinematic_follow_v090",false)
            .putBoolean("manual_path_v090",false)
            .putString("tracking_motion_v061","Fixed actor average")
            .putInt("tracking_deadzone_v061",16)
            .putBoolean("job_watchdog_v090",true)
            .putBoolean("quality_gate_v090",true)
            .putBoolean("thermal_adaptive_v090",true)
            .putBoolean("memory_guard_v090",true)
            .apply();}}
    void applySafeMode(){if(!prefs.getBoolean("safe_mode_v090",false))return;prefs.edit().putBoolean("v090_face_follow_enabled",false).putBoolean("smart_tracking",false).putBoolean("dense_tracking_v060",false).putBoolean("dynamic_zoom_v050",false).putBoolean("cinematic_follow_v090",false).putString("tracking_motion_v061","Fixed actor average").putString("video_codec_v050","H.264/AVC").putString("speed_mode_v070","Quick").apply();}
    boolean faceFollow(){return prefs.getBoolean("v090_face_follow_enabled",false);}
    void setFaceFollow(boolean on){SharedPreferences.Editor e=prefs.edit().putBoolean("v090_face_follow_enabled",on).putBoolean("smart_tracking",on).putBoolean("dense_tracking_v060",on);if(on)e.putString("tracking_motion_v061",prefs.getBoolean("cinematic_follow_v090",false)?"Custom":"Slow");else e.putString("tracking_motion_v061","Fixed actor average").putBoolean("dynamic_zoom_v050",false).putBoolean("cinematic_follow_v090",false);e.apply();}
    void setSafeMode(boolean on){prefs.edit().putBoolean("safe_mode_v090",on).putInt("v090_crash_burst",0).apply();if(on)applySafeMode();}
    String queueSummary(){return "Pending "+queue.count(V080ExportQueue.PENDING)+" • Running "+queue.count(V080ExportQueue.RUNNING)+" • Stalled "+queue.count(V080ExportQueue.STALLED)+" • Failed "+queue.count(V080ExportQueue.FAILED)+" • Done "+queue.count(V080ExportQueue.DONE);}
}
