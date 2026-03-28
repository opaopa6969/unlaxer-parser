# DGE Session: PropagationStopper 第3軸 -- syntaxContext による括弧省略

## テーマ
PropagationStopper は現在 2 軸で伝播を制御する: TokenKind (consumed/matchOnly) と invertMatch (true/false)。提案: 3 軸目「syntaxContext」を導入し、パーサーが「今どのような構文コンテキストにいるか」を子パーサーに伝播する。これにより `sin($a > 0 ? $a : -$a)` を二重括弧なしで書けるようにする。これは PropagationStopper の設計拡張であり、PEG パーサーコンビネータにおける「構文コンテキストの伝播制御」という論文レベルの貢献になりうる。

## キャラクター
- ☕ ヤン・ウェンリー -- 「要らなくない？」「最もシンプルな解は？」
- 🎩 千石武 -- 「品質基準を示す」「ユーザーのために」
- ⚔ リヴァイ兵長 -- 「汚い。動くもの見せろ。」
- 👤 今泉慶太 -- 「そもそも」「誰が困るの」

## 前提条件
- PropagationStopper 階層: AllPropagationStopper, DoConsumePropagationStopper, InvertMatchPropagationStopper, NotPropagatableSource
- 現在の 2 軸: TokenKind (consumed/matchOnly) は「トークンを消費するか先読みのみか」、invertMatch (true/false) は「マッチ条件を反転するか」
- ParseContext: トランザクション管理 (begin/commit/rollback)、パーサーフレームスタック、カーソル追跡
- ternary 文法: `TernaryExpression ::= '(' BooleanExpression '?' Expression ':' Expression ')'` -- 外側括弧必須
- 問題: `sin(($a > 0 ? $a : -$a))` の二重括弧。先行 DGE (dge-ternary-parens-in-context.md) で問題が詳細に分析済み
- 提案: syntaxContext (normal/argument/bracket/matchValue/...) を 3 軸目として導入

## 先輩 (ナレーション)
PropagationStopper は unlaxer の核心的な発明の一つだ。PEG パーサーコンビネータにおいて、親パーサーが子パーサーの動作モードを制御する -- これは一般的なコンビネータライブラリにない機能だ。2 軸で十分に戦えてきた。しかし「括弧省略」という実用上の問題が、3 軸目の必要性を突きつけている。

問題はシンプルだ。ternary の `(` と関数呼び出しの `(` は、コードポイント上は同じ文字だが、意味が違う。2 軸の伝播制御は「トークンをどう扱うか」を制御するが、「今どこにいるか」は制御しない。3 軸目はまさにその「どこにいるか」-- 構文コンテキスト -- を伝播で制御しようという提案だ。

---

## Scene 1: The Problem (revisited)

今泉: 「そもそもなぜ二重括弧が必要なんでしたっけ？ 前にも議論しましたよね？」

ヤン: 「まあ、復習しよう。紅茶を淹れ直す時間だし。」

```
入力: sin($a > 0 ? $a : -$a)

パーサーの視点:
1. sin   --> FunctionName マッチ
2. (     --> 関数呼び出しの開き括弧として消費
3. $a > 0 ? $a : -$a  --> NumberExpression をパース
4. )     --> 関数呼び出しの閉じ括弧

問題: ステップ 3 で TernaryExpression をパースしたい。
しかし TernaryExpression は '(' で始まる。
'(' は既にステップ 2 で消費済み。

結果: ternary として認識されない。
回避策: sin(($a > 0 ? $a : -$a)) -- 内側の ( が ternary 用。
```

千石: 「2 つの `(` は別物です。外側は関数呼び出しの構文。内側は ternary の区切り。しかしユーザーから見れば、sin の引数が条件式であることは自明です。二重括弧を要求するのは、処理系の都合をユーザーに押し付けています。」

今泉: 「要するに、C でも Java でも `sin(cond ? a : b)` で普通に書けるのに、tinyexpression だけ二重括弧なんですね。」

  --> **Gap 発見 (G-01): 「括弧の意味の二重性」-- 関数呼び出しの `(` と ternary の `(` をパーサーレベルでどう区別するか。現在の PropagationStopper の 2 軸 (TokenKind, invertMatch) はこの区別を表現できない。**

リヴァイ: 「他言語との比較は分かった。で、実コードで何例くらい影響するんだ。」

今泉: 「誰が困るんですか、具体的に。」

