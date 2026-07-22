package com.myvideolibrary.app.util

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * Losslessly trims a video to the range [startMs, endMs] by copying the already
 * compressed samples with [MediaMuxer] — no re-encoding, so there is no quality
 * loss ("distortion"). The start snaps back to the nearest keyframe, which is
 * unavoidable for a copy-only cut and imperceptible for removing an intro.
 */
object VideoTrimmer {

    private const val BUFFER_SIZE = 2 * 1024 * 1024

    /** @return true on success; the partial [output] is deleted on failure. */
    fun trim(input: File, output: File, startMs: Long, endMs: Long): Boolean {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        return try {
            extractor.setDataSource(input.absolutePath)

            // Add every audio/video track to the output and remember its mapping.
            val indexMap = HashMap<Int, Int>()
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    extractor.selectTrack(i)
                    indexMap[i] = muxer.addTrack(format)
                }
            }
            if (indexMap.isEmpty()) error("No copyable tracks")
            muxer.start()

            val startUs = startMs * 1000
            val endUs = endMs * 1000
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val firstUs = extractor.sampleTime.coerceAtLeast(0)

            val buffer = ByteBuffer.allocate(BUFFER_SIZE)
            val info = MediaCodec.BufferInfo()
            while (true) {
                val track = extractor.sampleTrackIndex
                if (track < 0) break
                val outTrack = indexMap[track]
                val sampleTime = extractor.sampleTime
                if (sampleTime > endUs) break
                if (outTrack != null && sampleTime >= 0) {
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    info.offset = 0
                    info.size = size
                    info.presentationTimeUs = (sampleTime - firstUs).coerceAtLeast(0)
                    info.flags = sampleFlagsToBufferFlags(extractor.sampleFlags)
                    muxer.writeSampleData(outTrack, buffer, info)
                }
                extractor.advance()
            }
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

    private fun sampleFlagsToBufferFlags(sampleFlags: Int): Int {
        var flags = 0
        if (sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
            flags = flags or MediaCodec.BUFFER_FLAG_KEY_FRAME
        }
        return flags
    }
}
