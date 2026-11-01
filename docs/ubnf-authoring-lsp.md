# UBNF authoring LSP の Java / Rust 対応

## syntax failure の固定棚卸し（#894）

Java 参照は `d8af6d9be6ad45cdad5eddf0a040cd5ce80d0602`。
生成 server の root は generated/UBNFParsers の shim から bootstrap/UBNFParsers へ委譲する。
LSPServerEmitter.emitParseDocument の doParse / publishDiagnostics / parseErrorMessage、
ParseContext.getParseFailureDiagnostics / getUnconsumedInputRuleStack と UbnfEditorIndex を棚卸しした。
UBNFMapper.parseWithSource は全入力と mapper 値検査も要求するため、
その最初の例外を LSP の syntax failure と同一視しない。

| 欄 | 固定 Java の情報源・変換 | native frontend の現状 / 今回の扱い |
|---|---|---|
| code / severity / source | ULX-PARSE-001 / Error / unlaxer | 既存値は同じ。これだけで完全一致とはしない |
| schemaVersion / kind / data.code | publishDiagnostics が 1 / syntax / ULX-PARSE-001 を組立てる | 根のkeyword失敗は同じdataを組立てる。深い失敗はnative Diagnosticにdataがなく#1024 |
| offset / range / line / character | failed frame の最遠 CP、部分成功なら consumed との最大。UTF-16 へ変換し、range は1 CP、CRLFを分断しない | native span は最初のエラーの byte/CP 範囲。根のkeyword失敗は専用経路で一致。深い失敗を最遠へ位置置換せず#1024 |
| consumedLength / totalLength | root の成功 token 全文の UTF-16 長。root 失敗は0、複数 grammar の部分成功は成功 prefix。total は String.length | native は全入力成功か最初のエラーのみ。LSP失敗時だけ完了 grammar prefix を再認識し hover の消費長を得る。根のsyntax dataは同じ。深い失敗のdataは#1024 |
| expectedTokens / expectedHints / expectedParsers | 最遠で失敗した各 frame の子を深さ2まで展開し TerminalSymbol の hint を収集。innermost terminal も追加。各配列を distinct/sorted/limit(20) | 予測型再帰下降の Diagnostic.message は単一の期待で、代替候補・試行失敗を保持しない。文字列からの推測はしない。後続 |
| deepestMatchedRule | trial recording がなければ最遠失敗 stack の最深 parser class 名 | native は当該 stack を持たない。ubnf.ubnf の Annotation / RuleBody / GrammarDecl 等の rule に対応づける失敗専用 recognizer が必要。後続 |
| ruleStack / ruleStackOmitted / relatedInformation | GrammarRule の失敗で最遠に達した stack（frame数最大、同数は先着）。成功 prefix の残入力 stack は別 API。offset一致時だけ公開し frame 開始を CP→UTF16 変換 | bootstrap UBNF parser は GrammarRule 非実装のため参照の通常syntaxでは欄自体が不在。存在しない stack を空配列として捏造しない。native 一般 runtime の stack はこの frontend とは別 |
| fallback hover | Ext は base DocumentState の consumedLength を表示。editor snapshot の成功/失敗とは別 | 最初の frontend error offset を表示していた。失敗時の prefix 再認識へ分離 |
| module/package | 個別 import の失敗は宣言 range で順に集約。URI変換/最終link失敗は文書先頭で説明prefix付き。既存 resolver が lock/hash/依存/境界を検証 | 個別解決は既存 Resolver を継続使用。最終link/URIのLSP envelopeを合わせる。import先のsyntax本文は上記provenanceと一緒に後続 |

rule の写像は `UBNFFileParser → UBNFFile`、`GrammarDeclParser → GrammarDecl`、
`AnnotationParser → Annotation`、`RuleBodyParser → RuleBody` として ubnf.ubnf に合わせる。
根のkeyword失敗の最深 terminal は `WordParser` で、これは Java が data に返す class 名を保つ。
ただし grammar を1つも消費せず、入力全体が trivia で末尾が改行のない `//` コメントの場合は、
CPPComment の LineTerminator が EOF で失敗するため、最深は `WildCardCharacterParser`、期待集合は改行3種を含む7個になる。
syntax 診断の range と data.line/character は DocumentSnapshot の規則（CR・LF・CRLF をそれぞれ1改行）で数え、
LF だけを数える module 回復 range とは別に扱う。

この棚卸しはsyntax全JSON一致の達成を意味しない。期待集合と失敗stackの再現には、
Java combinator の投機的失敗も再現する専用recognizerが必要で、通常frontendへの計測追加は
#197 の凍結と正常系コスト維持に反するため採用しない。

追跡: #864、親 #111。Java 参照 revision は `465b434eb7b1321bc5450600401869fde7a18e13`（#888 / #896 merge 後）。第一段の基準は `b96a9c07`。
生成した言語の LSP と、ここで扱う `.ubnf` 文法作者向け LSP は別のサーバーである。
Java の既存経路は維持し、Rust は対応した capability だけを広告する。

## コードとテストから固定したインターフェース

参照実装: `unlaxer-dsl/ubnf-vscode/src/main/java/org/unlaxer/dsl/lsp/ubnf/UBNFLanguageServerExt.java`、
`UbnfEditorIndex.java`、継承元を出力する `LSPServerEmitter.java` / `LSPQueryEmitter.java`。
参照テスト: 同 module の `UBNFLanguageServerExtTest`、`UbnfImportEditorTest`、
`UBNFLanguageServerTest` および `scripts/vsix-suite.cjs`。

| Java capability / wire method | Java の実際の振る舞い | Rust 現在の対応・共通検証 |
|---|---|---|
| `initialize`, `initialized`, `shutdown`, `exit` | Full sync、UTF-16、初期化前/終了後 request を拒否 | 実 stdio 共通 fixture |
| `textDocument/didOpen`, `didChange`, `didClose`, `didSave` | open buffer を索引化し import 元を更新。Full change、close 後は disk に戻る | 実 stdio 共通 fixture |
| `$/setTrace`, `$/cancelRequest`, workspace configuration / watched files | 標準通知（基準 server の no-op を含む） | no-op、同期処理 |
| `textDocument/publishDiagnostics` | parse `ULX-PARSE-001`、GrammarValidator、GenerationWarnings、module error | syntax / generation warnings / module error、validatorの接続済み範囲は後述。全 validator と syntax expected/stack は未完了 |
| `hoverProvider`: `textDocument/hover` | catalog/annotation、宣言・由来・文書内参照数、parse status | catalog/annotation、ローカル/直接 import token の hover、共通 fixture |
| `completionProvider`: `textDocument/completion` | resolve=false、trigger `@`, `=`, `.`。annotation/FQN/snippet、lexical/whitespace/scope別候補と textEdit | 同じ data と scope/直接 import 候補、共通 fixture |
| `definitionProvider`: `textDocument/definition` | AST の binding、外部 token / package URI | #886 第一単位、共通実 stdio / packaged VSIX |
| `referencesProvider`: `textDocument/references` | 文書内参照と includeDeclaration、外部宣言 | #886 第一単位、共通実 stdio / packaged VSIX |
| `renameProvider`: `textDocument/rename` | 完全文法の非公開ローカル binding のみ。重複/予約語等を拒否 | #886 第一単位、共通実 stdio / packaged VSIX |
| `documentSymbolProvider`: `textDocument/documentSymbol` | grammar に token/rule を入れ子表示、不完全入力の回復 | #886 第一単位、共通実 stdio / packaged VSIX |
| `foldingRangeProvider`: `textDocument/foldingRange` | grammar block と複数行 rule | #886 第一単位、共通実 stdio / packaged VSIX |
| `linkedEditingRangeProvider`: `textDocument/linkedEditingRange` | rename と同じ安全な binding の ranges | #886 第一単位、共通実 stdio / packaged VSIX |
| `codeActionProvider`: `textDocument/codeAction` | `W-TOKEN-UNRESOLVED` の FQN quick fix | 未対応 #901 |
| `signatureHelpProvider`: `textDocument/signatureHelp` | trigger `(`, `,`、annotation signature | 未対応 #901 |
| `semanticTokensProvider`: `textDocument/semanticTokens/full` | full=true、10 token types、modifier declaration。不完全入力でも動く | 未対応 #901（range/delta は Java も広告しない） |
| `ubnf/packageSource` | 検証済み index にある package source だけを返す | #886 第一単位、元位置/hash/cache寿命/任意URI拒否と packaged VSIX |
| `unlaxer/configure`（継承） | profile / client capability を再設定し cache 無効化 | #895：同じ options と experimental metadata、成功/失敗・全置換・文書保持を共通62件で検証 |

