package com.mhsx.actorsticker;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.view.*;
import android.widget.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

/**
 * v0.6 presentation/intelligence layer: modern tabs, live percentage progress,
 * multilingual UI, five themes, correction learning controls, confidence
 * timeline, smart moment filters, dense tracking and smart composition.
 */
public class MainActivityV060 extends MainActivityV050 {
    private static final String TAG_PROGRESS = "v060_progress_card";
    private static final String TAG_SETTINGS = "v060_settings_panel";
    private static final String TAG_GALLERY = "v060_gallery_panel";
    private static final String TAG_OUTPUT = "v060_output_panel";

    private final Handler v060ui = new Handler(Looper.getMainLooper());
    private boolean v060Destroyed;
    private TextView liveStage, livePercent, liveDetail;
    private ProgressBar liveBar;
    private long jobStartMs;
    private int lastProgress;
    private String lastStage = "";

    private final Runnable modernLoop = new Runnable() {
        @Override public void run() {
            if (v060Destroyed) return;
            try {
                ensureDefaults();
                applyDirection();
                View decor = getWindow().getDecorView();
                ensureProgressCard();
                modernize(decor, 0);
                injectPageIntelligence();
                updateLiveProgress();
                configureComposition();
            } catch (Throwable ignored) {}
            v060ui.postDelayed(this, 420L);
        }
    };

    @Override public void onCreate(Bundle state) {
        SharedPreferences p = prefs060();
        String theme = p.getString("theme_v060", "Dark");
        p.edit().putBoolean("dark_mode_v050", "Dark".equals(theme)).apply();
        setTheme("Dark".equals(theme) ? android.R.style.Theme_Material_NoActionBar : android.R.style.Theme_Material_Light_NoActionBar);
        super.onCreate(state);
        ensureDefaults();
        v060ui.post(modernLoop);
    }

