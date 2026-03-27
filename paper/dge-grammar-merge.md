# DGE Session: Priority 1 文法マージ (Boolean And/Or/Xor + Math関数 + Ternary + toNum)

## テーマ
tinyexpression-p4.ubnf (本番文法) に tinyexpression-p4-complete.ubnf の機能をマージする際の設計ギャップを発見する

## キャラクター
- ☕ ヤン・ウェンリー — 「要らなくない？」「最もシンプルな解は？」
- 🎩 千石武 — 「品質基準を示す」「ユーザーのために」
- ⚔ リヴァイ兵長 — 「汚い。動くもの見せろ。」
- 👤 今泉慶太 — 「そもそも」「誰が困るの」

## 対象
1. Boolean operator の 3 階層化 (Or | And & Xor ^)
2. Math 関数 (sin, cos, tan, sqrt, min, max, random)
3. Ternary 演算子 (condition ? then : else)
4. GGP concrete class への evalXxx メソッド追加 (48メソッド問題)
5. 移行リスクと後方互換性

## 先輩 (ナレーション)
production 文法 (`tinyexpression-p4.ubnf`) と complete 文法 (`tinyexpression-p4-complete.ubnf`) の差分をマージする。complete 文法では Boolean 演算子が 3 階層 (Or < And < Xor)、Math 関数 7 種、Ternary 演算子、toNum 関数、Not 演算子、String メソッド (toUpperCase, toLowerCase, trim) が追加されている。production 文法の BooleanExpression は現在フラットな Choice で、これを階層化する必要がある。

---

## Scene 1: Boolean operator の 3 階層マージ

先輩: 「complete 文法では BooleanExpression が 3 階層になっている。BooleanExpression (Or `|`) → BooleanAndExpression (`&`) → BooleanXorExpression (`^`) → BooleanFactor。production の BooleanExpression はフラットな Choice だから、これを置き換える。」

```
// complete 文法の構造:
BooleanExpression     ::= BooleanAndExpression { '|' BooleanAndExpression }
BooleanAndExpression  ::= BooleanXorExpression { '&' BooleanXorExpression }
BooleanXorExpression  ::= BooleanFactor { '^' BooleanFactor }
```

ヤン: 「要らなくない？ 一段階で十分じゃ？ tinyexpression のユーザーって業務ルールを書く人でしょ。`$a > 10 & $b < 20` は書くだろうけど、`$x | $y ^ $z` を書く人がいるとは思えない。紅茶もう一杯。」

千石: 「プログラミング言語として正しい優先順位を提供しないのは、ユーザーに対する不誠実です。`$a | $b & $c` と書いた時に `$a | ($b & $c)` と解釈されることを保証しなければ、いつか必ず事故が起きます。C, Java, JavaScript、全て同じ優先順位です。」

  → **Gap 発見: 演算子の優先順位テストケースが未定義。`$a | $b & $c` が `$a | ($b & $c)` になることを検証するテストが必要。**

リヴァイ: 「動くもの見せろ。complete 文法にはもう書いてあるんだろう。じゃあ MapperGenerator で生成して動かせ。どのパーサーが生成される？」

ヤン: 「BooleanOrExpr, BooleanAndExpr, BooleanXorExpr の 3 つの AST ノードが新たに生成される。問題は既存の BooleanExpr が消えること。」

今泉: 「そもそも、既存の BooleanExpr の @mapping はどうなるんですか？ production 文法では `@mapping(BooleanExpr, params=[value])` がついてますけど、complete 文法では `@mapping(BooleanOrExpr, params=[left, op, right])` に変わってますよね。params が `[value]` から `[left, op, right]` に変わるって、構造が全く違いません？」

  → **Gap 発見: BooleanExpr (params=[value]) → BooleanOrExpr (params=[left, op, right]) の構造変更。既存の evalBooleanExpr メソッドは value を 1 つ受け取る設計。新しい evalBooleanOrExpr は left/op/right を受け取る。既存コードの書き換え範囲が不明確。**

