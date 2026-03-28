# DGE Session: Ternary 演算子の設計 — バグの温床を事前に潰す

## テーマ
ternary 演算子 `condition ? then : else` を tinyexpression に追加すべきか。追加する場合、パーサーの曖昧性、ネスト、match 構文との衝突、型の直交性、短絡評価の一貫性をどう設計するか。Creator の恐怖「ternary はバグの温床」を正面から受け止め、全ての地雷を事前に可視化する。

## キャラクター
- ☕ ヤン・ウェンリー — 「要らなくない？」「最もシンプルな解は？」
- 🎩 千石武 — 「品質基準を示す」「ユーザーのために」
- ⚔ リヴァイ兵長 — 「汚い。動くもの見せろ。」
- 👤 今泉慶太 — 「そもそも」「誰が困るの」

## 前提条件
- 後方互換性は最重要前提（月10億トランザクション本番稼働中）
- 既存の if 式: `if($a>0){$a}else{-$a}`
- 提案する ternary: `$a>0 ? $a : -$a`
- 直交性原則: ternary は全型（number, string, boolean, object）で動作すべき
- 先行 DGE (dge-string-methods) で G-08 が NumberExpression のみを提案 — これは直交性違反として却下済み
- String ドットチェーンが無制限（型駆動）になった — ternary はこれと合成可能でなければならない
- 5バックエンド体制: ast-evaluator, compile-hand (javac), P4 系3種

## 先輩 (ナレーション)
tinyexpression の Creator は ternary 演算子を「怖い」と明言した。怖さには理由がある。C 言語の ternary は50年の歴史の中で数え切れないバグを生んできた。パーサーの優先順位、ネストの曖昧性、型の不一致。しかし同時に、ternary がない式言語は冗長になる。`if($a>0){$a}else{-$a}` は21文字、`$a>0?$a:-$a` は11文字。式言語にとってこの差は大きい。怖いからやらないのか、怖いから正しく設計するのか。この DGE はその判断材料を揃えるために行う。

---

## Scene 1: そもそも要るのか

今泉: 「そもそも if 式があるのに ternary 要るんですか？ if で全部書けるんでしょう？ ユーザーに聞いたんですか？」

ヤン: 「聞いてないだろうね。ただ、式言語のユーザーは簡潔さを求める。if 式と ternary の比較を見てみよう。」

```
// 絶対値
if($a > 0){ $a }else{ -$a }     // 30文字
$a > 0 ? $a : -$a               // 18文字

// デフォルト値
if($x == null){ 0 }else{ $x }   // 30文字
$x == null ? 0 : $x             // 16文字

// 条件付き文字列
if($lang == 'ja'){ 'こんにちは' }else{ 'hello' }   // 41文字
$lang == 'ja' ? 'こんにちは' : 'hello'              // 30文字
```

今泉: 「要するに、ternary は syntax sugar ですよね。セマンティクスは if 式と同じですか？ それとも何か違いがあるんですか？」

ヤン: 「セマンティクスは完全に同一にすべきだね。`condition ? then : else` は `if(condition){then}else{else}` と同じ AST を生成する。違いはシンタックスだけ。」

千石: 「セマンティクスが同一であれば、テストも同一のケースで回せます。ternary で書いた式と if で書いた式の評価結果が一致することを保証できる。これは品質保証上、非常に有利です。」

  → **Gap 発見 (G-01): ternary と if 式が同じ AST を生成するか、別の AST ノードを持つかが未定義。同じ AST (IfExpression) にすれば Evaluator の変更がゼロだが、エラーメッセージで「ternary の else 部分」と「if の else 部分」を区別できなくなる。**

今泉: 「他にないの？ ternary じゃなくて、もっと安全な短縮記法は。例えば Kotlin の `if` は式だから `val x = if (a > 0) a else -a` って書ける。tinyexpression の if も式なんでしょう？ なら中括弧を省略可能にするだけでいいんじゃないですか？」

  → **Gap 発見 (G-02): ternary 以外の代替案が未検討。(A) 中括弧省略 if: `if($a>0) $a else -$a`、(B) ternary: `$a>0 ? $a : -$a`、(C) 何もしない（if 式のまま）。代替案の比較が必要。**

ヤン: 「中括弧省略は新しいパーサー曖昧性を生む。`if($a>0) $a + 1 else $b` の `+ 1` は then 部分か、if 式全体への加算か。ternary は `?` と `:` が明確な区切りだから、実はこっちの方がパースしやすい面もある。」

リヴァイ: 「議論はいい。で、ユーザーは何人が ternary を要望したんだ？ ゼロか？」

  → **Gap 発見 (G-03): ternary に対するユーザー要望の実績データがない。需要が不明なまま設計を進めるリスク。**

---

## Scene 2: パーサーの恐怖 — 優先順位

