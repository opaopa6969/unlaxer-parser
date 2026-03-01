[← 04 - コアデータモデル](./04-core-datamodel.md) | [目次](./index.md) | [06 - 各コンビネータの動作 →](./06-combinators.md)

# 05 - トランザクションスタックとバックトラック

## バックトラックとは

バックトラック（backtracking）とは、パーサーが入力のある地点まで進んだ後、
「この解釈は間違いだった」と判断した場合に、以前の位置まで戻ることです。

例えば `sin(x)` をパースするとき：

1. `FunctionCallParser` が `TwoArgFunctionParser` を最初に試す
2. `sin(x` まで進むが、カンマ `,` が見つからない → 失敗
3. カーソルを `sin` の前まで巻き戻す（バックトラック）
4. 次に `SingleArgFunctionParser` を試す → 成功

## unlaxerのトランザクション機構

unlaxerでは、バックトラックを **トランザクションスタック** で実現しています。
これはデータベースのトランザクションと似た概念です：

| 操作 | データベース | unlaxer |
|------|-------------|---------|
| 開始 | `BEGIN` | トランザクション開始（カーソル位置を記録） |
| 確定 | `COMMIT` | マッチ成功 → カーソルを進めた位置で確定 |
| 取消 | `ROLLBACK` | マッチ失敗 → カーソルを元の位置に戻す |

### トランザクションの流れ

```
入力: "sin(x)" を FunctionCallParser でパース

FunctionCallParser (LazyChoice) 開始
│
├── TwoArgFunctionParser を試行
│   │ BEGIN トランザクション（位置 0 を記録）
│   ├── TwoArgFuncName: "sin" ✓（位置 3 に進む）
│   ├── '(': "(" ✓（位置 4 に進む）
│   ├── Expression: "x" ✓（位置 5 に進む）
│   ├── ',': なし ✗
│   │ ROLLBACK（位置 0 に戻る）← バックトラック！
│
├── SingleArgFunctionParser を試行
│   │ BEGIN トランザクション（位置 0 を記録）
│   ├── SingleArgFuncName: "sin" ✓（位置 3 に進む）
│   ├── '(': "(" ✓（位置 4 に進む）
│   ├── Expression: "x" ✓（位置 5 に進む）
│   ├── ')': ")" ✓（位置 6 に進む）
│   │ COMMIT（位置 6 で確定）
│
└── 結果: SingleArgFunctionParser でマッチ成功
```

## Choice におけるバックトラック

`Choice`（選択）パーサーは、候補を上から順に試します。
各候補を試す際にトランザクションを開始し、失敗したら巻き戻します。

```
Choice(A, B, C) の動作:

1. BEGIN
   A を試す → 失敗 → ROLLBACK
2. BEGIN
   B を試す → 成功 → COMMIT → B の結果を返す
3. C は試されない（B が成功したため）
```

### 重要な性質

- Choice は **最初に成功した** 候補の結果を返す（最長一致ではない）
- 候補の順序が重要：共通プレフィックスがある場合、長い方を先に置く
- 例：`"sqrt"` と `"sin"` → `"sqrt"` を先に配置

## Chain におけるバックトラック

`Chain`（連接）パーサーは、全ての子パーサーが順番に成功する必要があります。
途中で1つでも失敗すると、全体として失敗します。

```
Chain(A, B, C) の動作:

BEGIN
├── A を試す → 成功（位置進む）
├── B を試す → 失敗
ROLLBACK（A が消費した分も含めて巻き戻す）
```

## ZeroOrMore におけるバックトラック

`ZeroOrMore` は繰り返しパーサーで、0回以上のマッチを試みます。

```
ZeroOrMore(A) の動作:

ループ:
  BEGIN
  A を試す → 成功 → COMMIT → もう一度ループ
  ...
  BEGIN
  A を試す → 失敗 → ROLLBACK → ループ終了

結果: 成功した回数分のトークンを返す（0回でも成功）
```

## ネストしたトランザクション

トランザクションはスタック構造でネストします。
外側のパーサーが開始したトランザクションの中で、
内側のパーサーが新たなトランザクションを開始・完了します。

```
ExpressionParser の "1+2" パース:

[深さ0] ExpressionParser BEGIN
  [深さ1] TermParser BEGIN
    [深さ2] FactorParser BEGIN
      [深さ3] NumberParser BEGIN
        "1" マッチ → COMMIT [深さ3]
      COMMIT [深さ2]
    [深さ2] ZeroOrMore BEGIN
      乗除算なし → ROLLBACK → stopped [深さ2]
    COMMIT [深さ1]
  [深さ1] ZeroOrMore BEGIN
    [深さ2] Chain BEGIN
      "+" マッチ
      [深さ3] TermParser BEGIN
        "2" マッチ → ...
        COMMIT [深さ3]
      COMMIT [深さ2]
    もう一度ループ → 失敗 → ROLLBACK [深さ2]
    COMMIT [深さ1]
  COMMIT [深さ0]
```

## パフォーマンスへの影響

バックトラックは便利ですが、最悪の場合は指数的な時間がかかる可能性があります。
unlaxerでは以下の方法でこれを緩和しています：

1. **Choice の順序最適化** — より可能性の高い候補を先に配置
2. **LazyChoice による遅延評価** — 必要になるまでパーサーを生成しない
3. **トランザクションの効率的な実装** — カーソル位置の記録・復元のみで、
   入力文字列のコピーは行わない

### TinyCalcでの考慮点

`FactorParser` の候補順序は意図的に選ばれています：

```java
Parser.get(FunctionCallParser.class),     // 1. 関数呼び出し（最も複雑）
Parser.get(UnaryExpressionParser.class),  // 2. 単項演算子
Parser.get(NumberParser.class),           // 3. 数値
Parser.get(IdentifierParser.class),       // 4. 識別子
Parser.get(ParenExpressionParser.class)   // 5. 括弧式
```

この順序により、`sin(x)` は先に関数呼び出しとして認識され、
識別子 `sin` とは解釈されません。

---

[← 04 - コアデータモデル](./04-core-datamodel.md) | [目次](./index.md) | [06 - 各コンビネータの動作 →](./06-combinators.md)
