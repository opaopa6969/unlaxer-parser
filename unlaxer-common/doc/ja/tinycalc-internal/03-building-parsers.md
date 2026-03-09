[← 02 - TinyCalc BNF定義](./02-bnf.md) | [目次](./index.md) | [04 - コアデータモデル →](./04-core-datamodel.md)

# 03 - BNFからパーサーを組み立てる

## BNFとunlaxerの対応表

| BNF記法 | unlaxerのクラス | 用途 |
|---------|----------------|------|
| `A B C` | `Chain` / `LazyChain` | 連接 |
| `A \| B \| C` | `Choice` / `LazyChoice` | 選択 |
| `{ A }` | `ZeroOrMore` | 0回以上 |
| `A+` | `OneOrMore` | 1回以上 |
| `[ A ]` | `Optional` | 省略可能 |
| `'word'` | `WordParser("word")` | キーワード |
| `'c'`（1文字） | `SingleCharacterParser` の具象クラス | 単一文字 |

**LazyChain / LazyChoice** は通常の `Chain` / `Choice` と同じ動作をしますが、
パーサーの生成を遅延させることで循環参照を解決します。

## ステップ1: リーフパーサー（既存クラスの利用）

unlaxerには、よく使う文字パーサーが既に用意されています：

```java
// パッケージ org.unlaxer.parser.ascii
PlusParser           // '+'
MinusParser          // '-'
LeftParenthesisParser  // '('
RightParenthesisParser // ')'
DivisionParser       // '/'
PointParser          // '.'

// パッケージ org.unlaxer.parser.elementary
MultipleParser       // '*'
NumberParser         // 数値（符号・小数点・指数表記対応）

// パッケージ org.unlaxer.parser.posix
DigitParser          // 0-9
AlphabetUnderScoreParser      // a-z A-Z _
AlphabetNumericUnderScoreParser // a-z A-Z 0-9 _
CommaParser          // ','
```

## ステップ2: 新しいリーフパーサーの定義

TinyCalcに必要で、まだ存在しないパーサーを定義します。
`SingleCharacterParser` を拡張するのが最も簡単です：

```java
public static class SemicolonParser extends SingleCharacterParser {
    private static final long serialVersionUID = 1L;

    @Override
    public boolean isMatch(char target) {
        return ';' == target;
    }
}
```

**コード規約**: `false == condition` 形式ではなく `';' == target` のように
リテラルを左辺に置く「Yoda conditions」スタイルが使われています。

## ステップ3: キーワードパーサー

`WordParser` を使って文字列キーワードを定義します：

```java
new WordParser("var")
new WordParser("variable")
new WordParser("set")
new WordParser("sin")
new WordParser("random")
```

`WordParser` は指定された文字列と完全一致するかチェックします。

## ステップ4: 識別子パーサー

BNF:
```
Identifier ::= AlphabetUnderScore { AlphabetNumericUnderScore }
```

unlaxerでの実装：

```java
public static class IdentifierParser extends LazyChain {
    @Override
    public Parsers getLazyParsers() {
        return new Parsers(
            Parser.get(AlphabetUnderScoreParser.class),  // 先頭: 英字 or _
            new ZeroOrMore(                               // 2文字目以降: 0回以上
                Parser.get(AlphabetNumericUnderScoreParser.class)  // 英数字 or _
            )
        );
    }

    @Override
    public Optional<RecursiveMode> getNotAstNodeSpecifier() {
        return Optional.empty();
    }
}
```

### Parser.get() とは

`Parser.get(SomeParser.class)` は、パーサーのシングルトンインスタンスを取得します。
同じパーサークラスに対しては常に同じインスタンスが返されるため、
パーサーグラフ内で効率的にパーサーを共有できます。

### getNotAstNodeSpecifier() とは

`LazyChain` / `LazyChoice` をオーバーライドする際、
`getNotAstNodeSpecifier()` は AST（抽象構文木）ノードとしての扱いを指定します。
`Optional.empty()` を返すと、このパーサーのトークンは AST ノードとして保持されます。

## ステップ5: 選択パーサー（Choice）

BNF:
```
SingleArgFuncName ::= 'sin' | 'cos' | 'tan' | 'sqrt' | 'abs' | 'log'
```

unlaxerでの実装：

```java
public static class SingleArgFunctionNameParser extends Choice {
    public SingleArgFunctionNameParser() {
        super(
            Name.of("singleArgFunctionName"),  // パーサーに名前を付ける
            new WordParser("sqrt"),             // 長い候補を先に（最長一致）
            new WordParser("sin"),
            new WordParser("cos"),
            new WordParser("tan"),
            new WordParser("abs"),
            new WordParser("log")
        );
    }
}
```

