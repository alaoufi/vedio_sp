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

    private fun setDataSource(retriever: MediaMetadataRetriever, source: String) {
        if (source.startsWith("content://")) {
            retriever.setDataSource(context, Uri.parse(source))
        } else {
            retriever.setDataSource(source)
        }
    }
}
