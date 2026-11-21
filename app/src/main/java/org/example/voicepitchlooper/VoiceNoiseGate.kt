package org.example.voicepitchlooper

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Low-cost voice gate for the OUTPUT of the pitch shifter.
 *
 * PitchDetector needs about 85ms of history and runs at 60ms intervals. Using
 * pitchDetected alone as a VAD can cut the initial consonant of each phrase.
 * Energy onsets open the gate before pitch tracking settles. Optional
 * softer continuation preserves breathy endings; optional activity delay
 * aligns the gate with pitch-shifter output latency.
 *
 * This is NOT a tonal noise suppressor: any whine heard while speaking will
 * still be heard. An audio sample is needed before designing a notch/filter.
 */
class VoiceNoiseGate(
    holdMillis: Int = 150,
    releaseMillis: Double = 21.0,
    private val outputDelaySamples: Int = 0,
    private val preserveBreathyTail: Boolean = false,
    private val rawMicMode: Boolean = false,
    // 100 = exactly the existing tuned thresholds. Holds, release and gain unchanged.
    private val thresholdPercent: Int = 100
) {
    init {
        require(holdMillis in 0..2000)
        require(releaseMillis.isFinite() && releaseMillis in 1.0..1000.0)
        require(outputDelaySamples in 0..48000)
        require(thresholdPercent in 50..300)
    }

    private var gain = 0.0
    private var holdRemaining = 0
    private val holdSamples = holdMillis * 48  // 48kHz
    // Match the old 0.001 release coefficient when releaseMillis=21ms.
    private val releaseCoefficient = if (releaseMillis == 21.0) 0.001
        else 1.0 / (releaseMillis * 48.0)
    private var noiseFloor = 0.003
    private var recentStrongVoiceFrames = 0
    private val delayedActivity = if (outputDelaySamples > 0)
        ByteArray(outputDelaySamples + 1) else null
    private var activityCursor = 0

    /**
     * Called once per 20ms input frame. Independently opening on an obvious
     * energy onset prevents waiting ~100-180ms for fundamental tracking.
     * The higher threshold than the old pitch-based 0.008 RMS reduces noise
     * false positives. The floor adapts only on frames believed to be quiet.
     */
    private val thresholdScale = thresholdPercent / 100.0

    private fun currentEnergyThreshold(): Double =
        (if (rawMicMode) max(0.006, noiseFloor * 2.0)
        else max(0.015, noiseFloor * 3.0)) * thresholdScale

    /** Pure lookahead check: does NOT modify the noise floor, thresholds or hold state. */
    fun hasUpcomingEnergyOnset(futureRms: Double): Boolean =
        futureRms.isFinite() && futureRms >= currentEnergyThreshold()

    fun frameHasVoice(inputRms: Double, hasTrackedPitch: Boolean): Boolean {
        val rms = if (inputRms.isFinite()) inputRms.coerceAtLeast(0.0) else 0.0
        val pitchConfirmed = hasTrackedPitch && rms >= 0.008 * thresholdScale
        // 100% preserves existing onset detection (including tracked pitch).
        val onset = rms >= currentEnergyThreshold()
        val strongVoice = pitchConfirmed || onset
        if (strongVoice) {
            // 28 frames / 560ms of grace for a breathy phrase ending.
            recentStrongVoiceFrames = 28
        } else if (recentStrongVoiceFrames > 0) {
            recentStrongVoiceFrames--
        }
        val continuationThreshold = (if (rawMicMode) max(0.0025, noiseFloor * 1.5)
            else max(0.0045, noiseFloor * 1.8)) * thresholdScale
        val breathyContinuation = preserveBreathyTail && !strongVoice &&
            recentStrongVoiceFrames > 0 && rms >= continuationThreshold
        // Continue to regard low-level speech as active while it remains
        // audible. Otherwise the 560ms continuation window can expire in
        // the middle of a softly spoken sentence.
        if (rawMicMode && breathyContinuation) recentStrongVoiceFrames = 28
        if (!strongVoice && !breathyContinuation) {
            noiseFloor += (rms - noiseFloor) * 0.018
        }
        return strongVoice || breathyContinuation
    }

    fun process(output: ShortArray, length: Int, voiceDetected: Boolean) {
        require(length in 0..output.size)
        // Raw-mic and Standard defaults keep their verified gate behaviour.
        if (delayedActivity == null && voiceDetected) holdRemaining = holdSamples
        for (i in 0 until length) {
            val delayedVoice = if (delayedActivity == null) voiceDetected else {
                val size = delayedActivity.size
                delayedActivity[activityCursor] = if (voiceDetected) 1 else 0
                val read = (activityCursor + 1) % size
                val active = delayedActivity[read].toInt() != 0
                activityCursor = read
                active
            }
            if (delayedActivity != null && delayedVoice) holdRemaining = holdSamples
            val open = holdRemaining > 0 || (holdSamples == 0 && delayedVoice)
            if (holdRemaining > 0) holdRemaining--
            val target = if (open) 1.0 else 0.0
            val coeff = if (open) 0.012 else releaseCoefficient
            gain += (target - gain) * coeff
            output[i] = (output[i].toInt() * gain).roundToInt()
                .coerceIn(-32768, 32767).toShort()
        }
    }
}
