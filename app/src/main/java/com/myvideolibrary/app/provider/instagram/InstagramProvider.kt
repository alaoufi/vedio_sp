package com.myvideolibrary.app.provider.instagram

import com.myvideolibrary.app.data.model.VideoSource
import com.myvideolibrary.app.provider.VideoProvider
import com.myvideolibrary.app.provider.model.ProviderErrorType
import com.myvideolibrary.app.provider.model.ProviderException
import com.myvideolibrary.app.provider.model.ResolvedVideo
import com.myvideolibrary.app.provider.web.OpenGraphResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.math.BigInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Downloads public Instagram posts/reels. Primary path: turn the shortcode into
 * a media id and query Instagram's public media-info endpoint with the web
 * app-id header (works for public media without login). Falls back to scraping
 * the page / embed Open Graph tags. Private or login-only content can't be
 * extracted and fails with a clear message.
 */
@Singleton
class InstagramProvider @Inject constructor(
    private val client: OkHttpClient
) : VideoProvider {

    override val source: VideoSource = VideoSource.INSTAGRAM

    override fun canHandle(url: String): Boolean =
        url.lowercase().let { it.contains("instagram.com") || it.contains("instagr.am") }

    override suspend fun resolve(url: String): ResolvedVideo = withContext(Dispatchers.IO) {
        val shortcode = shortcodeOf(url)
        if (shortcode != null) {
            runCatching { resolveViaApi(url, shortcode) }.getOrNull()?.let { return@withContext it }
        }
        // Fallbacks: normal page Open Graph, then the public embed page.
        try {
            OpenGraphResolver.resolve(client, url, source, "Instagram video")
        } catch (e: ProviderException) {
            embedUrl(url)?.let { OpenGraphResolver.resolve(client, it, source, "Instagram video") }
                ?: throw e
        }
    }

    private fun resolveViaApi(url: String, shortcode: String): ResolvedVideo {
        val mediaId = shortcodeToMediaId(shortcode)
        val request = Request.Builder()
            .url("https://i.instagram.com/api/v1/media/$mediaId/info/")
            .header("User-Agent", "Instagram 219.0.0.12.117 Android")
            .header("x-ig-app-id", "936619743392459")
            .header("Accept-Language", "en-US")
            .build()
        val body = client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw ProviderException(ProviderErrorType.EXTRACTION_FAILED, "HTTP ${resp.code}")
            resp.body?.string()
        } ?: throw ProviderException(ProviderErrorType.EXTRACTION_FAILED, "Empty response")

        val video = Regex("\"video_versions\":\\[\\{[^}]*?\"url\":\"([^\"]+)\"")
            .find(body)?.groupValues?.get(1)
            ?: throw ProviderException(
                ProviderErrorType.EXTRACTION_FAILED,
                "No video (private or image-only post)"
            )
        val thumb = Regex("\"image_versions2\":\\{\"candidates\":\\[\\{[^}]*?\"url\":\"([^\"]+)\"")
            .find(body)?.groupValues?.get(1)
        val title = Regex("\"caption\":\\{[^}]*?\"text\":\"([^\"]{1,80})")
            .find(body)?.groupValues?.get(1)

        return ResolvedVideo(
            source = source,
            sourceUrl = url,
            title = decode(title ?: "Instagram video"),
            directUrl = decode(video),
            thumbnailUrl = thumb?.let { decode(it) }
        )
    }

    /** Shortcode from /p/, /reel/, /reels/ or /tv/ URLs. */
    private fun shortcodeOf(url: String): String? =
        Regex("/(?:p|reel|reels|tv)/([A-Za-z0-9_-]+)").find(url)?.groupValues?.get(1)

    /** Instagram base64 shortcode → numeric media id. */
    private fun shortcodeToMediaId(shortcode: String): BigInteger {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        var id = BigInteger.ZERO
        val base = BigInteger.valueOf(64)
        for (c in shortcode) {
            val v = alphabet.indexOf(c)
            if (v < 0) break
            id = id.multiply(base).add(BigInteger.valueOf(v.toLong()))
        }
        return id
    }

    private fun embedUrl(url: String): String? {
        val base = Regex("(https?://[^?#]*/(?:p|reel|reels|tv)/[^/?#]+)")
            .find(url)?.groupValues?.get(1) ?: return null
        return base.trimEnd('/') + "/embed/captioned/"
    }

    private fun decode(s: String): String =
        s.replace("\\u0026", "&").replace("\\/", "/")
}
