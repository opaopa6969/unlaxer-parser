# Semantic cardinality conformance (#160)

`Grammar.ubnf.txt` と `corpus.json` から各文法を構築する。capture の syntactic wrapper
だけではなく、参照先 helper 内部の semantic 値の個数を One / Optional / Many とする。
Node と mixed Text/Node の pair、group、alias、optional、repeat、bounded repeat、
separated、optional-of-many、および反復 item 内の named/inline delimiter を検証する。
separator や structural literal は semantic 値には数えない。

成功ケースの `value` は順序を保持する独立評価 oracle（`L:`=Leaf、`T:`=Text）。
`texts` は `[start, end, text]` の Unicode code-point span oracle。
一部の `nodes` は同値の Leaf が複数出現した場合も各 occurrence の span を区別する
独立 oracle であり、record.equals による source map 上書きを検出する。
全 AST field / node span / prefix consumed+matched cursor は Java と Rust で比較する。

Java の One は scalar、Optional は `Optional`、Many は `List`。
Rust は生成 evaluator の引数型を実コンパイルして `&Ast` / `&AstValue`、
`Option<&...>`、`&[...]` を検証する。CST・context・input を破棄してから評価する。
各文法は native Rust CLI と Java frontend の Rust backend の全5生成ファイルも比較する。

`RustConformanceTest` の以下の入口から実行する。

- `semanticCardinalityJavaOracle`: 常時 Java 実生成 javac/runtime。
- `semanticCardinalityRustOracle`: `-DrustConformance=true` で Rust 単体の独立 oracle。
- `semanticCardinalityJavaAndRustAgree`: 同フラグで Java/Rust 比較。

基準 `5a673e1` では Java node-pair の field が List でなく Object となり、
Rust helperMany 内の `(a)` が `a` へ縮むため、対応する検査が失敗する。
これらを例外扱い・skip せず、修正後の共通成功条件として扱う。
