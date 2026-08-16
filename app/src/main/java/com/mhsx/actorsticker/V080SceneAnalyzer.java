package com.mhsx.actorsticker;

import java.util.*;

/** Scene construction/ranking used by the v0.8 preview and foreground render queue. */
final class V080SceneAnalyzer {
    static final long MIN=2000L;
    static final class Segment {
        long startMs,endMs; float score; String rank,expression; final ArrayList<ActorScanStore.Hit> hits=new ArrayList<>();
        Segment(long s,long e,List<ActorScanStore.Hit> h,float q){startMs=s;endMs=e;hits.addAll(h);score=q;rank=q>=82?"BEST":q>=62?"GOOD":"REVIEW";expression=momentLabel(h);}
    }
    private V080SceneAnalyzer(){}

    static ArrayList<ActorScanStore.Hit> collect(ActorScanStore.ScanState state, Collection<Integer> actorIds, int videoIndex, boolean togetherOnly){
        ArrayList<ActorScanStore.Hit> all=new ArrayList<>();if(state==null||actorIds==null||actorIds.isEmpty())return all;
        ArrayList<ArrayList<ActorScanStore.Hit>> per=new ArrayList<>();
        for(int id:actorIds){ActorScanStore.Cluster c=state.byId(id);ArrayList<ActorScanStore.Hit> one=new ArrayList<>();if(c!=null)for(ActorScanStore.Hit h:c.hits)if(h.videoIndex==videoIndex)one.add(h);one.sort(Comparator.comparingLong(h->h.t));per.add(one);}
        if(!togetherOnly){for(ArrayList<ActorScanStore.Hit>x:per)all.addAll(x);all.sort(Comparator.comparingLong(h->h.t));return all;}
        if(per.isEmpty())return all;ArrayList<ActorScanStore.Hit> base=per.get(0);
        for(ActorScanStore.Hit h:base){boolean ok=true;for(int i=1;i<per.size();i++){if(nearest(per.get(i),h.t)>1400L){ok=false;break;}}if(ok){all.add(h);for(int i=1;i<per.size();i++){ActorScanStore.Hit n=nearestHit(per.get(i),h.t);if(n!=null)all.add(n);}}}
        all.sort(Comparator.comparingLong(h->h.t));return all;
    }

    static List<Segment> build(List<ActorScanStore.Hit> hits,long duration,long clipMs,long sampleMs,boolean exact,String expressionFilter,int topN){
        ArrayList<Segment> out=new ArrayList<>();if(hits==null||hits.isEmpty()||duration<MIN)return out;clipMs=Math.max(MIN,Math.min(60000L,clipMs));
        ArrayList<ActorScanStore.Hit> xs=new ArrayList<>(hits);xs.sort(Comparator.comparingLong(h->h.t));long gap=Math.max(1200L,Math.max(sampleMs*3L,2300L));int i=0;
        while(i<xs.size()){int j=i;while(j+1<xs.size()&&xs.get(j+1).t-xs.get(j).t<=gap)j++;long first=xs.get(i).t,last=xs.get(j).t;
            long paddedStart=Math.max(0,first-420L),paddedEnd=Math.min(duration,last+780L);long window=Math.min(clipMs,Math.max(MIN,paddedEnd-paddedStart));
            long bestT=bestMoment(xs,i,j);long start=Math.max(0,bestT-window/2);long end=start+window;if(end>duration){end=duration;start=Math.max(0,end-window);}if(exact&&duration>=clipMs){start=Math.max(0,bestT-clipMs/2);end=start+clipMs;if(end>duration){end=duration;start=Math.max(0,end-clipMs);}}
            if(end-start>=MIN){ArrayList<ActorScanStore.Hit> wh=new ArrayList<>();for(int k=i;k<=j;k++){ActorScanStore.Hit h=xs.get(k);if(h.t>=start-900&&h.t<=end+900)wh.add(h);}float q=quality(wh,end-start,sampleMs);Segment s=new Segment(start,end,wh,q);if(matchesExpression(s.expression,expressionFilter))out.add(s);}i=j+1;}
        out=dedupe(out);out.sort((a,b)->Float.compare(b.score,a.score));if(topN>0&&out.size()>topN)out=new ArrayList<>(out.subList(0,topN));out.sort(Comparator.comparingLong(a->a.startMs));return out;
    }

