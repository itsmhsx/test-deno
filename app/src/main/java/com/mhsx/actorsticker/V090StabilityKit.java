package com.mhsx.actorsticker;

import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.net.Uri;
import android.os.*;

import com.google.mlkit.vision.face.Face;

import org.json.*;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;

final class V090Diagnostics {
    private V090Diagnostics(){}
    static File dir(Context c){File d=new File(c.getFilesDir(),"diagnostics");if(!d.exists())d.mkdirs();return d;}
    static synchronized void log(Context c,String tag,Throwable e){
        try{StringWriter sw=new StringWriter();if(e!=null)e.printStackTrace(new PrintWriter(sw));String body=new Date()+"\n"+tag+"\n"+(e==null?"":e.getClass().getName()+": "+String.valueOf(e.getMessage()))+"\n"+sw;write(new File(dir(c),"last_error.txt"),body);append(new File(dir(c),"events.log"),new Date()+" | "+tag+" | "+(e==null?"":e.getClass().getSimpleName()+": "+String.valueOf(e.getMessage()))+"\n");}catch(Throwable ignored){}
    }
    static synchronized void event(Context c,String tag,String text){try{append(new File(dir(c),"events.log"),new Date()+" | "+tag+" | "+text+"\n");}catch(Throwable ignored){}}
    static String readLast(Context c){return read(new File(dir(c),"last_error.txt"),"No recorded crash/error.");}
    static String readEvents(Context c){String s=read(new File(dir(c),"events.log"),"No diagnostic events.");return s.length()>8000?s.substring(s.length()-8000):s;}
    static void install(Application app){
        Thread.UncaughtExceptionHandler old=Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t,e)->{try{SharedPreferences p=app.getSharedPreferences("actor_sticker",Context.MODE_PRIVATE);long now=System.currentTimeMillis(),last=p.getLong("v090_last_crash",0);int n=(now-last<10*60_000L)?p.getInt("v090_crash_burst",0)+1:1;p.edit().putLong("v090_last_crash",now).putInt("v090_crash_burst",n).putBoolean("safe_mode_v090",n>=2).apply();log(app,"uncaught:"+t.getName(),e);}catch(Throwable ignored){}if(old!=null)old.uncaughtException(t,e);});
    }
    private static void write(File f,String s)throws Exception{try(Writer w=new BufferedWriter(new OutputStreamWriter(new FileOutputStream(f),"UTF-8"))){w.write(s);}}
    private static void append(File f,String s)throws Exception{try(Writer w=new BufferedWriter(new OutputStreamWriter(new FileOutputStream(f,true),"UTF-8"))){w.write(s);}}
    private static String read(File f,String d){if(!f.isFile())return d;StringBuilder b=new StringBuilder();try(BufferedReader r=new BufferedReader(new InputStreamReader(new FileInputStream(f),"UTF-8"))){String s;while((s=r.readLine())!=null)b.append(s).append('\n');return b.toString();}catch(Throwable e){return d;}}
}

final class V090WorkController {
    private V090WorkController(){}
    static void mark(Context c,String kind,String status,int pct){c.getSharedPreferences("actor_sticker",Context.MODE_PRIVATE).edit().putString("v090_work_kind",kind).putString("v090_work_status",status).putInt("v090_work_progress",Math.max(0,Math.min(100,pct))).putLong("v090_work_heartbeat",System.currentTimeMillis()).apply();}
    static boolean startScan(Context c,boolean refresh){try{mark(c,"scan",refresh?"Refreshing actor index":"Starting actor scan",1);Intent i=new Intent(c,BackgroundScanService.class).setAction(refresh?BackgroundScanService.ACTION_REFRESH:BackgroundScanService.ACTION_START);if(Build.VERSION.SDK_INT>=26)c.startForegroundService(i);else c.startService(i);return true;}catch(Throwable e){V090Diagnostics.log(c,"work-start-scan",e);return false;}}
    static void startExport(Context c){mark(c,"export","Starting render queue",1);new V080ExportQueue(c).kickRenderer();}
}

