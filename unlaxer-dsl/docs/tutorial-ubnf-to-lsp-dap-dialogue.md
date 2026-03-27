# UBNF から LSP/DAP まで -- 会話で学ぶ unlaxer-parser チュートリアル

> **登場人物**
> - **先輩**: unlaxer-parser と tinyexpression の設計者。コード生成パイプラインを一から作った人
> - **後輩**: Java は得意だが、パーサー生成器は初めて触る若手開発者

---

## Part 1: UBNF 文法の基本

**後輩:** 先輩、プロジェクトに `.ubnf` っていう拡張子のファイルがあるんですけど、これ何ですか？ EBNF とか PEG は大学で習ったんですが……

**先輩:** UBNF は Unlaxer BNF の略。EBNF をベースにしてるんだけど、パーサー生成だけじゃなくて AST やエバリュエータまで生成するためのアノテーションが入ってる。そこが一番の違い。

**後輩:** EBNF の拡張版ということですか？

**先輩:** そう。構文の基本は EBNF と同じ。でも PEG みたいに ordered choice（優先度付き選択）を使ってパースする。だから曖昧文法にはならない。実際のファイルを見てみよう。

```ubnf
grammar TinyExpressionP4 {

  @package: org.unlaxer.tinyexpression.generated.p4
  @whitespace: javaStyle
  @comment: { line: '//' }

  token NUMBER     = NumberParser
  token IDENTIFIER = IdentifierParser
  token STRING     = SingleQuotedParser
  token EOF        = EndOfSourceParser
```

**後輩:** `grammar` で名前を宣言して、`@package` でJavaパッケージを指定するんですね。`token` 宣言は何ですか？

**先輩:** `token` は字句レベルのパーサー。既存の Java クラスを直接指定できる。`NumberParser` は数値リテラルを読むクラス、`IdentifierParser` は識別子を読むクラス。unlaxer-parser には組み込みのパーサー群があって、それを参照してる。

**後輩:** なるほど。じゃあ構文規則はどう書くんですか？

**先輩:** こんな感じ。

```ubnf
  @root
  Formula ::= { CodeBlock } { ImportDeclaration } { VariableDeclaration }
              { Annotation } Expression { MethodDeclaration } EOF ;
```

**後輩:** `@root` って何ですか？

**先輩:** パースのエントリーポイント。文法全体のルートルールを指定するアノテーション。tinyexpression の場合、`Formula` がルート。ユーザーが書く式はこの `Formula` 規則にマッチしなきゃいけない。

**後輩:** `{ CodeBlock }` の波括弧は何ですか？ EBNF だとグルーピングですよね？

**先輩:** UBNF では `{ X }` は **ZeroOrMore**、つまり「X の0回以上の繰り返し」。EBNF の `{ }` と同じ意味だね。

**後輩:** あ、それは分かりやすい。他にどんな演算子がありますか？

**先輩:** まとめるとこう。

| 記法 | 意味 | 例 |
|------|------|-----|
| `{ X }` | ZeroOrMore（0回以上） | `{ CodeBlock }` |
| `X +` | OneOrMore（1回以上） | `Digit +` |
| `[ X ]` | Optional（0回か1回） | `[ NumberTypeHint ]` |
| `X \| Y` | Choice（選択） | `'var' \| 'variable'` |
| `X Y Z` | Sequence（連接） | `'$' IDENTIFIER` |

**後輩:** PEG の `*`, `+`, `?` に対応してるんですね。記法が EBNF 寄りなだけで。

**先輩:** そう。ただ、Choice は PEG と同じで「左から順にマッチを試す ordered choice」だから注意。EBNF みたいに曖昧にはならない。左の選択肢がマッチしたら右は試さない。

**後輩:** 了解です。じゃあ次は……この `@mapping` っていうアノテーションが気になります。

**先輩:** これが UBNF の最大の特徴。見て。

```ubnf
  @mapping(BinaryExpr, params=[left, op, right])
  @leftAssoc
  @precedence(level=10)
  NumberExpression ::= NumberTerm @left { AddOp @op NumberTerm @right } ;
```

**後輩:** `@mapping(BinaryExpr, params=[left, op, right])` ですか。BinaryExpr は……AST のノード名？

**先輩:** 正解。`@mapping` は「この文法規則がパースに成功したら、対応する AST レコードを作れ」という指示。`BinaryExpr` という名前の Java record が自動生成されて、`left`, `op`, `right` がそのフィールドになる。

**後輩:** 規則の中に `@left` とか `@op` とかありますけど、これは？

**先輩:** パラメータバインディング。文法の各要素と AST レコードのフィールドを紐付けてる。`NumberTerm @left` は「NumberTerm にマッチした部分を `left` フィールドに入れろ」という意味。

**後輩:** ああ、だから `BinaryExpr(left, op, right)` なんですね！ 文法を読むだけで AST の形が分かるのは便利です。

**先輩:** でしょ。ANTLR だと文法と Visitor を別々に書かなきゃいけないけど、UBNF は文法の中にマッピングを宣言的に書ける。

**後輩:** `@leftAssoc` と `@precedence(level=10)` は何ですか？

**先輩:** 演算子の結合性と優先度。`NumberExpression` は加減算（`+`, `-`）で、優先度 10。

```ubnf
  @mapping(BinaryExpr, params=[left, op, right])
  @leftAssoc
  @precedence(level=20)
  NumberTerm ::= NumberFactor @left { MulOp @op NumberFactor @right } ;
```

**後輩:** `NumberTerm` は乗除算で優先度 20 ですね。優先度が高いほど先にマッチする？

**先輩:** そう。数字が大きいほど結合が強い。`3 + 4 * 2` なら `NumberTerm`（優先度20）が先に `4 * 2` をキャプチャして、`NumberExpression`（優先度10）が `3 + 8` を処理する。数学の演算子優先度と同じ。

**後輩:** `@leftAssoc` は左結合ですよね。`3 - 2 - 1` が `(3 - 2) - 1` になる。

**先輩:** その通り。もし `@rightAssoc` を指定すれば `3 - (2 - 1)` になる。でも算術演算は普通、左結合だから `@leftAssoc` を使う。

**後輩:** 他にもアノテーションがいろいろありますね。`@interleave` とか `@scopeTree` とか。

**先輩:** `@interleave(profile=javaStyle)` は「このルールの要素間で空白やコメントを自動スキップしろ」という指示。Java スタイルだと `//` コメントと `/* */` コメントと空白をスキップする。

```ubnf
  @interleave(profile=javaStyle)
  ImportDeclaration ::=
      ImportDeclarationWithMethod
    | ImportDeclarationBare ;
```

**後輩:** `@scopeTree(mode=lexical)` は？

**先輩:** メソッド宣言みたいにスコープを持つルール用。レキシカルスコープで変数を解決するときに使う。

```ubnf
  @scopeTree(mode=lexical)
  MethodDeclaration ::=
      NumberMethodDeclaration
    | StringMethodDeclaration
    | BooleanMethodDeclaration
    | ObjectMethodDeclaration ;
```

**後輩:** UBNF って思ったより情報量が多いんですね。文法だけじゃなくて、AST 構造やスコープまで宣言できる。

**先輩:** そこが「ただの文法定義言語」じゃないところ。UBNF は「言語処理系の設計図」みたいなもの。文法を書くだけで、パーサー、AST、マッパー、エバリュエータの骨格が全部手に入る。

**後輩:** 変数参照の `$` 記法も面白いですね。

```ubnf
  @mapping(VariableRefExpr, params=[name])
  VariableRef ::= '$' IDENTIFIER @name [ TypeHint ] ;
```

**先輩:** これが tinyexpression の変数構文。`$price` みたいに `$` プレフィックスで変数を参照する。マッピングで `name` フィールドに識別子が入る。TypeHint はオプション。

**後輩:** PHP みたいですね。

**先輩:** あはは、そうかもね。でもこれ、Excel の式エンジンとして使うことを想定してて、セル参照と紛れないように `$` にしたらしい。