将来機能の formatting、workspace 全体索引、completion resolve、semantic delta は今回の parity 条件に混ぜない。
未知 request は `-32601 Unsupported request method: <method>`、lsp4j 宣言済み未実装 request は `-32603 Internal error.`（#994）。全診断/profile は [#885](https://github.com/opaopa6969/unlaxer-parser/issues/885)、
navigation/編集支援/packageSource/native 配布の親は [#886](https://github.com/opaopa6969/unlaxer-parser/issues/886)。
AST navigation の第一単位を追加し、残る編集支援は [#901](https://github.com/opaopa6969/unlaxer-parser/issues/901)、
入力域/platform/起動失敗は [#902](https://github.com/opaopa6969/unlaxer-parser/issues/902) で継続する。
両子 issue の merge と #864 の全受け入れ条件確認まで、第一段の成功を #864 全体の完了とはしない。

## 実行と境界

`cd rust && RUSTUP_TOOLCHAIN=1.85.0 cargo build -p unlaxer-ubnf-lsp --release` で
`target/release/ubnf-language-server` を作る。stdin/stdout は既存 `unlaxer-lsp` の framed JSON-RPC を使い、JVM を起動しない。
VSIX は `ubnfLsp.server.backend=rust` と `ubnfLsp.server.rustPath`（実行ファイルの絶対パス）で選択する。
既定は Java。変更後は Reload Window。native executable の配布/同梱方針は後述。実際の同梱とクロスプラットフォーム検証は未完了 #902。
Playground の生成は従来どおり Java/Node/Rust toolchain を使う。

import は既存 `modules::resolve` / `packages::Resolver` を通す。package の lock/hash/依存/境界を検証し、
自動取得・resolve・書込み・コード実行はしない。local import の disk reader は Java/Rust とも
symlink の解決先が通常ファイルの場合だけ読み、1ファイル64 MiBまでに制限する（[#890](https://github.com/opaopa6969/unlaxer-parser/issues/890)、
[読取り契約](ubnf-packages.md#local-import-の読取り境界890)）。開いたローカル `.ubnf` buffer は disk より優先し、
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
BMP 内の Unicode 分類は `char::is_alphanumeric` による近似で、結合文字（Mn/Mc）と Nl/No は未照合（#902、親 #886）。
同じ version 以下の `didChange` も Java と同じく適用する（#885 の診断76文法で検証）。

構文エラー時の fallback hover は Java の consumed offset と Rust の最初の error offset を区別する（#885）。
構文エラーは両方の拒否を確認するが、native frontend の最初のエラー位置と
Java の farthest failure / expected / rule stack の同値性は未達成（#885）。
重複ルール宣言の fixture は補完候補の抑制と、両validatorが診断しないことを全JSONで比較する。
module error は共通 fixture の code/severity/source/range を比較し、resolver の説明文は別契約として残す。

ローカル module の未保存変更、change/close/save 後の再通知、percent encoded URI、
package の固定 hash/由来、hash 不一致・lock 欠落・package 境界逸脱の拒否を検証する。
virtual package path に開いた偽 buffer が検証済み source を置換できないこと、
server が manifest/lock/cache を書き換えないことも両版で検証する。
現時点の native file URI は Linux のローカル絶対パスを検証対象とし、UNC/Windows 等は #902（親 #886）に残す。

CI は `ubnf-authoring` 必須契約の12メソッドと、基準135件・navigation39件・local import 17件・package metadata 27件・artifact 39件・artifact UTF-8 29件・JSON-RPC 558件の実行件数を別々に検査する。
packaged VSIX は従来 Java smoke を保持し、別の隔離 profile で Java executable を不存在に設定して
Rust の completion/hover/診断と変更後の修復、および両 backend の definition/references・rename・documentSymbol・foldingRange・linkedEditingRange・readonly package source を確認する。最後に拡張の `deactivate` で
native server の子プロセスが終了し、残らないことを確認する（tinycalc-vscode #874/#880 と同じ後始末の水準）。
native executable 自体は外部パスを指定し、VSIX 内に同梱したとは扱わない。

## 検証コマンドと mutation

- `cd rust && RUSTUP_TOOLCHAIN=1.85.0 cargo fmt --all --check && cargo clippy --workspace --all-targets -- -D warnings && cargo test --workspace`
- 隔離 Maven repository と JDK21 で `-pl unlaxer-dsl/ubnf-vscode -am test -Dtest=UbnfAuthoringProtocolConformanceTest,UBNFLanguageServerExtTest,UbnfImportEditorTest,UBNFLanguageServerTest,GrammarValidatorTest,RustUbnfFrontendConformanceTest,PackageResolverConformanceTest,GenerationWarningsConformanceTest -DrustConformance=true -Dsurefire.failIfNoSpecifiedTests=false`。
- `python3 scripts/verify-optin-tests.py ubnf-authoring`、`python3 -m pytest -q scripts/tests`、両 parity checker、`actionlint .github/workflows/maven.yml`。
- VSIX 作成後 `node scripts/vsix-smoke.mjs target/ubnf-lsp-authoring.vsix` を Java と `UBNF_TEST_BACKEND=rust UBNF_TEST_RUST_PATH=<絶対パス>` の2経路で実行。headless 環境は `xvfb-run -a`、Chrome/Electron は `--password-store=basic`。

UTF-16 を CP 数に変える、local buffer overlay を外す、generation warning を空にする3変異で、
それぞれ共通 stdio fixture が失敗することを確認した。PR #888 のレビューで、位置の丸めを外す、置換範囲を ASCII に戻す、
修飾子を最初の区切りに戻す、hover の単語に補助文字を含める、importer の再通知を外す、VSIX suite の `deactivate` を外す
6変異でも、それぞれ共通 fixture・Rust テスト・packaged VSIX が失敗することを確認した。protocol テストの `@Test` を外すと、
残ったテスト自体が成功しても必須メソッド検査が失敗する。全変異を復元して再検証する。

## #885: validator 診断の接続（全条件の完了ではない）

Java 基準は PR #888 merge 後の `d8af6d9be6ad45cdad5eddf0a040cd5ce80d0602`。
`GrammarValidator.validate` の全 1624 行と `detectLeftRecursionIssues`、呼出先を調べた上で、
構文成功後の診断収集を `unlaxer_generator::validation::collect` に接続した。
既存 lowering は root/schema を前提とする fail-fast API なので、LSP の全診断収集には直接使わない。
lexical / adapter / token-contract / feature / token-stream / comment / lexical-context / embedding /
raising の解析は既存実装を再利用し、Java の authoring message/hint の組立てを分離する。
通常 parser、frontend の受理条件、Java validator、公開済み3.3.0は変更しない。

`spec-corpus/ubnf-authoring-lsp/diagnostics.json` は76文法の手書き全 JSON oracle。
従来27件と合わせ103件を両実 stdio で実行する。両 server を比較するだけでなく、各結果を独立期待値へ照合する。
`code` / `severity` / `source` / `message` / `range` とキーの有無・診断配列の順序を検査し、診断や位置のフィルタ・正規化はしない。
Java validator 診断には `data` と `relatedInformation` が**存在しない**ため、Rust も省略し、全 JSON 検査でこれも固定する。
これは `ULX-PARSE-001` の data を省略してよいという意味ではない（#894）。

range は rule 所属がある場合、Java の文書全体の最初の同名宣言（grammar 名も含む）を指す。
宣言は Java `UbnfEditorIndex` と同じく各 grammar の名前・token・rule の AST node の範囲内だけで探し、
rule 本体に書かれた `token R` や `grammar R` を宣言と取り違えない。
一般診断は `0:0–0:1`。`W-TOKEN-UNRESOLVED` は token の宣言行にある parserClass detail を指す。
detail は Java `tokenDetail` の正規表現と `strip()` に従い、名前と `=` の間にコメントがあれば空文字列（宣言行の `n:0–n:0`）になる。
Rust の byte span を UTF-16 に変換し、CRLF と非BMPの後、複数 grammar の同名宣言も固定した。
生成警告→各 grammar の validate→advisory 左再帰という Java の順序を保持する。
同じ・小さい version の Full `didChange` も Java に従って適用し、全76文法で診断の置換・修復と
別文書の open/close、save、close 後の query、session の shutdown/exit を検査する。

### validate の呼出順と Rust 利用経路

以下は全呼出先の棚卸しであり、全行の完全一致宣言ではない。`接続` は実装済み、
`残差` は受け入れ条件付き #893 で未完了。個別入力の完全一致は上の corpus の範囲に限る。
`W-` の severity は Warning (=2)、その他（`P-` を含む）は Error (=1)。source は `ubnf-validator`。
message は Java の `message + " — " + hint`。hint を捨てたり、CLI の短い message に置換したりしない。

| 順序・Java 検査 | code / Rust 利用経路 | 状態 |
|---|---|---|
| RegexRetirement | `E-REGEX-RETIRED` / `validation::collect` | AST 接続。旧構文の frontend 拒否との合成は未検証 #893 |
| LeftRecursionAnalysis.requireValid | `E-LEFT-RECURSION`, `E-LEFT-RECURSION-LIMIT` / `validation::recursion` | 接続。lowering は全 cycle を公開しないため source-level query を移植。token 優先、nullable、256深さ/1M work を維持 |
| NestedCaptures.problems | `E-NESTED-SHAPE` / 既存 `nested_captures::validate` は fail-fast | 残差。rule ごとの収集、共有 schema、hint/所属が未接続 #893 |
| validateScalarRanges / validateCaseInsensitiveTokens | `E-TOKEN-RANGE-SURROGATE`, `E-CI-EMPTY`, `E-CI-SCALAR` | 範囲/empty は接続。不対 surrogate を表せない Rust AST 入力の照合は残差 #893 |
| LexicalCompiler.compile | `E-LEXICAL` / `lexical::compile_syntax` | 接続。Java message の `E-LEXICAL:` prefix を付ける。duplicate/undefined/cycle/version/SAME_AS を比較 |
| TokenAdapterRegistry.build | `P-ADAPTER-{DEFINITION,DUPLICATE,UNKNOWN,VERSION}` / `adapters::AdapterRegistry::from_grammar` | 接続。宣言検査に加え Java と同じく各 `ADAPTER(...)` token 参照も検査し、span なしの Java と同じ code→subject（UTF-16順）で整列。空白だけの `context`・NBSP は #897 で Java の isBlank/trim に適合 |
| TokenContractRegistry.build | `P-TOKEN-CONTRACT-{VERSION,DEFINITION,DUPLICATE}` / `adapters::token_contract_diagnostics` | 接続。span なしの Java と同じ code 順に整列し、重複した `token` key は最初の値を subject にする（#897 で生成器・check と共有し、authoring 専用補正を削除）。空白だけの `context`・NBSP は #897 で Java の isBlank/trim に適合 |
| UBNFFeatures.validate | `E-FEATURE-{VERSION,UNKNOWN,DUPLICATE}` / `adapters::feature_diagnostics` | 接続。VERSION/UNKNOWN subject は `feature`、非v2を最優先する。#910 で生成器との共有関数へ統一し、authoring 専用補正を削除。check の inventory は Java と同じく feature を意味検査せず、構造検査で報告する |
| TokenStreamGrammar.problems | `E-TOKEN-STREAM-{SETTING,VERSION,TRIVIA,TOKEN,ANNOTATION}` / `token_stream::problems` | 接続 |
| GlobalComment.problems / warnings | `E-COMMENT-{SETTING,WHITESPACE}` / `global_comment::problems` | 接続。`W-COMMENT-IGNORED` は Java LSP と同じく GenerationWarnings だけから出す |
| LexicalContexts.requireValid | `E-LEXICAL-CONTEXT[-VERSION,-DUPLICATE,-TOKEN,-LITERAL,-LIMIT]` / `lexical_contexts::validate` | 接続。既存 lexical error を合成した message の全入力域は #893 |
| NamePredicates.requireValid | `E-NAME-PREDICATE-{DUPLICATE,IDENTITY,KIND,CONFLICT,CAPTURE,VERSION,LIMIT,LEFT-RECURSION}` / 既存 lowering の capture shape 検査 | 残差。独立した collect 経路なし #893 |
| EmbeddedGrammarEmitter.declarations | `E-EMBEDDING` / `embedded::validate` | 接続 |
| RaisingOperators.problems | `E-COMMIT-EMPTY-MESSAGE`, `E-CATCH-{EMPTY,DUPLICATE-LABEL,UNKNOWN-LABEL}`, `E-RAISING-REPETITION` / `raising::problems` | 接続。Java LSP は message に CP suffix を含めない。Rust CLI 用 `Problem::message` を流用せず code/rule/subject から組み立てる |
| validateGlobalWhitespace | `E-WHITESPACE-GLOBAL-{DUPLICATE,STYLE}` / `validation::globals` + 既存 lexical compiler | 接続 |
| validateUbnfFormat / validateMemoSafeTokens | `E-UBNF-VERSION[-DUPLICATE]`, `E-MEMO-SAFE-TOKEN-{VALUE,DUPLICATE,UNDEFINED,KIND}` / `validation::globals` + builtin registry | 接続 |
| validateRootPresence | `W-GENERAL-NO-ROOT` / `validation::globals` | 接続。Java LSP の declarative token library 例外を保持 |
| validateTokens | `W-TOKEN-UNRESOLVED` / `validation::tokens` | bundled候補は接続。Java の同梱5 packageのclass一覧を必須Javaテストで照合。追加classpath候補は #893 |
| validateJavaTokenBindings | `E-JAVA-TOKEN-FINAL` / 対応経路なし | 残差。任意class resolution/final/nested canonical名 #893 |
| validateRuleTokenParserNameCollisions / validateAdapterTokenParserNameCollisions | `E-RULE-TOKEN-NAME-COLLISION`, `E-TOKEN-ADAPTER-NAME-COLLISION` / `validation::tokens` | 接続 |
| validateBoundedRepeatElements | `E-BOUNDED-{OVERFLOW,INVALID}` / `validation::rules::bounded` | 接続。dead zero-count内部も走査 |
| validateCommonFields | `E-COMMONFIELD-MISMATCH` / 既存 `common_fields::families` | 残差。schema exceptionのruleと全message接続 #893 |
| validateEnumRules | `E-ENUM` / 既存 lowering enum validator | 残差。Javaの検査順・literal名・root projection・共有schemaが未接続 #893 |
| rule whitespace / mapping | `E-WHITESPACE-RULE-{STYLE,DUPLICATE}`, `E-MAPPING-{DUPLICATE-PARAM,MISSING-CAPTURE,UNLISTED-CAPTURE}` / `validation::rules::collect` | 接続。capture/typeofを含め収集 |
| validateAssoc | `E-ASSOC-{BOTH,NO-MAPPING,MAPPING-PARAM,MISSING-CAPTURE,NO-REPEAT}`, `E-RIGHTASSOC-NONCANONICAL` / 同上 | 接続。欠落した3captureを個別に報告 |
| choice profiles | `E-UNIQUE-LONGEST-{DUPLICATE,SHAPE,LIMIT,CONFLICT}`, `E-LONGEST-CHOICE-{DUPLICATE,SHAPE,ASSOC}`, `E-PREDICTIVE-CHOICE-{DUPLICATE,SHAPE,ASSOC,CONFLICT}` / 同上 | 接続 |
| validatePrecedence | `E-PRECEDENCE-{DUPLICATE,OVERFLOW,NEGATIVE,NO-ASSOC}` / 同上 | 接続。sourceからの負数受理はfrontend契約で別検査 |
| validateAdvancedAnnotations / validateScopeCaptures / validateCatalogAnnotations | `E-ANNOTATION-DUPLICATE-{INTERLEAVE,BACKREF,SCOPETREE,DECLARES}`, `E-ANNOTATION-{INTERLEAVE-PROFILE,SCOPETREE-MODE,DECLARES-CAPTURE,BACKREF-CAPTURE,CATALOG-NO-CAPTURE}` / 同上 | 接続 |
| validateTypeofElements | `E-TYPEOF-{MAPPING,MISSING-CAPTURE,UNKNOWN-CAPTURE}` / 同上 | 接続 |
| validatePrecedenceTopology / validateAssociativityConsistency | `E-PRECEDENCE-{ORDER,MIXED-ASSOC}`, `E-ASSOC-NO-PRECEDENCE` / 同上 | 接続 |
| validateUndefinedRuleRefs | `E-RULE-UNDEFINED` / 同上 | 接続。宣言順の候補、UTF-16 Levenshtein<=2、重複参照抑制。Java legacy query が postfix/separated を辿らない点も保持 |
| unique-longest cycle branch / detectLeftRecursionIssues | `E-UNIQUE-LONGEST-LEFT-RECURSION`, `W-LEFT-RECURSION[-LIMIT]` / `validation::recursion` + `collect` | 接続。advisoryは最後に追加 |

レビュー（PR #896）で見つけた、再利用した共有解析・frontend 表現に由来する残差は #893 に追記した。
空白だけの `context`/NBSP の判定は #897 で共有関数へ修正を集約した。残る対象は `E-LEXICAL` の最初の報告 token、lexical-context の件数上限と字句エラーの優先順、
左再帰の深さ・作業量の数え方（Java は1択の本体にも `ChoiceBody` 段を数える）、`{2147483647}` の raising 検査、
`@whitespace(...)` 内の区切り・コメントの表記、legacy `RegexTokenParser` の Simple token。
range 付きの `didChange` は両版とも先頭の text を全文として適用する（#895）。空 change は文書を変えない。

`validateRustDeclarations` は生成器向け別APIであり、この Java LSP は呼ばない。
単なる rule 名重複（従来 `duplicate` fixture）では Java validator は診断しないため、独自の重複エラーを足さない。
このfixtureは両serverで `[]` を確認し、以前の `validatorGap` ラベルだけを完全一致へ変更した。

### 分割と残る受け入れ条件

- [#893](https://github.com/opaopa6969/unlaxer-parser/issues/893): 上表の未接続意味解析・Java classpath・表現境界を含む validator 全診断。
- [#894](https://github.com/opaopa6969/unlaxer-parser/issues/894): `ULX-PARSE-001` の expected/stack/data/relatedInformation、consumedLength hover、module全文/複数失敗/URI位置。
- [#895](https://github.com/opaopa6969/unlaxer-parser/issues/895): initialize options と `unlaxer/configure`、experimental profile / languageQueryConsumer、cache/lifecycle を共通62件で接続・検証。
- [#994](https://github.com/opaopa6969/unlaxer-parser/issues/994): JSON-RPC 層を共通168ケースで検証。params 自体の欠落・型違い、document version の Gson 型変換、publishDiagnostics の `version: 0`、未知/未実装 method の応答（後述）。

既存の型解析に依存する全診断と、parser failure provenance、session profile は別の契約である。
一つの巨大な移植で Java の機能を省く代わりに、今回の診断接続を独立して revert できる単位とし、
未達の条件を各子 issue に残した。PR は `Refs #885`。#885 / #864 / #111 は open のまま。
CI は既存 `UbnfAuthoringProtocolConformanceTest` の3メソッドを必須化するため、新規クラスの `-Dtest` 追加は不要。
packaged VSIX は Java/Rust 両方で共通の `vsix-diagnostics.cjs` を実行し、Warning/Error のコード・message・rangeと修復を確認する。

#885 の追加 mutation は、validator 収集を空にする、rule range を `0:0–0:1` にする、
message から hint を外す、同じ/減少 version を拒否する4変異で共通実 stdio が失敗した。
新規 bundled class 候補テストの `@Test` を外す変異では Maven の残り2メソッドが成功しても、
`verify-optin-tests.py ubnf-authoring` が必須メソッド欠落を拒否した。変異を復元し3メソッドを再検証した。
PR #896 のレビューでは、候補提示の閾値を `< 2` にする、`ADAPTER(...)` token 参照の検査を外す、
宣言検索の AST node 範囲の制限を外す3変異が、それぞれ追加 fixture（`closest-name-*`、`adapter-token-references`、
`declaration-not-body-grammar-keyword`）で共通実 stdio を失敗させることを確認し、復元した。

## #886 第一単位: AST navigation と package source

Java/Rust の同じ stdio fixture を、`UbnfAuthoringProtocolConformanceTest#nativeAndJavaServersShareNavigationFixtures` で実行する。
`navigation.json` の29文書は source と手書きの binding/outline/folding の期待値を持ち、
`scripts/ubnf_authoring_navigation.py` の10シナリオが複数文書・import/package・キャッシュ寿命を検査する。
全39件で各ホストを独立期待値へ照合し、range・順序・キー有無・rename エラー全文を正規化しない。
従来103件とは別の必須メソッドにし、CI のテスト契約は削除・skip を失敗にする。

- definition は AST に由来する use と一意な宣言、references は同じ Symbol の文書内 use に結び付く。
  宣言は token→rule の順で、次に lexical reference→global policy→rule body/annotation の順を保つ。
  `includeDeclaration` はローカル宣言の有無と、外部定義の末尾追加を制御する。
  複数 alias / grammar が同じ外部宣言を指す場合はその文書内で共有し、別の開いた文書まで索引を広げない。
- rename / linked editing は source origin が一致する AST binding だけを許可する。
  token library の公開 token、外部 token、alias、capture、文字列/comment、重複・予約語・不正な新名、
  不完全入力・module失敗は拒否する。補完用の回復宣言から編集を許可しない。
  parameterized rule の合成名が実宣言に対応しない場合も拒否する。展開が同じ位置を複製する場合の
  references の重複は Java と同じく保持する。COMMIT / LABEL / CATCH / CAPTURE / CAPTURE_SCOPE と
  `@autoCommit` が挿入する COMMIT の中の参照は、group と同じく両版で use に含める。
  以前は Java も辿らず、rename が中の参照を残して文法を壊していたため、PR #903 のレビューで両版を直した。
- native frontend は `a .N` / `a. b.N` を受理するが Java の namespace prefix は連続した文字列を要求する。
  authoring 側で AST の元位置を検査し、この形を回復索引へ落として編集を拒否する。`a. N` は受理する。
  frontend 自体と parser ホットパスは変えない。消去済みの未使用 parameterized 宣言を含む元入力全体や
  他の frontend 制約は #902 に残す。構文診断全文/expected/data の parity は既存 #894。
- documentSymbol は grammar に token/rule を入れ子にし、各宣言名を selectionRange にする。
  不完全入力は masked source の非重複検索で回復し、その grammar range は文書全体になる。
  folding は Java の行単位 heuristic を維持する。comment 中の `::=`/`;` も対象になり、
  CRLF の grammar 行は Java regex の `.*` が `\r` に一致しないため grammar fold を返さない。
  これらを黙って改善して Java と異なる結果にしない。

package 定義先は `ubnf-package:/<sha256>/<encoded-file>` と元 source の UTF-16 range を返す。
外部 URI は検証済み resolver identity から構築する。local import の定義先は JDK の `Path.toUri()` と同じく
`-_.!~*'()` と `:@&=+$,;/` をそのまま残し、ほかを `%XX` にする（PR #903 のレビューで修正）。`ubnf/packageSource` は直前の索引作成で得た
snapshot の検索だけを行い、URI を resolver に渡さず、ファイル読取り・ネットワーク取得・cache更新をしない。
開いた直後は Java の refreshImporters と同じく自文書の索引が消えるため、definition/outline 等の
索引作成後に source が利用可能になる。他文書の通知は既存 importer を再索引化する。
検証後に artifact が変わっても source request 自体は snapshot を返し、次の索引作成時に再検証して破損を拒否する。
未検証hash・path traversal・file/http URI・未知URIは null。cache file と同じ file URI の偽bufferは
検証済みpackageを置換しない。import削除/closeでsnapshotを消し、manifest/lock/cacheを書き換えない。

VSIX は既存の file-only selector と `ubnf-package` content provider を保持する。両 backend で
実際に定義先を開き、readonly provider の元 source・hash・range を確認する。
特殊な `ubnf-package:` 文書を wire で直接 didOpen した場合、Java は reader の `Path.of(URI)` で
`Provider "ubnf-package" not installed` となり importer に E-MODULE を返す一方、Rust は非file bufferを
除外して検証済み source を保持する。この入力域の差は #902 に明示し、通常の packaged IDE 成功を
全 URI の同等性と扱わない。completion/hover のサロゲート内部位置・BMP分類等も #902 の残件である。

## native artifact の方針（配布・公開は行わない）

この段階は source build と外部絶対パスを選択する。VSIX に native binary を同梱せず、
Java 既定、Rust は明示選択のままとする。Rust 選択時に Java へ自動切替せず、起動エラーを既存 output に示す。
明示された実行ファイル以外を取得・探索・自動更新しない。Playground の生成 toolchain は別の要件である。

| platform / target triple | artifact 名を採用する場合の規約 | 現段階 |
|---|---|---|
| Linux x64 / `x86_64-unknown-linux-gnu` | `ubnf-language-server-<revision>-x86_64-unknown-linux-gnu` | 自環境でbuild、外部path、packaged IDE検証 |
| Linux arm64 / `aarch64-unknown-linux-gnu` | 同じ規約でtarget tripleを置換 | 未提供・未検証 #902 |
| macOS arm64/x64 / `aarch64-apple-darwin`, `x86_64-apple-darwin` | 同じ規約 | 未提供・未検証 #902 |
| Windows x64 / `x86_64-pc-windows-msvc` | 同じ規約＋`.exe` | 未提供・未検証、drive/UNC URIも #902 |

将来同梱する場合も target ごとに隔離し、revision/target/SHA-256 と実行可能bitを検査し、
対応する OS 上の JVM 不在・全広告 capability・URI・起動失敗・終了処理の検証を受け入れ条件にする。
未対応platformは外部pathを明示するかJava既定を使い、自動downloadを導入しない。
これは方針であり artifact 配布の完了ではない。公開済み3.3.0、Central、GitHub Releaseは変更しない。

Linux の再現は `cd rust && RUSTUP_TOOLCHAIN=1.85.0 cargo build --locked -p unlaxer-ubnf-lsp --release`、
生成した実行ファイルの絶対パスを `ubnfLsp.server.rustPath` に設定する。
protocol fixture では Rust process の PATH を空、JAVA_HOME を不存在にして起動する。
packaged IDE では `server.javaPath=/nonexistent-java` で起動する。
配布方針を全 platform で検証したとはせず、#902 と親 #886/#864 を open のまま維持する。

#886 第一単位の mutation は、公開tokenの編集許可、AST安全条件の除去、UTF-16→CP誤変換、
package snapshotの欠落、namespace回復の除去、CRLF foldingの変更、rename固有の拒否除去、
任意file URIの読み取り追加という8変異を、共通実stdioがすべて検出することを確認した。
navigation必須メソッドの `@Test` を除く変異も、残り3件のMaven成功後にopt-in契約が失敗する。
復元後、4必須メソッドと両suiteを再実行する。これらは既存parserの性能作業ではない。

## #890: local import の有限読取り

`spec-corpus/ubnf-authoring-lsp/local-imports.json` の共通17件を両実 stdio server、
Java-host Rust/native Rust の生成 CLI、および従来の Java flag CLI に送る。
`/dev/stdin`・`/proc/self/fd/0`・`/dev/zero`・`/dev/null`、テスト内で `mkfifo` する FIFO、Unix socket、directory、
symlink の通常/非通常/過大な実体、FIFO への symlink の連鎖と `..` を含む相対パス、64 MiB + 1 の sparse file、64 MiB ちょうどの有効な module を含む。
新しい拒否診断は message を含む全JSON（CRLF・非BMPを含む root の UTF-16 range）で一致を要求する。
各 didOpen の publishDiagnostics 後、同じ server の completion（64 MiB境界は未open文書のhover）と close 後の hover が返ることも必須とする。
成功時は両 Rust 生成 CLI の出力5ファイルを比較し、失敗時は部分出力がないことを確認する。
既存103＋39件と package/local module corpus は既存の期待値で引き続き検証する。

`LocalImportReaderTest` と Rust の `packages::tests::local_reader_*` は、open 前の拒否、
上限ちょうど、stat 後に増えるストリームの上限＋1バイトでの打切り、strict UTF-8 を直接検証する。
Rust は stat 後に FIFO・過大ファイルへ差し替えた open も、開いた handle の再検査で止まらず拒否することを検証する（Java の残りは #907）。
テストの opener 差替えにより競合をタイミングに依存せず再現し、読み過ぎる変異を必ず失敗させる。
通常parserのホットパスには変更を加えない。

64 MiBの正常文字列の読取り成功はreader単体でも検証する。有効な64 MiBコメントは既存Java parserの解析に時間がかかるため、このfixtureだけstdio待ち480秒・CLI待ち240秒、suite全体900秒の有限deadlineを使う。これは製品のtimeout設定や性能作業ではない（#197）。この変更は許容サイズ内の文法の解析時間を制限しない。

#910 の [feature 共通 corpus](../spec-corpus/feature-diagnostics/README.md) で12文法×LF/CRLFを追加し、
既存103件を含む baseline stdio は135件。feature の全JSONと同一/減少versionでの修復を比較する。
既存 lexical 診断の prefix・range は維持し、CLI生成時の prefix 修正とは別経路で検証する。

## package metadata の読取り（#908）

manifest/lock は両 backend で各8 MiBの bounded reader を通し、lock I/O、manifest 検証、lock JSON検証の順に処理する。
`package-documents.json` の共通27件で全診断JSONと後続requestを比較し、同じfileを3 CLIにも送る。
競合のない通常時の条件を満たしたので #908 は閉じる。Javaのstat後の差替え競合と完全なhandle型判定は #907/#911 に残す。
[上限・診断・競合の制限](ubnf-packages.md#manifest--lock-の-disk-読取り908残り-911)。


## package artifact の読取り（#913）

cache と明示 resolve の local artifact は既存 reader の8 MiB制限とhandle再検査を共用する。
`package-artifacts.json` の39件を両stdio・3 CLIに送り、全診断と後続requestを確認する。
local source は固定cacheから読む通常経路では取得せず、明示 `deps resolve` だけで両版の拒否を確認する。
[文言を維持した理由・検査順・Java競合の制限](ubnf-packages.md#cache--local-artifact-の-disk-読取り913)。

#919 の `package-artifact-utf8.json` は29件の共通 raw bytes で不正 UTF-8 診断と raw hash の優先順を照合する。
両 stdio の全診断と後続request、3 CLI、local の両 `deps resolve` を同じ fixture で検証する。

## initialize / configure の固定棚卸し（#895）

Java 参照は `384fcf3eb1c0ce3e207ac580cec5fcac4b478b34`（origin/master、2026-10-30）。`LSPServerEmitter.emitLifecycleMethods` /
`emitProfileMethods`、`LSPQueryEmitter.emit`、実生成 `UBNFLanguageServer` と
`UBNFLanguageServerExt` を対象とする。Java の製品コード・診断文は変更しない。

| 入力・状態 | Java の受理/拒否・応答 | native に移す契約 |
|---|---|---|
| options 省略/null/空 object、配列・scalar、未知 key | languageProfile key のある object 以外は既定設定として受理 | 同じ。未知 key は provider や I/O を有効にしない |
| languageProfile が object 以外 | -32602 `languageProfile must contain tsv and entry` | 同じ全文 |
| profile object の key が tsv/entry の2つ以外 | -32602 `languageProfile must contain only tsv and entry` | 同じ全文 |
| tsv/entry が文字列以外 | -32602 `languageProfile fields must be strings` | 同じ全文 |
| entry が UBNFFile 以外 | -32602 `languageProfile entry must match generated root` | 同じ全文 |
| TSV が不正 / grammar が UBNF 以外 / entry をローカル parse 不可 | LanguageProfile.parse/select の -32602（失敗時は設定復元） | 共通 profile validator を再利用 |
| 有効な profile | canonical TSV・language/package/version/grammar/entry を experimental.languageProfile に保存 | 同じ metadata。ファイル・provider の読込みなし |
| experimental.languageQueryConsumer | schemaVersion=1、PARSE 以外の7操作。transport は VALIDATE/COMPLETION/HOVER/DEFINITION、providerRegistered/available は全 false。profileAllowed は既定 true、profile 指定時は SUPPORTED/PARTIAL のみ true | 同じ全 JSON |
| profile による広告 | COMPLETION はキー削除、HOVER/DEFINITION/RENAME/CODE_ACTION は false、FORMAT 不可なら documentFormattingProvider/documentRangeFormattingProvider=false。他は拡張の capability を保持 | native が実装済みの capability と metadata に適用。#901 の未実装機能を広告しない |
| configure | initialize と同形の params/result。設定全置換（省略で既定へ）。成功時 parse cache 無効化、失敗時旧設定保持。文書を保持し自動再診断しない | 同じ。native の解析/query は毎回再構築し profile に依存した古い結果を再利用しない |
| 再設定後の document query | Ext の hover/completion/navigation は基底の profile gate を通らず応答する。診断は基底 VALIDATE gate を通る | この実挙動を維持 |
| packageSource snapshot | configure は Ext.indexByUri を消さない。document notification は refreshImporters で消す | 同じ寿命 |
| initialize 前 / 重複 initialize / shutdown 後 | -32002 `Server not initialized` / -32600 `Server already initialized` / -32600 `Server has shut down` | 同じ全文。失敗 initialize は再試行可能 |
| initialize/configure/shutdown の通知 | 実行せず無視。通知専用 method の request は -32600 `Notification method requires a notification` | 同じ lifecycle 境界 |
| exit | shutdown 後0、それ以外1で process 終了。以降 wire request は送れない | 同じ終了境界。handle 層も終了後拒否 |
| 同一/減少 version、range 付き Full change | Ext は version/range を見ず先頭 text を全文適用。空 change は文書不変 | native も適用。複数文書・再設定後の query/診断で検証 |
| 未オープン URI への didChange、document version の型 | Ext の parseDocument が未オープン URI も開いて診断する。version は lsp4j の Integer として読むだけで値は使わない（省略/null/32bit 整数は受理、範囲外・非整数の小数・真偽値・object は通知ごと破棄） | 同じ。#994 で Gson の数値文字列・整数値の小数の型変換と診断 version を追加（後述） |

Java の semanticTokens/codeAction/signatureHelp の実装と広告は維持する。
これらの native 移植は #901 のままとし、共通 fixture はホスト別の既存 capability も独立全 JSON 期待値で固定する。

棚卸しに対する検証は [configuration.json](../spec-corpus/ubnf-authoring-lsp/configuration.json) と
[既存 stdio checker の configuration suite](../scripts/ubnf_authoring_configuration.py) で行う。
入力40種の失敗 initialize と同じ40種の再設定、未知 options 10種、既定/制限/混合/ローカル profile、
複数文書の診断・同一/減少 version・range/空/複数 change、import buffer の変更後の全 hover/completion JSON、
package snapshot の保持と次の query の再検証、未オープン URI の Full change と document version の型、初期化前/重複/通知誤用/終了境界を含む62ケースである。
profile の source URL や未知の catalogPath を読み込まず、providerRegistered/available は false を維持する。
両ホストの InitializeResult は既存未実装 capability の差も含めて別々の独立期待値を持ち、
実測応答から期待値を生成しない。既存 UbnfAuthoringProtocolConformanceTest の必須メソッドに登録している。

native は query と parse を現 buffer から毎回構築し、構成に依存した結果を再設定後に再利用しない。
Java の基底 snapshot parse cache は設定成功時に無効化されるが、Ext の index はその対象ではない。VALIDATE 無効時は診断から index を作らない。
そのため configure では既に検証した package source snapshot を消さず、次の query で import を再検証する。
client の semantic-token multiline/overlap flags は Java の設定に含まれるが、native の token 提供は #901 の範囲であり、
この PR はその機能を広告せず、flags が付いた initialize/configure の受理を検証する。

## JSON-RPC 層の固定契約（#994）

参照は #895 と同じ `384fcf3eb1c0ce3e207ac580cec5fcac4b478b34`。
生成 `UBNFLanguageServer` / `lifecycleEndpoint` と `UBNFLanguageServerExt`、
`UBNFLspLauncherExt`、LSP4J 0.23.1 / Gson 2.13.2 を固定した。
Java 製品コード・既存 capability・公開済み3.3.0・通常 parser のホットパスは変更しない。
JSON-RPC 入力境界だけを native へ移し、新しい crate・ネットワーク・lock更新・コード実行は追加しない。

| 入力 / 観測項目 | Java の固定挙動 | native の選択と理由 |
|---|---|---|
| initialize/configure の params 欠落、null、`[null]` | `-32603 Internal error.`。initialize は未初期化、configure は旧設定と文書を保持 | 同じ code/message と状態。Java の NPE は欠陥だが、製品を変更せず失敗境界を保つ方が最小・可逆 |
| params の `[]`、scalar、`[7]`、capabilities の scalar/array | Gson が `-32700 Message could not be parsed.` | lifecycle dispatch より先に型を検査。失敗で構成を変更しない。InitializeParams の top-level 欄と実測した入れ子 DTO の検査を #1001 で追加（後述） |
| `[有効 InitializeParams]` | 単一 positional 引数として受理 | 同じ。失敗後の initialize 再試行をこの形でも確認 |
| document version | 省略/null/i32 に加え、数値文字列・整数値の小数・指数を Gson `nextInt` で受理。値の大小は無視 | `Integer.parseInt` 相当の BMP 数字、続いて double→i32→double の一致で判定。ASCII前後空白、符号、浮動小数suffix/16進浮動小数文字列も共通 fixture。範囲外・非整数・真偽値/object/array は通知ごと破棄 |
| publishDiagnostics | Ext は documentVersions を更新せず常に `version: 0`。configure 自体は通知しない | 同じ欄を追加。実 version を推測して送らず、観測契約を維持 |
| 未知 request | `-32601 Unsupported request method: <method>` | 全 message を一致させる |
| 未知 `$/` request（cancelRequest/progress/foo 等） | `result: null`。宣言済み通知 `$/setTrace` の request は従来の `-32600` | 同じ。lifecycle の前後では従来の未初期化/終了エラーを維持 |
| lsp4j 宣言済み未実装 request | `-32603 Internal error.` | 38 method を固定リストで扱う。willSaveWaitUntil/formatting/codeLens/documentHighlight/prepareRename/workspace symbol/executeCommand/willCreateFiles 等を含む。機能を実装したり広告したりせず default method の失敗を維持 |
| error.data | Internal error は JVM stack trace、parse error は Gson/JsonReader の説明 object | この2エラーの Java `data` のみ比較から除外。JVM の呼出履歴や Gson のクラス名・reader位置を native に捏造せず、実装詳細を安定契約にしないため。存在と型を検査し、code/message/id/result と他のキーは独立全 JSON と厳密比較 |

手書き fixture は `spec-corpus/ubnf-authoring-lsp/jsonrpc.json`。
`configuration.json` の既存の手書き InitializeResult/profile/診断 oracle も再利用する。
実測値を oracle に書き戻さず、12不正 params × initialize/configure、47 version × didOpen/didChange、
47 method、再試行/回復/終了の3件、計168ケースを両実 stdio に送る。
失敗 initialize 後の新 request は未初期化エラー、再試行は成功する。
失敗 configure 後は既存文書の全 hover、毎回新しいURIへの didOpen と診断抑制を確認し、
成功 configure 後の全診断 JSON により旧 VALIDATE gate の保持と回復を検証する。
不正 version の change は既存の異なる文書を保持し、全 documentSymbol JSON と通知不在を確認する。

共通 checker は、従来 suite の `diagnostics()` でも uri/version/diagnostics 以外のキーを拒否し、
新 suite は params 全体を独立 oracle に比較する。request の id を params より先に送信し、
null/省略/空配列/false/0/空文字を勝手に `{}` へ変換しない。
`UbnfAuthoringProtocolConformanceTest.nativeAndJavaServersShareJsonRpcFixtures` を既存
self-hosted CI の必須契約へ追加する。Java driver の `-Xmx8g`、runs-on、重い local-imports suite は維持する。

#901 の codeAction/signatureHelp/semanticTokens full は範囲外で、既存のホスト別 capability oracle を維持する。
#1001 で reader / DTO 境界を追補した。#994 の168件を残し、新たな390件を加えた計558件を
同じ `nativeAndJavaServersShareJsonRpcFixtures` で実行する。新しい test class/job は追加しない。
この成功を全 DTO・全 raw frame の同等性や親 #885/#864/#111 の完了とは扱わない。

### reader / DTO の選択と恒久差（#1001）

固定 Java revision は引き続き `384fcf3eb1c0ce3e207ac580cec5fcac4b478b34`。
LSP4J 0.23.1 `MessageTypeAdapter.read/parseParams`、`InitializeParamsTypeAdapter.read`、
Gson 2.13.2 の実 server を棚卸しした。method が既知なら stream から DTO を読み、
params が method より先なら JsonElement を保留して全キー読取り後に DTO へ変換する。
id はその時点まで読めた値だけが Java の error 回復に使われる。

| 手書き入力 / 境界 | 固定 Java の観測 | native の選択・理由 |
|---|---|---|
| id/method/params の全6キー順、object params | 全て受理 | 同じ。JSON文字列内のキー風の文字列や escaped property name を raw 検索で誤認しない |
| `params:[]`、method → params → id | reader の DTO 変換が id 読取り前に失敗し、応答なし | **恒久差**: 最終 id 付き `-32700 Message could not be parsed.` を維持。LSP4J/Gson reader の欠陥を模倣して client を固めず、JSON-RPC に沿った決定的応答を返す。他の5順は両版とも id 付き同エラー |
| positional arity 0 / 1 / 2 | 0は前行。method が params より先の `[{}]` は受理、`[null]` は `-32603 Internal error.`、`[{},{}]` は reader が失敗し応答なし。params が先なら単一引数の unwrap がなく、これらの配列は id 付き `-32700` | 0/1は同じ。methodより後の params は単一引数として unwrap、保留した配列は Java の決定的エラーに揃える。arity 2の Java 無応答だけは **恒久差** とし、native の id 付き `-32700` を維持（reader欠陥・client停止回避・JSON-RPC応答の理由は前行と同じ） |
| object の `capabilities:null`、単一 positional の同値 | Java setter が NPE で reader を失敗させ、応答なし。ただし params が method より先の配列は `-32700` | **恒久差**: object / stream の単一 positional は native の既定 capability による成功応答を維持。Java側のnull setter欠陥に合わせて client を固めず、JSON-RPC request を完結させる。保留配列の決定的エラーは同じ |
| `workDoneToken:{}` / `[]` / `true` | Either adapter が reader を失敗させ、応答なし | **恒久差**: native は従来どおり未使用 token を無視して成功を返す。Java reader 欠陥の移植は client を停止させるため安全でない。JSON-RPC request への決定的応答を維持する。数値 `1.5` は両版 `-32700`、文字列/null は受理 |
| InitializeParams の他欄・入れ子の型違い | id 付き `-32700 Message could not be parsed.`、initialize 未初期化 / configure 旧状態保持 | 同じ。対象の正確な範囲は下記。成功値の coercion と未知キー・untyped options は削らない |
| 型付き request の params `7` / `[]` / `"x"` / `false` | 宣言済み50 methodで id 付き `-32700`（実装/default method の dispatch より先） | 同じ。hover/formatting/documentSymbol/packageSource の単一 positional 正常形も比較。#901 の既存 Java 3機能を削らず、不正DTOのparse errorだけをnativeへ追加する |
| hover の `textDocument:7`、`position.line:[]` / 生 `1e400` | id 付き `-32700` | 同じ。#1001 時点の範囲。#1005 で受理域・他method・ClientCapabilities を下表の範囲へ拡張した |
| 重複 id / method / params | 既知methodの params は出現ごとに検査。正常な重複 id/method は最後の値。不正paramsを後の正常paramsで上書きしても先のparse errorを返す | 同じ入力で比較する。nativeはstream失敗時点の既読idを回復し（未読の場合は最終id付き応答を維持）、先の不正DTOで生じたerrorを後のparamsで消さない。未知methodで保留したparamsは最終methodで変換する。InitializeParamsのtop-level欄も出現ごとに検査する。全ての異種method切替や不正idのreader回復を同等と主張しない |
| `"id":null`（未知 / `$/foo` / formatting） | 通知として扱い、結果/errorを返さない | 同じ。無いidと同じ通知判定にする。readerの故障ではないため、通知をrequestに変える理由はない |
| Integer に収まらない数値 id（`0.5`、`2147483648`、`10000000000`、生 `1e400` / `-1e400`）、bool / array / object の id | reader が id を読めず、応答なし | **恒久差**: native は数値 id をそのまま返す（bool / array / object は `id:null` の `-32600 Invalid Request`）。JSON-RPC は応答の id を request と同じ値にすることを求める。Java reader の無応答を移植すると client を固めるため、他の無応答と同じ理由で移植しない |
| 整数値の小数・指数表記の id（`1.0`、`1E0`、`1e-400`） | Integer に変換し、`1` / `0` を返す | **恒久差**: native は request の数値をそのまま返す。JSON-RPC の「同じ値」に沿い、JSON の数値として等しいため client の対応付けは変わらない |
| didChange version の生 `1e400`、contentChanges の不正 DTO（`range` / `range.start` / `rangeLength` の型違い・生 `±1e400`、2件目以降の不正な change） | 通知を黙って破棄し、元文書を保持。Gson が全 change を読んでから Ext が先頭 text だけを使う | 同じ。native も全 change の `range`・`rangeLength`・`text` を検査してから先頭 text を適用する。null や Gson が変換できる値（`"0"`、`1e-400`、`"1"`）は両版とも受理する。`unlaxer-ubnf-lsp` 自身が serde_json `arbitrary_precision` を要求する。`-p` / `--workspace` の feature 統合に依存しない。新しい crate は追加しない。JSON を全体で受理した後 DTO Integer 変換で拒否し、request は元id付き `-32700`、通知は応答なし |
| error.data / stderr | -32603 の data は JVM stack trace、-32700 の data は Gson の説明 object。無応答時の stderr に NPE 等 | data の**内容だけ**を比較から外し、存在と型は検査する（#994 と同じ）。VM呼出履歴・Gson内部位置を移植する契約がないため。stderr の stack trace はwire応答ではなく比較対象外。id/code/message/result/他のキーの有無は全JSON比較 |

DTO の検査範囲は `processId`（Gson Integer）、`rootPath/rootUri/locale/trace`（Gson String）、
`clientInfo` と `name/version`、`workspaceFolders` と要素の `uri/name`、`workDoneToken` の数値、
`capabilities` の top-level `workspace/textDocument/notebookDocument/window/general`、代表的な
`textDocument.hover`、`textDocument.completion.completionItem.snippetSupport`、
`workspace.workspaceEdit`、`window.workDoneProgress`。null/省略は Java の受理域を保つ（capabilities null の恒久差は表のとおり）。
`processId:"1"`、`rootUri:1`、`rootPath:true`、`initializationOptions:7`、未知キー、
clientInfo/folder内の scalar→String、`hover.contentFormat:7` / `general.positionEncodings:7` の受理も固定する。
以上は #1001 時点の検査範囲。#1005 では下記の生成型表へ拡張した。全methodの内部DTOや将来版のparity完了とはしない。

fixture の `reader.orders` / `reader.orderParams` / `reader.duplicates` は手書きraw frameで、
serializeし直さず両実serverへ同じ文字列を送る。生の数値 `1e400`・重複キー・escaped key・順序を保持する。
oracleは実測結果から作らず、fixture内の明示されたcode/messageとホスト別の成功/無応答表を使う。
無応答は後続requestが有限の応答 timeout（suite 共通の既定20秒。共有 runner の負荷で JVM 起動や GC が遅れても誤検知しない）内に処理された後、共有の**1秒deadline**で余分なframeがないことを確認する。
失敗initializeを同じ未初期化serverへ順次送り、最後の正常initializeが成功することを確認する。
成功するinitializeだけ別sessionで検証して既初期化エラー・後続request・正常終了も調べ、JVM起動回数を抑える。
configureの失敗後は、旧文書の全hover/documentSymbolと新規URIの診断抑制で旧設定・文書を検証し、
後の正常configureで診断が回復することも従来どおり検査する。nativeだけ成功する恒久差のconfigureには
同じrestricted profileを渡し、対応表の選択と状態保持を混同しない。

#1001 の558件の直接stdio処理は、共有ホスト上で
JDK21 **17.663秒**、JDK17 **21.043秒**（suiteの elapsedSeconds）だった。
#994 の168件は約2.7秒/4.4秒だったため、今回の追加時間は約15秒/17秒で目標1分未満。
CIに新しいjobやlocal-importsの重複実行を足さず、self-hosted runs-onとJava serverの `-Xmx8g` を保つ。
`processId` 型検査だけを外した隔離nativeコピーは、実stdioの不正initializeが成功を返して新suiteに検出された。
正常コードでは同じfixtureが両JDKで成功する。変異はworktreeへ残さない。

### Gson / DTO 受理域の追補（#1005）

参照は同じ固定 Java `384fcf3eb1c0ce3e207ac580cec5fcac4b478b34`。Java 製品コードは変更しない。
指定 revision から別領域に構築した実生成 server と native へ、手書き raw frame と
実測から作らない独立全 JSON oracle を JDK17/JDK21 で送る。同じ必須 jsonrpc suite を
558→916件（358件追加）に拡張し、既存 test class/job、self-hosted runs-on、
Java server の `-Xmx8g`、既定応答 timeout 20秒を維持する。

| 境界 / 判断 | 固定 Java | native の選択と理由 |
|---|---|---|
| position の Integer | `"0"` / `" 0"` / `1.0` / `0E5` / `1e-400` / `"1e0"`、BMP数字、16進浮動文字列を Gson で変換。省略/null の primitive欄は0 | version と同じ `gson_integer` / `stream_int` を共有して受理。範囲外・非整数・container/bool は dispatch 前の元id付き `-32700 Message could not be parsed.` |
| 負の位置 | hover/completion は負の character に `-32603 Internal error.`。navigation は line/character を0へ丸める。hover/completion の存在しない line の語/行prefixは空 | method 別に同じ結果/エラー。型が正しい負値を共通の InvalidParams に置換しない |
| hover 以外の入れ子 DTO | `textDocument:7` / `uri:[]`、completion `position:7`、definition `line:"x"`、references `context:7`、rename `newName:[]`、codeAction `range:7`、packageSource `uri:[]` は元id付き `-32700` | 同じ。lifecycle/製品 dispatch 前に検査し、既存文書・設定を変更しない。#901 の既存 Java 機能を削らず不正DTOの拒否だけを追加 |
| workDoneToken の数値 | 整数値（`1.0` / underflow の0を含む）は受理。非整数/範囲外に元id付き `-32700` | lsp4j の継承欄から機械列挙した38標準request、および initialize/configure で同じ Integer 検査。文字列/null は受理 |
| workDoneToken の `{}` / `[]` / bool | Either reader の欠陥で無応答 | **恒久差**: #1001 と同じく未使用tokenを無視しnativeの決定的応答を維持。clientを停止させる欠陥を移植しない。hoverの `{}` を後続barrier＋1秒deadlineでも検証 |
| ClientCapabilities | synchronization、applyEdit、markdown、hover.dynamicRegistration、window.showMessage、workspaceFolders の指定の型違いは元id付き `-32700` | 全ClientCapabilitiesの欄名/型グラフを単一生成表で検査。initialize は未初期化、configure は旧設定・文書・新入力のVALIDATE gateを保持。null/未知キー/experimental は維持 |
| didChange text scalar | 数値/真偽値を String に変換して先頭changeをFull snapshotとして適用 | 同じ `gson_string` を検査と適用に共有。`7` / bool / 小数 / 指数 / 生 `1e400` を受理し、負の0や指数の原表記をraw frameから保持。後続changeの型違いも全体破棄し、元文書を保持 |
| null DTO 欄（レビュー追補） | `textDocument` の null/省略は `-32603 Internal error.`。`uri` の null/省略は未オープン文書として各 method の空結果。references の `context` null は宣言の参照に達したときだけ `-32603`。rename の `newName` null は編集可能な対象でだけ `-32603`、それ以外は既存の rename error | 同じ順序で再現する。`-32602 document URI required` / `newName required` の独自エラーは返さない。手書き oracle 31件を同じ suite に追加 |
| references `includeDeclaration` の文字列 | Gson の primitive boolean は文字列を `Boolean.parseBoolean`（大小無視の `true`）で変換 | 検査と同じ変換 `gson_boolean` で消費する。`"TRUE"` は宣言を含め、`"yes"` は含めない |
| Either の token 不一致（container/bool/数値） | `partialResultToken:{}`、Diagnostic `code:{}`、ParameterInformation `label:{}`、`publishDiagnostics.tagSupport:7`、`semanticTokens.requests.full:7` などは Either adapter が token を読まずに失敗し無応答 | **恒久差**: workDoneToken 以外は native が元id付き `-32700` を返す（workDoneToken は上記のとおり受理）。client を固める欠陥を移植しない。#1031 の enum 無応答と同じ分類 |

型表は `rust/unlaxer-ubnf-lsp/src/lsp4j-dto-schema.json` 一か所に置く。
`scripts/generate-lsp4j-dto-schema.py <org.eclipse.lsp4j-0.23.1-sources.jar>` で再生成でき、
元JARのSHA-256、version、requestとDTOの対応を記録する。継承欄を含む106 DTO / 299欄であり、
ClientCapabilitiesの全到達欄と、hover/completion/definition/references/rename/documentSymbol/
foldingRange/linkedEditingRange/codeAction/signatureHelp/semanticTokens full の11 requestを含む。
packageSourceはJava独自recordのuri Stringを別途検査する。標準の未実装requestはtop-level paramsと
該当するworkDoneTokenを検査するが、それら全methodの内部DTOを移植したとは主張しない。
Gson/lsp4jのscalar変換・単一要素collection・tokenによるEither分岐を保ち、nullや未知キーを
過剰拒否しない。型表はサーバーの実測結果やfixtureから生成しない。

この型グラフの成功を全raw frame（入れ子の重複キーを含む）/全DTO adapterの同等性とはしない。
追加調査で completionItemKind.valueSet の `1.5` は Java EnumTypeAdapter が無応答になることを
両JDKで確認し、受け入れ条件付き [#1031](https://github.com/opaopa6969/unlaxer-parser/issues/1031) に分離した。
native は決定的parse errorを返す。#1005 の指定の決定的parse errorと混同しない。
レビューで分離した didOpen の scalar text/uri と int overflow は、下記 #1033 で対応する。
error.data の比較範囲と親 #885/#864/#111、#893/#894/#901/#902 の継続は従来どおり。

直接stdioの885件は共有ホスト上で JDK21 **31.461秒** / JDK17 **34.664秒**、
追加したrequest/position/scalar部の計測は **3.831秒 / 5.105秒**だった。
従来558件の記録（17.663秒 / 21.043秒）に対する差は約14秒で、suite全体でも1分未満。
新しいjobや重いsuiteの重複を足さず、追加CI時間1分未満の目標内に収める。
レビュー追補の31件を加えた916件は、同じ共有ホストで直接stdio JDK21 **20.949秒** / JDK17 **21.437秒**（追加部 3.278秒 / 3.267秒）だった。
生成表の106 DTO/299欄と38 workDone methodは、JARのreflectionによる別実装でも照合した。
`workspace.applyEdit` のboolean検査だけを無効化した隔離コピーは、今回追加の不正initializeを
成功させてしまい、独立oracleとの比較で検出された。正常コードは同じ入力を拒否する。

### scalar 通知と int overflow の追補（#1033）

参照は固定 Java `384fcf3eb1c0ce3e207ac580cec5fcac4b478b34`（LSP4J 0.23.1 / Gson 2.13.2）。
Java 製品コードは変更せず、既存の必須 jsonrpc suite を916→951件へ拡張する。

| 境界 / 判断 | 固定 Java | native の選択と理由 |
|---|---|---|
| didOpen の scalar text | 数値・真偽値を Gson String へ変換し、文書を開く。数値は `1.50` / `0E5` / `-0` / `1e400` の原表記 | #1032 の `gson_string` を共有して受理。raw object の decoded key を走査する共通の原表記保持を didChange と共有し、String 変換を重複実装しない。独立の診断全JSON（`data.totalLength` が4/3）で `1.50` と `1.5` を区別する |
| didOpen / didChange / didSave / didClose の scalar uri | 同じ Gson String 変換。open はそのキーへ作成、change は先頭snapshotを置換、save は importer をrefresh、close はその文書を削除 | 4通知とも共有変換と数値原表記を使う。request の scalar uri lookup も同じ文字列へ変換する。別表記 URI の文書を同時に置き、対象の更新・削除と別文書保持を比較。save は空の文書検査だけで済ませず、生きた importer の再通知で検証する |
| `start + character` が `Integer.MAX_VALUE` と一致 / 直後 | UTF-16 start と非負 character を Java int で加算してから行末との最小値を選ぶ。2行目以降では負へwrapし、scope/useに一致しない。通常補完は40件、wrapしない行末はローカル規則込み42件 | **同じ**。明示的 `i32::wrapping_add` を使い、負の index offset は lookup 対象なしとして扱う。行内 prefix/word の丸めは独立に保持。決定的な応答で client 停止はないので、reader 無応答の恒久差と同列にしない |
| hover / definition / references / rename / linkedEditingRange | 同じ offset を共有。負値では hover は既存fallback、definition/referencesは空、renameは既存の安全なrename拒否、linkedEditingRangeはnull | 全て同じ signed offset 判定を共有。hover の keyword/annotation help は Java と同じく index lookup より先。型の正しいoverflowを parse error にしない。documentSymbol/foldingRange はpositionを使わない |

手書き raw frame と静的な独立全JSON oracleを同じ実 stdio suiteへ追加した。
completion の40候補は文法・help catalog・snippet契約から転記し、server実測値から生成しない。
LF文書のUTF-16 startは0/12/35/50、非BMPコメント＋CRLF文書では7/20/44/60。
未終端の `token T ::=` 行（start 12）では、wrap した offset で Java の `lastIndexOf("::=", offset)` が -1 になり、
字句用ではなく通常の40候補になる。native が丸めた行末 offset で字句状態を判定すると字句用候補になり、この oracle で検出する。
行末へ丸めた offset は半開区間 `[start, end)` の use に入らないため、navigation 4 method と hover の use 判定では
wrap と丸めを観測上区別できない。これらは同じ signed offset を通す回帰確認で、wrap を検出するのは completion の
scope（閉区間）と字句状態だけである（レビューで navigation 側の wrap を外す mutation が suite を通ることを確認した）。
1行目（加算overflowなし）、MAX_VALUE直前/一致/直後、最大character、存在しない行を確認する。
Java server の `-Xmx8g`・runner既定timeout 20秒・self-hosted runs-on・既存test class/jobを維持する。
通常parserのホットパス、公開3.3.0、editor parseのnetwork/lock/実行境界、新規crateは変更しない。
全DTO/全raw frameや親 #885/#864/#111 の完了は主張しない。

固定 revision の実生成 server と native の949ケースは JDK21 **26.505秒**、JDK17 **43.091秒**。
今回の追加部はそれぞれ **0.400秒 / 0.720秒**（suite内の計測区間）で、追加CI時間1分未満の目標内。
両JDKの関係するSurefireテストは各43件、failure/error/skip 0。JDK17は同じJava sourceを
`--release 17` で別領域へ再コンパイルし、17版common/dslと同じSurefireから実行する。
didOpen text変換・通知uri変換・text原表記・uri原表記・wrapping加算・didSaveのscalar uri受理を
個別に外すmutationは、いずれも追加oracleで検出する。

### syntax / module 診断の限定対応（#894）

#894 の限定対応は共通 [syntax-status.json](../spec-corpus/ubnf-authoring-lsp/syntax-status.json) /
[stdio runner](../scripts/ubnf_authoring_syntax_status.py) の69ケースで検証する。
10根失敗は空文書・無効入力・非BMP・trivia・LF/CRLF・成功prefix・複数grammarを全JSONで照合する。
JavaのChainはrollback後の自身のcursorで失敗候補を登録するため、根の前にtriviaがあると
parentのbrace候補は最遠へ届かず、期待集合は空文書の5候補ではなく3候補になる。
通常のbootstrap UBNFにはGrammarRuleがないためstack関連欄・relatedInformationは不在が正しい。

不完全annotation/FQN/group・無効body・複数grammarの9ケースはfallback hoverだけを全JSON比較する。
そのsyntax診断のcode存在は確認するが、message/range/dataの一致は未完了と証跡へ明示する。
この9ケースをsyntax完全一致として数えない。第一段のincomplete-annotationのhost別hover期待値は削除した。

24 module/package形をLF/CRLFの48ケースで検証する。欠落・複数失敗・循環、alias衝突、未解決token、
network拒否、非file/相対/opaque/authority/query/fragment/escape/space URI、
missing/stale lock・raw hash・依存未宣言・artifact依存/identity不一致を含む。
URI失敗はimportごとの推測位置へ変換せず、Javaの外側catchの全文と文書先頭rangeにする。
最終link失敗も同じ外側説明prefixを付け、個別import失敗は宣言順に集約する。
Java editorはsource snapshotではなくASTをresolveするため、複数grammar時にnative CLI resolverが足す
grammar名/CP範囲の外側wrapperを、このASTのspanと照合して除く。resolverの原因本文は保持する。
複数文書のopen/change/save/closeでimporter再診断・同じ/減少version・local overlayの復帰、
終了後requestの全error JSONを検証する。全package/local入力のbytesが不変で、lock更新・取得をしない。

完全一致が安全にできない欄は固定参照と理由を上表に残し、受け入れ条件付きの
[#1024](https://github.com/opaopa6969/unlaxer-parser/issues/1024)へ分離した。
Javaのkeyword prefix受理、parameterized syntax state、mapper値エラー時のroot成功、深い資源境界は
prefix再認識でも一般には同じにならない。この変更で全入力域のhover一致を主張しない。
URIの任意Unicode/percent-decoding/platformと特殊非file bufferの既存差は#902、DTO受理域は#1005。
#893のvalidator意味解析・#901のcapabilityには手を入れず、#894/#885/#864/#111は閉じない。
