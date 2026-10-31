# Simple annotation（#715）

契約は [docs/simple-annotations.md](../../docs/simple-annotations.md)。

- `corpus.json`: 受理する 16 文法。`grammar` は Java・Java-host Rust・native Rust のすべてで生成でき、
  生成物と移植可能性検査は `baseline` と同じ。`baseline` は注釈を除いた文法で、`@autoCommit` と同じ rule に
  併記した `with-auto-commit` だけは注釈と `@autoCommit` の両方を除いた文法（注釈だけを除いた `contrast` とは
  生成物が異なる）。`warnings` は `W-ANNOTATION-IGNORED` の
  rule・code・subject・code point span・suggestion・message。
- `rejected.json`: 両 frontend が構文エラーにする 5 文法（移植可能性検査は `P-SYNTAX`）。

読む側: `unlaxer-dsl/src/test/java/org/unlaxer/dsl/SimpleAnnotationConformanceTest.java`、
`rust/unlaxer-generator/tests/simple_annotations.rs`。