千石: 「具体的な式を見ましょう。`$a + $b > 0 ? 1 : 2` のパース結果は何ですか？」

```
解釈 A: ($a + $b > 0) ? 1 : 2      // 条件全体が ternary の condition
解釈 B: $a + ($b > 0 ? 1 : 2)      // $b > 0 ? 1 : 2 が加算の右辺
解釈 C: ($a + $b) > (0 ? 1 : 2)    // 0 ? 1 : 2 が比較の右辺
```

ヤン: 「C 言語では ternary の優先順位は代入の次に低い。つまり解釈 A が正解。ほぼ全ての演算子より ternary が低い。」

今泉: 「そもそも、tinyexpression に代入演算子はないですよね？ じゃあ ternary が最低優先順位ですか？」

千石: 「ただし、tinyexpression には `,` が引数区切りとして存在します。`func($a > 0 ? 1 : 2, $b)` の場合、`,` は関数引数の区切りであって ternary の一部ではない。パーサーはこれを正しく区別できますか？」

  → **Gap 発見 (G-04): ternary の優先順位が未定義。全演算子の中でどの位置に入れるか。特に Boolean 演算子 (`&`, `|`) との優先順位関係が重要。`$a > 0 & $b > 0 ? 'yes' : 'no'` は `($a > 0 & $b > 0) ? 'yes' : 'no'` か `$a > 0 & ($b > 0 ? 'yes' : 'no')` か。**

リヴァイ: 「文法を見せろ。PEG で書け。」

```
// 提案: ternary を式の最上位に置く
Expression      ::= TernaryExpr
TernaryExpr     ::= OrExpr ('?' Expression ':' Expression)?
OrExpr          ::= AndExpr ('|' AndExpr)*
AndExpr         ::= CompareExpr ('&' CompareExpr)*
CompareExpr     ::= AddExpr (CompOp AddExpr)?
AddExpr         ::= MulExpr (('+' / '-') MulExpr)*
MulExpr         ::= UnaryExpr (('*' / '/') UnaryExpr)*
UnaryExpr       ::= ('!' / '-')? PrimaryExpr
PrimaryExpr     ::= Number / String / Boolean / Variable / '(' Expression ')' / IfExpr / ...
```

ヤン: 「PEG は順序付き選択だから、曖昧性は文法の記述順で解決される。`TernaryExpr` を最上位に置けば、`?` は全ての二項演算子より低い優先順位になる。右辺の `Expression` は再帰的に `TernaryExpr` を許すから、ネストも自然に処理される。」

千石: 「右辺が `Expression` ということは、then 部分も else 部分も任意の式が入る。`$a ? $b + 1 : $c * 2` は問題ない。ただし `$a ? $b ? 1 : 2 : 3` のネストも許してしまう。」

  → **Gap 発見 (G-05): TernaryExpr の then 部分と else 部分に Expression（再帰的に TernaryExpr を含む）を許すと、ネスト ternary が文法的に合法になる。これを文法レベルで禁止するか、パーサーは許して lint / 静的解析で警告するか。**

---

## Scene 3: ネストの地獄

リヴァイ: 「`$a ? $b ? 1 : 2 : 3` を見せろ。パースツリーを書け。」

```
// 右結合（C 言語と同じ）
$a ? ($b ? 1 : 2) : 3

// パースツリー:
TernaryExpr
  condition: $a
  then: TernaryExpr
    condition: $b
    then: 1
    else: 2
  else: 3
```

ヤン: 「PEG で `TernaryExpr ::= OrExpr ('?' Expression ':' Expression)?` と書くと、then 部分の `Expression` が再び `TernaryExpr` にマッチする。つまり自然に右結合になる。C 言語と同じ動作だ。」

今泉: 「でも、人間はこれを正しく読めますか？ `$a ? $b ? 1 : 2 : 3` を見て、どの `:` がどの `?` に対応するか、即座にわかりますか？」

千石: 「わかりません。そしてわからないコードは品質の敵です。ユーザーが書いたつもりの意味と実際のパース結果が異なる可能性がある式を、本番で許容すべきではありません。」

リヴァイ: 「ネスト禁止か、括弧強制か。どっちだ。」

```
// 案 A: ネスト禁止（文法レベルで弾く）
TernaryExpr ::= OrExpr ('?' OrExpr ':' OrExpr)?
  → then/else に OrExpr を置くことで、ternary のネストを文法的に不可能にする
  → ネストしたければ括弧を使う: $a ? ($b ? 1 : 2) : 3

// 案 B: ネスト許可だが括弧を推奨（lint で警告）
TernaryExpr ::= OrExpr ('?' Expression ':' Expression)?
  → パーサーは通すが、ネスト検出時に warning を出す

// 案 C: then 部分はネスト禁止、else 部分は許可
TernaryExpr ::= OrExpr ('?' OrExpr ':' Expression)?
  → $a ? 1 : $b ? 2 : 3 は OK（if-else if チェーン的に使える）
  → $a ? $b ? 1 : 2 : 3 は NG
```

