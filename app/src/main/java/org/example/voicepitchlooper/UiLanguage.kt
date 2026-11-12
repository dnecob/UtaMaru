package org.example.voicepitchlooper

import android.content.Context
import android.content.res.Configuration
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import java.util.Locale

/** UI-only translation: never changes score parsing or audio engine state strings. */
object UiLanguage {
    private const val PREF_NAME = "utamaru_ui_language"
    private const val PREF_KEY = "language"
    val choices = listOf("auto", "ja", "zh-Hant", "en")

    fun selected(context: Context): String = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        .getString(PREF_KEY, "auto")?.takeIf { it in choices } ?: "auto"

    fun save(context: Context, choice: String) {
        require(choice in choices)
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putString(PREF_KEY, choice).apply()
    }

    private fun resolvedLanguage(context: Context): String {
        val chosen = selected(context)
        if (chosen != "auto") return chosen
        val sys = context.resources.configuration.locales[0]
        if (sys.language == "ja") return "ja"
        if (sys.language == "en") return "en"
        if (sys.language == "zh" && (sys.script == "Hant" ||
                sys.country in listOf("TW", "HK", "MO"))) return "zh-Hant"
        // Use English when the device language is not supported.
        return "en"
    }

    fun wrap(base: Context): Context {
        val language = resolvedLanguage(base)
        val locale = Locale.forLanguageTag(language)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }

