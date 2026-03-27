[English](./llm-era-and-unlaxer-dialogue.en.md) | [日本語](./llm-era-and-unlaxer-dialogue.ja.md) | [Index](./INDEX.ja.md)

---

# LLM時代とUnlaxer -- 会話で考える「フレームワークはまだ要るのか」

> **登場人物**
> - **先輩**: unlaxer-parser と tinyexpression の設計者。コード生成パイプラインを一から作った人。自虐ネタが得意
> - **後輩**: Java は得意だが、LLM で何でもできると思っている若手開発者。遠慮なく突っ込む

---

## Part 1: LLMは言語処理系を書けるのか？

**後輩:** 先輩、ちょっと聞いていいですか。最近 ChatGPT に「電卓言語を作って」って言ったら、それなりのものが出てきたんですよ。パーサーもエバリュエータも全部。Unlaxer って、もう要らなくないですか？

**先輩:** おっ、いきなり存在意義を問う質問だね。

**後輩:** すみません、でも気になって。だって「四則演算パーサーを再帰下降で書いて」って言えば、ほんとに動くコードが出てくるんですよ。

**先輩:** うん、出てくるよ。見てみよう。実際に LLM に「Java で四則演算パーサーを書いて。AST も作って」って頼むとこんなコードが返ってくる。

```java
// LLM が素で書いた四則演算パーサー（典型的な出力）
public class Calculator {
    private String input;
    private int pos;

    public double evaluate(String expression) {
        this.input = expression;
        this.pos = 0;
        double result = parseExpression();
        if (pos < input.length()) {
            throw new RuntimeException("Unexpected character: " + input.charAt(pos));
        }
        return result;
    }

    private double parseExpression() {
        double left = parseTerm();
        while (pos < input.length() && (input.charAt(pos) == '+' || input.charAt(pos) == '-')) {
            char op = input.charAt(pos++);
            double right = parseTerm();
            if (op == '+') left += right;
            else left -= right;
        }
        return left;
    }

    private double parseTerm() {
        double left = parseFactor();
        while (pos < input.length() && (input.charAt(pos) == '*' || input.charAt(pos) == '/')) {
            char op = input.charAt(pos++);
            double right = parseFactor();
            if (op == '*') left *= right;
            else left /= right;
        }
        return left;
    }

    private double parseFactor() {
        if (input.charAt(pos) == '(') {
            pos++; // skip '('
            double result = parseExpression();
            pos++; // skip ')'
            return result;
        }
        // Parse number
        int start = pos;
        while (pos < input.length() && (Character.isDigit(input.charAt(pos)) || input.charAt(pos) == '.')) {
            pos++;
        }
        return Double.parseDouble(input.substring(start, pos));
    }
}
```

**後輩:** ほら、ちゃんと動くじゃないですか！ 再帰下降で、演算子優先度もある。何が不満なんですか？

**先輩:** 動くよ。「四則演算の電卓」としてはね。じゃあ質問。これに変数を足してくれる？

**後輩:** LLM に頼めばいいですよね。「変数 `$x` を追加して」って。

**先輩:** うん、追加してくれる。じゃあ次、三項演算子 `condition ? a : b` を足して。そのあと `if/else` も。文字列型も。比較演算子も。メソッド定義も。外部 Java メソッド呼び出しも。型ヒントも。インポート文も。

**後輩:** ……それ全部足すんですか？

**先輩:** tinyexpression の UBNF 文法は 350 行以上あって、26種類の AST ノードを定義してる。LLM に「全部足して」って言ったらどうなると思う？

**後輩:** うーん……多分、途中で壊れますね。

**先輩:** そう。LLM の得意なことと苦手なことを整理しよう。

| LLM が得意なこと | LLM が苦手なこと |
|:---|:---|
| 既知パターンに従ったコード生成 | 一貫したアーキテクチャの維持 |
| 短い関数の実装 | 20以上のノード型の整合性管理 |
| ドキュメント化されたAPIの利用 | 新しい構造の「発明」 |
| バグの局所修正 | パーサー/AST/マッパー/エバリュエータの同期 |

**後輩:** 「構造の発明」が苦手ってどういう意味ですか？

**先輩:** LLM はトレーニングデータにあるパターンを再現するのが得意。でも「sealed interface で 26 ノードの AST を設計して、それに対応する exhaustive な switch を持つ Evaluator 基底クラスを生成して、GGP で手書き部分と分離して」なんて構造は、頼まないと出てこない。そして頼んでも、途中で整合性が崩れる。

**後輩:** でも、やれって言えばやるんじゃ……

**先輩:** やるよ。でも比べてみよう。Unlaxer で同じことをやるとこうなる。

```ubnf
grammar TinyExpressionP4 {

  @package: org.unlaxer.tinyexpression.generated.p4
  @whitespace: javaStyle
  @comment: { line: '//' }

  token NUMBER     = NumberParser
  token IDENTIFIER = IdentifierParser
  token STRING     = SingleQuotedParser

  @root
  Formula ::= { CodeBlock } { ImportDeclaration } { VariableDeclaration }
              { Annotation } [ Expression ] { MethodDeclaration } ;

  // 四則演算
  @mapping(BinaryExpr, params=[left, op, right])
  @leftAssoc
  @precedence(level=10)
  NumberExpression ::= NumberTerm @left { AddOp @op NumberTerm @right } ;

  @mapping(BinaryExpr, params=[left, op, right])
  @leftAssoc
  @precedence(level=20)
  NumberTerm ::= NumberFactor @left { MulOp @op NumberFactor @right } ;

  AddOp ::= '+' | '-' ;
  MulOp ::= '*' | '/' ;

  // 変数参照
  @mapping(VariableRefExpr, params=[name])
  VariableRef ::= '$' IDENTIFIER @name [ TypeHint ] ;

  // 三項演算子
  @mapping(TernaryExpr, params=[condition, thenExpr, elseExpr])
  TernaryExpression ::=
    BooleanFactor @condition '?' NumberExpression @thenExpr ':' NumberExpression @elseExpr ;

  // ... 残り300行の文法定義
}
```

**後輩:** 文法ファイル1つですね。

**先輩:** この 350 行の UBNF から、以下が自動生成される。

| 生成物 | 内容 | 行数 (目安) |
|:---|:---|:---|
| `TinyExpressionP4Parsers.java` | パーサーコンビネータ群 | ~800行 |
| `TinyExpressionP4AST.java` | sealed interface + 26 records | ~400行 |
| `TinyExpressionP4Mapper.java` | Token → AST 変換 | ~600行 |
| `TinyExpressionP4Evaluator.java` | abstract 基底クラス（GGP用） | ~200行 |
| LSP/DAP サーバー | Language Server + Debugger | ~500行 |

**後輩:** 合計 2,500 行以上が 350 行の文法から出てくる……

