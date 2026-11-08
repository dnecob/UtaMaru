package org.example.voicepitchlooper

/** The selected voice processing algorithm. Score and output routing are shared. */
enum class VoiceEngine(val caption: String) {
    STANDARD("標準（低遅延・破裂音対策済み）"),
    IMPROVED("音質改善（PSOLA風）"),
    RUBBER_BAND("Rubber Band（フォルマント維持）")
}
