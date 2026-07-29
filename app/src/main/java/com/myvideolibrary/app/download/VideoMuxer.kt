package com.myvideolibrary.app.download

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * Remuxes a separate video-only file and an audio-only file into a single MP4
 * **without re-encoding** (a copy of already-compressed samples), using the
 * platform [MediaMuxer]. Requires MP4-compatible codecs — H.264 video + AAC audio.
 */
object VideoMuxer {

    private const val BUFFER_SIZE = 1 * 1024 * 1024

    /**
     * @param onProgress optional 0..100 callback. Progress is derived from each
     *   track's sample presentation time; the video track (the bulk of the bytes)
     *   is weighted 0→90% and the audio track 90→100%.
     * @return true on success. On failure the partial [output] is deleted.
     */
    fun mux(
        videoFile: File,
        audioFile: File,
        output: File,
        onProgress: ((Int) -> Unit)? = null
    ): Boolean {
        var muxer: MediaMuxer? = null
        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        return try {
            videoExtractor.setDataSource(videoFile.absolutePath)
            audioExtractor.setDataSource(audioFile.absolutePath)

            val videoTrack = firstTrack(videoExtractor, "video/")
                ?: error("No video track")
            val audioTrack = firstTrack(audioExtractor, "audio/")
                ?: error("No audio track")

            val videoFormat = videoExtractor.getTrackFormat(videoTrack)
            val audioFormat = audioExtractor.getTrackFormat(audioTrack)
            videoExtractor.selectTrack(videoTrack)
            audioExtractor.selectTrack(audioTrack)

            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outVideo = muxer.addTrack(videoFormat)
            val outAudio = muxer.addTrack(audioFormat)
            muxer.start()

            copyTrack(videoExtractor, muxer, outVideo, durationUs(videoFormat), onProgress, 0, 90)
            copyTrack(audioExtractor, muxer, outAudio, durationUs(audioFormat), onProgress, 90, 10)

            muxer.stop()
            onProgress?.invoke(100)
            true
        } catch (e: Exception) {
            output.takeIf(File::exists)?.delete()
            false
        } finally {
            runCatching { videoExtractor.release() }
            runCatching { audioExtractor.release() }
            runCatching { muxer?.release() }
        }
    }

    /** Copies only the video track into [output] (drops audio). */
    fun stripAudio(input: File, output: File): Boolean = copySingleTrack(input, output, "video/")

    /** Copies only the audio track into [output] (an .m4a/AAC container). */
    fun extractAudio(input: File, output: File): Boolean = copySingleTrack(input, output, "audio/")

    private fun copySingleTrack(input: File, output: File, prefix: String): Boolean {
        var muxer: MediaMuxer? = null
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(input.absolutePath)
            val track = firstTrack(extractor, prefix) ?: error("No $prefix track")
            extractor.selectTrack(track)
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outTrack = muxer.addTrack(extractor.getTrackFormat(track))
            muxer.start()
            copyTrack(extractor, muxer, outTrack)
            muxer.stop()
            true
        } catch (e: Exception) {
            output.takeIf(File::exists)?.delete()
            false
        } finally {
            runCatching { extractor.release() }
            runCatching { muxer?.release() }
        }
    }

    private fun firstTrack(extractor: MediaExtractor, prefix: String): Int? {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(prefix)) return i
        }
        return null
    }

    private fun copyTrack(
        extractor: MediaExtractor,
        muxer: MediaMuxer,
        outTrack: Int,
        durationUs: Long = 0,
        onProgress: ((Int) -> Unit)? = null,
        base: Int = 0,
        span: Int = 0
    ) {
        val buffer = ByteBuffer.allocate(BUFFER_SIZE)
        val info = android.media.MediaCodec.BufferInfo()
        var lastPct = -1
        while (true) {
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            val sampleTime = extractor.sampleTime
            info.offset = 0
            info.size = size
            info.presentationTimeUs = sampleTime
            info.flags = sampleFlagsToBufferFlags(extractor.sampleFlags)
            muxer.writeSampleData(outTrack, buffer, info)
            if (onProgress != null && durationUs > 0 && span > 0 && sampleTime >= 0) {
                val pct = base + (sampleTime * span / durationUs).toInt().coerceIn(0, span)
                if (pct != lastPct) { onProgress(pct); lastPct = pct }
            }
            extractor.advance()
        }
    }

    /** Track duration in microseconds, or 0 when the format doesn't declare it. */
    private fun durationUs(format: MediaFormat): Long =
        if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0

    private fun sampleFlagsToBufferFlags(sampleFlags: Int): Int {
        var flags = 0
        if (sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
            flags = flags or android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME
        }
        return flags
    }
}
