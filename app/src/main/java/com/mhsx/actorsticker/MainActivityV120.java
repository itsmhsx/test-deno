package com.mhsx.actorsticker;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;

import androidx.media3.common.util.UnstableApi;

import java.lang.reflect.Field;
import java.util.Locale;

/** v1.2 Xiaomi / HyperOS optimized shell with private usage dashboard heartbeat. */
@UnstableApi
public class MainActivityV120 extends MainActivityV110 {
    private static final String TAB="v120_xiaomi_tab";
    private final Handler ui=new Handler(Looper.getMainLooper());
    private boolean dead;
    private final Runnable loop=new Runnable(){public void run(){if(dead)return;try{fixVersion(getWindow().getDecorView());inject(getWindow().getDecorView());}catch(Throwable ignored){}ui.postDelayed(this,900);}};

    @Override public void onCreate(Bundle b){super.onCreate(b);V120XiaomiTuner.applyAuto(this);V120UsageReporter.ping(this,true);ui.post(loop);if(V120XiaomiTuner.isXiaomi()&&!getSharedPreferences("actor_sticker",MODE_PRIVATE).getBoolean("xiaomi_notice_v120",false)){getSharedPreferences("actor_sticker",MODE_PRIVATE).edit().putBoolean("xiaomi_notice_v120",true).apply();Toast.makeText(this,"Xiaomi / HyperOS performance profile applied",Toast.LENGTH_LONG).show();}}
    @Override protected void onResume(){super.onResume();V120UsageReporter.ping(this,false);}
    @Override protected void onDestroy(){dead=true;ui.removeCallbacksAndMessages(null);super.onDestroy();}

    private int dp(int x){return Math.round(x*getResources().getDisplayMetrics().density);}
    private TextView tx(String s,int sp,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(sp);if(bold)t.setTypeface(null,1);t.setPadding(dp(5),dp(6),dp(5),dp(6));return t;}
    private TextView note(String s){TextView t=tx(s,12,false);t.setAlpha(.74f);return t;}
    private Button bt(String s){Button b=new Button(this);b.setText(s);b.setAllCaps(false);return b;}
    private LinearLayout card(String title){LinearLayout x=new LinearLayout(this);x.setOrientation(LinearLayout.VERTICAL);x.setPadding(dp(12),dp(10),dp(12),dp(10));x.addView(tx(title,17,true));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,dp(6),0,dp(6));x.setLayoutParams(lp);return x;}
    private LinearLayout content(){try{Field f=MainActivity.class.getDeclaredField("content");f.setAccessible(true);return(LinearLayout)f.get(this);}catch(Throwable e){return null;}}

    private void fixVersion(View v){if(v==null)return;if(v instanceof TextView){TextView t=(TextView)v;String s=String.valueOf(t.getText());if(s.contains("v1.1.0"))t.setText(s.replace("v1.1.0","v1.2.0"));else if(s.contains("v1.1")&&!s.contains("v1.2"))t.setText(s.replace("v1.1","v1.2"));}if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++)fixVersion(g.getChildAt(i));}}
    private void inject(View v){if(!(v instanceof ViewGroup))return;ViewGroup g=(ViewGroup)v;if(g instanceof LinearLayout&&g.getParent() instanceof HorizontalScrollView){boolean base=false;for(int i=0;i<g.getChildCount();i++)if(g.getChildAt(i) instanceof Button){String s=((Button)g.getChildAt(i)).getText().toString().toLowerCase(Locale.ROOT);if(s.contains("videos")||s.contains("ویدیو")||s.contains("video")){base=true;break;}}if(base){for(int i=0;i<g.getChildCount();i++)if(TAB.equals(g.getChildAt(i).getTag()))return;Button b=bt(V120XiaomiTuner.isXiaomi()?"Xiaomi / HyperOS":"Device Performance");b.setTag(TAB);b.setOnClickListener(x->showXiaomi());g.addView(b);return;}}for(int i=0;i<g.getChildCount();i++)inject(g.getChildAt(i));}

    private void showXiaomi(){LinearLayout c=content();if(c==null)return;c.removeAllViews();c.addView(tx("Actor Sticker Cutter • v1.2 Xiaomi / HyperOS",22,true));c.addView(note(V120XiaomiTuner.summary(this)));
        LinearLayout status=card("Background reliability");status.addView(note("Scan and Export continue to use foreground media-processing services. Xiaomi / HyperOS can still restrict background work if Battery is set to Restricted. The buttons below open the relevant system pages; no hidden permission is forced."));status.addView(tx("Battery unrestricted: "+(V120XiaomiTuner.batteryUnrestricted(this)?"YES":"NO / optimized"),14,true));status.addView(tx("Notifications allowed: "+(V120XiaomiTuner.notificationsAllowed(this)?"YES":"NO"),14,true));Button auto=bt("Open Xiaomi Auto-start settings");auto.setOnClickListener(v->V120XiaomiTuner.openAutostart(this));status.addView(auto);Button bat=bt("Open Battery / No restrictions settings");bat.setOnClickListener(v->V120XiaomiTuner.openBattery(this));status.addView(bat);Button no=bt("Enable foreground notifications");no.setOnClickListener(v->V120XiaomiTuner.requestNotifications(this));status.addView(no);Button app=bt("Open App details");app.setOnClickListener(v->V120XiaomiTuner.openAppDetails(this));status.addView(app);c.addView(status);

        LinearLayout perf=card("Xiaomi performance presets");perf.addView(note("Auto Xiaomi chooses scan density, face threads, prefetch and motion sampling from available RAM. Thermal Adaptive + Memory Guard stay enabled. Face Follow remains opt-in and is not enabled by these presets."));Button a=bt("Apply Auto Xiaomi profile");a.setOnClickListener(v->{String x=V120XiaomiTuner.applyPreset(this,"Auto Xiaomi");Toast.makeText(this,x,Toast.LENGTH_LONG).show();showXiaomi();});perf.addView(a);Button cool=bt("Stable / Low Heat");cool.setOnClickListener(v->{V120XiaomiTuner.applyPreset(this,"Stable / Low Heat");showXiaomi();});perf.addView(cool);Button fast=bt("Fast");fast.setOnClickListener(v->{V120XiaomiTuner.applyPreset(this,"Fast");showXiaomi();});perf.addView(fast);c.addView(perf);

        LinearLayout diag=card("Live device diagnostics");diag.addView(tx("RAM: "+V120XiaomiTuner.totalRamMb(this)+" MB",13,false));diag.addView(tx("Thermal: "+V120XiaomiTuner.thermal(this),13,false));diag.addView(tx("OS: "+V120XiaomiTuner.osFlavor(),13,false));String hb=getSharedPreferences("actor_sticker",MODE_PRIVATE).getString("usage_heartbeat_error_v120","");diag.addView(tx("Admin heartbeat: "+(hb.isEmpty()?"OK / queued":"Last error: "+hb),13,false));Button refresh=bt("Refresh diagnostics");refresh.setOnClickListener(v->{V120UsageReporter.ping(this,true);showXiaomi();});diag.addView(refresh);c.addView(diag);

        LinearLayout privacy=card("Private Admin Dashboard telemetry");privacy.addView(note("The dashboard receives only the one-way device hash, app version, manufacturer/brand, model, Android API level, license type and last-use timestamps. It does not upload photos, videos, contacts, phone number or IMEI."));c.addView(privacy);
    }
}