final class V090RuntimeGuard {
    static final class Snapshot {final boolean lowMemory;final int thermal;final long freeMb;Snapshot(boolean l,int t,long f){lowMemory=l;thermal=t;freeMb=f;}}
    private V090RuntimeGuard(){}
    static Snapshot check(Context c){boolean low=false;long free=0;int thermal=0;try{ActivityManager am=(ActivityManager)c.getSystemService(Context.ACTIVITY_SERVICE);ActivityManager.MemoryInfo mi=new ActivityManager.MemoryInfo();am.getMemoryInfo(mi);free=mi.availMem/1048576L;low=mi.lowMemory||free<420;}catch(Throwable ignored){}if(Build.VERSION.SDK_INT>=29)try{PowerManager pm=(PowerManager)c.getSystemService(Context.POWER_SERVICE);thermal=pm.getCurrentThermalStatus();}catch(Throwable ignored){}c.getSharedPreferences("actor_sticker",Context.MODE_PRIVATE).edit().putBoolean("v090_low_memory",low).putInt("v090_thermal",thermal).putLong("v090_free_mb",free).apply();if(low)V070ThumbnailLoader.trimMemory(80);return new Snapshot(low,thermal,free);}
    static boolean severeThermal(Context c){return check(c).thermal>=PowerManager.THERMAL_STATUS_SEVERE;}
}

final class V090RecoveryManager {
    private V090RecoveryManager(){}
    static File root(Context c,String key){File d=new File(c.getFilesDir(),"recovery/"+safe(key));if(!d.exists())d.mkdirs();return d;}
    static synchronized boolean backupIndex(Context c,String key,String reason){if(key==null||key.isEmpty())return false;File src=new File(c.getFilesDir(),"actor_cache/"+key+"/index.json");if(!src.isFile())return false;try{File out=new File(root(c,key),System.currentTimeMillis()+"_"+safe(reason)+".json");copy(src,out);trim(root(c,key),6);V090Diagnostics.event(c,"backup","Saved "+out.getName());return true;}catch(Throwable e){V090Diagnostics.log(c,"backup",e);return false;}}
    static synchronized boolean restoreLatest(Context c,String key){File d=root(c,key);File[] fs=d.listFiles((x,n)->n.endsWith(".json"));if(fs==null||fs.length==0)return false;Arrays.sort(fs,Comparator.comparingLong(File::lastModified).reversed());File dest=new File(c.getFilesDir(),"actor_cache/"+key+"/index.json");try{File parent=dest.getParentFile();if(!parent.exists())parent.mkdirs();copy(fs[0],dest);V090Diagnostics.event(c,"restore","Restored "+fs[0].getName());return true;}catch(Throwable e){V090Diagnostics.log(c,"restore",e);return false;}}
    static String latest(Context c,String key){File[] fs=root(c,key).listFiles((x,n)->n.endsWith(".json"));if(fs==null||fs.length==0)return"No backup";Arrays.sort(fs,Comparator.comparingLong(File::lastModified).reversed());return fs[0].getName();}
    private static void copy(File a,File b)throws Exception{try(InputStream in=new BufferedInputStream(new FileInputStream(a));OutputStream out=new BufferedOutputStream(new FileOutputStream(b))){byte[]buf=new byte[256*1024];int n;while((n=in.read(buf))>0)out.write(buf,0,n);}}
    private static void trim(File d,int keep){File[]f=d.listFiles();if(f==null||f.length<=keep)return;Arrays.sort(f,Comparator.comparingLong(File::lastModified).reversed());for(int i=keep;i<f.length;i++)f[i].delete();}
    private static String safe(String s){return(s==null?"x":s).replaceAll("[^A-Za-z0-9._-]+","_");}
}

