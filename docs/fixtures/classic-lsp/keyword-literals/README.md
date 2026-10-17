# Completion literal の保存 (#558)

`Keywords.ubnf` の literal を復号した独立期待値が `labels.json`。
LF、CR、CRLF、tab、double quote、backslash、両端の single quote、補助平面文字、
ZWJ family、結合文字列、文字通りの `\n` / `\u000a` を含む 12 ケース。

`TerminalElement.value()` は既に復号された値なので、completion 用の収集時に
引用符を再び削除してはいけない。Java ソースに書く段階で
`ParserCodegenUtil.escapeString` を使い、文字の意味は変更しない。
Rust の両 generator host は既存の Rust literal escape を使用する。

`ClassicLspKeywordConformanceTest` は実際の Java / Rust stdio サーバーを生成・compile
して、各 literal が正常に parse されること、Content-Length フレームによる completion
応答の label が全 12 期待値を完全一致で含むこと、全候補集合が両版で一致することを確認。
Java-host / native Rust-host の Rust 生成物も全ファイルで byte 一致を確認する。

```sh
RUSTUP_TOOLCHAIN=1.85.0 mvn -pl unlaxer-common,unlaxer-dsl -am test \
  -Dtest=ClassicLspKeywordConformanceTest \
  -Dsurefire.failIfNoSpecifiedTests=false -DrustConformance=true
```

素の Maven 実行では Java 側を実行し、Rust cross-check は上記 opt-in が必要。
