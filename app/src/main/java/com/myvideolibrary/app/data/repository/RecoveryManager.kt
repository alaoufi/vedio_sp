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
 * Rebuilds the library from the media files still on disk, and cleans up broken
 * entries.
 *
 * Clips are stored as real files under the app's private folders. If the database
 * is ever reset while an in-place update kept the files, this re-adds any *playable*
 * file that isn't already in the database. It also removes entries that can only
 * fail to play — a file that is gone, empty, or an interrupted parallel download
 * (a full-size but corrupt `.mp4` left next to a `.parts` marker). Recovered items
 * get the file name as a title and fresh metadata/thumbnails read from the file.
 */
@Singleton
class RecoveryManager @Inject constructor(
    private val videoDao: VideoDao,
    private val storageManager: StorageManager,
    private val thumbnailGenerator: ThumbnailGenerator
) {

    data class Result(
        val recovered: Int,
        val removedBroken: Int,
        val alreadyPresent: Int,
        val filesScanned: Int
    )

    suspend fun recoverFromStorage(): Result = withContext(Dispatchers.IO) {
        val removed = purgeBrokenEntries()

        val known = videoDao.getAllOnce()
            .mapNotNull { it.localPath.takeIf { p -> p.isNotBlank() } }
            .toHashSet()

        // Only completed clips live here; interrupted downloads are skipped below.
        val files = storageManager.videosDir.walkTopDown()
            .filter { it.isFile && it.length() > 0 && mediaType(it.name) != null && !isIncomplete(it) }
            .toList()

        var recovered = 0
        var already = 0
        for (file in files) {
            val path = file.absolutePath
            if (path in known) { already++; continue }
            val type = mediaType(file.name) ?: continue

            val meta = if (type != MediaType.IMAGE) {
                runCatching { thumbnailGenerator.readMetadata(path) }.getOrNull()
            } else null
            // A video/audio with no readable metadata or zero duration is corrupt or
            // incomplete — importing it would just reproduce "can't play this video".
            if (type != MediaType.IMAGE && (meta == null || meta.durationMs <= 0L)) continue

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
        Result(recovered = recovered, removedBroken = removed, alreadyPresent = already, filesScanned = files.size)
    }

    /**
     * Removes library rows that can only fail to play: a local file that is missing,
     * empty, or an interrupted parallel download (has a `.parts` sidecar). Leaves
     * link-only rows and non-file (content://) sources untouched, and deletes the
     * junk files themselves.
     */
    private suspend fun purgeBrokenEntries(): Int {
        val broken = videoDao.getAllOnce().filter { v ->
            if (v.isLinkOnly) return@filter false
            val path = v.localPath
            if (!path.startsWith("/")) return@filter false // content:// etc. — leave alone
            val file = File(path)
            !file.exists() || file.length() == 0L || File(path + PARTS_SUFFIX).exists()
        }
        if (broken.isEmpty()) return 0
        broken.forEach { v ->
            runCatching { File(v.localPath).delete() }
            runCatching { File(v.localPath + PARTS_SUFFIX).delete() }
        }
        videoDao.deleteByIds(broken.map { it.id })
        return broken.size
    }

    /** True when a full-size file is really an interrupted parallel download. */
    private fun isIncomplete(file: File): Boolean =
        File(file.absolutePath + PARTS_SUFFIX).exists()

    private fun mediaType(name: String): MediaType? =
        when (name.substringAfterLast('.', "").lowercase()) {
            "mp4", "mkv", "webm", "mov", "avi", "3gp", "m4v", "ts", "flv" -> MediaType.VIDEO
            "m4a", "mp3", "aac", "opus", "ogg", "oga", "wav", "flac" -> MediaType.AUDIO
            "jpg", "jpeg", "png", "webp", "gif", "bmp" -> MediaType.IMAGE
            else -> null
        }

    private companion object {
        const val PARTS_SUFFIX = ".parts"
    }
}
