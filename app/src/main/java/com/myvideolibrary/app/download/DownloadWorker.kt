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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicLong
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

            val audioUrl = download.audioUrl
            val finished = if (audioUrl.isNullOrBlank()) {
                downloadSmart(url, destFile, downloadId, download.title)
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

    /**
     * Picks the fastest safe strategy: parallel segmented download when the server
     * advertises a total size and honours ranges (bypasses YouTube's per-connection
     * throttle by using several connections at once); otherwise the sequential
     * chunked download. Falls back to sequential if the parallel attempt errors.
     */
    private suspend fun downloadSmart(
        url: String,
        destFile: File,
        downloadId: Long,
        title: String
    ): Boolean {
        val total = runCatching { probeTotalSize(url) }.getOrNull()
        if (total != null && total > MIN_PARALLEL_SIZE) {
            try {
                return downloadParallel(url, destFile, downloadId, total)
            } catch (e: Exception) {
                if (isStopped) return false
                // Parallel failed: discard the sparse pre-allocated file and retry
                // sequentially so we never keep a half-written file.
                runCatching { destFile.delete() }
                runCatching { File(destFile.parentFile, destFile.name + PARTS_SUFFIX).delete() }
            }
        }
        return downloadFile(url, destFile, downloadId, title, 0)
    }

    /** Asks for one byte to learn the total size (only if the server returns 206). */
    private suspend fun probeTotalSize(url: String): Long? = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", BROWSER_UA)
            .header("Range", "bytes=0-0")
        if (isTikTokCdn(url)) builder.header("Referer", "https://www.tiktok.com/")
        okHttpClient.newCall(builder.build()).execute().use { resp ->
            if (resp.code == 206) parseContentRangeTotal(resp.header("Content-Range")) else null
        }
    }

    /**
     * Downloads [url] into a pre-allocated [destFile] using several parallel Range
     * segments. Completed segment offsets are recorded in a sidecar so a killed
     * worker resumes only the missing segments. Returns false if stopped/paused.
     */
    private suspend fun downloadParallel(
        url: String,
        destFile: File,
        downloadId: Long,
        total: Long
    ): Boolean = coroutineScope {
        val partsFile = File(destFile.parentFile, destFile.name + PARTS_SUFFIX)
        val reusable = destFile.exists() && destFile.length() == total
        if (!reusable) {
            RandomAccessFile(destFile, "rw").use { it.setLength(total) }
            runCatching { partsFile.delete() }
        }
        val done = (if (partsFile.exists()) {
            partsFile.readLines().mapNotNull { it.toLongOrNull() }
        } else emptyList()).toHashSet()

        val segments = ArrayList<Pair<Long, Long>>()
        var s = 0L
        while (s < total) {
            segments.add(s to minOf(s + SEGMENT_SIZE - 1, total - 1))
            s += SEGMENT_SIZE
        }

        val downloaded = AtomicLong(done.sumOf { minOf(SEGMENT_SIZE, total - it) })
        val partsMutex = Mutex()
        val sem = Semaphore(PARALLELISM)

        // Single reporter polls the shared counter so segments stay lock-free.
        val progressJob = launch(Dispatchers.IO) {
            var lastBytes = downloaded.get()
            var lastTime = System.currentTimeMillis()
            var lastPercent = -1
            while (isActive) {
                delay(PROGRESS_INTERVAL_MS)
                val d = downloaded.get()
                val now = System.currentTimeMillis()
                val speed = (d - lastBytes) * 1000 / (now - lastTime).coerceAtLeast(1)
                val percent = ((d * 100) / total).toInt().coerceIn(0, 100)
                if (percent != lastPercent) {
                    downloadRepository.updateProgress(
                        downloadId, DownloadStatus.DOWNLOADING, percent, d, total, speed
                    )
                    lastPercent = percent
                }
                lastBytes = d
                lastTime = now
            }
        }

        val results = segments.filter { it.first !in done }.map { (start, end) ->
            async(Dispatchers.IO) {
                sem.withPermit {
                    if (isStopped) return@withPermit false
                    fetchSegment(url, destFile, start, end) { downloaded.addAndGet(it.toLong()) }
                    if (isStopped) return@withPermit false
                    partsMutex.withLock { partsFile.appendText("$start\n") }
                    true
                }
            }
        }.awaitAll()

        progressJob.cancel()
        if (isStopped || results.any { it == false }) return@coroutineScope false

        runCatching { partsFile.delete() }
        downloadRepository.updateProgress(
            downloadId, DownloadStatus.DOWNLOADING, 100, total, total, 0
        )
        true
    }

    /** Downloads one byte range into [destFile] at its offset. Requires a 206. */
    private fun fetchSegment(
        url: String,
        destFile: File,
        start: Long,
        end: Long,
        onBytes: (Int) -> Unit
    ) {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", BROWSER_UA)
            .header("Range", "bytes=$start-$end")
        if (isTikTokCdn(url)) builder.header("Referer", "https://www.tiktok.com/")

        okHttpClient.newCall(builder.build()).execute().use { resp ->
            // Parallel writes require partial content; a 200 would overwrite the file.
            if (resp.code != 206) throw IllegalStateException("HTTP ${resp.code}")
            val body = resp.body ?: throw IllegalStateException("Empty body")
            RandomAccessFile(destFile, "rw").use { raf ->
                raf.seek(start)
                val buffer = ByteArray(BUFFER_SIZE)
                body.byteStream().use { input ->
                    while (true) {
                        if (isStopped) return
                        val read = input.read(buffer)
                        if (read == -1) break
                        raf.write(buffer, 0, read)
                        onBytes(read)
                    }
                }
            }
        }
    }

    /**
     * Downloads [url] into [destFile] in fixed-size **Range chunks**. YouTube (and
     * some CDNs) throttle a single long-lived connection; requesting the file in
     * chunks keeps each request near full speed. Resuming is automatic: it always
     * continues from the current file length, so a killed worker picks up where it
     * left off instead of restarting.
     *
     * @return true when finished, false if the run was stopped/paused.
     */
    private suspend fun downloadFile(
        url: String,
        destFile: File,
        downloadId: Long,
        title: String,
        notificationId: Int
    ): Boolean = withContext(Dispatchers.IO) {
        var downloaded = if (destFile.exists()) destFile.length() else 0L
        var total = -1L
        val raf = RandomAccessFile(destFile, "rw")
        raf.seek(downloaded)

        val buffer = ByteArray(BUFFER_SIZE)
        var lastTick = 0L
        var lastBytes = downloaded
        var lastPercent = -1

        try {
            while (total < 0 || downloaded < total) {
                if (isStopped) return@withContext false

                val rangeStart = downloaded
                val rangeEnd = rangeStart + CHUNK_SIZE - 1
                val builder = Request.Builder()
                    .url(url)
                    .header("User-Agent", BROWSER_UA)
                    .header("Range", "bytes=$rangeStart-$rangeEnd")
                if (isTikTokCdn(url)) builder.header("Referer", "https://www.tiktok.com/")

                val response = okHttpClient.newCall(builder.build()).execute()
                var streamedWhole = false
                var chunkBytes = 0L

                response.use { resp ->
                    when (resp.code) {
                        416 -> { total = downloaded; return@use } // past EOF: done
                        200 -> {
                            // Server ignored Range and is sending the whole file.
                            streamedWhole = true
                            raf.setLength(0); raf.seek(0); downloaded = 0
                            total = resp.body?.contentLength()?.takeIf { it > 0 } ?: -1
                        }
                        206 -> {
                            if (total < 0) {
                                total = parseContentRangeTotal(resp.header("Content-Range"))
                                    ?: resp.body?.contentLength()?.takeIf { it > 0 }
                                        ?.let { rangeStart + it } ?: -1
                            }
                        }
                        else -> throw IllegalStateException("HTTP ${resp.code}")
                    }

                    val input = (resp.body ?: throw IllegalStateException("Empty body")).byteStream()
                    input.use {
                        while (true) {
                            if (isStopped) return@withContext false
                            val read = input.read(buffer)
                            if (read == -1) break
                            raf.write(buffer, 0, read)
                            downloaded += read
                            chunkBytes += read

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
                                    lastPercent = percent
                                }
                                lastTick = now
                                lastBytes = downloaded
                            }
                        }
                    }
                }

                // End conditions: whole file already streamed, reached total, or the
                // last chunk came up short (EOF) when the total was never advertised.
                if (streamedWhole) break
                if (total in 0..downloaded) break
                if (chunkBytes < CHUNK_SIZE) { total = downloaded; break }
            }

            downloadRepository.updateProgress(
                downloadId, DownloadStatus.DOWNLOADING, 100, downloaded,
                total.coerceAtLeast(downloaded), 0
            )
            true
        } finally {
            runCatching { raf.close() }
        }
    }

    /** Parses the total size from a `Content-Range: bytes start-end/total` header. */
    private fun parseContentRangeTotal(header: String?): Long? {
        val slash = header?.lastIndexOf('/') ?: return null
        if (slash < 0) return null
        return header.substring(slash + 1).trim().toLongOrNull()
    }

    /**
     * Downloads a video-only and an audio-only stream, then muxes them into
     * [destFile]. Temp files are kept between runs so a stopped/killed worker
     * resumes each part instead of restarting. Returns false if stopped/paused.
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

        if (!downloadSmart(videoUrl, videoTmp, downloadId, title)) {
            return@withContext false
        }
        if (!downloadSmart(audioUrl, audioTmp, downloadId, title)) {
            return@withContext false
        }

        // Merge phase.
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

    private fun isTikTokCdn(url: String): Boolean {
        val u = url.lowercase()
        return listOf("tiktok", "tiktokcdn", "byteicdn", "muscdn", "ibyteimg", "ttwstatic")
            .any { u.contains(it) }
    }

    companion object {
        const val KEY_DOWNLOAD_ID = "download_id"
        private const val BUFFER_SIZE = 64 * 1024

        /** Per-request Range size for the sequential fallback path. */
        private const val CHUNK_SIZE = 8L * 1024 * 1024

        // Parallel download tuning: several connections defeat YouTube's
        // per-connection throttle, so the whole file arrives much faster.
        private const val SEGMENT_SIZE = 4L * 1024 * 1024
        private const val PARALLELISM = 8
        private const val MIN_PARALLEL_SIZE = 2L * 1024 * 1024
        private const val PARTS_SUFFIX = ".parts"

        private const val PROGRESS_INTERVAL_MS = 500L
        private const val MAX_ATTEMPTS = 3
        private const val BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    }
}
