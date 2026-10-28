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
| IDebugProtocolServer の未実装31 request（stepIn / pause / evaluate / cancel / attach 等、JSON に全件列挙） | false | `Internal error.` | 文字列: `java.lang.UnsupportedOperationException`。引数 DTO の変換に失敗する場合は先に下記 #905 の parse 失敗になる |
| 未知 command | false | `Unsupported request method: unknown-fixture-command` | body なし |
| setBreakpoints の line -1 / 0 / 省略 | true | なし | Java と同じく -1 / 0 / 0 を返す。負の行は停止位置に一致しないが、Java と同じく breakpoint 集合を空でなくし、configurationDone の探索を有効にする |
| initialize 前の threads / 有効 stackTrace / scopes / variables、frameId 省略の scopes | true | なし | 通常の応答 object。initialize 前という理由だけでは拒否しない |

CRLF 内部と Projected 終端超過の位置変換は、それぞれ直接の `java.lang.IllegalArgumentException: inside CRLF` / `outside snapshot`。CompletionException を付けない。共通入力・停止順・終了・失敗後の状態は [`../projected-token-dap/crlf.json`](../projected-token-dap/crlf.json) と [`../projected-token-dap/overflow.json`](../projected-token-dap/overflow.json) で別途照合する。

Java の request 実装や既存機能は変更しない。引数型の変換は下記 #905 の fixture で検証する。任意の壊れた JSON や framing の網羅ではない。全 body の byte 一致を契約せず、JVM 固有の呼出履歴と NPE 説明の差は `spec-corpus/parity-ledger.json` の `dap-token-position-failures` に記録する。


## 型の緩い引数（#905）

Java 基準 revision は `bd7e6695`、LSP4J 0.23.1 / Gson 2.13.2 / JDK 21.0.9。
[`arguments.json`](arguments.json) は Rust の修正前に、生成 Java DAP の実 stdio で測定した独立期待値である。テスト中に実装から期待値を作らない。

**JSON の field 順序も入力の一部になる。** LSP4J は `arguments` が `command` より先なら JsonElement に一旦保存し、後で Gson の `JsonTreeReader` で DTO へ変換する。`command` が先なら `JsonReader` で直接変換する。同じ JSON 値でも数値と文字列の変換規則が異なるため、共通テストは両順序を明示して送信する。`Map.of` の反復順序に依存させない。

| int 引数の JSON 値 | arguments が先（tree） | command が先（stream） |
|---|---|---|
| `"1"`, `1.0`, `1e0` | 1 | 1 |
| `1.5`, `-1.5` | 1 / -1（小数部を切捨て） | parse 失敗 |
| `"1.5"` | parse 失敗（For input string） | parse 失敗（Expected an int） |
| `"1.0"`, `"1e0"`, `" 1"`, `"1 "` | parse 失敗 | 1 |
| `"0x1"`, `""`, `"abc"` | parse 失敗 | parse 失敗 |
| `"0x1p0"`, `"1d"` | parse 失敗 | 1 |
| `true`, `[]`, `{}` | parse 失敗 | parse 失敗 |
| `null`, `-0.0` | primitive int は 0 | primitive int は 0 |
| `2147483648`, `-2147483649` | -2147483648 / 2147483647（下位32 bit） | parse 失敗 |
| `"2147483648"`, `"-2147483649"` | parse 失敗 | parse 失敗 |
| `4294967297.9` | 1 | parse 失敗 |
| `1e400`, `1e9999` | 0 | parse 失敗 |
| `1e-400` | 0 | 0 |
| `1e10000`, `1e-10000` | Gson の scale 制限で parse 失敗 | 失敗 / 0 |
| `"+1"`, `"01"`, `"١"`, `"１"` | 1 | 1 |

この表の「int」は DTO への変換結果。例えば threadId / variablesReference の 0 は変換には成功するが、その後 adapter が `Internal error.` で拒否する。frameId 0 や breakpoint line 0 は受理する。範囲外の**数値**と**文字列**を同じ規則にしない。極端な指数の例外も fixture に全文を記録している。

