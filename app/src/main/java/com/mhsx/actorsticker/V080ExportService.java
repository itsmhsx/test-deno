package com.mhsx.actorsticker;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.provider.DocumentsContract;

import androidx.annotation.Nullable;
import androidx.media3.common.Effect;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.Crop;
import androidx.media3.transformer.*;

import java.io.*;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** v0.8.1 foreground renderer with persistent queue, recovery and reliable pause/resume. */
@UnstableApi
public class V080ExportService extends Service {
    static final String ACTION_START="com.mhsx.actorsticker.v080.START";
    static final String ACTION_PAUSE="com.mhsx.actorsticker.v080.PAUSE";
    static final String ACTION_RESUME="com.mhsx.actorsticker.v080.RESUME";
    static final String ACTION_CANCEL_CURRENT="com.mhsx.actorsticker.v080.CANCEL_CURRENT";
    private static final String CHANNEL="asc_v080_export";
    private static final int NOTIFY=8080;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private volatile boolean running,pauseRequested,cancelCurrent,kickRequested;
    private volatile Transformer active;
    private V080ExportQueue queue;
    private SharedPreferences prefs;

    @Override public void onCreate(){
        super.onCreate();
        queue=new V080ExportQueue(this);
        queue.resetInterrupted();
        prefs=getSharedPreferences("actor_sticker",MODE_PRIVATE);
        createChannel();
    }

    @Override public int onStartCommand(Intent intent,int flags,int startId){
        String a=intent==null?ACTION_START:intent.getAction();
        if(ACTION_PAUSE.equals(a)){
            pauseRequested=true;
            queue.pausePending();
            if(!running){
                try{startForeground(NOTIFY,notification("Render queue paused",progress()));}catch(Throwable ignored){}
                notifyState("Render queue paused",progress());
                try{stopForeground(false);}catch(Throwable ignored){}
                stopSelf(startId);
            }else notifyState("Paused after current clip",progress());
            return START_NOT_STICKY;
        }
        if(ACTION_CANCEL_CURRENT.equals(a)){
            cancelCurrent=true;
            main.post(()->{try{if(active!=null)active.cancel();}catch(Throwable ignored){}});
            return START_STICKY;
        }

        // Critical v0.8.1 fix: START and RESUME both clear the pause latch.
        pauseRequested=false;
        queue.resumePaused();
        kickRequested=true;
        startLoop();
        return START_STICKY;
    }

    @Nullable @Override public IBinder onBind(Intent i){return null;}
    @Override public void onDestroy(){try{if(active!=null)active.cancel();}catch(Throwable ignored){}worker.shutdownNow();super.onDestroy();}

    private synchronized void startLoop(){
        if(running){kickRequested=true;return;}
        if(queue.count(V080ExportQueue.PENDING)==0){
            notifyState("Render queue has no pending jobs",progress());
            stopSelf();
            return;
        }
        running=true;
        kickRequested=false;
        try{startForeground(NOTIFY,notification("Preparing render queue",progress()));}
        catch(Throwable e){
            running=false;
            prefs.edit().putString("v080_export_status","Foreground renderer failed: "+e.getClass().getSimpleName()).putLong("v080_export_heartbeat",System.currentTimeMillis()).apply();
            stopSelf();
            return;
        }
        notifyState("Preparing render queue",progress());
        worker.execute(()->{
            try{runQueue();}
            finally{
                boolean restart;
                synchronized(V080ExportService.this){
                    running=false;
                    restart=kickRequested&&!pauseRequested&&queue.count(V080ExportQueue.PENDING)>0;
                    kickRequested=false;
                }
                if(restart){main.post(this::startLoop);return;}
                if(queue.count(V080ExportQueue.PENDING)==0&&queue.count(V080ExportQueue.RUNNING)==0){
                    notifyState("Render queue finished",100);
                    try{stopForeground(false);}catch(Throwable ignored){}
                    stopSelf();
                }else if(pauseRequested){
                    notifyState("Render queue paused",progress());
                }else{
                    notifyState("Render queue waiting • tap Start / Resume",progress());
                }
            }
        });
    }

