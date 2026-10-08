# UBNF 文法から playground を作る

開発版 **3.3.0-SNAPSHOT**。公開済み 3.2.0 にはこの生成コマンドは含まれません。

UBNF を書く人には VSIX の **UBNF: はじめの一歩 / Catalog・Help**、
できた言語を試す人には、この手順で生成するブラウザ画面を用意しています。
ブラウザで動くのは **生成 Rust parser の WebAssembly** です。
Java-hosted compiler / native Rust compiler は同じプロジェクトを生成します。

## 言語を作りながら学ぶ

[はじめての言語づくり](https://opaopa6969.github.io/unlaxer-parser/) はインストール不要の体験教材です。
説明 → 問い → UBNF 文法の編集 → 自動チェックを繰り返し、8 ステップで数値・四則演算・括弧・
変数・条件式を持つ TinyExpression 風の小さな言語を作ります。完成した AST を学習用評価器で計算できます。
文法と進捗はブラウザ内に保存され、途中から再開できます。文法は `.ubnf` として保存できます。
生成 playground と VSIX の Help にも教材へのリンクがあります。リンク先の利用には通信が必要です。

学習画面には、文法を編集してその場で入力を解析する専用 WASM エンジンがあります。
通常の生成 playground と別の画面で、対応範囲を絞り、既存の Rust frontend / lowering / runtime を利用します。
Java / native Rust の生成 parser と、全教材の受理・拒否、消費位置、AST 全体とソース位置を比較します。
一般の UBNF を任意にコンパイルするサービスではありません。
教材の追加・ローカル起動・公開手順は [学習シナリオの構成](ubnf-learning-ja.md) を参照してください。

## まず動かす

### VSIX から操作する

開発版 VSIX の **UBNF: はじめの一歩 / Catalog・Help** を開きます。
環境準備・全文の文法例・入力例・失敗時の対処は画面内にあります。
Java 21、Node 20+、Rust 1.85+、`wasm32-unknown-unknown` target が必要です。

1. 例を「新規文書で開く」で開き、`.ubnf` に保存します。
2. 信頼済みワークスペースで、文法エディタ右上の **▶** を押します。
3. 文法の生成と WASM build が完了すると、隣に入力画面が開きます。
4. 例の試験入力を書いて「解析する」。文法を変えたら保存して再度 **▶** を押します。

Java / Node / Cargo を自動インストールする機能ではありません。実行ファイルの場所は
`ubnfLsp.server.javaPath` / `ubnfLsp.playground.nodePath` / `ubnfLsp.playground.cargoPath` で設定できます。
失敗したら通知の「環境準備・Help」「ログを開く」を使ってください。

生成先は拡張の管理フォルダに毎回作り、元文法や既存 project は上書きしません。
生成先のパスは UBNF LSP のログに残します。不要な生成物は、このパスを確認して片付けられます。
import を含め保存済みファイルを読みます。別の UBNF 文書に未保存の変更があれば先に保存します。
各生成工程は180秒まで。拡張の終了時は所有する生成プロセスを停止します。
webview にはその生成先の静的ファイルのみを許可し、HTTP server は起動しません。

### CLI から操作する

リポジトリのルートで実行します。Rust 1.85 以上、Node 20 以上が必要です。
出力先は新しいディレクトリにしてください。既存ディレクトリは上書きしません。

```sh
rustup target add wasm32-unknown-unknown
cargo run --locked --manifest-path rust/Cargo.toml -p unlaxer-generator -- \
  playground --grammar docs/examples/ubnf-v2/main.ubnf --output build/assignment-playground
cd build/assignment-playground
npm run build
npm start
```

表示された `UBNF_PLAYGROUND_URL=http://127.0.0.1:.../` を開き、`price = 12.5;` と入力して
「解析する」を押します。`price = 1e+;` や `price = 1;extra` も試してください。
文法は import された数値・識別子の部品を使用します。

生成後の build は `cargo --offline` を使います。`npm install`、CDN、外部 registry の
runtime は不要です。初回の Rust/Node/toolchain 準備だけは別途必要です。

## Java 側の生成コマンド

リポジトリのルートで Java 21 を使い、先に開発版をローカルへ install します。
Central へ公開する操作ではありません。

```sh
mvn -q -pl .,unlaxer-common,unlaxer-dsl install -DskipTests -Dgpg.skip=true
mvn -q -pl unlaxer-dsl exec:java \
  -Dexec.mainClass=org.unlaxer.dsl.CodegenMain \
  -Dexec.args='playground --grammar docs/examples/ubnf-v2/main.ubnf --output build/java-playground'
```

同じ `npm run build` / `npm start` で開けます。Java-hosted compiler を使っても、
ブラウザで JVM を起動するわけではありません。生成先は両経路とも Rust/WASM です。

## 画面に含まれるもの

- 入力全体の成功・不一致、部分解析の消費位置と最大一致位置。
- Unicode コードポイントの診断位置と、1 始まりの行・列。
- CST の node、子 node、範囲。範囲を押すと対応する入力を選択。
- `@mapping` による AST。認識成功と AST 投影エラーは区別。
- ルールの `@doc` を表示する検索付き言語 catalog。
- 生成時点の元文法、エラーの読み方、文法作者向けの入門/catalog/help へのリンク。

```ubnf
grammar Hello {
  @ubnf: v2
  @package: example.hello
  @root
  @mapping(Greeting, params=[text])
  @doc('hello と入力してください。大文字小文字を区別します。')
  Start ::= 'hello' @text;
}
```

この例を `hello.ubnf` に保存して生成すると、言語 catalog に「hello と入力してください」と
表示されます。HTML として実行せずテキストとして表示します。

## UBNF 自体の playground

[公開版 UBNF 文法 Playground](https://opaopa6969.github.io/unlaxer-parser/ubnf/) は、
インストールせずに文法そのものを書いて試せる画面です。入門の6文法、UBNF 自身の定義、
構文エラーの例を読み込み、編集して「解析する」を押します。構文の成否・エラー位置・AST・CST を表示し、
CST の範囲を押すと入力欄の対応箇所を選択できます。入力はブラウザ内で処理し、自動保存しません。

入力中に文脈に応じた補完候補が表示されます。`Ctrl+Space`（または「補完候補」ボタン）で明示的に開き、
上下キーで選択、`Enter` / `Tab` または候補のクリックで挿入、`Esc` で閉じます。
文法・token・ルール・基本 annotation の例と説明はヘルプの catalog を共有します。
同じ grammar 内の token / ルール名と `@mapping` の capture 名も候補になります。
`@whitespace:` の値を選ぶと、`javaStyle` が読み飛ばす空白・コメントや `none` の説明を確認できます。
コメント・文字列内と IME 変換中では補完せず、token 本体にはルール名を混ぜません。
これは単一文書の編集支援です。外部 import の読み込みや名前解決・生成可否の検証は CLI / VS Code で行います。

`--grammar unlaxer-dsl/grammar/ubnf.ubnf` を指定すれば、UBNF を受け入れる playground も生成できます。
そこには**文法そのものを試験入力として**入れます。生成されたメタ文法 parser が、
入門の6文法とメタ文法自身を読み取れることをテストしています。

メタ文法が入力を構文として読めることと、その文法から目的の backend を生成できることは別です。
名前解決・左再帰・backend の対応範囲などの検証は生成コマンドで行います。
ブラウザ上で入力した新しい文法をその場でコンパイルする機能ではありません。

公開サイトは `node scripts/build-learning.mjs` で教材・完成例の parser とまとめて生成します。
メタ文法から生成した `public/` に `unlaxer-dsl/src/main/resources/ubnf-playground/` の入口と例の UI を重ねます。
解析 UI・Worker・WASM は通常の生成 playground と共通です。入門例はヘルプの catalog から読み込みます。
`learning-pages.yml` が `master` の変更を配置し、文法例・エラー・範囲選択・狭い画面をブラウザで検証します。

## 再生成と安全性

元の `.ubnf` を編集したら、別の新規ディレクトリへ再生成して build してください。
`public/grammar.ubnf` は表示用スナップショットなので、それだけを編集しても WASM は変わりません。
import は生成時に解決します。生成後のブラウザから import 元への通信は行いません。

`playground --grammar ... --output ... --check` は生成予定のファイルと既存出力を比較する読み取り専用操作です。
手書きファイルの上書きや symlink 経由の出力は拒否します。失敗時の staging が残った場合は、そのパスを報告します。

入力はブラウザから外へ送信しません。初版の制限は UTF-8 で 64 KiB、Worker の実行3秒、
結果4 MiB、WASM メモリ256 MiB。打ち切り・WASM trap は「不一致」でなく「実行エラー」と表示します。
静的 UI からのコード実行・評価・外部 context の呼び出しはありません。

生成 project の `public/` は静的ホストに配置できますが、このコマンドは公開・deploy しません。
`serve.mjs` はループバックに bind するローカル試験用サーバーです。

## 対応範囲と検証

宣言的 token と Rust backend が対応するルールが対象です。旧 Java parser binding、
任意の host code、未対応 annotation は生成前に拒否します。
通常は入口から1つの mapped AST node が必要なので、入門例は `@mapping` を付けています。
例外的な parser-only / `@skip` の仕様は [AST 投影の契約](skip-ast-projection.md) を参照してください。

Java / native Rust の生成 project 全ファイルの一致、入門29入力と import を含む図解ガイド41入力の
受理・拒否、消費位置、AST、Unicode span を Java / Rust / WASM で検証します。
ブラウザテストでは実 WASM、catalog、CST 選択、AST、位置付きエラー、サイズ制限、
タイムアウト後の再試行、HTML の無害な表示、狭い画面と authoring help を確認します。

```sh
mvn -pl unlaxer-common,unlaxer-dsl -am test \
  -Dtest=PlaygroundCommandTest,DeclarativeTokenConformanceTest \
  -DrustConformance=true -Dsurefire.failIfNoSpecifiedTests=false
cd unlaxer-dsl/ubnf-vscode
npm ci
npx playwright install chromium
node scripts/playground-browser.mjs
```
