# Failure memo の保持予算 (#644)

Java/Rust の memoization は引き続き既定 OFF です。opt-in した safe failure memo は、
通常・Classic・state version 付きのすべての failure entry に有限予算を適用します。
位置 window だけでは、同じ位置に蓄積する多数の state epoch を制限できません。

| 予算 | Java system property | Rust environment variable | 既定 | 設定上限 |
| --- | --- | --- | ---: | ---: |
| failure 件数 | `unlaxer.memo.failure.maxEntries` | `UNLAXER_FAILURE_MEMO_MAX_ENTRIES` | 65,536 | 1,048,576 |
| failure 合計 charge | `unlaxer.memo.failure.maxBytes` | `UNLAXER_FAILURE_MEMO_MAX_BYTES` | 16 MiB | 1 GiB |
| failure 単体 charge | `unlaxer.memo.failure.maxEntryBytes` | `UNLAXER_FAILURE_MEMO_MAX_ENTRY_BYTES` | 1 MiB | 64 MiB |

値はプロセス内の最初の初期化時に一度だけ読みます。0 は明示的な保存停止です。
受理する表記は空でないASCII数字列です。空文字、不正・符号付き表記（`-0` / `+1`も含む）・
整数 overflow・設定上限超過は既定値へ戻します。
予算満杯では新しい結果を保存せず、既存 entry を保ちます。構文失敗や資源上限には変換しません。
同一 key の置換は古い charge を差し引いて判定し、不採用なら古い結果を保持します。
eviction は entry と charge を減らし、rollback 後の再保存にも予算を適用します。
保持中の元 epoch へ rollback した場合は、従来どおり実際の hit が可能です。

## Charge の範囲

これは cache が保持する payload の **構造的 charge** です。RSS の実測値や任意の外部
object graph 全体の上限ではありません。Java/Rust のレイアウト差を隠す共通 byte 数でもありません。

* Java: entry/key/diagnostic/map/index link の基礎 charge 512 bytes、diagnostic stack node ごと64 bytes、
  expected source 配列の容量、transaction event 配列の容量、trial record と名前の UTF-16 長を加算します。
  rollbackでtrial listをclearした後も、過去の件数high-waterを使い、保持された配列容量を過少計数しません。
  expected source が参照する共有 Parser 定義の内部 object graph は展開しません。
* Rust: entry/key/diagnostic/map の基礎 charge 256 bytes、expected ID vector の容量、
  期待名の UTF-8 長と各名32 bytes を加算します。共有されたデータも entry ごとに保守的に計数します。
* 予算判定のために diagnostic を複製したり、Parser の表示 callback を呼んだりしません。
  算術は範囲チェックまたは飽和演算で処理します。

可変長の位置索引には別の growth guard を適用し、failure 保存だけで遠方位置まで巨大な
空き索引を確保しません。Java の既存初期1024 slot と Rust の inline first bucket は基礎固定費です。
索引の拡張要求も合計 byte 設定を上限に判定しますが、payload charge と合算した RSS 上限ではありません。

cache を無効にしても必要な active/global syntax diagnostics、Rust の期待名 interner、
呼出側が保持する Parser・Entry・原文等はこの予算の対象外です。
success snapshot は [#652 の別予算](success-memo-budget.md) で計数・制限します。
failure 予算と混同した全process RSSの上限とは説明しません。

## 観測と検証

Java `PackratMemoTable` は `failureEntryCount()` / `failureRetainedBytes()` /
`failureBudgetMisses()` を公開します。Rust `ParseContext` の対応 API は
`failure_memo_entries()` / `failure_memo_retained_bytes()` / `failure_memo_budget_misses()` です。
Rust の件数と charge は現在の grammar session の table を観測します。
既存の failure hit 数とは別に、予算による不採用数を確認できます。

[共通 oracle](../conformance/failure-memo-budget/README.md) は予算内の実 hit、満杯 miss、
同位置の多数 epoch、rollback 後の hit、eviction と underrun、両 cursor、原文と CP/UTF-16/UTF-8、
syntax/semantic diagnostics を手書き期待値で固定します。言語固有 storage test は単体/合計容量、
古い置換 payload の解放、計数、distant index、不正設定を確認します。
#634 の資源上限と2 MiB再帰条件も維持します。

[#629 の測定](../conformance/stateful-failure-memo/benchmark-results.tsv) の no-retry 約62% overhead や、
[#624 の約41%](../conformance/pure-success-memo/benchmark-results.tsv) を撤回する変更ではありません。
今回の同位置 epoch stress の測定は保持予算の確認用で、production workload の性能保証ではありません。
trace付き成功、任意 stateful success、production workload は親 #184/#197 の別条件として残ります。

保持件数の `entryCount` / `maxEntryCount` は実際の異なる cache key の件数を数える。
同じ key の置換を新しい entry として重複加算していた旧カウンタを修正したため、
従来の統計値より小さくなる場合がある。TinyCalc の parse equality golden は、
CST・診断・hit・eviction を変えず、この2カウンタだけの134行を更新している。
