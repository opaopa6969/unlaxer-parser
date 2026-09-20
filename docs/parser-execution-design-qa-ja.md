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

## 優先順位と検証

最初は `DetailedOnFailure` の小さな実験、次に「文法の性質＋要求する機能」を扱う準備層を検討する。
全体のアーキテクチャを作り込む前に、詳細診断を成功経路から外す効果を測る。

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
