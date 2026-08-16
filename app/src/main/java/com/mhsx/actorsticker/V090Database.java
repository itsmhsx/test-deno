package com.mhsx.actorsticker;

import android.content.Context;
import androidx.room.*;

@Database(entities = {V090JobEntity.class}, version = 1, exportSchema = false)
abstract class V090Database extends RoomDatabase {
    abstract V090JobDao jobs();
    private static volatile V090Database instance;
    static V090Database get(Context c) {
        V090Database x = instance;
        if (x == null) synchronized (V090Database.class) {
            x = instance;
            if (x == null) instance = x = Room.databaseBuilder(c.getApplicationContext(), V090Database.class, "actor_sticker_v090.db")
                    .fallbackToDestructiveMigration()
                    .allowMainThreadQueries()
                    .build();
        }
        return x;
    }
}
