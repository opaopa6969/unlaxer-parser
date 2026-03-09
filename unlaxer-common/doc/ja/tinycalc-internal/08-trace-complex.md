[← 07 - 完全トレース: 1+2*3](./07-trace-1plus2mul3.md) | [目次](./index.md) | [09 - Lazyパーサーと再帰文法 →](./09-lazy-and-recursion.md)

# 08 - 完全トレース: `var x set 10; sin(x) + sqrt(3.14)`

## 入力

```
var x set 10; sin(x) + sqrt(3.14)
```

この入力は TinyCalc の全機能を使用しています：
- 変数宣言（初期化付き）
- 関数呼び出し（1引数）
- 四則演算
- 識別子の参照
- 浮動小数点数

## パーサー呼び出しの全体像

```
TinyCalcParser.parse("var x set 10; sin(x) + sqrt(3.14)")
│
├── VariableDeclarationsParser (ZeroOrMore)
│   ├── ループ1: VariableDeclarationParser
│   │   ├── [Space] → <EMPTY>
│   │   ├── VarKeywordParser (Choice)
│   │   │   ├── "variable" → "var" は3文字で "variable" は8文字 → 失敗
│   │   │   └── "var" ✓
│   │   ├── [Space] → " " (1文字の空白)
│   │   ├── IdentifierParser: "x" ✓
│   │   ├── [Space] → " "
│   │   ├── Optional(VariableInitializerParser)
│   │   │   ├── VariableInitializerParser
│   │   │   │   ├── [Space] → <EMPTY>
│   │   │   │   ├── WordParser("set"): "set" ✓
│   │   │   │   ├── [Space] → " "
│   │   │   │   ├── ExpressionParser: "10" ✓
│   │   │   │   ├── [Space] → <EMPTY>
│   │   │   │   └── 結果: "set 10" ✓
│   │   │   └── 結果: "set 10" ✓
│   │   ├── [Space] → <EMPTY>
│   │   ├── SemicolonParser: ";" ✓
│   │   ├── [Space] → <EMPTY>
│   │   └── 結果: "var x set 10;" ✓
│   ├── ループ2: VariableDeclarationParser
│   │   ├── VarKeywordParser → "sin" はキーワードでない → 失敗
│   │   └── バックトラック → ループ終了
│   └── 結果: 1回マッチ
│
├── [Space] → " "
│
├── ExpressionParser.parse("sin(x) + sqrt(3.14)")
│   ├── TermParser
│   │   ├── FactorParser
│   │   │   ├── FunctionCallParser (LazyChoice)
│   │   │   │   ├── TwoArgFunctionParser
│   │   │   │   │   ├── TwoArgFuncName → "sin" はmax/min/powでない → 失敗
│   │   │   │   │   └── バックトラック
│   │   │   │   ├── SingleArgFunctionParser
│   │   │   │   │   ├── SingleArgFuncName → "sin" ✓
│   │   │   │   │   ├── '(' ✓
│   │   │   │   │   ├── ExpressionParser → "x" ✓ (識別子)
│   │   │   │   │   ├── ')' ✓
│   │   │   │   │   └── 結果: "sin(x)" ✓
│   │   │   │   └── 結果: SingleArgFunctionParser ✓
│   │   │   └── 結果: "sin(x)" ✓
│   │   ├── ZeroOrMore(MulOp Factor)
│   │   │   ├── " " → MulOpParser: "+" は '*'/'/' でない → 失敗
│   │   │   └── 結果: 0回マッチ
│   │   └── 結果: "sin(x)" ✓
│   ├── ZeroOrMore(AddOp Term)
│   │   ├── ループ1:
│   │   │   ├── AddOpParser: "+" ✓
│   │   │   ├── TermParser
│   │   │   │   ├── FactorParser
│   │   │   │   │   ├── FunctionCallParser
│   │   │   │   │   │   ├── TwoArgFunctionParser → 失敗
│   │   │   │   │   │   ├── SingleArgFunctionParser
│   │   │   │   │   │   │   ├── SingleArgFuncName → "sqrt" ✓
│   │   │   │   │   │   │   ├── '(' ✓
│   │   │   │   │   │   │   ├── ExpressionParser → "3.14" ✓ (数値)
│   │   │   │   │   │   │   ├── ')' ✓
│   │   │   │   │   │   │   └── 結果: "sqrt(3.14)" ✓
│   │   │   │   │   │   └── 結果: SingleArgFunctionParser ✓
│   │   │   │   │   └── 結果: "sqrt(3.14)" ✓
│   │   │   │   ├── ZeroOrMore → 0回マッチ
│   │   │   │   └── 結果: "sqrt(3.14)" ✓
│   │   │   └── ループ1 成功: "+ sqrt(3.14)" マッチ
│   │   ├── ループ2: 入力終了 → ループ終了
│   │   └── 結果: 1回マッチ
│   └── 結果: "sin(x) + sqrt(3.14)" ✓
│
└── 結果: 全体マッチ ✓
```

## 注目ポイント

### 1. 変数宣言のループ終了

`VariableDeclarationsParser` は `ZeroOrMore` なので、変数宣言を繰り返し読みます。
2回目のループで `"sin"` を読もうとしますが、`VarKeywordParser`（"var" | "variable"）に
マッチしないため失敗し、ループが終了します。

### 2. VarKeywordParser の候補順序

```java
new WordParser("variable"),  // 長い方を先に
new WordParser("var")        // 短い方を後に
```

`"variable"` を先に置くことで、`"variable"` が `"var"` + `"iable"` と
誤って解析されることを防いでいます。
ただし入力が `"var "` の場合は `"variable"` が失敗してから `"var"` が成功します。

### 3. FunctionCallParser でのバックトラック

`sin(x)` のパース時、`FunctionCallParser` はまず `TwoArgFunctionParser` を試します。
`TwoArgFunctionNameParser`（min | max | pow）が `"sin"` にマッチしないため即座に失敗し、
バックトラックして `SingleArgFunctionParser` を試みます。

### 4. 関数内部の Expression

`sin(x)` の括弧内の `x` は、`ExpressionParser` → `TermParser` → `FactorParser` を経て
`IdentifierParser` としてマッチします。

`sqrt(3.14)` の括弧内の `3.14` は、同じパスを経て `NumberParser` としてマッチします。

### 5. 空白の処理

`WhiteSpaceDelimitedLazyChain` により、以下の位置で空白がスキップされます：

```
var[空白]x[空白]set[空白]10[空白];[空白]sin(x)[空白]+[空白]sqrt(3.14)
```

これらの空白はトークンツリーには `SpaceDelimitor` として記録されますが、
AST からは自動的に除外されます。

## パフォーマンスの観点

この例でのバックトラック回数：

| パーサー | バックトラック原因 | 回数 |
|----------|-------------------|------|
| VarKeywordParser | "variable" 試行後 "var" に戻る | 1 |
| FunctionCallParser | TwoArgFunc 失敗後に戻る | 2 |
| FactorParser | FuncCall/Unary 失敗後に戻る | 数回 |
| NumberParser内Choice | 小数形式の試行失敗 | 数回 |

バックトラック回数は入力長に対してほぼ線形であり、
TinyCalcの文法では指数的な爆発は起こりません。

---

[← 07 - 完全トレース: 1+2*3](./07-trace-1plus2mul3.md) | [目次](./index.md) | [09 - Lazyパーサーと再帰文法 →](./09-lazy-and-recursion.md)
