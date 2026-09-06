package com.myvideolibrary.app.provider.model

/**
 * One page of a provider feed (search or trending), plus an opaque continuation
 * the caller passes back to load the next page. [continuation] is null when the
 * feed has no more pages. The continuation is provider-internal and must not be
 * interpreted by the UI — only stored and handed back to
 * [com.myvideolibrary.app.provider.VideoProvider.feedMore].
 */
data class ProviderFeedPage(
    val items: List<ProviderSearchItem>,
    val continuation: Any? = null
)
