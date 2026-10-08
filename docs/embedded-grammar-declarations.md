# UBNF の埋め込み文法宣言

`@embedded` は、生成パーサーの確定した CST から本文 capture を取り出し、別の文法の任意 entry を呼び出す宣言です。Java / Java RustBackend / Rust native generator が同じ宣言を扱います。既存の global setting 構文を使い、宣言がない文法の生成 API は変わりません。

```ubnf
grammar TinyExpression {
  @embedded: {
    rule: 'JavaSource' body: 'body'
    language: 'java' package: 'example' version: '1'
    grammar: 'Java' entry: 'CompilationUnit'
  }
  token BODY = UNTIL(']T')
  @root @mapping(Expression) Expression ::= JavaSource;
  @mapping(JavaSource, params=[body]) JavaSource ::= 'T[' BODY @body ']T';
}
```

`rule` は親の境界ルール、`body` はそのルールの直接の scalar capture です。`full` はルール全体、`body` は capture の範囲となり、区切り文字の所有者は親領域です。文字列の trim、quote 除去、エスケープ展開は行いません。本文は同じ原文の正確な切片として渡します。

子文法を宣言しない終端文法は `@embedding: enabled` で provider API の生成だけを有効にします。`entry` は任意の宣言済みルールであり、`@root` や `Block` に限定しません。共通 fixture の Java 文法は root が Block ですが、埋め込みでは package / import / class を含む CompilationUnit を解析します。

## 生成 API と呼出し

Java は `XxxParsers.embeddedGrammar()`、Rust は `parser::embedded_grammar()` を生成します。それぞれ common `CstGrammar` / runtime `embedded::CstGrammar` を返します。

Java の呼出し例（3 つの生成パーサーを compile した後）:

```java
var root = new LanguageRegions.Language("formula", "example", "1", "FormulaInfo", "Document");
var tiny = new LanguageRegions.Language("tiny", "example", "1", "TinyExpression", "Expression");
var java = new LanguageRegions.Language("java", "example", "1", "Java", "CompilationUnit");
var providers = Map.<LanguageRegions.Language, EmbeddedLanguages.Grammar>of(
    root, FormulaInfoParsers.embeddedGrammar(),
    tiny, TinyExpressionParsers.embeddedGrammar(),
    java, JavaParsers.embeddedGrammar());
var result = EmbeddedLanguages.parse(new DocumentSnapshot("file:///formula", 7, source),
    root, providers, 8, 32);
var regions = result.tree();
```

Rust は同じ identity をキーにした `HashMap<Language, &dyn embedded::Grammar>` を `embedded::parse(&snapshot, &root, &providers, 8, 32)` に渡します。provider 登録は呼出し側が明示的に行います。`package` は language identity の構成要素であり、UBNF token vocabulary import の依存パッケージを自動取得する指定ではありません。

`Result.regions` / `Output.regions` には root を含む全領域が深さ優先で入ります。ID は `root`、`root/0`、`root/0/0` のように、同一 snapshot と同一 CST で決定的です。永続 symbol identity としては使いません。各 child の仮想 URI は host URI とこの ID から生成し、version は host と同じです。`full` / `body` はすべて host の Unicode code-point 半開区間、source map の output は各子の virtual snapshot です。

生成した region tree を既存の `LanguageQueries` に渡せます。completion / hover / definition provider の登録は別に行います。解析だけで query capability があるとは扱いません。

## 状態と制約

- 文法 identity は language / package / version / grammar / entry 全体で registry を照合します。未登録は `UNAVAILABLE`、存在しない entry は `UNSUPPORTED` です。登録 provider の grammar 名や応答 snapshot が異なる場合は拒否します。
- strict full-input 解析の失敗は `FAILED` です。回復 CST は `PARTIAL` となり、正常 AST と扱いません。子の失敗や未登録で、親が消費済みの境界や正常な sibling は消えません。親自身の strict 解析が失敗したときは、未確定の capture から child を推測しません。
- 同一ルールに複数宣言、未知ルール、未知/重複フィールド、空 identity、capture 不在、choice ルール、collection / optional capture、境界ルール自身の `@recovery` は `E-EMBEDDING` で拒否します。一般の mapping の制約は引き続き適用されます。
- grammar call の深さと総領域数に明示的な上限があります。上限超過は全結果を拒否し、不完全な木を成功として返しません。再帰的文法自体は parser runtime が扱い、文法内の backtracking を phase DAG に変換しません。
- この段階は bounded source slice の呼出しです。共有 cursor を使う無境界 grammar call、外部 process/provider 自動起動、EOF synthetic 修復、変換本文の wrapper 生成は含みません。既存 `SegmentSourceMap` の変換・生成 segment 機能と組み合わせる adapter は別層です。親 issue #369 全体をこの段階だけで完了とは扱いません。

## 共通の検証