今泉: 「そもそも、ネスト ternary が必要な場面ってあるんですか？ if 式があるなら、複雑な条件は if で書けばいいんじゃないですか？」

ヤン: 「いい指摘だ。ternary は simple case 専用。複雑な分岐は if 式か match 式を使う。そう割り切れば、ネスト禁止は自然な制約だ。」

  → **Gap 発見 (G-06): ネスト ternary の扱い。禁止/許可/条件付き許可のどれを選ぶか。案 A（文法レベル禁止）は安全だが表現力を制限する。案 C（else のみ許可）は if-else if チェーンパターンを許すが複雑。**

千石: 「案 A を推します。ネストが必要な場面は if 式で書けばいい。ternary は『1行で書ける簡潔な条件分岐』という役割に限定すべきです。明確な制約が品質を守ります。」

リヴァイ: 「案 A 一択。then と else に OrExpr を置け。ネストしたきゃ括弧で包め。括弧で包んだ場合は PrimaryExpr 経由で Expression に入るから、文法的に自然に許可される。」

```
// 案 A でもネストは書ける（括弧必須）
$a ? ($b ? 1 : 2) : 3     // OK: ($b ? 1 : 2) は PrimaryExpr '(' Expression ')'
$a ? $b ? 1 : 2 : 3       // NG: パースエラー
```

  → **Gap 発見 (G-07): 案 A（then/else を OrExpr に制限）を採用した場合、括弧付きネストは PrimaryExpr '(' Expression ')' 経由で動作するか。パーサーの検証が必要。**

---

## Scene 4: match との衝突

今泉: 「match の中に ternary があったらどうなるんですか？ 書いてみますね。」

```
match{
  $x > 0 -> $x > 1 ? 'high' : 'low',
  default -> 'none'
}
```

ヤン: 「パーサーの視点で考えよう。`->` の右辺がどこまでかが問題だ。」

```
// match case の構造
MatchCase ::= Condition '->' Value (',' MatchCase)*

// Value の終端は ',' か '}'
// つまり Value に ternary が入ると:
$x > 1 ? 'high' : 'low' ,
         ^^^^^^^^^^^^^^^^
// パーサーは 'low' の後の ',' を見て Value の終端と判断できるか？
```

千石: 「`->` の右辺を Expression とすれば、ternary は Expression の一部として丸ごとパースされます。`,` は Expression の一部ではないので、区切りとして正しく認識されるはずです。」

今泉: 「でも、ternary の else 部分にカンマが含まれる場合は？ 例えば関数呼び出し。」

```
match{
  $x > 0 -> $x > 1 ? func($a, $b) : func($c, $d),
  default -> 0
}
```

ヤン: 「`func($a, $b)` の中のカンマは `(` `)` で囲まれているから、パーサーは match の区切りとは認識しない。PEG パーサーは括弧のネストを追跡するからね。」

リヴァイ: 「それは実装依存の話だ。文法として保証しろ。」

  → **Gap 発見 (G-08): match case の value に ternary が入った場合のパース規則。`->` の右辺を Expression として扱えば ternary を含められるが、Expression の終端判定（`,` か `}` で区切る）が ternary の内部構造と衝突しないことを文法レベルで証明する必要がある。**

今泉: 「前もそうだったっけ？ if 式は match の中に入れられるんですか？」

```
match{
  $x > 0 -> if($x > 1){ 'high' }else{ 'low' },
  default -> 'none'
}
```

ヤン: 「if 式は `{` `}` で明確に区切られるから曖昧性がない。ternary は区切り文字が `:` だけで、開始・終了が対称じゃない。これが if 式より本質的に危険な理由だね。」

  → **Gap 発見 (G-09): if 式は `{}` で明確にスコープが区切られるが、ternary の `?` `:` は開き/閉じのペアではない。これが match, 関数引数, 括弧内など様々なコンテキストで曖昧性を生む根本原因。ternary を「括弧なし」で許可する場合、全てのコンテキストで曖昧性がないことの網羅的検証が必要。**

---

## Scene 5: 型の直交性

今泉: 「`$flag ? 1 : 'hello'` って何型ですか？ number ですか？ string ですか？」

千石: 「型が混在する ternary は危険です。compile-hand (javac) では、Java の三項演算子は型の互換性をコンパイル時にチェックします。`int` と `String` を混ぜたらコンパイルエラーです。」

ヤン: 「でも tinyexpression は動的型付けの側面もある。ast-evaluator は実行時に値を解決するから、`$flag ? 1 : 'hello'` は動く。flag が true なら number の 1、false なら string の 'hello' が返る。」

リヴァイ: 「5バックエンドで動作が違ったら終わりだ。」

