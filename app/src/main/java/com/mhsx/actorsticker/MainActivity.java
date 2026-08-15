package com.mhsx.actorsticker;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.graphics.*;
import android.media.*;
import android.net.Uri;
import android.os.*;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.view.*;
import android.widget.*;

import androidx.media3.common.MediaItem;
import androidx.media3.effect.Crop;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.Transformer;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

public class MainActivity extends Activity {
    private static final int REQ_VIDEOS = 1001;
    private static final int REQ_REFS = 1002;
    private static final int REQ_OUTPUT = 1003;
    private static final long MIN_CLIP_MS = 2000L;
    private static final long DEFAULT_SAMPLE_MS = 750L;
    private static final long DEFAULT_MAX_CLIP_MS = 8000L;

    private final ArrayList<Uri> videos = new ArrayList<>();
    private final ArrayList<Uri> refs = new ArrayList<>();
    private Uri outputTree;
    private LinearLayout content;
    private TextView statusBar;
    private ProgressBar progress;
    private SharedPreferences prefs;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile boolean cancelRequested = false;
    private volatile boolean running = false;
    private volatile Transformer activeTransformer;
    private volatile float[] actorTemplate;
    private volatile int actorTemplateCount = 0;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("actor_sticker", MODE_PRIVATE);
        restoreState();
        buildShell();
        showQueue();
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private TextView text(String s, int sp, boolean bold) {
        TextView v = new TextView(this);
        v.setText(s); v.setTextSize(sp); v.setTextColor(0xff172033);
        if (bold) v.setTypeface(null, 1);
        v.setPadding(dp(4), dp(6), dp(4), dp(6));
        return v;
    }
    private Button button(String s, View.OnClickListener c) {
        Button b = new Button(this); b.setText(s); b.setAllCaps(false); b.setOnClickListener(c); return b;
    }
    private EditText edit(String hint, String value) {
        EditText e = new EditText(this); e.setHint(hint); e.setText(value); e.setSingleLine(true); return e;
    }

