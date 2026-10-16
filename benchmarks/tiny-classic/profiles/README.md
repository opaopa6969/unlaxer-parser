# Classic Tiny failure memo の対照 profile

調査 #663、親 #197 / #184、保持上限 #644。製品基点 `f709d8c9`、Tiny pin と入力は
上位の `manifest.json`、harness は `3f519760`。既定 memo OFF と runtime/生成器は変更しない。
これは原因調査であり、専用 host での最終 deadline 判定ではない。

元の consumer を変更せずに fraud1/4 の cold と warm を観測すると、Java は warm でも
約3.18–3.45秒 / 7.43–7.74秒を要した。両 Rust host は約19–29 ms。
48観測の原文・完全 AST・全 CP span・native syntax/semantic 診断は元 Safe 参照と一致した。
時間は JFR と別 build の並行負荷を含み、現在の性能合格値とは扱わない。

次に補助 probe の失敗予算だけを有限の16/64 MiBに変え、同じ入力・明示 Formula 入口を
一度だけ parse/map した。Java21.0.9 / Rust1.85。warmup1回後の warm1回は次の通り。
全36観測で元 consumer の AST・全位置・全 native 診断が一致した。

| 入力 | Java failure cap | warm parse / map | main CPU | main-thread割当 | failure miss | failure entries / charge |
|---|---:|---:|---:|---:|---:|---:|
| fraud1 | 16 MiB | 3185 / 3.3 ms | 3111 ms | 5.94 GB | 1,421,352 | 1746 / 16.78 MB |
| fraud1 | 64 MiB | 73.3 / 4.7 ms | 73.3 ms | 73.0 MB | 0 | 2090 / 20.29 MB |
| fraud4 | 16 MiB | 7818 / 4.1 ms | 7679 ms | 12.22 GB | 2,073,210 | 1685 / 16.78 MB |
| fraud4 | 64 MiB | 79.0 / 5.5 ms | 79.4 ms | 80.1 MB | 0 | 1961 / 20.04 MB |

Java の成功予算拒否は全設定0、成功保持は1.33/1.56 MB、eviction/underrunも0。
fraud1 の success hit は1,725,922→14,180に減り、予算拒否に伴う再探索の大量反復を示す。
Rust は16/64 MiBの両方でfailure miss0、fraud1が18136 entries/7.64 MB、
fraud4が16913 entries/7.06 MB。requested allocationは13.79/15.25 MBで不変だった。
同じ byte 予算が同じ entry 数を意味するとは扱わない。

Java の割当は `ThreadMXBean` の累積 VM counter（API上の近似値）、CPU は主threadの
counter。Rust は補助 `GlobalAlloc` の requested bytes。いずれも保持量/RSSと異なる。
`failure-budget-observations.json` に cold/warmup/warm、実 hit、保持 charge、予算拒否、
process CPU、測定単位と基点を保持する。全 AST と JFR 原本は実行出力へ残す。

元 Java consumer の JFR weighted allocation は1操作あたり約5.94/12.31 GB。
CST snapshot の copy/freeze 自体は約0.5–1.1%、replay の他処理は約26–29%、
他の parse は約37%だった。上位は `StackSnapshot` / `ParseFrame` / `ParserCursor` /
`TokenList` / `StringSource` / `TransactionElement`。mapper は小さい。
分類は重複しないが、inclusive method一覧は重複する。CPU sampleは23–74件と疎いため、
割合から正確な CPU 時間を推定しない。主thread CPU と区間 probe は別に保存する。

主仮説は16 MiB failure cache の飽和による再探索である。
`PackratMemoTable.put` は残予算不足で新規保存を拒否し、`ParseContext.failureMemoCharge`
は絶対 stack snapshot 等を conservative に課金する。snapshot は immutable prefix を
共有する構造なので、payload/保持選別/予算の改善候補を比較できる。charge の過大評価や
構造変更の安全性はこの profile だけで確定しない。64 MiBを既定にする変更でもない。
32 MiB×全13入力、設定/fallback、失敗後状態と finite guard の回帰は次の製品単位で扱う。

## 再現

先に上位 README の `prepare` を専用 parser/Tiny worktree と isolated M2 で実行する。
この profile は Linux/POSIX、Java21以上、Rust1.85の opt-in tool。

```sh
python3 scripts/profile-tiny-classic-benchmark.py \
  --tiny /absolute/path/to/clean-pinned-tiny \
  --build /absolute/path/to/prepared/tiny-classic-benchmark \
  --output /absolute/path/to/profile-output \
  --budgets-mib 16 64 --warmups 1 --iterations 1
```

実行ごとに unmodified consumer の参照を先に実行する。その後、別 process ごとに
有限 failure 予算だけを変える。`--all` はmanifest全13入力と各明示entryを保持する。
root 自動retryや pinned grammar 改変、diagnostic 省略、unsafe memo は追加しない。
JFRはCPU/割当/GC/compile/threadCPUの6種eventを記録し、streaming analyzerで集計する。

補助 `PhaseConsumer` は元 JavaConsumer と同じ認識/map/正規化を使い、区間clockと公開
memo counterを加える。Rust probeは同じ生成5moduleと元runtime sourceを使う。
共有 grammar 終了時には cache が復元/破棄されるため、コピーしたruntimeのその直前に
公開getterによる計測行だけを加える。元runtime file/grammar/consumerは編集しない。
補助allocator、計測行、JFRには負荷があり、probe時間を最終性能合否へ流用しない。
元 consumer と補助 probe を別に保存し、意味が変われば非0で終了する。

出力はsource/toolchain hash、実command、環境load、全raw JSONL、JFR、summary TSV、
metadata.json。timeout/例外/部分parse/null AST/診断差/観測欠落は明示失敗となる。
通常CIで時間閾値を適用するtoolではない。#197/#184は未完了のまま保持する。
