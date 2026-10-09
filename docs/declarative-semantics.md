# 宣言的な意味規則（schema v1）

意味規則 JSON を UBNF の規則名・capture・mapping field に結び付け、生成 parser の
`EditorCst` から型、スコープ、値、呼出し候補を構築する。Java と Rust は同じ JSON と
原文を使い、診断、期待型、補完、Unicode コードポイント（CP）の位置を共有する。

JSON は UBNF 文法とは別の入力である。UBNF frontend に意味規則構文を追加したものではなく、
既存 frontend の `GrammarDecl` に対して専用 loader が検証する。
Java は `SemanticRulesLoader`、Rust は `unlaxer_generator::semantic_rules` が JSON を読み、
実行は `SemanticRuleEngine` と `unlaxer_runtime::semantic_rules` が担う。
Rust runtime 自体に JSON parser は要求しない。検証済み IR を直接構築する API もある。

## 読み込みと実行

[共通文法](../spec-corpus/semantic-rules/model.ubnf)と
[意味規則](../spec-corpus/semantic-rules/rules.json)を使う Java の例を示す。
`TypedModelMapper` はこの文法から生成したクラスである。

```java
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.semantic.SemanticRuleEngine;
import org.unlaxer.dsl.semantic.SemanticRulesLoader;
import org.unlaxer.editor.EditorCst;

var grammar = UBNFMapper.parse(Files.readString(
    Path.of("spec-corpus/semantic-rules/model.ubnf"))).grammars().get(0);
var program = SemanticRulesLoader.load(Files.readString(
    Path.of("spec-corpus/semantic-rules/rules.json")), grammar);
var inventory = SemanticRulesLoader.inventory(grammar);
String source = "builtin Int {} fn f(Int):Int; let x:Int; call f(?);";
var cst = TypedModelMapper.parseEditorCst(
    source, List.of("?", ")", ";", "}"), EditorCst.Options.defaults());
var analysis = SemanticRuleEngine.analyze(program, inventory, "memory:demo", 1, cst);
int cursor = source.codePointCount(0, source.indexOf('?'));
var query = SemanticRuleEngine.query(analysis, "memory:demo", 1, cursor, "");
// expected に Int、completions に x。diagnostics は analysis.diagnostics()。
// query.get().edit() は prefix を置き換える CP 範囲。
```

対応する Rust API は次のとおり。`generated` は同じ文法から生成した module とする。

```rust
use unlaxer_generator::semantic_rules::{inventory, load};
use unlaxer_runtime::{editor_cst, semantic_rules};

let grammar = unlaxer_ubnf::parse(&std::fs::read_to_string(
    "spec-corpus/semantic-rules/model.ubnf").unwrap())
    .unwrap().grammars.remove(0);
let program = load(&std::fs::read_to_string(
    "spec-corpus/semantic-rules/rules.json").unwrap(), &grammar).unwrap();
let current = inventory(&grammar).unwrap();
let source = "builtin Int {} fn f(Int):Int; let x:Int; call f(?);";
let cst = generated::parser::parse_editor_cst(
    source, &["?", ")", ";", "}"], editor_cst::Options::default()).unwrap();
let analysis = semantic_rules::analyze(
    &program, &current, "memory:demo", 1, &cst).unwrap();
let cursor = source[..source.find('?').unwrap()].chars().count();
let query = semantic_rules::query(&analysis, "memory:demo", 1, cursor, "").unwrap();
```

解析状態と意味診断は別である。`COMPLETE` でも未解決参照や型不一致の診断があり得るため、
状態だけを「意味的に正しい」の判定に使わない。`FAILED` では model と call-site を返さず、
`query` は空になる。有効な model への照会では URI/version の違い、範囲外 cursor、
不正な prefix、複数の引数に属する曖昧な cursor を拒否する。
prefix は cursor 直前の原文と一致し、同じ引数内に収まる必要がある。補完 edit は
`[cursor - prefix の CP 長, cursor)`。UTF-16 index や UTF-8 byte offset を渡さない。

## schema と selector

トップレベルの必須キーは `schemaVersion: 1`、`grammar`、
`profile: "portableNominal/1"`、`unknownLiterals`、`rules`。
各 rule は一意な `id`、UBNF の `node`、`emit` を持ち、任意で `dependsOn` を指定する。
selector は `{"capture":"name"}`、`{"field":"name"}`、`{"literal":"Int"}` のいずれか一つ。
`field` は `@mapping(..., params=[...])` の field と同名 capture の両方を要求する。
実行時は CST の capture を読むため、生成 AST の field を reflection で読む API ではない。

