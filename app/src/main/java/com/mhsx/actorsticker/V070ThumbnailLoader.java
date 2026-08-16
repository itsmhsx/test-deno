package com.mhsx.actorsticker;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Avoids full-size synchronous bitmap decoding while Actor Gallery is built. */
final class V070ThumbnailLoader {
    private static final ExecutorService pool=Executors.newFixedThreadPool(2);
    private static final LruCache<String,Bitmap> cache=new LruCache<String,Bitmap>(12*1024){
        @Override protected int sizeOf(String k,Bitmap b){return Math.max(1,b.getByteCount()/1024);}
    };
    private V070ThumbnailLoader(){}

    static void load(ImageView view, File file, int targetPx){
        if(view==null||file==null||!file.isFile())return;String key=file.getAbsolutePath()+"@"+targetPx;Bitmap hit=cache.get(key);if(hit!=null&&!hit.isRecycled()){view.setImageBitmap(hit);return;}
        pool.execute(()->{try{BitmapFactory.Options o=new BitmapFactory.Options();o.inJustDecodeBounds=true;BitmapFactory.decodeFile(file.getAbsolutePath(),o);int s=1;while(o.outWidth/s>targetPx*2||o.outHeight/s>targetPx*2)s*=2;BitmapFactory.Options d=new BitmapFactory.Options();d.inSampleSize=Math.max(1,s);d.inPreferredConfig=Bitmap.Config.RGB_565;Bitmap b=BitmapFactory.decodeFile(file.getAbsolutePath(),d);if(b==null)return;cache.put(key,b);view.post(()->{if(view.isAttachedToWindow())view.setImageBitmap(b);});}catch(Throwable ignored){}});
    }
}
