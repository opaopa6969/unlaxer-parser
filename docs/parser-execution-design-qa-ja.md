# パーサの設計コストと機能選択 — 問いと回答

記録日: 2026-09-21

確認した実装: `bb5d83121d2ec5f83c67bb616a8e220c2a6c030b`

この文書は、パーサの性能と設計についての対話を整理した設計メモである。
以下の実行モード・API・準備層は提案であり、実装済みの機能や性能保証ではない。
実測した施策は [パフォーマンスチューニング実践ノート](performance-tuning-ja.md) に記録している。

## 問い

> このパーサーの遅さには、「全式を transaction で包む」「失敗のたびに expected を記録する」
> 「Expr 木を実行時に解釈する」という設計そのものの費用がある、という話がある。
> どう直すのがよいか。上の設計でユーザーは何を得ているのか。
> 必要がなければ一般的なアーキテクチャでもよいし、必要があれば、動的に定義された parser と
> Features 指定からアーキテクチャを変えてもよい。どんな案があるか。

元の問いの改行・表記を整理したもの。

## 回答の方向性

必要な機能を指定し、文法ごとに実行方式を準備する構成を提案する。
`ParseContext` は中心に残す。「状態を扱えること」と「すべての情報を常時収集すること」は
別の要求なので、使う機能が少ない parser には、その少なさを実行コストへ反映させたい。

ただし、「dispatch や transaction を減らせば大きく速くなる」という以前の見立ては、
最新の実験結果に合わせて修正する必要がある。

- #210 の Rust direct-rule-call 実験は、complex で約 2.8% 短縮、comparison-heavy で差なし。
  生成コード量と build 時間が増え、採用には至らなかった。Java の実測結果ではない。
- #239 の Rust 原子式 checkpoint 省略は、最初の小さな改善が再測定で消え、不採用になった。
- 一方、診断の収集・共有・merge・replay を見直す施策では、改善が繰り返し観測された。

根拠は実践ノートのケース 6〜8、10、17、18を参照する。
三つの設計は費用を持つが、個々の費用の大きさと削減効果は測定で判断する。
特に `Expr` 解釈と全式 checkpoint の説明は現在の Rust runtime に対応し、
Java の object/combinator 実行方式と完全に同一ではない。

## 現在の設計でユーザーが得るもの

| 仕組み | ユーザーが得るもの | 必要性と見直せる部分 |
|---|---|---|
| 全式を transaction で包む | 分岐失敗時に位置・capture・scope・user state を戻し、手書き parser も安全に合成できる | rollback の保証は必要。保存対象・境界・回数は条件付きで最適化できる |
| 失敗時に expected を記録する | 最終エラーの説明、補完候補、解析途中の診断参照 | 詳細情報を常時収集する必要は用途による |
| `Expr` 木を実行時に解釈する | 動的に組み立てた文法を実行でき、生成 parser と手書き combinator が同じ仕組みを使える | 動的に定義できても、入力ごとに同じ木を汎用的に解釈する必要はない |

## 提案1: 詳細診断を失敗時に作るモード

PEG の分岐失敗は正常な制御フローなので、最終的に成功する入力でも大量に発生する。
成功する式のコンパイルでは、返さない expected 集合を維持する仕事を減らせる可能性がある。

```text
通常の解析: AST・位置・必要な状態を作る。構文診断は最小限
  ├─ 成功 → 結果を返す
  └─ 全体失敗 → 同じ初期状態から詳細診断付きで再解析
```

減らす対象は構文上の試行失敗の記録である。成功時に返す未定義参照の警告など、
要求された意味診断は残す。