千石: 「BooleanExpression を参照している箇所を全て洗い出すべきです。VariableDeclaration の BooleanSetter、IfExpression の condition、BooleanMethodDeclaration の body、BooleanMatchExpression の condition と value。全てが BooleanExpression を参照しています。」

リヴァイ: 「参照箇所はそのままでいい。BooleanExpression という名前は変わらない、中身の構造が変わるだけだ。パーサーの名前が同じなら呼び出し側は変更不要だろう。」

ヤン: 「そうだね。BooleanExpression → BooleanAndExpression → BooleanXorExpression → BooleanFactor の連鎖は内部的なもの。外から見れば BooleanExpression のままだ。ただ...」

今泉: 「ただ？」

ヤン: 「production 文法で BooleanExpression の選択肢だったもの（BooleanMatchExpression, ExternalBooleanInvocation, StringComparisonExpression, ComparisonExpression, true, false, VariableRef, MethodInvocation）が、complete 文法では BooleanFactor に移動してる。しかも complete の BooleanFactor には NotExpression, IsPresentFunction, InTimeRangeFunction, InDayTimeRangeFunction, StringPredicateMethod, SideEffectBooleanExpression, `( BooleanExpression )` が追加されてる。」

  → **Gap 発見: BooleanFactor に追加される選択肢の順序が重要。PEG の ordered choice では先に書いた方が優先される。StringComparisonExpression と ComparisonExpression の順序、NotExpression の位置が parse 成功率に直結する。complete 文法の順序が最適か検証が必要。**

今泉: 「誰が困るの？ 順序が間違ってると？」

ヤン: 「例えば ComparisonExpression が StringComparisonExpression より前にあると、`$name == 'hello'` が NumberExpression の比較として parse されてしまう。$name を NumberExpression として parse しようとして、VariableRef で消費して、== でマッチするけど 'hello' が NumberExpression じゃないから失敗。バックトラックして StringComparisonExpression を試す...はずだけど、PEG のバックトラック範囲によっては再試行しない可能性がある。」

  → **Gap 発見: StringComparisonExpression と ComparisonExpression の ordered choice 順序と、PEG バックトラック範囲の関係。complete 文法では StringComparisonExpression が先、これが正しいか実テストで確認が必要。**

---

## Scene 2: Math 関数マージ

先輩: 「complete 文法では sin, cos, tan, sqrt, min, max, random の 7 関数が MathFunction として定義されている。それぞれ @mapping で SinExpr, CosExpr, TanExpr, SqrtExpr, MinExpr, MaxExpr, RandomExpr にマッピングされる。」

今泉: 「そもそも誰が sin 使うんですか？ tinyexpression って業務ルール DSL ですよね。『もし売上が sin(pi/4) を超えたら』なんて書きます？」

ヤン: 「使わない可能性は高いね。でも original の tinyexpression (Java ライブラリ) が Math 関数をサポートしてたから、互換性のために入れてある。紅茶の砂糖と一緒で、使わないかもしれないけどないと困る人がいる。」

千石: 「互換性を謳うなら全て入れるべきです。中途半端なサポートはユーザーへの裏切りです。」

リヴァイ: 「全部一度に入れろ。7 関数とも @mapping で SinExpr 等にマッピングするだけだろう。evalSinExpr は `Math.sin(evalArg(node.arg()))` の 1 行だ。簡単な作業を分割するな。」

今泉: 「他にないの？ sin とか cos じゃなくて、もっと業務でよく使う関数は？ abs とか round とか ceil とか floor とか。」

  → **Gap 発見: abs, round, ceil, floor, pow, log, exp が complete 文法にない。original tinyexpression がサポートしていた数学関数との対応表が必要。P1 に含めるか P2 送りか判断基準が未定義。**

ヤン: 「いい指摘だ。min と max は業務ルールでよく使う。random も A/B テストで使う。sin/cos/tan は...まあ、入れるのは簡単だから入れておけばいい。」

