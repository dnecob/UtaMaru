package org.example.voicepitchlooper

/**
 * JNI wrapper over Rubber Band Library 4.0 RubberBandLiveShifter.
 * Use only from the AudioEngine's single audio worker thread.
 * The native implementation adapts the 960-sample mic frames to the library's fixed block size.
 * The library is optional until third_party/rubberband-4.0.0 is installed and the APK rebuilt.
 */
class RubberBandNative(sampleRate: Int = 48000) : AutoCloseable {
    companion object {
        val isAvailable: Boolean by lazy {
            try {
                System.loadLibrary("utamaru_rubberband")
                true
            } catch (_: UnsatisfiedLinkError) {
                false
            } catch (_: SecurityException) {
                false
            }
        }
    }

    private var nativeHandle: Long

    init {
        check(isAvailable) {
            "Rubber Bandが未導入です。PCでSETUP_RUBBERBAND.batを実行してAPKを再ビルドしてください。"
        }
        nativeHandle = nativeCreate(sampleRate)
        check(nativeHandle != 0L) { "Rubber Bandの初期化に失敗しました" }
    }

    fun process(samples: ShortArray, length: Int, pitchRatio: Double): ShortArray {
        require(length in 0..samples.size && length <= 960)
        check(nativeHandle != 0L) { "Rubber Bandは終了しています" }
        val out = ShortArray(length)
        val result = nativeProcess(nativeHandle, samples, length, pitchRatio, out)
        check(result == 0) { "Rubber Bandの音声処理エラー: $result" }
        return out
    }

    override fun close() {
        if (nativeHandle != 0L) {
            nativeDestroy(nativeHandle)
            nativeHandle = 0L
        }
    }

    private external fun nativeCreate(sampleRate: Int): Long
    private external fun nativeProcess(
        handle: Long, input: ShortArray, length: Int, ratio: Double, output: ShortArray
    ): Int
    private external fun nativeDestroy(handle: Long)
}
