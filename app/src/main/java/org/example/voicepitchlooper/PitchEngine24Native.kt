package org.example.voicepitchlooper

/** Native real-time 24kHz engines. Exactly 480 samples (20ms) per call.
 * Both engines use a common 200ms output timeline (9600 samples at 48kHz).
 * Requires Rubber Band 4.0.0 and Signalsmith Stretch + Linear from official sources.
 */
internal class PitchEngine24Native(engine: VoiceEngine) : AutoCloseable {
    companion object {
        val isAvailable: Boolean by lazy {
            try {
                System.loadLibrary("utamaru_pitch24_variants")
                true
            } catch (_: UnsatisfiedLinkError) {
                false
            } catch (_: SecurityException) {
                false
            }
        }
        const val OUTPUT_DELAY_48K = 9600
    }
    private var handle: Long
    init {
        require(engine.usesPitchEngine24())
        check(isAvailable) { "24kHz音声エンジンが未導入です。Android StudioでAPKを再ビルドしてください。" }
        // Keep the tested formant processing (native ID 2). The legacy
        // uncorrected (ID 1) and harmonic-exciter (ID 3) variants are not selected.
        val engineId = when (engine) {
            VoiceEngine.RUBBER_BAND -> 0
            VoiceEngine.SIGNALSMITH -> 2
            else -> error("unsupported native engine")
        }
        // Must match the native library shipped with the Signalsmith variants.
        // A previous library knew only IDs 0/1 and silently returned 0 for IDs 2/3.
        check(nativeCapabilities24V2() and (1 shl engineId) != 0) {
            "24kHzネイティブエンジンが旧版です。APKを再ビルドしてインストールしてください。"
        }
        handle = nativeCreate24V2(engineId)
        check(handle != 0L) {
            "24kHz音声エンジンの初期化に失敗 (mode=$engineId)。LogcatのUtaMaruPitch24を確認してください。"
        }
    }
    fun process(input: ShortArray, ratio: Double): ShortArray {
        require(input.size == VoiceRateBridge24.ENGINE_FRAME)
        check(handle != 0L)
        val output = ShortArray(VoiceRateBridge24.ENGINE_FRAME)
        val error = nativeProcess24V2(handle, input, ratio, output)
        check(error == 0) { "24kHz音声エンジンエラー: $error" }
        return output
    }
    override fun close() {
        if (handle != 0L) { nativeDestroy24V2(handle); handle = 0L }
    }
    private external fun nativeCapabilities24V2(): Int
    private external fun nativeCreate24V2(engineId: Int): Long
    private external fun nativeProcess24V2(handle: Long, input: ShortArray,
                                           ratio: Double, output: ShortArray): Int
    private external fun nativeDestroy24V2(handle: Long)
}
