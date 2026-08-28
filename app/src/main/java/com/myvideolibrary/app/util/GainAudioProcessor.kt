package com.myvideolibrary.app.util

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A Media3 [AudioProcessor] that multiplies the PCM samples by a gain factor, so
 * the player can go **above 100%** — ExoPlayer's own `player.volume` is clamped to
 * 0..1 and cannot boost. [gain] is 1.0 for 100%, 2.0 for 200%, etc. Samples are
 * hard-clamped to the 16-bit range so an extreme boost distorts (expected) rather
 * than wrapping around into noise.
 *
 * The processor is device-independent (no AudioEffect), so it works everywhere the
 * player does. [gain] can be changed live from any thread.
 */
@UnstableApi
class GainAudioProcessor : BaseAudioProcessor() {

    /** 1.0 = 100% (unchanged). Values > 1 boost; values < 1 attenuate. */
    @Volatile
    var gain: Float = 1f

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        // Same PCM format out as in — we only scale amplitudes.
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val g = gain
        val size = inputBuffer.remaining()
        val output = replaceOutputBuffer(size).order(ByteOrder.nativeOrder())

        if (g == 1f) {
            output.put(inputBuffer)
        } else {
            val input = inputBuffer.order(ByteOrder.nativeOrder()).asShortBuffer()
            while (input.hasRemaining()) {
                var v = (input.get() * g).toInt()
                if (v > Short.MAX_VALUE) v = Short.MAX_VALUE.toInt()
                else if (v < Short.MIN_VALUE) v = Short.MIN_VALUE.toInt()
                output.putShort(v.toShort())
            }
            inputBuffer.position(inputBuffer.limit())
        }
        output.flip()
    }
}
