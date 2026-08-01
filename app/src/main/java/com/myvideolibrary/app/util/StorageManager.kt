package com.myvideolibrary.app.util

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Central owner of the app's private on-disk layout. Everything lives under the
 * app-private files directory so data never leaks into shared/public storage.
 */
@Singleton
class StorageManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    val videosDir: File by lazy { subDir("videos") }
    val thumbnailsDir: File by lazy { subDir("thumbnails") }
    val downloadsDir: File by lazy { subDir("downloads") }
    val backupsDir: File by lazy { subDir("backups") }

    private fun subDir(name: String): File =
        File(context.filesDir, name).apply { if (!exists()) mkdirs() }

    /** A unique destination file for a newly downloaded/imported video. */
    fun newVideoFile(extension: String): File {
        val safeExt = extension.trim('.').ifEmpty { "mp4" }
        return File(videosDir, "vid_${System.currentTimeMillis()}_${randomSuffix()}.$safeExt")
    }

    fun newThumbnailFile(): File =
        File(thumbnailsDir, "thumb_${System.currentTimeMillis()}_${randomSuffix()}.jpg")

    /** Total bytes currently used by app-managed media. */
    fun usedBytes(): Long =
        listOf(videosDir, thumbnailsDir, downloadsDir).sumOf { dirSize(it) }

    private fun dirSize(dir: File): Long =
        dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun randomSuffix(): String =
        (100000..999999).random().toString()
}