    @Override protected void onDestroy() {
        v060Destroyed = true;
        v060ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private SharedPreferences prefs060() { return getSharedPreferences("actor_sticker", MODE_PRIVATE); }
    private int dp060(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private void ensureDefaults() {
        SharedPreferences p = prefs060();
        SharedPreferences.Editor e = p.edit();
        if (!p.contains("language_v060")) e.putString("language_v060", "en");
        if (!p.contains("theme_v060")) e.putString("theme_v060", "Dark");
        if (!p.contains("dense_tracking_v060")) e.putBoolean("dense_tracking_v060", true);
        if (!p.contains("tracking_step_ms_v060")) e.putInt("tracking_step_ms_v060", 120);
        if (!p.contains("smart_sampler_v060")) e.putBoolean("smart_sampler_v060", true);
        if (!p.contains("correction_learning_v060")) e.putBoolean("correction_learning_v060", true);
        if (!p.contains("smart_composition_v060")) e.putString("smart_composition_v060", "Look room");
        if (!p.contains("moment_filter_v060")) e.putString("moment_filter_v060", "All");
        if (!p.contains("best_only_v060")) e.putBoolean("best_only_v060", false);
        if (!p.contains("best_top_n_v060")) e.putInt("best_top_n_v060", 20);
        if (!p.contains("smart_rescan_v060")) e.putBoolean("smart_rescan_v060", true);
        if (!p.contains("confidence_timeline_v060")) e.putBoolean("confidence_timeline_v060", true);
        e.apply();
    }

    private static final class Palette {
        final int bg, card, text, sub, accent, accent2, border;
        Palette(int bg,int card,int text,int sub,int accent,int accent2,int border){this.bg=bg;this.card=card;this.text=text;this.sub=sub;this.accent=accent;this.accent2=accent2;this.border=border;}
    }

    private Palette palette() {
        String t = prefs060().getString("theme_v060", "Dark");
        if ("White".equals(t)) return new Palette(0xfff6f7fb,0xffffffff,0xff121826,0xff5b6474,0xff202938,0xff111827,0xffd8dee9);
        if ("Blue".equals(t)) return new Palette(0xffeef5ff,0xffffffff,0xff10213d,0xff53657f,0xff2563eb,0xff1d4ed8,0xffc9dcff);
        if ("Red".equals(t)) return new Palette(0xfffff1f2,0xffffffff,0xff2b1518,0xff76545a,0xffe11d48,0xffbe123c,0xffffc7d2);
        if ("Pink".equals(t)) return new Palette(0xfffff1f8,0xffffffff,0xff30152a,0xff76546e,0xffdb2777,0xffbe185d,0xffffc9e5);
        return new Palette(0xff0b111b,0xff151e2d,0xfff4f7fb,0xffaebbd0,0xff3b82f6,0xff60a5fa,0xff29364b);
    }

    private void applyDirection() {
        boolean rtl = "fa".equals(prefs060().getString("language_v060", "en"));
        getWindow().getDecorView().setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
    }

    private void modernize(View v, int depth) {
        if (v == null) return;
        Palette p = palette();
        if (depth == 0) v.setBackgroundColor(p.bg);
        if (v instanceof TextView && !(v instanceof Button)) {
            TextView t=(TextView)v;
            localize(t);
            t.setTextColor(p.text);
            t.setFontFeatureSettings("kern");
        }
        if (v instanceof EditText) {
            EditText e=(EditText)v; e.setTextColor(p.text); e.setHintTextColor(p.sub);
            e.setBackground(roundDrawable(p.card,p.border,12));
            e.setPadding(dp060(12),dp060(8),dp060(12),dp060(8));
        }
        if (v instanceof CheckBox) ((CheckBox)v).setTextColor(p.text);
        if (v instanceof Button) styleButton((Button)v, p);
        if (v instanceof Spinner) v.setBackground(roundDrawable(p.card,p.border,12));
        if (v instanceof LinearLayout) {
            Object tag=v.getTag();
            if (TAG_PROGRESS.equals(tag)||TAG_SETTINGS.equals(tag)||TAG_GALLERY.equals(tag)||TAG_OUTPUT.equals(tag)) v.setBackground(roundDrawable(p.card,p.border,18));
            else if (depth <= 3) v.setBackgroundColor(p.bg);
        }
        if (v instanceof ScrollView || v instanceof HorizontalScrollView) v.setBackgroundColor(p.bg);
        if (v instanceof ViewGroup) {
            ViewGroup g=(ViewGroup)v;
            for(int i=0;i<g.getChildCount();i++) modernize(g.getChildAt(i),depth+1);
        }
        getWindow().setStatusBarColor(p.bg);
        getWindow().setNavigationBarColor(p.bg);
    }

    private void styleButton(Button b, Palette p) {
        localize(b);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(13.5f);
        b.setMinHeight(dp060(44));
        b.setPadding(dp060(15),dp060(7),dp060(15),dp060(7));
        b.setBackground(roundDrawable(p.accent,p.accent2,14));
        ViewGroup.LayoutParams lp=b.getLayoutParams();
        if(lp instanceof LinearLayout.LayoutParams){LinearLayout.LayoutParams x=(LinearLayout.LayoutParams)lp;x.setMargins(dp060(4),dp060(4),dp060(4),dp060(4));b.setLayoutParams(x);}
    }

    private GradientDrawable roundDrawable(int fill,int stroke,int radius) {
        GradientDrawable d=new GradientDrawable(); d.setColor(fill); d.setCornerRadius(dp060(radius)); d.setStroke(dp060(1),stroke); return d;
    }

    private void ensureProgressCard() {
        ViewGroup host=(ViewGroup)findViewById(android.R.id.content);
        if(host==null||host.getChildCount()==0||!(host.getChildAt(0) instanceof LinearLayout))return;
        LinearLayout root=(LinearLayout)host.getChildAt(0);
        for(int i=0;i<root.getChildCount();i++) if(TAG_PROGRESS.equals(root.getChildAt(i).getTag())) return;
        Palette p=palette();
        LinearLayout card=new LinearLayout(this); card.setTag(TAG_PROGRESS); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(dp060(14),dp060(10),dp060(14),dp060(10));
        LinearLayout top=new LinearLayout(this); top.setOrientation(LinearLayout.HORIZONTAL); top.setGravity(Gravity.CENTER_VERTICAL);
        liveStage=new TextView(this); liveStage.setTextSize(13); liveStage.setTypeface(null,1); liveStage.setTextColor(p.text);
        livePercent=new TextView(this); livePercent.setTextSize(17); livePercent.setTypeface(null,1); livePercent.setTextColor(p.accent);
        top.addView(liveStage,new LinearLayout.LayoutParams(0,-2,1)); top.addView(livePercent); card.addView(top);
        liveBar=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal); liveBar.setMax(100); liveBar.setProgressTintList(ColorStateList.valueOf(p.accent)); liveBar.setProgressBackgroundTintList(ColorStateList.valueOf(p.border)); card.addView(liveBar,new LinearLayout.LayoutParams(-1,dp060(8)));
        liveDetail=new TextView(this); liveDetail.setTextSize(11.5f); liveDetail.setTextColor(p.sub); liveDetail.setPadding(0,dp060(4),0,0); card.addView(liveDetail);
        int index=Math.min(2,root.getChildCount()); root.addView(card,index,new LinearLayout.LayoutParams(-1,-2));
    }

    private void updateLiveProgress() {
        if(liveBar==null)return;
        SharedPreferences p=prefs060();
        boolean scan=p.getBoolean("bg_scan_running",false);
        boolean export=p.getBoolean("export_pending_v050",false);
        int base=baseProgress();
        int progress; String stage,detail;
        if(scan){progress=p.getInt("bg_scan_progress",0);stage=tr("Scanning actors");detail=p.getString("bg_scan_status",tr("Working"));}
        else if(export||base>0){int dense=p.getInt("v060_track_progress",0);progress=Math.max(base, Math.min(99,dense));stage=tr("Analyzing / Tracking / Exporting");detail=p.getString("v060_track_status",tr("Processing video"));}
        else {progress=0;stage=tr("Ready");detail=tr("Waiting for a job");}
        progress=Math.max(0,Math.min(100,progress));
        if(!stage.equals(lastStage)||progress<lastProgress){jobStartMs=System.currentTimeMillis();lastStage=stage;}
        if(jobStartMs==0&&progress>0)jobStartMs=System.currentTimeMillis();
        lastProgress=progress;
        liveBar.setProgress(progress); liveStage.setText(stage); livePercent.setText(progress+"%");
        if(progress>1&&progress<100&&jobStartMs>0){long elapsed=System.currentTimeMillis()-jobStartMs;long eta=(long)(elapsed*(100-progress)/(double)Math.max(1,progress));detail=detail+"  •  "+tr("ETA")+" "+formatDuration(eta);} 
        liveDetail.setText(detail);
    }

    private int baseProgress(){try{Field f=MainActivity.class.getDeclaredField("progress");f.setAccessible(true);ProgressBar b=(ProgressBar)f.get(this);return b==null?0:b.getProgress();}catch(Throwable e){return 0;}}
    private String formatDuration(long ms){long s=Math.max(0,ms/1000);return String.format(Locale.US,"%02d:%02d",s/60,s%60);}

    private void injectPageIntelligence() {
        LinearLayout c=content(); if(c==null||c.getChildCount()==0)return;
        String head=c.getChildAt(0) instanceof TextView?String.valueOf(((TextView)c.getChildAt(0)).getText()):"";
        String normalized=englishMeaning(head);
        if(normalized.contains("Settings")) injectSettings(c);
        else if(normalized.contains("Actor Gallery")) injectGallery(c);
        else if(normalized.contains("Output")) injectOutput(c);
    }

    private LinearLayout content(){try{Field f=MainActivity.class.getDeclaredField("content");f.setAccessible(true);return(LinearLayout)f.get(this);}catch(Throwable e){return null;}}
    private ActorScanStore.ScanState scanState(){try{Field f=MainActivity.class.getDeclaredField("scanState");f.setAccessible(true);return(ActorScanStore.ScanState)f.get(this);}catch(Throwable e){return null;}}
    private int selectedId(){try{Field f=MainActivity.class.getDeclaredField("selectedClusterId");f.setAccessible(true);return f.getInt(this);}catch(Throwable e){return -1;}}
    private ActorScanStore.Cluster selectedActor(){ActorScanStore.ScanState s=scanState();return s==null?null:s.byId(selectedId());}

    private LinearLayout panel(String tag,String title){Palette p=palette();LinearLayout box=new LinearLayout(this);box.setTag(tag);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp060(12),dp060(10),dp060(12),dp060(10));box.setBackground(roundDrawable(p.card,p.border,18));TextView h=new TextView(this);h.setText(title);h.setTextSize(16);h.setTypeface(null,1);h.setTextColor(p.text);box.addView(h);return box;}
    private CheckBox check(String text,boolean checked){CheckBox x=new CheckBox(this);x.setText(text);x.setChecked(checked);x.setTextColor(palette().text);return x;}
    private TextView small(String text){TextView x=new TextView(this);x.setText(text);x.setTextSize(12);x.setTextColor(palette().sub);x.setPadding(dp060(2),dp060(3),dp060(2),dp060(3));return x;}
    private Spinner spinner(String[] items,String selected){Spinner s=new Spinner(this);ArrayAdapter<String>a=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,items);s.setAdapter(a);for(int i=0;i<items.length;i++)if(items[i].equals(selected)){s.setSelection(i);break;}return s;}

    private void injectSettings(LinearLayout c){
        if(hasTag(c,TAG_SETTINGS))return; SharedPreferences p=prefs060();
        LinearLayout box=panel(TAG_SETTINGS,tr("Appearance & Language"));
        box.addView(small(tr("Language")));
        String[] langs={"English","فارسی","Türkçe","Русский"}; Spinner lang=spinner(langs,langLabel(p.getString("language_v060","en"))); box.addView(lang);
        lang.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?>a,View v,int pos,long id){String code=new String[]{"en","fa","tr","ru"}[pos];String old=p.getString("language_v060","en");if(!code.equals(old)){p.edit().putString("language_v060",code).apply();recreate();}}public void onNothingSelected(AdapterView<?>a){}});
        box.addView(small(tr("Theme"))); String[] themes={"Dark","White","Blue","Red","Pink"}; Spinner theme=spinner(themes,p.getString("theme_v060","Dark")); box.addView(theme);
        theme.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?>a,View v,int pos,long id){String value=themes[pos];String old=p.getString("theme_v060","Dark");if(!value.equals(old)){p.edit().putString("theme_v060",value).putBoolean("dark_mode_v050","Dark".equals(value)).apply();recreate();}}public void onNothingSelected(AdapterView<?>a){}});
        CheckBox sampler=check(tr("Smart Scene Sampler"),p.getBoolean("smart_sampler_v060",true));
        CheckBox dense=check(tr("Dense identity-locked tracking"),p.getBoolean("dense_tracking_v060",true));
        CheckBox learning=check(tr("Learn from my actor corrections"),p.getBoolean("correction_learning_v060",true));
        CheckBox smartRescan=check(tr("Smart Re-Scan only changed/new videos"),p.getBoolean("smart_rescan_v060",true));
        CheckBox timeline=check(tr("Confidence Timeline"),p.getBoolean("confidence_timeline_v060",true));
        box.addView(sampler);box.addView(dense);box.addView(learning);box.addView(smartRescan);box.addView(timeline);
        View.OnClickListener save=v->p.edit().putBoolean("smart_sampler_v060",sampler.isChecked()).putBoolean("dense_tracking_v060",dense.isChecked()).putBoolean("correction_learning_v060",learning.isChecked()).putBoolean("smart_rescan_v060",smartRescan.isChecked()).putBoolean("confidence_timeline_v060",timeline.isChecked()).apply();
        sampler.setOnClickListener(save);dense.setOnClickListener(save);learning.setOnClickListener(save);smartRescan.setOnClickListener(save);timeline.setOnClickListener(save);
        box.addView(small(tr("Dense tracking interval"))); String[] steps={"80 ms","120 ms","180 ms","250 ms"}; Spinner st=spinner(steps,p.getInt("tracking_step_ms_v060",120)+" ms"); st.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?>a,View v,int pos,long id){p.edit().putInt("tracking_step_ms_v060",new int[]{80,120,180,250}[pos]).apply();}public void onNothingSelected(AdapterView<?>a){}});box.addView(st);
        c.addView(box,Math.min(2,c.getChildCount()));
    }

    private void injectGallery(LinearLayout c){
        if(hasTag(c,TAG_GALLERY))return; SharedPreferences p=prefs060(); ActorScanStore.Cluster actor=selectedActor();
        LinearLayout box=panel(TAG_GALLERY,tr("Actor Intelligence"));
        box.addView(small(tr("Correction learning")+": "+V060Learning.summary(this)));
        Button learned=new Button(this);learned.setText(tr("Learned Smart Select"));learned.setOnClickListener(v->{ActorScanStore.Cluster b=V060Learning.best(this,scanState());if(b!=null)invokeBase("selectCluster",new Class[]{int.class},new Object[]{b.id});});box.addView(learned);
        Button good=new Button(this);good.setText(tr("✓ Correct actor / learn this face"));good.setEnabled(actor!=null);good.setOnClickListener(v->{ActorScanStore.Cluster a=selectedActor();if(a!=null){V060Learning.markPositive(this,a);prefs060().edit().putFloat("tracking_identity_threshold_v060",0.47f).apply();Toast.makeText(this,tr("Actor correction learned"),Toast.LENGTH_SHORT).show();invokeBase("showGallery",new Class[]{},new Object[]{});}});box.addView(good);
        Button bad=new Button(this);bad.setText(tr("✕ Wrong person / reject this identity"));bad.setEnabled(actor!=null);bad.setOnClickListener(v->{ActorScanStore.Cluster a=selectedActor();if(a!=null){V060Learning.markNegative(this,a);prefs060().edit().putFloat("tracking_identity_threshold_v060",0.53f).apply();Toast.makeText(this,tr("Wrong identity saved"),Toast.LENGTH_SHORT).show();}});box.addView(bad);
        Button refs=new Button(this);refs.setText(tr("Auto-build best reference pack"));refs.setEnabled(actor!=null);refs.setOnClickListener(v->{ActorScanStore.Cluster a=selectedActor();if(a!=null){V060Learning.markPositive(this,a);prefs060().edit().putInt("v060_reference_actor",a.id).putInt("v060_reference_count",a.thumbs.size()).apply();Toast.makeText(this,tr("Reference pack ready")+" • "+a.thumbs.size(),Toast.LENGTH_LONG).show();}});box.addView(refs);
        if(actor!=null){float conf=V060Learning.learnedConfidence(this,actor);box.addView(small(String.format(Locale.US,"%s: %.0f%%",tr("Learned identity confidence"),conf*100f)));if(p.getBoolean("confidence_timeline_v060",true))box.addView(new ConfidenceTimeline(this,actor),new LinearLayout.LayoutParams(-1,dp060(92)));}
        c.addView(box,Math.min(2,c.getChildCount()));
    }

    private void injectOutput(LinearLayout c){
        if(hasTag(c,TAG_OUTPUT))return; SharedPreferences p=prefs060();
        LinearLayout box=panel(TAG_OUTPUT,tr("v0.6 Smart Director"));
        CheckBox dense=check(tr("Dense tracking before every export"),p.getBoolean("dense_tracking_v060",true));dense.setOnClickListener(v->p.edit().putBoolean("dense_tracking_v060",dense.isChecked()).apply());box.addView(dense);
        box.addView(small(tr("Smart composition"))); String[] comps={"Face center","Rule of thirds","Look room"}; Spinner comp=spinner(comps,p.getString("smart_composition_v060","Look room"));comp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?>a,View v,int pos,long id){p.edit().putString("smart_composition_v060",comps[pos]).apply();configureComposition();}public void onNothingSelected(AdapterView<?>a){}});box.addView(comp);
        box.addView(small(tr("Moment filter"))); String[] types={"All","Talking","Reaction","Action","Close-up"}; Spinner moment=spinner(types,p.getString("moment_filter_v060","All"));moment.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?>a,View v,int pos,long id){p.edit().putString("moment_filter_v060",types[pos]).apply();}public void onNothingSelected(AdapterView<?>a){}});box.addView(moment);
        CheckBox best=check(tr("Export only best-ranked clips"),p.getBoolean("best_only_v060",false));best.setOnClickListener(v->p.edit().putBoolean("best_only_v060",best.isChecked()).apply());box.addView(best);
        box.addView(small(tr("Best clip limit"))); String[] top={"10","20","30","50"};Spinner topN=spinner(top,String.valueOf(p.getInt("best_top_n_v060",20)));topN.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?>a,View v,int pos,long id){p.edit().putInt("best_top_n_v060",Integer.parseInt(top[pos])).apply();}public void onNothingSelected(AdapterView<?>a){}});box.addView(topN);
        ActorScanStore.Cluster actor=selectedActor(); if(actor!=null&&p.getBoolean("confidence_timeline_v060",true)){box.addView(small(tr("Actor confidence timeline")));box.addView(new ConfidenceTimeline(this,actor),new LinearLayout.LayoutParams(-1,dp060(92)));}
        c.addView(box,Math.min(2,c.getChildCount()));
    }

    private void configureComposition(){try{Method m=TrackingPanEffect.class.getDeclaredMethod("configureV060",String.class);m.setAccessible(true);m.invoke(null,prefs060().getString("smart_composition_v060","Look room"));}catch(Throwable ignored){}}
    private boolean hasTag(ViewGroup g,String tag){for(int i=0;i<g.getChildCount();i++)if(tag.equals(g.getChildAt(i).getTag()))return true;return false;}

    private void invokeBase(String name,Class<?>[] types,Object[] args){Class<?> c=getClass().getSuperclass();while(c!=null){try{Method m=c.getDeclaredMethod(name,types);m.setAccessible(true);m.invoke(this,args);return;}catch(NoSuchMethodException e){c=c.getSuperclass();}catch(Throwable e){return;}}}

    private String langLabel(String code){if("fa".equals(code))return"فارسی";if("tr".equals(code))return"Türkçe";if("ru".equals(code))return"Русский";return"English";}

    private void localize(TextView t){String s=String.valueOf(t.getText());String translated=translateKnown(s);if(!s.equals(translated))t.setText(translated);if("fa".equals(prefs060().getString("language_v060","en")))t.setGravity((t instanceof Button)?Gravity.CENTER:Gravity.RIGHT);}
    private String englishMeaning(String s){String[] keys={"Settings","Actor Gallery","Output"};for(String k:keys){if(s.contains(k))return s;}if(s.contains("تنظیمات"))return"Settings";if(s.contains("گالری بازیگر"))return"Actor Gallery";if(s.contains("خروجی"))return"Output";if(s.contains("Ayarlar"))return"Settings";if(s.contains("Oyuncu Galerisi"))return"Actor Gallery";if(s.contains("Çıktı"))return"Output";if(s.contains("Настройки"))return"Settings";if(s.contains("Галерея актёров"))return"Actor Gallery";if(s.contains("Экспорт"))return"Output";return s;}

    private String translateKnown(String s){
        if(s==null)return""; String l=prefs060().getString("language_v060","en"); if("en".equals(l))return s;
        LinkedHashMap<String,String[]> m=dict();
        for(Map.Entry<String,String[]>e:m.entrySet())if(s.equals(e.getKey()))return e.getValue()[langIndex(l)];
        if(s.startsWith("v0.5 •"))return"v0.6 • Modern AI Tracking • Smart Director • Multi-language • Live Progress";
        if(s.equals("App version: 0.5.0"))return"App version: 0.6.0";
        return s;
    }
    private int langIndex(String l){return"fa".equals(l)?0:"tr".equals(l)?1:2;}
    private LinkedHashMap<String,String[]> dict(){LinkedHashMap<String,String[]>m=new LinkedHashMap<>();
        m.put("Videos",new String[]{"ویدیوها","Videolar","Видео"});m.put("Scan Actors",new String[]{"اسکن بازیگران","Oyuncuları Tara","Скан актёров"});m.put("Actor Gallery",new String[]{"گالری بازیگر","Oyuncu Galerisi","Галерея актёров"});m.put("Output",new String[]{"خروجی","Çıktı","Экспорт"});m.put("System Info",new String[]{"اطلاعات سیستم","Sistem Bilgisi","Система"});m.put("Settings",new String[]{"تنظیمات","Ayarlar","Настройки"});m.put("Dashboard",new String[]{"داشبورد","Gösterge Paneli","Панель"});
        m.put("+ Add videos",new String[]{"+ افزودن ویدیو","+ Video Ekle","+ Добавить видео"});m.put("Choose output folder",new String[]{"انتخاب پوشه خروجی","Çıktı klasörü seç","Выбрать папку вывода"});m.put("Start / Resume Scan",new String[]{"شروع / ادامه اسکن","Taramayı Başlat / Sürdür","Начать / продолжить скан"});m.put("Refresh full scan",new String[]{"اسکن کامل مجدد","Tam taramayı yenile","Полное пересканирование"});m.put("Open Actor Gallery",new String[]{"باز کردن گالری بازیگر","Oyuncu Galerisini Aç","Открыть галерею актёров"});m.put("Smart Select main actor",new String[]{"انتخاب هوشمند بازیگر اصلی","Ana oyuncuyu akıllı seç","Умный выбор главного актёра"});m.put("Review detected clips before export",new String[]{"بررسی کلیپ‌ها قبل از خروجی","Dışa aktarmadan önce klipleri incele","Проверить клипы перед экспортом"});
        return m;}

    private String tr(String en){String l=prefs060().getString("language_v060","en");if("en".equals(l))return en;Map<String,String[]>m=extraDict();String[]x=m.get(en);return x==null?en:x[langIndex(l)];}
    private Map<String,String[]> extraDict(){HashMap<String,String[]>m=new HashMap<>();
        m.put("Appearance & Language",new String[]{"ظاهر و زبان","Görünüm ve Dil","Оформление и язык"});m.put("Language",new String[]{"زبان","Dil","Язык"});m.put("Theme",new String[]{"تم","Tema","Тема"});m.put("Smart Scene Sampler",new String[]{"نمونه‌برداری هوشمند صحنه","Akıllı Sahne Örnekleme","Умный анализ сцен"});m.put("Dense identity-locked tracking",new String[]{"ردیابی متراکم با قفل هویت","Kimlik kilitli yoğun takip","Плотный трекинг с фиксацией личности"});m.put("Learn from my actor corrections",new String[]{"یادگیری از اصلاحات من","Düzeltmelerimden öğren","Обучаться на моих исправлениях"});m.put("Smart Re-Scan only changed/new videos",new String[]{"اسکن مجدد فقط ویدیوهای جدید/تغییرکرده","Yalnız yeni/değişen videoları tara","Пересканировать только новые/изменённые"});m.put("Confidence Timeline",new String[]{"تایم‌لاین اطمینان","Güven Zaman Çizelgesi","Шкала уверенности"});m.put("Dense tracking interval",new String[]{"فاصله ردیابی دقیق","Yoğun takip aralığı","Интервал плотного трекинга"});m.put("Actor Intelligence",new String[]{"هوش بازیگر","Oyuncu Zekâsı","Интеллект актёра"});m.put("Correction learning",new String[]{"یادگیری اصلاحات","Düzeltme öğrenimi","Обучение исправлениям"});m.put("Learned Smart Select",new String[]{"انتخاب هوشمند یادگرفته‌شده","Öğrenilmiş Akıllı Seçim","Умный выбор с обучением"});m.put("✓ Correct actor / learn this face",new String[]{"✓ بازیگر درست / یادگیری این چهره","✓ Doğru oyuncu / bu yüzü öğren","✓ Верный актёр / запомнить лицо"});m.put("✕ Wrong person / reject this identity",new String[]{"✕ شخص اشتباه / رد این هویت","✕ Yanlış kişi / kimliği reddet","✕ Не тот человек / отклонить личность"});m.put("Auto-build best reference pack",new String[]{"ساخت خودکار بهترین رفرنس‌ها","En iyi referans paketini otomatik oluştur","Автосбор лучших референсов"});m.put("Actor correction learned",new String[]{"اصلاح بازیگر یاد گرفته شد","Oyuncu düzeltmesi öğrenildi","Исправление запомнено"});m.put("Wrong identity saved",new String[]{"هویت اشتباه ذخیره شد","Yanlış kimlik kaydedildi","Неверная личность сохранена"});m.put("Reference pack ready",new String[]{"پک رفرنس آماده شد","Referans paketi hazır","Набор референсов готов"});m.put("Learned identity confidence",new String[]{"اطمینان هویت یادگرفته‌شده","Öğrenilmiş kimlik güveni","Уверенность изученной личности"});m.put("v0.6 Smart Director",new String[]{"کارگردان هوشمند v0.6","v0.6 Akıllı Yönetmen","v0.6 Умный режиссёр"});m.put("Dense tracking before every export",new String[]{"ردیابی دقیق قبل از هر خروجی","Her dışa aktarmadan önce yoğun takip","Плотный трекинг перед каждым экспортом"});m.put("Smart composition",new String[]{"کادربندی هوشمند","Akıllı kompozisyon","Умная композиция"});m.put("Moment filter",new String[]{"فیلتر نوع لحظه","An filtresi","Фильтр моментов"});m.put("Export only best-ranked clips",new String[]{"فقط بهترین کلیپ‌ها خروجی شوند","Yalnız en iyi klipleri dışa aktar","Экспортировать только лучшие клипы"});m.put("Best clip limit",new String[]{"حداکثر کلیپ برتر","En iyi klip sınırı","Лимит лучших клипов"});m.put("Actor confidence timeline",new String[]{"تایم‌لاین اطمینان بازیگر","Oyuncu güven zaman çizelgesi","Шкала уверенности актёра"});m.put("Scanning actors",new String[]{"در حال اسکن بازیگران","Oyuncular taranıyor","Сканирование актёров"});m.put("Analyzing / Tracking / Exporting",new String[]{"تحلیل / ردیابی / خروجی","Analiz / Takip / Dışa Aktarma","Анализ / Трекинг / Экспорт"});m.put("Processing video",new String[]{"در حال پردازش ویدیو","Video işleniyor","Обработка видео"});m.put("Working",new String[]{"در حال کار","Çalışıyor","Выполняется"});m.put("Ready",new String[]{"آماده","Hazır","Готово"});m.put("Waiting for a job",new String[]{"منتظر شروع کار","İş bekleniyor","Ожидание задачи"});m.put("ETA",new String[]{"زمان باقی‌مانده","Kalan","Осталось"});return m;}

    private final class ConfidenceTimeline extends View {
        private final ActorScanStore.Cluster actor;
        ConfidenceTimeline(Context c,ActorScanStore.Cluster actor){super(c);this.actor=actor;setPadding(dp060(6),dp060(8),dp060(6),dp060(8));}
        @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);Palette p=palette();Paint bg=new Paint(1);bg.setColor(p.border);canvas.drawRoundRect(0,dp060(18),getWidth(),getHeight()-dp060(12),dp060(10),dp060(10),bg);if(actor==null||actor.hits.isEmpty())return;long min=Long.MAX_VALUE,max=Long.MIN_VALUE;for(ActorScanStore.Hit h:actor.hits){min=Math.min(min,h.t);max=Math.max(max,h.t);}long span=Math.max(1,max-min);Paint bar=new Paint(1);float base=getHeight()-dp060(14);for(ActorScanStore.Hit h:actor.hits){float x=(h.t-min)/(float)span*getWidth();float conf=Math.max(0f,Math.min(1f,h.score*0.55f+h.quality*0.45f));bar.setColor(conf>0.72f?p.accent:(conf>0.48f?0xffffb020:0xffef4444));canvas.drawRect(x,base-conf*(getHeight()-dp060(34)),Math.min(getWidth(),x+dp060(3)),base,bar);}Paint txt=new Paint(1);txt.setColor(p.text);txt.setTextSize(dp060(11));canvas.drawText(tr("Confidence Timeline"),dp060(7),dp060(13),txt);}
    }
}
