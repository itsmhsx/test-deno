package com.mhsx.actorsticker;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.Rect;
import android.util.Base64;

import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceLandmark;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;

/**
 * v1.0 identity layer.
 * - landmark affine alignment before MobileFaceNet
 * - persistent multi-reference memory per actor
 * - hard-negative memory
 * - per-actor calibrated thresholds
 * - explicit needs-review / Unknown marking
 */
final class V100IdentityEngine {
    private static final int MAX_REFS = 8;
    private static final int MAX_NEG = 8;
    private V100IdentityEngine() {}

    static float[] alignedEmbedding(Context context, Bitmap frame, Face face, Rect fallbackBox, FaceEngine fallback) {
        if (frame == null || fallbackBox == null) return null;
        String mode = prefs(context).getString("identity_accuracy_v100", "Balanced");
        if (!"Fast".equals(mode) && face != null) {
            Bitmap aligned = align112(frame, face);
            if (aligned != null) {
                try {
                    float[] v = V080FaceEmbedder.embedding(aligned, new Rect(2, 2, 110, 110));
                    if (v != null && v.length == 192) return v;
                } finally { if (!aligned.isRecycled()) aligned.recycle(); }
            }
        }
        return fallback == null ? null : fallback.descriptor(frame, fallbackBox);
    }