```
// 5バックエンドでの $flag ? 1 : 'hello' の動作予測

ast-evaluator:     動く。動的型。結果は flag 次第で number or string。
compile-hand:      javac が型チェック。Object にアップキャスト？ それともエラー？
P4-ast-evaluator:  ast-evaluator と同じ動作のはず。
P4-dsl-java-code:  compile-hand と同じ制約があるはず。
P4-transpile:      ターゲット言語の型システムに依存。
```

  → **Gap 発見 (G-10): ternary の then/else で型が異なる場合の動作が5バックエンドで一致するか不明。ast-evaluator は動的型で通すが、compile-hand は javac の型システムに制約される。全バックエンドで同一の型ルールを強制するか、バックエンド固有の制約を許容するか。**

今泉: 「そもそも、先行 DGE で G-08 が ternary を NumberExpression だけに制限しようとしたのは、この問題を回避するためですよね。でも直交性原則でそれは却下された。」

千石: 「却下は正しい判断です。NumberExpression だけでは `$flag ? 'yes' : 'no'` が書けない。文字列の条件分岐こそ実業務で最も多いユースケースです。」

ヤン: 「整理しよう。直交性を守る場合の型ルール案。」

```
// 案 A: 型チェックなし（完全動的）
$flag ? 1 : 'hello'         → OK。結果型は実行時に決定。
$flag ? $obj : 42            → OK。何でもあり。

// 案 B: then/else の型一致を強制
$flag ? 1 : 'hello'         → エラー: number と string は混在不可
$flag ? 1 : 2               → OK: 両方 number
$flag ? 'yes' : 'no'        → OK: 両方 string

// 案 C: 型の上方変換（widening）
$flag ? 1 : 'hello'         → OK: 結果型は Object（任意型）
$flag ? 1 : 2               → OK: 結果型は number
$flag ? 'yes' : 'no'        → OK: 結果型は string
```

  → **Gap 発見 (G-11): ternary の型ルール。案 A は実装が簡単だが型安全性がない。案 B は厳格だがユーザーの自由度を制限する。案 C は中間だが「Object 型」の扱いが5バックエンドで異なる可能性がある。どの案を採用するか。**

今泉: 「誰が困るの？ 型が混在して。」

ヤン: 「ternary の結果を次の演算に使う場合に困る。`($flag ? 1 : 'hello') + 10` は number + number にしたいが、else が 'hello' のときに string + number になる。これは実行時エラーか、暗黙変換か。」

  → **Gap 発見 (G-12): ternary の結果を後続の演算に使う場合の型整合性。`($flag ? 1 : 'hello') + 10` や `($flag ? 'a' : 'b').toUpperCase()` のパースと型チェックのタイミング（パース時 / コンパイル時 / 実行時）が未定義。**

---

## Scene 6: `:` の衝突

千石: 「`:` は将来の slice 構文 `$str[0:3]` と衝突しませんか？」

ヤン: 「コンテキストで区別できるかを考えよう。」

```
// ternary の :
$a > 0 ? 1 : 2              // ? の後の : は ternary の区切り

// 将来の slice の :
$str[0:3]                   // [ ] の中の : は slice の区切り

// 組み合わせ
$flag ? $str[0:3] : $str[4:7]   // ternary の中に slice
```

千石: 「`[` `]` の中にいるかどうかで `:` の意味が変わる。PEG パーサーはコンテキストを追跡するから、技術的には区別可能です。ただし、実装の複雑さが増します。」

リヴァイ: 「今 slice はないんだろう。存在しないものとの衝突を心配するのは時間の無駄だ。」

今泉: 「でも前もそうだったっけ？ 後から追加しようとして既存の文法と衝突して苦労した経験はないんですか？」

  → **Gap 発見 (G-13): `:` のコンテキスト依存パース。現時点では ternary の `:` だけだが、将来 slice `$str[0:3]`、dict リテラル `{key: value}`、名前付き引数 `func(name: value)` などで `:` を使う可能性がある。これらが共存可能かの先行検証が必要。**

ヤン: 「紅茶を入れながら考えたけど、`:` の衝突は PEG では問題にならない。PEG は文法ルールの適用順序が明確だから、`[` の中にいれば slice の `:` として、`?` の後にいれば ternary の `:` として解釈される。同じ文字が複数の意味を持つのは、PEG では普通のことだ。`-` が減算と単項マイナスの両方を持つのと同じ。」

千石: 「ただし、エラーメッセージの品質は下がります。`:` が出現したときに、パーサーがどの文脈を期待していたかをユーザーに正しく伝える必要があります。」

  → **Gap 発見 (G-14): `:` に関するパースエラー時のエラーメッセージ品質。`$a ? 1 2` のように `:` を書き忘れた場合、「ternary の `:` が見つかりません」と出せるか。**

---

## Scene 7: 短絡評価

