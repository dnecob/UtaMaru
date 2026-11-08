package org.example.voicepitchlooper

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Legacy two-grain time-domain pitch shifter (48kHz, fixed ~171ms delay).
 *
 * v0.7.3: do not jump the read-head phase when the tracked fundamental changes.
 * Instead, render the old and new grains together for 30ms and crossfade.
 * Also ramp the mute/voiced envelope; toggling pitch tracking used to cut a
 * waveform at an arbitrary sample, causing an audible pop.
 *
 * This deliberately does not change the original timbre/algorithm otherwise.
 */
class LegacyPitchShifter(private val maxRatio: Double = 3.5,
                         private val minRatio: Double = 0.5) {
    init { PitchRange(minRatio, maxRatio) }
    private val mask = 32768 - 1
    private val history = FloatArray(32768)
    private val baseDelay = 8192.0

    private class Grain(val size: Int, var phase: Int = 0) {
        val window = DoubleArray(size) { i -> 0.5 - 0.5 * cos(2.0 * PI * i / size) }
        fun tick() { phase++; if (phase >= size) phase = 0 }
    }

    private var active = Grain(2048)
    private var incoming: Grain? = null
    private val crossfadeSamples = 1440 // 30ms at 48kHz
    private var crossfadeRemaining = 0
    private var written: Long = 0
    private var smoothedRatio = 1.0
    private var outputGain = 0.0

    private fun configureGrain(ratio: Double, fundamentalHz: Double?) {
        if (fundamentalHz == null || !fundamentalHz.isFinite() || fundamentalHz <= 0 ||
            abs(1.0 - ratio) < 0.035 || incoming != null) return
        val pitchPeriod = 48000.0 / fundamentalHz
        val fundamentalGrain = 2.0 * pitchPeriod / abs(1.0 - ratio)
        val cycles = (2048.0 / fundamentalGrain).roundToInt().coerceAtLeast(1)
        val ideal = (fundamentalGrain * cycles).roundToInt()
            .coerceIn(768, 4096).let { it + it % 2 }
        // At 5x/7x, large grains would read samples that haven't arrived yet.
        // Keep the 3.5x legacy algorithm completely unchanged.
        val maxSafeSize = if (maxRatio > 3.5) {
            val bound = ((baseDelay - 512.0) / (maxRatio - 1.0)).toInt()
                .coerceIn(768, 4096)
            bound - bound % 2
        } else 4096
        val desired = ideal.coerceAtMost(maxSafeSize)
        if (abs(desired - active.size) > active.size * 0.008) {
            // Keep normalized position close to the old grain, then crossfade
            // both read-head pairs to avoid an instantaneous waveform jump.
            val newPhase = ((active.phase.toLong() * desired) / active.size).toInt()
                .coerceIn(0, desired - 1)
            incoming = Grain(desired, newPhase)
            crossfadeRemaining = crossfadeSamples
        }
    }

    private fun sampleAt(position: Double): Double {
        val before = floor(position).toLong()
        val fraction = position - before
        val first = history[before.toInt() and mask].toDouble()
        val second = history[(before + 1).toInt() and mask].toDouble()
        return first + (second - first) * fraction
    }

    private fun fromGrain(g: Grain, shift: Double): Double {
        val p = g.phase
        val other = (p + g.size / 2) % g.size
        return g.window[p] * sampleAt(written - baseDelay - shift * p) +
            g.window[other] * sampleAt(written - baseDelay - shift * other)
    }

    fun process(samples: ShortArray, length: Int, targetRatio: Double,
                mute: Boolean, inputFundamentalHz: Double? = null): ShortArray {
        require(length in 0..samples.size)
        val output = ShortArray(length)
        val desired = if (targetRatio.isFinite()) targetRatio.coerceIn(minRatio, maxRatio)
                      else smoothedRatio
        configureGrain(desired, inputFundamentalHz)
        for (i in 0 until length) {
            history[written.toInt() and mask] = samples[i] / 32768.0f
            smoothedRatio += (desired - smoothedRatio) * 0.0015
            if (maxRatio > 3.5) {
                // During the 30ms grain crossfade, the old (longer) read head
                // must stay inside the already recorded audio history.
                val safeRatio = 1.0 + (baseDelay - 512.0) / active.size
                if (smoothedRatio > safeRatio) smoothedRatio = safeRatio
            }
            val shift = 1.0 - smoothedRatio
            val oldSound = fromGrain(active, shift)
            val next = incoming
            val mixed = if (next == null) oldSound else {
                val t = (crossfadeSamples - crossfadeRemaining).toDouble() /
                    crossfadeSamples
                oldSound * (1.0 - t) + fromGrain(next, shift) * t
            }
            // The old mode hard-muted on pitch-tracking dropout. Fade both
            // dropout and the first voiced output over several milliseconds.
            val audible = !mute && written >= baseDelay + 4096
            val targetGain = if (audible) 1.0 else 0.0
            outputGain += (targetGain - outputGain) * 0.003
            val clamped = (mixed * 0.82 * outputGain * 32767.0)
                .coerceIn(-32768.0, 32767.0)
            output[i] = clamped.toInt().toShort()
            written++
            active.tick()
            if (next != null) {
                next.tick()
                crossfadeRemaining--
                if (crossfadeRemaining <= 0) {
                    active = next
                    incoming = null
                }
            }
        }
        return output
    }
}
