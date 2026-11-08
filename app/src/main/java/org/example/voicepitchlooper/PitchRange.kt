package org.example.voicepitchlooper

import kotlin.math.roundToInt

/** Independent pitch-shift bounds. 1.0x always remains inside the range. */
data class PitchRange(val minimum: Double = 0.5, val maximum: Double = 3.5) {
    init {
        require(minimum.isFinite() && minimum in 0.3..1.0) { "低音側の倍率は0.3〜1.0倍" }
        require(maximum.isFinite() && maximum in 1.0..10.0) { "高音側の倍率は1.0〜10.0倍" }
    }

    fun clamp(ratio: Double): Double = ratio.coerceIn(minimum, maximum)

    companion object {
        val DEFAULT = PitchRange()
        fun lowerStep(value: Double): Double =
            ((value.coerceIn(0.3, 1.0) * 20.0).roundToInt() / 20.0).coerceIn(0.3, 1.0)
        fun upperStep(value: Double): Double =
            ((value.coerceIn(1.0, 10.0) * 4.0).roundToInt() / 4.0).coerceIn(1.0, 10.0)
    }
}