ヤン: 「短絡評価の話をしよう。compile-hand では javac が `?:` を if-else のバイトコードに変換するから、自動的に短絡評価される。ast-evaluator はどうする？」

```
// 副作用のある式（例示のため。tinyexpression に副作用があるかは別問題）
$flag ? heavyCalc($a) : heavyCalc($b)

// 短絡あり: $flag が true なら heavyCalc($b) は実行されない
// 短絡なし: 両方とも実行されて、$flag に基づいて結果を選ぶ
```

今泉: 「そもそも、tinyexpression の式に副作用はあるんですか？」

ヤン: 「純粋な式言語なら副作用はない。ただし、パフォーマンスには影響する。`$flag ? $price * 1.1 : $price * $complexDiscount` で、else 部分に重い計算が入っている場合、$flag が true なら else を評価しないことでパフォーマンスが改善する。」

千石: 「パフォーマンスの差異もバグの一種です。あるバックエンドでは1ミリ秒、別のバックエンドでは100ミリ秒。月10億トランザクションでこの差は致命的です。」

  → **Gap 発見 (G-15): 短絡評価の一貫性。compile-hand は javac 経由で自動短絡。ast-evaluator は実装次第。5バックエンド全てで短絡評価を保証するか、「短絡は保証しない（パフォーマンスヒントに過ぎない）」とするか。**

リヴァイ: 「if 式は短絡評価してるのか？」

ヤン: 「if 式は then ブロックと else ブロックが明確に分かれているから、AST traversal でも片方しか評価しない。これは短絡評価というより、制御フローそのもの。」

リヴァイ: 「なら ternary も同じにしろ。if と同じ AST を生成するなら、短絡も自動的に同じ動作になる。」

  → **Gap 発見 (G-16): ternary が if 式と同じ AST（IfExpression）を生成するなら、短絡評価は if 式と同一の動作が自動的に保証される。これは G-01（同じ AST か別 AST か）の決定に依存する。同じ AST にする最大の利点はここにある。**

---

## Scene 8: テストケース設計

リヴァイ: 「怖いなら テストを書け。20ケース以上。全部ここで設計しろ。」

千石: 「テストは以下のカテゴリに分けます。」

```
=== カテゴリ 1: 基本動作 ===
T-01: true ? 1 : 2                        → 1
T-02: false ? 1 : 2                       → 2
T-03: true ? 'yes' : 'no'                 → 'yes'
T-04: false ? 'yes' : 'no'                → 'no'
T-05: true ? true : false                 → true
T-06: false ? true : false                → false

=== カテゴリ 2: 変数 ===
T-07: $flag ? $a : $b                     → ($flag=true, $a=10, $b=20 → 10)
T-08: $flag ? $a : $b                     → ($flag=false, $a=10, $b=20 → 20)

=== カテゴリ 3: 比較条件 ===
T-09: $x > 0 ? 'positive' : 'non-positive'   → ($x=5 → 'positive')
T-10: $x > 0 ? 'positive' : 'non-positive'   → ($x=-3 → 'non-positive')
T-11: $x == 0 ? 'zero' : 'nonzero'           → ($x=0 → 'zero')

=== カテゴリ 4: 演算子優先順位 ===
T-12: $a + $b > 0 ? 1 : 2                → ($a=3,$b=2 → 1) // ($a+$b)>0 が condition
T-13: 1 + (true ? 2 : 3)                 → 3               // 括弧内 ternary
T-14: $a > 0 & $b > 0 ? 'both' : 'not'   → ($a=1,$b=1 → 'both')

=== カテゴリ 5: Boolean 演算子組み合わせ ===
T-15: $a > 0 | $b > 0 ? 'any' : 'none'   → ($a=-1,$b=1 → 'any')
T-16: !$flag ? 'inverted' : 'normal'     → ($flag=false → 'inverted')

=== カテゴリ 6: ネスト（括弧あり） ===
T-17: $a ? ($b ? 1 : 2) : 3              → ($a=true,$b=true → 1)
T-18: $a ? ($b ? 1 : 2) : 3              → ($a=true,$b=false → 2)
T-19: $a ? ($b ? 1 : 2) : 3              → ($a=false,$b=true → 3)

=== カテゴリ 7: ネスト（括弧なし → エラー期待。案A採用時） ===
T-20: $a ? $b ? 1 : 2 : 3                → パースエラー

=== カテゴリ 8: 型の直交性 ===
T-21: true ? 42 : 0                       → 42 (number)
T-22: true ? 'hello' : 'world'            → 'hello' (string)
T-23: true ? true : false                 → true (boolean)
T-24: true ? $obj : $obj2                 → $obj (object)

=== カテゴリ 9: match 内での ternary ===
T-25: match{ $x > 0 -> $x > 1 ? 'high' : 'low', default -> 'none' }
      → ($x=5 → 'high'), ($x=0 → 'none')

=== カテゴリ 10: if 式との等価性 ===
T-26: ($a > 0 ? $a : -$a) == if($a > 0){ $a }else{ -$a }  → true (全 $a)

=== カテゴリ 11: ドットチェーンとの合成 ===
T-27: ($flag ? 'hello' : 'world').toUpperCase()   → ($flag=true → 'HELLO')
T-28: $flag ? $name.toUpperCase() : $name.toLowerCase()  → 型駆動チェーン

=== カテゴリ 12: エッジケース ===
T-29: true ? 0 : 1                        → 0 (then が falsy number)
T-30: false ? 0 : ''                      → '' (else が空文字列)
```

  → **Gap 発見 (G-17): テストケース T-27 `($flag ? 'hello' : 'world').toUpperCase()` は ternary の結果にドットチェーンを適用する。ternary の結果が PrimaryExpr ではないため、括弧で囲まないとドットチェーンが適用できない可能性がある。パーサーの優先順位との整合性を検証する必要がある。**

  → **Gap 発見 (G-18): テストケース T-20（括弧なしネスト）がパースエラーになることを保証するテスト。エラーメッセージの内容も検証すべき。「ternary のネストには括弧が必要です」のような有用なメッセージが出るか。**

