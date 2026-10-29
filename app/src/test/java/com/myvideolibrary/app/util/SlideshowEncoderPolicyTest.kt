package com.myvideolibrary.app.util

import org.junit.Assert.assertEquals
import org.junit.Test

class SlideshowEncoderPolicyTest {
    @Test fun `a second capable hardware encoder is tried before software`() {
        assertEquals(listOf("hardware-1", "hardware-2", "software", null),
            SlideshowEncoderPolicy.order(listOf("hardware-1", "hardware-2"), listOf("software")))
    }
    @Test fun `duplicate codecs are tried only once and platform default is last`() {
        assertEquals(listOf("hardware", "software", null),
            SlideshowEncoderPolicy.order(listOf("hardware", "hardware"), listOf("software", "hardware")))
    }
}
