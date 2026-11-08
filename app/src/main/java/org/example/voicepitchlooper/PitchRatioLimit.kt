package org.example.voicepitchlooper

/** Maximum upward pitch-shift ratio; the lower bound remains 0.5x. */
enum class PitchRatioLimit(val maximum: Double, val caption: String) {
    STANDARD(3.5, "3.5倍（標準）"),
    WIDE(5.0, "5倍（拡張・実験）"),
    EXTREME(7.0, "7倍（広音域・実験）");

    fun clamp(value: Double): Double = value.coerceIn(0.5, maximum)
}
