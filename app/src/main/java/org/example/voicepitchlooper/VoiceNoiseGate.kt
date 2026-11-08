package org.example.voicepitchlooper

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Low-cost voice gate for the OUTPUT of the pitch shifter.
 *
 * PitchDetector needs about 85ms of history and runs at 60ms intervals. Using
 * pitchDetected alone as a VAD can cut the initial consonant of each phrase.
 * This v0.8.1 variant allows a clear microphone-energy onset to open the gate
 * before pitch tracking settles, but retains pitch+RMS confirmation and the
 * previous 150ms hold and smooth sample-level attack/release.
 *
 * This is NOT a tonal noise suppressor: any whine heard while speaking will
 * still be heard. An audio sample is needed before designing a notch/filter.
 */
class VoiceNoiseGate {
    private var gain = 0.0
    private var holdRemaining = 0
    private val holdSamples = 7200 // 150ms at 48kHz
    private var noiseFloor = 0.003  // conservative startup estimate

    /**
     * Called once per 20ms input frame. Independently opening on an obvious
     * energy onset prevents waiting ~100-180ms for fundamental tracking.
     * The higher threshold than the old pitch-based 0.008 RMS reduces noise
     * false positives. The floor adapts only on frames believed to be quiet.
     */
    fun frameHasVoice(inputRms: Double, hasTrackedPitch: Boolean): Boolean {
        val rms = if (inputRms.isFinite()) inputRms.coerceAtLeast(0.0) else 0.0
        val pitchConfirmed = hasTrackedPitch && rms >= 0.008
        val energyThreshold = max(0.015, noiseFloor * 3.0)
        val onset = rms >= energyThreshold
        if (!pitchConfirmed && !onset) {
            noiseFloor += (rms - noiseFloor) * 0.018
        }
        return pitchConfirmed || onset
    }

    fun process(output: ShortArray, length: Int, voiceDetected: Boolean) {
        require(length in 0..output.size)
        if (voiceDetected) holdRemaining = holdSamples
        for (i in 0 until length) {
            val open = holdRemaining > 0
            if (holdRemaining > 0) holdRemaining--
            val target = if (open) 1.0 else 0.0
            // 2ms attack and ~21ms release, same envelopes as v0.8.0.
            val coeff = if (open) 0.012 else 0.001
            gain += (target - gain) * coeff
            output[i] = (output[i].toInt() * gain).roundToInt()
                .coerceIn(-32768, 32767).toShort()
        }
    }
}
