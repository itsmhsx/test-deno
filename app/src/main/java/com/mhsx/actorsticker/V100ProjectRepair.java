package com.mhsx.actorsticker;

import android.content.Context;

import java.io.*;
import java.util.*;

/** Conservative project repair: backs up the index, removes only corrupt rows, preserves actor references and outputs. */
final class V100ProjectRepair {
    private V100ProjectRepair() {}
    static String repair(Context c,String project){if(project==null||project.isEmpty())return"No project loaded";ActorScanStore store=new ActorScanStore(c);File dir=store.projectDir(project),index=new File(dir,"index.json");File backupDir=new File(c.getFilesDir(),"repair_v100/"+safe(project));if(!backupDir.exists())backupDir.mkdirs();try{if(index.isFile())copy(index,new File(backupDir,"index_"+System.currentTimeMillis()+".json"));}catch(Throwable ignored){}
        ActorScanStore.ScanState s=store.load(project);if(s==null){File tmp=new File(dir,"index.json.tmp");if(tmp.isFile()){try{if(index.exists())index.delete();copy(tmp,index);s=store.load(project);}catch(Throwable ignored){}}}
        int removed=0,fixedHits=0;if(s!=null){Iterator<ActorScanStore.Cluster>it=s.clusters.iterator();while(it.hasNext()){ActorScanStore.Cluster a=it.next();if(a==null||a.centroid==null||a.centroid.length!=192||!finite(a.centroid)){it.remove();removed++;continue;}FaceEngine.normalize(a.centroid);Iterator<ActorScanStore.Hit>hi=a.hits.iterator();while(hi.hasNext()){ActorScanStore.Hit h=hi.next();if(!Float.isFinite(h.cx)||!Float.isFinite(h.cy)||h.cx<0||h.cx>1||h.cy<0||h.cy>1){hi.remove();fixedHits++;}}}if(s.byId(s.selectedClusterId)==null)s.selectedClusterId=s.clusters.isEmpty()?-1:s.clusters.get(0).id;try{store.save(s);}catch(Throwable e){return"Repair backup created, but index save failed: "+e.getClass().getSimpleName();}}
        int stalled=0;try{V080ExportQueue q=new V080ExportQueue(c);q.resetInterrupted();stalled=q.recoverStalled(1L);}catch(Throwable ignored){}
        return s==null?"Backup created. Actor index is unreadable; run Refresh Full Scan. Identity memory was preserved.":"Repair complete • removed corrupt actors "+removed+" • removed corrupt hits "+fixedHits+" • recovered queue jobs "+stalled+" • identity memory preserved";
    }
    private static boolean finite(float[]a){for(float v:a)if(!Float.isFinite(v))return false;return true;}
    private static void copy(File a,File b)throws Exception{try(InputStream in=new BufferedInputStream(new FileInputStream(a));OutputStream out=new BufferedOutputStream(new FileOutputStream(b))){byte[]buf=new byte[256*1024];int n;while((n=in.read(buf))>0)out.write(buf,0,n);}}
    private static String safe(String s){return s.replaceAll("[^A-Za-z0-9._-]","_");}
}
