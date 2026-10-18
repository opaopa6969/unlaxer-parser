# Nested capture の mapped node / enum 葉

#626 は #583 のうち、`@nestedCaptures` の直接 rule reference が mapped node / enum を表す場合を追加する。annotation のない flat API、既存 scalar text / i32、通常の optional/list の契約は維持する。

```ubnf
grammar Example {
  @package: example
  @root @nestedCaptures @mapping(Root,params=[value])
  Root ::= '[' [ {Leaf} ] @value ']';
  @mapping(Leaf,params=[text]) Leaf ::= 'a' @text;
}
```

Java の field は `Optional<List<ExampleAST.Leaf>>`、Rust は `Option<Vec<Box<Ast>>>` になる。Java は生成済みの具体 record / family interface を使い、Rust は既存の `Ast` enum を使う。Rust の node 葉は scalar の場合も `Box<Ast>` に統一し、再帰可能なサイズを保つ。enum 葉は両言語とも宣言済み enum 型であり、文字列へ変換しない。Java の `NestedShape` は既存 3 引数 constructor を保持し、mapped 葉では具体型名を持つ 4 引数 constructor を使う。

mapper は実解析時に保存した Value/Optional/List 境界から container を組み立て、Value 境界と元 CST の leaf token/node を出現順に対応させる。原文を再解析せず、原文文字列から AST を作り直さず、CP 範囲を identity の代わりにしない。同一 CP 範囲の別々のゼロ幅 node も別々に投影する。子 node は従来 mapper を通るため、Java の保持 `SourceMappedAst` と Rust の owned AST span は元の CP 位置を保つ。

enum は値であり、同じ enum 定数の出現を identity source map で区別しない。各出現の原文 CP 範囲と text は CST の `CaptureSnapshot` / `capture_tree::snapshot` の Value 葉で取得する。繰り返し、重複、空 leaf、補助文字、CRLF でもこの区別は同じである。

manual evaluator は nested 集約値全体を受け取る。Rust は `&Option<Vec<Box<Ast>>>` などを借用する。Java API schema と両 host の Rust schema / impact は実際の型と外側 cardinality を報告する。preferred snapshot の候補深さは、新しい capture 境界 wrapper も含めた Java CST の深さに揃える。既存 flat の計画は変更しない。

投影準備の CST 走査は深さ 256 未満、訪問・保留 node 合計 65,536 の境界を使う。snapshot 自体の text 保持上限は合計 UTF-8 2 MiB。これらは既存 CST、生成 AST、通常 mapper も含めた全プロセスメモリの上限ではない。元の mapped rule の意味変換・失敗条件は通常 mapper と共通である。呼び出し側が public CST の node ID / 辺を改変した場合の通常 mapper 全体の防御は #635 の別契約であり、この準備走査だけで不正 CST 全体への安全性を保証するものではない。

## 検証した組合せ

`spec-corpus/nested-mapped-leaves/` と `NestedMappedCaptureConformanceTest` は Java21 / Rust1.85 で両 frontend の Rust 出力 byte 一致、実 compile/map/manual eval、型 schema / impact を検証する。手書き oracle は optional/list の順序・有無・空値、子 AST の CP span と保持 source map、enum 出現境界、同 CP の別 node、scalar preferred、有限の自己再帰 mapped node、mapped family と dotted family の `commonField` 呼出し、preferred 候補の順序・深さ・位置を含む。

## この単位の範囲（#626 時点）

現在の対応範囲は [typed nested capture の表](typed-nested-captures.md#nested-の対応範囲と拡張先)を参照する。

- direct choice の mixed value 葉は [nested mixed values](nested-mixed-values.md) で対応する。unmapped rule alias / recursive helper を介した nested shape は引き続き早期拒否し、[separated list](nested-separated-captures.md) は #646 で対応する。
- mapped / enum 葉を含む複数要素 group や choice は早期拒否する。直接参照した mapped sum rule が family node へ投影される経路は対応する。
- shared mapping と nested field の family commonField は後続 [#637 の契約](nested-shared-fields.md)で扱う。`@typeof`、associative projection、`@eval`、token-stream 併用は既存の拒否を維持する。
- family 葉の commonField と通常 preferred snapshot は実 consumer で確認済み。nested field 自体の family commonField は #637 で追加検証する。recursive node を含む全組合せ、auto-eval / typeof との組合せはこの単位では未検証であり、完了としない。