今泉: 「30ケースありますね。これ全部を5バックエンドで通すんですか？」

リヴァイ: 「当然だ。5バックエンド x 30ケース = 150テスト。全部 green にしろ。」

  → **Gap 発見 (G-19): 5バックエンド x 30ケース = 150テスト。テストの実装コストと、バックエンド間の結果一致をどう自動検証するか。共通テストスイートから5バックエンド用テストを自動生成する仕組みが必要か。**

---

## Scene 9: MVP と段階的導入

今泉: 「全部やるんじゃなくて、段階的に入れられませんか？ 全部一気にやると怖いです。Creator が怖いって言ってるんですから。」

ヤン: 「段階を考えよう。」

```
Phase 1 (MVP): 括弧推奨 ternary
  - 文法: TernaryExpr ::= OrExpr ('?' OrExpr ':' OrExpr)?
  - ネスト: 括弧なしネストはパースエラー
  - 型: then/else は任意型（型チェックなし。案 A）
  - 短絡: if 式と同じ AST を生成（自動短絡）
  - テスト: 基本30ケース（T-01 ~ T-30）を ast-evaluator でのみ実施
  - match 内: 許可（Expression として）
  - scope: ast-evaluator のみ

Phase 2: 全バックエンド対応
  - compile-hand, P4 系3バックエンドに展開
  - 150テスト green 確認
  - 型混在時の動作を5バックエンドで統一

Phase 3: 型安全強化（オプション）
  - then/else の型一致を静的チェック（案 B）に移行
  - warning → error の段階的移行
  - ドットチェーンとの合成テスト
```

千石: 「Phase 1 の scope を ast-evaluator のみに限定するのは賢明です。最もリスクの低いバックエンドで実証してから横展開する。」

今泉: 「誰が困るの？ Phase 1 で ast-evaluator だけにすると。」

ヤン: 「本番で使えない。compile-hand が本番のメインバックエンドなら、Phase 2 まで本番投入できない。ただし、Phase 1 で設計の妥当性を検証できれば、Phase 2 は機械的作業になる。」

リヴァイ: 「Phase 1 を最小にしろ。ternary を足したことで既存の if 式が壊れないことを証明するのが Phase 1 の本当の目的だ。」

  → **Gap 発見 (G-20): ternary 追加による既存 if 式のパース回帰リスク。TernaryExpr を Expression の最上位に挿入することで、既存の IfExpr のパースパスが変わる可能性。既存テストスイートの全 green を Phase 1 の完了条件に含めるべき。**

  → **Gap 発見 (G-21): Phase 1 の完了条件（Exit Criteria）が未定義。(a) 新規30テスト green、(b) 既存テスト全 green、(c) パフォーマンス劣化なし、のどれを必須とするか。**

今泉: 「他にないの？ Phase の分け方。例えば、Phase 1 で ternary じゃなくて if 式の短縮記法（中括弧省略）を入れる手もありますよね。」

ヤン: 「それは G-02 で出た代替案だね。ternary ではなく if 式の短縮記法を Phase 1 にする...悪くない。でも、`?:` の方が世界的に広く知られているシンタックスだ。学習コストは ternary の方が低い。」

千石: 「ユーザーが『知っている』記法を採用することは品質の一部です。驚き最小の原則です。」

  → **Gap 発見 (G-22): ternary `?:` vs if 短縮記法のどちらを MVP に採用するかの最終判断が未決。驚き最小の原則では `?:` が有利だが、パーサーの安全性では `if-else`（区切りが明確）が有利。**

---

