# 第三者ソフトウェアの著作権・ライセンス表示

この文書は、唄まるのソースコードに同梱、またはビルド時に取得して使用する第三者ソフトウェアの出所とライセンスを示します。
**第三者の著作権はそれぞれの権利者に帰属し、唄まるの独自ソースのライセンス表示がこれらの条件を置き換えることはありません。**

**配布時の注意：** GitHub上の文書だけでなく、配布するAPK・対応ソースに対して必要な著作権表示・ライセンス条件が利用者に提供されるようにしてください。Signalsmithを自動取得してビルドした場合は、使用したリビジョンとそのライセンスをリリースごとに確認してください。

## Rubber Band Library 4.0.0

- 名称：Rubber Band Library
- 原著作者：Chris Cannam
- 著作権：Copyright 2007–2024 Particular Programs Ltd (trading as Breakfast Quay)
- ライセンス：GNU General Public License, version 2 or (at your option) any later version（GPL-2.0-or-later）。権利者との別途商用ライセンスも存在しますが、本リポジトリではGPLに基づく使用・再配布を想定しています。
- 公式サイト：https://breakfastquay.com/rubberband/
- ソースリポジトリ（公式ミラー）：https://github.com/breakfastquay/rubberband
- 本リポジトリの同梱ソース：`third_party/rubberband-4.0.0/`
- ライセンス全文：`third_party/rubberband-4.0.0/COPYING`

Androidアプリのネイティブライブラリは、同梱された `single/RubberBandSingle.cpp` およびそこから参照されるRubber Bandのソースで構築します。対応ソースコードとビルド設定はAPKの配布バージョンに合わせて提供する必要があります。

## Signalsmith Stretch

- 公式リポジトリ：https://github.com/Signalsmith-Audio/signalsmith-stretch
- ライセンス：MIT License
- 著作権：Copyright (c) 2022 Geraint Luff / Signalsmith Audio Ltd.
- ビルド時の取得先：`third_party/signalsmith-stretch/`
- 上流のライセンス原文：https://github.com/Signalsmith-Audio/signalsmith-stretch/blob/main/LICENSE.txt

MIT License（原文・著作権表示を含む）：

```text
MIT License

Copyright (c) 2022 Geraint Luff / Signalsmith Audio Ltd.

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## Signalsmith Linear

- 公式リポジトリ：https://github.com/Signalsmith-Audio/linear
- ライセンス：MIT License
- 著作権：Copyright (c) 2025 Signalsmith Audio
- ビルド時の取得先：`third_party/signalsmith-linear/`
- 上流のライセンス原文：https://github.com/Signalsmith-Audio/linear/blob/main/LICENSE.txt

MIT License（原文・著作権表示を含む）：

```text
MIT License

Copyright (c) 2025 Signalsmith Audio

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## Rubber Bandの配布ソースに含まれる第三者コード

以下はRubber Band配布ソースに含まれるコードの表示です。唄まるの現行ネイティブビルドはRubber Bandの組み込みFFT／リサンプラ構成を用いており、以下のコードすべてをバイナリにリンクするという意味ではありません。

### KissFFT

- 著作権：Copyright (c) 2003–2010 Mark Borgerding
- ライセンス：BSD-3-Clause（SPDX表記）
- ソース中の表示：`third_party/rubberband-4.0.0/src/ext/kissfft/COPYING`
- BSD-3-Clauseライセンス全文：`third_party/rubberband-4.0.0/LICENSES/BSD-3-Clause`
- 公式コード：https://github.com/mborgerding/kissfft

### Speex（リサンプラ関連コード）

- 著作権：Xiph.org Foundation、Jean-Marc Valin、およびソース内に記載される各権利者
- ライセンス：BSD系の許諾条件（著作権・免責・非推薦条項を含む）
- ライセンス全文：`third_party/rubberband-4.0.0/src/ext/speex/COPYING`

Rubber Bandの元の `README.md` と `COMPILING.md` にその他の依存物や条件も記載されています。Rubber Bandのソースを更新したりビルド方式を変更する場合は、同梱されるコードとライセンス表示を再確認してください。

## WindowsReceiver／VB-CABLE／ADB

- WindowsReceiverは唄まるのPCマイク機能用の別プログラムです。READMEにはソースコード `WindowsReceiver/src/main.go`、ライセンス・ビルド情報 `WindowsReceiver/SOURCE_AND_LICENSE.txt` が案内されています。公開リポジトリと配布するWindowsReceiver.zipに対応ソースおよびライセンス情報が用意されているか、リリース時に確認してください。
- VB-CABLEとAndroid SDK Platform-Tools（ADB）は本リポジトリや配布ZIPに同梱せず、利用者が各公式サイトから取得します。
  - VB-CABLE：https://vb-audio.com/Cable/
  - ADB：https://developer.android.com/tools/releases/platform-tools?hl=ja
