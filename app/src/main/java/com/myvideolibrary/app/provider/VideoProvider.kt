package com.myvideolibrary.app.provider

import com.myvideolibrary.app.data.model.VideoSource
import com.myvideolibrary.app.provider.model.ProviderSearchItem
import com.myvideolibrary.app.provider.model.ResolvedVideo
import com.myvideolibrary.app.provider.model.StreamSource

/**
 * Contract every video source must implement. The core app talks only to this
 * interface, never to a concrete platform, so new providers can be added — or a
 * broken one replaced — without touching the rest of the app.
 */
interface VideoProvider {

    /** The source this provider serves. */
    val source: VideoSource

    /** Whether this provider recognises and can process [url]. */
    fun canHandle(url: String): Boolean

    /**
     * Resolves a shareable URL into a downloadable [ResolvedVideo].
     * @throws com.myvideolibrary.app.provider.model.ProviderException on any
     *   recognised failure (invalid/private/deleted link, network, extraction).
     */
    suspend fun resolve(url: String): ResolvedVideo

    /** Optional keyword search. Providers that don't support it return empty. */
    suspend fun search(query: String): List<ProviderSearchItem> = emptyList()

    /**
     * Resolves [url] into a single, directly-playable progressive stream for
     * preview-without-download. The default derives it from [resolve]; providers
     * whose best download stream is *split* (video-only + audio) override this to
     * return a muxed URL a player can open directly.
     */
    suspend fun resolveStream(url: String): StreamSource {
        val r = resolve(url)
        return StreamSource(
            source = r.source,
            sourceUrl = r.sourceUrl,
            title = r.title,
            streamUrl = r.directUrl,
            thumbnailUrl = r.thumbnailUrl
        )
    }
}
