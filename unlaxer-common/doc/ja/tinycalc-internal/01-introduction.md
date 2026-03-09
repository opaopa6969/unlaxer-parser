---

[目次](./index.md) | [02 - TinyCalc BNF定義 →](./02-bnf.md)

# 01 - イントロダクション

## このドキュメントについて

本ドキュメントシリーズでは、パーサーコンビネータライブラリ **unlaxer** の内部構造を、
小さな計算機言語「**TinyCalc**」の実装を通じて詳細に解説します。

## unlaxerとは

unlaxer は、RELAX NG にインスパイアされた Java 向けパーサーコンビネータライブラリです。
以下の特徴を持ちます：

- **コンビネータパターン** — 小さなパーサーを組み合わせて複雑な文法を構築
- **無限先読みとバックトラック** — トランザクションスタックによる安全な試行・巻き戻し
- **再帰文法のサポート** — `LazyChain` / `LazyChoice` による循環参照の解決
- **豊富なデバッグ機能** — リスナーシステムによるパース過程の可視化
- **トークンツリーの自動構築** — パース結果を木構造として取得可能

## TinyCalcとは

TinyCalc は、unlaxer の動作を説明するために設計された小さな計算機言語です。
以下の機能を持ちます：

| 機能 | 例 |
|------|-----|
| 四則演算 | `1 + 2 * 3` |
| 括弧 | `(1 + 2) * 3` |
| 単項演算子 | `-5 + 3` |
| 浮動小数点数 | `3.14 * 2` |
| 組み込み関数（1引数） | `sin(3.14)`, `sqrt(2)` |
| 組み込み関数（2引数） | `max(1, 2)`, `pow(2, 8)` |
| 組み込み関数（0引数） | `random()` |
| 変数宣言 | `var x set 10;` |
| 識別子の参照 | `x + y * z` |

## ドキュメント構成

| 章 | 内容 |
|----|------|
| [02 - BNF定義](02-bnf.md) | TinyCalcの文法をBNFで定義 |
| [03 - パーサーの組み立て](03-building-parsers.md) | BNFからパーサーへの変換手順 |
| [04 - コアデータモデル](04-core-datamodel.md) | Source, Cursor, Token, Parsed の解説 |
| [05 - バックトラック](05-backtracking.md) | トランザクションスタックの動作 |
| [06 - コンビネータ](06-combinators.md) | 各コンビネータの詳細動作 |
| [07 - トレース: 1+2*3](07-trace-1plus2mul3.md) | 完全なパーストレース |
| [08 - トレース: 複合式](08-trace-complex.md) | 変数宣言と関数呼び出しのトレース |
| [09 - Lazyと再帰](09-lazy-and-recursion.md) | Lazyパーサーと再帰文法の解説 |
| [10 - デバッグシステム](10-debug-system.md) | リスナーとデバッグ機能 |

## ソースコード

動作するソースコードは `examples/tinycalc/` にあります：

```
examples/tinycalc/
  pom.xml                                          -- Mavenビルド設定
  src/main/java/org/unlaxer/tinycalc/
    TinyCalcParsers.java                            -- 全パーサー定義
    TinyCalcDemo.java                               -- デモ実行用メイン
```

### ビルドと実行

```bash
# unlaxer-commonをローカルにインストール
mvn -Dgpg.skip=true install

# TinyCalcをコンパイル
mvn -f examples/tinycalc/pom.xml compile

# デモ実行
mvn -f examples/tinycalc/pom.xml exec:java \
  -Dexec.mainClass="org.unlaxer.tinycalc.TinyCalcDemo"
```

---

[目次](./index.md) | [02 - TinyCalc BNF定義 →](./02-bnf.md)
