package com.myvideolibrary.app.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import java.io.File
import java.nio.ByteBuffer

/**
 * Builds a slideshow video from still pictures (+ optional music) using only the
 * low-level Android codec stack — MediaCodec for H.264 and MediaMuxer for the
 * MP4 — with no Media3/Transformer and no OpenGL. This is the most portable path
 * and works where the Transformer image pipeline stalls.
 *
 * Each picture is converted to YUV once and reused at a regular frame cadence.
 * Runs synchronously; call it
 * from a background thread (MediaCodec here is in blocking mode, no Looper).
 *
 * @return null on success, else a short error message.
 */
object SlideshowEncoder {

    private const val MIME = "video/avc"
    private const val W = 720
    private const val H = 1280
    private const val FPS = 10
    private const val BITRATE = 4_000_000
    private const val TIMEOUT_US = 10_000L

    private class Yuv(val y: ByteArray, val u: ByteArray, val v: ByteArray)

    fun encode(
        frames: List<File>,
        audio: File?,
        output: File,
        perImageMs: Long = 2500,
        stage: java.util.concurrent.atomic.AtomicReference<String>? = null,
        onProgress: (Int) -> Unit = {}
    ): String? {
        if (frames.isEmpty()) return "no images"
        // Try each capable encoder in turn (hardware, then software, then the
        // platform default). A single flaky encoder was the main reason a photo
        // post fell back to a still image on many devices — this makes the video
        // build succeed far more often.
        var lastError: String? = null
        for (name in candidateEncoderNames()) {
            output.delete()
            val err = runCatching {
                encodeWith(name, frames, audio, output, perImageMs, stage, onProgress)
            }.getOrElse { "${it.javaClass.simpleName}: ${it.message}" }
            if (err == null) return null
            lastError = err
        }
        return lastError ?: "no usable encoder"
    }

    private fun encodeWith(
        encoderName0: String?,
        frames: List<File>,
        audio: File?,
        output: File,
        perImageMs: Long,
        stage: java.util.concurrent.atomic.AtomicReference<String>?,
        onProgress: (Int) -> Unit
    ): String? {
        val imageCount = frames.size

        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var audioExtractor: MediaExtractor? = null
        var encoderName = "?"
        try {
            stage?.set("configuring")
            val format = MediaFormat.createVideoFormat(MIME, W, H).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
                )
                setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            encoder = if (encoderName0 != null) MediaCodec.createByCodecName(encoderName0)
            else MediaCodec.createEncoderByType(MIME)
            encoderName = runCatching { encoder!!.name }.getOrDefault(encoderName0 ?: "?")
            stage?.set("configure($encoderName)")
            encoder!!.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            stage?.set("starting($encoderName)")
            encoder!!.start()
            stage?.set("started($encoderName)")

            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            var audioFormat: MediaFormat? = null
            var audioSrcTrack = -1
            if (audio != null && audio.exists() && audio.length() > 0) {
                val ex = MediaExtractor()
                ex.setDataSource(audio.absolutePath)
                for (i in 0 until ex.trackCount) {
                    val f = ex.getTrackFormat(i)
                    val mime = f.getString(MediaFormat.KEY_MIME).orEmpty()
                    if (mime.startsWith("audio/")) {
                        if (mime != MediaFormat.MIMETYPE_AUDIO_AAC) {
                            ex.release()
                            return "$encoderName: post audio must be converted to AAC"
                        }
                        audioExtractor = ex; audioFormat = f; audioSrcTrack = i
                        break
                    }
                }
                if (audioExtractor == null) {
                    ex.release()
                    return "$encoderName: no audio track in post music"
                }
            }

            val audioDurationUs = audioFormat?.takeIf { it.containsKey(MediaFormat.KEY_DURATION) }
                ?.getLong(MediaFormat.KEY_DURATION)?.takeIf { it > 0 }
            if (audio != null && audioDurationUs == null) return "$encoderName: missing audio duration"
            val timeline = SlideshowTimeline(imageCount, audioDurationUs, perImageMs)

            val info = MediaCodec.BufferInfo()
            var videoTrack = -1
            var audioDstTrack = -1
            var muxerStarted = false

            var samplesFed = 0
            var cachedImage = -1
            var cachedYuv: Yuv? = null

            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = encoder!!.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        if (samplesFed == timeline.frameCount) {
                            encoder!!.queueInputBuffer(inIndex, 0, 0, timeline.durationUs,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val imageIndex = timeline.imageIndex(samplesFed)
                            if (imageIndex != cachedImage) {
                                val bitmap = BitmapFactory.decodeFile(frames[imageIndex].absolutePath)
                                    ?: return "$encoderName: couldn't decode picture ${imageIndex + 1}"
                                try { cachedYuv = bitmapToYuv(bitmap) } finally { bitmap.recycle() }
                                cachedImage = imageIndex
                            }
                            val image = encoder!!.getInputImage(inIndex)
                                ?: return "$encoderName: encoder gave no input image"
                            fillImage(image, cachedYuv!!)
                            encoder!!.queueInputBuffer(
                                inIndex, 0, W * H * 3 / 2, timeline.presentationTimeUs(samplesFed), 0
                            )
                            samplesFed++
                            stage?.set("frame $samplesFed/${timeline.frameCount}")
                            onProgress(samplesFed * 100 / timeline.frameCount)
                        }
                    }
                }

