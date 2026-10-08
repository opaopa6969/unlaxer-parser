# 明示的な曖昧性 profile

`@uniqueLongestChoice` は rule の成功候補をすべて同じ cursor と transaction state から試し、最大消費長の候補が一つだけで、1 code point 以上進む場合にその候補を選ぶ。

```ubnf
grammar Dispatch {
  @root @mapping(Result, params=[value]) @uniqueLongestChoice
  Root ::= 'a' @value | 'ab' @value;
}
```

`ab` は第二候補を選ぶ。最大長が同率なら選択を commit せず失敗する。短い候補間に同率があっても、それより長い候補が一つ成功すれば選べる。候補は 2〜64 個に制限し、空一致だけが最長になる場合も失敗する。直接・間接の左再帰を含む文法は、この profile と組み合わせた時点で両生成器が拒否する。既存 `@longestChoice` の同率時の宣言順、ordered choice、`@predictiveChoice`、演算子の反復による fold は変わらない。

| profile / 観測 | Java | Rust |
|---|---|---|
| frontend / IR / generated runtime | `UniqueLongestChoiceAnnotation` / `LazyUniqueLongestChoice` | `UniqueLongestChoice` / `Expr::UniqueLongestChoice` |
| 最大消費長と同率拒否 | 対応 | 対応 |
| rollback / memo OFF・ON | cursor、選択、capture を復元 | cursor、CST、capture、scope、state を復元 |
| 2〜64 候補 / profile 混在拒否 | 生成前検証と runtime 上限 | lowering と runtime 上限 |
| 型名で宣言・式を判別 | 後続 #379 | 後続 #379 |
| 一般の左再帰を評価 | 非対応を明示拒否 | 非対応を明示拒否 |

full-input diagnostics は失敗候補の最遠地点を code-point offset で報告する。この地点で profile の期待値が残っている場合、同率は `ambiguity` / `unique longest alternative`、空一致は `empty_choice` / `nonempty unique longest alternative`、runtime の候補数違反は `choice_limit` / `2 to 64 unique longest alternatives` になる。より先まで進んだ別の失敗候補があれば、その最遠 syntax failure を優先する。prefix parse は拒否後 cursor を元の位置へ戻す。外側の ordered choice が別の候補で成功することは許す。成功した AST/CST に捨てた候補の選択や capture は残さない。

この profile は一般化された全候補列挙でも C++ parser でもない。意味状態が外部 effect に依存する custom parser は transaction/replay 契約を満たす必要がある。候補数は直接選択の上限であり、任意の文法全体の計算量を保証しない。

## 固定した C++ 例と対応境界

最小 corpus は [WG21 N4950 (2023-05-10, C++23 final working draft)](https://www.open-std.org/jtc1/sc22/wg21/docs/papers/2023/n4950.pdf) の stmt.ambig / temp.res を参照する（版の確認は [N4951 編集報告](https://open-std.org/JTC1/SC22/WG21/docs/papers/2023/n4951.html)）。型名 `T` を固定した `T(a);` は宣言と式の候補が同じ長さで成功するので、この段階では曖昧性を診断する。`T(a)++;` は式側の候補だけで成功する。C++ に規定された宣言優先と型名照会は、version を明示した read-only name snapshot profile の後続段階で扱う。未解決の型名、任意 callback による推測、template instantiation や完全な C++ grammar の対応を主張しない。

## 検証

`unlaxer-dsl/src/test/resources/unique-longest/{corpus,invalid}.json` に共通入力と独立期待値を固定する。Java 生成 parser、Java frontend 生成 Rust、Java を PATH から外した native Rust frontend の生成物5ファイルを照合する。Unicode cursor、full-input 診断位置・種別・期待値、選択 AST 全field/span、memo OFF/ON、外側 rollback、空一致、既存 longest の tie、混在設定、65候補、直接/間接左再帰を比較する。frontend canonical CST/source-span 試験には同じ新 annotation の positive fixture を追加する。CI は比較 TSV と surefire reports を保存する。

これは #422 の範囲であり、親 #379 の完了には名前 snapshot と左再帰の明示 profile、追加の制限・回帰検証が残る。
