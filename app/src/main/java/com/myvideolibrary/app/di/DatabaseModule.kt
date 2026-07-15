package com.myvideolibrary.app.di

import android.content.Context
import androidx.room.Room
import com.myvideolibrary.app.data.local.AppDatabase
import com.myvideolibrary.app.data.local.DatabaseKeyManager
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
}
