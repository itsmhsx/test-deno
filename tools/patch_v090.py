from pathlib import Path
import re

# ---- Build/version/dependencies ----
gradle=Path('app/build.gradle')
s=gradle.read_text()
s=re.sub(r'versionCode\s+\d+','versionCode 12',s,count=1)
s=re.sub(r"versionName\s+'[^']+'","versionName '0.9.0'",s,count=1)
room="""    implementation 'androidx.room:room-runtime:2.7.2'\n    annotationProcessor 'androidx.room:room-compiler:2.7.2'\n    implementation 'androidx.recyclerview:recyclerview:1.4.0'\n"""
if 'androidx.room:room-runtime' not in s:
    s=s.replace("dependencies {\n", "dependencies {\n"+room,1)
gradle.write_text(s)

# ---- Launcher ----
manifest=Path('app/src/main/AndroidManifest.xml')
s=manifest.read_text()
s=s.replace('android:name=".MainActivityV081"\n            android:exported="true"','android:name=".MainActivityV090"\n            android:exported="true"',1)
if '<activity android:name=".MainActivityV081" android:exported="false" />' not in s:
    marker='<activity android:name=".MainActivityV080" android:exported="false" />'
    s=s.replace(marker,'<activity android:name=".MainActivityV081" android:exported="false" />\n        '+marker,1)
manifest.write_text(s)

# ---- Background scan routes + unified work heartbeat ----
bg=Path('app/src/main/java/com/mhsx/actorsticker/BackgroundScanService.java')
s=bg.read_text().replace('MainActivityV081.class','MainActivityV090.class')
old='''        prefs.edit().putBoolean("bg_scan_running", running)\n                .putInt("bg_scan_progress", Math.max(0, Math.min(100, progress)))\n                .putString("bg_scan_status", status)\n                .putLong("bg_scan_updated", System.currentTimeMillis()).apply();'''
new='''        long now090 = System.currentTimeMillis();\n        prefs.edit().putBoolean("bg_scan_running", running)\n                .putInt("bg_scan_progress", Math.max(0, Math.min(100, progress)))\n                .putString("bg_scan_status", status)\n                .putLong("bg_scan_updated", now090)\n                .putString("v090_work_kind", "scan")\n                .putString("v090_work_status", status)\n                .putInt("v090_work_progress", Math.max(0, Math.min(100, progress)))\n                .putLong("v090_work_heartbeat", now090).apply();'''
if old in s:s=s.replace(old,new,1)
else:raise SystemExit('v090 background updateState marker missing')
bg.write_text(s)

# ---- Face Quality Gate before neural embedding ----
face=Path('app/src/main/java/com/mhsx/actorsticker/FaceEngine.java')
s=face.read_text()
old='''    float[] descriptor(Bitmap src, Rect box) {\n        float[] learnedV080 = V080FaceEmbedder.embedding(src, box);'''
new='''    float[] descriptor(Bitmap src, Rect box) {\n        if (!V090FaceQualityGate.allow(src, box)) return null;\n        float[] learnedV080 = V080FaceEmbedder.embedding(src, box);'''
if old not in s:raise SystemExit('v090 FaceEngine learned embedding marker missing')
s=s.replace(old,new,1)
face.write_text(s)