    // Resources are selected by the Activity's localized Context.
    private val staticIds = mapOf(
        "Android 12+  |  PCマイク対応・試験版 0.8.7.21" to R.string.ui_001,
        "楽譜を編集" to R.string.ui_002,
        "例：D3A3F# GB3G3G（空白=1拍休符、#=半音上、数字=オクターブ）" to R.string.ui_003,
        "♫ 五線譜で楽譜を編集" to R.string.ui_004,
        "縦ドラッグで半音移動・1秒試聴。初期2小節・小節追加可能。" to R.string.ui_005,
        "楽譜プリセット（3曲まで保存）" to R.string.ui_006,
        "保存した曲をタップして呼び出せます" to R.string.ui_007,
        "現在の楽譜とBPMをプリセットに保存" to R.string.ui_008,
        "音声変換エンジン" to R.string.ui_009,
        "音声変換エンジンを選択" to R.string.ui_010,
        "標準（低遅延・破裂音対策済み）" to R.string.ui_011,
        "音質改善（PSOLA風）" to R.string.ui_012,
        "Rubber Band（フォルマント維持）" to R.string.ui_013,
        "Rubber Band（未導入・セットアップが必要）" to R.string.ui_014,
        "切り替えは停止中のみ。Rubber Bandは導入後、声のフォルマント維持を試せます。" to R.string.ui_015,
        "音声がないときのノイズ抑制（推奨）" to R.string.ui_016,
        "音声がない間は楽譜を一時停止" to R.string.ui_224,
        "自然声優先（元の音程に近い声を再合成しない・試験）" to R.string.ui_017,
        "元の音程との差が15セント以内なら原音を優先し、25セント以上で変換音へ戻します。標準・PSOLA風のみ。OFFでv0.8.6相当の音程変換と比較できます。" to R.string.ui_018,
        "原音モニター（変換を聴き比べる診断用）" to R.string.ui_019,
        "ピッチ変換倍率の範囲" to R.string.ui_020,
        "青＝低音側の下限、緑＝高音側の上限。左右のつまみを動かして指定。従来の設定は0.50〜3.50倍。範囲を広げるほど声質が崩れやすくなります。" to R.string.ui_021,
        "休符の音声出力" to R.string.ui_022,
        "生の声をそのまま出す" to R.string.ui_023,
        "無音にする" to R.string.ui_024,
        "直前の音程を維持" to R.string.ui_025,
        "直前の音程はループ末尾からも引き継ぎます。テスト音の休符は常に無音です。" to R.string.ui_026,
        "出力ブースト" to R.string.ui_027,
        "出力ブーストを調整" to R.string.ui_028,
        "スマホ再生・PC送信に共通。0 dBは従来と同じ音量。再生中も変更可能。上げすぎるとノイズ・ハウリングの危険があります。音割れを抑えるため、ピークが大きい場合は増幅を自動制限します。" to R.string.ui_029,
        "楽譜をテスト音で再生" to R.string.ui_030,
        "スマホで使う（スピーカー・イヤホン出力）" to R.string.ui_031,
        "マイク入力を音程変換して再生" to R.string.ui_032,
        "マイク入力をそのまま再生" to R.string.ui_033,
        "外部出力が確認できない場合はハウリング注意を表示します。接続済みでも実際の出力先が内蔵スピーカーなら警告します。" to R.string.ui_034,
        "PC用マイク（USB / Wi-Fi）" to R.string.ui_035,
        "USBでは 127.0.0.1。Wi-FiではPCのIPv4アドレスを指定。先にPC側の受信を開始。" to R.string.ui_036,
        "PCの接続先 (例: 192.168.1.10)" to R.string.ui_037,
        "PCへテスト音を送る" to R.string.ui_038,
        "マイク入力を音程変換してPCへ送る" to R.string.ui_039,
        "マイク入力をそのままPCへ送る" to R.string.ui_040,
        "モニター" to R.string.ui_041,
        "待機中。楽譜の試聴は五線譜編集画面から行えます。スマホ再生はAndroidのメディア出力先を設定。PC出力は受信プログラムを先に起動してください。" to R.string.ui_042,
        "注意: 実験版では声質・ノイズが悪化する場合があります。最初は音量を小さめに。スピーカーの音をマイクが拾うとハウリングするため、距離を離してください。アプリを離れると停止します。" to R.string.ui_043,
        "休符の処理方法" to R.string.ui_044,
        "キャンセル" to R.string.ui_045,
        "未保存" to R.string.ui_046,
        "未選択の楽譜（保存先は下のボタンで指定）" to R.string.ui_047,
        "編集中の変更を破棄しますか？" to R.string.ui_048,
        "読み込む" to R.string.ui_049,
        "楽譜の入力を確認してください" to R.string.ui_050,
        "保存先のプリセットを選択" to R.string.ui_051,
        "曲名" to R.string.ui_052,
        "保存" to R.string.ui_053,
        "上書き保存" to R.string.ui_054,
        "曲名を入力してください。楽譜とBPMを一緒に保存します。" to R.string.ui_055,
        "保存できませんでした" to R.string.ui_056,
        "楽譜の文字入力を確認してください" to R.string.ui_057,
        "ハウリングに注意" to R.string.ui_058,
        "外部スピーカーやイヤホンの接続が確認できません。スマホの内蔵スピーカーから出る音をマイクが拾うと、強いハウリングが起こる場合があります。音量を下げ、マイクとスピーカーを離してください。\n\nこのまま開始しますか？" to R.string.ui_059,
        "注意して開始" to R.string.ui_060,
        "楽譜録音にはマイクの許可が必要です" to R.string.ui_061,
        "マイク権限がないため音声変換できません。設定から許可してください。" to R.string.ui_062,
        "開始に失敗しました" to R.string.ui_063,
        "検出なし" to R.string.ui_064,
        "生マイク" to R.string.ui_065,
        "マイク変換" to R.string.ui_066,
        "テスト音" to R.string.ui_067,
        "無変換" to R.string.ui_068,
        "音声加工なし" to R.string.ui_069,
        "Rubber Band対象外" to R.string.ui_070,
        "原音比較" to R.string.ui_071,
        "自然声優先" to R.string.ui_072,
        "従来処理" to R.string.ui_073,
        "Androidで選択したメディア出力先（確認中）" to R.string.ui_074,
        "内蔵スピーカーへの出力を検出" to R.string.ui_075,
        "実際の音声出力先がスマホ内蔵スピーカーです。ハウリングする場合があります。音量を下げるか、外部出力へ切り替えてください。" to R.string.ui_076,
        "停止する" to R.string.ui_077,
        "続行する" to R.string.ui_078,
        "■ 音程変換再生を停止" to R.string.ui_079,
        "■ マイク入力の再生を停止" to R.string.ui_080,
        "■ 音程変換送信を停止" to R.string.ui_081,
        "■ マイク入力の送信を停止" to R.string.ui_082,
        "■ 楽譜のテスト再生を停止" to R.string.ui_083,
        "停止しました。再開できます。" to R.string.ui_084,
        "停止中…" to R.string.ui_085,
        "音声エラーが発生しました" to R.string.ui_086,
        "▶ 楽譜再生" to R.string.ui_087,
        "■ 再生停止" to R.string.ui_088,
        "↶ やり直し" to R.string.ui_089,
        "この状態はすでに履歴にあります" to R.string.ui_090,
        "現在の楽譜をやり直し履歴に一時保存しました" to R.string.ui_091,
        "楽譜のテンポ（60～500 BPM）" to R.string.ui_092,
        "設定" to R.string.ui_093,
        "60～500の数字を入力してください" to R.string.ui_094,
        "■ 録音を停止" to R.string.ui_095,
        "● 先頭から録音" to R.string.ui_096,
        "● 現在位置から録音" to R.string.ui_097,
        "録音終了。内容を確認し、右上の「変更を反映」で保存してください。" to R.string.ui_098,
        "マイクを準備しています…" to R.string.ui_099,
        "先頭から録音" to R.string.ui_100,
        "現在位置から録音" to R.string.ui_101,
        "BPMに合わせた音付き8カウント後、1拍ずつ音符を上書きします。録音していない前後の拍はそのまま残ります。録音内容は「戻す」で取り消せます。" to R.string.ui_102,
        "録音開始" to R.string.ui_103,
        "録音をキャンセルしました" to R.string.ui_104,
        "◀ 譜面の横移動 ▶" to R.string.ui_105,
        "鼻歌・口笛から楽譜を作成（録音した拍だけ上書き）" to R.string.ui_106,
        "録音開始までの残りカウント" to R.string.ui_107,
        "音付き8カウント → マイク録音 → 拍ごとに音符化。60～500 BPM対応。" to R.string.ui_108,
        "選択拍を休符に変更" to R.string.ui_109,
        "1拍単位の挿入・削除（後ろの音符を移動）" to R.string.ui_110,
        "休符を挿入\n（後ろを1拍ずらす）" to R.string.ui_111,
        "休符を挿入し、末尾に1小節追加しました" to R.string.ui_112,
        "休符を挿入しました" to R.string.ui_113,
        "選択拍を削除\n（後ろを詰める）" to R.string.ui_114,
        "※ 4/4を維持：挿入時は末尾の休符を利用し、足りなければ1小節追加。削除後の末尾は休符になります。" to R.string.ui_115,
        "小節の追加・削除（1小節＝4拍）" to R.string.ui_116,
        "末尾に小節追加" to R.string.ui_117,
        "末尾に4小節追加" to R.string.ui_118,
        "選択小節の後に1小節挿入" to R.string.ui_119,
        "末尾の小節を\n削除" to R.string.ui_120,
        "最低2小節は残します" to R.string.ui_121,
        "末尾の小節を削除しますか？" to R.string.ui_122,
        "最後の4拍に音符があります。取り消しは「↶ 戻す」で可能です。" to R.string.ui_123,
        "削除" to R.string.ui_124,
        "選択小節より後の\n小節全削除" to R.string.ui_125,
        "最低2小節は残します。第2小節以降を選択してください" to R.string.ui_126,
        "選択小節より後に削除する拍はありません" to R.string.ui_127,
        "選択小節より後をすべて削除しますか？" to R.string.ui_128,
        "全削除" to R.string.ui_129,
        "楽譜全体のオクターブ移動（休符はそのまま）" to R.string.ui_130,
        "全音符を1オクターブ下げる" to R.string.ui_131,
        "全音符を1オクターブ上げる" to R.string.ui_132,
        "※ C1～B6の範囲を超える音符がある場合、全体の移動を取り消します。" to R.string.ui_133,
        "拍の移動（音階ボタンで入力するときに使用）" to R.string.ui_134,
        "◀ 前の拍" to R.string.ui_135,
        "休符" to R.string.ui_136,
        "次の拍 ▶" to R.string.ui_137,
        "音符入力後に次の拍へ自動送り" to R.string.ui_138,
        "※ 履歴は最大30段階。音符の配置・ドラッグ・休符への変更は自動で履歴を作りません。残したい状態は上部の「↰」で一時保存できます。" to R.string.ui_139,
        "楽譜" to R.string.ui_140,
        "現在の楽譜をやり直し履歴に一時保存" to R.string.ui_141,
        "戻る" to R.string.ui_142,
        "変更を反映" to R.string.ui_143,
        "編集できませんでした" to R.string.ui_144,
        "低音側" to R.string.ui_145,
        "高音側" to R.string.ui_146,
        "変換倍率の範囲を設定。左つまみは低音側、右つまみは高音側" to R.string.ui_147,
        "音声エラー:" to R.string.ui_148,
        "機種や音声経路によって48kHzが使えない場合があります。" to R.string.ui_149,
        "エラー全文を共有してください。" to R.string.ui_150,
        "確認中" to R.string.ui_151,
        "動作中:" to R.string.ui_152,
        "出力先:" to R.string.ui_153,
        "現在の音符:" to R.string.ui_154,
        "目標音程:" to R.string.ui_155,
        "入力声の音程:" to R.string.ui_156,
        "変換倍率:" to R.string.ui_157,
        "変換方式:" to R.string.ui_158,
        "ノイズ抑制:" to R.string.ui_159,
        "声質比較:" to R.string.ui_160,
        "動作中: 生マイク（音程変換なし）" to R.string.ui_161,
        "生マイクを起動中…（音程変換・ノイズゲートなし）" to R.string.ui_162,
        "起動中…" to R.string.ui_163,
        "音質:" to R.string.ui_164,
        "無音" to R.string.ui_165,
        "低音側の下限" to R.string.ui_166,
        "高音側の上限" to R.string.ui_167,
        "直前の音程は" to R.string.ui_168,
        "休符 →" to R.string.ui_169,
        "を継続" to R.string.ui_170,
        "音" to R.string.ui_171,
        "倍" to R.string.ui_172,
        "録音中：" to R.string.ui_173,
        "第" to R.string.ui_174,
        "小節" to R.string.ui_175,
        "拍目" to R.string.ui_176,
        "カウント" to R.string.ui_177,
        "番" to R.string.ui_178,
        "番に" to R.string.ui_179,
        "範囲" to R.string.ui_180,
        "現在の音域：" to R.string.ui_181,
        "録音開始まで" to R.string.ui_182,
        "音を1オクターブ" to R.string.ui_183,
        "を入力してください" to R.string.ui_184,
        "未導入" to R.string.ui_185,
        "表示言語" to R.string.ui_186,
        "自動（端末設定）" to R.string.ui_187,
        "言語の切り替えは停止中のみ行えます" to R.string.ui_188,
        "C(ド)" to R.string.ui_189,
        "C(ド)#" to R.string.ui_190,
        "D(レ)" to R.string.ui_191,
        "D(レ)#" to R.string.ui_192,
        "E(ミ)" to R.string.ui_193,
        "F(ファ)" to R.string.ui_194,
        "F(ファ)#" to R.string.ui_195,
        "G(ソ)" to R.string.ui_196,
        "G(ソ)#" to R.string.ui_197,
        "A(ラ)" to R.string.ui_198,
        "A(ラ)#" to R.string.ui_199,
        "B(シ)" to R.string.ui_200,
        "「↶ 戻す」で取り消せます。" to R.string.ui_201,
        "録音は既に開始されています" to R.string.ui_202,
        "この端末では48kHzの録音ができません" to R.string.ui_203,
        "マイクを初期化できません" to R.string.ui_204,
        "マイク録音を開始できません" to R.string.ui_205,
        "楽譜を入力してください" to R.string.ui_206,
        "音符を1つ以上入力してください" to R.string.ui_207,
        "音符を1つ以上配置してください" to R.string.ui_208,
        "音符は4096拍以内にしてください" to R.string.ui_209,
        "音符は4096個以内にしてください" to R.string.ui_210,
        "音符は最大4096拍（1024小節）までです" to R.string.ui_211,
        "2小節（8拍）より短くできません" to R.string.ui_212,
        "移動する音符がありません" to R.string.ui_213,
        "末尾に音符があり、1024小節を超えるため休符を挿入できません" to R.string.ui_214,
        "音符は最大4096拍までです" to R.string.ui_215,
        "楽譜は最低1拍必要です" to R.string.ui_216,
        "PCの接続先を入力してください" to R.string.ui_217,
        "不明なエラー" to R.string.ui_218,
        "この機器は48kHzモノラル出力に非対応です" to R.string.ui_219,
        "この機器は48kHzマイク入力に非対応です" to R.string.ui_220,
        "音声処理の停止中です。もう一度試してください。" to R.string.ui_221,
        "生マイクは音声入力モードのみ有効です" to R.string.ui_222,
        "Rubber Bandが未導入です。SETUP_RUBBERBAND.bat実行後にAPKを再ビルドしてください。" to R.string.ui_223,
    )

