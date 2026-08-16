package com.mhsx.actorsticker;

import android.content.Context;
import android.graphics.Color;
import android.view.*;
import android.widget.*;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.*;

final class V090ActorAdapter extends ListAdapter<V090ActorAdapter.Item,V090ActorAdapter.Holder> {
    interface Callback { void select(int id); void buildReferences(int id); }
    static final class Item {
        final int id,count,videos; final float quality; final double score; final boolean selected; final String projectKey; final List<String> thumbs;
        Item(int id,int count,int videos,float quality,double score,boolean selected,String projectKey,List<String> thumbs){this.id=id;this.count=count;this.videos=videos;this.quality=quality;this.score=score;this.selected=selected;this.projectKey=projectKey;this.thumbs=thumbs;}
    }
    static final DiffUtil.ItemCallback<Item> DIFF=new DiffUtil.ItemCallback<Item>(){
        @Override public boolean areItemsTheSame(@NonNull Item a,@NonNull Item b){return a.id==b.id;}
        @Override public boolean areContentsTheSame(@NonNull Item a,@NonNull Item b){return a.count==b.count&&a.videos==b.videos&&Math.abs(a.quality-b.quality)<.001&&a.selected==b.selected&&a.thumbs.equals(b.thumbs);}
    };
    private final Context context; private final Callback cb; private final ActorScanStore store; private final int density;
    V090ActorAdapter(Context c,Callback cb){super(DIFF);context=c;this.cb=cb;store=new ActorScanStore(c);density=Math.max(1,Math.round(c.getResources().getDisplayMetrics().density));}
    private int dp(int x){return x*density;}

    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent,int viewType){
        LinearLayout card=new LinearLayout(context);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(12),dp(10),dp(12),dp(10));
        TextView title=new TextView(context);title.setTextSize(15);title.setTypeface(null,1);card.addView(title);
        TextView meta=new TextView(context);meta.setTextSize(12);card.addView(meta);
        LinearLayout thumbs=new LinearLayout(context);thumbs.setOrientation(LinearLayout.HORIZONTAL);card.addView(thumbs);
        LinearLayout buttons=new LinearLayout(context);buttons.setOrientation(LinearLayout.HORIZONTAL);
        Button select=new Button(context);select.setAllCaps(false);buttons.addView(select,new LinearLayout.LayoutParams(0,-2,1));
        Button refs=new Button(context);refs.setAllCaps(false);refs.setText("Reference bank");buttons.addView(refs,new LinearLayout.LayoutParams(0,-2,1));card.addView(buttons);
        RecyclerView.LayoutParams lp=new RecyclerView.LayoutParams(-1,-2);lp.setMargins(dp(4),dp(4),dp(4),dp(4));card.setLayoutParams(lp);return new Holder(card,title,meta,thumbs,select,refs);
    }

    @Override public void onBindViewHolder(@NonNull Holder h,int pos){Item x=getItem(pos);h.title.setText((x.selected?"✓ ":"")+"Actor "+(x.id+1));h.meta.setText(String.format(Locale.US,"%d detections • %d video(s) • quality %.0f%% • score %.1f",x.count,x.videos,x.quality*100,x.score));h.title.setTextColor(0xff172033);h.meta.setTextColor(0xff536071);h.itemView.setBackgroundColor(x.selected?0xffddf7e5:0xffffffff);h.thumbs.removeAllViews();int n=0;for(String rel:x.thumbs){if(rel==null||rel.isEmpty()||n>=3)continue;File f=store.thumbnailFile(x.projectKey,rel);if(!f.isFile())continue;ImageView iv=new ImageView(context);iv.setScaleType(ImageView.ScaleType.CENTER_CROP);LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(dp(88),dp(88));ip.setMargins(0,dp(4),dp(6),dp(4));h.thumbs.addView(iv,ip);V070ThumbnailLoader.load(iv,f,dp(88));n++;}h.select.setText(x.selected?"Selected":"Select actor");h.select.setOnClickListener(v->cb.select(x.id));h.refs.setOnClickListener(v->cb.buildReferences(x.id));}

    static final class Holder extends RecyclerView.ViewHolder{final TextView title,meta;final LinearLayout thumbs;final Button select,refs;Holder(View item,TextView t,TextView m,LinearLayout th,Button s,Button r){super(item);title=t;meta=m;thumbs=th;select=s;refs=r;}}
}
