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
 * Each picture is converted to YUV exactly once (not per output frame) and the
 * frame rate is low, since the images are static — otherwise the encode is so
 * slow on weak devices that it looks like it hangs. Runs synchronously; call it
 * from a background thread (MediaCodec here is in blocking mode, no Looper).
 *
 * @return null on success, else a short error message.
 */
object SlideshowEncoder {

    private const val MIME = "video/avc"
    private const val W = 720
    private const val H = 1280
    // Static pictures don't need a high frame rate; a low one keeps the encode fast.
    private const val FPS = 6
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
        val framesPerImage = maxOf(1, (perImageMs * FPS / 1000).toInt())
        val totalFrames = (frames.size * framesPerImage).coerceAtLeast(1)

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
            encoder = createAvcEncoder()
            encoderName = runCatching { encoder!!.name }.getOrDefault("?")
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
                        if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
                            audioExtractor = ex; audioFormat = f; audioSrcTrack = i
                        }
                        break
                    }
                }
                if (audioExtractor == null) ex.release()
            }

            val info = MediaCodec.BufferInfo()
            var videoTrack = -1
            var audioDstTrack = -1
            var muxerStarted = false

            var frameIndex = 0
            var repeats = 0
            var imgIdx = 0

            fun loadNext(): Yuv? {
                while (imgIdx < frames.size) {
                    val bm = runCatching { BitmapFactory.decodeFile(frames[imgIdx].absolutePath) }
                        .getOrNull()
                    imgIdx++
                    if (bm != null) {
                        val yuv = bitmapToYuv(bm)
                        bm.recycle()
                        return yuv
                    }
                }
                return null
            }
            var current: Yuv? = loadNext()

            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = encoder!!.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val yuv = current
                        if (yuv == null) {
                            encoder!!.queueInputBuffer(
                                inIndex, 0, 0, ptsUs(frameIndex),
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            val image = encoder!!.getInputImage(inIndex)
                                ?: return "$encoderName: encoder gave no input image"
                            fillImage(image, yuv)
                            encoder!!.queueInputBuffer(inIndex, 0, W * H * 3 / 2, ptsUs(frameIndex), 0)
                            frameIndex++
                            stage?.set("in $frameIndex/$totalFrames")
                            onProgress(frameIndex * 100 / totalFrames)
                            repeats++
                            if (repeats >= framesPerImage) {
                                current = loadNext()
                                repeats = 0
                            }
                        }
                    }
                }

                val outIndex = encoder!!.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        videoTrack = muxer.addTrack(encoder!!.outputFormat)
                        audioFormat?.let { audioDstTrack = muxer.addTrack(it) }
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

            if (frameIndex == 0) return "$encoderName: no frames encoded"

            val videoDurationUs = frameIndex.toLong() * 1_000_000L / FPS
            val ex = audioExtractor
            if (muxerStarted && ex != null && audioDstTrack >= 0) {
                stage?.set("audio")
                writeAudioLooped(muxer, ex, audioSrcTrack, audioDstTrack, audioFormat, videoDurationUs)
            }
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

    /**
     * Picks an AVC encoder that accepts flexible YUV. Prefers the device's
     * HARDWARE encoder — on many phones the bundled software AVC encoder
     * (c2.android.avc.encoder) stalls and never produces output, which looked
     * like a "timed out" hang. Falls back to a software one, then to the
     * platform default.
     */
    private fun createAvcEncoder(): MediaCodec {
        val flexible = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
        val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos

        fun isSoftware(info: MediaCodecInfo): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.isSoftwareOnly
            else info.name.lowercase().let { it.startsWith("omx.google") || it.startsWith("c2.android") }

        fun candidate(wantSoftware: Boolean): String? = infos.firstOrNull { info ->
            info.isEncoder &&
                info.supportedTypes.any { it.equals(MIME, ignoreCase = true) } &&
                isSoftware(info) == wantSoftware &&
                runCatching {
                    info.getCapabilitiesForType(MIME).colorFormats.any { it == flexible }
                }.getOrDefault(false)
        }?.name

        val name = candidate(wantSoftware = false) ?: candidate(wantSoftware = true)
        return if (name != null) MediaCodec.createByCodecName(name)
        else MediaCodec.createEncoderByType(MIME)
    }

    private fun ptsUs(frameIndex: Int): Long = frameIndex.toLong() * 1_000_000L / FPS

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
        if (yPix == 1 && yRow == W) {
            yb.position(0); yb.put(yuv.y)
        } else {
            for (j in 0 until H) for (i in 0 until W) yb.put(j * yRow + i * yPix, yuv.y[j * W + i])
        }

        val ub = planes[1].buffer; val uRow = planes[1].rowStride; val uPix = planes[1].pixelStride
        for (j in 0 until ch) for (i in 0 until cw) ub.put(j * uRow + i * uPix, yuv.u[j * cw + i])

        val vb = planes[2].buffer; val vRow = planes[2].rowStride; val vPix = planes[2].pixelStride
        for (j in 0 until ch) for (i in 0 until cw) vb.put(j * vRow + i * vPix, yuv.v[j * cw + i])
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