# ---- Fine-grained scan checkpoint, reference bank and runtime guards ----
scan=Path('app/src/main/java/com/mhsx/actorsticker/V070Scanner.java')
s=scan.read_text()
old='''                long duration = durationMs(context, uri);\n                if (duration < 2000)'''
new='''                V090RuntimeGuard.Snapshot runtime090 = V090RuntimeGuard.check(context);\n                long resumeFineMs090 = (vi==startVideo) ? Math.max(0L, s.nextMs) : 0L;\n                long duration = durationMs(context, uri);\n                if (duration < 2000)'''
if old not in s:raise SystemExit('v090 scanner duration marker missing')
s=s.replace(old,new,1)
old='''                File fragment = new File(fragmentDir, "scan.json");\n                if (refresh && fragment.exists()) deleteRec(fragmentDir);\n\n                VideoScan local = null;'''
new='''                File fragment = new File(fragmentDir, "scan.json");\n                if (refresh && fragment.exists()) deleteRec(fragmentDir);\n                boolean partialResume090 = resumeFineMs090 > 0L && fragment.isFile() && !db.hasVideo(fp, fragment.getAbsolutePath());\n\n                VideoScan local = null;'''
if old not in s:raise SystemExit('v090 scanner fragment marker missing')
s=s.replace(old,new,1)
old='''                    Mode m = mode(prefs);\n                    local = new VideoScan();'''
new='''                    Mode m = mode(prefs);\n                    if (partialResume090) {\n                        try { local = readFragment(fragmentDir, fragment); }\n                        catch (Throwable ignored) { local = new VideoScan(); resumeFineMs090 = 0L; }\n                    } else local = new VideoScan();'''
if old not in s:raise SystemExit('v090 scanner local init marker missing')
s=s.replace(old,new,1)
old='''                        for(long t:ft) {\n                            if(cancel.get()) break;\n                            idx++;'''
new='''                        for(long t:ft) {\n                            if(cancel.get()) break;\n                            if (resumeFineMs090 > 0L && t < resumeFineMs090) continue;\n                            idx++;'''
if old not in s:raise SystemExit('v090 fine loop marker missing')
s=s.replace(old,new,1)
old='''                            } finally { if(!frame.isRecycled()) frame.recycle(); }\n                            int p=35+(int)Math.round(idx/(double)total*64.0);'''
new='''                            } finally { if(!frame.isRecycled()) frame.recycle(); }\n                            if (idx % 24 == 0) {\n                                writeFragment(fragmentDir, fragment, local);\n                                s.videoIndex = vi; s.nextMs = Math.min(duration, t + m.fineStep);\n                                store.save(s);\n                            }\n                            int p=35+(int)Math.round(idx/(double)total*64.0);'''
if old not in s:raise SystemExit('v090 checkpoint insertion marker missing')
s=s.replace(old,new,1)
old='''float sc=FaceEngine.cosine(g.centroid,lc.centroid);if(sc>bs){bs=sc;best=g;}'''
new='''float sc=V090ReferenceBank.similarity(context,s.projectKey,g.id,lc.centroid,FaceEngine.cosine(g.centroid,lc.centroid));if(sc>bs){bs=sc;best=g;}'''
if old not in s:raise SystemExit('v090 reference matching marker missing')
s=s.replace(old,new,1)
old='''    private static Mode mode(SharedPreferences p){String s=p.getString("speed_mode_v070","Auto");'''
new='''    private static Mode mode(SharedPreferences p){if(p.getBoolean("v090_low_memory",false)||p.getInt("v090_thermal",0)>=3)return new Mode(4200,900,320,480);String s=p.getString("speed_mode_v070","Auto");'''
if old not in s:raise SystemExit('v090 scanner mode marker missing')
s=s.replace(old,new,1)
scan.write_text(s)

