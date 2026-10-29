package com.myvideolibrary.app.util

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Converts downloaded music (including MP3) to the AAC track MP4 requires. */
@OptIn(UnstableApi::class)
internal object SlideshowAudio {
    suspend fun prepare(context: Context, input: File, output: File) {
        withTimeout(3 * 60 * 1000L) {
            withContext(Dispatchers.Main.immediate) {
                var transformer: Transformer? = null
                var completed = false
                try {
                    suspendCancellableCoroutine<Unit> { continuation ->
                        transformer = Transformer.Builder(context)
                            .setAudioMimeType(MimeTypes.AUDIO_AAC)
                            .addListener(object : Transformer.Listener {
                                override fun onCompleted(composition: Composition, result: ExportResult) {
                                    completed = true
                                    if (continuation.isActive) continuation.resume(Unit)
                                }

                                override fun onError(composition: Composition, result: ExportResult, exception: ExportException) {
                                    if (continuation.isActive) continuation.resumeWithException(exception)
                                }
                            }).build()
                        val item = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(input)))
                            .setRemoveVideo(true).build()
                        try {
                            transformer!!.start(item, output.absolutePath)
                        } catch (error: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(error)
                        }
                    }
                } finally {
                    // Transformer belongs to the main Looper, including cancellation.
                    withContext(NonCancellable + Dispatchers.Main.immediate) {
                        if (!completed) transformer?.cancel()
                    }
                }
            }
            withContext(Dispatchers.IO) {
                require(output.isFile && output.length() > 0) { "Audio conversion produced no file" }
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(output.absolutePath)
                    val track = (0 until extractor.trackCount).firstOrNull {
                        extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_AAC
                    } ?: error("Audio conversion produced no AAC track")
                    extractor.selectTrack(track)
                    require(extractor.sampleTime >= 0) { "Converted audio has no samples" }
                } finally { extractor.release() }
            }
        }
    }
}
