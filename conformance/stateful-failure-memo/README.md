# Stateful failure の独立 oracle (#629)

`cases.tsv` は17個の手書き操作列です。入力は `😀?`、失敗する target は `😀` の後に `!` を要求します。
`F` の後は両 cursor 0、committed failure CST は空、通常の syntax failure CP は1です。
低水準 DETAILED_ON_FAILURE は診断を後段へ遅延するため、その pass の offset は0です。
最終生成 API の diagnose は全3modeで CP1 の syntax failure を確認します。

- F: 同じ safe failing rule を呼ぶ。各行の3列目は ON 時の累積実 failure hit 数。OFF は常に0。
- E/L/D: scope enter/leave/`😀` declare。Z は空 declare。
- R/A/C: reference、`kept` warning（CP1/length1）の追記、diagnostic clear。
- M: caller-owned user state を7→99へ変更し、明示的に version を進める。
- B: zero-width PEG assertion を一般 owned binding `owned` として公開する。
- S: B を CaptureScope 内で行い、binding は残さず version は保守的に進める。
- `( ... )`: rollback、`[ ... ]`: commit。外側 rollback は内側 commit も戻す。

Java/Rust 各17×memo2×diagnostics3 = **102観測**。実装の出力を oracle へ採取していません。
同状態 hit、変更 miss、rollback 後 hit、異 branch epoch 非再利用、no-op mutation、write-only event の
保持、binding/user state の rollback と syntax diagnostics を比較します。
Rust target には scope/declaration/reference の speculative effect も持たせ、失敗後に残らないことを確認します。
Java/Rust の CST 内部形を同一と主張せず、失敗時の committed CST と生成 AST/位置を共通観測にします。

`generated-cases.json` は @scopeTree/@declares/@backref を持つ失敗を optional で2回試してから
mapped Item を読む文法です。成功/失敗の2入力×memo2×diagnostics3 = **12観測/host**。
両 Rust 生成 host の5module byte equality、生成物の実compile、Java21/Rust1.85での typed AST/raw text/
CP・UTF16・UTF8、failure の CP1、未定義 reference warning のCP0/length2/messageと rollback を比較し、
ON の6設定すべてで実 failure hit を要求します。

追加 Rust test は longest winner の採用で loser の epoch allocator を後退させないことを検証します。
内部 unit は rollback の current/next 区別と counter exhaustion 時の permanent miss、
Java test は既存 long overflow の fail-fast を検証します。
既存 Classic/pure success/resource-depth/owned capture/scope の関連回帰も同時実行します。

```sh
mvn -pl unlaxer-common,unlaxer-dsl -am test \
  -Dtest=VersionedFailureMemoizationConformanceTest,PureSuccessMemoizationConformanceTest \
  -DrustConformance=true -Dsurefire.failIfNoSpecifiedTests=false
cargo test --manifest-path rust/Cargo.toml -p unlaxer-runtime --test stateful_failure_memo
```

報告: DSL `target/stateful-failure-memo-{java.tsv,rust.log,generated.tsv}`。
[version/key/安全性の契約](../../docs/stateful-failure-memo.md)。
記録付き success replay/production benchmark は親 #184/#197 に残ります。

## 限定 microbenchmark

`benchmark-results.tsv` は既存 pure-success の `benchmark.rs` をこの5word keyの実装で再実行した結果です。
2026-10-17、Rust1.85.0 `-O`、3 warmup + 5 samples の中央値。
retry0はOFF2472µs/ON4016µs（約62% overhead）、retry8はOFF25732µs/ON8257µsです。
旧#624の約41% overheadの記録も残し、既定OFFを変更しません。host負荷を含む一回の限定測定であり、
production workload/記録付きreplayの代替やCI性能閾値にはしません。

未達の資源上限: [#644](https://github.com/opaopa6969/unlaxer-parser/issues/644) は両言語の同位置・多数epochに対するfailure entry予算を担当します。
現在の位置windowはその件数を制限せず、Rust failure の retained byte 計数も0です。
本対応をfailure entryまで有界と説明しません。success snapshotの既存予算とは別の残差です。
