# 選択可能な字句処理の共通 corpus

`corpus.json` は文法・入力・独立期待値、`invalid.json` は拒否する profile の診断 code と subject。`TokenStreamConformanceTest` が Java と Rust を実生成・コンパイルし、各入力を 4 mode × trivia 公開有無で照合する。native Rust generator と Java 側 RustBackend の生成ファイルも一致を要求する。

空白、行・block コメント、Unicode、CRLF、分岐の rollback、予約語と識別子の最長一致、改行を構文 token とする文法、文字列と可変 fence の内部、反復、EMPTY / EOF を含む。AST の値・source span と named CST capture、字句列を検査する。補助 CST node の class 名や backend 固有の診断文言まで同一にする契約ではない。

`benchmark.ubnf` と `benchmark.rs` は同じ成功入力に対する時間・割当量・評価回数の測定。Java harness も同じケースを持つ。`measurement.tsv` は開発時の 1 回の記録で、期待値ファイルではない。再現コマンドと測定の限界は [仕様](../../docs/token-stream.md) を参照。

測定スナップショット: Linux x86_64、Java HotSpot 21.0.9、rustc 1.98.1 (`-O`)、2026-10-06。互換性テストは別途 Rust 1.85.0 / Java 17 でも実行する。8 文法・27 入力・216 組合せと、10 件の拒否 fixture を使用する。
