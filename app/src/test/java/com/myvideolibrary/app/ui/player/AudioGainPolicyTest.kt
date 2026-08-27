package com.myvideolibrary.app.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioGainPolicyTest {

    @Test
    fun `normal volume keeps player gain and no loudness boost`() {
        assertEquals(AudioGain(1f, 0), AudioGainPolicy.fromPercent(100))
    }

    @Test
    fun `boost range keeps player at one and maps remaining percent`() {
        assertEquals(AudioGain(1f, 10), AudioGainPolicy.fromPercent(101))
        assertEquals(AudioGain(1f, 1000), AudioGainPolicy.fromPercent(200))
    }

    @Test
    fun `values outside range are clamped`() {
        assertEquals(AudioGain(0f, 0), AudioGainPolicy.fromPercent(-8))
        assertEquals(AudioGain(1f, 1000), AudioGainPolicy.fromPercent(250))
    }
}
