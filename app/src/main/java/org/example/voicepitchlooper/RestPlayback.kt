package org.example.voicepitchlooper

import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.min

/** Only affects live microphone output. Score test tones keep their written rests. */
enum class RestPlaybackMode(val caption: String) {
    RAW_VOICE("生の声をそのまま出す"),
    SILENCE("無音にする"),
    HOLD_LAST("直前の音程を維持")
}

/** Fill rests in a *temporary playback copy* of the score, never in the saved score.
 * A rest at the start wraps back to the last actual note in the loop.
 */
object RestPlaybackPlan {
    fun holdLast(notes: List<ScoreNote>): List<ScoreNote> {
        require(notes.isNotEmpty() && notes.any { it.frequency != null })
        var previous = notes.last { it.frequency != null }
        return notes.map { note ->
            if (note.frequency == null) previous
            else { previous = note; note }
        }
    }
}

/** Keeps the original voice delayed by the same ~171 ms as the shifters, to reduce
 * gaps/clicks when moving from shifted voice to bypass and vice versa.
 * A short crossfade prevents abrupt waveform discontinuities at beat boundaries.
 */
class RestOutputMixer(
    private val delaySamples: Int = 8192,
    private val matchImprovedLevel: Boolean = false
) {
    enum class Selection { SHIFTED, RAW, SILENCE }

    private val ring = ShortArray(32768)
    private val mask = ring.size - 1
    private var written = 0L
    private var shiftedGain = 0.0
    private var rawGain = 0.0
    // Match wet voice loudness to the delayed dry voice during voiced frames only.
    // A conservative gain ceiling avoids amplifying faint artifacts/noise without bound.
    private var levelGain = 1.0
    private var levelGoal = 1.0

    init { require(delaySamples in 1 until ring.size) }

    fun mix(
        shifted: ShortArray, input: ShortArray, count: Int, selection: Selection,
        voiceDetected: Boolean = false
    ) {
        require(count in 0..shifted.size && count <= input.size)
        val wantShifted = if (selection == Selection.SHIFTED) 1.0 else 0.0
        val wantRaw = if (selection == Selection.RAW) 1.0 else 0.0
        val delayed = ShortArray(count)
        var wetSquare = 0.0
        var drySquare = 0.0
        var wetPeak = 0
        // Construct the actual latency-matched reference before measuring loudness.
        for (i in 0 until count) {
            val index = written.toInt() and mask
            ring[index] = input[i]
            val dry = if (written < delaySamples) 0 else
                ring[(written - delaySamples).toInt() and mask].toInt()
            delayed[i] = dry.toShort()
            if (matchImprovedLevel && voiceDetected && selection == Selection.SHIFTED) {
                val wet = shifted[i].toInt()
                wetSquare += wet.toDouble() * wet
                drySquare += dry.toDouble() * dry
                wetPeak = maxOf(wetPeak, kotlin.math.abs(wet))
            }
            written++
        }
        if (matchImprovedLevel && voiceDetected &&
            selection == Selection.SHIFTED && count > 0) {
            val wetRms = sqrt(wetSquare / count)
            val dryRms = sqrt(drySquare / count)
            // Skip quiet/no-pitch frames instead of boosting noise or silent PSOLA gaps.
            if (dryRms > 260.0 && wetRms > 130.0) {
                val peakCeiling = if (wetPeak > 0) 31000.0 / wetPeak else 4.0
                val desired = (dryRms / wetRms).coerceIn(0.75, min(4.0, maxOf(0.75, peakCeiling)))
                // Smooth over multiple 20ms frames: quick enough for a phrase, not pumping per sample.
                val follow = if (desired > levelGoal) 0.18 else 0.12
                levelGoal += (desired - levelGoal) * follow
            }
        }
        for (i in 0 until count) {
            // Sample-level smoothing avoids a step when the 20ms RMS estimate changes.
            levelGain += (levelGoal - levelGain) * 0.002
            shiftedGain += (wantShifted - shiftedGain) * 0.004
            rawGain += (wantRaw - rawGain) * 0.004
            val wet = shifted[i].toInt() * (if (matchImprovedLevel) levelGain else 1.0)
            val blended = wet * shiftedGain + delayed[i].toInt() * rawGain
            shifted[i] = blended.roundToInt().coerceIn(-32768, 32767).toShort()
        }
    }
}
