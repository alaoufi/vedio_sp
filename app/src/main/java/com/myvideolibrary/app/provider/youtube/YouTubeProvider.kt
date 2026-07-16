package com.myvideolibrary.app.provider.youtube

import com.myvideolibrary.app.data.model.VideoSource
import com.myvideolibrary.app.provider.VideoProvider
import com.myvideolibrary.app.provider.model.ProviderErrorType
import com.myvideolibrary.app.provider.model.ProviderException
import com.myvideolibrary.app.provider.model.ProviderSearchItem
import com.myvideolibrary.app.provider.model.ResolvedVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
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
 * YouTube provider backed by NewPipeExtractor.
 *
 * Extraction reverse-engineers YouTube's internal player and is inherently
 * fragile — it can break when YouTube changes, at which point only this module
 * (and the NewPipeExtractor dependency) needs updating.
 */
@Singleton
class YouTubeProvider @Inject constructor(
    private val client: OkHttpClient
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
        ensureInitialised()
        try {
            val info = StreamInfo.getInfo(ServiceList.YouTube, url)

            // Best single-file (muxed) stream — always playable, but YouTube caps
            // these around 720p.
            val bestMuxed = info.videoStreams
                .filter { !it.isVideoOnly && !it.content.isNullOrBlank() }
                .maxByOrNull { resolutionValue(it.getResolution()) }

            // Best MP4/H.264 video-only stream (higher resolutions live here) plus
            // best M4A/AAC audio — both MP4-container so they mux without re-encoding.
            val bestVideoOnly = info.videoOnlyStreams
                .filter { !it.content.isNullOrBlank() && it.format == MediaFormat.MPEG_4 }
                .maxByOrNull { resolutionValue(it.getResolution()) }
            val bestAudio = info.audioStreams
                .filter { !it.content.isNullOrBlank() && it.format == MediaFormat.M4A }
                .maxByOrNull { it.averageBitrate }

            val muxedRes = bestMuxed?.let { resolutionValue(it.getResolution()) } ?: 0
            val hiRes = bestVideoOnly?.let { resolutionValue(it.getResolution()) } ?: 0

            val resolved = when {
                // Quality first: prefer the highest-resolution video-only (up to
                // 1080p H.264) + audio and merge them. Parallel download keeps this
                // fast, so we no longer trade quality for speed.
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
                // Otherwise the best single-file muxed stream.
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
                    ProviderErrorType.EXTRACTION_FAILED,
                    "No downloadable stream found"
                )
            }
            resolved
        } catch (e: ContentNotAvailableException) {
            throw ProviderException(ProviderErrorType.NOT_FOUND, "Video is unavailable", e)
        } catch (e: ExtractionException) {
            throw ProviderException(
                ProviderErrorType.EXTRACTION_FAILED,
                "YouTube extraction failed (the extractor may need updating)", e
            )
        } catch (e: IOException) {
            throw ProviderException(ProviderErrorType.NETWORK, "Network error", e)
        }
    }

    override suspend fun search(query: String): List<ProviderSearchItem> =
        withContext(Dispatchers.IO) {
            ensureInitialised()
            try {
                val extractor = ServiceList.YouTube.getSearchExtractor(
                    query, emptyList(), ""
                )
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
                throw ProviderException(
                    ProviderErrorType.EXTRACTION_FAILED,
                    "YouTube search failed", e
                )
            }
        }

    /** Extracts the numeric height from a resolution label like "1080p60" → 1080. */
    private fun resolutionValue(resolution: String?): Int =
        resolution?.takeWhile(Char::isDigit)?.toIntOrNull() ?: 0

    private fun ensureInitialised() {
        synchronized(lock) {
            if (!initialised) {
                NewPipe.init(NewPipeDownloader(client), Localization.DEFAULT)
                initialised = true
            }
        }
    }

    companion object {
        private val lock = Any()
        @Volatile
        private var initialised = false
    }
}
