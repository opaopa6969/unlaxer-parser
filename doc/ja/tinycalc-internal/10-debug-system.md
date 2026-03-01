---

[← 09 - Lazyパーサーと再帰文法](./09-lazy-and-recursion.md) | [目次](./index.md)

# 10 - デバッグ・リスナーシステム

## 概要

unlaxer は、パース処理の過程を詳細に追跡するためのデバッグシステムを提供しています。
これにより、パーサーがどのように入力を解析しているかを可視化できます。

## TokenPrinter — トークンツリーの表示

最も簡単なデバッグ方法は、パース結果のトークンツリーを表示することです。

```java
Parser parser = TinyCalcParsers.getExpressionParser();
ParseContext context = new ParseContext(StringSource.createRootSource("1+2*3"));
Parsed parsed = parser.parse(context);

if (parsed.isSucceeded()) {
    // 全トークンツリー
    System.out.println(TokenPrinter.get(parsed.getRootToken()));

    // AST（メタノード除去済み）
    System.out.println(TokenPrinter.get(parsed.getRootToken(true)));
}
```

### 出力形式

```
'マッチした文字列' : パーサークラス名
```

各行はインデントにより親子関係を示します。

### 全トークンツリー vs AST

| | 全トークンツリー | AST |
|---|---|---|
| 取得方法 | `getRootToken()` | `getRootToken(true)` |
| 空白トークン | 含む | 除外 |
| Optional の空マッチ | `<EMPTY>` として含む | 除外 |
| ZeroOrMore の空マッチ | `<EMPTY>` として含む | 除外 |
| 用途 | デバッグ・内部動作の理解 | 意味解析・コード生成 |

## ParseContext の設定

### CreateMetaTokenSpecifier

メタトークン（各パーサーの中間結果）を生成するかどうかを制御します：

```java
// メタトークンを生成する（デフォルト: 生成しない）
ParseContext context = new ParseContext(
    StringSource.createRootSource("1+2*3"),
    CreateMetaTokenSpecifier.createMetaOn
);
```

メタトークンを有効にすると、パースの各ステップで中間トークンが生成され、
より詳細なトークンツリーを得ることができます。

## パース結果の確認パターン

### 成功・失敗の判定

```java
Parsed parsed = parser.parse(context);

if (parsed.isSucceeded()) {
    System.out.println("成功: " + parsed.status);
    Token root = parsed.getRootToken();
    System.out.println("マッチした文字列: " + root.source.toString());
} else {
    System.out.println("失敗: " + parsed.status);
}
```

### 完全マッチの判定

パーサーが入力全体を消費したかを確認するには、
マッチした長さと入力の長さを比較します：

```java
if (parsed.isSucceeded()) {
    Token root = parsed.getRootToken();
    String matched = root.source.toString();
    if (matched.length() == input.length()) {
        System.out.println("完全マッチ");
    } else {
        System.out.println("部分マッチ: " + matched);
    }
}
```

### ステータスの種類

| ステータス | 意味 | 例 |
|------------|------|-----|
| `succeeded` | 入力を消費して成功 | `"123"` に対する NumberParser |
| `stopped` | 0回マッチで成功 | `"abc"` に対する ZeroOrMore(Digit) |
| `failed` | マッチ失敗 | `"abc"` に対する DigitParser |

## テスト用ヘルパー（ParserTestBase）

unlaxer のテストクラスは `ParserTestBase` を継承して、
便利なヘルパーメソッドを利用できます：

```java
public class MyTest extends ParserTestBase {

    @Test
    public void testFullMatch() {
        Parser parser = TinyCalcParsers.getExpressionParser();
        // 入力全体を消費することを確認
        testAllMatch(parser, "1+2*3");
    }

    @Test
    public void testPartialMatch() {
        Parser parser = TinyCalcParsers.getExpressionParser();
        // "1" だけマッチすることを確認
        testPartialMatch(parser, "1 +", "1 ");
    }

    @Test
    public void testNoMatch() {
        Parser parser = TinyCalcParsers.getExpressionParser();
        // マッチしないことを確認
        testUnMatch(parser, "");
    }
}
```

