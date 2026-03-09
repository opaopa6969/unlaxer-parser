[← 05 - トランザクションスタックとバックトラック](./05-backtracking.md) | [目次](./index.md) | [07 - 完全トレース: 1+2*3 →](./07-trace-1plus2mul3.md)

# 06 - 各コンビネータの動作

## コンビネータ一覧

| コンビネータ | BNF記法 | 説明 |
|-------------|---------|------|
| `Chain` | `A B C` | 全てが順番に成功する必要がある |
| `Choice` | `A \| B \| C` | いずれか1つが成功すればよい |
| `ZeroOrMore` | `{ A }` | 0回以上の繰り返し |
| `OneOrMore` | `A+` | 1回以上の繰り返し |
| `Optional` | `[ A ]` | 0回または1回 |
| `WhiteSpaceDelimitedChain` | `A B C`（空白許容） | 要素間に空白を許容するChain |
| `LazyChain` | `A B C`（遅延） | 循環参照対応のChain |
| `LazyChoice` | `A \| B \| C`（遅延） | 循環参照対応のChoice |
| `LazyZeroOrMore` | `{ A }`（遅延） | 循環参照対応のZeroOrMore |

## Chain — 連接

### 動作

全ての子パーサーが順番に成功する必要があります。
1つでも失敗すると全体が失敗します。

### 例: TinyCalcでの使用

```java
// BNF: Digits '.' Digits
new Chain(
    digitsParser,
    pointParser,
    digitsParser
)
```

### パース過程

入力 `"3.14"` の場合：

```
Chain 開始
├── digitsParser: "3" ✓（位置 1）
├── pointParser: "." ✓（位置 2）
├── digitsParser: "14" ✓（位置 4）
└── 結果: 成功、マッチ "3.14"
```

入力 `"3+4"` の場合：

```
Chain 開始
├── digitsParser: "3" ✓（位置 1）
├── pointParser: "+" ✗
└── 結果: 失敗、バックトラック
```

## Choice — 選択

### 動作

候補を上から順に試し、最初に成功したものを返します。
全候補が失敗した場合のみ全体が失敗します。

### 例: TinyCalcでの使用

```java
// BNF: '*' | '/'
public static class MulOpParser extends Choice {
    public MulOpParser() {
        super(
            MultipleParser.class,   // '*' を先に試す
            DivisionParser.class    // '/' を次に試す
        );
    }
}
```

### パース過程

入力の現在位置が `"*3"` の場合：

```
Choice 開始
├── MultipleParser: "*" ✓
└── 結果: 成功、マッチ "*"
```

入力の現在位置が `"/3"` の場合：

```
Choice 開始
├── MultipleParser: "/" ✗ → バックトラック
├── DivisionParser: "/" ✓
└── 結果: 成功、マッチ "/"
```

## ZeroOrMore — 0回以上の繰り返し

### 動作

子パーサーを繰り返し適用し、失敗するまでマッチを続けます。
0回のマッチでも成功（`stopped` ステータス）を返します。

### 例: TinyCalcでの使用

```java
// BNF: { ('*' | '/') Factor }
new ZeroOrMore(
    new WhiteSpaceDelimitedChain(
        Parser.get(MulOpParser.class),
        Parser.get(FactorParser.class)
    )
)
```

### パース過程

入力 `"*3/2"` の場合：

```
ZeroOrMore 開始
├── ループ1: "*3" ✓（位置 2）→ 継続
├── ループ2: "/2" ✓（位置 4）→ 継続
├── ループ3: 入力終了 ✗ → ループ終了
└── 結果: 成功（2回マッチ）
```

入力 `"+3"` の場合（乗除算なし）：

```
ZeroOrMore 開始
├── ループ1: "+" ✗ → ループ終了
└── 結果: 成功（0回マッチ、stopped）
```

## OneOrMore — 1回以上の繰り返し

### 動作

`ZeroOrMore` と同じですが、最低1回はマッチする必要があります。

### 例: TinyCalcでの使用

```java
// BNF: Digit+
new OneOrMore(DigitParser.class)
```

### パース過程

入力 `"123"` の場合：

```
OneOrMore 開始
├── ループ1: "1" ✓ → 継続
├── ループ2: "2" ✓ → 継続
├── ループ3: "3" ✓ → 継続
├── ループ4: 次が数字でない → ループ終了
└── 結果: 成功（3回マッチ）
```

## Optional — 省略可能

### 動作

子パーサーのマッチを試み、失敗しても全体は成功を返します。

### 例: TinyCalcでの使用

```java
// BNF: ['set' Expression]
new Optional(
    Parser.get(VariableInitializerParser.class)
)
```

### パース過程

入力に `set` がある場合：

```
Optional 開始
├── VariableInitializerParser: "set 10" ✓
└── 結果: 成功、マッチ "set 10"
```

入力に `set` がない場合（直後にセミコロン）：

```
Optional 開始
├── VariableInitializerParser: ";" ✗ → バックトラック
└── 結果: 成功（stopped）、マッチなし
```

## WhiteSpaceDelimitedChain — 空白許容の連接

### 動作

`Chain` と同じですが、各子パーサーの前後に `SpaceDelimitor`（0個以上の空白）を自動挿入します。

### 内部変換

```
定義: WhiteSpaceDelimitedChain(A, B, C)

内部的に:
  Chain(Space, A, Space, B, Space, C, Space)
```

ここで `Space` は `SpaceDelimitor`（`LazyZeroOrMore` のサブクラス）です。

### 例: TinyCalcでの使用

```java
// BNF: Factor { ('*' | '/') Factor }
// "2 * 3" も "2*3" もパース可能
public static class TermParser extends WhiteSpaceDelimitedLazyChain {
    @Override
    public Parsers getLazyParsers() {
        return new Parsers(
            Parser.get(FactorParser.class),
            new ZeroOrMore(
                new WhiteSpaceDelimitedChain(
                    Parser.get(MulOpParser.class),
                    Parser.get(FactorParser.class)
                )
            )
        );
    }
}
```

### SpaceDelimitor の仕組み

`SpaceDelimitor` は `LazyZeroOrMore` を拡張しており、
`SpaceParser`（空白文字1つ）を0回以上繰り返します。

```java
public class SpaceDelimitor extends LazyZeroOrMore {
    @Override
    public Supplier<Parser> getLazyParser() {
        return new SupplierBoundCache<>(SpaceParser::new);
    }
}
```

`SpaceDelimitor` には `NodeKind.notNode` タグが付与されており、
AST（`getRootToken(true)`）からは自動的に除外されます。

## コンビネータの組み合わせ

TinyCalcの式パーサーは、コンビネータを組み合わせて構築されています：

```
ExpressionParser
  = WhiteSpaceDelimitedLazyChain(       ← 空白を許容する遅延Chain
      TermParser,                        ← 子パーサー1
      ZeroOrMore(                        ← 子パーサー2: 0回以上の繰り返し
        WhiteSpaceDelimitedChain(        ← 空白を許容するChain
          AddOpParser,                   ← Choice('+', '-')
          TermParser                     ← 再帰的にTermを参照
        )
      )
    )
```

この構造により、以下の全てが正しくパースされます：

- `1+2` — 空白なし
- `1 + 2` — 空白あり
- `1+2+3+4` — 複数の加算
- `1 + 2*3 - 4/5` — 演算子優先順位の正しい解析

---

[← 05 - トランザクションスタックとバックトラック](./05-backtracking.md) | [目次](./index.md) | [07 - 完全トレース: 1+2*3 →](./07-trace-1plus2mul3.md)
