package com.mhsx.actorsticker;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONObject;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.CertificateFactory;
import java.security.spec.X509EncodedKeySpec;
import java.text.SimpleDateFormat;
import java.util.*;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * v1.1 licensing client.
 * - Server owns the 100 lifetime serial hashes; raw serials are never bundled in the APK.
 * - First activation binds a serial to one device hash.
 * - Server responses are RSA/SHA-256 signed. The APK contains only the public key.
 * - Lifetime licenses can work offline for up to seven days between validations.
 * - The free trial is seven days from the first server-recorded start for this device hash.
 */
final class V110LicenseManager {
    static final String API = "https://noweucaayalylobeklzc.supabase.co/functions/v1/actor-license";
    private static final String PUBLIC_KEY_B64 = "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA7jOkxnY9DuBdTr+lUfMAVZDWI23/vMRVm2amACdNKqfTTYLNgUK1307wbceosjDPAMj8zOT3NwmEPsNKq9JChxME2Tne6Go4btB5ACC+Qbtx7TkuBKqGhOTvB/QYzwdNDtxUR7mWeWK5BKiNGHHk87SAiKICSzc7lD53WqLOUw+tadY+SCbC6MpkplpwDiLU777TJOxPOpcPW9fuzkl84MpTe5ySLnTZEupKPCR1fhbZ4bVEGtRDB6fqLrMLUUdgdlhcj/unPRREno9k4268PO/k8wrb6nG0UXYoK0nriOVEklYZOt2lX+RayM43lmb6F/xoFIPCIzq9iU3DUX2x3wIDAQAB";
    private static final String PREF = "asc_license_v110";
    private static final String AES_ALIAS = "asc_license_serial_v110";
    private static final long CLOCK_SKEW = 10L * 60L * 1000L;

    static final class Entitlement {
        final boolean valid; final String kind, plan, reason; final long validUntil, trialEndsAt;
        Entitlement(boolean valid,String kind,String plan,long validUntil,long trialEndsAt,String reason){this.valid=valid;this.kind=kind;this.plan=plan;this.validUntil=validUntil;this.trialEndsAt=trialEndsAt;this.reason=reason;}
        static Entitlement invalid(String reason){return new Entitlement(false,"none","none",0,0,reason);}
        String label(){if(!valid)return "Inactive";if("lifetime".equals(kind))return "Lifetime • 1 device";return "Free trial • 7 days";}
        long remaining(){long end="trial".equals(kind)?trialEndsAt:validUntil;return Math.max(0,end-System.currentTimeMillis());}
    }

    static final class Result {
        final String status, message; final Entitlement entitlement;
        Result(String status,String message,Entitlement entitlement){this.status=status;this.message=message;this.entitlement=entitlement;}
        boolean ok(){return entitlement!=null&&entitlement.valid;}
    }

    static String deviceHash(Context c){
        try{
            String id=Settings.Secure.getString(c.getContentResolver(),Settings.Secure.ANDROID_ID);
            if(id==null||id.trim().isEmpty())id="unknown";
            return hex(MessageDigest.getInstance("SHA-256").digest(("ASC110|"+c.getPackageName()+"|"+id).getBytes(StandardCharsets.UTF_8)));
        }catch(Throwable e){return "0".repeat(64);}
    }

    static Entitlement local(Context c){
        SharedPreferences p=c.getSharedPreferences(PREF,Context.MODE_PRIVATE);
        String token=p.getString("token","");
        if(token.isEmpty())return Entitlement.invalid("no_token");
        try{
            String[] parts=token.split("\\."); if(parts.length!=2)return Entitlement.invalid("bad_token");
            byte[] body=Base64.decode(parts[0],Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING);
            byte[] sig=Base64.decode(parts[1],Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING);
            Signature verifier=Signature.getInstance("SHA256withRSA");verifier.initVerify(publicKey());verifier.update(parts[0].getBytes(StandardCharsets.US_ASCII));
            if(!verifier.verify(sig))return Entitlement.invalid("signature");
            JSONObject o=new JSONObject(new String(body,StandardCharsets.UTF_8));
            if(!deviceHash(c).equals(o.optString("device","")))return Entitlement.invalid("device");
            long until=o.optLong("validUntil",0),trial=o.optLong("trialEndsAt",0),now=System.currentTimeMillis();
            long last=p.getLong("last_seen_wall",0);if(last>0&&now+CLOCK_SKEW<last)return Entitlement.invalid("clock_rollback");
            p.edit().putLong("last_seen_wall",Math.max(now,last)).apply();
            if(until<=now)return Entitlement.invalid("expired");
            String kind=o.optString("kind","none"),plan=o.optString("plan",kind);
            if("trial".equals(kind)&&trial>0&&trial<=now)return Entitlement.invalid("trial_expired");
            return new Entitlement(true,kind,plan,until,trial,"ok");
        }catch(Throwable e){return Entitlement.invalid("parse");}
    }

    static Result startTrial(Context c){return call(c,"trial",null,false);}
    static Result activate(Context c,String key){return call(c,"activate",normalize(key),true);}
    static Result validateStored(Context c){String key=loadSerial(c);if(key==null||key.isEmpty())return new Result("no_key","No lifetime key stored",Entitlement.invalid("no_key"));return call(c,"validate",key,true);}
    static Result deactivate(Context c){String key=loadSerial(c);if(key==null||key.isEmpty())return new Result("no_key","No lifetime key stored",Entitlement.invalid("no_key"));Result r=call(c,"deactivate",key,false);if("deactivated".equals(r.status)||"already_unbound".equals(r.status)){clear(c);return new Result(r.status,"Device released",Entitlement.invalid("deactivated"));}return r;}
    static boolean hasStoredLifetimeKey(Context c){String x=loadSerial(c);return x!=null&&!x.isEmpty();}

