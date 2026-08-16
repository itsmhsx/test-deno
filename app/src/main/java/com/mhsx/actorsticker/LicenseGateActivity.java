package com.mhsx.actorsticker;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.os.*;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.*;

import java.util.concurrent.*;

/** First screen for v1.1. No app UI is opened without a valid signed entitlement. */
public class LicenseGateActivity extends Activity {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private TextView status,device,trialInfo;
    private EditText key;
    private Button trial,activate;
    private volatile boolean dead;

    @Override public void onCreate(Bundle b){super.onCreate(b);getWindow().setStatusBarColor(0xff0b111b);getWindow().setNavigationBarColor(0xff0b111b);V110LicenseManager.Entitlement e=V110LicenseManager.local(this);if(e.valid){openApp();return;}build();if(V110LicenseManager.hasStoredLifetimeKey(this))validateStored();}
    @Override protected void onDestroy(){dead=true;worker.shutdownNow();super.onDestroy();}

    private int dp(int x){return Math.round(x*getResources().getDisplayMetrics().density);}
    private TextView text(String s,int sp,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextColor(Color.WHITE);t.setTextSize(sp);if(bold)t.setTypeface(null,1);t.setPadding(0,dp(5),0,dp(5));return t;}
    private Button button(String s){Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setMinHeight(dp(50));return b;}

    private void build(){
        ScrollView sc=new ScrollView(this);sc.setFillViewport(true);sc.setBackgroundColor(0xff0b111b);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(22),dp(28),dp(22),dp(28));sc.addView(root,new ScrollView.LayoutParams(-1,-2));
        TextView title=text("Actor Sticker Cutter",28,true);title.setGravity(Gravity.CENTER);root.addView(title);
        TextView ver=text("v1.1 Licensing Edition",14,false);ver.setTextColor(0xff94a3b8);ver.setGravity(Gravity.CENTER);root.addView(ver);
        root.addView(space(14));
        status=text("Choose Free Trial or activate a Lifetime license.",14,false);status.setTextColor(0xffcbd5e1);root.addView(status);
        device=text("Device: "+shortDevice(),12,false);device.setTextColor(0xff64748b);root.addView(device);

        LinearLayout free=card();free.addView(text("7-day Free Trial",19,true));trialInfo=text("Full app access for 7 days. The trial start is stored on the license server for this device.",13,false);trialInfo.setTextColor(0xffcbd5e1);free.addView(trialInfo);trial=button("Start 7-day Free Trial");trial.setOnClickListener(v->startTrial());free.addView(trial);root.addView(free);

        LinearLayout life=card();life.addView(text("Lifetime License",19,true));TextView note=text("Each serial activates on exactly one device. A second device is rejected unless the first device is deactivated/reset.",13,false);note.setTextColor(0xffcbd5e1);life.addView(note);key=new EditText(this);key.setHint("ASC-LIFE-XXXX-XXXX-XXXX-XXXX");key.setSingleLine(true);key.setTextColor(Color.WHITE);key.setHintTextColor(0xff64748b);key.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);key.setPadding(dp(12),dp(12),dp(12),dp(12));life.addView(key,new LinearLayout.LayoutParams(-1,-2));activate=button("Activate Lifetime License");activate.setOnClickListener(v->activate());life.addView(activate);root.addView(life);

        TextView privacy=text("License validation uses a one-way device hash. IMEI, phone number, contacts and media are not sent to the license server.",11,false);privacy.setTextColor(0xff64748b);root.addView(privacy);
        setContentView(sc);
    }

    private LinearLayout card(){LinearLayout x=new LinearLayout(this);x.setOrientation(LinearLayout.VERTICAL);x.setPadding(dp(16),dp(14),dp(16),dp(14));x.setBackgroundColor(0xff151e2d);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,dp(10),0,dp(10));x.setLayoutParams(lp);return x;}
    private View space(int h){Space s=new Space(this);s.setLayoutParams(new LinearLayout.LayoutParams(1,dp(h)));return s;}
    private String shortDevice(){String d=V110LicenseManager.deviceHash(this);return d.substring(0,Math.min(12,d.length())).toUpperCase();}

    private void busy(boolean on,String msg){runOnUiThread(()->{if(dead)return;if(status!=null)status.setText(msg);if(trial!=null)trial.setEnabled(!on);if(activate!=null)activate.setEnabled(!on);});}
    private void startTrial(){busy(true,"Contacting license server…");worker.execute(()->handle(V110LicenseManager.startTrial(this),false));}
    private void activate(){String s=key.getText().toString().trim();if(s.isEmpty()){status.setText("Enter your Lifetime serial first.");return;}busy(true,"Activating this device…");worker.execute(()->handle(V110LicenseManager.activate(this,s),true));}
    private void validateStored(){busy(true,"Validating saved Lifetime license…");worker.execute(()->handle(V110LicenseManager.validateStored(this),true));}
    private void handle(V110LicenseManager.Result r,boolean lifetime){runOnUiThread(()->{if(dead)return;if(r.ok()){status.setText(r.entitlement.label()+" • verified");Toast.makeText(this,lifetime?"Lifetime license active":"Free trial active",Toast.LENGTH_LONG).show();openApp();return;}busy(false,r.message);if("trial_expired".equals(r.status)&&trialInfo!=null)trialInfo.setText("Your 7-day trial has ended. Activate a Lifetime license to continue.");});}
    private void openApp(){if(dead)return;Intent i=new Intent(this,MainActivityV110.class);i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_NEW_TASK);startActivity(i);finish();}
    @Override public void onBackPressed(){finish();}
}
