package com.mhsx.actorsticker;

import android.content.Context;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.util.*;

/** Persistent private project/history and actor correction metadata for v0.8. */
final class V080StudioStore {
    private final Context context;
    private final File file;
    V080StudioStore(Context c){context=c.getApplicationContext();file=new File(c.getFilesDir(),"studio_v080.json");}

    synchronized void touchProject(String key,List<Uri> videos,String output){if(key==null||key.isEmpty())return;JSONObject root=loadRoot();try{JSONObject projects=root.optJSONObject("projects");if(projects==null){projects=new JSONObject();root.put("projects",projects);}JSONObject p=projects.optJSONObject(key);if(p==null)p=new JSONObject();p.put("lastUsed",System.currentTimeMillis());JSONArray a=new JSONArray();if(videos!=null)for(Uri u:videos)a.put(u.toString());p.put("videos",a);p.put("output",output==null?"":output);projects.put(key,p);saveRoot(root);}catch(Throwable ignored){}}
    synchronized List<String> recentProjectKeys(){ArrayList<String> out=new ArrayList<>();JSONObject ps=loadRoot().optJSONObject("projects");if(ps==null)return out;ArrayList<String> keys=new ArrayList<>();Iterator<String> it=ps.keys();while(it.hasNext())keys.add(it.next());keys.sort((a,b)->Long.compare(ps.optJSONObject(b)==null?0:ps.optJSONObject(b).optLong("lastUsed"),ps.optJSONObject(a)==null?0:ps.optJSONObject(a).optLong("lastUsed")));out.addAll(keys);return out;}
    synchronized void setActorName(String key,int id,String name){JSONObject root=loadRoot();try{JSONObject names=root.optJSONObject("actorNames");if(names==null){names=new JSONObject();root.put("actorNames",names);}names.put(key+":"+id,name==null?"":name.trim());saveRoot(root);}catch(Throwable ignored){}}
    synchronized String actorName(String key,int id){JSONObject n=loadRoot().optJSONObject("actorNames");String s=n==null?"":n.optString(key+":"+id,"");return s.isEmpty()?"Actor "+(id+1):s;}
    synchronized void saveSelectedActors(String key,Collection<Integer> ids){JSONObject root=loadRoot();try{JSONObject m=root.optJSONObject("multiActors");if(m==null){m=new JSONObject();root.put("multiActors",m);}JSONArray a=new JSONArray();if(ids!=null)for(Integer id:ids)a.put(id);m.put(key,a);saveRoot(root);}catch(Throwable ignored){}}
    synchronized LinkedHashSet<Integer> selectedActors(String key){LinkedHashSet<Integer> out=new LinkedHashSet<>();JSONObject m=loadRoot().optJSONObject("multiActors");JSONArray a=m==null?null:m.optJSONArray(key);if(a!=null)for(int i=0;i<a.length();i++)out.add(a.optInt(i));return out;}
    synchronized void feedback(String key,int actorId,String type){JSONObject root=loadRoot();try{JSONObject f=root.optJSONObject("feedback");if(f==null){f=new JSONObject();root.put("feedback",f);}String k=key+":"+actorId+":"+type;f.put(k,f.optInt(k,0)+1);saveRoot(root);}catch(Throwable ignored){}}
    synchronized int feedbackCount(String key,int actorId,String type){JSONObject f=loadRoot().optJSONObject("feedback");return f==null?0:f.optInt(key+":"+actorId+":"+type,0);}

    synchronized String suggestions(String key,ActorScanStore.ScanState s,long cacheBytes,String codec){
        if(s==null)return"Run Actor Scan to unlock project suggestions.";ArrayList<ActorScanStore.Cluster> xs=new ArrayList<>(s.clusters);xs.sort((a,b)->Double.compare(b.smartScore(),a.smartScore()));StringBuilder b=new StringBuilder();if(!xs.isEmpty()){ActorScanStore.Cluster top=xs.get(0);b.append("• ").append(actorName(key,top.id)).append(" has the strongest screen presence (\").append(top.count).append(" detections).\n");int high=0;for(ActorScanStore.Hit h:top.hits)if(h.quality>=.70f)high++;b.append("• ").append(high).append(" high-quality actor hits are ready for ranking.\n");}
        int weak=0;for(ActorScanStore.Cluster c:xs)if(c.count<3)weak++;if(weak>0)b.append("• ").append(weak).append(" tiny face group(s) may be noise; Merge/Split review is recommended.\n");b.append(String.format(Locale.US,"• Private actor cache: %.1f MB.\n",cacheBytes/1048576.0));b.append("• Recommended codec: ").append(codec==null?"Auto":codec).append(".\n");b.append("• Stable crop preset keeps dynamic zoom off unless you explicitly enable it.");return b.toString();
    }

    static long bytes(File f){if(f==null||!f.exists())return 0;if(f.isFile())return f.length();long n=0;File[]a=f.listFiles();if(a!=null)for(File x:a)n+=bytes(x);return n;}
    static long ageMs(File f){return f==null?Long.MAX_VALUE:System.currentTimeMillis()-f.lastModified();}
    synchronized long studioBytes(){return bytes(context.getFilesDir());}

    private JSONObject loadRoot(){if(!file.isFile())return new JSONObject();try{return new JSONObject(read(file));}catch(Throwable e){return new JSONObject();}}
    private void saveRoot(JSONObject root){try{File tmp=new File(file.getParentFile(),file.getName()+".tmp");try(Writer w=new BufferedWriter(new OutputStreamWriter(new FileOutputStream(tmp),"UTF-8"))){w.write(root.toString());}if(file.exists())file.delete();tmp.renameTo(file);}catch(Throwable ignored){}}
    private static String read(File f)throws Exception{StringBuilder b=new StringBuilder();try(BufferedReader r=new BufferedReader(new InputStreamReader(new FileInputStream(f),"UTF-8"))){String s;while((s=r.readLine())!=null)b.append(s);}return b.toString();}
}