**JDK17 と JDK21 で Java の結果が違う行がある。** Java の参照挙動は JDK の `BigDecimal` と `Double.toString` に依存する。fixture の `error` / `programPath` は JDK21 の値で、Rust はこれを返す。JDK17（Temurin 17.0.20 で実測）の値は行の `errorByJdk` / `programPathByJdk` に `"17"` として持ち、Java backend の比較は実行中 JDK の値を使う。Java/Rust の比較一覧には JDK21 の値を記録する。

| 行 | JDK21（既定、Rust） | JDK17（`…ByJdk`） |
|---|---|---|
| int `1e2147483648`（tree） | `Number has unsupported scale: 1e2147483648` | `Exponent overflow.` |
| int `1e-2147483648`, `1.0e-2147483647`（tree） | `Exponent overflow.` | `Scale out of range.` |
| launch program `1e23` | `1.0E23` | `9.999999999999999E22` |
| launch program `1e-323` | `9.9E-324` | `1.0E-323` |

他の行と stream 経路の診断は JDK17 でも同じになる（全行を JDK17 で照合済み）。

`launch` / `attach` の引数は型付き DTO と異なり `Map<String,Object>`。launch は `program` / `formulaSource` / `runtimeMode` / `steppingMode` の参照時に Java の `String.valueOf` を使う。元の map は application hook に保持する。

| program の JSON 値 | 実際の文字列（両順序） |
|---|---|
| `5`, `5.0` | `5.0` |
| `true`, `false` | `true` / `false` |
| `{}`, `[]` | `{}` / `[]` |
| `{"a":1,"b":true}` | `{a=1.0, b=true}`（入力順を保持） |
| `[1,true,"x"]` | `[1.0, true, x]` |
| `-0.0`, `1e400`, `-1e400` | `-0.0` / `Infinity` / `-Infinity` |
| `5e-324`, `1e-323` | `4.9E-324` / `9.9E-324`（JDK21。JDK17 の差は下記） |
| `1e-4`, `1e7`, `1e23` | `1.0E-4` / `1.0E7` / `1.0E23`（同上） |
| `null`, `""` | fallback の formulaSource もなければ adapter が launch を拒否 |

一方、型付きの `source.path` などの String field は数値の元の表記を保持する（`5` → `"5"`、`1E+0` → `"1E+0"`）。boolean は文字列化し、object / array は parse 失敗。Boolean field は文字列 `"true"`（大小文字を区別しない）だけ true、他の文字列は false。自由形式の launch `stopOnEntry` は Boolean.TRUE だけが true であり、この DTO 用の変換を適用しない。

parse 失敗は `success:false`、`message:"Message could not be parsed."`、**object body** の `message` に Gson と同じ診断を返す。tree の例:

```json
{"success":false,"message":"Message could not be parsed.","body":{"message":"java.lang.IllegalStateException: Expected BEGIN_ARRAY but was BEGIN_OBJECT at path $.breakpoints"}}
```

stream の診断は `$.arguments` からの path と、実際の frame の行・UTF-16 列を含む。IllegalStateException の Gson Troubleshooting URL も一致させる。改行、非 BMP、指数の大文字・符号を含む生 frame でも照合する。Java のメソッド引数配列も順序で異なり、例えば stackTrace `arguments:[]` は tree では BEGIN_ARRAY、stream では空の引数列の END_ARRAY で拒否し、stream の `[{}]` は単一 DTO として読まれる。launch の tree 経路は map の pair-array 表現も受理する。

54 数値境界 × 6 request（stackTrace / scopes / variables / next / continue / setBreakpoints）× 2順序、型・構造111件 × 2順序、launch 198件、literal 15件、DTO 全 field の request 557件の **計1,640観測 / route・backend** を照合する。Classic AST / Classic token（Java host・native Rust host）/ Generated / Projected の計9経路、14,760観測。成功時の ID 解釈、line の値、program/source の対応、next の1 step 前進、continue の終了まで確認する。失敗後は停止位置・scope・variables を保持し、余分な lifecycle event がないことも確認する。

