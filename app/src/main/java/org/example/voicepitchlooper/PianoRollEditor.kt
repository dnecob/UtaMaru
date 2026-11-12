package org.example.voicepitchlooper

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.app.AlertDialog
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.SeekBar
import android.text.InputType
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Compact, two-staff score editor (treble + bass). Each column remains ONE quarter-note
 * beat to retain compatibility with the existing player and textual score format.
 *
 * The complete C1..B6 range uses true ledger-line positions. The staff gap and
 * notehead dimensions stay unchanged while the viewport is compact.
 */
class PianoRollEditor(
    private val activity: MainActivity,
    source: List<ScoreNote>,
    initialBpm: Int,
    private val onSave: (String, Int) -> Unit,
    carriedHistory: HistoryCarry? = null,
    private val onHistorySaved: (HistoryCarry) -> Unit
) {
    private val model = ScoreEditorModel(source).apply { ensureAtLeastBeats(8) }
    private var editorBpm = initialBpm.coerceIn(60, 500)
    private lateinit var tempoLabel: TextView
    private lateinit var tempoBar: SeekBar
    private lateinit var autoAdvanceCheck: CheckBox
    private lateinit var recordButton: Button
    private lateinit var recordFromSelectionButton: Button
    private var activeRecordButton: Button? = null
    private lateinit var recordingStatus: TextView
    private lateinit var applyButton: Button
    private lateinit var backButton: Button
    private val editButtons = mutableListOf<Button>()
    // Only explicit editing operations are undoable. Placing/dragging a single note
    // intentionally does not create a history entry; an uncommitted draft is
    // added once on exit when the score text actually changed.
    data class EditorSnapshot(
        val notes: List<ScoreNote>, val bpm: Int, val beat: Int, val octave: Int
    )
    /** A carried undo stack is valid only for the main screen score it came from. */
    data class HistoryCarry(
        val mainScoreText: String,
        val mainBpm: Int,
        val undoStates: List<EditorSnapshot>
    )
    private val undoHistory = java.util.ArrayDeque<EditorSnapshot>()
    private val maxUndoLevels = 30
    private val enteringScoreText = ScoreParser.serialize(source)
    private val enteringBpm = initialBpm.coerceIn(60, 500)
    private val initiallyDisplayedScore = ScoreParser.serialize(model.notes())
    private var appliedToMain = false
    init {
        // Reopening the same score reuses the complete history, including any
        // draft saved when returning without tapping "変更を反映".
        // A different preset / text / BPM must not inherit a stale undo stack.
        if (carriedHistory?.mainScoreText == enteringScoreText &&
            carriedHistory.mainBpm == enteringBpm) {
            carriedHistory.undoStates.takeLast(maxUndoLevels).forEach { undoHistory.addLast(it) }
        }
    }
    private lateinit var undoButton: Button
    private lateinit var checkpointButton: Button
    private var recordingSnapshot: EditorSnapshot? = null
    private var recorder: MelodyRecorder? = null
    private var countInActive = false
    private var countdownToken = 0
    private var countdownPlayer: CountInClickPlayer? = null
    private lateinit var countdownDisplay: TextView
    private val recordUiStep = object : Runnable {
        override fun run() {
            val capture = recorder ?: return
            if (closed) return
            val timeBeats = capture.progressedBeats
            val cursor = (recordStartBeat + timeBeats.toInt()).coerceIn(0, ScoreParser.MAX_BEATS - 1)
            var enlarged = false
            while (cursor >= model.size && model.size < ScoreParser.MAX_BEATS) {
                commitRecordingSnapshot()
                model.appendRests(minOf(4, ScoreParser.MAX_BEATS - model.size))
                enlarged = true
            }
            if (enlarged) {
                scoreView.refresh()
                updateScrollbar()
            }
            val nextSelection = cursor.coerceIn(0, model.size - 1)
            if (selected != nextSelection) {
                selected = nextSelection
                syncSelection()
            }
            val left = scoreView.beatLeft(selected)
            val fractional = (timeBeats - timeBeats.toInt()) * scoreView.beatWidth
            timeline.scrollTo((left + fractional.toInt() - timeline.width / 3).coerceAtLeast(0), 0)
            handler.postDelayed(this, 40L)
        }
    }
    private var recordStartBeat = 0
    private var closed = false

    private val preview = NotePreviewPlayer(activity)
    private var selected = 0
    // In button-entry mode, pitch keys write the current beat and optionally
    // advance. The octave keys only choose the pitch register for future input.
    private var autoAdvanceNotes = activity.getSharedPreferences("utamaru_editor_settings", Context.MODE_PRIVATE)
        .getBoolean("auto_advance_notes", true)
    private var editOctave = source.firstOrNull { it.frequency != null }
        ?.label?.lastOrNull()?.digitToIntOrNull()?.coerceIn(1, 6) ?: 4
    private lateinit var scoreView: StaffScoreView
    private lateinit var timeline: HorizontalScrollView
    private lateinit var octaveInfo: TextView
    private lateinit var scoreScrollbar: android.widget.SeekBar
    private lateinit var scrollStatus: TextView
    private lateinit var dialog: Dialog
    private lateinit var playbackButton: Button
    private val handler = Handler(Looper.getMainLooper())
    private var playbackRunning = false
    private var playbackBeat = -1
    // The UI only follows the audio playhead; playback itself is clocked by audio samples.
    private val playbackUiStep = object : Runnable {
        override fun run() {
            if (!playbackRunning) return
            val audioBeat = preview.currentScoreBeat()
            if (audioBeat != playbackBeat) {
                playbackBeat = audioBeat
                selected = audioBeat.coerceIn(0, model.size - 1)
                syncSelection()
                moveToSelection()
            }
            handler.postDelayed(this, 40L)
        }
    }

    private fun stopPlayback() {
        if (!playbackRunning) return
        playbackRunning = false
        handler.removeCallbacks(playbackUiStep)
        preview.stopScore()
        if (::playbackButton.isInitialized) playbackButton.text = "▶ 楽譜再生"
    }

    private fun togglePlayback() {
        if (playbackRunning) { stopPlayback(); return }
        preview.startScore(model.notes(), editorBpm)
        playbackRunning = true
        playbackBeat = -1
        playbackButton.text = "■ 再生停止"
        handler.post(playbackUiStep)
    }
    private val ink = Color.rgb(27, 41, 62)
    private val muted = Color.rgb(87, 101, 122)
    private val blue = Color.rgb(38, 103, 211)
    private val pitchNames = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    private fun dp(v: Int) = (v * activity.resources.displayMetrics.density + 0.5f).toInt()
    private fun label(value: String, size: Float, color: Int = ink): TextView =
        LocalizedTextView(activity).apply { text = value; textSize = size; setTextColor(color) }
    private fun button(value: String, f: () -> Unit) = LocalizedButton(activity).apply {
        text = value; isAllCaps = false; setOnClickListener { f() }
        editButtons.add(this)
    }
    // Keep insertion/deletion labels on exactly two lines, scaling text down on small screens.
    private fun twoLineButton(value: String, f: () -> Unit): Button = button(value, f).apply {
        gravity = Gravity.CENTER
        setPadding(dp(2), 0, dp(2), 0)
        maxLines = 2
        setAutoSizeTextTypeUniformWithConfiguration(
            9, 14, 1, android.util.TypedValue.COMPLEX_UNIT_SP
        )
    }
    private fun row(vararg buttons: Button, height: Int = 40) = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        for (b in buttons) addView(b, LinearLayout.LayoutParams(0, dp(height), 1f))
    }
    private fun append(parent: LinearLayout, view: View, margin: Int = 0) {
        parent.addView(view, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(margin) })
    }

    private fun snapshot(): EditorSnapshot =
        EditorSnapshot(model.notes(), editorBpm, selected, editOctave)

    private fun updateUndoButton() {
        if (::undoButton.isInitialized) {
            undoButton.isEnabled = undoHistory.isNotEmpty() && recorder == null && !countInActive
            undoButton.text = if (undoHistory.isEmpty()) "↶ やり直し" else "↶ やり直し (${undoHistory.size})"
        }
        if (::checkpointButton.isInitialized) {
            checkpointButton.isEnabled = recorder == null && !countInActive
        }
    }

    private fun sameScoreAndTempo(a: EditorSnapshot, b: EditorSnapshot): Boolean =
        a.notes == b.notes && a.bpm == b.bpm

    private fun remember(snapshot: EditorSnapshot) {
        // Consecutive identical score states consume undo slots without giving
        // users a meaningful restoration point, especially after a checkpoint.
        val last = undoHistory.peekLast()
        if (last != null && sameScoreAndTempo(last, snapshot)) return
        if (undoHistory.size >= maxUndoLevels) undoHistory.removeFirst()
        undoHistory.addLast(snapshot)
        updateUndoButton()
    }

    /** Store exactly the score currently on screen, including tempo and selection.
     * Unlike individual note edits, this explicitly creates an undo point.
     */
    private fun saveCheckpoint() {
        if (countInActive || recorder != null || closed) return
        stopPlayback()
        val current = snapshot()
        val previous = undoHistory.peekLast()
        if (previous != null && sameScoreAndTempo(previous, current)) {
            Toast.makeText(activity, UiLanguage.text(activity, "この状態はすでに履歴にあります"), Toast.LENGTH_SHORT).show()
            return
        }
        remember(current)
        Toast.makeText(activity, UiLanguage.text(activity, "現在の楽譜をやり直し履歴に一時保存しました"), Toast.LENGTH_SHORT).show()
    }

    /** Prevent half-completed mutations: either all changes succeed or restore immediately. */
    private fun editWithUndo(edit: () -> Unit) {
        val previous = snapshot()
        stopPlayback()
        try {
            edit()
            if (previous.notes != model.notes() || previous.bpm != editorBpm) remember(previous)
        } catch (e: Exception) {
            model.restoreNotes(previous.notes)
            editorBpm = previous.bpm
            selected = previous.beat
            editOctave = previous.octave
            updateTempo(previous.bpm)
            refreshScore()
            errorToast(e)
        }
    }

    private fun undo() {
        if (countInActive || recorder != null || undoHistory.isEmpty()) return
        stopPlayback()
        preview.silence()
        val state = undoHistory.removeLast()
        model.restoreNotes(state.notes)
        editorBpm = state.bpm
        selected = state.beat.coerceIn(0, model.size - 1)
        editOctave = state.octave
        updateTempo(state.bpm)
        refreshScore()
        updateUndoButton()
    }

    // One undo state per entire recording, not one state per recorded beat.
    private fun commitRecordingSnapshot() {
        recordingSnapshot?.let { remember(it) }
        recordingSnapshot = null
    }

    // BPM changes alone do not create entries in the undo history.
    private fun updateTempo(value: Int) {
        val next = value.coerceIn(60, 500)
        if (editorBpm != next) stopPlayback()
        editorBpm = next
        if (::tempoLabel.isInitialized) tempoLabel.text = "${editorBpm} BPM  （タップで数値入力）"
        if (::tempoBar.isInitialized && tempoBar.progress != editorBpm - 60)
            tempoBar.progress = editorBpm - 60
    }

    private fun askExactTempo() {
        val field = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            setText(editorBpm.toString())
            setSelectAllOnFocus(true)
        }
        AlertDialog.Builder(activity)
            .setTitle(UiLanguage.text(activity, "楽譜のテンポ（60～500 BPM）"))
            .setView(field)
            .setNegativeButton(UiLanguage.text(activity, "キャンセル"), null)
            .setPositiveButton(UiLanguage.text(activity, "設定")) { _, _ ->
                val number = field.text.toString().toIntOrNull()
                if (number == null || number !in 60..500)
                    Toast.makeText(activity, UiLanguage.text(activity, "60～500の数字を入力してください"), Toast.LENGTH_SHORT).show()
                else updateTempo(number)
            }.show()
    }

    private fun setCaptureUi(capturing: Boolean) {
        // Only the active recording button is enabled so it can cancel the count-in
        // or stop the microphone. The other start button must not change the start beat.
        for (b in editButtons) {
            b.isEnabled = !capturing || (b === activeRecordButton) || (b === backButton)
        }
        if (!capturing) updateUndoButton()
        if (::tempoBar.isInitialized) tempoBar.isEnabled = !capturing
        if (::autoAdvanceCheck.isInitialized) autoAdvanceCheck.isEnabled = !capturing
        if (::scoreView.isInitialized) scoreView.isEnabled = !capturing
        if (::applyButton.isInitialized) applyButton.isEnabled = !capturing
        if (::recordButton.isInitialized) {
            recordButton.text = if (capturing && activeRecordButton === recordButton)
                "■ 録音を停止" else "● 先頭から録音"
        }
        if (::recordFromSelectionButton.isInitialized) {
            recordFromSelectionButton.text = if (capturing && activeRecordButton === recordFromSelectionButton)
                "■ 録音を停止" else "● 現在位置から録音"
        }
        if (!capturing) activeRecordButton = null
    }

    private fun beginCountdown(startBeat: Int, sourceButton: Button) {
        if (closed || countInActive || recorder != null || playbackRunning) return
        recordStartBeat = startBeat.coerceIn(0, model.size - 1)
        selected = recordStartBeat
        syncSelection()
        moveToSelection()
        activeRecordButton = sourceButton
        countInActive = true
        val token = ++countdownToken
        preview.silence()
        setCaptureUi(true)
        countdownDisplay.visibility = View.VISIBLE
        countdownDisplay.text = CountInClickSynth.COUNT_IN_BEATS.toString()
        recordingStatus.text = "録音開始まで ${CountInClickSynth.COUNT_IN_BEATS} カウント（${editorBpm} BPM）"
        // A single PCM stream keeps every click on the audio sample clock.
        countdownPlayer = CountInClickPlayer(
            context = activity,
            bpm = editorBpm,
            onCount = { remaining ->
                if (!closed && countInActive && token == countdownToken) {
                    countdownDisplay.text = remaining.toString()
                    recordingStatus.text = "録音開始まで $remaining カウント（${editorBpm} BPM）"
                }
            },
            onFinished = { failure ->
                if (!closed && countInActive && token == countdownToken) {
                    countdownPlayer = null
                    countInActive = false
                    countdownDisplay.visibility = View.GONE
                    if (failure == null) {
                        startMicrophoneCapture()
                    } else {
                        setCaptureUi(false)
                        recordingStatus.text = "カウント音を再生できません: $failure"
                        Toast.makeText(activity, UiLanguage.text(activity, "録音を開始しませんでした: $failure"), Toast.LENGTH_LONG).show()
                    }
                }
            }
        ).also { it.start() }
    }

    private fun startMicrophoneCapture() {
        if (closed) return
        val start = recordStartBeat
        val capture = MelodyRecorder(
            bpm = editorBpm,
            maxBeats = ScoreParser.MAX_BEATS - start,
            onBeat = { index, midi ->
                activity.runOnUiThread {
                    if (closed || recorder !== captureRef) return@runOnUiThread
                    val target = start + index
                    if (target in 0 until ScoreParser.MAX_BEATS) {
                        // Save only once, before the first recording edit (including auto-extend).
                        commitRecordingSnapshot()
                        // Append complete measures without truncating the existing score.
                        while (target >= model.size && model.size < ScoreParser.MAX_BEATS)
                            model.appendRests(minOf(4, ScoreParser.MAX_BEATS - model.size))
                        if (midi == null) model.setRest(target)
                        else model.setPitch(target, midi % 12, midi / 12 - 1)
                        scoreView.invalidate()
                        recordingStatus.text = "録音中：第${target / 4 + 1}小節・${target % 4 + 1}拍目を記録（${editorBpm} BPM）"
                    }
                }
            },
            onDone = { message ->
                activity.runOnUiThread {
                    if (closed) return@runOnUiThread
                    // The last completed beat has been posted before this callback.
                    recorder = null
                    recordingSnapshot = null
                    handler.removeCallbacks(recordUiStep)
                    setCaptureUi(false)
                    recordingStatus.text = message ?: "録音終了。内容を確認し、右上の「変更を反映」で保存してください。"
                }
            }
        )
        captureRef = capture
        recorder = capture
        recordingSnapshot = snapshot()
        recordingStatus.text = "マイクを準備しています…"
        capture.start()
        handler.post(recordUiStep)
    }

    private var captureRef: MelodyRecorder? = null

    private fun toggleRecording(fromStart: Boolean) {
        if (countInActive || recorder != null) { stopRecording(); return }
        if (playbackRunning) stopPlayback()
        val startBeat = if (fromStart) 0 else selected
        val sourceButton = if (fromStart) recordButton else recordFromSelectionButton
        activity.ensureEditorMicrophonePermission {
            if (closed || recorder != null || countInActive) return@ensureEditorMicrophonePermission
            AlertDialog.Builder(activity)
                .setTitle(UiLanguage.text(activity, if (fromStart) "先頭から録音" else "現在位置から録音"))
                .setMessage(
                    UiLanguage.text(activity,
                        "第${startBeat / 4 + 1}小節の${startBeat % 4 + 1}拍目から録音します。") +
                    UiLanguage.text(activity,
                        "BPMに合わせた音付き8カウント後、1拍ずつ音符を上書きします。" +
                        "録音していない前後の拍はそのまま残ります。" +
                        "録音内容は「戻す」で取り消せます。")
                )
                .setNegativeButton(UiLanguage.text(activity, "キャンセル"), null)
                .setPositiveButton(UiLanguage.text(activity, "録音開始")) { _, _ -> beginCountdown(startBeat, sourceButton) }
                .show()
        }
    }

    /** Also called by MainActivity.onStop(), so capture never continues in background. */
    fun stopRecording() {
        if (countInActive) {
            countInActive = false
            countdownToken++
            countdownPlayer?.cancel()
            countdownPlayer = null
            preview.silence()
            if (::countdownDisplay.isInitialized) countdownDisplay.visibility = View.GONE
            if (::recordingStatus.isInitialized) recordingStatus.text = "録音をキャンセルしました"
            if (::recordButton.isInitialized) setCaptureUi(false)
        }
        recorder?.stop()
        handler.removeCallbacks(recordUiStep)
    }

    private fun syncSelection() {
        // Keep the pitch/octave guidance to a single line without implying
        // that the number buttons select the pitch C.
        octaveInfo.text = "音階・音域を指定（現在の音域：$editOctave）"
        scoreView.invalidate()
    }

    private fun updateScrollbar() {
        timeline.post {
            if (!::scoreScrollbar.isInitialized) return@post
            scoreView.fitEightBeats(timeline.width)
            val available = (scoreView.scoreRight() - timeline.width).coerceAtLeast(0)
            scoreScrollbar.max = max(1, available)
            scoreScrollbar.isEnabled = available > 0
            scoreScrollbar.progress = timeline.scrollX.coerceIn(0, available)
            updateScrollStatus()
        }
    }

    private fun updateScrollStatus() {
        if (!::scrollStatus.isInitialized) return
        val firstVisibleBeat = (timeline.scrollX / scoreView.beatWidth).coerceAtLeast(0)
        val lastMeasure = (model.size + 3) / 4
        val currentMeasure = (firstVisibleBeat / 4 + 1).coerceIn(1, lastMeasure)
        scrollStatus.text = "◀ 譜面の横移動：$currentMeasure / $lastMeasure 小節 ▶"
    }

    private fun refreshScore() {
        scoreView.refresh()
        syncSelection()
        updateScrollbar()
        moveToSelection()
    }

    private fun moveToSelection() {
        timeline.post {
            val left = scoreView.beatLeft(selected)
            val right = left + scoreView.beatWidth
            val start = timeline.scrollX
            when {
                right > start + timeline.width -> timeline.smoothScrollTo(
                    right - timeline.width + dp(12), 0)
                left < start -> timeline.smoothScrollTo(max(0, left - dp(12)), 0)
            }
        }
    }

    private fun chooseBeat(step: Int) {
        stopPlayback()
        selected = step
        val n = model.noteAt(step)
        if (n.frequency != null) editOctave = n.label.last().digitToInt().coerceIn(1, 6)
        syncSelection()
    }

    private fun previewIfChanged(before: ScoreNote, after: ScoreNote) {
        if (before == after) return
        if (after.frequency == null) preview.silence()
        else preview.play(after.frequency)
    }

    /** Make the selected beat a rest, just like placing an individual pitch:
     * no undo-history entry. Both rest buttons share this behavior.
     * Beat insertion (which shifts notes) still uses editWithUndo separately.
     */
    private fun setSelectedRest() {
        if (model.noteAt(selected).frequency == null) return
        stopPlayback()
        model.setRest(selected)
        preview.silence()
        syncSelection()
    }

    private fun choosePitch(pitch: String) {
        stopPlayback()
        val old = model.noteAt(selected)
        model.setPitch(selected, pitchNames.indexOf(pitch), editOctave)
        previewIfChanged(old, model.noteAt(selected))
        // Keep the chosen octave even when the next beat already has a note.
        // A manual selection (chooseBeat) may synchronize it to that note.
        if (autoAdvanceNotes && selected < model.size - 1) {
            selected++
            moveToSelection()
        }
        syncSelection()
    }

    private fun chooseOctave(value: Int) {
        // Register selector only. Never alter a note until a pitch key is pressed.
        stopPlayback()
        editOctave = value
        syncSelection()
    }

    private fun placePitch(beat: Int, semitone: Int, octave: Int) {
        stopPlayback()
        selected = beat
        editOctave = octave.coerceIn(1, 6)
        val before = model.noteAt(beat)
        model.setPitch(beat, semitone, editOctave)
        previewIfChanged(before, model.noteAt(beat))
        syncSelection()
    }

    fun show() {
        // Pinned header: toolbar, score and horizontal navigator stay visible.
        // Only tempo, recording and note-editing controls scroll vertically.
        val pinnedScore = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(9), dp(1), dp(9), dp(2))
            setBackgroundColor(Color.rgb(250, 251, 253))
        }
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(9), dp(6), dp(9), dp(13))
            setBackgroundColor(Color.rgb(250, 251, 253))
        }
        val tempoRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val minusTempo = button("−5") { updateTempo(editorBpm - 5) }
        tempoRow.addView(minusTempo, LinearLayout.LayoutParams(dp(57), dp(38)))
        tempoLabel = label("", 15f, blue).apply {
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setOnClickListener { if (recorder == null && !countInActive) askExactTempo() }
        }
        tempoRow.addView(tempoLabel, LinearLayout.LayoutParams(0, dp(38), 1f))
        val plusTempo = button("+5") { updateTempo(editorBpm + 5) }
        tempoRow.addView(plusTempo, LinearLayout.LayoutParams(dp(57), dp(38)))
        append(root, tempoRow, 6)
        tempoBar = SeekBar(activity).apply {
            max = 440 // 60 to 500 BPM; preset format stores an integer BPM
            progress = editorBpm - 60
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) updateTempo(progress + 60)
                }
                override fun onStartTrackingTouch(bar: SeekBar?) { }
                override fun onStopTrackingTouch(bar: SeekBar?) { }
            })
        }
        append(root, tempoBar)
        updateTempo(editorBpm)
        timeline = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = true
            isFillViewport = true
        }
        scoreView = StaffScoreView(activity, model,
            selectedBeat = { selected },
            onBeat = { chooseBeat(it) },
            onStaffNote = { beat, natural, octave -> placePitch(beat, natural, octave) },
            onHorizontalPan = { pixels -> timeline.scrollBy(pixels, 0) })
        timeline.addView(scoreView)
        append(pinnedScore, timeline, 0)
        val scrollPanel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(1), dp(8), dp(2))
            setBackgroundColor(Color.rgb(228, 239, 255))
        }
        scrollStatus = label("◀ 譜面の横移動 ▶", 13f, blue).apply {
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
        }
        append(scrollPanel, scrollStatus)
        scoreScrollbar = android.widget.SeekBar(activity).apply {
            max = 1
            progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf(blue)
            thumbTintList = android.content.res.ColorStateList.valueOf(blue)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(143, 167, 205))
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) timeline.scrollTo(progress, 0)
                }
                override fun onStartTrackingTouch(bar: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(bar: android.widget.SeekBar?) {}
            })
        }
        append(scrollPanel, scoreScrollbar)
        append(pinnedScore, scrollPanel, 1)
        timeline.setOnScrollChangeListener { _, _, _, _, _ ->
            val available = (scoreView.scoreRight() - timeline.width).coerceAtLeast(0)
            scoreScrollbar.progress = timeline.scrollX.coerceIn(0, available)
            updateScrollStatus()
        }
        append(root, label("鼻歌・口笛から楽譜を作成（録音した拍だけ上書き）", 13f, muted), 9)
        recordButton = button("● 先頭から録音") { toggleRecording(fromStart = true) }
        recordFromSelectionButton = button("● 現在位置から録音") {
            toggleRecording(fromStart = false)
        }
        append(root, row(recordButton, recordFromSelectionButton, height = 48), 3)
        countdownDisplay = label(CountInClickSynth.COUNT_IN_BEATS.toString(), 44f, blue).apply {
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            visibility = View.GONE
            setBackgroundColor(Color.rgb(228, 239, 255))
            contentDescription = UiLanguage.text(activity, "録音開始までの残りカウント")
        }
        append(root, countdownDisplay, 3)
        recordingStatus = label("音付き8カウント → マイク録音 → 拍ごとに音符化。60～500 BPM対応。", 12f, muted)
        append(root, recordingStatus, 3)
        append(root, row(
            button("選択拍を休符に変更") { setSelectedRest() },
            button("▶ 楽譜再生") { togglePlayback() }.also { playbackButton = it },
            height = 40
        ), 6)
        append(root, label("1拍単位の挿入・削除（後ろの音符を移動）", 13f, muted), 10)
        append(root, row(
            twoLineButton("休符を挿入\n（後ろを1拍ずらす）") {
                editWithUndo {
                    val extended = model.insertRestAndShift(selected)
                    preview.silence()
                    refreshScore()
                    Toast.makeText(activity,
                        UiLanguage.text(activity, if (extended) "休符を挿入し、末尾に1小節追加しました" else "休符を挿入しました"),
                        Toast.LENGTH_SHORT).show()
                }
            },
            twoLineButton("選択拍を削除\n（後ろを詰める）") {
                editWithUndo {
                    model.deleteBeatAndCloseGap(selected)
                    preview.silence()
                    refreshScore()
                }
            }, height = 62
        ), 4)
        append(root, label("※ 4/4を維持：挿入時は末尾の休符を利用し、足りなければ1小節追加。削除後の末尾は休符になります。", 11f, muted), 3)
        append(root, label("小節の追加・削除（1小節＝4拍）", 13f, muted), 10)
        append(root, row(
            button("末尾に小節追加") {
                editWithUndo {
                    model.appendRests(4)
                    selected = model.size - 4
                    refreshScore()
                }
            },
            button("末尾に4小節追加") {
                editWithUndo {
                    model.appendRests(16)
                    selected = model.size - 16
                    refreshScore()
                }
            }, height = 42
        ), 4)
        append(root, row(
            button("選択小節の後に1小節挿入") {
                val index = ((selected / 4) + 1) * 4
                editWithUndo {
                    model.insertRests(index.coerceAtMost(model.size), 4)
                    selected = index.coerceAtMost(model.size - 1)
                    refreshScore()
                }
            }, height = 40
        ), 4)
        append(root, row(
            button("末尾の小節を\n削除") {
                if (model.size < 12) {
                    Toast.makeText(activity, UiLanguage.text(activity, "最低2小節は残します"), Toast.LENGTH_SHORT).show()
                    return@button
                }
                val deleteOne = {
                    editWithUndo {
                        model.removeLastMeasure()
                        selected = selected.coerceAtMost(model.size - 1)
                        refreshScore()
                    }
                }
                if (model.lastMeasureHasNotes()) {
                    AlertDialog.Builder(activity)
                        .setTitle(UiLanguage.text(activity, "末尾の小節を削除しますか？"))
                        .setMessage(UiLanguage.text(activity, "最後の4拍に音符があります。取り消しは「↶ 戻す」で可能です。"))
                        .setNegativeButton(UiLanguage.text(activity, "キャンセル"), null)
                        .setPositiveButton(UiLanguage.text(activity, "削除")) { _, _ -> deleteOne() }
                        .show()
                } else deleteOne()
            },
            button("選択小節より後の\n小節全削除") {
                val keep = ((selected / 4) + 1) * 4
                if (keep < 8) {
                    Toast.makeText(activity, UiLanguage.text(activity, "最低2小節は残します。第2小節以降を選択してください"), Toast.LENGTH_LONG).show()
                    return@button
                }
                val tail = (model.size - keep).coerceAtLeast(0)
                if (tail == 0) {
                    Toast.makeText(activity, UiLanguage.text(activity, "選択小節より後に削除する拍はありません"), Toast.LENGTH_SHORT).show()
                    return@button
                }
                val pitched = (keep until model.size).count { model.noteAt(it).frequency != null }
                AlertDialog.Builder(activity)
                    .setTitle(UiLanguage.text(activity, "選択小節より後をすべて削除しますか？"))
                    .setMessage(UiLanguage.text(activity, "第${selected / 4 + 1}小節まで残し、後ろの${tail}拍（音符${pitched}個）を削除します。\n「↶ 戻す」で取り消せます。"))
                    .setNegativeButton(UiLanguage.text(activity, "キャンセル"), null)
                    .setPositiveButton(UiLanguage.text(activity, "全削除")) { _, _ ->
                        editWithUndo {
                            model.removeAllMeasuresAfter(selected)
                            selected = selected.coerceAtMost(model.size - 1)
                            refreshScore()
                        }
                    }.show()
            }, height = 64
        ), 4)

        append(root, label("楽譜全体のオクターブ移動（休符はそのまま）", 13f, muted), 10)
        append(root, row(
            button("全音符を1オクターブ下げる") {
                editWithUndo {
                    val moved = model.transposeAllOctaves(-1)
                    val note = model.noteAt(selected)
                    editOctave = note.label.lastOrNull()?.digitToIntOrNull()?.coerceIn(1, 6)
                        ?: (editOctave - 1).coerceIn(1, 6)
                    refreshScore()
                    Toast.makeText(activity, UiLanguage.text(activity, "${moved}音を1オクターブ下げました"), Toast.LENGTH_SHORT).show()
                }
            },
            button("全音符を1オクターブ上げる") {
                editWithUndo {
                    val moved = model.transposeAllOctaves(1)
                    val note = model.noteAt(selected)
                    editOctave = note.label.lastOrNull()?.digitToIntOrNull()?.coerceIn(1, 6)
                        ?: (editOctave + 1).coerceIn(1, 6)
                    refreshScore()
                    Toast.makeText(activity, UiLanguage.text(activity, "${moved}音を1オクターブ上げました"), Toast.LENGTH_SHORT).show()
                }
            }, height = 62
        ), 4)
        append(root, label("※ C1～B6の範囲を超える音符がある場合、全体の移動を取り消します。", 11f, muted), 3)

        append(root, label("拍の移動（音階ボタンで入力するときに使用）", 13f, muted), 12)
        append(root, row(
            button("◀ 前の拍") { chooseBeat((selected - 1).coerceAtLeast(0)); moveToSelection() },
            button("休符") { setSelectedRest() },
            button("次の拍 ▶") { chooseBeat((selected + 1).coerceAtMost(model.size - 1)); moveToSelection() }, height = 40
        ), 4)

        // One header serves both the pitch-name and octave controls.
        octaveInfo = label("", 13f).apply { gravity = Gravity.CENTER }
        append(root, octaveInfo, 12)
        // These number buttons select the octave for the NEXT pitch input only.
        // Existing notes are never rewritten merely by changing this register.
        append(root, row(
            button("1") { chooseOctave(1) }, button("2") { chooseOctave(2) },
            button("3") { chooseOctave(3) }, button("4") { chooseOctave(4) },
            button("5") { chooseOctave(5) }, button("6") { chooseOctave(6) }, height = 38
        ), 2)
        autoAdvanceCheck = LocalizedCheckBox(activity).apply {
            text = "音符入力後に次の拍へ自動送り"
            textSize = 14f
            isChecked = autoAdvanceNotes
            setOnCheckedChangeListener { _, enabled ->
                autoAdvanceNotes = enabled
                activity.getSharedPreferences("utamaru_editor_settings", Context.MODE_PRIVATE)
                    .edit().putBoolean("auto_advance_notes", enabled).apply()
            }
        }
        append(root, autoAdvanceCheck, 4)
        // The 12 chromatic pitches keep their standard score notation but gain
        // Japanese solfege labels for easier recognition. Four columns prevent
        // the longer labels (e.g., F(ファ)#) from clipping on narrow phones.
        fun pitchButton(name: String, japanese: String): Button =
            button("${name.removeSuffix("#")}($japanese)${if (name.endsWith("#")) "#" else ""}") {
                choosePitch(name)
            }.apply {
                textSize = 12f
                minWidth = 0
                minimumWidth = 0
                setPadding(dp(1), 0, dp(1), 0)
            }
        append(root, row(
            pitchButton("C", "ド"), pitchButton("C#", "ド"),
            pitchButton("D", "レ"), pitchButton("D#", "レ"), height = 42
        ), 8)
        append(root, row(
            pitchButton("E", "ミ"), pitchButton("F", "ファ"),
            pitchButton("F#", "ファ"), pitchButton("G", "ソ"), height = 42
        ), 2)
        append(root, row(
            pitchButton("G#", "ソ"), pitchButton("A", "ラ"),
            pitchButton("A#", "ラ"), pitchButton("B", "シ"), height = 42
        ), 2)
        append(root, label("※ 履歴は最大30段階。音符の配置・ドラッグ・休符への変更は自動で履歴を作りません。残したい状態は上部の「↰」で一時保存できます。", 11f, muted), 9)

        // Full-screen score editor. The system status/navigation bars stay visible;
        // only the content below the fixed toolbar scrolls vertically.
        val scrolling = ScrollView(activity).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = true
            addView(root)
        }
        val surfaceColor = Color.rgb(250, 251, 253)
        val surface = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(surfaceColor)
            // Explicit insets are required when targeting Android 15/16 edge-to-edge.
            // Do not hide the status bar or place the editor buttons under system gestures.
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
        val toolbar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(5), dp(4), dp(5), dp(4))
            setBackgroundColor(Color.rgb(235, 242, 252))
        }
        toolbar.addView(label("楽譜", 16f).apply {
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        undoButton = button("↶ やり直し") { undo() }.apply {
            // Keep the history count on the first line even at the 30-entry limit.
            // Auto-size covers larger system fonts without changing toolbar layout.
            setSingleLine(true)
            maxLines = 1
            setPadding(dp(2), 0, dp(2), 0)
            setAutoSizeTextTypeUniformWithConfiguration(
                10, 13, 1, android.util.TypedValue.COMPLEX_UNIT_SP
            )
        }
        toolbar.addView(undoButton, LinearLayout.LayoutParams(dp(92), dp(44)))
        checkpointButton = button("↰") { saveCheckpoint() }.apply {
            textSize = 21f
            contentDescription = UiLanguage.text(activity, "現在の楽譜をやり直し履歴に一時保存")
            minWidth = 0
            minimumWidth = 0
            setPadding(0, 0, 0, 0)
        }
        toolbar.addView(checkpointButton, LinearLayout.LayoutParams(dp(40), dp(44)))
        backButton = button("戻る") { dialog.dismiss() }
        toolbar.addView(backButton, LinearLayout.LayoutParams(dp(52), dp(44)))
        applyButton = button("変更を反映") {
            try {
                val text = model.toScoreText()
                ScoreParser.parse(text) // Same validator as playback.
                onSave(text, editorBpm)
                appliedToMain = true
                dialog.dismiss()
            } catch (e: Exception) { errorToast(e) }
        }
        toolbar.addView(applyButton, LinearLayout.LayoutParams(dp(94), dp(44)))
        surface.addView(toolbar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        surface.addView(pinnedScore, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        surface.addView(scrolling, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        dialog = Dialog(activity, android.R.style.Theme_Material_Light_NoActionBar)
        dialog.setContentView(surface)
        dialog.setOnDismissListener {
            closed = true
            stopRecording()
            stopPlayback()
            // "戻る" (or Android system Back) leaves the main screen untouched.
            // Keep an exact draft in the undo stack only if the note/rest text
            // actually changed. No duplicate entry for a read-only visit or BPM-only edit.
            if (!appliedToMain && ScoreParser.serialize(model.notes()) != initiallyDisplayedScore) {
                remember(snapshot())
            }
            // A successful "変更を反映" changes the score on the main screen;
            // otherwise it stays at the source score and BPM from entry.
            onHistorySaved(HistoryCarry(
                mainScoreText = if (appliedToMain) ScoreParser.serialize(model.notes()) else enteringScoreText,
                mainBpm = if (appliedToMain) editorBpm else enteringBpm,
                undoStates = undoHistory.toList()
            ))
            preview.close()
        }
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(surfaceColor))
            addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            statusBarColor = surfaceColor
            navigationBarColor = surfaceColor
            @Suppress("DEPRECATION")
            decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            setDecorFitsSystemWindows(false)
        }
        syncSelection()
        updateUndoButton()
        dialog.show()
        // Set *after* show(): ordinary AlertDialog window width/height caps no longer apply.
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        updateScrollbar()
    }

    private fun errorToast(e: Exception) {
        Toast.makeText(activity, UiLanguage.text(activity, e.message ?: "編集できませんでした"), Toast.LENGTH_SHORT).show()
    }
}

/** A compact grand-staff renderer with quarter-note heads, stems, barlines and rests. */
private class StaffScoreView(
    activity: Activity,
    private val model: ScoreEditorModel,
    private val selectedBeat: () -> Int,
    private val onBeat: (Int) -> Unit,
    private val onStaffNote: (Int, Int, Int) -> Unit,
    private val onHorizontalPan: (Int) -> Unit
) : View(activity) {
    private val density = resources.displayMetrics.density
    private val scaled = resources.displayMetrics.scaledDensity
    private fun d(v: Float) = v * density
    private val labelWidth = d(34f)
    var beatWidth = d(26f).toInt()
        private set
    fun fitEightBeats(viewportPx: Int) {
        if (viewportPx <= 0) return
        val proposed = ((viewportPx - labelWidth - d(2f)) / 8f).toInt()
        val newWidth = minOf(d(26f).toInt(), max(1, proposed))
        if (newWidth != beatWidth) { beatWidth = newWidth; requestLayout(); invalidate() }
    }
    // Preserve true ledger-line note positions across C1..B6 and the existing
    // 12 x 8.2dp noteheads / 8.5dp staff spacing, but trim unused vertical padding.
    // Reclaim vertical screen space for the editing controls. Move both staves
    // slightly upward; keep enough headroom for B6, and room below C1 for labels.
    private val scoreHeight = d(236f).toInt()
    private val trebleTop = d(77f)
    private val staffGap = d(8.5f)
    private val bottomTreble = trebleTop + 4 * staffGap
    // E4 on the treble bottom line -> D4 -> C4 -> B3 -> A3 on the bass top line.
    // Two staff gaps between these lines make the transition continuous, with
    // one half-line gap per diatonic step across the treble/bass boundary.
    private val bassTop = bottomTreble + 2 * staffGap
    private val bottomBass = bassTop + 4 * staffGap
    private val ink = Color.rgb(38, 52, 73)
    private val blue = Color.rgb(38, 103, 211)
    // Both out-of-range drag directions use the same red warning.
    private val red = Color.rgb(208, 49, 63)
    private val gray = Color.rgb(104, 116, 134)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var downX = 0f
    private var downY = 0f
    private var lastRawX = 0f
    private var downBeat = -1
    private var dragStartMidi = -1
    // Drag previews beyond the supported C1..B6 range are shown in warning colors.
    // Never save unsupported C0 / C7 notes: only the in-range boundary pitch is stored.
    private var overflowDirection = 0  // +1 = above B6, -1 = below C1 (both red)
    private var overflowBeat = -1
    private val chromaticStep = d(11f) // One semitone per 11dp of vertical movement.
    // 0 = undecided, 1 = vertical note movement, 2 = horizontal score panning
    private var gestureMode = 0
    private val touchSlop = d(8f)

    fun beatLeft(beat: Int): Int = (labelWidth + beat * beatWidth).toInt()
    fun scoreRight(): Int = beatLeft(max(8, model.size)) + d(17f).toInt()
    fun refresh() { requestLayout(); invalidate() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(scoreRight() + d(2f).toInt(), scoreHeight)
    }
    private fun line(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, color: Int, thickness: Float) {
        paint.style = Paint.Style.STROKE
        paint.color = color
        paint.strokeWidth = d(thickness)
        c.drawLine(x1, y1, x2, y2, paint)
        paint.style = Paint.Style.FILL
    }
    private fun text(c: Canvas, s: String, x: Float, y: Float, sp: Float, color: Int, bold: Boolean = false) {
        paint.style = Paint.Style.FILL
        paint.color = color
        paint.textSize = sp * scaled
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        c.drawText(s, x, y, paint)
    }
    private fun rect(c: Canvas, left: Float, top: Float, right: Float, bottom: Float, color: Int) {
        paint.style = Paint.Style.FILL
        paint.color = color
        c.drawRect(left, top, right, bottom, paint)
    }

    /** Actual notehead position over the full C1..B6 range. */
    private fun noteY(note: ScoreNote): Float? {
        val parts = ScoreEditorModel.pitchParts(note.label) ?: return null
        val (name, _, octave) = parts
        val bass = octave <= 3
        val reference = if (bass) 2 * 7 + 4 else 4 * 7 + 2
        return (if (bass) bottomBass else bottomTreble) -
            ((octave * 7 + name) - reference) * (staffGap / 2f)
    }

    private fun drawNote(c: Canvas, x: Float, note: ScoreNote, selected: Boolean, overflow: Int) {
        if (note.frequency == null) {
            // Quarter rest: shaped zig-zag, rather than a piano-roll empty cell.
            val center = trebleTop + 2 * staffGap
            val p = Path().apply {
                moveTo(x - d(3f), center - d(15f))
                lineTo(x + d(4f), center - d(5f))
                lineTo(x - d(2f), center + d(1f))
                lineTo(x + d(4f), center + d(9f))
                lineTo(x - d(2f), center + d(14f))
            }
            paint.color = if (selected) blue else ink
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = d(2.4f)
            c.drawPath(p, paint)
            paint.style = Paint.Style.FILL
            return
        }
        val pitch = ScoreEditorModel.pitchParts(note.label) ?: return
        val (baseIndex, accidental, realOctave) = pitch
        val bass = realOctave <= 3
        // In treble clef the bottom line is E4; in bass clef it is G2.
        val referenceIndex = if (bass) 2 * 7 + 4 else 4 * 7 + 2
        val bottom = if (bass) bottomBass else bottomTreble
        val y = bottom - ((realOctave * 7 + baseIndex) - referenceIndex) * (staffGap / 2f)
        val color = when (overflow) {
            1 -> red
            -1 -> red
            else -> if (selected) blue else ink
        }
        // Ledger lines at every second diatonic step outside the staff.
        var ledger = bottom + staffGap
        while (ledger <= y + d(1f)) {
            line(c, x - d(10f), ledger, x + d(10f), ledger, ink, 1f)
            ledger += staffGap
        }
        ledger = bottom - 5 * staffGap
        while (ledger >= y - d(1f)) {
            line(c, x - d(10f), ledger, x + d(10f), ledger, ink, 1f)
            ledger -= staffGap
        }
        paint.color = color
        paint.style = Paint.Style.FILL
        c.save()
        c.rotate(-17f, x, y)
        c.drawOval(x - d(6.0f), y - d(4.1f), x + d(6.0f), y + d(4.1f), paint)
        c.restore()
        val stemUp = if (bass) (y > bassTop + 2 * staffGap) else (y > trebleTop + 2 * staffGap)
        if (stemUp) {
            line(c, x + d(5f), y, x + d(5f), y - d(26f), color, 1.35f)
        } else {
            line(c, x - d(5f), y, x - d(5f), y + d(26f), color, 1.35f)
        }
        if (accidental != 0) text(c, if (accidental > 0) "♯" else "♭", x - d(11f), y + d(4f), 16f, color)
        // Out-of-range drag previews are red at both limits (above B6 / below C1).
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(253, 254, 255))
        val cols = max(8, model.size)
        val selected = selectedBeat()
        // HorizontalScrollView exposes only a small clip of the potentially 4096-beat
        // score. Restrict all per-beat drawing to that clip so editor redraw remains
        // proportional to the viewport rather than the complete song length.
        val clip = canvas.clipBounds
        val firstVisible = ((clip.left - labelWidth) / beatWidth).toInt().coerceIn(0, cols)
        val lastVisible = (((clip.right - labelWidth) / beatWidth).toInt() + 2)
            .coerceIn(firstVisible, cols)
        for (beat in firstVisible until lastVisible) {
            val left = beatLeft(beat).toFloat()
            if (beat == selected) rect(canvas, left + d(1f), d(19f),
                left + beatWidth - d(1f), scoreHeight - d(10f), Color.rgb(230, 240, 255))
            if (beat % 4 == 0) text(canvas, "${beat / 4 + 1}", left + d(9f), d(14f), 9f, gray)
            if (beat < model.size) {
                text(canvas, "${beat + 1}", left + beatWidth / 2f, d(23f), 10f, gray)
                text(canvas, model.noteAt(beat).label.let { if (it == "休符") "" else it },
                    left + beatWidth / 2f, scoreHeight - d(10f), 9f,
                    if (beat == selected) blue else gray, beat == selected)
            }
            if (beat > 0 && beat % 4 == 0) {
                val barX = left
                line(canvas, barX, trebleTop, barX, bottomTreble, gray, 1.35f)
                line(canvas, barX, bassTop, barX, bottomBass, gray, 1.35f)
            }
        }
        val staffLeft = max(d(6f), clip.left.toFloat())
        val staffRight = minOf(beatLeft(model.size).toFloat() + d(8f), clip.right.toFloat())
        if (staffLeft < staffRight) {
            for (row in 0..4) {
                line(canvas, staffLeft, trebleTop + row * staffGap, staffRight, trebleTop + row * staffGap, ink, 0.85f)
                line(canvas, staffLeft, bassTop + row * staffGap, staffRight, bassTop + row * staffGap, ink, 0.85f)
            }
        }
        // End-repeat barline (repeat from the beginning): two dots + thin/thick lines.
        // Space is reserved after the last note so the repeat sign isn't clipped.
        val endX = beatLeft(model.size).toFloat() + d(8f)
        if (endX + d(2f) >= clip.left && endX - d(14f) <= clip.right) {
            val thinX = endX - d(5f)
            for ((top, bottom) in arrayOf(trebleTop to bottomTreble, bassTop to bottomBass)) {
                line(canvas, thinX, top, thinX, bottom, ink, 1.1f)
                line(canvas, endX, top, endX, bottom, ink, 3.2f)
                paint.style = Paint.Style.FILL
                paint.color = ink
                val dotX = thinX - d(5.5f)
                canvas.drawCircle(dotX, top + staffGap * 1.5f, d(1.7f), paint)
                canvas.drawCircle(dotX, top + staffGap * 2.5f, d(1.7f), paint)
            }
        }
        // Clef labels exist only at the start of the scrollable score.
        if (clip.left <= d(45f)) {
            text(canvas, "𝄞", d(16f), trebleTop + 3 * staffGap, 32f, ink)
            text(canvas, "𝄢", d(16f), bassTop + 3 * staffGap, 28f, ink)
            text(canvas, "4", d(31f), trebleTop + 1.5f * staffGap, 12f, ink)
            text(canvas, "4", d(31f), trebleTop + 3.3f * staffGap, 12f, ink)
        }
        for (beat in firstVisible until minOf(lastVisible, model.size)) {
            val x = beatLeft(beat) + beatWidth / 2f
            val warning = if (beat == overflowBeat) overflowDirection else 0
            drawNote(canvas, x, model.noteAt(beat), selected == beat, warning)
        }
    }

    private fun pitchAt(y: Float): Pair<Int, Int>? {
        val bass = y > (bottomTreble + bassTop) / 2f
        val bottom = if (bass) bottomBass else bottomTreble
        val ref = if (bass) 2 * 7 + 4 else 4 * 7 + 2
        val diatonicIndex = ref + ((bottom - y) / (staffGap / 2f)).roundToInt()
        val octave = Math.floorDiv(diatonicIndex, 7)
        if (octave !in 1..6) return null
        val nameIndex = Math.floorMod(diatonicIndex, 7)
        val semitones = intArrayOf(0, 2, 4, 5, 7, 9, 11)
        return semitones[nameIndex] to octave
    }

    private fun beatAt(x: Float): Int = ((x - labelWidth) / beatWidth).toInt()

    /** Translate vertical drag into semitone changes, not diatonic steps. */
    private fun moveNote(y: Float) {
        if (downBeat !in 0 until model.size || dragStartMidi < 0) return
        val offset = ((downY - y) / chromaticStep).roundToInt()
        val attemptedMidi = dragStartMidi + offset
        val nextOverflow = when {
            attemptedMidi > 95 -> 1
            attemptedMidi < 24 -> -1
            else -> 0
        }
        if (overflowDirection != nextOverflow || overflowBeat != downBeat) {
            overflowDirection = nextOverflow
            overflowBeat = downBeat
            invalidate()
        }
        // Inclusive range C1..B6. Integer division maps MIDI 24 to C1.
        val midi = attemptedMidi.coerceIn(24, 95)
        val semitone = Math.floorMod(midi, 12)
        val octave = midi / 12 - 1
        onStaffNote(downBeat, semitone, octave)
    }

    private fun startPitchMidi(beat: Int, touchY: Float): Int {
        val existing = model.noteAt(beat)
        val parts = ScoreEditorModel.pitchParts(existing.label)
        if (parts != null) {
            val naturals = intArrayOf(0, 2, 4, 5, 7, 9, 11)
            return ((parts.third + 1) * 12 + naturals[parts.first] + parts.second)
                .coerceIn(24, 95)
        }
        // Dragging an empty beat starts from the natural note nearest touch-down.
        val pitch = pitchAt(touchY) ?: return -1
        return ((pitch.second + 1) * 12 + pitch.first).coerceIn(24, 95)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                lastRawX = event.rawX
                downBeat = if (event.x >= labelWidth) beatAt(event.x) else -1
                gestureMode = 0
                dragStartMidi = if (downBeat in 0 until model.size) {
                    startPitchMidi(downBeat, downY)
                } else -1
                overflowDirection = 0
                overflowBeat = -1
                // Keep the outer vertical ScrollView from stealing a note drag.
                // Horizontal panning is implemented here for the long score.
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount != 1) return true
                if (gestureMode == 0) {
                    val dx = event.rawX - lastRawX
                    val dy = event.y - downY
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                        gestureMode = if (abs(dx) > abs(dy) * 1.3f) 2 else 1
                    }
                }
                when (gestureMode) {
                    1 -> moveNote(event.y)
                    2 -> onHorizontalPan((lastRawX - event.rawX).roundToInt())
                }
                lastRawX = event.rawX
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (gestureMode == 0 && downBeat in 0 until model.size) {
                    if (event.y > d(32f) && event.y < scoreHeight - d(18f)) {
                        val existingY = noteY(model.noteAt(downBeat))
                        if (existingY != null && abs(event.y - existingY) < d(10f)) {
                            onBeat(downBeat)
                        } else {
                            val pitch = pitchAt(event.y)
                            if (pitch != null) onStaffNote(downBeat, pitch.first, pitch.second)
                            else onBeat(downBeat)
                        }
                    } else onBeat(downBeat)
                } else if (gestureMode == 1 && downBeat in 0 until model.size) {
                    moveNote(event.y)
                }
                parent?.requestDisallowInterceptTouchEvent(false)
                if (gestureMode == 0) performClick()
                gestureMode = 0
                overflowDirection = 0
                overflowBeat = -1
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                gestureMode = 0
                overflowDirection = 0
                overflowBeat = -1
                invalidate()
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
