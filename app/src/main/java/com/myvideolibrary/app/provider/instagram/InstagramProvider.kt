package com.myvideolibrary.app.provider.instagram

import com.myvideolibrary.app.data.model.VideoSource
import com.myvideolibrary.app.provider.VideoProvider
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
        OpenGraphResolver.resolve(client, url, source, "Instagram video")
    }
}