`requests` は LSP4J 0.23.1 の `IDebugProtocolServer` の全 method の引数 DTO（`org.eclipse.lsp4j.debug` の javap で確認した field 型）について、field ごとに型の合わない値と受理される値を両順序で送った Java の実測である。生成 adapter が実装しない stepIn / evaluate / source / setExceptionBreakpoints などの default method も、LSP4J は DTO へ変換してから `UnsupportedOperationException` を投げるため、変換に失敗すれば `Message could not be parsed.` になる。入れ子の Source / ValueFormat / SourceBreakpoint / ExceptionOptions なども同じ規則で検査し、Source の `adapterData` や launch の `__restart` など `Object` 型の field は任意の値を受理する。restart の `arguments`（`Either<LaunchRequestArguments, AttachRequestArguments>`）は LSP4J が別の JsonElement として読み直すため、frame の順序によらず tree の規則で `$` からの path になる（例: `Expected BOOLEAN but was NUMBER at path $.noDebug`）。session の状態に依存しない parse 失敗と `UnsupportedOperationException` の行だけを収め、停止中の session で連続して送り、前後の状態が変わらないことも確かめる。

`DapArgumentConformance` は既存の必須4テストクラスから呼び出す。CI のクラス・メソッド契約は既存の `.github/optin-test-contracts.json` にあり、新しいテストクラスは追加していない。`target/dap-arguments-<route>-<backend>.tsv` と `-raw.tsv` を出力し、CI は全経路の比較一覧を必須にする。

今回の fixture の parse 失敗は body.message の全文まで一致し、Gson 固有の文言を例外種類だけへ弱めた行はない。既存の Internal error. の JVM stack trace / helpful NPE 説明の差は #900 の契約を維持する。任意の未知拡張 field、重複 JSON key、不完全な map-entry 配列、壊れた framing の総当り互換はこの fixture の範囲外。Java 製品コード・公開3.3.0・parser ホットパスは変更しない。

Rust の `serve` / `serve_stdio` は実 frame の表記と順序を使う。JSON Value を直接渡す `Server::handle` は、その Value を直列化した入力に同じ規則を適用するため、呼出側が既に失った数値表記や空白は復元しない。serde_json の object は key 順に並ぶので、`handle` では常に `arguments` が `command` より先（tree）の規則になり、field の検査順も key 順になる。field 順序は frame から読むため、serde_json の `preserve_order`（indexmap などの追加 crate）は使わない。数値は `arbitrary_precision` で元の表記を保つ。

## launch 直後の表示と複数要素の引数配列（#938）

基準 revision `aa5c78c8` の生成 Java DAP を実 stdio で測定し、[`launch-state.json`](launch-state.json) に独立期待値を固定した。JDK 21.0.9 Oracle と JDK 17.0.12 Oracle の双方で確認した。Java 製品コード、公開済み3.3.0、parser ホットパスは変更しない。

**初回 launch と再 launch は同じ状態ではない。** launch はまだファイルを読まず、既存の step だけを取り消す。ファイルの存在、数値/object/配列/boolean program による違いはなく、program は #905 の文字列化規則を使う。

