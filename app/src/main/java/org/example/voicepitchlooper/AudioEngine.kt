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
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.max
import java.util.ArrayDeque

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
        const val RECEIVER_NOT_STARTED = "受信プログラム(WindowsReceiver)を起動し、PCとの接続（USBまたはWi-Fi）も確認してください。"
        private const val FRAME = 960  // exactly 20 ms of 48kHz mono PCM
        private const val ENGINE_FRAME = 480 // 20 ms at 24kHz
        // Output noise-gate envelope, shared by Standard, PSOLA, Rubber Band and raw mic.
        // Timings are sample-accurate; no need to round the release constant to FRAME (20 ms).
        private const val VOICE_GATE_HOLD_MS = 100
        private const val VOICE_GATE_RELEASE_TAU_MS = 30.0
        private const val SCORE_STARTUP_GRACE_MS = 300L
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
    @Volatile private var scoreHighPassPercent = 50

    /** Common score-relative high-pass; 0% is OFF, 5..90% in 5% steps. */
    fun setScoreHighPassPercent(value: Int) {
        require(value in 0..90 && value % 5 == 0)
        scoreHighPassPercent = value
    }

    /** May be changed by the main-thread slider while the audio worker runs. */
    fun setOutputBoostDb(value: Int) {
        require(value in 0..OutputBoostProcessor.MAX_BOOST_DB)
        outputBoostDb = value
    }
    private var worker: Thread? = null
    @Volatile private var activeCapture: VoiceInputLookahead? = null

    @Synchronized
    fun start(notes: List<ScoreNote>, bpm: Int, voiceMode: Boolean,
              outputTarget: OutputTarget = OutputTarget.ANDROID, pcHost: String = "127.0.0.1",
              voiceEngine: VoiceEngine = VoiceEngine.STANDARD, noiseGateEnabled: Boolean = true,
              noiseGateThresholdPercent: Int = 100,
              restMode: RestPlaybackMode = RestPlaybackMode.HOLD_LAST,
              pauseScoreOnSilence: Boolean = true,
              maxPitchRatio: Double = 3.5, minPitchRatio: Double = 0.5,
              nearUnityEnabled: Boolean = false, originalMonitor: Boolean = false,
              directMic: Boolean = false) {
        check(worker?.isAlive != true) { "音声処理の停止中です。もう一度試してください。" }
        require((notes.isNotEmpty() || (voiceMode && directMic)) && bpm in 30..500)
        require(!directMic || voiceMode) { "生マイクは音声入力モードのみ有効です" }
        PitchRange(minPitchRatio, maxPitchRatio) // validate before starting worker
        require(noiseGateThresholdPercent in 50..300)
        if (voiceMode && !directMic &&
            voiceEngine.usesPitchEngine24()) {
            check(PitchEngine24Native.isAvailable) {
                "24kHzエンジンが未導入です。SETUP_PITCH24.bat実行後にAPKを再ビルドしてください。"
            }
        }
        if (outputTarget == OutputTarget.PC) require(pcHost.isNotBlank()) { "PCの接続先を入力してください" }
        running = true
        worker = Thread {
            try {
                runAudio(notes, bpm, voiceMode, outputTarget, pcHost, voiceEngine,
                    noiseGateEnabled, noiseGateThresholdPercent, restMode, pauseScoreOnSilence, maxPitchRatio, minPitchRatio,
                    nearUnityEnabled, originalMonitor, directMic)
            } catch (e: Exception) {
                if (running) {
                    // A refused TCP connection means nothing is listening at the
                    // selected receiver endpoint. Keep timeouts/address failures
                    // distinguishable from this specific case.
                    val message = if (outputTarget == OutputTarget.PC && e is ConnectException)
                        RECEIVER_NOT_STARTED
                    else "${e.javaClass.simpleName}: ${e.message ?: "不明なエラー"}"
                    onError(message)
                }
            } finally {
                running = false
                onStopped()
            }
        }.apply { name = "VoicePitchAudio"; start() }
    }

    fun stop() {
        running = false
        activeCapture?.finish() // wake audio worker if it is waiting on microphone PCM
    }

    @SuppressLint("MissingPermission")  // RECORD_AUDIO checked by MainActivity.
    private fun runAudio(notes: List<ScoreNote>, bpm: Int, voiceMode: Boolean,
                         outputTarget: OutputTarget, pcHost: String, voiceEngine: VoiceEngine,
                         noiseGateEnabled: Boolean, noiseGateThresholdPercent: Int,
                         restMode: RestPlaybackMode,
                         pauseScoreOnSilence: Boolean,
                         maxPitchRatio: Double, minPitchRatio: Double,
                         nearUnityEnabled: Boolean, originalMonitor: Boolean,
                         directMic: Boolean) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        var track: AudioTrack? = null
        var record: AudioRecord? = null
        var pcSocket: Socket? = null
        var native24: PitchEngine24Native? = null
        var micReader: Thread? = null
        var inputLookahead: VoiceInputLookahead? = null
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
            val rateBridge = if (voiceMode && !directMic) VoiceRateBridge24() else null
            val detector = if (voiceMode && !directMic) PitchDetector() else null
            val improvedShifter = if (voiceMode && !directMic && voiceEngine == VoiceEngine.IMPROVED)
                PitchShifter(maxPitchRatio, minPitchRatio) else null
            val previousShifter = if (voiceMode && !directMic && voiceEngine == VoiceEngine.STANDARD)
                LegacyPitchShifter(maxPitchRatio, minPitchRatio) else null
            if (voiceMode && !directMic &&
                voiceEngine.usesPitchEngine24()) {
                native24 = PitchEngine24Native(voiceEngine)
            }
            // All four microphone modes use the same post-voice envelope.
            // Keep each mode's established voice detection, PSOLA delay and
            // breathy-tail behavior unchanged; only hold/release timings change.
            val gate = if (voiceMode && noiseGateEnabled) {
                if (!directMic && voiceEngine == VoiceEngine.IMPROVED) {
                    // PSOLA is delayed by 8192 samples (~171ms).
                    VoiceNoiseGate(holdMillis = VOICE_GATE_HOLD_MS,
                        releaseMillis = VOICE_GATE_RELEASE_TAU_MS,
                        outputDelaySamples = 8192, preserveBreathyTail = true,
                        thresholdPercent = noiseGateThresholdPercent)
                } else if (directMic) {
                    // Raw microphone keeps energy-only VAD, breathy-tail
                    // continuation and its gentler noise detection thresholds.
                    VoiceNoiseGate(holdMillis = VOICE_GATE_HOLD_MS,
                        releaseMillis = VOICE_GATE_RELEASE_TAU_MS,
                        preserveBreathyTail = true, rawMicMode = true,
                        thresholdPercent = noiseGateThresholdPercent)
                } else if (!directMic && native24 != null) {
                    VoiceNoiseGate(holdMillis = VOICE_GATE_HOLD_MS,
                        releaseMillis = VOICE_GATE_RELEASE_TAU_MS,
                        outputDelaySamples = PitchEngine24Native.OUTPUT_DELAY_48K,
                        thresholdPercent = noiseGateThresholdPercent)
                } else {
                    // Standard path is unchanged.
                    VoiceNoiseGate(holdMillis = VOICE_GATE_HOLD_MS,
                        releaseMillis = VOICE_GATE_RELEASE_TAU_MS,
                        thresholdPercent = noiseGateThresholdPercent)
                }
            } else null
            // Voice activity is reused from the normal noise gate when enabled.
            // Otherwise keep a lightweight, independent input-level detector.
            // This is separate from output muting: pausing the score must work
            // even if "noise suppression when silent" is switched off.
            val scoreActivityDetector = if (voiceMode && !directMic && pauseScoreOnSilence && gate == null)
                VoiceNoiseGate() else null
            val pauseScoreClock = voiceMode && !directMic && pauseScoreOnSilence
            // Ignore tap/startup noise for the first 300ms of captured audio.
            // The score stays on its first note until this grace period ends;
            // audio capture, conversion and transmission continue normally.
            val scoreStartupGraceSamples = RATE * SCORE_STARTUP_GRACE_MS / 1000L
            // Wet/dry latency is known to be 8192 samples in the two Kotlin
            // shifters. Rubber Band has its own variable internal delay.
            val naturalBypass = if (voiceMode && !directMic && native24 == null)
                NaturalVoiceBypass(dryLevel = if (voiceEngine == VoiceEngine.STANDARD) 0.82 else 0.90)
                else null
            val restMixer = if (voiceMode && !directMic) RestOutputMixer(
                delaySamples = if (native24 != null) PitchEngine24Native.OUTPUT_DELAY_48K else 8192,
                matchImprovedLevel = voiceEngine != VoiceEngine.STANDARD) else null
            val heldNotes = if (voiceMode && !directMic && restMode == RestPlaybackMode.HOLD_LAST)
                RestPlaybackPlan.holdLast(notes) else notes
            // Rest-skipping applies only to live pitch conversion, not score
            // test tones. Keep written rests untouched in the saved score.
            val restSkipDistances = if (voiceMode && !directMic && restMode == RestPlaybackMode.SKIP_TO_NEXT)
                RestPlaybackPlan.stepsUntilNextNote(notes) else null
            // All microphone paths (raw/shifted, phone/PC) use the same
            // continuous full-frame capture and short pre-onset lookahead.
            inputLookahead = if (voiceMode) VoiceInputLookahead(FRAME, 3) else null
            activeCapture = inputLookahead
            var captured = ShortArray(FRAME)
            var captured24 = ShortArray(ENGINE_FRAME)
            val rawNote = ScoreNote("—", null)
            val networkFrame = ByteArray(FRAME * 2)
            val outputBooster = OutputBoostProcessor()
            // Shared score-relative high-pass after rest mixing, before final gain.
            // The filter runs at 48kHz for all 4 pitch-conversion engines.
            // Direct mic, dry original monitoring, and score test tones bypass it.
            val scoreHighPass = if (voiceMode && !directMic) ScoreHighPass() else null
            // Score note changes lead engine output by its conversion latency.
            // Each 20ms note entry follows the audio through the corresponding
            // shifter: ~171ms (9 frames) Kotlin engines; 200ms (10) native.
            // This preserves score-relative filtering during fast note changes.
            val scoreNoteDelayFrames = if (native24 != null) 10 else 9
            val delayedScoreNotes = ArrayDeque<Double>()
            val netOut = pcSocket?.getOutputStream()
            var lastPitch: Double? = null
            var lastPitchNs = 0L
            var lastRatio = 1.0
            var sampleCount = 0L
            // Separate from the microphone clock. Audio capture and output
            // continue uninterrupted while the score clock is paused.
            var scoreSampleCount = 0L
            // Extra musical beats passed when skipping rests; the audio clock
            // itself continues at its original pace with no skipped samples.
            var skippedRestSteps = 0L
            var scoreVoiceHoldFrames = 0  // 5 x 20ms quiet-frame grace period
            val testSynth = if (voiceMode) null else ScoreToneSynth(notes, bpm, RATE)
            // Advance the score using the actual PCM sample clock, not wall time.
            // This keeps all notes equally long even when audio/network writes block.
            val samplesPerStep = RATE * 60.0 / bpm
            val startNs = System.nanoTime()
            var lastStatusNs = 0L
            record?.startRecording()
            if (record != null) {
                val microphone = record!!
                val micBuffer = inputLookahead!!
                micReader = Thread {
                    try {
                        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                        while (running) {
                            // AudioRecord may return a short positive read, even
                            // with READ_BLOCKING. Accumulate the entire frame.
                            val frame = ShortArray(FRAME)
                            var filled = 0
                            var zeroReads = 0
                            while (running && filled < FRAME) {
                                val read = microphone.read(frame, filled, FRAME - filled,
                                    AudioRecord.READ_BLOCKING)
                                if (read < 0) error("マイクの読み取りエラー: $read")
                                if (read == 0) {
                                    if (++zeroReads >= 4) error("マイクから音声データを取得できません")
                                    continue
                                }
                                zeroReads = 0
                                filled += read
                            }
                            if (running && filled == FRAME) micBuffer.enqueue(frame)
                        }
                    } catch (e: Exception) {
                        if (running) micBuffer.fail(e)
                    } finally {
                        // Do not silently treat a stopped capture as silence.
                        // If it ended unexpectedly, the main worker exits too.
                        if (running) micBuffer.fail(
                            IllegalStateException("マイク入力が予期せず停止しました"))
                        else micBuffer.finish()
                    }
                }.apply { name = "UtaMaruMicInput"; start() }
            }
            track?.play()
            while (running) {
                val count: Int
                var bufferedInputRms = 0.0
                var upcomingInputRms = 0.0
                if (voiceMode) {
                    // Continuous producer/consumer buffer is shared by direct
                    // mic and all pitch engines, for phone and PC outputs.
                    val inputFrame = inputLookahead!!.take() ?: break
                    captured = inputFrame.samples
                    bufferedInputRms = inputFrame.rms
                    upcomingInputRms = inputFrame.futurePeakRms
                    count = FRAME
                    if (!directMic) {
                        captured24 = rateBridge!!.downsample(captured)
                        detector!!.push(captured24, ENGINE_FRAME)
                    }
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
                val inputRms = if (voiceMode) bufferedInputRms else 0.0
                val earlyVoice = gate?.frameHasVoice(inputRms, trackedPitch != null) ?: false
                // Only the TIMING of gate opening changes: upcoming frames are
                // inspected with the existing threshold, without updating VAD
                // state or changing noise-suppression strength/release settings.
                val gateVoice = earlyVoice ||
                    (gate?.hasUpcomingEnergyOnset(upcomingInputRms) ?: false)
                // Keep the output gate independent from score activity. When
                // the noise gate is OFF, enabling the score pause option must
                // not alter the standard shifter's existing onset/mute logic.
                // The pause-on-silence option is the only mode with a startup
                // grace period. Do not carry any activity/hold time detected
                // during startup into the score clock after the 300ms boundary.
                val scoreStartupGrace = pauseScoreClock && sampleCount < scoreStartupGraceSamples
                val scoreVoice = if (!pauseScoreClock || scoreStartupGrace) false
                    else if (gate != null) earlyVoice
                    else scoreActivityDetector!!.frameHasVoice(inputRms, trackedPitch != null)
                // Start advancing on the FIRST voice-bearing input frame after
                // the grace period, without waiting for YIN to settle. Once
                // speaking, allow short syllable gaps as before.
                if (pauseScoreClock && scoreVoice) scoreVoiceHoldFrames = 5
                val advanceScore = !pauseScoreClock ||
                    (!scoreStartupGrace && scoreVoiceHoldFrames > 0)
                val scoreClock = if (pauseScoreClock) scoreSampleCount else sampleCount
                // The raw-microphone path does not use score timing or rest logic.
                // Keep those computations exclusively in score-based modes.
                val note: ScoreNote
                val targetNote: ScoreNote
                if (directMic) {
                    note = rawNote
                    targetNote = rawNote
                } else {
                    var step = (scoreClock / samplesPerStep).toLong() + skippedRestSteps
                    if (restSkipDistances != null) {
                        val toSkip = restSkipDistances[(step % notes.size).toInt()]
                        if (toSkip > 0) {
                            // Jump whole rest beats, including across the loop end.
                            // Keep the fractional time of the current beat intact.
                            skippedRestSteps += toSkip
                            step += toSkip
                        }
                    }
                    val noteIndex = (step % notes.size).toInt()
                    note = notes[noteIndex]
                    targetNote = heldNotes[noteIndex]
                }
                val output: ShortArray
                val ratio: Double?
                if (voiceMode) {
                    if (!directMic && trackedPitch != null && targetNote.frequency != null)
                        lastRatio = (targetNote.frequency / trackedPitch).coerceIn(minPitchRatio, maxPitchRatio)
                    ratio = if (!directMic && trackedPitch != null && targetNote.frequency != null) lastRatio else null
                    output = if (directMic) captured else {
                        val shifted24 = when (voiceEngine) {
                        VoiceEngine.IMPROVED -> improvedShifter!!.process(captured24, ENGINE_FRAME, lastRatio,
                            mute = targetNote.frequency == null,
                            inputFundamentalHz = trackedPitch,
                            suppressRawFallback = !nearUnityEnabled && !originalMonitor)
                        VoiceEngine.STANDARD -> previousShifter!!.process(captured24, ENGINE_FRAME, lastRatio,
                            // With the gate enabled, let clear speech onsets
                            // through even while YIN collects its first period.
                            // The old behaviour is unchanged when gate is OFF.
                            mute = targetNote.frequency == null ||
                                (trackedPitch == null && !gateVoice),
                            inputFundamentalHz = trackedPitch)
                        VoiceEngine.RUBBER_BAND, VoiceEngine.SIGNALSMITH ->
                            native24!!.process(captured24, lastRatio)
                        }
                        rateBridge!!.upsample(shifted24)
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
                    gate?.process(output, count, voiceDetected = gateVoice)
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
                    gate?.process(output, count, voiceDetected = gateVoice)
                    // Preserve the old stricter pitch-qualified detection for
                    // dry/wet volume matching; do not pump up unpitched noise.
                    // Rest selection is relevant only to pitch-converted voice.
                    val restSelection = when {
                        note.frequency != null || restMode == RestPlaybackMode.HOLD_LAST ->
                            RestOutputMixer.Selection.SHIFTED
                        restMode == RestPlaybackMode.RAW_VOICE -> RestOutputMixer.Selection.RAW
                        else -> RestOutputMixer.Selection.SILENCE
                    }
                    restMixer!!.mix(output, captured, count, restSelection,
                        voiceDetected = voiceDetected)
                }
                // Keep score-note delay history advancing even while HPF is OFF;
                // the output note must still align if the slider is enabled later.
                if (scoreHighPass != null) {
                    delayedScoreNotes.addLast(targetNote.frequency ?: Double.NaN)
                    val alignedHz = if (delayedScoreNotes.size > scoreNoteDelayFrames)
                        delayedScoreNotes.removeFirst().takeIf { it.isFinite() && it > 0.0 } else null
                    val hpPercent = scoreHighPassPercent
                    // True bypass at stable 0%: do not even call process().
                    // A pending 20ms transition from an active filter to 0%
                    // must finish first, to avoid a discontinuity/click.
                    if (hpPercent > 0 || scoreHighPass.needsFadeOut()) {
                        scoreHighPass.process(output, count,
                            if (originalMonitor || (note.frequency == null &&
                                restMode != RestPlaybackMode.HOLD_LAST)) null else alignedHz,
                            hpPercent)
                    }
                }
                // The pitch-dependent auto gain and manual boost continue to
                // share the existing ONE final peak limiter for phone and PC.
                // Auto-compensate loudness lost on UPWARD pitch shifts only.
                // Do not boost raw mic, test tone, original-voice monitor or
                // an unshifted/raw-rest path. HOLD_LAST rests still have a note.
                val automaticGainRatio = if (voiceMode && !directMic && !originalMonitor &&
                    (note.frequency != null || restMode == RestPlaybackMode.HOLD_LAST)) ratio
                    else null
                // Both gains are combined and peak-limited once at the final
                // common output point, for phone playback and PC transmission.
                outputBooster.process(output, count, outputBoostDb, automaticGainRatio)
                if (outputTarget == OutputTarget.ANDROID) {
                    var offset = 0
                    while (running && offset < count) {
                        val wrote = track!!.write(output, offset, count - offset, AudioTrack.WRITE_BLOCKING)
                        if (wrote <= 0) error("スピーカーへの出力エラー: $wrote")
                        offset += wrote
                    }
                } else {
                    // All modes produce a complete FRAME (960 samples).
                    // The old partial-read zero padding is no longer needed.
                    for (i in 0 until FRAME) {
                        val v = output[i].toInt()
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
                            naturalBypass == null -> "24kHzネイティブエンジン対象外"
                            originalMonitor -> "原音比較"
                            nearUnityEnabled -> "自然声優先"
                            else -> "従来処理"
                        }, internalSpeaker))
                    lastStatusNs = now
                }
            }
        } finally {
            // Keep running intact until the outer worker handles exceptions;
            // clearing it here would hide all real audio errors from onError.
            inputLookahead?.finish()
            activeCapture = null
            try { record?.stop() } catch (_: Exception) { }
            try { micReader?.join(500L) } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            val dropped = inputLookahead?.droppedFrameCount() ?: 0L
            if (dropped > 0L) android.util.Log.w("UtaMaruAudio",
                "Microphone FIFO overflow: $dropped frame(s) dropped while output was stalled")
            try { native24?.close() } catch (_: Exception) { }
            record?.release()
            try { track?.pause(); track?.flush(); track?.stop() } catch (_: Exception) { }
            track?.release()
            try { pcSocket?.close() } catch (_: Exception) { }
        }
    }
}
