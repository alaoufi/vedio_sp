package com.myvideolibrary.app.provider.model

import com.myvideolibrary.app.data.model.VideoSource

/**
 * A remote video resolved by a provider into everything the download manager
 * needs: a direct, downloadable media URL plus display metadata.
 */
data class ResolvedVideo(
    val source: VideoSource,
    val sourceUrl: String,
    val title: String,
    val directUrl: String,
    /**
     * When set, [directUrl] is a video-only stream and this is the matching
     * audio-only stream; the download manager muxes the two into one file.
     */
    val audioUrl: String? = null,
    val thumbnailUrl: String? = null,
    val author: String? = null,
    val durationMs: Long = 0,
    val quality: String? = null
)

/** Lightweight search result (used by providers that support search). */
data class ProviderSearchItem(
    val source: VideoSource,
    val url: String,
    val title: String,
    val thumbnailUrl: String? = null,
    val author: String? = null,
    val durationMs: Long = 0,
    /** When set, the direct downloadable URL is already known (skip re-resolving). */
    val directUrl: String? = null
)

/** Categorised, user-presentable failure reasons for provider operations. */
enum class ProviderErrorType {
    INVALID_LINK,
    PRIVATE_CONTENT,
    NOT_FOUND,
    UNSUPPORTED,
    NETWORK,
    EXTRACTION_FAILED,
    UNKNOWN
}

/** Thrown by providers; carries a typed reason so the UI can localise the message. */
class ProviderException(
    val type: ProviderErrorType,
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)
