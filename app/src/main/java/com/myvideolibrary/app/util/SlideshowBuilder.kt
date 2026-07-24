package com.myvideolibrary.app.util

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

/**
 * Builds a playable video from a set of still images plus a background audio
 * track — the on-device equivalent of a TikTok "photo mode" slideshow, whose
 * only real media are the pictures and the music. Everything runs locally via
 * Media3 Transformer; no server is involved.
 *
 * Must be driven from a thread that owns a Looper (call it on the main thread);
 * the encoding itself runs on Transformer's own threads.
 */
@OptIn(UnstableApi::class)
object SlideshowBuilder {

    private const val OUT_W = 1080
    private const val OUT_H = 1920
    private const val FPS = 30

    /**
     * @param images the slideshow pictures, in order
     * @param audio background music, or null for a silent slideshow
     * @param perImageMs how long each picture is shown
     * @return true on success (a video was written to [output])
     */
    suspend fun build(
        context: Context,
        images: List<File>,
        audio: File?,
        output: File,
        perImageMs: Long = 2500,
        onProgress: (Int) -> Unit
    ): Boolean = suspendCancellableCoroutine { cont ->
        if (images.isEmpty()) { cont.resume(false); return@suspendCancellableCoroutine }
        val handler = Handler(Looper.getMainLooper())

        // Each picture, normalised to a common portrait canvas so a mix of sizes
        // exports cleanly (letterboxed, nothing cropped away).
        val fit = Presentation.createForWidthAndHeight(
            OUT_W, OUT_H, Presentation.LAYOUT_SCALE_TO_FIT
        )
        val imageItems = images.map { file ->
            val mediaItem = MediaItem.Builder()
                .setUri(Uri.fromFile(file))
                .setImageDurationMs(perImageMs)
                .build()
            EditedMediaItem.Builder(mediaItem)
                .setFrameRate(FPS)
                .setEffects(Effects(emptyList(), listOf(fit)))
                .build()
        }
        val videoSequence = EditedMediaItemSequence(imageItems)

        val sequences = mutableListOf(videoSequence)
        if (audio != null && audio.exists() && audio.length() > 0) {
            val audioItem = EditedMediaItem.Builder(
                MediaItem.fromUri(Uri.fromFile(audio))
            ).build()
            // isLooping = true → the music repeats to cover the whole slideshow.
            sequences.add(EditedMediaItemSequence(listOf(audioItem), true))
        }

        val composition = Composition.Builder(sequences).build()

        val transformer = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, result: ExportResult) {
                    if (cont.isActive) cont.resume(output.exists() && output.length() > 0)
                }

                override fun onError(
                    composition: Composition,
                    result: ExportResult,
                    exception: ExportException
                ) {
                    if (cont.isActive) { output.delete(); cont.resume(false) }
                }
            })
            .build()

        val holder = ProgressHolder()
        val poll = object : Runnable {
            override fun run() {
                if (!cont.isActive) return
                if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    onProgress(holder.progress)
                }
                handler.postDelayed(this, 500)
            }
        }
        cont.invokeOnCancellation {
            handler.removeCallbacksAndMessages(null)
            runCatching { transformer.cancel() }
            output.delete()
        }

        runCatching { transformer.start(composition, output.absolutePath) }
            .onFailure {
                if (cont.isActive) { output.delete(); cont.resume(false) }
                return@suspendCancellableCoroutine
            }
        handler.postDelayed(poll, 500)
    }
}
