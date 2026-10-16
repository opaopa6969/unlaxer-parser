# 結合演算子の AST 投影（Java / Rust）

`@leftAssoc` / `@rightAssoc` の `left`・`op`・`right` は、既存 Java の共有 mapping schema に従って投影します。Rust の Java/native 両 source host は同じ型・値・原文 CP 範囲を生成します。結合方向と precedence の解析契約は変更しません。

```ubnf
@root @mapping(Binary,params=[left,op,right]) @leftAssoc @precedence(level=10)
Expr ::= Term @left { '-' @op Term @right };
@mapping(Binary,params=[left,op,right]) @leftAssoc @precedence(level=20)
Term ::= Factor @left { '/' @op Factor @right };
Factor ::= NUMBER | '(' Expr ')';
```

この共有 recursive schema は Java の `Binary left, List<String> op, List<Binary> right` に対応します。mapped operand を持たない literal は `Binary(null, [literal], [])` に包み、複数 precedence の演算子と同じ family を構成します。別の mapped operand が混ざる場合は、既存 Java の基底 AST 型と Rust の具体 `Ast` variant を保持します。宣言順を逆にしても同じ結果になります。

Rust は IR に `Kind::NullableNode`（Java host は `Kind.NULLABLE_NODE`）を追加します。`Cardinality::One` のまま、格納型は `Option<Box<Ast>>`、手書き evaluator の引数は `Option<&Ast>` です。これは Optional capture のコンテナではありません。実際に解析した演算子 node の left capture は必須で、`Some` を格納します。`None` は合成 literal leaf 専用です。literal leaf の op は一要素、right は空です。source span は operand の元 CP 半開区間を保持します。

低水準 IR validator は NullableNode を `left: NullableNode One, op: Text Many, right: Node Many` の結合演算子 mapping に限定します。Optional/Many の NullableNode、非結合 mapping、別 field/private typeof capture の NullableNode は生成前に拒否します。公開 Rust AST enum は caller が直接組み立てられるため、手書き consumer はこの不変条件を維持してください。

`@typeof` は空許容を Optional 型として比較せず、実 left operand の具体 Node 型を比較します。`@commonField(left)` の Rust accessor は格納型への `&Option<Box<Ast>>` を返します。自動 binary evaluator は合成 leaf を literal hook に渡し、実 node を従来の binary hook へ渡します。不正な合成 leaf 形は受理しません。DAP の AST traversal も合成 leaf を含めて元の field 順に歩きます。

単一 mapping の従来の scalar/Text operand、直接 mapped Node、および Object に相当する Text/Node 混在 operand は維持します。全ての結合演算子を synthetic leaf に変換するわけではありません。例えば単一 left-associative mapping の Text/自己 Node 混在は Java `Object` / Rust `AstValue` のままです。右結合で、単一の透明 operand 自体が自己 Node・別 Node・Text に到達する形は、Java の shared schema と同様に合成 leaf を使います。左右それぞれの到達集合を混同しません。例えば `Expr ::= Factor @left { '+' @op Expr @right }; Factor ::= 'a' | Leaf;` の左 operand は自己再帰しないため、Java `Object` / Rust `AstValue` のままです。

## Preferred snapshot と失敗

結合演算子に対する preferred snapshot の一律未対応判定を解除します。右結合の branch/fallback parser wrapper の違いを候補深さへ反映し、通常 mapper と preferred mapper は同じ意味値を返します。候補順・具体型・保持 source map・CP span・再選択・並行 mapping の契約は他の mapped rule と共通です。括弧内と右再帰の候補も対象です。構文失敗と typeof 変換失敗は区別し、後続の失敗で既存 snapshot は変化しません。

Java の結合 mapping + `@typeof` では補助型の未生成と spine operand の誤った generic 型判定を修正します。正常に生成できていた Java の AST record の field 型・コンストラクタは維持します。#631 の混在右結合では、従来 AST が右だけを基底型へ広げたため mapper の `List<Binary>` と一致せず、生成コードがコンパイルできませんでした。左右がそろって再帰的な fold の場合だけ広げるよう修正し、非再帰の混在左 operand を持つ場合の右は `List<Binary>` にそろえます。Rust の該当共有 literal spine を再生成する consumer は、従来の Text/`AstValue` operand 引数から nullable Node 引数へ移行が必要です。

## 検証範囲

