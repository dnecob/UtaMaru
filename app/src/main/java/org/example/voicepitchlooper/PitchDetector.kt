package org.example.voicepitchlooper

import kotlin.math.sqrt

/** Lightweight YIN-like fundamental frequency detector, 12kHz analysis, about 85ms window. */
class PitchDetector {
    private val analysisRate = 12_000.0
    private val windowSize = 1024
    private val downsampleFactor = 4
    private val ring = FloatArray(2048)
    private var next = 0
    private var filled = 0
    private var accumulator = 0.0f
    private var decimationCounter = 0
    private val window = FloatArray(windowSize)
    private val yin = DoubleArray(172)

    fun push(samples: ShortArray, length: Int) {
        for (n in 0 until length) {
            accumulator += samples[n] / 32768.0f
            decimationCounter++
            if (decimationCounter == downsampleFactor) {
                ring[next] = accumulator / downsampleFactor
                next = (next + 1) % ring.size
                filled = (filled + 1).coerceAtMost(ring.size)
                accumulator = 0.0f
                decimationCounter = 0
            }
        }
    }

    fun detect(): Double? {
        if (filled < windowSize) return null
        var sumSquares = 0.0
        for (i in 0 until windowSize) {
            val value = ring[(next - windowSize + i + ring.size) % ring.size]
            window[i] = value
            sumSquares += value * value
        }
        if (sqrt(sumSquares / windowSize) < 0.006) return null

        val minLag = 12          // 1000 Hz
        val maxLag = 171         // ~70 Hz
        val compareLength = windowSize - maxLag
        yin[0] = 1.0
        var sumDifferences = 0.0
        var candidate = -1
        var bestLag = -1
        var bestValue = Double.POSITIVE_INFINITY
        for (lag in 1..maxLag) {
            var difference = 0.0
            for (j in 0 until compareLength) {
                val delta = (window[j] - window[j + lag]).toDouble()
                difference += delta * delta
            }
            sumDifferences += difference
            val normalized = if (sumDifferences <= 1e-12) 1.0
                             else difference * lag / sumDifferences
            yin[lag] = normalized
            if (lag < minLag) continue
            if (normalized < bestValue) { bestLag = lag; bestValue = normalized }
            if (candidate < 0 && normalized < 0.17) candidate = lag
            if (candidate >= 0 && lag > candidate && normalized > yin[lag - 1]) {
                candidate = lag - 1
                break
            }
        }
        val lag = if (candidate >= minLag) candidate
                  else if (bestValue < 0.25) bestLag else -1
        if (lag < minLag) return null
        val a = yin[(lag - 1).coerceAtLeast(1)]
        val b = yin[lag]
        val c = yin[(lag + 1).coerceAtMost(maxLag)]
        val denom = a - 2.0 * b + c
        val correction = if (kotlin.math.abs(denom) < 1e-9) 0.0
                         else ((a - c) / (2.0 * denom)).coerceIn(-1.0, 1.0)
        val hz = analysisRate / (lag + correction)
        return hz.takeIf { it in 70.0..1000.0 }
    }
}
