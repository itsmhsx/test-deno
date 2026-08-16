from pathlib import Path
import re

# ---- version + persistent work dependency ----
gradle=Path('app/build.gradle')
s=gradle.read_text()
s=re.sub(r'versionCode\s+\d+','versionCode 13',s,count=1)
s=re.sub(r"versionName\s+'[^']+'","versionName '1.0.0'",s,count=1)
if 'androidx.work:work-runtime' not in s:
    s=s.replace("dependencies {\n","dependencies {\n    implementation 'androidx.work:work-runtime:2.10.1'\n",1)
gradle.write_text(s)

# ---- v1.0 launcher, keep v0.9 activity for compatibility ----
manifest=Path('app/src/main/AndroidManifest.xml')
s=manifest.read_text()
s=s.replace('android:name=".MainActivityV090"\n            android:exported="true"','android:name=".MainActivityV100"\n            android:exported="true"',1)
if '<activity android:name=".MainActivityV090" android:exported="false" />' not in s:
    marker='<activity android:name=".MainActivityV081" android:exported="false" />'
    s=s.replace(marker,'<activity android:name=".MainActivityV090" android:exported="false" />\n        '+marker,1)
manifest.write_text(s)

# ---- full landmark detector for the fine identity engine ----
face=Path('app/src/main/java/com/mhsx/actorsticker/FaceEngine.java')
s=face.read_text()
s=s.replace('.setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)','.setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)',1)
face.write_text(s)

# ---- CPU / NNAPI backend policy for MobileFaceNet ----
embed=Path('app/src/main/java/com/mhsx/actorsticker/V080FaceEmbedder.java')
s=embed.read_text()
old='''        options.setNumThreads(Math.max(1,Math.min(4,threads)));\n        options.setUseXNNPACK(true);'''
new='''        options.setNumThreads(Math.max(1,Math.min(4,threads)));\n        String backend100=context.getSharedPreferences("actor_sticker",Context.MODE_PRIVATE).getString("face_backend_v100","CPU");\n        if ("NNAPI".equals(backend100) && android.os.Build.VERSION.SDK_INT>=27) options.setUseNNAPI(true);\n        else options.setUseXNNPACK(true);'''
if old not in s: raise SystemExit('v100 embedder backend marker missing')
s=s.replace(old,new,1)
embed.write_text(s)

# ---- Face Recognition v3: detailed landmarks + affine aligned MobileFaceNet in fine pass ----
scan=Path('app/src/main/java/com/mhsx/actorsticker/V070Scanner.java')
s=scan.read_text()
old='''                                if(faces==null) continue;\n                                HashSet<Integer> used=new HashSet<>();\n                                for(Face face:faces) {'''
new='''                                if(faces==null) continue;\n                                List<Face> detailed100=null;\n                                boolean detail100=!"Fast".equals(prefs.getString("identity_accuracy_v100","Balanced"));\n                                if(detail100) try{detailed100=full.detect(frame);}catch(Throwable ignored){}\n                                HashSet<Integer> used=new HashSet<>();\n                                for(Face face:faces) {'''
if old not in s: raise SystemExit('v100 scanner detailed-face marker missing')
s=s.replace(old,new,1)
old='''                                    a=System.nanoTime();\n                                    float[] d=full.descriptor(frame,box); if(d==null) continue;\n                                    float q=full.quality(frame,box);'''
new='''                                    a=System.nanoTime();\n                                    Face detailedFace100=detail100?nearestFace(detailed100,box):null;\n                                    float[] d=V100IdentityEngine.alignedEmbedding(context,frame,detailedFace100,box,full); if(d==null) continue;\n                                    float q=full.quality(frame,box);'''
if old not in s: raise SystemExit('v100 scanner embedding marker missing')
s=s.replace(old,new,1)
old='''float sc=V090ReferenceBank.similarity(context,s.projectKey,g.id,lc.centroid,FaceEngine.cosine(g.centroid,lc.centroid));if(sc>bs){bs=sc;best=g;}'''
new='''float raw100=V090ReferenceBank.similarity(context,s.projectKey,g.id,lc.centroid,FaceEngine.cosine(g.centroid,lc.centroid));float sc=V100IdentityEngine.score(context,s.projectKey,g.id,lc.centroid,raw100);if(sc>bs){bs=sc;best=g;}'''
if old not in s: raise SystemExit('v100 project score marker missing')
s=s.replace(old,new,1)
old='''            if(best==null||bs<threshold){int id=0;for(ActorScanStore.Cluster g:s.clusters)id=Math.max(id,g.id+1);best=new ActorScanStore.Cluster(id);best.centroid=lc.centroid.clone();FaceEngine.normalize(best.centroid);s.clusters.add(best);}\n            int old=best.count,total=old+lc.count;'''
new='''            float threshold100=best==null?threshold:V100IdentityEngine.threshold(context,s.projectKey,best.id,threshold);\n            boolean unknown100=best!=null && bs<threshold100;\n            if(best==null||unknown100){int id=0;for(ActorScanStore.Cluster g:s.clusters)id=Math.max(id,g.id+1);best=new ActorScanStore.Cluster(id);best.centroid=lc.centroid.clone();FaceEngine.normalize(best.centroid);s.clusters.add(best);if(unknown100&&context.getSharedPreferences("actor_sticker",Context.MODE_PRIVATE).getBoolean("unknown_person_v100",true))V100IdentityEngine.markNeedsReview(context,s.projectKey,best.id,bs,threshold100);}\n            V100IdentityEngine.observe(context,s.projectKey,best.id,lc.centroid,(float)(lc.qualitySum/Math.max(1,lc.count)));\n            int old=best.count,total=old+lc.count;'''
if old not in s: raise SystemExit('v100 dynamic threshold marker missing')
s=s.replace(old,new,1)
scan.write_text(s)