千石: 「条件付き計算は業務ルールの基本です。`sin($angle > 0 ? $angle : -$angle)` -- 絶対値の三角関数。`max($price > 1000 ? $price * 0.9 : $price, $minPrice)` -- 割引計算。これらは全て二重括弧を要求されます。エンドユーザーに二重括弧の理由を説明するのは、品質の敗北です。」

  --> **Gap 発見 (G-02): 二重括弧の影響を受ける式パターンの完全な列挙がない。単引数関数、多引数関数、ネスト関数呼び出し、match 式内 -- それぞれで括弧省略の期待と現実のギャップがどの程度か。**

---

## Scene 2: PropagationStopper の設計思想を振り返る

ヤン: 「PropagationStopper の設計を振り返ろう。TokenKind と invertMatch の 2 軸で足りてたのに、なぜ 3 軸目が要る？」

```
現在の PropagationStopper 階層:

PropagationStopper (interface) -- マーカー
  |
  +-- AllPropagationStopper
  |     子に (consumed, false) を強制。両軸を止める。
  |
  +-- DoConsumePropagationStopper
  |     TokenKind を consumed に固定。invertMatch はそのまま通す。
  |
  +-- InvertMatchPropagationStopper (via AbstractPropagatableSource)
  |     invertMatch を false に固定。TokenKind はそのまま通す。
  |
  +-- NotPropagatableSource (via AbstractPropagatableSource)
        invertMatch を反転。トランザクション (begin/commit/rollback) を挟む。
```

千石: 「整理すると、2 軸はこういう設計思想です。」

```
軸 1: TokenKind -- 「トークンを消費するか先読みだけか」
  consumed  --> パース結果が AST に残る
  matchOnly --> 先読みだけ、カーソルは戻る

軸 2: invertMatch -- 「マッチ条件を反転するか」
  false --> 通常のマッチ
  true  --> 否定先読み (Not predicate)

2 軸の直積:
  (consumed, false)  -- 通常のパース
  (consumed, true)   -- 消費するが反転マッチ (稀)
  (matchOnly, false) -- 先読み
  (matchOnly, true)  -- 否定先読み
```

ヤン: 「この 2 軸は『パースモード』の制御だ。つまり『どうパースするか』。でも 3 軸目の syntaxContext は『どこでパースしているか』。これは別の次元だね。」

今泉: 「そもそも、その『別の次元』を PropagationStopper に入れるのは正しいんですか？ PropagationStopper はパースモードの伝播制御のために作られたのに、構文コンテキストの伝播は別の責務では？」

  --> **Gap 発見 (G-03): 3 軸目は PropagationStopper の責務拡張なのか、別の仕組み (例えば ParseContext のコンテキストスタック) として実装すべきなのか。設計上の責務分離の問題。**

リヴァイ: 「共通点を見ろ。どっちも『親が子の動作を制御する情報の伝播』だ。伝播制御という共通パラダイムに収まるなら、同じ仕組みでいい。」

ヤン: 「まあそうだね。ただ 2 軸は boolean ベースで対称的だ。3 軸目は enum で非対称。合成のルールが変わる。」

```
2 軸の合成:
  AllPropagationStopper: (*, *) --> (consumed, false)  -- 定数置換
  DoConsumePropagationStopper: (*, inv) --> (consumed, inv)  -- 1 軸だけ固定
  InvertMatchPropagationStopper: (tk, *) --> (tk, false)  -- 1 軸だけ固定

3 軸目を追加すると?
  (tk, inv, ctx) --> ???
  合成はどうなる?
```

  --> **Gap 発見 (G-04): 3 軸目の合成ルール。TokenKind と invertMatch は boolean/enum の定数置換で合成が明確。syntaxContext の合成は? 「argument の中の argument」はどうなるか。コンテキストの合成表が定義されていない。**

---

## Scene 3: syntaxContext の設計

千石: 「コンテキストの種類を列挙しましょう。」

```
syntaxContext の候補:

normal     -- デフォルト。括弧必須。通常の式コンテキスト。
argument   -- 関数引数の中。sin(...), min(..., ...) の () 内。
bracket    -- 配列添字の中。$arr[...] の [] 内。
matchValue -- match case の値部分。match{ cond -> ... } の -> 右辺。
grouping   -- 明示的なグルーピング括弧 (...) の中。
```

今泉: 「要するに、コンテキストが argument のとき、ternary は外側括弧を省略できる。そういうことですよね？」

千石: 「そうです。ternary パーサーが『今 argument コンテキストにいる』と知っていれば、`(` を自分で要求せず、関数呼び出しの `(` を信頼できます。」

ヤン: 「ちょっと待って。ternary が括弧を省略するということは、ternary パーサーの文法定義が変わるということだ。」

