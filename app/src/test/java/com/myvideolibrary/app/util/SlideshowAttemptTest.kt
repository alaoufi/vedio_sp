package com.myvideolibrary.app.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SlideshowAttemptTest {
    @Test fun `successful encoder returning null at done is success not timeout`() = runBlocking {
        assertNull(SlideshowAttempt.run(1000, { "done" }) { null })
    }

    @Test fun `encoder failure keeps its actual error`() = runBlocking {
        assertEquals("codec failed", SlideshowAttempt.run(1000, { "configure" }) { "codec failed" })
    }

    @Test fun `real deadline expiry reports the last stage`() = runBlocking {
        assertEquals("timed out @ muxing", SlideshowAttempt.run(20, { "muxing" }) {
            delay(1000)
            null
        })
    }

    @Test fun `external cancellation propagates instead of becoming a timeout`() {
        try {
            runBlocking { SlideshowAttempt.run(1000, { "encode" }) { throw CancellationException("cancelled") } }
            fail("Cancellation must propagate")
        } catch (expected: CancellationException) {
            assertEquals("cancelled", expected.message)
        }
    }
}