    private static Result call(Context c,String action,String key,boolean saveKeyOnActive){
        HttpURLConnection conn=null;
        try{
            JSONObject req=new JSONObject();req.put("action",action);req.put("device",deviceHash(c));req.put("appVersion","1.1.0");if(key!=null)req.put("key",key);
            conn=(HttpURLConnection)new URL(API).openConnection();conn.setRequestMethod("POST");conn.setConnectTimeout(12000);conn.setReadTimeout(15000);conn.setDoOutput(true);conn.setRequestProperty("Content-Type","application/json; charset=utf-8");conn.setRequestProperty("Accept","application/json");conn.setUseCaches(false);
            byte[] raw=req.toString().getBytes(StandardCharsets.UTF_8);conn.setFixedLengthStreamingMode(raw.length);try(OutputStream out=conn.getOutputStream()){out.write(raw);}
            int code=conn.getResponseCode();InputStream in=code>=200&&code<400?conn.getInputStream():conn.getErrorStream();String text=read(in);JSONObject res=new JSONObject(text.isEmpty()?"{}":text);String status=res.optString("status",code>=500?"server_error":"invalid");
            String token=res.optString("token","");
            if(("active".equals(status)||"trial".equals(status))&&!token.isEmpty()){
                saveToken(c,token,res.optLong("serverTime",System.currentTimeMillis()));
                Entitlement e=local(c);if(!e.valid){clearTokenOnly(c);return new Result("bad_signature","Server token verification failed",e);}
                if(saveKeyOnActive&&key!=null&&"active".equals(status))saveSerial(c,key);
                return new Result(status,e.label(),e);
            }
            if("device_mismatch".equals(status))return new Result(status,"This lifetime license is already active on another device.",Entitlement.invalid(status));
            if("trial_expired".equals(status))return new Result(status,"The 7-day free trial has expired. Enter a lifetime license.",Entitlement.invalid(status));
            if("disabled".equals(status))return new Result(status,"This license has been disabled.",Entitlement.invalid(status));
            if("invalid".equals(status))return new Result(status,"Invalid lifetime license key.",Entitlement.invalid(status));
            if("deactivated".equals(status)||"already_unbound".equals(status))return new Result(status,"Device released.",Entitlement.invalid(status));
            return new Result(status,"License server response: "+status,Entitlement.invalid(status));
        }catch(Throwable e){return new Result("network_error","Cannot reach license server. Check your internet connection.",local(c));}
        finally{if(conn!=null)conn.disconnect();}
    }

    static void clear(Context c){clearTokenOnly(c);c.getSharedPreferences(PREF,Context.MODE_PRIVATE).edit().remove("serial_enc").apply();}
    private static void clearTokenOnly(Context c){c.getSharedPreferences(PREF,Context.MODE_PRIVATE).edit().remove("token").remove("server_time").apply();}
    private static void saveToken(Context c,String token,long serverTime){c.getSharedPreferences(PREF,Context.MODE_PRIVATE).edit().putString("token",token).putLong("server_time",serverTime).putLong("last_seen_wall",System.currentTimeMillis()).apply();}

    private static PublicKey publicKey()throws Exception{byte[] der=Base64.decode(PUBLIC_KEY_B64,Base64.DEFAULT);return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));}
    private static String normalize(String s){return s==null?"":s.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+","");}
    private static String hex(byte[] a){StringBuilder b=new StringBuilder(a.length*2);for(byte x:a)b.append(String.format(Locale.US,"%02x",x&255));return b.toString();}
    private static String read(InputStream in)throws Exception{if(in==null)return"";try(BufferedReader r=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8))){StringBuilder b=new StringBuilder();String line;while((line=r.readLine())!=null)b.append(line);return b.toString();}}

    private static SecretKey aesKey()throws Exception{
        KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);Key k=ks.getKey(AES_ALIAS,null);if(k instanceof SecretKey)return(SecretKey)k;
        KeyGenerator g=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");g.init(new KeyGenParameterSpec.Builder(AES_ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build());return g.generateKey();
    }
    private static void saveSerial(Context c,String serial){try{Cipher ci=Cipher.getInstance("AES/GCM/NoPadding");ci.init(Cipher.ENCRYPT_MODE,aesKey());byte[] iv=ci.getIV(),ct=ci.doFinal(serial.getBytes(StandardCharsets.UTF_8));String enc=Base64.encodeToString(iv,Base64.NO_WRAP)+"."+Base64.encodeToString(ct,Base64.NO_WRAP);c.getSharedPreferences(PREF,Context.MODE_PRIVATE).edit().putString("serial_enc",enc).apply();}catch(Throwable ignored){}}
    private static String loadSerial(Context c){try{String enc=c.getSharedPreferences(PREF,Context.MODE_PRIVATE).getString("serial_enc","");if(enc.isEmpty())return null;String[]p=enc.split("\\.");if(p.length!=2)return null;byte[]iv=Base64.decode(p[0],Base64.DEFAULT),ct=Base64.decode(p[1],Base64.DEFAULT);Cipher ci=Cipher.getInstance("AES/GCM/NoPadding");ci.init(Cipher.DECRYPT_MODE,aesKey(),new GCMParameterSpec(128,iv));return new String(ci.doFinal(ct),StandardCharsets.UTF_8);}catch(Throwable e){return null;}}

    static String when(long ms){if(ms<=0)return"—";return new SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.US).format(new Date(ms));}
    static String remaining(long ms){long s=Math.max(0,ms/1000),d=s/86400,h=(s%86400)/3600,m=(s%3600)/60;return d>0?d+"d "+h+"h":h+"h "+m+"m";}
}
