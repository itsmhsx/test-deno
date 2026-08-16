package com.mhsx.actorsticker;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Build;
import android.os.SystemClock;

import java.nio.ByteBuffer;
import java.util.*;

/** Production diagnostics, accelerator tuning, codec benchmark and project regression suite. */
final class V100PerformanceKit {
    private V100PerformanceKit() {}

    static String tuneFaceBackend(Context c){
        SharedPreferences p=p(c);Bitmap b=Bitmap.createBitmap(160,160,Bitmap.Config.ARGB_8888);for(int y=0;y<160;y++)for(int x=0;x<160;x++){int v=(x*3+y*2)&255;b.setPixel(x,y,Color.rgb(v,Math.min(255,v+18),Math.max(0,v-12)));}
        Rect r=new Rect(16,12,144,148);String best="CPU";double bestMs=Double.MAX_VALUE;int bestThreads=2;
        try{
            int[] ts={1,2,4};for(int t:ts){p.edit().putString("face_backend_v100","CPU").putInt("face_threads_v090",t).apply();V080FaceEmbedder.configureThreads(t);double ms=benchEmbedding(b,r);if(ms<bestMs){bestMs=ms;best="CPU";bestThreads=t;}}
            if(Build.VERSION.SDK_INT>=27){p.edit().putString("face_backend_v100","NNAPI").putInt("face_threads_v090",2).apply();V080FaceEmbedder.configureThreads(2);double ms=benchEmbedding(b,r);if(ms<bestMs*0.95){bestMs=ms;best="NNAPI";bestThreads=2;}}
        }catch(Throwable ignored){}finally{if(!b.isRecycled())b.recycle();}
        p.edit().putString("face_backend_v100",best).putInt("face_threads_v090",bestThreads).putBoolean("backend_benchmark_done_v100",true).putFloat("backend_ms_v100",(float)bestMs).apply();V080FaceEmbedder.configureThreads(bestThreads);
        return String.format(Locale.US,"%s • %d thread(s) • %.1f ms/embedding",best,bestThreads,bestMs);
    }
    private static double benchEmbedding(Bitmap b,Rect r){for(int i=0;i<3;i++)V080FaceEmbedder.embedding(b,r);long s=System.nanoTime();int n=8;for(int i=0;i<n;i++)if(V080FaceEmbedder.embedding(b,r)==null)return 1e9;return(System.nanoTime()-s)/1e6/n;}

    static String analyzeCodecs(Context c){
        CodecBench avc=benchCodec("video/avc"),hevc=benchCodec("video/hevc");CodecBench best=pick(avc,hevc);if(best!=null)p(c).edit().putString("video_codec_v050",best.mime.equals("video/hevc")?"H.265/HEVC":"H.264/AVC").putString("codec_benchmark_v100",best.text()).apply();return "AVC: "+avc.text()+"\nHEVC: "+hevc.text()+"\nSelected: "+(best==null?"Auto":best.mime+" / "+best.name);
    }
    private static CodecBench pick(CodecBench a,CodecBench b){if(a.ok&&b.ok)return b.fps>a.fps*1.08?b:a;if(a.ok)return a;if(b.ok)return b;return null;}
    private static CodecBench benchCodec(String mime){
        CodecBench out=new CodecBench(mime);try{
            MediaCodecInfo info=findEncoder(mime);if(info==null){out.note="not available";return out;}out.name=info.getName();out.hardware=Build.VERSION.SDK_INT>=29&&info.isHardwareAccelerated();
            final int w=320,h=180,frames=30;MediaFormat f=MediaFormat.createVideoFormat(mime,w,h);f.setInteger(MediaFormat.KEY_BIT_RATE,800000);f.setInteger(MediaFormat.KEY_FRAME_RATE,30);f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,1);f.setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            MediaCodec mc=MediaCodec.createByCodecName(info.getName());long start=System.nanoTime();int queued=0,done=0;boolean eos=false;byte[]yuv=new byte[w*h*3/2];Arrays.fill(yuv,0,w*h,(byte)96);Arrays.fill(yuv,w*h,yuv.length,(byte)128);MediaCodec.BufferInfo bi=new MediaCodec.BufferInfo();
            try{mc.configure(f,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);mc.start();long deadline=SystemClock.elapsedRealtime()+7000;while(SystemClock.elapsedRealtime()<deadline&&!eos){if(queued<=frames){int ix=mc.dequeueInputBuffer(10000);if(ix>=0){ByteBuffer in=mc.getInputBuffer(ix);if(in!=null){in.clear();if(queued<frames){in.put(yuv);mc.queueInputBuffer(ix,0,yuv.length,queued*33333L,0);queued++;}else{mc.queueInputBuffer(ix,0,0,queued*33333L,MediaCodec.BUFFER_FLAG_END_OF_STREAM);queued++;}}}}int ox;while((ox=mc.dequeueOutputBuffer(bi,0))>=0){if(bi.size>0)done++;if((bi.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0)eos=true;mc.releaseOutputBuffer(ox,false);}}long ns=System.nanoTime()-start;out.fps=done/Math.max(0.001,ns/1e9);out.ok=done>=Math.min(20,frames);out.note=out.ok?"ok":"configure/throughput failed";}finally{try{mc.stop();}catch(Throwable ignored){}try{mc.release();}catch(Throwable ignored){}}
        }catch(Throwable e){out.note=e.getClass().getSimpleName();}return out;
    }
    private static MediaCodecInfo findEncoder(String mime){try{for(MediaCodecInfo i:new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos()){if(!i.isEncoder())continue;for(String t:i.getSupportedTypes())if(t.equalsIgnoreCase(mime)){if(Build.VERSION.SDK_INT<29||i.isHardwareAccelerated())return i;}}for(MediaCodecInfo i:new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos()){if(!i.isEncoder())continue;for(String t:i.getSupportedTypes())if(t.equalsIgnoreCase(mime))return i;}}catch(Throwable ignored){}return null;}