    private data class PatternSpec(val text: String, val resId: Int) {
        val regex: Regex = Regex("^" + text.split(Regex("\\{\\d+\\}"))
            .joinToString("([\\s\\S]*?)") { Regex.escape(it) } + "$", setOf(RegexOption.DOT_MATCHES_ALL))
    }

    private val patterns = listOf(
        PatternSpec("この楽譜：{0} BPM（変更は五線譜編集画面）", R.string.ui_dynamic_001),
        PatternSpec("現在：+{0} dB（最大約{1}倍）", R.string.ui_dynamic_002),
        PatternSpec("現在の範囲：{0}〜{1}倍", R.string.ui_dynamic_003),
        PatternSpec("休符：{0} ▼", R.string.ui_dynamic_004),
        PatternSpec("呼出中：{0}番 {1}　※変更は保存ボタンで確定", R.string.ui_dynamic_006),
        PatternSpec("{0}を読み込みました", R.string.ui_dynamic_007),
        PatternSpec("{0}番は未保存です。下の保存ボタンから登録できます", R.string.ui_dynamic_008),
        PatternSpec("現在の楽譜やBPMには未保存の変更があります。{0}を読み込むと元に戻せません。", R.string.ui_dynamic_009),
        PatternSpec("{0}番：{1}", R.string.ui_dynamic_010),
        PatternSpec("曲{0}", R.string.ui_dynamic_011),
        PatternSpec("{0}番に{1}", R.string.ui_dynamic_012),
        PatternSpec("{0}を{1}番に保存しました", R.string.ui_dynamic_013),
        PatternSpec("起動中…  |  {0}音  |  {1} BPM  |  音質: {2}", R.string.ui_dynamic_014),
        PatternSpec("  |  範囲{0}〜{1}倍", R.string.ui_dynamic_015),
        PatternSpec("音声エラー: {0}", R.string.ui_dynamic_016),
        PatternSpec("{0} BPM  （タップで数値入力）", R.string.ui_dynamic_017),
        PatternSpec("↶ やり直し ({0})", R.string.ui_dynamic_018),
        PatternSpec("録音開始まで {0} カウント（{1} BPM）", R.string.ui_dynamic_019),
        PatternSpec("カウント音を再生できません: {0}", R.string.ui_dynamic_020),
        PatternSpec("録音を開始しませんでした: {0}", R.string.ui_dynamic_021),
        PatternSpec("録音中：第{0}小節・{1}拍目を記録（{2} BPM）", R.string.ui_dynamic_022),
        PatternSpec("第{0}小節の{1}拍目から録音します。", R.string.ui_dynamic_023),
        PatternSpec("音階・音域を指定（現在の音域：{0}）", R.string.ui_dynamic_024),
        PatternSpec("◀ 譜面の横移動：{0} / {1} 小節 ▶", R.string.ui_dynamic_025),
        PatternSpec("第{0}小節まで残し、後ろの{1}拍（音符{2}個）を削除します。", R.string.ui_dynamic_026),
        PatternSpec("{0}音を1オクターブ下げました", R.string.ui_dynamic_027),
        PatternSpec("{0}音を1オクターブ上げました", R.string.ui_dynamic_028),
        PatternSpec("マイク読み取りエラー: {0}", R.string.ui_dynamic_029),
        PatternSpec("録音エラー: {0}", R.string.ui_dynamic_030),
        PatternSpec("音名を読み取れません: {0}", R.string.ui_dynamic_031),
        PatternSpec("使用できない文字: '{0}'（位置 {1}）", R.string.ui_dynamic_032),
        PatternSpec("{0} を移動すると音域C1～B6を超えます。楽譜は変更していません", R.string.ui_dynamic_033),
        PatternSpec("マイクの読み取りエラー: {0}", R.string.ui_dynamic_034),
        PatternSpec("スピーカーへの出力エラー: {0}", R.string.ui_dynamic_035),
    )

