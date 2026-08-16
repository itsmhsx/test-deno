package com.mhsx.actorsticker;

import androidx.room.*;
import java.util.*;

@Dao
abstract class V090JobDao {
    @Query("SELECT * FROM render_jobs ORDER BY createdAt ASC, id ASC") abstract List<V090JobEntity> all();
    @Query("SELECT * FROM render_jobs WHERE state=:state ORDER BY createdAt ASC, id ASC LIMIT 1") abstract V090JobEntity firstByState(String state);
    @Query("SELECT * FROM render_jobs WHERE id=:id LIMIT 1") abstract V090JobEntity byId(String id);
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract void upsert(V090JobEntity e);
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract void upsertAll(List<V090JobEntity> e);
    @Query("SELECT COUNT(*) FROM render_jobs") abstract int total();
    @Query("SELECT COUNT(*) FROM render_jobs WHERE state=:state") abstract int count(String state);
    @Query("DELETE FROM render_jobs WHERE state=:state") abstract int deleteState(String state);
    @Query("DELETE FROM render_jobs") abstract void clearAll();
    @Query("UPDATE render_jobs SET state=:state,error=:error,updatedAt=:now,heartbeatAt=:now WHERE id=:id") abstract int setState(String id,String state,String error,long now);
    @Query("UPDATE render_jobs SET heartbeatAt=:now,updatedAt=:now WHERE id=:id AND state='RUNNING'") abstract int heartbeat(String id,long now);
    @Query("UPDATE render_jobs SET state='PENDING',error='',updatedAt=:now WHERE state='PAUSED'") abstract int resumePaused(long now);
    @Query("UPDATE render_jobs SET state='PAUSED',error='Paused',updatedAt=:now WHERE state='PENDING'") abstract int pausePending(long now);
    @Query("UPDATE render_jobs SET state='PENDING',error='',updatedAt=:now WHERE state='FAILED' OR state='STALLED'") abstract int retryBroken(long now);
    @Query("UPDATE render_jobs SET state='PENDING',error='Recovered after interruption',updatedAt=:now WHERE state='RUNNING'") abstract int resetInterrupted(long now);
    @Query("UPDATE render_jobs SET state='STALLED',error='Watchdog: no heartbeat',updatedAt=:now WHERE state='RUNNING' AND heartbeatAt>0 AND heartbeatAt<:cutoff") abstract int markStalled(long cutoff,long now);
    @Query("DELETE FROM render_jobs WHERE state='DONE'") abstract int clearDone();
    @Query("DELETE FROM render_jobs WHERE state='FAILED' OR state='STALLED'") abstract int clearBroken();

    @Transaction
    V090JobEntity claimNext(long now) {
        V090JobEntity e = firstByState(V080ExportQueue.PENDING);
        if (e == null) return null;
        e.state = V080ExportQueue.RUNNING;
        e.attempts++;
        e.error = "";
        e.updatedAt = now;
        e.heartbeatAt = now;
        upsert(e);
        return byId(e.id);
    }
}
