# Preferred AST snapshot（Java / Rust）

生成 mapper は、1 回の構文解析に現れる mapped AST 候補を保持し、AST の単純名を優先して選択できます。`preferred` は数値型の変換指定ではありません。Java の既存 `mapParsedTree` / `mapSubtreeTree` と選択規則を維持し、Rust の UBNF 生成に対応 API を追加しています（#567）。

```java
var tree = ExampleMapper.mapParsedTree(committedRoot);
var selected = tree.select("Item");
var ast = selected.sourceMap().ast();
var span = selected.sourceMap().sourceSpanOf(ast); // CP、終端を含まない
var candidates = tree.candidates();              // immutable preorder view
```

```rust
let snapshot = generated::mapper::parse_mapped_tree("😀1+2")?;
let selected = snapshot.select(Some("Item"));
let ast = selected.ast();
let original_text = snapshot.source_text(selected.span());
// snapshot を保持すれば、別の parse / map 後も原文・AST・位置は変わらない。
```

## 順位と候補

候補は Java token tree の preorder です。`@skip` の subtree は候補に含めません。enum/Text 自体は AST 候補ではありませんが、root enum の生成 wrapper、mapped Node、enum/Text/Node を持つ mapped record は候補になります。sum の透明投影は具体的な AST の単純名を使います。例えば `Family.Item` の優先名は `Item` です。

候補は次の順に比較します。

1. 指定した単純名に一致する候補を優先する。
2. token tree の深さが浅い候補を優先する。
3. 原文の開始 CP が大きい候補を優先する。
4. すべて同じなら preorder で後の候補を選ぶ。

`null` / Rust `None`、空文字、Java `String.isBlank()` 相当の文字列は全候補を優先します。存在しない名前は default と同じ結果です。単純名が衝突しても曖昧エラーにはせず、上記の安定した順位を使います。

ここでの深さは Rust の rule CST 深さではありません。name predicate は Java token tree では透明で、深さを加えません。capture、group、optional、repeat、separated、recovery の Java wrapper を含みます。例えば `Root ::= '😀' Atom @left '+' [Atom @right]` では左 Atom が深さ 2、右 Atom が深さ 3 なので、`Item` を優先すると左が選ばれます。

## Rust の解析入口と保持範囲

通常の `parse_tree` と `map` は従来の CST / AST 契約を保ちます。snapshot は別入口 `parse_tree_for_mapping`、`parse_tree_for_mapping_with_options`、`parse_tree_for_mapping_with_name_snapshots` が作る CST を使ってください。`map_parsed_tree` は grammar root、`map_subtree_tree` は明示した rule entry を扱います。代替 entry を runtime で解析する場合は `parser::mapping_grammar()` が返す共有 grammar を使用できます。

認識規則は共通ですが、metadata capture が追加されるため、資源上限に達する入力深さは通常入口と異なる場合があります。

内部 capture は call site の Java 深さと grammar schema と source call-site および `@typeof` 制約の fingerprint を記録し、通常の transaction / memo に従います。標準 CST、別 grammar の CST、synthetic value node を entry に渡した場合はエラーです。root に `@mapping` がある場合はその root rule が必須です。代替 entry の全入力消費は呼出側が確認してください。候補の `node_id()` は Rust の rule entry を示し、Java wrapper token への参照ではありません。回復 wrapper を持つ rule entry は、その wrapper を含めた深さから列挙します。

snapshot は原文、CP 境界、候補 AST を所有します。同じ候補を再選択すると同じ AST を参照し、別 snapshot は独立です。Rust では各候補が AST を所有するため、親候補の内部 AST と子候補の AST のアドレス同一性は保証しません。Java の既存 identity source map は維持されます。AST 内の各 node の `span()` と `source_text` を組み合わせて、保持した原文を取り出せます。位置は UTF-16 / byte / 表示幅ではなく、元の Unicode code point 単位です。正規化はしません。

## 失敗と資源上限

候補の mapping が失敗した場合（例: int / i32 overflow）、snapshot 作成全体が失敗します。失敗候補を無視して別候補へ進めません。候補ゼロ、選択 subtree 内の recovery marker、不正 root・source 境界・span・arena edge・cycle も拒否します。以前に成功した snapshot は変更しません。

Rust の `MappingLimits` は候補数を既定 4096、展開作業量を 1,000,000 に制限します。`map_subtree_tree_with_limits` で調整できます。既存の再帰的 AST projection を守るため CST projection 深さ 256、Java token 深さ 4096 も上限です。caller が公開 CST fields を変更した場合も、構造検査後に traversal します。`Tree::validate_structure` は source と private CP 境界表の一致、および parser の postorder arena 契約を検査します。

単純な大きい list を走査する際は capture edge を rule ごとに索引化します。候補 AST の投影は候補ごとに行うため、深く包含した多数候補では合計の投影作業量が増えます。その合計を上限で検査します。

## 未完の範囲

#547 全体をこの機能だけで完了とはしません。commonField を使わない mapped sum、associative mapping、および特殊 lowering で UBNF call site と IR の参照が一対一対応しない構造は #569 の対象です。その場合、通常の生成・解析 API は保持し、`mapping_grammar()` は `None`、preferred 解析入口は明示エラーを返します。nested container / recursive unmapped helper は #568 で継続します。公開低水準 IR には Java の source wrapper 情報がないため、IR 単独の `unlaxer-codegen::generate` にはこの source 由来 API を追加しません。

## 共通検証

`spec-corpus/preferred-ast/cases.json` は独立に書いた候補順序・名前・深さ・CP span・評価値を共有します。`PreferredAstConformanceTest` は Java21 の実生成・compile・eval、Java/native 両ホストの Rust 5 ファイル一致と Rust 実 compile・eval、再選択 identity、後続 / 並行 mapping、失敗後 snapshot、subtree、zero-width tie、不正 arena と作業量上限を検証します。

```sh
mvn -pl unlaxer-common,unlaxer-dsl -am test \
  -Dtest=PreferredAstConformanceTest -DfailIfNoTests=false -DrustConformance=true
cargo +1.85.0 test --locked --manifest-path rust/Cargo.toml \
  -p unlaxer-runtime -p unlaxer-codegen -p unlaxer-generator
```

Rust を使う観測は `-DrustConformance=true` が必須です。素の Maven 実行で skip した結果を両版の検証根拠にはしません。
