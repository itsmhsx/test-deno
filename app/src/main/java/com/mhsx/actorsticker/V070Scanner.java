package com.mhsx.actorsticker;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.OpenableColumns;

import com.google.mlkit.vision.face.Face;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** v0.7 performance scanner: coarse discovery -> fine identity pass -> private per-video cache. */
final class V070Scanner {
    interface Progress { void update(int percent, String status); }
    static final class Result {
        final boolean complete; final int progress; final String status;
        Result(boolean c, int p, String s) { complete=c; progress=p; status=s; }
    }
    private static final int FRAGMENT_FORMAT = 1;

    private V070Scanner() {}

    static Result run(Context context, SharedPreferences prefs, ActorScanStore store,
                      AtomicBoolean cancel, boolean refresh, Progress progress) {
        ArrayList<Uri> videos = readVideos(prefs);
        if (videos.isEmpty()) return new Result(false, 0, "No videos selected");
        long decodeNs=0, detectNs=0, identityNs=0;
        int coarseFrames=0, fineFrames=0, cacheHits=0, cacheMisses=0;
        V070IndexDb db = new V070IndexDb(context);
        try {
            String projectKey = computeProjectKey(context, videos);
            if (refresh) store.clearProject(projectKey);
            ActorScanStore.ScanState state = refresh ? null : store.load(projectKey);
            if (state != null && state.complete) return new Result(true,100,"Cached v0.7 scan complete • "+stableGroups(state)+" actor group(s)");
            if (state == null) state = new ActorScanStore.ScanState(projectKey);
            final ActorScanStore.ScanState s = state;
            s.sampleMs = mode(prefs).fineStep;
            int startVideo = Math.max(0, Math.min(videos.size(), s.videoIndex));
            boolean incremental = prefs.getBoolean("incremental_cache_v070", true);
            boolean prefetch = prefs.getBoolean("parallel_prefetch_v070", true);
            float threshold = clamp(prefs.getFloat("cluster_threshold",0.58f),0.36f,0.88f);

            for (int vi=startVideo; vi<videos.size() && !cancel.get(); vi++) {
                Uri uri = videos.get(vi);
                long duration = durationMs(context, uri);
                if (duration < 2000) { s.videoIndex=vi+1; s.nextMs=0; store.save(s); continue; }
                long size = querySize(context, uri);
                String fp = fingerprint(uri, size, duration);
                File fragmentDir = new File(context.getFilesDir(), "actor_cache_v070/video_"+fp);
                File fragment = new File(fragmentDir, "scan.json");
                if (refresh && fragment.exists()) deleteRec(fragmentDir);

                VideoScan local = null;
                if (incremental && fragment.isFile() && db.hasVideo(fp, fragment.getAbsolutePath())) {
                    try { local = readFragment(fragmentDir, fragment); } catch (Throwable ignored) { local=null; }
                }
                if (local != null) {
                    cacheHits++;
                    mergeIntoProject(context, store, s, local, vi, threshold);
                    progress.update(overall(vi, videos.size(), 1.0), "Cache hit "+(vi+1)+"/"+videos.size()+" • no video decode");
                } else {
                    cacheMisses++;
                    Mode m = mode(prefs);
                    local = new VideoScan();
                    TreeSet<Long> candidates = new TreeSet<>();
                    double faceRatioSum=0; int faceRatioCount=0;
                    float coarseMin = prefs.getFloat("min_face_v070", 0.065f);
                    try (V070QuickFaceDetector quick = new V070QuickFaceDetector(coarseMin);
                         V070FrameSampler sampler = new V070FrameSampler(context, uri, m.quickHeight)) {
                        for (long t=0; t<duration && !cancel.get(); t+=m.coarseStep) {
                            long next=t+m.coarseStep; if(prefetch && next<duration) sampler.prefetch(next);
                            long a=System.nanoTime(); Bitmap frame=sampler.frameAt(t); decodeNs+=System.nanoTime()-a; coarseFrames++;
                            if(frame==null) continue;
                            try {
                                a=System.nanoTime(); List<Face> faces=quick.detect(frame); detectNs+=System.nanoTime()-a;
                                if(faces!=null && !faces.isEmpty()) {
                                    candidates.add(t);
                                    for(Face f:faces){Rect b=f.getBoundingBox(); if(b!=null){faceRatioSum += Math.max(b.width()/(double)Math.max(1,frame.getWidth()),b.height()/(double)Math.max(1,frame.getHeight()));faceRatioCount++;}}
                                }
                            } finally { if(!frame.isRecycled()) frame.recycle(); }
                            int lp=(int)Math.min(34,Math.round((t/(double)Math.max(1,duration))*34));
                            progress.update(overall(vi,videos.size(),lp/100.0),"Quick pass "+(vi+1)+"/"+videos.size()+" • "+lp+"%");
                        }
                    }
                    if(cancel.get()) break;

                    TreeSet<Long> fineTimes = new TreeSet<>();
                    for(Long c:candidates) {
                        long from=Math.max(0,c-m.coarseStep/2), to=Math.min(duration-1,c+m.coarseStep/2);
                        for(long t=from;t<=to;t+=m.fineStep) fineTimes.add(t);
                    }
                    float adaptiveMin = faceRatioCount==0 ? coarseMin : clamp((float)(faceRatioSum/faceRatioCount*0.34),0.055f,0.16f);
                    if(prefs.getBoolean("adaptive_min_face_v070",true)) prefs.edit().putFloat("last_adaptive_min_face_v070",adaptiveMin).apply();
                    else adaptiveMin=coarseMin;

                    if(!fragmentDir.exists()) fragmentDir.mkdirs();
                    try (V070QuickFaceDetector quick = new V070QuickFaceDetector(adaptiveMin);
                         FaceEngine full = new FaceEngine();
                         V070FrameSampler sampler = new V070FrameSampler(context, uri, m.fineHeight)) {
                        int idx=0, total=Math.max(1,fineTimes.size());
                        ArrayList<Long> ft=new ArrayList<>(fineTimes);
                        for(long t:ft) {
                            if(cancel.get()) break;
                            idx++; if(prefetch && idx<ft.size()) sampler.prefetch(ft.get(idx));
                            long a=System.nanoTime(); Bitmap frame=sampler.frameAt(t); decodeNs+=System.nanoTime()-a; fineFrames++;
                            if(frame==null) continue;
                            try {
                                a=System.nanoTime(); List<Face> faces=quick.detect(frame); detectNs+=System.nanoTime()-a;
                                if(faces==null) continue;
                                HashSet<Integer> used=new HashSet<>();
                                for(Face face:faces) {
                                    Rect box=face.getBoundingBox(); if(box==null||box.width()<24||box.height()<24) continue;
                                    a=System.nanoTime();
                                    float[] d=full.descriptor(frame,box); if(d==null) continue;
                                    float q=full.quality(frame,box);
                                    LocalMatch match=bestLocal(local,d,threshold,used);
                                    boolean alignOnDemand=prefs.getBoolean("landmark_on_demand_v070",true) && (match==null || match.score<threshold+0.07f || q>0.78f);
                                    if(alignOnDemand) {
                                        try {
                                            List<Face> detailed=full.detect(frame); Face df=nearestFace(detailed,box);
                                            float[] aligned=df==null?null:V060AlignedDescriptor.descriptor(full,frame,df);
                                            if(aligned!=null){d=aligned;match=bestLocal(local,d,threshold,used);}
                                        } catch(Throwable ignored) {}
                                    }
                                    LocalCluster lc;
                                    float sim;
                                    if(match==null){lc=new LocalCluster(local.clusters.size());lc.centroid=d.clone();FaceEngine.normalize(lc.centroid);local.clusters.add(lc);sim=1f;}
                                    else {lc=match.cluster;sim=match.score;}
                                    used.add(lc.id); lc.add(d,q,new LocalHit(t,box.centerX()/(float)frame.getWidth(),box.centerY()/(float)frame.getHeight(),frame.getWidth(),frame.getHeight(),sim,q));
                                    maybeLocalThumb(full,frame,box,fragmentDir,lc,q);
                                    identityNs+=System.nanoTime()-a;
                                }
                            } finally { if(!frame.isRecycled()) frame.recycle(); }
                            int p=35+(int)Math.round(idx/(double)total*64.0);
                            progress.update(overall(vi,videos.size(),p/100.0),"Fine identity pass "+(vi+1)+"/"+videos.size()+" • "+p+"%");
                        }
                    }
                    if(cancel.get()) break;
                    writeFragment(fragmentDir, fragment, local);
                    db.putVideo(fp, shortHash(uri.toString()), size, duration, fragment.getAbsolutePath(), local.faceCount());
                    mergeIntoProject(context, store, s, local, vi, threshold);
                }
                s.videoIndex=vi+1; s.nextMs=0; store.save(s);
                progress.update(overall(vi,videos.size(),1.0),"Indexed "+(vi+1)+"/"+videos.size()+" • "+stableGroups(s)+" actor group(s)");
            }

            if(cancel.get()) {
                s.complete=false; store.save(s);
                return new Result(false, Math.max(0,prefs.getInt("bg_scan_progress",0)), "Scan paused • v0.7 checkpoint kept");
            }
            s.complete=true; s.videoIndex=videos.size(); s.nextMs=0; store.save(s);
            double decodeMs=decodeNs/1e6, detectMs=detectNs/1e6, identityMs=identityNs/1e6;
            saveMetrics(prefs,db,decodeMs,detectMs,identityMs,coarseFrames,fineFrames,cacheHits,cacheMisses);
            return new Result(true,100,"v0.7 scan complete • "+stableGroups(s)+" actor group(s) • cache "+cacheHits+" hit(s)");
        } catch(Throwable e) {
            prefs.edit().putString("v070_last_error",e.getClass().getSimpleName()+": "+String.valueOf(e.getMessage())).apply();
            return new Result(false,Math.max(0,prefs.getInt("bg_scan_progress",0)),"v0.7 scan failed • "+e.getClass().getSimpleName());
        } finally { try{db.close();}catch(Throwable ignored){} }
    }

