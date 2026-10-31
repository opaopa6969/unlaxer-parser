# Rust full-spec対応表

親issue: [#111](https://github.com/opaopa6969/unlaxer-parser/issues/111)。限定デモの完成とfull-specの完成を区別する。以下は機能群別台帳であり、全メソッドを監査した完全互換宣言ではない。各群の実装前に受け入れcorpusを追加し、差を明記する。

## 基準と判定

- 継続parity監査基準（2026-10-16）: Java `6816a2a69651ab5819f0397f9cba5510413871df`、JDK21、Rust1.85。Java17固有の互換維持を新機能の制約にしない。公開済み3.3.0と開発中の追加を区別する。#535〜#549、#552、#555等の受け入れ条件を順に解消し、全体の未完了は親#111で継続する。

- 初期互換基準: unlaxer-parser `72de487020cd6321660dc7114f31ae008c629416`、tinyexpression `6c8196b7879abe58d6b739d3e63a16e853745ced`。構文・annotationの列挙元は`unlaxer-dsl/src/main/java/org/unlaxer/dsl/bootstrap/UBNFAST.java`、仕様案は`unlaxer-dsl/specs/`。文書と実装が異なる場合はテストで差を確認し、意図を記録する。
- 現状監査（2026-09-30、#327）: unlaxer-parser `0b79ca4ddcfdde2fbce893bf30f3a64ae2a49d0a`、tinyexpression `19945446216b7f0d6d8817b353385cbb96bcee25`。初期基準からの要求を消したり、別engineの実装でClassicの互換性を証明したことにはしない。
- 「生成済み」は現在の限定UBNFから生成・コンパイル・実行できる範囲。「runtimeのみ」は手書きAPIで使えるがUBNF経路は未対応。「未対応」は今後の作業で、対応済みとして数えない。
- 共通機能はJava/Rust双方で実装・検証する。片側だけの完了は共通機能の完了とせず、言語固有の表現差・制約を対応表とissueに残す。
- 共通corpusは既存`evolution/conformance.json`の37入力×4段階、診断境界は`evolution/diagnostics.json`の8入力。full-spec用corpusは各行の拡張とともに追加する。現在の有限corpusは全言語の互換性証明ではない。
- 人が書いたcorpusに加え、seed付きで生成した文法・入力によるproperty-based差分テスト（#705、[docs](../docs/property-based-testing.md)）を検証手段に使う。Java/native Rustの受理・2つのcursor・rule CSTとspan・`diagnose`の失敗CP位置・`@mapping` AST、memo/診断/cursor reset設定間の一致、rollback・span・決定性・原文再構成の不変条件、Java host/native生成物のbyte一致、左再帰の両frontend拒否を確かめる。CIは固定seed `1..24`。生成対象外の構文は個別corpusが扱い、期待候補文字列の表現差は比べない。見つかった差は個別issue（#706）と`spec-corpus/property-based/`に固定する。全言語互換の証明ではない。

## 二つの対応軸

このrepositoryのRust `unlaxer-runtime` / `unlaxer-generator` は **Classicの状態付きcombinator経路**。
一方、現在の下流 `tinyexpression-rs` は公開repositoryにvendoringされた **ubnfc生成parser** を使い、
`unlaxer-runtime` には依存しない。[選び方](../docs/engine-selection-guide-ja.md)を参照。

TinyExpressionの評価器・CLI・AOTの実在は製品側の到達点として数えるが、Classicの未移植
annotation、`ParseContext` API、consume/invert伝播、incremental、IDE protocolの完了証拠にはしない。
下表は、明記しない限りこのrepositoryのClassic Rust生成・runtimeを対象とする。
「下流」とした行の根拠・再現条件は後段の現状監査に記録する。

## 対応表

Java 公開 parser の宣言単位の棚卸しは [全API対応表](../docs/java-api-rust-parity.md)（#716）を参照する。基準 revision、機械列挙との一致、Rust の利用経路と共通 corpus の有無、未対応の子 issue を記録する。下の機能群表だけで全公開APIの完了とはしない。

以下は開発版の実装範囲であり、公開済み3.3.0の機能一覧ではない。個別PRの実装・共通検証と、CI/mergeを含むissueの完了を区別する。未完了は親#111と記載した子issueで追跡する。

2026-10-17の追加実consumer監査で再現した、flat tokenStreamの直接literal captureがJavaだけnullになる差（#668）と左結合のliteral operand誤選択（#669）は、開発候補で修正し両Rust hostとの共有回帰を通過した。#668は19文法69入力×4 mode×trivia公開有無、#669は21文法69入力で実consumerを検証する。CI/mergeと全体回帰は別の完了条件として残る。非捕捉の固定literal delimiter付き左結合規則は#674の開発候補で両Rust hostへ対応し、7文法27入力×6設定×3consumerで共有回帰を通過した。基準Javaの非捕捉optional/zero-or-more repeat delimiterも#679の開発候補で対応し、固定literal列を1段で包む6文法27入力×6設定×3consumerを検証した。任意helper/choice等までの対応を意味しない。これらを新nestedの共通制約と混同しない。

| 機能群 | 現状 | 検証根拠・残る条件 |
|---|---|---|
| 同一ファイルの複数 grammar | Java 向けと両 Rust host の独立生成、両 host の Rust check・module 配置・衝突拒否に対応（#714） | [配置契約](../docs/multiple-grammars.md)。共有 corpus の実 compile/parse/map/eval・CP span・byte 一致。grammar 間の rule 合成は行わず、impact は単一 grammar のまま |
| Classic LSP構文診断 | Java/Rustで未消費末尾と最遠構文失敗を比較して表示位置を選択（#552） | [表示契約](../docs/classic-lsp-diagnostics.md)。TinyCalc共通20ケースを両生成サーバーの実stdioで検証。UTF-16範囲・offset・必要literalを比較し、public parser primary/farthestと資源失敗は保持。候補全体の表示文言はbackend固有 |
| 最終エラーのrule stack | Detailed時だけ、最終エラーに有効だった規則（外側から、名前と開始CP）を付ける（#703）。確定エラー・ラベル付き失敗は送出時点、通常の失敗は最遠位置に届いた失敗規則呼出し（深い方、同点は先） | [契約・選択規則・コスト](../docs/rule-stack-diagnostics.md)。14 fixture・15入力の手書き期待値をmemo on/off・全診断policyで両runtimeに適用し、memo再生のrebaseを確認。上限32段・省略数・原文CPを保持。失敗したパースだけが対象（成功時は未消費入力用の別API）。TinyCalc 9文書・UBNF `COMMIT`/`LABEL`文法3文書・CRLF分割2文書を両生成LSPの実stdioで`relatedInformation`/`data.ruleStack`まで比較し、property-based testでも両版のstackを照合 |
| Java UBNF frontend → Rust generator | 対応範囲の生成に対応。識別子境界 #131、annotation境界・eval既定値・commonField trivia #538を修正 | 共通positive/negative frontend fixture、両host生成一致、実consumerで検証。構文の受理とtargetの実装対応を区別 |
| literal enum | Java/Rust両hostで型付きscalar/optional/listとroot wrapperを生成（#537） | [契約・命名移行・出現位置](../docs/enum-values.md)。24文法56入力を6設定で実compile/map/evalし、34負例・生成物/impact一致・exhaustive consumer破壊を検証。enum/Text/Node混在とshared mapping、rootとの併用も対応（#539）。追加9文法116入力×6設定で型・値・出現span・DAP AST stepsを確認。typeof混在制約も両hostで対応（#542） |
| typed common fields | `@commonField`を両hostで検証・生成（#541）。Javaは実field型のinterface accessor、Rustはborrowed family view | [契約](../docs/common-fields.md)。10文法30入力・13負例で実compile/eval・CP位置・生成物/API schemaを比較。nested containerのshared/common型は[専用契約](../docs/nested-shared-fields.md)で対応。両版で拒否する組み合わせは#697 |
| numeric captures | Java int / Rust i32のchecked scalar/optional/list mappingを統一（#566） | [契約](../docs/numeric-captures.md)。8文法48入力、上下限/overflow/非整数と失敗CP位置を実consumerで比較。mixed/text aliasは別の型契約 |
| AST family / dotted / shared | `@commonField`の有無によらず具体子variantへ透明投影（#592） | [契約](../docs/family-projection.md)。9文法25入力・不正schema3文法、両host生成一致・実mapper/eval・preferred選択/CP位置。associativeは下記#593 |
| 一階の引数付き規則 | `Name(p1, ..., pn) ::=` と `Name(e1, ..., en)` を両frontendで受け付け（呼出し判定はgrammar単位）、生成前に単相化（#701）。展開名は引数の正規形から決め、同じ引数の呼び出しは共有。runtimeと定義のない文法の生成物は不変 | [契約・停止性の根拠](../docs/parameterized-rules.md)。引数の数・未定義名・引数付き呼出しグラフの循環・展開上限（実引数と本体で65536要素、呼出し位置をまたいだ展開の入れ子128段）・注釈/captureを位置付きで拒否し、展開後の文法に`E-LEFT-RECURSION`等を適用。10文法26入力×6設定（#699のalias透過と入れ子128段ちょうどの境界を含む）、展開後拒否2例、位置付き拒否23例を手書き展開と比較し、Java生成物と両Rust hostの生成物一致・実consumer・check/impactを確認。高階規則・ラムダは範囲外 |
| 値渡しの引数 | 定義の引数に`=`を付けた`Body(=tag) ::=`を両frontendで受け付け、instanceを`CAPTURE_SCOPE(CAPTURE(Body(=tag), 実引数) ...)`、使用を`SAME_AS(Body(=tag))`へ展開（#704）。binding名はUBNFで書けない綴りでユーザーのcaptureと衝突せず、有効範囲は呼ばれた規則に閉じる。runtimeは不変 | [契約・有効範囲の根拠](../docs/call-by-value-arguments.md)。8文法36入力×6設定（名前付き閉じタグ・heredoc・同じ規則の入れ子・入れ子128段ちょうど等）、位置付き拒否33例（宣言順・instance共有に依存しない判定、値渡しの規則の本体・実引数の`CAPTURE`（名前渡しの中継を含む）、値の引数と同名の`SAME_AS`、展開名への参照を含む）を手書きのCAPTURE/SAME_AS文法と比較し、Java生成物（binding名の置換で一致）・両Rust hostの生成物byte一致・実consumerの結果とparse後のcontext状態・check/impactを確認。値渡しを使わない既存文法1424個の構文木・生成物がbaseと一致 |
| left recursion生成境界 | 入力非消費の再帰cycleを両hostで生成前拒否（#588） | [契約](../docs/left-recursion.md)。nullable rule/group/tokenの受理拒否、実consumer、bounded解析を比較。64/96重groupの受理を#618で維持。max=0の実行契約は#598、nullable repeatは#614で対応 |
| preferred AST snapshot | Java既存選択とRust source由来candidate APIを統一（#567）。名前・Java wrapper depth・CP開始位置・preorder tieを比較し原文とASTを保持 | [契約](../docs/preferred-ast-snapshots.md)。20文法25入力、実compile/eval・両host全生成物一致・保持/並行mapping・不正CST/資源上限・typeof差のfingerprint拒否。family/assoc候補は#592/#593で対応。nested containerも[対応形](../docs/typed-nested-captures.md)のpreferred snapshotを検証し、両版で拒否する組み合わせは#693〜#697 |
| mapping-time typeof | Java/Rust両hostで`@typeof`の値型制約を実装（#542）。private/forward capture、複数制約、enum/family/scalar/container、mapped sumを対応 | [契約・IR移行](../docs/typeof-constraints.md)。32文法100入力×6設定、5負例、全Rust生成物一致、値・失敗stage・CP位置を比較。[nested typeof](../docs/nested-typeof.md)（#660）は23文法51入力×6設定×3consumerでprivate/shared/mixed/separated・保持snapshotを比較。学習用独立interpreterは明示拒否 |
| UBNF source snapshot | Java `parseWithSource` / Rust `parse_with_source`。同じparseのowned AST・元入力・code-point位置、capture/typeofの範囲を保持（#318） | [契約と検証](../docs/ubnf-source-snapshot.md)。LF/CRLFの共通corpusで全ノードの位置・元textを比較。import展開後の複数ファイル由来は未対応 |
| 移植可能性チェック | 両hostの `check --target rust` と位置付きJSON（#157）。未対応機能の全出現inventory、対応範囲内は構造検証 | [契約と検証](../docs/portability-check.md)。共通corpus/実P4/注入負例で結果・位置・exit一致、無書込み・外部class非実行。構造検証はfail-first、target Javaや完全移植の保証とは区別 |
| 公開ParseContext・custom parser | runtimeと生成入口を実装。生成grammarは`OnceLock<SharedGrammar>`で1回構築し、並行parseでは不変graphだけを共有（#185）。Java の名前参照・factory 構築は構築時の`RuleNames`で rule ID へ解決し、Java 6経路/Rust 5経路の共通corpusで固定（#845） | 共有入力・Unicode位置・typed状態・CST/captureのrollback、先読み、生成parser混在を継続検証。tinyexpressionでsetup/探索を分離して再測定 |
| literal・参照・sequence・ordered choice・group | 生成済み | optional経由の再帰等も検証し、非消費ループを拒否 |
| 空literal `''` | 通常grammarはJava `WordParser("")`（常に失敗）、tokenStream/lexicalContextはJava `Lexing.LiteralParser("")`（幅0成功）として両host生成（#713）。手書き`Expr::literal("")`のPEG成功は維持 | [契約](../docs/empty-literal.md)。19文法80入力でprefix両cursor・Classic4mode・診断6設定・rule stack（失敗時・入力残り）・AST/CST・rollback・非BMP/EOF・反復停止・COMMIT/LABEL/CATCH・@autoCommitとの組み合わせを、Java生成とJava/native両hostのRustを別compile/runして比較。`P-EMPTY-LITERAL`は廃止。非空literalのtokenStream Classic反転差は範囲外 |
| Classic汎用token mode | Javaの4種kind・invert・stopperをRust Expr/Rule/custom entryへ伝播（#571）。Projected sourceを保持 | [契約](../docs/classic-token-propagation.md)。共通166入力・lexical12・state11を各6設定で比較。Tiny fenceは#573、observer失敗は#564、owned capture/replayは#590で実装。UBNF接続は[#591の契約](../docs/general-owned-capture-syntax.md)、診断入口は[#655の契約](../docs/prior-matched-diagnostics.md)で比較。#545は完了済み。残る公開APIの監査は#716と[既知差台帳](../docs/parity-ledger.md)で継続 |
| Classic sequence trivia source | 外側triviaもJava同等のdelimiter graphで収集し、明示Classic CSTからASTへの原文・CP範囲を一致（#606） | [契約](../docs/classic-sequence-trivia.md)。共通81行、8文法32入力、4kind/invert/rollback/資源上限を検証。通常native scannerとvirtual projectionを維持 |
| zero-bound repetition / negative zero | 一般Repeat(0,0)は子・terminatorを評価せず両cursor/stateを保持（#598）。Java Zero/LazyZero/EOF と Rust ZeroMatch は従来の負の検査を維持 | [契約](../docs/zero-repetition.md)。共通bounded192行、legacy48行、生成22文法、invalid bounds4文法、既存EOF32観測と資源上限をJava21/Rust1.85で検証 |
| NonOrdered runtime | Java/Rust公開APIで各child出現を一度ずつgreedy選択（#536）。Java失敗候補の状態漏れも修正 | [共通59ケース](../docs/nonordered-runtime.md)を各6設定で検証。実入力順CST/capture・両cursor・失敗時状態復元。宣言順優先であり、全順列backtrackingは行わない。新UBNF構文なし |
| longestChoice/predictiveChoice | 両frontendから生成、Java/Rust runtimeを実装 | 最大消費・同長の宣言順・敗者のrollback、保守的FIRSTと全候補失敗時の診断を検証。最長選択は意味論、予測選択は最適化であり、通常choiceやroot retryの解消とは別（[実践ノート](../docs/performance-tuning-ja.md) ケース1・2） |
| uniqueLongestChoice・曖昧性境界 | 両frontend・生成器・runtimeで唯一の最大一致を選択し、同率・空一致を明示拒否（#422 / #430） | [方式比較と共通境界corpus](../docs/explicit-ambiguity-profiles.md)。固定C++23の最小例、独立AST/CST/CP診断、12段入れ子、2〜64候補と65拒否を比較。既存choice順は維持。一般左再帰・nullable左cycleは生成前拒否、GLR/Earley/全候補forestは未対応 |
| namePredicate・version付き名前判定 | immutable snapshot の TYPE/VALUE/resolved gate を両frontend・生成器・runtimeへ接続（#425 / #427） | [名前snapshot契約](../docs/versioned-name-snapshots.md)。UNKNOWN・version不一致を拒否し、rollback・memo・子entry・recovery整合と資源上限を比較。外部I/Oや型推論は行わず、完全なC++意味論は未対応。Java/Rustの再帰guard差は上記境界表に記録。名前述語の宣言前検証と診断優先順位は共通51入力（rootあり/なし）で比較する（#858、[実行契約](../spec-corpus/execution/README.md)） |
| 診断policy・FIRST候補除外 | Java/RustのDetailed/失敗時詳細化/Auto、候補除外を実装 | 再実行可能性・診断参照・custom parserの宣言に依存。低水準APIと生成入口を区別し、意味診断と失敗位置を保持。任意副作用の安全性や常時高速化は保証しない（同ノート ケース25〜27） |
| nullable Classic Repeat | 通常Repeatを両hostから生成し、Java同等に成功したzero-progress iterationを一度数えて停止（#614） | [契約](../docs/nullable-repetition.md)。11文法30入力×6で実parse/map・typed list・両host byte一致、96state行×6・sticky資源失敗を比較。purePEG/lexical契約は維持。nullable separationも[#616の契約](../docs/nullable-separation.md)で対応 |
| optional・0/1回以上・bounded repeat・separated | UBNF生成・Option/Vec AST・mapper・Semantics実装 | 70ケースの受理一致、20成功ケースのJava/Rust AST全field・全span一致、Rust評価値oracle。外側captureは明示`@nestedCaptures`の[型契約](../docs/typed-nested-captures.md)で対応。nested typeofは#660で対応。既存auto-literal leafとnested manual owner、およびauto-passthrough ownerとnested manual leafの評価は#673の共通corpusで792観測・1080回のトップレベル評価を検証。非再帰の透過aliasを経由するnestedは両版で対応。nested所有ruleのauto-eval/assoc、tokenStreamとの併用、循環helper経由は両版で位置付き拒否（#694/#695/#696） |
| ANY/EOF/EMPTY/CHAR_RANGE/NEGATION/UNTIL/LOOKAHEAD/NEGATIVE_LOOKAHEAD | UBNF生成とJava互換Expr・両cursorを実装 | 48文法109入力でprefix/両cursor/全入力受理、受理56入力のAST/spanを比較。Classic一般modeは#571、historical EOF/Zeroは#598。全CST形状の同一性は主張しない |
| positive assertion / match-only | Java PegLookaheadとRust ahead()は純PEG境界。Java MatchOnlyとRust .match_only()はClassicのmatched/token公開境界 | [PEG契約](../docs/peg-assertions.md)の22入力・4状態traceと[Classic契約](../docs/classic-token-propagation.md)を各6設定で検証。[旧入口の監査](../docs/positive-assertion-contracts.md)18入力・retry3入力の観測も維持。[prior-matched診断](../docs/prior-matched-diagnostics.md)は同じClassic/transaction境界で比較し、native PEGのsource-frontierとの差をAPI契約として保持（#655）。grammar呼び出し（全入力・`parse_grammar`）の失敗とrule frameはJavaと同じmax(consumed, matched)で測る（[#706](../docs/prior-matched-diagnostics.md#grammar-呼び出しの-frontier706)）。#545は完了済み。残る公開APIの監査は#716で継続 |
| error | `ERROR(...)`をJava/native Rust両hostから生成（#329）。非消費で必ず失敗し、expected候補を提示 | [12文法19入力の契約・corpus・再現手順](../docs/error-elements.md)。受理/両cursor、CP位置と明示hint、AST/span、状態rollback、診断/memo modeを比較。native候補の表示差は個別oracleで保持。下記の回復とは別 |
| Number token | 限定生成済み。不完全な指数（`2e`・`2e+`）は両hostとも仮数部だけを受理し、失敗位置は指数記号・符号の後（`R0 ::= NUM 'a'` に `2e` で CP2、[#717](https://github.com/opaopa6969/unlaxer-parser/issues/717)） | 実Java `NumberParser` を基準にした共通oracle `conformance/number-token/cases.tsv`（36入力×2 kind×反転、memo・診断・reset の全設定。`NumberTokenOracleTest` に `-Dnumber.oracle.write=true` で再生成）を Java とRust（native・Classic token mode・grammar入口）で比較。仮数部末尾・`.`・`EParser`・指数末尾の内部失敗と負の先読みの hint も、別の共通oracle `conformance/number-token/diagnostics.tsv`（8形×memo OFF/SAFE_FAILURES、記録順と rule stack を含む）で Java と一致（[#752](https://github.com/opaopa6969/unlaxer-parser/issues/752)）。SAFE_FAILURES で負の先読み内で失敗した Num の failure memo を Java だけが再生する差は [#794](https://github.com/opaopa6969/unlaxer-parser/issues/794) で追跡。PBT corpus の `number-incomplete-exponent` 系4例（trivia・Classic mode・数値field）は除外なしで一致。数値型・overflow・triviaを実言語仕様に合わせる |
| Identifier・Single/DoubleQuoted・EndOfSource token binding | UBNF生成・runtime実装 | 22文法78入力の受理/両cursor比較と受理48 AST/spanのfixture。ASCII identifier、生escape、single quoteだけ除去するJava mapper契約。tinyexpression文字列評価は別途検証 |
| GraphemeParser・型付きG位置 | Unicode17 extended grapheme分割・snapshot境界表・strict/floor/ceiling変換・両host token binding（#510） | [契約と公式766ケース/共通fixture](../docs/grapheme-coordinates.md)。CP cursor/span、原文、CRLF、混在/rollback/先読み、生成ASTを検証。表示幅/正規化とAstValue意味投影は別契約 |
| tinyexpression StringLiteral token binding | exact FQNを両生成経路で対応（#168） | 固定した実tinyexpressionクラスと共通corpusで字句・両cursor・AST/spanを比較。文字列評価の意味論は別途検証 |
| tinyexpression CodeStart/CodeEnd token binding | exact FQNを両生成経路で対応（#170）。内部triviaなしの原子的字句 | 実tinyexpressionクラスと共通corpusで行頭/行末・両cursor・AST/spanを比較。codeblockの実行やJavaのparser tag/CST構造同値は含まない |
| Tiny fenceのClassic mode | CodeStart/CodeEndのJava graphとLongCodeBlockのatomic原文tokenを4kind/invertへ対応（#573） | [契約](../docs/tiny-classic-modes.md)。実Tiny固定revisionと744入力×6で両cursor/result/leaf/診断位置を比較、trace有無・stopper・2MiB資源境界、既存両host生成corpusも検証 |
| tinyexpression LongCodeBlock token binding | exact FQN を Java/native Rust 両生成器から `Expr::LongCodeBlock` に写像（#316 / tinyexpression#232） | N >= 4、同幅の単独終端行、opaque 本文、Unicode/CRLF/CR、atomic failure、実 Java parser と14文法86入力の比較。本文の AOT 実行は TinyExpression 側の別 API |
| target-neutral token adapter | `ADAPTER(id, version)` / `@tokenAdapter`、4 builtin と独自 Java class / Rust function 登録を両hostから生成（#158） | [契約と検証](../docs/token-adapters.md)。46位置診断ケース、16 custom実行、実Tiny 125入力×FQN/adapter。外部実装の意味的同値性・任意コード翻訳は保証しない |
| `CI('word')` | Unicode17 simple upper→lower比較、Java/native両生成経路とruntime（#533、3.3.0公開後の開発版） | [契約と共通fixture](../docs/case-insensitive-tokens.md)。原文・CP spanを保持、非空literal、full folding/NFCなし。生成Javaも固定版へ移行し、手書きIgnoreCaseWordParserのJDK依存比較は維持 |
| REGEX・任意外部token | 開発版はREGEX実行を廃止し、両frontend/生成器がE-REGEX-RETIREDで拒否（#612）。代替は宣言的字句コンビネータ | [移行契約](../docs/regex-retirement.md)。12文法35入力と7拒否ケースでcode/位置・両host生成一致・実consumerを検証。正規表現のbacktrackingを再現する自動変換ではない。任意外部parserはRust実装と明示adapterを要求 |
| Classic atomic trivia | Identifier/Number/Quotedの内部graphを外側triviaから分離しJavaの原子性と一致（#601） | [契約](../docs/classic-atomic-trivia.md)。52入力×6と8文法32入力、trace/宣言trivia/復元を比較。明示Classic外側sourceも#606で対応 |
| global/rule whitespace・interleave・global comment | javaStyle/none・interleave と non-nullable な名前付き lexical trivia を両生成経路で対応。global/rule の相対・package import、tokenStream 全4modeの global trivia に対応（#172 / #393 / #432）。global `@comment: { line: '//' }` を Java の CPPComment と同じ cursor・診断位置で両生成経路に対応し、rule 優先順位を Java でも局所化（#712） | [trivia契約](../docs/rule-trivia.md)と[tokenStream契約](../docs/token-stream.md)。親子の独立設定・override・token内部・nullable拒否・原文位置を共通corpusで比較。global comment は8文法54入力と無効設定11件を Java・両Rust host で実行比較。`line: '//'` 以外の comment 形式と名前付き global whitespace との併記は両版で位置付き拒否（javaStyle と併記した場合は 3.3.0 互換で受理し、`W-COMMENT-IGNORED` を同じ span で報告）。任意host parserのtrivia登録、lexicalContext のない tokenStream の rule-local whitespace/interleave と tokenStream の全 profile における rule-local 名前付き定義・`@comment` は対応範囲外 |
| scoped lexical context | 明示 terminal 集合を両frontend・生成器・runtimeで切替。global named trivia と rule-local javaStyle/none の合成、4 lexing modes を対応（#377 / #467） | [契約と共通corpus](../docs/contextual-lexing.md)。raw CP/UTF-16 位置・AST/CST・cache policy・失敗/choice/独立子entryの復元を比較。inventory は global 定義の原文一覧。動的indent/ASI・完全なTSX構文は未対応 |
| imports・複数grammar・namespace | 宣言的token moduleの相対import・aliasと、builtin/local/HTTPSの固定package・manifest/lock/cache・出典表示に両hostで対応（#368 / #401 / #407 / #412） | [package契約](../docs/ubnf-packages.md)。明示resolveだけが取得し、通常生成はオフライン。exact版/hash・推移依存・競合/循環/境界外参照拒否を比較。rule/evaluator/host-code importと一般の複数grammar合成は未対応 |
| mapping・capture・source-preserving AST | scalar/optional/list/groupと混在Text/Node値を生成。Rustはspan付きAstValue、JavaはObject系。shared mappingの型joinは宣言順非依存。単一capture内の複数semantic子とhelper内部optional/repeatのcardinality・全値収集を両言語で実装（#160）。純mapped aliasもNodeを保持し、直接/多段/group/delimiter・複数targetの値と位置を両backendで比較（#163）。Java位置binding #116・zero-field生成 #129・複合text capture #132・混在値 #156を修正 | 再帰unmapped helperは[共通契約](../docs/recursive-helpers.md)で対応（#582）。14文法40入力×6設定、失敗後状態/資源制限も比較。明示nested containerは上記の型契約・mapped/mixed/shared/separated/typeofに対応。非再帰の透過aliasを経由する形は両版で対応し（[契約](../docs/typed-nested-captures.md#透過-rule-alias)）、循環helperやcaptureを含むhelperの経由は両版で位置付き拒否して既存flat helperの投影と区別する。未変換left recursionは#588で拒否 |
| evaluator dispatch・網羅性 | Java/Rustの自動literal/passthrough/conditional/binary/variableとmanual/unknown義務を実装（#543）。checked conversionと短絡・失敗順序を保持 | [契約・移行](../docs/evaluation-strategies.md)。56入力×6設定、13不正文法、実compile/eval/負compile、両Rust host source/API schema一致。合法Java overloadのinspectionと個別originも#574で対応 |
| 原文保持rename・format・code action | token/trivia所有とchecked plan、完全symbol identityに基づく定義/import名・明示alias rename、snapshot付きquery policyをJava/Rustへ実装（#382） | [契約・共通fixture](../docs/source-preserving-edits.md)。shadowing/別module/文字列・CRLF/Unicode・部分入力・3段source mapを保持。FORMATは明示policyによる既存空白編集。workspace batchと単一host queryを区別し、生成LSP/Playground登録は別の統合単位 |
| 文法進化の API 影響レポート | Java / Rust target の AST / evaluator schema と read-only `impact` JSON（#159） | [契約と再現手順](../docs/api-impact-report.md)。16変更×LF/CRLF、Javaの合法overload保持とsignature別差分（#574）、Rust target の両host一致、実 javac/rustc の旧・新利用者検証。全API/ABIや意味的互換性の保証ではない |
| leftAssoc/rightAssoc/precedence | shared literal spine・mixed operand・direct mapped node・scalar・preferred候補を両hostで生成（#593） | [契約](../docs/associative-projection.md)。初期13文法39入力・拒否3・実DAP consumerに加え、#669のliteral capture修正後は21文法69入力を両host実consumerで比較。固定literal delimiterは#674、固定literal列の1段optional/repeat delimiterは#679の共通corpusで対応。合成leafのみNULLABLE_NODE/NullableNodeのNone、実leftは必須。公開IR/生成field consumerは追従が必要。`@nestedCaptures`規則との併用は両版で位置付き拒否、拡張は#695 |
| nested capture runtime boundary | Java CaptureBoundaryParser/CaptureSnapshot と Rust CaptureBoundary/capture_tree::snapshot（#609）。value/optional/listのCST境界とimmutable snapshot | [契約](../docs/nested-capture-boundaries.md)。共通104行×memo2×diagnostics3、4kind/空・欠損/Unicode/Projected/Generated/rollback・資源境界を検証。入力長比例の追加座標表を作らず必要端点だけを変換。生成nested schema/mapper/manual eval/commonField/preferredは下記の対応形で検証。typeofは[#660の契約](../docs/nested-typeof.md)で対応。nested所有ruleの`@eval`は#694 |
| typed nested capture | `@nestedCaptures`でOptional/Listの全階層、text/i32/node/enum/mixed葉、shared/commonField、separated itemを両hostから生成（#619/#626/#637/#646/#653） | [型契約](../docs/typed-nested-captures.md)、[mapped葉](../docs/nested-mapped-leaves.md)、[shared型](../docs/nested-shared-fields.md)、[separated](../docs/nested-separated-captures.md)。独立期待値で実compile/map/manual eval・原文CP・空/欠損/重複・preferred・資源/失敗後状態を比較。[mixed葉](../docs/nested-mixed-values.md)は20文法54入力×6設定×3consumerで値・CP・DAP・separated合成を確認。[nested typeof](../docs/nested-typeof.md)は#660で対応。[透過alias](../docs/typed-nested-captures.md#透過-rule-alias)は両版で対応。nested ownerの`@eval`/assoc/tokenStream等は両版で位置付き拒否し、拡張は#693〜#697。通常auto-eval leafとの既存合成は[共通corpus](../spec-corpus/nested-auto-eval/README.md)で実評価を検証（#673） |
| public CST mapping | Java/Rustの公開mapper入口で原文・CP境界・木構造と探索/出力予算を検証（#635） | [契約](../docs/public-mapper-cst.md)。caller改変CST、cycle、長いtext、不正span、正常DAG・flatten・非包含spanを両host実consumerで検証。#680は通常木の反復探索をDFS区間索引へ置換し、大規模flattenを既存上限内で検証する。異親DAGの上限付き探索と実投影のwork/text上限は維持。RSS全体上限ではなくmapperの構造的予算 |
| parser failure publication | Javaの通常child例外は元例外を再送し、外側parse公開境界の登録状態を復元（#649）。両版のresource abortは復元後もsticky（#628） | [通常例外](../docs/ordinary-parser-exceptions.md)・[資源中断](../docs/resource-abort-publication.md)。既存observer無効化と区別し、共有状態oracle/実生成consumerで再試行・両cursor・CST・metadata・owned値を比較。Rust panic後のcontextは破棄。未登録の外部I/Oはrollback対象外 |
| cut・labeled failure | Java Commit/Label/CatchLabels と Rust commit/label/catch_labels、UBNF `COMMIT` / `LABEL` / `CATCH`（#700）。確定エラーは recovery 境界か最上位だけ、ラベル付き失敗は名指しした CATCH だけが捕まえる | [契約](../docs/cut-and-labeled-failures.md)。runtime の共通 45 入力・4 状態を各 6 設定で比較。UBNF 9 文法 25 入力を Java 生成物と両 host の Rust 生成物（byte 一致）で実 parse し、validator の位置付き拒否 4 件・portability・LSP diagnostic を比較。自動 cut 挿入は下の行 |
| 自動 cut（`@autoCommit`） | FIRST 集合と文脈の検査で、`COMMIT` を入れても入力全体の解析の受理と CST が変わらない選択肢を両 frontend が lint（`I-AUTO-COMMIT`、info、位置付き）し、`@autoCommit` を付けた規則だけ展開後に `COMMIT(参照)` を挿入（#702）。全体の `@whitespace: javaStyle` の文法も対象（#728）。既定 OFF、注釈の無い文法は不変 | [条件と根拠](../docs/auto-commit.md)。lint 33 文法 31 件、実行 10 文法 108 入力を自動挿入・手書き・注釈なしで Java と両 Rust host の 6 設定で比較（受理・確定エラーの位置と message・CST）。javaStyle の文法は差分 fuzz（`AutoCommitWhitespaceFuzzTest`）でも比較。書き換えた構文木は手書きと一致し、生成物は byte 一致。`@comment`・宣言的 token の空白・規則の `@whitespace` / `@interleave`・終端記号の包み込み・途中で止まる解析は範囲外 |
| owned general capture/replay | Java NamedCapture/ReplayParser/CaptureScope と Rust OwnedCapture/Replay/CaptureScope（#590）。Projected/Generatedも実textを保持し、既存AST captureとは独立 | [契約](../docs/owned-capture-replay.md)。共通42入力・11状態・10診断を各6設定、5traceと2MiB深さ上限を比較。[一般UBNF構文](../docs/general-owned-capture-syntax.md)も両hostから生成（#591）。受理拒否・scope・provenance・rollback・実consumerを比較。残る公開APIの監査は#716で継続。captureの個別差#761/#762はPR #785・#787・#799で解消（[台帳](../docs/parity-ledger.md)） |
| backref・MatchedToken相当 | context-wide replay、scope付き参照検証、scopeなしrule-local capture比較（#325）を区別。owned runtime replayは#590で対応 | [scope効果](../docs/generated-scope-effects.md)のcapture比較は意味診断、[owned replay](../docs/owned-capture-replay.md)は構文認識。名前寿命・nested scope・projected/generated textは共通runtime fixtureあり。一般UBNF接続は[#591の契約](../docs/general-owned-capture-syntax.md)で対応 |
| PropagationStopper・consume/invert・virtual token・metadata | 限定TokenExpr（#438）と一般ExprのClassic entry（#571）。portable string metadata/関連IDをtransactionへ接続 | [metadata契約](../docs/portable-token-metadata.md)のsource/両cursor/CP span/rollback/retention/失効IDと[一般伝播](../docs/classic-token-propagation.md)を比較。Java任意Objectはhost固有。UBNF接続は#591で対応。stateful failure memoは下記#629、任意副作用の成功cacheは保証しない |
| 多段階解析と前処理 | Java / Rust の bounded phase pipeline、条件付き phase、source map を運ぶ実生成 parser の共通例（#380） | [契約](../docs/analysis-pipeline.md)と[実行例](../examples/preprocessing/README.md)。前方参照・相互 field・include/設定変更・遅延/循環/打切り/修復・元文書 CP 診断を比較。任意 macro/C preprocessor 構文や無制限の固定点 solver は含まない |
| shared-input language entry call | Java/Rustの同一ParseContext・原文CPからexact registry entryをprefix呼出しし、親の後続を継続。opaque境界＋owned子CST、state/trivia/memoとmetadata rollback（#487） | [API・制限・生成consumer導線](../docs/shared-language-calls.md)。34共通ケース＋direct lexical session8組、両hostの消費/失敗/AST位置・budget・staleを比較。bounded `@embedded`、任意rule import、一般context engineとは別契約 |
| 宣言的な意味規則 | Java / Rust の同じ JSON loader・IR・generated CST 実行と region query provider（#381） | [契約・API・共通 corpus](../docs/declarative-semantics.md)。型・scope・宣言・参照・引数期待型・補完・診断、部分入力、文法進化、CP span と package/project identity を照合。portableNominal/1 のみで、完全な各言語意味論や任意 AST の自動解釈は含まない |
| lexical scope store・宣言/参照/semantic diagnostics | Java rollback修正とRust公開ParseContextへのtransactional store接続（#174）、owned Treeへのsnapshot | scope depth/lookup/shadowing/イベント履歴/失敗時復元を共通operation corpusで比較。AST/IDEへのmetadata搬送は未完了 |
| scopeTree/declares/スコープ参照 | 両frontendから生成、Java capture-site選択も修正（#176）。mode/description metadata保持、CP位置、nested/repeated captureとrollbackを比較 | 両modeは解析時stack。評価時dynamic環境やclosure、LSP/DAP利用は未対応 |
| skip | Java の存在しない AST 型参照を修正し、両hostの Rust 生成に AST subtree 投影境界を実装（#323） | [契約・移行・共通corpus](../docs/skip-ast-projection.md)。構文・capture・scope/rollback は保持し、明示captureはtext。rootのsyntax成功とASTなしのmapping失敗を区別 |
| catalog/doc/simple等 | `@catalog` は両frontendから静的 `CatalogSpec` を生成（#180）。`@doc` も両Rust emitterが順序付き `RULE_DOCS` を保持。parser/AST/evaluatorには作用しない。Simple annotation（`@nestedCaptures`・`@autoCommit` 以外）はJavaと同じく両生成経路で受理して名前を使わず、`W-ANNOTATION-IGNORED` を同じcode・spanで報告（#715）。`@autoCommit` の解析はその rule を opaque として外す | [metadata契約](../docs/catalog-metadata.md)。catalog metadataからresolver・context別LSP利用への自動接続と、残るannotationのJava実動作を検証。Simple annotationは[共通corpus](../docs/simple-annotations.md)16文法で警告・生成物・CLIのstrictを3経路比較。一般のtyped/provider query対応とは区別 |
| recovery | Java/Rust runtimeと両hostの`@recovery(sync/auto/skip)`生成、CP span/message付きCST marker（#331） | [契約・共通corpus・再現手順](../docs/error-recovery.md)。18文法43入力×6設定で回復情報、両cursor、rollback、正常ASTと回復時mapping拒否を比較。部分/error AST型と汎用伝播は未対応 |
| incremental cache | 両版に上限付きsnapshot/chunk cacheと生成LSP無効化（#546）。未監査Exprは自動cache対象外（#613） | [契約](../docs/incremental-cache.md)。27編集/revision・21 LSP操作・11chunk分割で全再解析・位置・診断・回復・状態を比較。編集後は全文解析。部分CST再配置/接合は両版未実装 |
| SafeFailures memoの保持窓 | Javaの保持窓に対応するRustのbucket解放と、両言語の遠距離backtrack後の再保存を実装（#290） | 共通6入力で受理・consumed/farthest位置を照合、各言語で窓OFF/ONの診断・hit数一致、32,000 CP人工負荷の追加heap peak約95.5%削減。任意文法のhit不変・時間計算量保証・stateful memo parityの完了は含まない |
| memo key・resource・Classic mode | matched reset policy、rollback可能なstate version、grammar owner、純Classic modeと深さ制限を再利用に反映（#621/#629/#632/#634） | [reset policy](../docs/memo-reset-policy.md)、[stateful failure](../docs/stateful-failure-memo.md)、[Classic memo](../docs/classic-memo.md)。同入力のOFF/ON、実hit、失敗後状態、生成consumerを比較。既定OFF、任意custom/stateful成功を無条件cacheしない |
| pure success memo | Rustにowned snapshotの安全な成功再利用を追加し、Javaのコピー隔離と比較（#624） | [契約](../docs/pure-success-memo.md)。状態rollback・生成consumer・diagnostic hint・Classic cursorを検証。保持件数/単体/合計chargeとJavaのコピー前判定・非再帰copyは [成功memo予算](../docs/success-memo-budget.md)（#652）。共通600構成/言語で検証、共有外部graphやRSS全体の上限ではない |
| bounded failure memo | Java/Rustとも件数・単体/合計payloadの予算を設け、満杯時は通常解析へ戻る（#644） | [契約](../docs/failure-memo-budget.md)。600構成/言語で診断・実hit・状態epoch・Unicode・保持窓を検証。構造的chargeでありプロセスRSS上限ではない。成功memoの予算は [別契約](../docs/success-memo-budget.md)（#652）、実Tiny性能は#197。有限failure予算の飽和と再探索を[#663の対照profile](../benchmarks/tiny-classic/profiles/README.md)で再現し、#670で有限合計既定を両版32 MiBへ揃えた。欠省・不正値等25設定/言語と実Tiny全13入力×3consumer×3phaseを新既定で検証したが、最終性能deadlineの合格を意味しない。検証harnessは[#657の契約](../benchmarks/tiny-classic/README.md) |
| UBNF authoring LSP | JVM 不要 `ubnf-language-server` と VSIX の Rust 明示選択。Java 既定を維持 | [固定 capability/request 表・共通実stdio 127＋39＋62件](../docs/ubnf-authoring-lsp.md)。起動/Full sync、completion/hover、生成警告、validator76文法、AST definition/references・rename・outline/folding・linked editing、readonly package source の元位置・hash・失敗/cache境界、initialize/configure・profile metadata・session（#895）。残る validator #893、syntax/module #894、FQN quick fix/signature/semantic tokens #901、入力域/platform #902（親 #885/#886/#864）。一般生成 LSP の成功で完了としない |
| LSP/DAP | Classic Rust stdio LSP と両host opt-in `--lsp` 生成を実装（#451）。full sync、profile、syntax diagnostics、keyword/typed/provider completion、provider hover/definition、full semantic-token provider（#535、3.3.0公開後）。[契約と共通 oracle](../docs/classic-rust-lsp.md) | Java/Rust生成serverの実framed通信18位置fixture、実provider6fixture、stale/invalid/update/closeを検証。full semantic tokens は独立30-case corpusと両言語各7modeで検証。既定dataは空、legend/provider hookを利用者が実装。LSP cancellation、両言語のsemanticTokens range/delta・rename/format/code-action transport は未対応。下流Java VSIX起動はRust protocolの証拠にしない。DAPのAST/token inspectionは次行、live evaluator制御は別範囲 |
| LSP初期化・再設定 | 両生成serverでinitialize前のrequest/通知、重複initialize、通知methodへのrequestを実行前に拒否（#549）。再設定は明示`unlaxer/configure` | [契約・移行と59-step実通信oracle](../docs/classic-lsp-lifecycle.md)。callback回数・文書/version保持・失敗設定復元・shutdown/exitを比較。通常clientへの動的能力登録は行わない |
| VS Code起動・停止 | TinyCalcと生成拡張でJava/Rustのstdio実行ファイルを明示選択（#548、3.3.0公開後）。Java既定を維持し、standalone Java LSP mainと配布依存も修正 | [利用法と実VSIX試験](../docs/rust-vscode-launch.md)。Linux x86_64 / VS Code 1.96.4で両backendのLSP/DAP・設定不正・異常終了/再起動・子process終了を検証。全OS配布やlive evaluator制御の保証ではない |
| DAP typed AST / token inspection | Classic Rust stdio DAP と両host opt-in `--dap` 生成を実装（#462 / #463）。strict AST mapper と上限付き実token trace、breakpoint/next/continue・変数hook | [契約と独立共通fixture](../docs/classic-rust-dap.md)。実Java/Rust framed通信、UTF-16開始/終了位置、CR/LF/CRLF、source offset、0/1-based、parse/map失敗を検証。[token traceの独立fixtureと対応範囲](../docs/token-trace.md)も検証。generated text/原文anchorを明示する公開経路も実stdioで検証（#863）。Projected terminal の本文・論理 CP 範囲・停止順も共通 stdio で検証（#889）。論理終端超過は Java と同じく該当 stackTrace だけが失敗し、variables・next/continue・終了状態を維持（#892、Java/JDK の呼出履歴は言語固有）。CRLF 内部の token は位置変換 request の局所失敗に揃え、Classic/Generated/Projected の失敗 message・body 型・先頭行も実 stdio で比較（#899/#900、helpful NPE の説明は意図した差）。未対応複合atom/未宣言customのtrace、live evaluator制御・advanced DAPは未対応。TinyCalc/生成VSIXの起動・停止は#548/#555で検証。#384は完了済み、親#111は未完了 |
| tinyexpression-rs | 下流にparser・typed AST・scalar/context付き評価器・FormulaInfo loader・CLIを実装済み。parserはubnfc生成物 | Java goldenとの値/数値bits/失敗種別、tree/closure/traceを比較する有限corpusがある。Java任意classや全バックエンド互換の証明ではない。root-family retryは残る |
| rustcodeblock | 下流に本文/span保持、既定拒否、明示許可付き`tinyexpression-aot`と型付きnative bindingを実装済み | 実Java/Rust本文を共有oracleでcompile/execute、rustc診断を元のCP位置へ戻す。通常parse/evalはcompiler非起動。sandboxではなく、Java自動翻訳・FormulaInfo全体AOT・AOTのLSP/DAP統合は未対応 |
| ネイティブ配布 | 本repoはgeneratorと縮小文法CLI。下流は`tinyexpression` / `tinyexpression-aot`、C ABI、wasmのbuild・smoke・配布経路を持つ | 下流CLIのstdin/file/JSON/終了コードを検証。確認したnative archiveはLinux x86_64用であり、全OS・完全static・全tagの配布実績は主張しない |
| Rust製UBNF frontend・native generator | syntax frontend・source snapshotと、対応範囲のlowering/CLI/5module emitterを実装 | Java/nativeの既存・混在値文法で全5file一致、空PATHの生成/check、手書き/symlink保護。全backend機能の生成完了とは区別。frontend既知差と構造分析上限を文書化 |
| 入力DSLの機械語生成 | DSL全式の専用machine-code loweringは未対応 | 下流AOTはRust本文をcompile/linkするが、固定したDSLソースは実行時にparseしtyped-AST評価器で評価する。generator/評価CLI/native本文のバイナリ化とは区別する |

実本番 FormulaInfo → P4 → Java の native 接続は [production bridge](../docs/tinyexpression-production-bridge.md) で Tiny `f86ce8a5` 固定の両 host 独立 parser / 原文位置 / 実 javac query を検証する（strict 16入力177観測、editor partial 9入力29観測）。初版 #470 では未接続だった既存 Java Tiny LSP consumer の opt-in 診断・EOF 補完は [Tiny #252](https://github.com/opaopa6969/tinyexpression/pull/252) / [#254](https://github.com/opaopa6969/tinyexpression/pull/254) に実装され、`0d84f0dc` の長い fence も追加検証する。この現行 Java consumer の証拠を固定 pin の共通 corpus と混同せず、現行 Rust Tiny LSP consumer の同等性や全言語対応は主張しない。

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

公開context/combinator、optional/repeat、typed AST、token/annotation、captureの開発候補は上表の対応形と共通fixtureで検証する。追加実験で見つけたJava numeric capture #115とcapture選択 #116の生成コードは修正した。直接numeric captureのchecked int/i32変換は#566で統一し、外側captureの入れ子container型とpreferred AST選択は上表の対応形で実装・検証し、両版で拒否する組み合わせの拡張は#693〜#697で扱う。下流の実装済み評価器・rustcodeblockはその契約とcorpusを利用し、Classic側の未対応と製品側の残る境界を別々に進める。各行を小さなPRに分け、テスト・CI・merge・子issue closeまで行う。全体issueは未対応行を残したままcloseしない。

Javaの継承階層を一対一に移植するのではなく、文法と観測可能な振る舞いを対象とする。JVM任意オブジェクト・reflection・bytecodeのnative直接実行はできないため、Rust側のhost interfaceと移植コードの境界を明記する。差を消して比較を通したことにせず、意図的な差は独立したfixtureにする。

純mapped aliasの型移行（#163）は[API変更と利用側の移行文書](../docs/pure-mapped-alias-migration.md)を参照。
Javaでは従来のStringからObject系のNode保持へ変わるため、文字列依存の利用者は更新が必要。
先行する#165はJavaのpreferred選択Tokenとsource snapshotを原子的に取得する追加APIで、
Rustでは既存のowned AST/spanの後続・並行mapping耐性を検証する。
tinyexpressionはowned source resolverで従来のslice字句処理を維持する。
Javaのpreferred型候補探索は[owned snapshot API](../docs/preferred-ast-snapshots.md)でRustへ移植済み。特殊associative projectionも[共通契約](../docs/associative-projection.md)で対応する（#593）。

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
Rustのtyped/provider completion・provider hoverは[Classic LSP](../docs/classic-rust-lsp.md)で対応済み。
`CATALOGS` の `(context, name)` からresolverを自動選択する接続は未対応であり、
静的metadataの生成と、明示provider登録によるquery対応を区別する。

Classicのparser生成は`Expr`combinator定義を生成し、共通runtimeで実行する。直接コードを出す別engineの存在は、この状態付きruntimeを移植し終えた証拠ではない。Rustでビルドされた実行ファイルであることは、入力式を機械語にコンパイルしていることを意味しない。

移植に伴う追加提案は、両言語を対象とする受け入れ条件付きで追跡する。未実装の計画を上記の対応済み件数には含めない。

- [#157](https://github.com/opaopa6969/unlaxer-parser/issues/157): portability検査と機械可読な診断レポート（上記範囲を実装）。
- [#158](https://github.com/opaopa6969/unlaxer-parser/issues/158): target-neutralなtoken adapter契約（上記範囲を実装）。
- [#159](https://github.com/opaopa6969/unlaxer-parser/issues/159): 文法進化に伴う生成API影響レポート（上記範囲を実装）。

### UBNF authoring JSON-RPC (#994)

Java 固定 revision `384fcf3eb1c0ce3e207ac580cec5fcac4b478b34` を参照に、native の
initialize/configure params の失敗、Gson Integer の version 受理域、診断通知の `version: 0`、
未知/`$/`/lsp4j 宣言済み未実装 request を揃える。手書き168ケースの独立全 JSON oracle を
両実 stdio に送り、初期化失敗後の再試行と configure 失敗後の設定・既存文書・新入力を検証する。
JVM/Gson 固有 error.data だけを除外し、code/message/result/id と他のキーは比較する。
#901 の3機能は残る。reader のキー順・positional arity・capabilities null の無応答は #1001。
型検査は params 自体と capabilities の型だけで、InitializeParams の他欄・入れ子 DTO の型違い、`id: null`、
JSON 数値の f64 overflow の差も #1001 に記録した。
[固定対応表と検証境界](../docs/ubnf-authoring-lsp.md#json-rpc-層の固定契約994) を参照。
