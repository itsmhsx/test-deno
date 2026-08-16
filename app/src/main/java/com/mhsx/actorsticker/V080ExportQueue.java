package com.mhsx.actorsticker;

import android.content.Context;
import android.content.Intent;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.util.*;

/** Private persistent render queue. RUNNING jobs are restored to PENDING after a process restart. */
final class V080ExportQueue {
    static final String PENDING="PENDING", RUNNING="RUNNING", DONE="DONE", FAILED="FAILED", PAUSED="PAUSED";
    private final File file;
    private final Context app;
    V080ExportQueue(Context c){app=c.getApplicationContext();file=new File(c.getFilesDir(),"v080_export_queue.json");}

    static final class Job {
        String id=UUID.randomUUID().toString();
        String projectKey="", sourceUri="", outputTree="", outputName="";
        String actorIds="", mode="separate", aspect="1:1", preset="Instagram 1:1", codec="Auto";
        String state=PENDING, error="";
        int videoIndex, attempts;
        long startMs,endMs;
        float score;
        boolean safeZone=true, tracking=true, maxQuality=true;

        JSONObject json() throws Exception {JSONObject o=new JSONObject();
            o.put("id",id);o.put("projectKey",projectKey);o.put("sourceUri",sourceUri);o.put("outputTree",outputTree);o.put("outputName",outputName);
            o.put("actorIds",actorIds);o.put("mode",mode);o.put("aspect",aspect);o.put("preset",preset);o.put("codec",codec);o.put("state",state);o.put("error",error);
            o.put("videoIndex",videoIndex);o.put("attempts",attempts);o.put("startMs",startMs);o.put("endMs",endMs);o.put("score",score);
            o.put("safeZone",safeZone);o.put("tracking",tracking);o.put("maxQuality",maxQuality);return o;}
        static Job from(JSONObject o){Job j=new Job();j.id=o.optString("id",j.id);j.projectKey=o.optString("projectKey","");j.sourceUri=o.optString("sourceUri","");j.outputTree=o.optString("outputTree","");j.outputName=o.optString("outputName","");j.actorIds=o.optString("actorIds","");j.mode=o.optString("mode","separate");j.aspect=o.optString("aspect","1:1");j.preset=o.optString("preset","Instagram 1:1");j.codec=o.optString("codec","Auto");j.state=o.optString("state",PENDING);j.error=o.optString("error","");j.videoIndex=o.optInt("videoIndex",0);j.attempts=o.optInt("attempts",0);j.startMs=o.optLong("startMs",0);j.endMs=o.optLong("endMs",0);j.score=(float)o.optDouble("score",0);j.safeZone=o.optBoolean("safeZone",true);j.tracking=o.optBoolean("tracking",true);j.maxQuality=o.optBoolean("maxQuality",true);return j;}
    }

    synchronized ArrayList<Job> load(){ArrayList<Job> xs=new ArrayList<>();if(!file.isFile())return xs;try{String s=read(file);JSONArray a=new JSONArray(s);for(int i=0;i<a.length();i++){JSONObject o=a.optJSONObject(i);if(o!=null)xs.add(Job.from(o));}}catch(Throwable ignored){}return xs;}
    synchronized void save(List<Job> xs){try{JSONArray a=new JSONArray();for(Job j:xs)a.put(j.json());File tmp=new File(file.getParentFile(),file.getName()+".tmp");try(Writer w=new BufferedWriter(new OutputStreamWriter(new FileOutputStream(tmp),"UTF-8"))){w.write(a.toString());}if(file.exists())file.delete();if(!tmp.renameTo(file)){try(Writer w=new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file),"UTF-8"))){w.write(a.toString());}tmp.delete();}}catch(Throwable ignored){}}
    synchronized void append(List<Job> add){ArrayList<Job> xs=load();xs.addAll(add);save(xs);kickRenderer();}
    synchronized void resetInterrupted(){ArrayList<Job> xs=load();boolean dirty=false;for(Job j:xs)if(RUNNING.equals(j.state)){j.state=PENDING;j.error="Recovered after interruption";dirty=true;}if(dirty)save(xs);}
    synchronized Job claimNext(){ArrayList<Job> xs=load();for(Job j:xs){if(PENDING.equals(j.state)){j.state=RUNNING;j.attempts++;j.error="";save(xs);return copy(j);}}return null;}
    synchronized void update(String id,String state,String error){ArrayList<Job> xs=load();for(Job j:xs)if(j.id.equals(id)){j.state=state;j.error=error==null?"":error;break;}save(xs);}
    synchronized void retryFailed(){ArrayList<Job> xs=load();for(Job j:xs)if(FAILED.equals(j.state)){j.state=PENDING;j.error="";}save(xs);kickRenderer();}
    synchronized void pausePending(){ArrayList<Job> xs=load();for(Job j:xs)if(PENDING.equals(j.state))j.state=PAUSED;save(xs);}
    synchronized void resumePaused(){ArrayList<Job> xs=load();for(Job j:xs)if(PAUSED.equals(j.state))j.state=PENDING;save(xs);}
    synchronized void clearCompleted(){ArrayList<Job> xs=load();xs.removeIf(j->DONE.equals(j.state));save(xs);}
    synchronized int count(String state){int n=0;for(Job j:load())if(state.equals(j.state))n++;return n;}

    void kickRenderer(){
        try{
            Intent i=new Intent(app,V080ExportService.class).setAction(V080ExportService.ACTION_START);
            if(Build.VERSION.SDK_INT>=26)app.startForegroundService(i);else app.startService(i);
        }catch(Throwable e){
            app.getSharedPreferences("actor_sticker",Context.MODE_PRIVATE).edit()
                    .putString("v080_export_status","Renderer start failed: "+e.getClass().getSimpleName())
                    .putLong("v080_export_heartbeat",System.currentTimeMillis()).apply();
        }
    }

    private static Job copy(Job x){try{return Job.from(x.json());}catch(Throwable e){return x;}}
    private static String read(File f)throws Exception{StringBuilder b=new StringBuilder();try(BufferedReader r=new BufferedReader(new InputStreamReader(new FileInputStream(f),"UTF-8"))){String s;while((s=r.readLine())!=null)b.append(s);}return b.toString();}
}
