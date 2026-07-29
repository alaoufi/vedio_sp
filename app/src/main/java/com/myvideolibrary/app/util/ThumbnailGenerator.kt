package com.myvideolibrary.app.util

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** Video metadata read from a media file. */
data class VideoMetadata(
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val bitrate: Long
) {
    /** A simple quality label derived from the vertical resolution. */
    val qualityLabel: String
        get() = when {
            height >= 2160 -> "4K"
            height >= 1440 -> "1440p"
            height >= 1080 -> "1080p"
            height >= 720 -> "720p"
            height >= 480 -> "480p"
            height > 0 -> "${height}p"
            else -> "—"
        }
}

/**
 * Extracts metadata and generates thumbnail images from video files. Heavy work
 * runs on [Dispatchers.IO]; callers should invoke from a coroutine.
 */
@Singleton
class ThumbnailGenerator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val storageManager: StorageManager
) {

    suspend fun readMetadata(source: String): VideoMetadata? = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            setDataSource(retriever, source)
            val duration = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val width = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            val height = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
            val bitrate = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                ?.toLongOrNull() ?: 0L
            VideoMetadata(duration, width, height, bitrate)
        } catch (e: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** Generates a JPEG thumbnail and returns its absolute path, or null on failure. */
    suspend fun generateThumbnail(
        source: String,
        atMs: Long = 1000
    ): String? = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            setDataSource(retriever, source)
            val frame: Bitmap = retriever.getFrameAtTime(
                atMs * 1000,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            ) ?: return@withContext null

            val outFile: File = storageManager.newThumbnailFile()
            FileOutputStream(outFile).use { out ->
                frame.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            frame.recycle()
            outFile.absolutePath
        } catch (e: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * Extracts up to [count] evenly-spaced, downscaled frames across the clip for
     * an animated "quick preview". Frames are sampled between 5% and 95% of the
     * duration to skip intros/black tails, decoded at [OPTION_CLOSEST_SYNC] for
     * speed, and scaled down so cycling them stays light on memory. Returns an
     * empty list on failure; the caller owns recycling the returned bitmaps.
     */
    suspend fun extractPreviewFrames(
        source: String,
        count: Int = 8,
        targetWidth: Int = 480
    ): List<Bitmap> = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        val frames = ArrayList<Bitmap>(count)
        try {
            setDataSource(retriever, source)
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            if (durationMs <= 0L) return@withContext emptyList()

            val startMs = (durationMs * 0.05).toLong()
            val endMs = (durationMs * 0.95).toLong()
            val span = (endMs - startMs).coerceAtLeast(1L)
            val steps = count.coerceAtLeast(1)
            for (i in 0 until steps) {
                val atMs = startMs + span * i / steps
                val frame: Bitmap? = if (android.os.Build.VERSION.SDK_INT >= 27) {
                    // Decode straight to a small bitmap — cheaper than full-size.
                    retriever.getScaledFrameAtTime(
                        atMs * 1000,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        targetWidth,
                        0 // 0 height = keep aspect ratio
                    )
                } else {
                    retriever.getFrameAtTime(
                        atMs * 1000,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                    )
                }
                if (frame != null) frames.add(frame)
            }
            frames
        } catch (e: Exception) {
            frames.forEach { runCatching { it.recycle() } }
            emptyList()
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun setDataSource(retriever: MediaMetadataRetriever, source: String) {
        if (source.startsWith("content://")) {
            retriever.setDataSource(context, Uri.parse(source))
        } else {
            retriever.setDataSource(source)
        }
    }
}
