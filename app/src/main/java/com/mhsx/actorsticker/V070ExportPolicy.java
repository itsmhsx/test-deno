package com.mhsx.actorsticker;

import android.content.SharedPreferences;

final class V070ExportPolicy {
    private V070ExportPolicy() {}

    static boolean isFixedCamera(SharedPreferences p) {
        String mode=p.getString("tracking_motion_v061","Slow");
        return "Fixed center".equals(mode)||"Fixed actor average".equals(mode)||"Manual fixed".equals(mode);
    }

    static boolean shouldDenseTrack(SharedPreferences p) {
        if(!p.getBoolean("dense_tracking_v060",true))return false;
        return !(p.getBoolean("fixed_skip_dense_v070",true)&&isFixedCamera(p));
    }
}