    private static void saveMetrics(SharedPreferences p,V070IndexDb db,double dec,double det,double id,int coarse,int fine,int hits,int misses){
        p.edit().putFloat("perf_decode_ms_v070",(float)dec).putFloat("perf_detect_ms_v070",(float)det).putFloat("perf_identity_ms_v070",(float)id)
                .putInt("perf_coarse_frames_v070",coarse).putInt("perf_fine_frames_v070",fine).putInt("perf_cache_hits_v070",hits).putInt("perf_cache_misses_v070",misses).apply();
        db.putPerf("decode_ms",dec);db.putPerf("detect_ms",det);db.putPerf("identity_ms",id);
    }

    private static Mode mode(SharedPreferences p){String s=p.getString("speed_mode_v070","Auto");if("Quick".equals(s))return new Mode(4200,900,320,480);if("Ultra Accurate".equals(s))return new Mode(1700,400,480,720);if("Balanced".equals(s))return new Mode(2800,650,360,560);int c=Runtime.getRuntime().availableProcessors();return c>=8?new Mode(2400,550,400,640):new Mode(3400,800,320,480);}
    private static final class Mode{final long coarseStep,fineStep;final int quickHeight,fineHeight;Mode(long c,long f,int q,int h){coarseStep=c;fineStep=f;quickHeight=q;fineHeight=h;}}

