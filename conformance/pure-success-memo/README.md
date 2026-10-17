# Pure success memo の共通oracle

`generated-cases.json` は実装出力から採取していない手書きの6文法/24入力です。
Java生成parserとJava-host/native-hostのRust生成物を実compileして、OFF/ON × 3診断modeの
受理、CST原文、CP/UTF16/UTF8終了位置、typed AST値/範囲、失敗位置を同じ期待値へ比較します。
Rust両hostの5生成moduleはbyte equalityも要求します。ASCII、emoji、7CPのfamily、結合文字、
zero-width、predictive/longest choiceを含みます。

`successHit=true` の行は各hostのcounterで実success hitを要求します。
nullableなZero文法はFIRST候補除外等で再試行が省かれ得るため、ここでは意味比較だけを要求し、
zero-widthの実success hitは低水準の独立rollback試験で検証します。
失敗時のexpected/hintの綴りはbackendごとのAPI契約を保ち、同一backendのOFF/ON診断は別途全体比較します。

`rollback.tsv` は手書きの6原文（UTF8 hex、空は`-`）とCP/UTF16/UTF8長です。
Java `PureSuccessRollbackTest` とRust public `pure_success_memo.rs` の双方で、3回の試行の各原文/位置、
実success hit、外側rollback、metadata/IDの無効化と再発行、copy-in/out、診断のOFF/ON一致を検証します。
Rustはさらに元arenaに残る別nodeの後ろへedgeを再配置します。

```sh
mvn -pl unlaxer-common,unlaxer-dsl -am test \
  -Dtest=PureSuccessRollbackTest,PureSuccessMemoizationConformanceTest,SafeSuccessMemoizationTest \
  -DrustConformance=true -Dsurefire.failIfNoSpecifiedTests=false
cargo test --manifest-path rust/Cargo.toml -p unlaxer-runtime
```

Java21/Rust1.85。生成結果の報告はDSL `target/pure-success-memo-{java,conformance}.tsv` です。
新Rust moduleは追加しておらず、runtimeは従来どおり直接`rustc`でもcompileできます。

`benchmark.rs` は同じpure CSTを何度も試す限定microbenchmarkです（3 warmup、5回の中央値）。
リトライ0はoverhead、8は探索削減の例です。#197のproduction workloadやdeadlineの代替にはしません。

```sh
rustc --edition=2021 -O --crate-type=rlib --crate-name=unlaxer_runtime \
  rust/unlaxer-runtime/src/lib.rs -o /tmp/libunlaxer_runtime.rlib
rustc --edition=2021 -O conformance/pure-success-memo/benchmark.rs \
  --extern unlaxer_runtime=/tmp/libunlaxer_runtime.rlib -o /tmp/pure-success-bench
/tmp/pure-success-bench
```

RustのClassic/general token mode、stateful rule、opt-in terminal traceのsuccess replayは未対応です。
[境界・保存予算・互換性](../../docs/pure-success-memo.md)を参照してください。#184/#197は未完のままです。

`benchmark-results.tsv` は2026-10-17、Rust1.85.0 `-O`、本変更の1回の測定です。
retry0はOFF1812µs/ON2560µs（約41% overhead）、retry8はOFF14898µs/ON7173µs（約2.1倍高速）でした。
後者のcheckpointは325005→61005、実success hitは8000です。測定値をtestの閾値にはしません。
この測定は同一processの3 warmup+5 samplesで、host負荷による差を含みます。
残差: #628 (Classic)、#629 (stateful failure)、親 #184/#197 (記録付きreplay/production benchmark等)。
