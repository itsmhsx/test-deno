package com.mhsx.actorsticker;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.net.Uri;

import java.util.Set;

/** Lightweight scene intelligence used by the verified v0.5 export build. */
final class V050SceneRefiner {
    private V050SceneRefiner() {}

    static long[] refine(Context context, Uri source, long startMs, long endMs, long sourceDurationMs, boolean exact) {
        long len = Math.max(0, endMs - startMs);
        if (len < 2000) return new long[]{startMs, endMs};
        MediaMetadataRetriever mr = new MediaMetadataRetriever();
        try {
            mr.setDataSource(context, source);
            long rs = nearestVisualCut(mr, startMs, Math.max(0, startMs - 700), Math.min(sourceDurationMs, startMs + 700));
            long re = nearestVisualCut(mr, endMs, Math.max(0, endMs - 700), Math.min(sourceDurationMs, endMs + 700));
            if (exact) {
                long shift = rs - startMs;
                startMs = clamp(startMs + shift, 0, Math.max(0, sourceDurationMs - len));
                endMs = Math.min(sourceDurationMs, startMs + len);
                startMs = Math.max(0, endMs - len);
            } else {
                if (Math.abs(rs - startMs) <= 650) startMs = rs;
                if (Math.abs(re - endMs) <= 650) endMs = re;
                if (endMs - startMs < 2000) { startMs = Math.max(0, endMs - 2000); }
            }
        } catch (Throwable ignored) {
        } finally { try { mr.release(); } catch (Throwable ignored) {} }

        // Align to nearby compressed audio sample boundaries. This avoids cutting
        // through a codec packet; it is not semantic speech recognition.
        long[] audio = alignAudioSamples(context, source, startMs, endMs);
        if (audio != null) {
            if (exact) {
                long shift = audio[0] - startMs;
                startMs = clamp(startMs + shift, 0, Math.max(0, sourceDurationMs - len));
                endMs = Math.min(sourceDurationMs, startMs + len);
            } else {
                if (Math.abs(audio[0] - startMs) <= 260) startMs = audio[0];
                if (Math.abs(audio[1] - endMs) <= 260) endMs = audio[1];
            }
        }
        return new long[]{Math.max(0, startMs), Math.min(sourceDurationMs, Math.max(startMs + 1, endMs))};
    }

