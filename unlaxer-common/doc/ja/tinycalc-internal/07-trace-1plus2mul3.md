[← 06 - 各コンビネータの動作](./06-combinators.md) | [目次](./index.md) | [08 - 完全トレース: var x set 10; sin(x) + sqrt(3.14) →](./08-trace-complex.md)

# 07 - 完全トレース: `1+2*3`

## 入力

```
1+2*3
```

期待される解析: `1 + (2 * 3)` — 乗算が先に結合

## パーサー呼び出しの全体像

```
ExpressionParser.parse("1+2*3")
├── [Space] → <EMPTY>
├── TermParser.parse("1+2*3")
│   ├── [Space] → <EMPTY>
│   ├── FactorParser.parse("1+2*3")
│   │   ├── FunctionCallParser → 失敗（数字は関数名でない）→ バックトラック
│   │   ├── UnaryExpressionParser → 失敗（'+'/'-' でない）→ バックトラック
│   │   ├── NumberParser.parse("1+2*3")
│   │   │   ├── [Optional Sign] → <EMPTY>
│   │   │   ├── Choice(digits-point-digits | digits-point | digits | point-digits)
│   │   │   │   ├── digits-point-digits: "1" → "+" は '.' でない → 失敗
│   │   │   │   ├── digits-point: "1" → "+" は '.' でない → 失敗
│   │   │   │   └── digits: "1" ✓
│   │   │   ├── [Optional Exponent] → <EMPTY>
│   │   │   └── 結果: "1" ✓
│   │   └── 結果: NumberParser で "1" マッチ ✓
│   ├── [Space] → <EMPTY>
│   ├── ZeroOrMore( MulOp Factor )
│   │   ├── ループ1: "+" → MulOp(* | /) → 失敗 → ループ終了
│   │   └── 結果: 0回マッチ (stopped)
│   ├── [Space] → <EMPTY>
│   └── 結果: TermParser で "1" マッチ ✓
├── [Space] → <EMPTY>
├── ZeroOrMore( AddOp Term )
│   ├── ループ1:
│   │   ├── [Space] → <EMPTY>
│   │   ├── AddOpParser: "+" ✓
│   │   ├── [Space] → <EMPTY>
│   │   ├── TermParser.parse("2*3")
│   │   │   ├── [Space] → <EMPTY>
│   │   │   ├── FactorParser.parse("2*3")
│   │   │   │   ├── FunctionCallParser → 失敗 → バックトラック
│   │   │   │   ├── UnaryExpressionParser → 失敗 → バックトラック
│   │   │   │   ├── NumberParser: "2" ✓
│   │   │   │   └── 結果: NumberParser で "2" マッチ ✓
│   │   │   ├── [Space] → <EMPTY>
│   │   │   ├── ZeroOrMore( MulOp Factor )
│   │   │   │   ├── ループ1:
│   │   │   │   │   ├── [Space] → <EMPTY>
│   │   │   │   │   ├── MulOpParser: "*" ✓
│   │   │   │   │   ├── [Space] → <EMPTY>
│   │   │   │   │   ├── FactorParser.parse("3")
│   │   │   │   │   │   ├── FunctionCallParser → 失敗 → バックトラック
│   │   │   │   │   │   ├── UnaryExpressionParser → 失敗 → バックトラック
│   │   │   │   │   │   ├── NumberParser: "3" ✓
│   │   │   │   │   │   └── 結果: NumberParser で "3" マッチ ✓
│   │   │   │   │   └── ループ1 成功: "*3" マッチ
│   │   │   │   ├── ループ2: 入力終了 → ループ終了
│   │   │   │   └── 結果: 1回マッチ
│   │   │   ├── [Space] → <EMPTY>
│   │   │   └── 結果: TermParser で "2*3" マッチ ✓
│   │   └── ループ1 成功: "+2*3" マッチ
│   ├── ループ2: 入力終了 → ループ終了
│   └── 結果: 1回マッチ
├── [Space] → <EMPTY>
└── 結果: ExpressionParser で "1+2*3" 全体マッチ ✓
```

## バックトラックの詳細

この例では以下のバックトラックが発生しています：

### 1. FactorParser での FunctionCallParser 試行（3回）

各数値 `1`, `2`, `3` をパースする際、`FactorParser`（LazyChoice）は
まず `FunctionCallParser` を試みます。数字で始まる入力は関数名にマッチしないため、
即座に失敗してバックトラックします。

### 2. FactorParser での UnaryExpressionParser 試行（3回）

同様に、`UnaryExpressionParser` は `+` または `-` で始まる必要がありますが、
数字で始まるため失敗してバックトラックします。

### 3. NumberParser 内の Choice（3回）

`NumberParser` は数値形式を `Choice` で試行します：
1. `digits.digits`（小数）→ `.` が見つからず失敗
2. `digits.`（末尾ドット）→ `.` が見つからず失敗
3. `digits`（整数）→ 成功

## 実際の出力: トークンツリー

```
'1+2*3' : ExpressionParser
 '1' : TermParser
  '1' : FactorParser
   '1' : NumberParser
    <EMPTY> : optional-signParser
    '1' : Choice
     '1' : digits
      '1' : any-digit
       '1' : DigitParser
    <EMPTY> : Optional
  <EMPTY> : ZeroOrMore
 '+2*3' : ZeroOrMore
  '+2*3' : WhiteSpaceDelimitedChain
   '+' : AddOpParser
    '+' : PlusParser
   '2*3' : TermParser
    '2' : FactorParser
     '2' : NumberParser
      <EMPTY> : optional-signParser
      '2' : Choice
       '2' : digits
        '2' : any-digit
         '2' : DigitParser
      <EMPTY> : Optional
    '*3' : ZeroOrMore
     '*3' : WhiteSpaceDelimitedChain
      '*' : MulOpParser
       '*' : MultipleParser
      '3' : FactorParser
       '3' : NumberParser
        <EMPTY> : optional-signParser
        '3' : Choice
         '3' : digits
          '3' : any-digit
           '3' : DigitParser
        <EMPTY> : Optional
```

## 実際の出力: AST（縮約後）

```
'1+2*3' : ExpressionParser
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

## AST の読み方

AST は演算子の優先順位を木構造で表現しています：

```
      Expression(1+2*3)
      ├── Term(1)
      │   └── Factor(1) → Number(1)
      ├── Plus(+)
      └── Term(2*3)
          ├── Factor(2) → Number(2)
          ├── Multiple(*)
          └── Factor(3) → Number(3)
```

この構造から：
- `2*3` は1つの `Term` としてグループ化されている → 乗算が先に実行される
- `1` と `2*3` は `+` で結合されている → 加算が後に実行される
- つまり `1 + (2 * 3) = 7` と正しく解析されている

---

[← 06 - 各コンビネータの動作](./06-combinators.md) | [目次](./index.md) | [08 - 完全トレース: var x set 10; sin(x) + sqrt(3.14) →](./08-trace-complex.md)
