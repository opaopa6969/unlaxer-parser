# TinyCalc パーサー内部解説

unlaxerパーサーコンビネータライブラリの内部構造を、TinyCalc計算機言語の実装を通じて解説するドキュメントシリーズです。

## 目次

1. [イントロダクション](01-introduction.md)
2. [TinyCalc BNF定義](02-bnf.md)
3. [BNFからパーサーを組み立てる](03-building-parsers.md)
4. [コアデータモデル](04-core-datamodel.md)
5. [トランザクションスタックとバックトラック](05-backtracking.md)
6. [各コンビネータの動作](06-combinators.md)
7. [完全トレース: 1+2*3](07-trace-1plus2mul3.md)
8. [完全トレース: var x set 10; sin(x) + sqrt(3.14)](08-trace-complex.md)
9. [Lazyパーサーと再帰文法](09-lazy-and-recursion.md)
10. [デバッグ・リスナーシステム](10-debug-system.md)
11. [2.4.0互換レイヤーと診断拡張](11-compat-and-diagnostics.md)

## ソースコード

動作するコードは [`examples/tinycalc/`](../../../examples/tinycalc/) にあります。
