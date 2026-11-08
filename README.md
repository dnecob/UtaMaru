# 唄まる -UtaMaru-

### Sorry, It's Japanese-Only for Now...
Support for additional languages is planned once the audio quality improvements have reached a satisfactory level.<br>
For now, development is focused on improving sound quality and stability.

**楽譜に指定した音程へ、Androidスマートフォンのマイク音声をリアルタイムで変換するアプリです。<br>
** 画面内の楽譜を繰り返し再生しながら声の音程を合わせ、スマホで再生したり、Windowsへ送ってPCマイクとして利用したりできます。<br>
 入力音声をそのまま出すモードも搭載しています。

- 対応OS：**Android 12以降（API 31以上）**<br>
- PCマイク機能：**Windows 10/11（64bit）**、USBまたはWi-Fi接続<br>
- 開発状況：**ベータ版 0.8.7.20**。音質・安定性の改善を優先して開発中です<br>

## ダウンロード

公開済みのAPKやWindowsReceiverの配布ファイルは、このリポジトリの **Releases** から入手してください。<br>
Releasesに配布ファイルが表示されない場合は、まだ公開されていません。

- Android：`UtaMaru` のAPK（配布用に署名されたもの）<br>
- Windows：`WindowsReceiver` のZIP（Windowsの仮想マイクとして使う場合のみ）

スマホ単体で使う場合に **WindowsReceiver、ADB、VB-CABLEは必要ありません**。

## スマホだけで使う場合

1. APKをAndroid 12以降の端末にインストールして起動し、マイクの使用を許可します。<br>
2. イヤホンなどを接続し、Androidの音声出力先を設定します。内蔵スピーカーを使う場合はハウリングに注意してください。<br>
3. 楽譜を文字で入力するか、**「♫ 五線譜で楽譜を編集」** から音符やBPMを編集します。<br>
4. 音声変換エンジンを選びます。最初は **「標準」** での動作確認をおすすめします。<br>
5. **「マイク入力を音程変換して再生」** を押して開始します。変換せずに再生する場合は **「マイク入力をそのまま再生」** を押します。<br>
6. 停止するときは、使用中のボタンをもう一度押します。

操作説明は、スマホでも読みやすいテキスト版 **[ReadMe.txt](ReadMe.txt)** にもあります。

### 楽譜の入力形式

- 音名 `C D E F G A B` と、任意の `#`（シャープ）、`b`（フラット）、オクターブ番号 `1`～`6` を並べます。例：`C4` `F#4` `Bb5`。<br>
- オクターブ番号を省略すると `4` になります（例：`C` は `C4`）。<br>
- **音符1個＝1拍**。音符同士の区切り文字は不要です。<br>
- **半角スペース1個＝休符1拍**。前後の空白も休符として扱われます。<br>
- 例：`D3A3F# GB3G3G`（`F#` と `G` の間に1拍の休符）。<br>
- 最大256拍。4拍子の小節が途中で終わる場合は、末尾が休符で補完されます。<br>
- BPMは五線譜編集画面で設定します（60～500、初期値200）。

楽譜の文字列をコピー＆ペーストして、ほかの利用者と共有できます。 

### 音声設定

- **標準**：通常の変換方式<br>
- **音質改善（PSOLA風）**：試験的な変換方式。周期的なノイズが出る場合があります<br>
- **Rubber Band**：Rubber Band Libraryを利用する方式（フォルマント維持）<br>
- **休符の音声出力**：生の声／無音／直前の音程を維持<br>
- **出力ブースト**：0～+18 dB。上げすぎるとノイズやハウリングが増幅されることがあります

変換倍率には設定された上限・下限があり、声の音程が楽譜の音に必ず完全一致するわけではありません。

## Windowsのマイクとして使う場合<br>

WindowsReceiverを使ってPCに音声を送り、VB-CABLE経由でDiscord・OBSなどの入力に利用できます。

詳しいセットアップ手順は **[WindowsReceiver/ReadMe.txt](WindowsReceiver/ReadMe.txt)** を参照してください。

- USB接続：ADB（Google SDK Platform-Tools）が必要。アプリのPC接続先は `127.0.0.1`<br>
- Wi-Fi接続：PCとスマホを同じLANに接続し、アプリのPC接続先にPCのIPv4アドレスを入力<br>
- Windowsのマイク入力デバイスには **`CABLE Output`** を指定

VB-CABLEとADBは同梱していません。公式配布元から入手してください。

- [VB-CABLE（公式）](https://vb-audio.com/Cable/)<br>
- [Android SDK Platform-Tools（Google公式）](https://developer.android.com/tools/releases/platform-tools?hl=ja)

## 開発・ビルド

Android Studioでリポジトリのルートを開いてください。<br>
Android SDK API 36、JDK 17、対応するNDKとCMakeが必要です。<br>
SDKのパスは各自の `local.properties` に設定します（Git管理対象外）。

**Rubber Band Library 4.0.0のソースは `third_party/rubberband-4.0.0/` に同梱しています。<br>
** `SETUP_RUBBERBAND.bat` はソースが存在しない場合の再取得用で、通常のクローン後に毎回実行する必要はありません。<br>
Androidアプリのネイティブビルドには同梱ソースの `single/RubberBandSingle.cpp` を利用します。

`BUILD_UTAMARU_APK.bat` は **デバッグ署名のAPK** を作成します。Releasesに配布するAPKは、別途リリース署名してください。署名鍵とパスワードをGitHubにアップロードしないでください。

## ソースコード・ライセンス

- 唄まるAndroidアプリの独自ソースコードは **GNU GPL version 2 or later（GPL-2.0-or-later）** の条件で公開する想定です。<br>
- ライセンス本文：[LICENSE](LICENSE)<br>
- 組み込みライブラリ **Rubber Band Library 4.0.0** もGPL-2.0-or-laterの下で再配布しています。<br>
- 著作権・ライセンス本文は `third_party/rubberband-4.0.0/README.md`、`third_party/rubberband-4.0.0/COPYING` を参照してください。<br>
- その他の第三者コードの表示：[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)

GPLに基づく配布物の対応ソースコードは、実際に配布するAPKと一致するバージョンを提供してください。<br>
本リポジトリにはRubber Bandのソース一式を含めています。

※ WindowsReceiverの実行ファイルについては、このリポジトリ内に対応するソースコードが含まれていません。<br>
 WindowsReceiverを含む配布物の権利・配布条件は別途確認してください。