    private static Face nearestFace(List<Face> faces,Rect target){if(faces==null)return null;Face best=null;double bd=Double.MAX_VALUE;for(Face f:faces){Rect b=f.getBoundingBox();if(b==null)continue;double dx=b.centerX()-target.centerX(),dy=b.centerY()-target.centerY();double d=dx*dx+dy*dy;if(d<bd){bd=d;best=f;}}return best;}
    private static LocalMatch bestLocal(VideoScan s,float[] d,float threshold,Set<Integer> used){LocalCluster best=null;float bs=-1;for(LocalCluster c:s.clusters){if(used.contains(c.id)||c.centroid==null)continue;float sc=FaceEngine.cosine(c.centroid,d);if(sc>bs){bs=sc;best=c;}}return best==null||bs<threshold?null:new LocalMatch(best,bs);}
    private static final class LocalMatch{final LocalCluster cluster;final float score;LocalMatch(LocalCluster c,float s){cluster=c;score=s;}}

    private static void mergeIntoProject(Context context,ActorScanStore store,ActorScanStore.ScanState s,VideoScan local,int videoIndex,float threshold)throws Exception{
        for(LocalCluster lc:local.clusters){if(lc.count<=0||lc.centroid==null)continue;ActorScanStore.Cluster best=null;float bs=-1;for(ActorScanStore.Cluster g:s.clusters){if(g.centroid==null)continue;float sc=FaceEngine.cosine(g.centroid,lc.centroid);if(sc>bs){bs=sc;best=g;}}
            if(best==null||bs<threshold){int id=0;for(ActorScanStore.Cluster g:s.clusters)id=Math.max(id,g.id+1);best=new ActorScanStore.Cluster(id);best.centroid=lc.centroid.clone();FaceEngine.normalize(best.centroid);s.clusters.add(best);}
            int old=best.count,total=old+lc.count; if(old>0&&best.centroid!=null&&best.centroid.length==lc.centroid.length){for(int i=0;i<best.centroid.length;i++)best.centroid[i]=(best.centroid[i]*old+lc.centroid[i]*lc.count)/Math.max(1,total);FaceEngine.normalize(best.centroid);}else best.centroid=lc.centroid.clone();
            best.count=total;best.qualitySum+=lc.qualitySum;best.videosSeen.add(videoIndex);best.lastSeenVideo=videoIndex;
            for(LocalHit h:lc.hits){best.hits.add(new ActorScanStore.Hit(videoIndex,h.t,h.cx,h.cy,h.fw,h.fh,h.score,h.quality));best.lastSeenMs=Math.max(best.lastSeenMs,h.t);}
            int serial=0;for(String path:lc.thumbs){try{Bitmap b=BitmapFactory.decodeFile(path);if(b!=null){String n=store.saveThumbnail(s.projectKey,best.id,best.count+(serial++),b);best.thumbs.add(n);best.thumbQualities.add(0.75f);b.recycle();}}catch(Throwable ignored){}}
        }
    }

