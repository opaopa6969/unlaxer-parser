# Diagnostics::Auto の Rust facade 計測（issue #261）

2026-09-21、Rust runtime の既定を Detailed から Auto へ変更した前後を、同じ
`tinyexpression_rs::parse` facade で比較した。成功入力は短縮し、失敗入力は再解析分だけ増えた。
これは Rust 側の検証記録であり、並行して実装される Java 側の完了根拠ではない。

## 条件

- 比較元: unlaxer-parser `5f70015922a024e17f0f7e6ee81b113e390671d4`。
- 比較先: この記録と同じ commit の Rust runtime（`feat/diagnostics-auto-rust-261`）。
- facade / fixture: tinyexpression `873800201a6aa2128538f11f88f297687b8e886b`。
  `benchmarks/fixtures/` の4入力を無改変で使用。
- Linux WSL2 `6.18.33.2-microsoft-standard-WSL2`、AMD Ryzen 9 7950X、CPU 0 に固定。
- `cargo +1.85`（cargo / rustc 1.85.1）、release、`--locked --offline`。
  依存は path 指定の tinyexpression-rs と unlaxer-runtime だけ。外部 crate・unsafe の追加なし。
- `mktemp -d` 配下に独立 crate を作成。master は `git archive 5f70015 rust` で展開し、
  `patch."https://github.com/opaopa6969/unlaxer-parser.git".unlaxer-runtime.path` と直接の path 依存を
  master / 作業版に切り替えて別 target directory へビルドした。
- facade の `ParseOptions::with_memoization(Memoization::SafeFailures)` は変更せず、
  master では Detailed、作業版では Auto → DetailedOnFailure になる。
- 各入力・各版で5 process × 31回 = **155回**。各 process の先頭5回は warmup として除外。
  process の master / 作業版の実行順を試行ごとに反転し、並列には計測しなかった。
- 各 parse の前後で `std::thread::yield_now()` 後に `/proc/thread-self/schedstat` の
  実行時間（ns）を読み、差分155個の中央値を採用。単一 thread の CPU 時間であり wall time ではない。
  scheduler の計数更新を促さない試行では0が出たため、その試行は破棄して全件取り直した。
- 入力読込み・grammar warmup・出力検査・結果の破棄は計測区間外。
  計時 overhead の中央値は4.829 µs（1001回）で、下表から差し引いていない。

## 結果

単位は **ms / parse（CPU 時間の中央値）**。変化率は作業版 / master − 1。

| 入力 | master（Detailed） | 作業版（Auto） | 変化率 |
|---|---:|---:|---:|
| complex.tiny | 2.421026 | 1.611198 | -33.4% |
| complex-x64.tiny | 150.388057 | 93.742803 | -37.7% |
| complex-half.tiny | 4.824607 | 8.313479 | +72.3% |
| complex-tail.tiny | 2.548998 | 4.307486 | +69.0% |

成功2入力では生成 parser の全 CST（source・全 node・span・capture・scope を含む Debug 出力）と
facade の AST 全体が byte 単位で一致。失敗2入力では `ParseError` の offset / expected、
`ParseDiagnostic` 全体、facade のエラー全体が byte 単位で一致した。

## 検証

| 検証 | 結果 |
|---|---|
| `cargo +1.85 fmt --all -- --check` | PASS |
| `cargo +1.85 clippy --workspace --all-targets --locked --offline -- -D warnings` | PASS |
| `cargo +1.85 test --workspace --locked --offline` | PASS、164 tests + allocation 監査2本 |
| `unlaxer-alloc-audit` | checkpoint 全シナリオ PASS。診断は1回 / 1024回の失敗とも9 allocations、intern済み診断の反復は0 |
| Auto の allocation 契約 | shared / owned、memo off / on、成功 / 構文失敗 / 末尾未消費で、Auto と解決先の allocation 数・要求バイト数が一致 |
| `RustNativeEmitterTest` | PASS、1 JUnit test（3 fixture の生成文字列一致） |
| tinyexpression-rs | runtime を作業版へ patch し32 tests PASS。Cargo.lock は復元 |

## 制約

失敗が多い LSP 等は Detailed を明示する。Auto は文法走査の結果から固定し、失敗率で動的には切り替えない。
custom の宣言は作者の契約であり、runtime は診断非依存性や再実行可能性を実証しない。
`SharedGrammar = Arc<[Rule]>` の既存 API を保つためキャッシュを追加せず、各全入力解析呼出しの準備時に
一度文法走査する。解析中・Detailed 再解析中の走査はない。今回の4入力・単一環境の中央値は他の負荷での性能保証ではない。