# ---- Tracking: opt-in face follow, cinematic rate limit, manual path and freeze ----
track=Path('app/src/main/java/com/mhsx/actorsticker/TrackingPanEffect.java')
s=track.read_text()
old='''    private static volatile float CFG_FIXED_Y = 0.42f;\n'''
new=old+'''    private static volatile boolean CFG_CINEMATIC = false;\n    private static volatile float CFG_CINE_SPEED = 0.10f;\n    private static volatile float CFG_CINE_DEAD = 0.16f;\n    private static volatile String CFG_PATH = "";\n    private static volatile float CFG_FREEZE_AT = 1f;\n'''
if old not in s:raise SystemExit('v090 tracking static marker missing')
s=s.replace(old,new,1)
old='''    private final boolean safeZone;\n'''
new=old+'''    private long cineLastMs = Long.MIN_VALUE;\n    private float cineX = Float.NaN, cineY = Float.NaN;\n'''
s=s.replace(old,new,1)
marker='''    private boolean isFixedMode() {'''
config='''    static void configureV090(boolean cinematic, float maxSpeed, float deadZone, String pathSpec, float freezeAt) {\n        CFG_CINEMATIC = cinematic;\n        CFG_CINE_SPEED = clamp(maxSpeed, 0.005f, 0.40f);\n        CFG_CINE_DEAD = clamp(deadZone, 0.02f, 0.35f);\n        CFG_PATH = pathSpec == null ? "" : pathSpec.trim();\n        CFG_FREEZE_AT = clamp(freezeAt, 0f, 1f);\n    }\n\n'''
if marker not in s:raise SystemExit('v090 configure insertion marker missing')
s=s.replace(marker,config+marker,1)
start=s.find('    private Center motionCenter(long globalMs) {')
end=s.find('    private Center wideCenter',start)
if start<0 or end<0:raise SystemExit('v090 motionCenter block missing')
new_motion='''    private Center motionCenter(long globalMs) {\n        Center anchor = wholeSegmentCenter();\n        float progress = segmentProgress(globalMs);\n        if (!CFG_PATH.isEmpty()) {\n            float p = CFG_FREEZE_AT < 0.999f ? Math.min(progress, CFG_FREEZE_AT) : progress;\n            return manualPathCenter(p, anchor);\n        }\n        if (!tracking) return anchor;\n        if ("Fixed center".equals(CFG_MOTION_MODE)) return new Center(0.50f, 0.50f, 1f);\n        if ("Fixed actor average".equals(CFG_MOTION_MODE)) return anchor;\n        if ("Manual fixed".equals(CFG_MOTION_MODE)) return new Center(CFG_FIXED_X, CFG_FIXED_Y, 1f);\n\n        float strength; long radius; double sigma;\n        if ("Very slow".equals(CFG_MOTION_MODE)) { strength = 0.12f; radius = 7000L; sigma = 2800.0; }\n        else if ("Slow".equals(CFG_MOTION_MODE)) { strength = 0.24f; radius = 5600L; sigma = 2100.0; }\n        else if ("Normal".equals(CFG_MOTION_MODE)) { strength = 0.62f; radius = 3200L; sigma = 1100.0; }\n        else { strength = CFG_FOLLOW_STRENGTH; radius = (long)(2600L + (1f - strength) * 5200L); sigma = 850.0 + (1f - strength) * 2500.0; }\n        Center raw = wideCenter(globalMs, radius, sigma);\n        float dx = raw.x - anchor.x, dy = raw.y - anchor.y;\n        float dz = Math.max(CFG_DEAD_ZONE, CFG_CINEMATIC ? CFG_CINE_DEAD : 0f);\n        dx = Math.abs(dx) <= dz ? 0f : Math.copySign(Math.abs(dx) - dz, dx);\n        dy = Math.abs(dy) <= dz * 0.75f ? 0f : Math.copySign(Math.abs(dy) - dz * 0.75f, dy);\n        Center target = new Center(clamp(anchor.x + dx * strength, 0.08f, 0.92f), clamp(anchor.y + dy * strength, 0.08f, 0.92f), raw.confidence);\n        return CFG_CINEMATIC ? cinematicCenter(globalMs, target, anchor) : target;\n    }\n\n    private float segmentProgress(long globalMs) {\n        if (hits.size() < 2) return 0.5f; long a=hits.get(0).t,b=hits.get(hits.size()-1).t;\n        if (b<=a) return 0.5f; return clamp((globalMs-a)/(float)(b-a),0f,1f);\n    }\n\n    private Center manualPathCenter(float p, Center fallback) {\n        try {\n            String[] parts=CFG_PATH.split(";"); if(parts.length<2)return fallback;\n            float[] ts=new float[parts.length],xs=new float[parts.length],ys=new float[parts.length];\n            int n=0; for(String part:parts){String[]q=part.split(":");if(q.length<3)continue;ts[n]=Float.parseFloat(q[0]);xs[n]=clamp(Float.parseFloat(q[1]),.04f,.96f);ys[n]=clamp(Float.parseFloat(q[2]),.04f,.96f);n++;}\n            if(n==0)return fallback;if(p<=ts[0])return new Center(xs[0],ys[0],1f);\n            for(int i=1;i<n;i++)if(p<=ts[i]){float k=clamp((p-ts[i-1])/Math.max(.001f,ts[i]-ts[i-1]),0f,1f);k=k*k*(3f-2f*k);return new Center(lerp(xs[i-1],xs[i],k),lerp(ys[i-1],ys[i],k),1f);}\n            return new Center(xs[n-1],ys[n-1],1f);\n        } catch(Throwable e){return fallback;}\n    }\n\n    private Center cinematicCenter(long t, Center target, Center anchor) {\n        if (cineLastMs==Long.MIN_VALUE || t<cineLastMs || Float.isNaN(cineX)) { cineLastMs=t;cineX=anchor.x;cineY=anchor.y; }\n        float dt=Math.max(.001f,Math.min(1f,(t-cineLastMs)/1000f));cineLastMs=t;\n        float dx=target.x-cineX,dy=target.y-cineY; if(Math.abs(dx)<CFG_CINE_DEAD)dx=0;if(Math.abs(dy)<CFG_CINE_DEAD*.75f)dy=0;\n        float max=CFG_CINE_SPEED*dt;dx=clamp(dx,-max,max);dy=clamp(dy,-max,max);cineX=clamp(cineX+dx,.05f,.95f);cineY=clamp(cineY+dy,.05f,.95f);return new Center(cineX,cineY,target.confidence);\n    }\n\n'''
s=s[:start]+new_motion+s[end:]
track.write_text(s)