    private void runQueue(){
        for(;;){
            if(pauseRequested)break;
            V080ExportQueue.Job j=queue.claimNext();
            if(j==null)break;
            cancelCurrent=false;
            try{
                notifyState("Exporting • "+j.outputName,progress());
                exportJob(j);
                if(cancelCurrent)queue.update(j.id,V080ExportQueue.PAUSED,"Cancelled by user");
                else queue.update(j.id,V080ExportQueue.DONE,"");
            }catch(Throwable e){
                String m=e.getClass().getSimpleName()+": "+String.valueOf(e.getMessage());
                queue.update(j.id,cancelCurrent?V080ExportQueue.PAUSED:V080ExportQueue.FAILED,m);
            }
            notifyState("Queue progress",progress());
        }
    }

    private void exportJob(V080ExportQueue.Job j)throws Exception{
        ActorScanStore.ScanState state=new ActorScanStore(this).load(j.projectKey);
        if(state==null)throw new IllegalStateException("Actor index missing; run Refresh Full Scan once");
        LinkedHashSet<Integer> ids=parseIds(j.actorIds);
        if(ids.isEmpty())throw new IllegalStateException("No actor selected");
        ArrayList<ActorScanStore.Hit> all=V080SceneAnalyzer.collect(state,ids,j.videoIndex,"together".equals(j.mode));
        ArrayList<ActorScanStore.Hit> hits=new ArrayList<>();
        for(ActorScanStore.Hit h:all)if(h.t>=j.startMs-1400&&h.t<=j.endMs+1400)hits.add(h);
        if(hits.isEmpty())throw new IllegalStateException("No actor track points for clip");
        float inputAspect=aspectFromHits(hits),targetAspect="Original".equals(j.aspect)?inputAspect:aspectValue(j.aspect);
        configureTracking(ids.size()>1);
        TrackingPanEffect pan=new TrackingPanEffect(hits,j.startMs,inputAspect,targetAspect,j.tracking,j.safeZone);
        Crop crop=TrackingPanEffect.centeredCrop(inputAspect,targetAspect);
        ArrayList<Effect> effects=new ArrayList<>();effects.add(pan);effects.add(crop);
        MediaItem media=new MediaItem.Builder().setUri(Uri.parse(j.sourceUri)).setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder().setStartPositionMs(j.startMs).setEndPositionMs(j.endMs).build()).build();
        EditedMediaItem edited=new EditedMediaItem.Builder(media).setEffects(new Effects(Collections.emptyList(),effects)).build();
        File dir=new File(getCacheDir(),"v080_exports");if(!dir.exists())dir.mkdirs();
        File out=new File(dir,safe(j.outputName));if(out.exists())out.delete();
        CountDownLatch latch=new CountDownLatch(1);AtomicReference<Throwable> err=new AtomicReference<>();
        main.post(()->{
            try{
                Transformer.Builder b=new Transformer.Builder(this);
                b.experimentalSetTrimOptimizationEnabled(true);
                b.experimentalSetMaxFramesInEncoder(4);
                String codec=j.codec;
                if(codec==null||codec.isEmpty()||"Auto".equals(codec))codec=V070CodecAdvisor.bestCodec(prefs);
                if("HEVC".equals(codec)&&V070CodecAdvisor.hasHardware("video/hevc"))b.setVideoMimeType(MimeTypes.VIDEO_H265);else b.setVideoMimeType(MimeTypes.VIDEO_H264);
                if(j.maxQuality){
                    int bitrate=bitrate(j.preset);
                    DefaultEncoderFactory enc=new DefaultEncoderFactory.Builder(this).setRequestedVideoEncoderSettings(new VideoEncoderSettings.Builder().setBitrate(bitrate).build()).build();
                    b.setEncoderFactory(enc);
                }
                Transformer tr=b.addListener(new Transformer.Listener(){
                    @Override public void onCompleted(Composition c,ExportResult r){active=null;latch.countDown();}
                    @Override public void onError(Composition c,ExportResult r,ExportException e){active=null;err.set(e);latch.countDown();}
                }).build();
                active=tr;
                tr.start(edited,out.getAbsolutePath());
            }catch(Throwable e){err.set(e);latch.countDown();}
        });
        while(!latch.await(2,TimeUnit.SECONDS)){
            notifyState("Exporting • "+j.outputName,progress());
            if(cancelCurrent)break;
        }
        if(cancelCurrent){main.post(()->{try{if(active!=null)active.cancel();}catch(Throwable ignored){}});if(out.exists())out.delete();return;}
        if(err.get()!=null){if(out.exists())out.delete();throw new Exception(err.get());}
        if(!out.isFile()||out.length()==0)throw new IOException("Empty export");
        copyToTree(out,Uri.parse(j.outputTree),j.outputName);
        out.delete();
    }

    private void configureTracking(boolean multi){
        boolean zoom=prefs.getBoolean("dynamic_zoom_v050",false)&&!multi;
        TrackingPanEffect.configure(zoom,prefs.getBoolean("lost_recovery_v050",true),prefs.getBoolean("identity_lock_v050",true),prefs.getFloat("max_zoom_v050",1.14f));
        TrackingPanEffect.configureV060(prefs.getString("smart_composition_v060","Look room"));
        try{Method m=TrackingPanEffect.class.getDeclaredMethod("configureMotion",String.class,float.class,float.class,float.class,float.class);m.setAccessible(true);m.invoke(null,prefs.getString("tracking_motion_v061","Slow"),prefs.getInt("tracking_follow_v061",18)/100f,prefs.getInt("tracking_deadzone_v061",12)/100f,prefs.getInt("tracking_fixed_x_v061",50)/100f,prefs.getInt("tracking_fixed_y_v061",50)/100f);}catch(Throwable ignored){}
    }
    private int bitrate(String p){if(p==null)return 16_000_000;if(p.contains("Telegram"))return 5_000_000;if(p.contains("Maximum")||p.contains("Original"))return 32_000_000;if(p.contains("Reels")||p.contains("Story"))return 20_000_000;if(p.contains("4:5"))return 18_000_000;return 16_000_000;}
    private float aspectFromHits(List<ActorScanStore.Hit> h){for(ActorScanStore.Hit x:h)if(x.fw>0&&x.fh>0)return x.fw/(float)x.fh;return 16f/9f;}
    private float aspectValue(String s){if("9:16".equals(s))return 9f/16f;if("4:5".equals(s))return 4f/5f;if("16:9".equals(s))return 16f/9f;return 1f;}
    private LinkedHashSet<Integer> parseIds(String s){LinkedHashSet<Integer>x=new LinkedHashSet<>();if(s!=null)for(String q:s.split(","))try{x.add(Integer.parseInt(q.trim()));}catch(Throwable ignored){}return x;}
    private void copyToTree(File src,Uri tree,String name)throws Exception{String id=DocumentsContract.getTreeDocumentId(tree);Uri parent=DocumentsContract.buildDocumentUriUsingTree(tree,id);Uri dest=DocumentsContract.createDocument(getContentResolver(),parent,"video/mp4",name);if(dest==null)throw new IOException("Cannot create output document");try(InputStream in=new BufferedInputStream(new FileInputStream(src));OutputStream out=new BufferedOutputStream(getContentResolver().openOutputStream(dest,"w"))){if(out==null)throw new IOException("Cannot open output");byte[]buf=new byte[1024*1024];int n;while((n=in.read(buf))>0)out.write(buf,0,n);out.flush();}}
    private String safe(String s){return(s==null?"clip.mp4":s).replaceAll("[^A-Za-z0-9._-]+","_");}

    private int progress(){ArrayList<V080ExportQueue.Job> xs=queue.load();if(xs.isEmpty())return 0;int done=0;for(V080ExportQueue.Job j:xs)if(V080ExportQueue.DONE.equals(j.state)||V080ExportQueue.FAILED.equals(j.state))done++;return Math.max(0,Math.min(100,(int)Math.round(done*100.0/xs.size())));}
    private void createChannel(){if(Build.VERSION.SDK_INT>=26){NotificationManager n=getSystemService(NotificationManager.class);n.createNotificationChannel(new NotificationChannel(CHANNEL,"Actor Sticker Export",NotificationManager.IMPORTANCE_LOW));}}
    private Notification notification(String text,int pct){Intent open=new Intent(this,MainActivityV081.class);PendingIntent pi=PendingIntent.getActivity(this,80,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CHANNEL):new Notification.Builder(this);b.setContentTitle("Actor Sticker Cutter • v0.8.1").setContentText(text).setSmallIcon(android.R.drawable.stat_sys_upload).setOngoing(running).setContentIntent(pi).setProgress(100,pct,false);return b.build();}
    private void notifyState(String text,int pct){getSystemService(NotificationManager.class).notify(NOTIFY,notification(text,pct));prefs.edit().putInt("v080_export_progress",pct).putString("v080_export_status",text).putLong("v080_export_heartbeat",System.currentTimeMillis()).apply();}
}
