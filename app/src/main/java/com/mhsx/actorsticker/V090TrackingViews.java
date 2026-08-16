package com.mhsx.actorsticker;

import android.content.Context;
import android.graphics.*;
import android.view.MotionEvent;
import android.view.View;

import java.util.*;

final class V090TrackingPathView extends View {
    interface Listener { void changed(String spec); }
    private final Paint line=new Paint(1),actor=new Paint(1),point=new Paint(1),grid=new Paint(1);
    private float[] xs={.5f,.5f,.5f}, ys={.42f,.42f,.42f};
    private int active=-1; private Listener listener; private List<ActorScanStore.Hit> hits=Collections.emptyList();
    V090TrackingPathView(Context c){super(c);line.setColor(0xff2563eb);line.setStrokeWidth(dp(3));actor.setColor(0xff94a3b8);actor.setStrokeWidth(dp(2));point.setColor(0xffe11d48);grid.setColor(0x335b6474);grid.setStrokeWidth(dp(1));setMinimumHeight(dp(180));}
    int dp(int x){return Math.round(x*getResources().getDisplayMetrics().density);}
    void setListener(Listener l){listener=l;}
    void setHits(List<ActorScanStore.Hit> h){hits=h==null?Collections.emptyList():new ArrayList<>(h);invalidate();}
    void setSpec(String s){try{String[]p=s.split(";");for(int i=0;i<Math.min(3,p.length);i++){String[]a=p[i].split(":");if(a.length>=3){xs[i]=cl(Float.parseFloat(a[1]));ys[i]=cl(Float.parseFloat(a[2]));}}}catch(Throwable ignored){}invalidate();}
    String spec(){return String.format(Locale.US,"0:%.4f:%.4f;0.5:%.4f:%.4f;1:%.4f:%.4f",xs[0],ys[0],xs[1],ys[1],xs[2],ys[2]);}
    @Override protected void onDraw(Canvas c){super.onDraw(c);int w=getWidth(),h=getHeight();for(int i=1;i<4;i++){float y=h*i/4f;c.drawLine(0,y,w,y,grid);}for(int i=1;i<4;i++){float x=w*i/4f;c.drawLine(x,0,x,h,grid);}if(hits.size()>1){long min=hits.get(0).t,max=hits.get(hits.size()-1).t;Path p=new Path();boolean first=true;for(ActorScanStore.Hit hit:hits){float px=max<=min?w*.5f:(hit.t-min)/(float)(max-min)*w;float py=cl(hit.cx)*h;if(first){p.moveTo(px,py);first=false;}else p.lineTo(px,py);}c.drawPath(p,actor);}Path manual=new Path();for(int i=0;i<3;i++){float px=i*w/2f,py=cl(xs[i])*h;if(i==0)manual.moveTo(px,py);else manual.lineTo(px,py);}c.drawPath(manual,line);for(int i=0;i<3;i++){float px=i*w/2f,py=cl(xs[i])*h;c.drawCircle(px,py,dp(9),point);}}
    @Override public boolean onTouchEvent(MotionEvent e){float w=Math.max(1,getWidth()),h=Math.max(1,getHeight());if(e.getAction()==MotionEvent.ACTION_DOWN){active=Math.round((e.getX()/w)*2f);active=Math.max(0,Math.min(2,active));getParent().requestDisallowInterceptTouchEvent(true);}if((e.getAction()==MotionEvent.ACTION_MOVE||e.getAction()==MotionEvent.ACTION_DOWN)&&active>=0){xs[active]=cl(e.getY()/h);invalidate();if(listener!=null)listener.changed(spec());return true;}if(e.getAction()==MotionEvent.ACTION_UP||e.getAction()==MotionEvent.ACTION_CANCEL){active=-1;getParent().requestDisallowInterceptTouchEvent(false);return true;}return true;}
    private static float cl(float x){return Math.max(.04f,Math.min(.96f,x));}
}

final class V090WrongPersonTimelineView extends View {
    private final Paint ok=new Paint(1),bad=new Paint(1),grid=new Paint(1);private List<ActorScanStore.Hit> hits=Collections.emptyList();
    V090WrongPersonTimelineView(Context c){super(c);ok.setColor(0xff22c55e);bad.setColor(0xffef4444);grid.setColor(0x33475569);setMinimumHeight(dp(90));}
    int dp(int x){return Math.round(x*getResources().getDisplayMetrics().density);}
    void setHits(List<ActorScanStore.Hit> h){hits=h==null?Collections.emptyList():new ArrayList<>(h);invalidate();}
    int suspicious(){int n=0;for(ActorScanStore.Hit h:hits)if(conf(h)<.52f)n++;return n;}
    @Override protected void onDraw(Canvas c){super.onDraw(c);int w=getWidth(),hh=getHeight();c.drawLine(0,hh*.48f,w,hh*.48f,grid);if(hits.isEmpty())return;long min=hits.get(0).t,max=hits.get(hits.size()-1).t;float bw=Math.max(2,w/(float)Math.max(1,hits.size()));for(ActorScanStore.Hit h:hits){float x=max<=min?0:(h.t-min)/(float)(max-min)*w;float cf=conf(h);Paint p=cf<.52f?bad:ok;c.drawRect(x,hh*(1-cf),Math.min(w,x+bw),hh,p);}}
    private float conf(ActorScanStore.Hit h){return Math.max(0,Math.min(1,h.score*.55f+h.quality*.45f));}
}
