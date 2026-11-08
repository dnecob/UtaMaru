package org.example.voicepitchlooper

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Sample-accurate 8-beat count-in at 48 kHz. Kept Android-free to allow JVM tests.
 * The sound is a short, smooth two-partial click, not the overtone-rich score-note preview.
 */
class CountInClickSynth(val bpm: Int, val sampleRate: Int = 48_000) {
    companion object { const val COUNT_IN_BEATS = 8 }

    init { require(bpm in 60..500 && sampleRate > 0) }

    val beatStarts: IntArray = IntArray(COUNT_IN_BEATS) { beat ->
        (beat * sampleRate.toDouble() * 60.0 / bpm).roundToInt()
    }
    val totalFrames: Int = (COUNT_IN_BEATS.toDouble() * sampleRate * 60.0 / bpm).roundToInt()
    private val clickFrames: Int = minOf((sampleRate * 0.052).roundToInt(), beatStarts[1] / 3)
    private val attackFrames = maxOf(1, (sampleRate * 0.003).roundToInt())
    private val releaseFrames = maxOf(1, (sampleRate * 0.007).roundToInt())

    fun render(startFrame: Int, output: ShortArray, length: Int = output.size) {
        require(startFrame >= 0 && length in 0..output.size && startFrame + length <= totalFrames)
        var beat = beatStarts.indexOfLast { it <= startFrame }.coerceAtLeast(0)
        for (i in 0 until length) {
            val frame = startFrame + i
            while (beat < COUNT_IN_BEATS - 1 && frame >= beatStarts[beat + 1]) beat++
            val sinceBeat = frame - beatStarts[beat]
            if (sinceBeat >= clickFrames) {
                output[i] = 0
                continue
            }
            val attack = (sinceBeat.toDouble() / attackFrames).coerceIn(0.0, 1.0)
            val release = ((clickFrames - 1 - sinceBeat).toDouble() / releaseFrames).coerceIn(0.0, 1.0)
            val smooth = 0.5 - 0.5 * cos(PI * minOf(attack, release))
            val accented = beat % 4 == 0
            val frequency = if (accented) 880.0 else 660.0
            val phase = (2.0 * PI * frequency / sampleRate) * sinceBeat
            val wave = (sin(phase) + 0.18 * sin(phase * 2.0)) / 1.18
            output[i] = (wave * 13_500.0 * smooth).roundToInt()
                .coerceIn(-32768, 32767).toShort()
        }
    }
}