    static String accuracyReport(Context c,ActorScanStore.ScanState state,int actorId){if(state==null)return"No actor index";ActorScanStore.Cluster a=state.byId(actorId);if(a==null)return"Select an actor first";ArrayList<ActorScanStore.Hit>h=new ArrayList<>(a.hits);h.sort(Comparator.comparingLong(x->x.t));if(h.isEmpty())return"No track points";float thr=V100IdentityEngine.threshold(c,state.projectKey,actorId,0.58f);int high=0,low=0,switches=0;double score=0,jitter=0;int jn=0;ActorScanStore.Hit prev=null;for(ActorScanStore.Hit x:h){score+=x.score;if(x.score>=thr)high++;else low++;if(prev!=null&&x.videoIndex==prev.videoIndex){long dt=x.t-prev.t;double d=Math.hypot(x.cx-prev.cx,x.cy-prev.cy);if(dt>0&&dt<1400){jitter+=d;jn++;if(d>0.20&&x.score<0.82)switches++;}}prev=x;}double precision=high*100.0/h.size();double avg=score*100.0/h.size();double jit=jn==0?0:jitter*100/jn;return String.format(Locale.US,"Estimated actor precision: %.1f%%\nAverage identity confidence: %.1f%%\nLow-confidence frames: %d/%d\nPossible identity switches: %d\nTracking jitter: %.2f%% frame width\nNeeds review groups: %s",precision,avg,low,h.size(),switches,jit,V100IdentityEngine.needsReview(c,state.projectKey));}

    static String regression(Context c,ActorScanStore.ScanState state){
        ArrayList<String> pass=new ArrayList<>(),fail=new ArrayList<>();
        if(V080FaceEmbedder.available())pass.add("Face model");else fail.add("Face model");
        try{Bitmap b=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888);for(int y=0;y<128;y++)for(int x=0;x<128;x++)b.setPixel(x,y,Color.rgb((x+y)&255,(x*2)&255,(y*2)&255));float[]a=V080FaceEmbedder.embedding(b,new Rect(4,4,124,124)),d=V080FaceEmbedder.embedding(b,new Rect(4,4,124,124));b.recycle();if(a!=null&&d!=null&&FaceEngine.cosine(a,d)>0.995f)pass.add("Embedding repeatability");else fail.add("Embedding repeatability");}catch(Throwable e){fail.add("Embedding repeatability");}
        if(state!=null&&state.clusters!=null&&!state.clusters.isEmpty())pass.add("Actor index read");else fail.add("Actor index read");
        boolean dims=true,finite=true;if(state!=null)for(ActorScanStore.Cluster a:state.clusters){if(a.centroid==null||a.centroid.length!=192)dims=false;if(a.centroid!=null)for(float v:a.centroid)if(!Float.isFinite(v))finite=false;}if(dims)pass.add("192D identity cache");else fail.add("192D identity cache");if(finite)pass.add("Finite embeddings");else fail.add("Finite embeddings");
        try{V080ExportQueue q=new V080ExportQueue(c);q.load();pass.add("Persistent queue read");}catch(Throwable e){fail.add("Persistent queue read");}
        try{SharedPreferences p=p(c);if(!p.getBoolean("v090_face_follow_enabled",false))pass.add("Zero-motion default");else fail.add("Zero-motion default is ON");}catch(Throwable e){fail.add("Follow preference");}
        try{FileCheck x=fileCheck(c,state);if(x.ok)pass.add("Identity memory storage");else fail.add("Identity memory storage");}catch(Throwable e){fail.add("Identity memory storage");}
        return "PASS "+pass.size()+"/"+(pass.size()+fail.size())+" • "+String.join(", ",pass)+(fail.isEmpty()?"":"\nFAIL • "+String.join(", ",fail));
    }
    private static FileCheck fileCheck(Context c,ActorScanStore.ScanState s){try{java.io.File d=new java.io.File(c.getFilesDir(),"identity_v100");if(!d.exists())d.mkdirs();java.io.File f=new java.io.File(d,"selftest.tmp");try(java.io.FileOutputStream o=new java.io.FileOutputStream(f)){o.write(7);}boolean ok=f.isFile()&&f.length()==1;f.delete();return new FileCheck(ok);}catch(Throwable e){return new FileCheck(false);}}

    static void markLaunch(Context c,long elapsedMs){p(c).edit().putLong("v100_last_ui_ready_ms",elapsedMs).apply();}
    static String perfSummary(Context c){SharedPreferences p=p(c);return "UI ready "+p.getLong("v100_last_ui_ready_ms",0)+" ms • Face "+p.getString("face_backend_v100","CPU")+" "+p.getInt("face_threads_v090",2)+"T • "+String.format(Locale.US,"%.1f ms",p.getFloat("backend_ms_v100",0));}
    private static SharedPreferences p(Context c){return c.getSharedPreferences("actor_sticker",Context.MODE_PRIVATE);}
    private static final class FileCheck{final boolean ok;FileCheck(boolean x){ok=x;}}
    private static final class CodecBench{final String mime;String name="-",note="";boolean ok,hardware;double fps;CodecBench(String m){mime=m;}String text(){return name+" • "+(hardware?"HW":"SW/unknown")+" • "+(ok?String.format(Locale.US,"%.1f fps",fps):note);}}
}
