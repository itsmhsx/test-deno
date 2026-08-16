package com.mhsx.actorsticker;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.*;
import android.widget.*;

import androidx.media3.common.util.UnstableApi;

import java.io.File;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** v0.7 performance control surface layered over the stable v0.6.1 camera UI. */
@UnstableApi
public class MainActivityV070 extends MainActivityV060 {
    private static final String TAG_TAB="v070_performance_tab", TAG_SETTINGS="v070_speed_settings";
    private final Handler ui070=new Handler(Looper.getMainLooper());
    private final ExecutorService perfWorker=Executors.newSingleThreadExecutor();
    private boolean dead070;

    private final Runnable loop070=new Runnable(){@Override public void run(){if(dead070)return;try{ensureDefaults070();fixVersionLabels(getWindow().getDecorView());injectPerformanceTab(getWindow().getDecorView());injectSpeedSettings();}catch(Throwable ignored){}ui070.postDelayed(this,650);}};

    @Override public void onCreate(Bundle state){super.onCreate(state);ensureDefaults070();ui070.post(loop070);}
    @Override protected void onDestroy(){dead070=true;ui070.removeCallbacksAndMessages(null);perfWorker.shutdownNow();super.onDestroy();}

    private SharedPreferences p(){return getSharedPreferences("actor_sticker",MODE_PRIVATE);}
    private int dp(int x){return Math.round(x*getResources().getDisplayMetrics().density);}

    private void ensureDefaults070(){SharedPreferences x=p();SharedPreferences.Editor e=x.edit();
        if(!x.contains("speed_mode_v070"))e.putString("speed_mode_v070","Auto");
        if(!x.contains("two_pass_scan_v070"))e.putBoolean("two_pass_scan_v070",true);
        if(!x.contains("frame_extractor_v070"))e.putBoolean("frame_extractor_v070",true);
        if(!x.contains("landmark_on_demand_v070"))e.putBoolean("landmark_on_demand_v070",true);
        if(!x.contains("adaptive_min_face_v070"))e.putBoolean("adaptive_min_face_v070",true);
        if(!x.contains("incremental_cache_v070"))e.putBoolean("incremental_cache_v070",true);
        if(!x.contains("parallel_prefetch_v070"))e.putBoolean("parallel_prefetch_v070",true);
        if(!x.contains("lazy_thumbs_v070"))e.putBoolean("lazy_thumbs_v070",true);
        if(!x.contains("fixed_skip_dense_v070"))e.putBoolean("fixed_skip_dense_v070",true);
        if(!x.contains("trim_opt_v070"))e.putBoolean("trim_opt_v070",true);
        if(!x.contains("auto_codec_v070"))e.putBoolean("auto_codec_v070",true);
        if(!x.contains("controlled_encoder_v070"))e.putBoolean("controlled_encoder_v070",true);
        e.apply();
    }