```
// 現在
TernaryExpression ::= '(' BoolExpr '?' Expr ':' Expr ')'

// syntaxContext=argument のとき
TernaryExpression ::= BoolExpr '?' Expr ':' Expr

// つまり、文法が動的に変わる
// これは PEG の静的文法定義に反しないか?
```

  --> **Gap 発見 (G-05): 文法のコンテキスト依存性。syntaxContext によって文法ルールが動的に変わるのは、PEG の静的・宣言的な性質と矛盾しないか。パーサーコンビネータだから動的に変えられるが、「文法のコンテキスト自由性」をどの程度まで犠牲にするか。**

今泉: 「他にないの？ コンテキストの表現方法。」

千石: 「3 つの選択肢を検討しましょう。」

```
選択肢 A: enum
  enum SyntaxContext { NORMAL, ARGUMENT, BRACKET, MATCH_VALUE }
  + シンプル、型安全
  - 拡張に再コンパイルが必要

選択肢 B: ビットフラグ
  int context = CTX_ARGUMENT | CTX_ALLOWS_BARE_TERNARY;
  + 複数の性質を組み合わせ可能
  - 可読性が低い、フラグ爆発のリスク

選択肢 C: 文字列ベース
  String context = "argument";
  + 最も柔軟、UBNF からの指定が容易
  - 型安全性なし、typo のリスク
```

  --> **Gap 発見 (G-06): syntaxContext の表現型の選択。enum/ビットフラグ/文字列のどれを採用するか。将来の拡張性と現在の型安全性のトレードオフ。**

リヴァイ: 「ネストはどうなる。」

```
sin(min($a > 0 ? $a : 0, $b))

コンテキストの伝播:
1. sin(  --> context = argument
2. min(  --> context = argument (argument の中の argument)
3. $a > 0 ? $a : 0  --> ternary は context=argument なので括弧省略

問題なし? argument + argument = argument なので。

では:
$arr[sin($a > 0 ? $a : 0)]

1. $arr[ --> context = bracket
2. sin(  --> context = argument (bracket の中の argument)
3. $a > 0 ? $a : 0 --> ternary は context=argument なので括弧省略

bracket の中の argument... コンテキストはスタックか? 最新のものが勝つ?
```

  --> **Gap 発見 (G-07): コンテキストのネスト戦略。スタック (最新が勝つ)? 合成 (bracket + argument = ?)? 最内側のコンテキストだけが有効? ネストが深くなったときの振る舞いが未定義。**

---

## Scene 4: ParseContext への実装

リヴァイ: 「ParseContext にどう入れるか。具体的に見せろ。」

```java
// 案 A: ParseContext にフィールド追加
public class ParseContext implements ... {
    // 既存
    final Deque<TransactionElement> tokenStack;
    final Deque<ParseFrame> parseFrames;

    // 追加
    SyntaxContext currentSyntaxContext = SyntaxContext.NORMAL;
}

// 案 B: ParseContext にスタック追加
public class ParseContext implements ... {
    final Deque<SyntaxContext> syntaxContextStack = new ArrayDeque<>();
}

// 案 C: parse メソッドのシグネチャ変更
public Parsed parse(ParseContext ctx, TokenKind tk, boolean inv, SyntaxContext syntaxCtx);
```

ヤン: 「案 C はやめよう。parse メソッドのシグネチャを変えると全パーサーに影響する。互換性の破壊が大きすぎる。」

リヴァイ: 「案 A と案 B の違いは?」

千石: 「案 A は単一値。ネストに対応できません。案 B はスタックですが、begin/commit/rollback との連携が問題です。」

```
トランザクションとの連携:

1. begin() -- 新しい TransactionElement をスタックに push
2. パース実行
3a. commit() -- 成功。TransactionElement をマージ。
3b. rollback() -- 失敗。TransactionElement を pop して巻き戻す。

syntaxContext がスタックにある場合:
- begin() で syntaxContext も保存する?
- rollback() で syntaxContext も戻す?
- commit() で syntaxContext はどうなる?
```

  --> **Gap 発見 (G-08): トランザクション rollback 時の syntaxContext の扱い。rollback で syntaxContext も巻き戻すべきか。もし巻き戻さない場合、失敗したパース試行が後続のパースに syntaxContext を漏洩する。巻き戻す場合、TransactionElement に syntaxContext を含める必要がある。**

今泉: 「そもそも、PropagationStopper の 2 軸 (TokenKind, invertMatch) はトランザクションに参加してないですよね？ parse メソッドの引数として伝播してるだけで。なら 3 軸目も同じ方式でいいのでは？」

