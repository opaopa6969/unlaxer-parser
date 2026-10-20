# UBNF 空 literal `''` — Java / Rust 共通契約

Issue: [#713](https://github.com/opaopa6969/unlaxer-parser/issues/713)（親 #111）

Java の生成 parser は rule 本体の `''` を生成・compile できるが、Rust 移植検査は
`P-EMPTY-LITERAL` で拒否していた。両者を「狭い方」に揃えて Java を拒否するのではなく、
Java の既存の意味を実測し、両 Rust frontend（Java host / native）・移植検査・lowering・
runtime・生成コードをその意味に合わせた。公開済み 3.3.0 は変更しない。

## Java の二つの意味

Java の生成器は grammar の字句方式によって `''` を別の parser にする。

| grammar | Java 生成 parser | 意味 |
|---|---|---|
| 通常（`@tokenStream` も `@lexicalContext` もない） | `WordParser("")` | **常に失敗**。長さ0の source は `isPresent()` が偽なので、反転（invert）・matchOnly・memo の有無に関係なく成功しない。入力を消費せず、現在位置に期待候補 `''` を記録する。失敗した consuming leaf として `consume(0)` を呼ぶため、`[ ]` / `{ }` 直下では matched cursor が consumed cursor へ戻る |
| `@tokenStream: enabled` または `@lexicalContext` を使う | `Lexing.LiteralParser("")` | **幅0で成功**。lexing session・lexical goal の有無に関係なく成功する。Classic で反転したときだけ失敗し、長さ0なので `consume` を呼ばない |

宣言的 token（`token T ::= ''`）の `''` は字句式であり、どちらの grammar でも幅0で成功する。
これは従来から両 host で対応済みで、本変更の対象外である。

## Rust の表現と理由

| Java | Rust IR（`unlaxer_codegen::ir::Expression` / Java `GrammarIR`） | 生成 runtime 式 |
|---|---|---|
| `WordParser("")` | `EmptyWordToken` | `Expr::JavaEmptyWord` |
| `Lexing.LiteralParser("")` | `Literal("")` | `Expr::JavaEmptyLiteral` |

- 手書きの `Expr::literal("")` / `Expr::Literal("")` は PEG の空 literal として**常に成功する既存契約を保つ**。
  生成コードはこれを使わない。Classic token mode での `Literal("")` の既存挙動（WordParser と同じく失敗）も変えない。
- `JavaEmptyWord` は native では `""` を期待候補として現在の consumed 位置で失敗する（Rust の literal 失敗と同じ位置規則）。
  Classic では反転を含め常に失敗し、失敗した直接 consuming leaf として Java と同じ matched cursor の巻き戻しを受ける。
  FIRST 集合は空（必ず失敗）、memo・deferred diagnostics は他の終端と同じく安全。
- `JavaEmptyLiteral` は native では幅0で成功する（`Literal("")` と同じ）。Classic では非反転なら幅0の token を追加して成功し、
  反転なら失敗する。どちらの場合も cursor を動かさず、失敗後の matched cursor の巻き戻しもしない。FIRST 集合は nullable。
- 新しい runtime 式を二つにしたのは、native / Classic のどちらかで既存の `Literal("")` や `Error` と意味が異なり、
  既存式を流用すると手書き API の契約か Java 互換のどちらかを壊すためである。`Error("")` は期待候補を記録しない点で
  `WordParser("")` と異なる。
- 構造上は非空 literal と同じ位置に置く。直接の `[ '' ]` は literal と同じく helper を作らず、繰り返し要素の `''` は
  Java の DelimitedChain と同じ trivia 境界で包む。`@leftAssoc` / `@rightAssoc` の非意味 delimiter としても扱う。
- nullable 判定は `EmptyWordToken` を非 nullable（成功しない）、`Literal("")` を nullable とする。両 host で同じ規則であり、
  生成物の byte 一致に影響する。予測子（`@predictiveChoice`）は既定どおり `Any` で、期待候補 `''` が失われない。
- opt-in の Rust LSP keyword には空文字列を入れない。

`P-EMPTY-LITERAL` はもう発行しない。code 名は別の意味に転用しない（[移植可能性チェック](portability-check.md)）。
`@enum` の `''` は従来どおり両 host で `E-ENUM`（定数名にできない）として拒否する。

## 共通 oracle と検証

[`spec-corpus/empty-literal`](../spec-corpus/empty-literal/) は 13 文法 53 入力。root / sequence / choice の rollback /
field capture の rollback / optional・repeat（capture 付き）/ `''+`・`''{0,3}`・`'' % ','`・`{ [ '' ] }` の進捗停止 /
`javaStyle` trivia / 先読み後の matched cursor / 宣言的 token の `''`・`LOOKAHEAD('')`・`NEGATIVE_LOOKAHEAD('')` /
tokenStream / lexicalContext / 左結合の prefix・tail delimiter を含み、非 BMP 文字・EOF・空入力の code point 位置を確認する。

各入力について次を記録する。

- `prefix`: 生成 parser の直接 parse の受理と consumed / matched cursor（memo OFF / SAFE_FAILURES で同一）
- `classic`: Classic token mode の consumed / matchOnly × 非反転 / 反転の 4 通り（memo 2 通りで同一）
- `diagnostic`: 全入力診断（memo 2 × Auto / Detailed / DetailedOnFailure の 6 通りで同一）
- `ast`: 受理時の AST 全 field と code point span
- `cst`: 受理時の rule 単位 CST（rule 名と code point 範囲。helper・終端は透過）

`EmptyLiteralConformanceTest` は、素の `mvn test` でも Java 生成 parser（`javac` で compile）を oracle と照合する。
`-DrustConformance=true` では、Java host と native host の Rust 5 ファイルの byte 一致を確認したうえで、
**それぞれを別 binary として `rustc` で compile・実行**し、両方を同じ oracle と照合する。byte 一致は実行の代わりにしない。
native generator は空 `PATH` で起動する。

### Java 側の既存表示差

`diagnostic` は Rust 側の期待値であり、Java の既存表示が異なる入力だけ `javaDiagnostic` を記録する（受理・kind・primary offset は全入力で共通）。
空 literal の期待候補は Rust では `""`、Java では `''` と表示する。ほかの差は非空 literal に置き換えた対照文法でも同じく現れる既存差で、
空 literal 固有ではない。

- Java は literal を引用符付きで表示し、`javaStyle` trivia の補助候補、同じ chain の後続要素、`RootParser` などの class 名、
  幅0で成功した token 名を候補に加え、trailing input の farthest 候補が異なる。
- 先読み（`LOOKAHEAD`）で matched cursor が進んだ後の失敗を、Java は matched 位置、Rust は consumed 位置で farthest に記録する
  （MatchedCursor。非空 `'z'` でも同じ差）。
- choice を root とする rule で選ばれた枝が capture helper の場合、Java の token 木に root rule の token が残らない
  （CaptureRollback の `javaCst`。非空 literal でも同じ差）。

既知の範囲外: token-stream / lexical-context grammar の Classic token mode で、**非空** literal を反転させたときの意味
（Java の `LiteralParser` は幅0成功、Rust Classic は `WordParser` と同じく1文字単位）は本変更以前からの差であり、ここでは扱わない。
corpus の token-stream 例はこの差に交絡しないよう、`;` を宣言的 token にしている。

```sh
RUSTUP_TOOLCHAIN=1.85.0 mvn -pl unlaxer-common,unlaxer-dsl -am test \
  -Dtest=EmptyLiteralConformanceTest -DrustConformance=true \
  -Dsurefire.failIfNoSpecifiedTests=false
```

結果は `unlaxer-dsl/target/empty-literal-java.tsv` と `unlaxer-dsl/target/rust-empty-literal.tsv` に残る。
