package com.myvideolibrary.app.provider.tiktok

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.myvideolibrary.app.data.model.VideoSource
import com.myvideolibrary.app.provider.VideoProvider
import com.myvideolibrary.app.provider.model.ProviderErrorType
import com.myvideolibrary.app.provider.model.ProviderException
import com.myvideolibrary.app.provider.model.ProviderSearchItem
import com.myvideolibrary.app.provider.model.ResolvedVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TikTok provider that resolves the **watermark-free** video via a public resolver
 * service (tikwm). This is far more reliable than scraping TikTok's own page, which
 * frequently omits the media URL for non-browser requests.
 *
 * Privacy note: the TikTok link is sent to the resolver service to obtain the
 * download URL. This is an explicit, user-chosen trade-off for reliability; no
 * other data leaves the device, and the video itself is stored locally.
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

        val api = "$RESOLVER_BASE/api/?hd=1&url=" + URLEncoder.encode(url, "UTF-8")
        val request = Request.Builder()
            .url(api)
            .header("User-Agent", UA)
            .build()

        val bodyString = try {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw ProviderException(ProviderErrorType.NETWORK, "HTTP ${resp.code}")
                }
                resp.body?.string()
            }
        } catch (e: IOException) {
            throw ProviderException(ProviderErrorType.NETWORK, "Network error", e)
        } ?: throw ProviderException(ProviderErrorType.EXTRACTION_FAILED, "Empty response")

        val root = runCatching { gson.fromJson(bodyString, JsonObject::class.java) }.getOrNull()
            ?: throw ProviderException(ProviderErrorType.EXTRACTION_FAILED, "Bad response")

        val code = root.get("code")?.takeIf { !it.isJsonNull }?.asInt ?: -1
        if (code != 0) {
            val msg = root.str("msg").orEmpty()
            throw mapError(msg)
        }

        val data = root.obj("data")
            ?: throw ProviderException(ProviderErrorType.EXTRACTION_FAILED, "No data")

        // The clean still image (photo-post picture, else full-res poster).
        val cleanImage = data.cleanImage()

        // Photo / slideshow posts have no video: tikwm returns the background
        // music in the "play"/"hdplay" fields, which is why they used to download
        // as audio only. Detect them by the images array and save the picture.
        if (data.isPhotoPost()) {
            val images = data.allImages().ifEmpty { listOfNotNull(cleanImage) }
            val first = images.firstOrNull()
                ?: throw ProviderException(
                    ProviderErrorType.EXTRACTION_FAILED, "No image found in this post"
                )
            // Rebuild the original slideshow on-device: pictures + background music
            // (the "play"/"music" field is the audio track for a photo post).
            val music = data.str("music")?.takeIf { it.isNotBlank() }
                ?: data.str("play")?.takeIf { it.isNotBlank() }
            return@withContext ResolvedVideo(
                source = VideoSource.TIKTOK,
                sourceUrl = url,
                title = data.str("title")?.takeIf { it.isNotBlank() } ?: "TikTok photo",
                directUrl = first,
                audioUrl = music?.let { absolutize(it) },
                thumbnailUrl = cleanImage ?: first,
                author = data.obj("author")?.str("nickname"),
                imageUrls = images,
                isSlideshow = true
            )
        }

        // hdplay / play are both watermark-free; prefer HD.
        val play = data.str("hdplay")?.takeIf { it.isNotBlank() }
            ?: data.str("play")?.takeIf { it.isNotBlank() }
            ?: cleanImage
            ?: throw ProviderException(
                ProviderErrorType.EXTRACTION_FAILED,
                "No watermark-free URL returned"
            )

        ResolvedVideo(
            source = VideoSource.TIKTOK,
            sourceUrl = url,
            title = data.str("title")?.takeIf { it.isNotBlank() } ?: "TikTok video",
            directUrl = absolutize(play),
            // A pure image with no play-button overlay, badge, or watermark, so
            // an image-only save is the raw picture — never a screenshot.
            thumbnailUrl = cleanImage,
            author = data.obj("author")?.str("nickname"),
            durationMs = (data.num("duration") ?: 0) * 1000
        )
    }

    override suspend fun search(query: String): List<ProviderSearchItem> =
        withContext(Dispatchers.IO) {
            val q = query.trim()
            if (q.isEmpty()) return@withContext emptyList()

            // Page through the search feed (tikwm returns a small batch + a cursor)
            // so the user gets a full, scrollable list like TikTok itself, not ~6.
            val out = ArrayList<ProviderSearchItem>()
            var cursor = "0"
            var pages = 0
            while (out.size < MAX_SEARCH_RESULTS && pages < MAX_SEARCH_PAGES) {
                val api = "$RESOLVER_BASE/api/feed/search?count=30&cursor=$cursor&keywords=" +
                    URLEncoder.encode(q, "UTF-8")
                val request = Request.Builder().url(api).header("User-Agent", UA).build()
                val body = try {
                    client.newCall(request).execute().use { resp ->
                        if (!resp.isSuccessful) return@use null
                        resp.body?.string()
                    }
                } catch (e: IOException) {
                    if (out.isEmpty()) throw ProviderException(ProviderErrorType.NETWORK, "Network error", e)
                    break
                } ?: break

                val root = runCatching { gson.fromJson(body, JsonObject::class.java) }.getOrNull() ?: break
                if ((root.get("code")?.takeIf { !it.isJsonNull }?.asInt ?: -1) != 0) break
                val data = root.obj("data") ?: break
                val videos = data.get("videos")?.takeIf { it.isJsonArray }?.asJsonArray ?: break
                if (videos.size() == 0) break

                out += videos.mapNotNull(::mapSearchVideo)
                pages++

                val hasMore = runCatching { data.get("hasMore")?.asBoolean }.getOrNull() == true
                val next = data.str("cursor") ?: break
                if (!hasMore || next == cursor) break
                cursor = next
            }
            out
        }

    private fun mapSearchVideo(el: com.google.gson.JsonElement): ProviderSearchItem? {
        val v = el.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val id = v.str("video_id") ?: v.str("id") ?: return null
        val author = v.obj("author")
        val handle = author?.str("unique_id")
        return ProviderSearchItem(
            source = VideoSource.TIKTOK,
            url = if (handle != null) "https://www.tiktok.com/@$handle/video/$id"
            else "https://www.tiktok.com/video/$id",
            title = v.str("title")?.takeIf { it.isNotBlank() } ?: "TikTok video",
            thumbnailUrl = v.cleanImage(),
            author = author?.str("nickname"),
            durationMs = (v.num("duration") ?: 0) * 1000,
            // tikwm already gives the watermark-free URL in search results,
            // but for a photo post "play" is only the music — leave it null
            // so the download path re-resolves and saves the picture.
            directUrl = if (v.isPhotoPost()) null
            else v.str("play")?.let { absolutize(it) }
        )
    }

    private fun mapError(msg: String): ProviderException {
        val m = msg.lowercase()
        return when {
            m.contains("private") -> ProviderException(
                ProviderErrorType.PRIVATE_CONTENT, "This video is private"
            )
            m.contains("not found") || m.contains("deleted") || m.contains("does not exist") ->
                ProviderException(ProviderErrorType.NOT_FOUND, "Video not found or deleted")
            m.contains("url") -> ProviderException(
                ProviderErrorType.INVALID_LINK, "Invalid TikTok link"
            )
            else -> ProviderException(
                ProviderErrorType.EXTRACTION_FAILED,
                msg.ifBlank { "Could not extract the video" }
            )
        }
    }

    /** tikwm sometimes returns a host-relative path; make it absolute. */
    private fun absolutize(pathOrUrl: String): String =
        if (pathOrUrl.startsWith("http")) pathOrUrl else "$RESOLVER_BASE$pathOrUrl"

    private fun JsonObject.obj(name: String): JsonObject? =
        get(name)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.str(name: String): String? =
        get(name)?.let { if (it.isJsonNull) null else runCatching { it.asString }.getOrNull() }

    private fun JsonObject.num(name: String): Long? =
        get(name)?.let { if (it.isJsonNull) null else runCatching { it.asLong }.getOrNull() }

    /** A TikTok photo/slideshow post: has an images array, so no real video. */
    private fun JsonObject.isPhotoPost(): Boolean =
        get("images")?.takeIf { it.isJsonArray }?.asJsonArray?.size()?.let { it > 0 } == true

    /** Every picture URL in a photo/slideshow post, in order, made absolute. */
    private fun JsonObject.allImages(): List<String> =
        get("images")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { el ->
                el.takeIf { !it.isJsonNull }?.let { runCatching { it.asString }.getOrNull() }
                    ?.takeIf { it.isNotBlank() }?.let { absolutize(it) }
            } ?: emptyList()

    /** First image of a TikTok photo/slideshow post, if this is one. */
    private fun JsonObject.firstImage(): String? =
        get("images")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.firstOrNull { !it.isJsonNull }
            ?.let { runCatching { it.asString }.getOrNull() }
            ?.takeIf { it.isNotBlank() }

    /**
     * The cleanest still image for an "image only" save: for a photo post the
     * original uploaded picture (no watermark, no chrome); otherwise the
     * full-resolution poster frame. Falls back to the smaller cover.
     */
    private fun JsonObject.cleanImage(): String? =
        (firstImage() ?: str("origin_cover") ?: str("cover"))?.let { absolutize(it) }

    companion object {
        private const val RESOLVER_BASE = "https://www.tikwm.com"
        /** Upper bounds for paging the TikTok search feed. */
        private const val MAX_SEARCH_RESULTS = 60
        private const val MAX_SEARCH_PAGES = 4
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    }
}
