# UBNF authoring LSP の Java / Rust 対応

追跡: #864、親 #111。Java 参照 revision は `b96a9c07`（第一段の分岐点）。
生成した言語の LSP と、ここで扱う `.ubnf` 文法作者向け LSP は別のサーバーである。
Java の既存経路は維持し、Rust は対応した capability だけを広告する。

## コードとテストから固定したインターフェース

参照実装: `unlaxer-dsl/ubnf-vscode/src/main/java/org/unlaxer/dsl/lsp/ubnf/UBNFLanguageServerExt.java`、
`UbnfEditorIndex.java`、継承元を出力する `LSPServerEmitter.java` / `LSPQueryEmitter.java`。
参照テスト: 同 module の `UBNFLanguageServerExtTest`、`UbnfImportEditorTest`、
`UBNFLanguageServerTest` および `scripts/vsix-suite.cjs`。

| Java capability / wire method | Java の実際の振る舞い | Rust 第一段・共通検証 |
|---|---|---|
| `initialize`, `initialized`, `shutdown`, `exit` | Full sync、UTF-16、初期化前/終了後 request を拒否 | 実 stdio 共通 fixture |
| `textDocument/didOpen`, `didChange`, `didClose`, `didSave` | open buffer を索引化し import 元を更新。Full change、close 後は disk に戻る | 実 stdio 共通 fixture |
| `$/setTrace`, `$/cancelRequest`, workspace configuration / watched files | 標準通知（基準 server の no-op を含む） | no-op、同期処理 |
| `textDocument/publishDiagnostics` | parse `ULX-PARSE-001`、GrammarValidator、GenerationWarnings、module error | syntax / generation warnings / module error。完全な validator と syntax expected/stack は未対応 |
| `hoverProvider`: `textDocument/hover` | catalog/annotation、宣言・由来・文書内参照数、parse status | catalog/annotation、ローカル/直接 import token の hover、共通 fixture |
| `completionProvider`: `textDocument/completion` | resolve=false、trigger `@`, `=`, `.`。annotation/FQN/snippet、lexical/whitespace/scope別候補と textEdit | 同じ data と scope/直接 import 候補、共通 fixture |
| `definitionProvider`: `textDocument/definition` | AST の binding、外部 token / package URI | 未対応 |
| `referencesProvider`: `textDocument/references` | 文書内参照と includeDeclaration、外部宣言 | 未対応 |
| `renameProvider`: `textDocument/rename` | 完全文法の非公開ローカル binding のみ。重複/予約語等を拒否 | 未対応 |
| `documentSymbolProvider`: `textDocument/documentSymbol` | grammar に token/rule を入れ子表示、不完全入力の回復 | 未対応 |
| `foldingRangeProvider`: `textDocument/foldingRange` | grammar block と複数行 rule | 未対応 |
| `linkedEditingRangeProvider`: `textDocument/linkedEditingRange` | rename と同じ安全な binding の ranges | 未対応 |
| `codeActionProvider`: `textDocument/codeAction` | `W-TOKEN-UNRESOLVED` の FQN quick fix | 未対応 |
| `signatureHelpProvider`: `textDocument/signatureHelp` | trigger `(`, `,`、annotation signature | 未対応 |
| `semanticTokensProvider`: `textDocument/semanticTokens/full` | full=true、10 token types、modifier declaration。不完全入力でも動く | 未対応（range/delta は Java も広告しない） |
| `ubnf/packageSource` | 検証済み index にある package source だけを返す | 未対応 |
| `unlaxer/configure`（継承） | profile / client capability を再設定し cache 無効化 | 未対応。initialize の非空 options も明示拒否 |

