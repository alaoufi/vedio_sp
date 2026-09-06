package com.myvideolibrary.app.provider

import com.myvideolibrary.app.data.model.VideoSource
import com.myvideolibrary.app.provider.model.ProviderChannelPage
import com.myvideolibrary.app.provider.model.ProviderFeedPage
import com.myvideolibrary.app.provider.model.ProviderSearchItem
import com.myvideolibrary.app.provider.model.ProviderVideoDetail
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

    /** Optional trending/popular feed. Providers that don't support it return empty. */
    suspend fun trending(): List<ProviderSearchItem> = emptyList()

    /**
     * First page of a feed for infinite scroll. When [query] is null this is the
     * trending/home feed; otherwise it is a keyword search. The returned
     * [ProviderFeedPage.continuation] is passed back to [feedMore] to load the
     * next page. The default delegates to [search]/[trending] with no
     * continuation (single page); providers override to support pagination.
     */
    suspend fun feed(query: String?): ProviderFeedPage =
        ProviderFeedPage(if (query.isNullOrBlank()) trending() else search(query), null)

    /**
     * Loads the next page of a feed given a [continuation] previously returned by
     * [feed] or [feedMore]. The default returns an empty, terminal page.
     */
    suspend fun feedMore(continuation: Any?): ProviderFeedPage =
        ProviderFeedPage(emptyList(), null)

    /**
     * Full detail for one video (description, uploader, related list) powering a
     * detail page. Returns null for providers that don't support it.
     */
    suspend fun details(url: String): ProviderVideoDetail? = null

    /**
     * First page of a channel/uploader's videos, for channel browsing. [channelUrl]
     * is the uploader URL carried on a [ProviderVideoDetail] or search item.
     * Returns null for providers that don't support it.
     */
    suspend fun channel(channelUrl: String): ProviderChannelPage? = null

    /** Next page of a channel's videos given a continuation from [channel]/[channelMore]. */
    suspend fun channelMore(channelUrl: String, continuation: Any?): ProviderChannelPage? = null

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
