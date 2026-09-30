# scope なし `@backref` の capture equality 契約

`corpus.json` は Java と Rust の生成 parser に共通の独立期待値を置く。
文法全体に `@scopeTree` がない場合、`@backref(name=...)` はその rule 本体で
同名 capture が完了した順に最初の値と後続値を比較する。比較する値は Java
`String.trim()` 相当で前後の U+0020 以下を取り除いた文字列で、空文字も有効。
不一致は構文の成否を変えず、各後続 capture の trim 後の Unicode スカラ範囲に
`ERROR` 診断を記録する。失敗分岐や親 transaction は診断を戻す。

`@scopeTree` が文法にある場合は従来の参照・宣言の規則を使う。
テストは prefix cursor、全入力構文受理、AST 全 field と node span、
semantic diagnostic・宣言・参照・rollback state を同一入力で比較する。
Java と Rust の UBNF frontend が出力する Rust 5 ファイルは byte 単位でも照合する。

15文法・24入力には、別名同parser、同名別parser、alias/group/nested/repeat、
0/1出現、空capture、非0位置のEMPTY、trim、CRLF、補助文字、callee境界、
`@skip`、ASTなしroot、親/choice rollback、従来のscope参照を含む。

`Auto` / `Detailed` / `DetailedOnFailure` × memo `Off` / `SafeFailures` の6組で、
低水準入口の両cursorと意味状態を比較する。生成された全入力入口では、Javaの
`diagnose(input, options)`の構文結果、Rustのtreeと意味状態も確認する。
Javaの`diagnose`は意味状態を返さないため、そのAPIからの意味診断取得は検証対象としない。
Rust公開`ahead()` / `not_ahead()`のrollbackはruntimeの`capture_equality.rs`で別途検証する。

再現（JDK 21とRust 1.85が必要）:

```sh
RUSTUP_TOOLCHAIN=1.85.0 mvn -pl unlaxer-common,unlaxer-dsl -am test \
  -Dtest=CaptureEqualityConformanceTest -DrustConformance=true \
  -Dsurefire.failIfNoSpecifiedTests=false
```

`unlaxer-dsl/target/rust-capture-equality.tsv`に結果を保存する。
CIはこのファイルの存在・非空を確認し、`rust-conformance` artifactに収録する。