CPython には、通常解析が失敗した場合だけ、詳細な構文エラーを検出する `invalid_` 規則を
有効にして再解析する仕組みがある。unlaxer の expected 収集と同じ方式ではないが、
成功経路からエラー説明用の仕事を外す先例になる。
[CPython の実装資料](https://github.com/python/cpython/blob/main/InternalDocs/parser.md#how-syntax-errors-are-reported)

### 適用条件

custom parser は解析途中の診断を読めるし、listener は外部作用を起こすことがある。
自動再解析は、診断を受理判定に使わず、同じ初期状態から再実行可能と確認できる parser に限定する。
解析途中の診断参照、callback、外部 I/O、共有状態などを確認できない場合は従来方式を使う。
失敗時の state rollback だけでは、再実行の安全性を証明したことにならない。

TinyExpression の通常コンパイルには適する可能性がある。入力途中の失敗が多いエディタでは、
最初から詳細診断付きで動かす方がよい可能性もある。
再解析が必要な場合の memo の診断情報も、最初の簡易解析と混同せず管理する必要がある。

### 実測（2026-09-21、unlaxer-parser #257、Rust 限定の opt-in 実験）

`ParseOptions::with_diagnostics(Diagnostics::DetailedOnFailure)` として実装し、TinyExpression の fixture で計測した
（[実践ノート ケース25](performance-tuning-ja.md)）。

- 成功する入力: complex.tiny で **-48%**（x1）〜 **-35%**（x64、20.9 KB）。差分計測で見積もった上限（39〜45%）に近い
- 失敗する入力（前半で切断、末尾に `@`）: 再解析の分だけ **+46〜+73%**
- 受理結果・CST・capture・scope・`ParseError`（offset / expected）は `Detailed` と一致。既定は `Detailed` のまま

Java 側も同日に `ParseOptions.Diagnostics.DETAILED_ON_FAILURE` として実装した（unlaxer-parser #259、[ケース26](performance-tuning-ja.md)）。
parser 単体で -17%（x1）〜 -24.5%（x64）、失敗入力で +59〜+79%。Rust より効果が小さいのは、memo の transaction replay と parse frame の維持が
診断とは別に残るため。

用途で損益が逆転するので、runtime が既定を変えるのではなく利用者が要求として選ぶ形にした。解析中に診断を読む custom parser と
再実行できない副作用には不適で、この判定を parser の性質から自動化するのが提案2の役割になる。

## 提案2: Features は要求する結果を指定し、安全性は parser の性質から判定する

将来の API のイメージを次に示す。クラス名・定数名を含め、現在存在する API ではない。

```java
PreparedParser parser = grammar.prepare(
    ParseProfile.builder()
        .output(TYPED_AST_WITH_SPANS)
        .diagnostics(DETAILED_ON_FAILURE)
        .trace(OFF)
        .build()
);

parser.parse(context);
```

ユーザーに `transactions(false)` を選ばせるだけの構成は避ける。
文法が backreference や scope を受理判定に使っていれば、その状態管理は必須だからである。

準備処理へ渡す情報を二つに分ける。

- ユーザーの要求: AST、CST、位置情報、診断、補完、trace のどれが必要か。
- parser の性質: どの状態を読み書きするか、途中失敗で状態が残るか、再実行できるか。

この組み合わせから、安全な実行方式を選ぶ。性質を解析できない custom parser は通常の
`ParseContext` 経由で実行し、必要な機能が失われる指定は準備時にエラーにする。
高速モードの選択で文法の受理結果が変わってはいけない。

Java/Rust で API の表現は異なってよいが、要求する機能、適用条件、観測可能な結果は揃える。

### 実装（2026-09-21、最小案、unlaxer-parser #261）

決定: `ParseProfile` / `PreparedParser` は選ぶ軸が診断以外にも増えた時点で設計し、いまは既存 `ParseOptions` に `Auto`（Rust `Diagnostics::Auto`、
Java `Diagnostics.AUTO`）を足して**既定**にした。Auto は準備時に 1 回解決する: 生成 entry point（失敗時に `Detailed` で再解析できる）で
未宣言の custom / 手書き parser を含まなければ `DetailedOnFailure`、含めば `Detailed`、低水準 API では常に `Detailed`。custom parser は
Rust `Expr::CustomWith`、Java `DiagnosticsAgnostic` で「解析中に診断を読まない、再実行できる」を宣言し、未宣言は保守的に扱う。

実測（[ケース27](performance-tuning-ja.md)）: Rust の tinyexpression は既定で `DetailedOnFailure` に解決され facade -33.8% / -23.5%。
Java の tinyexpression は手書き `StringLiteralParser` が未宣言で、facade も低水準 API のため `DETAILED` のまま（tinyexpression #168）。
低水準 API 利用者の観測結果は変わらないことを test で固定した。要求する機能を診断以外（CST / trace / 補完）へ広げる段階で、
この解決規則を capability 表に一般化する（#264）。

## 提案3: 動的に定義した parser を一度だけ実行計画へ変換する

```text
UBNF / ParserCombinator による定義
              ↓
    文法を固定して性質を解析
              ↓
       必要な機能と照合
              ↓
       再利用可能な実行計画
              ↓
       ParseContext 上で実行
```

固定するのは文法であり、入力ごとに変わる `ParseContext` の値ではない。
文法を変更したら新しい計画を作る。

準備時に決める候補は次のとおり。

- 連続する literal 照合をまとめる。
- 診断・trace が不要な区間からその処理を除く。
- 必要な rollback 境界と保存対象を決める。
- 確実に判別できる分岐だけ専用 dispatch へ落とす。
- custom parser を呼ぶ位置には通常の状態管理を残す。

これらは accepted input だけでなく、位置、診断、callback の観測契約も保てる範囲で行う。
`Expr` を関数に置き換えるだけでなく、文法と要求機能が確定したことで不要になった仕事を
取り除くことが目的である。#210 の direct-call 実験では memo・診断・checkpoint・CST 構築の
多くがそのまま残っていた。

初期段階から JIT は必要ない。固定文法には生成コード、動的文法には事前解析済みの実行計画を
検討する。命令列に変換しても解釈コストは残るので、速度改善は実測する。

## 提案4: 解析中に必要な状態と、解析後に作れる情報を分ける

scope が後続の構文の受理判定に影響するなら、解析中に更新し、失敗時に rollback する必要がある。
一方、完成した AST から宣言・参照を集め、未定義変数を報告するだけなら、成功後の一回の走査へ
移せる可能性がある。その部分を後ろへ移すと、失敗分岐で scope を更新して取り消す仕事が消える。

型付き AST と source span だけを要求する用途では、完全な CST を常に構築する必要があるかも
見直せる。ただし現在の mapper や custom parser が CST を読む場合は、その依存を解く実装が必要で、
フラグ追加だけでは済まない。

DAP に必要な位置情報と、parser の全試行履歴も別の要求である。
評価用 AST に位置を保持することと、解析過程を trace することは分けて指定できる。

## 提案5: 要求する機能に合う既存エンジンを選ぶ（#264）

この提案は設計であり、既存の `ParseContext`、生成API、既定エンジンを変更しない。
Classic の速度作業は [第6ラウンドで打ち止め](performance-tuning-ja.md#これで旧版コンビネータ-runtime-の速度作業は打ち止めにする)とする。
簡素な生成経路には既存の ubnfc を使い、Classic に同じ生成エンジンを追加することは既定案にしない。
[現在の選び方](engine-selection-guide-ja.md)と、将来の同一文法用 facade の自動選択を区別する。

### capability と選択規則

capability は文法revision・生成target・出力profileに属する。エンジン名だけから推測しない。
次の表は必要な証明と現状の境界であり、全文法の互換性宣言ではない。

| 要求 | Classic Java | Classic Rust | ubnfc Java / Rust を選べる条件 |
|---|---|---|---|
| typed AST と元ソース位置 | mapper と owned source map | typed AST と CP span | 同じ文法の独立oracleで全field・元text・CP/UTF-16位置が一致し、明示AST adapterがある |
| 完全なCST、virtual token、任意metadata | Token木とparser固有契約 | owned TreeはあるがJava virtual/metadata全互換は未完了 | 完全CSTを持たないので不適格。typed ASTや字句列で代用しない |
| 動的parser合成、transactional user state、capture/replay | ParseContext上の公開API | 公開ParseContext/combinator。生成replay等の未対応は対応表に残る | parserを純粋と仮定せず、要求した状態の同等性を実証できなければ不適格 |
| 詳細な失敗診断 | DETAILED / AUTOの既存契約 | Detailed / Autoの既存契約 | 主位置/expectedと全試行履歴は異なる要求。rich診断が必要なら安全な再解析または最初からClassic |
| trace / LSP / DAP | Java生成・protocol実装の対応範囲 | Classic Rustサーバーの実protocolは未対応 | 別engineのIDE生成をClassic Rust対応と数えず、要求したprotocolをその生成targetで検証する |
| incremental、memo方針の実行時選択 | 既存Java APIの対応範囲 | incremental未対応、SafeFailuresの既存範囲 | 全文再解析・静的memo選択との違いを要求として扱う |
| 未知のcustom parser / provider | 既存rich経路で宣言された契約を使う | 同左。Java任意classの自動翻訳はしない | 副作用・状態依存・再実行安全性が不明なら自動選択しない |

将来の facade の `auto` は、まず要求を列挙し、その全てを満たす検証済み候補だけを残す。
ASTだけを要求する固定文法で ubnfc と adapter の同値性が確認できれば、その生成経路を候補にする。
rich capability が必要なら Classic の対象言語へ進む。どの候補も満たさない要求は、理由付きで拒否する。
たとえば Rust incremental 要求を Classic Rust に切り替えただけで受理しない。
エンジンを明示した場合も同じcapability検査を行い、不足機能を黙って省かない。
選択理由、文法revision、target、profile、adapter revisionを結果に記録する案とする。

### 失敗時のrich診断

再解析するのは、lean経路が構文拒否した入力についてrich診断が要求され、両経路の同値性と
callback/providerの再実行安全性が事前に確認できる場合に限る。元の入力、文法revision、
snapshotを固定し、新しいcontextと別のmemo表を使う。再解析では、再実行可能と確認したcallbackだけを呼ぶ。
外部I/Oや再実行不可能なcallbackがあれば、最初からrich経路を選ぶ。

lean拒否/rich受理、成功ASTやspanの相違はengine mismatchとして報告し、rich成功へ置き換えない。
探索上限・期限・メモリ制限による終了も構文拒否と分け、別engineへの無制限retryにしない。
全体の期限と試行回数を共有し、rich診断を得られなければその理由を返す。
診断の表示形式や候補集合に意図的な差がある場合は、各targetの独立期待値と差分表を固定する。

### memo無しの安全性と試作の判断

「副作用が無い」と「memo無しで探索が安全」は別の性質である。
最初の証明対象は、非nullableな反復、FIRSTが互いに素な選択、入力を必ず進める再帰、
計算量と消費境界が既知のterminalだけを使う限定文法とする。
nullable循環、FIRSTの重なり、lookaheadによる重複探索、可変長tokenの再走査、
未知custom、scope/backreference等の状態依存は保守的に不適格とする。
再帰の進行証明だけで線形時間を保証せず、全規則の探索と字句処理の上限を別途示す。
証明できない文法は、memoを持つ既存生成経路またはClassicを選ぶ。資源上限はどの候補にも必要である。

既存ubnfcと明示adapterを使う隔離driverの試作判断は
[別issue #435](https://github.com/opaopa6969/unlaxer-parser/issues/435)で追跡する。
Rustから始める場合も、Java Classic / Java ubnfcと同じ入力・独立AST/span/失敗oracleを実行し、
Java側facadeの未実装範囲を明記する。片側の試作を共通APIの完成とは数えない。
生成/準備、認識、AST adapter、失敗診断を分けて測り、未測定の速度向上は約束しない。
結論は採用可能・不採用・証拠不足を記録できる形とし、既存生成経路で足りれば新engineを作らない。
この設計PRや追跡issueの作成は、凍結したClassic性能作業やprototype実装の開始を意味しない。

### Java / Rustで検証する観測

同じ文法・元入力・snapshotに対して、次を各実装の独立期待値へ照合する。

| fixture | 比較するもの |
|---|---|
| 正常入力、Unicode/CRLF、trailing input | 受理、消費/最大一致、AST全field、元text、CP spanとUTF-16 |
| choice失敗、先読み、nested rollback | committed CST/capture/scope/user state、敗者の情報が残らないこと |
| syntax failure、callback、資源上限 | 失敗種別、診断位置/expected、試行回数、callbackの再実行可否 |
| capability不足、未知custom、memo無し不適格 | Java/Rust共通の選択/拒否理由、未対応の明示 |
| 保持した結果と後続parse | owned snapshotが変わらず、古いsource mapを誤用しないこと |

現在の `AutoDiagnosticsTest` と Rustのdeferred-diagnostics試験は、既存診断policyの安全性を
確認する基準である。将来のengine切替、Java/Rust全CST互換、prototypeの速度を検証した証拠にはしない。

## 優先順位と検証

提案1〜4の当初の順序は `DetailedOnFailure` の小さな実験と、次の「文法の性質＋要求する機能」を扱う準備層だった。
診断policyの到達点は上の実装節を参照し、現在のengine選択の次の判断は提案5と #435 に従う。
以下の観測項目は将来の判断にも使うが、Classicの性能作業を再開する指示ではない。

- 従来の成功式に加え、不正入力と編集途中の入力を測る。
- 成功時の短縮と失敗時の再解析コストを分けて記録する。
- 同じ出力 profile で、受理結果・AST・source span・要求された診断・state を比較する。
- 診断を読む custom parser、listener、nested rollback など、従来方式へ戻す境界を検証する。
- Java/Rust 双方で共通 fixture と既存の実行方式との比較を行う。
- 実行計画を導入する段階では、準備時間と繰り返し parse の時間を別々に測る。

`ParseContext` の柔軟性には残す価値がある。利用する機能と文法の性質に合わせて、
不要な仕事を減らせる構成を目指す。この文書の保存は、これらの施策の実装開始を意味しない。

## 参照

- [パフォーマンスチューニング実践ノート](performance-tuning-ja.md)
- [性能改善の handoff #214](https://github.com/opaopa6969/unlaxer-parser/issues/214)
- [direct-rule-call 実験 #210](https://github.com/opaopa6969/unlaxer-parser/issues/210)
- [原子式 checkpoint 省略実験 #239](https://github.com/opaopa6969/unlaxer-parser/issues/239)
- [Rust の ParseContext / Expr 実装](../rust/unlaxer-runtime/src/lib.rs)
- [Java の ParseContext 実装](../unlaxer-common/src/main/java/org/unlaxer/context/ParseContext.java)
