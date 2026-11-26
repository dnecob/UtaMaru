package org.example.voicepitchlooper

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.tan

/**
 * Score-relative output high-pass used by all pitch-conversion engines.
 *
 * 48kHz mono, 14th-order inverse-Chebyshev. Percent is the stop-band
 * frequency as a proportion of the output score note's fundamental.
 * 0% is a true bypass. Parameter changes crossfade over 20ms to avoid clicks.
 * Source-mic/direct monitoring and test tones are deliberately excluded.
 */
internal class ScoreHighPass {
    private val rate = 48000.0

    private class Section(
        private val b0: Double, private val b1: Double, private val b2: Double,
        private val a1: Double, private val a2: Double
    ) {
        private var z1 = 0.0
        private var z2 = 0.0
        fun apply(x: Double): Double {
            val y = b0 * x + z1
            z1 = b1 * x - a1 * y + z2
            z2 = b2 * x - a2 * y
            return y
        }
    }

    private class Cascade(private val sections: Array<Section>) {
        fun apply(x: Double): Double {
            var y = x
            for (section in sections) y = section.apply(y)
            return y
        }
    }

    // Each row: normalized analogue pole real/imag, and zero imag frequency.
    // Form into digital SOS with bilinear transform at a note's stop frequency.
    private val prototype = arrayOf(
        doubleArrayOf(-0.048367201829766858, 1.0824681826876956, 0.9937122098932426),
        doubleArrayOf(-0.14267627202571015, 1.02818870801424, 0.94388333030836735),
        doubleArrayOf(-0.22983095817805588, 0.92235155817880388, 0.84672419922828424),
        doubleArrayOf(-0.30546096039685011, 0.77026384981158624, 0.70710678118654746),
        doubleArrayOf(-0.36577387456745558, 0.57955189567308307, 0.53203207651533657),
        doubleArrayOf(-0.40774535932856132, 0.35977878948756759, 0.3302790619551671),
        doubleArrayOf(-0.42927078917658801, 0.12196487249175317, 0.11196447610330787)
    )

    private fun build(scoreHz: Double, percent: Int): Cascade {
        val stopHz = (percent / 100.0 * scoreHz).coerceIn(5.0, rate * 0.35)
        val warped = 2.0 * rate * tan(PI * stopHz / rate)
        val twoRate = 2.0 * rate
        return Cascade(Array(prototype.size) { i ->
            val p = prototype[i]
            val pr = p[0] * warped
            val pi = p[1] * warped
            val zi = p[2] * warped
            val denomP = (twoRate - pr) * (twoRate - pr) + pi * pi
            val pdRe = (twoRate * twoRate - pr * pr - pi * pi) / denomP
            val pdIm = 2.0 * twoRate * pi / denomP
            val denomZ = twoRate * twoRate + zi * zi
            val zdRe = (twoRate * twoRate - zi * zi) / denomZ
            val zdIm = 2.0 * twoRate * zi / denomZ
            val a1 = -2.0 * pdRe
            val a2 = pdRe * pdRe + pdIm * pdIm
            val b1 = -2.0 * zdRe
            val b2 = zdRe * zdRe + zdIm * zdIm
            val gain = (1.0 - a1 + a2) / (1.0 - b1 + b2)
            Section(gain, gain * b1, gain * b2, a1, a2)
        })
    }

    private data class Key(val scoreHz: Double?, val percent: Int)
    private class Branch(val key: Key, val cascade: Cascade?) {
        fun apply(input: Double): Double = cascade?.apply(input) ?: input
    }

    private var current: Branch? = null
    private var incoming: Branch? = null
    private var fadeProgress = 0
    private val fadeSamples = 960  // 20ms at 48kHz

    /**
     * True only while filtered audio still needs processing to fade to bypass.
     * A stable 0% bypass is never sent to process(), so prepare its state here;
     * this also lets a subsequent 0% -> nonzero change crossfade from dry audio.
     * Called only from the audio worker thread.
     */
    fun needsFadeOut(): Boolean {
        if (current == null) current = Branch(Key(null, 0), null)
        return current?.cascade != null || incoming != null
    }

    fun process(samples: ShortArray, length: Int, scoreHz: Double?, percent: Int) {
        require(length in 0..samples.size)
        require(percent in 0..90 && percent % 5 == 0)
        val validNote = scoreHz?.takeIf { it.isFinite() && it > 0.0 }
        // Never alter RAW-voice rests or silence, even during a transition.
        if (validNote == null) {
            current = null
            incoming = null
            fadeProgress = 0
            return
        }
        val desired = if (percent == 0) Key(null, 0) else Key(validNote, percent)
        if ((incoming ?: current)?.key != desired) {
            // If another change arrives before a previous fade finishes,
            // retain the dominant branch and transition from there.
            if (incoming != null) {
                if (fadeProgress >= fadeSamples / 2) current = incoming
                incoming = null
            }
            if (current?.key != desired) {
                val next = Branch(desired,
                    if (percent == 0) null else build(validNote, percent))
                if (current == null) current = next
                else {
                    incoming = next
                    fadeProgress = 0
                }
            }
        }
        if (current?.cascade == null && incoming == null) return
        for (i in 0 until length) {
            val x = samples[i].toDouble() / 32768.0
            val oldOut = current!!.apply(x)
            val next = incoming
            val y = if (next == null) oldOut else {
                val newOut = next.apply(x)
                fadeProgress++
                val t = (fadeProgress.toDouble() / fadeSamples).coerceIn(0.0, 1.0)
                if (fadeProgress >= fadeSamples) {
                    current = next
                    incoming = null
                }
                (1.0 - t) * oldOut + t * newOut
            }
            samples[i] = (y * 32768.0).roundToInt().coerceIn(-32768, 32767).toShort()
        }
    }
}
