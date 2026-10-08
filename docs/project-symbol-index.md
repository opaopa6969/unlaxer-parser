# 文書・依存ライブラリをまたぐシンボル解決

`ProjectSymbolIndex` は、各言語の provider が作った [SemanticModel](semantic-model.md)
をプロジェクト単位で束ねる不変スナップショットである。Java は
`org.unlaxer.dsl.semantic.ProjectSymbolIndex`、Rust は
`unlaxer_runtime::semantic_project::ProjectSymbolIndex` を使う。

例えば `main` が `lib` の `accessor: DbAccessor` を `db` として import すると、
`IAccessor` を要求する補完に `db` が現れる。補完 edit の位置は `main`、
definition の位置は `lib` の宣言を指す。`DbAccessor` の継承関係や公開状態を
変更したスナップショットでは、その情報に従って候補・診断が変わる。

## データ契約

| データ | 意味 |
|---|---|
| project ID / version | プロジェクトの名前空間と解析世代。version は非負、更新は単調増加 |
| Module | module ID、元文書の SemanticModel、公開 symbol ID の集合、import 宣言 |
| SemanticModel | URI、文書 version、原文、型・scope・symbol。既存の検証規則を維持 |
| ModuleRef | dependency ID と module ID。空の dependency は現在の所有者内を指す |
| Import | ローカル名、対象 ModuleRef、対象の公開名、scope、宣言 span、visibleFrom |
| Dependency | canonical ID、固定 version、artifact SHA-256、解決済み module 群 |
| Identity | project / dependency / dependencyVersion / module / symbol |
| Definition | Identity、元文書 URI / version / 宣言 span |
| Completion | Candidate、適合性、理由、現在の文書への version 付き edit |

span と cursor は半開の Unicode code point 座標で、UTF-16 offset ではない。
URI は呼び出し側で正規化する。同じ所有者内で URI・module ID を重複させない。
別プロジェクトは別インスタンスとなり、静的な共有シンボル表は持たない。
symbol ID は provider が module 内で安定させる。ファイル移動でも module ID と
symbol ID を保てば identity は変わらず、Definition の URI / version が変わる。

型 ID は provider が名前空間を含めて正規化する。同じ型 ID が複数文書に現れる場合、
kind、親型リスト、field 名・型リストが一致しなければ `CONFLICTING_TYPE` とする。
宣言位置は文書ごとに異なってよい。親型・field の順序も契約の一部とする。
各 SemanticModel は参照する型情報を含む必要があり、import 先の宣言から参照型情報を
供給するのは provider の責務である。型情報の更新時は、その情報を持つ依存文書の
model も更新する。ソース抽出・jar / d.ts / crate 解釈をこの表へ組み込まない。

この import は**対象言語のシンボル import**であり、UBNF 文法の import とは別である。
埋め込み言語の仮想文書を使う場合、その URI と module ID を provider が付け、
元の文書への変換は region の source map を使う。ここでは CP 座標を再変換しない。

## 解決と補完

1. cursor を含む最も内側の scope から親へ探索する。
2. `visibleFrom <= cursor` の import とローカル宣言が対象になる。
3. 同じ scope のローカル宣言は import を隠し、内側の名前は外側を隠す。
4. 同じ scope の import alias が複数あれば `AMBIGUOUS_IMPORT`。
   一方が未解決でも曖昧性を解消したことにはしない。
5. import 先では root scope の宣言名を検索し、公開 ID に含まれるものだけを使う。
   非公開は `PRIVATE_SYMBOL`、module / 名前の欠落は `UNRESOLVED_IMPORT`。

相互 import は宣言を先に登録するため利用できる。import 自体の再 export、
推移的な wildcard export はこの版にはない。循環 import を再帰的に展開しない。
依存ライブラリ内の無修飾 import はそのライブラリ内で解決し、利用側プロジェクトを
暗黙に参照しない。別の lock 済み依存への参照は dependency ID を明示する。