`spec-corpus/associative-projection/cases.json` は Java/Rust 共通の独立期待値です。left/right、共有宣言順、literal/mixed/direct Node、括弧、Unicode、空白、typeof/commonField/autoEval の実 consumer を含みます。`AssociativeProjectionConformanceTest` は Java21 の実 compile/map/eval、両 host Rust 全ファイル一致、Rust1.85 の実 compile/map/eval、DAP traversal、生成前の共通拒否を検証します。低水準 IR の NullableNode 制限も Rust の試験に含みます。

```sh
mvn -pl unlaxer-common,unlaxer-dsl -am test \
  -Dtest=AssociativeProjectionConformanceTest -Dsurefire.failIfNoSpecifiedTests=false \
  -DrustConformance=true
cargo +1.85.0 test --locked --manifest-path rust/Cargo.toml \
  -p unlaxer-codegen -p unlaxer-generator
```

この変更は #593（親 #569）の結合演算子投影です。nested container（#583）と recursive helper（#582）は別の意味単位であり、#547 全体の完了を主張しません。

#631 の `MixedRightOperands` fixture は文字列と mapped leaf の左右順、右再帰の多段入力と末尾失敗を検証します。preferred depth は元 CST の branch/fallback 構造から決めています。演算子のない `x` の Leaf は深さ3、branchを持つ `x+a` では内部Chainが1つ加わるため深さ4です。右側Exprは深さ3ずつ増え、そのExprがbranchを持つかどうかも反映します。

## Literal の capture 識別（#669）

左結合の `left` / `op` / `right` は、生成 parser の capture binding を先に特定し、その範囲内から operand の parser 型を探します。同じ `WordParser`（tokenStream / lexical context では `Lexing.LiteralParser`）を使う別の literal や構造 delimiter を代用しません。例えば `'a' @left { '+' @op 'a' @right }` の `a+a` は、Java / Rust とも `left="a", op=["+"], right=["a"]` になります。従来 Java の `right=["+"]` は誤投影でした。共有 mapping の追加 rule にも同じ選択を適用します。

AST の field 型・コンストラクタ・source span の単位は変更しません。既存の右結合は capture binding による選択を維持し、Rust の mapper 意味も変更しません。通常 associative rule を nested mapped field から参照する経路は対象ですが、nested 所有 rule 自体の associative annotation を新たに許可する変更ではありません。

追加 oracle は直接 literal、同一 literal の別 capture、tokenStream、構造 delimiter、共有 mapping、precedence、補助文字 / CRLF、複数反復、右再帰、nested 参照を扱います。通常 mapper と preferred snapshot の値・候補 CP 範囲・保持値、構文失敗を同じ期待値で確認します。`PreferredAstConformanceTest` の共通 runner は Java / native host の Rust 全生成ファイル一致に加え、各出力を別々に `rustc` で compile/run します。DAP・合成 nullable leaf・mapped/mixed operand・commonField・typeof・autoEval は既存共通 corpus の回帰で検証します。

## 左結合の固定 delimiter（#674）

`Root ::= '(' NUMBER @left { '+' @op NUMBER @right } ')'` のような固定 delimiter を持つ左結合も、Java / native 両 Rust host で生成します。これは基準 Java `6816a2a6` で既に正常だった経路への対応です。Rust の canonical shape 制限を、両版共通の制約として扱い直したものではありません。

検査では、rule の直下 Sequence と、その唯一の無制限 Repeat の直下 Sequence から、capture を持たない**非空の固定 literal**だけを除いた形が `left { op right }` であることを要求します。検査用の列を作るだけで、実 IR・parser・CST から delimiter は取り除きません。prefix、left と Repeat の間、suffix、Repeat 内の op/right の前後に置いた固定 literal は入力として必要で、AST span と preferred source はその原文を保持します。`left` は scalar、`op` と `right` は順序付き List の既存 schema のままです。

Optional delimiter、空 literal、別の Repeat、一般的な helper/choice を挟む形は今回開放しません。これらは Rust の生成前診断を維持し、Java の全ての左結合文法と同等になったとは主張しません。非 canonical な右結合や nested-owner associative annotation も対象外です。

`spec-corpus/associative-delimiters` は NUMBER、literal、tokenStream、Unicode / CRLF、複数 precedence の mapped operand、非捕捉 delimiter、0/1/複数回反復と失敗を共有します。Java21 と両 host の Rust1.85 出力を実 compile/map/eval し、memo OFF / SAFE_FAILURES と診断 AUTO / DETAILED / DETAILED_ON_FAILURE の6組を検証します。CP失敗位置は Java の DETAILED で、構文失敗なら farthest、成功した prefix の後に余剰入力がある場合は consumed end を観測し、Rust の完全入力解析が返す位置と比較します。直接 ParseContext の deferred 診断に、完全入力 API の自動再試行を仮定しません。
