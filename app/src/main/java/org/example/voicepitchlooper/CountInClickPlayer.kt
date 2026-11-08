package org.example.voicepitchlooper

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/** One clocked PCM stream; audio and UI count use the actual playback-head position. */
class CountInClickPlayer(
    private val context: Context,
    private val bpm: Int,
    private val onCount: (Int) -> Unit,
    private val onFinished: (String?) -> Unit
) {
    @Volatile private var cancelled = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var worker: Thread? = null

    fun start() {
        check(worker == null)
        worker = Thread({ playClicks() }, "utamaru-count-in").also { it.start() }
    }

    fun cancel() {
        cancelled = true
        worker?.interrupt()
    }

    private fun playClicks() {
        var track: AudioTrack? = null
        var failure: String? = null
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            val synth = CountInClickSynth(bpm)
            val rate = synth.sampleRate
            val minBytes = AudioTrack.getMinBufferSize(
                rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minBytes > 0) { "48kHzの音声出力に対応していません" }
            val output = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder()
                    .setSampleRate(rate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(maxOf(minBytes, 480 * 2 * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track = output
            check(output.state == AudioTrack.STATE_INITIALIZED) { "音声出力を初期化できません" }
            val manager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                ?.let { output.setPreferredDevice(it) }
            output.setVolume(0.7f)
            output.play()

            val buffer = ShortArray(480)  // 10ms
            var queued = 0
            var announced = 0
            fun playedFrames(): Long = output.playbackHeadPosition.toLong() and 0xFFFF_FFFFL
            fun announce() {
                val heard = playedFrames()
                while (announced < CountInClickSynth.COUNT_IN_BEATS && synth.beatStarts[announced] <= heard) {
                    val remaining = CountInClickSynth.COUNT_IN_BEATS - announced
                    announced++
                    mainHandler.post { if (!cancelled) onCount(remaining) }
                }
            }
            while (!cancelled && queued < synth.totalFrames) {
                val count = minOf(buffer.size, synth.totalFrames - queued)
                synth.render(queued, buffer, count)
                var offset = 0
                while (!cancelled && offset < count) {
                    val written = output.write(buffer, offset, count - offset, AudioTrack.WRITE_BLOCKING)
                    check(written > 0) { "カウント音の再生に失敗しました ($written)" }
                    offset += written
                }
                queued += offset
                announce()
            }
            // AudioTrack.write queues samples; do not start the microphone until
            // the actual playback head has finished all eight beats.
            val deadline = SystemClock.elapsedRealtime() + 2_000L
            while (!cancelled && playedFrames() < synth.totalFrames) {
                announce()
                check(SystemClock.elapsedRealtime() < deadline) { "カウント音の再生が停止しました" }
                Thread.sleep(5L)
            }
            if (!cancelled) {
                announce()
                // Give the speaker path a small tail so its last click won't enter the mic.
                Thread.sleep(50L)
            }
        } catch (e: InterruptedException) {
            if (!cancelled) failure = "再生が中断されました"
        } catch (e: Exception) {
            if (!cancelled) failure = e.message ?: "カウント音を再生できません"
        } finally {
            try { track?.pause() } catch (_: Exception) { }
            try { track?.flush() } catch (_: Exception) { }
            try { track?.release() } catch (_: Exception) { }
        }
        if (!cancelled) mainHandler.post { if (!cancelled) onFinished(failure) }
    }
}
