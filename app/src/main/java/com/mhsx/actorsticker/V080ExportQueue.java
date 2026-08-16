package com.mhsx.actorsticker;

import android.content.Context;
import android.content.Intent;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.util.*;

/** v0.9 Room-backed render queue with atomic claims, heartbeat watchdog and legacy JSON migration. */
final class V080ExportQueue {
    static final String PENDING="PENDING", RUNNING="RUNNING", DONE="DONE", FAILED="FAILED", PAUSED="PAUSED", STALLED="STALLED";
    private final Context app;
    private final V090JobDao dao;
    private final File legacy;

    V080ExportQueue(Context c){
        app=c.getApplicationContext();
        dao=V090Database.get(app).jobs();
        legacy=new File(c.getFilesDir(),"v080_export_queue.json");
        migrateLegacyOnce();
    }

    static final class Job {
        String id=UUID.randomUUID().toString();
        String projectKey="", sourceUri="", outputTree="", outputName="";
        String actorIds="", mode="separate", aspect="1:1", preset="Instagram 1:1", codec="Auto";
        String state=PENDING, error="";
        int videoIndex, attempts;
        long startMs,endMs;
        float score;
        boolean safeZone=true, tracking=false, maxQuality=true;

        JSONObject json() throws Exception {JSONObject o=new JSONObject();
            o.put("id",id);o.put("projectKey",projectKey);o.put("sourceUri",sourceUri);o.put("outputTree",outputTree);o.put("outputName",outputName);
            o.put("actorIds",actorIds);o.put("mode",mode);o.put("aspect",aspect);o.put("preset",preset);o.put("codec",codec);o.put("state",state);o.put("error",error);
            o.put("videoIndex",videoIndex);o.put("attempts",attempts);o.put("startMs",startMs);o.put("endMs",endMs);o.put("score",score);
            o.put("safeZone",safeZone);o.put("tracking",tracking);o.put("maxQuality",maxQuality);return o;}
        static Job from(JSONObject o){Job j=new Job();j.id=o.optString("id",j.id);j.projectKey=o.optString("projectKey","");j.sourceUri=o.optString("sourceUri","");j.outputTree=o.optString("outputTree","");j.outputName=o.optString("outputName","");j.actorIds=o.optString("actorIds","");j.mode=o.optString("mode","separate");j.aspect=o.optString("aspect","1:1");j.preset=o.optString("preset","Instagram 1:1");j.codec=o.optString("codec","Auto");j.state=o.optString("state",PENDING);j.error=o.optString("error","");j.videoIndex=o.optInt("videoIndex",0);j.attempts=o.optInt("attempts",0);j.startMs=o.optLong("startMs",0);j.endMs=o.optLong("endMs",0);j.score=(float)o.optDouble("score",0);j.safeZone=o.optBoolean("safeZone",true);j.tracking=o.optBoolean("tracking",false);j.maxQuality=o.optBoolean("maxQuality",true);return j;}
    }

    synchronized ArrayList<Job> load(){ArrayList<Job> xs=new ArrayList<>();for(V090JobEntity e:dao.all())xs.add(fromEntity(e));return xs;}
    synchronized void save(List<Job> xs){dao.clearAll();if(xs!=null&&!xs.isEmpty()){ArrayList<V090JobEntity> es=new ArrayList<>();long now=System.currentTimeMillis();for(Job j:xs)es.add(toEntity(j,now));dao.upsertAll(es);}}
    synchronized void append(List<Job> add){if(add==null||add.isEmpty())return;long now=System.currentTimeMillis();ArrayList<V090JobEntity> es=new ArrayList<>();for(Job j:add)es.add(toEntity(j,now));dao.upsertAll(es);kickRenderer();}
    synchronized void resetInterrupted(){dao.resetInterrupted(System.currentTimeMillis());}
    synchronized Job claimNext(){V090JobEntity e=dao.claimNext(System.currentTimeMillis());return e==null?null:fromEntity(e);}
    synchronized void update(String id,String state,String error){dao.setState(id,state,error==null?"":error,System.currentTimeMillis());}
    synchronized void heartbeat(String id){if(id!=null)dao.heartbeat(id,System.currentTimeMillis());}
    synchronized int recoverStalled(long staleMs){long now=System.currentTimeMillis();return dao.markStalled(now-Math.max(5000L,staleMs),now);}
    synchronized void retryFailed(){dao.retryBroken(System.currentTimeMillis());kickRenderer();}
    synchronized void retryStalled(){dao.retryBroken(System.currentTimeMillis());kickRenderer();}
    synchronized void pausePending(){dao.pausePending(System.currentTimeMillis());}
    synchronized void resumePaused(){dao.resumePaused(System.currentTimeMillis());}
    synchronized void clearCompleted(){dao.clearDone();}
    synchronized void clearBroken(){dao.clearBroken();}
    synchronized int count(String state){return dao.count(state);}