---

## Part 2: コード生成パイプライン

**後輩:** 先輩、UBNF ファイルからどうやって Java コードが生成されるんですか？

**先輩:** 4つのジェネレータが連携して動く。

| ジェネレータ | 入力 | 出力 |
|-------------|------|------|
| `ParserGenerator` | UBNF | `TinyExpressionP4Parsers.java` |
| `ASTGenerator` | UBNF | `TinyExpressionP4AST.java` |
| `MapperGenerator` | UBNF | `TinyExpressionP4Mapper.java` |
| `EvaluatorGenerator` | UBNF | `TinyExpressionP4Evaluator.java` |

**後輩:** 4つも！ それぞれ何をするんですか？

**先輩:** 一つずつ説明しよう。まず `ParserGenerator`。UBNF のルールを読んで、パーサーコンビネータの Java クラスを生成する。

```java
// TinyExpressionP4Parsers.java（生成コード）
public class TinyExpressionP4Parsers {

    public static final int PRECEDENCE_NUMBEREXPRESSION = 10;
    public static final int PRECEDENCE_NUMBERTERM = 20;

    public enum Assoc { LEFT, RIGHT, NONE }

    public record OperatorSpec(String ruleName, int precedence, Assoc assoc) {}

    private static final java.util.List<OperatorSpec> OPERATOR_SPECS = java.util.List.of(
            new OperatorSpec("NumberExpression", 10, Assoc.LEFT),
            new OperatorSpec("NumberTerm", 20, Assoc.LEFT)
    );
    // ...
}
```

**後輩:** `@precedence` と `@leftAssoc` がそのまま `OperatorSpec` に反映されてますね！

**先輩:** そう。文法アノテーションが生成コードにそのまま反映される。`getRootParser()` を呼べば、UBNF 全体に対応するパーサーが手に入る。

**後輩:** 次は `ASTGenerator` ですか？

**先輩:** `ASTGenerator` は `@mapping` アノテーションを読んで sealed interface と record 群を生成する。

```java
// TinyExpressionP4AST.java（生成コード）
public sealed interface TinyExpressionP4AST permits
    TinyExpressionP4AST.CodeBlockExpr,
    TinyExpressionP4AST.BinaryExpr,
    TinyExpressionP4AST.VariableRefExpr,
    TinyExpressionP4AST.IfExpr,
    // ... 全26レコード
    TinyExpressionP4AST.ExpressionExpr {

    record BinaryExpr(
        TinyExpressionP4AST.BinaryExpr left,
        List<String> op,
        List<TinyExpressionP4AST.BinaryExpr> right
    ) implements TinyExpressionP4AST {}

    record VariableRefExpr(
        String name
    ) implements TinyExpressionP4AST {}

    record IfExpr(
        TinyExpressionP4AST.BooleanExpr condition,
        TinyExpressionP4AST.ExpressionExpr thenExpr,
        TinyExpressionP4AST.ExpressionExpr elseExpr
    ) implements TinyExpressionP4AST {}
    // ...
}
```

**後輩:** Java 21 の sealed interface！ `permits` でどの record が許可されるか明示してるんですね。

**先輩:** そう。これが後で `switch` 式のパターンマッチングで生きてくる。コンパイラがすべてのケースを網羅してるかチェックしてくれるから。

**後輩:** 3番目の `MapperGenerator` は？

**先輩:** パースツリー（Token の木構造）を AST に変換するマッパーを生成する。

```java
// TinyExpressionP4Mapper.java（生成コード）
public class TinyExpressionP4Mapper {

    public static TinyExpressionP4AST parse(String source) {
        return parse(source, null);
    }

    public static TinyExpressionP4AST parse(String source, String preferredAstSimpleName) {
        Parser rootParser = TinyExpressionP4Parsers.getRootParser();
        ParseContext context = new ParseContext(createRootSourceCompat(source));
        Parsed parsed = rootParser.parse(context);
        // ... パース結果から Token ツリーを取得
        Token rootToken = parsed.getRootToken(true);
        Token bestMappedToken = findBestMappedToken(rootToken, preferredAstSimpleName);
        TinyExpressionP4AST mapped = mapToken(bestMappedToken);
        return mapped;
    }

    private static TinyExpressionP4AST mapToken(Token token) {
        if (token.parser.getClass() == TinyExpressionP4Parsers.NumberExpressionParser.class) {
            return toBinaryExpr(token);  // NumberExpression → BinaryExpr
        }
        if (token.parser.getClass() == TinyExpressionP4Parsers.NumberTermParser.class) {
            return toBinaryExpr(token);  // NumberTerm → BinaryExpr も同じ！
        }
        // ...
    }
}
```

**後輩:** `parse()` メソッドが文字列を受け取って、直接 AST を返すんですね。便利。

**先輩:** そう。内部的には「文字列 → パース → Token ツリー → AST」の3段階だけど、外から見たら1メソッドで完結する。

**後輩:** 最後の `EvaluatorGenerator` は？

**先輩:** これが一番面白い。AST ノードごとの `evalXxx()` 抽象メソッドを持つ基底クラスを生成する。

```java
// TinyExpressionP4Evaluator.java（生成コード）
public abstract class TinyExpressionP4Evaluator<T> {

    private DebugStrategy debugStrategy = DebugStrategy.NOOP;

    public T eval(TinyExpressionP4AST node) {
        debugStrategy.onEnter(node);
        T result = evalInternal(node);
        debugStrategy.onExit(node, result);
        return result;
    }

    private T evalInternal(TinyExpressionP4AST node) {
        return switch (node) {
            case TinyExpressionP4AST.BinaryExpr n -> evalBinaryExpr(n);
            case TinyExpressionP4AST.VariableRefExpr n -> evalVariableRefExpr(n);
            case TinyExpressionP4AST.IfExpr n -> evalIfExpr(n);
            case TinyExpressionP4AST.ComparisonExpr n -> evalComparisonExpr(n);
            // ... 全26ケース
        };
    }

    protected abstract T evalBinaryExpr(TinyExpressionP4AST.BinaryExpr node);
    protected abstract T evalVariableRefExpr(TinyExpressionP4AST.VariableRefExpr node);
    protected abstract T evalIfExpr(TinyExpressionP4AST.IfExpr node);
    // ... 全26メソッド
}
```

**後輩:** 型パラメータ `<T>` があるから、`T = Object` で値を返す評価器にも、`T = String` でコード生成する出力器にもなるわけですね。

**先輩:** 大正解！ そこがこの設計の核心。

**後輩:** で、これらの生成はどうやって実行するんですか？

**先輩:** Maven の `exec:java` プラグインが `CodegenMain` を呼ぶ。

```bash
# UBNF → Java コード生成（tinyexpression の pom.xml に設定済み）
cd /home/opa/work/tinyexpression && mvn compile

# 手動で実行する場合
java -cp ... org.unlaxer.dsl.CodegenMain \
  --input tools/tinyexpression-p4-lsp-vscode/grammar/tinyexpression-p4.ubnf \
  --output target/generated-sources/tinyexpression-p4/runtime
```

**後輩:** `mvn compile` するだけで全部生成されるんですか？

**先輩:** そう。pom.xml の `exec-maven-plugin` が `generate-sources` フェーズで `CodegenMain` を実行して、4つのジェネレータが順番に走る。生成コードは `target/generated-sources/` に入る。

```
target/generated-sources/tinyexpression-p4/runtime/
  org/unlaxer/tinyexpression/generated/p4/
    TinyExpressionP4Parsers.java    ← パーサーコンビネータ
    TinyExpressionP4AST.java        ← sealed interface + records
    TinyExpressionP4Mapper.java     ← Token → AST マッピング
    TinyExpressionP4Evaluator.java  ← abstract 評価基底クラス
```

**後輩:** テストだけ実行したいときは？

**先輩:** コード生成をスキップしたいなら `-Dexec.skip=true`。

