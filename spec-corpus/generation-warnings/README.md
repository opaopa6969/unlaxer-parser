# 生成の警告（#715）

契約は [docs/simple-annotations.md](../../docs/simple-annotations.md#生成の警告を-cli-まで届ける)。

`corpus.json` の各行は、生成物を変えない警告（`W-ANNOTATION-IGNORED`・`W-AUTO-COMMIT-SKIPPED`）の
code・grammar・rule・code point span・message と、Rust 生成 CLI が stderr に出す 1 行（`line`）を持つ。
`--strict` / `--fail-on warning` では、同じ行に `strict: <件数> warning(s) fail generation` を足して
終了コード 5 で失敗し、何も書かない。

読む側: `unlaxer-dsl/src/test/java/org/unlaxer/dsl/GenerationWarningsConformanceTest.java`、
`rust/unlaxer-generator/tests/generation_warnings.rs`。