千石: 「MinFunction と MaxFunction は params=[left, right] で 2 引数固定ですが、`min(1, 2, 3)` のように可変長引数は対応しないのですか？」

  → **Gap 発見: min/max が 2 引数固定。min(a, b, c) のような 3 引数以上の呼び出しに対応していない。nested min(min(a,b),c) で代替できるが、ユーザビリティの観点で検討が必要。**

リヴァイ: 「2 引数で十分だ。ネストすればいい。可変長はパーサーの複雑さが跳ね上がる。」

今泉: 「要するに、Math 関数って全部 NumberExpression を受け取って NumberExpression を返すわけですよね。型の問題はないと？」

ヤン: 「random() は引数なしで 0.0-1.0 の double を返す。他は全部 Number → Number。型は単純。ただ...」

今泉: 「ただ？」

ヤン: 「sqrt(-1) の時どうする？ NaN を返す？ エラーにする？ tan(pi/2) は？ Infinity を返す？」

  → **Gap 発見: 数学関数のエッジケース処理が未定義。sqrt(負数) → NaN? エラー? tan(pi/2) → Infinity? Division by zero と同様のエラーハンドリングポリシーが必要。**

千石: 「NaN や Infinity がそのまま後続の計算に流れ込むのは危険です。比較演算子で NaN == NaN が false になる、NaN > 0 も false になる。ユーザーが意図しない挙動を引き起こします。」

  → **Gap 発見: NaN/Infinity の伝播ポリシーが未定義。Java の Math.sin 等はそのまま NaN/Infinity を返す。tinyexpression としてこれを許容するか、エラーに変換するか決定が必要。**

---

## Scene 3: Ternary 演算子

先輩: 「complete 文法では TernaryExpression が定義されている。`BooleanFactor '?' NumberExpression ':' NumberExpression`。NumberFactor の選択肢の一つとして追加される。」

```
@mapping(TernaryExpr, params=[condition, thenExpr, elseExpr])
TernaryExpression ::=
  BooleanFactor @condition '?' NumberExpression @thenExpr ':' NumberExpression @elseExpr ;
```

ヤン: 「if 式があるのに ternary 要るの？ `if ($a > 10) { 1 } else { 2 }` で同じことできるでしょ。」

千石: 「ternary は式の中にインラインで書けるのが価値です。`$base + ($discount ? $price * 0.9 : $price)` のように。if 式だと `$base + if($discount){ $price * 0.9 } else { $price }` になりますが、これは文法的に許容されていますか？」

今泉: 「そもそも if 式って NumberFactor に入ってますから、`$base + if(...)` は文法的には OK ですよね。じゃあ ternary は本当にシンタックスシュガーですか？」

ヤン: 「シンタックスシュガーだね。でも C/Java/JavaScript ユーザーにとっては `?:` の方が自然。まあ、入れるコストが低いなら入れてもいいんじゃないですか。」

リヴァイ: 「問題は別のところだ。complete 文法の TernaryExpression は NumberExpression しか返さない。`$flag ? 'yes' : 'no'` は書けない。」

  → **Gap 発見: TernaryExpression が NumberExpression 固定。StringExpression, BooleanExpression を返す Ternary が定義されていない。StringTernaryExpression, BooleanTernaryExpression が必要か？ それとも型多相な TernaryExpression にするか？**

千石: 「if 式は Expression を返すので型多相ですが、ternary が NumberExpression 固定というのは設計の一貫性を欠きます。」

今泉: 「誰が困るの？ 文字列の ternary が書けないと？」

ヤン: 「`$isVip ? 'Premium' : 'Standard'` を書きたいユーザーは確実にいる。if 式で代替できるから致命的ではないけど、ternary を入れる理由が『C ライクな表記』なら String 版がないのは片手落ち。」

