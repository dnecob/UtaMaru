package org.example.voicepitchlooper

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow

/**
 * Lightweight, per-frame peak-limited output gain for 16-bit PCM.
 * Applied at the common output point after voice conversion/rest mixing and
 * before either AudioTrack.write() or TCP PCM encoding. No extra audio latency.
 *
 * 0 dB is a strict no-op for backward compatibility with all previous modes.
 * At positive boost, use the peak of the whole available frame to choose a
 * clipping-safe gain. Attack is immediate; release/upward gain changes are
 * smoothed between frames to avoid repeated pumping and slider clicks.
 */
internal class OutputBoostProcessor {
    companion object {
        const val MAX_BOOST_DB = 18
        const val STEP_DB = 3
        private const val LIMIT_PEAK = 0.97 * Short.MAX_VALUE
        private const val RELEASE_PER_FRAME = 0.12 // ~150 ms at 20-ms frames
    }

    private var appliedGain = 1.0

    fun process(samples: ShortArray, count: Int, boostDb: Int) {
        require(boostDb in 0..MAX_BOOST_DB)
        require(count in 0..samples.size)
        // A zero setting must not alter the existing PCM signal at all.
        if (boostDb == 0) {
            appliedGain = 1.0
            return
        }
        if (count == 0) return
        val requestedGain = 10.0.pow(boostDb / 20.0)
        var peak = 0
        for (i in 0 until count) {
            // Convert to Int before abs: abs(Short.MIN_VALUE) needs 32768.
            val magnitude = abs(samples[i].toInt())
            if (magnitude > peak) peak = magnitude
        }
        val safeGain = if (peak == 0) requestedGain
                       else min(requestedGain, LIMIT_PEAK / peak)
        val firstGain: Double
        val lastGain: Double
        if (safeGain < appliedGain) {
            // Act immediately when a new peak would otherwise clip.
            firstGain = safeGain
            lastGain = safeGain
        } else {
            firstGain = appliedGain
            lastGain = firstGain + (safeGain - firstGain) * RELEASE_PER_FRAME
        }
        // Smooth the release over the frame rather than stepping its envelope.
        val step = (lastGain - firstGain) / count
        var gain = firstGain
        for (i in 0 until count) {
            gain += step
            val scaled = (samples[i] * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            samples[i] = scaled.toShort()
        }
        appliedGain = lastGain
    }
}