                val outIndex = encoder!!.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        videoTrack = muxer.addTrack(encoder!!.outputFormat)
                        audioFormat?.let { audioDstTrack = muxer!!.addTrack(it) }
                        muxer.start()
                        muxerStarted = true
                        stage?.set("muxing")
                    }
                    outIndex >= 0 -> {
                        val outBuf = encoder!!.getOutputBuffer(outIndex)
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && muxerStarted && outBuf != null) {
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            muxer.writeSampleData(videoTrack, outBuf, info)
                        }
                        encoder!!.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }

            if (samplesFed != timeline.frameCount) return "$encoderName: incomplete video frames"

            val ex = audioExtractor
            if (muxerStarted && ex != null && audioDstTrack >= 0) {
                stage?.set("audio")
                writeAudioLooped(muxer, ex, audioSrcTrack, audioDstTrack, audioFormat, timeline.durationUs)
            }
            check(muxerStarted && videoTrack >= 0) { "No video track was produced" }
            stage?.set("finalizing")
            // Finalizing MP4 can fail; report that failure instead of swallowing it.
            muxer.stop()
            muxer.release()
            muxer = null
            verifyOutput(output, audio != null, timeline.durationUs)
            stage?.set("done")
            return null
        } catch (e: Throwable) {
            output.delete()
            return "$encoderName ${e.javaClass.simpleName}: ${e.message}"
        } finally {
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { audioExtractor?.release() }
        }
    }

    private fun verifyOutput(output: File, audioRequired: Boolean, expectedDurationUs: Long) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(output.absolutePath)
            var videoFound = false
            var audioFound = false
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/")) {
                    videoFound = true
                    check(format.containsKey(MediaFormat.KEY_DURATION)) { "Missing video duration" }
                    val duration = format.getLong(MediaFormat.KEY_DURATION)
                    check(kotlin.math.abs(duration - expectedDurationUs) <= 250_000L) { "Incomplete video duration" }
                }
                if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) audioFound = true
            }
            check(videoFound && (!audioRequired || audioFound)) { "Missing video or music track" }
        } finally { extractor.release() }
    }

    /**
     * AVC encoders to try, in order: the device HARDWARE encoder first (the bundled
     * software one stalls on some phones), then a software one, then the platform
     * default (null = createEncoderByType). Each is attempted until one produces a
     * video, so a single flaky encoder never forces a still-image fallback.
     */
    private fun candidateEncoderNames(): List<String?> {
        val flexible = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
        val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos

        fun isSoftware(info: MediaCodecInfo): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.isSoftwareOnly
            else info.name.lowercase().let { it.startsWith("omx.google") || it.startsWith("c2.android") }

        fun candidates(wantSoftware: Boolean): List<String> = infos.filter { info ->
            info.isEncoder &&
                info.supportedTypes.any { it.equals(MIME, ignoreCase = true) } &&
                isSoftware(info) == wantSoftware &&
                runCatching {
                    info.getCapabilitiesForType(MIME).colorFormats.any { it == flexible }
                }.getOrDefault(false)
        }.map { it.name }

        return SlideshowEncoderPolicy.order(candidates(false), candidates(true))
    }

    /** Converts an ARGB bitmap to packed I420 YUV once (BT.601), reused per frame. */
    private fun bitmapToYuv(bitmap: Bitmap): Yuv {
        val pixels = IntArray(W * H)
        val bw = bitmap.width.coerceAtMost(W)
        val bh = bitmap.height.coerceAtMost(H)
        bitmap.getPixels(pixels, 0, W, 0, 0, bw, bh)
        val cw = W / 2
        val ch = H / 2
        val y = ByteArray(W * H)
        val u = ByteArray(cw * ch)
        val v = ByteArray(cw * ch)
        for (j in 0 until H) {
            val rowBase = j * W
            for (i in 0 until W) {
                val c = pixels[rowBase + i]
                val r = (c shr 16) and 0xff
                val g = (c shr 8) and 0xff
                val b = c and 0xff
                y[rowBase + i] = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16)
                    .coerceIn(0, 255).toByte()
                if (i and 1 == 0 && j and 1 == 0) {
                    val ci = (j / 2) * cw + i / 2
                    u[ci] = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).coerceIn(0, 255).toByte()
                    v[ci] = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).coerceIn(0, 255).toByte()
                }
            }
        }
        return Yuv(y, u, v)
    }

    /** Copies cached YUV into the encoder's flexible input image, respecting strides. */
    private fun fillImage(image: Image, yuv: Yuv) {
        val planes = image.planes
        val cw = W / 2
        val ch = H / 2

        val yb = planes[0].buffer; val yRow = planes[0].rowStride; val yPix = planes[0].pixelStride
        if (yPix == 1) {
            if (yRow == W) {
                yb.position(0); yb.put(yuv.y)
            } else {
                for (j in 0 until H) { yb.position(j * yRow); yb.put(yuv.y, j * W, W) }
            }
        } else {
            for (j in 0 until H) for (i in 0 until W) yb.put(j * yRow + i * yPix, yuv.y[j * W + i])
        }

        fillChroma(planes[1], yuv.u, cw, ch)
        fillChroma(planes[2], yuv.v, cw, ch)
    }

    /**
     * Copies a chroma plane, choosing the fastest path the encoder's layout
     * allows: a per-row bulk copy for planar I420 (pixelStride == 1), else a
     * per-sample write for semi-planar (interleaved UV, pixelStride == 2).
     */
    private fun fillChroma(plane: Image.Plane, src: ByteArray, cw: Int, ch: Int) {
        val buf = plane.buffer
        val row = plane.rowStride
        val pix = plane.pixelStride
        if (pix == 1) {
            if (row == cw) {
                buf.position(0); buf.put(src)
            } else {
                for (j in 0 until ch) {
                    buf.position(j * row)
                    buf.put(src, j * cw, cw)
                }
            }
        } else {
            for (j in 0 until ch) {
                val base = j * row
                val srcBase = j * cw
                for (i in 0 until cw) buf.put(base + i * pix, src[srcBase + i])
            }
        }
    }

    private fun writeAudioLooped(
        muxer: MediaMuxer,
        extractor: MediaExtractor,
        srcTrack: Int,
        dstTrack: Int,
        audioFormat: MediaFormat?,
        videoDurationUs: Long
    ) {
        extractor.selectTrack(srcTrack)
        val loopLen = audioFormat?.takeIf { it.containsKey(MediaFormat.KEY_DURATION) }
            ?.getLong(MediaFormat.KEY_DURATION)?.takeIf { it > 0 } ?: videoDurationUs
        val buffer = ByteBuffer.allocate(256 * 1024)
        val info = MediaCodec.BufferInfo()
        var loopOffset = 0L
        while (loopOffset < videoDurationUs) {
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) {
                loopOffset += loopLen
                extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                continue
            }
            val pts = extractor.sampleTime + loopOffset
            if (pts >= videoDurationUs) break
            info.offset = 0
            info.size = size
            info.presentationTimeUs = pts
            info.flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                MediaCodec.BUFFER_FLAG_KEY_FRAME
            } else {
                0
            }
            muxer.writeSampleData(dstTrack, buffer, info)
            extractor.advance()
        }
    }
}
