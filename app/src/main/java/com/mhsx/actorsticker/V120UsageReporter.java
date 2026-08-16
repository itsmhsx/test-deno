package com.mhsx.actorsticker;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** v1.2 privacy-minimal usage heartbeat for the private admin dashboard. */
final class V120UsageReporter {
    private static final String API="https://noweucaayalylobeklzc.supabase.co/functions/v1/actor-heartbeat";
    private static final String PREF="actor_sticker";
    private static final long MIN_GAP_MS=5L*60L*1000L;
    private static final ExecutorService IO=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"asc-usage-v120");t.setDaemon(true);return t;});
    private V120UsageReporter(){}

    static void ping(Context context, boolean force){
        Context c=context.getApplicationContext();
        V110LicenseManager.Entitlement e=V110LicenseManager.local(c);
        if(!e.valid)return;
        String token=V110LicenseManager.signedToken(c);
        if(token==null||token.isEmpty())return;
        SharedPreferences p=c.getSharedPreferences(PREF,Context.MODE_PRIVATE);
        long now=System.currentTimeMillis(),last=p.getLong("usage_heartbeat_v120",0);
        if(!force && now-last<MIN_GAP_MS)return;
        p.edit().putLong("usage_heartbeat_v120",now).apply();
        IO.execute(()->send(c,token));
    }

    private static void send(Context c,String token){
        HttpURLConnection conn=null;
        try{
            JSONObject j=new JSONObject();
            j.put("device",V110LicenseManager.deviceHash(c));
            j.put("token",token);
            j.put("appVersion","1.2.0");
            String brand=(Build.MANUFACTURER==null?"":Build.MANUFACTURER)+"/"+(Build.BRAND==null?"":Build.BRAND);
            j.put("brand",brand);
            j.put("model",Build.MODEL==null?"":Build.MODEL);
            j.put("sdk",Build.VERSION.SDK_INT);
            byte[] raw=j.toString().getBytes(StandardCharsets.UTF_8);
            conn=(HttpURLConnection)new URL(API).openConnection();
            conn.setRequestMethod("POST");conn.setConnectTimeout(8000);conn.setReadTimeout(8000);conn.setDoOutput(true);conn.setUseCaches(false);
            conn.setRequestProperty("Content-Type","application/json; charset=utf-8");conn.setFixedLengthStreamingMode(raw.length);
            try(OutputStream o=conn.getOutputStream()){o.write(raw);}int code=conn.getResponseCode();
            InputStream in=code>=200&&code<400?conn.getInputStream():conn.getErrorStream();if(in!=null)try{while(in.read()!=-1){}in.close();}catch(Throwable ignored){}
            if(code<200||code>=300)c.getSharedPreferences(PREF,Context.MODE_PRIVATE).edit().putString("usage_heartbeat_error_v120","HTTP "+code).apply();
            else c.getSharedPreferences(PREF,Context.MODE_PRIVATE).edit().remove("usage_heartbeat_error_v120").apply();
        }catch(Throwable x){c.getSharedPreferences(PREF,Context.MODE_PRIVATE).edit().putString("usage_heartbeat_error_v120",x.getClass().getSimpleName()).apply();}
        finally{if(conn!=null)conn.disconnect();}
    }
}
