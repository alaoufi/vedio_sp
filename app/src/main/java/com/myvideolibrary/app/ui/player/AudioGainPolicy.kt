package com.myvideolibrary.app.ui.player

data class AudioGain(
    val playerVolume: Float,
    val loudnessGainMillibels: Int
)

object AudioGainPolicy {
    const val MIN_PERCENT = 0
    const val DEFAULT_PERCENT = 100
    const val MAX_PERCENT = 200
    private const val MILLIBELS_PER_BOOST_PERCENT = 10

    fun fromPercent(percent: Int): AudioGain {
        val value = percent.coerceIn(MIN_PERCENT, MAX_PERCENT)
        return if (value <= DEFAULT_PERCENT) {
            AudioGain(value / DEFAULT_PERCENT.toFloat(), 0)
        } else {
            AudioGain(1f, (value - DEFAULT_PERCENT) * MILLIBELS_PER_BOOST_PERCENT)
        }
    }
}
