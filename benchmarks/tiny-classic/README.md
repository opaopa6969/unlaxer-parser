# Checked Classic Tiny parse+map harness

追跡 #657、親 #184 / #197。Java21 / Rust1.85。性能数値の合否判定と、意味の一致を分ける。
Classic の Safe 既定 OFF を変更せず、現在の同一 P4 UBNF から生成する。

Tiny pin は `7d7bd1cbfab1bd9548ba53c73cc50502f0437c67`。
`manifest.json` は入力/文法の SHA256、code-point 長、正負、独立期待値を保持する。
fraud 5式は pin の `P4PackratFraudFormulaTest` の定数を無改変で抽出した。
既存 `complex` / `comparison-heavy` / `flat-arithmetic` / `large-match` と、数値・
補助文字・文字列内 CRLF・不正入力の小 oracle を別 group として保持する。
Unicode/CRLF の小 oracle は root だけでなく全 AST node/field/span の手書き期待値を持つ。

既存 fraud JUnit は `RuntimeException` を握り潰し、実閾値は2秒である。さらに pin の
`P4PreferredAstMapper` 既定と Rust Criterion は ubnfc frontend を使う。これらを現 Classic
の成功/1秒の根拠として流用しない。#197 の古い3.3秒/10秒超の記録は後続改善前であり、
現在の性能値として扱わない。

## 再現

ユーザー/別 agent の作業 tree は使わず、parser と Tiny を専用 worktree にする。
Maven repository は専用の絶対パスを指定する。`prepare` はその repository へ
`3.3.1-benchmark-SNAPSHOT` として現 parser を install し、Tiny の `${unlaxer.version}` を
コマンドラインで上書きする。解決された common/dsl の両 version を確認し、POM 自体は
編集しない。flattened POM は build 前の bytes に戻す。公開 artifact は変更しない。

```sh
python3 scripts/run-tiny-classic-benchmark.py \
  --tiny /absolute/path/to/clean-pinned-tiny \
  --maven-repo /absolute/path/to/isolated-m2 --mode prepare
python3 scripts/run-tiny-classic-benchmark.py \
  --tiny /absolute/path/to/clean-pinned-tiny \
  --maven-repo /absolute/path/to/isolated-m2 --mode verify --corpus oracle
```

`prepare` は現 Java generator で Java4 file/Rust5 module、native generator で Rust5 module
を生成する。Rust は全 file の byte 一致を必須にし、各 host の出力から別 consumer を
compile する。Java は pin の外部 STRING/code-fence parser だけを依存とし、現生成物を
classpath の先頭に置く。Java/Rust とも manifest の同名 entry を一度だけ parse/map する。通常は `Formula`。
`comparison-heavy` は caller が `BooleanExpression` / `BooleanOrExpr` を明示する既存利用経路を
使う。Java は公開 generated parser class を `Parser.get` し、
`mapSubtreeTokenWithSourceMap`、Rust は公開 `grammar()` の同名 rule を指定して
`ParseContext::parse_shared_grammar` と `mapper::map` を呼ぶ。P4 facade、automatic alternate
root/result-family retry、ubnfc、手書き evaluator を呼ばない。

`verify --corpus fraud` / `production` / `all` は実 corpus の意味確認にも使える。
`--memo SAFE_FAILURES --diagnostics DETAILED` で設定を明示して絞ることもできる。
既定は小 oracle、memo OFF/ON、diagnostics3種、Java/Rust-Java-host/Rust-native-host。
例外・timeout・部分 parse・null AST・期待外 rejection は全て明示失敗になる。
成功 expected の fraud が拒否されたら、期待値を変更せず別の機能 gap として追跡する。

元の比較では `comparison-heavy` は JMH の FacadeState にだけ含まれ、普通の Formula
入口では NumberExpression prefix が先に成功して外側 EOF が CP5 で失敗する。
`manifest.json` の `formula_root_audit` と監査 raw record はこの拒否を保持する。
fixture の受理期待値を下げたのではなく、caller が選ぶ有効な typed entry を固定した。
以下は root 選択制約の明示 RED 監査であり、通常の production verify とは別に保存する。

