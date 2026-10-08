# UBNF VS Code Extension — `unlaxer` editing `unlaxer`

This is the VS Code extension for `.ubnf` files — and it is **generated
from `ubnf.ubnf`**, the meta-grammar that describes the UBNF syntax in
UBNF itself.

The base LSP is generated from the meta-grammar. The handwritten extension adds
validation, navigation, snippets and authoring help. Keyword vocabulary is read
from the meta-grammar AST; descriptions and tutorials are maintained separately.
This is not yet a fully generated replacement for the handwritten UBNF frontend.

## はじめて使う場合：画面内の catalog/help

拡張をインストールしたら、コマンドパレットから
**UBNF: はじめの一歩 / Catalog・Help** を開いてください。
`.ubnf` のエディタ右上の本のアイコン、および VS Code の Getting Started からも開けます。

- **はじめの一歩**：単語 → 数字 → 代入 → リスト → 文字列 → 同じ区切りの6段階。
  文法の全文、行ごとの意味、成功入力・失敗入力、確認項目を同じ画面に表示します。
- **構文 catalog**：日本語のやりたいこと・記号・キーワードから検索できます。
- **困ったとき**：文法エラー、未定義名、入口、空白、左再帰、Unicode位置、LSP起動の直し方。
- **新規文書で開く**：同梱サンプルを未保存の新規 UBNF 文書に開きます。既存文書を変更しません。
  `.ubnf` として保存すると LSP が診断します。

help は LSP が起動できなくても利用でき、外部サイト・CDN・通信は不要です。
試験入力欄の成功/失敗はテスト済みの**期待値**で、help 画面で解析を実行した結果ではありません。
言語用 playground は、保存した文法のエディタ右上の **▶**、または
**UBNF: この文法の Playground を生成して開く** で作れます。
画面内の「環境準備」に従い Java 21、Node 20+、Rust 1.85+ と
`wasm32-unknown-unknown` target を準備してください。信頼済みワークスペースで実行します。
生成・WASM build 後に、隣の画面で試験入力を解析できます。HTTP server や外部公開は不要です。
文法や import 元を変更したら保存して再生成します。元ファイルは上書きしません。
生成先は拡張の管理フォルダで、ログにパスを表示します。
VS Code を使わない場合は [生成 CLI](../../docs/ubnf-playground-ja.md) も利用できます。

共通 assets は `../src/main/resources/ubnf-help/` にあります。`npm run compile` で
`help-dist/` にコピーされ、VSIX に同梱されます。`catalog.json` の全文例は
`DeclarativeTokenConformanceTest#authoringCatalogExamplesAgreeInJavaAndRust` が
Java / Rust の受理・拒否、消費位置、AST・Unicode span、両 Rust emitter の一致を検証します。

## v2 / import の編集支援

`token NAME ::= ...` の補完は宣言的字句の語彙と token 名を出します。旧 Java FQN 候補は
`token NAME = ...` のときだけです。初期 grammar snippet は `@mapping` / `@doc` を含み、
そのまま playground を生成できます。

相対 `@import num from 'numbers.ubnf'` を解決し、`num.` の補完、hover、定義への移動を提供します。
module の認識と検証は共通の `UBNFMapper` / `UBNFModuleLoader` を使います。
開いている import 元の編集 buffer を優先し、変更・保存・close 時には利用側の診断を更新します。
外部から変更された保存済み module は次の editor request で再読込します。
生成コマンドは保存済みファイルを使うため、生成前にはすべて保存してください。

rename / linked editing は AST の参照とソース位置に基づきます。文字列・コメント・capture・
mapping 型名を同名という理由で書き換えず、grammar ごとの宣言を区別します。
壊れた構文、重複名、予約語、import alias、capture、外部参照、token-only module の公開 token は
自動 rename しません。未開封の利用元を把握できないため、外部 module は定義へ移動して確認します。
参照一覧は現在の文書と外部の定義位置までで、workspace 全体の検索ではありません。

