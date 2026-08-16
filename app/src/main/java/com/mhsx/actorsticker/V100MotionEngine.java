package com.mhsx.actorsticker;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;

import java.util.*;

/**
 * v1.0 opt-in motion refinement. It is never used while Face Follow is disabled.
 * Uses a small iterative Lucas-Kanade translation solver between decoded frames,
 * plus a torso color-histogram appearance check for short face-loss recovery.
 */
final class V100MotionEngine {
    private V100MotionEngine() {}

    static ArrayList<ActorScanStore.Hit> refine(Context c, String sourceUri, List<ActorScanStore.Hit> input, long startMs, long endMs) {
        ArrayList<ActorScanStore.Hit> locked = actorLock(input);
        if (locked.size() < 2 || sourceUri == null || endMs <= startMs) return locked;
        if (!c.getSharedPreferences("actor_sticker",Context.MODE_PRIVATE).getBoolean("optical_flow_v100",true)) return locked;
        int step = c.getSharedPreferences("actor_sticker",Context.MODE_PRIVATE).getInt("flow_step_ms_v100",180);
        step=Math.max(100,Math.min(400,step));
        MediaMetadataRetriever r=new MediaMetadataRetriever();
        ArrayList<ActorScanStore.Hit> out=new ArrayList<>();
        Bitmap prev=null;float pcx=Float.NaN,pcy=Float.NaN;float[] body=null;long prevT=-1;
        try{
            r.setDataSource(c, Uri.parse(sourceUri));
            for(long t=startMs;t<=endMs;t+=step){
                ActorScanStore.Hit anchor=nearest(locked,t,Math.max(260,step));
                Bitmap cur=frame(r,t);
                if(cur==null)continue;
                float cx=Float.isNaN(pcx)?(anchor==null?0.5f:anchor.cx):pcx;
                float cy=Float.isNaN(pcy)?(anchor==null?0.45f:anchor.cy):pcy;
                float conf=anchor==null?0.45f:anchor.score;
                if(anchor!=null&&Math.abs(anchor.t-t)<=step/2){cx=anchor.cx;cy=anchor.cy;conf=Math.max(conf,anchor.score);}
                else if(prev!=null&&!Float.isNaN(pcx)){
                    Flow f=lucasKanade(prev,cur,pcx,pcy);
                    float[] nowBody=bodyHistogram(cur,pcx,pcy);
                    float bodySim=body==null||nowBody==null?0.5f:cos(body,nowBody);
                    boolean bodyRecovery=c.getSharedPreferences("actor_sticker",Context.MODE_PRIVATE).getBoolean("body_reid_v100",true);
                    if(f.valid && (!bodyRecovery || bodySim>=0.38f || f.confidence>=0.62f)){
                        cx=clamp(pcx+f.dx/cur.getWidth(),0.04f,0.96f);cy=clamp(pcy+f.dy/cur.getHeight(),0.05f,0.95f);conf=clamp(0.45f+0.45f*f.confidence+0.10f*bodySim,0f,1f);
                    } else if(anchor!=null){float k=0.18f;cx=pcx*(1-k)+anchor.cx*k;cy=pcy*(1-k)+anchor.cy*k;conf=Math.max(0.40f,anchor.score*0.75f);}
                }
                float[] nb=bodyHistogram(cur,cx,cy);if(nb!=null)body=nb;
                int fw=anchor!=null&&anchor.fw>0?anchor.fw:cur.getWidth();int fh=anchor!=null&&anchor.fh>0?anchor.fh:cur.getHeight();
                out.add(new ActorScanStore.Hit(anchor==null?videoIndex(locked):anchor.videoIndex,t,cx,cy,fw,fh,conf,anchor==null?conf:anchor.quality));
                if(prev!=null&&!prev.isRecycled())prev.recycle();prev=cur;pcx=cx;pcy=cy;prevT=t;
            }
        }catch(Throwable ignored){return locked;}
        finally{if(prev!=null&&!prev.isRecycled())prev.recycle();try{r.release();}catch(Throwable ignored){}}
        return out.size()>=2?actorLock(out):locked;
    }

    /** Actor Lock 2.0: a large identity/path jump needs three consistent high-confidence points. */
    static ArrayList<ActorScanStore.Hit> actorLock(List<ActorScanStore.Hit> input){
        ArrayList<ActorScanStore.Hit>x=new ArrayList<>();if(input!=null)x.addAll(input);x.sort(Comparator.comparingLong(h->h.t));if(x.size()<3)return x;
        ArrayList<ActorScanStore.Hit>o=new ArrayList<>();o.add(x.get(0));
        for(int i=1;i<x.size();i++){
            ActorScanStore.Hit prev=o.get(o.size()-1),cur=x.get(i);float d=dist(prev,cur);long dt=Math.max(1,cur.t-prev.t);
            if(d>0.18f&&dt<1200&&cur.score<0.82f){
                boolean stable=i+2<x.size()&&dist(cur,x.get(i+1))<0.085f&&dist(cur,x.get(i+2))<0.11f&&x.get(i+1).score>0.60f&&x.get(i+2).score>0.60f;
                if(!stable)continue;
            }
            o.add(cur);
        }
        return o;
    }

