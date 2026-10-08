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
