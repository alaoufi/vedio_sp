package com.myvideolibrary.app.provider.youtube

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.myvideolibrary.app.data.model.VideoSource
import com.myvideolibrary.app.provider.VideoProvider
import com.myvideolibrary.app.provider.model.ProviderErrorType
import com.myvideolibrary.app.provider.model.ProviderException
import com.myvideolibrary.app.provider.model.ProviderSearchItem
import com.myvideolibrary.app.provider.model.ResolvedVideo
import com.myvideolibrary.app.provider.model.StreamSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * YouTube provider backed by NewPipeExtractor, with a public Piped API fallback.
 *
 * NewPipe reverse-engineers YouTube's player and periodically breaks when YouTube
 * changes. When that happens we fall back to a list of public Piped instances,
 * which expose the same streams through a stable JSON API. Two independent
 * backends make extraction far more resilient — the core purpose of this app.
 */
@Singleton
class YouTubeProvider @Inject constructor(
    private val client: OkHttpClient,
    private val gson: Gson
) : VideoProvider {

    override val source: VideoSource = VideoSource.YOUTUBE

    override fun canHandle(url: String): Boolean {
        val u = url.lowercase()
        return u.contains("youtube.com/watch") ||
            u.contains("youtu.be/") ||
            u.contains("youtube.com/shorts/") ||
            u.contains("m.youtube.com")
    }

    override suspend fun resolve(url: String): ResolvedVideo = withContext(Dispatchers.IO) {
        // Primary: NewPipe (best quality when it works).
        val viaNewPipe = runCatching { resolveViaNewPipe(url) }
        viaNewPipe.getOrNull()?.let { return@withContext it }

        // Fallback: Piped instances.
        resolveViaPiped(url)?.let { return@withContext it }

        // Both failed — surface the original NewPipe error (mapped).
        throw mapThrowable(viaNewPipe.exceptionOrNull())
    }

    override suspend fun resolveStream(url: String): StreamSource = withContext(Dispatchers.IO) {
        val viaNewPipe = runCatching { resolveStreamViaNewPipe(url) }
        viaNewPipe.getOrNull()?.let { return@withContext it }

        resolveStreamViaPiped(url)?.let { return@withContext it }

        throw mapThrowable(viaNewPipe.exceptionOrNull())
    }

    // ---- NewPipe backend ----

    private fun resolveViaNewPipe(url: String): ResolvedVideo {
        ensureInitialised()
        val info = StreamInfo.getInfo(ServiceList.YouTube, url)

        val bestMuxed = info.videoStreams
            .filter { !it.isVideoOnly && !it.content.isNullOrBlank() }
            .maxByOrNull { resolutionValue(it.getResolution()) }

        val bestVideoOnly = info.videoOnlyStreams
            .filter { !it.content.isNullOrBlank() && it.format == MediaFormat.MPEG_4 }
            .maxByOrNull { resolutionValue(it.getResolution()) }
        val bestAudio = info.audioStreams
            .filter { !it.content.isNullOrBlank() && it.format == MediaFormat.M4A }
            .maxByOrNull { it.averageBitrate }

        val muxedRes = bestMuxed?.let { resolutionValue(it.getResolution()) } ?: 0
        val hiRes = bestVideoOnly?.let { resolutionValue(it.getResolution()) } ?: 0

        return when {
            bestVideoOnly != null && bestAudio != null && hiRes >= muxedRes ->
                ResolvedVideo(
                    source = VideoSource.YOUTUBE,
                    sourceUrl = url,
                    title = info.name ?: "YouTube video",
                    directUrl = bestVideoOnly.content,
                    audioUrl = bestAudio.content,
                    thumbnailUrl = info.thumbnails.lastOrNull()?.url,
                    author = info.uploaderName,
                    durationMs = info.duration * 1000,
                    quality = bestVideoOnly.getResolution()
                )
            bestMuxed != null ->
                ResolvedVideo(
                    source = VideoSource.YOUTUBE,
                    sourceUrl = url,
                    title = info.name ?: "YouTube video",
                    directUrl = bestMuxed.content,
                    thumbnailUrl = info.thumbnails.lastOrNull()?.url,
                    author = info.uploaderName,
                    durationMs = info.duration * 1000,
                    quality = bestMuxed.getResolution()
                )
            else -> throw ProviderException(
                ProviderErrorType.EXTRACTION_FAILED, "No downloadable stream found"
            )
        }
    }

    private fun resolveStreamViaNewPipe(url: String): StreamSource {
        ensureInitialised()
        val info = StreamInfo.getInfo(ServiceList.YouTube, url)
        val muxed = info.videoStreams
            .filter { !it.isVideoOnly && !it.content.isNullOrBlank() }
            .maxByOrNull { resolutionValue(it.getResolution()) }
            ?: throw ProviderException(
                ProviderErrorType.EXTRACTION_FAILED, "No playable stream found"
            )
        return StreamSource(
            source = VideoSource.YOUTUBE,
            sourceUrl = url,
            title = info.name ?: "YouTube video",
            streamUrl = muxed.content,
            thumbnailUrl = info.thumbnails.lastOrNull()?.url
        )
    }

    // ---- Piped fallback ----

    /** Fetches the streams JSON from the first Piped instance that answers. */
    private fun fetchPiped(videoId: String): JsonObject? {
        for (base in PIPED_INSTANCES) {
            val json = runCatching {
                val req = Request.Builder()
                    .url("$base/streams/$videoId")
                    .header("User-Agent", UA)
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    resp.body?.string()
                }
            }.getOrNull() ?: continue
            val obj = runCatching { gson.fromJson(json, JsonObject::class.java) }.getOrNull()
                ?: continue
            // A usable response has at least one video stream.
            val count = obj.get("videoStreams")?.takeIf { it.isJsonArray }?.asJsonArray?.size() ?: 0
            if (count > 0) return obj
        }
        return null
    }

    private fun resolveViaPiped(url: String): ResolvedVideo? {
        val id = videoId(url) ?: return null
        val root = fetchPiped(id) ?: return null
        val videoStreams = root.arr("videoStreams")
        val audioStreams = root.arr("audioStreams")

        val bestVideoOnly = videoStreams
            .filter { it.bool("videoOnly") && it.str("format") == "MPEG_4" && !it.str("url").isNullOrBlank() }
            .maxByOrNull { pipedRes(it) }
        val bestAudio = audioStreams
            .filter { it.str("format") == "M4A" && !it.str("url").isNullOrBlank() }
            .maxByOrNull { it.int("bitrate") }
        val bestMuxed = videoStreams
            .filter { !it.bool("videoOnly") && !it.str("url").isNullOrBlank() }
            .maxByOrNull { pipedRes(it) }

        val title = root.str("title") ?: "YouTube video"
        val thumb = root.str("thumbnailUrl")
        val durationMs = (root.long("duration") ?: 0) * 1000

        return when {
            bestVideoOnly != null && bestAudio != null &&
                pipedRes(bestVideoOnly) >= (bestMuxed?.let { pipedRes(it) } ?: 0) ->
                ResolvedVideo(
                    source = VideoSource.YOUTUBE,
                    sourceUrl = url,
                    title = title,
                    directUrl = bestVideoOnly.str("url")!!,
                    audioUrl = bestAudio.str("url"),
                    thumbnailUrl = thumb,
                    durationMs = durationMs,
                    quality = bestVideoOnly.str("quality")
                )
            bestMuxed != null ->
                ResolvedVideo(
                    source = VideoSource.YOUTUBE,
                    sourceUrl = url,
                    title = title,
                    directUrl = bestMuxed.str("url")!!,
                    thumbnailUrl = thumb,
                    durationMs = durationMs,
                    quality = bestMuxed.str("quality")
                )
            else -> null
        }
    }

    private fun resolveStreamViaPiped(url: String): StreamSource? {
        val id = videoId(url) ?: return null
        val root = fetchPiped(id) ?: return null
        val muxed = root.arr("videoStreams")
            .filter { !it.bool("videoOnly") && !it.str("url").isNullOrBlank() }
            .maxByOrNull { pipedRes(it) } ?: return null
        return StreamSource(
            source = VideoSource.YOUTUBE,
            sourceUrl = url,
            title = root.str("title") ?: "YouTube video",
            streamUrl = muxed.str("url")!!,
            thumbnailUrl = root.str("thumbnailUrl")
        )
    }

    override suspend fun search(query: String): List<ProviderSearchItem> =
        withContext(Dispatchers.IO) {
            ensureInitialised()
            try {
                val extractor = ServiceList.YouTube.getSearchExtractor(query, emptyList(), "")
                extractor.fetchPage()
                extractor.initialPage.items
                    .filterIsInstance<org.schabi.newpipe.extractor.stream.StreamInfoItem>()
                    .map { item ->
                        ProviderSearchItem(
                            source = VideoSource.YOUTUBE,
                            url = item.url,
                            title = item.name ?: "",
                            thumbnailUrl = item.thumbnails.lastOrNull()?.url,
                            author = item.uploaderName,
                            durationMs = (item.duration.takeIf { it > 0 } ?: 0) * 1000
                        )
                    }
            } catch (e: Exception) {
                // Fall back to Piped search so keyword search still works if NewPipe breaks.
                searchViaPiped(query).ifEmpty {
                    throw ProviderException(
                        ProviderErrorType.EXTRACTION_FAILED, "YouTube search failed", e
                    )
                }
            }
        }

    private fun searchViaPiped(query: String): List<ProviderSearchItem> {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        for (base in PIPED_INSTANCES) {
            val body = runCatching {
                val req = Request.Builder()
                    .url("$base/search?q=$q&filter=videos")
                    .header("User-Agent", UA)
                    .build()
                client.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) return@use null
                    r.body?.string()
                }
            }.getOrNull() ?: continue
            val root = runCatching { gson.fromJson(body, JsonObject::class.java) }.getOrNull()
                ?: continue
            val items = root.get("items")?.takeIf { it.isJsonArray }?.asJsonArray ?: continue
            val mapped = items.mapNotNull { el ->
                val o = el.asJsonObject
                val itemUrl = o.str("url") ?: return@mapNotNull null
                if (!itemUrl.contains("/watch")) return@mapNotNull null
                val id = itemUrl.substringAfter("v=", "").ifBlank { return@mapNotNull null }
                ProviderSearchItem(
                    source = VideoSource.YOUTUBE,
                    url = "https://www.youtube.com/watch?v=$id",
                    title = o.str("title") ?: "",
                    thumbnailUrl = o.str("thumbnail"),
                    author = o.str("uploaderName"),
                    durationMs = (o.long("duration") ?: 0) * 1000
                )
            }
            if (mapped.isNotEmpty()) return mapped
        }
        return emptyList()
    }

    // ---- Helpers ----

    /** Extracts the 11-char video id from any YouTube URL form. */
    private fun videoId(url: String): String? {
        val patterns = listOf(
            Regex("""youtu\.be/([A-Za-z0-9_-]{11})"""),
            Regex("""[?&]v=([A-Za-z0-9_-]{11})"""),
            Regex("""youtube\.com/shorts/([A-Za-z0-9_-]{11})"""),
            Regex("""youtube\.com/embed/([A-Za-z0-9_-]{11})""")
        )
        for (p in patterns) p.find(url)?.groupValues?.get(1)?.let { return it }
        return null
    }

    private fun resolutionValue(resolution: String?): Int =
        resolution?.takeWhile(Char::isDigit)?.toIntOrNull() ?: 0

    private fun pipedRes(o: JsonObject): Int = resolutionValue(o.str("quality"))

    private fun mapThrowable(t: Throwable?): ProviderException = when (t) {
        is ProviderException -> t
        is ContentNotAvailableException ->
            ProviderException(ProviderErrorType.NOT_FOUND, "Video is unavailable", t)
        is IOException ->
            ProviderException(ProviderErrorType.NETWORK, "Network error", t)
        is ExtractionException ->
            ProviderException(
                ProviderErrorType.EXTRACTION_FAILED,
                "YouTube extraction failed (the extractor may need updating)", t
            )
        else -> ProviderException(
            ProviderErrorType.EXTRACTION_FAILED, "Could not extract the video", t
        )
    }

    private fun ensureInitialised() {
        synchronized(lock) {
            if (!initialised) {
                NewPipe.init(NewPipeDownloader(client), Localization.DEFAULT)
                initialised = true
            }
        }
    }

    private fun JsonObject.arr(name: String) =
        get(name)?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull {
            it.takeIf { e -> e.isJsonObject }?.asJsonObject
        } ?: emptyList()

    private fun JsonObject.str(name: String): String? =
        get(name)?.let { if (it.isJsonNull) null else runCatching { it.asString }.getOrNull() }

    private fun JsonObject.int(name: String): Int =
        get(name)?.let { if (it.isJsonNull) null else runCatching { it.asInt }.getOrNull() } ?: 0

    private fun JsonObject.long(name: String): Long? =
        get(name)?.let { if (it.isJsonNull) null else runCatching { it.asLong }.getOrNull() }

    private fun JsonObject.bool(name: String): Boolean =
        get(name)?.let { if (it.isJsonNull) false else runCatching { it.asBoolean }.getOrDefault(false) }
            ?: false

    companion object {
        private val lock = Any()
        @Volatile
        private var initialised = false

        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"

        /** Public Piped API instances, tried in order until one answers. */
        private val PIPED_INSTANCES = listOf(
            "https://api.piped.private.coffee",
            "https://pipedapi.kavin.rocks",
            "https://pipedapi.reallyaweso.me",
            "https://pipedapi.adminforge.de",
            "https://api.piped.yt",
            "https://pipedapi.nosebs.ru",
            "https://pipedapi.ducks.party",
            "https://pipedapi.darkness.services"
        )
    }
}