```sh
python3 scripts/run-tiny-classic-benchmark.py \
  --tiny /absolute/path/to/clean-pinned-tiny \
  --maven-repo /absolute/path/to/isolated-m2 --mode verify --corpus production \
  --memo SAFE_FAILURES --diagnostics DETAILED --entry-override Formula
```

この typed entry に retry の無い実用経路があることを、#187/#197 の全 Formula root
再試行0という AC の完了には数えない。pinned grammar は変更しない。

各 operation は fresh `ParseContext` を使い、実際に選んだ entry 名、両 cursor、完全入力、canonical typed AST と
全 node CP span、semantic diagnostics、native syntax diagnostic、memo 実 hit を記録する。
OFF/ON と同 host の各 trial では native syntax も完全比較し、異なる backend 間では
expected の表示名/引用符を残したまま失敗 CP と hint の有無を比較する。成功時の
reached/expected は生データへ保持するが、そのまま構文エラーとは扱わない。
Deferred getter は offset0/expected空の低水準契約を保ち、自動 retry を追加しない。

## 時間の境界

`parse_map_ns` は source text を受け取った後の context 構築、生成 parser 認識、生成 mapper、
保持 AST/source-map snapshot の構築を含む。ファイル I/O、生成/compile、process startup、
JSON/canonical serialization、診断の表示 materialization、context teardown は除く。
Rust の mapper 用 `Tree` snapshot clone と Java の retained source-map layer はそれぞれ
現 API のコストであり、同じ内部実装とは主張しない。

`cold` は fresh process の最初の operation で、初回の parser/grammar lazy 構築と Java
class loading/JIT の影響を含む。`warmup` は同 process の明示準備、`warm` はその後の
operation。全て raw ns を残す。`process_wall_ns` は startup/出力/終了を含む process
全体であり、operation の時間と比較しない。verify は既定では cold1操作のみ。`--warmups 1 --iterations 1` で意味検証時にも各 phase
を確認できるが、常に `measurement_eligible=false`。共有 host の load average/CPU と build identity も保存する。
他の build/CI が動いている verify の数字は合格根拠にしない。

最終測定は #652 と #653 の統合後、clean な固定 parser revision と専用 host で行う。
両 prerequisite commit が HEAD の祖先であり、`--exclusive-host` を明示しない限り
`--mode measure` は拒否する。これは process/runner の停止や設定変更を行う機能ではない。

```sh
python3 scripts/run-tiny-classic-benchmark.py \
  --tiny /absolute/path/to/clean-pinned-tiny \
  --maven-repo /absolute/path/to/isolated-m2 --mode measure --corpus all \
  --prerequisite 652:<merged-commit> --prerequisite 653:<merged-commit> \
  --exclusive-host --warmups 5 --iterations 10
```

#197 の fraud parse+map は1秒未満。既存 Classic facade は parse deadline の既定10秒を
持つ。harness は facade の協調 listener を追加せず、cold/warm の Safe sample に manifest の
上限を適用する（production sample は parse+map 全体10秒）。これを facade listener の
同等実装とは主張しない。外側 process timeout も別に記録し、timeout を pass にしない。
OFF の実測も保存するが、Safe の合格と取り違えない。

出力は `target/tiny-classic-benchmark/` 以下。build.json、解決依存、生成file hash、
各 command log、run別 environment.json / 生 consumer JSONL / samples.jsonl / result.json
を保持する。失敗時も対象 consumer の出力を残す。source/runtime/consumer hash が変われば
古い build の再利用を拒否する。

この準備 unit だけで #184/#197 の stateful/performance/full-spec 全体を close しない。


## 継続検証

`Classic Tiny semantic oracle` CIは独立checkoutと専用Maven repositoryで同じpinから再生成し、
harness負例9 testsと小oracleの216 samples（4入力×memo2×diagnostics3×3consumer×3phase）を検証する。
両Rust出力のbyte一致、全consumer compile、完全AST/CP/診断、12設定の意味一致を必須にし、
raw JSONLとbuild identityを保存する。CIは共有runner上の `verify` であり、全sampleの
`measurement_eligible=false` を確認する。fraudの期限達成や全corpus OFFの完了はこのCIの合格条件と混同しない。
