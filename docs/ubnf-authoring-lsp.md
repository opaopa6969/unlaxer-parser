# UBNF authoring LSP の Java / Rust 対応

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
| `unlaxer/configure`（継承） | profile / client capability を再設定し cache 無効化 | 未対応。initialize の非空 options も明示拒否 |

将来機能の formatting、workspace 全体索引、completion resolve、semantic delta は今回の parity 条件に混ぜない。
未対応 request は `-32601`。全診断/profile は [#885](https://github.com/opaopa6969/unlaxer-parser/issues/885)、
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

CI は `ubnf-authoring` 必須契約の5メソッドと、基準103件（従来27＋診断76）・navigation39件・local import 17件の実行件数を別々に検査する。
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
range 付きの `didChange` は Java が先頭の text を全文として適用し、Rust は拒否する（#895）。

`validateRustDeclarations` は生成器向け別APIであり、この Java LSP は呼ばない。
単なる rule 名重複（従来 `duplicate` fixture）では Java validator は診断しないため、独自の重複エラーを足さない。
このfixtureは両serverで `[]` を確認し、以前の `validatorGap` ラベルだけを完全一致へ変更した。

### 分割と残る受け入れ条件

- [#893](https://github.com/opaopa6969/unlaxer-parser/issues/893): 上表の未接続意味解析・Java classpath・表現境界を含む validator 全診断。
- [#894](https://github.com/opaopa6969/unlaxer-parser/issues/894): `ULX-PARSE-001` の expected/stack/data/relatedInformation、consumedLength hover、module全文/複数失敗/URI位置。
- [#895](https://github.com/opaopa6969/unlaxer-parser/issues/895): initialize options と `unlaxer/configure`、experimental profile / languageQueryConsumer、cache/lifecycle。

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
既存103件を含む baseline stdio は127件。feature の全JSONと同一/減少versionでの修復を比較する。
既存 lexical 診断の prefix・range は維持し、CLI生成時の prefix 修正とは別経路で検証する。