## Gap リスト

| # | Gap | 発見 Scene | 深刻度 | カテゴリ |
|---|-----|-----------|--------|---------|
| G-01 | ternary と if 式が同じ AST（IfExpression）を生成するか別ノードを持つか未定義。同一 AST なら Evaluator 変更ゼロだがエラーメッセージの区別が不可 | Scene 1 | 高 | AST 設計 |
| G-02 | ternary 以外の代替案（中括弧省略 if、何もしない）の比較が未実施 | Scene 1 | 中 | 設計前提 |
| G-03 | ternary に対するユーザー要望の実績データがない | Scene 1 | 中 | 設計前提 |
| G-04 | ternary の優先順位が未定義。全演算子中のどの位置か。特に `&` `|` との関係 | Scene 2 | 高 | パーサー |
| G-05 | then/else に Expression を許すとネスト ternary が文法的に合法になる。文法レベル禁止 vs lint 警告 | Scene 2 | 高 | パーサー |
| G-06 | ネスト ternary の扱い: 禁止 / 許可 / 条件付き許可の選択 | Scene 3 | 高 | 設計判断 |
| G-07 | 案 A（then/else を OrExpr に制限）で括弧付きネストが PrimaryExpr 経由で正しく動作するかの検証 | Scene 3 | 中 | パーサー |
| G-08 | match case の value に ternary が入った場合のパース規則。Expression の終端判定と ternary 内部構造の非衝突証明 | Scene 4 | 高 | パーサー |
| G-09 | ternary の `?:` は開き/閉じペアではないため、全コンテキスト（match, 関数引数, 括弧内）での曖昧性の網羅的検証が必要 | Scene 4 | 高 | パーサー |
| G-10 | then/else で型が異なる場合の動作が5バックエンドで一致するか不明 | Scene 5 | 高 | 型システム |
| G-11 | ternary の型ルール: 完全動的(A) / 型一致強制(B) / 上方変換(C) の選択 | Scene 5 | 高 | 型システム |
| G-12 | ternary の結果を後続演算に使う場合の型整合性とチェックタイミング | Scene 5 | 中 | 型システム |
| G-13 | `:` の将来用途（slice, dict, 名前付き引数）との共存可能性の先行検証 | Scene 6 | 低 | パーサー |
| G-14 | `:` 書き忘れ等のパースエラー時のエラーメッセージ品質 | Scene 6 | 低 | UX |
| G-15 | 短絡評価の一貫性。5バックエンド全てで短絡を保証するか | Scene 7 | 高 | セマンティクス |
| G-16 | 同一 AST 採用なら短絡は自動保証。G-01 の決定に依存 | Scene 7 | 中 | セマンティクス |
| G-17 | ternary 結果へのドットチェーン適用。`(ternary).method()` のパース可否 | Scene 8 | 中 | パーサー |
| G-18 | 括弧なしネストのパースエラー時のエラーメッセージ品質 | Scene 8 | 低 | UX |
| G-19 | 5バックエンド x 30ケース = 150テストの実装コストと自動検証の仕組み | Scene 8 | 中 | テスト |
| G-20 | ternary 追加による既存 if 式のパース回帰リスク | Scene 9 | 高 | パーサー |
| G-21 | Phase 1 の完了条件（Exit Criteria）が未定義 | Scene 9 | 中 | プロセス |
| G-22 | ternary `?:` vs if 短縮記法のどちらを MVP にするかの最終判断 | Scene 9 | 中 | 設計判断 |

---

## Decision Record

| # | 決定事項 | 根拠 | Scene |
|---|---------|------|-------|
| D-01 | ternary は if 式と同じ AST（IfExpression）を生成する | Evaluator 変更ゼロ、短絡評価の自動保証、テスト等価性の保証。エラーメッセージの区別は source location で対応可能 | Scene 1, 7 |
| D-02 | ternary の優先順位は全演算子中で最低（OrExpr より低い） | C/Java/JavaScript と同じ。ユーザーの既存メンタルモデルを尊重 | Scene 2 |
| D-03 | ネスト ternary は文法レベルで禁止（then/else を OrExpr に制限）。括弧で囲めば許可 | 曖昧性の排除。複雑な分岐は if 式か match 式を使う設計方針 | Scene 3 |
| D-04 | ternary は全型（number, string, boolean, object）で動作する | 直交性原則。NumberExpression 限定は却下済み（先行 DGE G-08） | Scene 5 |
| D-05 | Phase 1 の型ルールは案 A（完全動的、型チェックなし） | ast-evaluator は動的型。Phase 2 以降で型安全強化を検討 | Scene 5 |
| D-06 | Phase 1 は ast-evaluator のみ。Phase 2 で全バックエンド展開 | リスク最小化。設計の妥当性を最もシンプルなバックエンドで検証 | Scene 9 |
| D-07 | ternary 追加時は既存テスト全 green を必須条件とする | 後方互換性は sacred。ternary 追加で既存の if 式が壊れることは絶対に許されない | Scene 9 |