# ---- Export service: hard opt-in follow + heartbeat/watchdog + memory/thermal policy ----
service=Path('app/src/main/java/com/mhsx/actorsticker/V080ExportService.java')
s=service.read_text().replace('MainActivityV081.class','MainActivityV090.class').replace('Actor Sticker Cutter • v0.8.1','Actor Sticker Cutter • v0.9')
old='''        configureTracking(ids.size()>1);\n        TrackingPanEffect pan=new TrackingPanEffect(hits,j.startMs,inputAspect,targetAspect,j.tracking,j.safeZone);'''
new='''        configureTracking(ids.size()>1);\n        boolean follow090 = prefs.getBoolean("v090_face_follow_enabled", false) && j.tracking;\n        TrackingPanEffect pan=new TrackingPanEffect(hits,j.startMs,inputAspect,targetAspect,follow090,j.safeZone);'''
if old not in s:raise SystemExit('v090 export tracking marker missing')
s=s.replace(old,new,1)
old='''        CountDownLatch latch=new CountDownLatch(1);AtomicReference<Throwable> err=new AtomicReference<>();\n        main.post(()->{'''
new='''        V090RuntimeGuard.Snapshot guard090=V090RuntimeGuard.check(this);\n        if(prefs.getBoolean("thermal_adaptive_v090",true) && guard090.thermal>=android.os.PowerManager.THERMAL_STATUS_SEVERE){\n            long waitUntil=System.currentTimeMillis()+120000L;\n            while(System.currentTimeMillis()<waitUntil && V090RuntimeGuard.severeThermal(this)){queue.heartbeat(j.id);notifyState("Cooling before export • "+j.outputName,progress());Thread.sleep(4000L);}\n            guard090=V090RuntimeGuard.check(this);\n        }\n        final V090RuntimeGuard.Snapshot renderGuard090=guard090;\n        CountDownLatch latch=new CountDownLatch(1);AtomicReference<Throwable> err=new AtomicReference<>();\n        main.post(()->{'''
if old not in s:raise SystemExit('v090 export guard marker missing')
s=s.replace(old,new,1)
s=s.replace('b.experimentalSetMaxFramesInEncoder(4);','b.experimentalSetMaxFramesInEncoder(renderGuard090.lowMemory||renderGuard090.thermal>=2?2:4);',1)
old='''                if("HEVC".equals(codec)&&V070CodecAdvisor.hasHardware("video/hevc"))b.setVideoMimeType(MimeTypes.VIDEO_H265);else b.setVideoMimeType(MimeTypes.VIDEO_H264);'''
new='''                if(renderGuard090.thermal>=android.os.PowerManager.THERMAL_STATUS_SEVERE) codec="AVC";\n                if("HEVC".equals(codec)&&V070CodecAdvisor.hasHardware("video/hevc"))b.setVideoMimeType(MimeTypes.VIDEO_H265);else b.setVideoMimeType(MimeTypes.VIDEO_H264);'''
s=s.replace(old,new,1)
s=s.replace('int bitrate=bitrate(j.preset);','int bitrate=bitrate(j.preset); if(renderGuard090.lowMemory||renderGuard090.thermal>=2) bitrate=Math.min(bitrate,12_000_000);',1)
old='''        while(!latch.await(2,TimeUnit.SECONDS)){\n            notifyState("Exporting • "+j.outputName,progress());'''
new='''        while(!latch.await(2,TimeUnit.SECONDS)){\n            queue.heartbeat(j.id);\n            V090WorkController.mark(this,"export","Exporting • "+j.outputName,progress());\n            notifyState("Exporting • "+j.outputName,progress());'''
s=s.replace(old,new,1)
old='''        boolean zoom=prefs.getBoolean("dynamic_zoom_v050",false)&&!multi;\n        TrackingPanEffect.configure(zoom,prefs.getBoolean("lost_recovery_v050",true),prefs.getBoolean("identity_lock_v050",true),prefs.getFloat("max_zoom_v050",1.14f));'''
new='''        boolean follow090=prefs.getBoolean("v090_face_follow_enabled",false);\n        boolean zoom=follow090&&prefs.getBoolean("dynamic_zoom_v050",false)&&!multi;\n        TrackingPanEffect.configure(zoom,prefs.getBoolean("lost_recovery_v050",true),prefs.getBoolean("identity_lock_v050",true),prefs.getFloat("max_zoom_v050",1.14f));'''
s=s.replace(old,new,1)
old='''m.invoke(null,prefs.getString("tracking_motion_v061","Slow"),prefs.getInt("tracking_follow_v061",18)/100f,prefs.getInt("tracking_deadzone_v061",12)/100f,prefs.getInt("tracking_fixed_x_v061",50)/100f,prefs.getInt("tracking_fixed_y_v061",50)/100f);}catch(Throwable ignored){}\n    }'''
new='''m.invoke(null,follow090?prefs.getString("tracking_motion_v061","Slow"):"Fixed actor average",prefs.getInt("tracking_follow_v061",18)/100f,prefs.getInt("tracking_deadzone_v061",16)/100f,prefs.getInt("tracking_fixed_x_v061",50)/100f,prefs.getInt("tracking_fixed_y_v061",42)/100f);}catch(Throwable ignored){}\n        try{TrackingPanEffect.configureV090(prefs.getBoolean("cinematic_follow_v090",false)&&follow090,prefs.getInt("cinematic_speed_v090",10)/100f,prefs.getInt("cinematic_deadzone_v090",16)/100f,prefs.getBoolean("manual_path_v090",false)?prefs.getString("tracking_path_v090",""):"",prefs.getInt("tracking_freeze_v090",100)/100f);}catch(Throwable ignored){}\n    }'''
if old not in s:raise SystemExit('v090 configure tracking tail marker missing')
s=s.replace(old,new,1)
service.write_text(s)

