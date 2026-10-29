package com.myvideolibrary.app.util

internal class SlideshowTimeline(
    private val imageCount: Int,
    audioDurationUs: Long?,
    perImageMs: Long = 2500
) {
    init { require(imageCount > 0 && perImageMs > 0) }
    val fps = 10
    val durationUs = audioDurationUs?.takeIf { it > 0 } ?: (imageCount.toLong() * perImageMs * 1000)
    private val frameDurationUs = 1_000_000L / fps
    val frameCount = ((durationUs + frameDurationUs - 1) / frameDurationUs).also {
        require(it in 1..Int.MAX_VALUE.toLong()) { "Post duration is too long" }
    }.toInt()
    fun presentationTimeUs(frame: Int): Long = frame * frameDurationUs
    fun imageIndex(frame: Int): Int =
        (presentationTimeUs(frame) * imageCount / durationUs).toInt().coerceIn(0, imageCount - 1)
}