    static long perceptualHash(Context context, Uri source, long tMs) {
        MediaMetadataRetriever mr = new MediaMetadataRetriever();
        Bitmap b = null, s = null;
        try {
            mr.setDataSource(context, source);
            b = mr.getFrameAtTime(Math.max(0, tMs) * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (b == null) return Long.MIN_VALUE;
            s = Bitmap.createScaledBitmap(b, 8, 8, true);
            double mean = 0; int[] lum = new int[64]; int k = 0;
            for (int y=0;y<8;y++) for(int x=0;x<8;x++){int c=s.getPixel(x,y); int l=(Color.red(c)*3+Color.green(c)*6+Color.blue(c))/10; lum[k++]=l; mean+=l;}
            mean /= 64.0; long hash=0;
            for(int i=0;i<64;i++) if(lum[i]>=mean) hash |= (1L<<i);
            return hash;
        } catch(Throwable e){ return Long.MIN_VALUE; }
        finally { if(s!=null&&s!=b&&!s.isRecycled())s.recycle(); if(b!=null&&!b.isRecycled())b.recycle(); try{mr.release();}catch(Throwable ignored){} }
    }

    static boolean isNearDuplicate(long hash, Set<Long> existing, int maxHamming) {
        if (hash == Long.MIN_VALUE) return false;
        for (Long h : existing) if (h != null && h != Long.MIN_VALUE && Long.bitCount(hash ^ h) <= maxHamming) return true;
        return false;
    }

    static float visualQuality(Context context, Uri source, long tMs) {
        MediaMetadataRetriever mr = new MediaMetadataRetriever(); Bitmap b=null,s=null;
        try {
            mr.setDataSource(context,source); b=mr.getFrameAtTime(Math.max(0,tMs)*1000L,MediaMetadataRetriever.OPTION_CLOSEST);
            if(b==null)return 0.5f; s=Bitmap.createScaledBitmap(b,48,48,true);
            double sum=0,sum2=0,edge=0; int n=0;
            for(int y=1;y<47;y+=2)for(int x=1;x<47;x+=2){
                float m=lum(s.getPixel(x,y)); sum+=m; sum2+=m*m;
                edge+=Math.abs(lum(s.getPixel(x+1,y))-lum(s.getPixel(x-1,y)))+Math.abs(lum(s.getPixel(x,y+1))-lum(s.getPixel(x,y-1))); n++;
            }
            double avg=sum/Math.max(1,n), var=Math.max(0,sum2/Math.max(1,n)-avg*avg);
            float brightness=(float)(1.0-Math.min(1.0,Math.abs(avg-128.0)/140.0));
            float contrast=(float)Math.min(1.0,Math.sqrt(var)/58.0);
            float sharp=(float)Math.min(1.0,(edge/Math.max(1,n))/95.0);
            return Math.max(0f,Math.min(1f,0.25f*brightness+0.25f*contrast+0.50f*sharp));
        }catch(Throwable e){return 0.5f;}
        finally{if(s!=null&&s!=b&&!s.isRecycled())s.recycle();if(b!=null&&!b.isRecycled())b.recycle();try{mr.release();}catch(Throwable ignored){}}
    }

    private static long nearestVisualCut(MediaMetadataRetriever mr,long target,long from,long to){
        long best=target; double bestScore=0; Bitmap prev=null;
        try{
            for(long t=from;t<=to;t+=175){Bitmap cur=null,small=null;try{
                cur=mr.getFrameAtTime(t*1000L,MediaMetadataRetriever.OPTION_CLOSEST_SYNC); if(cur==null)continue;
                small=Bitmap.createScaledBitmap(cur,12,12,true); if(prev!=null){double d=frameDifference(prev,small);double penalty=Math.abs(t-target)/900.0;double score=d-penalty*0.12;if(score>bestScore&&d>0.13){bestScore=score;best=t;}}
                if(prev!=null&&!prev.isRecycled())prev.recycle(); prev=small; small=null;
            }finally{if(small!=null&&!small.isRecycled())small.recycle();if(cur!=null&&!cur.isRecycled())cur.recycle();}}
        }finally{if(prev!=null&&!prev.isRecycled())prev.recycle();}
        return best;
    }
    private static double frameDifference(Bitmap a,Bitmap b){double d=0;for(int y=0;y<12;y++)for(int x=0;x<12;x++)d+=Math.abs(lum(a.getPixel(x,y))-lum(b.getPixel(x,y)))/255.0;return d/144.0;}

    private static long[] alignAudioSamples(Context context,Uri source,long startMs,long endMs){
        MediaExtractor ex=new MediaExtractor();
        try{
            ex.setDataSource(context,source,null); int audio=-1;
            for(int i=0;i<ex.getTrackCount();i++){MediaFormat f=ex.getTrackFormat(i);String mime=f.getString(MediaFormat.KEY_MIME);if(mime!=null&&mime.startsWith("audio/")){audio=i;break;}}
            if(audio<0)return null; ex.selectTrack(audio);
            long s=nearestSample(ex,startMs*1000L); long e=nearestSample(ex,endMs*1000L);
            return new long[]{s/1000L,e/1000L};
        }catch(Throwable ignored){return null;}finally{try{ex.release();}catch(Throwable ignored){}}
    }
    private static long nearestSample(MediaExtractor ex,long targetUs){
        ex.seekTo(Math.max(0,targetUs),MediaExtractor.SEEK_TO_CLOSEST_SYNC); long a=ex.getSampleTime(); if(a<0)return targetUs;
        long best=a; long d=Math.abs(a-targetUs); for(int i=0;i<4;i++){if(!ex.advance())break;long t=ex.getSampleTime();if(t<0)break;long nd=Math.abs(t-targetUs);if(nd<d){d=nd;best=t;}}
        return best;
    }
    private static float lum(int c){return 0.299f*Color.red(c)+0.587f*Color.green(c)+0.114f*Color.blue(c);}
    private static long clamp(long x,long lo,long hi){return Math.max(lo,Math.min(hi,x));}
}
