package com.myvideolibrary.app.provider.instagram

import com.myvideolibrary.app.data.model.VideoSource
import com.myvideolibrary.app.provider.VideoProvider
import com.myvideolibrary.app.provider.model.ProviderException
import com.myvideolibrary.app.provider.model.ResolvedVideo
import com.myvideolibrary.app.provider.web.OpenGraphResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Downloads public Instagram posts, reels and IGTV by reading the share page's
 * Open Graph tags. Private or login-walled posts can't be extracted and fail
 * with a clear message (Instagram gates most content behind login).
 */
@Singleton
class InstagramProvider @Inject constructor(
    private val client: OkHttpClient
) : VideoProvider {

    override val source: VideoSource = VideoSource.INSTAGRAM

    override fun canHandle(url: String): Boolean =
        url.lowercase().let { it.contains("instagram.com") || it.contains("instagr.am") }

    override suspend fun resolve(url: String): ResolvedVideo = withContext(Dispatchers.IO) {
        try {
            OpenGraphResolver.resolve(client, url, source, "Instagram video")
        } catch (e: ProviderException) {
            // The normal page is often a login wall; the embed page exposes the
            // media for public posts more reliably.
            embedUrl(url)?.let { OpenGraphResolver.resolve(client, it, source, "Instagram video") }
                ?: throw e
        }
    }

    /** Turns a post/reel URL into its public embed URL, or null if not one. */
    private fun embedUrl(url: String): String? {
        val base = Regex("(https?://[^?#]*/(?:p|reel|reels|tv)/[^/?#]+)")
            .find(url)?.groupValues?.get(1) ?: return null
        return base.trimEnd('/') + "/embed/captioned/"
    }
}