リヴァイ: 「condition 側も問題だ。`BooleanFactor @condition` になってるが、これは `BooleanFactor` であって `BooleanExpression` じゃない。`$a | $b ? 1 : 0` と書いたとき、condition は `$a | $b` 全体じゃなくて `$a` だけになる。`|` が BooleanExpression レベルで、BooleanFactor は最内側だから。」

  → **Gap 発見: TernaryExpression の condition が BooleanFactor 限定。`$a & $b ? 1 : 0` や `$a | $b ? 1 : 0` を condition として使えない。BooleanExpression にすべきか、それとも括弧を強制すべきか (`($a | $b) ? 1 : 0`)。**

今泉: 「前もそうだったっけ？ C 言語の ternary って `?` の優先順位どうなってましたっけ？」

ヤン: 「C では ternary の優先順位はかなり低い。代入の次。だから `a || b ? x : y` は `(a || b) ? x : y` と解釈される。complete 文法の設計は BooleanFactor に限定してるから、`$a | $b ? 1 : 0` は `$a | (($b) ? 1 : 0)` になる。C とは違う挙動だ。」

  → **Gap 発見: Ternary の優先順位が C/Java と異なる。complete 文法では BooleanFactor 限定なので `$a | $b ? 1 : 0` → `$a | ($b ? 1 : 0)` となり、C の `($a | $b) ? 1 : 0` とは異なる。ユーザーの期待と合わない可能性。ドキュメントによる明示が最低限必要。**

千石: 「ternary の `:` と他の `:` の衝突はありませんか？ 将来 slice 記法 `expr[start:end]` が入ったときに曖昧さが出ませんか？」

ヤン: 「slice は StringExpression の話で、ternary は NumberFactor の中だから文法レベルでは衝突しない。ただ、将来 ternary を式全体に拡張したときに slice の `:` との区別が問題になるかもしれない。」

  → **Gap 発見: Ternary の `:` と Slice の `:` の将来的な衝突リスク。現状は文法スコープが異なるので問題ないが、拡張時に注意。**

---

## Scene 4: GGP concrete class 爆発 — 48 メソッド問題

先輩: 「新しい AST ノードは以下の通り: BooleanOrExpr, BooleanAndExpr, BooleanXorExpr, NotExpr, SinExpr, CosExpr, TanExpr, SqrtExpr, MinExpr, MaxExpr, RandomExpr, TernaryExpr, ToNumExpr。13 種類。GGP concrete class は P4TypedAstEvaluator, P4TypedJavaCodeEmitter, P4DefaultJavaCodeEmitter, P4TemplateJavaCodeEmitter の 4 つ。13 x 4 = 52 メソッド。」

リヴァイ: 「52 個の新しい evalXxx メソッドを 4 つのクラスに書くのか？ 汚い。」

ヤン: 「ちょっと待って。全部のクラスに全メソッドが必要なわけじゃない。@eval strategy=default みたいな仕組みがあれば、基底クラスにデフォルト実装を置いて、concrete class はオーバーライドが必要なものだけ書けばいい。」

今泉: 「そもそも @eval strategy=default って実装されてるんですか？」

ヤン: 「...まだだね。」

  → **Gap 発見: @eval strategy=default が未実装。これがないと全 concrete class に全メソッドを手書きする必要がある。マージ前にこの仕組みを実装すべきか、マージ後に refactor すべきか判断が必要。**

千石: 「デフォルト実装なしに 52 メソッドを手書きするのは、品質の維持が困難です。コピペによるバグが確実に発生します。」

リヴァイ: 「分解しろ。Math 関数の 7 つ (sin/cos/tan/sqrt/min/max/random) は全て同じパターンだ。evalSinExpr は `Math.sin(eval(node.arg()))` で、evalCosExpr は `Math.cos(eval(node.arg()))` で...テンプレで生成できるだろう。」

  → **Gap 発見: Math 関数の eval メソッドはテンプレート化できる。しかし現在の GGP コード生成にテンプレートパターンの仕組みがない。手書きするにしても、共通の evalMathFunction(node, Function<Double,Double> fn) のような共通メソッドを抽出すべき。**