ヤン: 「鋭い。TokenKind と invertMatch はメソッド引数として伝播する。スタックには入っていない。同じ方式なら...」

```java
// 案 D: PropagationStopper と同じ方式 -- parse メソッドの引数に入れる
// ただし既存シグネチャは変えない
// Context オブジェクトでラップする

// 案 D-1: ParseContext 経由で間接的に渡す
public class ParseContext {
    private SyntaxContext currentSyntaxContext = SyntaxContext.NORMAL;

    public SyntaxContext getSyntaxContext() { return currentSyntaxContext; }
    public SyntaxContext pushSyntaxContext(SyntaxContext ctx) {
        SyntaxContext prev = this.currentSyntaxContext;
        this.currentSyntaxContext = ctx;
        return prev;  // 呼び出し元が保存して復元
    }
}

// 使用側:
public class SinFunction extends ... {
    @Override
    public Parsed parse(ParseContext ctx, TokenKind tk, boolean inv) {
        // '(' を消費
        SyntaxContext prev = ctx.pushSyntaxContext(SyntaxContext.ARGUMENT);
        try {
            Parsed argParsed = argumentParser.parse(ctx, tk, inv);
            // ')' を消費
            return argParsed;
        } finally {
            ctx.pushSyntaxContext(prev);  // 復元
        }
    }
}
```

  --> **Gap 発見 (G-09): 案 D-1 は try/finally で復元するため、rollback 時にも自動復元される。しかし PEG の ordered choice で「最初の選択肢が途中まで進んで失敗、2 番目を試す」場合、syntaxContext の復元タイミングが正しいか。Choice パーサーの各選択肢が syntaxContext を変更する場合の安全性。**

リヴァイ: 「パフォーマンスは。ParseContext にフィールド 1 つ追加するだけなら問題ないが、スタック操作が毎回入ると影響がある。」

  --> **Gap 発見 (G-10): パフォーマンス影響。syntaxContext の伝播コスト。フィールド読み書きだけなら O(1) だが、関数呼び出しのたびに push/pop が発生する。tinyexpression の式サイズでは問題にならないだろうが、大規模文法では? ベンチマークの基準がない。**

---

## Scene 5: UBNF での表現

今泉: 「文法にどう書くんですか？ UBNF で。」

千石: 「3 つの案があります。」

```
案 A: アノテーション方式
  SinFunction ::= 'sin' '(' @context(argument) NumberExpression ')' ;

  利点: 明示的。文法を読むだけでコンテキスト変更が見える。
  欠点: 全ての関数定義にアノテーションが必要。冗長。

案 B: 暗黙方式
  関数呼び出しの () 内は自動的に argument コンテキスト。
  文法には何も書かない。ジェネレータが自動設定。

  利点: 文法がシンプルなまま。
  欠点: 暗黙のルールが増える。「なぜ sin の中では括弧省略できるのか」が文法から読み取れない。

案 C: 新しいコンビネータ方式
  SinFunction ::= 'sin' '(' ArgumentContext(NumberExpression) ')' ;

  ArgumentContext は PropagationStopper の一種。
  子パーサーに syntaxContext=ARGUMENT を伝播する。

  利点: PropagationStopper のパラダイムに統一。
  欠点: コンビネータが増える。
```

ヤン: 「案 C がいいんじゃないか。AllPropagationStopper が (consumed, false) を強制するように、ArgumentContextPropagationStopper が (tk, inv, argument) を強制する。パラダイムが揃う。」

今泉: 「でも、そうすると PropagationStopper の名前が合わなくなりませんか？ 伝播を『止める』のではなく、伝播で『設定する』のですから。」

  --> **Gap 発見 (G-11): 命名の問題。PropagationStopper は「伝播を止める」意味だが、syntaxContext は「伝播で設定する」。止めるのではなく注入する。PropagationController? PropagationModifier? 名前が設計思想を反映しなくなるリスク。**

リヴァイ: 「ジェネレータの話だ。UBNF から Java コードを生成するとき、@context アノテーションをどう処理する。」

  --> **Gap 発見 (G-12): UBNF ジェネレータの対応。案 A の場合、ジェネレータが @context アノテーションを認識して ArgumentContextPropagationStopper を生成する必要がある。案 B の場合、ジェネレータが関数呼び出しパターンを自動検出して ArgumentContext を挿入する。案 C の場合、UBNF に新しいコンビネータ構文が必要。いずれの案でもジェネレータの変更が必要。**

---

## Scene 6: ternary 以外の用途

