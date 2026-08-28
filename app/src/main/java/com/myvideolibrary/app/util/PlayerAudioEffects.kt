package com.myvideolibrary.app.util

import android.media.audiofx.BassBoost
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.PresetReverb
import android.media.audiofx.Virtualizer

/**
 * Wraps Android's audio effects (bass boost, virtualizer/surround, reverb and an
 * extra loudness stage) and attaches them to the player's audio session. Every
 * effect is created defensively — an OEM that doesn't support one simply skips it
 * instead of crashing. Call [apply] to switch preset and [release] on teardown.
 */
class PlayerAudioEffects(private val audioSessionId: Int) {

    enum class Preset { NONE, BASS, SURROUND, HALL, CONCERT }

    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    private var reverb: PresetReverb? = null
    private var loudness: LoudnessEnhancer? = null

    var current: Preset = Preset.NONE
        private set

    /** Applies [preset], (re)building the underlying effects for the session. */
    fun apply(preset: Preset) {
        current = preset
        release()
        if (audioSessionId == 0) return
        when (preset) {
            Preset.NONE -> Unit
            Preset.BASS -> {
                bass(900)
                loud(400)
            }
            Preset.SURROUND -> {
                surround(1000)
                loud(300)
            }
            Preset.HALL -> {
                hall(PresetReverb.PRESET_LARGEHALL)
            }
            Preset.CONCERT -> {
                bass(700)
                surround(800)
                hall(PresetReverb.PRESET_MEDIUMHALL)
                loud(500)
            }
        }
    }

    private fun bass(strength: Int) = runCatching {
        bassBoost = BassBoost(PRIORITY, audioSessionId).apply {
            if (strengthSupported) setStrength(strength.toShort())
            enabled = true
        }
    }

    private fun surround(strength: Int) = runCatching {
        virtualizer = Virtualizer(PRIORITY, audioSessionId).apply {
            if (strengthSupported) setStrength(strength.toShort())
            enabled = true
        }
    }

    private fun hall(preset: Short) = runCatching {
        reverb = PresetReverb(PRIORITY, audioSessionId).apply {
            this.preset = preset
            enabled = true
        }
    }

    /** Extra perceived-loudness stage (millibels), on top of the PCM gain. */
    private fun loud(millibels: Int) = runCatching {
        loudness = LoudnessEnhancer(audioSessionId).apply {
            setTargetGain(millibels)
            enabled = true
        }
    }

    fun release() {
        runCatching { bassBoost?.release() }
        runCatching { virtualizer?.release() }
        runCatching { reverb?.release() }
        runCatching { loudness?.release() }
        bassBoost = null
        virtualizer = null
        reverb = null
        loudness = null
    }

    private companion object {
        const val PRIORITY = 1000
    }
}