今泉: 「P4TypedAstEvaluator と P4TypedJavaCodeEmitter って、同じ AST ノードに対して全く違う処理をするんですよね？ AstEvaluator は実行時に値を計算、JavaCodeEmitter は Java コードの文字列を生成。この 2 つは共通化できないんじゃ？」

ヤン: 「その通り。でもそれぞれの中では共通化できる。P4TypedAstEvaluator の中では evalSinExpr, evalCosExpr, evalTanExpr は全て `Math.xxx(evalNumberArg(node.arg()))` のパターン。P4TypedJavaCodeEmitter の中でも `"Math.xxx(" + emitExpr(node.arg()) + ")"` のパターン。」

リヴァイ: 「問題は Boolean 系だ。BooleanOrExpr, BooleanAndExpr, BooleanXorExpr の eval は似てるが演算子が違う。evalBooleanOrExpr は `left || right`、evalBooleanAndExpr は `left && right`、evalBooleanXorExpr は `left ^ right`。これは...」

千石: 「既存の evalBooleanExpr は value を 1 つ取る設計でした。新しい BooleanOrExpr は left/op/right を取ります。これは BinaryExpr と同じ構造です。既存の evalBinaryExpr のパターンを踏襲すべきです。」

  → **Gap 発見: BooleanOrExpr/AndExpr/XorExpr は BinaryExpr と同じ left/op/right 構造だが、BinaryExpr の eval は NumberExpression 前提で書かれている。Boolean 版の eval を BinaryExpr に統合するか、別メソッドにするか設計判断が必要。**

今泉: 「P4DefaultJavaCodeEmitter と P4TemplateJavaCodeEmitter の違いってなんですか？ どちらも Java コードを生成するんですよね？」

ヤン: 「Default は手続き的にコードを組み立てる。Template は Velocity か何かのテンプレートを使う。両方に同じ 13 メソッドを追加するのは確かに辛い。」

  → **Gap 発見: P4DefaultJavaCodeEmitter と P4TemplateJavaCodeEmitter の役割分担が不明確。同じ出力を 2 つの方式で生成している場合、新しいノード追加時のメンテナンスコストが倍になる。P1 では片方だけ対応して残りは P2 にすべきか？**

---

## Scene 5: 移行リスク — 後方互換性

今泉: 「既存のテスト壊れませんか？ BooleanExpression の内部構造が変わるんですよね。」

ヤン: 「AST の形が変わる。今まで `BooleanExpr(value=ComparisonExpr(...))` だったのが `BooleanOrExpr(left=BooleanAndExpr(left=BooleanXorExpr(left=ComparisonExpr(...))), right=null)` みたいになる。AST を直接触るテストは全部壊れる。」

  → **Gap 発見: BooleanExpr → BooleanOrExpr への変更で、AST を直接参照するテスト (AstEvaluatorTest, P4AstEvaluatorTest 等) が破壊される。影響範囲の調査と修正計画が必要。**

千石: 「テストだけではありません。evalBooleanExpr を呼び出している全てのコードパスが影響を受けます。P4TypedAstEvaluator.evalBooleanExpr は削除されて evalBooleanOrExpr に置き換わる。呼び出し元が明示的に evalBooleanExpr を呼んでいる箇所は全てコンパイルエラーになります。」

リヴァイ: 「コンパイルエラーなら見つかる。問題は実行時の挙動変化だ。`true` だけの BooleanExpression はどうなる？ 今までは `BooleanExpr(value=true)` だった。新しい構造では `BooleanOrExpr(left=BooleanAndExpr(left=BooleanXorExpr(left=true)))` になる。eval の連鎖が深くなる。」

  → **Gap 発見: 単純な `true`/`false` リテラルでも 3 階層のラッパーが発生する。BooleanOrExpr → BooleanAndExpr → BooleanXorExpr → BooleanFactor → `true`。eval の連鎖が 3 段深くなり、パフォーマンスへの影響とデバッグの複雑さが増す。最適化の検討が必要。**