# ---- Backup before destructive actor repair + follow opt-in in queue composition ----
main80=Path('app/src/main/java/com/mhsx/actorsticker/MainActivityV080.java')
s=main80.read_text()
s=s.replace('''private void mergeSelected(String key){ActorScanStore.ScanState s=scan();''','''private void mergeSelected(String key){V090RecoveryManager.backupIndex(this,key,"before_merge");ActorScanStore.ScanState s=scan();''',1)
s=s.replace('''private void splitSelected(String key){ActorScanStore.ScanState s=scan();''','''private void splitSelected(String key){V090RecoveryManager.backupIndex(this,key,"before_split");ActorScanStore.ScanState s=scan();''',1)
s=s.replace('''boolean track=p().getBoolean("smart_tracking",true);''','''boolean track=p().getBoolean("v090_face_follow_enabled",false)&&p().getBoolean("smart_tracking",false);''',1)
main80.write_text(s)

# ---- Final guards ----
checks={
 'app/src/main/java/com/mhsx/actorsticker/TrackingPanEffect.java':['configureV090','cinematicCenter','manualPathCenter'],
 'app/src/main/java/com/mhsx/actorsticker/V070Scanner.java':['partialResume090','V090ReferenceBank.similarity','idx % 24'],
 'app/src/main/java/com/mhsx/actorsticker/V080ExportService.java':['follow090','queue.heartbeat','V090RuntimeGuard'],
 'app/src/main/java/com/mhsx/actorsticker/FaceEngine.java':['V090FaceQualityGate.allow'],
}
for path,markers in checks.items():
    text=Path(path).read_text()
    for m in markers:
        if m not in text: raise SystemExit('v0.9 integration guard missing '+m+' in '+path)
print('v0.9 stability/accuracy integration patch applied')