**先輩:** しかも全部が型で繋がってる。AST の record を1つ追加したら、Mapper にも Evaluator にもコンパイルエラーが出て、実装漏れを教えてくれる。LLM が書いた Calculator クラスでこれやろうとしたら？

**後輩:** 全部手動で整合性を取らないといけない。

**先輩:** そういうこと。LLM は「電卓を作って」には答えられる。でも「言語処理系を設計して」には答えられない。パターンの再現と構造の設計は別物なんだ。

---

## Part 2: Token効率の話

**後輩:** でも先輩、ぶっちゃけ LLM にお願いし続ければ最終的にはできるんじゃないですか？ 時間はかかるけど。

**先輩:** できるよ。問題は「どれだけコストがかかるか」。ここで token 効率の話をしよう。

**後輩:** token？ API のコストってことですか？

**先輩:** それもあるけど、もっと本質的な話。LLM とのやり取りには「token」っていう単位がある。入力も出力も全部 token でカウントされる。これは直接コストに跳ねるし、コンテキストウィンドウの消費にも関わる。

実際のデータを見てみよう。今回、tinyexpression の P4 バックエンドを作るのにかかった token 数。

**後輩:** 測ったんですか？

**先輩:** 概算だけどね。

### フレームワークなしで LLM に全部書かせた場合

```
想定 token 消費: ~30,000 tokens

内訳:
  - 初期実装の説明・依頼          5,000 tokens
  - LLM が生成したコード          8,000 tokens
  - バグ報告 + エラーメッセージ    3,000 tokens
  - LLM の修正コード              4,000 tokens
  - 2回目のバグ報告               2,000 tokens
  - 2回目の修正                   3,000 tokens
  - 3回目のバグ + 修正            3,000 tokens
  - テスト結果のフィードバック     2,000 tokens
  ──────────────────────────────────
  バグ修正の往復:     ~10,000 tokens (全体の33%)
```

**後輩:** 33% がバグ修正！

**先輩:** そう。LLM が書いたコードには「パーサーの優先度が逆」「AST ノードの型が不一致」「switch の case 漏れ」みたいなバグが必ず入る。それを報告して、修正してもらって、また別のバグが出て……この往復が token を食う。

### Unlaxer + LLM の場合

```
実績 token 消費: ~3,000 tokens

内訳:
  - 「P4TypedAstEvaluator を実装して」      500 tokens
  - LLM の実装コード                       1,200 tokens
  - 「P4TypedJavaCodeEmitter も」            300 tokens
  - LLM の実装コード                         800 tokens
  - 軽微なバグ修正 1回                       200 tokens
  ──────────────────────────────────────
  バグ修正の往復:       ~200 tokens (全体の5%未満)
```

**後輩:** 10分の1！ でも……なんでそんなに差が出るんですか？

**先輩:** 理由は3つある。

**理由1: 型がガイドレールになる。**

Unlaxer が生成した `TinyExpressionP4Evaluator<T>` は abstract クラスで、26個の `evalXxx()` メソッドが全部 abstract として定義されてる。LLM は「このメソッドを全部実装してね」って言われるだけでいい。何を実装すべきか考えなくていい。

```java
// 生成コード: TinyExpressionP4Evaluator.java
public abstract class TinyExpressionP4Evaluator<T> {

    public T eval(TinyExpressionP4AST node) {
        return switch (node) {
            case BinaryExpr n       -> evalBinaryExpr(n);
            case VariableRefExpr n  -> evalVariableRefExpr(n);
            case IfExpr n           -> evalIfExpr(n);
            case TernaryExpr n      -> evalTernaryExpr(n);
            case SinExpr n          -> evalSinExpr(n);
            case StringConcatExpr n -> evalStringConcatExpr(n);
            // ... 全26ケース、コンパイラが網羅性を保証
        };
    }

    protected abstract T evalBinaryExpr(BinaryExpr node);
    protected abstract T evalVariableRefExpr(VariableRefExpr node);
    protected abstract T evalIfExpr(IfExpr node);
    // ... 全26メソッド
}
```

LLM はこの abstract メソッドのシグネチャを見て、1つずつ実装するだけ。「どんな AST ノードがあるか」を推測する必要がない。

**理由2: バグが構造レベルで発生しない。**

パーサーと AST の不整合は生成時点で検出される。Mapper の変換漏れもコンパイルエラーになる。LLM が「switch の case を1つ忘れた」らコンパイラが教えてくれる。だからバグ修正の往復が激減する。

**理由3: コンテキストが小さい。**

LLM に渡すのは「生成された abstract クラス」と「こういう風に実装して」という指示だけ。パーサーの仕組みも、AST の構造も、マッパーの実装も渡す必要がない。全部フレームワークが面倒を見てるから。

**後輩:** なるほど……でも、Unlaxer を理解するのにも token 使いますよね？ LLM に「Unlaxer って何？ UBNF って何？」って説明するコストがあるじゃないですか。

**先輩:** 鋭い指摘。初回コストはある。Unlaxer の基本概念を LLM に伝えるのに 500〜1,000 tokens くらいかかる。でもこれは1回きりのコスト。

考えてみて。フレームワークなしだと、機能を追加するたびに「パーサー修正 → AST 修正 → マッパー修正 → エバリュエータ修正」の4段階で往復が発生する。Unlaxer だと UBNF を修正して再生成するだけ。追加するたびにコスト差が広がる。

```
累積 token コスト:

機能追加    フレームワークなし    Unlaxer + LLM
────────    ──────────────────    ──────────────
1回目       30,000                3,000 + 1,000(学習) = 4,000
2回目       25,000                2,500
3回目       25,000                2,500
4回目       20,000                2,000
5回目       20,000                2,000
────────    ──────────────────    ──────────────
合計       120,000               13,000
```

**後輩:** 5回の機能追加で10倍近い差ですか……

**先輩:** しかもフレームワークなしの方は「前のバグ修正で別のバグが入る」っていう退行リスクもある。Unlaxer だと再生成されるコードは常に文法と一致してるから、退行しない。

**後輩:** 積み上がり部分が圧倒的に少ない、ってそういうことですね。

**先輩:** そう。LLM の token は安くなる一方だけど、「ゼロ」にはならない。10倍の効率差は、token 単価がいくら下がっても消えない。

---

## Part 3: 型の強制力

**後輩:** 先輩、さっき「型がガイドレール」って言ってましたけど、テストでも同じことできませんか？ テスト駆動で LLM にコードを書かせれば、バグも見つかるし。

**先輩:** いい質問。テストと型の違いを説明しよう。

**後輩:** 違いがあるんですか？ どっちも「コードが正しいか検証する手段」ですよね？

**先輩:** テストは**確認**、型は**証明**。確認と証明は違う。

**後輩:** 哲学的ですね……具体的に言うと？

**先輩:** OK、Unlaxer が使っている4つの型安全メカニズムを見てみよう。

### メカニズム 1: sealed interface の exhaustive switch