将来機能の formatting、workspace 全体索引、completion resolve、semantic delta は今回の parity 条件に混ぜない。
未対応 request は `-32601`。全診断/profile は [#885](https://github.com/opaopa6969/unlaxer-parser/issues/885)、
navigation/編集支援/packageSource/native 配布は [#886](https://github.com/opaopa6969/unlaxer-parser/issues/886) で継続する。
両子 issue の merge と #864 の全受け入れ条件確認まで、第一段の成功を #864 全体の完了とはしない。

## 実行と境界

`cd rust && RUSTUP_TOOLCHAIN=1.85.0 cargo build -p unlaxer-ubnf-lsp --release` で
`target/release/ubnf-language-server` を作る。stdin/stdout は既存 `unlaxer-lsp` の framed JSON-RPC を使い、JVM を起動しない。
VSIX は `ubnfLsp.server.backend=rust` と `ubnfLsp.server.rustPath`（実行ファイルの絶対パス）で選択する。
既定は Java。変更後は Reload Window。native executable のクロスプラットフォーム同梱は後続。
Playground の生成は従来どおり Java/Node/Rust toolchain を使う。

import は既存 `modules::resolve` / `packages::Resolver` を通す。package の lock/hash/依存/境界を検証し、
自動取得・resolve・書込み・コード実行はしない。local import が `/dev/stdin`・FIFO 等の通常ファイル以外を読んで止まる問題は
Java/Rust 共通の既存挙動で、[#890](https://github.com/opaopa6969/unlaxer-parser/issues/890) で直す。開いたローカル `.ubnf` buffer は disk より優先し、
package の検証済み source を editor buffer で上書きしない。URI は percent encoding を復号して local file を読む。
位置は frontend の UTF-8 byte / CP span を編集時に UTF-16 へ変換し、parser 本体は変更しない。

共通入力・独立期待値は `spec-corpus/ubnf-authoring-lsp/`、実 Java/Rust stdio 比較は
`UbnfAuthoringProtocolConformanceTest`。限定一致と未対応の診断を区別して検証する。

## 第一段の検証範囲

共通 22 文書と 5 import/package シナリオ（計27件）を両実 stdio server へ送る。
completion は順序・snippet・documentation・textEdit を含む全 JSON、対象 hover と
生成警告は全 JSON を比較する。CRLF、非BMP直後の UTF-16 textEdit、同名宣言衝突、
複数 grammar、コメント中の偽宣言、不完全 annotation/FQN/qualified import を含む。
独立期待値のラベル・除外ラベル・説明・診断コードを確認してから Java/Rust を比較する。

位置は LSP 仕様と Java `UbnfEditorIndex.offset` に合わせ、行末を超える character は行末（`\n` の直前）、
文書末を超える line は文書末に丸める。completion の置換範囲と hover の単語は Java の
`Character.isLetterOrDigit`（UTF-16 単位なので補助文字は単語に含めない）に合わせ、修飾子は Java の
`([A-Za-z_]\w*\.)\w*$` と同じく最後の `alias.` だけを使う（先頭が `.` なら修飾なし）。共通 fixture の
`past-line-end`・`non-ascii-prefix`・`leading-dot`・`nested-qualifier`・`supplementary-letter-hover` で固定した。
残差: サロゲートペアの内側を指す位置は Rust が `-32602` で拒否し、Java は UTF-16 の途中 offset のまま処理する。
BMP 内の Unicode 分類は `char::is_alphanumeric` による近似で、結合文字（Mn/Mc）と Nl/No は未照合（#886）。
同じ version 以下の `didChange` を Rust は無視し、Java は適用する（#885）。

構文エラー時の fallback hover は Java の consumed offset と Rust の最初の error offset を区別する（#885）。
構文エラーは両方の拒否を確認するが、native frontend の最初のエラー位置と
Java の farthest failure / expected / rule stack の同値性は未達成（#885）。
重複宣言の fixture では補完候補の抑制を比較し、validator 診断は未対応と明記する。
module error は共通 fixture の code/severity/source/range を比較し、resolver の説明文は別契約として残す。

ローカル module の未保存変更、change/close/save 後の再通知、percent encoded URI、
package の固定 hash/由来、hash 不一致・lock 欠落・package 境界逸脱の拒否を検証する。
virtual package path に開いた偽 buffer が検証済み source を置換できないこと、
server が manifest/lock/cache を書き換えないことも両版で検証する。
現時点の native file URI は Linux のローカル絶対パスを検証対象とし、UNC/Windows 等は #886 に残す。

CI は `ubnf-authoring` 必須契約の2メソッドと27件の実行件数を検査する。
packaged VSIX は従来 Java smoke を保持し、別の隔離 profile で Java executable を不存在に設定して
Rust の completion/hover/診断と変更後の修復、未広告 definition を確認する。最後に拡張の `deactivate` で
native server の子プロセスが終了し、残らないことを確認する（tinycalc-vscode #874/#880 と同じ後始末の水準）。
native executable 自体は外部パスを指定し、VSIX 内に同梱したとは扱わない。

## 検証コマンドと mutation

- `cd rust && RUSTUP_TOOLCHAIN=1.85.0 cargo fmt --all --check && cargo clippy --workspace --all-targets -- -D warnings && cargo test --workspace`
- 隔離 Maven repository と JDK21 で `-pl unlaxer-dsl/ubnf-vscode -am test -Dtest=UbnfAuthoringProtocolConformanceTest,UBNFLanguageServerExtTest,UbnfImportEditorTest,UBNFLanguageServerTest,RustUbnfFrontendConformanceTest,PackageResolverConformanceTest,GenerationWarningsConformanceTest -DrustConformance=true -Dsurefire.failIfNoSpecifiedTests=false`。
- `python3 scripts/verify-optin-tests.py ubnf-authoring`、`python3 -m pytest -q scripts/tests`、両 parity checker、`actionlint .github/workflows/maven.yml`。
- VSIX 作成後 `node scripts/vsix-smoke.mjs target/ubnf-lsp-authoring.vsix` を Java と `UBNF_TEST_BACKEND=rust UBNF_TEST_RUST_PATH=<絶対パス>` の2経路で実行。headless 環境は `xvfb-run -a`、Chrome/Electron は `--password-store=basic`。

UTF-16 を CP 数に変える、local buffer overlay を外す、generation warning を空にする3変異で、
それぞれ共通 stdio fixture が失敗することを確認した。PR #888 のレビューで、位置の丸めを外す、置換範囲を ASCII に戻す、
修飾子を最初の区切りに戻す、hover の単語に補助文字を含める、importer の再通知を外す、VSIX suite の `deactivate` を外す
6変異でも、それぞれ共通 fixture・Rust テスト・packaged VSIX が失敗することを確認した。protocol テストの `@Test` を外すと、
残ったテスト自体が成功しても必須メソッド検査が失敗する。全変異を復元して再検証する。
