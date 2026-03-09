[← 08 - 完全トレース: var x set 10; sin(x) + sqrt(3.14)](./08-trace-complex.md) | [目次](./index.md) | [10 - デバッグ・リスナーシステム →](./10-debug-system.md)

# 09 - Lazyパーサーと再帰文法

## 再帰文法の問題

TinyCalc の文法には以下の循環参照があります：

```
Expression → Term → Factor → '(' Expression ')'
```

`Expression` が `Factor` を含み、`Factor` が `Expression` を含む — これは循環参照です。

### なぜ問題か？

通常の `Chain` や `Choice` は、コンストラクタで子パーサーのインスタンスを受け取ります：

```java
// これは動作する（循環なし）
Chain chain = new Chain(
    Parser.get(DigitParser.class),
    Parser.get(PlusParser.class),
    Parser.get(DigitParser.class)
);
```

しかし、循環参照がある場合：

```java
// これはコンパイルできるが、実行時に問題が起きる可能性がある
// ExpressionParser が FactorParser を参照し、
// FactorParser が ExpressionParser を参照する
```

`Parser.get()` はシングルトンを返すため、最初の呼び出し時にインスタンスが
まだ生成されていないと問題になります。

## Lazy パーサーによる解決

unlaxer は `LazyChain`、`LazyChoice`、`LazyZeroOrMore` を提供しています。
これらは **遅延初期化** パターンでこの問題を解決します。

### 通常のパーサー vs Lazy パーサー

| | Chain | LazyChain |
|---|---|---|
| 子パーサーの指定 | コンストラクタ引数 | `getLazyParsers()` メソッド |
| 初期化タイミング | オブジェクト生成時 | 最初のパース実行時 |
| 循環参照 | 不可 | 可能 |

### getLazyParsers() の仕組み

```java
public static class ExpressionParser extends WhiteSpaceDelimitedLazyChain {
    @Override
    public Parsers getLazyParsers() {
        // このメソッドは最初のparse()呼び出し時に実行される
        // その時点で全てのパーサークラスは既にロード済み
        return new Parsers(
            Parser.get(TermParser.class),
            new ZeroOrMore(
                new WhiteSpaceDelimitedChain(
                    Parser.get(AddOpParser.class),
                    Parser.get(TermParser.class)
                )
            )
        );
    }
}
```

`getLazyParsers()` が呼ばれるのは、実際にこのパーサーでパースが開始される時です。
その時点では `TermParser` のシングルトンインスタンスは既に生成されているため、
`Parser.get(TermParser.class)` は正常に動作します。

## TinyCalc における循環参照の全体像

```
ExpressionParser ──→ TermParser ──→ FactorParser
       ↑                                  │
       │                                  ├──→ FunctionCallParser
       │                                  │       │
       │                                  │       ├──→ SingleArgFunctionParser ──→ ExpressionParser ⟲
       │                                  │       └──→ TwoArgFunctionParser ───→ ExpressionParser ⟲
       │                                  │
       │                                  ├──→ UnaryExpressionParser ──→ FactorParser ⟲
       │                                  │
       │                                  └──→ ParenExpressionParser ──→ ExpressionParser ⟲
       │
       └───────────────────────────────────────── ⟲ 循環参照ポイント
```

循環参照のポイントは3箇所：
1. `ParenExpressionParser` → `ExpressionParser`（括弧式）
2. `SingleArgFunctionParser` → `ExpressionParser`（関数引数）
3. `TwoArgFunctionParser` → `ExpressionParser`（関数引数）

さらに `UnaryExpressionParser` → `FactorParser` も間接的な循環です。

## Lazy パーサーの種類と使い分け

### LazyChain

連接パーサーの遅延版。子パーサーが全て順番に成功する必要があります。

```java
public static class SingleArgFunctionParser extends WhiteSpaceDelimitedLazyChain {
    @Override
    public Parsers getLazyParsers() {
        return new Parsers(
            Parser.get(SingleArgFunctionNameParser.class),
            Parser.get(LeftParenthesisParser.class),
            Parser.get(ExpressionParser.class),      // ← 循環参照
            Parser.get(RightParenthesisParser.class)
        );
    }
}
```