```java
// TinyExpressionP4AST.java（生成コード）
public sealed interface TinyExpressionP4AST permits
    BinaryExpr, VariableRefExpr, IfExpr, TernaryExpr,
    SinExpr, CosExpr, TanExpr, SqrtExpr, MinExpr, MaxExpr,
    RandomExpr, StringConcatExpr, StringLiteralExpr,
    ComparisonExpr, AndExpr, OrExpr, NotExpr,
    // ... 全26ノード
    ExpressionExpr {
    // ...
}
```

```java
// TinyExpressionP4Evaluator.java（生成コード）
private T evalInternal(TinyExpressionP4AST node) {
    return switch (node) {
        case BinaryExpr n       -> evalBinaryExpr(n);
        case VariableRefExpr n  -> evalVariableRefExpr(n);
        case IfExpr n           -> evalIfExpr(n);
        // ... 全26ケース
    };
    // ↑ case を1つでも忘れたらコンパイルエラー！
}
```

**後輩:** Java 21 の sealed switch ですね。permits に含まれるすべての型を case で網羅しないとコンパイルが通らない。

**先輩:** そう。テストだと「このケースのテストを書き忘れた」が起きうる。でも sealed switch は忘れようがない。コンパイラが許さない。

**後輩:** でも、case の中身が間違ってる可能性はありますよね？

**先輩:** もちろん。型は「構造の整合性」を保証するだけで、「意味の正しさ」はテストが必要。でも「構造が正しい」と「意味が正しい」のどっちが先に壊れるか考えてみて。

**後輩:** 構造ですね。case を忘れたとか、型が合わないとか。

**先輩:** そう。一番多いバグを一番早い段階（コンパイル時）で潰せるのが型の強み。

### メカニズム 2: record による typo 防止

```java
// 生成された record
public record BinaryExpr(
    BinaryExpr left,
    List<String> op,
    List<BinaryExpr> right
) implements TinyExpressionP4AST {}
```

```java
// LLM が書くコード — typo したら？
Object result = node.lefft();  // コンパイルエラー！ left() の typo
Object result = node.left();   // OK
```

**後輩:** ああ、record だからアクセサメソッドが自動生成されて、typo がコンパイルエラーになる。

**先輩:** Map でデータを持ち回す方式だと typo は実行時エラー。しかも `NullPointerException` とか `ClassCastException` とかいう、原因が分かりにくいエラーになる。record なら一発でわかる。

**後輩:** リフレクションで AST を扱ってたら同じ問題がありますよね。

**先輩:** 実は tinyexpression には以前リフレクションベースのエバリュエータがあった。フィールド名を文字列で指定して `getClass().getDeclaredField("left")` とかやってた。これが遅い上に、typo が実行時まで分からない。P4TypedAstEvaluator はこれを record のパターンマッチで置き換えた。

### メカニズム 3: @mapping の整合性チェック

```ubnf
@mapping(BinaryExpr, params=[left, op, right])
NumberExpression ::= NumberTerm @left { AddOp @op NumberTerm @right } ;
```

**後輩:** これ、もし `@mapping` のパラメータ名と文法中の `@xxx` がずれてたらどうなるんですか？

**先輩:** 生成時にエラーになる。

```
Error: @mapping parameter 'lefft' has no corresponding @binding in rule 'NumberExpression'
       Available bindings: left, op, right
```

**後輩:** 生成時に！ ビルドする前に！

**先輩:** そう。UBNF を処理する時点で、文法と AST の不整合が検出される。LLM にコードを書いてもらう段階ですでに整合性が保証されてる。

### メカニズム 4: GGP（Generation Gap Pattern）の安全網

```
[生成コード] TinyExpressionP4Evaluator<T>  ← abstract, 毎回再生成
       ↑ extends
[手書きコード] P4TypedAstEvaluator          ← concrete, 人間/LLM が書く
```

**後輩:** GGP は前のチュートリアルでも出てきましたね。

**先輩:** GGP のポイントは「再生成しても手書き部分が壊れない」こと。UBNF を変更して再生成したら、基底クラスに新しい abstract メソッドが増える。手書きのサブクラスでそれを実装し忘れたら、コンパイルエラー。

**後輩:** テストだとどうなりますか？

**先輩:** テストだと「新しいノード型を追加したけど、テストケースを追加し忘れた」が普通に起きる。テストスイートが緑のまま、実は新機能が一切テストされてないっていう状態になる。

**後輩:** あー、それは確かにありますね。「テスト通ってるから大丈夫」って思ったら、そもそもテストが足りなかったっていう。

**先輩:** 型は「テストを書き忘れた」状態を許さない。sealed switch は全ケースを要求するし、abstract メソッドは全実装を要求する。

**後輩:** テストは確認、型は証明。証明の方が強い……確かにそうですね。

**先輩:** 具体例を出そう。P4TypedAstEvaluator には 28 個の `evalXxx()` メソッドがある。

```java
// P4TypedAstEvaluator.java の evalXxx() メソッド一覧（28個）
evalBinaryExpr(BinaryExpr)
evalVariableRefExpr(VariableRefExpr)
evalIfExpr(IfExpr)
evalTernaryExpr(TernaryExpr)
evalSinExpr(SinExpr)
evalCosExpr(CosExpr)
evalTanExpr(TanExpr)
evalSqrtExpr(SqrtExpr)
evalMinExpr(MinExpr)
evalMaxExpr(MaxExpr)
evalRandomExpr(RandomExpr)
evalToNumExpr(ToNumExpr)
evalStringConcatExpr(StringConcatExpr)
evalStringLiteralExpr(StringLiteralExpr)
evalComparisonExpr(ComparisonExpr)
evalAndExpr(AndExpr)
evalOrExpr(OrExpr)
evalNotExpr(NotExpr)
evalMethodInvocationExpr(MethodInvocationExpr)
evalExternalBooleanInvocationExpr(ExternalBooleanInvocationExpr)
evalExternalNumberInvocationExpr(ExternalNumberInvocationExpr)
evalExternalStringInvocationExpr(ExternalStringInvocationExpr)
evalExternalObjectInvocationExpr(ExternalObjectInvocationExpr)
evalSideEffectNumberExpr(SideEffectNumberExpr)
evalSideEffectStringExpr(SideEffectStringExpr)
evalSideEffectBooleanExpr(SideEffectBooleanExpr)
evalNumberMatchExpr(NumberMatchExpr)
evalStringMatchExpr(StringMatchExpr)
```

**後輩:** 28 個！ テストで全部網羅するのは大変ですね。

**先輩:** テストも当然書くよ。でも「28 個の eval メソッドが存在すること」はテストじゃなくてコンパイラが保証してくれる。テストは「各 eval の計算結果が正しいこと」の確認に集中できる。

**後輩:** 責任の分離ですね。構造はコンパイラ、意味はテスト。

**先輩:** そう。で、LLM にコードを書かせるとき、コンパイラが「構造」を保証してくれるから、LLM は「意味」に集中できる。これが token 効率に直結する。

