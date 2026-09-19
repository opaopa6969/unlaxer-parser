# 生成parserのスコープ注釈

JavaとRustは、`@scopeTree(mode=lexical|dynamic)`、`@declares(symbol=name, description=doc)`、
文法内に`@scopeTree`がある場合の`@backref(name=name)`を生成parserへ接続する（#176）。
Java frontendからのRust生成と、JVM不要のnative generatorは同じIR・生成コードになる。

## 動作契約

- スコープ付きルールは開始時にenterし、成功時にleaveする。
- 同じルールが宣言も行う場合、leaveしてから親スコープ（なければglobal）へ登録する。
- `symbol` / `name`はそのルール本体のcapture名。パーサークラスが同じ別のtokenから推測しない。
- optional不在はイベントなし。反復では全出現を処理し、入れ子captureは内側の完了から順に処理する。
  別ルール・再帰呼出しの内部captureは、呼出し元のcaptureとして拾わない。
- 宣言後に参照を処理する。未定義参照は`WARNING`（`未定義のシンボル: 'name'`）を残すが、構文解析自体は成功する。
- captureの原文をJava `String.trim()`相当（両端のU+0000〜U+0020）でtrimする。
  引用符・escapeはそのままで、文字列literalの値への変換は行わない。
  offsetは先頭trim分を加算し、offsetとlengthはUnicodeコードポイント単位。空の名前は無視する。
- choice失敗、子成功後の親rollback、lookaheadによる一時状態はtransactionに従って復元する。
  詳細は[transactional scope store](transactional-scopes.md)を参照。

両modeは現在、**解析時のネストしたスコープスタック**として動作する。
`dynamic`というmetadataは保持するが、評価時の動的環境やclosureを実装したという意味ではない。
`description`もmetadataとして保持するのみで、そのcaptureの存在や値は検証・評価しない。

## APIと移行

Javaは従来の`ScopeStore`と生成metadata query APIを使う。
Rustは生成`RuleEffects`でmode・declaration・referenceを公開し、
`ParseContext.scopes()` / `scopes_mut()`で実状態を扱う。
owned `Tree.scopes()`はtree取得時のsnapshotなので、context破棄・後続解析・rollbackから独立する。
typed AST内にscope storeを埋め込むことやLSP/DAPへの搬送は未実装。
また、同名nested captureをmapped fieldにするとJavaは外側scalar、Rustは内外listになる既存差がある。
これは[#177](https://github.com/opaopa6969/unlaxer-parser/issues/177)で追跡し、
今回のnested scope比較ではmappingなしの宣言ルールで両イベントを検査する。AST互換済みとはしない。

Java生成parserは再生成が必要。従来の「同じparser classの最初のtoken」を取る挙動や、
対象が見つからないと別tokenを採用する挙動は修正される。
宣言・スコープ参照の対象capture欠損、および重複`@declares`は生成前に明示的な検証エラーになる。
mappingなしの注釈付きルールでもcaptureは利用できるが、mappingのfield/capture整合性検査は緩めない。

`@scopeTree`がない文法の`@backref`は別契約である。Javaの従来の同一ルール内テキスト比較は変更せず、
Rustのlowererは明示的に未対応エラーを返す。公開Rust combinatorのcontext-wide replayとも区別する。

## 検証

`ScopeCaptureBindingTest`はJava実生成parserをコンパイルしてcapture選択と位置を検査する。
`ScopeAnnotationConformanceTest`は共通文法・入力・独立期待値からJava/Rustの受理、cursor、
AST field/span、scope depth、lookup、宣言・参照・診断を比較する。
Java/native frontendの全5生成file一致と、native generatorが空PATHでも動くことを検査し、
結果を`unlaxer-dsl/target/rust-scope-annotations.tsv`へ保存する。CIではTSVを必須artifactとする。
