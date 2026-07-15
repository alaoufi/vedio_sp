package com.myvideolibrary.app.provider

import com.myvideolibrary.app.data.model.VideoSource
import com.myvideolibrary.app.provider.model.ProviderSearchItem
import com.myvideolibrary.app.provider.model.ResolvedVideo

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
}