    private void fixVersionLabels(View v){if(v==null)return;if(v instanceof TextView){TextView t=(TextView)v;String s=String.valueOf(t.getText());if(s.contains("v0.6")||s.contains("v0.6.1"))t.setText(s.replace("v0.6.1","v0.7").replace("v0.6","v0.7"));if(s.equals("App version: 0.6.0")||s.equals("App version: 0.6.1"))t.setText("App version: 0.7.0");}if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++)fixVersionLabels(g.getChildAt(i));}}

    private void injectPerformanceTab(View v){if(!(v instanceof ViewGroup))return;ViewGroup g=(ViewGroup)v;if(g instanceof LinearLayout && g.getParent() instanceof HorizontalScrollView){for(int i=0;i<g.getChildCount();i++)if(TAG_TAB.equals(g.getChildAt(i).getTag()))return;Button b=new Button(this);b.setTag(TAG_TAB);b.setAllCaps(false);b.setText(tr("Performance"));styleButton(b);b.setOnClickListener(x->showPerformance());g.addView(b);return;}for(int i=0;i<g.getChildCount();i++)injectPerformanceTab(g.getChildAt(i));}

    private void injectSpeedSettings(){if(!"settings".equals(currentPage()))return;LinearLayout c=content();if(c==null)return;for(int i=0;i<c.getChildCount();i++)if(TAG_SETTINGS.equals(c.getChildAt(i).getTag()))return;LinearLayout box=panel(TAG_SETTINGS,tr("Performance Engine v0.7"));
        box.addView(label(tr("Scan speed / accuracy"),13,true));String[] modes={"Quick","Balanced","Ultra Accurate","Auto"};Spinner mode=spinner(modes,p().getString("speed_mode_v070","Auto"));mode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?>a,View v,int pos,long id){p().edit().putString("speed_mode_v070",modes[pos]).apply();}public void onNothingSelected(AdapterView<?>a){}});box.addView(mode);
        addToggle(box,"two_pass_scan_v070",tr("Two-pass scan: quick discovery + accurate identity"),true);
        addToggle(box,"frame_extractor_v070",tr("Media3 FrameExtractor 1.10 fast sampling"),true);
        addToggle(box,"landmark_on_demand_v070",tr("Landmarks only when identity needs verification"),true);
        addToggle(box,"adaptive_min_face_v070",tr("Adaptive minimum face size"),true);
        addToggle(box,"incremental_cache_v070",tr("Reuse unchanged videos from private cache"),true);
        addToggle(box,"parallel_prefetch_v070",tr("One-frame decode prefetch pipeline"),true);
        addToggle(box,"lazy_thumbs_v070",tr("Lazy / sampled Actor Gallery thumbnails"),true);
        addToggle(box,"fixed_skip_dense_v070",tr("Skip dense tracking in Fixed camera modes"),true);
        addToggle(box,"trim_opt_v070",tr("Transformer trim optimization when compatible"),true);
        addToggle(box,"auto_codec_v070",tr("Auto-select fastest hardware codec"),true);
        addToggle(box,"controlled_encoder_v070",tr("Controlled encoder queue / thermal friendly"),true);
        Button bench=new Button(this);bench.setAllCaps(false);bench.setText(tr("Benchmark AVC / HEVC on this phone"));styleButton(bench);bench.setOnClickListener(v->runCodecBenchmark());box.addView(bench);
        Button dash=new Button(this);dash.setAllCaps(false);dash.setText(tr("Open Performance Dashboard"));styleButton(dash);dash.setOnClickListener(v->showPerformance());box.addView(dash);
        c.addView(box);
    }

    private void addToggle(LinearLayout box,String key,String text,boolean def){CheckBox c=new CheckBox(this);c.setText(text);c.setChecked(p().getBoolean(key,def));c.setTextColor(textColor());c.setOnClickListener(v->p().edit().putBoolean(key,c.isChecked()).apply());box.addView(c);}

    private void showPerformance(){LinearLayout c=content();if(c==null)return;c.removeAllViews();c.addView(label(tr("Performance Dashboard • v0.7"),21,true));SharedPreferences x=p();double decode=x.getFloat("perf_decode_ms_v070",0),detect=x.getFloat("perf_detect_ms_v070",0),identity=x.getFloat("perf_identity_ms_v070",0),export=x.getFloat("perf_export_ms_v070",0);double total=Math.max(1,decode+detect+identity+export);
        c.addView(metric(tr("Frame decode / extraction"),decode,decode/total));c.addView(metric(tr("Face detection"),detect,detect/total));c.addView(metric(tr("Identity grouping"),identity,identity/total));c.addView(metric(tr("Video export"),export,export/total));
        c.addView(label(tr("Coarse frames")+": "+x.getInt("perf_coarse_frames_v070",0)+"   •   "+tr("Fine frames")+": "+x.getInt("perf_fine_frames_v070",0),14,false));
        c.addView(label(tr("Incremental cache")+": "+x.getInt("perf_cache_hits_v070",0)+" hit / "+x.getInt("perf_cache_misses_v070",0)+" miss",14,false));
        c.addView(label(String.format(Locale.US,"%s: %.1f%%",tr("Adaptive minimum face size"),x.getFloat("last_adaptive_min_face_v070",0.065f)*100),14,false));
        c.addView(label(tr("Hardware codecs")+": "+V070CodecAdvisor.hardwareSummary(),14,false));
        long avc=x.getLong("codec_bench_avc_ms_v070",-1),hevc=x.getLong("codec_bench_hevc_ms_v070",-1);c.addView(label(tr("Codec benchmark")+": AVC "+fmtMs(avc)+" • HEVC "+fmtMs(hevc)+" • "+tr("Auto")+" = "+V070CodecAdvisor.bestCodec(x),14,true));
        c.addView(label(tr("Scan mode")+": "+x.getString("speed_mode_v070","Auto")+" • Media3 1.10.1 FrameExtractor • sync-seek/downscale",13,false));
        Button bench=new Button(this);bench.setText(tr("Run codec benchmark"));bench.setAllCaps(false);styleButton(bench);bench.setOnClickListener(v->runCodecBenchmark());c.addView(bench);
        Button clean=new Button(this);clean.setText(tr("Clear v0.7 per-video speed cache"));clean.setAllCaps(false);styleButton(clean);clean.setOnClickListener(v->confirmClearSpeedCache());c.addView(clean);
        Button settings=new Button(this);settings.setText(tr("Open Settings"));settings.setAllCaps(false);styleButton(settings);settings.setOnClickListener(v->invokeBase("showSettings"));c.addView(settings);
    }

    private View metric(String name,double ms,double fraction){LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(10),dp(7),dp(10),dp(7));box.setBackground(round(cardColor(),borderColor(),14));TextView top=label(String.format(Locale.US,"%s  %.0f ms  (%.0f%%)",name,ms,fraction*100),13,true);box.addView(top);ProgressBar b=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);b.setMax(100);b.setProgress((int)Math.round(fraction*100));box.addView(b,new LinearLayout.LayoutParams(-1,dp(7)));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,dp(4),0,dp(4));box.setLayoutParams(lp);return box;}

    private void runCodecBenchmark(){ArrayList<Uri> vs=videos();if(vs.isEmpty()){Toast.makeText(this,tr("Add a video first"),Toast.LENGTH_SHORT).show();return;}Toast.makeText(this,tr("Codec benchmark running on a 2.5s sample…"),Toast.LENGTH_LONG).show();perfWorker.execute(()->{try{V070CodecAdvisor.Benchmark b=V070CodecAdvisor.benchmark(this,vs.get(0),p());runOnUiThread(()->new AlertDialog.Builder(this).setTitle(tr("Codec benchmark")).setMessage("AVC: "+fmtMs(b.avcMs)+"\nHEVC: "+fmtMs(b.hevcMs)+"\n"+tr("Recommended")+": "+b.best).setPositiveButton("OK",(d,w)->showPerformance()).show());}catch(Throwable e){runOnUiThread(()->Toast.makeText(this,tr("Benchmark failed")+": "+e.getClass().getSimpleName(),Toast.LENGTH_LONG).show());}});}
    private String fmtMs(long v){return v<0||v==Long.MAX_VALUE?"N/A":String.format(Locale.US,"%.2fs",v/1000.0);}

    private void confirmClearSpeedCache(){new AlertDialog.Builder(this).setTitle(tr("Clear speed cache?")).setMessage(tr("Only v0.7 per-video acceleration fragments will be removed. Your source videos are untouched.")).setNegativeButton(tr("Cancel"),null).setPositiveButton(tr("Clear"),(d,w)->perfWorker.execute(()->{deleteRec(new File(getFilesDir(),"actor_cache_v070"));runOnUiThread(()->Toast.makeText(this,tr("Speed cache cleared"),Toast.LENGTH_SHORT).show());})).show();}

    @SuppressWarnings("unchecked") private ArrayList<Uri> videos(){try{Field f=MainActivity.class.getDeclaredField("videos");f.setAccessible(true);return new ArrayList<>((ArrayList<Uri>)f.get(this));}catch(Throwable e){return new ArrayList<>();}}
    private String currentPage(){try{Field f=MainActivity.class.getDeclaredField("currentPage");f.setAccessible(true);return String.valueOf(f.get(this));}catch(Throwable e){return"";}}
    private LinearLayout content(){try{Field f=MainActivity.class.getDeclaredField("content");f.setAccessible(true);return(LinearLayout)f.get(this);}catch(Throwable e){return null;}}
    private void invokeBase(String name){Class<?> q=getClass().getSuperclass();while(q!=null){try{java.lang.reflect.Method m=q.getDeclaredMethod(name);m.setAccessible(true);m.invoke(this);return;}catch(NoSuchMethodException e){q=q.getSuperclass();}catch(Throwable e){return;}}}

    private LinearLayout panel(String tag,String title){LinearLayout b=new LinearLayout(this);b.setTag(tag);b.setOrientation(LinearLayout.VERTICAL);b.setPadding(dp(12),dp(10),dp(12),dp(10));b.setBackground(round(cardColor(),borderColor(),18));b.addView(label(title,17,true));return b;}
    private TextView label(String s,int sp,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(sp);t.setTextColor(textColor());if(bold)t.setTypeface(null,1);t.setPadding(dp(4),dp(5),dp(4),dp(5));return t;}
    private Spinner spinner(String[] items,String selected){Spinner s=new Spinner(this);s.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,items));for(int i=0;i<items.length;i++)if(items[i].equals(selected))s.setSelection(i);return s;}
    private void styleButton(Button b){b.setTextColor(Color.WHITE);b.setBackground(round(accentColor(),accentColor(),14));b.setPadding(dp(12),dp(6),dp(12),dp(6));}
    private GradientDrawable round(int fill,int stroke,int r){GradientDrawable d=new GradientDrawable();d.setColor(fill);d.setCornerRadius(dp(r));d.setStroke(dp(1),stroke);return d;}
    private int textColor(){return"Dark".equals(p().getString("theme_v060","Dark"))?0xfff4f7fb:0xff172033;}private int cardColor(){return"Dark".equals(p().getString("theme_v060","Dark"))?0xff151e2d:0xffffffff;}private int borderColor(){return"Dark".equals(p().getString("theme_v060","Dark"))?0xff29364b:0xffd8dee9;}private int accentColor(){String t=p().getString("theme_v060","Dark");if("Red".equals(t))return 0xffe11d48;if("Pink".equals(t))return 0xffdb2777;if("White".equals(t))return 0xff202938;return 0xff2563eb;}
    private static void deleteRec(File f){if(f==null||!f.exists())return;if(f.isDirectory()){File[]a=f.listFiles();if(a!=null)for(File x:a)deleteRec(x);}try{f.delete();}catch(Throwable ignored){}}

    private String tr(String en){String l=p().getString("language_v060","en");if("en".equals(l))return en;Map<String,String[]>m=new HashMap<>();
        m.put("Performance",new String[]{"کارایی","Performans","Производительность"});m.put("Performance Engine v0.7",new String[]{"موتور سرعت v0.7","v0.7 Performans Motoru","Движок производительности v0.7"});m.put("Scan speed / accuracy",new String[]{"سرعت / دقت اسکن","Tarama hızı / doğruluk","Скорость / точность сканирования"});m.put("Two-pass scan: quick discovery + accurate identity",new String[]{"اسکن دو مرحله‌ای: پیدا کردن سریع + هویت دقیق","İki aşamalı tarama: hızlı bulma + doğru kimlik","Двухпроходное сканирование: быстрый поиск + точная личность"});m.put("Media3 FrameExtractor 1.10 fast sampling",new String[]{"نمونه‌برداری سریع Media3 FrameExtractor 1.10","Hızlı Media3 FrameExtractor 1.10 örnekleme","Быстрый Media3 FrameExtractor 1.10"});m.put("Landmarks only when identity needs verification",new String[]{"Landmark فقط موقع تأیید هویت","Landmark yalnız kimlik doğrulamada","Ориентиры только при проверке личности"});m.put("Adaptive minimum face size",new String[]{"حداقل اندازه چهره تطبیقی","Uyarlanabilir minimum yüz boyutu","Адаптивный минимальный размер лица"});m.put("Reuse unchanged videos from private cache",new String[]{"استفاده مجدد از ویدیوهای بدون تغییر","Değişmeyen videoları özel önbellekten kullan","Повторно использовать неизменённые видео"});m.put("One-frame decode prefetch pipeline",new String[]{"پیش‌خوانی یک فریم برای Decode","Tek kare ön getirme hattı","Предзагрузка одного кадра"});m.put("Lazy / sampled Actor Gallery thumbnails",new String[]{"لود سبک عکس‌های گالری بازیگر","Oyuncu galerisi küçük resimlerini tembel yükle","Ленивая загрузка миниатюр галереи"});m.put("Skip dense tracking in Fixed camera modes",new String[]{"حذف Tracking سنگین در حالت ثابت","Sabit kamera modunda yoğun takibi atla","Пропуск плотного трекинга в фиксированных режимах"});m.put("Transformer trim optimization when compatible",new String[]{"بهینه‌سازی Trim در صورت سازگاری","Uyumluysa Transformer kırpma optimizasyonu","Оптимизация обрезки Transformer при совместимости"});m.put("Auto-select fastest hardware codec",new String[]{"انتخاب خودکار سریع‌ترین کدک سخت‌افزاری","En hızlı donanım codec'ini otomatik seç","Автовыбор самого быстрого аппаратного кодека"});m.put("Controlled encoder queue / thermal friendly",new String[]{"صف کنترل‌شده Encoder / مناسب حرارت","Kontrollü encoder kuyruğu / termal dostu","Контролируемая очередь энкодера"});m.put("Benchmark AVC / HEVC on this phone",new String[]{"تست سرعت AVC / HEVC روی این گوشی","Bu telefonda AVC / HEVC testi","Тест AVC / HEVC на этом телефоне"});m.put("Open Performance Dashboard",new String[]{"باز کردن داشبورد کارایی","Performans Panelini Aç","Открыть панель производительности"});m.put("Performance Dashboard • v0.7",new String[]{"داشبورد کارایی • v0.7","Performans Paneli • v0.7","Панель производительности • v0.7"});m.put("Frame decode / extraction",new String[]{"Decode / استخراج فریم","Kare decode / çıkarma","Декодирование / извлечение кадров"});m.put("Face detection",new String[]{"تشخیص چهره","Yüz algılama","Обнаружение лиц"});m.put("Identity grouping",new String[]{"گروه‌بندی هویت","Kimlik gruplama","Группировка личности"});m.put("Video export",new String[]{"خروجی ویدیو","Video dışa aktarma","Экспорт видео"});m.put("Coarse frames",new String[]{"فریم‌های سریع","Kaba kareler","Грубые кадры"});m.put("Fine frames",new String[]{"فریم‌های دقیق","İnce kareler","Точные кадры"});m.put("Incremental cache",new String[]{"کش افزایشی","Artımlı önbellek","Инкрементальный кэш"});m.put("Hardware codecs",new String[]{"کدک‌های سخت‌افزاری","Donanım codec'leri","Аппаратные кодеки"});m.put("Codec benchmark",new String[]{"تست کدک","Codec testi","Тест кодека"});m.put("Auto",new String[]{"خودکار","Otomatik","Авто"});m.put("Scan mode",new String[]{"حالت اسکن","Tarama modu","Режим сканирования"});m.put("Run codec benchmark",new String[]{"اجرای تست کدک","Codec testini çalıştır","Запустить тест кодека"});m.put("Clear v0.7 per-video speed cache",new String[]{"پاک کردن کش سرعت v0.7","v0.7 hız önbelleğini temizle","Очистить кэш скорости v0.7"});m.put("Open Settings",new String[]{"باز کردن تنظیمات","Ayarları Aç","Открыть настройки"});m.put("Add a video first",new String[]{"اول یک ویدیو اضافه کن","Önce video ekle","Сначала добавьте видео"});m.put("Codec benchmark running on a 2.5s sample…",new String[]{"تست کدک روی نمونه ۲.۵ ثانیه‌ای در حال اجراست…","2.5 sn örnekte codec testi çalışıyor…","Тест кодека на образце 2,5 с…"});m.put("Recommended",new String[]{"پیشنهادی","Önerilen","Рекомендуется"});m.put("Benchmark failed",new String[]{"تست ناموفق بود","Test başarısız","Тест не удался"});m.put("Clear speed cache?",new String[]{"کش سرعت پاک شود؟","Hız önbelleği temizlensin mi?","Очистить кэш скорости?"});m.put("Only v0.7 per-video acceleration fragments will be removed. Your source videos are untouched.",new String[]{"فقط کش شتاب‌دهی v0.7 پاک می‌شود و ویدیوهای اصلی دست‌نخورده می‌مانند.","Yalnız v0.7 hız parçaları silinir; kaynak videolar değişmez.","Удалится только кэш ускорения v0.7; исходные видео не изменятся."});m.put("Cancel",new String[]{"لغو","İptal","Отмена"});m.put("Clear",new String[]{"پاک کردن","Temizle","Очистить"});m.put("Speed cache cleared",new String[]{"کش سرعت پاک شد","Hız önbelleği temizlendi","Кэш скорости очищен"});
        String[]a=m.get(en);int i="fa".equals(l)?0:"tr".equals(l)?1:2;return a==null?en:a[i];}
}