`WhiteSpaceDelimitedLazyChain` は `LazyChain` のサブクラスで、
空白処理を自動化します。

### LazyChoice

選択パーサーの遅延版。候補のいずれかが成功すればよいです。

```java
public static class FactorParser extends LazyChoice {
    @Override
    public Parsers getLazyParsers() {
        return new Parsers(
            Parser.get(FunctionCallParser.class),
            Parser.get(UnaryExpressionParser.class),
            Parser.get(NumberParser.class),
            Parser.get(IdentifierParser.class),
            Parser.get(ParenExpressionParser.class)  // ← 循環参照
        );
    }
}
```

### LazyZeroOrMore

繰り返しパーサーの遅延版。`getLazyParser()` で繰り返す単位のパーサーを指定します。

```java
public static class VariableDeclarationsParser extends LazyZeroOrMore {
    @Override
    public Supplier<Parser> getLazyParser() {
        return new SupplierBoundCache<>(
            () -> Parser.get(VariableDeclarationParser.class)
        );
    }

    @Override
    public Optional<Parser> getLazyTerminatorParser() {
        return Optional.empty();
    }
}
```

`SupplierBoundCache` は `Supplier` の結果をキャッシュするユーティリティです。
初回呼び出し時にパーサーを生成し、以降はキャッシュされたインスタンスを返します。

## 無限再帰の防止

循環参照がある場合、無限再帰に陥る可能性があります。
例えば：

```
Expression → Term → Factor → '(' Expression ')' → Term → Factor → ...
```

unlaxer では、この無限再帰は以下のメカニズムで自然に防止されます：

### 1. 入力の消費

各パーサーは入力を消費します。`'('` は1文字消費するため、
再帰するたびに残りの入力が短くなります。
最終的に入力が尽きるか、マッチしない文字に到達して再帰が停止します。

### 2. Choice の失敗による停止

`FactorParser` は `LazyChoice` です。
括弧式 `'(' Expression ')'` を試すには `'('` が必要です。
入力に `'('` がなければこの候補は即座に失敗し、他の候補を試みます。

### 例: `((1+2))` のパース

```
ExpressionParser("((1+2))")
└── TermParser → FactorParser
    └── ParenExpressionParser
        ├── '(' ✓                    ← 位置 0→1
        ├── ExpressionParser("(1+2))")
        │   └── TermParser → FactorParser
        │       └── ParenExpressionParser
        │           ├── '(' ✓        ← 位置 1→2
        │           ├── ExpressionParser("1+2)")
        │           │   └── ... "1+2" マッチ
        │           └── ')' ✓        ← 位置 5→6
        └── ')' ✓                    ← 位置 6→7
```

括弧のネストはうまく処理されますが、無限にネストすることはありません
（入力文字列は有限なので）。

## getNotAstNodeSpecifier() の役割

`LazyChain` と `LazyChoice` のサブクラスでは、
`getNotAstNodeSpecifier()` をオーバーライドする必要があります。

```java
@Override
public Optional<RecursiveMode> getNotAstNodeSpecifier() {
    return Optional.empty();
}
```

`Optional.empty()` を返すと、このパーサーが生成するトークンは
AST ノードとして保持されます。

`WhiteSpaceDelimitedLazyChain` は基底クラスでこのメソッドを実装しているため、
サブクラスでの明示的なオーバーライドは不要です。

## Parser.get() の内部動作

```java
Parser parser = Parser.get(ExpressionParser.class);
```

`Parser.get()` は以下の処理を行います：

1. 内部キャッシュ（`ConcurrentHashMap`）でクラスに対応するインスタンスを検索
2. 見つからなければ、リフレクションでデフォルトコンストラクタを呼び出してインスタンスを生成
3. 生成したインスタンスをキャッシュに登録
4. キャッシュされたインスタンスを返す

これにより、同じパーサークラスに対して常に同じインスタンスが使われます。
Lazy パーサーの場合、インスタンス生成時には子パーサーはまだ設定されず、
最初の `parse()` 呼び出し時に `getLazyParsers()` が実行されます。

---

[← 08 - 完全トレース: var x set 10; sin(x) + sqrt(3.14)](./08-trace-complex.md) | [目次](./index.md) | [10 - デバッグ・リスナーシステム →](./10-debug-system.md)
