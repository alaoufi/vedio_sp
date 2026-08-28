package com.myvideolibrary.app.data.repository

import com.myvideolibrary.app.data.local.dao.VideoDao
import com.myvideolibrary.app.data.local.entity.VideoEntity
import com.myvideolibrary.app.data.model.MediaType
import com.myvideolibrary.app.data.model.VideoSource
import com.myvideolibrary.app.util.StorageManager
import com.myvideolibrary.app.util.ThumbnailGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Rebuilds the library from the media files still on disk.
 *
 * The clips, downloads and imports are stored as real files under the app's private
 * folders. If the database is ever reset (e.g. an encryption-key failure that made
 * the library look empty) while an in-place update kept those files, this scans them
 * and re-adds any file that isn't already in the database — so the videos come back
 * even when their original metadata is gone. Recovered items get the file name as a
 * title and fresh metadata/thumbnails read from the file itself.
 */
@Singleton
class RecoveryManager @Inject constructor(
    private val videoDao: VideoDao,
    private val storageManager: StorageManager,
    private val thumbnailGenerator: ThumbnailGenerator
) {

    data class Result(val recovered: Int, val alreadyPresent: Int, val filesScanned: Int)

    suspend fun recoverFromStorage(): Result = withContext(Dispatchers.IO) {
        val known = videoDao.getAllOnce()
            .mapNotNull { it.localPath.takeIf { p -> p.isNotBlank() } }
            .toHashSet()

        val files = listOf(storageManager.videosDir, storageManager.downloadsDir)
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile } }
            .filter { it.length() > 0 && mediaType(it.name) != null }

        var recovered = 0
        var already = 0
        for (file in files) {
            val path = file.absolutePath
            if (path in known) { already++; continue }
            val type = mediaType(file.name) ?: continue

            val meta = if (type != MediaType.IMAGE) {
                runCatching { thumbnailGenerator.readMetadata(path) }.getOrNull()
            } else null
            val thumb = when (type) {
                MediaType.VIDEO -> runCatching { thumbnailGenerator.generateThumbnail(path) }.getOrNull()
                MediaType.IMAGE -> path
                else -> null
            }

            val entity = VideoEntity(
                title = file.nameWithoutExtension.ifBlank { file.name },
                localPath = path,
                source = VideoSource.LOCAL_IMPORT.id,
                mediaType = type.id,
                thumbnailPath = thumb,
                fileSize = file.length(),
                duration = meta?.durationMs ?: 0L,
                width = meta?.width ?: 0,
                height = meta?.height ?: 0,
                quality = meta?.qualityLabel,
                createdDate = file.lastModified().takeIf { it > 0 } ?: System.currentTimeMillis()
            )
            if (runCatching { videoDao.insert(entity) }.isSuccess) recovered++
        }
        Result(recovered = recovered, alreadyPresent = already, filesScanned = files.size)
    }

    private fun mediaType(name: String): MediaType? =
        when (name.substringAfterLast('.', "").lowercase()) {
            "mp4", "mkv", "webm", "mov", "avi", "3gp", "m4v", "ts", "flv" -> MediaType.VIDEO
            "m4a", "mp3", "aac", "opus", "ogg", "oga", "wav", "flac" -> MediaType.AUDIO
            "jpg", "jpeg", "png", "webp", "gif", "bmp" -> MediaType.IMAGE
            else -> null
        }
}