---

## Part 4: 他のフレームワーク（ANTLR等）+ LLM vs Unlaxer + LLM

**後輩:** 先輩、フレームワークが大事なのは分かりました。でも Unlaxer じゃなくてもいいですよね？ ANTLR とか Tree-sitter とか、メジャーなやつがあるじゃないですか。

**先輩:** うん、当然その比較は必要だね。LLM と組み合わせたときにどうなるか、考えてみよう。

### ANTLR + LLM

**後輩:** ANTLR は有名ですよね。Stack Overflow にもたくさん回答がある。

**先輩:** ANTLR は素晴らしいパーサージェネレータ。文法を書けばパーサーが生成される。Visitor と Listener パターンが自動生成されるから、木の走査は楽にできる。

**後輩:** じゃあ LLM に ANTLR の文法を書かせて、生成されたコードを使えば……

**先輩:** パーサーまではいい。でもその先を考えてみて。

```
ANTLR が生成してくれるもの:
  ✅ Lexer
  ✅ Parser
  ✅ Visitor/Listener の基底クラス

ANTLR が生成してくれないもの:
  ❌ 型付き AST（sealed interface + records）
  ❌ パースツリー → AST のマッパー
  ❌ Evaluator の基底クラス
  ❌ LSP サーバー
  ❌ DAP サーバー
```

**後輩:** あー、Visitor はあるけど、その先は全部手書き……

**先輩:** そう。ANTLR + LLM だと、LLM には「ANTLR の Visitor を使って型付き AST を作る」っていうタスクを依頼することになる。できるけど、型の強制がない。Visitor のメソッドを1つ忘れても、コンパイルは通る（デフォルト実装が null を返すだけだから）。

```java
// ANTLR の Visitor — case 漏れがコンパイルエラーにならない
public class MyVisitor extends CalcBaseVisitor<Object> {
    @Override
    public Object visitAddExpr(CalcParser.AddExprContext ctx) {
        // 実装する
    }
    // visitMulExpr を忘れても、コンパイルは通る！
    // デフォルトの visitChildren(ctx) が呼ばれるだけ
}
```

**後輩:** それは確かに怖い。

### Tree-sitter + LLM

**先輩:** Tree-sitter はエディタ統合に特化してる。増分パースが得意で、VS Code や Neovim のシンタックスハイライトに使われてる。

**後輩:** それは良さそうですね。

**先輩:** エディタ統合は強い。でも問題がいくつかある。

```
Tree-sitter の特徴:
  ✅ 増分パース（エディタ向き）
  ✅ 複数言語のバインディング（C, Rust, JS, ...）
  ❌ Java ネイティブではない（C ベース + バインディング）
  ❌ AST の型安全性がない（汎用ノード型）
  ❌ Evaluator は完全に別の話
  ❌ LSP は手書き
  ❌ DAP は別世界
```

**後輩:** Java じゃないんですか。

**先輩:** そう。Tree-sitter は C で書かれていて、Java バインディングはコミュニティ製のものがある程度。JNI 経由のオーバーヘッドもある。tinyexpression みたいに「Java エコシステムの中で完結したい」場合には向かない。

### Unlaxer + LLM

**先輩:** 比較表にまとめよう。

```
┌─────────────────────┬──────────────┬──────────────┬──────────────┐
│                     │  ANTLR + LLM │ Tree-sitter  │ Unlaxer + LLM│
│                     │              │   + LLM      │              │
├─────────────────────┼──────────────┼──────────────┼──────────────┤
│ パーサー生成         │ ✅ 堅い      │ ✅ 堅い      │ ✅ 堅い      │
│ 型付き AST          │ ❌ 手書き    │ ❌ 汎用ノード │ ✅ 自動生成  │
│ AST マッパー         │ ❌ 手書き    │ ❌ 手書き    │ ✅ 自動生成  │
│ Evaluator 基底      │ ❌ なし      │ ❌ なし      │ ✅ 自動生成  │
│ exhaustive switch   │ ❌           │ ❌           │ ✅           │
│ GGP                 │ ❌           │ ❌           │ ✅           │
│ LSP                 │ ❌ 手書き    │ △ 部分的    │ ✅ 自動生成  │
│ DAP                 │ ❌ 手書き    │ ❌ 手書き    │ ✅ 自動生成  │
│ Java ネイティブ      │ ✅           │ ❌ C + JNI   │ ✅           │
│ LLM の学習コスト    │ 低い         │ 中程度       │ 中程度       │
│ LLM がバグを入れる  │ AST/Eval層   │ 全層         │ eval 層のみ  │
│   余地がある層       │              │              │              │
└─────────────────────┴──────────────┴──────────────┴──────────────┘
```

**後輩:** 「LLM がバグを入れる余地がある層」が面白いですね。Unlaxer だと eval 層だけ。

**先輩:** そう。パーサー、AST、マッパーは生成されるから、LLM がバグを入れようがない。LLM が触れるのは evalXxx() の中身だけ。で、その中身も型で制約されてる。バグの入り込む余地が構造的に小さい。

**後輩:** でも ANTLR には Stack Overflow の回答がありますよ。LLM のトレーニングデータにもたくさん含まれてるはず。Unlaxer は……。

**先輩:** ユーザーが1人だからトレーニングデータにない。言いたいことは分かるよ。

**後輩:** はい、そこが心配で。

**先輩:** でもね、LLM はドキュメントより**型を読む方が得意**だよ。

**後輩:** え？

**先輩:** Stack Overflow の回答は自然言語。「こういう場合はこうしてください」って書いてある。でもそれを LLM が正しく解釈するかどうかはわからない。一方、Java の型情報は曖昧さがない。

```java
// LLM にとってこれは「完全な仕様書」
protected abstract Object evalBinaryExpr(BinaryExpr node);

// BinaryExpr の定義:
public record BinaryExpr(
    BinaryExpr left,
    List<String> op,
    List<BinaryExpr> right
) implements TinyExpressionP4AST {}
```

**後輩:** 確かに……引数の型と戻り値の型が分かれば、LLM は何をすべきか推測できる。

**先輩:** `BinaryExpr` の `left` が `BinaryExpr` 型、`op` が `List<String>`、`right` が `List<BinaryExpr>`。再帰構造で、演算子は文字列リスト。LLM はこの型情報だけで「ああ、left を再帰的に eval して、op と right をペアにして順番に適用すればいいんだな」って分かる。

**後輩:** Stack Overflow の回答がなくても、型が語る。

**先輩:** そういうこと。ANTLR の知名度は武器だけど、LLM にとっては「型による制約」の方が「過去の回答の記憶」より信頼できるガイドなんだ。

---

## Part 5: LLMが実際にUnlaxerで何をしたか（今回の実例）

**後輩:** 先輩、理論はわかりました。でも実際に LLM が Unlaxer を使って何か作ったんですか？ 具体的な成果を見せてほしいです。