| emit | 項目 | 意味 |
|---|---|---|
| `scope` | なし | node の原文範囲を lexical scope にする |
| `type` | `name`, `kind`, 任意の `parents` | `BUILTIN` / `RECORD` / `INTERFACE` の型宣言 |
| `field` | `owner`, `name`, `type` | `owner` は type rule の id。最内側の包含型に所属 |
| `symbol` | `name`, `type`, `visibility` | `after` は宣言末尾から、`scopeStart` は scope 冒頭から可視 |
| `signature` | `name`, `parameters`, `result` | 固定個数の引数と戻り値型を持つ呼出し候補 |
| `call` | `name`, `arguments` | 呼出しと引数ごとの照会位置 |
| `expression` | `type` | node と同じ範囲の引数に明示した型を与える |
| `reference` | `name` | node と同じ範囲の引数を、選択した名前で可視値へ解決する |

`owner` と `visibility` は文字列、その他の表中の項目は selector である。
名前・単一の型は一つの値を要求する。`parents` / `parameters` / `arguments` は出現順の列を
扱い、capture がない空列も表現できる。単一 selector がゼロ個または複数に束縛されると
`SELECTOR_CARDINALITY` になる。`literal` は schema の固定値を一つ返す。
`dependsOn` は未定義参照・循環・実行層との整合を検証する。実行層は
scope → type/field → symbol/signature/expression/reference → call で固定され、
依存指定で任意のコードや別の評価順序を注入することはできない。

### 参照、固定式、UNKNOWN

例えば次の規則を使う。

```json
[
  {"id":"references","node":"Reference","emit":"reference","name":{"capture":"name"}},
  {"id":"integer","node":"Integer","emit":"expression","type":{"literal":"Int"}},
  {"id":"holes","node":"Hole","emit":"expression","type":{"literal":"?"}}
]
```

`Reference ::= ID @name;` の `reference` は識別子 capture だけを名前として使う。
共通文法では Rust backend の capture 契約に従い、Reference に
`@mapping(Reference, params=[name])` を付ける。
引数原文に末尾の空白やコメントが含まれても、その文字列を名前に混ぜない。
`expression` の `Int` は別途 type 宣言が必要であり、数値の綴りから自動推論しない。
`?` は共通モデルの UNKNOWN であり、type 宣言を要求しない。

関連付けは **node span と call の argument capture span の完全一致**で行う。
wrapper が範囲を広げる文法では、一致を保つ capture 配置が必要である。同じ範囲に複数の
expression/reference が束縛されると `AMBIGUOUS_EXPRESSION` で失敗する。
明示規則がない引数は trim した原文を可視値の名前として解決するため、コメントを含む参照には
明示規則を使う。`unknownLiterals` に一致する引数、synthetic な引数、ERROR と交差する引数は
UNKNOWN になる。明示 expression の型が `?` の場合も解決済みの UNKNOWN として扱い、
未解決参照診断を出さない。参照が見つからなければ `UNRESOLVED_REFERENCE` を付け、
型は UNKNOWN に保つ。UNKNOWN は既知型への適合成功を意味しない。

## 部分入力と失敗

`EditorCst` は原文 slice、CP span、synthetic、ERROR / MISSING defect を保持する。
型・値・signature・expression・reference は、node と全 capture が synthetic でなく、
node が ERROR と交差しないときだけ採用する。補完用に追加した文字から宣言を作らない。
scope は途中入力でも原文範囲を保持し、未閉鎖 block の宣言が root に漏れることを防ぐ。

call は不完全でも実在する名前を保持できれば扱う。ただし名前が synthetic/空、または
capture/field の名前範囲が ERROR と交差する場合は `INCOMPLETE_BINDING` を診断し、
その call と補完位置を作らない。`literal` の call name は schema の明示定数なので
原文から回復した名前とは区別する。破損した引数は UNKNOWN として周辺の確定情報を残す。

未定義の型、重複・曖昧な binding、型循環、inventory 不整合、上限超過などで model 構築に
失敗すると `FAILED` と位置付き診断を返す。同名 signature は overload として保持し、
最も近い lexical scope の候補群を使う。呼出し不適合や曖昧さは診断する。
推論の `LIMIT` / `UNSUPPORTED` / `CYCLE` / `INVALID` は `INFERENCE_<STATE>` と
`PARTIAL` を返し、既知の model を保持する。不確かな型判定を成功扱いした補完は返さない。

## 検証と上限

