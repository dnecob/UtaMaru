package org.example.voicepitchlooper

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.pow

/**
 * 1-second note preview. Continuous phase during drag, with additional audible
 * harmonics for small phone speakers that cannot reproduce C1-C3 fundamentals.
 * No pause/flush on each note: stopping the track can create clicking noises.
 */
class NotePreviewPlayer(context: Context) : AutoCloseable {
    private val lock = java.lang.Object()
    @Volatile private var closed = false
    private var requestedHz: Double? = null
    private var expiresAtNs = 0L
    private var scoreSynth: ScoreToneSynth? = null
    @Volatile private var scoreBeat = 0

    fun startScore(notes: List<ScoreNote>, bpm: Int) {
        val synth = ScoreToneSynth(notes, bpm)
        synchronized(lock) {
            if (closed) return
            requestedHz = null
            scoreBeat = 0
            scoreSynth = synth
            lock.notifyAll()
        }
    }

    fun stopScore() {
        synchronized(lock) {
            scoreSynth = null
            requestedHz = null
            lock.notifyAll()
        }
    }

    fun currentScoreBeat(): Int = scoreBeat
    private val appContext = context.applicationContext
    private val worker = Thread({ audioLoop() }, "utamaru-note-preview").apply {
        isDaemon = true
        start()
    }

    fun play(hz: Double, durationMs: Int = 1000) {
        if (hz !in 30.0..2500.0 || closed) return
        synchronized(lock) {
            if (closed) return
            scoreSynth = null
            requestedHz = hz
            expiresAtNs = System.nanoTime() + durationMs.coerceIn(20, 10_000) * 1_000_000L
            lock.notifyAll()
        }
    }

    fun silence() {
        synchronized(lock) {
            requestedHz = null
            lock.notifyAll()
        }
    }

    override fun close() {
        closed = true
        synchronized(lock) {
            requestedHz = null
            scoreSynth = null
            lock.notifyAll()
        }
        worker.interrupt()
    }

    private fun audioLoop() {
        // Keep audio rendering independent from redraws and touch handling.
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
        val sampleRate = 48_000
        val block = 480 // 10 ms; allows pitch changes to be heard quickly
        var track: AudioTrack? = null
        try {
            val minBytes = AudioTrack.getMinBufferSize(sampleRate,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minBytes > 0) { "AudioTrack unsupported" }
            val output = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(maxOf(minBytes, block * 2 * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track = output
            val manager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                ?.let { output.setPreferredDevice(it) }
            // A low note can be inaudible on the phone's small speaker even at full PCM level.
            // Use the MEDIA volume control and an audible overtone-rich timbre.
            output.setVolume(0.65f)

            val pcm = ShortArray(block)
            var phase = 0.0
            var currentHz = 0.0
            var gain = 0.0
            val weights = DoubleArray(10)
            // Start once and output silence between previews to avoid clicks on low notes.
            output.play()
            while (!closed) {
                val score = synchronized(lock) { scoreSynth }
                if (score != null) {
                    score.render(pcm)
                    scoreBeat = score.currentBeat
                    val wrote = output.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
                    if (wrote < 0) throw IllegalStateException("AudioTrack score write failed: $wrote")
                    continue
                }
                val hz = synchronized(lock) {
                    if (System.nanoTime() >= expiresAtNs) requestedHz = null
                    requestedHz
                }
                if (hz != null && currentHz == 0.0) currentHz = hz
                val desiredGain = if (hz != null) 1.0 else 0.0
                val desiredHz = hz ?: currentHz
                // Compute spectral weights once per 10ms block, not on the audio thread's
                // every sample. A gentle virtual-speaker high-pass model favors overtones.
                val weightingHz = if (desiredHz > 0.0) desiredHz else 220.0
                var totalWeight = 0.0
                for (h in 1..weights.size) {
                    val partialHz = weightingHz * h
                    val bassResponse = (partialHz * partialHz) / (partialHz * partialHz + 190.0 * 190.0)
                    val highRolloff = 1.0 / (1.0 + (partialHz / 2300.0).pow(4.0))
                    val w = bassResponse * highRolloff / h.toDouble().pow(0.72)
                    weights[h - 1] = w
                    totalWeight += w
                }
                for (i in pcm.indices) {
                    // Phase is never reset, including when dragging C3 -> C#3 -> D3.
                    currentHz += (desiredHz - currentHz) / 384.0
                    phase += 2.0 * PI * currentHz / sampleRate
                    if (phase >= 2.0 * PI) phase -= 2.0 * PI
                    val rate = if (desiredGain > gain) 1.0 / 480.0 else 1.0 / 960.0
                    gain = if (desiredGain > gain) (gain + rate).coerceAtMost(1.0)
                           else (gain - rate).coerceAtLeast(0.0)
                    if (gain <= 0.0) {
                        pcm[i] = 0
                    } else {
                        var wave = 0.0
                        for (h in 1..weights.size) wave += sin(phase * h) * weights[h - 1]
                        // Normalize peak sum to avoid clipping while the low fundamental
                        // is reconstructed perceptually from the upper harmonics.
                        val signal = wave / totalWeight.coerceAtLeast(1.0e-8)
                        pcm[i] = (signal * 27000.0 * gain).toInt()
                            .coerceIn(-32768, 32767).toShort()
                    }
                }
                val written = output.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
                if (written < 0) throw IllegalStateException("AudioTrack write failed: $written")
            }
        } catch (e: Exception) {
            if (!closed) Log.w("UtaMaru", "Note preview unavailable", e)
        } finally {
            try { track?.stop() } catch (_: Exception) { }
            try { track?.release() } catch (_: Exception) { }
        }
    }
}
