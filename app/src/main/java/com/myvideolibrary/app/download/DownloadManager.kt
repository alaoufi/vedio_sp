package com.myvideolibrary.app.download

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.myvideolibrary.app.data.local.entity.DownloadEntity
import com.myvideolibrary.app.data.model.DownloadStatus
import com.myvideolibrary.app.data.repository.DownloadRepository
import com.myvideolibrary.app.data.repository.SettingsRepository
import com.myvideolibrary.app.util.StorageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates the download queue on top of WorkManager. Each download maps to a
 * unique work request keyed by its database id, so pause/resume/cancel operate on
 * a single, addressable job that survives process death.
 */
@Singleton
class DownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloadRepository: DownloadRepository,
    private val settingsRepository: SettingsRepository,
    private val storageManager: StorageManager
) {

    private val workManager get() = WorkManager.getInstance(context)

    /** Creates a download record and enqueues the work. Returns the download id. */
    suspend fun enqueue(
        title: String,
        source: String,
        sourceUrl: String,
        directUrl: String,
        audioUrl: String? = null,
        thumbnailUrl: String? = null,
        kind: com.myvideolibrary.app.data.model.DownloadKind =
            com.myvideolibrary.app.data.model.DownloadKind.FULL,
        imageUrls: List<String> = emptyList()
    ): Long {
        val ext = when (kind) {
            com.myvideolibrary.app.data.model.DownloadKind.AUDIO_ONLY -> "m4a"
            com.myvideolibrary.app.data.model.DownloadKind.IMAGE_ONLY -> "jpg"
            else -> "mp4"
        }
        val dest = storageManager.newVideoFile(ext).absolutePath
        val id = downloadRepository.create(
            DownloadEntity(
                title = title,
                source = source,
                sourceUrl = sourceUrl,
                downloadUrl = directUrl,
                audioUrl = audioUrl,
                thumbnailUrl = thumbnailUrl,
                imageUrls = imageUrls.takeIf { it.isNotEmpty() }?.joinToString("\n"),
                kind = kind.id,
                destPath = dest,
                status = DownloadStatus.WAITING.id,
                downloadDate = System.currentTimeMillis()
            )
        )
        startWork(id)
        return id
    }

    /**
     * Enqueues a resolved item, handling the special cases centrally: a photo
     * post is saved as an image, and a multi-image slideshow fans out into one
     * image download per picture so the whole post is saved.
     */
    suspend fun enqueueResolved(
        resolved: com.myvideolibrary.app.provider.model.ResolvedVideo,
        kind: com.myvideolibrary.app.data.model.DownloadKind =
            com.myvideolibrary.app.data.model.DownloadKind.FULL
    ): Long {
        val imageKind = com.myvideolibrary.app.data.model.DownloadKind.IMAGE_ONLY
        // A photo/slideshow post.
        if (resolved.isSlideshow && resolved.imageUrls.isNotEmpty()) {
            // A single-picture photo post is just an image: save it directly
            // instead of building a one-frame "slideshow" video on-device — the
            // MediaCodec encode is fragile and fails on many OEMs, which is why
            // these posts often didn't download at all.
            if (resolved.imageUrls.size == 1) {
                return enqueue(
                    title = resolved.title,
                    source = resolved.source.id,
                    sourceUrl = resolved.sourceUrl,
                    directUrl = resolved.imageUrls.first(),
                    thumbnailUrl = resolved.thumbnailUrl ?: resolved.imageUrls.first(),
                    kind = imageKind
                )
            }
            // Multiple pictures: rebuild the slideshow into one video (images + music).
            return enqueue(
                title = resolved.title,
                source = resolved.source.id,
                sourceUrl = resolved.sourceUrl,
                directUrl = resolved.imageUrls.first(),
                audioUrl = resolved.audioUrl,
                thumbnailUrl = resolved.thumbnailUrl,
                kind = com.myvideolibrary.app.data.model.DownloadKind.SLIDESHOW,
                imageUrls = resolved.imageUrls
            )
        }
        if (resolved.isImage && resolved.imageUrls.size > 1) {
            var lastId = 0L
            resolved.imageUrls.forEachIndexed { index, img ->
                lastId = enqueue(
                    title = "${resolved.title} (${index + 1})",
                    source = resolved.source.id,
                    sourceUrl = resolved.sourceUrl,
                    directUrl = img,
                    thumbnailUrl = img,
                    kind = imageKind
                )
            }
            return lastId
        }
        return enqueue(
            title = resolved.title,
            source = resolved.source.id,
            sourceUrl = resolved.sourceUrl,
            directUrl = resolved.directUrl,
            audioUrl = resolved.audioUrl,
            thumbnailUrl = resolved.thumbnailUrl,
            kind = if (resolved.isImage) imageKind else kind
        )
    }

    /** (Re)starts the worker for an existing download record. */
    suspend fun startWork(id: Long) {
        // Plain background worker, no constraints and no foreground service:
        // starting a dataSync foreground service crashed the app on Android 14/15,
        // and a network constraint left the job stuck in "Waiting" on restrictive
        // OEMs. WorkManager runs this immediately while the app is in the foreground.
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf(DownloadWorker.KEY_DOWNLOAD_ID to id))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .addTag(TAG_DOWNLOAD)
            .build()

        workManager.enqueueUniqueWork(workName(id), ExistingWorkPolicy.REPLACE, request)
    }

    suspend fun pause(id: Long) {
        workManager.cancelUniqueWork(workName(id))
        downloadRepository.setStatus(id, DownloadStatus.PAUSED)
    }

    suspend fun resume(id: Long) {
        downloadRepository.setStatus(id, DownloadStatus.WAITING)
        startWork(id)
    }

    suspend fun retry(id: Long) {
        downloadRepository.updateProgress(id, DownloadStatus.WAITING, 0, 0, 0, 0)
        startWork(id)
    }

    suspend fun cancel(id: Long) {
        workManager.cancelUniqueWork(workName(id))
        val download = downloadRepository.get(id)
        download?.destPath?.let { runCatching { File(it).takeIf(File::exists)?.delete() } }
        downloadRepository.setStatus(id, DownloadStatus.CANCELED)
    }

    suspend fun remove(download: DownloadEntity) {
        workManager.cancelUniqueWork(workName(download.id))
        download.destPath
            ?.takeIf { download.status != DownloadStatus.COMPLETED.id }
            ?.let { runCatching { File(it).takeIf(File::exists)?.delete() } }
        downloadRepository.delete(download)
    }

    private fun workName(id: Long): String = "$WORK_PREFIX$id"

    companion object {
        const val TAG_DOWNLOAD = "video_download"
        private const val WORK_PREFIX = "download_"
    }
}