loader は未知キー、重複 JSON キー、先頭 BOM、不正 Unicode scalar、余分な末尾入力を拒否する。
schema/version/profile/grammar、selector、node/capture/field、owner、visibility、依存先と
循環を実行前に検証する。schema エラーは Java の `SchemaException.code()/path()`、
Rust の `SchemaError.code/path` で取得できる。原文解析の診断は code、URI、version、
CP span、rule id を持つ。

| 対象 | 上限 |
|---|---:|
| JSON 入力の UTF-8 bytes | 1 MiB |
| JSON 階層 / value 数 | 深さ 64 / 16,384 |
| IR の文字列と inventory の合計 UTF-8 bytes | 1 MiB |
| schema/IR の個々の文字列 | 1,024 CP |
| 意味規則 | 256 |
| inventory の node / capture・field の合計 | 4,096 / 16,384 |
| inventory 抽出時の各規則の構文走査 | 深さ 64 / 16,384 要素 |
| CST node / capture の合計 | 16,384 / 65,536 |
| CST capture text の合計 UTF-8 bytes（重複分も計上） | 4 MiB |
| 意味規則と CST の binding | 4,096 |
| scope（暗黙 root を含む） / 型 binding | 512 / 512 |
| 実行時に選択する単一の名前・型 | trim 後 1,024 CP |
| TypeSystem / CallInference の評価 budget | 各 4,096 |
| snapshot URI | 4,096 CP |

これは意味規則層の上限であり、parser の入力全長・構文解析費用の上限を代替しない。
呼出し側は利用する parser の入力・再帰・回復試行制限も設定する。

## inventory の進化

inventory は grammar 名と各規則の capture / mapping field 集合から作る。
生成 Java クラス名や Rust enum 名は照合キーではなく、それだけの rename では意味規則は変わらない。
capture の rename は selector の更新を必要とし、古い schema は `UNDEFINED_CAPTURE` などで
拒否される。field の削除や同名 capture の削除も検証する。実行時には Program の inventory と
現在の inventory の完全一致を要求する。

文法変更時は変更後の `GrammarDecl` から inventory と Program を作り直す。
inventory は文法本文全体のハッシュではなく、同じ名前・capture を保った意味の変化まで
証明しない。別 AST adapter も、同じ規則名・capture・原文範囲を持つ `EditorCst` と inventory を
供給する必要がある。任意の AST を自動解釈する API ではない。

## region と query provider

`SemanticRuleQueryProvider`（Rust は `semantic_rules::QueryProvider`）は
`LanguageQueries.Provider` に接続し、`VALIDATE` と `COMPLETION` を提供する。
Java の登録例を示す。`project` と `regions` は現在の snapshot に対して構築する。

```java
var language = new org.unlaxer.source.LanguageRegions.Language(
    "typed", "example/typed", "1", "TypedModel", "Document");
var provider = new org.unlaxer.dsl.semantic.SemanticRuleQueryProvider(
    program, inventory, language, project.id(), project.version(),
    request -> TypedModelMapper.parseEditorCst(
        request.region().sourceMap().output().text(),
        List.of("?", ")", ";", "}"), EditorCst.Options.defaults()));
var queries = new org.unlaxer.source.LanguageQueries(
    regions, project, java.util.Map.of(language, provider));
var result = queries.query(hostSnapshot, project, hostCursor,
    org.unlaxer.source.LanguageRegions.Operation.COMPLETION,
    java.util.Map.of("prefix", ""));
```

provider は language id / package id / package version / grammar / entry の完全一致と
project id/version を確認する。構築時には grammar、entry 規則の実在、Program と inventory の
一致を要求する。parser callback の結果原文も region snapshot と一致しなければならない。
package identity は dispatch 境界で渡す情報であり、JSON が package を取得・検証したり、
callback が正しい entry を実行したことを推測したりはしない。対応する parser の登録は呼出し側の責務である。

cursor は `LanguageQueries` が host CP 位置から region CP 位置へ変換する。
診断、補完候補の宣言位置、prefix 置換 edit は source map を通して host に戻る。
edit は現在の host の region body 内に収まる必要があり、曖昧な写像や境界外編集は拒否する。
例えば host が `😀{` で始まる場合、inner の位置は 2 CP だけ移動し、UTF-16 の 3 code unit ではない。
古い host/project/provider 応答は拒否する。未登録 provider は `UNAVAILABLE`、
FORMAT など未対応の操作は `UNSUPPORTED` となる。

## 型付き診断と生成 LSP

