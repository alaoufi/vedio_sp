package com.myvideolibrary.app.data.repository

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.myvideolibrary.app.data.local.dao.VideoDao
import com.myvideolibrary.app.data.local.entity.VideoEntity
import com.myvideolibrary.app.data.model.MediaType
import com.myvideolibrary.app.data.model.VideoSource
import com.myvideolibrary.app.util.StorageManager
import com.myvideolibrary.app.util.ThumbnailGenerator
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Rebuilds the library from the media files still on disk, and cleans up broken
 * entries.
 *
 * Files are re-added from two places: the user's chosen **save folder** (a SAF
 * tree — where finished downloads are copied under readable names) and the app's
 * private video/download folders. If the database is ever reset while the files
 * survive, this brings the clips back. It imports any file that isn't a leftover
 * interrupted download (a ".parts" marker); metadata/thumbnails are best-effort,
 * because MediaMetadataRetriever fails on perfectly playable files on some OEMs
 * (e.g. Huawei) and rejecting those would drop real videos. Same-size files are
 * imported once, so the internal + external copies of one clip don't duplicate.
 */
@Singleton
class RecoveryManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val videoDao: VideoDao,
    private val settingsRepository: SettingsRepository,
    private val storageManager: StorageManager,
    private val thumbnailGenerator: ThumbnailGenerator
) {

    data class Result(
        val recovered: Int,
        val removedBroken: Int,
        val alreadyPresent: Int,
        val filesScanned: Int,
        /** Total video rows in the database after this run — tells display-bug from loss. */
        val libraryCount: Int
    )

    suspend fun recoverFromStorage(): Result = withContext(Dispatchers.IO) {
        val removed = purgeBrokenEntries()
        val all = videoDao.getAllOnce()
        val known = all
            .mapNotNull { it.localPath.takeIf { p -> p.isNotBlank() } }
            .toHashSet()

        val importedSizes = HashSet<Long>()
        var recovered = 0
        var already = 0
        var scanned = 0

        suspend fun tryImport(localPath: String, name: String, size: Long, type: MediaType, lastModified: Long) {
            scanned++
            if (localPath in known) { already++; importedSizes.add(size); return }
            if (size in importedSizes) return // same clip from the other copy — skip
            val meta = if (type != MediaType.IMAGE) {
                runCatching { thumbnailGenerator.readMetadata(localPath) }.getOrNull()
            } else null
            val thumb = when (type) {
                MediaType.VIDEO -> runCatching { thumbnailGenerator.generateThumbnail(localPath) }.getOrNull()
                MediaType.IMAGE -> localPath
                else -> null
            }
            val entity = VideoEntity(
                title = name.substringBeforeLast('.').ifBlank { name },
                localPath = localPath,
                source = VideoSource.LOCAL_IMPORT.id,
                mediaType = type.id,
                thumbnailPath = thumb,
                fileSize = size,
                duration = meta?.durationMs ?: 0L,
                width = meta?.width ?: 0,
                height = meta?.height ?: 0,
                quality = meta?.qualityLabel,
                createdDate = lastModified.takeIf { it > 0 } ?: System.currentTimeMillis()
            )
            if (runCatching { videoDao.insert(entity) }.isSuccess) {
                recovered++
                importedSizes.add(size)
            }
        }

        // 1) The user's chosen save folder first — its copies have readable names.
        val treeUri = settingsRepository.getSettings().storagePath
        if (!treeUri.isNullOrBlank()) {
            runCatching {
                DocumentFile.fromTreeUri(context, Uri.parse(treeUri))?.listFiles()?.forEach { doc ->
                    val name = doc.name
                    val type = name?.let(::mediaType)
                    if (doc.isFile && type != null && doc.length() > 0) {
                        tryImport(doc.uri.toString(), name, doc.length(), type, doc.lastModified())
                    }
                }
            }
        }

        // 2) The app's private folders — skip interrupted downloads (.parts markers).
        listOf(storageManager.videosDir, storageManager.downloadsDir)
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile } }
            .filter { it.length() > 0 && mediaType(it.name) != null && !isIncomplete(it) }
            .forEach { file ->
                mediaType(file.name)?.let { type ->
                    tryImport(file.absolutePath, file.name, file.length(), type, file.lastModified())
                }
            }

        Result(
            recovered = recovered,
            removedBroken = removed,
            alreadyPresent = already,
            filesScanned = scanned,
            libraryCount = all.size + recovered
        )
    }

    /**
     * Removes library rows that can only fail to play: a local file that is missing,
     * empty, or an interrupted parallel download (has a ".parts" sidecar). Leaves
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