ヤン: 「ternary の括弧省略だけのために 3 軸目を入れるのか？ 他に何に使える？」

千石: 「他にも用途があります。」

```
用途 1: match 式内の ternary
  match{
    $x > 0 -> $x > 1 ? 'high' : 'low',
    $x == 0 -> 'zero',
    default -> 'negative'
  }

  問題: ',' が ternary の一部なのか match case の区切りなのか。
  matchValue コンテキストなら、ternary パーサーが ',' を見て
  「ここは match の中だから ',' は自分のものではない」と判断できる。

用途 2: 配列添字内の ternary
  $arr[$cond ? 0 : 1]

  bracket コンテキストなら ternary の ')' と ']' を混同しない。
  （ただし ternary は () を使うので直接の衝突はないが、
   将来 ternary の括弧省略が他のコンテキストにも拡張された場合。）

用途 3: 将来の lambda 式
  $fn = ($x) -> $x > 0 ? $x : -$x

  lambda 本体コンテキストなら、-> が lambda のものか
  ternary 的な何かの一部か区別できる。

用途 4: dict リテラル
  { key: $cond ? value1 : value2, key2: ... }

  dict コンテキストなら ':' の意味が明確。
```

今泉: 「前もそうだったっけ？ 汎用的な仕組みを入れたら、使い道が際限なく広がって設計が肥大化したこと。」

  --> **Gap 発見 (G-13): 汎用化のリスク。syntaxContext が汎用的すぎると、あらゆる曖昧性解消に使われ始め、文法の静的な宣言性が失われる。コンテキスト依存文法 (CSG) に近づいていく。どこに境界線を引くか。**

ヤン: 「用途 1 (match 内の ternary) は確かに価値がある。でも用途 3, 4 は将来の話で、今決める必要はない。」

リヴァイ: 「用途を列挙するのはいいが、各用途で syntaxContext が本当に必要かは個別に検証しないと分からない。match 式の ',' 問題は syntaxContext なしでも解けるかもしれない。」

  --> **Gap 発見 (G-14): 各用途の代替解法の存在。match 式の ',' 問題は PEG の ordered choice の評価順序で解けるかもしれない。配列添字の問題は brackets のパースルールで解けるかもしれない。syntaxContext が唯一の解法であることを各用途で検証していない。**

---

## Scene 7: 他のパーサーフレームワークはどうしてるか

千石: 「他のパーサーフレームワークはこの問題をどう解決していますか？ 比較しましょう。」

```
ANTLR (LL(*)):
  TernaryExpression は演算子優先順位として定義。
  expr : expr '?' expr ':' expr   // 優先順位で解決
       | functionCall
       | ...
  ;
  LL(*) の適応的先読みで、ternary の開始/終了を文脈なしで判定。
  括弧は不要。コンテキストの概念自体が不要。

Parsec (LL(k) / モナディック):
  Reader モナドでコンテキストを伝播。
  local (const ArgumentContext) $ parseTernary
  パーサーが暗黙的にコンテキストを参照できる。
  Reader モナドの local 関数がまさに
  「スコープを限定したコンテキスト変更」。

tree-sitter (GLR):
  曖昧性を許容。複数のパースツリーを生成して後で解消。
  ternary も関数呼び出しも両方パースして、
  後処理 (semantic analysis) で正しい方を選ぶ。

unlaxer (PEG + PropagationStopper):
  PEG は ordered choice。最初にマッチした方が勝つ。
  曖昧性は許容されず、文法の記述順序が結果を決定する。
  PropagationStopper で親が子の動作を明示的に制御。
```

今泉: 「要するに PEG の ordered choice が制約なんですね。ANTLR なら演算子優先順位で自然に解決できるのに、PEG ではできない。」

ヤン: 「PEG の制約と言えばそうだが、PEG の ordered choice には決定的なパースの保証という利点がある。曖昧性がないということは、パース結果が一意に定まるということだ。ANTLR の適応的先読みは柔軟だが、文法によっては指数的に遅くなる。」

  --> **Gap 発見 (G-15): PEG 固有の問題か汎用的な問題か。ANTLR が演算子優先順位で解決するということは、これは PEG の表現力の限界に起因する問題。ならば PropagationStopper の 3 軸目は「PEG の表現力を補う仕組み」として位置づけられる。この位置づけは論文の貢献度にどう影響するか。**

千石: 「Parsec の Reader モナドとの対応が興味深いですね。」

