package com.myvideolibrary.app.ui.player

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import androidx.media3.common.Player

data class PlayerAudioSettings(
    val volumePercent: Int = AudioGainPolicy.DEFAULT_PERCENT,
    val bassBoostEnabled: Boolean = false,
    val surroundEnabled: Boolean = false,
    val speechClarityEnabled: Boolean = false
)

/** Owns optional Android audio effects for one Media3 audio session. */
class PlayerAudioEffects {
    private var sessionId = Int.MIN_VALUE
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    private var equalizer: Equalizer? = null

    fun applyVolume(player: Player, settings: PlayerAudioSettings) {
        val gain = AudioGainPolicy.fromPercent(settings.volumePercent)
        player.volume = gain.playerVolume
    }

    fun applyToSession(player: Player, audioSessionId: Int, settings: PlayerAudioSettings) {
        applyVolume(player, settings)
        if (audioSessionId <= 0) return
        if (sessionId != audioSessionId) {
            releaseEffects()
            sessionId = audioSessionId
        }
        val gain = AudioGainPolicy.fromPercent(settings.volumePercent)
        configureLoudness(audioSessionId, gain.loudnessGainMillibels)
        configureBassBoost(audioSessionId, settings.bassBoostEnabled)
        configureSurround(audioSessionId, settings.surroundEnabled)
        configureSpeechClarity(audioSessionId, settings.speechClarityEnabled)
    }

    fun release() {
        releaseEffects()
        sessionId = Int.MIN_VALUE
    }

    private fun configureLoudness(audioSessionId: Int, gainMillibels: Int) {
        if (gainMillibels == 0) {
            releaseLoudness()
            return
        }
        loudnessEnhancer = loudnessEnhancer ?: runCatching {
            LoudnessEnhancer(audioSessionId)
        }.getOrNull()
        runCatching {
            loudnessEnhancer?.setTargetGain(gainMillibels)
            loudnessEnhancer?.setEnabled(true)
        }.onFailure { releaseLoudness() }
    }

    private fun configureBassBoost(audioSessionId: Int, enabled: Boolean) {
        if (!enabled) {
            releaseBassBoost()
            return
        }
        bassBoost = bassBoost ?: runCatching { BassBoost(0, audioSessionId) }.getOrNull()
        runCatching {
            bassBoost?.setStrength(650)
            bassBoost?.setEnabled(true)
        }.onFailure { releaseBassBoost() }
    }

    private fun configureSurround(audioSessionId: Int, enabled: Boolean) {
        if (!enabled) {
            releaseVirtualizer()
            return
        }
        virtualizer = virtualizer ?: runCatching { Virtualizer(0, audioSessionId) }.getOrNull()
        runCatching {
            virtualizer?.setStrength(500)
            virtualizer?.setEnabled(true)
        }.onFailure { releaseVirtualizer() }
    }

    private fun configureSpeechClarity(audioSessionId: Int, enabled: Boolean) {
        if (!enabled) {
            releaseEqualizer()
            return
        }
        equalizer = equalizer ?: runCatching { Equalizer(0, audioSessionId) }.getOrNull()
        runCatching {
            val effect = equalizer ?: return@runCatching
            val clarityBand = (0 until effect.numberOfBands.toInt()).firstOrNull { band ->
                val range = effect.getBandFreqRange(band.toShort())
                range[0] <= 2_000_000 && range[1] >= 2_000_000
            }
            clarityBand?.let { effect.setBandLevel(it.toShort(), 300) }
            effect.setEnabled(true)
        }.onFailure { releaseEqualizer() }
    }

    private fun releaseEffects() {
        releaseLoudness()
        releaseBassBoost()
        releaseVirtualizer()
        releaseEqualizer()
    }

    private fun releaseLoudness() { loudnessEnhancer.releaseSafely(); loudnessEnhancer = null }
    private fun releaseBassBoost() { bassBoost.releaseSafely(); bassBoost = null }
    private fun releaseVirtualizer() { virtualizer.releaseSafely(); virtualizer = null }
    private fun releaseEqualizer() { equalizer.releaseSafely(); equalizer = null }

    private fun android.media.audiofx.AudioEffect?.releaseSafely() {
        runCatching { this?.release() }
    }
}
