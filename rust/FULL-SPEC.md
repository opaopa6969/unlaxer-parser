# Rust full-spec対応表

親issue: [#111](https://github.com/opaopa6969/unlaxer-parser/issues/111)。限定デモの完成とfull-specの完成を区別する。以下は機能群別台帳であり、全メソッドを監査した完全互換宣言ではない。各群の実装前に受け入れcorpusを追加し、差を明記する。

## 基準と判定

- 初期互換基準: unlaxer-parser `72de487020cd6321660dc7114f31ae008c629416`、tinyexpression `6c8196b7879abe58d6b739d3e63a16e853745ced`。構文・annotationの列挙元は`unlaxer-dsl/src/main/java/org/unlaxer/dsl/bootstrap/UBNFAST.java`、仕様案は`unlaxer-dsl/specs/`。文書と実装が異なる場合はテストで差を確認し、意図を記録する。
- 現状監査（2026-09-30、#327）: unlaxer-parser `0b79ca4ddcfdde2fbce893bf30f3a64ae2a49d0a`、tinyexpression `19945446216b7f0d6d8817b353385cbb96bcee25`。初期基準からの要求を消したり、別engineの実装でClassicの互換性を証明したことにはしない。
- 「生成済み」は現在の限定UBNFから生成・コンパイル・実行できる範囲。「runtimeのみ」は手書きAPIで使えるがUBNF経路は未対応。「未対応」は今後の作業で、対応済みとして数えない。
- 共通機能はJava/Rust双方で実装・検証する。片側だけの完了は共通機能の完了とせず、言語固有の表現差・制約を対応表とissueに残す。
- 共通corpusは既存`evolution/conformance.json`の37入力×4段階、診断境界は`evolution/diagnostics.json`の8入力。full-spec用corpusは各行の拡張とともに追加する。現在の有限corpusは全言語の互換性証明ではない。

## 二つの対応軸

このrepositoryのRust `unlaxer-runtime` / `unlaxer-generator` は **Classicの状態付きcombinator経路**。
一方、現在の下流 `tinyexpression-rs` は公開repositoryにvendoringされた **ubnfc生成parser** を使い、
`unlaxer-runtime` には依存しない。[選び方](../docs/engine-selection-guide-ja.md)を参照。

TinyExpressionの評価器・CLI・AOTの実在は製品側の到達点として数えるが、Classicの未移植
annotation、`ParseContext` API、consume/invert伝播、incremental、IDE protocolの完了証拠にはしない。
下表は、明記しない限りこのrepositoryのClassic Rust生成・runtimeを対象とする。
「下流」とした行の根拠・再現条件は後段の現状監査に記録する。

## 対応表

| 機能群 | 現状 | 追加の受け入れ条件 |
|---|---|---|
| Java UBNF frontend → Rust generator | 限定範囲で生成済み。隣接参照の識別子境界 #131 修正 | 全構文・annotationのpositive/negative fixture、未対応の明示拒否、再生成一致 |
| UBNF source snapshot | Java `parseWithSource` / Rust `parse_with_source`。同じparseのowned AST・元入力・code-point位置、capture/typeofの範囲を保持（#318） | [契約と検証](../docs/ubnf-source-snapshot.md)。LF/CRLFの共通corpusで全ノードの位置・元textを比較。import展開後の複数ファイル由来は未対応 |
| 移植可能性チェック | 両hostの `check --target rust` と位置付きJSON（#157）。未対応機能の全出現inventory、対応範囲内は構造検証 | [契約と検証](../docs/portability-check.md)。共通corpus/実P4/注入負例で結果・位置・exit一致、無書込み・外部class非実行。構造検証はfail-first、target Javaや完全移植の保証とは区別 |
| 公開ParseContext・custom parser | runtimeと生成入口を実装。生成grammarは`OnceLock<SharedGrammar>`で1回構築し、並行parseでは不変graphだけを共有（#185） | 共有入力・Unicode位置・typed状態・CST/captureのrollback、先読み、生成parser混在を継続検証。tinyexpressionでsetup/探索を分離して再測定 |
| literal・参照・sequence・ordered choice・group | 生成済み | optional経由の再帰等も検証し、非消費ループを拒否 |
| longestChoice/predictiveChoice | 両frontendから生成、Java/Rust runtimeを実装 | 最大消費・同長の宣言順・敗者のrollback、保守的FIRSTと全候補失敗時の診断を検証。最長選択は意味論、予測選択は最適化であり、通常choiceやroot retryの解消とは別（[実践ノート](../docs/performance-tuning-ja.md) ケース1・2） |
| uniqueLongestChoice・曖昧性境界 | 両frontend・生成器・runtimeで唯一の最大一致を選択し、同率・空一致を明示拒否（#422 / #430） | [方式比較と共通境界corpus](../docs/explicit-ambiguity-profiles.md)。固定C++23の最小例、独立AST/CST/CP診断、12段入れ子、2〜64候補と65拒否を比較。既存choice順は維持。一般左再帰・nullable左cycleは生成前拒否、GLR/Earley/全候補forestは未対応 |
| namePredicate・version付き名前判定 | immutable snapshot の TYPE/VALUE/resolved gate を両frontend・生成器・runtimeへ接続（#425 / #427） | [名前snapshot契約](../docs/versioned-name-snapshots.md)。UNKNOWN・version不一致を拒否し、rollback・memo・子entry・recovery整合と資源上限を比較。外部I/Oや型推論は行わず、完全なC++意味論は未対応。Java/Rustの再帰guard差は上記境界表に記録 |
| 診断policy・FIRST候補除外 | Java/RustのDetailed/失敗時詳細化/Auto、候補除外を実装 | 再実行可能性・診断参照・custom parserの宣言に依存。低水準APIと生成入口を区別し、意味診断と失敗位置を保持。任意副作用の安全性や常時高速化は保証しない（同ノート ケース25〜27） |
| optional・0/1回以上・bounded repeat・separated | UBNF生成・Option/Vec AST・mapper・Semantics実装 | 70ケースの受理一致、20成功ケースのJava/Rust AST全field・全span一致、Rust評価値oracle。外側captureの入れ子container型は両backendとも未完了 |
| ANY/EOF/EMPTY/CHAR_RANGE/NEGATION/UNTIL/LOOKAHEAD/NEGATIVE_LOOKAHEAD | UBNF生成とJava互換Expr・両cursorを実装 | 48文法・109入力でprefix受理/両cursorと全入力受理が一致、受理56入力のAST/spanも独立fixtureに一致。汎用consume/invert伝播と全CST同値は未完了 |
| error | `ERROR(...)`をJava/native Rust両hostから生成（#329）。非消費で必ず失敗し、expected候補を提示 | [12文法19入力の契約・corpus・再現手順](../docs/error-elements.md)。受理/両cursor、CP位置と明示hint、AST/span、状態rollback、診断/memo modeを比較。native候補の表示差は個別oracleで保持。下記の回復とは別 |
| Number token | 限定生成済み | 不完全指数の診断差を記録済み。数値型・overflow・triviaを実言語仕様に合わせる |
| Identifier・Single/DoubleQuoted・EndOfSource token binding | UBNF生成・runtime実装 | 22文法78入力の受理/両cursor比較と受理48 AST/spanのfixture。ASCII identifier、生escape、single quoteだけ除去するJava mapper契約。tinyexpression文字列評価は別途検証 |
| tinyexpression StringLiteral token binding | exact FQNを両生成経路で対応（#168） | 固定した実tinyexpressionクラスと共通corpusで字句・両cursor・AST/spanを比較。文字列評価の意味論は別途検証 |
| tinyexpression CodeStart/CodeEnd token binding | exact FQNを両生成経路で対応（#170）。内部triviaなしの原子的字句 | 実tinyexpressionクラスと共通corpusで行頭/行末・両cursor・AST/spanを比較。codeblockの実行やJavaのparser tag/CST構造同値は含まない |
| tinyexpression LongCodeBlock token binding | exact FQN を Java/native Rust 両生成器から `Expr::LongCodeBlock` に写像（#316 / tinyexpression#232） | N >= 4、同幅の単独終端行、opaque 本文、Unicode/CRLF/CR、atomic failure、実 Java parser と14文法86入力の比較。本文の AOT 実行は TinyExpression 側の別 API |
| target-neutral token adapter | `ADAPTER(id, version)` / `@tokenAdapter`、4 builtin と独自 Java class / Rust function 登録を両hostから生成（#158） | [契約と検証](../docs/token-adapters.md)。46位置診断ケース、16 custom実行、実Tiny 125入力×FQN/adapter。外部実装の意味的同値性・任意コード翻訳は保証しない |
| CASE_INSENSITIVE・REGEX・任意外部token | 未対応（上記の明示binding / adapterを除く） | Unicode/regex方言を確定。任意Java parserクラスはRust実装と明示adapterを要求 |
| global/rule whitespace・interleave | javaStyle/none・interleave と non-nullable な名前付き lexical trivia を両生成経路で対応。global/rule の相対・package import、tokenStream 全4modeの global trivia に対応（#172 / #393 / #432） | [trivia契約](../docs/rule-trivia.md)と[tokenStream契約](../docs/token-stream.md)。親子の独立設定・override・token内部・nullable拒否・原文位置を共通corpusで比較。任意host parserのtrivia登録、global `@comment`、lexicalContext のない tokenStream の rule-local whitespace/interleave と tokenStream の全 profile における rule-local 名前付き定義は対応範囲外 |
| scoped lexical context | 明示 terminal 集合を両frontend・生成器・runtimeで切替。global named trivia と rule-local javaStyle/none の合成、4 lexing modes を対応（#377 / #467） | [契約と共通corpus](../docs/contextual-lexing.md)。raw CP/UTF-16 位置・AST/CST・cache policy・失敗/choice/独立子entryの復元を比較。inventory は global 定義の原文一覧。動的indent/ASI・完全なTSX構文は未対応 |
| imports・複数grammar・namespace | 宣言的token moduleの相対import・aliasと、builtin/local/HTTPSの固定package・manifest/lock/cache・出典表示に両hostで対応（#368 / #401 / #407 / #412） | [package契約](../docs/ubnf-packages.md)。明示resolveだけが取得し、通常生成はオフライン。exact版/hash・推移依存・競合/循環/境界外参照拒否を比較。rule/evaluator/host-code importと一般の複数grammar合成は未対応 |
| mapping・capture・source-preserving AST | scalar/optional/list/groupと混在Text/Node値を生成。Rustはspan付きAstValue、JavaはObject系。shared mappingの型joinは宣言順非依存。単一capture内の複数semantic子とhelper内部optional/repeatのcardinality・全値収集を両言語で実装（#160）。純mapped aliasもNodeを保持し、直接/多段/group/delimiter・複数targetの値と位置を両backendで比較（#163）。Java位置binding #116・zero-field生成 #129・複合text capture #132・混在値 #156を修正 | 入れ子container型、再帰的unmapped rule、typeof/commonField/enum、全Java capture規則との互換性 |
| evaluator dispatch・網羅性 | 限定範囲で生成済み | 新nodeのE0004/E0046検証を拡張。eval annotation、型境界、短絡評価を追加。Java sum/dotted evaluatorの不具合 #130 は修正済み |
| 原文保持rename・format・code action | token/trivia所有とchecked plan、完全symbol identityに基づく定義/import名・明示alias rename、snapshot付きquery policyをJava/Rustへ実装（#382） | [契約・共通fixture](../docs/source-preserving-edits.md)。shadowing/別module/文字列・CRLF/Unicode・部分入力・3段source mapを保持。FORMATは明示policyによる既存空白編集。workspace batchと単一host queryを区別し、生成LSP/Playground登録は別の統合単位 |
| 文法進化の API 影響レポート | Java / Rust target の AST / evaluator schema と read-only `impact` JSON（#159） | [契約と再現手順](../docs/api-impact-report.md)。13変更×LF/CRLF、Rust target の両host一致、実 javac/rustc の旧・新利用者検証。全API/ABIや意味的互換性の保証ではない |
| leftAssoc/rightAssoc/precedence | canonical leftAssocとrightAssoc、precedence metadata、schemaを統合したshared mapping、混在factorを生成 | 左辺＋op/right列と右再帰、文法階層による優先順位を検証。非canonical右結合形、Javaの特殊null/literal leafとRust AstValueの構造互換は未完了。Java raw CST反復欠落 #138・右結合 #139 は独立修正 |
| backref・MatchedToken相当 | context-wide replay、scope付き文法の参照検証、scopeなしUBNFのrule-local capture比較（#325）を区別して対応 | [比較の契約・移行・共通corpus](../docs/generated-scope-effects.md)。capture比較は構文認識ではなく意味診断。replayのUBNF接続、名前の寿命・入れ子・伝播の全互換、コピー言語のpositive/negative testは未完了 |
| PropagationStopper・consume/invert・virtual token・metadata | 明示 runtime API の word / sequence / choice / invert / consume-invert stopper と4種 token、portable string metadata / 関連 ID を追加（#438）。Java 任意 Object / 関連 Token の既存 API は維持 | [契約・独立共通 fixture](../docs/portable-token-metadata.md)。Java/Rust 実 parser の source / 両 cursor / CP span / 失敗と rollback / owned retention / 失効 ID を検証。任意 custom parser の全伝播、全 combinator / annotation / UBNF 接続と stateful memo parity は未完了 |
| 多段階解析と前処理 | Java / Rust の bounded phase pipeline、条件付き phase、source map を運ぶ実生成 parser の共通例（#380） | [契約](../docs/analysis-pipeline.md)と[実行例](../examples/preprocessing/README.md)。前方参照・相互 field・include/設定変更・遅延/循環/打切り/修復・元文書 CP 診断を比較。任意 macro/C preprocessor 構文や無制限の固定点 solver は含まない |
| 宣言的な意味規則 | Java / Rust の同じ JSON loader・IR・generated CST 実行と region query provider（#381） | [契約・API・共通 corpus](../docs/declarative-semantics.md)。型・scope・宣言・参照・引数期待型・補完・診断、部分入力、文法進化、CP span と package/project identity を照合。portableNominal/1 のみで、完全な各言語意味論や任意 AST の自動解釈は含まない |
| lexical scope store・宣言/参照/semantic diagnostics | Java rollback修正とRust公開ParseContextへのtransactional store接続（#174）、owned Treeへのsnapshot | scope depth/lookup/shadowing/イベント履歴/失敗時復元を共通operation corpusで比較。AST/IDEへのmetadata搬送は未完了 |
| scopeTree/declares/スコープ参照 | 両frontendから生成、Java capture-site選択も修正（#176）。mode/description metadata保持、CP位置、nested/repeated captureとrollbackを比較 | 両modeは解析時stack。評価時dynamic環境やclosure、LSP/DAP利用は未対応 |
| skip | Java の存在しない AST 型参照を修正し、両hostの Rust 生成に AST subtree 投影境界を実装（#323） | [契約・移行・共通corpus](../docs/skip-ast-projection.md)。構文・capture・scope/rollback は保持し、明示captureはtext。rootのsyntax成功とASTなしのmapping失敗を区別 |
| catalog/doc/simple等 | `@catalog` は両frontendから静的 `CatalogSpec` を生成（#180）。parser/AST/evaluatorには作用しない。doc/simpleは未対応 | catalog resolver・context別LSP利用とprotocol test、残るannotationのJava実動作を検証 |
| recovery | Java/Rust runtimeと両hostの`@recovery(sync/auto/skip)`生成、CP span/message付きCST marker（#331） | [契約・共通corpus・再現手順](../docs/error-recovery.md)。18文法43入力×6設定で回復情報、両cursor、rollback、正常ASTと回復時mapping拒否を比較。部分/error AST型と汎用伝播は未対応 |
| incremental cache | Rust未対応 | 編集差分と全再解析の一致、位置・診断・利用者状態・回復情報の無効化 |
| SafeFailures memoの保持窓 | Javaの保持窓に対応するRustのbucket解放と、両言語の遠距離backtrack後の再保存を実装（#290） | 共通6入力で受理・consumed/farthest位置を照合、各言語で窓OFF/ONの診断・hit数一致、32,000 CP人工負荷の追加heap peak約95.5%削減。任意文法のhit不変・時間計算量保証・stateful memo parityの完了は含まない |
| LSP/DAP | Classic Rust経路は未対応。下流TinyExpressionのVSIX/LSP/DAPはJavaサーバーを使う | Rust AST/eval traceのspan保持は実装済みだが、Rust製LSP/DAPとは別。UTF-16変換、diagnostics/completion、breakpoint/step/変数表示を実protocolで検証する必要がある |
| tinyexpression-rs | 下流にparser・typed AST・scalar/context付き評価器・FormulaInfo loader・CLIを実装済み。parserはubnfc生成物 | Java goldenとの値/数値bits/失敗種別、tree/closure/traceを比較する有限corpusがある。Java任意classや全バックエンド互換の証明ではない。root-family retryは残る |
| rustcodeblock | 下流に本文/span保持、既定拒否、明示許可付き`tinyexpression-aot`と型付きnative bindingを実装済み | 実Java/Rust本文を共有oracleでcompile/execute、rustc診断を元のCP位置へ戻す。通常parse/evalはcompiler非起動。sandboxではなく、Java自動翻訳・FormulaInfo全体AOT・AOTのLSP/DAP統合は未対応 |
| ネイティブ配布 | 本repoはgeneratorと縮小文法CLI。下流は`tinyexpression` / `tinyexpression-aot`、C ABI、wasmのbuild・smoke・配布経路を持つ | 下流CLIのstdin/file/JSON/終了コードを検証。確認したnative archiveはLinux x86_64用であり、全OS・完全static・全tagの配布実績は主張しない |
| Rust製UBNF frontend・native generator | syntax frontend全18annotation/12token/9element種、対応範囲のlowering/CLI/5module emitterを実装 | Java/nativeの既存・混在値文法で全5file一致、空PATHの生成/check、手書き/symlink保護。全backend機能の生成完了とは区別。frontend既知差と構造分析上限を文書化 |
| 入力DSLの機械語生成 | DSL全式の専用machine-code loweringは未対応 | 下流AOTはRust本文をcompile/linkするが、固定したDSLソースは実行時にparseしtyped-AST評価器で評価する。generator/評価CLI/native本文のバイナリ化とは区別する |

## 下流TinyExpressionの現状監査（2026-09-30）

以下は上記の固定revisionに対する証拠であり、初期基準の全機能が移植されたという宣言ではない。
共有worktreeの変更には触れず、隔離したcheckoutで検証した。

| 対象 | 今回確認した証拠 | 証明しない範囲 |
|---|---|---|
| Rust workspace | Rust 1.85.0で109テスト成功、失敗・ignoreなし。CLI、評価器、native binding、AOTの実compiler試験を含む | 全OS、すべてのJavaバックエンド、任意の利用者コード |
| Java側の再検証 | Classic `0b79ca4`を隔離Maven repositoryへinstall後、下記の選択17テスト成功、失敗・error・skipなし。本文の非実行契約、実Java本文、評価oracle、Classic生成parserの共有fixtureを含む | Java全suiteや全backendの検証ではない |
| 評価互換 | `java-diff/golden` の953式・12,609行をRust tree/closure/traceと照合。型・数値bits・文字列/boolean・Java例外種別を比較 | 今回Java goldenを再生成したわけではない。random()の数値は一致対象外。Javaクラス呼出しの一部はRust hostの代替実装 |
| FormulaInfo | 保存済みJava golden 53行とのload/eval比較、文書内CP位置の試験 | FormulaInfo全体のAOT、任意JVMクラスのロード |
| Rust本文の実行 | `native-bindings/cases.json` の29ケースをJava実本文とRust実本文でcompile/executeし、同じ期待値・失敗種別と比較 | Javaソースの自動翻訳ではない。未登録classの1ケースだけは既存stub機構で状態を構成 |
| JVMなしCLI | 空PATH・存在しないJAVA_HOMEで`(1+2)*3`を評価して9、`"こんにちは😀"`をparseしてroot span `[0,8)` | compilerや通常のOS動的ライブラリも不要という意味ではない。AOTのbuildにはrustc/linkerが必要 |
| 生成物の整合性 | P4/FormulaInfoのvendored SHA-256 manifest、compat生成物、pin定数が一致 | private生成器による再生成は実行していない。manifest一致だけでは生成器の正しさを証明しない |
| 配布・IDE | v2.0.0 ReleaseにLinux x86_64 CLI/libとwasmのassetが存在。現行sourceのAOT build/配布経路も確認。VSIXのLSP/DAP起動先はJava | 今回Release assetをダウンロードして動作検証したわけではない。全tagの配布やRust製LSP/DAPの証明ではない |

公開ソースの根拠:

- [評価差分テスト](https://github.com/opaopa6969/tinyexpression/blob/19945446216b7f0d6d8817b353385cbb96bcee25/rust/tinyexpression-rs/tests/java_differential.rs)と[FormulaInfo比較](https://github.com/opaopa6969/tinyexpression/blob/19945446216b7f0d6d8817b353385cbb96bcee25/rust/tinyexpression-rs/tests/formula_info.rs)。Rust `Program::compile`はclosure準備であり、式全体の機械語生成ではない。
- [AOT契約・Java/Rust型境界](https://github.com/opaopa6969/tinyexpression/blob/19945446216b7f0d6d8817b353385cbb96bcee25/docs/rust-codeblock-aot.md)、[実compiler試験](https://github.com/opaopa6969/tinyexpression/blob/19945446216b7f0d6d8817b353385cbb96bcee25/rust/tinyexpression-aot/tests/build.rs)、[共通29ケースのRust側](https://github.com/opaopa6969/tinyexpression/blob/19945446216b7f0d6d8817b353385cbb96bcee25/rust/tinyexpression-aot/tests/native_shared.rs)と[Java側](https://github.com/opaopa6969/tinyexpression/blob/19945446216b7f0d6d8817b353385cbb96bcee25/src/test/java/org/unlaxer/tinyexpression/codeblock/NativeBindingConformanceTest.java)。明示許可はsandboxを提供しない。
- [生成物検査](https://github.com/opaopa6969/tinyexpression/blob/19945446216b7f0d6d8817b353385cbb96bcee25/rust/check-generated.sh)、[root-family retry](https://github.com/opaopa6969/tinyexpression/blob/19945446216b7f0d6d8817b353385cbb96bcee25/rust/tinyexpression-rs/src/lib.rs)、[VSIXのJava起動](https://github.com/opaopa6969/tinyexpression/blob/19945446216b7f0d6d8817b353385cbb96bcee25/tools/tinyexpression-p4-lsp-vscode/src/extension.ts)、[v2.0.0 Release](https://github.com/opaopa6969/tinyexpression/releases/tag/v2.0.0)。

### 再現コマンドと検証境界

JDK 21、Maven、Rust 1.85.0、linkerを用意し、冒頭のrevisionを別々のcheckoutへ固定する。
以下の2変数はそれぞれのcheckoutの絶対pathに置き換える。Maven repositoryは隔離し、
同じ`3.1.1`というversion名だけで異なる候補buildを混同しない。

```sh
classic_repo=/path/to/unlaxer-parser-at-0b79ca4
tiny_repo=/path/to/tinyexpression-at-19945446
audit_m2=$(mktemp -d)

cd "$classic_repo"
mvn -B -ntp -Dmaven.repo.local="$audit_m2" \
  -pl unlaxer-common,unlaxer-dsl -am install -DskipTests -Dgpg.skip=true

cd "$tiny_repo"
cargo +1.85.0 test --workspace --locked --manifest-path rust/Cargo.toml
UBNFC_DIR=/nonexistent-ubnfc-checkout bash rust/check-generated.sh
mvn -B -ntp -Dmaven.repo.local="$audit_m2" clean test \
  -Dgpg.skip=true -Dtinyexpression.skipRailroad=true \
  -Dtinyexpression.rust.shared=true \
  -Dtest=NativeBindingConformanceTest,CodeBlockSourceTest,CodeBlockNoCompilerTest,EvalContextContractTest,P4RustNumericEvaluatorOracleTest,P4RustScalarEvaluatorOracleTest,P4RustRootExpressionAcceptanceTest,P4RustSharedFixtureAcceptanceTest

printf '%s' '(1+2)*3' | env PATH='' JAVA_HOME=/nonexistent-jvm \
  "$tiny_repo/rust/target/debug/tinyexpression" eval -
printf '%s' '"こんにちは😀"' | env PATH='' JAVA_HOME=/nonexistent-jvm \
  "$tiny_repo/rust/target/debug/tinyexpression" parse -
```

installではflattenを無効にせず、`${revision}`を解決したPOMを隔離repositoryへ入れる。
`-DskipTests`のinstall成功自体はテスト成功ではない。Rust workspaceの成功と、上記の
Java選択テストの成功だけを今回の実行証拠とする。Java全suite・全backend互換やClassicの
未実装機能まで検証したことにはしない。共有fixtureのJava試験はClassic生成parserを対象とし、
それだけで下流ubnfc parserの全AST同値を証明するものでもない。

## 完了の扱いと順序

公開context/combinator、optional/repeatとtyped ASTを基盤として、次はtoken/annotationとcapture互換性を拡げる。追加実験で見つけたJava numeric capture #115とcapture選択 #116の生成コードは修正した。int変換とRustの字句保持の差は共有corpusで固定し、数値意味論のbackend間統一と外側captureの入れ子container型は未完了事項とする。下流の実装済み評価器・rustcodeblockはその契約とcorpusを利用し、Classic側の未対応と製品側の残る境界を別々に進める。各行を小さなPRに分け、テスト・CI・merge・子issue closeまで行う。全体issueは未対応行を残したままcloseしない。

Javaの継承階層を一対一に移植するのではなく、文法と観測可能な振る舞いを対象とする。JVM任意オブジェクト・reflection・bytecodeのnative直接実行はできないため、Rust側のhost interfaceと移植コードの境界を明記する。差を消して比較を通したことにせず、意図的な差は独立したfixtureにする。

純mapped aliasの型移行（#163）は[API変更と利用側の移行文書](../docs/pure-mapped-alias-migration.md)を参照。
Javaでは従来のStringからObject系のNode保持へ変わるため、文字列依存の利用者は更新が必要。
先行する#165はJavaのpreferred選択Tokenとsource snapshotを原子的に取得する追加APIで、
Rustでは既存のowned AST/spanの後続・並行mapping耐性を検証する。
tinyexpressionはowned source resolverで従来のslice字句処理を維持する。
Javaのpreferred型候補探索自体のRust移植は未対応。

tinyexpressionのStringLiteral対応（#168）は[実クラスとの比較・字句契約](../docs/tiny-string-token.md)を参照。
既存FQNを維持した上で、#158 の [token ID・登録API・位置診断](../docs/token-adapters.md)からも生成・実行を検証する。
CodeStart/CodeEnd対応（#170）の[行境界・字句契約と実行機能との区別](../docs/tiny-code-fence.md)も参照。
rule-level trivia（#172）の[契約と Java global none の移行](../docs/rule-trivia.md)も参照。
transactional scope store（#174）の[rollback契約とruntime API](../docs/transactional-scopes.md)も参照。
生成scope annotation（#176）の[capture・metadata契約とJava移行](../docs/generated-scope-effects.md)も参照。
同名nested/並列captureのAST型・全出現収集の修正（#177）は
[cardinality契約とJava API移行](../docs/nested-capture-migration.md)を参照。
共存するcaptureはlist、排他的choiceはscalar、欠損枝はoptionalとして両言語で比較する。
`@catalog`（#180）の[静的metadata契約と既存Java LSPの限界](../docs/catalog-metadata.md)も参照。
Rustのcontext-aware completion/hoverはまだ未実装であり、metadata生成とLSP対応を区別する。

Classicのparser生成は`Expr`combinator定義を生成し、共通runtimeで実行する。直接コードを出す別engineの存在は、この状態付きruntimeを移植し終えた証拠ではない。Rustでビルドされた実行ファイルであることは、入力式を機械語にコンパイルしていることを意味しない。

移植に伴う追加提案は、両言語を対象とする受け入れ条件付きで追跡する。未実装の計画を上記の対応済み件数には含めない。

- [#157](https://github.com/opaopa6969/unlaxer-parser/issues/157): portability検査と機械可読な診断レポート（上記範囲を実装）。
- [#158](https://github.com/opaopa6969/unlaxer-parser/issues/158): target-neutralなtoken adapter契約（上記範囲を実装）。
- [#159](https://github.com/opaopa6969/unlaxer-parser/issues/159): 文法進化に伴う生成API影響レポート（上記範囲を実装）。
