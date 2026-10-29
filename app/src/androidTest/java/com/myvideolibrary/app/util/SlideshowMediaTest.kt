package com.myvideolibrary.app.util

import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaExtractor
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer

@RunWith(AndroidJUnit4::class)
class SlideshowMediaTest {
    @Test fun mp3PhotoExportsCompletePlayableVideoWithSound(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val dir = File(context.cacheDir, "slideshow-test-${System.nanoTime()}").apply { mkdirs() }
        try {
            val mp3 = File(dir, "download.audio")
            instrumentation.context.assets.open("tone.mp3").use { source -> mp3.outputStream().use { source.copyTo(it) } }
            val aac = File(dir, "tone.m4a")
            SlideshowAudio.prepare(context, mp3, aac)
            val image = File(dir, "frame.jpg")
            val bitmap = Bitmap.createBitmap(720, 1280, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.BLUE)
            image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            bitmap.recycle()
            val video = File(dir, "result.mp4")
            val error = withContext(Dispatchers.IO) { SlideshowEncoder.encode(listOf(image), aac, video) }
            assertNull(error, error)

            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(video.absolutePath)
                var videoTrack = -1
                var audioTrack = -1
                for (i in 0 until extractor.trackCount) {
                    val f = extractor.getTrackFormat(i)
                    when (f.getString(MediaFormat.KEY_MIME)) {
                        MediaFormat.MIMETYPE_VIDEO_AVC -> videoTrack = i
                        MediaFormat.MIMETYPE_AUDIO_AAC -> audioTrack = i
                    }
                }
                assertTrue("Missing H264 video", videoTrack >= 0)
                assertTrue("Missing AAC music", audioTrack >= 0)
                val duration = extractor.getTrackFormat(videoTrack).getLong(MediaFormat.KEY_DURATION)
                assertTrue("Incomplete or extended video: $duration", duration in 163_900_000L..164_300_000L)
                val audioDuration = extractor.getTrackFormat(audioTrack).getLong(MediaFormat.KEY_DURATION)
                assertTrue("Incomplete soundtrack: $audioDuration", audioDuration in 163_900_000L..164_300_000L)
                extractor.selectTrack(videoTrack)
                var previous = -1L
                var count = 0
                while (extractor.sampleTime >= 0) {
                    val time = extractor.sampleTime
                    if (previous >= 0) assertTrue("Irregular video timestamp: $previous -> $time", time - previous in 1..150_000L)
                    previous = time
                    count++
                    extractor.advance()
                }
                assertTrue("Too few video samples: $count", count >= 1639)
                extractor.unselectTrack(videoTrack)
                extractor.selectTrack(audioTrack)
                extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                assertTrue("Empty audio track", extractor.readSampleData(ByteBuffer.allocate(65536), 0) > 0)
                assertAudible(extractor, audioTrack)
            } finally { extractor.release() }
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(video.absolutePath)
                val middle = retriever.getFrameAtTime(82_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                assertNotNull("Cannot seek/decode the video midpoint", middle)
                middle?.recycle()
                val ending = retriever.getFrameAtTime(163_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                assertNotNull("Cannot seek/decode the video ending", ending)
                ending?.recycle()
            } finally { retriever.release() }
        } finally { dir.deleteRecursively() }
    }

    private fun assertAudible(extractor: MediaExtractor, track: Int) {
        extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        val format = extractor.getTrackFormat(track)
        val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        try {
            decoder.configure(format, null, null, 0)
            decoder.start()
            val info = MediaCodec.BufferInfo()
            val deadline = android.os.SystemClock.elapsedRealtime() + 20_000
            var audible = false
            while (!audible && android.os.SystemClock.elapsedRealtime() < deadline) {
                val inputIndex = decoder.dequeueInputBuffer(10_000)
                if (inputIndex >= 0) {
                    val input = decoder.getInputBuffer(inputIndex)!!
                    val size = extractor.readSampleData(input, 0)
                    if (size < 0) break
                    decoder.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                    extractor.advance()
                }
                val outputIndex = decoder.dequeueOutputBuffer(info, 10_000)
                if (outputIndex >= 0) {
                    val pcm = decoder.getOutputBuffer(outputIndex)!!.order(java.nio.ByteOrder.nativeOrder())
                    var offset = info.offset
                    while (offset + 2 <= info.offset + info.size) {
                        if (kotlin.math.abs(pcm.getShort(offset).toInt()) > 100) audible = true
                        offset += 2
                    }
                    decoder.releaseOutputBuffer(outputIndex, false)
                }
            }
            assertTrue("AAC track decodes to silence", audible)
        } finally {
            runCatching { decoder.stop() }
            decoder.release()
        }
    }
}
