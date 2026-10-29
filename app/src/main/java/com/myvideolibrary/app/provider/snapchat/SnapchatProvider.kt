package com.myvideolibrary.app.provider.snapchat

import com.myvideolibrary.app.data.model.VideoSource
import com.myvideolibrary.app.provider.VideoProvider
import com.myvideolibrary.app.provider.model.ResolvedVideo
import com.myvideolibrary.app.provider.model.ProviderException
import com.myvideolibrary.app.provider.model.ProviderErrorType
import com.myvideolibrary.app.provider.web.OpenGraphResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves public story shares to their exact snap, and Spotlight via page metadata.
 * Private or expired snaps fail without selecting a different story or preview.
 */
@Singleton
class SnapchatProvider @Inject constructor(
    private val client: OkHttpClient
) : VideoProvider {

    private val pageClient = client.newBuilder().callTimeout(30, TimeUnit.SECONDS).build()

    override val source: VideoSource = VideoSource.SNAPCHAT

    override fun canHandle(url: String): Boolean =
        url.toHttpUrlOrNull()?.host?.let {
            it == "snapchat.com" || it.endsWith(".snapchat.com")
        } ?: false

    override suspend fun resolve(url: String): ResolvedVideo = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36")
            .header("Accept-Language", "en-US,en;q=0.9").build()
        try {
            pageClient.newCall(request).execute().use { response ->
                if (response.code == 404) throw ProviderException(ProviderErrorType.NOT_FOUND, "Snapchat snap not found")
                if (!response.isSuccessful) throw ProviderException(ProviderErrorType.NETWORK, "HTTP ${response.code}")
                val body = response.body ?: throw ProviderException(ProviderErrorType.EXTRACTION_FAILED, "Empty Snapchat page")
                val finalUrl = response.request.url.toString()
                if (body.contentType()?.type.equals("video", ignoreCase = true)) {
                    return@withContext ResolvedVideo(source, url, "Snapchat video", finalUrl)
                }
                val html = body.string()
                SnapchatStoryParser.resolve(html, finalUrl, url)
                    ?: OpenGraphResolver.resolvePage(html, url, source, "Snapchat video")
            }
        } catch (error: IOException) {
            throw ProviderException(ProviderErrorType.NETWORK, "Could not load the public Snapchat share page", error)
        }
    }
}
