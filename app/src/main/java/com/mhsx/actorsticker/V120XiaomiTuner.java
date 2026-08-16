package com.mhsx.actorsticker;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

import java.lang.reflect.Method;
import java.util.Locale;

/** Device-aware tuning and settings helpers for Xiaomi / Redmi / POCO / HyperOS. */
final class V120XiaomiTuner {
    private V120XiaomiTuner(){}
    static boolean isXiaomi(){String x=((Build.MANUFACTURER==null?"":Build.MANUFACTURER)+" "+(Build.BRAND==null?"":Build.BRAND)).toLowerCase(Locale.ROOT);return x.contains("xiaomi")||x.contains("redmi")||x.contains("poco");}
    static long totalRamMb(Context c){try{ActivityManager am=(ActivityManager)c.getSystemService(Context.ACTIVITY_SERVICE);ActivityManager.MemoryInfo mi=new ActivityManager.MemoryInfo();am.getMemoryInfo(mi);return mi.totalMem/(1024L*1024L);}catch(Throwable e){return 0;}}
    static String osFlavor(){String hyper=prop("ro.mi.os.version.name");String miui=prop("ro.miui.ui.version.name");if(!hyper.isEmpty())return "HyperOS "+hyper;if(!miui.isEmpty())return "MIUI "+miui;return isXiaomi()?"Xiaomi Android":"Android";}
    private static String prop(String k){try{Class<?> c=Class.forName("android.os.SystemProperties");Method m=c.getDeclaredMethod("get",String.class);m.setAccessible(true);Object v=m.invoke(null,k);return v==null?"":String.valueOf(v);}catch(Throwable e){return"";}}

    static void applyAuto(Context c){SharedPreferences p=c.getSharedPreferences("actor_sticker",Context.MODE_PRIVATE);if(!isXiaomi()||p.getBoolean("xiaomi_auto_profile_done_v120",false))return;applyPreset(c,"Auto Xiaomi");p.edit().putBoolean("xiaomi_auto_profile_done_v120",true).apply();}
    static String applyPreset(Context c,String preset){SharedPreferences p=c.getSharedPreferences("actor_sticker",Context.MODE_PRIVATE);long ram=totalRamMb(c);String mode=preset;
        SharedPreferences.Editor e=p.edit().putBoolean("memory_guard_v090",true).putBoolean("thermal_adaptive_v090",true).putString("xiaomi_profile_v120",preset);
        if("Stable / Low Heat".equals(preset)){e.putString("speed_mode_v070","Quick").putBoolean("parallel_prefetch_v070",false).putInt("face_threads_v090",2).putInt("flow_step_ms_v100",240).putString("video_codec_v050","H.264/AVC");}
        else if("Fast".equals(preset)){e.putString("speed_mode_v070","Balanced").putBoolean("parallel_prefetch_v070",true).putInt("face_threads_v090",Math.max(2,Math.min(4,Runtime.getRuntime().availableProcessors()/2))).putInt("flow_step_ms_v100",160);}
        else {if(ram>0&&ram<=4600){mode="Auto Xiaomi • Low RAM";e.putString("speed_mode_v070","Quick").putBoolean("parallel_prefetch_v070",false).putInt("face_threads_v090",2).putInt("flow_step_ms_v100",230).putString("video_codec_v050","H.264/AVC");}else if(ram>0&&ram<=8500){mode="Auto Xiaomi • Balanced";e.putString("speed_mode_v070","Balanced").putBoolean("parallel_prefetch_v070",true).putInt("face_threads_v090",3).putInt("flow_step_ms_v100",190);}else{mode="Auto Xiaomi • Performance";e.putString("speed_mode_v070","Balanced").putBoolean("parallel_prefetch_v070",true).putInt("face_threads_v090",4).putInt("flow_step_ms_v100",170);}}
        e.putString("xiaomi_profile_effective_v120",mode).apply();V080FaceEmbedder.configureThreads(p.getInt("face_threads_v090",2));return mode;
    }

    static boolean batteryUnrestricted(Context c){try{PowerManager pm=(PowerManager)c.getSystemService(Context.POWER_SERVICE);return pm.isIgnoringBatteryOptimizations(c.getPackageName());}catch(Throwable e){return false;}}
    static String thermal(Context c){if(Build.VERSION.SDK_INT<29)return"n/a";try{PowerManager pm=(PowerManager)c.getSystemService(Context.POWER_SERVICE);int s=pm.getCurrentThermalStatus();switch(s){case PowerManager.THERMAL_STATUS_NONE:return"None";case PowerManager.THERMAL_STATUS_LIGHT:return"Light";case PowerManager.THERMAL_STATUS_MODERATE:return"Moderate";case PowerManager.THERMAL_STATUS_SEVERE:return"Severe";case PowerManager.THERMAL_STATUS_CRITICAL:return"Critical";case PowerManager.THERMAL_STATUS_EMERGENCY:return"Emergency";case PowerManager.THERMAL_STATUS_SHUTDOWN:return"Shutdown";default:return String.valueOf(s);}}catch(Throwable e){return"unknown";}}
    static boolean notificationsAllowed(Context c){if(Build.VERSION.SDK_INT<33)return true;return c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED;}
    static void requestNotifications(Activity a){if(Build.VERSION.SDK_INT>=33&&a.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)a.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},1201);else openNotificationSettings(a);}

    static void openAutostart(Activity a){Intent[] tries=new Intent[]{component("com.miui.securitycenter","com.miui.permcenter.autostart.AutoStartManagementActivity"),component("com.miui.securitycenter","com.miui.permcenter.permissions.PermissionsEditorActivity")};for(Intent i:tries)if(launch(a,i))return;openAppDetails(a);}
    static void openBattery(Activity a){try{Intent i=component("com.miui.powerkeeper","com.miui.powerkeeper.ui.HiddenAppsConfigActivity");i.putExtra("package_name",a.getPackageName());i.putExtra("package_label","Actor Sticker Cutter");if(launch(a,i))return;}catch(Throwable ignored){}try{a.startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));}catch(Throwable e){openAppDetails(a);}}
    static void openNotificationSettings(Activity a){try{Intent i=new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);i.putExtra(Settings.EXTRA_APP_PACKAGE,a.getPackageName());a.startActivity(i);}catch(Throwable e){openAppDetails(a);}}
    static void openAppDetails(Activity a){try{a.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:"+a.getPackageName())));}catch(Throwable ignored){}}
    private static Intent component(String pkg,String cls){Intent i=new Intent();i.setComponent(new ComponentName(pkg,cls));i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);return i;}
    private static boolean launch(Activity a,Intent i){try{if(i.resolveActivity(a.getPackageManager())!=null){a.startActivity(i);return true;}}catch(Throwable ignored){}return false;}

    static String summary(Context c){SharedPreferences p=c.getSharedPreferences("actor_sticker",Context.MODE_PRIVATE);return Build.MANUFACTURER+" "+Build.MODEL+" • "+osFlavor()+" • Android "+Build.VERSION.RELEASE+" / API "+Build.VERSION.SDK_INT+" • RAM "+totalRamMb(c)+" MB • Thermal "+thermal(c)+" • Profile "+p.getString("xiaomi_profile_effective_v120",p.getString("xiaomi_profile_v120","Not applied"));}
}
