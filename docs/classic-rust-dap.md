# Classic typed AST DAP

Classic の生成 grammar で実typed ASTをinspectionする stdio DAP 経路を提供する（#462、親 #111 / #384）。これは入力をstrict parse/mapした後のAST traversalである。applicationの実評価は `runtimeVariables` / `runtime_variables` などの明示hookに委ね、ASTのnext操作をlive evaluatorの実行停止と呼ばない。

Java-host/native-Rustの両CLIは `generate --target rust --grammar input.ubnf --output generated --dap` を受け付ける。通常生成は従来の5ファイル、`--lsp` または `--dap` 単独は6ファイル、両指定はLSP/DAP順の7ファイルとなる。`--check` は全artifactを確認する。

```toml
[dependencies]
unlaxer-runtime = { path = "path/to/rust/unlaxer-runtime" }
unlaxer-dap = { path = "path/to/rust/unlaxer-dap" }
```

```rust
mod generated;
fn main() -> std::io::Result<()> {
    generated::dap::serve_stdio()
}
```

clientはinitialize後、`launch` に `program`（旧Java互換の `formulaSource` も受理）、`runtimeMode: "ast"` または `steppingMode: "ast"` を渡し、breakpoint設定、configurationDoneへ進む。stopOnEntryが有効なら最初のAST nodeで停止する。nextは次のnode、continueは次のbreakpoint行にあるnodeへ進む。最後はoutput/terminated/exitedを返す。configurationDoneのparse/map失敗、空AST、位置が無効なASTにはstoppedを返さない。

| 契約 | Java / Rust 共通の挙動 |
|---|---|
| protocol | initialize/launch/configurationDone/setBreakpoints/threads/stackTrace/scopes/variables/next/continue/disconnect。未知/無効requestはfailure response |
| thread/frame | 単一thread 1、現在nodeのframe 0、variablesReference 1。値そのもののreferenceは0 |
| 座標 | 内部spanは原文CP。DAPの開始/終了columnはUTF-16、line/columnの基底はclientのlinesStartAt1/columnsStartAt1に従う。省略時は1-based |
| CR / LF / CRLF | immutable source snapshotで変換する。CRLF途中の境界は表示可能な位置として受理しない |
| traversal | node自身、mapping field宣言順の子node。optional/listを再帰し、mixed値のtextはnode数に含めない |
| source slice | applicationのsource hookが返す独立textとline offsetを用いる。元program pathを維持する |
| variables | 現在nodeのlabel/text、runtimeMode、astNodeCount、astCurrentNode、applicationのruntimeVariablesの結果 |
| failure | failed launch/nextは現在stepを変更しない。mapperエラーをtoken stepへ置き換えず、relaunchで古いASTを再利用しない |

[DAP公式schema](https://raw.githubusercontent.com/microsoft/debug-adapter-protocol/main/debugAdapterProtocol.json) はcolumnをUTF-16と定義し、基底をclient初期化へ委ねる。今回JavaのstackTraceも、既存breakpoint helperと同じ `DocumentSnapshot` の変換へ統一した。disconnectではresponse前にprocessを終了せず、stepを解放してclient側のstdio終了を待つ。Rustはdisconnect responseを送ってserver loopを終える。

Rustの `generated::dap::steps` はstrictな既存 `parser::parse_tree` と `mapper::map` を使う。`AstBackend` はこの関数を受け取る。独自 `Backend` は `steps(snapshot, arguments)`、`resolve_source`、`runtime_variables`、`before_step`、`after_step` を実装できる。Javaは既存 `resolveDebugSource`、`runtimeVariables`、step hooksを保持し、`parseDebugAst` hookでapplicationのmapperを選べる。両言語ともhookによる評価の意味はapplicationの責任である。Rustのhook値は文字列のBTreeMap、Javaは既存String値Mapを保持する。

Rustはtoken steppingを明示拒否する。現行Rust CSTのrule/capture nodeはJava literal leafではなく、代用しない。Javaの既存token modeを維持し、実parser leaf traceの対応と共通oracleは後続 #463 へ分離した。Javaの任意additionalVariables/subclassの再現、conditional/function/data breakpoints、pause/stepIn/stepOut、evaluate、変数変更、ソース取得、live evaluator制御、既存下流VSIXのRust起動はこのsliceの完了範囲に含めない。initializeはConfigurationDoneだけを広告する。Java固有の追加hookや高度なrequestは削除しない。

Rustの入力frameはContent-Length byte数、CRLFのASCII header、最大4MiBのbody、最大8KiB/32項目のheaderを検証する。元program fileは4MiBまでである。Javaは既存LSP4J transportとfile読込契約を維持する。標準source/AST corpusの観測値は共通とし、このRust側の入力上限は言語固有の制約として残す。共有frame処理はstd-only `unlaxer-protocol` に置き、LSPの従来framing契約を保持する。

## 検証

`docs/fixtures/classic-dap/grammar.ubnf` と独立 `ast.json` は9入力、field順が字句順と異なる `zeta/alpha`、optional/list/mixed node、Unicode、CR/LF/CRLF、部分入力、parse失敗、application mapper失敗を含む。生成Javaと生成Rustの実processへ同じbyte frameを渡し、0/1-based・source offset 0/7の組合せ、node text/count、開始/終了座標、entry/breakpoint/next/continue、失敗requestの状態維持を比較する。backend hookと無効span/framingのRust回帰、既存Java DAP compile/nonBMPと実LSP回帰も実行する。

```sh
mvn -pl unlaxer-common,unlaxer-dsl -am test \
 -Dtest=ClassicDapProtocolConformanceTest,ClassicLspProtocolConformanceTest,DAPCompileVerificationTest,DAPNonBmpLineMappingTest,DAPGeneratorTest,RustUbnfFrontendConformanceTest \
 -DrustConformance=true -Dsurefire.failIfNoSpecifiedTests=false
cargo +1.85.0 test --locked --manifest-path rust/Cargo.toml \
 -p unlaxer-dap -p unlaxer-lsp -p unlaxer-codegen -p unlaxer-generator
```

Rust protocol比較にはopt-inが必要である。証跡は `unlaxer-dsl/target/classic-dap-protocol.tsv`、CIはこの生成を必須とする。親 #111 / #384 はこのsliceだけではcloseしない。root-retryの性能凍結やClassic/ubnfc engine境界は維持する。
