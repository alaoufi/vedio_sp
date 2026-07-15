package com.myvideolibrary.app.di

import com.myvideolibrary.app.data.repository.DownloadRepository
import com.myvideolibrary.app.data.repository.DownloadRepositoryImpl
import com.myvideolibrary.app.data.repository.FolderRepository
import com.myvideolibrary.app.data.repository.FolderRepositoryImpl
import com.myvideolibrary.app.data.repository.SettingsRepository
import com.myvideolibrary.app.data.repository.SettingsRepositoryImpl
import com.myvideolibrary.app.data.repository.VideoRepository
import com.myvideolibrary.app.data.repository.VideoRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindVideoRepository(impl: VideoRepositoryImpl): VideoRepository

    @Binds
    @Singleton
    abstract fun bindFolderRepository(impl: FolderRepositoryImpl): FolderRepository

    @Binds
    @Singleton
    abstract fun bindSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository

    @Binds
    @Singleton
    abstract fun bindDownloadRepository(impl: DownloadRepositoryImpl): DownloadRepository
}
