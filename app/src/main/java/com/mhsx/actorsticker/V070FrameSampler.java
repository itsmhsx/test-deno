package com.mhsx.actorsticker;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;

import androidx.media3.common.Effect;
import androidx.media3.common.MediaItem;
import androidx.media3.common.SeekParameters;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.Presentation;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.inspector.frame.FrameExtractor;

import java.util.Collections;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Fast v0.7 frame sampler. FrameExtractor keeps one decoder pipeline alive for
 * a whole video, seeks to sync frames and performs down-scaling in Media3's GL
 * pipeline before the Bitmap reaches face analysis.
 */
@UnstableApi
final class V070FrameSampler implements AutoCloseable {
    private final FrameExtractor extractor;
    private Future<FrameExtractor.Frame> prefetched;
    private long prefetchedMs = Long.MIN_VALUE;

    V070FrameSampler(Context context, Uri source, int analysisHeight) {
        int h = Math.max(240, Math.min(960, analysisHeight));
        MediaItem mediaItem = MediaItem.fromUri(source);
        extractor = new FrameExtractor.Builder(context, mediaItem)
                .setSeekParameters(SeekParameters.CLOSEST_SYNC)
                .setMediaCodecSelector(MediaCodecSelector.DEFAULT)
                .setEffects(Collections.<Effect>singletonList(Presentation.createForHeight(h)))
                .build();
    }

    void prefetch(long positionMs) {
        if (positionMs < 0) return;
        if (prefetched != null && prefetchedMs == positionMs) return;
        prefetchedMs = positionMs;
        prefetched = extractor.getFrame(positionMs);
    }

    Bitmap frameAt(long positionMs) throws Exception {
        Future<FrameExtractor.Frame> f;
        if (prefetched != null && prefetchedMs == positionMs) {
            f = prefetched;
            prefetched = null;
            prefetchedMs = Long.MIN_VALUE;
        } else {
            f = extractor.getFrame(Math.max(0L, positionMs));
        }
        FrameExtractor.Frame frame = f.get(15, TimeUnit.SECONDS);
        return frame == null ? null : frame.bitmap;
    }

    @Override public void close() {
        try { if (prefetched != null) prefetched.cancel(true); } catch (Throwable ignored) {}
        prefetched = null;
        try { extractor.close(); } catch (Throwable ignored) {}
    }
}