`docs/fixtures/embedded-grammars/` の 3 つの UBNF 文法を実生成・compile します。`cases.tsv` は正常 3 層、失敗した子、後続 sibling、未登録 provider、host 失敗の独立期待値を保持し、絵文字を含む CP 範囲と delimiter 所有を Java / Rust で検証します。`invalid.tsv` は宣言の受理/拒否を比較します。Java RustBackend と native generator の生成ファイルは byte 単位で比較し、同一 Rust probe を実行します。追加で stale response、identity 不一致、重複 sibling、予算超過、PARTIAL、未知 entry を検証します。

```sh
mvn -pl unlaxer-common,unlaxer-dsl -am test -Dtest=EmbeddedLanguagesTest,EmbeddedGrammarConformanceTest -DrustConformance=true -Dsurefire.failIfNoSpecifiedTests=false
cargo test --manifest-path rust/Cargo.toml -p unlaxer-runtime --test embedded
cargo test --manifest-path rust/Cargo.toml -p unlaxer-generator --test embedded
```

## Partial editor regions

Generated parsers provide an opt-in editor registry:

```java
var formula = FormulaInfoParsers.embeddedEditorGrammar(
    List.of("}F"), org.unlaxer.editor.EditorCst.Options.defaults());
var tiny = TinyExpressionParsers.embeddedEditorGrammar(
    List.of("]T"), org.unlaxer.editor.EditorCst.Options.defaults());
```

```rust
let formula = formula::parser::embedded_editor_grammar(
    vec!["}F".into()], unlaxer_runtime::editor_cst::Options::default());
```

Use these adapters in the same `EmbeddedLanguages.parse` / `embedded::parse`
registry as strict child grammars or external providers. Strict parsing is attempted
first; EOF repair runs only after failure and remains bounded by the supplied
fragment/attempt limits. Completion fragments are explicit caller configuration;
the adapter does not invent a universal list of missing delimiters.

A repaired grammar reports PARTIAL. Discovered child full/body spans are clipped
original-source CP ranges, and child inputs contain only original text. An opening
must have original text before its body; a capture containing inserted body text
is not exposed as a child region. Empty original bodies are allowed when the opening
exists. A missing child provider stays UNAVAILABLE, and a syntactically invalid child
does not prevent a healthy sibling from being parsed. EOF repair does not repair an
invalid middle of the document or change the strict grammar's accepted language.

Rebuild the region tree from each immutable document snapshot. Region IDs express
current tree paths; a moved block may acquire another path, and the ID alone does
not establish snapshot identity. The shared `editor.tsv` corpus runs 23 edit states,
including parent/child delimiter removal and restoration, block insertion, movement
and deletion, empty inputs, synthetic-body refusal, budgets and provider absence.
Every old tree is rejected against the next project snapshot. Body source maps remain
exact COPY maps of original text; no missing closing delimiter enters an edit.

`Result.canonicalJson()` / `Output::canonical_json()` provides the same region
view in Java and Rust, with the snapshot version represented as a decimal string so
JavaScript cannot round a 64-bit version. Grammar/entry/state/full/body and parent IDs
are explicit; this representation does not claim semantic capabilities.

### Generated Playground integration

Generated projects include `src/region_adapter.rs`, defaulting to no registered
languages. Register known grammars explicitly in this hook. The working adapter at
[`fixtures/embedded-grammars/region_adapter.rs`](fixtures/embedded-grammars/region_adapter.rs)
uses the generated FormulaInfo → TinyExpression → Java registry, the same partial
mode and the same original-source ownership rules as the Java host. Its Java grammar
is the portable parser fixture; it does not pretend to run javac in a browser.

To reproduce the fixture project, generate a Playground from
`FormulaInfoPlayground.ubnf`, generate `TinyExpression.ubnf` and `Java.ubnf` into
`src/tiny` and `src/java`, and copy that adapter to `src/region_adapter.rs`. The
Playground root uses the declarative equivalent of UNTIL because browser generation
requires declarative tokens; its independent expected editor positions are the same.

Editor mode passes a request version through `pg_editor_snapshot(cursor, version)`.
The older `pg_editor(cursor)` remains supported with version zero. The language panel
shows complete, partial, failed, unavailable, unsupported and timed-out states, and
selects each body's original text. Selection refuses changed input; the Worker also
discards outdated request responses. Both Java-generated and native-generated
Playgrounds use the same assets and registry hook.

`EmbeddedGrammarConformanceTest` runs the shared Java/native Rust edit sequence and
an actual generated WASM project. `scripts/embedded-regions-browser.mjs` (under
`unlaxer-dsl/ubnf-vscode`) verifies Chromium display, Unicode CP→UTF16 selection,
partial/failed siblings, block edits, stale selection and strict-mode compatibility.
The `embedded-grammars` CI job runs both and uploads browser observations.

This completes the partial-region/update/Playground slice #421. Shared-input grammar
calls, a generated LSP registry bridge, full semantic query wiring for every child
language, and the existing TinyExpression production-editor migration remain parent
#369 requirements. Existing `LanguageQueries` and external-provider bridges remain
available to explicitly registered clients; this view does not imply a provider is
installed or attach guessed semantics to incomplete source.