    private static void maybeLocalThumb(FaceEngine engine,Bitmap frame,Rect box,File dir,LocalCluster c,float q){try{if(c.thumbs.size()>=3&&q<=c.bestThumbQuality+0.05f)return;Bitmap b=engine.thumbnail(frame,box,160);if(b==null)return;File f=new File(dir,"c"+c.id+"_"+c.count+".webp");try(OutputStream o=new BufferedOutputStream(new FileOutputStream(f))){b.compress(Bitmap.CompressFormat.WEBP,82,o);}b.recycle();if(c.thumbs.size()<3)c.thumbs.add(f.getAbsolutePath());else c.thumbs.set(0,f.getAbsolutePath());c.bestThumbQuality=Math.max(c.bestThumbQuality,q);}catch(Throwable ignored){}}

    private static void writeFragment(File dir,File file,VideoScan scan)throws Exception{if(!dir.exists())dir.mkdirs();JSONObject root=new JSONObject();root.put("format",FRAGMENT_FORMAT);JSONArray cs=new JSONArray();for(LocalCluster c:scan.clusters){JSONObject o=new JSONObject();o.put("id",c.id);o.put("count",c.count);o.put("quality",c.qualitySum);JSONArray cen=new JSONArray();for(float v:c.centroid)cen.put(v);o.put("centroid",cen);JSONArray hs=new JSONArray();for(LocalHit h:c.hits){JSONArray a=new JSONArray();a.put(h.t);a.put(h.cx);a.put(h.cy);a.put(h.fw);a.put(h.fh);a.put(h.score);a.put(h.quality);hs.put(a);}o.put("hits",hs);JSONArray th=new JSONArray();for(String p:c.thumbs)th.put(new File(p).getName());o.put("thumbs",th);cs.put(o);}root.put("clusters",cs);File tmp=new File(dir,"scan.tmp");try(Writer w=new BufferedWriter(new OutputStreamWriter(new FileOutputStream(tmp),"UTF-8"))){w.write(root.toString());}if(file.exists())file.delete();if(!tmp.renameTo(file))throw new IOException("fragment commit failed");}
    private static VideoScan readFragment(File dir,File file)throws Exception{StringBuilder b=new StringBuilder();try(BufferedReader r=new BufferedReader(new InputStreamReader(new FileInputStream(file),"UTF-8"))){String l;while((l=r.readLine())!=null)b.append(l);}JSONObject root=new JSONObject(b.toString());if(root.optInt("format")!=FRAGMENT_FORMAT)return null;VideoScan s=new VideoScan();JSONArray cs=root.optJSONArray("clusters");if(cs==null)return s;for(int i=0;i<cs.length();i++){JSONObject o=cs.getJSONObject(i);LocalCluster c=new LocalCluster(o.optInt("id",i));c.count=o.optInt("count");c.qualitySum=o.optDouble("quality");JSONArray cen=o.getJSONArray("centroid");c.centroid=new float[cen.length()];for(int j=0;j<cen.length();j++)c.centroid[j]=(float)cen.getDouble(j);JSONArray hs=o.optJSONArray("hits");if(hs!=null)for(int j=0;j<hs.length();j++){JSONArray a=hs.getJSONArray(j);c.hits.add(new LocalHit(a.getLong(0),(float)a.getDouble(1),(float)a.getDouble(2),a.getInt(3),a.getInt(4),(float)a.getDouble(5),(float)a.getDouble(6)));}JSONArray th=o.optJSONArray("thumbs");if(th!=null)for(int j=0;j<th.length();j++)c.thumbs.add(new File(dir,th.getString(j)).getAbsolutePath());s.clusters.add(c);}return s;}

