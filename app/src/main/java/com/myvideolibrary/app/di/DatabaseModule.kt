package com.myvideolibrary.app.di

import android.content.Context
import androidx.room.Room
import com.myvideolibrary.app.data.local.AppDatabase
import com.myvideolibrary.app.data.local.DatabaseKeyManager
import com.myvideolibrary.app.data.local.MIGRATION_1_2
import com.myvideolibrary.app.data.local.MIGRATION_2_3
import com.myvideolibrary.app.data.local.MIGRATION_3_4
import com.myvideolibrary.app.data.local.MIGRATION_4_5
import com.myvideolibrary.app.data.local.MIGRATION_5_6
import com.myvideolibrary.app.data.local.MIGRATION_6_7
import com.myvideolibrary.app.data.local.MIGRATION_7_8
import com.myvideolibrary.app.data.local.MIGRATION_8_9
import com.myvideolibrary.app.data.local.MIGRATION_9_10
import com.myvideolibrary.app.data.local.MIGRATION_10_11
import com.myvideolibrary.app.data.local.MIGRATION_11_12
import com.myvideolibrary.app.data.local.MIGRATION_12_13
import com.myvideolibrary.app.data.local.MIGRATION_13_14
import com.myvideolibrary.app.data.local.MIGRATION_14_15
import com.myvideolibrary.app.data.local.MIGRATION_15_16
import com.myvideolibrary.app.data.local.MIGRATION_16_17
import com.myvideolibrary.app.data.local.dao.DownloadDao
import com.myvideolibrary.app.data.local.dao.FolderDao
import com.myvideolibrary.app.data.local.dao.SettingsDao
import com.myvideolibrary.app.data.local.dao.VideoDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SupportFactory
import javax.inject.Singleton

/**
 * Provides the encrypted Room database and its DAOs.
 *
 * The database file lives in the app's private storage and is encrypted at rest
 * with SQLCipher using a key held only in the Android Keystore (see
 * [DatabaseKeyManager]).
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(
        @ApplicationContext context: Context,
        keyManager: DatabaseKeyManager
    ): AppDatabase {
        // Load the SQLCipher native libraries before opening the database.
        SQLiteDatabase.loadLibs(context)
        val passphrase = keyManager.getOrCreatePassphrase()
        val factory = SupportFactory(passphrase)

        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            AppDatabase.DATABASE_NAME
        )
            .openHelperFactory(factory)
            .addMigrations(
                MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
                MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9,
                MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13,
                MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17
            )
            .fallbackToDestructiveMigrationOnDowngrade()
            .build()
    }

    @Provides
    fun provideVideoDao(db: AppDatabase): VideoDao = db.videoDao()

    @Provides
    fun provideFolderDao(db: AppDatabase): FolderDao = db.folderDao()

    @Provides
    fun provideDownloadDao(db: AppDatabase): DownloadDao = db.downloadDao()

    @Provides
    fun provideSettingsDao(db: AppDatabase): SettingsDao = db.settingsDao()

    @Provides
    fun providePlaylistDao(db: AppDatabase): com.myvideolibrary.app.data.local.dao.PlaylistDao =
        db.playlistDao()

    @Provides
    fun provideSavedSearchDao(db: AppDatabase): com.myvideolibrary.app.data.local.dao.SavedSearchDao =
        db.savedSearchDao()
}
