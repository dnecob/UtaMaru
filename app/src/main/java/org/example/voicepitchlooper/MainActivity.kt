package org.example.voicepitchlooper

import android.Manifest
import android.app.Activity
import android.content.Context
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.RadioGroup
import android.widget.RadioButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var scoreInput: EditText
    private lateinit var scoreEditorButton: Button
    private lateinit var presetStore: ScorePresetStore
    private lateinit var presetButtons: Array<Button>
    private lateinit var savePresetButton: Button
    private lateinit var presetStatus: TextView
    private var currentPresetSlot: Int? = null
    private var loadedPresetScore: String? = null
    private var loadedPresetBpm: Int? = null
    private var currentBpm = 200
    private lateinit var bpmStatus: TextView
    private var scoreEditor: PianoRollEditor? = null
    // Retained while this activity lives, even after the editor dialog closes.
    private var editorHistory: PianoRollEditor.HistoryCarry? = null
    private var pendingEditorMicPermission: (() -> Unit)? = null
    private lateinit var info: TextView
    private lateinit var startVoice: Button
    private lateinit var startRawAndroid: Button
    private lateinit var startRawPc: Button
    private lateinit var startTest: Button
    private lateinit var startPcVoice: Button
    private lateinit var startPcTest: Button
    private lateinit var pcHostInput: EditText
    private var pendingTarget = OutputTarget.ANDROID
    private var pendingDirectMic = false
    private var activeOutputTarget = OutputTarget.ANDROID
    // Which start button currently owns the running audio stream.
    // Only that button remains clickable, and it becomes the stop button.
    private var activeVoiceMode = false
    private var activeDirectMic = false
    private var stoppingAudio = false
    private var speakerWarningShown = false
    private lateinit var voiceEngineSpinner: Spinner
    private lateinit var languageSpinner: Spinner
    private lateinit var pauseScoreOnSilenceCheck: CheckBox
    private lateinit var noiseGateCheck: CheckBox
    private lateinit var naturalVoiceCheck: CheckBox
    private lateinit var originalMonitorCheck: CheckBox
    private lateinit var pitchRangeSlider: PitchRangeSlider
    private lateinit var pitchRangeLabel: TextView
    private var pitchRange = PitchRange.DEFAULT
    private lateinit var outputBoostLabel: TextView
    private lateinit var outputBoostSlider: SeekBar
    private var outputBoostDb = 0
    private lateinit var restModeButton: Button
    private var restMode = RestPlaybackMode.HOLD_LAST
    private lateinit var engine: AudioEngine
    private var active = false
    private var playingAndroidTest = false
    private var pendingMicStart = false
    private var lastAudioError: String? = null
    private val colors by lazy(LazyThreadSafetyMode.NONE) { UiTheme.palette(this) }
    private val ink get() = colors.ink
    private val muted get() = colors.muted
    private val blue get() = colors.accent

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(UiLanguage.wrap(newBase))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("ui_score_text", scoreInput.text.toString())
        outState.putString("ui_pc_host", pcHostInput.text.toString())
        outState.putInt("ui_bpm", currentBpm)
        outState.putInt("ui_engine", voiceEngineSpinner.selectedItemPosition)
        outState.putBoolean("ui_pause_score_silence", pauseScoreOnSilenceCheck.isChecked)
        outState.putBoolean("ui_gate", noiseGateCheck.isChecked)
        outState.putBoolean("ui_original_monitor", originalMonitorCheck.isChecked)
        outState.putInt("ui_preset_slot", currentPresetSlot ?: -1)
        outState.putString("ui_loaded_score", loadedPresetScore)
        outState.putInt("ui_loaded_bpm", loadedPresetBpm ?: -1)
        super.onSaveInstanceState(outState)
    }

    override fun onRetainNonConfigurationInstance(): Any? = editorHistory

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(if (UiTheme.isDark(this)) R.style.AppThemeDark else R.style.AppTheme)
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        editorHistory = lastNonConfigurationInstance as? PianoRollEditor.HistoryCarry
        engine = AudioEngine(
            onStatus = { status -> runOnUiThread { if (active) renderStatus(status) } },
            onError = { error -> runOnUiThread {
                lastAudioError = error
                info.text = "音声エラー: $error\n機種や音声経路によって48kHzが使えない場合があります。"
                Toast.makeText(this, UiLanguage.text(this, "音声エラーが発生しました"), Toast.LENGTH_LONG).show()
            } },
            onStopped = { runOnUiThread {
                stoppingAudio = false
                setRunning(false)
                lastAudioError?.let { info.text = "音声エラー: $it\nエラー全文を共有してください。" }
            } }
        )
        presetStore = ScorePresetStore(this)
        restMode = try {
            RestPlaybackMode.valueOf(getSharedPreferences("utamaru_audio_settings", MODE_PRIVATE)
                .getString("rest_playback_mode", RestPlaybackMode.HOLD_LAST.name) ?: "")
        } catch (_: IllegalArgumentException) { RestPlaybackMode.HOLD_LAST }
        val audioSettings = getSharedPreferences("utamaru_audio_settings", MODE_PRIVATE)
        // Migrate the three maximum-ratio presets from v0.7.4 automatically.
        val oldUpper = when (audioSettings.getString("max_pitch_ratio", "STANDARD")) {
            "WIDE" -> 5.0
            "EXTREME" -> 7.0
            else -> 3.5
        }
        pitchRange = try {
            PitchRange(audioSettings.getFloat("min_pitch_ratio", 0.5f).toDouble(),
                audioSettings.getFloat("max_pitch_ratio_value", oldUpper.toFloat()).toDouble())
        } catch (_: IllegalArgumentException) { PitchRange.DEFAULT }
        outputBoostDb = audioSettings.getInt("output_boost_db", 0)
            .coerceIn(0, OutputBoostProcessor.MAX_BOOST_DB)
        engine.setOutputBoostDb(outputBoostDb)
        buildUi()
        val savedEngine = audioSettings.getString("voice_engine", VoiceEngine.IMPROVED.name)
        val index = VoiceEngine.entries.indexOfFirst { it.name == savedEngine }
        voiceEngineSpinner.setSelection(index.takeIf { it >= 0 } ?: VoiceEngine.IMPROVED.ordinal)
        if (savedInstanceState == null) restoreLastPreset() else {
            scoreInput.setText(savedInstanceState.getString("ui_score_text", "D3A3F# GB3G3G"))
            pcHostInput.setText(savedInstanceState.getString("ui_pc_host", "127.0.0.1"))
            updateBpm(savedInstanceState.getInt("ui_bpm", 200))
            voiceEngineSpinner.setSelection(savedInstanceState.getInt("ui_engine", VoiceEngine.IMPROVED.ordinal))
            pauseScoreOnSilenceCheck.isChecked = savedInstanceState.getBoolean("ui_pause_score_silence", true)
            noiseGateCheck.isChecked = savedInstanceState.getBoolean("ui_gate", true)
            originalMonitorCheck.isChecked = savedInstanceState.getBoolean("ui_original_monitor", false)
            currentPresetSlot = savedInstanceState.getInt("ui_preset_slot", -1).takeIf { it >= 0 }
            loadedPresetScore = savedInstanceState.getString("ui_loaded_score")
            loadedPresetBpm = savedInstanceState.getInt("ui_loaded_bpm", -1).takeIf { it >= 0 }
            refreshPresetButtons()
        }
    }

    // Keep the footer in sync with the release version without changing it every update.
    private fun displayVersion(): String {
        @Suppress("DEPRECATION")
        val version = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
        // Show the public version series (e.g. 0.8.7) without the internal build suffix.
        return version.substringBefore('-').split('.').take(3).joinToString(".")
    }

    private fun dp(n: Int): Int = (resources.displayMetrics.density * n + 0.5f).toInt()
    private fun label(text: String, size: Float, bold: Boolean = false, color: Int = ink): TextView =
        LocalizedTextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun add(parent: LinearLayout, view: View, topDp: Int = 0) {
        parent.addView(view, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(topDp) })
    }

    private fun buildUi() {
        window.statusBarColor = colors.background
        window.navigationBarColor = colors.background
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (UiTheme.isDark(this)) 0 else
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR

        val scroll = ScrollView(this).apply {
            setBackgroundColor(colors.background)
            isFillViewport = true
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(12), dp(22), dp(28))
            setOnApplyWindowInsetsListener { v, insets ->
                val sys = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(dp(22) + sys.left, dp(12) + sys.top,
                    dp(22) + sys.right, dp(28) + sys.bottom)
                insets
            }
        }
        scroll.addView(root)
        setContentView(scroll)

        add(root, label("楽譜を編集", 17f, true))
        add(root, label("例：D3A3F# GB3G3G（空白=1拍休符、#=半音上、数字=オクターブ）", 13f, color = muted), 5)
        scoreInput = EditText(this).apply {
            setSingleLine(true)
            setText("D3A3F# GB3G3G")
            textSize = 21f
            setSelectAllOnFocus(true)
            setPadding(dp(12), dp(9), dp(12), dp(9))
        }
        add(root, scoreInput, 8)
        scoreEditorButton = LocalizedButton(this).apply {
            text = "♫ 五線譜で楽譜を編集"
            isAllCaps = false
            setOnClickListener { openGraphicalEditor() }
        }
        add(root, scoreEditorButton, 7)
        bpmStatus = label("この楽譜：200 BPM（変更は五線譜編集画面）", 12f, color = muted)
        add(root, bpmStatus, 3)
        add(root, label("縦ドラッグで半音移動・1秒試聴。初期2小節・小節追加可能。", 12f, color = muted), 3)
        add(root, label("楽譜プリセット（3曲まで保存）", 16f, true), 16)
        presetStatus = label("保存した曲をタップして呼び出せます", 12f, color = muted)
        add(root, presetStatus, 3)
        val presetRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        presetButtons = Array(3) { index ->
            LocalizedButton(this).apply {
                isAllCaps = false
                textSize = 12f
                setOnClickListener { recallPreset(index) }
                presetRow.addView(this, LinearLayout.LayoutParams(0, dp(53), 1f))
            }
        }
        add(root, presetRow, 5)
        savePresetButton = LocalizedButton(this).apply {
            text = "現在の楽譜とBPMをプリセットに保存"
            isAllCaps = false
            setOnClickListener { selectSaveSlot() }
        }
        add(root, savePresetButton, 4)
        refreshPresetButtons()
        add(root, label("音声変換エンジン", 15f, true), 12)
        // Give the engine selector a visible outline and an explicit drop-down chevron.
        // The default platform Spinner has little affordance on some Android 12 launchers.
        val engineSelector = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(7).toFloat()
                setColor(this@MainActivity.colors.selector)
                setStroke(dp(1), this@MainActivity.colors.border)
            }
        }
        voiceEngineSpinner = Spinner(this).apply {
            background = ColorDrawable(Color.TRANSPARENT)
            minimumHeight = dp(50)
            setPadding(dp(12), 0, dp(32), 0)
            contentDescription = UiLanguage.text(this@MainActivity, "音声変換エンジンを選択")
            val entries = VoiceEngine.entries.map { engine ->
                if (engine == VoiceEngine.RUBBER_BAND && !RubberBandNative.isAvailable)
                    UiLanguage.text(this@MainActivity, "Rubber Band（未導入・セットアップが必要）")
                else UiLanguage.text(this@MainActivity, engine.caption)
            }
            adapter = ArrayAdapter(this@MainActivity,
                android.R.layout.simple_spinner_item, entries).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
        }
        engineSelector.addView(voiceEngineSpinner, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(50)))
        engineSelector.addView(label("▾", 23f, color = blue).apply {
            gravity = Gravity.CENTER
            isClickable = false
            isFocusable = false
        }, FrameLayout.LayoutParams(dp(32), ViewGroup.LayoutParams.MATCH_PARENT,
            Gravity.END or Gravity.CENTER_VERTICAL))
        add(root, engineSelector, 5)
        add(root, label("切り替えは停止中のみ。Rubber Bandは導入後、声のフォルマント維持を試せます。",
            12f, color = muted), 2)
        val settings = getSharedPreferences("utamaru_audio_settings", MODE_PRIVATE)
        pauseScoreOnSilenceCheck = LocalizedCheckBox(this).apply {
            text = "音声がない間は楽譜を一時停止"
            textSize = 14f
            isChecked = settings.getBoolean("pause_score_on_silence", true)
            setOnCheckedChangeListener { _, checked ->
                settings.edit().putBoolean("pause_score_on_silence", checked).apply()
            }
        }
        add(root, pauseScoreOnSilenceCheck, 8)
        noiseGateCheck = LocalizedCheckBox(this).apply {
            text = "音声がないときのノイズ抑制（推奨）"
            textSize = 14f
            isChecked = true
        }
        add(root, noiseGateCheck, 8)
        naturalVoiceCheck = LocalizedCheckBox(this).apply {
            text = "自然声優先（元の音程に近い声を再合成しない・試験）"
            textSize = 14f
            isChecked = settings.getBoolean("near_unity_bypass", false)
            setOnCheckedChangeListener { _, checked ->
                settings.edit().putBoolean("near_unity_bypass", checked).apply()
            }
        }
        add(root, naturalVoiceCheck, 5)
        add(root, label("元の音程との差が15セント以内なら原音を優先し、25セント以上で変換音へ戻します。" +
            "標準・PSOLA風のみ。OFFでv0.8.6相当の音程変換と比較できます。", 12f, color = muted), 2)
        originalMonitorCheck = LocalizedCheckBox(this).apply {
            text = "原音モニター（変換を聴き比べる診断用）"
            textSize = 14f
            isChecked = false
        }
        voiceEngineSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?,
                                        position: Int, id: Long) {
                val supported = position != VoiceEngine.RUBBER_BAND.ordinal
                naturalVoiceCheck.isEnabled = !active && supported
                originalMonitorCheck.isEnabled = !active && supported
                if (!supported) originalMonitorCheck.isChecked = false
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) { }
        }

        add(root, label("ピッチ変換倍率の範囲", 15f, true), 16)
        pitchRangeLabel = label("", 15f, true)
        updatePitchRangeLabel()
        add(root, pitchRangeLabel, 5)
        pitchRangeSlider = PitchRangeSlider(this).apply {
            setRange(pitchRange.minimum, pitchRange.maximum)
            onRangeChanged = { low, high ->
                pitchRange = PitchRange(low, high)
                updatePitchRangeLabel()
                getSharedPreferences("utamaru_audio_settings", MODE_PRIVATE).edit()
                    .putFloat("min_pitch_ratio", low.toFloat())
                    .putFloat("max_pitch_ratio_value", high.toFloat()).apply()
            }
        }
        add(root, pitchRangeSlider, 2)
        add(root, label("青＝低音側の下限、緑＝高音側の上限。左右のつまみを動かして指定。" +
            "従来の設定は0.50〜3.50倍。範囲を広げるほど声質が崩れやすくなります。", 12f, color = muted), 2)

        add(root, label("休符の音声出力", 15f, true), 16)
        restModeButton = LocalizedButton(this).apply {
            isAllCaps = false
            text = "休符：${restMode.caption} ▼"
            setOnClickListener { chooseRestMode() }
        }
        add(root, restModeButton, 4)
        add(root, label("直前の音程はループ末尾からも引き継ぎます。テスト音の休符は常に無音です。",
            12f, color = muted), 2)

        add(root, label("出力ブースト", 15f, true), 17)
        outputBoostLabel = label("", 15f, true)
        updateOutputBoostLabel()
        add(root, outputBoostLabel, 4)
        outputBoostSlider = SeekBar(this).apply {
            max = OutputBoostProcessor.MAX_BOOST_DB / OutputBoostProcessor.STEP_DB
            progress = outputBoostDb / OutputBoostProcessor.STEP_DB
            contentDescription = UiLanguage.text(this@MainActivity, "出力ブーストを調整")
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    outputBoostDb = progress * OutputBoostProcessor.STEP_DB
                    engine.setOutputBoostDb(outputBoostDb)
                    updateOutputBoostLabel()
                    getSharedPreferences("utamaru_audio_settings", MODE_PRIVATE).edit()
                        .putInt("output_boost_db", outputBoostDb).apply()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) { }
                override fun onStopTrackingTouch(seekBar: SeekBar?) { }
            })
        }
        add(root, outputBoostSlider, 2)
        add(root, label("スマホ再生・PC送信に共通。0 dBは従来と同じ音量。" +
            "再生中も変更可能。上げすぎるとノイズ・ハウリングの危険があります。" +
            "音割れを抑えるため、ピークが大きい場合は増幅を自動制限します。",
            12f, color = muted), 2)

        startTest = LocalizedButton(this).apply {
            text = "楽譜をテスト音で再生"
            isAllCaps = false
            visibility = View.GONE
            setOnClickListener {
                if (active) {
                    if (playingAndroidTest) stopAudio()
                } else begin(voiceMode = false, target = OutputTarget.ANDROID)
            }
        }
        add(root, label("スマホで使う（スピーカー・イヤホン出力）", 17f, true), 20)
        startVoice = LocalizedButton(this).apply {
            text = "マイク入力を音程変換して再生"
            isAllCaps = false
            setOnClickListener { toggleMicButton(OutputTarget.ANDROID, directMic = false) }
        }
        add(root, startVoice, 6)
        startRawAndroid = LocalizedButton(this).apply {
            text = "マイク入力をそのまま再生"
            isAllCaps = false
            setOnClickListener { toggleMicButton(OutputTarget.ANDROID, directMic = true) }
        }
        add(root, startRawAndroid, 5)
        add(root, label("外部出力が確認できない場合はハウリング注意を表示します。接続済みでも実際の出力先が内蔵スピーカーなら警告します。", 12f, color = muted), 3)

        add(root, label("PC用マイク（USB / Wi-Fi）", 17f, true), 25)
        add(root, label("USBでは 127.0.0.1。Wi-FiではPCのIPv4アドレスを指定。先にPC側の受信を開始。", 12f, color = muted), 6)
        pcHostInput = EditText(this).apply {
            setSingleLine(true)
            setText("127.0.0.1")
            textSize = 18f
            hint = UiLanguage.text(this@MainActivity, "PCの接続先 (例: 192.168.1.10)")
        }
        add(root, pcHostInput, 8)
        startPcTest = LocalizedButton(this).apply {
            text = "PCへテスト音を送る"
            isAllCaps = false
            visibility = View.GONE
            setOnClickListener { begin(voiceMode = false, target = OutputTarget.PC) }
        }
        startPcVoice = LocalizedButton(this).apply {
            text = "マイク入力を音程変換してPCへ送る"
            isAllCaps = false
            setOnClickListener { toggleMicButton(OutputTarget.PC, directMic = false) }
        }
        add(root, startPcVoice, 9)
        startRawPc = LocalizedButton(this).apply {
            text = "マイク入力をそのままPCへ送る"
            isAllCaps = false
            setOnClickListener { toggleMicButton(OutputTarget.PC, directMic = true) }
        }
        add(root, startRawPc, 5)
        add(root, label("モニター", 17f, true), 23)
        info = label("待機中。楽譜の試聴は五線譜編集画面から行えます。スマホ再生はAndroidのメディア出力先を設定。PC出力は受信プログラムを先に起動してください。", 15f)
            .apply { setLineSpacing(dp(5).toFloat(), 1.0f) }
        add(root, info, 9)
        add(root, label(
            "注意: 実験版では声質・ノイズが悪化する場合があります。最初は音量を小さめに。" +
                "スピーカーの音をマイクが拾うとハウリングするため、距離を離してください。" +
                "アプリを離れると停止します。", 12f, color = muted
        ), 23)

        // The last control in the scrolling main screen, using the same outlined
        // selector style as the pitch conversion engine. All choices are persistent.
        add(root, label("表示言語", 15f, true), 22)
        val languageSelector = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(7).toFloat()
                setColor(this@MainActivity.colors.selector)
                setStroke(dp(1), this@MainActivity.colors.border)
            }
        }
        languageSpinner = Spinner(this).apply {
            background = ColorDrawable(Color.TRANSPARENT)
            minimumHeight = dp(50)
            setPadding(dp(12), 0, dp(32), 0)
            contentDescription = UiLanguage.text(this@MainActivity, "表示言語")
            val names = listOf(UiLanguage.text(this@MainActivity, "自動（端末設定）"),
                "日本語", "繁體中文", "English")
            adapter = ArrayAdapter(this@MainActivity,
                android.R.layout.simple_spinner_item, names).also {
                    it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                }
        }
        languageSelector.addView(languageSpinner, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(50)))
        languageSelector.addView(label("▾", 23f, color = blue).apply {
            gravity = Gravity.CENTER
            isClickable = false
            isFocusable = false
        }, FrameLayout.LayoutParams(dp(32), ViewGroup.LayoutParams.MATCH_PARENT,
            Gravity.END or Gravity.CENTER_VERTICAL))
        add(root, languageSelector, 5)
        languageSpinner.setSelection(UiLanguage.choices.indexOf(UiLanguage.selected(this)).coerceAtLeast(0))
        languageSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?,
                                        position: Int, id: Long) {
                val newChoice = UiLanguage.choices.getOrNull(position) ?: return
                if (newChoice == UiLanguage.selected(this@MainActivity)) return
                if (active || stoppingAudio) {
                    Toast.makeText(this@MainActivity,
                        UiLanguage.text(this@MainActivity, "言語の切り替えは停止中のみ行えます"),
                        Toast.LENGTH_SHORT).show()
                    languageSpinner.setSelection(UiLanguage.choices.indexOf(UiLanguage.selected(this@MainActivity)))
                    return
                }
                UiLanguage.save(this@MainActivity, newChoice)
                recreate()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) { }
        }
        // Three choices share one horizontal row; avoids a tall vertical theme menu.
        add(root, label(getString(R.string.theme_setting), 15f, true), 16)
        val themeGroup = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            contentDescription = getString(R.string.theme_setting)
        }
        val themeButtons = UiTheme.choices.mapIndexed { index, mode ->
            RadioButton(this).apply {
                id = View.generateViewId()
                text = getString(when (index) {
                    0 -> R.string.theme_auto_short
                    1 -> R.string.theme_light
                    else -> R.string.theme_dark
                })
                // The full name is available to screen readers even though the button is short.
                contentDescription = getString(when (index) {
                    0 -> R.string.theme_auto
                    1 -> R.string.theme_light
                    else -> R.string.theme_dark
                })
                buttonDrawable = null
                gravity = Gravity.CENTER
                textSize = 14f
                minHeight = dp(44)
                setPadding(dp(4), 0, dp(4), 0)
                isChecked = mode == UiTheme.selected(this@MainActivity)
                themeGroup.addView(this, RadioGroup.LayoutParams(0, dp(44), 1f).apply {
                    leftMargin = dp(2)
                    rightMargin = dp(2)
                })
            }
        }
        fun styleThemeChoices() {
            themeButtons.forEach { choice ->
                val chosen = choice.isChecked
                choice.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(7).toFloat()
                    setColor(if (chosen) this@MainActivity.colors.accent else this@MainActivity.colors.selector)
                    setStroke(dp(1), if (chosen) this@MainActivity.colors.accent else this@MainActivity.colors.border)
                }
                choice.setTextColor(if (chosen) {
                    if (UiTheme.isDark(this)) colors.background else Color.WHITE
                } else colors.ink)
            }
        }
        styleThemeChoices()
        add(root, themeGroup, 5)
        themeGroup.setOnCheckedChangeListener { _, checkedId ->
            val index = themeButtons.indexOfFirst { it.id == checkedId }
            val next = UiTheme.choices.getOrNull(index) ?: return@setOnCheckedChangeListener
            val current = UiTheme.selected(this@MainActivity)
            if (next == current) return@setOnCheckedChangeListener
            if (active || stoppingAudio) {
                Toast.makeText(this@MainActivity, getString(R.string.theme_switch_stopped),
                    Toast.LENGTH_SHORT).show()
                val oldIndex = UiTheme.choices.indexOf(current)
                if (oldIndex >= 0) themeGroup.check(themeButtons[oldIndex].id)
                styleThemeChoices()
                return@setOnCheckedChangeListener
            }
            UiTheme.save(this@MainActivity, next)
            recreate()
        }
        add(root, label("唄まる -UtaMaru- v${displayVersion()} Android12+ ©赫ねこ", 12f, color = muted).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            maxLines = 1
            // Fit one footer line even on narrower Android 12 devices.
            setAutoSizeTextTypeUniformWithConfiguration(9, 12, 1, TextView.AUTO_SIZE_TEXT_TYPE_UNIFORM)
        }, 18)
    }

    private fun updateOutputBoostLabel() {
        if (::outputBoostLabel.isInitialized) {
            val ratio = Math.pow(10.0, outputBoostDb / 20.0)
            outputBoostLabel.text = String.format(Locale.JAPAN,
                "現在：+%d dB（最大約%.1f倍）", outputBoostDb, ratio)
        }
    }

    private fun updatePitchRangeLabel() {
        if (::pitchRangeLabel.isInitialized) {
            pitchRangeLabel.text = String.format(Locale.JAPAN,
                "現在の範囲：%.2f〜%.2f倍",
                pitchRange.minimum, pitchRange.maximum)
        }
    }

    private fun chooseRestMode() {
        if (active) return
        val choices = RestPlaybackMode.entries.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(UiLanguage.text(this, "休符の処理方法"))
            .setSingleChoiceItems(choices.map { UiLanguage.text(this, it.caption) }.toTypedArray(), restMode.ordinal) { dialog, index ->
                restMode = choices[index]
                restModeButton.text = "休符：${restMode.caption} ▼"
                getSharedPreferences("utamaru_audio_settings", MODE_PRIVATE)
                    .edit().putString("rest_playback_mode", restMode.name).apply()
                dialog.dismiss()
            }
            .setNegativeButton(UiLanguage.text(this, "キャンセル"), null)
            .show()
    }

    private fun updateBpm(value: Int) {
        currentBpm = value.coerceIn(60, 500)
        bpmStatus.text = "この楽譜：${currentBpm} BPM（変更は五線譜編集画面）"
    }

    /** Permission is requested only when the user starts recording in the editor. */
    fun ensureEditorMicrophonePermission(onGranted: () -> Unit) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            onGranted()
        } else {
            pendingEditorMicPermission = onGranted
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 102)
        }
    }

    private fun refreshPresetButtons() {
        for (i in 0..2) {
            val stored = presetStore.load(i)
            val caption = stored?.name ?: "未保存"
            presetButtons[i].text = "${i + 1}  ${if (i == currentPresetSlot) "●" else ""}\n${caption.take(10)}"
        }
        val slot = currentPresetSlot
        presetStatus.text = if (slot == null) "未選択の楽譜（保存先は下のボタンで指定）"
            else "呼出中：${slot + 1}番 ${presetStore.load(slot)?.name ?: ""}　※変更は保存ボタンで確定"
    }

    private fun restoreLastPreset() {
        val slot = presetStore.lastSlot() ?: return
        val item = presetStore.load(slot) ?: return
        applyPreset(slot, item)
    }

    private fun applyPreset(slot: Int, item: SavedScorePreset) {
        scoreInput.setText(item.scoreText)
        scoreInput.setSelection(item.scoreText.length)
        updateBpm(item.bpm)
        currentPresetSlot = slot
        loadedPresetScore = item.scoreText
        loadedPresetBpm = item.bpm
        presetStore.markLastSlot(slot)
        refreshPresetButtons()
        Toast.makeText(this, UiLanguage.text(this, "${item.name}を読み込みました"), Toast.LENGTH_SHORT).show()
    }

    private fun hasUnsavedEdits(): Boolean {
        val current = scoreInput.text.toString()
        val loaded = loadedPresetScore ?: "D3A3F# GB3G3G"
        val savedBpm = loadedPresetBpm ?: 200
        return current != loaded || currentBpm != savedBpm
    }

    private fun recallPreset(slot: Int) {
        if (active) return
        val item = presetStore.load(slot)
        if (item == null) {
            Toast.makeText(this, UiLanguage.text(this, "${slot + 1}番は未保存です。下の保存ボタンから登録できます"), Toast.LENGTH_LONG).show()
            return
        }
        if (hasUnsavedEdits()) {
            AlertDialog.Builder(this)
                .setTitle(UiLanguage.text(this, "編集中の変更を破棄しますか？"))
                .setMessage(UiLanguage.text(this, "現在の楽譜やBPMには未保存の変更があります。${item.name}を読み込むと元に戻せません。"))
                .setNegativeButton(UiLanguage.text(this, "キャンセル"), null)
                .setPositiveButton(UiLanguage.text(this, "読み込む")) { _, _ -> applyPreset(slot, item) }
                .show()
        } else applyPreset(slot, item)
    }

    private fun selectSaveSlot() {
        if (active) return
        val score = try {
            ScoreParser.serialize(ScoreParser.parseCompleteMeasures(scoreInput.text.toString()))
        } catch (e: Exception) {
            scoreInput.error = UiLanguage.text(this, e.message.orEmpty())
            Toast.makeText(this, UiLanguage.text(this, "楽譜の入力を確認してください"), Toast.LENGTH_SHORT).show()
            return
        }
        val captions = Array(3) { index ->
            val existing = presetStore.load(index)
            "${index + 1}番：${existing?.name ?: "（未保存）"}"
        }
        AlertDialog.Builder(this)
            .setTitle(UiLanguage.text(this, "保存先のプリセットを選択"))
            .setItems(captions) { _, selected -> askPresetName(selected, score, currentBpm) }
            .setNegativeButton(UiLanguage.text(this, "キャンセル"), null)
            .show()
    }

    private fun askPresetName(slot: Int, score: String, bpm: Int) {
        val old = presetStore.load(slot)
        val nameInput = EditText(this).apply {
            setSingleLine(true)
            setText(old?.name ?: "曲${slot + 1}")
            setSelectAllOnFocus(true)
            hint = UiLanguage.text(this@MainActivity, "曲名")
        }
        AlertDialog.Builder(this)
            .setTitle(UiLanguage.text(this, "${slot + 1}番に${if (old == null) "保存" else "上書き保存"}"))
            .setMessage(UiLanguage.text(this, "曲名を入力してください。楽譜とBPMを一緒に保存します。"))
            .setView(nameInput)
            .setNegativeButton(UiLanguage.text(this, "キャンセル"), null)
            .setPositiveButton(UiLanguage.text(this, "保存")) { _, _ ->
                val name = nameInput.text.toString().trim().take(24).ifBlank { "曲${slot + 1}" }
                val stored = SavedScorePreset(name, score, bpm)
                if (presetStore.save(slot, stored)) {
                    // The saved text uses complete measures, including trailing rests.
                    scoreInput.setText(score)
                    scoreInput.setSelection(score.length)
                    currentPresetSlot = slot
                    loadedPresetScore = score
                    loadedPresetBpm = bpm
                    refreshPresetButtons()
                    Toast.makeText(this, UiLanguage.text(this, "${name}を${slot + 1}番に保存しました"), Toast.LENGTH_LONG).show()
                } else Toast.makeText(this, UiLanguage.text(this, "保存できませんでした"), Toast.LENGTH_LONG).show()
            }.show()
    }

    private fun openGraphicalEditor() {
        if (active) return
        val parsed = try { ScoreParser.parseCompleteMeasures(scoreInput.text.toString()) }
                     catch (e: Exception) {
                         scoreInput.error = UiLanguage.text(this, e.message.orEmpty())
                         Toast.makeText(this, UiLanguage.text(this, "楽譜の文字入力を確認してください"), Toast.LENGTH_SHORT).show()
                         return
                     }
        scoreEditor = PianoRollEditor(
            activity = this,
            source = parsed,
            initialBpm = currentBpm,
            onSave = { scoreText, updatedBpm ->
                scoreInput.setText(scoreText)
                scoreInput.setSelection(scoreText.length)
                updateBpm(updatedBpm)
            },
            carriedHistory = editorHistory,
            onHistorySaved = { state -> editorHistory = state }
        ).also { it.show() }
    }

    /** Start from this button, or stop here without scrolling elsewhere. */
    private fun toggleMicButton(target: OutputTarget, directMic: Boolean) {
        if (active) {
            if (activeVoiceMode && activeOutputTarget == target && activeDirectMic == directMic)
                stopAudio()
            return
        }
        if (!stoppingAudio) requestMicThenBegin(target, directMic)
    }

    private fun requestMicThenBegin(target: OutputTarget, directMic: Boolean = false) {
        if (active) return
        speakerWarningShown = false
        // Detect attached outputs BEFORE opening a live feedback path.
        if (target == OutputTarget.ANDROID && !AudioOutputSafety.hasConnectedExternalOutput(this)) {
            AlertDialog.Builder(this)
                .setTitle(UiLanguage.text(this, "ハウリングに注意"))
                .setMessage(UiLanguage.text(this, "外部スピーカーやイヤホンの接続が確認できません。スマホの内蔵スピーカーから出る音をマイクが拾うと、強いハウリングが起こる場合があります。音量を下げ、マイクとスピーカーを離してください。\n\nこのまま開始しますか？"))
                .setNegativeButton(UiLanguage.text(this, "キャンセル"), null)
                .setPositiveButton(UiLanguage.text(this, "注意して開始")) { _, _ ->
                    speakerWarningShown = true
                    proceedMicStart(target, directMic)
                }
                .show()
        } else proceedMicStart(target, directMic)
    }

    private fun proceedMicStart(target: OutputTarget, directMic: Boolean) {
        pendingTarget = target
        pendingDirectMic = directMic
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            begin(voiceMode = true, target = target, directMic = directMic)
        } else {
            pendingMicStart = true
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 100)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 102) {
            val callback = pendingEditorMicPermission
            pendingEditorMicPermission = null
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) callback?.invoke()
            else Toast.makeText(this, UiLanguage.text(this, "楽譜録音にはマイクの許可が必要です"), Toast.LENGTH_LONG).show()
            return
        }
        if (requestCode == 100 && pendingMicStart) {
            pendingMicStart = false
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                begin(voiceMode = true, target = pendingTarget, directMic = pendingDirectMic)
            } else {
                info.text = "マイク権限がないため音声変換できません。設定から許可してください。"
            }
        }
    }

    private fun begin(voiceMode: Boolean, target: OutputTarget, directMic: Boolean = false) {
        if (active) return
        // 生マイクは楽譜入力やBPMに依存しない。楽譜が編集中でも利用可能。
        val score = if (directMic) emptyList() else try { ScoreParser.parseCompleteMeasures(scoreInput.text.toString()) }
                    catch (e: Exception) {
                        scoreInput.error = UiLanguage.text(this, e.message.orEmpty())
                        return
                    }
        val bpm = currentBpm
        val voiceEngine = VoiceEngine.entries[voiceEngineSpinner.selectedItemPosition.coerceIn(0, VoiceEngine.entries.lastIndex)]
        val gate = noiseGateCheck.isChecked
        try {
            engine.start(score, bpm, voiceMode, target, pcHostInput.text.toString().trim(),
                voiceEngine = voiceEngine, noiseGateEnabled = gate, restMode = restMode,
                pauseScoreOnSilence = pauseScoreOnSilenceCheck.isChecked,
                maxPitchRatio = pitchRange.maximum, minPitchRatio = pitchRange.minimum,
                nearUnityEnabled = naturalVoiceCheck.isChecked,
                originalMonitor = originalMonitorCheck.isChecked, directMic = directMic)
            if (voiceMode && !directMic) getSharedPreferences("utamaru_audio_settings", MODE_PRIVATE)
                .edit().putString("voice_engine", voiceEngine.name).apply()
            playingAndroidTest = !voiceMode && target == OutputTarget.ANDROID
            activeOutputTarget = target
            activeVoiceMode = voiceMode
            activeDirectMic = directMic
            stoppingAudio = false
            if (target == OutputTarget.PC) speakerWarningShown = false
            lastAudioError = null
            info.text = if (directMic) "生マイクを起動中…（音程変換・ノイズゲートなし）" else
                UiLanguage.text(this, "起動中…  |  ${score.size}音  |  $bpm BPM  |  音質: ${UiLanguage.text(this, voiceEngine.caption)}") +
                if (voiceMode) UiLanguage.text(this, "  |  範囲${pitchRange.minimum}〜${pitchRange.maximum}倍") else ""
            setRunning(true)
        } catch (e: Exception) {
            info.text = UiLanguage.text(this, e.message ?: "開始に失敗しました")
        }
    }

    private fun renderStatus(s: AudioEngine.AudioStatus) {
        fun hz(n: Double?): String =
            if (n == null) "検出なし" else String.format(Locale.JAPAN, "%.1f Hz", n)
        val multiplier = s.ratio?.let { String.format(Locale.JAPAN, "%.3f 倍", it) } ?: "—"
        info.text = if (s.mode == "生マイク")
            "動作中: 生マイク（音程変換なし）\nノイズ抑制: ${s.noiseGate}\n出力先: ${s.route}" else
            "動作中: ${s.mode}\n" +
                "現在の音符: ${s.note}\n" +
                "目標音程: ${hz(s.targetHz)}\n" +
                "入力声の音程: ${if (s.mode == "テスト音") "—" else hz(s.detectedHz)}\n" +
                "変換倍率: $multiplier（${pitchRange.minimum}〜${pitchRange.maximum}倍）\n" +
                "変換方式: ${s.qualityMode}  |  ノイズ抑制: ${s.noiseGate}\n" +
                "声質比較: ${s.naturalVoice}\n" +
                "出力先: ${s.route}"
        // Reset warning eligibility when an external output becomes the actual route.
        // A subsequent switch back to the internal speaker must warn again.
        if (activeOutputTarget == OutputTarget.ANDROID &&
            !s.routedToPhoneSpeaker && !s.route.contains("確認中")) {
            speakerWarningShown = false
        }
        if (activeOutputTarget == OutputTarget.ANDROID && s.routedToPhoneSpeaker &&
            !speakerWarningShown && !isFinishing) {
            speakerWarningShown = true
            AlertDialog.Builder(this)
                .setTitle(UiLanguage.text(this, "内蔵スピーカーへの出力を検出"))
                .setMessage(UiLanguage.text(this, "実際の音声出力先がスマホ内蔵スピーカーです。ハウリングする場合があります。音量を下げるか、外部出力へ切り替えてください。"))
                .setNegativeButton(UiLanguage.text(this, "停止する")) { _, _ -> stopAudio() }
                .setPositiveButton(UiLanguage.text(this, "続行する"), null)
                .show()
        }
    }

    private fun setRunning(value: Boolean) {
        active = value
        val androidConverted = value && activeVoiceMode &&
            activeOutputTarget == OutputTarget.ANDROID && !activeDirectMic
        val androidRaw = value && activeVoiceMode &&
            activeOutputTarget == OutputTarget.ANDROID && activeDirectMic
        val pcConverted = value && activeVoiceMode &&
            activeOutputTarget == OutputTarget.PC && !activeDirectMic
        val pcRaw = value && activeVoiceMode &&
            activeOutputTarget == OutputTarget.PC && activeDirectMic
        startVoice.isEnabled = !value || (androidConverted && !stoppingAudio)
        startVoice.text = if (androidConverted) "■ 音程変換再生を停止"
                          else "マイク入力を音程変換して再生"
        startRawAndroid.isEnabled = !value || (androidRaw && !stoppingAudio)
        startRawAndroid.text = if (androidRaw) "■ マイク入力の再生を停止"
                               else "マイク入力をそのまま再生"
        startPcVoice.isEnabled = !value || (pcConverted && !stoppingAudio)
        startPcVoice.text = if (pcConverted) "■ 音程変換送信を停止"
                            else "マイク入力を音程変換してPCへ送る"
        startRawPc.isEnabled = !value || (pcRaw && !stoppingAudio)
        startRawPc.text = if (pcRaw) "■ マイク入力の送信を停止"
                          else "マイク入力をそのままPCへ送る"
        startTest.isEnabled = !value || (playingAndroidTest && !stoppingAudio)
        startTest.text = if (value && playingAndroidTest) "■ 楽譜のテスト再生を停止"
                         else "楽譜をテスト音で再生"
        startPcTest.isEnabled = !value
        pcHostInput.isEnabled = !value
        scoreInput.isEnabled = !value
        scoreEditorButton.isEnabled = !value
        savePresetButton.isEnabled = !value
        if (::presetButtons.isInitialized) for (button in presetButtons) button.isEnabled = !value
        voiceEngineSpinner.isEnabled = !value
        languageSpinner.isEnabled = !value
        pauseScoreOnSilenceCheck.isEnabled = !value
        noiseGateCheck.isEnabled = !value
        naturalVoiceCheck.isEnabled = !value &&
            voiceEngineSpinner.selectedItemPosition != VoiceEngine.RUBBER_BAND.ordinal
        originalMonitorCheck.isEnabled = !value &&
            voiceEngineSpinner.selectedItemPosition != VoiceEngine.RUBBER_BAND.ordinal
        pitchRangeSlider.isEnabled = !value
        restModeButton.isEnabled = !value
        if (value) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (!isFinishing) info.text = "停止しました。再開できます。"
        }
    }

    private fun stopAudio() {
        if (!active || stoppingAudio) return
        stoppingAudio = true
        // Disable only the active stop button until AudioEngine reports completion.
        setRunning(true)
        engine.stop()
        info.text = "停止中…"
    }

    override fun onStop() {
        super.onStop()
        engine.stop() // Background recording is deliberately disabled in this prototype.
        scoreEditor?.stopRecording()
    }

    override fun onDestroy() {
        engine.stop()
        scoreEditor?.stopRecording()
        super.onDestroy()
    }
}