**先輩:** 見せるよ。今回、1つの LLM セッション（数時間）で以下を全部やった。

### 成果一覧

```
1. MapperGenerator のバグ修正
   - allMappingRules() が直接の子孫以外を拾ってしまう問題
   - findDirectDescendants() の再帰ロジック修正
   → 生成される Mapper の品質が向上

2. P4TypedAstEvaluator の実装
   - TinyExpressionP4Evaluator<Object> を extends
   - 28個の evalXxx() メソッドを全実装
   - リフレクション完全排除、sealed switch でディスパッチ

3. P4TypedJavaCodeEmitter の実装
   - TinyExpressionP4Evaluator<String> を extends
   - AST → Java ソースコード変換
   - 型情報を使った安全なコード生成

4. P4DefaultJavaCodeEmitter の実装
   - デフォルト値ベースの簡易コード生成

5. UBNF 文法の Expression 順序修正
   - NumberFactor 内の選択肢の優先度を修正
   - TernaryExpression を先に試すように変更

6. DAP デフォルトを ast-evaluator に変更
   - デバッガのデフォルトバックエンドを変更
```

**後輩:** 6個もタスクを！ で、どれくらい時間がかかったんですか？

**先輩:** 全部合わせて数時間。重要なのは、これが可能だった理由。

**後輩:** フレームワークがあったから？

**先輩:** そう。1つずつ見てみよう。

### P4TypedAstEvaluator — なぜ数時間で28メソッド書けたか

**先輩:** LLM に渡した情報はこれだけ。

```
1. TinyExpressionP4Evaluator.java（生成された基底クラス）  — 型情報
2. TinyExpressionP4AST.java（生成された AST 定義）         — record 構造
3. 「BinaryExpr は再帰的な四則演算」という1行の説明
4. 「VariableRefExpr は CalculationContext から値を取る」という1行の説明
```

**後輩:** それだけ？

**先輩:** それだけ。LLM は型情報から「何をすべきか」を推測して、28 個のメソッドを全部書いた。コンパイルエラーは0。論理バグも1つだけ（leaf ノードの判定で BinaryExpr の特殊ケース処理が不足していた）。

**後輩:** フレームワークなしだったら？

**先輩:** まず「AST をどういう構造にするか」から議論が始まる。「sealed interface にする？ abstract class にする？ Visitor パターンにする？」って。その議論だけで数千 token 消費する。で、決まった構造を実装してもらっても、パーサーとの整合性は手動チェック。

### ベンチマーク: リフレクション版の1400倍速

**先輩:** BackendSpeedComparisonTest の結果を見せよう。

```
┌──────────────────────┬────────────────┬───────────┐
│ バックエンド          │ 実行時間/call  │ 相対速度   │
├──────────────────────┼────────────────┼───────────┤
│ compile-hand (JIT)   │    0.02 μs     │   1.0x    │
│ P4-typed-eval (new)  │    0.15 μs     │   7.5x    │
│ P4-typed-reuse       │    0.08 μs     │   4.0x    │
│ ast-hand-cached      │    0.30 μs     │  15.0x    │
│ ast-hand-full        │    5.00 μs     │ 250.0x    │
│ P4-reflection        │  110.00 μs     │ 5500.0x   │
└──────────────────────┴────────────────┴───────────┘

P4-typed-reuse vs P4-reflection = 約1,400倍高速
```

**後輩:** 1400倍！？

**先輩:** リフレクションで `getClass().getDeclaredField()` を毎回呼ぶのと、sealed switch でコンパイル時にディスパッチが確定してるのとでは、そのくらい差が出る。JIT も sealed switch の方が最適化しやすい。

**後輩:** つまり LLM が書いた P4TypedAstEvaluator は、人間が書いた ast-hand-cached よりも速い？

**先輩:** そう。理由は record のアクセスがフィールド直読みだから。手書きの AST ノードは interface 経由で virtual dispatch が入ってた。record + sealed switch は JIT にとって最高のおもてなし。

**後輩:** LLM が書いたコードの方が速い……。

**先輩:** 正確には「LLM が**フレームワークの型に従って**書いたコードの方が速い」。フレームワークが record と sealed interface を使うように強制してるから、LLM はパフォーマンスのことを考えなくても高速なコードが出てくる。

### UBNF 文法修正 — 人間の判断 + LLM の実行

**先輩:** Expression の順序修正は面白い例だった。

```ubnf
// 修正前: TernaryExpression が NumberExpression の後にくる
NumberFactor ::=
    NumberMatchExpression
  | IfExpression
  | MathFunction
  | NUMBER
  | VariableRef
  | MethodInvocation
  | TernaryExpression      // ← ここだと NumberExpression が先にマッチして
  | '(' NumberExpression ')' ;  //   条件部分を数値として食ってしまう

// 修正後: TernaryExpression を先に試す
NumberFactor ::=
    TernaryExpression      // ← 先に試す
  | NumberMatchExpression
  | IfExpression
  | MathFunction
  | NUMBER
  | VariableRef
  | MethodInvocation
  | '(' NumberExpression ')' ;
```

**後輩:** ordered choice だから順番が大事なんですね。

**先輩:** そう。この「順番の問題」は LLM に「なんか三項演算子が動かないんだけど」って言えば修正案を出してくれる。でもそれは UBNF の文法ファイルを直すだけで済む。パーサーやマッパーの修正は不要。再生成すれば全部直る。

**後輩:** フレームワークなしだと、パーサーの再帰下降のコードを直して、AST の構築コードを直して、Evaluator も直して……

**先輩:** しかも「直した結果、他のテストが壊れる」っていうリスクと戦いながらね。UBNF なら文法の1行を移動するだけ。影響範囲がゼロ。

### 全部1セッションで完了

**後輩:** これが全部、1回のセッションでできたと。

**先輩:** フレームワークがなければ無理だった。特に MapperGenerator のバグ修正は、Unlaxer のコード生成パイプラインの内部を理解する必要があった。でも LLM は `allMappingRules()` と `findDirectDescendants()` のシグネチャと、テストの期待値を見て、何が間違ってるか推測できた。

**後輩:** 型情報がガイドになってる、ここでも。

**先輩:** そう。`allMappingRules()` が `List<MappingRule>` を返して、`findDirectDescendants()` が `List<GrammarRule>` を返す。型を見れば「あ、MappingRule と GrammarRule の関係が怪しい」って分かる。

---

## Part 6: 生成AI時代のフレームワークの価値

**後輩:** 先輩、ここまでの話を聞いて思ったんですけど、LLM 時代だからフレームワークが要らなくなる、って逆なんですね。

**先輩:** そう。**逆説的に、LLM 時代だからこそフレームワークの価値が上がる**。

**後輩:** なんでですか？

**先輩:** LLM の最大の強みは「正しいパターンに従ったコード生成」だって話したよね。じゃあ「正しいパターン」は誰が定義するの？

