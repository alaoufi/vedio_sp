package com.myvideolibrary.app.download

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.myvideolibrary.app.data.local.entity.VideoEntity
import com.myvideolibrary.app.data.model.DownloadStatus
import com.myvideolibrary.app.data.repository.DownloadRepository
import com.myvideolibrary.app.data.repository.VideoRepository
import com.myvideolibrary.app.util.StorageManager
import com.myvideolibrary.app.util.ThumbnailGenerator
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import kotlin.coroutines.coroutineContext

/**
 * Downloads a single video file with resume support (HTTP Range), progress
 * reporting and a foreground notification. Cancellation (pause) leaves the partial
 * file in place so the next run resumes from where it stopped.
 */
@HiltWorker
class DownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val okHttpClient: OkHttpClient,
    private val downloadRepository: DownloadRepository,
    private val videoRepository: VideoRepository,
    private val storageManager: StorageManager,
    private val thumbnailGenerator: ThumbnailGenerator,
    private val notifier: DownloadNotifier
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val downloadId = inputData.getLong(KEY_DOWNLOAD_ID, -1)
        if (downloadId <= 0) return Result.failure()

        val download = downloadRepository.get(downloadId) ?: return Result.failure()
        val url = download.downloadUrl
        if (url.isNullOrBlank()) {
            downloadRepository.setStatus(
                downloadId, DownloadStatus.FAILED, "Missing download URL"
            )
            return Result.failure()
        }

        val notificationId = downloadId.toInt()
        val destFile = File(download.destPath ?: storageManager.newVideoFile("mp4").absolutePath)
        return try {
            downloadRepository.setStatus(downloadId, DownloadStatus.DOWNLOADING)
            // Best-effort foreground promotion. On Android 14/15 starting a
            // foreground service can throw; if so we keep going as a plain worker so
            // the download still runs instead of getting stuck in "Waiting".
            safeSetForeground(notificationId, download.title, 0, true, 0)

            val audioUrl = download.audioUrl
            val finished = if (audioUrl.isNullOrBlank()) {
                downloadFile(url, destFile, downloadId, download.title, notificationId)
            } else {
                downloadAndMux(
                    videoUrl = url,
                    audioUrl = audioUrl,
                    destFile = destFile,
                    downloadId = downloadId,
                    title = download.title,
                    notificationId = notificationId
                )
            }

            if (!finished) {
                // Interrupted (paused/stopped): keep partial data for resume.
                downloadRepository.setStatus(downloadId, DownloadStatus.PAUSED)
                return Result.success()
            }

            finalize(downloadId, download.title, destFile)
            notifier.showComplete(notificationId, download.title, true)
            Result.success(workDataOf(KEY_DOWNLOAD_ID to downloadId))
        } catch (e: Exception) {
            coroutineContext.ensureActive()
            val attempts = runAttemptCount
            if (attempts < MAX_ATTEMPTS) {
                downloadRepository.updateProgress(
                    downloadId, DownloadStatus.WAITING, download.progress,
                    download.downloadedBytes, download.totalBytes, 0
                )
                Result.retry()
            } else {
                downloadRepository.setStatus(
                    downloadId, DownloadStatus.FAILED, e.message ?: "Download failed"
                )
                notifier.showComplete(notificationId, download.title, false)
                Result.failure()
            }
        }
    }

    /** Returns true if the file finished, false if the run was stopped/paused. */
    private suspend fun downloadFile(
        url: String,
        destFile: File,
        downloadId: Long,
        title: String,
        notificationId: Int
    ): Boolean = withContext(Dispatchers.IO) {
        val existing = if (destFile.exists()) destFile.length() else 0L

        val requestBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", BROWSER_UA)
        // TikTok's CDN rejects requests without a matching Referer.
        if (isTikTokCdn(url)) {
            requestBuilder.header("Referer", "https://www.tiktok.com/")
        }
        if (existing > 0) requestBuilder.header("Range", "bytes=$existing-")
        val response = okHttpClient.newCall(requestBuilder.build()).execute()

        response.use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            val body = resp.body ?: throw IllegalStateException("Empty body")

            val partial = resp.code == 206 && existing > 0
            val reportedLength = body.contentLength().takeIf { it > 0 } ?: -1
            val total = if (partial && reportedLength > 0) existing + reportedLength else reportedLength

            val raf = RandomAccessFile(destFile, "rw")
            if (partial) raf.seek(existing) else raf.setLength(0)

            var downloaded = if (partial) existing else 0L
            val buffer = ByteArray(BUFFER_SIZE)
            val source = body.byteStream()

            var lastTick = 0L
            var lastBytes = downloaded
            var lastPercent = -1

            raf.use { file ->
                source.use { input ->
                    while (true) {
                        if (isStopped) return@withContext false
                        val read = input.read(buffer)
                        if (read == -1) break
                        file.write(buffer, 0, read)
                        downloaded += read

                        val now = System.currentTimeMillis()
                        if (now - lastTick >= PROGRESS_INTERVAL_MS) {
                            val speed = if (lastTick == 0L) 0
                            else (downloaded - lastBytes) * 1000 / (now - lastTick).coerceAtLeast(1)
                            val percent = if (total > 0) ((downloaded * 100) / total).toInt() else 0
                            if (percent != lastPercent) {
                                downloadRepository.updateProgress(
                                    downloadId, DownloadStatus.DOWNLOADING,
                                    percent, downloaded, total.coerceAtLeast(0), speed
                                )
                                safeSetForeground(notificationId, title, percent, total <= 0, speed)
                                lastPercent = percent
                            }
                            lastTick = now
                            lastBytes = downloaded
                        }
                    }
                }
            }

            downloadRepository.updateProgress(
                downloadId, DownloadStatus.DOWNLOADING, 100, downloaded,
                total.coerceAtLeast(downloaded), 0
            )
            true
        }
    }

    /**
     * Downloads a video-only and an audio-only stream, then muxes them into
     * [destFile]. Temp files are recreated per run (no cross-retry resume) to keep
     * the merge deterministic. Returns false if stopped/paused mid-way.
     */
    private suspend fun downloadAndMux(
        videoUrl: String,
        audioUrl: String,
        destFile: File,
        downloadId: Long,
        title: String,
        notificationId: Int
    ): Boolean = withContext(Dispatchers.IO) {
        val videoTmp = File(destFile.parentFile, destFile.name + ".video.part")
        val audioTmp = File(destFile.parentFile, destFile.name + ".audio.part")
        videoTmp.takeIf(File::exists)?.delete()
        audioTmp.takeIf(File::exists)?.delete()

        if (!downloadFile(videoUrl, videoTmp, downloadId, title, notificationId)) {
            return@withContext false
        }
        if (!downloadFile(audioUrl, audioTmp, downloadId, title, notificationId)) {
            return@withContext false
        }

        // Merge phase: show an indeterminate notification while muxing.
        safeSetForeground(notificationId, title, 100, true, 0)
        val merged = VideoMuxer.mux(videoTmp, audioTmp, destFile)
        videoTmp.delete()
        audioTmp.delete()
        if (!merged) throw IllegalStateException("Failed to merge video and audio")
        true
    }

    private suspend fun finalize(downloadId: Long, title: String, destFile: File) {
        val meta = thumbnailGenerator.readMetadata(destFile.absolutePath)
        val thumb = thumbnailGenerator.generateThumbnail(destFile.absolutePath)
        val download = downloadRepository.get(downloadId)

        val videoId = videoRepository.addVideo(
            VideoEntity(
                title = title,
                thumbnailPath = thumb,
                localPath = destFile.absolutePath,
                source = download?.source ?: "other",
                sourceUrl = download?.sourceUrl,
                duration = meta?.durationMs ?: 0,
                fileSize = destFile.length(),
                quality = meta?.qualityLabel,
                width = meta?.width ?: 0,
                height = meta?.height ?: 0,
                createdDate = System.currentTimeMillis(),
                contentHash = "${destFile.length()}_${meta?.durationMs ?: 0}"
            )
        )
        download?.let {
            downloadRepository.update(
                it.copy(
                    videoId = videoId,
                    status = DownloadStatus.COMPLETED.id,
                    progress = 100,
                    destPath = destFile.absolutePath,
                    errorMessage = null
                )
            )
        }
    }

    /**
     * Promotes the worker to a foreground service, swallowing the exceptions that
     * Android 14/15 can throw when a foreground service of type dataSync cannot be
     * started. The download continues either way.
     */
    private suspend fun safeSetForeground(
        notificationId: Int,
        title: String,
        progress: Int,
        indeterminate: Boolean,
        speed: Long
    ) {
        runCatching {
            setForeground(
                notifier.foregroundInfo(notificationId, title, progress, indeterminate, speed)
            )
        }
    }

    private fun isTikTokCdn(url: String): Boolean {
        val u = url.lowercase()
        return listOf("tiktok", "tiktokcdn", "byteicdn", "muscdn", "ibyteimg", "ttwstatic")
            .any { u.contains(it) }
    }

    companion object {
        const val KEY_DOWNLOAD_ID = "download_id"
        private const val BUFFER_SIZE = 64 * 1024
        private const val PROGRESS_INTERVAL_MS = 500L
        private const val MAX_ATTEMPTS = 3
        private const val BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    }
}
