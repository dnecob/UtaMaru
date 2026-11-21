package org.example.voicepitchlooper

import java.util.ArrayDeque
import kotlin.math.sqrt

/**
 * Bounded, thread-safe PCM FIFO between the continuously running microphone
 * capture thread and the audio-processing/output thread (phone or PC).
 *
 * Keep 2 future 20-ms frames for a 40-ms output-gate lookahead, preserving
 * quiet consonants just ahead of clearly voiced frames. Audio samples remain
 * untouched. The existing noise-gate thresholds and coefficients are unchanged.
 */
internal class VoiceInputLookahead(
    private val frameSamples: Int,
    private val lookaheadFrames: Int = 2,
    private val maxFrames: Int = 32
) {
    init {
        require(frameSamples > 0)
        require(lookaheadFrames in 1..8)
        require(maxFrames > lookaheadFrames + 1)
    }

    data class Frame(val samples: ShortArray, val rms: Double, val futurePeakRms: Double)
    private data class Entry(val samples: ShortArray, val rms: Double)
    private val pending = ArrayDeque<Entry>(maxFrames)
    private val lock = java.lang.Object()
    private var finished = false
    private var failure: Exception? = null
    private var droppedFrames = 0L

    /** Called only by the microphone capture thread. */
    fun enqueue(samples: ShortArray) {
        require(samples.size == frameSamples) { "Microphone frame must be complete" }
        var sum = 0.0
        for (sample in samples) {
            val normalized = sample.toDouble() / 32768.0
            sum += normalized * normalized
        }
        synchronized(lock) {
            if (finished) return
            // When output stalls, keep latency bounded and preserve the latest
            // live mic frames rather than building an unbounded playback delay.
            if (pending.size >= maxFrames) {
                pending.removeFirst()
                droppedFrames++
            }
            pending.addLast(Entry(samples, sqrt(sum / frameSamples)))
            lock.notifyAll()
        }
    }

    /** Called by processing thread. Null means capture has stopped. */
    fun take(): Frame? = synchronized(lock) {
        while (pending.size <= lookaheadFrames && !finished && failure == null) {
            lock.wait()
        }
        failure?.let { throw it }
        if (finished || pending.size <= lookaheadFrames) return@synchronized null
        val oldest = pending.removeFirst()
        var futureMax = 0.0
        var scanned = 0
        for (entry in pending) {
            if (entry.rms > futureMax) futureMax = entry.rms
            if (++scanned >= lookaheadFrames) break
        }
        Frame(oldest.samples, oldest.rms, futureMax)
    }

    fun droppedFrameCount(): Long = synchronized(lock) { droppedFrames }

    fun fail(error: Exception) = synchronized(lock) {
        if (!finished) failure = error
        finished = true
        lock.notifyAll()
    }

    fun finish() = synchronized(lock) {
        finished = true
        pending.clear()
        lock.notifyAll()
    }
}