**後輩:** ……フレームワーク？

**先輩:** そう。フレームワークが「正しいパターン」を型として定義して、LLM はそのパターンに従ってコードを埋める。これが最も効率的な分業。

```
人間の仕事:
  1. 何をパースするか決める（言語設計）
  2. UBNF 文法を書く
  3. @mapping でASTの形を決める
  4. 生成されたコードをレビューする

フレームワーク（Unlaxer）の仕事:
  1. UBNF → パーサー生成
  2. UBNF → AST 生成（sealed interface + records）
  3. UBNF → マッパー生成
  4. UBNF → Evaluator 基底クラス生成
  5. UBNF → LSP サーバー生成
  6. UBNF → DAP サーバー生成

LLM の仕事:
  1. evalXxx() メソッドの中身を実装
  2. テストの実装
  3. ドキュメントの執筆
  4. バグの局所修正
```

**後輩:** 三者の役割が明確ですね。

### @eval strategy: 未来の自動化

**先輩:** もっと先の話をすると、Unlaxer には `@eval` strategy というロードマップがある。

```ubnf
// 将来の UBNF 構文（構想中）
@mapping(BinaryExpr, params=[left, op, right])
@eval(strategy=binary_arithmetic, left=left, op=op, right=right)
@leftAssoc
@precedence(level=10)
NumberExpression ::= NumberTerm @left { AddOp @op NumberTerm @right } ;
```

**後輩:** `@eval(strategy=binary_arithmetic)` ですか。これは……

**先輩:** 評価戦略を文法に直接書く。`binary_arithmetic` なら「left を再帰的に eval して、op と right をペアにして四則演算する」っていう定型パターンを Evaluator が自動実装する。

```java
// @eval strategy で自動生成されるコード（将来）
@Override
protected Object evalBinaryExpr(BinaryExpr node) {
    if (node.left() == null && node.right().isEmpty()) {
        return evalLeaf(node.op().get(0));  // leaf: literal or variable
    }
    Object current = eval(node.left());
    for (int i = 0; i < node.op().size(); i++) {
        Object right = eval(node.right().get(i));
        current = applyBinaryOp(node.op().get(i), current, right);
    }
    return current;
}
```

**後輩:** これが実現したら、LLM が evalXxx() を書く必要すらなくなる？

**先輩:** 定型パターンについてはね。`binary_arithmetic` とか `variable_lookup` とか `passthrough` とか、よくあるパターンは自動化できる。

| strategy | 意味 | 例 |
|:---|:---|:---|
| `binary_arithmetic` | 二項演算の再帰評価 | `1 + 2 * 3` |
| `variable_lookup` | コンテキストから変数解決 | `$price` |
| `literal` | リテラル値をそのまま返す | `42`, `'hello'` |
| `function_call` | 関数呼び出し | `sin($x)` |
| `conditional` | 条件分岐 | `if/else`, `? :` |
| `passthrough` | 子ノードをそのまま返す | ラッパールール |
| `invocation` | メソッド呼び出し | `call myFunc()` |

**後輩:** 7つの strategy で大部分カバーできそうですね。

**先輩:** P4TypedAstEvaluator の 28 メソッドのうち、20 以上はこれらの strategy で自動生成できる見込み。残りの特殊ケースだけ LLM か人間が書く。

### JavaCodeBuilder: コード生成コードすら型安全に

**後輩:** Unlaxer って、コード生成のコードも生成するんですよね。そこも型安全なんですか？

**先輩:** `JavaCodeBuilder` がある。

```java
// JavaCodeBuilder の使い方
JavaCodeBuilder java = new JavaCodeBuilder("com.example");
java.imports("java.util.List", "java.util.Optional");
java.publicClass("MyMapper", cls -> {
    cls.field("private static final", "Map<String,String>", "CACHE", "new HashMap<>()");
    cls.blankLine();
    cls.method("public static", "MyAST", "parse", m -> {
        m.param("String", "source");
        m.body(b -> {
            b.varDecl("Parser", "parser", "getParser()");
            b.ifBlock("parser == null", ib -> {
                ib.throwNew("IllegalArgumentException", "\"No parser\"");
            });
            b.returnStmt("parser.parse(source)");
        });
    });
});
String javaSource = java.build();
```

**後輩:** `StringBuilder` で文字列結合するんじゃなくて、構造化された API でコードを組み立てるんですね。

**先輩:** そう。`cls.method()` の中で `m.body()` を書かないとコンパイルエラー。インデントは自動。閉じ括弧も自動。LLM がこの API を使ってコード生成ロジックを書くと、構文的に壊れた Java コードが出力される心配がない。

**後輩:** 型で正しさを強制する、っていう思想が徹底してますね。

### 未来: UBNF から全部手に入る世界

**先輩:** 最終的な目標はこう。

```
UBNF 文法ファイル (350行)
  ↓ unlaxer-dsl codegen
  ├── Parsers.java        — パーサーコンビネータ群
  ├── AST.java            — sealed interface + records
  ├── Mapper.java         — Token → AST 変換
  ├── Evaluator.java      — abstract 基底 (@eval で大半自動)
  ├── LSPServer.java      — Language Server Protocol
  ├── DAPServer.java      — Debug Adapter Protocol
  └── VSCode Extension    — エディタプラグイン

人間が書くもの:
  - UBNF 文法
  - 特殊な eval ロジック（@eval でカバーできないもの）

LLM が書くもの:
  - 特殊な eval ロジックの実装
  - テスト
  - ドキュメント
```

**後輩:** UBNF を書くだけで言語処理系 + LSP + DAP が手に入る。それってつまり……

**先輩:** 「自分だけの言語を作る」ハードルが劇的に下がる。

**後輩:** でも先輩、そうなると LLM が UBNF 文法を書けば、人間すら要らなくなるんじゃ……

**先輩:** ……。

**後輩:** すみません、言い過ぎました？

**先輩:** いや、大事な問いだよ。答えはこう。**文法設計は人間の仕事**。何をパースするか決めるのは AI じゃない。

**後輩:** なぜですか？ LLM に「Excel の式言語を作って」って言えば……

**先輩:** 「Excel の式言語」って何？ `=SUM(A1:A10)` もサポートするの？ `=VLOOKUP` は？ `=LAMBDA` は？ 配列数式は？ どこまでが「Excel の式言語」なのかを決めるのは、ビジネス要件であって、技術の問題じゃない。

**後輩:** ああ……そうですね。「何を作るか」は人間が決める。

**先輩:** tinyexpression が「`$` プレフィックスで変数を参照する」と決めたのは技術的な理由じゃなくて、Excel のセル参照と紛れないようにっていう運用上の理由。`if/else` を式にしたのも、tinyexpression が「式言語」だから。これらは設計判断であって、コード生成の問題じゃない。

**後輩:** 人間が設計して、フレームワークが構造を保証して、LLM が実装する。

