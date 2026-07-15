package com.myvideolibrary.app.provider.tiktok

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.myvideolibrary.app.data.model.VideoSource
import com.myvideolibrary.app.provider.VideoProvider
import com.myvideolibrary.app.provider.model.ProviderErrorType
import com.myvideolibrary.app.provider.model.ProviderException
import com.myvideolibrary.app.provider.model.ResolvedVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TikTok provider, isolated from the rest of the app so it can be updated
 * independently.
 *
 * Metadata (title, author, thumbnail) comes from TikTok's official **oEmbed**
 * endpoint, which is stable. The direct media URL is scraped from the share
 * page's embedded JSON — this part is fragile and may break when TikTok changes
 * its page structure; failures surface as a typed [ProviderException].
 */
@Singleton
class TikTokProvider @Inject constructor(
    private val client: OkHttpClient,
    private val gson: Gson
) : VideoProvider {

    override val source: VideoSource = VideoSource.TIKTOK

    override fun canHandle(url: String): Boolean =
        url.lowercase().let { it.contains("tiktok.com") || it.contains("vm.tiktok") }

    override suspend fun resolve(url: String): ResolvedVideo = withContext(Dispatchers.IO) {
        if (!canHandle(url)) {
            throw ProviderException(ProviderErrorType.INVALID_LINK, "Not a TikTok link")
        }

        val meta = fetchOEmbed(url)
        val pageHtml = fetchPage(url)
        val directUrl = extractPlayAddr(pageHtml)
            ?: throw ProviderException(
                ProviderErrorType.EXTRACTION_FAILED,
                "Could not extract the video URL (TikTok may have changed, or the video is private)"
            )

        ResolvedVideo(
            source = VideoSource.TIKTOK,
            sourceUrl = url,
            title = meta?.get("title")?.asStringOrNull()
                ?: "TikTok video",
            directUrl = directUrl,
            thumbnailUrl = meta?.get("thumbnail_url")?.asStringOrNull(),
            author = meta?.get("author_name")?.asStringOrNull()
        )
    }

    private fun fetchOEmbed(url: String): JsonObject? = try {
        val oembedUrl = "https://www.tiktok.com/oembed?url=$url"
        val request = Request.Builder().url(oembedUrl).header("User-Agent", UA).build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return null
            resp.body?.string()?.let { gson.fromJson(it, JsonObject::class.java) }
        }
    } catch (e: Exception) {
        null // Metadata is best-effort; the direct URL is what matters.
    }

    private fun fetchPage(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        return try {
            client.newCall(request).execute().use { resp ->
                when {
                    resp.code == 404 -> throw ProviderException(
                        ProviderErrorType.NOT_FOUND, "Video not found or deleted"
                    )
                    !resp.isSuccessful -> throw ProviderException(
                        ProviderErrorType.NETWORK, "HTTP ${resp.code}"
                    )
                    else -> resp.body?.string().orEmpty()
                }
            }
        } catch (e: IOException) {
            throw ProviderException(ProviderErrorType.NETWORK, "Network error", e)
        }
    }

    /**
     * Pulls the first playable URL out of the page's embedded JSON. TikTok escapes
     * forward slashes and unicode, so the captured value is unescaped before use.
     */
    private fun extractPlayAddr(html: String): String? {
        for (key in listOf("\"playAddr\":\"", "\"downloadAddr\":\"")) {
            val start = html.indexOf(key)
            if (start >= 0) {
                val from = start + key.length
                val end = html.indexOf('"', from)
                if (end > from) {
                    val raw = html.substring(from, end)
                    val decoded = unescape(raw)
                    if (decoded.startsWith("http")) return decoded
                }
            }
        }
        return null
    }

    private fun unescape(value: String): String =
        value.replace("\\u002F", "/")
            .replace("\\u0026", "&")
            .replace("\\/", "/")

    private fun com.google.gson.JsonElement.asStringOrNull(): String? =
        if (isJsonNull) null else runCatching { asString }.getOrNull()

    companion object {
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    }
}