この editor API は VSIX の Java LSP 固有です。Java/Rust の文法認識・module 解決は変更せず、
共通 conformance を維持します。Rust LSP を新設する変更ではありません。

```bash
npm ci
npx playwright install chromium
npm run test:help
# Linux: 配布する VSIX そのものを、隔離した VS Code profile で起動検証
xvfb-run -a npm run test:vsix -- target/ubnf-lsp-3.3.0-SNAPSHOT.vsix
```

Chromium で全レッスン・検索・ダウンロード・モバイル幅を操作し、別のテストで
webview から未検証のパスやコマンドを受け付けないことを確認します。
スクリーンショットは `target/ubnf-help-desktop.png` と `target/ubnf-help-mobile.png` です。
VSIX smoke は配布物を一時ディレクトリへ展開し、拡張の起動・help の表示・同梱 Java LSP の
補完、文法からの生成、WASM build、実 webview の起動完了を検証します。
ユーザーの VS Code profile や拡張には触れません。初回は公式の VS Code
テスト用実行ファイルをダウンロードするためネットワーク接続が必要です。

## Why this exists

`.ubnf` files are unlaxer's primary input. Until this extension, editing
them meant trial-and-error against `mvn package` to find typos. With
this extension installed:

- Real-time parse diagnostics
- Keyword completion (`grammar`, `token`, `@root`, `@mapping`, …)
- Go-to-definition for rule references (`@backref`)
- Hover with parse status
- Semantic tokens (valid / invalid)

And — more importantly — it proves the unlaxer pipeline is **complete
enough to specify itself**.

## How it is built

```mermaid
flowchart TD
    Src["unlaxer-dsl/grammar/ubnf.ubnf<br/>(source of truth)"]
    Gen["target/generated-sources/ubnf/org/unlaxer/dsl/bootstrap/generated/<br/>UBNFLanguageServer.java<br/>UBNFLspLauncher.java"]
    Jar["target/ubnf-lsp-server.jar (fat jar)"]
    Dist[server-dist/ubnf-lsp-server.jar]
    Vsix["target/ubnf-lsp-0.1.0.vsix<br/>(install this in VS Code)"]

    Src -- "CodegenMain --generators LSP,Launcher" --> Gen
    Gen -- "maven-shade-plugin" --> Jar
    Jar -- "antrun copy" --> Dist
    Dist -- "npm install + vsce package" --> Vsix
```

All of this is wrapped in `pom.xml` so a top-level `mvn -B package`
produces the VSIX.

## Quick start

```bash
# from repo root
mvn -B install -DskipTests              # builds + installs into local m2
cd unlaxer-dsl/ubnf-vscode
mvn -B verify -Dgpg.skip=true           # produces target/ubnf-lsp-0.1.0.vsix

# install the extension (requires VS Code on PATH)
code --install-extension target/ubnf-lsp-0.1.0.vsix

# open any .ubnf file (e.g. unlaxer-dsl/grammar/ubnf.ubnf itself!)
```

## How it relates to `unlaxer init`

`unlaxer init <name>` (Issue #5) generates a brand-new VS Code extension
scaffold for an arbitrary DSL. This `ubnf-vscode/` is the **canonical
worked example** of that same pattern, applied to UBNF itself. The
template under `unlaxer-dsl/src/main/resources/scaffold/` and this
directory share the same shape: pom.xml (codegen + shade + vsce), a
`vscode-extension/` subdirectory, an `IMPLEMENTATION` doc.

## Historical record

The original self-hosting milestone (the moment `ubnf.ubnf` first
generated a working `UBNFLanguageServer`) is documented in
[`docs/ubnf-self-hosting.md`](../../docs/ubnf-self-hosting.md).

## Related issues

- opaopa6969/unlaxer-parser#4 — Grammar Editor LSP (this extension)
- opaopa6969/unlaxer-parser#5 — `unlaxer init` scaffold (the template)
- opaopa6969/unlaxer-parser#8 — replacing the hand-written
  `UBNFParsers.java` with the generated one
