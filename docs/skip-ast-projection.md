# `@skip` の AST 投影契約

対象: Java 生成 parser / AST / mapper と、Java-host・native Rust-host の Rust 生成経路。
関連 issue: [#323](https://github.com/opaopa6969/unlaxer-parser/issues/323)。

## 何を省き、何を残すか

`@skip` は「この構文を解析しない」指定ではない。その規則と内部の subtree を、AST の自動探索・投影から除外する指定である。

| 対象 | `@skip` の効果 |
|---|---|
| 構文規則、入力消費、失敗条件 | 残す |
| CST、capture、ソース位置 | 残す |
| scope / 宣言 / backref、transaction と rollback | 残す |
| 規則自身の `@mapping` | AST 型・mapper dispatch・evaluator 義務を生成しない |
| 規則内の mapped 子 | この subtree を通じた AST 探索には現れない |
| 親から明示的に capture した値 | 子 AST ではなく、capture した範囲の text として扱う |

子の mapped 規則自体を文法から削除するわけではない。同じ規則を skipped subtree の外から呼べば、通常どおり AST にできる。
したがって子規則の AST 型・evaluator method は生成物に残り得る。

Java では skipped 規則自身に `notNode` tag を付けるが、共有される子 parser へ tag を再帰伝播させない。
このため再帰文法の初期化で parser グラフを無限巡回せず、別の呼出箇所の子 parser を汚染しない。
subtree 全体を隠す保証は生成 typed mapper の境界で実現する。汎用 `TagBasedReducer` の子昇格規則は変更しない。
この文書の CST は元の token 木（Java の `getOriginalChildren()`）を指し、tag による filtered view とは区別する。

```ubnf
grammar Example {
  @root @mapping(Root, params=[hidden, visible])
  Root ::= Hidden @hidden Leaf @visible;

  @skip @mapping(Ghost)
  Hidden ::= '(' Leaf ')';

  @mapping(Leaf, params=[text])
  Leaf ::= 'x' @text;
}
```

入力 `(x)x` では `hidden` は `(x)`、`visible` は `Leaf` node になる。`Ghost` 型は生成しない。
`@skip` は whitespace の読み飛ばしや構文エラーの回復指定とは異なる。

## root と mapper の境界

root が `@skip`、または透明な alias / group / choice を通じて skipped subtree に到達する場合も、parser は構文を解析できる。
解析成功と AST の存在は別である。AST が得られない入力に対し、mapper は明示的なエラーを返す。
Rust の root mapper は、従来どおり投影結果がちょうど 1 node であることを要求する。
空 AST 型を含む生成物もコンパイルできるが、存在しない値を evaluator が評価することはない。

親に `@mapping(Root)` があれば、その親は独立した AST node である。子がすべて skipped でも親まで自動的に消えることはない。

## 型・位置と移行上の注意

- Java では skipped 規則の存在しない型を AST field / mapper の型推論が参照する不整合を修正した。該当する field は text 型となる。optional / repeated capture の外側の型は維持する。
- Rust は `@skip` を移植不能な annotation として拒否していたが、IR の `Rule.skip` と mapper の投影境界として扱う。手書きの `unlaxer_codegen::ir::Rule` struct literal には `skip: false` を追加する。runtime の `unlaxer_runtime::Rule` は変更しない。
- Java の IR は既存 5 引数の `Rule` constructor を保持する。skip を省略した場合は `false`。
- skipped mapping は共有 AST schema の統合や生成 method 名の衝突判定には参加しない。一方、参照先、capture と mapping params の整合性、非消費ループなどの文法検証は省かない。
- text は既存 mapper の字句変換契約に従う。任意の String field に独立した span field を追加する変更ではない。source span は CST/capture と source-preserving AST の既存 API で保持する。
- 未対応の annotation（`@enum`、`@eval` など）を `@skip` で囲って Rust 対応済みにする機能ではない。未知 annotation も引き続き拒否する。

## 検証

[共通 corpus](../spec-corpus/skip-mapping/README.md) と実コンパイラによる
`JavaSkipMappingTest` / `SkipMappingConformanceTest`、lowering の `RustSkipLoweringTest` で検証する。
通常の Maven test だけでは Rust conformance は skip されるため、明示的に有効化する。

```sh
mvn -pl unlaxer-common,unlaxer-dsl -am test \
  -Dtest=JavaSkipMappingTest,RustSkipLoweringTest,SkipMappingConformanceTest,RustUbnfFrontendConformanceTest,RustPortabilityConformanceTest \
  -DrustConformance=true -Dsurefire.failIfNoSpecifiedTests=false
```

CI は `unlaxer-dsl/target/rust-skip-mapping.tsv` を必須成果物として保存する。
これは AST 投影の互換性拡張であり、transaction を減らす性能施策ではない。
