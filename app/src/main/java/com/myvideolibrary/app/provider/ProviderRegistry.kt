package com.myvideolibrary.app.provider

import com.myvideolibrary.app.data.model.VideoSource
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds all registered [VideoProvider]s and routes a URL to the one that can
 * handle it. Providers are injected as a set, so adding one is a DI-only change.
 */
@Singleton
class ProviderRegistry @Inject constructor(
    private val providers: Set<@JvmSuppressWildcards VideoProvider>
) {

    fun providerForUrl(url: String): VideoProvider? =
        providers.firstOrNull { runCatching { it.canHandle(url) }.getOrDefault(false) }

    fun providerForSource(source: VideoSource): VideoProvider? =
        providers.firstOrNull { it.source == source }

    fun all(): List<VideoProvider> = providers.toList()
}