今泉: 「前もそうだったっけ？ NumberExpression も NumberExpression → NumberTerm → NumberFactor の 3 階層ですよね。同じ問題は起きてないんですか？」

ヤン: 「いい指摘。NumberExpression は最初から 3 階層で、テストもそれ前提で書かれてる。だから問題ない。Boolean はフラットから 3 階層への変更だから、既存テストが壊れる。でも NumberExpression で問題ないなら、Boolean でも 3 階層のパフォーマンスは問題ないはずだ。」

千石: 「移行手順が重要です。以下の順序でないと中間状態でテストが全壊します。(1) complete 文法を production にコピー (2) MapperGenerator で再生成 (3) 新しい eval メソッドを全 concrete class に追加 (4) 旧 evalBooleanExpr を削除 (5) テスト修正。この 5 ステップのどこかで止まると、全テストが壊れた状態が長時間続きます。」

  → **Gap 発見: マージの原子性問題。文法変更 → コード生成 → eval 実装 → テスト修正の 4 ステップを 1 コミットで行うか、段階的に行うか。段階的に行う場合、中間状態でのテスト壊れを許容する戦略が必要。**

リヴァイ: 「ブランチを切れ。feature branch で全部やって、全テスト green になってから merge だ。中間状態なんか知ったことか。」

今泉: 「NotExpression と toNum も入るんですよね。NotExpression は `not(BooleanExpression)` で、toNum は `toNum(StringExpression, NumberExpression)`。これらの eval は別の concrete class に影響しますか？」

ヤン: 「NotExpression は Boolean 系。evalNotExpr は `!evalBoolean(node.value())` の 1 行。toNum は evalToNumExpr で `Double.parseDouble(evalString(node.value()))` に defaultValue のフォールバック。どちらもシンプルだけど、toNum は例外処理が必要。」

  → **Gap 発見: toNum の例外処理設計が未定義。`toNum('abc', 0)` で NumberFormatException が発生した場合に defaultValue を返すのか。null や空文字列の場合は？ `toNum('', 0)` → 0? `toNum(null, 0)` → 0? VariableRef が未設定の場合の挙動。**

千石: 「StringExpression の値が null になるケースを考慮しなければなりません。VariableRef が未設定 (isPresent = false) の場合、StringExpression の eval は null を返す可能性があります。toNum(null, default) は default を返すべきですが、これを明示しないとクラスごとに実装が割れます。」

  → **Gap 発見: VariableRef 未設定時の toNum の挙動。isPresent チェックなしで toNum を呼んだ場合の NullPointerException リスク。eval 内で null チェックを入れるか、文法レベルで isPresent を強制するか。**

今泉: 「要するに、この P1 マージで本当に安全なのは Math 関数だけってことですか？ Boolean 階層化は破壊的変更、Ternary は設計未決定、toNum は例外処理未定義...」

ヤン: 「...そうだね。Math 関数は既存を壊さない追加。Boolean 階層化は必要だけど破壊的。Ternary は入れるならちゃんと設計してから。toNum は例外ポリシーを決めてから。」

リヴァイ: 「優先順位を付けろ。Math 関数 → Boolean 階層化 → toNum → Ternary の順だ。一気にやるな。」

  → **Gap 発見: P1 の中の優先順位が未定義。全てを同時にマージするのか、サブフェーズに分けるのか。リスクの低い順（Math関数 → NotExpr → toNum → Boolean階層化 → Ternary）で段階的にマージすべき。**

---

## Gap リスト

