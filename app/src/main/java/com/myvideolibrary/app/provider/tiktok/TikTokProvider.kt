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

        // hdplay / play are both watermark-free; prefer HD.
        val play = data.str("hdplay")?.takeIf { it.isNotBlank() }
            ?: data.str("play")?.takeIf { it.isNotBlank() }
            ?: throw ProviderException(
                ProviderErrorType.EXTRACTION_FAILED,
                "No watermark-free URL returned"
            )

        ResolvedVideo(
            source = VideoSource.TIKTOK,
            sourceUrl = url,
            title = data.str("title")?.takeIf { it.isNotBlank() } ?: "TikTok video",
            directUrl = absolutize(play),
            thumbnailUrl = data.str("cover")?.let { absolutize(it) },
            author = data.obj("author")?.str("nickname"),
            durationMs = (data.num("duration") ?: 0) * 1000
        )
    }

    override suspend fun search(query: String): List<ProviderSearchItem> =
        withContext(Dispatchers.IO) {
            val q = query.trim()
            if (q.isEmpty()) return@withContext emptyList()
            val api = "$RESOLVER_BASE/api/feed/search?count=20&keywords=" +
                URLEncoder.encode(q, "UTF-8")
            val request = Request.Builder().url(api).header("User-Agent", UA).build()

            val body = try {
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext emptyList()
                    resp.body?.string()
                }
            } catch (e: IOException) {
                throw ProviderException(ProviderErrorType.NETWORK, "Network error", e)
            } ?: return@withContext emptyList()

            val root = runCatching { gson.fromJson(body, JsonObject::class.java) }.getOrNull()
                ?: return@withContext emptyList()
            if ((root.get("code")?.takeIf { !it.isJsonNull }?.asInt ?: -1) != 0) {
                return@withContext emptyList()
            }
            val videos = root.obj("data")?.get("videos")?.takeIf { it.isJsonArray }?.asJsonArray
                ?: return@withContext emptyList()

            videos.mapNotNull { el ->
                val v = el.asJsonObject
                val id = v.str("video_id") ?: v.str("id") ?: return@mapNotNull null
                val author = v.obj("author")
                val handle = author?.str("unique_id")
                ProviderSearchItem(
                    source = VideoSource.TIKTOK,
                    url = if (handle != null) "https://www.tiktok.com/@$handle/video/$id"
                    else "https://www.tiktok.com/video/$id",
                    title = v.str("title")?.takeIf { it.isNotBlank() } ?: "TikTok video",
                    thumbnailUrl = v.str("cover")?.let { absolutize(it) },
                    author = author?.str("nickname"),
                    durationMs = (v.num("duration") ?: 0) * 1000,
                    // tikwm already gives the watermark-free URL in search results.
                    directUrl = v.str("play")?.let { absolutize(it) }
                )
            }
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

    companion object {
        private const val RESOLVER_BASE = "https://www.tikwm.com"
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    }
}
