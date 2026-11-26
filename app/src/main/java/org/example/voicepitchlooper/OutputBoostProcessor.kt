package org.example.voicepitchlooper

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow

/**
 * Final output gain for all PCM routes (Android AudioTrack and PC TCP).
 *
 * For voiced, upward-pitch-shifted output, compensate loss progressively:
 *   1x = 0dB; 2x = +3.01dB; 4x or more = +6.02dB.
 * The user's existing manual 0..18dB boost is independent. Both gains
 * are combined BEFORE a single frame-peak limiter (no double clipping).
 *
 * No extra audio buffering/latency. Rapid score-note changes use a smooth
 * 20ms-frame gain ramp and an immediate peak-safe ceiling, not a hard step.
 * The unchanged zero-boost path is sample-identical to previous releases.
 */
internal class OutputBoostProcessor {
    companion object {
        const val MAX_BOOST_DB = 18
        const val STEP_DB = 3
        private const val LIMIT_PEAK = 0.97 * Short.MAX_VALUE
        private const val MANUAL_RELEASE_PER_FRAME = 0.12
        private const val AUTO_ATTACK_PER_FRAME = 0.50
        private const val AUTO_RELEASE_PER_FRAME = 0.60
    }

    private var appliedGain = 1.0
    private var autoReleaseFrames = 0

    /**
     * @param pitchRatio The actual note/input fundamental ratio, or null when
     *                   no pitched voice is available. Downward shifts and
     *                   raw output never receive this automatic compensation.
     */
    fun process(samples: ShortArray, count: Int, boostDb: Int, pitchRatio: Double? = null) {
        require(boostDb in 0..MAX_BOOST_DB)
        require(count in 0..samples.size)
        if (count == 0) return

        val highRatio = pitchRatio?.takeIf { it.isFinite() && it > 1.0 }
            ?.coerceAtMost(4.0) ?: 1.0
        val autoGain = kotlin.math.sqrt(highRatio)   // capped at 2x (+6.02 dB)
        // Strict unmodified path for the default level, including raw mic and
        // all score test tones. Only transitional gain smoothing can change it.
        if (boostDb == 0 && autoGain == 1.0 && appliedGain == 1.0) return

        val manualGain = 10.0.pow(boostDb / 20.0)
        val requestedGain = manualGain * autoGain
        if (autoGain > 1.0) autoReleaseFrames = 6
        else if (autoReleaseFrames > 0) autoReleaseFrames--
        var peak = 0
        for (i in 0 until count) {
            val magnitude = abs(samples[i].toInt())
            if (magnitude > peak) peak = magnitude
        }
        // Absolute ceiling for every frame before final PCM conversion.
        val safetyCeiling = if (peak == 0) requestedGain else LIMIT_PEAK / peak
        val safeTarget = min(requestedGain, safetyCeiling)
        // Hard limit the starting level only when needed to prevent clipping.
        val firstGain = min(appliedGain, safetyCeiling)
        val fraction = if (autoReleaseFrames > 0) {
            if (safeTarget >= firstGain) AUTO_ATTACK_PER_FRAME else AUTO_RELEASE_PER_FRAME
        } else MANUAL_RELEASE_PER_FRAME
        val lastGain = firstGain + (safeTarget - firstGain) * fraction
        val step = (lastGain - firstGain) / count
        var gain = firstGain
        for (i in 0 until count) {
            gain += step
            val scaled = (samples[i] * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            samples[i] = scaled.toShort()
        }
        // Normalized exact unity once the previous gain is close enough;
        // prevents unnecessary processing of all subsequent unshifted audio.
        appliedGain = if (boostDb == 0 && autoGain == 1.0 &&
            kotlin.math.abs(lastGain - 1.0) < 0.0005) 1.0 else lastGain
    }
}
