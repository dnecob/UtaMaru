package org.example.voicepitchlooper

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Experimental voiced-speech pitch synchronous overlap-and-add shifter.
 *
 * This is a deliberately dependency-free prototype, NOT the Rubber Band engine.
 * For periodic input it copies waveform fragments without resampling them,
 * aiming to preserve vocal formants better than the v0.2 2-head granulator.
 *
 * Source peak marks are estimated near successive pitch periods, not from a
 * robust glottal closure detector. Breath, consonants and rapidly changing
 * fundamentals can still sound rough; comparisons must be made on device.
 *
 * Latency: 8192 samples (~171ms) plus platform/PC buffers.
 * Supports any input block size, 48kHz mono PCM16.
 */
class PitchShifter(private val maxRatio: Double = 3.5,
                   private val minRatio: Double = 0.5) {
    init { PitchRange(minRatio, maxRatio) }
    // Preserve the 1600Hz upper bound at the standard 3.5x setting.
    private val maxOutputHz = 1600.0 * maxRatio / 3.5
    private val minOutputPeriod = 48000.0 / maxOutputHz
    // Preserve old >=65Hz behavior at the 0.5x setting. Lower settings
    // may need synthesis periods longer than the former 750-sample cap.
    private val minOutputHz = if (minRatio < 0.5) 25.0 else 65.0
    private val maxOutputPeriod = 48000.0 / minOutputHz
    private val rate = 48000.0
    private val ringSize = 32768
    private val mask = ringSize - 1
    private val input = FloatArray(ringSize)
    private val sum = FloatArray(ringSize)
    private val weight = FloatArray(ringSize)
    private val delay = 8192L
    private var cursor = 0L
    private var nextPulse = Double.NaN
    private var sourceMark = Double.NaN
    private var sourcePeriod = 220.0 // initial estimate ~218Hz
    private var outputHz = 220.0
    private var voicedMix = 0.0
    private var muteGain = 0.0
    private var previousRatio = 1.0

    // Keep a sample-indexed circular history for a matching fixed output delay.
    private fun valueAt(position: Int): Double = input[position and mask].toDouble()

    private fun findMark(expected: Double, period: Double, first: Boolean): Double {
        val halfWidth = (period * if (first) 0.48 else 0.32).roundToInt().coerceIn(8, 320)
        val center = expected.roundToInt()
        // Pick a positive peak for phase-consistent glottal-pulse candidates.
        var bestIndex = center
        var best = -Double.MAX_VALUE
        for (offset in -halfWidth..halfWidth) {
            val at = center + offset
            // At least 1ms of input margin on both sides is always available.
            val v = valueAt(at)
            if (v > best) {
                best = v
                bestIndex = at
            }
        }
        return bestIndex.toDouble()
    }

    /** Progress the analysis pulse marks without restarting on every note. */
    private fun sourcePulseNear(sourceTime: Double): Double {
        if (sourceMark.isNaN()) sourceMark = findMark(sourceTime, sourcePeriod, true)
        // sourceTime never runs backwards; track and correct peaks each period.
        var hops = 0
        while (sourceTime > sourceMark + sourcePeriod * 0.52 && hops < 16) {
            sourceMark = findMark(sourceMark + sourcePeriod, sourcePeriod, false)
            hops++
        }
        if (sourceTime < sourceMark - sourcePeriod * 0.55 || hops == 16) {
            sourceMark = findMark(sourceTime, sourcePeriod, true)
        }
        return sourceMark
    }

    private fun placePulse(synthesisCenter: Double, targetPeriod: Double, now: Long) {
        val sourceTime = synthesisCenter - delay
        val analysisCenter = sourcePulseNear(sourceTime)
        // Overlap also on downward shifts, when synthesis periods grow longer.
        val radius = max(sourcePeriod * 1.2, targetPeriod * 0.90)
            .roundToInt().coerceIn(64, 1500)
        val start = synthesisCenter.roundToInt()
        for (offset in -radius..radius) {
            val t = start.toLong() + offset
            if (t < now || t > now + 3500) continue
            val x = (analysisCenter + offset).roundToInt()
            val w = (0.5 + 0.5 * cos(PI * offset / radius)).toFloat()
            val idx = t.toInt() and mask
            sum[idx] += valueAt(x).toFloat() * w
            weight[idx] += w
        }
    }

    fun process(
        samples: ShortArray, length: Int, targetRatio: Double,
        mute: Boolean, inputFundamentalHz: Double? = null
    ): ShortArray {
        require(length in 0..samples.size)
        val result = ShortArray(length)
        val voiced = inputFundamentalHz?.takeIf { it.isFinite() && it in 70.0..1000.0 }
        val clampedRatio = if (targetRatio.isFinite()) targetRatio.coerceIn(minRatio, maxRatio) else previousRatio
        previousRatio = clampedRatio
        if (voiced != null) {
            val period = rate / voiced
            sourcePeriod += (period - sourcePeriod) * 0.16
        }
        val wantedHz = ((voiced ?: (rate / sourcePeriod)) * clampedRatio)
            .coerceIn(minOutputHz, maxOutputHz)

        for (i in 0 until length) {
            val t = cursor
            input[(t.toInt() and mask)] = samples[i] / 32768.0f

            // Smooth note changes without resetting pulse phase. ~12ms time constant.
            outputHz += (wantedHz - outputHz) * 0.0017
            voicedMix += ((if (voiced == null) 0.0 else 1.0) - voicedMix) * 0.0012
            muteGain += ((if (mute) 0.0 else 1.0) - muteGain) * 0.004

            val usable = t > delay + 2400
            if (usable) {
                if (nextPulse.isNaN()) nextPulse = t.toDouble() + 256.0
                val periodOut = (rate / outputHz).coerceIn(minOutputPeriod, maxOutputPeriod)
                val radius = max(sourcePeriod * 1.2, periodOut * 0.9).coerceIn(64.0, 1500.0)
                // Schedule each grain early enough for its left edge to be available.
                var scheduled = 0
                while (nextPulse <= t + radius && scheduled < 4) {
                    placePulse(nextPulse, periodOut, t)
                    nextPulse += periodOut
                    scheduled++
                }
            }
            val idx = t.toInt() and mask
            val produced = if (weight[idx] > 1e-5f) sum[idx] / weight[idx] else 0f
            // Bypass for non-periodic consonants / temporarily unpitched input.
            val dry = if (t > delay) valueAt((t - delay).toInt()) else 0.0
            val out = ((produced * voicedMix + dry * (1.0 - voicedMix)) * muteGain * 0.90)
                .coerceIn(-1.0, 1.0)
            result[i] = (out * 32767.0).roundToInt().coerceIn(-32768, 32767).toShort()
            sum[idx] = 0f
            weight[idx] = 0f
            cursor++
        }
        return result
    }
}
