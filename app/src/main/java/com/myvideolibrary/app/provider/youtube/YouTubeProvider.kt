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

            // Prefer a muxed (video+audio) stream so the result is a single
            // playable file with no post-processing.
            val stream = info.videoStreams
                .filter { !it.isVideoOnly && !it.content.isNullOrBlank() }
                .maxByOrNull { it.getResolution().filter(Char::isDigit).toIntOrNull() ?: 0 }
                ?: throw ProviderException(
                    ProviderErrorType.EXTRACTION_FAILED,
                    "No downloadable stream found"
                )

            ResolvedVideo(
                source = VideoSource.YOUTUBE,
                sourceUrl = url,
                title = info.name ?: "YouTube video",
                directUrl = stream.content,
                thumbnailUrl = info.thumbnails.lastOrNull()?.url,
                author = info.uploaderName,
                durationMs = info.duration * 1000,
                quality = stream.getResolution()
            )
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

    override suspend fun search(query: String): List<ProviderSearchItem> = emptyList()

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