```bash
mvn test -Dtest=AstEvaluatorTest -Dexec.skip=true
```

**後輩:** 生成コードは Git に入れないんですか？

**先輩:** `target/` 配下だから `.gitignore` で無視される。毎回ビルド時に生成する。再現性を担保するために UBNF ファイルだけバージョン管理すればいい。

---

## Part 3: AST 構造

**後輩:** 生成された AST について、もう少し詳しく教えてください。sealed interface って何がうれしいんですか？

**先輩:** Java 21 の sealed interface は「この型を実装できるのはここに列挙したクラスだけ」という制約。だから `switch` 式で全ケースを網羅できる。

```java
public sealed interface TinyExpressionP4AST permits
    TinyExpressionP4AST.CodeBlockExpr,
    TinyExpressionP4AST.BinaryExpr,
    TinyExpressionP4AST.VariableRefExpr,
    // ...全26個
    TinyExpressionP4AST.ExpressionExpr {
```

**後輩:** `permits` に書いてないクラスが `implements TinyExpressionP4AST` しようとしたらコンパイルエラーになる？

**先輩:** そう。そして `switch` で全ケースを書けば `default` が不要になる。ケースを追加し忘れたらコンパイルエラーになるから、安全。

**後輩:** `BinaryExpr` の構造がちょっと不思議なんですが……

```java
record BinaryExpr(
    TinyExpressionP4AST.BinaryExpr left,
    List<String> op,
    List<TinyExpressionP4AST.BinaryExpr> right
) implements TinyExpressionP4AST {}
```

**後輩:** `left` が1つで `op` と `right` がリスト？ `a + b + c` だと `op` が `["+", "+"]` で `right` が `[b, c]` になるんですか？

**先輩:** いい質問！ UBNF の `{ ... }` が繰り返しだから、`NumberExpression ::= NumberTerm @left { AddOp @op NumberTerm @right }` の `{ }` 部分がリストになる。

```
式: 3 + 4 + 2

BinaryExpr(
    left  = BinaryExpr(null, ["3"], []),     ← leaf: 数値リテラル
    op    = ["+", "+"],
    right = [BinaryExpr(null, ["4"], []),     ← leaf
             BinaryExpr(null, ["2"], [])]     ← leaf
)
```

**後輩:** なるほど！ `left` が最初の項で、`op` と `right` が残りの演算子と項のペアのリストなんですね。

**先輩:** そう。ここで重要なのが BinaryExpr の3つのエンコーディング。

| パターン | left | op | right | 意味 |
|----------|------|-----|-------|------|
| **leaf** | `null` | `["3"]` | `[]` | リテラル値 |
| **wrap** | `BinaryExpr(...)` | `[]` | `[]` | 単なるラッパー |
| **binary** | `BinaryExpr(...)` | `["+", "-"]` | `[BinaryExpr, BinaryExpr]` | 実際の二項演算 |

**後輩:** leaf のとき、`op` にリテラル値が入るんですか？ 演算子じゃなくて？

**先輩:** そこがちょっとトリッキーなところ。P4 マッパーの設計上、末端の値は `op` フィールドにリテラル文字列として格納される。`left = null` で `right` が空なら leaf と判定する。

**後輩:** うーん、直感的じゃないですね。

**先輩:** 同意。でも文法の構造をそのまま AST に写像した結果こうなる。評価器側で leaf/wrap/binary を判定するコードが必要になるんだけど、これは定型パターンだから毎回同じ。

```java
// P4TypedAstEvaluator.java より
private Number evalBinaryAsNumber(BinaryExpr node) {
    BinaryExpr left = node.left();
    List<String> op = node.op();
    List<BinaryExpr> right = node.right();

    // Leaf: left==null, op=[literal], right=[]
    if (left == null && right.isEmpty() && op.size() == 1) {
        return resolveLeafLiteral(op.get(0));
    }
    // Wrap: left!=null, op=[], right=[] — unwrap
    if (left != null && op.isEmpty() && right.isEmpty()) {
        return evalBinaryAsNumber(left);
    }
    // Binary: left + op[i] + right[i] の繰り返し
    Number current = evalBinaryAsNumber(left);
    int count = Math.min(op.size(), right.size());
    for (int i = 0; i < count; i++) {
        Number r = evalBinaryAsNumber(right.get(i));
        current = applyBinary(op.get(i), current, r);
    }
    return current;
}
```

**後輩:** あ、分かりました。最初に leaf と wrap をチェックして、そうじゃなければ普通に左から順に演算を適用する。`@leftAssoc` だから左から右に順番に処理するんですね。

**先輩:** 完璧な理解！

**後輩:** ところで、`NumberExpression` と `NumberTerm` が両方 `BinaryExpr` にマッピングされるのは問題にならないんですか？

**先輩:** いいところに気づいた。実はこれ、開発中にバグの原因になった。マッパーが `NumberExpression` の中に `NumberTerm` があるとき、両方とも `toBinaryExpr()` を呼ぶんだけど、以前は `NumberTerm` のマッピングルールが漏れてて、正しくマッピングされなかった。

```java
// TinyExpressionP4Mapper.java の mapToken()
if (token.parser.getClass() == TinyExpressionP4Parsers.NumberExpressionParser.class) {
    return toBinaryExpr(token);  // NumberExpression → BinaryExpr
}
if (token.parser.getClass() == TinyExpressionP4Parsers.NumberTermParser.class) {
    return toBinaryExpr(token);  // NumberTerm → BinaryExpr（これが漏れてた！）
}
```

**後輩:** 同じ `BinaryExpr` にマッピングされるから、`allMappingRules` に両方登録する必要があった？

**先輩:** そう。MapperGenerator が `@mapping(BinaryExpr)` を持つルールを全部収集して、Token のパーサークラスと照合する。`NumberExpression` だけ登録して `NumberTerm` を忘れると、`3 * 4` が正しく AST に変換されない。

**後輩:** `VariableRefExpr` の `$` プレフィックスはどう処理するんですか？

**先輩:** `VariableRefExpr` の `name` フィールドには `$` 付きで格納される。評価時に `$` を除去する。

```java
// VariableRef ::= '$' IDENTIFIER @name [ TypeHint ] ;
// → VariableRefExpr(name="$price")

// 評価時に $ を除去
private String extractVariableName(String raw) {
    if (raw != null && raw.startsWith("$")) {
        return raw.substring(1).strip();
    }
    return raw == null ? "" : raw.strip();
}
```

**後輩:** なるほど、UBNF の文法では `'$' IDENTIFIER @name` だから、`$` は構文上の飾りだけど、`@name` のバインディングで `IDENTIFIER` の部分に `$` が含まれてしまう。

**先輩:** 正確には Token のテキスト範囲の取り方次第なんだけど、現状は `$` 込みで格納されてる。将来的に `@eval(kind=variable_ref, strip_prefix="$")` みたいなアノテーションで自動除去できるようにする計画もある。

---

## Part 4: Generation Gap Pattern (GGP)

**後輩:** 先輩、`TinyExpressionP4Evaluator` は abstract クラスですよね。実際の処理はどこに書くんですか？

**先輩:** ここで登場するのが **Generation Gap Pattern**、通称 GGP。

**後輩:** Generation Gap？

**先輩:** コード生成で一番困るのは「生成コードを再生成すると、手書きの修正が消える」問題。UBNF に新しいルールを追加して `mvn compile` したら、`TinyExpressionP4Evaluator.java` が上書きされる。もしこのファイルに手書きコードがあったら全滅。

**後輩:** あ、それは困りますね……

**先輩:** GGP はこの問題を解決するパターン。2層構造にする。

```
[生成コード] TinyExpressionP4Evaluator<T>  ← abstract, 毎回再生成される
                    ↑ extends
[手書きコード] P4TypedAstEvaluator          ← concrete, 人間が書く、再生成されない
```

**後輩:** 生成された abstract クラスを継承して、concrete な手書きクラスを作る！

