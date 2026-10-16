# IDE 機能と LSP / DAP / unlaxer の対応棚卸し

Issue: [#498](https://github.com/opaopa6969/unlaxer-parser/issues/498)。
IntelliJ IDEA と Eclipse JDT の編集・ナビゲーション・リファクタリング・検索・解析・デバッグ機能を、
(a) LSP 3.17 / 3.18 と DAP の標準、(b) unlaxer が生成・提供している LSP / DAP の現状に対応づける。
拡張（`unlaxer/...` 名前空間）を設計する前に参照する表である。この文書はコードを変更しない。

- 調査基準: `master` 978f07df（#497 merge 後）、2026-10-15。
- 仕様: LSP は 3.18（公式サイトで「Current」）、DAP は公式 specification。メソッド名は仕様本文で確認した。確認しきれなかったものは「要確認」と書き、最後に一覧にする。
- 「現状」欄は、該当箇所の `ファイル:行` かテスト名を根拠として書く。根拠が見つからない場合は「なし」とする。推測で「実装済み」とは書かない。

## 開発版の追補

元の棚卸しは上記revisionに固定した記録である。以降のJava21 / Rust1.85共通検証による変更は以下を参照する。
生成server、UBNF専用server、下流の手書き拡張を区別し、片側の拡張機能で共通parityを主張しない。

| 変更 | 現行の契約・検証 |
|---|---|
| full semantic tokens（#535） | [Java/Rust providerとlegend](classic-rust-lsp.md)。共通30-case corpus、実stdio、各7設定。既定は空、range/deltaは未対応 |
| lifecycleと構文診断（#549/#552） | [initialize/configure](classic-lsp-lifecycle.md)の59操作と[診断位置](classic-lsp-diagnostics.md)の20ケースを両生成serverで比較 |
| snapshot cache（#546/#613） | [不変snapshot再利用](incremental-cache.md)。同じ文書/設定/stateだけ再利用し、変更時は全文解析。LSPのincremental syncや部分CST再解析とは別 |
| VS CodeとDAP終了（#548/#555） | [両backend起動](rust-vscode-launch.md)を実VSIXで検証。TinyCalc/新規生成拡張を対象とし、全下流拡張の互換性は主張しない |

Java LSP4Jのcancellation機構があることと、利用者の同期providerやparserが途中で停止できることは別である。
Rust transportのparse中interruptは未実装。rename/format/code-action transportとlive evaluator debuggerも、この追補で対応した機能には含めない。

## 1. 読み方

### 区分

| 区分 | 意味 |
|---|---|
| **1** | 標準（LSP / DAP）にあるが、unlaxer の生成物では未実装か部分的。拡張なしで埋められる |
| **2** | 標準に対応するものがない。`unlaxer/...` 名前空間の独自拡張の候補 |
| **3** | 対象外。生成器の仕事ではない（ビルドやプロジェクトのモデルは BSP の担当、hot code replace・ヒープ・データフローなど言語の意味論や実行系に依存するもの、クライアントだけで完結するもの） |
| 済 | 生成物（または UBNF 言語サーバ）で実装されており、根拠となるテストがある |

### 「文法から自動生成できるか」

| 記号 | 意味 |
|---|---|
| ◎ | 文法と生成済み parser（CST）だけで生成できる |
| ○ | 文法の注釈（`@declares` / `@backref` / `@scopeTree` / `@mapping` / `@doc` / `@embedded` / `@recovery`）と既存の runtime 情報を使えば生成できる |
| △ | 生成器が用意できるのは枠（transport・hook）まで。中身は provider かアプリケーションの hook が埋める |
| × | 文法からは生成できない（言語の意味論、外部の実行系、またはモデルが必要） |

### 優先度

- **P1**: 文法から安く生成でき、IDE 体験への効果が大きい。次に着手する候補。
- **P2**: 効果はあるが、前提（増分解析、provider など）が要る。
- **P3**: 需要が限られる、または意味論側の比重が大きい。

### 根拠の略号

| 略号 | パス |
|---|---|
| LSE | `unlaxer-dsl/src/main/java/org/unlaxer/dsl/codegen/LSPServerEmitter.java`（行は、生成コードを出力している emitter 側の行） |
| LQE | `unlaxer-dsl/src/main/java/org/unlaxer/dsl/codegen/LSPQueryEmitter.java` |
| LDE | `unlaxer-dsl/src/main/java/org/unlaxer/dsl/codegen/LSPDiagnosticEmitter.java` |
| DPE | `unlaxer-dsl/src/main/java/org/unlaxer/dsl/codegen/DAPProtocolEmitter.java` |
| DRE | `unlaxer-dsl/src/main/java/org/unlaxer/dsl/codegen/DAPRuntimeEmitter.java` |
| RL | `rust/unlaxer-lsp/src/lib.rs` |
| RD | `rust/unlaxer-dap/src/lib.rs` |
| UBX | `unlaxer-dsl/ubnf-vscode/src/main/java/org/unlaxer/dsl/lsp/ubnf/UBNFLanguageServerExt.java`（UBNF 自身の言語サーバ。生成 `UBNFLanguageServer` を手書きで拡張したもの） |
| SS | `unlaxer-dsl/src/main/java/org/unlaxer/dsl/runtime/ScopeStore.java` |
| golden | `unlaxer-dsl/src/test/resources/golden/lsp_snapshot.java.txt` / `dap_snapshot.java.txt`（`CodegenSnapshotTest`） |

## 2. unlaxer の現状のまとめ

対象は 5 つのサーバである。どれも stdio で動く。

| サーバ | 広告している capability | 根拠 |
|---|---|---|
| Java 生成 LSP（`LSPGenerator`） | `textDocumentSync=Full`、`completionProvider`（resolve なし）、`hoverProvider`、`semanticTokensProvider`（legend は `valid`/`invalid` だけ）。`definitionProvider` は provider を登録した場合だけ。`experimental.languageQueryConsumer` / `experimental.languageProfile` | LSE:93-108、LQE:12-30、LSE:490-520、golden、`LSPGeneratorTest`、`LSPProfileInitializationTest#generatedServerNegotiatesProfilesAndRejectsInvalidSettings` |
| Rust Classic LSP（`unlaxer-lsp`、`--lsp`） | `positionEncoding=utf-16`、`textDocumentSync=1`（Full）、`completionProvider`、`hoverProvider`。`definitionProvider` は provider を登録した場合だけ。experimental は Java と同じ | RL:178-218、`rust/unlaxer-lsp/tests/protocol.rs`、`ClassicLspProtocolConformanceTest#generatedServersShareIndependentPositionsAndVersionTransactions` |
| UBNF 言語サーバ（ubnf-vscode） | 上の Java 生成分に加えて、completion の trigger 文字、definition、references、rename、documentSymbol、codeAction、foldingRange、linkedEditingRange、signatureHelp、semanticTokens（10 種類）。独自 request `ubnf/packageSource` | UBX:298-328、`UBNFLanguageServerExtTest#initializeAdvertisesRichCapabilities` |
| Java 生成 DAP（`DAPGenerator`） | `supportsConfigurationDoneRequest` だけ。request は launch / configurationDone / setBreakpoints / next / continue / threads / stackTrace / scopes / variables / disconnect | DPE:47-56、DAPGenerator.java:62-90、`DAPGeneratorTest`、`ClassicDapProtocolConformanceTest#generatedJavaAndRustShareStrictTypedAstInspection` |
| Rust Classic DAP（`unlaxer-dap`、`--dap`） | `supportsConfigurationDoneRequest` だけ。request は Java と同じ集合。それ以外は `unsupported command` | RD:177、RD:183-351、`rust/unlaxer-dap/tests/protocol.rs`、`ClassicTokenDapProtocolConformanceTest#generatedTokenDapUsesRealBoundedTerminalTrace` |

注意点:

- 生成 DAP は、parse と map が終わった後に AST またはトークンを順にたどる **inspection** である。実行中の evaluator を止めるデバッガではない（`docs/classic-rust-dap.md`、`docs/token-trace.md`）。そのため、意味論に依存する DAP 機能は区分 3 にした。
- `unlaxer-dsl/docs/lsp-extensions.md` と `unlaxer-dsl/docs/UBNF-EXTENSION-ROADMAP.md` で ✅ が付いている documentSymbol / rename / documentHighlight / codeLens / inlayHints は、下流 TinyExpression の手書きクラス `TinyExpressionP4LanguageServerExt` の実装である（`unlaxer-dsl/docs/lsp-extensions.md:229`）。生成器の能力ではないので、この棚卸しでは「生成: なし」と扱う。
- 編集用の基盤（`SourceEdits` / `ProjectRename` / `ProjectSymbolIndex`）は Java と Rust の両方にあるが、LSP の rename / format / codeAction の transport には接続していない（`docs/source-preserving-edits.md` の能力表「LSP rename/format/codeAction transport: Not implemented」、`LSPQueryConsumerTest#actualProviderCompletionsUseHostSnapshotAndUtf16` の 115 行目で `getRenameProvider()` / `getDocumentFormattingProvider()` / `getCodeActionProvider()` が null であることを確認している）。
- 生成 Java LSP は `@declares` などがある文法で `ScopeStore.registerDispatcher` を呼ぶ（LSE:167-168）。しかし宣言・参照・診断（SS:228, 259, 295）を LSP へ出さず、parse 後に `context.close()` で捨てている（LSE:194）。Rust の scope 機構（`rust/unlaxer-runtime/src/scope.rs`）も、LSP の `GrammarBackend::validate` では使っていない（RL:73-112）。

## 3. 対応表: LSP（編集・表示）

| # | IDE 機能 | IntelliJ / Eclipse での名前 | LSP の対応 | unlaxer Java の現状 | unlaxer Rust の現状 | 文法から自動生成 | 優先度 | 区分 |
|---|---|---|---|---|---|---|---|---|
| E1 | 意味的ハイライト | IJ: Semantic highlighting（Annotator）/ Ec: Semantic Highlighting | `textDocument/semanticTokens/full`・`/full/delta`・`/range` | 生成: 広告はするが legend は `valid`/`invalid`。hook `additionalSemanticTokenData` を override しなければ常に空（LSE:99-104, 444-449, 757-776）。UBNF: 10 種類（UBX:293-296, 317-321, 730; `semanticTokensProduceData`） | 開発版で full provider と legend hook を追加（#535）。既定dataは空、range/deltaと文法からの自動色付けは未対応。[共通wire corpus](classic-rust-lsp.md)でJavaと比較 | ◎ token 宣言・keyword literal（LSE:885）・`@declares`（declaration modifier）から | P1 | 1 |
| E2 | 補完（基本・文脈依存） | IJ: Basic / Smart Completion / Ec: Content Assist | `textDocument/completion` | 生成: 文法中のすべての keyword を位置と無関係に列挙し、catalog・editor hook・provider の結果を足す（LSE:97, 684-717, 387-414）。UBNF: trigger 文字あり、文脈別（UBX:302, 390; `completionOffersDeclaredRuleNames`） | keyword、`Backend::complete`、provider（RL:290-337） | ◎ カーソル位置で期待される token（`ParseFailureDiagnostics` の expected、Rust `editor_cst::literal_completions` = `rust/unlaxer-runtime/src/editor_cst.rs:300`。Playground では使用済み: `unlaxer-dsl/src/main/resources/playground/lib.rs:73`） | P1（文脈化） | 1 |
| E3 | 補完 resolve・snippet 挿入 | IJ: Live Templates / Postfix Completion / Ec: Templates | `completionItem/resolve`、`InsertTextFormat.Snippet` | 生成: `resolveProvider=false`（LSE:96）。UBNF: annotation の snippet（`completionAfterAtOffersAnnotationSnippets`） | `resolveProvider:false`（RL:180） | ○ `@mapping(params=…)` から雛形を作れる | P3 | 1 |
| E4 | パラメータ情報 | IJ: Parameter Info / Ec: Context Information | `textDocument/signatureHelp` | 生成: なし。UBNF: `@mapping` の中だけ（UBX:313-315, 695; `signatureHelpInsideMappingAnnotation`） | なし | △ 呼出しの構文は文法から分かるが、仮引数は意味論 | P3 | 1 |
| E5 | クイックドキュメント | IJ: Quick Documentation / Ec: Javadoc hover | `textDocument/hover` | 生成: カーソル位置と関係なく、文書全体の成否（`Valid X` か parse エラー文）だけを返す（LSE:722-750）。provider 登録時はその結果（LQE:85-91）。UBNF: annotation と rule の説明（UBX:477; `hoverOnRuleNameShowsDeclaration`） | 同じく診断文か `Valid X`（RL:338-405, 404） | ◎ カーソル位置の CST node と、その rule の `@doc`（`ParserRuleEmitter.java:554` では Javadoc 化までしている） | P1 | 1 |
| E6 | 構文エラー表示 | IJ: Error highlighting / Ec: Problems | `textDocument/publishDiagnostics` | 生成: `ULX-PARSE-001` を 1 件。data に expected / deepest rule を入れる。hook と provider の診断を合わせて出す（LSE:290-336, LDE:10-57; `LSPGeneratorTest#testGeneratesActionableMachineReadableParseDiagnostics`） | 同等（RL:73-112, 469-605） | ◎ | 済（複数エラー化は E8） | 済 |
| E7 | 未定義参照などの検査 | IJ: Inspections（Unresolved reference）/ Ec: Errors/Warnings | `textDocument/publishDiagnostics` | 生成: ScopeStore の診断（SS:213, 228）を出さない（LSE:167-168, 194）。UBNF: 未解決 token と左再帰を出す（`validatorReportsUnresolvedTokenWithFqnHint`, `validatorReportsLeftRecursionAsWarning`） | runtime（`scope.rs`）はあるが LSP 未接続（RL:73-112） | ○ `@declares` / `@backref` / `@scopeTree`（`docs/generated-scope-effects.md`） | P1 | 1 |
| E8 | 複数の構文エラー（回復） | IJ / Ec: 編集中の部分解析 | `publishDiagnostics`（複数件） | 生成: 最遠の失敗 1 件だけ（LSE:290-336） | 1 件だけ（RL:90-111） | ○ `@recovery`（`docs/error-recovery.md`） | P2 | 1 |
| E9 | pull 型の診断 | IJ / Ec: エディタが要求したときの検査 | `textDocument/diagnostic`、`workspace/diagnostic`、`workspace/diagnostic/refresh` | なし | なし | ◎（既存の診断を pull で返すだけ） | P2 | 1 |
| E10 | Quick Fix / Intention | IJ: Intention Actions（Alt+Enter）/ Ec: Quick Fix（Ctrl+1） | `textDocument/codeAction`、`codeAction/resolve` | 生成: なし。profile で無効化する枠だけある（LSE:501）。UBNF: `W-TOKEN-UNRESOLVED` の FQN 置換だけ（UBX:667-690; `codeActionOffersFqnQuickFix`） | なし。能力表に `CODE_ACTION` を `transport:false` で出すだけ（RL:193-213） | ○ 期待 token の挿入（例: `;` を補う）、未定義シンボルの宣言追加 | P2 | 1 |
| E11 | 整形 | IJ: Reformat Code / Ec: Format | `textDocument/formatting`・`rangeFormatting`・`rangesFormatting`（3.18）・`onTypeFormatting` | なし。profile で無効化する枠だけ（LSE:496-500）。`SourceEdits` の FORMAT は既存空白片の置換に限られ、LSP に未接続（`docs/source-preserving-edits.md`） | なし | △ 空白の方針は言語ごと。`SourceEdits` で安全な枠は作れる | P2 | 1 |
| E12 | 折り畳み | IJ: Code Folding / Ec: Folding | `textDocument/foldingRange`、`workspace/foldingRange/refresh`（3.18） | 生成: なし。UBNF: 行単位の正規表現で判定、CST は使っていない（UBX:629-660） | なし | ◎ 複数行にまたがる CST node（`@mapping` rule） | P1 | 1 |
| E13 | 選択範囲の拡大・縮小 | IJ: Extend Selection（Ctrl+W）/ Ec: Expand Selection To Enclosing Element | `textDocument/selectionRange` | なし（リポジトリ内に実装なし） | なし | ◎ CST の祖先列そのもの | P1 | 1 |
| E14 | 出現箇所のハイライト | IJ: Highlight Usages / Ec: Mark Occurrences | `textDocument/documentHighlight`（kind: Text / Read / Write） | 生成: なし（下流の手書き実装だけ: `unlaxer-dsl/docs/lsp-extensions.md:178-229`）。UBNF: なし | なし | ○ `ScopeStore.getAllReferences`（SS:295）。Write = `@declares`、Read = `@backref` | P1 | 1 |
| E15 | 連動編集 | IJ: 同時編集（タグ名など）/ Ec: Linked Mode | `textDocument/linkedEditingRange` | 生成: なし。UBNF: あり（UBX:583-598） | なし | ○ scope 情報 | P3 | 1 |
| E16 | インレイヒント | IJ: Inlay Hints / Ec: Code Minings（引数名） | `textDocument/inlayHint`、`inlayHint/resolve` | なし | なし | △（`@mapping` のフィールド名を値の前に出すだけなら ○） | P3 | 1 |
| E17 | Code Vision / Code Mining | IJ: Code Vision / Ec: Code Minings | `textDocument/codeLens`、`codeLens/resolve` | なし（下流の stub だけ: `unlaxer-dsl/docs/UBNF-EXTENSION-ROADMAP.md:356`） | なし | △ | P3 | 1 |
| E18 | 色の表示 | IJ: Color preview / Ec: なし（要確認） | `textDocument/documentColor`、`colorPresentation` | なし | なし | △ 色リテラルの token を宣言できれば | P3 | 1 |
| E19 | リンク | IJ: Hyperlinks / Ec: Hyperlink detectors | `textDocument/documentLink`、`documentLink/resolve` | なし（UBNF の import は definition で解決: `UbnfImportEditorTest#importsResolveCompletionDefinitionHoverAndReferences`） | なし | ○ import / URL の token | P3 | 1 |
| E20 | アウトライン・構造表示・パンくず | IJ: Structure / File Structure（Ctrl+F12）/ Ec: Outline / Quick Outline（Ctrl+O） | `textDocument/documentSymbol` | 生成: なし（下流の手書き実装だけ）。UBNF: あり（UBX:601-626; `documentSymbolBuildsGrammarOutline`） | なし | ○ `@declares` の宣言（SS:259）と `@mapping` の型名を kind にする | P1 | 1 |
| E21 | Surround With | IJ: Surround With（Ctrl+Alt+T）/ Ec: Surround With | `codeAction`（`refactor.rewrite`）と 3.18 の `SnippetTextEdit` | なし | なし | ○ 括弧を作る rule | P3 | 1 |
| E22 | 括弧の対応・自動で閉じる・コメント切替 | IJ: Brace matching / Comment with Line Comment / Ec: 同等 | なし（クライアントの language-configuration で実現） | `unlaxer init` の固定テンプレート（`unlaxer-dsl/src/main/resources/scaffold/vscode-extension/language-configuration.json.template`）。UBNF は手書き（`unlaxer-dsl/ubnf-vscode/language-configuration.json`） | — | ◎ literal の対（現在は固定テンプレート） | P3 | 3 |
| E23 | 対応する括弧へ移動 | IJ: Move Caret to Matching Brace / Ec: Go to Matching Bracket | なし | なし | なし | ◎ CST で対になる literal | P3 | 2 |
| E24 | 文・要素を上下に移動 | IJ: Move Statement Up/Down / Ec: Move Lines | なし | なし | なし | ◎ 反復（`{ X }` / Separated）中の CST 兄弟を入れ替える。trivia は `SourceEdits` で保持 | P2 | 2 |
| E25 | 行の結合 | IJ: Join Lines / Ec: Join Lines | なし | なし | なし | △ | P3 | 2 |
| E26 | Enter キーでの継続 | IJ: Smart Enter / コメント継続 / Ec: 同等 | 一部は `onTypeFormatting` | なし | なし | △ | P3 | 2 |
| E27 | 言語注入（埋め込み言語） | IJ: Language Injection / Ec: 汎用機能はない（要確認） | なし（VS Code はクライアント側の request forwarding か仮想文書で対応） | 生成: `LanguageQueries` で completion / hover / definition / 診断を領域の provider へ転送している（LQE:31-103, LDE:14-46）。領域の一覧はクライアントに出さない。領域の表示は Playground だけ（`docs/embedded-grammar-declarations.md` 125-143 行） | 同じく転送（RL:421-441） | ◎ `@embedded`（`docs/embedded-grammar-declarations.md`） | P1 | 2 |
| E28 | 構文木ビューア | IJ: PSI Viewer / Ec: AST View（JDT の追加ツール） | なし | LSP にはなし。Playground に CST 表示がある（`docs/ubnf-playground-ja.md:87`、`unlaxer-dsl/src/main/resources/playground/playground.js:149-160`） | LSP にはなし。`editor_cst` の `canonical_json` がある（Playground の `lib.rs:84`） | ◎ CST / AST | P1 | 2 |

## 4. 対応表: LSP（ナビゲーション・リファクタリング・検索・解析）

| # | IDE 機能 | IntelliJ / Eclipse での名前 | LSP の対応 | unlaxer Java の現状 | unlaxer Rust の現状 | 文法から自動生成 | 優先度 | 区分 |
|---|---|---|---|---|---|---|---|---|
| N1 | 定義へ移動 | IJ: Go to Declaration（Ctrl+B）/ Ec: Open Declaration（F3） | `textDocument/definition` | 生成: handler はある。広告するのは provider を登録したときだけ（LQE:13, 107-117）。hook `customDefinition`（LSE:436）を使う場合は、subclass が自分で広告する。scope から作る既定の実装はない。UBNF: あり（UBX:523-536; `definitionJumpsToRuleDeclaration`） | provider を登録したときだけ（RL:185-192） | ○ `ScopeStore.resolve`（SS:178）、`ProjectSymbolIndex` | P1 | 1 |
| N2 | 宣言へ移動（定義と区別する言語） | IJ: Go to Declaration / Ec: Open Declaration | `textDocument/declaration` | なし | なし | ○ | P3 | 1 |
| N3 | 型定義へ移動 | IJ: Go to Type Declaration / Ec: Open Type | `textDocument/typeDefinition` | なし | なし | △ `ProjectSymbolIndex` の型 ID（`docs/project-symbol-index.md:33`） | P3 | 1 |
| N4 | 実装へ移動 | IJ: Go to Implementation / Ec: Open Implementation | `textDocument/implementation` | なし | なし | × | P3 | 1 |
| N5 | 参照の検索 | IJ: Find Usages（Alt+F7）/ Ec: References（Ctrl+Shift+G） | `textDocument/references` | 生成: なし。UBNF: あり。他文書は宣言だけ（UBX:539-555; `referencesFindAllOccurrences`） | なし | ○ `ScopeStore.getAllReferences`、`ProjectSymbolIndex` | P1 | 1 |
| N6 | 呼出し階層 | IJ: Call Hierarchy / Ec: Open Call Hierarchy | `textDocument/prepareCallHierarchy`、`callHierarchy/incomingCalls`・`outgoingCalls` | なし（下流で「generated サーバー制限で実装不可」とされている: `unlaxer-dsl/docs/UBNF-EXTENSION-ROADMAP.md:375`） | なし | △ 一般には意味論。UBNF 自身では rule の参照グラフなので ◎ | P2 | 1 |
| N7 | 型階層 | IJ: Type Hierarchy（Ctrl+H）/ Ec: Open Type Hierarchy（F4） | `textDocument/prepareTypeHierarchy`、`typeHierarchy/supertypes`・`subtypes` | なし | なし | △ `ProjectSymbolIndex` の親型リスト | P3 | 1 |
| N8 | シンボル検索 | IJ: Go to Symbol / Ec: Open Type（Ctrl+Shift+T）/ Open Symbol | `workspace/symbol`、`workspaceSymbol/resolve` | なし | なし | ○ `@declares`、`ProjectSymbolIndex` | P2 | 1 |
| N9 | ライブラリ・仮想ソースを開く | IJ: ライブラリのソース・逆コンパイル表示 / Ec: Class File Editor | `workspace/textDocumentContent`、`workspace/textDocumentContent/refresh`（3.18） | UBNF: 独自 request `ubnf/packageSource`（UBX:325-328）と、クライアント側の `ubnf-package:` content provider（`unlaxer-dsl/ubnf-vscode/src/extension.ts:56-58`） | — | ◎ | P2（独自 request を標準へ移す） | 1 |
| N10 | Moniker（リポジトリをまたぐ識別子） | IJ / Ec: なし（索引ツール向け） | `textDocument/moniker` | なし | なし | ○ `Identity`（project / dependency / module / symbol: `docs/project-symbol-index.md:23`） | P3 | 1 |
| R1 | 名前の変更 | IJ: Rename（Shift+F6）/ Ec: Rename（Alt+Shift+R） | `textDocument/prepareRename`、`textDocument/rename` | 生成: なし。profile で無効化する枠だけ（LSE:495）。`ProjectRename` は LSP に未接続。UBNF: 単一文書のみ（UBX:558-579, 578）。`prepareRename` はない（`UbnfImportEditorTest#renameIsAstBoundNotTextReplacement`） | なし（`semantic_rename` は未接続） | ○ `ScopeStore`（単一文書）、`ProjectRename`（複数文書: `docs/source-preserving-edits.md`） | P1 | 1 |
| R2 | ファイルの移動・改名に伴う更新 | IJ: Move / Rename File / Ec: Move | `workspace/willRenameFiles`・`didRenameFiles` など | なし | なし | △ module ID と URI の対応 | P3 | 1 |
| R3 | 抽出・インライン化 | IJ: Extract Variable / Method、Inline / Ec: Extract Local Variable、Inline | `codeAction` の kind `refactor.extract` / `refactor.inline`、`codeAction/resolve` | なし | なし | × 意味論 | P3 | 1 |
| R4 | 対話型のリファクタリング（入力ダイアログ） | IJ: Change Signature（Ctrl+F6）/ Ec: Change Method Signature | なし（codeAction は対話入力を持たない。3.18 の `SnippetTextEdit` の placeholder で一部代用できる） | なし | なし | × | P3 | 2 |
| R5 | import の整理 | IJ: Optimize Imports / Ec: Organize Imports | `codeAction` の kind `source.organizeImports` | なし | なし | ○ `ProjectSymbolIndex` の Import、UBNF の import | P3 | 1 |
| R6 | 安全な削除 | IJ: Safe Delete / Ec: Delete（参照確認つき） | `codeAction`、`references` | なし | なし | ○ | P3 | 1 |
| R7 | 変更のプレビュー | IJ: Refactoring Preview / Ec: Refactoring wizard の preview | `WorkspaceEdit.changeAnnotations`（`needsConfirmation`）、3.18 の WorkspaceEdit metadata | なし | なし | ◎ `SourceEdits` の plan | P3 | 1 |
| R8 | 構造検索・置換 | IJ: Structural Search and Replace / Ec: 汎用機能はない（Java Search は構造パターンではない） | なし | なし | なし | ◎ パターンを同じ文法で parse して CST を照合する。置換は `SourceEdits` | P1 | 2 |
| S1 | 全文検索 | IJ: Find in Files / Ec: File Search | なし（クライアントの機能） | — | — | — | — | 3 |
| A1 | プロジェクト全体の検査 | IJ: Inspect Code / Ec: Problems（build 時） | `workspace/diagnostic` | なし | なし | ○ | P2 | 1 |
| A2 | パース失敗の説明（期待 token・最深の規則・回復・曖昧さ） | IJ: なし（ANTLR plugin の Parse tree / Profiler、Grammar-Kit の Live Preview が近い。要確認） | なし。一部は `Diagnostic.data` / `relatedInformation`、3.18 の `MarkupContent` メッセージで代用 | 生成: `Diagnostic.data` に expectedTokens / expectedHints / expectedParsers / deepestMatchedRule を出す（LSE:304-324）。これを説明する UI や request はない | data は expectedTokens だけ（RL:101） | ◎ `ParseFailureDiagnostics`、`@recovery`、`@uniqueLongestChoice` の ambiguity 診断（`docs/explicit-ambiguity-profiles.md`） | P1 | 2 |
| A3 | データフロー解析 | IJ: Analyze Data Flow To/From Here | なし | — | — | × | — | 3 |
| A4 | 依存構造の解析 | IJ: Dependency Matrix / Analyze Dependencies | なし | — | — | × | — | 3 |
| A5 | 重複コードの検出 | IJ: Locate Duplicates | なし | — | — | × | — | 3 |
| A6 | 実行構成・テストの実行 | IJ: Run gutter / Run Configurations / Ec: Run As | なし（`codeLens` と `executeCommand` で代用） | — | — | × | — | 3 |
| A7 | テストと実装の往復 | IJ: Go to Test | なし | — | — | × | — | 3 |
| A8 | AI による行補完 | IJ: Full Line Code Completion | `textDocument/inlineCompletion`（3.18） | なし | なし | × モデルに依存 | — | 3 |

## 5. 対応表: LSP の基盤（同期・交渉・コマンド）

| # | 機能 | IntelliJ / Eclipse での名前 | LSP の対応 | unlaxer Java の現状 | unlaxer Rust の現状 | 文法から自動生成 | 優先度 | 区分 |
|---|---|---|---|---|---|---|---|---|
| B1 | 増分同期 | IDE 内部（利用者に名前は見えない） | `TextDocumentSyncKind.Incremental` | Full のみ（LSE:94）。`IncrementalParseCache` を持っているが毎回全体を再解析（LSE:224-246）。UBNF は先頭の change を全文として扱う（UBX:372） | Full のみ。範囲つき edit は拒否（RL:178, 260） | ◎（runtime の増分解析しだい） | P2 | 1 |
| B2 | 位置エンコーディングの交渉 | IDE 内部（利用者に名前は見えない） | `positionEncoding`（3.17、utf-8 / utf-16 / utf-32） | 設定していない（既定の utf-16） | utf-16 固定（RL:178） | ◎ 内部の span は code point（= utf-32） | P3 | 1 |
| B3 | キャンセル | IDE 内部（利用者に名前は見えない） | `$/cancelRequest` | LSP4J が処理 | 受け取るだけで、処理中の割込みはない（RL:224、`docs/classic-rust-lsp.md`） | — | P2 | 1 |
| B4 | 進捗表示 | IDE 内部（利用者に名前は見えない） | `$/progress`、`window/workDoneProgress/create` | なし | なし | — | P3 | 1 |
| B5 | 設定の受け渡し | IDE 内部（利用者に名前は見えない） | `workspace/configuration`、`workspace/didChangeConfiguration` | 空の実装（LSE:788） | notification として無視（RL:409） | — | P3 | 1 |
| B6 | ファイル監視 | IDE 内部（利用者に名前は見えない） | `workspace/didChangeWatchedFiles` | 空の実装（LSE:791） | 無視（RL:409） | — | P2（import や project の更新） | 1 |
| B7 | コマンド実行・編集の適用 | IDE 内部（利用者に名前は見えない） | `workspace/executeCommand`、`workspace/applyEdit` | 生成: なし。profile で無効化する枠だけ（LSE:502） | なし | — | P1（区分 2 の fallback 経路として） | 1 |
| B8 | 文書を開かせる | IDE 内部（利用者に名前は見えない） | `window/showDocument` | なし | なし | — | P2（区分 2 の fallback 経路として） | 1 |
| B9 | マルチルート | IDE 内部（利用者に名前は見えない） | `workspace/workspaceFolders` など | なし | なし | — | P3 | 1 |

## 6. 対応表: DAP（デバッグ）

生成 DAP はどちらも `initialize` で `supportsConfigurationDoneRequest` しか返さない（DPE:52、RD:177）。以下で「なし」と書いた request は、Java では `IDebugProtocolServer` の既定実装、Rust では `unsupported command`（RD:351）になる。

| # | IDE 機能 | IntelliJ / Eclipse での名前 | DAP の対応 | unlaxer Java の現状 | unlaxer Rust の現状 | 文法から自動生成 | 優先度 | 区分 |
|---|---|---|---|---|---|---|---|---|
| D1 | 行ブレークポイント | IJ: Line Breakpoint / Ec: Line Breakpoint | `setBreakpoints`、`Breakpoint.verified` | 行のみ。その行に step があるか確かめずに `verified=true` を返す（DPE:133-155, 145） | 同じ（RD:211-230, 227） | ◎ step 位置で検証し、近い行へ移す | P1（検証） | 1 |
| D2 | 条件付きブレークポイント | IJ: Condition / Ec: Conditional | `SourceBreakpoint.condition`、`supportsConditionalBreakpoints` | なし（condition は読まない） | なし | △ 条件式の評価器が要る（rule 名や node 種別との一致だけなら ◎） | P2 | 1 |
| D3 | ヒット回数 | IJ: Pass count / Ec: Hit count | `hitCondition`、`supportsHitConditionalBreakpoints` | なし | なし | ◎ 回数は adapter の中で数えられる | P2 | 1 |
| D4 | ログポイント | IJ: Breakpoint の「Evaluate and log」 / Ec: Tracepoint（要確認） | `logMessage`、`supportsLogPoints` | なし | なし | ◎ `{label}` や `{text}` の補間程度 | P2 | 1 |
| D5 | メソッドブレークポイント（→ rule ブレークポイント） | IJ: Method Breakpoint / Ec: Method Breakpoint | `setFunctionBreakpoints`、`supportsFunctionBreakpoints` | なし | なし | ◎ rule 名と `@mapping` の型名 | P1 | 1 |
| D6 | 例外ブレークポイント（→ 構文エラー・回復・曖昧さで止める） | IJ: Java Exception Breakpoint / Ec: Exception Breakpoint | `setExceptionBreakpoints`、`exceptionBreakpointFilters`、`exceptionInfo`、`supportsExceptionInfoRequest` | なし。失敗時は止まらず output と terminated で終わる（DPE:83-88, DRE:13-90, DRE:558-580） | 同じ（RD:246-251） | ◎ `ParseFailureDiagnostics`・回復・ambiguity | P1 | 1 |
| D7 | ステップイン・アウト | IJ: Step Into（F7）/ Step Out（Shift+F8） / Ec: Step Into（F5）/ Step Return（F7） | `stepIn`、`stepOut` | なし。`next` だけ（DPE:160-185） | なし。`next` だけ（RD:313-329） | ◎ AST / CST の深さ | P1 | 1 |
| D8 | 呼出し先を選んでステップイン | IJ: Smart Step Into（Shift+F7）/ Ec: Step Into Selection | `stepInTargets`、`supportsStepInTargetsRequest` | なし | なし | ◎ 子 node の一覧 | P2 | 1 |
| D9 | ステップの粒度 | IJ / Ec: 文・行・命令単位のステップ | `granularity`、`supportsSteppingGranularity` | launch 時に token / ast を選ぶだけ（DPE:62-79） | 同じ（RD:183-209） | ◎ | P3 | 1 |
| D10 | カーソル位置まで実行 | IJ: Run to Cursor（Alt+F9）/ Ec: Run to Line（Ctrl+R） | クライアントが一時ブレークポイントを置いて `continue`。列の指定には `breakpointLocations` が要る | 列を無視するので行単位だけ（DPE:133-155） | 同じ（RD:211-230） | ◎ | P2 | 1 |
| D11 | 行内のブレークポイント位置 | IJ: lambda 等の行内ブレークポイント | `breakpointLocations`、`supportsBreakpointLocationsRequest` | なし | なし | ◎ その行にある step の開始位置 | P2 | 1 |
| D12 | 次に実行する位置の変更 | IJ: Jump To Line（プラグイン。要確認）/ Ec: なし（要確認） | `goto`、`gotoTargets`、`supportsGotoTargetsRequest` | なし | なし | ◎ step 番号を動かすだけ | P3 | 1 |
| D13 | フレームの巻き戻し | IJ: Reset Frame / Ec: Drop to Frame | `restartFrame`、`supportsRestartFrame` | なし。frame は常に 1 つ（DPE:263） | なし。frame は 1 つ（RD:256-270） | ◎ frame を祖先 node にすれば、その開始へ戻せる | P2 | 1 |
| D14 | 逆方向の実行 | IJ / Ec: 標準機能はない（外部の記録型デバッガ） | `stepBack`、`reverseContinue`、`supportsStepBack` | なし | なし | ◎ step 列は事前に計算済みなので、戻るのは添字を減らすだけ | P1 | 1 |
| D15 | 一時停止 | IJ: Pause / Ec: Suspend | `pause` | なし | なし | — 同期的な inspection なので意味が薄い | P3 | 1 |
| D16 | 呼出しスタック | IJ: Frames / Ec: Debug view | `stackTrace` | 現在の node の frame 1 つだけ（DPE:235-300） | 同じ（RD:256-270） | ◎ CST / AST の祖先列を frame にする | P1 | 1 |
| D17 | 変数ツリー | IJ: Variables / Ec: Variables | `scopes`、`variables`（`variablesReference>0` で入れ子） | 平坦。子の参照は常に 0（DPE:302-420, 353-388） | 平坦（RD:271-311） | ◎ `@mapping` のフィールド | P1 | 1 |
| D18 | 値の変更 | IJ: Set Value（F2）/ Ec: Change Value | `setVariable`、`setExpression` | なし | なし | × | — | 3 |
| D19 | 式の評価・ウォッチ | IJ: Evaluate Expression（Alt+F8）、Watches / Ec: Display、Expressions | `evaluate`（context: watch / repl / hover）、`supportsEvaluateForHovers` | なし | なし | △ 入力を文法で parse して CST を返すところまでは ◎。値の評価は application の hook | P2 | 1 |
| D20 | デバッグコンソールの補完 | IJ / Ec: Console の補完 | `completions`、`supportsCompletionsRequest` | なし | なし | ◎ LSP の keyword と同じもの | P3 | 1 |
| D21 | ソースの取得・読込済みソース | IJ / Ec: ディスクにないソースの表示 | `source`（`sourceReference`）、`loadedSources` | なし。program の path をそのまま渡す（DPE:273-286） | なし。範囲外と明記（`docs/classic-rust-dap.md`） | ◎ 埋め込み領域や仮想文書 | P2 | 1 |
| D22 | 再起動・終了 | IJ: Rerun / Stop / Ec: Relaunch / Terminate | `restart`、`terminate` | なし | なし | ◎ | P3 | 1 |
| D23 | 実行中の値をエディタ上に表示 | IJ: Show values inline / Ec: なし（要確認） | LSP の `textDocument/inlineValue` と DAP の値 | なし | なし | ○ 現在 node の text | P3 | 1 |
| D24 | パーサ内部の追跡（rule の試行・rollback・memo） | IJ: なし（ANTLR plugin の Profiler が近い。要確認） | なし（DAP は実行停止のモデル。`output` event で文字列を流すことはできる） | Java runtime に debug listener がある（`unlaxer-common/src/main/java/org/unlaxer/listener/DebugTransactionListener.java`、`CombinedDebugListener.java`）。DAP には未接続 | token trace は rollback を記録している（`docs/token-trace.md`）。DAP では採用された leaf だけを辿る | ◎ | P2 | 2 |
| D25 | プロセスへの attach | IJ: Attach to Process / Ec: Remote Java Application | `attach` | なし | なし | × 実行中の evaluator は application が持つ | — | 3 |
| D26 | Hot code replace | IJ: HotSwap / Ec: Hot Code Replace | なし（java-debug の独自拡張。要確認） | — | — | × | — | 3 |
| D27 | メモリ・ヒープの表示 | IJ: Memory View / Ec: All Instances | `readMemory` / `writeMemory`（生のメモリだけ。ヒープ上のオブジェクトはない） | — | — | × | — | 3 |
| D28 | 逆アセンブル・命令ブレークポイント | IJ: Bytecode viewer / Ec: Disassembly（CDT） | `disassemble`、`setInstructionBreakpoints` | — | — | × | — | 3 |
| D29 | データブレークポイント | IJ: Field Watchpoint / Ec: Watchpoint | `dataBreakpointInfo`、`setDataBreakpoints`、`supportsDataBreakpoints` | なし | なし | × 監視する状態は evaluator が持つ | — | 3 |
| D30 | 子デバッグセッション（埋め込み言語ごと） | IJ / Ec: 複数のデバッガを同時に使う | `startDebugging`（reverse request） | なし | なし | × 子の言語のデバッガが要る | — | 3 |

## 7. 区分 1: 標準にあるのに未実装（拡張なしで埋められる）

全部で **68 件**（E 20 件、N / R / A 17 件、B 9 件、D 22 件）。3〜6 節の各表で「区分」列が `1` の行を数えた。うち **P1 は 19 件**で、内訳は次のとおり（自動生成しやすい順）。

| 根拠となる文法情報 | 項目 |
|---|---|
| ◎ CST だけ | E13 selectionRange、E12 foldingRange、E1 semanticTokens（実データ）、E2 文脈依存の補完、E5 カーソル位置の hover（`@doc`） |
| ○ scope 注釈（`@declares` / `@backref` / `@scopeTree`） | E7 scope 診断、E14 documentHighlight、E20 documentSymbol、N1 definition（既定実装）、N5 references、R1 rename（単一文書から） |
| ◎ DAP の step 列 / AST | D1 ブレークポイントの検証、D5 rule ブレークポイント、D6 構文エラーで停止、D7 stepIn / stepOut、D14 stepBack / reverseContinue、D16 祖先 frame、D17 入れ子の変数 |
| 基盤 | B7 executeCommand / applyEdit（区分 2 の fallback に使う） |

補足:

- E1・E5・E12 は、UBNF 言語サーバで手書きしたもの（UBX）を、生成器が文法から出せるようにする作業である。UBNF 言語サーバも、生成物ができれば手書き部分を減らせる。
- N1・N5・R1・E14・E20 は、ScopeStore が parse 中に集めた情報を捨てずに LSP へ渡すだけでよい（LSE:167-168, 194）。複数文書へ広げるときは `ProjectSymbolIndex` / `ProjectRename` を接続する（P2）。
- D7・D14・D16・D17 は、どちらの DAP も parse 後の step 列を持っているので（DRE:13-90、RD:313-343）、実行系がなくても実装できる。inspection 型の DAP だから安く作れる機能である。

## 8. 区分 2: 標準にない（`unlaxer/...` 拡張の候補）

全部で **10 件**: E23 対応括弧へ移動、E24 要素の上下移動、E25 行の結合、E26 Enter での継続、E27 言語注入、E28 構文木ビューア、R4 対話型リファクタリング、R8 構造検索・置換、A2 パース失敗の説明、D24 パーサ内部の追跡。
E25・E26・R4 は意味論の比重が大きいので P3 にした。

### 上位 5 候補

拡張は `experimental` の capability（例: `experimental.unlaxer.syntaxTree: true`）で広告する。メソッド名は `unlaxer/...` とする。拡張を知らないクライアントにも、標準の経路（`workspace/executeCommand` → `workspace/applyEdit` / `WorkspaceEdit`、`window/showDocument`、3.18 の `workspace/textDocumentContent`、`Diagnostic` / `hover` への埋め込み）で劣化した機能を届ける。

| 順位 | 候補（仮のメソッド名） | 理由（1 行） | 生成元の文法情報 | 前例 | 拡張を知らないクライアントでの fallback |
|---|---|---|---|---|---|
| 1 | 構文木ビューア `unlaxer/syntaxTree`（E28） | 文法作者がいちばん欲しい「なぜこの木になったか」を、追加情報なしで CST から直接出せる | CST（Java の Token 木、Rust の `editor_cst` の `canonical_json`）、`@mapping` の AST、recovery / missing の defect | rust-analyzer `rust-analyzer/viewSyntaxTree`（JSON）、clangd `textDocument/ast`（`astProvider`）、IntelliJ PSI Viewer、Eclipse AST View | `workspace/executeCommand`（`unlaxer.syntaxTree`）で、木を `unlaxer-syntax:` の仮想 URI に置き、`window/showDocument` で開かせる（3.18 対応クライアントは `workspace/textDocumentContent` で中身を取る）。最低限は標準の `selectionRange` / `documentSymbol` が木の一部を見せる |
| 2 | パース失敗の説明 `unlaxer/explainParse`（A2） | 期待 token・最深の規則・回復した範囲・曖昧さを、runtime はもう計算しているのに、表示が 1 行のメッセージに潰れている | `ParseFailureDiagnostics`（expectedTokens / Hints / Parsers / deepestMatchedRule: LSE:186-196）、`@recovery`、`@uniqueLongestChoice` の `ambiguity` / `empty_choice` | rust-analyzer `rust-analyzer/getFailedObligations`（失敗理由をデバッグ用に返す）、clangd の `Diagnostic.category` / `Diagnostic.codeActions`、IDE では ANTLR plugin の Profiler（要確認） | 構造化した情報は今どおり `Diagnostic.data` に入れる。人が読む説明は `relatedInformation`（候補ごとの位置）と、3.18 の `MarkupContent` メッセージ（`markupMessageSupport` があるとき）に出す。それもなければ、エラー位置の `hover` に説明を出す |
| 3 | 構造検索・置換 `unlaxer/ssr`（R8） | パターンを同じ文法で parse すれば、言語ごとに手書きしなくても構造で照合できる。テキスト置換と違って trivia も保てる | 文法の rule（パターン内の `$x` を任意の rule に対応させる）、CST、`SourceEdits`（trivia 保持・衝突検査）、`ProjectRename` と同じ snapshot 検査 | rust-analyzer `experimental/ssr`、IntelliJ Structural Search and Replace | `workspace/executeCommand`（`unlaxer.ssr`、引数はパターン）で、サーバが `workspace/applyEdit` を送る。検索だけなら Location の配列を返し、置換は `codeAction`（「一致をすべて置換」）の `WorkspaceEdit` として出す |
| 4 | 言語注入の領域 `unlaxer/languageRegions`（E27） | `@embedded` の領域は生成 parser が確定しているのに、どこが何の言語かをクライアントは知らない | `@embedded` の宣言、領域の language identity、状態（COMPLETE / PARTIAL / FAILED / UNAVAILABLE / UNSUPPORTED / TIMEOUT）、source map | clangd `textDocument/inactiveRegions`（サーバから領域一覧を通知）、IntelliJ Language Injection、VS Code の embedded language（request forwarding）の手法 | 補完・hover・定義・診断は、今もサーバの中で転送しているので（LQE:31-103, LDE:14-46）拡張がなくても動く。加えて、領域を `foldingRange` と `documentSymbol` の node として出し、子の言語の token を `semanticTokens` に混ぜる |
| 5 | 要素の上下移動 `unlaxer/moveItem`（E24） | 反復（`{ X }`、Separated）の中の兄弟の入替えは文法から機械的に決まり、区切り文字と trivia の扱いも生成器が知っている | 反復と区切りの文法構造、CST の兄弟、`SourceEdits` | rust-analyzer `experimental/moveItem`、IntelliJ Move Statement Up/Down | `codeAction`（kind `refactor.rewrite`、「上へ移動」「下へ移動」）として `WorkspaceEdit` を返す |

候補にしなかったもの:

- D24 パーサ内部の追跡は、DAP の `output` event と、上の 1・2 で多くを代用できる。単独の拡張は後回しにする（P2）。
- E23 対応括弧へ移動は、クライアントの language-configuration で大半を満たせる（P3）。
- 補足: inlay hints と型階層は、LSP 3.17 で `textDocument/inlayHint` と `textDocument/prepareTypeHierarchy` が標準になる前、clangd が独自拡張（`clangd/inlayHints`、型階層の提案）で提供していた。clangd の拡張一覧には「標準化されたら deprecate する」とある。rust-analyzer にも同様の経緯があったと思われる（要確認）。独自拡張は、後で標準に置き換えられる前提で名前空間と capability を分けておく。

## 9. 区分 3: 対象外（生成器の仕事ではない）

全部で **15 件**。

| # | 機能 | 理由 |
|---|---|---|
| E22 | 括弧の対応・自動で閉じる・コメント切替 | プロトコルの外で、クライアントの宣言的な設定（language-configuration / TextMate）で実現する。LSP / DAP 生成器の範囲ではない（`unlaxer init` の scaffold 生成の改善としては扱える） |
| S1 | 全文検索 | クライアントの機能 |
| A3 | データフロー解析 | 言語の意味論（値の流れ）に依存し、文法から導けない |
| A4 | 依存構造の解析 | ビルド / プロジェクトのモデルが要る。BSP（Build Server Protocol）か、ビルドツールの担当 |
| A5 | 重複コードの検出 | 索引ツールの仕事。意味的な同一視の基準が言語ごと |
| A6 | 実行構成・テストの実行 | ビルドとテストのモデル。BSP の担当（rust-analyzer も `experimental/runnables` で Cargo に依存している） |
| A7 | テストと実装の往復 | 命名規約とプロジェクト構成に依存する |
| A8 | AI による行補完 | 補完モデルに依存する。標準（`textDocument/inlineCompletion`）はあるが、文法から生成するものではない |
| D18 | 値の変更 | 生成 DAP は解析後の inspection で、変更する実行状態がない。実行状態は application の evaluator が持つ |
| D25 | attach | 実行中の evaluator は application が持つ。生成器はプロセスモデルを持たない |
| D26 | Hot code replace | 実行系（JVM 等）の意味論に依存する。DAP の標準にもない |
| D27 | メモリ・ヒープの表示 | 実行系のメモリモデルに依存する |
| D28 | 逆アセンブル・命令ブレークポイント | 命令列を持つ実行系の機能。parser にはない |
| D29 | データブレークポイント | 監視する状態は evaluator が持つ。AST inspection には「書き込み」がない |
| D30 | 子デバッグセッション | 埋め込まれた子の言語ごとのデバッガが要る。生成器の範囲を超える |

## 10. 件数

| 区分 | 件数 | 内訳 |
|---|---|---|
| 1 | 68 | E1-E5, E7-E21（20 件）／ N1-N10, R1-R3, R5-R7, A1（17 件）／ B1-B9（9 件）／ D1-D17, D19-D23（22 件）。うち P1 は 19 件 |
| 2 | 10 | E23-E28, R4, R8, A2, D24。うち P1 は E27, E28, R8, A2 の 4 件 |
| 3 | 15 | E22, S1, A3-A8, D18, D25-D30 |
| 済 | 1 | E6 |


## 11. 要確認

確かめきれなかったので、拡張を設計する前に一次資料で確認する。

1. Eclipse に、汎用の言語注入機能があるか（E27）。
2. Eclipse に、色の表示（`documentColor` 相当）があるか（E18）。
3. Eclipse の「Tracepoint」の名前と、ログポイント相当の機能の有無（D4）。
4. IntelliJ の「Jump To Line」が標準機能かプラグインか。Eclipse の同等機能の有無（D12）。
5. Eclipse に、実行中の値をエディタ上に表示する機能があるか（D23）。
6. ANTLR v4 IntelliJ plugin の Parse tree / Profiler、Grammar-Kit の Live Preview の正確な機能名と範囲（A2, D24）。
7. java-debug（vscode-java-debug）の hot code replace がどの独自 request / event で実装されているか（D26）。
8. jdtls の独自拡張の一覧。今回は根拠に使っていない。前例としては、仮想ソースを返す `java/classFileContents`（N9 の `ubnf/packageSource` と同じ型）を想定しているが、名前と現在の扱いは未確認。
9. LSP 3.18 の `workspace/textDocumentContent` に対応しているクライアント（VS Code の版）。区分 2 の fallback で頼る前に確認する。
10. rust-analyzer が LSP 3.17 より前に inlay hints を独自拡張（`experimental/inlayHints` など）で出していたか（8 節の補足）。
11. rust-analyzer の構文木 request の旧名（`rust-analyzer/syntaxTree`）と現在の `rust-analyzer/viewSyntaxTree` の関係。現行の拡張一覧で確認できたのは `rust-analyzer/viewSyntaxTree` だけ。

## 12. 関連文書

- [Classic Rust LSP](classic-rust-lsp.md)、[Classic AST / token DAP](classic-rust-dap.md)、[token trace](token-trace.md)
- [埋め込み文法宣言](embedded-grammar-declarations.md)、[言語 query の転送](language-query-forwarding.md)、[外部解析 provider](external-language-providers.md)
- [文書をまたぐシンボル解決](project-symbol-index.md)、[原文を保つ編集と rename](source-preserving-edits.md)、[意味解析 query](semantic-queries.md)
- [エラー回復](error-recovery.md)、[明示的な曖昧性 profile](explicit-ambiguity-profiles.md)、[生成 parser の scope 注釈](generated-scope-effects.md)
- 外部: [LSP 3.18 specification](https://microsoft.github.io/language-server-protocol/specifications/lsp/3.18/specification/)、[DAP specification](https://microsoft.github.io/debug-adapter-protocol/specification)、[rust-analyzer LSP extensions](https://github.com/rust-lang/rust-analyzer/blob/master/docs/book/src/contributing/lsp-extensions.md)、[clangd extensions](https://clangd.llvm.org/extensions)
