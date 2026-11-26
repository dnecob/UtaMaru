package org.example.voicepitchlooper

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.roundToInt

/** Real-time 48k PCM -> 24k PCM -> 48k PCM adapter for pitch engines only.
 * 96-tap Blackman low-pass (~10.8kHz); state persists across 20ms frames.
 * 48k microphone capture, VAD, output routing, and final output gain are unchanged.
 */
internal class VoiceRateBridge24 {
    companion object {
        const val INPUT_FRAME = 960
        const val ENGINE_FRAME = 480
        private const val TAPS = 96
        private val lowPass = DoubleArray(TAPS) { i ->
            val x = i - (TAPS - 1) * 0.5
            val cutoff = 10800.0 / 48000.0
            val sinc = if (x == 0.0) 2.0 * cutoff else sin(2.0 * PI * cutoff * x) / (PI * x)
            val blackman = 0.42 - 0.5 * cos(2.0 * PI * i / (TAPS - 1)) +
                0.08 * cos(4.0 * PI * i / (TAPS - 1))
            sinc * blackman
        }.let { raw ->
            val sum = raw.sum()
            DoubleArray(TAPS) { raw[it] / sum }
        }
    }

    private val downHistory = DoubleArray(TAPS)
    private val upHistory = DoubleArray(TAPS)
    private var downIndex = 0
    private var upIndex = 0

    fun downsample(input: ShortArray): ShortArray {
        require(input.size == INPUT_FRAME)
        val output = ShortArray(ENGINE_FRAME)
        var out = 0
        for (i in input.indices) {
            downHistory[downIndex] = input[i].toDouble()
            downIndex = (downIndex + 1) % TAPS
            if ((i and 1) == 1) {
                var sum = 0.0
                var cursor = (downIndex + TAPS - 1) % TAPS
                for (tap in 0 until TAPS) {
                    sum += lowPass[tap] * downHistory[cursor]
                    if (--cursor < 0) cursor = TAPS - 1
                }
                output[out++] = sum.roundToInt().coerceIn(-32768, 32767).toShort()
            }
        }
        return output
    }

    fun upsample(input: ShortArray): ShortArray {
        require(input.size == ENGINE_FRAME)
        val output = ShortArray(INPUT_FRAME)
        for (i in output.indices) {
            // x2 interpolation: insert a zero at every odd 48k sample.
            upHistory[upIndex] = if ((i and 1) == 0) input[i / 2].toDouble() else 0.0
            upIndex = (upIndex + 1) % TAPS
            var sum = 0.0
            var cursor = (upIndex + TAPS - 1) % TAPS
            for (tap in 0 until TAPS) {
                sum += 2.0 * lowPass[tap] * upHistory[cursor]
                if (--cursor < 0) cursor = TAPS - 1
            }
            output[i] = sum.roundToInt().coerceIn(-32768, 32767).toShort()
        }
        return output
    }
}