    void kickRenderer(){
        try{
            V090WorkController.mark(app,"export","Starting render queue",1);
            Intent i=new Intent(app,V080ExportService.class).setAction(V080ExportService.ACTION_START);
            if(Build.VERSION.SDK_INT>=26)app.startForegroundService(i);else app.startService(i);
        }catch(Throwable e){
            V090Diagnostics.log(app,"renderer-start",e);
            app.getSharedPreferences("actor_sticker",Context.MODE_PRIVATE).edit()
                    .putString("v080_export_status","Renderer start failed: "+e.getClass().getSimpleName())
                    .putLong("v080_export_heartbeat",System.currentTimeMillis()).apply();
        }
    }

    private void migrateLegacyOnce(){
        if(dao.total()>0||!legacy.isFile())return;
        try{
            String raw=read(legacy);JSONArray a=new JSONArray(raw);ArrayList<V090JobEntity> es=new ArrayList<>();long now=System.currentTimeMillis();
            for(int i=0;i<a.length();i++){JSONObject o=a.optJSONObject(i);if(o==null)continue;Job j=Job.from(o);if(RUNNING.equals(j.state))j.state=PENDING;es.add(toEntity(j,now));}
            if(!es.isEmpty())dao.upsertAll(es);
            File migrated=new File(legacy.getParentFile(),"v080_export_queue.migrated.json");
            if(migrated.exists())migrated.delete();legacy.renameTo(migrated);
        }catch(Throwable e){V090Diagnostics.log(app,"queue-migration",e);}
    }

    private static V090JobEntity toEntity(Job j,long now){V090JobEntity e=new V090JobEntity();e.id=j.id;e.projectKey=j.projectKey;e.sourceUri=j.sourceUri;e.outputTree=j.outputTree;e.outputName=j.outputName;e.actorIds=j.actorIds;e.mode=j.mode;e.aspect=j.aspect;e.preset=j.preset;e.codec=j.codec;e.state=j.state;e.error=j.error;e.videoIndex=j.videoIndex;e.attempts=j.attempts;e.startMs=j.startMs;e.endMs=j.endMs;e.score=j.score;e.safeZone=j.safeZone;e.tracking=j.tracking;e.maxQuality=j.maxQuality;e.createdAt=now;e.updatedAt=now;e.heartbeatAt=RUNNING.equals(j.state)?now:0L;return e;}
    private static Job fromEntity(V090JobEntity e){Job j=new Job();j.id=e.id;j.projectKey=e.projectKey;j.sourceUri=e.sourceUri;j.outputTree=e.outputTree;j.outputName=e.outputName;j.actorIds=e.actorIds;j.mode=e.mode;j.aspect=e.aspect;j.preset=e.preset;j.codec=e.codec;j.state=e.state;j.error=e.error;j.videoIndex=e.videoIndex;j.attempts=e.attempts;j.startMs=e.startMs;j.endMs=e.endMs;j.score=e.score;j.safeZone=e.safeZone;j.tracking=e.tracking;j.maxQuality=e.maxQuality;return j;}
    private static String read(File f)throws Exception{StringBuilder b=new StringBuilder();try(BufferedReader r=new BufferedReader(new InputStreamReader(new FileInputStream(f),"UTF-8"))){String s;while((s=r.readLine())!=null)b.append(s);}return b.toString();}
}
