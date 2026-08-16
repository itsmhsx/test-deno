package com.mhsx.actorsticker;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(tableName = "render_jobs", indices = {@Index("state"), @Index("createdAt"), @Index("heartbeatAt")})
final class V090JobEntity {
    @PrimaryKey @NonNull String id = "";
    String projectKey = "", sourceUri = "", outputTree = "", outputName = "";
    String actorIds = "", mode = "separate", aspect = "1:1", preset = "Instagram 1:1", codec = "Auto";
    String state = V080ExportQueue.PENDING, error = "";
    int videoIndex, attempts;
    long startMs, endMs, createdAt, updatedAt, heartbeatAt;
    float score;
    boolean safeZone = true, tracking = false, maxQuality = true;
}