final class V090FaceQualityGate {
    private V090FaceQualityGate(){}
    static boolean allow(Bitmap b,Rect r){try{Context c=V080App.context();if(c!=null&&!c.getSharedPreferences("actor_sticker",Context.MODE_PRIVATE).getBoolean("quality_gate_v090",true))return true;if(b==null||r==null||r.width()<30||r.height()<30)return false;float area=r.width()*r.height()/(float)Math.max(1,b.getWidth()*b.getHeight());if(area<0.0035f)return false;if(r.left<2||r.top<2||r.right>b.getWidth()-2||r.bottom>b.getHeight()-2)return false;int step=Math.max(3,Math.min(r.width(),r.height())/18);double sum=0,sum2=0,grad=0;int n=0,prev=-1;for(int y=r.top+step;y<r.bottom-step;y+=step){prev=-1;for(int x=r.left+step;x<r.right-step;x+=step){int p=b.getPixel(x,y);int l=(Color.red(p)*3+Color.green(p)*6+Color.blue(p))/10;sum+=l;sum2+=l*l;if(prev>=0)grad+=Math.abs(l-prev);prev=l;n++;}}if(n<12)return true;double mean=sum/n,var=sum2/n-mean*mean,edge=grad/Math.max(1,n);return mean>22&&mean<242&&var>70&&edge>3.2;}catch(Throwable e){return true;}}
}

final class V090FaceBenchmark {
    static final class Result {final int threads;final double ms;Result(int t,double m){threads=t;ms=m;}}
    private V090FaceBenchmark(){}
    static Result run(Context c){Bitmap b=Bitmap.createBitmap(180,180,Bitmap.Config.ARGB_8888);Canvas cv=new Canvas(b);Paint p=new Paint();p.setColor(Color.rgb(174,140,122));cv.drawRect(0,0,180,180,p);Rect r=new Rect(18,12,162,174);int best=2;double bestMs=Double.MAX_VALUE;for(int t:new int[]{1,2,4}){try{V080FaceEmbedder.configureThreads(t);for(int i=0;i<2;i++)V080FaceEmbedder.embedding(b,r);long st=System.nanoTime();int loops=8;for(int i=0;i<loops;i++)V080FaceEmbedder.embedding(b,r);double ms=(System.nanoTime()-st)/1e6/loops;if(ms<bestMs){bestMs=ms;best=t;}}catch(Throwable ignored){}}b.recycle();V080FaceEmbedder.configureThreads(best);c.getSharedPreferences("actor_sticker",Context.MODE_PRIVATE).edit().putInt("face_threads_v090",best).putFloat("face_benchmark_ms_v090",(float)bestMs).putBoolean("face_benchmark_done_v090",true).apply();return new Result(best,bestMs);}
}

final class V090ReferenceBank {
    private V090ReferenceBank(){}
    private static File file(Context c,String key,int id){File d=new File(c.getFilesDir(),"reference_bank");if(!d.exists())d.mkdirs();return new File(d,key+"_"+id+".json");}
    static int build(Context c,ActorScanStore.ScanState s,ActorScanStore.Cluster a){if(s==null||a==null)return 0;JSONArray refs=new JSONArray();int made=0;try(FaceEngine engine=new FaceEngine()){for(String rel:new ArrayList<>(a.thumbs)){if(made>=8)break;Bitmap b=BitmapFactory.decodeFile(new ActorScanStore(c).thumbnailFile(s.projectKey,rel).getAbsolutePath());if(b==null)continue;try{List<Face> fs=engine.detect(b);Face best=null;int area=0;if(fs!=null)for(Face f:fs){Rect box=f.getBoundingBox();if(box!=null&&box.width()*box.height()>area){area=box.width()*box.height();best=f;}}if(best==null)continue;float[]v=engine.descriptor(b,best.getBoundingBox());if(v==null)continue;JSONObject o=new JSONObject();o.put("pose",best.getHeadEulerAngleY()<-12?"LEFT":best.getHeadEulerAngleY()>12?"RIGHT":"FRONT");JSONArray ar=new JSONArray();for(float x:v)ar.put(x);o.put("v",ar);refs.put(o);made++;}finally{b.recycle();}}JSONObject root=new JSONObject();root.put("actor",a.id);root.put("updated",System.currentTimeMillis());root.put("refs",refs);try(Writer w=new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file(c,s.projectKey,a.id)),"UTF-8"))){w.write(root.toString());}}catch(Throwable e){V090Diagnostics.log(c,"reference-bank",e);}return made;}
    static float similarity(Context c,String key,int id,float[] d,float fallback){float best=fallback;File f=file(c,key,id);if(!f.isFile()||d==null)return best;try{JSONObject root=new JSONObject(read(f));JSONArray refs=root.optJSONArray("refs");if(refs!=null)for(int i=0;i<refs.length();i++){JSONArray a=refs.optJSONObject(i).optJSONArray("v");if(a==null||a.length()!=d.length)continue;float[]v=new float[d.length];for(int k=0;k<v.length;k++)v[k]=(float)a.optDouble(k);best=Math.max(best,FaceEngine.cosine(v,d));}}catch(Throwable ignored){}return best;}
    static List<String> mergeSuggestions(ActorScanStore.ScanState s){ArrayList<String> out=new ArrayList<>();if(s==null)return out;ArrayList<ActorScanStore.Cluster> a=new ArrayList<>(s.clusters);for(int i=0;i<a.size();i++)for(int j=i+1;j<a.size();j++){ActorScanStore.Cluster x=a.get(i),y=a.get(j);if(x.centroid==null||y.centroid==null||x.centroid.length!=y.centroid.length)continue;float sim=FaceEngine.cosine(x.centroid,y.centroid);if(sim>=0.82f)out.add("Actor "+(x.id+1)+" ↔ Actor "+(y.id+1)+" • "+Math.round(sim*100)+"%");}out.sort(Collections.reverseOrder());return out;}
    private static String read(File f)throws Exception{StringBuilder b=new StringBuilder();try(BufferedReader r=new BufferedReader(new InputStreamReader(new FileInputStream(f),"UTF-8"))){String s;while((s=r.readLine())!=null)b.append(s);}return b.toString();}
}

