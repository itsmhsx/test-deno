package com.mhsx.actorsticker;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;

import androidx.media3.common.util.UnstableApi;

import java.lang.reflect.Field;
import java.util.Locale;
import java.util.concurrent.*;

/** v1.1 app shell. Face Follow defaults from v1.0 remain unchanged (strictly OFF until user enables it). */
@UnstableApi
public class MainActivityV110 extends MainActivityV100 {
    private static final String TAB="v110_license_tab";
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private boolean dead;
    private final Runnable loop=new Runnable(){public void run(){if(dead)return;try{fixVersion(getWindow().getDecorView());inject(getWindow().getDecorView());}catch(Throwable ignored){}ui.postDelayed(this,750);}};

    @Override public void onCreate(Bundle b){super.onCreate(b);ui.post(loop);}
    @Override protected void onResume(){super.onResume();V110LicenseManager.Entitlement e=V110LicenseManager.local(this);if(!e.valid){startActivity(new Intent(this,LicenseGateActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));finish();}}
    @Override protected void onDestroy(){dead=true;ui.removeCallbacksAndMessages(null);worker.shutdownNow();super.onDestroy();}

    private int dp(int x){return Math.round(x*getResources().getDisplayMetrics().density);}
    private TextView t(String s,int sp,boolean bold){TextView x=new TextView(this);x.setText(s);x.setTextSize(sp);if(bold)x.setTypeface(null,1);x.setPadding(dp(5),dp(5),dp(5),dp(5));return x;}
    private Button b(String s){Button x=new Button(this);x.setText(s);x.setAllCaps(false);return x;}
    private LinearLayout content(){try{Field f=MainActivity.class.getDeclaredField("content");f.setAccessible(true);return(LinearLayout)f.get(this);}catch(Throwable e){return null;}}

    private void fixVersion(View v){if(v==null)return;if(v instanceof TextView){TextView x=(TextView)v;String s=String.valueOf(x.getText());if(s.contains("v1.0.0"))x.setText(s.replace("v1.0.0","v1.1.0"));else if(s.contains("v1.0")&&!s.contains("v1.1"))x.setText(s.replace("v1.0","v1.1"));}if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++)fixVersion(g.getChildAt(i));}}
    private void inject(View v){if(!(v instanceof ViewGroup))return;ViewGroup g=(ViewGroup)v;if(g instanceof LinearLayout&&g.getParent() instanceof HorizontalScrollView){boolean base=false;for(int i=0;i<g.getChildCount();i++)if(g.getChildAt(i) instanceof Button){String s=((Button)g.getChildAt(i)).getText().toString().toLowerCase(Locale.ROOT);if(s.contains("videos")||s.contains("ویدیو")||s.contains("video")){base=true;break;}}if(base){for(int i=0;i<g.getChildCount();i++)if(TAB.equals(g.getChildAt(i).getTag()))return;Button x=b("License");x.setTag(TAB);x.setOnClickListener(z->showLicense());g.addView(x);return;}}for(int i=0;i<g.getChildCount();i++)inject(g.getChildAt(i));}

    private void showLicense(){LinearLayout c=content();if(c==null)return;c.removeAllViews();V110LicenseManager.Entitlement e=V110LicenseManager.local(this);c.addView(t("License • v1.1",22,true));c.addView(t(e.label(),17,true));c.addView(t("Device: "+V110LicenseManager.deviceHash(this).substring(0,12).toUpperCase(Locale.ROOT),12,false));if(e.valid){c.addView(t("Signed entitlement valid until: "+V110LicenseManager.when(e.validUntil),12,false));if("trial".equals(e.kind)){c.addView(t("Trial ends: "+V110LicenseManager.when(e.trialEndsAt),12,false));c.addView(t("Remaining: "+V110LicenseManager.remaining(e.remaining()),12,false));}else c.addView(t("Offline grace remaining: "+V110LicenseManager.remaining(e.remaining()),12,false));}
        if("lifetime".equals(e.kind)){Button validate=b("Validate Lifetime now");validate.setOnClickListener(v->{validate.setEnabled(false);worker.execute(()->{V110LicenseManager.Result r=V110LicenseManager.validateStored(this);runOnUiThread(()->{Toast.makeText(this,r.ok()?"License renewed":r.message,Toast.LENGTH_LONG).show();showLicense();});});});c.addView(validate);Button release=b("Deactivate / release this device");release.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("Release this device?").setMessage("This frees the Lifetime serial so it can be activated on another device.").setNegativeButton("Cancel",null).setPositiveButton("Deactivate",(d,w)->worker.execute(()->{V110LicenseManager.Result r=V110LicenseManager.deactivate(this);runOnUiThread(()->{Toast.makeText(this,r.message,Toast.LENGTH_LONG).show();if("deactivated".equals(r.status)||"already_unbound".equals(r.status)){startActivity(new Intent(this,LicenseGateActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));finish();}else showLicense();});})).show());c.addView(release);}else{c.addView(t("Upgrade: enter one of your Lifetime serials from the activation screen after the trial expires, or clear the current trial token only by waiting for the server-defined end date.",12,false));}
        Button copy=b("Copy full device fingerprint");copy.setOnClickListener(v->{((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Actor Sticker Device",V110LicenseManager.deviceHash(this)));Toast.makeText(this,"Device fingerprint copied",Toast.LENGTH_SHORT).show();});c.addView(copy);
    }
}
