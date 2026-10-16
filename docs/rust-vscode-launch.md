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

初期化応答の待機上限は `server.startupTimeoutMs`（既定30000ms、1000〜300000ms）で設定できる。応答しないserverも、設定変更・再起動・拡張終了時には初期化待ちを中断して所有processを終了する。

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

実エディタ試験の対象はLinux x86_64、VS Code 1.96.4。macOS/Windows向け配布物をこの試験で検証したとは扱わない。一般の文法では[生成Rust LSP](classic-rust-lsp.md)と[生成Rust DAP](classic-rust-dap.md)のmain / Cargo設定を使ってnative binaryを作成し、対応する拡張の設定に渡す。

## 実VSIXの検証

```sh
xvfb-run -a npm run test:vsix -- target/tinycalc.vsix
```

配布VSIXを一時directoryへ展開し、実際の拡張hostでJava/Rust両方を起動する。空白を含む実行パス、補完、同期後hover、既定definition非提供、Unicode診断、AST/token DAP、異常終了からの再起動、設定不正からの復帰、停止時のchild process終了を確認する。初期化に応答せずTERMも無視するfixtureを使い、timeout・設定修正・deactivate時の中断と強制終了、未処理Promise rejectionがないことも検査する。観測値は `target/ide-java.json` と `target/ide-rust.json` に保存し、両者を比較する。ユーザーのVS Code profileは使わない。
