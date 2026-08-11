package com.myvideolibrary.app.security

import org.junit.Assert.assertEquals
import org.junit.Test

class PinRetryPolicyTest {
    @Test
    fun `locks for thirty seconds after five failed attempts`() {
        assertEquals(0L, PinRetryPolicy.lockDurationMs(4))
        assertEquals(30_000L, PinRetryPolicy.lockDurationMs(5))
    }
}
