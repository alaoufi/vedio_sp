package com.myvideolibrary.app.di

import com.myvideolibrary.app.provider.VideoProvider
import com.myvideolibrary.app.provider.instagram.InstagramProvider
import com.myvideolibrary.app.provider.snapchat.SnapchatProvider
import com.myvideolibrary.app.provider.tiktok.TikTokProvider
import com.myvideolibrary.app.provider.youtube.YouTubeProvider
import dagger.Binds
import dagger.Module
import dagger.multibindings.IntoSet
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Registers all providers into a single set. Adding a new provider is a matter of
 * adding one `@Binds @IntoSet` line here — no other code changes.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ProviderModule {

    @Binds
    @IntoSet
    abstract fun bindTikTokProvider(provider: TikTokProvider): VideoProvider

    @Binds
    @IntoSet
    abstract fun bindYouTubeProvider(provider: YouTubeProvider): VideoProvider

    @Binds
    @IntoSet
    abstract fun bindInstagramProvider(provider: InstagramProvider): VideoProvider

    @Binds
    @IntoSet
    abstract fun bindSnapchatProvider(provider: SnapchatProvider): VideoProvider
}
