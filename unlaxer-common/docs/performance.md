# Performance Guide

## Parser Architecture

unlaxer は **PEG (Parsing Expression Grammar) ベースのバックトラッキングパーサー**。
全選択肢を試行し、最初に成功したものを採用する ordered choice モデル。

## 時間計算量

### 通常ケース: O(n)
- 入力に対して各位置で1つの選択肢のみ成功する場合
- ほとんどの実用的な文法がこのケースに該当

### 最悪ケース: O(2^n)
- 全選択肢がほぼ同じ長さまでマッチしてから失敗する場合
- 例: `A = 'a' A 'a' | 'a'` のような曖昧な文法

### メモ化適用時: O(n^3)
- Packrat parsing の理論的上限
- メモ化テーブルのメモリコスト: O(n * |rules|)

## メモ化ガイド

### いつ使うべきか

`ParseContext.doMemoize` を有効にすべき場面:

1. **深い選択肢**: Choice の代替が多く (5個以上)、各代替が長い prefix を共有する
2. **ネストした繰り返し**: `{ { A } B }` のような二重ループ構造
3. **大きな入力**: 1000行以上のソースをパースする場合

### いつ不要か

1. **単純な文法**: キーワード + 式の組み合わせ程度
2. **小さな入力**: 100行以下
3. **LL(1) 的な構造**: 各位置で最初のトークンを見れば決定できる文法

### 使用方法

```java
ParseContext context = new ParseContext(source);
context.doMemoize = true;  // メモ化を有効化
Parsed result = parser.parse(context);
```

## 左再帰の注意

unlaxer は **左再帰を検出しない**。左再帰のある文法はスタックオーバーフローを起こす。

```
// NG: 左再帰（無限ループ）
Expression = Expression '+' Term | Term ;

// OK: 繰り返しで書き換え
Expression = Term { '+' Term } ;

// OK: @rightAssoc で右結合に
@rightAssoc
Expression = Term { '+' Expression } ;
```

## プロファイル方法

### ParserListener でトレース

```java
ParseContext context = new ParseContext(source);
context.addListener(new DebugParserListener(OutputLevel.detail));
Parsed result = parser.parse(context);
// build/parserTest/ にログ出力
```

### 実行時間計測

```java
long start = System.nanoTime();
Parsed result = parser.parse(context);
long elapsed = System.nanoTime() - start;
System.out.printf("Parse time: %.2f ms%n", elapsed / 1_000_000.0);
```

## ベンチマーク参考値

tinyexpression 文法 (~300行 UBNF) での計測:

| 入力サイズ | パース時間 (目安) | メモ化 |
|-----------|-----------------|--------|
| 10行 | < 1ms | 不要 |
| 100行 | ~5ms | 不要 |
| 1000行 | ~50ms | 推奨 |
| 10000行 | ~500ms | 必須 |

※ 環境依存。Java 21, modern hardware での概算。

## インクリメンタルパーシング

`IncrementalParseCache` を使用すると、変更箇所の前後のみ再パースできる。
LSP サーバーの `textDocument/didChange` での応答改善に有効。

```java
IncrementalParseCache cache = new IncrementalParseCache();
// 初回パース
cache.parse(source, parser);
// 更新パース（変更範囲のみ再パース）
cache.update(newSource, changeRange, parser);
```

## 設計上のヒント

1. **トークン定義を活用**: 正規表現トークン (`REGEX`) でレキサーレベルの高速マッチ
2. **@whitespace で自動空白スキップ**: 手動の空白処理を避ける
3. **@recovery で部分パース**: エラー後も続行してフィードバックを最大化
4. **選択肢の順序**: 頻度の高い代替を先頭に配置（PEG は ordered choice）