```
Parsec:
  ask      :: m Context       -- 現在のコンテキストを取得
  local f  :: m a -> m a      -- コンテキストを変更してパーサーを実行

unlaxer (提案):
  ParseContext.getSyntaxContext()   -- 現在のコンテキストを取得
  ArgumentContextStopper(parser)   -- コンテキストを変更して子パーサーを実行

対応関係:
  ask    <--> getSyntaxContext()
  local  <--> ArgumentContextStopper (PropagationStopper の新サブクラス)
```

ヤン: 「面白い。Parsec の Reader モナドを PropagationStopper パターンとして具象化した、と言える。モナドの暗黙的な合成を、明示的なクラス階層で表現している。」

  --> **Gap 発見 (G-16): Parsec の Reader モナドとの形式的対応。PropagationStopper が Reader モナドの具象化であるなら、モナド則 (left identity, right identity, associativity) に対応する性質が PropagationStopper にもあるはずだ。この形式的対応を証明できれば、論文の理論的貢献になる。**

---

## Scene 8: 論文への貢献

ヤン: 「これ論文に入れるレベル？」

千石: 「PropagationStopper が 2 軸から 3 軸に拡張されるのは、設計レベルの変更です。論文で扱うに値します。」

```
既存の貢献:
  PropagationStopper: PEG パーサーコンビネータにおける伝播制御
  - 2 軸 (TokenKind, invertMatch)
  - 4 つの状態空間
  - 合成表 (AllPropagationStopper, DoConsume, InvertMatch)

提案の貢献:
  3 軸目 syntaxContext の追加
  - 状態空間: 4 --> 4 * |SyntaxContext| (context 種類数)
  - 合成ルールの拡張
  - Parsec Reader モナドとの形式的対応
  - PEG の表現力限界の補完

論文セクション案:
  Section X: Context-Aware Propagation Control
  X.1 Motivation -- 二重括弧問題
  X.2 Design -- syntaxContext 軸の導入
  X.3 Formal correspondence with Reader monad
  X.4 Composition rules for 3-axis system
  X.5 Implementation in ParseContext
```

リヴァイ: 「論文の話はいいが、状態空間が 4 から 4*N に増えるんだ。合成表の検証は?」

ヤン: 「形式化の問題だね。操作的意味論に 3 軸目を追加するとなると...」

```
現在の操作的意味論 (概要):

  parse(p, ctx, tk, inv) --> (result, ctx')

3 軸追加:

  parse(p, ctx, tk, inv, sc) --> (result, ctx')

  ただし sc (syntaxContext) は TokenKind, invertMatch と異なり:
  - boolean ではなく enum (有限集合)
  - 合成が非可換の可能性がある
  - デフォルト値 (NORMAL) への復帰ルールが必要
```

  --> **Gap 発見 (G-17): 操作的意味論の拡張。3 軸目を形式化するには、syntaxContext の合成規則を定義し、合成の結合律と単位元 (NORMAL) の存在を証明する必要がある。これが成立すれば、PropagationStopper の 3 軸システムがモノイド構造を持つことを示せる。**

今泉: 「そもそも、論文にするなら R1 (理論家の査読者) に何を聞かれますか？」

ヤン: 「『なぜ 3 軸で十分なのか。4 軸目、5 軸目が要らない保証は？』と聞かれるだろうね。」

  --> **Gap 発見 (G-18): 軸の完全性。3 軸で十分であることの論拠。パーサーコンビネータが制御する必要がある「伝播パラメータ」のカテゴリは何種類あるか。TokenKind (消費モード)、invertMatch (マッチ条件)、syntaxContext (構文位置) の 3 つで網羅的か。**

---

## Scene 9: MVP

リヴァイ: 「今本当に必要なのは ternary の括弧省略だけだろ。」

ヤン: 「その通り。argument コンテキスト 1 つだけで MVP を作ろう。」

```
MVP スコープ:
  - SyntaxContext enum に NORMAL と ARGUMENT の 2 値だけ
  - ParseContext に currentSyntaxContext フィールド追加
  - ArgumentContextStopper (PropagationStopper 新サブクラス) 1 つ追加
  - TernaryExpression.parse() で syntaxContext=ARGUMENT なら括弧省略

MVP で動く式:
  sin($a > 0 ? $a : -$a)         -- OK
  min($a > 0 ? $a : 0, $b)       -- OK (argument 内)
  max(sin($a > 0 ? $a : -$a), 0) -- OK (ネストした argument)

MVP で動かない式 (将来):
  match{ $x > 0 -> $x > 1 ? 'high' : 'low', ... }  -- matchValue 未実装
  $arr[$cond ? 0 : 1]  -- bracket 未実装
```