**先輩:** そう。でも実装だけは任せていい。P4TypedAstEvaluator の 28 メソッドを一個一個考えるのは人間の仕事じゃない。型が教えてくれるシグネチャに従って中身を書くのは、LLM の方がミスが少ないくらいだ。

**後輩:** 人間にしかできないことに集中する、と。

**先輩:** そういうこと。

---

## Part 7: ユーザー1人問題

**後輩:** 先輩……。

**先輩:** ん？

**後輩:** いいこと言ってるんですけど……Unlaxer のユーザー、先輩1人じゃないですか？

**先輩:** ……知ってた？

**後輩:** GitHub 見ました。Star 1。

**先輩:** その Star は僕のだよ。

**後輩:** ……。

**先輩:** ……。

**後輩:** いや、笑い事じゃなくて。こんなに良いフレームワークなのに、誰も使ってないのは問題じゃないですか？ ANTLR は何千もの Star があって、企業でも使われてて。

**先輩:** 正しいアーキテクチャに人数は関係ない。

**後輩:** かっこいいこと言ってますけど、現実問題として、ユーザーが1人のフレームワークに依存するのはリスクですよ。メンテナンスが止まったら？ バグがあったら？

**先輩:** うん、それは正当な懸念。でもいくつか反論させて。

### 反論 1: 生成されたコードは依存しない

**先輩:** Unlaxer が生成するコードは、Unlaxer 自体に依存しない。

**後輩:** え？

**先輩:** 生成された `TinyExpressionP4Parsers.java` は `unlaxer-common` のパーサーコンビネータを使ってるけど、AST の record 群は純粋な Java コード。`TinyExpressionP4AST.java` は sealed interface と record だけで構成されてて、外部依存がゼロ。

```java
// 生成された AST — 外部依存なし
public sealed interface TinyExpressionP4AST permits ... {
    record BinaryExpr(BinaryExpr left, List<String> op, List<BinaryExpr> right)
        implements TinyExpressionP4AST {}
    // ...
}
```

**後輩:** 確かに、Java 標準ライブラリだけですね。

**先輩:** 仮に Unlaxer のメンテナンスが止まっても、生成済みのコードはそのまま動く。最悪、UBNF は捨てて生成コードだけ手動メンテナンスに切り替えればいい。ロックインが小さい。

### 反論 2: Scala の parser combinator が羨ましかった

**先輩:** ちょっと昔話をさせて。僕が Unlaxer を作り始めたのは、Scala の parser combinator が羨ましかったから。

```scala
// Scala の parser combinator（イメージ）
def expr: Parser[Int] = term ~ rep("+" ~ term | "-" ~ term) ^^ {
  case t ~ rest => rest.foldLeft(t) {
    case (acc, "+" ~ r) => acc + r
    case (acc, "-" ~ r) => acc - r
  }
}
```

**後輩:** Scala はパターンマッチも標準であるし、case class もあるし、パーサーコンビネータが言語に馴染んでますよね。

**先輩:** でも 2010 年代の Java にはそれがなかった。パターンマッチもなし、sealed class もなし、record もなし。仕方なく Unlaxer は Java 8 の限界の中で戦ってた。

**後輩:** それが Java 21 で……

**先輩:** やっと追いついた。いや、追い越したとすら思ってる。

```java
// Java 21 の sealed switch — Scala のパターンマッチと同等以上
return switch (node) {
    case BinaryExpr(var left, var op, var right) -> evalBinary(left, op, right);
    case VariableRefExpr(var name)               -> lookupVariable(name);
    case IfExpr(var cond, var then, var else_)    -> evalIf(cond, then, else_);
    // ... exhaustive — 網羅性をコンパイラが保証
};
```

**後輩:** record の分解パターン！ Scala の case class と同じことが Java でできる。

**先輩:** しかも sealed interface の exhaustive check は Scala にも最近まで無かった機能。Java 21 は「正しい方向に進化した」と僕は思ってる。Unlaxer はその進化を最大限活用してる。

### 反論 3: 今は2人だよ

**後輩:** 2人？ 誰ですか？

**先輩:** 僕と、LLM。

**後輩:** ……。

**先輩:** 冗談じゃなくて。LLM は Unlaxer の「ユーザー」だと思ってる。型情報を読んで、evalXxx() を実装して、テストを書いてくれる。Stack Overflow の回答数はゼロでも、型という「完全なドキュメント」がある限り、LLM は Unlaxer を使いこなせる。

**後輩:** LLM をユーザーとカウントするのは新しい発想ですね……。

**先輩:** 真面目な話をすると、OSS は「使う人がいるから価値がある」のは間違いない。でも「使う人がいないから価値がない」は論理的に正しくない。

**後輩:** ？

**先輩:** 数学の定理は、誰も知らなくても正しい。正しいアーキテクチャも同じ。sealed interface + GGP + exhaustive switch が正しいアプローチなのは、ユーザー数とは関係ない。

**後輩:** でも実用的には、知ってもらわないと……

**先輩:** うん。だからチュートリアルを書いてる。こうやって会話劇にしてるのも、LLM が読みやすい形式にするため。

**後輩:** あ、なるほど。この会話自体が LLM のトレーニングデータになりうる？

**先輩:** なりうるかは分からないけど、少なくとも LLM のコンテキストに入れれば理解してもらえる。UBNF の記法、GGP のパターン、@mapping の使い方。全部この会話の中で説明してる。

**後輩:** 「LLM に読ませるドキュメント」という新しいカテゴリのドキュメンテーション。

**先輩:** そう。API リファレンスは人間もLLMも読みにくい。Stack Overflow は断片的。でも会話劇は「なぜそうなってるか」の文脈が自然に含まれる。LLM が一番理解しやすい形式だと思ってる。

### OSS として公開するメリット

**後輩:** じゃあ先輩、Unlaxer を OSS として公開し続けるメリットは何ですか？ ユーザーが増えなくても。

**先輩:** いくつかある。

```
1. Maven Central に公開済み
   - 誰でも <dependency> を追加するだけで使える
   - 自分のプロジェクトでも安定したバージョン管理ができる

2. LLM が参照できる
   - ソースコードが公開されていれば、LLM が型情報を読める
   - 「Unlaxer の MapperGenerator のバグを直して」が成立する

3. 設計の記録
   - 「なぜ sealed interface なのか」「なぜ GGP なのか」の判断が
     コードとドキュメントに残る
   - 10年後の自分にとっての最高のドキュメント

4. 技術的挑戦
   - 「Java でパーサーコンビネータ + コード生成 + LSP + DAP」を
     1人で作れることの証明
   - 技術力の証明として最も雄弁
```

**後輩:** 4番目、就活で使えそうですね。

**先輩:** 転職する予定はないけどね。

**後輩:** でも「ANTLR みたいなの Java で1人で作りました」って言ったら面接官びっくりしますよ。しかも ANTLR にない機能（型付き AST 自動生成、GGP、LSP/DAP）まであるって。

