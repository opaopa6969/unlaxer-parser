# Simple annotation（#715）

契約は [docs/simple-annotations.md](../../docs/simple-annotations.md)。

- `corpus.json`: 受理する 15 文法。`grammar` は Java・Java-host Rust・native Rust のすべてで生成でき、
  生成物と移植可能性検査は `baseline`（注釈を除いた文法）と同じ。`warnings` は `W-ANNOTATION-IGNORED` の
  rule・code・subject・code point span・suggestion・message。
- `rejected.json`: 両 frontend が構文エラーにする 4 文法（移植可能性検査は `P-SYNTAX`）。

読む側: `unlaxer-dsl/src/test/java/org/unlaxer/dsl/SimpleAnnotationConformanceTest.java`、
`rust/unlaxer-generator/tests/simple_annotations.rs`。