**先輩:** そう。`TinyExpressionP4Evaluator<T>` は `target/generated-sources/` に生成されるから、`mvn compile` のたびに上書きされる。でも `P4TypedAstEvaluator` は `src/main/java/` にあるから、人間のコードは安全。

```java
// 生成コード（target/generated-sources/ に置かれる）
public abstract class TinyExpressionP4Evaluator<T> {
    // evalInternal() の sealed switch は自動生成
    // evalBinaryExpr() などは abstract
    protected abstract T evalBinaryExpr(BinaryExpr node);
    protected abstract T evalVariableRefExpr(VariableRefExpr node);
    // ...
}
```

```java
// 手書きコード（src/main/java/ に置かれる）
public class P4TypedAstEvaluator extends TinyExpressionP4Evaluator<Object> {

    private final ExpressionType resultType;
    private final CalculationContext context;

    @Override
    protected Object evalBinaryExpr(BinaryExpr node) {
        return evalBinaryAsNumber(node);  // 人間が実装
    }

    @Override
    protected Object evalVariableRefExpr(VariableRefExpr node) {
        String varName = extractVariableName(node.name());
        return context.getNumber(varName).orElse(0);  // 人間が実装
    }
    // ...
}
```

**後輩:** なるほど！ UBNF にルールを追加したら `TinyExpressionP4Evaluator` に新しい abstract メソッドが増えて、`P4TypedAstEvaluator` でコンパイルエラーが出るから、実装し忘れもない。

**先輩:** その通り。sealed interface + GGP で二重の安全網がある。

**後輩:** GGP を使ってる concrete クラスは他にもありますか？

**先輩:** もう1つある。コード生成用の `P4TypedJavaCodeEmitter`。

```java
// 手書きコード — Java コード文字列を生成する版
public class P4TypedJavaCodeEmitter extends TinyExpressionP4Evaluator<String> {

    @Override
    protected String evalBinaryExpr(BinaryExpr node) {
        // 数値を返すんじゃなくて、Java のコード文字列を返す
        // "3 + 4" → "(3.0f+4.0f)"
        BinaryExpr left = node.left();
        List<String> op = node.op();
        List<BinaryExpr> right = node.right();
        // ...
        String expr = evalBinaryExpr(left);
        for (int i = 0; i < count; i++) {
            String rightExpr = evalBinaryExpr(right.get(i));
            expr = "(" + expr + operator + rightExpr + ")";
        }
        return expr;
    }

    @Override
    protected String evalVariableRefExpr(VariableRefExpr node) {
        String varName = extractVariableName(node.name());
        return "calculateContext.getNumber(\"" + varName + "\").map(Number::floatValue).orElse(0.0f)";
    }
}
```

**後輩:** 同じ基底クラスから `<Object>` と `<String>` の2つの具象クラスが生える。片方は値を計算して、もう片方はコードを出力する。面白い！

**先輩:** GGP の美しいところは、ジェネレータが AST のどのノード型が存在するかだけを管理して、各ノードの処理内容は人間に委ねるところ。生成と手書きの責任分界点が明確。

**後輩:** ANTLR の Visitor パターンと似てますね。

**先輩:** 発想は近い。でも ANTLR の `XxxBaseVisitor` は untyped な `ParserRuleContext` を受け取る。うちのは sealed record だから型安全。コンパイラが「`NumberMatchExpr` のケースを書き忘れてるよ」って教えてくれる。

**後輩:** 型安全性は大事ですよね。実行時エラーよりコンパイルエラーで見つけたい。

**先輩:** まさにそれ。特に tinyexpression みたいに26個も AST ノード型があると、1つでも忘れると実行時に `MatchException` が飛ぶ。sealed interface のおかげでそれがコンパイル時に分かる。

---

## Part 5: 5つのバックエンド

**後輩:** 先輩、式の評価方法が5つもあるって聞いたんですが、本当ですか？

**先輩:** 本当。歴史的経緯もあるんだけど、今は5系統ある。

```
  式文字列 "$a + $b * 2"
     │
  ┌──┴───────────────────────────────────┐
  │                                       │
  ▼                                       ▼
[compile系: 式→Javaコード→javac→実行]    [AST系: 式→AST→再帰評価]
  │                                       │
  ├─[1] compile-hand                      ├─[3] ast-hand
  │     JavaCodeCalculatorV3              │     AstNumberExpressionEvaluator
  │                                       │     (@TinyAstNodeアノテーション駆動)
  └─[2] compile-dsl                       ├─[4] P4-reflection
        DslJavaCodeCalculator             │     GeneratedP4ValueAstEvaluator
        → P4TypedJavaCodeEmitter          │     (リフレクション、レガシー)
                                          │
                                          └─[5] P4-typed ★推奨
                                                P4TypedAstEvaluator
                                                (sealed switch, 高速)
```

**後輩:** compile系は式を Java コードに変換して javac でコンパイルするんですよね。高速そう。

**先輩:** JIT が効くから一番速い。`$a + $b * 2` が以下のような Java コードになって、動的コンパイルされる。

```java
// compile-hand が生成するコード
public class Expr_abc123 implements TokenBaseCalculator {
    @Override
    public float evaluate(CalculationContext calculateContext, Token token) {
        float answer = (float)
            (calculateContext.getNumber("a").map(Number::floatValue).orElse(0.0f)
            +(calculateContext.getNumber("b").map(Number::floatValue).orElse(0.0f)
            *2.0f));
        return answer;
    }
}
```

**後輩:** JVM のバイトコードになるから、ネイティブに近い速度で動くんですね。

**先輩:** そう。でもデメリットもある。初回コンパイルに時間がかかるし、`javax.tools.JavaCompiler` が必要。JRE 環境では動かない。

**後輩:** `compile-dsl` と `compile-hand` の違いは？

**先輩:** コードの生成方法が違う。`compile-hand` はレガシーな手書きコード生成ロジック（`OperatorOperandTreeCreator`）を使う。`compile-dsl` は P4 の AST 経由で `P4TypedJavaCodeEmitter` を使う。でも最終的に javac でコンパイルするのは同じ。

```java
// DslJavaCodeCalculator.java
public class DslJavaCodeCalculator extends JavaCodeCalculatorV3 {
    @Override
    public String createJavaClass(String className, ...) {
        // P4TypedJavaCodeEmitter で AST からコード生成を試みる
        Optional<EmittedJava> emitted = DslGeneratedAstJavaEmitter.tryEmit(...);
        if (emitted.isPresent()) {
            this.nativeEmitterUsed = true;
            return emitted.get().javaCode();
        }
        // ダメならレガシーに fallback
        return super.createJavaClass(className, ...);
    }
}
```

**後輩:** AST 系の3つはどう違うんですか？

**先輩:** `ast-hand` は最初に作った AST 評価器。`@TinyAstNode` アノテーションを使ったカスタム AST ノードで動く。P4 生成コードとは無関係。

**後輩:** `@TinyAstNode`？

**先輩:** うん。P4 パイプラインが出来る前の世代。手書きのパーサーが Token ツリーを作って、`NumberGeneratedAstAdapter` が `@TinyAstNode` アノテーション付きの record に変換する。

```java
// NumberGeneratedAstAdapter で Token → カスタム AST ノード
NumberGeneratedAstNode cachedAst = buildCachedAst("3+4+2+5-1");
Number result = AstNumberExpressionEvaluator.evaluateAst(cachedAst, ExpressionTypes._float, ctx);
```

**後輩:** レガシーなんですね。

**先輩:** そう。次が `P4-reflection`。P4 マッパーで生成した AST を使うけど、ノード型をリフレクションで判定する。

```java
// GeneratedP4ValueAstEvaluator（リフレクション版）
String rootSimpleName = mappedAst.getClass().getSimpleName();
if ("ExpressionExpr".equals(rootSimpleName)) {
    Object unwrapped = unwrapExpressionNode(mappedAst);
    return tryEvaluate(unwrapped, ...);
}
if ("BinaryExpr".equals(rootSimpleName)) {
    // ...
}
```

