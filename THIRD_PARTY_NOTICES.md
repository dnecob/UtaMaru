# 第三者ソフトウェアの著作権・ライセンス表示

この文書は、唄まるのソースコードに同梱される第三者ソフトウェアの出所を示します。
**第三者の著作権はそれぞれの権利者に帰属し、唄まるの独自ソースのライセンス表示がこれらの条件を置き換えることはありません。**

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

## Rubber Bandの配布ソースに含まれる第三者コード

以下はRubber Band配布ソースに含まれるコードの表示です。唄まるの現行ネイティブビルドはRubber Bandの組み込みFFT／リサンプラ構成を用いており、以下のコードすべてをバイナリにリンクするという意味ではありません。

### KissFFT

- 著作権：Copyright (c) 2003–2010 Mark Borgerding
- ライセンス：BSD-3-Clause（SPDX表記）
- ソース中の表示：`third_party/rubberband-4.0.0/src/ext/kissfft/COPYING`
- 公式コード：https://github.com/mborgerding/kissfft

### Speex（リサンプラ関連コード）

- 著作権：Xiph.org Foundation、Jean-Marc Valin、およびソース内に記載される各権利者
- ライセンス：BSD系の許諾条件（著作権・免責・非推薦条項を含む）
- ライセンス全文：`third_party/rubberband-4.0.0/src/ext/speex/COPYING`

Rubber Bandの元の `README.md` と `COMPILING.md` にその他の依存物や条件も記載されています。Rubber Bandのソースを更新したりビルド方式を変更する場合は、同梱されるコードとライセンス表示を再確認してください。

## WindowsReceiver／VB-CABLE／ADB

- WindowsReceiverは唄まるのPCマイク機能用の別プログラムです。今回確認したプロジェクトには実行ファイルがありますが、対応ソースコードや独立したライセンス表示は含まれていません。配布条件は公開前に権利者が確認してください。
- VB-CABLEとAndroid SDK Platform-Tools（ADB）は本リポジトリや配布ZIPに同梱せず、利用者が各公式サイトから取得します。
  - VB-CABLE：https://vb-audio.com/Cable/
  - ADB：https://developer.android.com/tools/releases/platform-tools?hl=ja