| configurationDone 前の request | Java と Rust の契約 |
|---|---|
| token モードの stackTrace（threadId 1） | `id:0, name:"<program>", source:{path:program文字列}, line:前回のsourceLineOffset+lineBase, column:columnBase` の1 frame。source.name / endLine / endColumn は付けない。初回の offset は0なので、1基底なら line/column は1 |
| AST モードの stackTrace（threadId 1） | 空。前回の AST/token step は表示しない |
| scopes（frameId 0／省略） | 空。frameId 1 は既存の invalid request 失敗 |
| variables（variablesReference 1） | 初回は空。再 launch は前回 **configurationDone の準備で取得した**変数を名前・値・順序ごと保持する。新 launch の runtimeMode / steppingMode で置き換えない |
| threads | `[{"id":1,"name":"main"}]` |
| next / continue（threadId 1）、不正 thread / variablesReference | `Internal error.`、文字列 body の先頭行 `java.util.concurrent.CompletionException: java.lang.IllegalArgumentException: invalid or unavailable debug request`。失敗後の表示は変わらない |

準備時の変数順は `runtimeMode`、`steppingMode`、application hook の追加変数。steppingMode の値は `ToKeN` / `AsT` のような元の大小文字も保持する。hook が予約名を上書きしても、その位置は変わらない。Rust の hook は既存の `BTreeMap` の順、Java の hook は戻り値の map の順を使うため、application が対応する順を提供する。Classic AST の共通テスト hook は両 JDK で順序が固定される LinkedHashMap を使い、runtimeLabel / sourceLength も比較する。

共通テストは初回・停止中からの再 launch・終了後の再 launch、存在する／しないファイル、数値・object・配列・boolean、0/1 基底、両 field 順序、token/AST を検査する。Classic AST 専用 backend は AST のみ、Classic token / Generated / Projected は両モードの launch を検査する。計9経路で1,536 pending session。`1e23` / `1e-323` の path は #905 と同じ JDK 差があり、`pathByJdk` に JDK17 の値を持つ。Rust は JDK21 の値に合わせる。他の pending 応答と下記の無応答には JDK 差を観測していない。

### command が先で arguments が2要素以上の場合

LSP4J は、引数 DTO を1個読む request に `arguments:[{},{}]` のような配列を送ると、2個目で `Expected END_ARRAY but was BEGIN_OBJECT ... path $.arguments[1]` を発生させる。Java は **response を返さない**。JDK17/21 の実測では21秒間、エラー response・lifecycle event・切断はなく、stderr に `StreamMessageProducer fireError` の SEVERE、`JsonSyntaxException` と上記診断・stack trace を出した。その後の正しい `threads` には応答し、session は維持された。「永久に無応答」とは断定せず、この観測時間を契約の根拠にする。

Rust はクライアントを待ち続けさせず、**`success:false`、`message:"Message could not be parsed."`、object body の message に上記診断（行・UTF-16列を含む）を返す**。これは意図した差で、`spec-corpus/parity-ledger.json` の `dap-launch-state` に記録する。従来の先頭 DTO の実行・後続要素の黙殺は廃止する。launch / next / configurationDone / initialize / disconnect の副作用を起こさないため、成功扱いしない最小の案を選んだ。引数1個の配列は既存どおり dispatch する。arguments が先の tree 経路、および引数 DTO を取らない threads / 未知 command の規則は #905 のまま。先頭 DTO 自体が不正な場合は先にその parse 失敗を返す。

Classic AST / Classic token（Java host・native Rust host）/ Generated / Projected の共通 stdio で12 request（stackTrace / setBreakpoints / launch / next / initialize / configurationDone / evaluate / source / scopes / variables / continue / disconnect）×配列長2/3×pending/stopped の48行を各経路で確認する。Java は1秒の観測窓で無応答・生存を確認し、後続 threads を barrier にして、遅れて届く response や event も拒否する。Rust は失敗 response の形と診断全文を照合し、両版とも frame / scope / variables の不変、後続 request の応答、単一要素の正常 dispatch を確認する。

`DapLaunchStateConformance` は既存の必須4クラスから呼び出すため、CI の `-Dtest` と `.github/optin-test-contracts.json` は既存登録を利用する。9経路の `target/dap-launch-state-<route>-<backend>.tsv` と `-raw.tsv` を保存し、CI は比較一覧を必須にする。無応答の観測では同じ read Future を保持し、timeout 後に別の reader を積まない。
