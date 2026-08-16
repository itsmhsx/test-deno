package com.mhsx.actorsticker;

import android.app.Activity;
import android.content.SharedPreferences;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.net.Uri;
import android.os.Build;

import androidx.media3.common.Effect;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.Presentation;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.Transformer;

import java.io.File;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@UnstableApi
final class V070CodecAdvisor {
    static final class Benchmark {
        final long avcMs, hevcMs; final String best;
        Benchmark(long a,long h,String b){avcMs=a;hevcMs=h;best=b;}
    }
    private V070CodecAdvisor() {}

    static String bestCodec(SharedPreferences p) {
        String saved=p.getString("codec_best_v070","");
        if("HEVC".equals(saved)&&hasHardware("video/hevc"))return "HEVC";
        if("AVC".equals(saved)&&hasHardware("video/avc"))return "AVC";
        if(hasHardware("video/hevc"))return "HEVC";
        return "AVC";
    }

    static boolean hasHardware(String mime) {
        try {
            for(MediaCodecInfo i:new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos()){
                if(!i.isEncoder())continue; boolean supports=false;
                for(String t:i.getSupportedTypes())if(mime.equalsIgnoreCase(t)){supports=true;break;}
                if(!supports)continue;
                if(Build.VERSION.SDK_INT>=29){if(i.isHardwareAccelerated()&&!i.isSoftwareOnly())return true;}
                else {String n=i.getName().toLowerCase();if(!(n.startsWith("omx.google")||n.startsWith("c2.android")))return true;}
            }
        }catch(Throwable ignored){}
        return false;
    }

    static String hardwareSummary(){return "AVC "+(hasHardware("video/avc")?"HW":"SW")+" • HEVC "+(hasHardware("video/hevc")?"HW":"SW");}

    static Benchmark benchmark(Activity activity, Uri source, SharedPreferences prefs) throws Exception {
        long avc=runOne(activity,source,MimeTypes.VIDEO_H264,"avc");
        long hevc=hasHardware("video/hevc")?runOne(activity,source,MimeTypes.VIDEO_H265,"hevc"):Long.MAX_VALUE;
        String best=(hevc<Long.MAX_VALUE&&hevc<avc*1.12)?"HEVC":"AVC";
        prefs.edit().putLong("codec_bench_avc_ms_v070",avc==Long.MAX_VALUE?-1:avc)
                .putLong("codec_bench_hevc_ms_v070",hevc==Long.MAX_VALUE?-1:hevc)
                .putString("codec_best_v070",best).apply();
        return new Benchmark(avc,hevc,best);
    }

    private static long runOne(Activity activity,Uri source,String mime,String tag)throws Exception{
        File out=new File(activity.getCacheDir(),"codec_bench_v070_"+tag+".mp4");if(out.exists())out.delete();
        MediaItem media=new MediaItem.Builder().setUri(source).setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder().setStartPositionMs(0).setEndPositionMs(2500).build()).build();
        EditedMediaItem edited=new EditedMediaItem.Builder(media).setEffects(new Effects(Collections.emptyList(),Collections.<Effect>singletonList(Presentation.createForShortSide(480)))).build();
        CountDownLatch latch=new CountDownLatch(1);AtomicReference<Throwable> err=new AtomicReference<>();
        long start=System.nanoTime();
        activity.runOnUiThread(()->{
            try{
                Transformer t=new Transformer.Builder(activity).setVideoMimeType(mime).addListener(new Transformer.Listener(){
                    @Override public void onCompleted(Composition c,ExportResult r){latch.countDown();}
                    @Override public void onError(Composition c,ExportResult r,ExportException e){err.set(e);latch.countDown();}
                }).build();
                t.start(edited,out.getAbsolutePath());
            }catch(Throwable e){err.set(e);latch.countDown();}
        });
        if(!latch.await(90,TimeUnit.SECONDS)){if(out.exists())out.delete();return Long.MAX_VALUE;}
        long ms=Math.max(1,(System.nanoTime()-start)/1_000_000L);if(err.get()!=null||!out.isFile()||out.length()==0)ms=Long.MAX_VALUE;if(out.exists())out.delete();return ms;
    }
}