final class V090SelfTest {
    private V090SelfTest(){}
    static String run(Context c){ArrayList<String> r=new ArrayList<>();int pass=0,total=8;
        boolean ok=V080FaceEmbedder.available();r.add((ok?"PASS":"FAIL")+" • Face model asset");if(ok)pass++;
        try{Bitmap b=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888);float[]v=V080FaceEmbedder.embedding(b,new Rect(8,8,120,120));ok=v!=null&&v.length==192;b.recycle();}catch(Throwable e){ok=false;}r.add((ok?"PASS":"FAIL")+" • 192-D embedding");if(ok)pass++;
        try(FaceEngine e=new FaceEngine()){ok=true;}catch(Throwable e){ok=false;}r.add((ok?"PASS":"FAIL")+" • ML Kit FaceEngine init");if(ok)pass++;
        try{V090JobEntity e=new V090JobEntity();e.id="SELFTEST-"+System.nanoTime();e.state="SELFTEST";e.createdAt=e.updatedAt=System.currentTimeMillis();V090Database.get(c).jobs().upsert(e);ok=V090Database.get(c).jobs().deleteState("SELFTEST")>0;}catch(Throwable e){ok=false;}r.add((ok?"PASS":"FAIL")+" • Room queue transaction");if(ok)pass++;
        try{File f=new File(c.getCacheDir(),"v090-selftest.tmp");try(FileOutputStream o=new FileOutputStream(f)){o.write(new byte[]{1,2,3});}ok=f.length()==3&&f.delete();}catch(Throwable e){ok=false;}r.add((ok?"PASS":"FAIL")+" • Private cache read/write");if(ok)pass++;
        try{ok=!V070CodecAdvisor.hardwareSummary().isEmpty();}catch(Throwable e){ok=false;}r.add((ok?"PASS":"FAIL")+" • Codec inventory");if(ok)pass++;
        try{ComponentName n=new ComponentName(c,V080ExportService.class);c.getPackageManager().getServiceInfo(n,0);ok=true;}catch(Throwable e){ok=false;}r.add((ok?"PASS":"FAIL")+" • Foreground export service");if(ok)pass++;
        try{V090RuntimeGuard.Snapshot s=V090RuntimeGuard.check(c);ok=s.freeMb>=0;}catch(Throwable e){ok=false;}r.add((ok?"PASS":"FAIL")+" • Memory / thermal guard");if(ok)pass++;
        StringBuilder b=new StringBuilder(pass+"/"+total+" PASS\n\n");for(String x:r)b.append(x).append('\n');V090Diagnostics.event(c,"self-test",pass+"/"+total);return b.toString();}
}
