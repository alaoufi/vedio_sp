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
 * Frames are expected to already be uniform WxH JPEGs. Runs synchronously, so
 * call it from a background (IO) thread; MediaCodec here is in blocking mode and
 * needs no Looper.
 *
 * @return null on success, else a short error message.
 */
object SlideshowEncoder {

    private const val MIME = "video/avc"
    private const val W = 720
    private const val H = 1280
    private const val FPS = 24
    private const val BITRATE = 5_000_000
    private const val TIMEOUT_US = 10_000L

    fun encode(
        frames: List<File>,
        audio: File?,
        output: File,
        perImageMs: Long = 2500,
        onProgress: (Int) -> Unit = {}
    ): String? {
        if (frames.isEmpty()) return "no images"
        val totalFrames = (frames.size * maxOf(1, (perImageMs * FPS / 1000).toInt())).coerceAtLeast(1)

        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var audioExtractor: MediaExtractor? = null
        try {
            val format = MediaFormat.createVideoFormat(MIME, W, H).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
                )
                setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            // Prefer a SOFTWARE encoder: many hardware encoders (notably on
            // Xiaomi/MediaTek) stall on ByteBuffer YUV input, which is exactly the
            // "timed out" hang. Software encoders accept flexible YUV reliably.
            encoder = createAvcEncoder()
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            // Prepare the music track up front (AAC only) so it can be added to the
            // muxer before it starts. Anything else → silent video.
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

            val framesPerImage = maxOf(1, (perImageMs * FPS / 1000).toInt())
            var frameIndex = 0
            var repeats = 0
            var imgIdx = 0

            fun loadNext(): Bitmap? {
                while (imgIdx < frames.size) {
                    val bm = runCatching { BitmapFactory.decodeFile(frames[imgIdx].absolutePath) }
                        .getOrNull()
                    imgIdx++
                    if (bm != null) return bm
                }
                return null
            }
            var current: Bitmap? = loadNext()

            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val bm = current
                        if (bm == null) {
                            encoder.queueInputBuffer(
                                inIndex, 0, 0, ptsUs(frameIndex),
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            encoder.getInputImage(inIndex)?.let { fillImage(it, bm) }
                            encoder.queueInputBuffer(inIndex, 0, W * H * 3 / 2, ptsUs(frameIndex), 0)
                            frameIndex++
                            onProgress(frameIndex * 100 / totalFrames)
                            repeats++
                            if (repeats >= framesPerImage) {
                                bm.recycle()
                                current = loadNext()
                                repeats = 0
                            }
                        }
                    }
                }

                val outIndex = encoder.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        videoTrack = muxer.addTrack(encoder.outputFormat)
                        audioFormat?.let { audioDstTrack = muxer.addTrack(it) }
                        muxer.start()
                        muxerStarted = true
                    }
                    outIndex >= 0 -> {
                        val outBuf = encoder.getOutputBuffer(outIndex)
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && muxerStarted && outBuf != null) {
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            muxer.writeSampleData(videoTrack, outBuf, info)
                        }
                        encoder.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }

            if (frameIndex == 0) return "no frames encoded"

            val videoDurationUs = frameIndex.toLong() * 1_000_000L / FPS
            val ex = audioExtractor
            if (muxerStarted && ex != null && audioDstTrack >= 0) {
                writeAudioLooped(muxer, ex, audioSrcTrack, audioDstTrack, audioFormat, videoDurationUs)
            }
            return null
        } catch (e: Throwable) {
            output.delete()
            return "${e.javaClass.simpleName}: ${e.message}"
        } finally {
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { audioExtractor?.release() }
        }
    }

    /** Finds a software AVC encoder that accepts flexible YUV; falls back to any. */
    private fun createAvcEncoder(): MediaCodec {
        val flexible = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
        val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        for (info in infos) {
            if (!info.isEncoder) continue
            if (info.supportedTypes.none { it.equals(MIME, ignoreCase = true) }) continue
            val software = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                info.isSoftwareOnly
            } else {
                val n = info.name.lowercase()
                n.startsWith("omx.google") || n.startsWith("c2.android")
            }
            if (!software) continue
            val supportsFlexible = runCatching {
                info.getCapabilitiesForType(MIME).colorFormats.any { it == flexible }
            }.getOrDefault(false)
            if (supportsFlexible) return MediaCodec.createByCodecName(info.name)
        }
        return MediaCodec.createEncoderByType(MIME)
    }

    private fun ptsUs(frameIndex: Int): Long = frameIndex.toLong() * 1_000_000L / FPS

    /** Fills a YUV420-flexible input image from an ARGB bitmap (BT.601 full-range-ish). */
    private fun fillImage(image: Image, bitmap: Bitmap) {
        val pixels = IntArray(W * H)
        val bw = bitmap.width.coerceAtMost(W)
        val bh = bitmap.height.coerceAtMost(H)
        bitmap.getPixels(pixels, 0, W, 0, 0, bw, bh)

        val planes = image.planes
        val yBuf = planes[0].buffer; val yRow = planes[0].rowStride; val yPix = planes[0].pixelStride
        val uBuf = planes[1].buffer; val uRow = planes[1].rowStride; val uPix = planes[1].pixelStride
        val vBuf = planes[2].buffer; val vRow = planes[2].rowStride; val vPix = planes[2].pixelStride

        for (y in 0 until H) {
            for (x in 0 until W) {
                val c = pixels[y * W + x]
                val r = (c shr 16) and 0xff
                val g = (c shr 8) and 0xff
                val b = c and 0xff
                val yy = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yBuf.put(y * yRow + x * yPix, yy.coerceIn(0, 255).toByte())
                if (x and 1 == 0 && y and 1 == 0) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    val cx = x / 2
                    val cy = y / 2
                    uBuf.put(cy * uRow + cx * uPix, u.coerceIn(0, 255).toByte())
                    vBuf.put(cy * vRow + cx * vPix, v.coerceIn(0, 255).toByte())
                }
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