    private static long bestMoment(List<ActorScanStore.Hit> xs,int i,int j){double best=-1;long t=xs.get(i).t;for(int k=i;k<=j;k++){ActorScanStore.Hit h=xs.get(k);double q=h.quality*0.68+Math.max(0,h.score)*0.32;double center=1.0-Math.min(1.0,Math.abs(h.cx-.5)*1.25);q=q*.88+center*.12;if(q>best){best=q;t=h.t;}}return t;}
    private static float quality(List<ActorScanStore.Hit> hs,long duration,long sampleMs){if(hs.isEmpty())return 0;double q=0,sim=0,motion=0;ActorScanStore.Hit prev=null;for(ActorScanStore.Hit h:hs){q+=h.quality;sim+=Math.max(0,Math.min(1,h.score));if(prev!=null){double dx=h.cx-prev.cx,dy=h.cy-prev.cy;motion+=Math.sqrt(dx*dx+dy*dy);}prev=h;}q/=hs.size();sim/=hs.size();double density=Math.min(1.0,hs.size()/Math.max(1.0,duration/(double)Math.max(350,sampleMs)));double stable=1.0-Math.min(1.0,motion/Math.max(1,hs.size()-1)/0.22);return(float)Math.max(0,Math.min(100,100*(q*.50+sim*.22+density*.18+stable*.10)));}
    private static ArrayList<Segment> dedupe(ArrayList<Segment> xs){xs.sort(Comparator.comparingLong(a->a.startMs));ArrayList<Segment> out=new ArrayList<>();for(Segment s:xs){boolean merged=false;for(int i=Math.max(0,out.size()-3);i<out.size();i++){Segment p=out.get(i);long ov=Math.max(0,Math.min(p.endMs,s.endMs)-Math.max(p.startMs,s.startMs));long shortLen=Math.min(p.endMs-p.startMs,s.endMs-s.startMs);float centerDist=centerDistance(p.hits,s.hits);if(shortLen>0&&ov>shortLen*.62&&centerDist<.16f){if(s.score>p.score)out.set(i,s);merged=true;break;}}if(!merged)out.add(s);}return out;}
    private static float centerDistance(List<ActorScanStore.Hit>a,List<ActorScanStore.Hit>b){float[]x=center(a),y=center(b);float dx=x[0]-y[0],dy=x[1]-y[1];return(float)Math.sqrt(dx*dx+dy*dy);}private static float[] center(List<ActorScanStore.Hit>x){double sx=0,sy=0,w=0;for(ActorScanStore.Hit h:x){double q=.2+h.quality;sx+=h.cx*q;sy+=h.cy*q;w+=q;}return new float[]{w==0?.5f:(float)(sx/w),w==0?.5f:(float)(sy/w)};}
    private static String momentLabel(List<ActorScanStore.Hit> hs){if(hs.isEmpty())return"Silent reaction";double q=0,m=0;ActorScanStore.Hit p=null;for(ActorScanStore.Hit h:hs){q+=h.quality;if(p!=null){double dx=h.cx-p.cx,dy=h.cy-p.cy;m+=Math.sqrt(dx*dx+dy*dy);}p=h;}q/=hs.size();m/=Math.max(1,hs.size()-1);if(q>.82&&m<.035)return"Close-up";if(m>.16)return"Action";if(hs.size()>=6&&m>.055)return"Talking";if(q>.72&&m<.045)return"Reaction";return"Silent reaction";}
    private static boolean matchesExpression(String label,String filter){if(filter==null||filter.isEmpty()||"All".equals(filter))return true;if(filter.equals(label))return true;if("Reaction".equals(filter))return label.contains("Reaction");return false;}
    private static long nearest(List<ActorScanStore.Hit> xs,long t){long b=Long.MAX_VALUE;for(ActorScanStore.Hit h:xs)b=Math.min(b,Math.abs(h.t-t));return b;}private static ActorScanStore.Hit nearestHit(List<ActorScanStore.Hit> xs,long t){ActorScanStore.Hit b=null;long d=Long.MAX_VALUE;for(ActorScanStore.Hit h:xs){long x=Math.abs(h.t-t);if(x<d){d=x;b=h;}}return b;}
}