| # | Gap | 発見 Scene | 重要度 | カテゴリ |
|---|-----|-----------|--------|---------|
| G-01 | 演算子の優先順位テストケースが未定義 (`$a \| $b & $c` の検証) | Scene 1 | High | テスト |
| G-02 | BooleanExpr → BooleanOrExpr の構造変更で params が [value] → [left, op, right] に変化。evalBooleanExpr の書き換え範囲不明 | Scene 1 | Critical | 実装 |
| G-03 | BooleanFactor の ordered choice 順序の妥当性検証。StringComparison vs Comparison の順序 | Scene 1 | High | 文法設計 |
| G-04 | abs, round, ceil, floor, pow, log, exp が complete 文法にない。original との対応表が必要 | Scene 2 | Medium | 文法設計 |
| G-05 | min/max が 2 引数固定。可変長引数の検討 | Scene 2 | Low | 文法設計 |
| G-06 | sqrt(負数), tan(pi/2) 等の数学関数エッジケース処理が未定義 | Scene 2 | Medium | 仕様 |
| G-07 | NaN/Infinity の伝播ポリシーが未定義 | Scene 2 | Medium | 仕様 |
| G-08 | TernaryExpression が NumberExpression 固定。String/Boolean 版が未定義 | Scene 3 | High | 文法設計 |
| G-09 | Ternary の condition が BooleanFactor 限定。複合条件 (`$a \| $b ? x : y`) の挙動が C/Java と異なる | Scene 3 | High | 文法設計 |
| G-10 | Ternary `:` と Slice `:` の将来的衝突リスク | Scene 3 | Low | 文法設計 |
| G-11 | @eval strategy=default が未実装。52 メソッド手書き問題 | Scene 4 | High | 実装 |
| G-12 | Math 関数 eval のテンプレート化/共通メソッド抽出が未設計 | Scene 4 | Medium | 実装 |
| G-13 | BooleanOr/And/XorExpr と BinaryExpr の構造統合の設計判断が必要 | Scene 4 | Medium | 設計 |
| G-14 | P4DefaultJavaCodeEmitter と P4TemplateJavaCodeEmitter の役割分担不明確。両方に対応するか片方のみか | Scene 4 | Medium | 設計 |
| G-15 | BooleanExpr → BooleanOrExpr 変更で AST 直接参照テストが破壊される | Scene 5 | Critical | テスト |
| G-16 | 単純な true/false でも 3 階層ラッパーが発生。パフォーマンスとデバッグ影響 | Scene 5 | Low | パフォーマンス |
| G-17 | マージの原子性問題。文法変更〜テスト修正を 1 コミットにするか段階的にするか | Scene 5 | High | プロセス |
| G-18 | toNum の例外処理設計 (NumberFormatException, null, 空文字列) | Scene 5 | High | 仕様 |
| G-19 | VariableRef 未設定時の toNum の NullPointerException リスク | Scene 5 | High | 仕様 |
| G-20 | P1 内のサブ優先順位が未定義。一括 vs 段階的マージ | Scene 5 | High | プロセス |

---

## Decision Record

### DR-01: Boolean 演算子は 3 階層にする
- **理由**: C/Java/JavaScript と同じ優先順位 (Or < And < Xor) を提供するため。ヤンの「1 段階で十分」は却下。千石の品質基準を採用。
- **影響**: BooleanExpr → BooleanOrExpr へのリネーム、既存テスト破壊
- **状態**: 採用 (complete 文法に既に設計済み)

### DR-02: Math 関数は 7 種全て P1 で入れる
- **理由**: 実装コストが低く、既存を壊さない純粋な追加。リヴァイの「簡単な作業を分割するな」を採用。
- **影響**: 7 つの AST ノード追加、各 concrete class に eval メソッド追加
- **状態**: 採用

### DR-03: Ternary は NumberExpression 固定で P1 に入れる (String/Boolean 版は P2)
- **理由**: complete 文法の設計をそのまま採用。String/Boolean 版は設計検討が必要なため P2 に先送り。
- **影響**: TernaryExpr の追加。condition の BooleanFactor 制限はドキュメントで明示。
- **状態**: 仮採用 (G-08, G-09 のレビュー待ち)