    private static final class VideoScan{final ArrayList<LocalCluster> clusters=new ArrayList<>();int faceCount(){int n=0;for(LocalCluster c:clusters)n+=c.count;return n;}}
    private static final class LocalCluster{final int id;int count;double qualitySum;float[] centroid;float bestThumbQuality;final ArrayList<LocalHit> hits=new ArrayList<>();final ArrayList<String> thumbs=new ArrayList<>();LocalCluster(int i){id=i;}void add(float[]d,float q,LocalHit h){count++;qualitySum+=q;if(centroid==null)centroid=d.clone();else{float a=1f/Math.min(30f,Math.max(2,count));for(int i=0;i<centroid.length;i++)centroid[i]=centroid[i]*(1-a)+d[i]*a;}FaceEngine.normalize(centroid);hits.add(h);}}
    private static final class LocalHit{final long t;final float cx,cy;final int fw,fh;final float score,quality;LocalHit(long t,float x,float y,int w,int h,float s,float q){this.t=t;cx=x;cy=y;fw=w;fh=h;score=s;quality=q;}}

    private static ArrayList<Uri> readVideos(SharedPreferences p){ArrayList<Uri> out=new ArrayList<>();try{JSONArray a=new JSONArray(p.getString("videos_json","[]"));for(int i=0;i<a.length();i++){String s=a.optString(i);if(!s.isEmpty())out.add(Uri.parse(s));}}catch(Throwable ignored){}return out;}
    private static String computeProjectKey(Context c,List<Uri> vs)throws Exception{MessageDigest md=MessageDigest.getInstance("SHA-256");for(int i=0;i<vs.size();i++){Uri u=vs.get(i);digest(md,i+"|");digest(md,u.toString());digest(md,"|"+displayName(c,u));digest(md,"|"+querySize(c,u));digest(md,"|"+durationMs(c,u));digest(md,"\n");}return hex(md.digest()).substring(0,32);}
    private static String fingerprint(Uri u,long size,long duration)throws Exception{MessageDigest md=MessageDigest.getInstance("SHA-256");digest(md,u.toString()+"|"+size+"|"+duration);return hex(md.digest()).substring(0,24);}
    private static String shortHash(String s)throws Exception{MessageDigest md=MessageDigest.getInstance("SHA-256");digest(md,s);return hex(md.digest()).substring(0,16);}
    private static void digest(MessageDigest m,String s)throws Exception{m.update(s.getBytes("UTF-8"));}private static String hex(byte[]d){StringBuilder b=new StringBuilder();for(byte x:d)b.append(String.format(Locale.US,"%02x",x&255));return b.toString();}
    private static long querySize(Context c,Uri u){try(Cursor x=c.getContentResolver().query(u,new String[]{OpenableColumns.SIZE},null,null,null)){return x!=null&&x.moveToFirst()&&!x.isNull(0)?x.getLong(0):-1;}catch(Throwable e){return -1;}}
    private static String displayName(Context c,Uri u){try(Cursor x=c.getContentResolver().query(u,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(x!=null&&x.moveToFirst()){String s=x.getString(0);if(s!=null)return s;}}catch(Throwable ignored){}return String.valueOf(u.getLastPathSegment());}
    private static long durationMs(Context c,Uri u){MediaMetadataRetriever m=new MediaMetadataRetriever();try{m.setDataSource(c,u);String s=m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);return s==null?-1:Long.parseLong(s);}catch(Throwable e){return -1;}finally{try{m.release();}catch(Throwable ignored){}}}
    private static int stableGroups(ActorScanStore.ScanState s){int n=0;for(ActorScanStore.Cluster c:s.clusters)if(c.count>=2)n++;return n;}
    private static int overall(int vi,int total,double local){return (int)Math.max(0,Math.min(99,Math.round(((vi+Math.max(0,Math.min(1,local)))/Math.max(1,total))*100.0)));}
    private static float clamp(float x,float a,float b){return Math.max(a,Math.min(b,x));}
    private static void deleteRec(File f){if(f==null||!f.exists())return;if(f.isDirectory()){File[]a=f.listFiles();if(a!=null)for(File x:a)deleteRec(x);}try{f.delete();}catch(Throwable ignored){}}
}
