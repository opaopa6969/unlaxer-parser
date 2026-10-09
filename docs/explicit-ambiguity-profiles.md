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
| 型名で宣言・式を判別 | 明示version snapshot / `@namePredicate` (#425) | 同じ分類 / rollback / 診断 (#425) |
| 一般の左再帰を評価 | 非対応を明示拒否 | 非対応を明示拒否 |

full-input diagnostics は失敗候補の最遠地点を code-point offset で報告する。この地点で profile の期待値が残っている場合、同率は `ambiguity` / `unique longest alternative`、空一致は `empty_choice` / `nonempty unique longest alternative`、runtime の候補数違反は `choice_limit` / `2 to 64 unique longest alternatives` になる。より先まで進んだ別の失敗候補があれば、その最遠 syntax failure を優先する。prefix parse は拒否後 cursor を元の位置へ戻す。外側の ordered choice が別の候補で成功することは許す。成功した AST/CST に捨てた候補の選択や capture は残さない。

この profile は一般化された全候補列挙でも C++ parser でもない。意味状態が外部 effect に依存する custom parser は transaction/replay 契約を満たす必要がある。候補数は直接選択の上限であり、任意の文法全体の計算量を保証しない。

## 固定した C++ 例と対応境界

最小 corpus は [WG21 N4950 (2023-05-10, C++23 final working draft)](https://www.open-std.org/jtc1/sc22/wg21/docs/papers/2023/n4950.pdf) の stmt.ambig / temp.res を参照する（版の確認は [N4951 編集報告](https://open-std.org/JTC1/SC22/WG21/docs/papers/2023/n4951.html)）。型名 `T` を固定した `T(a);` は宣言と式の候補が同じ長さで成功するので、この段階では曖昧性を診断する。`T(a)++;` は式側の候補だけで成功する。C++ に規定された宣言優先と型名照会の最小形は、[versionを明示した read-only name snapshot profile](versioned-name-snapshots.md) の ordered declaration/expression candidates で扱う。未解決の型名、任意 callback による推測、template instantiation や完全な C++ grammar の対応を主張しない。

## 検証

`unlaxer-dsl/src/test/resources/unique-longest/{corpus,invalid}.json` に共通入力と独立期待値を固定する。Java 生成 parser、Java frontend 生成 Rust、Java を PATH から外した native Rust frontend の生成物5ファイルを照合する。Unicode cursor、full-input 診断位置・種別・期待値、選択 AST 全field/span、memo OFF/ON、外側 rollback、空一致、既存 longest の tie、混在設定、65候補、直接/間接左再帰を比較する。frontend canonical CST/source-span 試験には同じ新 annotation の positive fixture を追加する。CI は比較 TSV と surefire reports を保存する。

unique-longest の初期範囲は #422、名前 snapshot は #425/#427、後段の境界 corpus と方式比較は #430 に対応する。親 #379 の完了確認はこれらの実装・CI・mergeをまとめて行う。

## 方式の選択と固定 corpus

C++23 N4950 の [expr.prim] / [expr.add] / [stmt.ambig] の最小形に対応する共通 fixture を `unlaxer-dsl/src/test/resources/ambiguity-boundaries/` に固定する。これは C++ 全体の文法を移植したものではなく、必要な選択を区別するための小さい文法である。

|固定形|方式|独立期待値 / 追跡|
|---|---|---|
|`(10)` の primary expression|既存の sequence / mapped rule|Group→Number、字面 `10`、CP span `[0,4)` / `[1,3)`|
|`10-3-2` の additive expression|直接左再帰を反復へ明示変換し既存 `@leftAssoc` を使う|Binary の left/op/right全field、Number各span、宣言順の演算子列|
|固定既知型 `T` の `T(a);` / `T(a)++;`|既存 ordered declaration/expression|前者はDeclaration、後者はExpression、両ASTの全field/span|
|二つの同長 candidate を持つ `T(a);`|`@uniqueLongestChoice`|同率を `ambiguity` として拒否、任意の勝者を保持しない（#422）|
|一般の名前 `T` / `U` と version別TYPE/VALUE|`@namePredicate` の限定snapshot判定|TYPE宣言、VALUE式、UNKNOWN明示拒否、version/rollback/memo整合（#425/#427）|

数値字面は `(NUMBER) @value` で TEXT capture とし、言語ごとの数値APIへの暗黙変換をこのfixtureに入れない。反復の AST は既存の演算子・右operand配列を維持し、後の evaluator がその配列を左から fold する。新profileの導入は既存 ordered / longest / predictive の選択順を変えない。

|検討方式|今回の採用 / 非対応|
|---|---|
|文法変換|通常の演算子左再帰は反復へ明示変換できるため採用。一般の直接/間接左再帰の自動変換はしない。|
|限定的な判定|immutable/versioned snapshot のTYPE/VALUE/resolved gateを採用。未解決をVALUEに仮定せず、provider I/Oや型推論を行わない。|
|複数候補保持|unique-longestは同じcheckpointから候補を試し、唯一の最大一致CSTだけをcommitする。同率診断に必要な長さは保持するが、全候補のAST forestは公開しない。|
|GLR / Earley / parse forest|一般曖昧文法や一般左再帰には別の方式が必要になる。既存の単一root/CST/mapper・rollback契約への変更が大きいため今回の方式には採用しない。|

## 深さ・空一致・資源の境界

共通 generated fixture は12段の nested unique maximum、12段先のUnicode同率失敗、64候補で最短/最長の唯一候補を照合する。65候補は生成前に拒否する。空一致だけの最大候補は失敗し、直接/間接左再帰・nullable prefix を通る左cycleは新profileの生成前に拒否する。nullable cycleの診断を得るために実行時の無限再帰やstack overflowを待たない。

|境界|Java|Rust|
|---|---|---|
|unique-longest候補数|2〜64、生成/runtime双方で拒否|同じ|
|名前 snapshot|64 snapshot、合計4096 names、各name256 CP|同じ|
|新profileの左再帰/nullable左cycle|生成前拒否|同じ|
|既存runtimeのrule/callback再帰数値制限|Rustと同じ256のglobal深度制限はない|rule nesting / parser calls 256の診断guard|
|Rust guardの既定stack検証|言語固有の制約として対応表へ記録|debug/releaseの通常test threadで診断、stack拡大なし|

候補数64は文法全体の時間・メモリの上限ではない。nested ambiguityは候補再試行によって増幅するため、共通12段stress corpusの成功を任意の入力に対する計算量保証へ広げない。snapshotはparse前に検証してコピーする読み取り専用データであり、外部検索や無制限の名前解決を実行しない。左再帰を評価する一般engine、完全C++のtemplates/type inference、全候補forestを必要とする文法は対応範囲外である。

親 #379 の受入確認は `unique-longest` と `ambiguity-boundaries` の独立AST/CST/CP診断corpus、`name-predicate` の生成corpus、`spec-corpus/name-snapshots/runtime.json` のversion/rollback/child境界、Rust既定stackguardの単体試験を合わせて行う。CIは共通TSVとsurefire reportsを保存する。全依存PRのCI/mergeを確認するまで親issueは閉じない。
