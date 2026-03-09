[← 03 - BNFからパーサーを組み立てる](./03-building-parsers.md) | [目次](./index.md) | [05 - トランザクションスタックとバックトラック →](./05-backtracking.md)

# 04 - コアデータモデル

## 概要

unlaxerのパース処理は、以下の主要なデータ構造によって成り立っています：

```
Source（入力文字列）
  ↓
Parser.parse(ParseContext) → Parsed（パース結果）
  ↓
Parsed.getRootToken() → Token（トークンツリー）
```

## Source — 入力文字列

`Source` はパース対象の文字列を表すインターフェースです。

### 生成方法

```java
// ルートSourceの生成
Source source = StringSource.createRootSource("1+2*3");
```

### 種類

| SourceKind | 用途 |
|------------|------|
| `root` | 最上位のソース文字列 |
| `subSource` | パース結果として切り出されたサブ文字列 |
| `attached` | 他のソースに関連付けられたソース |
| `detached` | 独立したソース |

Source はコードポイント単位でアクセスされるため、Unicode サロゲートペアも正しく処理されます。

## Cursor — 位置追跡

`Cursor` はソース内の現在位置を追跡します。

### 主要な情報

| メソッド | 説明 |
|----------|------|
| `position()` | コードポイントインデックス |
| `lineNumber()` | 行番号 |
| `positionInLine()` | 行内の位置 |
| `positionInRoot()` | ルートSourceからの絶対位置 |

### カーソルの種類

```java
public enum CursorKind {
    startInclusive,  // 範囲の開始（含む）
    endExclusive     // 範囲の終了（含まない）
}
```

## ParseContext — パース実行環境

`ParseContext` はパース処理全体の状態を管理するコンテキストオブジェクトです。

### 生成方法

```java
ParseContext context = new ParseContext(
    StringSource.createRootSource("1+2*3")
);
```

### 主な役割

1. **ソースの管理** — 入力文字列の保持
2. **カーソル位置の管理** — 現在の読み取り位置
3. **トークンスタックの管理** — パース中のトークン階層
4. **トランザクション管理** — バックトラック用のセーブポイント
5. **デバッグリスナーの管理** — パース過程のイベント通知

## Parsed — パース結果

`Parsed` はパーサーの実行結果を表すクラスです。

### ステータス

```java
public enum Status {
    succeeded,  // パース成功
    stopped,    // パース停止（成功とみなされる）
    failed;     // パース失敗
}
```

### 主要メソッド

```java
Parsed parsed = parser.parse(context);

// ステータスの確認
parsed.isSucceeded();  // succeeded または stopped なら true
parsed.isFailed();     // failed なら true

// トークンツリーの取得
Token rootToken = parsed.getRootToken();          // 全トークン
Token astToken = parsed.getRootToken(true);       // AST（メタノード除去済み）
```

### succeeded と stopped の違い

- `succeeded` — パーサーが入力を消費して成功した
- `stopped` — パーサーが「0回以上」の繰り返しで0回マッチして停止した（成功扱い）

例えば `ZeroOrMore` は、1回もマッチしなくても `stopped` を返します。

## Token — トークンツリー

`Token` はパース結果を木構造で表現するクラスです。
各ノードは、マッチした部分文字列とそれを生成したパーサーの情報を持ちます。

### 主要フィールド

```java
public class Token {
    public final Source source;        // マッチした部分文字列
    public Parser parser;              // このトークンを生成したパーサー
    public Optional<Token> parent;     // 親トークン
    // 子トークン（原本と、ASTノードのみのフィルタ済み版）
    private final TokenList originalChildren;
    public final TokenList filteredChildren;
}
```

### トークンツリーの例

入力 `1+2*3` をパースした結果のトークンツリー：

```
'1+2*3' : ExpressionParser
 '1' : TermParser
  '1' : FactorParser
   '1' : NumberParser
    '1' : DigitParser
  <EMPTY> : ZeroOrMore            ← * / の演算なし
 '+2*3' : ZeroOrMore              ← + - の繰り返し部分
  '+2*3' : WhiteSpaceDelimitedChain
   '+' : AddOpParser
    '+' : PlusParser
   '2*3' : TermParser
    '2' : FactorParser
     '2' : NumberParser
      '2' : DigitParser
    '*3' : ZeroOrMore
     '*3' : WhiteSpaceDelimitedChain
      '*' : MulOpParser
       '*' : MultipleParser
      '3' : FactorParser
       '3' : NumberParser
        '3' : DigitParser
```

### 完全トークンツリー vs AST

`getRootToken()` は全てのトークンを含むツリーを返します。
`getRootToken(true)` はメタノード（`SpaceDelimitor` 等）を除去した AST を返します。

```
AST: '1+2*3' : ExpressionParser
 '1' : TermParser
  '1' : FactorParser
   '1' : NumberParser
    '1' : DigitParser
 '+' : PlusParser
 '2*3' : TermParser
  '2' : FactorParser
   '2' : NumberParser
    '2' : DigitParser
  '*' : MultipleParser
  '3' : FactorParser
   '3' : NumberParser
    '3' : DigitParser
```

### TokenPrinter

`TokenPrinter` はトークンツリーを人間が読みやすい形式で出力するユーティリティです。

```java
// トークンツリーの文字列表現を取得
String treeText = TokenPrinter.get(parsed.getRootToken());
System.out.println(treeText);
```

## データフローまとめ

```
         入力文字列
            |
     StringSource.createRootSource("1+2*3")
            |
            v
    +-----------------+
    |   ParseContext   |  ← Source + Cursor + TokenStack + Listeners
    +-----------------+
            |
     parser.parse(context)
            |
            v
    +-----------------+
    |     Parsed      |  ← Status + Token
    +-----------------+
            |
     parsed.getRootToken()
            |
            v
    +-----------------+
    |   Token Tree    |  ← 木構造のパース結果
    +-----------------+
            |
     TokenPrinter.get(token)
            |
            v
       文字列表現
```

---

[← 03 - BNFからパーサーを組み立てる](./03-building-parsers.md) | [目次](./index.md) | [05 - トランザクションスタックとバックトラック →](./05-backtracking.md)
