package org.example.voicepitchlooper

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * v0.8.7 experiment: preserve the actual input waveform when pitch correction
 * is negligible, rather than resynthesizing already-correct speech.
 *
 * The unprocessed voice is delayed by exactly 8192 samples to match the
 * standard / PSOLA shifters. Do not apply this bypass to Rubber Band: its
 * algorithm and JNI block adapter have a different latency.
 *
 * Both the processed and dry paths run through the existing output gate and
 * rest selection AFTER this stage. Thus the bypass does not skip either.
 */
class NaturalVoiceBypass(
    private val delaySamples: Int = 8192,
    private val dryLevel: Double = 0.82
) {
    private val ring = ShortArray(32768)
    private val mask = ring.size - 1
    private var written = 0L
    private var dryMix = 0.0
    private var dryLatched = false

    init {
        require(delaySamples in 1 until ring.size)
        require(dryLevel.isFinite() && dryLevel > 0.0 && dryLevel <= 1.0)
    }

    /**
     * With 15/25 cent hysteresis, don't mix two differently pitched signals
     * for an entire note (which can create beating / robotic chorusing).
     * Only crossfade for a few milliseconds when entering/leaving dry mode.
     * While latched, the possible tuning error is at most 25 cents.
     */
    private fun dryTarget(pitchRatio: Double?): Double {
        if (pitchRatio == null || !pitchRatio.isFinite() || pitchRatio <= 0) {
            dryLatched = false
            return 0.0
        }
        val cents = abs(1200.0 * ln(pitchRatio) / ln(2.0))
        if (cents <= 15.0) dryLatched = true
        if (cents >= 25.0) dryLatched = false
        return if (dryLatched) 1.0 else 0.0
    }

    /** In-place blend; always advance dry history, even when bypass is OFF. */
    fun process(
        input: ShortArray, processed: ShortArray, length: Int,
        pitchRatio: Double?, nearUnityEnabled: Boolean,
        originalMonitor: Boolean
    ) {
        require(length in 0..input.size && length <= processed.size)
        val destination = when {
            originalMonitor -> 1.0
            nearUnityEnabled -> dryTarget(pitchRatio)
            else -> 0.0
        }
        for (i in 0 until length) {
            val index = written.toInt() and mask
            ring[index] = input[i]
            val dry = if (written >= delaySamples) {
                ring[(written - delaySamples).toInt() and mask].toInt()
            } else 0
            // 12ms exponential smoothing of dry/wet transitions at 48kHz.
            dryMix += (destination - dryMix) * 0.00174
            // In original-monitor mode, preserve full original level. In the
            // near-unity blend, match the normal processed engine gain.
            val dryGain = if (originalMonitor) 1.0 else dryLevel
            val mixed = processed[i].toInt() * (1.0 - dryMix) +
                dry * dryGain * dryMix
            processed[i] = mixed.roundToInt().coerceIn(-32768, 32767).toShort()
            written++
        }
    }
}
