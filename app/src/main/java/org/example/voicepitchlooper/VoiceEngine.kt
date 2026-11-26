package org.example.voicepitchlooper

/** The selected voice processing algorithm. Score and output routing are shared. */
enum class VoiceEngine(val caption: String) {
    STANDARD("標準（低遅延・一般的）"),
    IMPROVED("音質改善（推奨・PSOLA風）"),
    RUBBER_BAND("Stretcher（Rubber Band）"),
    SIGNALSMITH("Signalsmith（+フォルマント補正）")
}

/** Both native 24kHz engines use the same audio path. */
fun VoiceEngine.usesPitchEngine24(): Boolean = when (this) {
    VoiceEngine.RUBBER_BAND, VoiceEngine.SIGNALSMITH -> true
    else -> false
}