**先輩:** ……ちょっと転職サイト見てこようかな。

**後輩:** 冗談ですってば。

---

## まとめ: LLM時代のフレームワーク三原則

**後輩:** 先輩、最後にまとめてもらえますか？ LLM 時代にフレームワークに求められることは何ですか？

**先輩:** 3つの原則にまとめよう。

### 原則 1: 型でパターンを強制せよ

```
LLM は「正しいパターンに従う」のが得意。
フレームワークが「正しいパターン」を型として定義すれば、
LLM は自動的に正しいコードを書く。

Unlaxer の実践:
  - sealed interface → 全 AST ノードの列挙を強制
  - abstract method  → 全 eval メソッドの実装を強制
  - record           → フィールドアクセスの typo を防止
  - @mapping         → 文法と AST の整合性を生成時に保証
```

### 原則 2: バグの入り込む余地を構造的に減らせ

```
LLM が触れるコードの範囲が狭いほど、バグは少ない。
フレームワークが多くを生成すれば、LLM が書く部分は小さくなる。

Unlaxer の実践:
  - パーサー: 100% 生成（LLM が触る余地なし）
  - AST:     100% 生成（LLM が触る余地なし）
  - マッパー: 100% 生成（LLM が触る余地なし）
  - Evaluator基底: 100% 生成（ディスパッチロジック）
  - evalXxx() の中身: LLM が書く（ここだけ）
```

### 原則 3: 再生成しても手書き部分を壊すな

```
GGP（Generation Gap Pattern）で生成コードと手書きコードを分離。
UBNF を変更して再生成しても、手書きの evalXxx() は安全。
新しいノードが増えたらコンパイルエラーで通知。

Unlaxer の実践:
  - 基底クラス: 毎回再生成（TinyExpressionP4Evaluator）
  - 具象クラス: 手書き/LLM 書き（P4TypedAstEvaluator）
  - 契約: abstract メソッドで繋がる → 追加は検出、削除も検出
```

**後輩:** シンプルですね。型で強制、範囲を狭める、再生成に耐える。

**先輩:** この3つを満たすフレームワークなら、Unlaxer じゃなくてもいい。でも今のところ、パーサー + AST + マッパー + Evaluator + LSP + DAP を全部生成して、しかも全部を型で繋げてるフレームワークは他に知らない。

**後輩:** ANTLR はパーサーまで、Tree-sitter はエディタまで。全部やるのは……

**先輩:** Unlaxer だけ。ユーザー1人だけど。

**後輩:** 2人ですよ。LLM を含めれば。

**先輩:** ……ありがとう。

**後輩:** で、そろそろ3人目になってもいいかなって思ってます。

**先輩:** え？

**後輩:** 今度、自分の DSL を UBNF で書いてみていいですか？ 設定ファイル用の小さい言語を作りたくて。

**先輩:** ……もちろん。tinycalc の例から始めるといい。`unlaxer-dsl/examples/tinycalc.ubnf` にあるから。

**後輩:** ありがとうございます！ あ、でも1つ条件があります。

**先輩:** なに？

**後輩:** 困ったら ChatGPT に聞いていいですか？

**先輩:** ……当然だよ。この会話全体がそれを推奨してるんだから。

**後輩:** ですよね。じゃあ、LLM 時代のフレームワークユーザーとして、やってみます。

**先輩:** 歓迎するよ。これで3人だ。

**後輩:** 4人ですよ。僕も LLM 使うので。

**先輩:** ……数え方がおかしい気がするけど、まあいいか。

---

## 付録: この会話で登場した技術用語

| 用語 | 説明 |
|:---|:---|
| **UBNF** | Unlaxer BNF。EBNF をベースに AST マッピングやスコープ定義を含む文法記述言語 |
| **sealed interface** | Java 17+ の機能。permits で許可された型のみが実装できるインターフェース |
| **record** | Java 16+ の機能。イミュータブルなデータキャリア。自動的にアクセサ、equals、hashCode を生成 |
| **exhaustive switch** | sealed interface に対する switch 式。全ケースの網羅をコンパイラが検証 |
| **GGP** | Generation Gap Pattern。生成コード（基底クラス）と手書きコード（サブクラス）を分離するパターン |
| **@mapping** | UBNF アノテーション。文法規則と AST record の対応を宣言 |
| **@eval strategy** | UBNF アノテーション（構想中）。評価戦略を文法に宣言して Evaluator を自動生成 |
| **ordered choice** | PEG の選択演算子。左から順にマッチを試み、最初にマッチしたものを採用 |
| **JavaCodeBuilder** | コード生成用の型安全な Java ソースビルダー |
| **token (LLM)** | LLM の入出力の単位。テキストの断片。APIコストとコンテキストウィンドウの消費に直結 |
| **LSP** | Language Server Protocol。エディタに言語サポート（補完、定義ジャンプ等）を提供するプロトコル |
| **DAP** | Debug Adapter Protocol。エディタにデバッグ機能（ブレークポイント、ステップ実行等）を提供するプロトコル |

---

## 付録: 参照ファイル一覧

### tinyexpression (ユーザー側)

| ファイル | 説明 |
|:---|:---|
| `docs/ubnf/tinyexpression-p4-complete.ubnf` | P4 世代の UBNF 文法定義（350行以上） |
| `P4TypedAstEvaluator.java` | GGP concrete: AST 評価器（28個の evalXxx） |
| `P4TypedJavaCodeEmitter.java` | GGP concrete: Java コード生成 |
| `P4DefaultJavaCodeEmitter.java` | GGP concrete: デフォルト値コード生成 |
| `BackendSpeedComparisonTest.java` | 5つのバックエンドの速度比較テスト |

### unlaxer-parser (フレームワーク側)

| ファイル | 説明 |
|:---|:---|
| `ParserGenerator.java` | UBNF → パーサーコンビネータ生成 |
| `ASTGenerator.java` | UBNF → sealed interface + records 生成 |
| `MapperGenerator.java` | UBNF → Token → AST マッパー生成 |
| `EvaluatorGenerator.java` | UBNF → GGP 基底クラス生成 |
| `JavaCodeBuilder.java` | 型安全な Java ソースコードビルダー |
| `LSPGenerator.java` | UBNF → LSP サーバー生成 |
| `DAPGenerator.java` | UBNF → DAP サーバー生成 |

---

> この会話劇は、実際の LLM (Claude) による unlaxer-parser / tinyexpression 開発の経験に基づいています。
> token 消費量は概算値であり、モデルやプロンプト設計によって変動します。
> ベンチマーク値は BackendSpeedComparisonTest の結果に基づく相対比較です。

---

[English](./llm-era-and-unlaxer-dialogue.en.md) | [日本語](./llm-era-and-unlaxer-dialogue.ja.md) | [Index](./INDEX.ja.md)
