# UBNF 空 literal `''` 共通コーパス（#713）

契約は [docs/empty-literal.md](../../docs/empty-literal.md)。通常 grammar の `''` は Java `WordParser("")` として
常に失敗し、`@tokenStream` / `@lexicalContext` grammar の `''` は Java `Lexing.LiteralParser("")` として幅0で成功する。

`corpus.json` は 30 文法 196 入力の独立期待値である。各入力の `prefix`（直接 parse の受理と consumed / matched cursor）、
`classic`（consumed / matchOnly × 非反転 / 反転）、`diagnostic`（全入力診断）、`ruleStack`（失敗時と入力が残ったときの rule stack）、`ast`（全 field と code point span）、
`cst`（commit 済み rule 単位 CST）を Java 生成 parser と、Java host・native host が生成した Rust をそれぞれ compile した binary で比較する。
memo OFF / SAFE_FAILURES と Auto / Detailed / DetailedOnFailure の組み合わせで値が変わらないことも各 host で確認する。

`diagnostic` は Rust 側の期待値で、Java の既存表示が異なる入力だけ `javaDiagnostic` に全文を記録する。
CST に host 別期待値は認めない（#767）。Java は返却 `Parsed` の選択枝ではなく context の commit 済み root を比較する。
CaptureRollback / Labeled / AutoCommit の成功例は `cstModes`（通常 / Classic consumed / Classic matchOnly、非反転）も共通期待値で固定する。
同じ harness は [root-choice-cst](../root-choice-cst/) の対照文法も実行する。
期待候補を削る・並べ替えるなどの正規化はしない。差の内訳と、非空 literal の対照でも同じ差が出ることは契約文書に記載した。
期待値は Java 生成 parser と両 Rust host の実行結果が一致した値を採録したうえで、各値を空 literal の意味（失敗位置、
rollback、Occurs の matched cursor、反復停止、幅0成功）に照らして個別に確認した。以後はどの host の変更もこの値と照合する。

```sh
RUSTUP_TOOLCHAIN=1.85.0 mvn -pl unlaxer-common,unlaxer-dsl -am test \
  -Dtest=EmptyLiteralConformanceTest -DrustConformance=true \
  -Dsurefire.failIfNoSpecifiedTests=false
```

#768 の追加8文法・104入力は、非空 lexical literal の Classic 反転を固定する。
`classicStart` は `[consumed, matched]`（省略時 `[0,0]`）で、Classic parse の前に wildcard の
consumed / matchOnly を実行して cursor を設定する。`classicCst` / `classicFailure` は `classic` と
同じ4モード順で、rule CST と失敗時の CP 位置（成功なら null）を記録する。これらは追加ケースで必須とし、
Java / Rust 別の oracle で反転の差を緩和しない。通常 literal の対照、EOF、非 BMP、短い入力、
lexical goal の最長一致、goal の外の rule、optional / repeat / sequence を含む。
