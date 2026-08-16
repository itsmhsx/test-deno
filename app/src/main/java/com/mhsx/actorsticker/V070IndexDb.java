package com.mhsx.actorsticker;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

/** Small metadata database; heavy hit payloads remain private fragment files. */
final class V070IndexDb extends SQLiteOpenHelper {
    V070IndexDb(Context c) { super(c, "actor_index_v070.db", null, 1); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE video_cache (fingerprint TEXT PRIMARY KEY, uri_hash TEXT, size_bytes INTEGER, duration_ms INTEGER, fragment_path TEXT, faces INTEGER, scanned_at INTEGER)");
        db.execSQL("CREATE TABLE perf (name TEXT PRIMARY KEY, value REAL, updated_at INTEGER)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {}

    void putVideo(String fingerprint, String uriHash, long size, long duration, String fragment, int faces) {
        getWritableDatabase().execSQL("INSERT OR REPLACE INTO video_cache(fingerprint,uri_hash,size_bytes,duration_ms,fragment_path,faces,scanned_at) VALUES(?,?,?,?,?,?,?)",
                new Object[]{fingerprint, uriHash, size, duration, fragment, faces, System.currentTimeMillis()});
    }

    boolean hasVideo(String fingerprint, String fragmentPath) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT fragment_path FROM video_cache WHERE fingerprint=?", new String[]{fingerprint})) {
            return c.moveToFirst() && fragmentPath.equals(c.getString(0));
        } catch (Throwable ignored) { return false; }
    }

    void putPerf(String name, double value) {
        getWritableDatabase().execSQL("INSERT OR REPLACE INTO perf(name,value,updated_at) VALUES(?,?,?)",
                new Object[]{name, value, System.currentTimeMillis()});
    }

    double perf(String name, double fallback) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT value FROM perf WHERE name=?", new String[]{name})) {
            return c.moveToFirst() ? c.getDouble(0) : fallback;
        } catch (Throwable ignored) { return fallback; }
    }
}
