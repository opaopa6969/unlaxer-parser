# tinyexpression の code fence token 契約

追跡: #170 / #316（親 #111 / #158、tinyexpression#232）。次の exact FQN を Java frontend と native Rust
frontend の両方から生成する。短名や別 package の同名 class は拒否する。

```ubnf
token CODE_START = org.unlaxer.tinyexpression.parser.javalang.CodeStartParser
token CODE_BODY = UNTIL('```')
token CODE_END = org.unlaxer.tinyexpression.parser.javalang.CodeEndParser
token LONG_CODE_BLOCK = org.unlaxer.tinyexpression.parser.javalang.LongCodeBlockParser
```

## 原子的な字句と行境界

開始 token は行頭の triple backtick、scheme、colon、dotted class identifier、行末を
連続して認識する。scheme は ASCII letter/underscore で始まり、続きは ASCII
alphanumeric/underscore。class identifier は同じ identifier を dot で連結したもの。
これは Java 言語仕様全体の identifier ではなく、実 tinyexpression parser の契約である。

終了 token は行頭の triple backtick と行末を認識する。行頭は入力先頭または直前の
codepoint が CR/LF の位置、行末は CRLF、CR、LF、EOF の順で判定する。改行は消費する。
token 内部の空白やコメントは許容しない。したがって通常の trivia 付き sequence へ
展開せず、原子的な字句として実装する。外側の `javaStyle` trivia は従来どおりで、
読み飛ばした後の位置に対して行頭条件を判定する。

生成 parser 以外でも、公開 `ParseContext` と同じ combinator を使える。

```rust
use unlaxer_runtime::{Expr, ParseContext};

let mut context = ParseContext::new("```rust:example.Block\nbody\n```");
context.parse(&Expr::code_start()).unwrap();
assert_eq!(context.remaining(), "body\n```");
```

終了側は `Expr::code_end()`。失敗した字句解析は両 cursor・capture・利用者状態を
既存の transaction 境界へ戻し、他の combinator や手書き parser と組み合わせられる。

位置は Unicode codepoint の半開区間であり、AST は CST と独立して所有する。
mapper は既存の text capture 契約（Java `String.strip()` 相当の空白除去と外側の単引用符
除去）を使うため、AST の文字列値と raw source span は区別する。NUL は strip 対象では
なく保持する。全 CST の構造同値や Java の scheme/codeIdentifier tag の移植完了は
主張しない。これらの metadata は full-spec 対応表で引き続き追跡する。

## 長い fence（#316）

従来の `CodeStart` / `CodeEnd` はそのまま残す。`LongCodeBlock` は N >= 4 個の backtick、
`scheme:qualified.Name`、改行、opaque な本文、同じ N 個だけの終端行を一つの token として読む。
開始・終端は行頭、前後に空白なし。LF / CRLF / CR、終端後の EOF に対応する。
短い/長い fence、行中の backtick、終端らしい行の末尾空白は本文に残る。
同じ長さの単独行は host string/comment の内部でも終端になるので、本文中の単独行より長い N を選ぶ。

Java frontend と Java 非依存の native Rust frontend は同じ `LongCodeBlockToken` IR へ写像し、
`Expr::LongCodeBlock` を生成する。動的 combinator から `Expr::long_code_block()` でも利用できる。
失敗は token 開始位置で `long code block` を報告し、両 cursor、capture、user state を戻す。
Java/Rust scanner の内部 offset はそれぞれ UTF-16 / UTF-8 だが、公開 span は code point。
本文・fence 幅を保持する TinyExpression AST/AOT の契約は
[TinyExpression CodeBlock](https://github.com/opaopa6969/tinyexpression/blob/master/docs/code-block-source-contract.md) に置く。

## 実クラスを使う検証

`TinyCodeFenceConformanceTest` は tinyexpression commit
`7d7bd1cbfab1bd9548ba53c73cc50502f0437c67` の実クラスを oracle にする。
ロードした3 class の CodeSource が指定した `target/classes` であることも検査する。
共通 corpus は14文法・86入力（受理40、拒否46）。受理/全入力判定、consumed/matched
cursor、AST 全フィールドと node span を独立期待値と照合する。実 P4 と同じ zero-field
mapping・`javaStyle` の構成と、非ゼロ位置の mapped child node も含む。
Java/native Rust の全5生成ファイル（計70ファイル）を比較し、生成 Rust を
実際にコンパイル・実行する。native 生成は空の PATH でも動作し、Java を起動しない。

```sh
mvn -B -pl unlaxer-dsl test -Dtest=TinyCodeFenceConformanceTest \
  -DrustConformance=true -Dtinyexpression.classes=/absolute/path/to/tinyexpression/target/classes
```

外部 checkout がない通常のローカル test では skip する。CI downstream job では固定
checkout をビルドして実行し、`target/rust-tiny-code-fence.tsv` の存在と artifact 保存を
必須にする。通常 test の成功だけを、実 oracle との互換性の証拠にはしない。

## 実行機能とは別の層

この機能は code fence と body の構文認識だけである。scheme が `java` や `rust` でも
parse/generate はコードをコンパイル・実行しない。明示許可付きの本文 AOT、コンパイラ診断写像、
型付き host binding は [TinyExpression の専用 API/CLI](https://github.com/opaopa6969/tinyexpression/blob/master/docs/rust-codeblock-aot.md)
で実装されている。ここでの token 対応がそれらを実行するわけではない。
Java ソースの Rust への自動翻訳も行わない。

実文法の `UNTIL('```')` は読み始めた位置以降、最初に現れた triple backtick で止まる。
ただし `javaStyle` の外側 trivia は `UNTIL` の呼び出し前にも作用し、body 先頭の空白や
コメントを読み飛ばす。そこを通過した後の行途中に triple backtick があれば、後続の
正しい閉じ行を探し直さず、行頭条件により失敗する。
閉じ fence が欠ける場合も `CodeEnd` が失敗する。Markdown の汎用 fenced code block
scanner や、埋め込み言語の文字列/commentを解釈する scanner ではない。

汎用 token ID/schema/登録 API を含む #158、残る annotation/evaluator、
tinyexpression-rs 全体、LSP/DAP は引き続き未完了である。

以下は #170 時点の歴史的な制約であり、#316 の現在の完了根拠ではない。その時点で固定した P4 文法全体を native generator に渡すと、token 宣言の検証後に
`unsupported annotation Interleave { profile: "javaStyle" } on Formula` と終了コード3で
停止する。未対応 annotation を捨てて「生成成功」とはしない。実 tinyexpression の
バイナリが完成したことを意味しない。
