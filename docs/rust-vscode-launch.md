# VS CodeからJava / Rust生成サーバーを使う

開発版のTinyCalc拡張と `unlaxer init` の拡張テンプレートは、同じエディタ機能からJava / Rustのstdioサーバーを選べる。公開済み3.3.0には含まれない。Javaが既定で、既存の `server.javaPath` / `server.jarPath` / `server.jvmArgs` は維持する。

```json
{
  "tinycalcLsp.server.backend": "rust",
  "tinycalcLsp.server.rustLspPath": "/absolute/path/tinycalc-lsp",
  "tinycalcLsp.server.rustDapPath": "/absolute/path/tinycalc-dap"
}
```

一般の生成拡張では `tinycalcLsp` を生成した `<name>Lsp` へ置き換える。実行ファイル名はPATHからも検索できる。引数は `server.rustLspArgs` / `server.rustDapArgs` の文字列配列へ渡す。コマンド文字列のshell展開は行わないため、空白を含むパスもそのまま指定する。

初期化応答の待機上限は `server.startupTimeoutMs`（既定30000ms、1000〜300000ms）で設定できる。応答しないserverも、設定変更・再起動・拡張終了時には初期化待ちを中断して所有processを終了する。 初期化応答だけで開始完了とは扱わず、`initialized` 通知を含む client の開始処理が完了してから通常の LSP shutdown を許可する。開始途中の送信失敗・中断では所有 process を終了し、失敗した開始の後も明示的に再起動できる。

設定変更時にLSPを再起動し、以降に作るDAP sessionは選択されたbackendを使用する。開始済みのdebug sessionはそのまま継続する。コマンドパレットの「Restart Language Server」で明示的にも再起動できる。空のRust実行ファイル設定・未知のbackend・不正な引数は起動前に拒否する。異常終了時の自動再起動は3分以内に5回までとし、繰り返す場合は設定を修正して明示的に再起動する。

DAPのAST/token操作は構造のinspectionであり、実評価器の実行停止ではない。言語の補完・hover・definition等の内容は選択した生成サーバーとproviderに依存する。既定のTinyCalcにはdefinition providerがない。

## TinyCalcのビルド

Java21とRust1.85以上、Node20、npmを用意し、repository rootで実行する。

```sh
mvn -pl unlaxer-common,unlaxer-dsl,unlaxer-dsl/tinycalc-vscode -am package -DskipTests
cd unlaxer-dsl/tinycalc-vscode
npm ci
npm run build:rust
npx vsce package --allow-missing-repository --skip-license --out target/tinycalc.vsix
```

`build:rust` は同じ `grammar/tinycalc.ubnf` からLSP/DAPを生成し、現在のhost向け実行ファイルを `server-dist/<platform>-<arch>/` にコピーする。Rustを省いた従来のJavaのみのpackageも作成できる。Rustを使う場合は実行ファイルを明示設定する。Rust側はJVMを起動しない。

TinyCalc と新規生成する拡張は VS Code 1.97.2 以上を必要とする。実エディタ試験の対象は Linux x86_64、VS Code 1.97.2。macOS/Windows向け配布物をこの試験で検証したとは扱わない。一般の文法では[生成Rust LSP](classic-rust-lsp.md)と[生成Rust DAP](classic-rust-dap.md)のmain / Cargo設定を使ってnative binaryを作成し、対応する拡張の設定に渡す。

## 実VSIXの検証

```sh
xvfb-run -a npm run test:vsix -- target/tinycalc.vsix
```

配布VSIXを一時directoryへ展開し、実際の拡張hostでJava/Rust両方を起動する。空白を含む実行パス、補完、同期後hover、既定definition非提供、Unicode診断、AST/token DAP、異常終了からの再起動、設定不正からの復帰、停止時のchild process終了を確認する。初期化に応答せずTERMも無視するfixtureを使い、timeout・設定修正・deactivate時の中断と強制終了、未処理Promise rejectionがないことも検査する。実 backend の初期化応答後に writer を破棄済み Node stream へ差し替える試験では、reader の close 通知より先に送信失敗が届く順序を再現し、開始途中の shutdown 禁止・開始失敗の通知・再起動後の LSP 復旧を確認する。この注入は試験内に限り、backend の障害そのものを再現したとは扱わない。観測値は `target/ide-java.json` と `target/ide-rust.json` に保存し、両者を比較する。ユーザーのVS Code profileは使わない。

生成DAP launcherは `disconnect` の応答を書き出してflushしてからtransportを終了する。Java/Rustとも初期化前の切断も受理し、正常切断・相手EOFでは終了code0となる。入力を閉じずに応答を待つclientでもJavaの所有workerが残らず、後続sessionは新adapterで開始できる。

Javaのlibrary API `launch(InputStream, OutputStream)` は完了futureを返し、渡されたstreamをcloseせず、processも終了しない。`launchStdio()` は同じ完了を待つ。通常の要求は非同期・双方向通信を維持し、custom adapterの `runInTerminal` 等のreverse requestも応答を受信できる。切断要求だけは応答のflushまで待ち、5秒以内に応答しないadapter hookは異常完了となる。

入出力のI/O失敗はfutureへ直ちに通知し、所有executorへ割り込みによる停止を要求する。任意の借用 `InputStream` が割り込みを無視して既にreadでblockしている場合、そのreadを解除するためのclose・EOF等はcallerの責任となる。libraryはその解除を待ってエラー通知を遅らせない。standaloneの生成 `main` だけはprocessの所有者として `System.exit` で正常時0、transport失敗時1を適用するため、非協調的なstdio readもprocessとともに終了する。libraryの `launch` / `launchStdio` は `System.exit` を呼ばない。通常接続中にclientがstreamを開いたまま送信を止めた場合は接続が継続する。

実VSIX試験は各backendのエディタログを `target/ide-<backend>-editor.log` へ保存し、`Invalid debug adapter` と未処理Promise rejectionがないことを検査する。[#555](https://github.com/opaopa6969/unlaxer-parser/issues/555) の切断時ログはこの試験と生成launcherの実通信試験で回帰を防ぐ。


VS Code 1.96.4 は正常な切断応答の後、終了済み session に遅れて届く adapter exit で `Invalid debug adapter` を出す場合がある。これは Java/Rust 共通の editor host の終了処理であり、[1.96.4 の実装](https://github.com/microsoft/vscode/blob/1.96.4/src/vs/workbench/api/browser/mainThreadDebugService.ts)と[修正を含む 1.97.2 の実装](https://github.com/microsoft/vscode/blob/1.97.2/src/vs/workbench/api/browser/mainThreadDebugService.ts)を根拠に最低対応版を揃えている。生成 backend に終了待ち時間を追加して回避しない。

実 VSIX 試験では通常操作に加え、実 DAP child の終了と editor の停止要求を受けてから fixture wrapper の exit 通知を遅らせる。同じ AST/token 操作、再起動、無応答時の終了を両 backend で実行し、session 終了後の exit 到達と観測値の一致を検査する。`target/ide-<backend>-late-exit.json` は遅延ケースの観測値、`*-status.json` は各ケースの合否、`*-timeline.jsonl` は request / reply / process / session の順序、`*-pids.jsonl` は所有 child と wrapper を記録する。通常・遅延の両方で editor error と未処理 rejection の禁止を維持する。

一方の backend が失敗しても他方の試験を実行する。失敗ケースの directory は `target/failed-<backend>[-late-exit]/` に保存し、CI は失敗時も観測値・editor logs・終了記録を artifact に添付する。結果が存在しても status が失敗なら合格とは扱わず、`ide-results.json` に全体の合否を残す。