    /** 3-point affine alignment: left eye, right eye and mouth/nose -> canonical 112x112 face. */
    static Bitmap align112(Bitmap frame, Face face) {
        try {
            FaceLandmark le = face.getLandmark(FaceLandmark.LEFT_EYE);
            FaceLandmark re = face.getLandmark(FaceLandmark.RIGHT_EYE);
            FaceLandmark mouth = face.getLandmark(FaceLandmark.MOUTH_BOTTOM);
            if (mouth == null) mouth = face.getLandmark(FaceLandmark.NOSE_BASE);
            if (le == null || re == null || mouth == null) return null;
            PointF a = le.getPosition(), b = re.getPosition(), c = mouth.getPosition();
            if (a == null || b == null || c == null) return null;
            float eyeDist = (float)Math.hypot(a.x-b.x, a.y-b.y);
            if (eyeDist < 10f) return null;
            float[] src = {a.x,a.y,b.x,b.y,c.x,c.y};
            float[] dst = {38f,42f,74f,42f,56f,80f};
            Matrix m = new Matrix();
            if (!m.setPolyToPoly(src,0,dst,0,3)) return null;
            Bitmap out = Bitmap.createBitmap(112,112,Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(out);
            canvas.drawARGB(255, 18, 18, 18);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            canvas.drawBitmap(frame,m,p);
            return out;
        } catch (Throwable ignored) { return null; }
    }

    static float score(Context c, String project, int actorId, float[] candidate, float fallback) {
        if (candidate == null) return fallback;
        Model m = load(c, project, actorId);
        if (m.refs.isEmpty()) return fallback;
        ArrayList<Float> scores = new ArrayList<>();
        for (float[] r : m.refs) {
            float s = FaceEngine.cosine(r,candidate);
            if (Float.isFinite(s)) scores.add(s);
        }
        if (scores.isEmpty()) return fallback;
        scores.sort(Collections.reverseOrder());
        float vote = scores.get(0);
        if (scores.size() >= 2) vote = vote*0.62f + scores.get(1)*0.38f;
        if (scores.size() >= 3) vote = vote*0.84f + scores.get(2)*0.16f;
        float positive = Math.max(fallback, vote);
        float negMax = -1f;
        for (float[] n : m.negatives) negMax = Math.max(negMax, FaceEngine.cosine(n,candidate));
        if (negMax > 0f && negMax > positive - 0.055f) {
            positive -= Math.min(0.14f, 0.055f + Math.max(0f, negMax-positive)*0.45f);
        }
        return clamp(positive,-1f,1f);
    }

    static float threshold(Context c, String project, int actorId, float base) {
        Model m = load(c,project,actorId);
        if (m.refs.size() < 2) return clamp(base,0.42f,0.76f);
        double sum=0; int n=0;
        for(int i=0;i<m.refs.size();i++) for(int j=i+1;j<m.refs.size();j++) {
            float s=FaceEngine.cosine(m.refs.get(i),m.refs.get(j)); if(s>0){sum+=s;n++;}
        }
        float pos = n==0 ? base : (float)(sum/n - 0.13);
        float neg = -1f;
        for(float[] r:m.refs) for(float[] h:m.negatives) neg=Math.max(neg,FaceEngine.cosine(r,h));
        float calibrated = clamp(Math.max(base-0.03f,pos),0.44f,0.76f);
        if(neg>0) calibrated=clamp(Math.max(calibrated,neg+0.045f),0.44f,0.80f);
        return calibrated;
    }

    static synchronized void observe(Context c,String project,int actorId,float[] embedding,float quality){
        if(embedding==null||embedding.length!=192||quality<0.28f)return;
        Model m=load(c,project,actorId);
        float max=-1f; for(float[] r:m.refs)max=Math.max(max,FaceEngine.cosine(r,embedding));
        if(max>0.987f)return;
        float[] v=embedding.clone();FaceEngine.normalize(v);
        if(m.refs.size()<MAX_REFS){m.refs.add(v);m.qualities.add(quality);}else{
            int worst=0;float wq=Float.MAX_VALUE;for(int i=0;i<m.qualities.size();i++)if(m.qualities.get(i)<wq){wq=m.qualities.get(i);worst=i;}
            if(quality>wq+0.03f){m.refs.set(worst,v);m.qualities.set(worst,quality);}else return;
        }
        save(c,project,actorId,m);
    }

    static synchronized int buildFromActor(Context c, ActorScanStore.ScanState state, ActorScanStore.Cluster actor) {
        if(state==null||actor==null)return 0;
        ActorScanStore store=new ActorScanStore(c); int built=0;
        try(FaceEngine full=new FaceEngine()){
            ArrayList<String> thumbs=new ArrayList<>(actor.thumbs);
            for(int i=0;i<thumbs.size()&&i<12;i++){
                Bitmap b=null;
                try{
                    b=android.graphics.BitmapFactory.decodeFile(store.thumbnailFile(state.projectKey,thumbs.get(i)).getAbsolutePath());
                    if(b==null)continue;List<Face> faces=full.detect(b);Face best=null;int area=0;
                    if(faces!=null)for(Face f:faces){Rect r=f.getBoundingBox();if(r!=null&&r.width()*r.height()>area){area=r.width()*r.height();best=f;}}
                    if(best==null)continue;float[] v=alignedEmbedding(c,b,best,best.getBoundingBox(),full);if(v==null)continue;
                    float q=full.quality(b,best.getBoundingBox());observe(c,state.projectKey,actor.id,v,q);built++;
                }catch(Throwable ignored){}finally{if(b!=null&&!b.isRecycled())b.recycle();}
            }
        }catch(Throwable ignored){}
        return Math.min(MAX_REFS,load(c,state.projectKey,actor.id).refs.size());
    }

    static synchronized String teachNearestHardNegative(Context c, ActorScanStore.ScanState state, int actorId) {
        if(state==null)return "No actor index"; ActorScanStore.Cluster a=state.byId(actorId);if(a==null||a.centroid==null)return "Select an actor first";
        ActorScanStore.Cluster best=null;float score=-1f;
        for(ActorScanStore.Cluster x:state.clusters){if(x==null||x.id==actorId||x.centroid==null)continue;float s=FaceEngine.cosine(a.centroid,x.centroid);if(s>score){score=s;best=x;}}
        if(best==null)return "No lookalike actor found";
        Model m=load(c,state.projectKey,actorId);float[] n=best.centroid.clone();FaceEngine.normalize(n);
        float max=-1;for(float[] x:m.negatives)max=Math.max(max,FaceEngine.cosine(x,n));if(max<0.985f){if(m.negatives.size()>=MAX_NEG)m.negatives.remove(0);m.negatives.add(n);save(c,state.projectKey,actorId,m);}
        return String.format(Locale.US,"Actor %d learned as hard negative • similarity %.1f%%",best.id,score*100f);
    }

    static void markNeedsReview(Context c,String project,int actorId,float score,float threshold){
        SharedPreferences p=prefs(c);String key="v100_needs_review_"+safe(project);String old=p.getString(key,"");LinkedHashSet<String>s=new LinkedHashSet<>();if(old!=null&&!old.isEmpty())s.addAll(Arrays.asList(old.split(",")));s.add(actorId+":"+String.format(Locale.US,"%.3f",score)+":"+String.format(Locale.US,"%.3f",threshold));while(s.size()>80)s.remove(s.iterator().next());p.edit().putString(key,String.join(",",s)).apply();
    }
    static String needsReview(Context c,String project){String x=prefs(c).getString("v100_needs_review_"+safe(project),"");if(x==null||x.isEmpty())return "0";return String.valueOf(x.split(",").length);}

    static String summary(Context c,String project,int actorId){Model m=load(c,project,actorId);return "References "+m.refs.size()+" • hard negatives "+m.negatives.size()+" • threshold "+String.format(Locale.US,"%.1f%%",threshold(c,project,actorId,0.58f)*100f);}

    private static Model load(Context c,String project,int actorId){
        Model m=new Model();File f=file(c,project,actorId);if(!f.isFile())return m;
        try{JSONObject o=new JSONObject(read(f));JSONArray rs=o.optJSONArray("refs"),qs=o.optJSONArray("qualities"),ns=o.optJSONArray("negatives");if(rs!=null)for(int i=0;i<rs.length();i++){float[]v=decode(rs.optString(i));if(v!=null&&v.length==192){m.refs.add(v);m.qualities.add(qs==null?0.5f:(float)qs.optDouble(i,0.5));}}if(ns!=null)for(int i=0;i<ns.length();i++){float[]v=decode(ns.optString(i));if(v!=null&&v.length==192)m.negatives.add(v);}}catch(Throwable ignored){}
        return m;
    }
    private static void save(Context c,String project,int actorId,Model m){
        try{File f=file(c,project,actorId),tmp=new File(f.getParentFile(),f.getName()+".tmp");JSONObject o=new JSONObject();JSONArray rs=new JSONArray(),qs=new JSONArray(),ns=new JSONArray();for(float[]v:m.refs)rs.put(encode(v));for(Float q:m.qualities)qs.put(q);for(float[]v:m.negatives)ns.put(encode(v));o.put("refs",rs);o.put("qualities",qs);o.put("negatives",ns);try(Writer w=new BufferedWriter(new OutputStreamWriter(new FileOutputStream(tmp),"UTF-8"))){w.write(o.toString());}if(f.exists())f.delete();tmp.renameTo(f);}catch(Throwable ignored){}
    }
    private static File file(Context c,String project,int actorId){File d=new File(c.getFilesDir(),"identity_v100/"+safe(project));if(!d.exists())d.mkdirs();return new File(d,"actor_"+actorId+".json");}
    private static String safe(String s){return(s==null?"project":s).replaceAll("[^A-Za-z0-9._-]","_");}
    private static SharedPreferences prefs(Context c){return c.getSharedPreferences("actor_sticker",Context.MODE_PRIVATE);}
    private static String encode(float[] a){ByteBuffer b=ByteBuffer.allocate(a.length*4).order(ByteOrder.LITTLE_ENDIAN);for(float v:a)b.putFloat(v);return Base64.encodeToString(b.array(),Base64.NO_WRAP);}
    private static float[] decode(String s){try{byte[]r=Base64.decode(s,Base64.NO_WRAP);if(r.length%4!=0)return null;ByteBuffer b=ByteBuffer.wrap(r).order(ByteOrder.LITTLE_ENDIAN);float[]a=new float[r.length/4];for(int i=0;i<a.length;i++)a[i]=b.getFloat();return a;}catch(Throwable e){return null;}}
    private static String read(File f)throws Exception{StringBuilder b=new StringBuilder();try(BufferedReader r=new BufferedReader(new InputStreamReader(new FileInputStream(f),"UTF-8"))){String s;while((s=r.readLine())!=null)b.append(s);}return b.toString();}
    private static float clamp(float v,float a,float b){return Math.max(a,Math.min(b,v));}
    private static final class Model{final ArrayList<float[]>refs=new ArrayList<>(),negatives=new ArrayList<>();final ArrayList<Float>qualities=new ArrayList<>();}
}
