# 生成 DAP の失敗応答（#900）

Java 基準 revision は `1a8c8921`（LSP4J 0.23.1）。`requests.json` は実 stdio で測定した独立期待値で、実装から生成しない。Classic AST、Classic token、Generated token、Projected token に同じ request を送り、各 backend を期待値へ照合したうえで Java/Rust の一覧も比較する。Classic token は Java host / native Rust host の両生成物を含む。

各 route / backend で261 request・状態の組を照合する（fresh 69、pending 64、stopped 63、terminated 65）。

`fresh` は initialize 前、`pending` は launch 成功後 configurationDone 前、`stopped` は entry 停止、`terminated` は exited 後。比較項目は success、message、body の型、body の先頭行。成功する対照 request も含み、JVM 固有の helpful NPE 説明と呼出履歴だけを除く。どの phase も省略するとテストは失敗する。生 response は `target/dap-failures-<route>-<backend>-raw.tsv`、比較一覧は `target/dap-failures-<route>-<backend>.tsv`。

| request / 状態 | success | message | body 型・先頭行（Java/Rust） |
|---|---|---|---|
| 不正 thread の stackTrace / next / continue、不正 frame の scopes、不正 reference の variables。引数 null も含む | false | `Internal error.` | 文字列: `java.util.concurrent.CompletionException: java.lang.IllegalArgumentException: invalid or unavailable debug request` |
| 有効 thread でも停止前・終了後の next / continue | false | `Internal error.` | 同上 |
| program 未指定 / 不正 steppingMode の launch | false | `Internal error.` | 文字列: `java.util.concurrent.CompletionException: java.lang.IllegalArgumentException: invalid program or stepping mode` |
| 有効な新 launch がない configurationDone | false | `Internal error.` | 文字列: `java.util.concurrent.CompletionException: java.lang.IllegalStateException: configurationDone requires a new launch` |
| setBreakpoints(null) / null breakpoint | false | `Internal error.` | 文字列: 例外型 `java.lang.NullPointerException` が一致。Java の JVM メソッド・parameter 名を含む説明と Rust の `missing setBreakpoints arguments` / `null breakpoint` は意図した差 |
| IDebugProtocolServer の未実装31 request（stepIn / pause / evaluate / cancel / attach 等、JSON に全件列挙） | false | `Internal error.` | 文字列: `java.lang.UnsupportedOperationException` |
| 未知 command | false | `Unsupported request method: unknown-fixture-command` | body なし |
| setBreakpoints の line -1 / 0 / 省略 | true | なし | Java と同じく -1 / 0 / 0 を返す。負の行は停止位置に一致しない |
| initialize 前の threads / 有効 stackTrace / scopes / variables、frameId 省略の scopes | true | なし | 通常の応答 object。initialize 前という理由だけでは拒否しない |

CRLF 内部と Projected 終端超過の位置変換は、それぞれ直接の `java.lang.IllegalArgumentException: inside CRLF` / `outside snapshot`。CompletionException を付けない。共通入力・停止順・終了・失敗後の状態は [`../projected-token-dap/crlf.json`](../projected-token-dap/crlf.json) と [`../projected-token-dap/overflow.json`](../projected-token-dap/overflow.json) で別途照合する。

Java の request 実装や既存機能は変更しない。任意の壊れた JSON 型や framing の網羅ではない。全 body の byte 一致を契約せず、JVM 固有の呼出履歴と NPE 説明の差は `spec-corpus/parity-ledger.json` の `dap-token-position-failures` に記録する。