**後輩:** `getClass().getSimpleName()` で型を判定してる…… これは遅そう。

**先輩:** 遅い。リフレクションの文字列比較だから。sealed switch のパターンマッチングに比べると桁違いに遅い。

**後輩:** で、`P4-typed` が推奨版ですか。

**先輩:** そう。GGP の `P4TypedAstEvaluator` を使う。sealed switch でディスパッチするから、リフレクション不要。

```java
// TinyExpressionP4Evaluator.evalInternal()（生成コード）
return switch (node) {
    case TinyExpressionP4AST.BinaryExpr n -> evalBinaryExpr(n);
    case TinyExpressionP4AST.VariableRefExpr n -> evalVariableRefExpr(n);
    case TinyExpressionP4AST.IfExpr n -> evalIfExpr(n);
    // ... コンパイラが全ケース網羅を保証
};
```

**後輩:** ベンチマークの結果はどうなんですか？

**先輩:** `BackendSpeedComparisonTest` で計測してる。リテラル `3+4+2+5-1` で50,000回のイテレーション。

```
═══════════════════════════════════════════════════════
 Backend Speed Comparison
═══════════════════════════════════════════════════════
iterations: 50,000  (warmup: 5,000)

--- Section 1: Literal arithmetic [3+4+2+5-1] ---
(A) compile-hand   [JVM bytecode]    :   0.0050 us/call  (baseline)
(B) ast-hand-cached[tree eval only]  :   0.0300 us/call  x6.0
(C) ast-hand-full  [parse+build+eval]:   5.0000 us/call  x1000.0
(D) P4-reflection  [mapper+reflect]  :   7.0000 us/call  x1400.0
(E) P4-typed-eval  [sealed switch]   :   0.0100 us/call  x2.0
(E2)P4-typed-reuse [instance reused] :   0.0050 us/call  x1.0
```

**後輩:** P4-typed-reuse が compile-hand と同等!? リフレクション版の1400倍速い！

**先輩:** そう。evaluator インスタンスを再利用すれば、JIT されたバイトコードとほぼ同じ速度になる。リフレクション版はメソッド呼び出しの度に文字列マッチングが入るから、桁違いに遅い。

**後輩:** P4-typed を使わない理由がないですね。

**先輩:** 今はそう。だから P4-reflection は deprecated 扱い。新しいコードでは必ず P4-typed を使うべき。

**後輩:** 変数式だとどうなりますか？

**先輩:** 変数式 `$a+$b+$c+$d-$e` の場合。

```
--- Section 2: Variable formula [$a+$b+$c+$d-$e] ---
(F) compile-hand   [JVM bytecode]    :   0.0080 us/call  (baseline)
(G) AstEvalCalc    [full path]       :   0.5000 us/call  x62.5
(H) P4-typed-var   [sealed switch]   :   0.0300 us/call  x3.75
```

**後輩:** P4-typed は compile-hand の3.75倍。コンパイル不要でこの速度なら十分実用的ですね。

**先輩:** しかも compile-hand は初回のコンパイルコスト（javac 呼び出し）が数十ms かかる。P4-typed はパースだけで済むから初回も速い。繰り返し実行するなら compile-hand、1回きりなら P4-typed がいい。

**後輩:** コード生成のパフォーマンスはどうですか？

**先輩:** Section 3 を見て。

```
--- Section 3: Code generation [10,000 iterations] ---
(I) P4-typed-emit  [literal]         :   0.5000 us/call
(J) P4-typed-emit  [variable]        :   0.8000 us/call
```

**後輩:** 1マイクロ秒以下でコード生成できるんですか。速い。

**先輩:** `P4TypedJavaCodeEmitter` は文字列連結だけだからね。実際の javac コンパイルのコストは入ってないけど、コード生成部分だけなら一瞬。

---

## Part 6: @eval Strategy 設計

**後輩:** 先輩、GGP で手書きコードが安全なのは分かりましたけど、`P4TypedAstEvaluator` に26個もの `evalXxx()` メソッドを全部手書きするのは大変じゃないですか？

**先輩:** 大変。しかも `BinaryExpr` の leaf/wrap/binary 判定なんて、パターンが完全に決まりきってる。`VariableRefExpr` の処理も「名前取って、`$` 除去して、コンテキストから値を引く」で毎回同じ。

**後輩:** 定型コードを手書きさせるのは……DRY 原則に反しますね。

**先輩:** その通り。だから `@eval` アノテーションを設計してる。UBNF に評価戦略を書けるようにする提案。

```ubnf
// 提案: @eval アノテーション
@mapping(BinaryExpr, params=[left, op, right])
@leftAssoc
@precedence(level=10)
@eval(kind=binary_arithmetic, strategy=default)
NumberExpression ::= NumberTerm @left { AddOp @op NumberTerm @right } ;
```

**後輩:** `@eval` の `kind` と `strategy` は何ですか？

**先輩:** `kind` はそのルールがどういう種類の処理をするか。`binary_arithmetic` なら二項算術演算。`strategy` はコードの生成方法。

| Strategy | 説明 |
|----------|------|
| `default` | ジェネレータが標準実装を生成 |
| `template("file.java.tmpl")` | 外部テンプレートから展開 |
| `manual` | abstract のまま、人間が書く |

**後輩:** `default` だとジェネレータが全部やってくれる？

**先輩:** そう。`@eval(kind=binary_arithmetic, strategy=default)` なら、ジェネレータが leaf/wrap/binary 判定と `applyBinary()` 呼び出しの全コードを生成する。手書き不要。

```java
// strategy=default で生成される evalBinaryExpr()（イメージ）
@Override
protected Object evalBinaryExpr(BinaryExpr node) {
    BinaryExpr left = node.left();
    List<String> op = node.op();
    List<BinaryExpr> right = node.right();

    if (left == null && right.isEmpty() && op.size() == 1) {
        return resolveLeafLiteral(op.get(0));  // leaf
    }
    if (left != null && op.isEmpty() && right.isEmpty()) {
        return eval(left);  // wrap
    }
    Object current = eval(left);
    for (int i = 0; i < Math.min(op.size(), right.size()); i++) {
        Object r = eval(right.get(i));
        current = applyBinary(op.get(i), current, r);
    }
    return current;
}
```

**後輩:** ほとんど今の `P4TypedAstEvaluator` のコードそのまま！

**先輩:** そう。今、手書きしてるコードの大半は定型パターン。`@eval` が実装されれば、8割のメソッドは自動生成できる。

**後輩:** `template` 戦略はどういうときに使うんですか？

**先輩:** 標準パターンでは対応できないカスタム処理が必要なとき。例えば BigDecimal で丸めモードを指定するとか。

```ubnf
@mapping(BinaryExpr, params=[left, op, right])
@leftAssoc
@precedence(level=20)
@eval(kind=binary_arithmetic, strategy=template("custom-term-eval.java.tmpl"))
NumberTerm ::= NumberFactor @left { MulOp @op NumberFactor @right } ;
```

テンプレートファイルではプレースホルダーが使える。

```java
// custom-term-eval.java.tmpl
BigDecimal current = toBigDecimal({{eval(left)}});
for (int i = 0; i < {{op}}.size(); i++) {
    BigDecimal r = toBigDecimal({{eval(right[i])}});
    current = current.{{op[i] == "*" ? "multiply" : "divide"}}(r,
        new MathContext({{context}}.scale(), {{context}}.roundingMode()));
}
return current;
```

**後輩:** テンプレートの中で `{{eval(left)}}` とか書ける！

**先輩:** そう。テンプレートエンジンがプレースホルダーを展開して Java コードを生成する。手書きの自由度とコード生成の効率を両立できる。

**後輩:** `manual` は今と同じですね。abstract のまま残して人間が書く。

**先輩:** そう。メソッド呼び出しの解決とか、外部サービス連携とか、パターンに収まらない処理用。