### 主要なヘルパーメソッド

| メソッド | 用途 |
|----------|------|
| `testAllMatch(parser, source)` | 入力全体を消費することを確認 |
| `testPartialMatch(parser, source, matched)` | 指定した部分だけマッチすることを確認 |
| `testUnMatch(parser, source)` | マッチしないことを確認 |
| `testSucceededOnly(parser, source)` | パースが成功することを確認（消費量は問わない） |

## TinyCalcDemo の実装

`TinyCalcDemo.java` は、パーサーの動作を色付きで確認できるデモプログラムです。

### 実行方法

```bash
mvn -f examples/tinycalc/pom.xml exec:java \
  -Dexec.mainClass="org.unlaxer.tinycalc.TinyCalcDemo"
```

### 出力例

```
=== TinyCalc Demo ===
--- Simple Expressions ---
  [FULL MATCH] Expression   "1+2*3" -> matched: "1+2*3"
  [FULL MATCH] Expression   "1 + 2 * 3" -> matched: "1 + 2 * 3"
  [FULL MATCH] Expression   "(1+2)*3" -> matched: "(1+2)*3"
--- Function Calls ---
  [FULL MATCH] Expression   "sin(3.14)" -> matched: "sin(3.14)"
  [FULL MATCH] Expression   "max(1,2)" -> matched: "max(1,2)"
  [FULL MATCH] Expression   "random()" -> matched: "random()"
--- Full TinyCalc (with variables) ---
  [FULL MATCH] TinyCalc     "var x set 10; sin(x) + sqrt(3.14)" -> ...
--- Expected Failures ---
  [FAILED]   Expression   ""
  [FAILED]   Expression   "+++"
  [PARTIAL]  Expression   "1 +" -> matched: "1 "
```

色分け：
- 緑: FULL MATCH（完全マッチ）
- 黄: PARTIAL（部分マッチ）
- 赤: FAILED（失敗）

### トレース出力

特定の入力に対して詳細なトークンツリーを表示することもできます：

```java
static void parseExpressionWithTrace(String input) {
    Parser parser = TinyCalcParsers.getExpressionParser();
    ParseContext context = new ParseContext(StringSource.createRootSource(input));
    Parsed parsed = parser.parse(context);

    if (parsed.isSucceeded()) {
        Token rootToken = parsed.getRootToken();
        System.out.println("Token Tree:");
        System.out.println(TokenPrinter.get(rootToken));

        System.out.println("Reduced Token Tree (AST):");
        System.out.println(TokenPrinter.get(parsed.getRootToken(true)));
    }
}
```

## デバッグのヒント

### 1. パース失敗の原因調査

パースが失敗した場合、部分マッチの結果からどこまで成功したかを確認できます：

```java
Parsed parsed = parser.parse(context);
if (parsed.isSucceeded()) {
    String matched = parsed.getRootToken().source.toString();
    // matched の長さ → どこまで消費されたか
    // input.length() - matched.length() → どこで止まったか
}
```

### 2. トークンツリーによる構造確認

パースが成功しても期待通りの構造でない場合、
トークンツリーを出力して確認します：

```java
System.out.println(TokenPrinter.get(parsed.getRootToken()));
```

各ノードのパーサークラス名から、どの規則でマッチしたかがわかります。

### 3. 段階的なテスト

複雑な文法をデバッグする際は、サブパーサーから段階的にテストします：

```java
// まず数値パーサー単体でテスト
testAllMatch(Parser.get(NumberParser.class), "3.14");

// 次にFactor
testAllMatch(Parser.get(FactorParser.class), "3.14");

// 次にTerm
testAllMatch(Parser.get(TermParser.class), "2*3.14");

// 最後にExpression
testAllMatch(Parser.get(ExpressionParser.class), "1+2*3.14");
```

---

[← 09 - Lazyパーサーと再帰文法](./09-lazy-and-recursion.md) | [目次](./index.md)