### DR-04: マージは feature branch で段階的に行う
- **理由**: リヴァイの「ブランチ切れ」+「一気にやるな」を採用。Math → Not → toNum → Boolean → Ternary の順。
- **影響**: 各ステップで CI green を確認してから次に進む
- **状態**: 採用

### DR-05: @eval strategy=default は P1 では実装しない
- **理由**: P1 のスコープをこれ以上広げない。52 メソッドは手書きで対応。Math 関数は共通ヘルパーメソッドで重複を減らす。
- **影響**: P2 で @eval strategy=default を実装し、P1 で追加したメソッドを refactor
- **状態**: 仮採用

---

## Action Items

### Priority: Critical (ブロッカー)

| # | Action | 関連 Gap | 担当 | 見積 |
|---|--------|---------|------|------|
| A-01 | BooleanExpr → BooleanOrExpr 変更の影響範囲調査 (evalBooleanExpr を呼んでいる全箇所を列挙) | G-02, G-15 | - | 2h |
| A-02 | AstEvaluatorTest, P4AstEvaluatorTest で BooleanExpr を直接参照するテストの一覧作成 | G-15 | - | 1h |
| A-03 | toNum の例外処理ポリシー決定 (null/空文字/不正文字列の振る舞い仕様書) | G-18, G-19 | - | 1h |

### Priority: High (P1 マージに必要)

| # | Action | 関連 Gap | 担当 | 見積 |
|---|--------|---------|------|------|
| A-04 | feature branch 作成 (`feature/p1-grammar-merge`) | G-17 | - | 5min |
| A-05 | Step 1: Math 関数 7 種を production 文法に追加 + MapperGenerator で再生成 + eval 実装 | G-12 | - | 4h |
| A-06 | Step 2: NotExpression を production 文法に追加 + eval 実装 | - | - | 1h |
| A-07 | Step 3: toNum を production 文法に追加 + eval 実装 (A-03 のポリシーに従う) | G-18 | - | 2h |
| A-08 | Step 4: Boolean 3 階層化 + BooleanFactor 導入 + eval 実装 + テスト修正 | G-02, G-15 | - | 8h |
| A-09 | Step 5: Ternary を production 文法に追加 + eval 実装 | G-08, G-09 | - | 3h |
| A-10 | Boolean 優先順位テストケース作成 (`$a \| $b & $c` 等) | G-01 | - | 2h |
| A-11 | BooleanFactor の ordered choice 順序検証テスト | G-03 | - | 2h |
| A-12 | Math 関数の eval 共通ヘルパーメソッド設計 (`evalUnaryMathFunction`, `evalBinaryMathFunction`) | G-12 | - | 1h |

### Priority: Medium (P1 中に判断、実装は P2 可)

| # | Action | 関連 Gap | 担当 | 見積 |
|---|--------|---------|------|------|
| A-13 | NaN/Infinity 伝播ポリシーの決定と文書化 | G-06, G-07 | - | 1h |
| A-14 | original tinyexpression の数学関数一覧との対応表作成 (abs, round 等の不足確認) | G-04 | - | 2h |
| A-15 | BooleanOr/And/XorExpr と BinaryExpr の構造統合可否検討 | G-13 | - | 2h |
| A-16 | P4DefaultJavaCodeEmitter / P4TemplateJavaCodeEmitter の P1 対応範囲決定 | G-14 | - | 1h |

### Priority: Low (P2 以降)

| # | Action | 関連 Gap | 担当 | 見積 |
|---|--------|---------|------|------|
| A-17 | String/Boolean 版 TernaryExpression の設計 | G-08 | - | 4h |
| A-18 | Ternary condition を BooleanExpression に拡張する設計検討 | G-09 | - | 2h |
| A-19 | min/max 可変長引数の検討 | G-05 | - | 2h |
| A-20 | @eval strategy=default の設計・実装 | G-11 | - | 8h |
| A-21 | 3 階層ラッパーの最適化検討 (不要な中間ノード除去) | G-16 | - | 4h |