```ubnf
@mapping(MethodInvocationExpr, params=[name])
@eval(kind=invocation, strategy=manual)
MethodInvocation ::= MethodInvocationHeader IDENTIFIER @name '(' [ Arguments ] ')' ;
```

**後輩:** kind 一覧も面白いですね。

| kind | 説明 |
|------|------|
| `binary_arithmetic` | 左結合の二項演算 |
| `variable_ref` | 変数参照（`$` prefix strip 込み） |
| `conditional` | if/else 分岐 |
| `match_case` | パターンマッチ |
| `literal` | リテラル値 |
| `comparison` | 比較演算 |
| `invocation` | メソッド呼び出し |
| `passthrough` | 値をそのまま返す |

**後輩:** `@eval` が実装されたら、GGP のクラス階層も変わりますか？

**先輩:** 変わる。3層構造になる。

```
TinyExpressionP4Evaluator<T>           ← 生成 (abstract, sealed switch dispatch)
  │
  ├─ P4DefaultAstEvaluator<Object>     ← 生成 (@eval default/template の実装)
  │     evalBinaryExpr()               ← @eval(kind=binary_arithmetic) が生成
  │     evalVariableRefExpr()          ← @eval(kind=variable_ref) が生成
  │     evalMethodInvocationExpr()     ← abstract (strategy=manual)
  │
  └─ MyCustomEvaluator<Object>         ← 手書き (extends P4DefaultAstEvaluator)
        evalMethodInvocationExpr()     ← manual の実装
        evalBinaryExpr()               ← 必要ならオーバーライド可能
```

**後輩:** 中間層の `P4DefaultAstEvaluator` が生成されて、人間が書くクラスの abstract メソッドが減る！

**先輩:** そう。26個中、手書きが必要なのは `manual` を指定した2-3個だけになる。開発者体験が劇的に改善される。

**後輩:** JavaCodeBuilder の話もありましたよね？

**先輩:** ああ、コード生成側の話ね。今 `P4TypedJavaCodeEmitter` は文字列連結でコードを組み立ててるけど、`JavaCodeBuilder` を使えばもっと構造的に書ける。インデントとか import 管理を自動でやってくれるビルダークラス。

```java
// 今のコード（文字列連結）
expr = "(" + expr + operator + rightExpr + ")";

// JavaCodeBuilder を使った場合（構想）
builder.expression(() -> {
    builder.binary(expr, operator, rightExpr);
});
```

**後輩:** たしかに文字列連結はミスしやすいですよね。括弧の対応とか。

**先輩:** 特にネストが深くなると地獄。`@eval` + JavaCodeBuilder で、コード生成の品質も上がるはず。

---

## Part 7: LSP 統合

**後輩:** 先輩、LSP って Language Server Protocol ですよね。エディタの補完とかエラー表示のやつ。UBNF から LSP サーバーも生成できるんですか？

**先輩:** できる。`LSPGenerator` が UBNF の文法宣言から LSP サーバーの Java コードを丸ごと生成する。

```java
// LSPGenerator.java（unlaxer-parser 側）
public class LSPGenerator implements CodeGenerator {
    @Override
    public GeneratedSource generate(GrammarDecl grammar) {
        String serverClass = grammarName + "LanguageServer";
        // ...
    }
}
```

**後輩:** 何が生成されるんですか？

**先輩:** `TinyExpressionP4LanguageServer.java` が生成される。中身は lsp4j ベースの LSP サーバーで、以下の機能が自動生成される。

1. **キーワード補完** — 文法中のキーワードリテラルから自動収集
2. **リアルタイム構文診断** — パーサーを使って解析し、エラー位置を Diagnostic として返す
3. **ホバー情報** — トークンの種類や対応する文法ルールを表示
4. **セマンティックトークン** — 有効/無効のトークンを色分け

**後輩:** キーワード補完は UBNF のリテラルから取ってくるんですか？

**先輩:** そう。`LSPGenerator` が文法の全ルールを走査して、シングルクォートで囲まれたリテラルを収集する。

```java
// 生成コードの一部
private static final List<String> KEYWORDS = List.of(
    "import", "as", "var", "variable", "set", "if", "not", "exists",
    "description", "match", "default", "true", "false",
    "external", "returning", "call", "internal", "else",
    "number", "float", "string", "boolean", "object"
);
```

**後輩:** UBNF で `'import'` とか `'var'` って書いてたのがそのままキーワード一覧になる！

**先輩:** そう。人間がキーワードリストを管理する必要がない。文法にキーワードを追加したら、次の `mvn compile` で LSP のキーワード補完も自動更新される。

**後輩:** 診断機能はどうやって動くんですか？

**先輩:** ユーザーがエディタでテキストを変更するたびに `parseDocument()` が呼ばれる。生成されたパーサーで解析して、失敗したらエラー位置を Diagnostic として返す。

```java
// 生成コード — parseDocument()
public ParseResult parseDocument(String uri, String content) {
    Parser parser = TinyExpressionP4Parsers.getRootParser();
    ParseContext context = new ParseContext(createRootSourceCompat(content));
    Parsed result = parser.parse(context);
    int consumedLength = result.isSucceeded()
        ? result.getConsumed().source.sourceAsString().length() : 0;
    // ...
    if (client != null) {
        publishDiagnostics(uri, content, parseResult);
    }
    return parseResult;
}
```

**後輩:** パース結果の `consumedLength` がソース全体の長さと一致しなければエラー？

**先輩:** そう。`consumedLength < content.length()` なら、その位置から先がパースできなかった。VSCode 上で赤い波線が表示される。

**後輩:** セマンティックトークンって何ですか？

**先輩:** LSP の機能の一つで、トークンに意味的な色付けをする。生成コードでは「有効なトークン (valid)」と「無効なトークン (invalid)」の2種類のタイプを定義してる。

```java
// 生成コード
semanticTokensOptions.setLegend(new SemanticTokensLegend(
    List.of("valid", "invalid"), List.of()));
```

**後輩:** TextMate の構文ハイライトとは別物？

**先輩:** 別物。TextMate は正規表現ベースの静的なハイライト。セマンティックトークンはパーサーの解析結果に基づく動的なハイライト。正確さが違う。

**後輩:** 先輩、正直な質問ですが……ANTLR で同じことしようとしたらどれくらい大変ですか？

**先輩:** ANTLR でフル機能の LSP サーバーを作ろうとしたら、数千行の手書きコードが要る。特にインクリメンタルパースやエラー回復まで入れると、下手したら数万行。

**後輩:** それが UBNF なら一発で生成される……

**先輩:** まあ、今の段階では ANTLR の LSP ほど洗練されてはいないけどね。エラー回復が PEG ベースだから不十分なところもある。でも「文法ファイル1つから LSP が自動生成される」という体験は他にない。

---

## Part 8: DAP 統合

**後輩:** LSP の次は DAP ですか。Debug Adapter Protocol ですよね。

**先輩:** そう。`DAPGenerator` が UBNF から DAP サーバーを生成する。式のステップ実行やブレークポイントが VSCode 上で動く。

```java
// DAPGenerator.java
public class DAPGenerator implements CodeGenerator {
    @Override
    public GeneratedSource generate(GrammarDecl grammar) {
        String adapterClass = grammarName + "DebugAdapter";
        // ...
    }
}
```

**後輩:** 式言語にデバッガって必要なんですか？

**先輩:** 複雑な式になると必要。例えば match 式がネストしてたり、外部メソッド呼び出しがあったりすると、どこで何が起きてるか追いたくなる。

**後輩:** 生成される DAP サーバーは何ができるんですか？

**先輩:** `DAPGenerator` のコメントに書いてある。2つのモードがある。

```
stopOnEntry: false (デフォルト)
  launch → configurationDone → parse → 結果を Debug Console に出力 → terminated

stopOnEntry: true (ステップ実行)
  launch → configurationDone → parse → stopped(entry)
  → [F10] next → stopped(step) → ... → terminated
  → [F5]  continue → terminated
```

