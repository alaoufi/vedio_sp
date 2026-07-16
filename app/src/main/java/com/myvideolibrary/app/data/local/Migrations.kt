package com.myvideolibrary.app.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Adds the audio_url column for muxed (video-only + audio) downloads. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE downloads ADD COLUMN audio_url TEXT")
    }
}

/** Adds the is_locked column for per-video locking. */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE videos ADD COLUMN is_locked INTEGER NOT NULL DEFAULT 0")
    }
}