    private fun translated(context: Context, text: String): String? {
        staticIds[text]?.let { return context.getString(it) }
        for (pattern in patterns) {
            val match = pattern.regex.matchEntire(text) ?: continue
            var translation = context.getString(pattern.resId)
            for (i in 1 until match.groupValues.size) {
                translation = translation.replace("{" + (i - 1) + "}", text(context, match.groupValues[i]))
            }
            return translation
        }
        return null
    }

    /** Converts displayed Japanese only. Original engine/score identifiers are untouched. */
    fun text(context: Context, value: String): String {
        if (resolvedLanguage(context) == "ja" || value.isEmpty()) return value
        translated(context, value)?.let { return it }
        // Multi-line status messages have independently translated labels.
        if (value.contains('\n')) return value.split('\n').joinToString("\n") { text(context, it) }
        // A few interpolated status lines contain adjacent literal fragments.
        var out = value
        val fragments = listOf(
            "動作中:", "現在の音符:", "目標音程:", "入力声の音程:",
            "変換倍率:", "変換方式:", "ノイズ抑制:", "声質比較:", "出力先:",
            "音声エラー:", "機種や音声経路によって48kHzが使えない場合があります。",
            "エラー全文を共有してください。", "音質:", "休符 →", "を継続",
            "標準（低遅延・破裂音対策済み）", "音質改善（PSOLA風）",
            "Rubber Band（フォルマント維持）", "自然声優先", "原音比較", "従来処理",
            "無変換", "音声加工なし", "Rubber Band対象外", "生マイク", "マイク変換",
            "テスト音", "検出なし", "確認中", "起動中…", "倍"
        )
        for (fragment in fragments) {
            val target = staticIds[fragment] ?: continue
            if (out.contains(fragment)) out = out.replace(fragment, context.getString(target))
        }
        return out
    }
}

class LocalizedTextView(context: Context) : TextView(context) {
    override fun setText(text: CharSequence?, type: BufferType?) {
        super.setText(text?.let { UiLanguage.text(context, it.toString()) }, type)
    }
}

class LocalizedButton(context: Context) : Button(context) {
    override fun setText(text: CharSequence?, type: TextView.BufferType?) {
        super.setText(text?.let { UiLanguage.text(context, it.toString()) }, type)
    }
}

class LocalizedCheckBox(context: Context) : CheckBox(context) {
    override fun setText(text: CharSequence?, type: TextView.BufferType?) {
        super.setText(text?.let { UiLanguage.text(context, it.toString()) }, type)
    }
}
