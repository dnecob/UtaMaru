package org.example.voicepitchlooper

import kotlin.math.PI
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Exact-beat clock for the score test tone (48kHz audio samples, not UI timers).
 * Non-integer samples/beat are distributed deterministically, without drift.
 * Used both by the main test-player and the full-screen score editor.
 */
class ScoreToneSynth(
    notes: List<ScoreNote>,
    private val bpm: Int,
    private val sampleRate: Int = 48_000
) {
    private val score = notes.toList()
    private val beatDenominator = sampleRate * 60L
    private val weights = DoubleArray(10)
    private var weightSum = 1.0
    private var previousBeat = -1
    private var samplePosition = 0L
    private var phase = 0.0
    private val fadeFraction = 0.006 * bpm / 60.0

    init {
        require(score.isNotEmpty())
        require(bpm in 30..500 && sampleRate > 0)
    }

    val sampleCount: Long get() = samplePosition
    val currentBeat: Int get() = beatAt(samplePosition)

    fun beatAt(sample: Long): Int =
        (((sample.coerceAtLeast(0L) * bpm) / beatDenominator) % score.size).toInt()

    /** Writes exactly [count] samples, including accurate boundaries *inside* the block. */
    fun render(out: ShortArray, count: Int = out.size) {
        require(count in 0..out.size)
        for (i in 0 until count) {
            val beat = beatAt(samplePosition)
            val hz = score[beat].frequency
            if (beat != previousBeat) {
                previousBeat = beat
                if (hz != null) {
                    weightSum = 0.0
                    for (h in weights.indices) {
                        val partial = hz * (h + 1)
                        val bass = partial * partial / (partial * partial + 190.0 * 190.0)
                        val treble = 1.0 / (1.0 + (partial / 2300.0).pow(4.0))
                        val weight = bass * treble / (h + 1).toDouble().pow(0.72)
                        weights[h] = weight
                        weightSum += weight
                    }
                }
            }
            if (hz == null) {
                out[i] = 0
            } else {
                val beatPart = (samplePosition * bpm % beatDenominator).toDouble() /
                    beatDenominator.toDouble()
                // Short predictable fades prevent clicks while preserving the actual beat length.
                val envelope = min(1.0, min(beatPart / fadeFraction,
                    (1.0 - beatPart) / fadeFraction)).coerceAtLeast(0.0)
                phase += 2.0 * PI * hz / sampleRate
                if (phase >= 2.0 * PI) phase -= 2.0 * PI
                var wave = 0.0
                for (h in weights.indices) wave += sin(phase * (h + 1)) * weights[h]
                out[i] = (wave / weightSum.coerceAtLeast(1.0e-8) * 27000.0 * envelope)
                    .toInt().coerceIn(-32768, 32767).toShort()
            }
            samplePosition++
        }
    }
}