**重要**: `Choice` は上から順に各候補を試します。
`"sqrt"` と `"sin"` のように共通プレフィックスがある場合、
長い候補を先に配置しないと正しく解析されない場合があります。

## ステップ6: 循環参照を含むパーサー（LazyChain）

関数呼び出しのBNF:
```
SingleArgFunction ::= SingleArgFuncName '(' Expression ')'
```

`Expression` はまだ定義されていませんが、
`LazyChain` を使えば問題ありません：

```java
public static class SingleArgFunctionParser extends WhiteSpaceDelimitedLazyChain {
    @Override
    public Parsers getLazyParsers() {
        return new Parsers(
            Parser.get(SingleArgFunctionNameParser.class),
            Parser.get(LeftParenthesisParser.class),
            Parser.get(ExpressionParser.class),     // ← 循環参照！
            Parser.get(RightParenthesisParser.class)
        );
    }
}
```

`getLazyParsers()` は実際にパースが開始されるまで呼ばれません。
その時点では `ExpressionParser` のインスタンスが既に生成されているため、
循環参照が解決されます。

## ステップ7: 式パーサー（演算子優先順位）

### Term（乗除算）

```java
public static class TermParser extends WhiteSpaceDelimitedLazyChain {
    @Override
    public Parsers getLazyParsers() {
        return new Parsers(
            Parser.get(FactorParser.class),
            new ZeroOrMore(
                new WhiteSpaceDelimitedChain(     // 空白を許容するChain
                    Parser.get(MulOpParser.class),
                    Parser.get(FactorParser.class)
                )
            )
        );
    }
}
```

### Expression（加減算）

```java
public static class ExpressionParser extends WhiteSpaceDelimitedLazyChain {
    @Override
    public Parsers getLazyParsers() {
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

### WhiteSpaceDelimitedLazyChain / WhiteSpaceDelimitedChain

これらのクラスは、子パーサー間に自動的に `SpaceDelimitor`（0回以上の空白文字）を挿入します。
これにより `1+2*3` も `1 + 2 * 3` も同じようにパースできます。

内部的には：
```
元: [Factor, ZeroOrMore(...)]
↓ WhiteSpaceDelimited により変換
実際: [Space, Factor, Space, ZeroOrMore(...), Space]
```

## ステップ8: 変数宣言

```java
public static class VariableDeclarationParser extends WhiteSpaceDelimitedLazyChain {
    @Override
    public Parsers getLazyParsers() {
        return new Parsers(
            Parser.get(VarKeywordParser.class),       // 'var' | 'variable'
            Parser.get(IdentifierParser.class),        // 識別子名
            new Optional(                              // 省略可能な初期化
                Parser.get(VariableInitializerParser.class)  // 'set' Expression
            ),
            Parser.get(SemicolonParser.class)           // ';'
        );
    }
}
```

## ステップ9: ルートパーサー

```java
public static class TinyCalcParser extends WhiteSpaceDelimitedLazyChain {
    @Override
    public Parsers getLazyParsers() {
        return new Parsers(
            Parser.get(VariableDeclarationsParser.class),  // 0個以上の変数宣言
            Parser.get(ExpressionParser.class)              // 式
        );
    }
}
```

## パーサー定義の全体像

```
TinyCalcParser (WhiteSpaceDelimitedLazyChain)
├── VariableDeclarationsParser (LazyZeroOrMore)
│   └── VariableDeclarationParser (WhiteSpaceDelimitedLazyChain)
│       ├── VarKeywordParser (Choice): "variable" | "var"
│       ├── IdentifierParser (LazyChain)
│       ├── Optional
│       │   └── VariableInitializerParser: "set" Expression
│       └── SemicolonParser
└── ExpressionParser (WhiteSpaceDelimitedLazyChain)
    ├── TermParser (WhiteSpaceDelimitedLazyChain)
    │   ├── FactorParser (LazyChoice)
    │   │   ├── FunctionCallParser (LazyChoice)
    │   │   │   ├── TwoArgFunctionParser
    │   │   │   ├── SingleArgFunctionParser
    │   │   │   └── NoArgFunctionParser
    │   │   ├── UnaryExpressionParser (LazyChain)
    │   │   ├── NumberParser (既存)
    │   │   ├── IdentifierParser (LazyChain)
    │   │   └── ParenExpressionParser ← Expression (循環!)
    │   └── ZeroOrMore: MulOp Factor
    └── ZeroOrMore: AddOp Term
```

---

[← 02 - TinyCalc BNF定義](./02-bnf.md) | [目次](./index.md) | [04 - コアデータモデル →](./04-core-datamodel.md)