# ---- Actor Lock 2.0 + opt-in optical flow/body recovery before crop path ----
svc=Path('app/src/main/java/com/mhsx/actorsticker/V080ExportService.java')
s=svc.read_text()
old='''        boolean follow090 = prefs.getBoolean("v090_face_follow_enabled", false) && j.tracking;\n        TrackingPanEffect pan=new TrackingPanEffect(hits,j.startMs,inputAspect,targetAspect,follow090,j.safeZone);'''
new='''        boolean follow090 = prefs.getBoolean("v090_face_follow_enabled", false) && j.tracking;\n        if(follow090 && prefs.getBoolean("actor_lock2_v100",true)) hits=V100MotionEngine.actorLock(hits);\n        if(follow090 && prefs.getBoolean("optical_flow_v100",true)) hits=V100MotionEngine.refine(this,j.sourceUri,hits,j.startMs,j.endMs);\n        TrackingPanEffect pan=new TrackingPanEffect(hits,j.startMs,inputAspect,targetAspect,follow090,j.safeZone);'''
if old not in s: raise SystemExit('v100 export motion marker missing')
s=s.replace(old,new,1)
s=s.replace('new Intent(this,MainActivityV090.class)','new Intent(this,MainActivityV100.class)')
s=s.replace('Actor Sticker Cutter • v0.9','Actor Sticker Cutter • v1.0')
svc.write_text(s)

# ---- scan notification opens v1.0 ----
bg=Path('app/src/main/java/com/mhsx/actorsticker/BackgroundScanService.java')
s=bg.read_text().replace('MainActivityV090.class','MainActivityV100.class')
bg.write_text(s)

# ---- baseline profile production paths ----
prof=Path('app/src/main/baseline-prof.txt')
s=prof.read_text()
lines=[
'HSPLcom/mhsx/actorsticker/MainActivityV100;->onCreate(Landroid/os/Bundle;)V',
'Lcom/mhsx/actorsticker/V100IdentityEngine;',
'Lcom/mhsx/actorsticker/V100MotionEngine;',
'Lcom/mhsx/actorsticker/V100PerformanceKit;',
'Lcom/mhsx/actorsticker/V100RecoveryWorker;'
]
for line in lines:
    if line not in s:s+=line+'\n'
prof.write_text(s)

print('v1.0 production identity, motion, persistence and performance patch applied')
