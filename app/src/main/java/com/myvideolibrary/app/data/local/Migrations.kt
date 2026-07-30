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

/** Adds is_link_only for saved links that stream instead of storing a file. */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE videos ADD COLUMN is_link_only INTEGER NOT NULL DEFAULT 0")
    }
}

/** Adds category_order for a user-defined category display order. */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE settings ADD COLUMN category_order TEXT")
    }
}

/** Adds the download kind (full/video/audio/image). */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE downloads ADD COLUMN kind TEXT NOT NULL DEFAULT 'full'")
    }
}

/** Adds media_type (video/audio/image) so audio + image items are filterable. */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE videos ADD COLUMN media_type TEXT NOT NULL DEFAULT 'video'")
    }
}

/**
 * Indexes the columns the library query filters and sorts on, so large
 * libraries stay fast. is_locked is applied to every query; category/source/
 * media_type are common filters; play_count/last_played_date back the stats.
 */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS index_videos_is_locked ON videos(is_locked)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_videos_category ON videos(category)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_videos_source ON videos(source)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_videos_media_type ON videos(media_type)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_videos_play_count ON videos(play_count)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_videos_last_played_date ON videos(last_played_date)")
    }
}

/** Adds the end-of-clip action (stop / repeat / next) player preference. */
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE settings ADD COLUMN end_of_clip_action TEXT NOT NULL DEFAULT 'next'")
    }
}

/** Adds per-category visibility (hidden) and password protection. */
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE settings ADD COLUMN hidden_categories TEXT")
        db.execSQL("ALTER TABLE settings ADD COLUMN category_passwords TEXT")
    }
}

/** Adds the password gating the category-management screen. */
val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE settings ADD COLUMN manage_categories_password TEXT")
    }
}

/** Adds image_urls for building a video from a TikTok slideshow post. */
val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE downloads ADD COLUMN image_urls TEXT")
    }
}

/** Adds playlists and their video membership table. */
val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS playlists (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "name TEXT NOT NULL, created_date INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS playlist_videos (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "playlist_id INTEGER NOT NULL, video_id INTEGER NOT NULL, position INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_playlist_videos_playlist_id " +
                "ON playlist_videos (playlist_id)"
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_playlist_videos_playlist_id_video_id " +
                "ON playlist_videos (playlist_id, video_id)"
        )
    }
}

val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS saved_searches (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "name TEXT NOT NULL, created_date INTEGER NOT NULL, " +
                "search TEXT, " +
                "favorites_only INTEGER NOT NULL DEFAULT 0, " +
                "protected_mode INTEGER NOT NULL DEFAULT 0, " +
                "sources TEXT, categories TEXT, media_types TEXT, tags TEXT, sort_order TEXT)"
        )
    }
}