千石: 「MVP としては十分です。ただし、NORMAL と ARGUMENT の 2 値だけでも、合成ルールは定義してください。」

```
MVP 合成表:
  NORMAL   + ArgumentContextStopper --> ARGUMENT
  ARGUMENT + ArgumentContextStopper --> ARGUMENT  (冪等)
  ARGUMENT + AllPropagationStopper  --> ???
```

  --> **Gap 発見 (G-19): MVP でも必要な合成ルール。AllPropagationStopper は全伝播を止める。syntaxContext も NORMAL に戻すのか? DoConsumePropagationStopper は TokenKind だけ止める。syntaxContext はスルーするのか? 既存の PropagationStopper サブクラスとの相互作用を MVP でも定義する必要がある。**

今泉: 「でも、そもそもこれ、もっとシンプルな解法ないですか？」

ヤン: 「ほう。」

今泉: 「PropagationStopper に 3 軸目を追加するんじゃなくて、文法レベルで解決する方法。」

```
Alternative: ArgumentExpression を導入

// 通常の式 (括弧必須の ternary)
Expression ::= ... | TernaryExpression | ...
TernaryExpression ::= '(' BoolExpr '?' Expr ':' Expr ')'

// 引数用の式 (括弧不要の ternary)
ArgumentExpression ::= BareTernaryExpression | Expression
BareTernaryExpression ::= BoolExpr '?' Expr ':' Expr

// 関数呼び出し
SinFunction ::= 'sin' '(' ArgumentExpression ')'

メリット:
  - PropagationStopper の変更不要
  - 文法レベルで完結。コンテキストの概念が不要。
  - PEG のまま。ordered choice で BareTernary を先に試す。

デメリット:
  - ArgumentExpression と Expression の二重定義
  - 関数が増えるたびに ArgumentExpression を使う必要
  - 文法の重複が増える
```

  --> **Gap 発見 (G-20): 文法レベル vs 伝播制御レベルの選択。ArgumentExpression アプローチは PropagationStopper を変更せず文法の重複で解決する。syntaxContext アプローチは文法はシンプルだが伝播制御が複雑化する。どちらが unlaxer の設計思想 (PropagationStopper による明示的な伝播制御) に合致するか。**

千石: 「ArgumentExpression アプローチは、文法の DRY 原則に反します。TernaryExpression の定義が 2 箇所に分散する。修正漏れのリスクです。」

リヴァイ: 「一方で、PropagationStopper に 3 軸目を入れるのは、核心的なフレームワークの変更だ。影響範囲が大きい。」

ヤン: 「トレードオフだね。」

```
比較表:

                     | ArgumentExpression    | syntaxContext (3 軸目)
---------------------|----------------------|------------------------
変更箇所              | 文法定義のみ          | PropagationStopper + ParseContext
影響範囲              | 新しいパーサークラス追加| フレームワーク全体
DRY                  | 違反 (定義重複)       | 準拠 (定義は1つ)
拡張性               | 低 (用途ごとに重複)   | 高 (コンテキスト追加で対応)
形式化               | 不要                  | 必要 (合成ルール等)
論文への貢献          | 低                    | 高
実装難易度            | 低                    | 中〜高
unlaxer らしさ       | 文法偏重              | 伝播制御偏重
```

  --> **Gap 発見 (G-21): 判断基準の欠如。2 つのアプローチのどちらを選ぶかの判断基準が明確でない。「unlaxer らしさ」は主観的。客観的な判断基準 (例: 文法ルール数の増加率、パーサークラスの複雑度指標) が定義されていない。**

今泉: 「前もそうだったっけ？ 最初はシンプルな方で始めて、後から汎用化したこと。invertMatch も最初から 2 軸だったんですか？」

ヤン: 「いい質問だ。最初は TokenKind だけだった。invertMatch は後から追加された。つまり『必要になったら軸を追加する』という進化的アプローチの実績がある。」

  --> **Gap 発見 (G-22): 進化的アプローチの適用。MVP では ArgumentExpression (文法レベル) で解決し、用途が増えたら syntaxContext (3 軸目) に移行する、という段階的アプローチの妥当性。ただし文法レベルの解法から伝播制御レベルの解法への移行コストが未評価。**

---

## Gap Summary

