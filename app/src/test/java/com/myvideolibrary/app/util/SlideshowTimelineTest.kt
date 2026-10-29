package com.myvideolibrary.app.util

import org.junit.Assert.*
import org.junit.Test

class SlideshowTimelineTest {
    @Test fun `single photo preserves the complete 164 second soundtrack`() {
        val timeline = SlideshowTimeline(1, 164_000_000L)
        assertEquals(164_000_000L, timeline.durationUs)
        assertEquals(1640, timeline.frameCount)
        assertEquals(0, timeline.imageIndex(timeline.frameCount - 1))
    }

    @Test fun `every sample has a regular timestamp until the exact end`() {
        val timeline = SlideshowTimeline(2, 10_000_000L)
        assertEquals(100, timeline.frameCount)
        for (i in 0 until timeline.frameCount) {
            assertEquals(i * 100_000L, timeline.presentationTimeUs(i))
            assertTrue(timeline.presentationTimeUs(i) < timeline.durationUs)
        }
    }

    @Test fun `pictures divide the soundtrack duration evenly`() {
        val timeline = SlideshowTimeline(4, 8_000_000L)
        assertEquals(0, timeline.imageIndex(0))
        assertEquals(0, timeline.imageIndex(19))
        assertEquals(1, timeline.imageIndex(20))
        assertEquals(3, timeline.imageIndex(79))
    }

    @Test fun `post without music preserves each picture duration without an extra hold`() {
        val timeline = SlideshowTimeline(3, null)
        assertEquals(7_500_000L, timeline.durationUs)
        assertEquals(75, timeline.frameCount)
    }

    @Test fun `fractional final frame never extends the requested duration`() {
        val timeline = SlideshowTimeline(1, 1_250_000L)
        assertEquals(1_250_000L, timeline.durationUs)
        assertEquals(13, timeline.frameCount)
        assertEquals(1_200_000L, timeline.presentationTimeUs(12))
    }
}
