package com.myvideolibrary.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.myvideolibrary.app.data.local.dao.DownloadDao
import com.myvideolibrary.app.data.local.dao.FolderDao
import com.myvideolibrary.app.data.local.dao.SettingsDao
import com.myvideolibrary.app.data.local.dao.VideoDao
import com.myvideolibrary.app.data.local.entity.DownloadEntity
import com.myvideolibrary.app.data.local.entity.FolderEntity
import com.myvideolibrary.app.data.local.entity.SettingsEntity
import com.myvideolibrary.app.data.local.entity.VideoEntity

@Database(
    entities = [
        VideoEntity::class,
        FolderEntity::class,
        DownloadEntity::class,
        SettingsEntity::class
    ],
    version = 5,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun videoDao(): VideoDao
    abstract fun folderDao(): FolderDao
    abstract fun downloadDao(): DownloadDao
    abstract fun settingsDao(): SettingsDao

    companion object {
        const val DATABASE_NAME = "my_video_library.db"
    }
}
