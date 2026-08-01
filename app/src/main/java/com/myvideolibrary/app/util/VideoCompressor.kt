package com.myvideolibrary.app.util

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

/**
 * Re-encodes a video to H.265/HEVC entirely on the device, cutting the file size
 * roughly 40–60% at a high, visually near-identical quality (no server involved).
 *
 * Video is already a compressed format, so a truly lossless pass saves almost
 * nothing; HEVC at a high bitrate is the practical "smaller, same-looking" option.
 * If the device can't encode HEVC — or the result isn't actually smaller — the
 * caller keeps the original untouched.
 *
 * Transformer must be driven from a thread that owns a Looper, so [compress] is
 * meant to be called from the main thread (e.g. an Activity's lifecycleScope);
 * the heavy encoding runs on Transformer's own internal threads.
 */
@OptIn(UnstableApi::class)
object VideoCompressor {

    /** Outcome of a compression attempt. */
    sealed interface Result {
        data class Success(val output: File, val originalBytes: Long, val newBytes: Long) : Result
        /** Encoding finished but produced no meaningful saving; [output] already deleted. */
        object NoGain : Result
        data class Failed(val reason: String?) : Result
    }

    suspend fun compress(
        context: Context,
        input: File,
        output: File,
        onProgress: (Int) -> Unit
    ): Result = suspendCancellableCoroutine { cont ->
        val handler = Handler(Looper.getMainLooper())
        val originalBytes = input.length()

        val transformer = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H265)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, result: ExportResult) {
                    if (!cont.isActive) return
                    val newBytes = output.length()
                    // Require a real saving (>3%), else the re-encode wasn't worth it.
                    if (newBytes in 1 until (originalBytes - originalBytes / 32)) {
                        cont.resume(Result.Success(output, originalBytes, newBytes))
                    } else {
                        output.delete()
                        cont.resume(Result.NoGain)
                    }
                }

                override fun onError(
                    composition: Composition,
                    result: ExportResult,
                    exception: ExportException
                ) {
                    if (!cont.isActive) return
                    output.delete()
                    cont.resume(Result.Failed(exception.message))
                }
            })
            .build()

        val edited = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(input))).build()

        // Poll progress until the coroutine completes.
        val holder = ProgressHolder()
        val poll = object : Runnable {
            override fun run() {
                if (!cont.isActive) return
                if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    onProgress(holder.progress)
                }
                handler.postDelayed(this, 400)
            }
        }

        cont.invokeOnCancellation {
            handler.removeCallbacksAndMessages(null)
            runCatching { transformer.cancel() }
            output.delete()
        }

        runCatching { transformer.start(edited, output.absolutePath) }
            .onFailure {
                if (cont.isActive) {
                    output.delete()
                    cont.resume(Result.Failed(it.message))
                }
                return@suspendCancellableCoroutine
            }
        handler.postDelayed(poll, 400)
    }
}