    private void buildShell() {
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(0xfff4f6fa);
        TextView title = text("Actor Sticker Cutter • Android", 22, true); title.setPadding(dp(14),dp(14),dp(14),dp(4)); root.addView(title);
        TextView sub = text("v0.2 • Offline face scan • Actor matching • Smart crop/export • 2s minimum", 12, false); sub.setPadding(dp(14),0,dp(14),dp(8)); root.addView(sub);

        HorizontalScrollView hs = new HorizontalScrollView(this); hs.setHorizontalScrollBarEnabled(false);
        LinearLayout tabs = new LinearLayout(this); tabs.setOrientation(LinearLayout.HORIZONTAL); tabs.setPadding(dp(8),0,dp(8),dp(4));
        tabs.addView(button("Queue", v -> showQueue()));
        tabs.addView(button("Actor", v -> showActor()));
        tabs.addView(button("System Info", v -> showSystem()));
        tabs.addView(button("Settings", v -> showSettings()));
        hs.addView(tabs); root.addView(hs);

        ScrollView scroll = new ScrollView(this); content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(dp(12),dp(8),dp(12),dp(16)); scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1,0,1));
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); progress.setMax(100); progress.setProgress(0); root.addView(progress, new LinearLayout.LayoutParams(-1,dp(10)));
        statusBar = text("Ready", 12, false); statusBar.setBackgroundColor(0xffe8edf6); statusBar.setPadding(dp(12),dp(8),dp(12),dp(8)); root.addView(statusBar);
        setContentView(root);
    }

    private void clear(String heading) { content.removeAllViews(); content.addView(text(heading, 19, true)); }
    private void line(String s) { content.addView(text(s, 14, false)); }
    private void setStatus(String s) { runOnUiThread(() -> statusBar.setText(s)); }
    private void setProgress(int p) { runOnUiThread(() -> progress.setProgress(Math.max(0,Math.min(100,p)))); }

    private void showQueue() {
        clear("Video Queue");
        content.addView(button("+ Add videos", v -> chooseVideos()));
        content.addView(button("Choose output folder", v -> chooseOutput()));
        line("Output: " + (outputTree == null ? "Not selected" : outputTree.toString()));
        line("Videos: " + videos.size());
        if (videos.isEmpty()) line("No videos in queue.");
        for (int i=0;i<videos.size();i++) line(String.format(Locale.US, "%02d  %s", i+1, displayName(videos.get(i))));
        content.addView(button("Validate queue (2s guard)", v -> validateQueue()));
        content.addView(button("Analyze actor + Crop/Export", v -> startProcessing()));
        content.addView(button("Cancel current job", v -> cancelJob()));
        content.addView(button("Clear queue", v -> { if(!running){videos.clear();saveState();showQueue();} }));
        TextView note = text("Face detection model is bundled in the APK and works offline. Clips shorter than 2.0 seconds are rejected before export. Smart crop follows the average actor position inside each detected scene.", 12, false);
        note.setBackgroundColor(0xffe8f5e9); note.setPadding(dp(10),dp(10),dp(10),dp(10)); content.addView(note);
    }

    private void showActor() {
        clear("Actor / References");
        content.addView(button("Select reference photos", v -> chooseRefs()));
        line("Reference images: " + refs.size());
        for (Uri u: refs) line("• " + displayName(u));
        line("Reference template: " + (actorTemplate == null ? "Not built" : (actorTemplateCount + " usable face(s)")));
        content.addView(button("Build / Refresh actor reference", v -> buildActorTemplateAsync(true)));
        content.addView(button("Clear references", v -> { if(!running){refs.clear();actorTemplate=null;actorTemplateCount=0;saveState();showActor();} }));
        line("Tip: use 3–6 clear photos: front, left/right angle, bright and low-light views.");
    }

    private void showSettings() {
        clear("Settings");
        EditText threshold = edit("Actor match threshold", String.format(Locale.US,"%.2f",prefs.getFloat("threshold",0.55f))); threshold.setInputType(0x2002); content.addView(threshold);
        EditText sample = edit("Scan interval ms", String.valueOf(prefs.getLong("sample_ms",DEFAULT_SAMPLE_MS))); sample.setInputType(2); content.addView(sample);
        EditText maxClip = edit("Max clip seconds", String.format(Locale.US,"%.1f",prefs.getLong("max_clip_ms",DEFAULT_MAX_CLIP_MS)/1000f)); maxClip.setInputType(0x2002); content.addView(maxClip);
        CheckBox keep = new CheckBox(this); keep.setText("Keep screen awake while processing"); keep.setChecked(prefs.getBoolean("keep_awake",true)); content.addView(keep);
        content.addView(button("Save settings", v -> {
            try {
                float th = Float.parseFloat(threshold.getText().toString().trim());
                long sm = Long.parseLong(sample.getText().toString().trim());
                float sec = Float.parseFloat(maxClip.getText().toString().trim());
                th = Math.max(0.20f,Math.min(0.95f,th)); sm = Math.max(250,Math.min(3000,sm)); long mm = Math.max(MIN_CLIP_MS,(long)(sec*1000f));
                prefs.edit().putFloat("threshold",th).putLong("sample_ms",sm).putLong("max_clip_ms",mm).putBoolean("keep_awake",keep.isChecked()).apply();
                setStatus("Settings saved");
            } catch(Exception e) { Toast.makeText(this,"Invalid settings",Toast.LENGTH_SHORT).show(); }
        }));
        line("Minimum clip duration is permanently locked to 2.0 seconds.");
        line("Output aspect ratio: 9:16 portrait, actor-centered.");
        line("Network Tools are removed. This build does not request INTERNET permission.");
    }

    private void showSystem() {
        clear("System Information");
        content.addView(button("Refresh", v -> showSystem()));
        line("Manufacturer: " + Build.MANUFACTURER);
        line("Brand / Model: " + Build.BRAND + " / " + Build.MODEL);
        line("Device / Product: " + Build.DEVICE + " / " + Build.PRODUCT);
        line("Hardware / Board: " + Build.HARDWARE + " / " + Build.BOARD);
        line("Android: " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")");
        line("Security patch: " + Build.VERSION.SECURITY_PATCH);
        line("ABIs: " + Arrays.toString(Build.SUPPORTED_ABIS));
        line("CPU cores: " + Runtime.getRuntime().availableProcessors());
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo(); ((ActivityManager)getSystemService(ACTIVITY_SERVICE)).getMemoryInfo(mi);
        line(String.format(Locale.US,"RAM: %.2f GB total • %.2f GB free • low=%s",mi.totalMem/1073741824.0,mi.availMem/1073741824.0,mi.lowMemory));
        StatFs sf = new StatFs(getFilesDir().getAbsolutePath());
        line(String.format(Locale.US,"App storage: %.2f GB free / %.2f GB",sf.getAvailableBytes()/1073741824.0,sf.getTotalBytes()/1073741824.0));
        Intent bat = registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (bat != null) {
            int level=bat.getIntExtra(BatteryManager.EXTRA_LEVEL,-1), scale=bat.getIntExtra(BatteryManager.EXTRA_SCALE,100), temp=bat.getIntExtra(BatteryManager.EXTRA_TEMPERATURE,0);
            line("Battery: "+Math.round(level*100f/Math.max(1,scale))+"% • "+String.format(Locale.US,"%.1f°C",temp/10f));
        }
        line("Video codecs (first 20):");
        try {
            MediaCodecInfo[] infos = new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos(); int shown=0;
            for (MediaCodecInfo info: infos) { if (shown>=20) break; for(String t:info.getSupportedTypes()) if(t.startsWith("video/")){ line("  " + (info.isEncoder()?"ENC ":"DEC ") + info.getName()+" • "+t); shown++; break; } }
        } catch(Exception e) { line("Codec query error: "+e.getMessage()); }
        line("Face engine: ML Kit bundled face detector 16.1.7 (offline)");
        line("Video engine: AndroidX Media3 Transformer 1.10.1");
        line("App version: 0.2.0");
    }

    private void chooseVideos() {
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT); i.setType("video/*"); i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true); i.addCategory(Intent.CATEGORY_OPENABLE); startActivityForResult(i,REQ_VIDEOS);
    }
    private void chooseRefs() {
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT); i.setType("image/*"); i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true); i.addCategory(Intent.CATEGORY_OPENABLE); startActivityForResult(i,REQ_REFS);
    }
    private void chooseOutput() {
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE); i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION); startActivityForResult(i,REQ_OUTPUT);
    }

    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data) {
        super.onActivityResult(requestCode,resultCode,data); if(resultCode!=RESULT_OK||data==null)return;
        int flags=data.getFlags()&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try {
            if(requestCode==REQ_OUTPUT){ outputTree=data.getData(); if(outputTree!=null)getContentResolver().takePersistableUriPermission(outputTree,flags); }
            else {
                ArrayList<Uri> dst=requestCode==REQ_VIDEOS?videos:refs;
                if(data.getClipData()!=null){ for(int x=0;x<data.getClipData().getItemCount();x++){Uri u=data.getClipData().getItemAt(x).getUri(); addUnique(dst,u); persist(u,Intent.FLAG_GRANT_READ_URI_PERMISSION);} }
                else if(data.getData()!=null){ Uri u=data.getData(); addUnique(dst,u); persist(u,Intent.FLAG_GRANT_READ_URI_PERMISSION); }
                if(requestCode==REQ_REFS){actorTemplate=null;actorTemplateCount=0;}
            }
        } catch(Exception ignored) {}
        saveState(); if(requestCode==REQ_REFS)showActor(); else showQueue();
    }
    private void persist(Uri u,int flags){ try{getContentResolver().takePersistableUriPermission(u,flags);}catch(Exception ignored){} }
    private void addUnique(ArrayList<Uri> list,Uri u){ if(!list.contains(u))list.add(u); }

    private FaceDetector newDetector() {
        FaceDetectorOptions options = new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                .setMinFaceSize(0.08f).build();
        return FaceDetection.getClient(options);
    }

    private void buildActorTemplateAsync(boolean showResult) {
        if(running){Toast.makeText(this,"A job is running",Toast.LENGTH_SHORT).show();return;}
        if(refs.isEmpty()){Toast.makeText(this,"Select reference photos first",Toast.LENGTH_SHORT).show();return;}
        setStatus("Building actor reference…"); setProgress(0);
        worker.execute(() -> {
            try {
                FaceDetector detector=newDetector(); ArrayList<float[]> descs=new ArrayList<>(); int done=0;
                for(Uri u:refs){
                    Bitmap b=loadBitmap(u); if(b!=null){ List<Face> faces=detect(detector,b); Face best=largest(faces); if(best!=null){float[] d=descriptor(b,best.getBoundingBox()); if(d!=null)descs.add(d);} b.recycle(); }
                    done++; setProgress(done*100/Math.max(1,refs.size()));
                }
                detector.close();
                if(descs.isEmpty()) throw new IllegalStateException("No usable face found in reference photos");
                actorTemplate=averageNormalized(descs); actorTemplateCount=descs.size(); setStatus("Actor reference ready: "+descs.size()+" face(s)");
                if(showResult) runOnUiThread(() -> new AlertDialog.Builder(this).setTitle("Actor reference ready").setMessage(actorTemplateCount+" reference face(s) accepted.\n\nNow add videos and run Analyze actor + Crop/Export.").setPositiveButton("OK",null).show());
            } catch(Exception e){ actorTemplate=null;actorTemplateCount=0; showError("Reference build failed",e); }
            setProgress(0);
        });
    }

    private Bitmap loadBitmap(Uri u) {
        try(InputStream in=getContentResolver().openInputStream(u)){ return BitmapFactory.decodeStream(in); } catch(Exception e){ return null; }
    }
    private List<Face> detect(FaceDetector detector,Bitmap b) throws Exception {
        return Tasks.await(detector.process(InputImage.fromBitmap(b,0)),15,TimeUnit.SECONDS);
    }
    private Face largest(List<Face> faces){ Face best=null; long area=-1; for(Face f:faces){Rect r=f.getBoundingBox(); long a=(long)r.width()*r.height(); if(a>area){area=a;best=f;}} return best; }

    private float[] descriptor(Bitmap src,Rect box) {
        Rect r=new Rect(Math.max(0,box.left),Math.max(0,box.top),Math.min(src.getWidth(),box.right),Math.min(src.getHeight(),box.bottom));
        if(r.width()<20||r.height()<20)return null;
        Bitmap crop=Bitmap.createBitmap(src,r.left,r.top,r.width(),r.height()); Bitmap small=Bitmap.createScaledBitmap(crop,20,20,true); if(crop!=small)crop.recycle();
        float[] v=new float[400]; double mean=0; int k=0;
        for(int y=0;y<20;y++)for(int x=0;x<20;x++){int c=small.getPixel(x,y); float g=(Color.red(c)*0.299f+Color.green(c)*0.587f+Color.blue(c)*0.114f)/255f; v[k++]=g;mean+=g;}
        small.recycle(); mean/=v.length; double norm=0; for(int i=0;i<v.length;i++){v[i]-=(float)mean;norm+=v[i]*v[i];} norm=Math.sqrt(norm)+1e-8; for(int i=0;i<v.length;i++)v[i]/=(float)norm; return v;
    }
    private float cosine(float[] a,float[] b){ if(a==null||b==null||a.length!=b.length)return -1; float s=0; for(int i=0;i<a.length;i++)s+=a[i]*b[i]; return s; }
    private float[] averageNormalized(List<float[]> ds){ float[] a=new float[ds.get(0).length]; for(float[] d:ds)for(int i=0;i<a.length;i++)a[i]+=d[i]; double n=0;for(float x:a)n+=x*x;n=Math.sqrt(n)+1e-8;for(int i=0;i<a.length;i++)a[i]/=(float)n;return a; }

    private void validateQueue() {
        if(videos.isEmpty()){Toast.makeText(this,"Add videos first",Toast.LENGTH_SHORT).show();return;}
        setStatus("Validating video durations…"); worker.execute(() -> {
            int valid=0,rejected=0,unknown=0; StringBuilder r=new StringBuilder();
            for(Uri u:videos){ long d=durationMs(u); if(d>0&&d<MIN_CLIP_MS){rejected++;r.append("REJECT <2s: ").append(displayName(u)).append('\n');} else if(d<=0){unknown++;r.append("UNKNOWN: ").append(displayName(u)).append('\n');} else {valid++;r.append(String.format(Locale.US,"OK %.1fs: %s\n",d/1000.0,displayName(u)));} }
            final String msg="Valid: "+valid+" • Rejected <2s: "+rejected+" • Unknown: "+unknown+"\n\n"+r;
            runOnUiThread(() -> new AlertDialog.Builder(this).setTitle("Queue validation").setMessage(msg).setPositiveButton("OK",null).show()); setStatus("Validation finished");
        });
    }

    private void startProcessing() {
        if(running){Toast.makeText(this,"Job already running",Toast.LENGTH_SHORT).show();return;}
        if(videos.isEmpty()){Toast.makeText(this,"Add videos first",Toast.LENGTH_SHORT).show();return;}
        if(refs.isEmpty()){Toast.makeText(this,"Select actor reference photos",Toast.LENGTH_SHORT).show();return;}
        if(outputTree==null){Toast.makeText(this,"Choose output folder",Toast.LENGTH_SHORT).show();return;}
        running=true;cancelRequested=false; if(prefs.getBoolean("keep_awake",true))getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        worker.execute(() -> {
            int totalClips=0; ArrayList<String> report=new ArrayList<>();
            try {
                if(actorTemplate==null) buildTemplateBlocking();
                FaceDetector detector=newDetector();
                for(int vi=0;vi<videos.size()&&!cancelRequested;vi++){
                    Uri video=videos.get(vi); setStatus("Scanning "+(vi+1)+"/"+videos.size()+": "+displayName(video));
                    List<Segment> segments=scanVideo(video,detector,vi,videos.size());
                    if(cancelRequested)break;
                    report.add(displayName(video)+": "+segments.size()+" scene(s)");
                    int ci=0;
                    for(Segment s:segments){
                        if(cancelRequested)break;
                        if(s.endMs-s.startMs<MIN_CLIP_MS){report.add("  skipped <2s");continue;}
                        ci++; String name=safeBase(displayName(video))+String.format(Locale.US,"_actor_%02d_%dms.mp4",ci,s.startMs);
                        setStatus("Exporting "+name);
                        File temp=exportSegmentBlocking(video,s,name);
                        if(temp!=null&&temp.isFile()&&temp.length()>0){copyToOutputTree(temp,name);temp.delete();totalClips++;report.add("  OK "+name+String.format(Locale.US,"  %.1fs  score %.2f",(s.endMs-s.startMs)/1000f,s.score));}
                    }
                }
                detector.close();
                final int clips=totalClips; final String msg=(cancelRequested?"Cancelled\n\n":"Completed\n\n")+"Exported clips: "+clips+"\n\n"+joinLines(report,80);
                runOnUiThread(() -> new AlertDialog.Builder(this).setTitle("Processing result").setMessage(msg).setPositiveButton("OK",null).show());
                setStatus(cancelRequested?"Cancelled":"Finished • "+clips+" clip(s)");
            } catch(Exception e){ showError("Processing failed",e); }
            finally {running=false;activeTransformer=null;setProgress(0);runOnUiThread(() -> getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));}
        });
    }

    private void buildTemplateBlocking() throws Exception {
        FaceDetector detector=newDetector(); ArrayList<float[]> ds=new ArrayList<>();
        for(Uri u:refs){Bitmap b=loadBitmap(u);if(b!=null){Face f=largest(detect(detector,b));if(f!=null){float[] d=descriptor(b,f.getBoundingBox());if(d!=null)ds.add(d);}b.recycle();}}
        detector.close(); if(ds.isEmpty())throw new IllegalStateException("No usable face found in references"); actorTemplate=averageNormalized(ds);actorTemplateCount=ds.size();
    }

    private List<Segment> scanVideo(Uri uri,FaceDetector detector,int videoIndex,int videoCount) throws Exception {
        MediaMetadataRetriever mr=new MediaMetadataRetriever(); mr.setDataSource(this,uri);
        long duration=parseLong(mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION),-1); if(duration<MIN_CLIP_MS){mr.release();return Collections.emptyList();}
        long sampleMs=prefs.getLong("sample_ms",DEFAULT_SAMPLE_MS); float threshold=prefs.getFloat("threshold",0.55f); ArrayList<Hit> hits=new ArrayList<>();
        long steps=Math.max(1,(duration+sampleMs-1)/sampleMs); long step=0;
        for(long t=0;t<duration&&!cancelRequested;t+=sampleMs){
            Bitmap frame=null;
            try {frame=mr.getFrameAtTime(t*1000,MediaMetadataRetriever.OPTION_CLOSEST); if(frame==null){step++;continue;} List<Face> faces=detect(detector,frame); Hit best=null;
                for(Face f:faces){float[] d=descriptor(frame,f.getBoundingBox());float sc=cosine(actorTemplate,d);if(sc>=threshold&&(best==null||sc>best.score)){Rect b=f.getBoundingBox();best=new Hit(t,(b.centerX()/(float)frame.getWidth()),(b.centerY()/(float)frame.getHeight()),frame.getWidth(),frame.getHeight(),sc);}}
                if(best!=null)hits.add(best);
            } catch(Exception ignored) {} finally {if(frame!=null&&!frame.isRecycled())frame.recycle();}
            step++; int local=(int)(step*100/steps); int overall=(int)(((videoIndex + local/100.0)/Math.max(1,videoCount))*100); setProgress(overall);
        }
        mr.release(); return buildSegments(hits,duration,sampleMs,prefs.getLong("max_clip_ms",DEFAULT_MAX_CLIP_MS));
    }

    private List<Segment> buildSegments(List<Hit> hits,long duration,long sampleMs,long maxClipMs) {
        ArrayList<Segment> out=new ArrayList<>(); if(hits.isEmpty())return out; long gap=Math.max(1400,(long)(sampleMs*2.4)); int i=0;
        while(i<hits.size()){
            int j=i; while(j+1<hits.size()&&hits.get(j+1).t-hits.get(j).t<=gap)j++;
            long rawStart=Math.max(0,hits.get(i).t-500), rawEnd=Math.min(duration,hits.get(j).t+1000); if(rawEnd-rawStart>=MIN_CLIP_MS){
                long chunkStart=rawStart;
                while(chunkStart<rawEnd){long chunkEnd=Math.min(rawEnd,chunkStart+maxClipMs); if(rawEnd-chunkEnd>0&&rawEnd-chunkEnd<MIN_CLIP_MS)chunkEnd=rawEnd; if(chunkEnd-chunkStart>=MIN_CLIP_MS){float sx=0,sy=0,ss=0;int n=0,fw=0,fh=0;for(int k=i;k<=j;k++){Hit h=hits.get(k);if(h.t>=chunkStart&&h.t<=chunkEnd){sx+=h.cx*h.score;sy+=h.cy*h.score;ss+=h.score;n++;fw=h.fw;fh=h.fh;}}if(n==0){Hit h=hits.get(i);sx=h.cx;sy=h.cy;ss=h.score;fw=h.fw;fh=h.fh;n=1;}float denom=Math.max(0.001f,ss);out.add(new Segment(chunkStart,chunkEnd,sx/denom,sy/denom,fw,fh,ss/n));} chunkStart=chunkEnd;}
            } i=j+1;
        } return out;
    }

    private File exportSegmentBlocking(Uri source,Segment s,String name) throws Exception {
        File root=getExternalFilesDir(null);if(root==null)root=getFilesDir();File dir=new File(root,"exports");if(!dir.exists()&&!dir.mkdirs())throw new IOException("Cannot create temp export directory");File out=new File(dir,name);if(out.exists())out.delete();
        MediaItem.ClippingConfiguration clip=new MediaItem.ClippingConfiguration.Builder().setStartPositionMs(s.startMs).setEndPositionMs(s.endMs).build();
        MediaItem item=new MediaItem.Builder().setUri(source).setClippingConfiguration(clip).build();
        Crop crop=createPortraitCrop(s); Effects effects=new Effects(Collections.emptyList(),Collections.singletonList(crop)); EditedMediaItem edited=new EditedMediaItem.Builder(item).setEffects(effects).build();
        CountDownLatch latch=new CountDownLatch(1); AtomicReference<Exception> error=new AtomicReference<>();
        runOnUiThread(() -> {
            try {
                Transformer tr=new Transformer.Builder(this).addListener(new Transformer.Listener(){
                    @Override public void onCompleted(Composition composition, ExportResult result){activeTransformer=null;latch.countDown();}
                    @Override public void onError(Composition composition, ExportResult result, ExportException ex){activeTransformer=null;error.set(ex);latch.countDown();}
                }).build(); activeTransformer=tr; tr.start(edited,out.getAbsolutePath());
            } catch(Exception e){error.set(e);latch.countDown();}
        });
        if(!latch.await(20,TimeUnit.MINUTES)){runOnUiThread(() -> {if(activeTransformer!=null)activeTransformer.cancel();});throw new TimeoutException("Export timeout");}
        if(cancelRequested){if(out.exists())out.delete();return null;} if(error.get()!=null)throw error.get(); if(!out.isFile()||out.length()==0)throw new IOException("Empty export file"); return out;
    }

    private Crop createPortraitCrop(Segment s) {
        float w=Math.max(1,s.fw),h=Math.max(1,s.fh),aspect=9f/16f;float left=0,top=0,right=w,bottom=h;
        if(w/h>aspect){float cw=h*aspect;left=s.cx*w-cw/2;left=Math.max(0,Math.min(w-cw,left));right=left+cw;}else{float ch=w/aspect;top=s.cy*h-ch/2;top=Math.max(0,Math.min(h-ch,top));bottom=top+ch;}
        float l=-1f+2f*(left/w), r=-1f+2f*(right/w), t=1f-2f*(top/h), b=1f-2f*(bottom/h); return new Crop(l,r,b,t);
    }

    private void copyToOutputTree(File src,String name) throws Exception {
        String treeId=DocumentsContract.getTreeDocumentId(outputTree); Uri parent=DocumentsContract.buildDocumentUriUsingTree(outputTree,treeId); Uri dest=DocumentsContract.createDocument(getContentResolver(),parent,"video/mp4",name); if(dest==null)throw new IOException("Cannot create output document");
        try(InputStream in=new BufferedInputStream(new FileInputStream(src));OutputStream out=new BufferedOutputStream(getContentResolver().openOutputStream(dest,"w"))){if(out==null)throw new IOException("Cannot open output stream");byte[] buf=new byte[1024*1024];int n;while((n=in.read(buf))>0){if(cancelRequested)break;out.write(buf,0,n);}out.flush();}
    }

    private void cancelJob(){cancelRequested=true;setStatus("Cancelling…");runOnUiThread(() -> {try{if(activeTransformer!=null)activeTransformer.cancel();}catch(Exception ignored){}});}
    private void showError(String title,Exception e){setStatus(title+": "+e.getClass().getSimpleName());runOnUiThread(() -> new AlertDialog.Builder(this).setTitle(title).setMessage(e.toString()).setPositiveButton("OK",null).show());}

    private long durationMs(Uri u){MediaMetadataRetriever m=new MediaMetadataRetriever();try{m.setDataSource(this,u);return parseLong(m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION),-1);}catch(Exception e){return -1;}finally{try{m.release();}catch(Exception ignored){}}}
    private long parseLong(String s,long def){try{return s==null?def:Long.parseLong(s);}catch(Exception e){return def;}}
    private String safeBase(String n){int dot=n.lastIndexOf('.');if(dot>0)n=n.substring(0,dot);return n.replaceAll("[^A-Za-z0-9._-]+","_");}
    private String joinLines(List<String> xs,int max){StringBuilder b=new StringBuilder();int n=Math.min(max,xs.size());for(int i=0;i<n;i++)b.append(xs.get(i)).append('\n');if(xs.size()>max)b.append("… ").append(xs.size()-max).append(" more");return b.toString();}
    private String displayName(Uri u){try(Cursor c=getContentResolver().query(u,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst())return c.getString(0);}catch(Exception ignored){}return u.getLastPathSegment()==null?u.toString():u.getLastPathSegment();}

    private void saveState(){Set<String> vs=new LinkedHashSet<>(),rs=new LinkedHashSet<>();for(Uri u:videos)vs.add(u.toString());for(Uri u:refs)rs.add(u.toString());SharedPreferences.Editor e=prefs.edit().putStringSet("videos",vs).putStringSet("refs",rs);if(outputTree!=null)e.putString("output",outputTree.toString());e.apply();}
    private void restoreState(){for(String s:prefs.getStringSet("videos",Collections.emptySet()))try{videos.add(Uri.parse(s));}catch(Exception ignored){}for(String s:prefs.getStringSet("refs",Collections.emptySet()))try{refs.add(Uri.parse(s));}catch(Exception ignored){}String out=prefs.getString("output",null);if(out!=null)outputTree=Uri.parse(out);}

    @Override protected void onDestroy(){cancelRequested=true;try{if(activeTransformer!=null)activeTransformer.cancel();}catch(Exception ignored){}worker.shutdownNow();super.onDestroy();}

    private static final class Hit {final long t;final float cx,cy;final int fw,fh;final float score;Hit(long t,float cx,float cy,int fw,int fh,float score){this.t=t;this.cx=cx;this.cy=cy;this.fw=fw;this.fh=fh;this.score=score;}}
    private static final class Segment {final long startMs,endMs;final float cx,cy;final int fw,fh;final float score;Segment(long s,long e,float cx,float cy,int fw,int fh,float score){startMs=s;endMs=e;this.cx=cx;this.cy=cy;this.fw=fw;this.fh=fh;this.score=score;}}
}
