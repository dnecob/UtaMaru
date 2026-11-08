package org.example.voicepitchlooper

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import kotlin.math.log2
import kotlin.math.roundToInt

/**
 * Simple beat-quantized, monophonic pitch capture for humming / whistling.
 * This does NOT record or retain an audio file. It only extracts one note per beat.
 * Input sample clock, not UI refreshes, determines each beat boundary.
 */
class MelodyRecorder(
    private val bpm: Int,
    private val maxBeats: Int,
    private val onBeat: (Int, Int?) -> Unit,
    private val onDone: (String?) -> Unit
) {
    companion object {
        private const val RATE = 48_000
        private const val BLOCK = 960 // 20 ms, matches the main audio engine.
        // PitchDetector analyses the last ~85 ms; attribute a result to its midpoint.
        private const val ANALYSIS_HALF_WINDOW = 2048L
    }

    @Volatile private var running = false
    @Volatile var progressedBeats = 0.0
        private set
    private var worker: Thread? = null

    fun start() {
        check(worker == null) { "録音は既に開始されています" }
        require(bpm in 60..500 && maxBeats in 1..256)
        running = true
        worker = Thread({ capture() }, "utamaru-melody-capture").apply { start() }
    }

    fun stop() { running = false }

    @SuppressLint("MissingPermission") // MainActivity requests RECORD_AUDIO before starting.
    private fun capture() {
        var microphone: AudioRecord? = null
        var error: String? = null
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val minimum = AudioRecord.getMinBufferSize(RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minimum > 0) { "この端末では48kHzの録音ができません" }
            val record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minimum, BLOCK * 2 * 8)
            )
            microphone = record
            check(record.state == AudioRecord.STATE_INITIALIZED) { "マイクを初期化できません" }
            val detector = PitchDetector()
            val samplesPerBeat = RATE * 60.0 / bpm
            var samplesCaptured = 0L
            var pitchChecks = 0
            var beat = 0
            val readings = ArrayList<Double>(24)
            val pcm = ShortArray(BLOCK)
            record.startRecording()
            check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "マイク録音を開始できません"
            }
            while (running && beat < maxBeats) {
                val amount = record.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING)
                if (!running) break
                check(amount >= 0) { "マイク読み取りエラー: $amount" }
                if (amount == 0) continue
                detector.push(pcm, amount)
                samplesCaptured += amount
                val windowMidpoint = (samplesCaptured - ANALYSIS_HALF_WINDOW).coerceAtLeast(0L)
                progressedBeats = (windowMidpoint / samplesPerBeat).coerceIn(0.0, maxBeats.toDouble())
                val observedBeat = progressedBeats.toInt()
                while (beat < observedBeat && beat < maxBeats) {
                    onBeat(beat, MelodyPitchQuantizer.noteFromHzSamples(readings))
                    readings.clear()
                    beat++
                }
                if (beat >= maxBeats) break
                pitchChecks++
                // YIN-like pitch detection is relatively expensive; run every 40 ms.
                if (pitchChecks % 2 == 0) {
                    val progress = (windowMidpoint / samplesPerBeat) - beat
                    // Ignore unstable note changes at each beat boundary.
                    if (progress in 0.12..0.90) {
                        val hz = detector.detect()
                        if (hz != null) readings.add(hz)
                    }
                }
            }
            // Stop can land partway through a beat. Keep that beat only if
            // at least half of it was recorded, so short accidental taps aren't notes.
            if (beat < maxBeats && readings.isNotEmpty()) {
                val fraction = ((samplesCaptured - ANALYSIS_HALF_WINDOW) / samplesPerBeat) - beat
                if (fraction >= 0.5) onBeat(beat, MelodyPitchQuantizer.noteFromHzSamples(readings))
            }
        } catch (e: Exception) {
            error = "録音エラー: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            running = false
            try { microphone?.stop() } catch (_: Exception) {}
            microphone?.release()
            onDone(error)
        }
    }
}

/** Pure Kotlin so this quantization step can be tested independently of Android. */
object MelodyPitchQuantizer {
    /** Returns MIDI note 24..95 (C1..B6), or null for an unvoiced beat. */
    fun noteFromHzSamples(samples: List<Double>): Int? {
        val notes = samples.mapNotNull { hz ->
            if (hz !in 30.0..2000.0 || !hz.isFinite()) null
            else (69.0 + 12.0 * log2(hz / 440.0)).takeIf { it in 24.0..95.49 }
        }.sorted()
        if (notes.size < 2) return null
        val size = notes.size
        val median = if (size % 2 == 1) notes[size / 2]
                     else (notes[size / 2 - 1] + notes[size / 2]) / 2.0
        return median.roundToInt().coerceIn(24, 95)
    }
}