`resolve` は RESOLVED / UNRESOLVED / AMBIGUOUS と候補・診断を返す。
`diagnostics` は cursor に関係なく全 import を検査する。補完では解決済みの名前に
`SemanticModel` と同じ nominal な適合判定を行い、YES と UNKNOWN をこの順で返す。
同順位は code point 順で並べる。NO・曖昧・未解決候補は返さない。UNKNOWN を YES と
みなさず、補完と `isAssignable` は同じ判定・理由を使う。

Java:

```java
var index = new ProjectSymbolIndex("workspace", 10, modules, lockedDependencies);
var definition = index.resolve("main", 10, 7, cursor, "db");
var items = index.complete("main", 10, 7, cursor, prefix, "IAccessor");
var next = index.withModules(11, updatedModules);
```

Rust:

```rust
let index = ProjectSymbolIndex::new("workspace".into(), 10, modules, locked_dependencies)?;
let definition = index.resolve("main", 10, 7, cursor, "db")?;
let items = index.complete("main", 10, 7, cursor, prefix, "IAccessor")?;
let next = index.with_modules(11, updated_modules)?;
```

呼び出しに project / document の両 version を要求し、不一致を `STALE_SNAPSHOT`
として拒否する。prefix が実際の原文の cursor 直前と一致しなければ
`PREFIX_MISMATCH`。補完 edit はその prefix の CP span のみを置き換える。
編集の適用側でも文書 version を再確認する。非同期応答のキャンセル・新世代への
適用制御は query 層 #376 の責務であり、この不変インスタンスに状態を上書きしない。

## 更新と lock

`withModules` は全 project module を置き換えて検証し直す。変更・削除・移動後の
補完と診断は新しい表から求める。古いインスタンスは古い version で引き続き読める。
同じ URI の原文を変更する際は文書 version も増やし、version の巻き戻し・同じ
version での原文差し替えは `STALE_DOCUMENT`。型情報だけが変わる依存文書は
原文 version を保ったまま project version を更新できる。

Dependency は解決済みであり、ネットワーク・ファイル I/O を一切行わない。
version は数字で始まる固定値、SHA-256 は小文字の16進64桁を要求する。
`latest` / version range を受理しない。artifact のハッシュ検証と cache 読み込みは
package resolver の責務で、この API が検証するのは lock identity の形式である。
lock 自体を変更するときは、新しい dependency 群で新世代のインスタンスを作る。

全再構築が正しさの基準であり、増分解析の高速化・容量制限付き query cache は
#376 の別層で扱う。generics / overload / structural / trait の判定は #375、
AST からこのモデルを作る共通の意味規則は #381 で拡張する。

## Java / Rust の共通検証

[`spec-corpus/project-symbols/corpus.json`](../spec-corpus/project-symbols/corpus.json)
に23ケース・56観測の独立期待値を置く。import、別名、shadowing、非公開、未解決、
曖昧性、循環、固定依存、別プロジェクト、型宣言変更、削除、移動、古い snapshot、
補助面文字を含む prefix と definition / edit 位置、lock 失敗を両実装へ適用する。
Rust の JSON 出力は Java 出力だけでなく、同じ独立期待値とも比較する。

```sh
mvn -B -pl unlaxer-common,unlaxer-dsl -am test \
  -Dtest=ProjectSymbolIndexConformanceTest,SemanticModelConformanceTest,SemanticModelUbnfTest \
  -DrustConformance=true -Dsurefire.failIfNoSpecifiedTests=false
cargo +1.85.0 clippy --locked --manifest-path rust/Cargo.toml \
  -p unlaxer-runtime --all-targets -- -D warnings
```

CI は opt-in の Rust 比較を必須実行し、`unlaxer-dsl/target/rust-project-symbols.tsv`
の非空を確認して artifact に保存する。既存 #372 の期待値・位置は変更しない。
