package com.myvideolibrary.app.download

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
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
        thumbnailUrl: String? = null
    ): Long {
        val dest = storageManager.newVideoFile("mp4").absolutePath
        val id = downloadRepository.create(
            DownloadEntity(
                title = title,
                source = source,
                sourceUrl = sourceUrl,
                downloadUrl = directUrl,
                audioUrl = audioUrl,
                thumbnailUrl = thumbnailUrl,
                destPath = dest,
                status = DownloadStatus.WAITING.id,
                downloadDate = System.currentTimeMillis()
            )
        )
        startWork(id)
        return id
    }

    /** (Re)starts the worker for an existing download record. */
    suspend fun startWork(id: Long) {
        val wifiOnly = settingsRepository.getSettings().wifiOnlyDownloads
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(
                if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
            )
            .build()

        // No setExpedited: expedited jobs have per-app quotas on Android 12+, and
        // once the quota is spent the job is deferred and can sit in ENQUEUED
        // ("Waiting") indefinitely. A normal foreground worker starts promptly.
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf(DownloadWorker.KEY_DOWNLOAD_ID to id))
            .setConstraints(constraints)
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
