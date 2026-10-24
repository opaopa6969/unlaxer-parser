# 生成の警告（#715）

契約は [docs/simple-annotations.md](../../docs/simple-annotations.md#生成の警告を-cli-まで届ける)。

`corpus.json` の各行は、生成物を変えない警告（`W-ANNOTATION-IGNORED`・`W-AUTO-COMMIT-SKIPPED`・`W-COMMENT-IGNORED`）の
code・grammar・rule・code point span・message と、Rust 生成 CLI が stderr に出す 1 行（`line`）を持つ。
`--strict` / `--fail-on warning` では、同じ行に `strict: <件数> warning(s) fail generation` を足して
終了コード 5 で失敗し、何も書かない。

#777 のコメント警告は単独・他の警告との混在を含む。
`ordering.json` は複数 grammar の収集順序を両ライブラリで検証する。Rust 生成 CLI は
複数 grammar を拒否する既存の制限（#714）があるため、CLI の corpus とは分ける。
`../global-comment/ignored.json` の全8例も `generationWarnings` を期待値にして読む。
message と span は契約と文法の文字列から独立に作り、補助文字を含む CP 位置を固定する。

読む側: `unlaxer-dsl/src/test/java/org/unlaxer/dsl/GenerationWarningsConformanceTest.java`、
`rust/unlaxer-generator/tests/generation_warnings.rs`。
