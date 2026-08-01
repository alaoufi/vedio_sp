package com.myvideolibrary.app.di

import com.myvideolibrary.app.provider.VideoProvider
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
    // Instagram and Snapchat removed: their public extraction was unreliable and
    // rarely worked, so they no longer appear in search or link download. Their
    // sites can still be captured through the in-app browser's media sniffer.
}
