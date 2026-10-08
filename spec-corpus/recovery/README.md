# 状態付き回復の共通コーパス

`corpus.json` は Java 生成parser と Java/native Rust両hostの生成parserに同じ入力を与える
独立oracle。正常な受理と、エラー領域を保持した回復受理を区別する。

- `prefix`: 受理、消費cursor、照合cursor（Unicode code point単位）。
- `recoveries`: committed CSTに残る回復領域の半開CP span。全件のmessageは
  `syntax error: skipped to sync point`。outer rollbackや負けたchoiceの領域は含まない。
- `declarations`: 省略時は空。scopeで成功時の宣言が残り、失敗したchildの宣言が回復前に消えることを比較する。
- `ast`: 回復も余剰入力もない成功時だけ。全fieldとsource spanを比較する。
- `failureDetail`: 指定したケースでは、full-input失敗の`offset/expected`も独立期待値と比較する。
  同期点より先で起きたchildの失敗履歴を、回復成功時に消さないことを固定する。

18文法43入力を、`Auto` / `Detailed` / `DetailedOnFailure` × memo `Off` / `SafeFailures`の
6設定で検証する。root annotation、UTF-16/code-point境界、同期tokenのprefix競合、CRLF、
NBSP/EM SPACE、sync/auto/skip、反復、ordered/predictive/longest choice、scope rollbackを含む。

`RecoveryConformanceTest`は全5生成fileのbyte一致、実javac/rustcコンパイルと実行を検査する。
native generatorと生成済みprobeは`PATH=''`、無効な`JAVA_HOME`で起動する。
回復時はCSTを返せるが、通常のtyped mapperは明示拒否し、正常ASTを捏造しない。
Javaのroot/subtreeのmapping入口とsource-based parse、Rustのmapperをそれぞれ検証する。
Java `diagnose`の`recovery`分類は、Rustが返すowned Treeの回復記録と対応させる。
構文失敗のnative expected候補文言を正規化しない。`failureDetail`の指定例は
明示的な`ERROR('child tail')`により両backend共通の期待文言を持つ。

結果は`unlaxer-dsl/target/rust-recovery.tsv`に、全258入力・設定組の期待値と両backendの実測値を保存する。
追加の`failure_expected/java_failure/rust_failure`列は、独立期待値がある場合だけ比較対象とし、
それ以外のnative診断は観測値として保存する（Javaの回復分類とRustのTree成功は表現が異なる）。

詳細は[回復の契約](../../docs/error-recovery.md)を参照。
