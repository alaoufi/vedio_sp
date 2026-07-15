package com.myvideolibrary.app.provider.tiktok

import com.google.gson.Gson
import com.google.gson.JsonElement
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
 * TikTok provider, isolated so it can be updated independently.
 *
 * Produces a **watermark-free** download by using the video's `playAddr` (the
 * in-app streaming URL, which carries no watermark) and deliberately avoiding
 * `downloadAddr` (the share URL, which is watermarked).
 *
 * Metadata comes from TikTok's official **oEmbed** endpoint (stable). The media
 * URL is parsed from the share page's embedded JSON (`__UNIVERSAL_DATA_FOR_
 * REHYDRATION__`, with a `SIGI_STATE` fallback) — this part is fragile and may
 * break when TikTok changes its page; failures surface as a typed exception.
 */
@Singleton
class TikTokProvider @Inject constructor(
    private val client: OkHttpClient,
    private val gson: Gson
) : VideoProvider {

    override val source: VideoSource = VideoSource.TIKTOK

    override fun canHandle(url: String): Boolean =
        url.lowercase().let {
            it.contains("tiktok.com") || it.contains("vm.tiktok") || it.contains("vt.tiktok")
        }

    override suspend fun resolve(url: String): ResolvedVideo = withContext(Dispatchers.IO) {
        if (!canHandle(url)) {
            throw ProviderException(ProviderErrorType.INVALID_LINK, "Not a TikTok link")
        }

        // Follows short links (vm./vt.) to the canonical page automatically.
        val (finalUrl, html) = fetchPage(url)
        val meta = fetchOEmbed(finalUrl)

        val extracted = extractNoWatermark(html)
            ?: throw ProviderException(
                ProviderErrorType.EXTRACTION_FAILED,
                "Could not extract a watermark-free URL (TikTok may have changed, or the video is private)"
            )

        ResolvedVideo(
            source = VideoSource.TIKTOK,
            sourceUrl = finalUrl,
            title = extracted.title
                ?: meta?.get("title")?.asStringOrNull()
                ?: "TikTok video",
            directUrl = extracted.url,
            thumbnailUrl = extracted.cover ?: meta?.get("thumbnail_url")?.asStringOrNull(),
            author = extracted.author ?: meta?.get("author_name")?.asStringOrNull(),
            durationMs = extracted.durationMs
        )
    }

    private data class Extracted(
        val url: String,
        val title: String?,
        val author: String?,
        val cover: String?,
        val durationMs: Long
    )

    // ---- Network ----

    /** Returns the final (redirect-resolved) URL and the page HTML. */
    private fun fetchPage(url: String): Pair<String, String> {
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
                    else -> resp.request.url.toString() to resp.body?.string().orEmpty()
                }
            }
        } catch (e: IOException) {
            throw ProviderException(ProviderErrorType.NETWORK, "Network error", e)
        }
    }

    private fun fetchOEmbed(url: String): JsonObject? = try {
        val oembedUrl = "https://www.tiktok.com/oembed?url=$url"
        val request = Request.Builder().url(oembedUrl).header("User-Agent", UA).build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return null
            resp.body?.string()?.let { gson.fromJson(it, JsonObject::class.java) }
        }
    } catch (e: Exception) {
        null
    }

    // ---- Extraction (watermark-free) ----

    private fun extractNoWatermark(html: String): Extracted? =
        fromUniversalData(html) ?: fromSigiState(html)

    /** Modern format: __UNIVERSAL_DATA_FOR_REHYDRATION__. */
    private fun fromUniversalData(html: String): Extracted? {
        val json = extractScriptJson(html, "__UNIVERSAL_DATA_FOR_REHYDRATION__") ?: return null
        val root = runCatching { gson.fromJson(json, JsonObject::class.java) }.getOrNull() ?: return null

        val item = root.obj("__DEFAULT_SCOPE__")
            ?.obj("webapp.video-detail")
            ?.obj("itemInfo")
            ?.obj("itemStruct")
            ?: return null

        val video = item.obj("video") ?: return null
        val url = playAddrOf(video) ?: return null

        return Extracted(
            url = url,
            title = item.str("desc"),
            author = item.obj("author")?.str("nickname"),
            cover = video.str("cover") ?: video.str("originCover"),
            durationMs = (video.num("duration") ?: 0) * 1000
        )
    }

    /** Legacy fallback: window['SIGI_STATE']. */
    private fun fromSigiState(html: String): Extracted? {
        val json = extractScriptJson(html, "SIGI_STATE") ?: return null
        val root = runCatching { gson.fromJson(json, JsonObject::class.java) }.getOrNull() ?: return null

        val itemModule = root.obj("ItemModule") ?: return null
        val firstKey = itemModule.keySet().firstOrNull() ?: return null
        val item = itemModule.obj(firstKey) ?: return null

        val video = item.obj("video") ?: return null
        val url = playAddrOf(video) ?: return null

        return Extracted(
            url = url,
            title = item.str("desc"),
            author = item.obj("author")?.let { it.str("nickname") } ?: item.str("author"),
            cover = video.str("cover"),
            durationMs = (video.num("duration") ?: 0) * 1000
        )
    }

    /**
     * Prefers the watermark-free `playAddr`; if absent, uses the highest-quality
     * `bitrateInfo` play URL (also watermark-free). Never returns `downloadAddr`.
     */
    private fun playAddrOf(video: JsonObject): String? {
        video.str("playAddr")?.takeIf { it.startsWith("http") }?.let { return it }

        val bitrates = video.get("bitrateInfo")?.takeIf { it.isJsonArray }?.asJsonArray
        bitrates?.forEach { entry ->
            val playAddr = entry.asJsonObject.obj("PlayAddr") ?: return@forEach
            val urlList = playAddr.get("UrlList")?.takeIf { it.isJsonArray }?.asJsonArray
            urlList?.firstOrNull()?.asString?.takeIf { it.startsWith("http") }?.let { return it }
        }
        return null
    }

    // ---- Helpers ----

    /** Pulls the JSON body of a <script id="..."> ... </script> block. */
    private fun extractScriptJson(html: String, scriptId: String): String? {
        val marker = "id=\"$scriptId\""
        val idIndex = html.indexOf(marker)
        if (idIndex < 0) return null
        val open = html.indexOf('>', idIndex)
        if (open < 0) return null
        val close = html.indexOf("</script>", open)
        if (close < 0) return null
        return html.substring(open + 1, close).trim()
    }

    private fun JsonObject.obj(name: String): JsonObject? =
        get(name)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.str(name: String): String? =
        get(name)?.let { if (it.isJsonNull) null else runCatching { it.asString }.getOrNull() }

    private fun JsonObject.num(name: String): Long? =
        get(name)?.let { if (it.isJsonNull) null else runCatching { it.asLong }.getOrNull() }

    private fun JsonElement.asStringOrNull(): String? =
        if (isJsonNull) null else runCatching { asString }.getOrNull()

    companion object {
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    }
}
