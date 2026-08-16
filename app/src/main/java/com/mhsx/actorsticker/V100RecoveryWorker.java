package com.mhsx.actorsticker;

import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.concurrent.TimeUnit;

/** WorkManager-backed recovery layer for queue state across process death / reboot. */
public final class V100RecoveryWorker extends Worker {
    static final String UNIQUE_NOW="actor_sticker_v100_recover_now";
    static final String UNIQUE_PERIODIC="actor_sticker_v100_recover_periodic";
    public V100RecoveryWorker(@NonNull Context c,@NonNull WorkerParameters p){super(c,p);}

    @NonNull @Override public Result doWork(){
        Context c=getApplicationContext();
        try{
            V080ExportQueue q=new V080ExportQueue(c);q.resetInterrupted();int stalled=q.recoverStalled(45000L);int pending=q.count(V080ExportQueue.PENDING);
            c.getSharedPreferences("actor_sticker",Context.MODE_PRIVATE).edit().putLong("v100_recovery_checked",System.currentTimeMillis()).putInt("v100_recovered_stalled",stalled).putBoolean("v100_recovery_pending",pending>0).apply();
            if(pending>0&&c.getSharedPreferences("actor_sticker",Context.MODE_PRIVATE).getBoolean("persistent_jobs_v100",true)){
                try{Intent i=new Intent(c,V080ExportService.class).setAction(V080ExportService.ACTION_START);if(Build.VERSION.SDK_INT>=26)c.startForegroundService(i);else c.startService(i);c.getSharedPreferences("actor_sticker",Context.MODE_PRIVATE).edit().putBoolean("v100_recovery_pending",false).apply();}
                catch(Throwable denied){c.getSharedPreferences("actor_sticker",Context.MODE_PRIVATE).edit().putString("v100_recovery_note","Queue preserved; Android deferred foreground restart: "+denied.getClass().getSimpleName()).apply();}
            }
            return Result.success();
        }catch(Throwable e){return getRunAttemptCount()<3?Result.retry():Result.failure();}
    }

    static void schedule(Context c){try{
        WorkManager wm=WorkManager.getInstance(c);
        wm.enqueueUniqueWork(UNIQUE_NOW, ExistingWorkPolicy.REPLACE,new OneTimeWorkRequest.Builder(V100RecoveryWorker.class).build());
        PeriodicWorkRequest p=new PeriodicWorkRequest.Builder(V100RecoveryWorker.class,15, TimeUnit.MINUTES).build();
        wm.enqueueUniquePeriodicWork(UNIQUE_PERIODIC,ExistingPeriodicWorkPolicy.UPDATE,p);
    }catch(Throwable ignored){}
    }
}
