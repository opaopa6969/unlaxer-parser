# spec-corpus/ubnf — 出所と扱い

このディレクトリの `*.json` は **`ubnfc` リポジトリが原本**で、ここにあるのは複製である。

| 項目 | 値 |
|---|---|
| 原本 | `opaopa6969/ubnfc` の `corpus/spec/ubnf/*.json` |
| 複製元 commit | `5a33c82b8c5237c6778e65ea19fa7f249b773ca5`（2026-09-24） |
| 原本のローダ | `ubnfc/crates/ubnfc-harness/src/corpus/spec.rs` / `tests/spec_corpus.rs` |
| 4 実装クロスチェック | `ubnfc/examples/ubnf-crosscheck/`、報告は `ubnfc/docs/reports/2026-09-24-spec-derived-oracle.md` |

## なぜここにも置くのか

ケースの期待値は `unlaxer-dsl/specs/{ubnf-syntax,annotations,validation}.md` の**規範文だけ**から
導かれている（どの実装の観測値も使っていない）。引用元の仕様書は **この** リポジトリにあるので、
仕様書を編集したときに壊れるべきなのは **この** リポジトリのテストである。sibling repository の
`ubnfc` 側が後から気づく形になっていると、3.1.0 のような release の前に検出できない。

そのため:

- `UbnfSpecCorpusTest`（Java）と `rust/unlaxer-ubnf/tests/spec_corpus.rs`（Rust）が
  **同じ JSON・同じ期待値**を読み、Java bootstrap（`UBNFParsers` / `UBNFMapper` /
  `GrammarValidator`）と Rust frontend（`unlaxer-ubnf`）の両方を駆動する（AGENTS.md の対称性）。
- 両テストとも **引用の実在検査**を行う（`citation.heading` が仕様書に見出し行として実在し、
  `citation.sentence` が空白の畳み込みだけを許して逐語で含まれること）。
  仕様書を書き換えたらこちらのテストが落ちる。

## 更新の向き

**原本は `ubnfc` 側**。ケースの追加・修正は `ubnfc/corpus/spec/ubnf/` に対して行い、
ここへは複製し直す（この表の commit を更新する）。逆向きに編集しないこと。

## スキーマ

`ubnfc/corpus/spec/README.md` を参照。要点だけ:

- `scope`: `parse`（受理・拒否・構文木）/ `validate`（診断コード）/ `runtime`（生成パーサの実行時挙動）
- `expectation.verdict`: `accept` / `reject` / `diagnostic`(+`code`) / `undetermined`(+`question`)
- `expectation.canonicalContains` / `sameCanonicalAs`: `accept` のときだけ、正準 AST JSON への期待
- `citation`: `file`（リポジトリルート相対）/ `heading`（見出し行そのもの）/ `sentence`（逐語）
