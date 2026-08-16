package com.mhsx.actorsticker;

import android.content.*;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.*;

import androidx.media3.common.*;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.Crop;
import androidx.media3.transformer.*;

import java.io.File;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

@UnstableApi
final class V090PreviewRenderer {
    interface Callback { void done(File file,String error); }
    private V090PreviewRenderer(){}

    static void render(Context c,Uri uri,int videoIndex,ActorScanStore.Cluster actor,Callback cb){
        new Thread(()->{File out=null;try{
            if(actor==null||uri==null)throw new IllegalStateException("Select actor and video first");
            ArrayList<ActorScanStore.Hit> all=new ArrayList<>();for(ActorScanStore.Hit h:actor.hits)if(h.videoIndex==videoIndex)all.add(h);all.sort(Comparator.comparingLong(h->h.t));if(all.isEmpty())throw new IllegalStateException("No actor track points in this video");
            long dur=duration(c,uri);long mid=all.get(all.size()/2).t;long start=Math.max(0,mid-2000);long end=Math.min(dur>0?dur:start+4000,start+4000);if(end-start<2500){start=Math.max(0,end-4000);}ArrayList<ActorScanStore.Hit> hits=new ArrayList<>();for(ActorScanStore.Hit h:all)if(h.t>=start-1500&&h.t<=end+1500)hits.add(h);if(hits.isEmpty())hits.addAll(all);
            float input=16f/9f;for(ActorScanStore.Hit h:hits)if(h.fw>0&&h.fh>0){input=h.fw/(float)h.fh;break;}float target=1f;
            SharedPreferences p=c.getSharedPreferences("actor_sticker",Context.MODE_PRIVATE);boolean tracking=p.getBoolean("v090_face_follow_enabled",false)&&p.getBoolean("smart_tracking",false);
            TrackingPanEffect.configure(p.getBoolean("dynamic_zoom_v050",false)&&tracking,p.getBoolean("lost_recovery_v050",true),p.getBoolean("identity_lock_v050",true),p.getFloat("max_zoom_v050",1.14f));
            try{TrackingPanEffect.configureMotion(tracking?p.getString("tracking_motion_v061","Slow"):"Fixed actor average",p.getInt("tracking_follow_v061",18)/100f,p.getInt("tracking_deadzone_v061",16)/100f,p.getInt("tracking_fixed_x_v061",50)/100f,p.getInt("tracking_fixed_y_v061",42)/100f);}catch(Throwable ignored){}
            try{TrackingPanEffect.configureV090(p.getBoolean("cinematic_follow_v090",false)&&tracking,p.getInt("cinematic_speed_v090",10)/100f,p.getInt("cinematic_deadzone_v090",16)/100f,p.getBoolean("manual_path_v090",false)?p.getString("tracking_path_v090",""):"",p.getInt("tracking_freeze_v090",100)/100f);}catch(Throwable ignored){}
            TrackingPanEffect pan=new TrackingPanEffect(hits,start,input,target,tracking,true);Crop crop=TrackingPanEffect.centeredCrop(input,target);MediaItem media=new MediaItem.Builder().setUri(uri).setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder().setStartPositionMs(start).setEndPositionMs(end).build()).build();EditedMediaItem edited=new EditedMediaItem.Builder(media).setEffects(new Effects(Collections.emptyList(),Arrays.asList(pan,crop))).build();
            File d=new File(c.getCacheDir(),"v090_preview");if(!d.exists())d.mkdirs();out=new File(d,"preview_"+System.currentTimeMillis()+".mp4");
            CountDownLatch latch=new CountDownLatch(1);AtomicReference<Throwable> err=new AtomicReference<>();File finalOut=out;Handler main=new Handler(Looper.getMainLooper());main.post(()->{try{Transformer tr=new Transformer.Builder(c).setVideoMimeType(MimeTypes.VIDEO_H264).addListener(new Transformer.Listener(){@Override public void onCompleted(Composition comp,ExportResult r){latch.countDown();}@Override public void onError(Composition comp,ExportResult r,ExportException e){err.set(e);latch.countDown();}}).build();tr.start(edited,finalOut.getAbsolutePath());}catch(Throwable e){err.set(e);latch.countDown();}});
            if(!latch.await(8,TimeUnit.MINUTES))throw new TimeoutException("Preview render timed out");if(err.get()!=null)throw new Exception(err.get());if(!out.isFile()||out.length()==0)throw new IllegalStateException("Preview file empty");File ok=out;main.post(()->cb.done(ok,null));return;
        }catch(Throwable e){V090Diagnostics.log(c,"preview",e);File bad=out;if(bad!=null)bad.delete();new Handler(Looper.getMainLooper()).post(()->cb.done(null,e.getClass().getSimpleName()+": "+String.valueOf(e.getMessage())));}},"v090-preview").start();
    }
    private static long duration(Context c,Uri u){MediaMetadataRetriever m=new MediaMetadataRetriever();try{m.setDataSource(c,u);String s=m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);return s==null?-1:Long.parseLong(s);}catch(Throwable e){return-1;}finally{try{m.release();}catch(Throwable ignored){}}}
}