    private static Flow lucasKanade(Bitmap a,Bitmap b,float ncx,float ncy){
        if(a==null||b==null||a.getWidth()!=b.getWidth()||a.getHeight()!=b.getHeight())return new Flow();
        int w=a.getWidth(),h=a.getHeight();float x=clamp(ncx,0.12f,0.88f)*w,y=clamp(ncy,0.12f,0.88f)*h;float u=0,v=0;double finalResidual=999;
        for(int iter=0;iter<3;iter++){
            double a11=0,a12=0,a22=0,b1=0,b2=0,res=0;int n=0;
            for(int gy=-4;gy<=4;gy++)for(int gx=-4;gx<=4;gx++){
                int px=Math.round(x+gx*4),py=Math.round(y+gy*4);int qx=Math.round(px+u),qy=Math.round(py+v);
                if(px<2||py<2||px>=w-2||py>=h-2||qx<2||qy<2||qx>=w-2||qy>=h-2)continue;
                float ix=(gray(a,px+1,py)-gray(a,px-1,py))*0.5f;float iy=(gray(a,px,py+1)-gray(a,px,py-1))*0.5f;float it=gray(b,qx,qy)-gray(a,px,py);
                a11+=ix*ix;a12+=ix*iy;a22+=iy*iy;b1+=-ix*it;b2+=-iy*it;res+=Math.abs(it);n++;
            }
            double det=a11*a22-a12*a12;if(n<20||det<1e4)return new Flow();double du=(a22*b1-a12*b2)/det,dv=(-a12*b1+a11*b2)/det;u+=du;v+=dv;finalResidual=res/Math.max(1,n);if(Math.abs(du)+Math.abs(dv)<0.15)break;
        }
        Flow f=new Flow();if(Math.abs(u)>w*0.12||Math.abs(v)>h*0.12)return f;f.valid=true;f.dx=(float)u;f.dy=(float)v;f.confidence=clamp((float)(1.0-finalResidual/55.0),0f,1f);return f;
    }

    private static Bitmap frame(MediaMetadataRetriever r,long ms){try{
        if(android.os.Build.VERSION.SDK_INT>=27)return r.getScaledFrameAtTime(ms*1000L,MediaMetadataRetriever.OPTION_CLOSEST,480,270);
        Bitmap b=r.getFrameAtTime(ms*1000L,MediaMetadataRetriever.OPTION_CLOSEST);if(b==null)return null;int w=Math.min(480,b.getWidth());int h=Math.max(1,Math.round(b.getHeight()*(w/(float)b.getWidth())));Bitmap s=Bitmap.createScaledBitmap(b,w,h,true);if(s!=b)b.recycle();return s;
    }catch(Throwable e){return null;}}

    private static float[] bodyHistogram(Bitmap b,float cx,float cy){try{
        int w=b.getWidth(),h=b.getHeight();int l=Math.max(0,Math.round((cx-0.14f)*w)),rr=Math.min(w,Math.round((cx+0.14f)*w));int t=Math.max(0,Math.round((cy+0.08f)*h)),bb=Math.min(h,Math.round((cy+0.38f)*h));if(rr-l<8||bb-t<8)return null;
        float[]hist=new float[64];int n=0;int sx=Math.max(1,(rr-l)/28),sy=Math.max(1,(bb-t)/28);for(int y=t;y<bb;y+=sy)for(int x=l;x<rr;x+=sx){int c=b.getPixel(x,y);int rb=((c>>16)&255)>>6,gb=((c>>8)&255)>>6,bl=(c&255)>>6;hist[rb*16+gb*4+bl]++;n++;}if(n==0)return null;double norm=0;for(float z:hist)norm+=z*z;norm=Math.sqrt(norm)+1e-6;for(int i=0;i<hist.length;i++)hist[i]/=(float)norm;return hist;
    }catch(Throwable e){return null;}}
    private static float cos(float[]a,float[]b){if(a==null||b==null||a.length!=b.length)return 0;double s=0,aa=0,bb=0;for(int i=0;i<a.length;i++){s+=a[i]*b[i];aa+=a[i]*a[i];bb+=b[i]*b[i];}return(float)(s/(Math.sqrt(aa*bb)+1e-6));}
    private static ActorScanStore.Hit nearest(List<ActorScanStore.Hit>x,long t,long max){ActorScanStore.Hit b=null;long d=Long.MAX_VALUE;for(ActorScanStore.Hit h:x){long z=Math.abs(h.t-t);if(z<d){d=z;b=h;}}return d<=max?b:null;}
    private static int videoIndex(List<ActorScanStore.Hit>x){return x.isEmpty()?0:x.get(0).videoIndex;}
    private static float dist(ActorScanStore.Hit a,ActorScanStore.Hit b){return(float)Math.hypot(a.cx-b.cx,a.cy-b.cy);}
    private static float gray(Bitmap b,int x,int y){int c=b.getPixel(x,y);return 0.299f*((c>>16)&255)+0.587f*((c>>8)&255)+0.114f*(c&255);}
    private static float clamp(float v,float a,float b){return Math.max(a,Math.min(b,v));}
    private static final class Flow{boolean valid;float dx,dy,confidence;}
}