---

## Observe → Suggest → Act

### O-S-A 1: 文法への TernaryExpr 追加

- **Observe**: tinyexpression に ternary 構文がない。if 式は verbose で、式言語のユーザーは簡潔な条件分岐を期待する。
- **Suggest**: `TernaryExpr ::= OrExpr ('?' OrExpr ':' OrExpr)?` を Expression の最上位に追加する。
- **Act**: PEG 文法に TernaryExpr ルールを追加。@mapping で IfExpression にマッピング。condition は最初の OrExpr、then は `?` 後の OrExpr、else は `:` 後の OrExpr。

### O-S-A 2: if 式との等価性テスト

- **Observe**: ternary が if 式と同じ AST を生成するなら、同じ入力に対して同じ結果を返すはず。しかしそれが保証されていない。
- **Suggest**: ternary と if 式のペアテストを作成し、全ての入力パターンで結果が一致することを検証する。
- **Act**: `TernaryEquivalenceTest` を作成。T-26 パターン（ternary == if 式）を全型・全条件パターンで実施。

### O-S-A 3: 既存テストの回帰検証

- **Observe**: TernaryExpr を Expression の最上位に挿入すると、既存の全式のパースパスが変わる。
- **Suggest**: ternary 追加前後で既存テストスイート全体を実行し、全 green を確認する。
- **Act**: ternary 文法追加前に既存テストの snapshot を取得。追加後に全テスト実行。差分がゼロであることを確認。

### O-S-A 4: match 内 ternary のパース検証

- **Observe**: match case の value に ternary が入った場合のパース動作が未検証。
- **Suggest**: T-25 パターンのパーステストを作成し、match の `,` 区切りと ternary の内部構造が衝突しないことを検証する。
- **Act**: `match{ $x > 0 -> $x > 1 ? 'high' : 'low', default -> 'none' }` のパーステストを作成。AST が正しく MatchExpr > MatchCase > TernaryExpr（= IfExpression）の構造になることを検証。

### O-S-A 5: `:` の将来衝突分析

- **Observe**: `:` を ternary で使うと、将来の slice/dict/名前付き引数構文との衝突リスクがある。
- **Suggest**: 将来追加が予想される `:` 使用構文をリストアップし、PEG 文法上での共存可能性を机上検証する。
- **Act**: slice `$str[0:3]`、dict `{key: value}`、named arg `func(name: value)` の文法ルールを仮定義し、ternary の `:` とコンテキストが重複しないことを確認。文書化して将来の参照とする。

---

## Action Items

| 優先度 | アクション | 依存 | 対応 Gap |
|--------|----------|------|---------|
| P0 | ternary 追加前の既存テスト全 green 確認（ベースライン） | なし | G-20 |
| P0 | PEG 文法に `TernaryExpr ::= OrExpr ('?' OrExpr ':' OrExpr)?` を追加 | なし | G-04, G-05, G-06 |
| P0 | @mapping で IfExpression を生成する設定 | 文法追加 | G-01, G-16 |
| P1 | 基本テスト30ケース（T-01 ~ T-30）を ast-evaluator で実装 | 文法追加 | G-19 |
| P1 | ternary 追加後の既存テスト全 green 回帰確認 | 文法追加 | G-20 |
| P1 | ternary と if 式の等価性テスト実装 | 文法追加 | G-01 |
| P1 | 括弧付きネストが PrimaryExpr 経由で動作することの検証テスト | 文法追加 | G-07 |
| P1 | match 内 ternary のパーステスト | 文法追加 | G-08, G-09 |
| P2 | compile-hand バックエンドへの ternary 展開 | Phase 1 完了 | G-10, G-15 |
| P2 | P4 系3バックエンドへの ternary 展開 | Phase 1 完了 | G-10, G-15 |
| P2 | 5バックエンド x 30ケース = 150テスト全 green | Phase 2 各バックエンド | G-19 |
| P2 | 5バックエンドでの型混在動作の統一ルール策定 | Phase 2 | G-10, G-11 |
| P3 | `:` の将来用途との共存可能性の文書化 | なし | G-13 |
| P3 | パースエラーメッセージの品質改善（`:` 忘れ、ネスト禁止） | Phase 1 完了 | G-14, G-18 |
| P3 | ternary vs if 短縮記法の最終判断（G-22 の解決） | ユーザーフィードバック | G-02, G-22 |
| P3 | Phase 3 型安全強化（then/else 型一致の静的チェック）の設計 | Phase 2 完了 | G-11, G-12 |
| P3 | ユーザー要望データの収集 | なし | G-03 |
| P3 | Phase 1 完了条件（Exit Criteria）の明文化 | なし | G-21 |