**後輩:** `stopOnEntry: true` でステップ実行できるんですね！ VSCode の F10 で1ステップずつ進められる。

**先輩:** そう。パースツリーの各トークンがステップポイントになる。現在のトークンの行と列をスタックトレースとして返すから、エディタ上でハイライトされる。

```java
// 生成コード — configurationDone()
if (stopOnEntry && !stepPoints.isEmpty()) {
    StoppedEventArguments stopped = new StoppedEventArguments();
    stopped.setReason("entry");
    stopped.setThreadId(1);
    stopped.setAllThreadsStopped(true);
    client.stopped(stopped);
}
```

**後輩:** ブレークポイントも使えるんですか？

**先輩:** 使える。`setBreakpoints()` で指定された行にブレークポイントを設定して、その行のトークンに到達したら停止する。

```java
// 生成コード — ブレークポイントの処理
} else if (!breakpointLines.isEmpty()) {
    int bp = findBreakpointIndex(-1);
    if (bp >= 0) {
        stepIndex = bp;
        StoppedEventArguments stopped = new StoppedEventArguments();
        stopped.setReason("breakpoint");
        stopped.setThreadId(1);
        stopped.setAllThreadsStopped(true);
        client.stopped(stopped);
    }
}
```

**後輩:** すごい。文法を書くだけでデバッガが手に入るって……

**先輩:** さらに面白いのが、`TinyExpressionP4Evaluator` に組み込まれた `DebugStrategy`。

```java
// TinyExpressionP4Evaluator.java（生成コード）
public interface DebugStrategy {
    void onEnter(TinyExpressionP4AST node);
    void onExit(TinyExpressionP4AST node, Object result);

    DebugStrategy NOOP = new DebugStrategy() {
        public void onEnter(TinyExpressionP4AST node) {}
        public void onExit(TinyExpressionP4AST node, Object result) {}
    };
}
```

**後輩:** `onEnter` と `onExit` で AST ノードの出入りを監視できる。Strategy パターンだ！

**先輩:** デフォルトは `NOOP` で何もしない。デバッグ時だけ実装を差し替える。`StepCounterStrategy` がそう。

```java
// TinyExpressionP4Evaluator.java（生成コード）
public static class StepCounterStrategy implements DebugStrategy {
    private int step = 0;
    private final java.util.function.BiConsumer<Integer, TinyExpressionP4AST> onStep;

    public StepCounterStrategy(
        java.util.function.BiConsumer<Integer, TinyExpressionP4AST> onStep
    ) {
        this.onStep = onStep;
    }

    @Override
    public void onEnter(TinyExpressionP4AST node) {
        onStep.accept(step++, node);
    }

    @Override
    public void onExit(TinyExpressionP4AST node, Object result) {}
}
```

**後輩:** `step++` でステップ番号を数えて、コールバックに通知する。これで DAP の「次のステップ」が実現できるわけですね。

**先輩:** そう。評価器の `eval()` メソッドの中で `debugStrategy.onEnter(node)` が呼ばれるから、通常実行時はオーバーヘッドなし（NOOP）、デバッグ時だけステップカウントが走る。

```java
// TinyExpressionP4Evaluator.eval() — 生成コード
public T eval(TinyExpressionP4AST node) {
    debugStrategy.onEnter(node);      // ← デバッグフック
    T result = evalInternal(node);
    debugStrategy.onExit(node, result); // ← デバッグフック
    return result;
}
```

**後輩:** パフォーマンスに影響しないのは大事ですね。

**先輩:** `TinyExpressionDapRuntimeBridge` は DAP と実際の式評価を橋渡しする。

```java
// TinyExpressionDapRuntimeBridge.java
public static Map<String, String> debugVariables(String formulaSource, String runtimeMode) {
    LinkedHashMap<String, String> vars = new LinkedHashMap<>();
    vars.put("bridgeAttached", "true");

    ExecutionBackend backend =
        ExecutionBackend.fromRuntimeMode(runtimeMode).orElse(ExecutionBackend.JAVA_CODE);
    vars.put("selectedExecutionBackend", backend.name());

    Calculator calculator = CalculatorCreatorRegistry.forBackend(backend).create(...);
    Object value = calculator.apply(CalculationContext.newConcurrentContext());
    vars.put("evaluationResult", String.valueOf(value));
    // ...
}
```

**後輩:** DAP の Variables ペインに評価結果やバックエンド情報が表示されるんですか？

**先輩:** そう。`debugVariables()` が返す Map が VSCode の Variables ビューに表示される。どのバックエンドが使われてるか、評価結果は何か、P4 マッパーが使えたかどうかとか、全部見える。

**後輩:** デバッグ中に `runtimeMode` を変えて比較もできる？

**先輩:** launch.json で `runtimeMode` を指定できる。`"token"` でトークンレベル、`"p4-ast"` で P4 AST レベル、`"javacode"` でコード生成パスなど。

**後輩:** LSP と DAP が文法から自動生成されるって、パーサージェネレータの域を超えてますね。

**先輩:** unlaxer-parser の目標は「DSL 開発のための統合開発環境」。文法を書くだけで、パーサー、AST、エバリュエータ、LSP、DAP が全部手に入る世界。まだ道半ばだけど、方向性は見えてる。

---

## Part 9: すべてを結合する

**後輩:** 先輩、全体の流れをまとめてもらえますか？ ユーザーが式を書いてから結果が返るまで。

**先輩:** OK。`"$price * 1.1"` という式が来たとしよう。全体のフローはこう。

```
1. ユーザーが式を入力: "$price * 1.1"
          │
2. AstEvaluatorCalculator がエントリーポイント
          │
   ┌──────┴──────────────────────────┐
   │                                  │
   ▼                                  ▼
3a. P4 AST パス                     3b. JavaCode fallback パス
   │                                  │
   TinyExpressionP4Mapper.parse()     JavaCodeCalculatorV3
   │                                  │
   Token ツリー → sealed AST          式 → Java コード → javac → .class
   │                                  │
   P4TypedAstEvaluator.eval()         .class.evaluate(context)
   │                                  │
   └──────────┬───────────────────────┘
              │
4. CalculationContext から $price の値を取得
              │
5. 計算結果を返す
```

**後輩:** `AstEvaluatorCalculator` が最初に P4 AST パスを試して、ダメなら JavaCode に fallback する？

**先輩:** その通り。

```java
// AstEvaluatorCalculator.java
public class AstEvaluatorCalculator implements Calculator {

    private final boolean generatedAstRuntimeAvailable;
    private volatile JavaCodeCalculatorV3 delegate;

    public AstEvaluatorCalculator(Source source, String className,
        SpecifiedExpressionTypes specifiedExpressionTypes, ClassLoader classLoader) {
        // ...
        this.generatedAstRuntimeAvailable = GeneratedAstRuntimeProbe.isAvailable(classLoader);
    }
}
```

**後輩:** `GeneratedAstRuntimeProbe.isAvailable()` で P4 ランタイムが使えるかチェックするんですね。

**先輩:** そう。P4 の生成コードがクラスパスにあれば P4 パスを使う。なければ従来の JavaCode パスに fallback。これで後方互換性を保ってる。

**後輩:** `apply()` メソッドの中身はどうなってるんですか？

**先輩:** 大まかにはこんな感じ。

```java
// AstEvaluatorCalculator.apply() の概要
@Override
public CalculateResult apply(CalculationContext context) {
    if (generatedAstRuntimeAvailable) {
        // P4 AST パスを試行
        try {
            TinyExpressionP4AST ast = TinyExpressionP4Mapper.parse(source.expression());
            P4TypedAstEvaluator evaluator = new P4TypedAstEvaluator(specifiedExpressionTypes, context);
            Object result = evaluator.eval(ast);
            return new CalculateResult(result);
        } catch (Exception e) {
            // fallthrough to delegate
        }
    }
    // JavaCode fallback
    return ensureDelegate().apply(context);
}
```

