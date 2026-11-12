package org.example.voicepitchlooper

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioDeviceInfo
import android.os.Build
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Process
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.max

enum class OutputTarget { ANDROID, PC }

/** Prototype pipeline. Only one destination per run: Android media output or a PC TCP receiver. */
class AudioEngine(
    private val onStatus: (AudioStatus) -> Unit,
    private val onError: (String) -> Unit,
    private val onStopped: () -> Unit
) {
    companion object {
        const val RATE = 48000
        const val PC_PORT = 50005
        private const val FRAME = 960  // exactly 20 ms of 48kHz 16-bit mono PCM
    }

    data class AudioStatus(
        val note: String,
        val targetHz: Double?,
        val detectedHz: Double?,
        val ratio: Double?,
        val route: String,
        val mode: String,
        val qualityMode: String,
        val noiseGate: String,
        val naturalVoice: String,
        val routedToPhoneSpeaker: Boolean = false
    )

    @Volatile private var running = false
    @Volatile private var outputBoostDb = 0

    /** May be changed by the main-thread slider while the audio worker runs. */
    fun setOutputBoostDb(value: Int) {
        require(value in 0..OutputBoostProcessor.MAX_BOOST_DB)
        outputBoostDb = value
    }
    private var worker: Thread? = null

    @Synchronized
    fun start(notes: List<ScoreNote>, bpm: Int, voiceMode: Boolean,
              outputTarget: OutputTarget = OutputTarget.ANDROID, pcHost: String = "127.0.0.1",
              voiceEngine: VoiceEngine = VoiceEngine.STANDARD, noiseGateEnabled: Boolean = true,
              restMode: RestPlaybackMode = RestPlaybackMode.HOLD_LAST,
              pauseScoreOnSilence: Boolean = true,
              maxPitchRatio: Double = 3.5, minPitchRatio: Double = 0.5,
              nearUnityEnabled: Boolean = false, originalMonitor: Boolean = false,
              directMic: Boolean = false) {
        check(worker?.isAlive != true) { "音声処理の停止中です。もう一度試してください。" }
        require((notes.isNotEmpty() || (voiceMode && directMic)) && bpm in 30..500)
        require(!directMic || voiceMode) { "生マイクは音声入力モードのみ有効です" }
        PitchRange(minPitchRatio, maxPitchRatio) // validate before starting worker
        if (voiceMode && !directMic && voiceEngine == VoiceEngine.RUBBER_BAND) {
            check(RubberBandNative.isAvailable) {
                "Rubber Bandが未導入です。SETUP_RUBBERBAND.bat実行後にAPKを再ビルドしてください。"
            }
        }
        if (outputTarget == OutputTarget.PC) require(pcHost.isNotBlank()) { "PCの接続先を入力してください" }
        running = true
        worker = Thread {
            try {
                runAudio(notes, bpm, voiceMode, outputTarget, pcHost, voiceEngine,
                    noiseGateEnabled, restMode, pauseScoreOnSilence, maxPitchRatio, minPitchRatio,
                    nearUnityEnabled, originalMonitor, directMic)
            } catch (e: Exception) {
                if (running) onError("${e.javaClass.simpleName}: ${e.message ?: "不明なエラー"}")
            } finally {
                running = false
                onStopped()
            }
        }.apply { name = "VoicePitchAudio"; start() }
    }

    fun stop() { running = false }

    @SuppressLint("MissingPermission")  // RECORD_AUDIO checked by MainActivity.
    private fun runAudio(notes: List<ScoreNote>, bpm: Int, voiceMode: Boolean,
                         outputTarget: OutputTarget, pcHost: String, voiceEngine: VoiceEngine,
                         noiseGateEnabled: Boolean, restMode: RestPlaybackMode,
                         pauseScoreOnSilence: Boolean,
                         maxPitchRatio: Double, minPitchRatio: Double,
                         nearUnityEnabled: Boolean, originalMonitor: Boolean,
                         directMic: Boolean) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        var track: AudioTrack? = null
        var record: AudioRecord? = null
        var pcSocket: Socket? = null
        var rubberShifter: RubberBandNative? = null
        try {
            if (outputTarget == OutputTarget.ANDROID) {
                val format = AudioFormat.Builder()
                    .setSampleRate(RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
                val minSize = AudioTrack.getMinBufferSize(
                    RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                require(minSize > 0) { "この機器は48kHzモノラル出力に非対応です" }
                track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
                    )
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(max(minSize, FRAME * 2 * 8))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                check(track.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack初期化に失敗" }
            } else {
                // USB: adb reverse tcp:50005 tcp:50005, host = 127.0.0.1
                // Wi-Fi: host = Windows PC's IPv4 address (private LAN).
                pcSocket = Socket().apply {
                    tcpNoDelay = true
                    connect(InetSocketAddress(pcHost, PC_PORT), 2500)
                }
            }
            if (voiceMode) {
                val recordMin = AudioRecord.getMinBufferSize(
                    RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                require(recordMin > 0) { "この機器は48kHzマイク入力に非対応です" }
                record = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, max(recordMin, FRAME * 2 * 8)
                )
                check(record.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord初期化に失敗" }
            }
            // 生マイクでは音程検出・変換・楽譜処理を起動しない。
            // ノイズゲートだけは共通のON/OFF設定に従って使用する。
            val detector = if (voiceMode && !directMic) PitchDetector() else null
            val improvedShifter = if (voiceMode && !directMic && voiceEngine == VoiceEngine.IMPROVED)
                PitchShifter(maxPitchRatio, minPitchRatio) else null
            val previousShifter = if (voiceMode && !directMic && voiceEngine == VoiceEngine.STANDARD)
                LegacyPitchShifter(maxPitchRatio, minPitchRatio) else null
            if (voiceMode && !directMic && voiceEngine == VoiceEngine.RUBBER_BAND) {
                rubberShifter = RubberBandNative(RATE)
            }
            // Raw microphone uses the same output noise gate, with energy-only
            // voice activity detection (no pitch detection or pitch shifting).
            val gate = if (voiceMode && noiseGateEnabled) VoiceNoiseGate() else null
            // Voice activity is reused from the normal noise gate when enabled.
            // Otherwise keep a lightweight, independent input-level detector.
            // This is separate from output muting: pausing the score must work
            // even if "noise suppression when silent" is switched off.
            val scoreActivityDetector = if (voiceMode && !directMic && pauseScoreOnSilence && gate == null)
                VoiceNoiseGate() else null
            val pauseScoreClock = voiceMode && !directMic && pauseScoreOnSilence
            // Wet/dry latency is known to be 8192 samples in the two Kotlin
            // shifters. Rubber Band has its own variable internal delay.
            val naturalBypass = if (voiceMode && !directMic && voiceEngine != VoiceEngine.RUBBER_BAND)
                NaturalVoiceBypass(dryLevel = if (voiceEngine == VoiceEngine.STANDARD) 0.82 else 0.90)
                else null
            val restMixer = if (voiceMode && !directMic) RestOutputMixer(matchImprovedLevel = voiceEngine != VoiceEngine.STANDARD) else null
            val heldNotes = if (voiceMode && !directMic && restMode == RestPlaybackMode.HOLD_LAST)
                RestPlaybackPlan.holdLast(notes) else notes
            val captured = ShortArray(FRAME)
            val rawNote = ScoreNote("—", null)
            val networkFrame = ByteArray(FRAME * 2)
            val outputBooster = OutputBoostProcessor()
            val netOut = pcSocket?.getOutputStream()
            var lastPitch: Double? = null
            var lastPitchNs = 0L
            var lastRatio = 1.0
            var sampleCount = 0L
            // Separate from the microphone clock. Audio capture and output
            // continue uninterrupted while the score clock is paused.
            var scoreSampleCount = 0L
            var scoreVoiceHoldFrames = 0  // 5 x 20ms quiet-frame grace period
            val testSynth = if (voiceMode) null else ScoreToneSynth(notes, bpm, RATE)
            // Advance the score using the actual PCM sample clock, not wall time.
            // This keeps all notes equally long even when audio/network writes block.
            val samplesPerStep = RATE * 60.0 / bpm
            val startNs = System.nanoTime()
            var lastStatusNs = 0L
            record?.startRecording()
            track?.play()
            while (running) {
                val count: Int
                if (voiceMode) {
                    count = record!!.read(captured, 0, FRAME, AudioRecord.READ_BLOCKING)
                    if (count <= 0) error("マイクの読み取りエラー: $count")
                    if (!directMic) detector!!.push(captured, count)
                } else {
                    count = FRAME
                }
                val now = System.nanoTime()
                if (voiceMode && !directMic && sampleCount % (FRAME * 3) == 0L) {
                    val candidate = detector!!.detect()
                    if (candidate != null && (lastPitch == null ||
                        candidate / lastPitch!! in 0.53..1.89 ||
                        now - lastPitchNs > 400_000_000L)) {
                        lastPitch = candidate
                        lastPitchNs = now
                    }
                }
                val trackedPitch = lastPitch?.takeIf { now - lastPitchNs < 400_000_000L }
                // Input RMS is evaluated once per microphone frame. No extra
                // pitch analysis is needed for the pause-on-silence option.
                var inputRms = 0.0
                if (voiceMode && (gate != null || pauseScoreClock) && count > 0) {
                    var power = 0.0
                    for (i in 0 until count) {
                        val sample = captured[i].toDouble() / 32768.0
                        power += sample * sample
                    }
                    inputRms = kotlin.math.sqrt(power / count)
                }
                val earlyVoice = gate?.frameHasVoice(inputRms, trackedPitch != null) ?: false
                // Keep the output gate independent from score activity. When
                // the noise gate is OFF, enabling the score pause option must
                // not alter the standard shifter's existing onset/mute logic.
                val scoreVoice = if (!pauseScoreClock) false else if (gate != null) earlyVoice
                    else scoreActivityDetector!!.frameHasVoice(inputRms, trackedPitch != null)
                // Start advancing on the FIRST voice-bearing input frame, not
                // after YIN settles. After voice ends, tolerate a brief gap
                // so natural syllable transitions do not chatter the clock.
                if (pauseScoreClock && scoreVoice) scoreVoiceHoldFrames = 5
                val advanceScore = !pauseScoreClock || scoreVoiceHoldFrames > 0
                val scoreClock = if (pauseScoreClock) scoreSampleCount else sampleCount
                val step = if (directMic) 0L else (scoreClock / samplesPerStep).toLong()
                val noteIndex = if (directMic) 0 else (step % notes.size).toInt()
                val note = if (directMic) rawNote else notes[noteIndex]
                val targetNote = if (directMic) note else heldNotes[noteIndex]
                val restSelection = when {
                    note.frequency != null || restMode == RestPlaybackMode.HOLD_LAST ->
                        RestOutputMixer.Selection.SHIFTED
                    restMode == RestPlaybackMode.RAW_VOICE -> RestOutputMixer.Selection.RAW
                    else -> RestOutputMixer.Selection.SILENCE
                }
                val output: ShortArray
                val ratio: Double?
                if (voiceMode) {
                    if (!directMic && trackedPitch != null && targetNote.frequency != null)
                        lastRatio = (targetNote.frequency / trackedPitch).coerceIn(minPitchRatio, maxPitchRatio)
                    ratio = if (!directMic && trackedPitch != null && targetNote.frequency != null) lastRatio else null
                    output = if (directMic) captured else when (voiceEngine) {
                        VoiceEngine.IMPROVED -> improvedShifter!!.process(captured, count, lastRatio,
                            mute = targetNote.frequency == null, inputFundamentalHz = trackedPitch)
                        VoiceEngine.STANDARD -> previousShifter!!.process(captured, count, lastRatio,
                            // With the gate enabled, let clear speech onsets
                            // through even while YIN collects its first period.
                            // The old behaviour is unchanged when gate is OFF.
                            mute = targetNote.frequency == null ||
                                (trackedPitch == null && !earlyVoice),
                            inputFundamentalHz = trackedPitch)
                        VoiceEngine.RUBBER_BAND -> rubberShifter!!.process(captured, count, lastRatio)
                    }
                } else {
                    ratio = null
                    output = ShortArray(count)
                    // Sample-accurate note boundaries, even for BPMs whose beat size
                    // does not divide a 960-sample output frame.
                    testSynth!!.render(output, count)
                }
                // Perform voice-preserving interpolation BEFORE the existing noise
                // gate and rest mixer so neither output stage is bypassed.
                if (voiceMode && !directMic && naturalBypass != null) {
                    naturalBypass.process(captured, output, count,
                        pitchRatio = ratio?.takeIf { targetNote.frequency != null },
                        nearUnityEnabled = nearUnityEnabled,
                        originalMonitor = originalMonitor)
                }
                var voiceDetected = false
                if (voiceMode && directMic) {
                    // Process raw PCM in place before final gain and PC/Android output.
                    // OFF leaves captured samples untouched, as in the old raw mode.
                    gate?.process(output, count, voiceDetected = earlyVoice)
                } else if (voiceMode) {
                    // Reuse the RMS already calculated for the pause/noise
                    // gate; only scan here when both options are disabled.
                    val rms = if (gate != null || pauseScoreClock) inputRms else {
                        var sumSquares = 0.0
                        for (i in 0 until count) {
                            val v = captured[i].toDouble() / 32768.0
                            sumSquares += v * v
                        }
                        kotlin.math.sqrt(sumSquares / count)
                    }
                    voiceDetected = trackedPitch != null && rms >= 0.008
                    gate?.process(output, count, voiceDetected = earlyVoice)
                    // Preserve the old stricter pitch-qualified detection for
                    // dry/wet volume matching; do not pump up unpitched noise.
                    restMixer!!.mix(output, captured, count, restSelection,
                        voiceDetected = voiceDetected)
                }
                // Same final gain for converted voice, raw microphone and test
                // tones, regardless of Android speaker or Windows receiver.
                // No processing at the default 0 dB setting.
                outputBooster.process(output, count, outputBoostDb)
                if (outputTarget == OutputTarget.ANDROID) {
                    var offset = 0
                    while (running && offset < count) {
                        val wrote = track!!.write(output, offset, count - offset, AudioTrack.WRITE_BLOCKING)
                        if (wrote <= 0) error("スピーカーへの出力エラー: $wrote")
                        offset += wrote
                    }
                } else {
                    // Fixed frame size for Windows receiver; pad any partial microphone read with silence.
                    for (i in 0 until FRAME) {
                        val v = if (i < count) output[i].toInt() else 0
                        networkFrame[i * 2] = v.toByte()
                        networkFrame[i * 2 + 1] = (v ushr 8).toByte()
                    }
                    netOut!!.write(networkFrame)
                    // In test mode there is no AudioRecord blocking clock: pace at 20 ms per frame.
                    if (!voiceMode) {
                        val targetNs = startNs + ((sampleCount + FRAME) * 1_000_000_000L / RATE)
                        val remainingMs = (targetNs - System.nanoTime()) / 1_000_000L
                        if (remainingMs > 0) Thread.sleep(remainingMs.coerceAtMost(100L))
                    }
                }
                if (pauseScoreClock) {
                    if (advanceScore) scoreSampleCount += count
                    if (!scoreVoice && scoreVoiceHoldFrames > 0) scoreVoiceHoldFrames--
                }
                sampleCount += count
                if (now - lastStatusNs > 150_000_000L) {
                    val routedDevices = if (outputTarget == OutputTarget.ANDROID &&
                        Build.VERSION.SDK_INT >= 36) track?.routedDevices.orEmpty() else
                        listOfNotNull(track?.routedDevice)
                    val route = if (outputTarget == OutputTarget.PC) "PC: $pcHost:$PC_PORT" else
                        routedDevices.firstOrNull()?.let { "${it.productName} (type ${it.type})" }
                            ?: "Androidで選択したメディア出力先（確認中）"
                    val internalSpeaker = routedDevices.any {
                        it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER ||
                        it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE
                    }
                    val shownNote = if (voiceMode && !directMic && note.frequency == null &&
                        restMode == RestPlaybackMode.HOLD_LAST)
                        "休符 → ${targetNote.label}を継続" else note.label
                    onStatus(AudioStatus(shownNote,
                        if (voiceMode && !directMic) targetNote.frequency else if (!voiceMode) note.frequency else null,
                        if (voiceMode && !directMic) trackedPitch else null,
                        ratio, route,
                        if (directMic) "生マイク" else if (voiceMode) "マイク変換" else "テスト音",
                        if (directMic) "無変換" else voiceEngine.caption,
                        if (gate == null) "OFF" else "ON",
                        if (directMic) "音声加工なし" else when {
                            naturalBypass == null -> "Rubber Band対象外"
                            originalMonitor -> "原音比較"
                            nearUnityEnabled -> "自然声優先"
                            else -> "従来処理"
                        }, internalSpeaker))
                    lastStatusNs = now
                }
            }
        } finally {
            try { rubberShifter?.close() } catch (_: Exception) { }
            try { record?.stop() } catch (_: Exception) { }
            record?.release()
            try { track?.pause(); track?.flush(); track?.stop() } catch (_: Exception) { }
            track?.release()
            try { pcSocket?.close() } catch (_: Exception) { }
        }
    }
}