| ID | Gap | Observe | Suggest |
|----|-----|---------|---------|
| G-01 | 括弧の意味の二重性 | 関数呼び出しの `(` と ternary の `(` を区別できない | syntaxContext による区別、または文法レベルの分離 |
| G-02 | 影響パターンの未列挙 | 二重括弧が必要な式パターンの完全な列挙がない | 単引数/多引数/ネスト/match の各パターンを検証 |
| G-03 | 責務の帰属 | 3 軸目は PropagationStopper か別の仕組みか | 「伝播制御」の共通パラダイムに収まるかで判断 |
| G-04 | 3 軸目の合成ルール | syntaxContext の合成表が未定義 | 最低限 MVP の 2 値 (NORMAL, ARGUMENT) の合成表を定義 |
| G-05 | 文法のコンテキスト依存性 | syntaxContext で文法ルールが動的に変わる | PEG の宣言性とのトレードオフを明記 |
| G-06 | 表現型の選択 | enum/ビットフラグ/文字列の未決定 | MVP は enum、将来必要なら sealed interface に移行 |
| G-07 | コンテキストのネスト戦略 | argument の中の bracket の中の argument の扱い | 「最内側のコンテキストが勝つ」スタック方式を検討 |
| G-08 | rollback 時の扱い | トランザクション rollback で syntaxContext がどうなるか | try/finally 方式なら自動復元。TransactionElement に含める必要はない |
| G-09 | Choice パーサーとの安全性 | ordered choice の各選択肢が syntaxContext を変更する場合 | 選択肢ごとに try/finally で復元すれば安全 |
| G-10 | パフォーマンス影響 | push/pop のコスト | フィールド読み書きのみなら O(1)。ベンチマーク基準の策定 |
| G-11 | 命名の問題 | PropagationStopper は「止める」だが 3 軸目は「設定する」 | PropagationController への改名、または新名称の検討 |
| G-12 | UBNF ジェネレータ対応 | どの案でもジェネレータの変更が必要 | 案 C (コンビネータ方式) ならジェネレータ変更が最小 |
| G-13 | 汎用化のリスク | あらゆる曖昧性解消に syntaxContext が使われる危険 | 用途の厳格なホワイトリスト管理 |
| G-14 | 各用途の代替解法 | match 式の ',' 問題等は syntaxContext なしで解ける可能性 | 各用途で syntaxContext の必要性を個別に検証 |
| G-15 | PEG 固有の問題か | ANTLR なら演算子優先順位で解決可能 | 「PEG の表現力を補う仕組み」として位置づけ |
| G-16 | Reader モナドとの対応 | Parsec の local 関数と PropagationStopper の形式的対応 | 対応関係を証明し、論文の理論的貢献にする |
| G-17 | 操作的意味論の拡張 | 3 軸の合成規則の形式化が必要 | モノイド構造の証明を試みる |
| G-18 | 軸の完全性 | 3 軸で十分かの保証がない | パーサーが制御する伝播パラメータのカテゴリ分析 |
| G-19 | 既存 Stopper との相互作用 | AllPropagationStopper 等が syntaxContext をどう扱うか | MVP でも合成ルールの定義が必要 |
| G-20 | 文法レベル vs 伝播制御レベル | ArgumentExpression vs syntaxContext のトレードオフ | 判断基準を設けて比較 |
| G-21 | 判断基準の欠如 | 2 つのアプローチの選択基準が主観的 | 客観的な複雑度指標を定義 |
| G-22 | 進化的アプローチ | 文法レベル --> 伝播制御への段階的移行の妥当性 | 移行コストを事前に評価 |

## Decision (暫定)

1. **MVP は ArgumentExpression (文法レベル) で実装を検討する** -- リスクが低く、PropagationStopper の核心を変更しない
2. **syntaxContext (3 軸目) は論文の理論セクションで議論する** -- Parsec Reader モナドとの対応は形式的に面白い
3. **実装移行のトリガー**: ArgumentExpression の重複が 3 箇所以上になったら、syntaxContext への移行を再検討
4. **合成ルールの形式化は先行して進める** -- 実装とは独立に、理論的な検証が可能

## Action Items

- [ ] G-02: 二重括弧の影響パターンの完全列挙
- [ ] G-04: MVP 合成表 (NORMAL, ARGUMENT) の定義
- [ ] G-08: try/finally 方式の安全性をサンプルコードで検証
- [ ] G-14: match 式の ',' 問題が syntaxContext なしで解けるか検証
- [ ] G-16: Parsec Reader monad と PropagationStopper の形式的対応を整理
- [ ] G-19: AllPropagationStopper, DoConsumePropagationStopper が syntaxContext をどう扱うかの設計
- [ ] G-20: ArgumentExpression プロトタイプの実装と評価
- [ ] G-22: ArgumentExpression --> syntaxContext 移行コストの見積もり
