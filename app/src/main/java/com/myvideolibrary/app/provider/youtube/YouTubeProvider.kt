package com.myvideolibrary.app.provider.youtube

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.myvideolibrary.app.data.model.VideoSource
import com.myvideolibrary.app.provider.VideoProvider
import com.myvideolibrary.app.provider.model.ProviderErrorType
import com.myvideolibrary.app.provider.model.ProviderException
import com.myvideolibrary.app.provider.model.ProviderFeedPage
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

    private fun mapStreamItem(item: org.schabi.newpipe.extractor.stream.StreamInfoItem) =
        ProviderSearchItem(
            source = VideoSource.YOUTUBE,
            url = item.url,
            title = item.name ?: "",
            thumbnailUrl = item.thumbnails.lastOrNull()?.url,
            author = item.uploaderName,
            durationMs = (item.duration.takeIf { it > 0 } ?: 0) * 1000,
            isShort = runCatching { item.isShortFormContent }.getOrDefault(false)
        )

    /** Pulls several pages from a NewPipe extractor so the user sees many results. */
    private fun collectPages(
        extractor: org.schabi.newpipe.extractor.ListExtractor<out org.schabi.newpipe.extractor.InfoItem>
    ): List<ProviderSearchItem> {
        extractor.fetchPage()
        val out = ArrayList<ProviderSearchItem>()
        var page = extractor.initialPage
        var pages = 0
        while (true) {
            out += page.items.filterIsInstance<org.schabi.newpipe.extractor.stream.StreamInfoItem>()
                .map(::mapStreamItem)
            pages++
            if (out.size >= MAX_RESULTS || pages >= MAX_PAGES || !page.hasNextPage()) break
            page = runCatching { extractor.getPage(page.nextPage) }.getOrNull() ?: break
        }
        return out
    }

    override suspend fun search(query: String): List<ProviderSearchItem> =
        withContext(Dispatchers.IO) {
            ensureInitialised()
            try {
                collectPages(ServiceList.YouTube.getSearchExtractor(query, emptyList(), ""))
            } catch (e: Exception) {
                // Fall back to Piped search so keyword search still works if NewPipe breaks.
                searchViaPiped(query).ifEmpty {
                    throw ProviderException(
                        ProviderErrorType.EXTRACTION_FAILED, "YouTube search failed", e
                    )
                }
            }
        }

    override suspend fun trending(): List<ProviderSearchItem> =
        withContext(Dispatchers.IO) {
            ensureInitialised()
            try {
                collectPages(ServiceList.YouTube.kioskList.defaultKioskExtractor)
            } catch (e: Exception) {
                trendingViaPiped()
            }
        }

    // ---- Infinite-scroll feed (paged) ----

    /**
     * Holds a live NewPipe list extractor and the token for its next page so
     * [feedMore] can continue exactly where [feed] stopped. Opaque to the UI.
     */
    private class NewPipeCont(
        val extractor: org.schabi.newpipe.extractor.ListExtractor<out org.schabi.newpipe.extractor.InfoItem>,
        val nextPage: org.schabi.newpipe.extractor.Page
    )

    override suspend fun feed(query: String?): ProviderFeedPage =
        withContext(Dispatchers.IO) {
            ensureInitialised()
            try {
                val extractor: org.schabi.newpipe.extractor.ListExtractor<out org.schabi.newpipe.extractor.InfoItem> =
                    if (query.isNullOrBlank()) {
                        ServiceList.YouTube.kioskList.defaultKioskExtractor
                    } else {
                        ServiceList.YouTube.getSearchExtractor(query, emptyList(), "")
                    }
                extractor.fetchPage()
                pageToFeed(extractor, extractor.initialPage)
            } catch (e: Exception) {
                // NewPipe broke — fall back to a single Piped page (no continuation).
                val items = if (query.isNullOrBlank()) trendingViaPiped() else searchViaPiped(query)
                if (items.isEmpty() && !query.isNullOrBlank()) {
                    throw ProviderException(
                        ProviderErrorType.EXTRACTION_FAILED, "YouTube search failed", e
                    )
                }
                ProviderFeedPage(items, null)
            }
        }

    override suspend fun feedMore(continuation: Any?): ProviderFeedPage =
        withContext(Dispatchers.IO) {
            val cont = continuation as? NewPipeCont
                ?: return@withContext ProviderFeedPage(emptyList(), null)
            ensureInitialised()
            try {
                val page = cont.extractor.getPage(cont.nextPage)
                pageToFeed(cont.extractor, page)
            } catch (e: Exception) {
                ProviderFeedPage(emptyList(), null)
            }
        }

    /** Maps one NewPipe page to a feed page, carrying a continuation when more remain. */
    private fun pageToFeed(
        extractor: org.schabi.newpipe.extractor.ListExtractor<out org.schabi.newpipe.extractor.InfoItem>,
        page: org.schabi.newpipe.extractor.ListExtractor.InfoItemsPage<out org.schabi.newpipe.extractor.InfoItem>
    ): ProviderFeedPage {
        val items = page.items
            .filterIsInstance<org.schabi.newpipe.extractor.stream.StreamInfoItem>()
            .map(::mapStreamItem)
        val nextPage = page.nextPage
        val next = if (page.hasNextPage() && nextPage != null) NewPipeCont(extractor, nextPage) else null
        return ProviderFeedPage(items, next)
    }

    // ---- Video detail + related ("up next") ----

    override suspend fun details(url: String): com.myvideolibrary.app.provider.model.ProviderVideoDetail? =
        withContext(Dispatchers.IO) {
            ensureInitialised()
            runCatching { detailsViaNewPipe(url) }.getOrNull()
                ?: detailsViaPiped(url)
        }

    private fun detailsViaNewPipe(url: String): com.myvideolibrary.app.provider.model.ProviderVideoDetail {
        val info = StreamInfo.getInfo(ServiceList.YouTube, url)
        val related = info.relatedItems
            .filterIsInstance<org.schabi.newpipe.extractor.stream.StreamInfoItem>()
            .map(::mapStreamItem)
        return com.myvideolibrary.app.provider.model.ProviderVideoDetail(
            source = VideoSource.YOUTUBE,
            url = url,
            title = info.name ?: "YouTube video",
            thumbnailUrl = info.thumbnails.lastOrNull()?.url,
            description = info.description?.content,
            author = info.uploaderName,
            channelUrl = info.uploaderUrl,
            channelAvatarUrl = info.uploaderAvatars.lastOrNull()?.url,
            subscriberCount = info.uploaderSubscriberCount,
            viewCount = info.viewCount,
            likeCount = info.likeCount,
            uploadDate = info.textualUploadDate,
            durationMs = info.duration * 1000,
            related = related
        )
    }

    private fun detailsViaPiped(url: String): com.myvideolibrary.app.provider.model.ProviderVideoDetail? {
        val id = videoId(url) ?: return null
        val root = fetchPiped(id) ?: return null
        val related = root.arr("relatedStreams").mapNotNull { o ->
            val itemUrl = o.str("url") ?: return@mapNotNull null
            if (!itemUrl.contains("/watch")) return@mapNotNull null
            val vid = itemUrl.substringAfter("v=", "").ifBlank { return@mapNotNull null }
            ProviderSearchItem(
                source = VideoSource.YOUTUBE,
                url = "https://www.youtube.com/watch?v=$vid",
                title = o.str("title") ?: "",
                thumbnailUrl = o.str("thumbnail"),
                author = o.str("uploaderName"),
                durationMs = (o.long("duration") ?: 0L) * 1000
            )
        }
        return com.myvideolibrary.app.provider.model.ProviderVideoDetail(
            source = VideoSource.YOUTUBE,
            url = url,
            title = root.str("title") ?: "YouTube video",
            thumbnailUrl = root.str("thumbnailUrl"),
            description = root.str("description"),
            author = root.str("uploader"),
            channelUrl = root.str("uploaderUrl")?.let { normaliseChannelUrl(it) },
            channelAvatarUrl = root.str("uploaderAvatar"),
            subscriberCount = root.long("uploaderSubscriberCount") ?: -1,
            viewCount = root.long("views") ?: -1,
            likeCount = root.long("likes") ?: -1,
            uploadDate = root.str("uploadDate"),
            durationMs = (root.long("duration") ?: 0L) * 1000,
            related = related
        )
    }

    // ---- Channel browsing ----

    private class ChannelCont(
        val extractor: org.schabi.newpipe.extractor.channel.ChannelExtractor,
        val tabExtractor: org.schabi.newpipe.extractor.channel.tabs.ChannelTabExtractor,
        val nextPage: org.schabi.newpipe.extractor.Page
    )

    override suspend fun channel(channelUrl: String): com.myvideolibrary.app.provider.model.ProviderChannelPage? =
        withContext(Dispatchers.IO) {
            ensureInitialised()
            runCatching { channelViaNewPipe(channelUrl) }.getOrNull()
        }

    override suspend fun channelMore(
        channelUrl: String,
        continuation: Any?
    ): com.myvideolibrary.app.provider.model.ProviderChannelPage? =
        withContext(Dispatchers.IO) {
            val cont = continuation as? ChannelCont ?: return@withContext null
            ensureInitialised()
            runCatching {
                val page = cont.tabExtractor.getPage(cont.nextPage)
                val items = page.items
                    .filterIsInstance<org.schabi.newpipe.extractor.stream.StreamInfoItem>()
                    .map(::mapStreamItem)
                val nextPage = page.nextPage
                val next = if (page.hasNextPage() && nextPage != null) {
                    ChannelCont(cont.extractor, cont.tabExtractor, nextPage)
                } else null
                com.myvideolibrary.app.provider.model.ProviderChannelPage(
                    name = cont.extractor.name ?: "",
                    avatarUrl = cont.extractor.avatars.lastOrNull()?.url,
                    bannerUrl = cont.extractor.banners.lastOrNull()?.url,
                    subscriberCount = cont.extractor.subscriberCount,
                    items = items,
                    continuation = next
                )
            }.getOrNull()
        }

    private fun channelViaNewPipe(channelUrl: String): com.myvideolibrary.app.provider.model.ProviderChannelPage {
        val extractor = ServiceList.YouTube.getChannelExtractor(channelUrl)
        extractor.fetchPage()
        // Pick the "Videos" tab (first tab that lists videos).
        val videosTab = extractor.tabs.firstOrNull {
            it.contentFilters.contains(org.schabi.newpipe.extractor.channel.tabs.ChannelTabs.VIDEOS)
        } ?: extractor.tabs.firstOrNull()
        var items = emptyList<ProviderSearchItem>()
        var next: ChannelCont? = null
        if (videosTab != null) {
            val tabExtractor = ServiceList.YouTube.getChannelTabExtractor(videosTab)
            tabExtractor.fetchPage()
            val page = tabExtractor.initialPage
            items = page.items
                .filterIsInstance<org.schabi.newpipe.extractor.stream.StreamInfoItem>()
                .map(::mapStreamItem)
            val nextPage = page.nextPage
            if (page.hasNextPage() && nextPage != null) {
                next = ChannelCont(extractor, tabExtractor, nextPage)
            }
        }
        return com.myvideolibrary.app.provider.model.ProviderChannelPage(
            name = extractor.name ?: "",
            avatarUrl = extractor.avatars.lastOrNull()?.url,
            bannerUrl = extractor.banners.lastOrNull()?.url,
            subscriberCount = extractor.subscriberCount,
            items = items,
            continuation = next
        )
    }

    /** Piped returns "/channel/UCxxxx"; make it a full YouTube URL NewPipe understands. */
    private fun normaliseChannelUrl(path: String): String =
        if (path.startsWith("http")) path else "https://www.youtube.com$path"

    private fun trendingViaPiped(): List<ProviderSearchItem> {
        for (base in PIPED_INSTANCES) {
            val body = runCatching {
                val req = Request.Builder()
                    .url("$base/trending?region=SA")
                    .header("User-Agent", UA)
                    .build()
                client.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) return@use null
                    r.body?.string()
                }
            }.getOrNull() ?: continue
            val arr = runCatching { gson.fromJson(body, com.google.gson.JsonArray::class.java) }
                .getOrNull() ?: continue
            val mapped = arr.mapNotNull { el ->
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
                    durationMs = (o.long("duration") ?: 0L) * 1000
                )
            }
            if (mapped.isNotEmpty()) return mapped
        }
        return emptyList()
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
                // Arabic region so trending/search reflect the Arab world, not the US.
                NewPipe.init(
                    NewPipeDownloader(client),
                    Localization("ar", "SA"),
                    org.schabi.newpipe.extractor.localization.ContentCountry("SA")
                )
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

        /** Upper bounds for multi-page fetching so search/trending feel full. */
        private const val MAX_RESULTS = 60
        private const val MAX_PAGES = 3

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