同じ provider の `diagnostics(VALIDATE)` は `ProviderProtocol.Diagnostic` を返す。
code と原文 CP span を保持し、message は `CODE: rule [analysis=STATE]`、severity は
`ERROR` である。`LanguageQueries.diagnosticsAll` が各 region の source map を通して
host に写像し、生成 Java LSP / Classic Rust LSP が host の UTF-16 range と現在の
文書 version を付けて `textDocument/publishDiagnostics` へ転送する。

意味モデルの状態と診断収集の状態は区別する。model が `FAILED` でも位置付き診断が
得られた場合、型付き応答は `PARTIAL`、message の `analysis` は `FAILED` とする。
model や既存 item query の状態を成功に変えるものではない。診断のない失敗は
`FAILED` のまま、診断のない正常・途中入力は `COMPLETE` / `PARTIAL` のまま返す。
検証以外の typed request は `UNSUPPORTED`。package/project 不一致、古い parser 原文を拒否する。

登録は明示的である。Java の生成 LSP subclass は `languageQueries(snapshot)` と
`languageQueryCapabilities()`、Rust の `Backend` は `language_queries(snapshot)` と
`query_capabilities()` に、この provider を含む最新 binding と `VALIDATE` を渡す。
能力表示は transport・選択 profile・登録 provider の交差で決まる。
open/change/save で再検証し、修復と close で古い診断を消去する。同じ本文でも新しい
version には再通知し、以前の本文へ戻る編集も再解析する。chunk 集合の一致だけで
別の全文の解析結果を再利用しない。古い文書 version や
binding を現在の結果として通知しない。

[共通 lifecycle fixture](../spec-corpus/semantic-diagnostics/events.jsonl) の独立期待値と
`SemanticDiagnosticConformanceTest` が、実 UBNF 生成 Java/Rust parser、意味規則、
両 LSP consumer を接続してこの契約を検証する。Unicode/CRLF、型不一致、未定義型、
構文失敗、途中入力、修復、保存、close、古い更新、stale host/project を含む。

## 型システムの拡張境界

schema v1 が受理する profile は **`portableNominal/1` のみ**。
名前付き型と明示した interface parent 関係を `TypeSystem` の nominal / NAMED provider に渡し、
共通 `CallInference` を使う。型宣言は root scope に限定し、local type は
`TYPE_SCOPE_UNSUPPORTED` とする。field は model に保持するが、この profile で構造的部分型や
member access の推論を有効にするものではない。

JSON からカスタム TypeSystem provider を登録する機構はない。generics、型変数、variance、
union/intersection、言語固有の変換・overload 優先順位、フロー依存の型、borrow/lifetime、
複数ファイルにわたる名前解決などが必要なら、別の `LanguageQueries.Provider` を実装し、
`TypeSystem` / `CallInference` の対応 API や言語固有の解析器へ接続する。
その provider も snapshot、位置、package identity、capabilities の契約を守る。
未知 profile を portable profile とみなして成功させない。
この実装は Java / TypeScript / Rust などの完全な意味論を提供しない。

## ローカル検証と CI

repository root で JDK 21、`rustc`、`cargo` を用意して実行する。

```sh
mvn -pl unlaxer-common,unlaxer-dsl -am test \
  -Dtest=SemanticRulesConformanceTest,SemanticDiagnosticConformanceTest \
  -DrustConformance=true -Dsurefire.failIfNoSpecifiedTests=false
```

`generatedCstAndRulesDriveBothRuntimes` は opt-in であり、素の `mvn test` では skip される。
Java の schema テストだけの成功を Java/Rust 共通機能の検証完了と扱わない。
CI の Rust backend and Java conformance job は同じ `-DrustConformance=true` を指定する。
ローカルでも surefire の skipped 数と assumption 理由を確認し、skip 0 の実行結果を残す。

共通 corpus は実生成 parser から strict/partial CST を作り、Java/Rust の一致に加えて
`cases.json` の独立 expected（型、引数、診断と span、期待型、補完、edit）を検査する。
schema 拒否、Unicode、上限、capture/class 進化、回復された call name、region の位置変換と
package/project identity 拒否も検証する。生成 Rust は Java backend と native generator の
出力一致も確認する。frontend の受理・拒否や `UBNFMapper` を変更する場合は、さらに実行する。

```sh
mvn -pl unlaxer-common,unlaxer-dsl -am test \
  -Dtest=RustUbnfFrontendConformanceTest -DrustConformance=true \
  -Dsurefire.failIfNoSpecifiedTests=false
```
