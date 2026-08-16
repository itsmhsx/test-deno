package com.mhsx.actorsticker;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;

final class V050ExportPolicy {
    private V050ExportPolicy() {}

    static int requestedBitrate(int shortSide, SharedPreferences prefs) {
        String profile = prefs.getString("quality_profile_v050", "Maximum");
        if ("Fast".equals(profile)) {
            if (shortSide >= 1400) return 10_000_000;
            if (shortSide >= 900) return 7_000_000;
            return 4_500_000;
        }
        if ("Balanced".equals(profile)) {
            if (shortSide >= 2000) return 24_000_000;
            if (shortSide >= 1400) return 18_000_000;
            if (shortSide >= 1000) return 13_000_000;
            if (shortSide >= 700) return 9_000_000;
            return 6_000_000;
        }
        // Maximum: preserve source crop resolution and ask the hardware encoder
        // for a high bitrate; MainActivityV040 already retries with safe defaults.
        if (shortSide >= 2000) return 48_000_000;
        if (shortSide >= 1400) return 34_000_000;
        if (shortSide >= 1000) return 28_000_000;
        if (shortSide >= 700) return 18_000_000;
        return 10_000_000;
    }

    static String normalizedCodec(SharedPreferences prefs) {
        String requested = prefs.getString("video_codec_v050", "Auto");
        if ("H.265/HEVC".equals(requested) && supportsEncoder("video/hevc")) return "HEVC";
        if ("H.264/AVC".equals(requested)) return "AVC";
        return "AUTO";
    }

    static boolean supportsEncoder(String mime) {
        try {
            for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos()) {
                if (!info.isEncoder()) continue;
                for (String t : info.getSupportedTypes()) if (mime.equalsIgnoreCase(t)) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }
}