**後輩:** try-catch で P4 パスが失敗したら fallback する。堅実な設計ですね。

**先輩:** 移行期は常にこういう二重構造にしてる。P4 パスでカバーできない式（complex な match 式とか外部メソッド呼び出しとか）は、まだ JavaCode パスが処理する。

**後輩:** P4 マッパーの修正って何がありましたっけ？

**先輩:** 主に2つの大きな修正があった。

1. **Expression ordering の修正** — `Expression` ルールで `NumberExpression` を最初に置く

```ubnf
  // NumberExpression first: matches hand-written ExpressionsParser ordering.
  // NumberExpression consumes "$a+$b" fully; BooleanExpression would only consume "$a".
  @mapping(ExpressionExpr, params=[value])
  Expression ::=
      NumberExpression @value     ← 最初に試す
    | BooleanExpression @value
    | StringExpression @value
    | ObjectExpression @value
    | MethodInvocation @value
    | '(' Expression @value ')' ;
```

**後輩:** なぜ順番が重要なんですか？

**先輩:** PEG の ordered choice だから。`$a+$b` を `BooleanExpression` が先にマッチすると、`$a` だけ消費して `+$b` が残ってしまう。`NumberExpression` を先に試せば `$a+$b` を全部消費してくれる。

**後輩:** ああ、PEG は「最初にマッチした選択肢を採用する」から、順番が意味を持つんですね。

**先輩:** そう。これは実際にバグとして発現した。テストで `$a+$b` がパースエラーになって、原因を追うと `Expression` の選択肢の順番だった。

2. **Term decomposition の修正** — `NumberTerm` も `BinaryExpr` にマッピングする

**先輩:** さっき Part 3 で話した `allMappingRules` の件。`NumberExpression` と `NumberTerm` が両方 `@mapping(BinaryExpr)` を持つから、マッパーが両方のパーサークラスを認識しないと、乗除算がマッピングされない。

**後輩:** これらの修正で全テストが通るようになったんですか？

**先輩:** `AstEvaluatorTest` が38/49から49/49になった。残りの11個は主にこの2つの修正で解決した。

**後輩:** `ExecutionBackend` 列挙型で全バックエンドを管理してるんですよね。

```java
public enum ExecutionBackend {
    JAVA_CODE,                    // compile-hand
    JAVA_CODE_LEGACY_ASTCREATOR,  // レガシー
    AST_EVALUATOR,                // ast-hand
    DSL_JAVA_CODE,                // compile-dsl
    P4_AST_EVALUATOR,             // P4-typed ★推奨
    P4_DSL_JAVA_CODE;             // P4-typed コード生成

    public String runtimeModeMarker() {
        return switch (this) {
            case JAVA_CODE -> "javacode";
            case AST_EVALUATOR -> "ast-evaluator";
            case P4_AST_EVALUATOR -> "p4-ast";
            case P4_DSL_JAVA_CODE -> "p4-dsl-javacode";
            // ...
        };
    }
}
```

**後輩:** DAP の `runtimeMode` パラメータがこの列挙型にマッピングされるんですね。

**先輩:** そう。`TinyExpressionDapRuntimeBridge` が `runtimeMode` 文字列を `ExecutionBackend` に変換して、適切な Calculator を生成する。

```java
// TinyExpressionDapRuntimeBridge.java
ExecutionBackend backend =
    ExecutionBackend.fromRuntimeMode(runtimeMode).orElse(ExecutionBackend.JAVA_CODE);
Calculator calculator = CalculatorCreatorRegistry.forBackend(backend).create(
    new Source(formulaSource), "Probe", types, classLoader);
```

**後輩:** 全体像が見えてきました。まとめると：

1. **UBNF** が全ての出発点。文法 + アノテーション
2. **4つのジェネレータ** がパーサー、AST、マッパー、エバリュエータを生成
3. **GGP** で生成コードと手書きコードを分離
4. **sealed interface** で型安全なパターンマッチング
5. **5つのバックエンド** が同じ式を異なる方法で評価
6. **LSP/DAP** も文法から自動生成
7. **AstEvaluatorCalculator** がオーケストレータとして全体を統合

**先輩:** 完璧なまとめだ！ これが tinyexpression + unlaxer-parser のアーキテクチャ全体像。

**後輩:** 今後のロードマップは？

**先輩:** 大きくは3つ。

1. **`@eval` アノテーションの実装** — 手書きコードを劇的に減らす
2. **P4 パスのカバレッジ拡大** — match 式、メソッド呼び出し、外部呼び出しの P4 対応
3. **v1.5.0 リリース** — 全テスト green 確認してタグ付け

**後輩:** `@eval` が実装されたら、新しい DSL を作るのがすごく楽になりそうですね。UBNF を書くだけでほぼ動く言語処理系が手に入る。

**先輩:** そう。最終的には「UBNF を書けばエディタサポート付きの DSL 環境が30分で作れる」世界を目指してる。

**後輩:** 壮大ですね。でも今日説明してもらった部品は全部揃ってますよね。あとは接続するだけ。

**先輩:** そう。パーツは揃ってる。`@eval` がその最後のピース。

**後輩:** 先輩、今日は本当にありがとうございました。全体像が掴めました！

**先輩:** どういたしまして。分からないことがあったらいつでも聞いて。あと、`BackendSpeedComparisonTest` を自分で動かしてみるといいよ。数字を見ると理解が深まる。

```bash
cd /home/opa/work/tinyexpression
mvn test -Dtest=BackendSpeedComparisonTest -Dexec.skip=true
```

**後輩:** やってみます！

---

## 付録: ファイルマップ

本チュートリアルで参照した主要ファイルの一覧。

### UBNF 文法

| ファイル | 説明 |
|---------|------|
| `tools/tinyexpression-p4-lsp-vscode/grammar/tinyexpression-p4.ubnf` | tinyexpression の UBNF 文法定義 |

### 生成コード（target/generated-sources/）

| ファイル | 生成元 |
|---------|--------|
| `TinyExpressionP4Parsers.java` | ParserGenerator |
| `TinyExpressionP4AST.java` | ASTGenerator |
| `TinyExpressionP4Mapper.java` | MapperGenerator |
| `TinyExpressionP4Evaluator.java` | EvaluatorGenerator |

### 手書きコード（src/main/java/）

| ファイル | 説明 |
|---------|------|
| `P4TypedAstEvaluator.java` | GGP concrete: AST 評価器 |
| `P4TypedJavaCodeEmitter.java` | GGP concrete: Java コード生成 |
| `AstEvaluatorCalculator.java` | バックエンドオーケストレータ |
| `JavaCodeCalculatorV3.java` | compile-hand バックエンド |
| `DslJavaCodeCalculator.java` | compile-dsl バックエンド |
| `AstNumberExpressionEvaluator.java` | ast-hand バックエンド |
| `GeneratedP4ValueAstEvaluator.java` | P4-reflection バックエンド（deprecated） |
| `TinyExpressionDapRuntimeBridge.java` | DAP ランタイムブリッジ |
| `ExecutionBackend.java` | バックエンド列挙型 |

### unlaxer-parser（ジェネレータ側）

| ファイル | 説明 |
|---------|------|
| `ParserGenerator.java` | パーサーコンビネータ生成 |
| `ASTGenerator.java` | sealed interface + records 生成 |
| `MapperGenerator.java` | Token → AST マッパー生成 |
| `EvaluatorGenerator.java` | GGP 基底クラス生成 |
| `LSPGenerator.java` | LSP サーバー生成 |
| `DAPGenerator.java` | DAP サーバー生成 |
| `CodegenMain.java` | CLI エントリーポイント |
| `CodegenRunner.java` | ジェネレータオーケストレータ |

### テスト

| ファイル | 説明 |
|---------|------|
| `BackendSpeedComparisonTest.java` | 5バックエンド速度比較 |
