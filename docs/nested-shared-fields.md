# Nested field を共有する mapping と family

#637 は #583 のうち、複数の rule が同じ型付き nested record / variant を作る経路と、その field を family の共通 accessor から読む経路を扱う。`@nestedCaptures` を付けない既存 flat 共有 mapping の型合流は変更しない。

```ubnf
grammar Example {
  @package: example
  @root @mapping(Family) @commonField(value)
  Start ::= A | B;
  @nestedCaptures @mapping(Row,params=[value])
  A ::= 'a' [ {Leaf} ] @value;
  @nestedCaptures @mapping(Row,params=[value])
  B ::= 'b' [ {Leaf} ] @value;
  @mapping(Leaf,params=[text]) Leaf ::= '😀' @text;
}
```

A/B は同じ Java `Row(Optional<List<ExampleAST.Leaf>> value)` / Rust `Ast::Row { value: Option<Vec<Box<Ast>>>, ... }` を作る。Java の `Family.value()` と Rust の `common_family().value` は、この再帰型全体を返す。Rust の view は `&Option<Vec<Box<Ast>>>` を借用する。

## 共有できる条件

同じ mapping 型名を使う全 contributor が `@nestedCaptures` を明示し、parameter の名前・順序、Optional/List の全階層、leaf 型が一致する必要がある。node 葉は Java の mapped record / family 型名まで比較し、Rust が全 node を `Box<Ast>` で表すことを理由に異なる nominal 型を合流しない。enum は宣言名を比較する。text と i32 は別型である。

flat/nested 混在、内側 container の違い、異なる node / enum 型などは、両 host が `E-NESTED-SHAPE` で生成前に拒否する。Java structured diagnostic の規則を source snapshot の CP span に解決し、native diagnostic はその CP 範囲を表示する。Object / AstValue に広げて形を失わせない。共有するのは公開型の契約であり、literal や repetition の最小・最大数まで同じ文法であることは求めない。

共有型の比較は全 contributor の個別検証（capture traversal / schema depth の上限を含む）が終わってから行う。型ごとの共有比較は一度だけ行い、未検証の深い規則を先に再帰走査しない。

Java→Rust lowerer の共有 Field 合流では NestedShape を保持する。既存 3/4 引数の flat Field constructor に情報を落として作り直さない。

## Family と source map

異なる record が同じ nested field 型を持つ family の `@commonField` は既存の共通投影で動いており、この単位で実 consumer の検証範囲に含める。dotted family の field、text/node/enum の入れ子、field を持たない mapped sum 自身に `@nestedCaptures` が付いた場合も検証する。`@commonField` の通常条件（mapped sum、各 variant の field 存在と型一致など）は維持する。

実行時の capture 境界と mapper は #619/#626 の仕組みをそのまま使う。元 CST から直接 map し、共有する record のインスタンスや子 node の CP span を出現位置ごとに保持する。preferred snapshot は `ParseContext.getCurrent().getTokens()` にある committed root を渡す。sum parser の `Parsed.getRootToken()` が返す内側 token と、公開 root mapping API の入力契約を混同しない。

## 共通 oracle と残る範囲

`spec-corpus/nested-shared-fields/` と `NestedSharedFieldConformanceTest` は Java21 / Rust1.85 の両 host byte 一致、実 compile/map/manual eval、Java/Rust API schema・impact、common accessor、root/child CP、保持 source map、preferred 候補、不正 shape と失敗後の再利用を比較する。実行には `-DrustConformance=true` が必要。

両 host の portability check も `@nestedCaptures` を既知の annotation として扱う。対応する schema は `portable=true`、不正な nested shape は規則の CP 範囲を持つ `P-NESTED-SHAPE`、family 共通型の不一致は既存の `P-COMMONFIELD` となる。生成前診断の `E-*` と portability の `P-*` を区別する。他の未知 annotation の拒否は維持する。

[Nested separated](nested-separated-captures.md)、[mixed 葉](nested-mixed-values.md)、[公開 CST の mapper 防御](public-mapper-cst.md)は #646 / #653 / #635 で実装・共通検証を追加した。通常のauto-literal leafをnested ownerから評価する経路と、通常のauto-passthrough ownerからnested leafへ委譲する経路は[#673の共通consumer](../spec-corpus/nested-auto-eval/README.md)で値・callback順・CP・保持snapshotまで検証する。nested owner自身のauto-eval/associative/token-streamと、未対応のunmapped alias / recursive helper経由のshapeは両hostの生成前拒否を維持する。親 #583/#568の全体回帰・CI・mergeは別の完了条件である。

`@typeof`との合成は [nested typeof](nested-typeof.md)（#660）で対応し、private localとshared constructor fieldの型・空値のCP位置を比較する。
