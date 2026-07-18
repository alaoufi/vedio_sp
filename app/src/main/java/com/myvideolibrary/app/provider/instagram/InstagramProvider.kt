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
 * Downloads public Instagram posts/reels. Instagram itself now returns
 * "login_required" for every logged-out request, so the primary path is a public
 * embed relay (kk/ddinstagram) that serves the media to link-preview bots via
 * Open Graph tags. Falls back to Instagram's own media-info API and page scrape.
 * Private/login-only content can't be extracted and fails with a clear message.
 */
@Singleton
class InstagramProvider @Inject constructor(
    private val client: OkHttpClient
) : VideoProvider {

    override val source: VideoSource = VideoSource.INSTAGRAM

    override fun canHandle(url: String): Boolean =
        url.lowercase().let { it.contains("instagram.com") || it.contains("instagr.am") }

    override suspend fun resolve(url: String): ResolvedVideo = withContext(Dispatchers.IO) {
        relayResolve(url)?.let { return@withContext it }
        shortcodeOf(url)?.let { sc ->
            runCatching { resolveViaApi(url, sc) }.getOrNull()?.let { return@withContext it }
        }
        try {
            OpenGraphResolver.resolve(client, url, source, "Instagram video")
        } catch (e: ProviderException) {
            embedUrl(url)?.let { OpenGraphResolver.resolve(client, it, source, "Instagram video") }
                ?: throw e
        }
    }

    /** Fetches the video via an embed relay that exposes og:video to bot clients. */
    private fun relayResolve(url: String): ResolvedVideo? {
        for (host in RELAY_HOSTS) {
            val relay = url.replace(Regex("(?i)([a-z0-9-]+\\.)?instagram\\.com"), host)
            val result = runCatching { fetchOg(relay, url) }.getOrNull()
            if (result != null) return result
        }
        return null
    }

    private fun fetchOg(relayUrl: String, originalUrl: String): ResolvedVideo? {
        val request = Request.Builder()
            .url(relayUrl)
            .header("User-Agent", "Discordbot/2.0 (+https://discordapp.com)")
            .build()
        val body = client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return null
            resp.body?.string()
        } ?: return null
        val video = meta(body, "og:video:secure_url") ?: meta(body, "og:video") ?: return null
        return ResolvedVideo(
            source = source,
            sourceUrl = originalUrl,
            title = meta(body, "og:title")?.takeIf { it.isNotBlank() } ?: "Instagram video",
            directUrl = decode(video),
            thumbnailUrl = meta(body, "og:image")?.let { decode(it) }
        )
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
            ?: throw ProviderException(ProviderErrorType.EXTRACTION_FAILED, "No video")
        val thumb = Regex("\"image_versions2\":\\{\"candidates\":\\[\\{[^}]*?\"url\":\"([^\"]+)\"")
            .find(body)?.groupValues?.get(1)
        return ResolvedVideo(
            source = source,
            sourceUrl = url,
            title = "Instagram video",
            directUrl = decode(video),
            thumbnailUrl = thumb?.let { decode(it) }
        )
    }

    private fun shortcodeOf(url: String): String? =
        Regex("/(?:p|reel|reels|tv)/([A-Za-z0-9_-]+)").find(url)?.groupValues?.get(1)

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

    /** Reads a `<meta property|name="prop" content="…">` tag, either attribute order. */
    private fun meta(html: String, prop: String): String? {
        val esc = Regex.escape(prop)
        Regex("<meta[^>]+(?:property|name)=[\"']$esc[\"'][^>]+content=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
            .find(html)?.let { return it.groupValues[1] }
        return Regex("<meta[^>]+content=[\"']([^\"']+)[\"'][^>]+(?:property|name)=[\"']$esc[\"']", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)
    }

    private fun decode(s: String): String =
        s.replace("\\u0026", "&").replace("\\/", "/").replace("&amp;", "&")

    private companion object {
        val RELAY_HOSTS = listOf("kkinstagram.com", "ddinstagram.com")
    }
}
