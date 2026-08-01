package com.myvideolibrary.app.provider.snapchat

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
 * Downloads public Snapchat Spotlight videos by reading the share page's Open
 * Graph tags. Ephemeral stories/snaps aren't publicly hosted and can't be
 * extracted; those fail with a clear message.
 */
@Singleton
class SnapchatProvider @Inject constructor(
    private val client: OkHttpClient
) : VideoProvider {

    override val source: VideoSource = VideoSource.SNAPCHAT

    override fun canHandle(url: String): Boolean =
        url.lowercase().let { it.contains("snapchat.com") || it.contains("t.snapchat.com") }

    override suspend fun resolve(url: String): ResolvedVideo = withContext(Dispatchers.IO) {
        OpenGraphResolver.resolve(client, url, source, "Snapchat video")
    }
}
