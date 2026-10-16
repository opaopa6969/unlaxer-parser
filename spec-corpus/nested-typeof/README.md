# Nested typeof oracle

`generated-cases.json` は手書きの意味型・値・CP・失敗期待値。製品の出力から snapshot を採録しない。外側 container identity と scalar underlying identity を区別する。

`NestedTypeofConformanceTest` は Java21、Rust1.85 で Java と両 Rust 出力を独立にコンパイルし、各入力を memo 2 × diagnostics 3 通りで確認する。Rust 5 file byte 比較、manual evaluator の型付き引数、preferred と source-map snapshot 保持も確認する。private capture は公開 AST field に現れない。API schema は Java/native host の JSON も比較する。

不正 schema の早期拒否は `invalid-cases.json` と Rust direct-IR `nested_shapes` で検証する。既存 flat oracle は `spec-corpus/typeof` を維持する。alias/helper、auto-eval、associative、token-stream の新合成はこの corpus の対象外。
