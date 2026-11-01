# Java 公開 parser API と Rust の対応表

基準: `origin/master` の `5a4e51620dc245186f683cded7c6a191398a9550`（2026-10-27）。親 [#716](https://github.com/opaopa6969/unlaxer-parser/issues/716) / [#111](https://github.com/opaopa6969/unlaxer-parser/issues/111)。

第1段は棚卸し・対応表・子 issue 分割。runtime の変更や全API parity の完了宣言ではない。

## 列挙範囲と更新

- `unlaxer-common/src/main/java/org/unlaxer/parser/**/*.java` の全階層（combinator / elementary / posix / referencer に加え ascii / clang / incremental と root package）。public class だけでなく interface / enum / record / 公開nested型も含む。
- 関連境界は `org/unlaxer/Parser*.java`、`*Tag*.java`、Name / Parsed / Token / TokenKind / TokenList / TokenPredicators / TransactionElement / CapturedValue / Committed / TypedToken / TokenEffecterWithMatcher と、`context` / `capture` / `reducer` / `listener` package の全階層（ParseContext が実装する interface、ParseOptions、失敗診断、name snapshot、portable metadata、capture snapshot、memo 設定、reducer、listener）。package 単位で走査するため、これらへ新しい公開型を足すと検査が失敗する。source座標一般・editor・DSL生成API全体は機能群表の範囲で、このparser宣言一覧には含めない。
- 公開 static method は factory の命名を推測せず utility も全件列挙。interfaceの暗黙publicも含み、overloadは引数型で分ける。constructor・instance method・field・合成enum values等は型行の動作監査に含め、個別宣言行は作らない。非公開outer内のpublic型、private/protected、コメント、文字列内の偽宣言は除く。
- JDK 21+ の javac parse-only AST を使用し、依存解決・ビルド・annotation processor・外部取得を行わない。追加ファイルは実ソースの再走査で検出する。判定は名前検索ではなくJava parse/構築処理とRust Expr/実行処理を読み、共通corpusと実行testの対応範囲を区別した。
- 初期機能群表は `79975781` 時点の `rust/FULL-SPEC.md`。これは機能単位の記録であり、この全宣言表による個別監査へ接続する。
- [機能群表](../rust/FULL-SPEC.md)・[既知差台帳](parity-ledger.md)の既存契約と未解決差も併読する。言語固有は検証不要という意味ではない。
- #848: master `6bc349c1`（#855 merge）を基準に行境界4型の共通契約を検証。EmptyLine / WildCardLine の2型は #846 依存のまま残し、#848 は未完了。詳細は conformance/line-boundary/README.md。宣言の棚卸し基準revisionは据え置く。

```sh
python3 scripts/check-java-api-parity.py --list
python3 scripts/check-java-api-parity.py
python3 scripts/check-java-api-parity.py --write-doc
python3 scripts/check-java-api-parity.py --remap --write-doc
python3 -m pytest -q scripts/tests
```

JSON の apis は監査済み宣言の固定一覧。--write-doc は未登録宣言を自動承認せず失敗する。
新しい公開型・static overload、削除、signature 変更は実ソースとの集合比較で失敗する。
line は現行ソースから表示し、宣言の移動だけでは API 変更としない。根拠は監査時のコード・既存テストであり、今回それらを再実行したという意味ではない。
行を指す根拠は location と anchor（その行の先頭の宣言文字列。識別子の途中では切らない）を持ち、anchor がファイル内、scope があればその宣言の字下げブロック内で一意に一致する行を指していなければ失敗する。
参照先の行の挿入・削除でずれたら --remap が anchor から行番号だけを付け替える。付け替え後の全検査（anchor が見つからない・複数行に一致する・形式が不正な根拠、schema、公開 API の一致）が一つでも失敗すれば JSON も Markdown も書き換えず失敗し、宣言の改名・削除は人が再監査する。

## 状態と追跡

| 状態 | API 行数 |
|---|---:|
| 対応済み（記載した共通契約） | 139 |
| 対応あり・全API共通検証なし | 227 |
| 未対応（記載した動作） | 44 |
| 言語固有（移植境界あり） | 43 |

対応済みは記載した契約・fixture の範囲を指す。言語固有も免除ではなく、実用上同等な構築・移植境界の検証を子 issue で追跡する。
memo 安全宣言は既存 [#184](https://github.com/opaopa6969/unlaxer-parser/issues/184) に集約し、重複 issue は作らない。

## 子 issue

| issue | 意味単位・分割理由 |
|---|---|
| [#845 parity: parser構築・名前参照・identityのRust移植境界を共通corpusで固定する](https://github.com/opaopa6969/unlaxer-parser/issues/845) | 反射・Class/Supplier・lazy graph・名前参照の構築境界を一つの意味単位にする。Java機構を削除せず、Rustの明示Rule ID/registryへの移植と失敗条件を検証する。 |
| [#846 parity: 任意terminator付き反復のRust利用経路と共通corpusを追加する](https://github.com/opaopa6969/unlaxer-parser/issues/846) | body/terminatorの実行順序とcursor・CST・所有状態を一体で扱う。固定文字列JavaUntilだけで全Overloadを完了にしない。 |
| [#847 parity: 選択済みChoice・NonOrderedの再実行をRustと共通検証する](https://github.com/opaopa6969/unlaxer-parser/issues/847) | 過去に選んだbranch/occurrence順をtransactionalに保存・再実行する意味単位。文字列replayとは分ける。 |
| [#848 parity: 行頭・source先頭・行終端parserのRust利用経路を共通検証する](https://github.com/opaopa6969/unlaxer-parser/issues/848) | CR/LF/CRLF/EOFと初期cursorに依存する境界判定をまとめる。EmptyLine/WildCardLineの合成は任意terminator付き反復の子issueに依存する。 |
| [#849 parity: ASCII・POSIX・引用・括弧の公開parserをRustの字句構成と共通corpusへ対応付ける](https://github.com/opaopa6969/unlaxer-parser/issues/849) | 認識部品は既にあるため、公開文字集合・字句合成・抽出の共通oracleを追加し、不足だけ実装する。行頭や任意terminatorの基盤は別issue。 |
| [#850 parity: SuggestableParser・Suggestの候補と停止結果をRustで利用可能にする](https://github.com/opaopa6969/unlaxer-parser/issues/850) | parserのprefix候補算法、suggestedBy、Collectorのstoppedを一体で固定する。LSPの一般completionがあることだけで対応済みにしない。 |
| [#851 parity: parser tag・meta node・解析後CST predicateのRust移植経路を揃える](https://github.com/opaopa6969/unlaxer-parser/issues/851) | 共有parserのtagと原CST/reduced CST・解析後predicateの境界をまとめる。@skipとNotASTNodeの異なる子昇格を明示する。 |
| [#852 parity: MatchedTokenの履歴選択・slice・effectをRustのowned replayと共通検証する](https://github.com/opaopa6969/unlaxer-parser/issues/852) | 旧token selectorと派生wordのsource変換が一つの認識経路を作るためまとめる。新OwnedCapture/Replayの既存契約は保持する。 |
| [#853 parity: 公開combinatorの構築variant・wrapper・FIRST照会を共通corpusで固定する](https://github.com/opaopa6969/unlaxer-parser/issues/853) | 既存Expr経路を公開API行へ接続する共通検証。holder引数、Reverse overload、Classic Not、公開FIRST照会の不足は具体的に実装する。性能改善やmemo安全宣言は含めない。 |
| [#854 parity: ParseContext・解析結果・metadataの公開API利用経路を共通corpusで固定する](https://github.com/opaopa6969/unlaxer-parser/issues/854) | contextと返却された結果の所有/抽出境界を一体で検証する。既存portable metadata/recovery/cacheの契約を再利用し、raw Objectやstopped等の不足を明記して埋める。 |
| [#856 parity: 失敗診断・ParseOptions・RuleStack・RaisedFailureの公開APIを共通corpusで固定する](https://github.com/opaopa6969/unlaxer-parser/issues/856) | 失敗位置・期待候補・rule stack・raised failure・資源上限と、それを選ぶ ParseOptions の診断設定を一つの意味単位にする。既存 rule-stack/prior-matched/resource-abort の corpus を再利用し、TrialRecord 等の未比較項目と DiagnosticsSafety の移植境界を埋める。memo 設定は #854 と #184 に残す。 |

## issue #716 の重点12項目

| Java API | 個別判定・根拠 |
|---|---|
| `ChildOccursWithTerminator` | [対応済み（記載した共通契約）](#terminated-repeat) |
| `MatchedChoiceParser` | [未対応（記載した動作）](#selected-choice) |
| `StartOfLineParser` | [対応済み（記載した共通契約）](#start-of-line) |
| `SuggestableParser / Suggest` | [未対応（記載した動作）](#suggest) |
| `TagWrapper` | [未対応（記載した動作）](#tag-wrapper) |
| `MetaFunctionParser` | [言語固有（移植境界あり）](#meta-function) |
| `ReferenceByNameParser` | [対応済み（記載した共通契約）](#reference-by-name) |
| `NotASTNode` | [未対応（記載した動作）](#not-ast-node) |
| `PredicateAnyMatchForParsedParser` | [未対応（記載した動作）](#parsed-predicate) |
| `EscapeInQuotedParser` | [対応あり・全API共通検証なし](#escape-in-quoted) |
| `ParenthesesParser` | [対応あり・全API共通検証なし](#parentheses) |
| `MatchedNonOrderedParser` | [未対応（記載した動作）](#selected-order) |

## 判定の根拠と利用経路

### construction

**対応済み（記載した共通契約）**。Java の Class/Supplier/newInstance factory・lazy children・共有instance と Rust の Rule index・RuleNames・OnceLock<SharedGrammar>・都度 share_grammar が同じ grammar を構築し、cases.tsv（通常/空/EOF/不完全/CRLF/非BMP、同一rule再利用、choice敗者、MatchOnly先読み、外側失敗後の再試行、consumed/matchOnly×invert、全memo/diagnostics）の cursor・失敗CP・RuleStack と、根の token・getOriginalTokens 相当・葉 token が一致する。中間 node は言語間で比較しない（Java は combinator token を持ち、Rust の Fragment は省く。meta-function の境界、#851）。代わりに各言語のテストが、中間 node を含む原 CST 全体が構築経路間で等しいことを検査する（Rust の Custom source 版 NotProbe は Expr が異なるので別群）。construction.tsv は factory の同一性・失敗時の非cache・(kind, class) key と kind なし key の等価・null kind の NullPointerException（既存挙動。Rust の key は Option で null がない）・lazy children の一回構築・独立context（Java の global scope map / Rust の set_state は次の context に残らず、graph は capture/scope/user state を持たない）・循環（Java eager は StackOverflowError。Rust の OnceLock 再入は現実装で deadlock するので実行しない。lazy/Rule index は成功）・左再帰の資源上限・RootParserIndicator の範囲を両言語で実行する。RootParserIndicator では、Java の getRoot/findFirstFromRoot（ReferenceByNameParser・ReferenceParser）・findFirstToParent/findParents・getPath・ParserHierarchy.isRoot・ParserPrinter が indicator で止まる。Rust は scope ごとに別 grammar と RuleNames を持たせ、外側の名前を scope の table に登録しない。どちらも失敗 RuleStack は呼び出し元の frame を保つ。newWithoutRecursive は入れ子の Chain/Choice の型と Name を保ち、tag は写さない（既存の挙動。tag は #851）。子が 1 つの Commit からその子を除くと、構築時に IndexOutOfBoundsException になる（Rust の移植では構築エラー）。parent pointer・ParserFinder/ParserPath の親走査・PseudoRoot・Name の kind 別等価は Java 固有で、`-` 行に理由と Rust の代替不変条件を記す。ChildOccurs と ParserPrinter は、Rust の Expr variant / RuleStack 上の adapter 例で実行する。StaticParser（ascii/elementary の具象 class が implements）・Initializable・ParserInitializer は呼び出し元のない marker である。instanceof や型引数からの参照がなく、ParserFactoryByClass/ParserFactoryBySupplier の initialize() 呼び出しはコメントアウトされている。観測できる動作がないことを marker-no-effect 行（initialize 0 回・同一 instance・通常 parse）で固定し、Rust には対応物を持たない。LazyInstance は Transaction.commit と MetaFunctionTokenReducer で CST に作用するので、meta-function 判定へ移した。凍結した共有 graph を別々の context で同時に解析する場合（#1007）も、shared-graph.tsv（参照の名前/predicate、nullable、再帰、Unicode、失敗後の再利用）・cases.tsv・RootParserIndicator の scope を両言語が 8 thread × 40 round で各行の期待値と照合する。Java は遅延 cache（ReferenceParser と Matched* の参照先、AbstractParser.root は不変 Optional 1 本、parserByName と ParserFactoryByClass の初期化 map は ConcurrentHashMap）を lock なしで公開し、初回の同時解決は各 thread が同じ結果を計算する。Rust は不変の Arc grammar を共有する。AbstractParser.parse を継承する利用者 parser の委譲先（createParser の結果）は、親の monitor の下で一度だけ作り、親を設定して子の list を差し替えて足してから volatile field で公開する（作成中の別 context は待ち、例外は何も公開しない）。Rust は委譲先を grammar の外の OnceLock に置き、両言語とも shared-graph.tsv を first-use と凍結済みで照合し、construction.tsv の lazy-delegate-* 行で一度だけの作成・失敗後の再作成を共通に、付け足し・走査・再入・cache 寿命・凍結前の検索を Java 固有行で固定する。同じ context の同時利用と、利用者が解析中に行う graph 変更は対象外。AbstractPropagatableSource.computedInvertMatch は TokenPrinter の (inverted) 表示だけが読む直前の呼び出しの値で、並行時は他の context の値になり得る（Rust に対応物はない）。context と結果の metadata は #854 で扱う。ClassAndKind(Class).kind() の NPE、newWithoutRecursive の子置換、ParserNameContext の同一instance重複拒否は #845 で修正した。

利用経路: `RuleNames::new([...])` で名前を宣言して `reference(name)` から `Expr::Rule(id)` を作り `share(rules)` で共有、または `static G: OnceLock<SharedGrammar>` + `get_or_init`。失敗し得る factory は成功後だけ cell を set し、kind 付き key は key ごとの共有値。identity は `Arc::ptr_eq`、token の生成元は `Node.rule == id`。 RootParserIndicator の scope は、scope 用の `RuleNames` と別の `SharedGrammar` を作り、外側の rule から `Expr::Custom` で `context.parse_shared_grammar(scope, 0, false)` を呼ぶ。Rule ID は発行した table で共有した grammar にだけ有効なので、rules を作り直すときは table と参照も作り直す。

実装: [rust/unlaxer-runtime/src/lib.rs#L363](../rust/unlaxer-runtime/src/lib.rs#L363)、[rust/unlaxer-runtime/src/lib.rs#L598](../rust/unlaxer-runtime/src/lib.rs#L598)、[unlaxer-common/src/main/java/org/unlaxer/parser/ParserFactoryByClass.java#L14](../unlaxer-common/src/main/java/org/unlaxer/parser/ParserFactoryByClass.java#L14)、[unlaxer-common/src/main/java/org/unlaxer/parser/ParserFactoryBySupplier.java#L11](../unlaxer-common/src/main/java/org/unlaxer/parser/ParserFactoryBySupplier.java#L11)

テスト: [unlaxer-common/src/test/java/org/unlaxer/parser/ParserConstructionParityTest.java](../unlaxer-common/src/test/java/org/unlaxer/parser/ParserConstructionParityTest.java)、[rust/unlaxer-runtime/tests/parser_construction.rs](../rust/unlaxer-runtime/tests/parser_construction.rs)、[rust/unlaxer-runtime/tests/shared_grammar.rs](../rust/unlaxer-runtime/tests/shared_grammar.rs)

共通 fixture: [conformance/parser-construction/cases.tsv](../conformance/parser-construction/cases.tsv)、[conformance/parser-construction/construction.tsv](../conformance/parser-construction/construction.tsv)

追跡: [#845](https://github.com/opaopa6969/unlaxer-parser/issues/845)、[#851](https://github.com/opaopa6969/unlaxer-parser/issues/851)、[#854](https://github.com/opaopa6969/unlaxer-parser/issues/854)

### reference-by-name

**対応済み（記載した共通契約）**。ReferenceByNameParser の root からの名前探索と Rust の構築時解決（RuleNames::reference、未登録は reference_or_never の never-match）が cases.tsv の再帰参照・未登録 alternative（choice敗者）・全kind/invert/memo/diagnostics で同じ結果になる。ParserNameContext と RuleNames の重複拒否（別parserの同名は両方拒否、同一rule/instanceの再利用は受理）・未登録 lookup・preorder 先頭一致・miss 後の再探索（Java は miss を cache しない。Rust は新しい table で rules を作り直して再解決する）、hit と root の cache（Java は hit を instance ごとに、root を最初の getRoot() で保持し、graph の変更や付け替えの後も自動では解決し直さない。Rust の共有済み grammar も Rule ID を変えない）、RootParserIndicator の範囲を construction.tsv で比較する。Rust の RuleNames は重複・未登録・宣言順不一致・範囲外の Rule index を構築エラーにする。ID は発行した table の grammar 専用で、作り直した rules に古い ID を流用すると、範囲内なら別の rule を指す（rule-names-stale-id）。そのため参照も作り直す。Expr::Custom の callback 内で使う index は検査できず、callback 側の責任とする。

利用経路: `let names = RuleNames::new(["Root", "Item"])?; names.reference("Item")?` → `Expr::Rule(1)`、`names.share(rules)?`。Java 互換の寛容な未登録は `names.reference_or_never("Missing")`。UBNF の `Root ::= Item;` は既存の生成経路。

実装: [unlaxer-common/src/main/java/org/unlaxer/parser/referencer/ReferenceByNameParser.java#L29](../unlaxer-common/src/main/java/org/unlaxer/parser/referencer/ReferenceByNameParser.java#L29)、[unlaxer-common/src/main/java/org/unlaxer/parser/AbstractParser.java#L209](../unlaxer-common/src/main/java/org/unlaxer/parser/AbstractParser.java#L209)、[unlaxer-common/src/main/java/org/unlaxer/parser/AbstractParser.java#L163](../unlaxer-common/src/main/java/org/unlaxer/parser/AbstractParser.java#L163)、[unlaxer-common/src/main/java/org/unlaxer/ParserNameContext.java#L13](../unlaxer-common/src/main/java/org/unlaxer/ParserNameContext.java#L13)、[rust/unlaxer-runtime/src/rule_names.rs#L90](../rust/unlaxer-runtime/src/rule_names.rs#L90)、[rust/unlaxer-runtime/src/rule_names.rs#L120](../rust/unlaxer-runtime/src/rule_names.rs#L120)、[rust/unlaxer-runtime/src/rule_names.rs#L153](../rust/unlaxer-runtime/src/rule_names.rs#L153)、[rust/unlaxer-runtime/src/lib.rs#L124](../rust/unlaxer-runtime/src/lib.rs#L124)

テスト: [unlaxer-common/src/test/java/org/unlaxer/parser/ParserConstructionParityTest.java](../unlaxer-common/src/test/java/org/unlaxer/parser/ParserConstructionParityTest.java)、[rust/unlaxer-runtime/tests/parser_construction.rs](../rust/unlaxer-runtime/tests/parser_construction.rs)、[unlaxer-dsl/src/test/java/org/unlaxer/dsl/codegen/RustConformanceTest.java](../unlaxer-dsl/src/test/java/org/unlaxer/dsl/codegen/RustConformanceTest.java)

共通 fixture: [conformance/parser-construction/cases.tsv](../conformance/parser-construction/cases.tsv)、[conformance/parser-construction/construction.tsv](../conformance/parser-construction/construction.tsv)

追跡: [#845](https://github.com/opaopa6969/unlaxer-parser/issues/845)

### reference-predicate

**対応済み（記載した共通契約）**。ReferenceParser の Predicate<Parser>（名前 equality と GrammarRule 名の任意 predicate）は最初の解析で一度だけ評価し、hit も miss も instance の寿命の間 cache する。Rust は predicate を構築時に一度だけ評価して `Expr::Rule(id)` か never-match を保持し、rules を拡張しても解決済み式を保つ。cases.tsv の両 predicate 経路と construction.tsv の miss cache・以後の評価0回・Referencer.AbstractReference の非cache探索で比較する。任意 Java class/instance predicate は、構築時に rule 名/metadata 上の predicate へ移す。

利用経路: `NAMES.iter().position(|name| predicate(name)).map_or(Expr::JavaEmptyWord, Expr::Rule)` を構築時に一度だけ評価する。毎回の探索（Referencer）は現在の rule vector への lookup 関数。

実装: [unlaxer-common/src/main/java/org/unlaxer/parser/referencer/ReferenceParser.java#L72](../unlaxer-common/src/main/java/org/unlaxer/parser/referencer/ReferenceParser.java#L72)、[unlaxer-common/src/main/java/org/unlaxer/parser/referencer/Referencer.java#L27](../unlaxer-common/src/main/java/org/unlaxer/parser/referencer/Referencer.java#L27)、[rust/unlaxer-runtime/src/lib.rs#L124](../rust/unlaxer-runtime/src/lib.rs#L124)

テスト: [unlaxer-common/src/test/java/org/unlaxer/parser/ParserConstructionParityTest.java](../unlaxer-common/src/test/java/org/unlaxer/parser/ParserConstructionParityTest.java)、[rust/unlaxer-runtime/tests/parser_construction.rs](../rust/unlaxer-runtime/tests/parser_construction.rs)

共通 fixture: [conformance/parser-construction/cases.tsv](../conformance/parser-construction/cases.tsv)、[conformance/parser-construction/construction.tsv](../conformance/parser-construction/construction.tsv)

追跡: [#845](https://github.com/opaopa6969/unlaxer-parser/issues/845)

### meta-function

**言語固有（移植境界あり）**。MetaFunctionParser 自体は空 marker だが、Transaction.commit の doCreateMetaToken/LazyInstance 判定と MetaFunctionTokenReducer の子昇格に作用する。Rust の Fragment 合成は中間 combinator node を省くが、Java の全 marker/収集設定の同値性を証明しない。 CreateMetaTokenSpecifier（旧綴り CreateMetaTokenSprcifier を含む）の meta token 生成設定、CommittedReducer/AbstractTokenReducer と Committed の originalTokens も同じ縮約境界に属する。 LazyInstance（LazyOccurs 系が実装）は MetaFunctionParser であっても createMetaOff のとき token を残し（Transaction.commit）、MetaFunctionTokenReducer の子昇格からも外れる。construction.tsv の lazy-instance-meta-token 行で、Java の原 CST（Chain(ZeroOrMore) の createMetaOn / createMetaOff、Chain(LazyZeroOrMore) の createMetaOff）を固定した。Rust には該当する設定がない。

利用経路: 通常は `Expr::Sequence/Choice` の Fragment、所有する CST が必要なら `Rule` node と明示 mapper へ移す。原 CST と reduced CST の違い、LazyInstance と createMetaToken の全設定を比較する。

実装: [unlaxer-common/src/main/java/org/unlaxer/parser/MetaFunctionParser.java#L3](../unlaxer-common/src/main/java/org/unlaxer/parser/MetaFunctionParser.java#L3)、[unlaxer-common/src/main/java/org/unlaxer/context/Transaction.java#L125](../unlaxer-common/src/main/java/org/unlaxer/context/Transaction.java#L125)、[unlaxer-common/src/main/java/org/unlaxer/reducer/MetaFunctionTokenReducer.java#L8](../unlaxer-common/src/main/java/org/unlaxer/reducer/MetaFunctionTokenReducer.java#L8)、[rust/unlaxer-runtime/src/lib.rs#L4098](../rust/unlaxer-runtime/src/lib.rs#L4098)、[unlaxer-common/src/main/java/org/unlaxer/context/CreateMetaTokenSpecifier.java#L3](../unlaxer-common/src/main/java/org/unlaxer/context/CreateMetaTokenSpecifier.java#L3)、[unlaxer-common/src/main/java/org/unlaxer/reducer/CommittedReducer.java#L6](../unlaxer-common/src/main/java/org/unlaxer/reducer/CommittedReducer.java#L6)、[unlaxer-common/src/main/java/org/unlaxer/Committed.java#L15](../unlaxer-common/src/main/java/org/unlaxer/Committed.java#L15)、[unlaxer-common/src/main/java/org/unlaxer/context/Transaction.java#L127](../unlaxer-common/src/main/java/org/unlaxer/context/Transaction.java#L127)、[unlaxer-common/src/main/java/org/unlaxer/parser/LazyInstance.java#L3](../unlaxer-common/src/main/java/org/unlaxer/parser/LazyInstance.java#L3)

テスト: [unlaxer-common/src/test/java/org/unlaxer/parser/ParserConstructionParityTest.java](../unlaxer-common/src/test/java/org/unlaxer/parser/ParserConstructionParityTest.java)

共通 fixture: [conformance/parser-construction/construction.tsv](../conformance/parser-construction/construction.tsv)

追跡: [#851](https://github.com/opaopa6969/unlaxer-parser/issues/851)

### terminated-repeat

**対応済み（記載した共通契約）**。ChildOccursWithTerminator / Occurs と Lazy* / Optional / ZeroOrOne / Repeat の terminator は body より前に、親の kind・invert に関係なく parse(context)（consumed・非反転）で独自 transaction 内に試す。成功は consumed 系の親でだけ commit され CollectingParser（Chain/Choice/Occurs 系と Commit・Label・stopper・NotPropagatableSource・Not・PegLookahead など ConstructedSingleChildParser 全部）は二重に収集され、matchOnly 系の親では rollback して停止する。min/max・max 到達後の未試行・nullable body/terminator の停止・Transaction.begin の matched reset・raised failure を含め、Rust `Expr::repeat_java_until`（`Expr::JavaTerminatedRepeat`）で実装した。独立した terminated-repeat corpus 732 行で4 token kind・invert・prior matched・reset 方針・両 cursor・原 token と kind 別 CST・失敗 CP・token stack を、所有状態 24 行で capture / user state / 終端呼出し回数 / 外側失敗後の再試行と同一 parser 再利用を、memo 2×診断3モードで比較する。Java 側は各型の全公開 constructor・newWithTerminator・Lazy 形を同じ行で実行する。wrappers 2342 行（23 scenario）で Commit/Label・3つの伝播 wrapper・cut/label 後の失敗と Choice/MatchOnly 越しの raise・生成形 rule（LazyChain/LazyChoice）・Not/MatchOnly/PegLookahead・NameResolutionScope の terminator を raised failure と token trace の葉まで、raised-state 112 行で cut/label/CatchLabels 下の capture・user state の rollback を、resource-abort の terminator/body 4 行で資源失敗の復元を比較する。手書きの非 collecting rule class が1段深いのは既存の #845 境界、ParserWrapper/ParserHolder/MappedSingleCharacterParserHolder は holders 900 行（9 scenario）で、Java の全 constructor と Rust の既存公開 API recipe（既定形は `p.stop_consume().stop_invert()`）を terminator・body・Chain 要素の位置で比較する。consumed+invert 固定・virtual kind 固定・createMetaToken=false・custom parser 経路は graph-utilities / meta-function / #853 の境界として未検証。Java DETAILED_ON_FAILURE の1行は #861 の既知差として Java テストに明示し、#862 の merge 後に外す。新 variant は memo 不安全・snapshot cache 非承認で、FIRST は Java と同じく body と terminator の和。

利用経路: `Expr::literal("a").repeat_java_until(Expr::literal(";"), 0, None)`（ZeroOrMore(inner, terminator)）。min/max は OneOrMore=(1, None)、Optional/ZeroOrOne=(0, Some(1))、Repeat#newWithTerminator=(min, Some(max))。Java の Class/Supplier/Lazy 指定・Name・ASTNodeKind は Rust の Expr 値と Rule 名へ移し（#845 の構築境界）、反射 identity は持ち込まない。UBNF 構文は追加しない。

実装: [unlaxer-common/src/main/java/org/unlaxer/parser/combinator/ChildOccursWithTerminator.java#L12](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/ChildOccursWithTerminator.java#L12)、[unlaxer-common/src/main/java/org/unlaxer/parser/combinator/Occurs.java#L67](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/Occurs.java#L67)、[rust/unlaxer-runtime/src/lib.rs#L238](../rust/unlaxer-runtime/src/lib.rs#L238)、[rust/unlaxer-runtime/src/lib.rs#L445](../rust/unlaxer-runtime/src/lib.rs#L445)、[rust/unlaxer-runtime/src/lib.rs#L4972](../rust/unlaxer-runtime/src/lib.rs#L4972)、[rust/unlaxer-runtime/src/lib.rs#L5030](../rust/unlaxer-runtime/src/lib.rs#L5030)、[unlaxer-common/src/main/java/org/unlaxer/context/Transaction.java#L131](../unlaxer-common/src/main/java/org/unlaxer/context/Transaction.java#L131)、[rust/unlaxer-runtime/src/lib.rs#L7964](../rust/unlaxer-runtime/src/lib.rs#L7964)

テスト: [unlaxer-common/src/test/java/org/unlaxer/parser/combinator/TerminatedRepeatParityTest.java](../unlaxer-common/src/test/java/org/unlaxer/parser/combinator/TerminatedRepeatParityTest.java)、[rust/unlaxer-runtime/tests/terminated_repeat.rs](../rust/unlaxer-runtime/tests/terminated_repeat.rs)、[unlaxer-common/src/test/java/org/unlaxer/context/ResourcePublicationTest.java](../unlaxer-common/src/test/java/org/unlaxer/context/ResourcePublicationTest.java)、[rust/unlaxer-runtime/tests/resource_publication.rs](../rust/unlaxer-runtime/tests/resource_publication.rs)

共通 fixture: [conformance/terminated-repeat/cases.tsv](../conformance/terminated-repeat/cases.tsv)、[conformance/terminated-repeat/state.tsv](../conformance/terminated-repeat/state.tsv)、[conformance/terminated-repeat/wrappers.tsv](../conformance/terminated-repeat/wrappers.tsv)、[conformance/terminated-repeat/holders.tsv](../conformance/terminated-repeat/holders.tsv)、[conformance/terminated-repeat/raised-state.tsv](../conformance/terminated-repeat/raised-state.tsv)、[conformance/resource-abort/cases.tsv](../conformance/resource-abort/cases.tsv)、[conformance/terminated-repeat/README.md](../conformance/terminated-repeat/README.md)

追跡: [#846](https://github.com/opaopa6969/unlaxer-parser/issues/846)

### until-variants

**対応済み（記載した共通契約）**。WildCardStringTerminatorParser の6 constructor（文字列群/単一 parser、MatchOnly 化の有無、Name 付き）は ZeroOrMore(WildCardStringParser, terminator) で、文字列群は Choice の terminator。parse(context) は呼出し元の resetMatchedWithConsumed を false にしてから始める。terminated-repeat corpus の until 系行で全 constructor を Java 側で実行し、Rust は `Expr::Any.repeat_java_until(..)` と既存 `Expr::JavaUntil` で同じ行を比較する。既存 JavaUntil は Classic CST の終端 Choice が1段浅かったため、Java と同じ Choice(MatchOnly(word)) に揃えた。

利用経路: `WildCardStringTerminatorParser(";", "\r\n")` ⇔ `Expr::Any.repeat_java_until(Expr::choice([Expr::literal(";").match_only(), Expr::literal("\r\n").match_only()]), 0, None)`。`(false, ...)` は match_only を外し、`(Parser)` は `t.match_only()`、`(false, Parser)` は `t`。固定1文字列は従来どおり `Expr::JavaUntil(";")` / UBNF `UNTIL(';')`。直接の `parse(context)` 呼出しは `set_reset_matched_with_consumed(false)` の後に Consumed で parse する。

実装: [unlaxer-common/src/main/java/org/unlaxer/parser/elementary/WildCardStringTerminatorParser.java#L17](../unlaxer-common/src/main/java/org/unlaxer/parser/elementary/WildCardStringTerminatorParser.java#L17)、[unlaxer-common/src/main/java/org/unlaxer/parser/elementary/WildCardStringTerminatorParser.java#L63](../unlaxer-common/src/main/java/org/unlaxer/parser/elementary/WildCardStringTerminatorParser.java#L63)、[rust/unlaxer-runtime/src/lib.rs#L445](../rust/unlaxer-runtime/src/lib.rs#L445)、[rust/unlaxer-runtime/src/token.rs#L1144](../rust/unlaxer-runtime/src/token.rs#L1144)

テスト: [unlaxer-common/src/test/java/org/unlaxer/parser/combinator/TerminatedRepeatParityTest.java](../unlaxer-common/src/test/java/org/unlaxer/parser/combinator/TerminatedRepeatParityTest.java)、[rust/unlaxer-runtime/tests/terminated_repeat.rs](../rust/unlaxer-runtime/tests/terminated_repeat.rs)、[unlaxer-dsl/src/test/java/org/unlaxer/dsl/codegen/RustConformanceTest.java](../unlaxer-dsl/src/test/java/org/unlaxer/dsl/codegen/RustConformanceTest.java)

共通 fixture: [conformance/terminated-repeat/cases.tsv](../conformance/terminated-repeat/cases.tsv)、[conformance/terminated-repeat/README.md](../conformance/terminated-repeat/README.md)

追跡: [#846](https://github.com/opaopa6969/unlaxer-parser/issues/846)

### selected-choice

**未対応（記載した動作）**。MatchedChoiceParser は以前選ばれた Choice の child parser を再実行する。Rust ordered_choice は最初の成功 Fragment を返すが、Choice identity ごとの選択 child を保存・再適用する公開 API を持たない。Backreference/Replay の文字列一致とは異なる。

利用経路: 明示 Choice ID と選択 branch ID の transaction 管理、および branch 再呼出しの API/例が必要。既存 `Expr::Choice` と `ParseContext::state` は部品であり完成した対応ではない。

実装: [unlaxer-common/src/main/java/org/unlaxer/parser/referencer/MatchedChoiceParser.java#L13](../unlaxer-common/src/main/java/org/unlaxer/parser/referencer/MatchedChoiceParser.java#L13)、[unlaxer-common/src/main/java/org/unlaxer/parser/combinator/ChoiceCommitAction.java#L7](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/ChoiceCommitAction.java#L7)、[rust/unlaxer-runtime/src/lib.rs#L5713](../rust/unlaxer-runtime/src/lib.rs#L5713)

テスト: [unlaxer-common/src/test/java/org/unlaxer/referencer/MatchedChoiceParserTest.java](../unlaxer-common/src/test/java/org/unlaxer/referencer/MatchedChoiceParserTest.java)

共通 fixture: 全API動作の共通根拠は未整備。

追跡: [#847](https://github.com/opaopa6969/unlaxer-parser/issues/847)

### selected-order

**未対応（記載した動作）**。MatchedNonOrderedParser は getOrdered に保存した parser 順を Ordered で再実行する。Rust non_ordered の selected Vec は呼出し内だけで、成功時は Fragment に変わり順序再適用用 handle を公開しない。既存 NonOrdered parity はこの機能を含まない。

利用経路: NonOrdered ID ごとに選択 occurrence の順序を保存し、同じ順序で再実行する API と rollback の共通 oracle を追加する。

実装: [unlaxer-common/src/main/java/org/unlaxer/parser/referencer/MatchedNonOrderedParser.java#L16](../unlaxer-common/src/main/java/org/unlaxer/parser/referencer/MatchedNonOrderedParser.java#L16)、[rust/unlaxer-runtime/src/lib.rs#L4120](../rust/unlaxer-runtime/src/lib.rs#L4120)

テスト: [unlaxer-common/src/test/java/org/unlaxer/referencer/MatchedNonOrderedParserTest.java](../unlaxer-common/src/test/java/org/unlaxer/referencer/MatchedNonOrderedParserTest.java)、[rust/unlaxer-runtime/tests/nonordered.rs](../rust/unlaxer-runtime/tests/nonordered.rs)

共通 fixture: 全API動作の共通根拠は未整備。

追跡: [#847](https://github.com/opaopa6969/unlaxer-parser/issues/847)

### nonordered

**対応済み（記載した共通契約）**。各 child 出現を一度だけ宣言順優先で選ぶ認識・CST入力順・両cursor・登録stateのrollbackは共通 oracle に対応。getOrdered の parser identity 再利用は selected-order の別行で追跡する。

利用経路: `Expr::non_ordered([Expr::literal("a"), Expr::literal("b")])`。専用 UBNF 構文はない。

実装: [unlaxer-common/src/main/java/org/unlaxer/parser/combinator/NonOrdered.java#L22](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/NonOrdered.java#L22)、[rust/unlaxer-runtime/src/lib.rs#L4120](../rust/unlaxer-runtime/src/lib.rs#L4120)

テスト: [unlaxer-common/src/test/java/org/unlaxer/parser/combinator/NonOrderedParityTest.java](../unlaxer-common/src/test/java/org/unlaxer/parser/combinator/NonOrderedParityTest.java)、[unlaxer-dsl/src/test/java/org/unlaxer/dsl/codegen/NonOrderedStateTest.java](../unlaxer-dsl/src/test/java/org/unlaxer/dsl/codegen/NonOrderedStateTest.java)、[rust/unlaxer-runtime/tests/nonordered.rs](../rust/unlaxer-runtime/tests/nonordered.rs)

共通 fixture: [spec-corpus/nonordered/cases.tsv](../spec-corpus/nonordered/cases.tsv)、[spec-corpus/nonordered/state.tsv](../spec-corpus/nonordered/state.tsv)

### start-of-line

**対応済み（記載した共通契約）**。CP0 または直前CR/LFを認識し、CRLF途中も真。invert=trueは非行頭でも失敗する。独立した line-boundary corpus で4 token kind（virtual consumedはmatchedを参照）・invert・両cursor・CST・失敗CP・二重rollbackと同一instance再試行をmemo2×診断3モードで比較する。新しいruntime APIは追加せず、Classicはtokenを作らないadapterを使う。関連static factoryの4経路とcache/独立instanceも実行する。

利用経路: 既存UBNF `token Boundary ::= BOL;` / `Expr::Lexical(.., LexicalExpression { op: Op::BOL, .. })`。Classicは `conformance/line-boundary/probe.rs` の `Expr::Custom` adapterがtoken_modeとposition/matched_positionを読み、同じLexicalExpression境界を検査する。語彙tokenとは零幅tokenの生成とinvertの契約を分ける。

実装: [unlaxer-common/src/main/java/org/unlaxer/parser/elementary/StartOfLineParser.java#L15](../unlaxer-common/src/main/java/org/unlaxer/parser/elementary/StartOfLineParser.java#L15)、[rust/unlaxer-runtime/src/lexical.rs#L64](../rust/unlaxer-runtime/src/lexical.rs#L64)、[rust/unlaxer-runtime/src/lib.rs#L104](../rust/unlaxer-runtime/src/lib.rs#L104)、[conformance/line-boundary/probe.rs#L20](../conformance/line-boundary/probe.rs#L20)

テスト: [unlaxer-dsl/src/test/java/org/unlaxer/dsl/codegen/DeclarativeTokenConformanceTest.java](../unlaxer-dsl/src/test/java/org/unlaxer/dsl/codegen/DeclarativeTokenConformanceTest.java)、[unlaxer-dsl/src/test/java/org/unlaxer/dsl/codegen/ContextualLexingConformanceTest.java](../unlaxer-dsl/src/test/java/org/unlaxer/dsl/codegen/ContextualLexingConformanceTest.java)、[unlaxer-dsl/src/test/java/org/unlaxer/dsl/codegen/LineBoundaryConformanceTest.java](../unlaxer-dsl/src/test/java/org/unlaxer/dsl/codegen/LineBoundaryConformanceTest.java)、[rust/unlaxer-runtime/tests/line_boundary.rs](../rust/unlaxer-runtime/tests/line_boundary.rs)

共通 fixture: [spec-corpus/declarative-tokens/tokens.ubnf](../spec-corpus/declarative-tokens/tokens.ubnf)、[spec-corpus/declarative-tokens/runtime.json](../spec-corpus/declarative-tokens/runtime.json)、[spec-corpus/contextual-lexing/corpus.json](../spec-corpus/contextual-lexing/corpus.json)、[conformance/line-boundary/cases.tsv](../conformance/line-boundary/cases.tsv)、[conformance/line-boundary/transactions.tsv](../conformance/line-boundary/transactions.tsv)、[conformance/line-boundary/lexical.tsv](../conformance/line-boundary/lexical.tsv)、[conformance/line-boundary/README.md](../conformance/line-boundary/README.md)

追跡: [#848](https://github.com/opaopa6969/unlaxer-parser/issues/848)

### line-composition

**対応あり・全API共通検証なし**。EmptyLineParser / WildCardLineParser は任意terminator付き反復に依存し未完了（#846）。guard付きRepeatは通常の受理/cursorを再現するがbodyごとのChainが原CSTへ増える。terminator-first ChoiceのRepeatはmatchOnlyで終端成功後も反復し、Javaと成否/cursorが異なる。CR/LFを外した文字クラスのRepeatはconsumed/matchOnlyの受理・cursor・原CSTまで揃うが、virtualTokenConsumedでJavaが成功したmatch-only terminatorをcommitしてmatched cursorを進める観測（WildCardLineの CR LF をC=0/M=0のEOFで受理など）と、terminator試行が残す失敗診断を再現しない。3つの反例corpusを両言語で実行した。4型の完了でこの2型や#848をcloseしない。

利用経路: BOL + Repeat(not_ahead(line_end); body) + line_end、Repeat(Choice(match_only(line_end), body))、CR/LFを除く文字クラスのRepeat + line_end を実際に比較したが全観測は再現できない。#846で任意terminatorの成功停止・親kind別commit/rollbackと原CSTを揃えた後、この2型の全kind/invertを検証する。

実装: [unlaxer-common/src/main/java/org/unlaxer/parser/elementary/EmptyLineParser.java#L12](../unlaxer-common/src/main/java/org/unlaxer/parser/elementary/EmptyLineParser.java#L12)、[unlaxer-common/src/main/java/org/unlaxer/parser/elementary/WildCardLineParser.java#L7](../unlaxer-common/src/main/java/org/unlaxer/parser/elementary/WildCardLineParser.java#L7)、[unlaxer-common/src/main/java/org/unlaxer/parser/combinator/Occurs.java#L67](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/Occurs.java#L67)、[conformance/line-boundary/probe.rs#L400](../conformance/line-boundary/probe.rs#L400)、[conformance/line-boundary/probe.rs#L454](../conformance/line-boundary/probe.rs#L454)、[conformance/line-boundary/probe.rs#L491](../conformance/line-boundary/probe.rs#L491)

テスト: [unlaxer-dsl/src/test/java/org/unlaxer/dsl/codegen/LineBoundaryConformanceTest.java](../unlaxer-dsl/src/test/java/org/unlaxer/dsl/codegen/LineBoundaryConformanceTest.java)、[rust/unlaxer-runtime/tests/line_boundary.rs](../rust/unlaxer-runtime/tests/line_boundary.rs)

共通 fixture: [conformance/line-boundary/compositions.tsv](../conformance/line-boundary/compositions.tsv)、[conformance/line-boundary/repeat-choice.tsv](../conformance/line-boundary/repeat-choice.tsv)、[conformance/line-boundary/char-class.tsv](../conformance/line-boundary/char-class.tsv)、[conformance/line-boundary/README.md](../conformance/line-boundary/README.md)

追跡: [#848](https://github.com/opaopa6969/unlaxer-parser/issues/848)、[#846](https://github.com/opaopa6969/unlaxer-parser/issues/846)

### suggest

**未対応（記載した動作）**。SuggestableParser は候補文字列の認識と、入力 prefix を短くして最初に候補が得られる集合を返す getSuggests を持つ。Suggest は候補と suggestedBy identity、Collector は siblings を走査し stopped を返す。Rust LSP の keyword/provider completion は snapshot と cursor に基づく別契約で、この候補算法・停止結果・identity の対応にはならない。

利用経路: 認識だけなら `Expr::Choice` / `CaseInsensitive`。補完候補集合、suggestedBy の安定 ID、stopped 相当の結果を parser/補完 adapter の共通例として用意する。Java の未接続 addSuggests や TODO を Rust 完了の根拠にしない。

実装: [unlaxer-common/src/main/java/org/unlaxer/parser/SuggestableParser.java#L17](../unlaxer-common/src/main/java/org/unlaxer/parser/SuggestableParser.java#L17)、[unlaxer-common/src/main/java/org/unlaxer/parser/Suggest.java#L6](../unlaxer-common/src/main/java/org/unlaxer/parser/Suggest.java#L6)、[unlaxer-common/src/main/java/org/unlaxer/parser/SuggestsCollectorParser.java#L16](../unlaxer-common/src/main/java/org/unlaxer/parser/SuggestsCollectorParser.java#L16)、[rust/unlaxer-lsp/src/lib.rs#L980](../rust/unlaxer-lsp/src/lib.rs#L980)

テスト: 全API動作の共通根拠は未整備。

共通 fixture: 全API動作の共通根拠は未整備。

追跡: [#850](https://github.com/opaopa6969/unlaxer-parser/issues/850)

### tag-wrapper

**未対応（記載した動作）**。TagWrapper は構築時に子 parser の Tag を add/remove し、その子を呼ぶ。Rust portable token metadata は parse ごとの node 値であり、共有 parser graph の tag mutation/伝播/探索の代替ではない。

利用経路: 明示 Rule ID→tag set と構築時 policy、および公開 tree/metadata の利用例が必要。共有 graph と context-local metadata の所有境界を決め、Java raw Object を自動変換すると主張しない。

実装: [unlaxer-common/src/main/java/org/unlaxer/parser/combinator/TagWrapper.java#L10](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/TagWrapper.java#L10)、[unlaxer-common/src/main/java/org/unlaxer/ParserTaggable.java#L8](../unlaxer-common/src/main/java/org/unlaxer/ParserTaggable.java#L8)、[rust/unlaxer-runtime/src/token.rs#L1](../rust/unlaxer-runtime/src/token.rs#L1)

テスト: 全API動作の共通根拠は未整備。

共通 fixture: 全API動作の共通根拠は未整備。

追跡: [#851](https://github.com/opaopa6969/unlaxer-parser/issues/851)

### not-ast-node

**未対応（記載した動作）**。NotASTNode は子へ notNode tag を追加し、汎用 TagBasedReducer はその node を縮約して子を昇格する。Rust generated mapper の @skip は subtree 探索を省く契約なので同等ではない。ASTNode の tag remove と複数 wrapper 順序も追跡する。

利用経路: 原 CST を保存したまま tag に応じた reduced tree を作る Rust projector と共通 fixture が必要。既存 UBNF `@skip` をこの行の対応済み証拠にしない。

実装: [unlaxer-common/src/main/java/org/unlaxer/parser/combinator/NotASTNode.java#L8](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/NotASTNode.java#L8)、[unlaxer-common/src/main/java/org/unlaxer/parser/combinator/ASTNode.java#L8](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/ASTNode.java#L8)、[unlaxer-common/src/main/java/org/unlaxer/reducer/TagBasedReducer.java#L7](../unlaxer-common/src/main/java/org/unlaxer/reducer/TagBasedReducer.java#L7)、[rust/unlaxer-runtime/src/mapping.rs#L1](../rust/unlaxer-runtime/src/mapping.rs#L1)

テスト: [unlaxer-dsl/src/test/java/org/unlaxer/dsl/codegen/SkipMappingConformanceTest.java](../unlaxer-dsl/src/test/java/org/unlaxer/dsl/codegen/SkipMappingConformanceTest.java)

共通 fixture: 全API動作の共通根拠は未整備。

追跡: [#851](https://github.com/opaopa6969/unlaxer-parser/issues/851)

### parsed-predicate

**未対応（記載した動作）**。PredicateAnyMatchForParsedParser は子の成功後、rootToken.flatten のいずれかが任意 Token predicate に合致しなければ Parsed を negate する。NamePredicate は immutable 名前 snapshot、CaptureEquality は意味診断であり、この CST predicate を代替しない。

利用経路: `ParseContext::parse/transaction` と返却 nodes/Tree の走査は部品。predicate 失敗時の consumed/matched・CST・state・failure位置を保つ公開 wrapper または adapter 例が必要。

実装: [unlaxer-common/src/main/java/org/unlaxer/parser/combinator/PredicateAnyMatchForParsedParser.java#L13](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/PredicateAnyMatchForParsedParser.java#L13)、[rust/unlaxer-runtime/src/lib.rs#L2428](../rust/unlaxer-runtime/src/lib.rs#L2428)、[rust/unlaxer-runtime/src/lib.rs#L2650](../rust/unlaxer-runtime/src/lib.rs#L2650)

テスト: 全API動作の共通根拠は未整備。

共通 fixture: 全API動作の共通根拠は未整備。

追跡: [#851](https://github.com/opaopa6969/unlaxer-parser/issues/851)

### escape-in-quoted

**対応あり・全API共通検証なし**。EscapeInQuotedParser は backslash と WildCardStringParser（1コードポイント）の LazyChain。Rust Quoted 内部にも backslash＋1scalar があり復号しないが、単独呼出し・trivia・Classic引数の独立 fixture はない。

利用経路: 単独なら `Expr::sequence([Expr::literal("\\"), Expr::Any])`、引用全体なら `Expr::Quoted(quote)`。trivia policy と短い末尾 backslash の位置を確認して使い分ける。

実装: [unlaxer-common/src/main/java/org/unlaxer/parser/elementary/EscapeInQuotedParser.java#L9](../unlaxer-common/src/main/java/org/unlaxer/parser/elementary/EscapeInQuotedParser.java#L9)、[rust/unlaxer-runtime/src/lib.rs#L123](../rust/unlaxer-runtime/src/lib.rs#L123)、[rust/unlaxer-runtime/src/lib.rs#L4098](../rust/unlaxer-runtime/src/lib.rs#L4098)

テスト: [unlaxer-dsl/src/test/java/org/unlaxer/dsl/codegen/RustConformanceTest.java](../unlaxer-dsl/src/test/java/org/unlaxer/dsl/codegen/RustConformanceTest.java)

共通 fixture: [unlaxer-dsl/src/test/resources/lexical/corpus.json](../unlaxer-dsl/src/test/resources/lexical/corpus.json)

追跡: [#849](https://github.com/opaopa6969/unlaxer-parser/issues/849)

### parentheses

**対応あり・全API共通検証なし**。ParenthesesParser は whitespace-delimited chain と inner parser identity による Token 抽出。Rust は sequence/trivia/capture で認識と inner 範囲を表せるが、Java の getParenthesesed/getInnerParserParsed の誤token拒否や named版の AST child選択を共通比較していない。

利用経路: `Expr::sequence([Literal("("), inner.capture("inner"), Literal(")")]).trivia_scope(true)`、または UBNF の括弧 literal と capture。Java identity による抽出は明示 capture 名/Rule ID へ移す。

実装: [unlaxer-common/src/main/java/org/unlaxer/parser/elementary/ParenthesesParser.java#L12](../unlaxer-common/src/main/java/org/unlaxer/parser/elementary/ParenthesesParser.java#L12)、[unlaxer-common/src/main/java/org/unlaxer/parser/elementary/NamedParenthesesParser.java#L12](../unlaxer-common/src/main/java/org/unlaxer/parser/elementary/NamedParenthesesParser.java#L12)、[rust/unlaxer-runtime/src/lib.rs#L375](../rust/unlaxer-runtime/src/lib.rs#L375)、[rust/unlaxer-runtime/src/lib.rs#L490](../rust/unlaxer-runtime/src/lib.rs#L490)

テスト: [unlaxer-dsl/src/test/java/org/unlaxer/dsl/codegen/RustConformanceTest.java](../unlaxer-dsl/src/test/java/org/unlaxer/dsl/codegen/RustConformanceTest.java)

共通 fixture: 全API動作の共通根拠は未整備。

追跡: [#849](https://github.com/opaopa6969/unlaxer-parser/issues/849)

### character-recipes

**対応あり・全API共通検証なし**。ASCII/POSIX と SingleCharacter/SingleString/MappedSingleCharacter は Java に記載された文字集合・char/int override で認識する。Rust Character/CharRange/Any/Choice で同じ集合を構成できるが、全公開クラス・newWithout・各 token kind/invert・expected文字列を共通 corpus が網羅していない。POSIX 名から Unicode 標準関数へ拡張しない。

利用経路: `Expr::character("label", predicate)` に各 Java constructor の集合をそのまま移す。例: Blank は space/tab、Space は CP32,9..13、Word は ASCII英数と underscore、Ascii は0..127。単一記号は `Expr::literal`。Java char-only override の非BMP拒否も保存する。

実装: [rust/unlaxer-runtime/src/lib.rs#L372](../rust/unlaxer-runtime/src/lib.rs#L372)、[unlaxer-common/src/main/java/org/unlaxer/parser/elementary/SingleCharacterParser.java#L14](../unlaxer-common/src/main/java/org/unlaxer/parser/elementary/SingleCharacterParser.java#L14)、[unlaxer-common/src/main/java/org/unlaxer/parser/elementary/SingleStringParser.java#L15](../unlaxer-common/src/main/java/org/unlaxer/parser/elementary/SingleStringParser.java#L15)、[unlaxer-common/src/main/java/org/unlaxer/parser/elementary/MappedSingleCharacterParser.java#L10](../unlaxer-common/src/main/java/org/unlaxer/parser/elementary/MappedSingleCharacterParser.java#L10)

テスト: [rust/unlaxer-runtime/tests/token_propagation.rs](../rust/unlaxer-runtime/tests/token_propagation.rs)

共通 fixture: [conformance/token-propagation/cases.tsv](../conformance/token-propagation/cases.tsv)

追跡: [#849](https://github.com/opaopa6969/unlaxer-parser/issues/849)

### lexical-composites

**対応あり・全API共通検証なし**。Number/Identifier/固定single-double quote は Rust の専用 atom に対応し既存 corpus がある。ただし任意 quote parser、Quoted.contents/Parts/QuotedContentsParser、補助数字parserの直接利用と全CST抽出まで同じ共通 corpus では未確認。

利用経路: `Expr::Number` / `Identifier` / `Quoted('"')`、UBNF token binding。汎用 QuotedParser は明示 sequence/quote/body の移植例を追加する。

実装: [rust/unlaxer-runtime/src/lib.rs#L111](../rust/unlaxer-runtime/src/lib.rs#L111)、[unlaxer-common/src/main/java/org/unlaxer/parser/elementary/QuotedParser.java#L19](../unlaxer-common/src/main/java/org/unlaxer/parser/elementary/QuotedParser.java#L19)

テスト: [unlaxer-dsl/src/test/java/org/unlaxer/dsl/codegen/RustConformanceTest.java](../unlaxer-dsl/src/test/java/org/unlaxer/dsl/codegen/RustConformanceTest.java)、[rust/unlaxer-runtime/tests/number_token.rs](../rust/unlaxer-runtime/tests/number_token.rs)

共通 fixture: [unlaxer-dsl/src/test/resources/lexical/corpus.json](../unlaxer-dsl/src/test/resources/lexical/corpus.json)、[conformance/number-token/cases.tsv](../conformance/number-token/cases.tsv)

追跡: [#849](https://github.com/opaopa6969/unlaxer-parser/issues/849)

### word-transforms

**対応あり・全API共通検証なし**。WordParser は通常word/ignoreCaseだけでなく Source・slice/reverse/effect と派生 factory相当の instance API を持つ。Rust Literal/CaseInsensitive は認識部品だが、任意 effect・範囲・source provenance の全経路は共通検証されていない。

利用経路: `Expr::Literal`（空は JavaEmptyWord）、固定Unicode CIは CaseInsensitive。実行前にホストで値変換してから明示 parser を構成する境界を文書化する。JDK依存 IgnoreCaseWordParser と Unicode17 固定版を混同しない。

実装: [unlaxer-common/src/main/java/org/unlaxer/parser/elementary/WordParser.java#L21](../unlaxer-common/src/main/java/org/unlaxer/parser/elementary/WordParser.java#L21)、[unlaxer-common/src/main/java/org/unlaxer/parser/elementary/IgnoreCaseWordParser.java#L3](../unlaxer-common/src/main/java/org/unlaxer/parser/elementary/IgnoreCaseWordParser.java#L3)、[rust/unlaxer-runtime/src/lib.rs#L102](../rust/unlaxer-runtime/src/lib.rs#L102)

テスト: [unlaxer-common/src/test/java/org/unlaxer/parser/elementary/UnicodeIgnoreCaseWordParserTest.java](../unlaxer-common/src/test/java/org/unlaxer/parser/elementary/UnicodeIgnoreCaseWordParserTest.java)、[rust/unlaxer-runtime/tests/case_insensitive.rs](../rust/unlaxer-runtime/tests/case_insensitive.rs)

共通 fixture: [spec-corpus/case-insensitive/jdk21-legacy-delta.tsv](../spec-corpus/case-insensitive/jdk21-legacy-delta.tsv)

追跡: [#852](https://github.com/opaopa6969/unlaxer-parser/issues/852)

### historical-replay

**対応あり・全API共通検証なし**。MatchedTokenParser は呼出しごとに現在の token stack から predicate で候補を集め、slice/effect/reverse 後に試す（#1017 で context 全体に残り rollback 済み・前回の候補を使い続ける cache を撤去）。空の投影は入力に紐づく幅0 token で一致し、反転時は失敗する。WordParser("") 自体は UBNF '' の契約どおり一致しない。Old版は単一 token。Rust Backreference と owned Replay は記録済み名前を replay できるが、class 単位の token predicate・複数履歴候補・任意変換・反転まで同等の公開APIはない。

利用経路: 新規コードは `capture_owned("name")` と `Expr::Replay("name")`、UBNF `CAPTURE/SAME_AS`。旧APIを移植する場合は token selector/変換/履歴寿命の明示 adapter を追加し共通 oracle で検証する。Usage003 の Palindrome 5 recipe は、最新 owned binding を選び Java Slicer と同じ CP 投影を照合して入力由来の消費 leaf を公開する `Expr::Custom` adapter と `capture_scope` で共通 fixture を実行する（#1017、移植例であり API 対応の完了ではない）。

実装: [unlaxer-common/src/main/java/org/unlaxer/parser/referencer/MatchedTokenParser.java#L25](../unlaxer-common/src/main/java/org/unlaxer/parser/referencer/MatchedTokenParser.java#L25)、[unlaxer-common/src/main/java/org/unlaxer/parser/referencer/OldMatchedTokenParser.java#L19](../unlaxer-common/src/main/java/org/unlaxer/parser/referencer/OldMatchedTokenParser.java#L19)、[rust/unlaxer-runtime/src/lib.rs#L4202](../rust/unlaxer-runtime/src/lib.rs#L4202)

テスト: [unlaxer-common/src/test/java/org/unlaxer/context/OwnedCaptureTest.java](../unlaxer-common/src/test/java/org/unlaxer/context/OwnedCaptureTest.java)、[rust/unlaxer-runtime/tests/owned_capture.rs](../rust/unlaxer-runtime/tests/owned_capture.rs)、[unlaxer-common/src/test/java/sample/PalindromeSampleTest.java](../unlaxer-common/src/test/java/sample/PalindromeSampleTest.java)、[rust/unlaxer-runtime/tests/palindrome_sample.rs](../rust/unlaxer-runtime/tests/palindrome_sample.rs)

共通 fixture: [conformance/owned-capture/cases.tsv](../conformance/owned-capture/cases.tsv)、[conformance/samples/palindrome.tsv](../conformance/samples/palindrome.tsv)

追跡: [#852](https://github.com/opaopa6969/unlaxer-parser/issues/852)

### owned-capture

**対応済み（記載した共通契約）**。名前付き owned capture/replay、capture scope、Input/Projected/Generated の provenance と失敗後状態は共通 corpus の契約に対応。旧 MatchedTokenParser の任意 selector/変換を含まない。

利用経路: `expr.capture_owned("x").capture_scope()`、`Expr::Replay("x")`。UBNF `CAPTURE/CAPTURE_SCOPE/SAME_AS`。

実装: [rust/unlaxer-runtime/src/lib.rs#L460](../rust/unlaxer-runtime/src/lib.rs#L460)、[rust/unlaxer-runtime/src/lib.rs#L4202](../rust/unlaxer-runtime/src/lib.rs#L4202)、[unlaxer-common/src/main/java/org/unlaxer/parser/combinator/NamedCapture.java#L15](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/NamedCapture.java#L15)、[unlaxer-common/src/main/java/org/unlaxer/parser/combinator/CaptureScope.java#L15](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/CaptureScope.java#L15)、[unlaxer-common/src/main/java/org/unlaxer/parser/elementary/ReplayParser.java#L18](../unlaxer-common/src/main/java/org/unlaxer/parser/elementary/ReplayParser.java#L18)

テスト: [unlaxer-common/src/test/java/org/unlaxer/context/OwnedCaptureTest.java](../unlaxer-common/src/test/java/org/unlaxer/context/OwnedCaptureTest.java)、[rust/unlaxer-runtime/tests/owned_capture.rs](../rust/unlaxer-runtime/tests/owned_capture.rs)、[unlaxer-dsl/src/test/java/org/unlaxer/dsl/codegen/OwnedCaptureConformanceTest.java](../unlaxer-dsl/src/test/java/org/unlaxer/dsl/codegen/OwnedCaptureConformanceTest.java)

共通 fixture: [conformance/owned-capture/cases.tsv](../conformance/owned-capture/cases.tsv)、[conformance/owned-capture/state.tsv](../conformance/owned-capture/state.tsv)、[conformance/owned-capture/failures.tsv](../conformance/owned-capture/failures.tsv)

### capture-boundaries

**対応済み（記載した共通契約）**。明示 value/optional/list/input capture boundary と origin、CaptureSnapshot.from の所有値・CP span・全token kind・reset・rollbackを共通 corpus で検証する。snapshot の INVALID_TREE/LIMIT は Java/Rust 各テストが同じ分類を確認するが、共通 fixture の行ではない。

利用経路: `Expr::CaptureBoundary(kind, child)` / `InputCaptureBoundary` と `capture_tree::snapshot`。UBNF nested capture 生成経路も既存。

実装: [rust/unlaxer-runtime/src/lib.rs#L164](../rust/unlaxer-runtime/src/lib.rs#L164)、[rust/unlaxer-runtime/src/capture_tree.rs#L1](../rust/unlaxer-runtime/src/capture_tree.rs#L1)、[unlaxer-common/src/main/java/org/unlaxer/parser/combinator/CaptureBoundaryParser.java#L7](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/CaptureBoundaryParser.java#L7)、[unlaxer-common/src/main/java/org/unlaxer/capture/CaptureSnapshot.java#L10](../unlaxer-common/src/main/java/org/unlaxer/capture/CaptureSnapshot.java#L10)、[rust/unlaxer-runtime/src/capture_tree.rs#L103](../rust/unlaxer-runtime/src/capture_tree.rs#L103)

テスト: [unlaxer-common/src/test/java/org/unlaxer/context/CaptureBoundaryTest.java](../unlaxer-common/src/test/java/org/unlaxer/context/CaptureBoundaryTest.java)、[rust/unlaxer-runtime/tests/capture_boundaries.rs](../rust/unlaxer-runtime/tests/capture_boundaries.rs)

共通 fixture: [conformance/capture-boundaries/runtime.tsv](../conformance/capture-boundaries/runtime.tsv)、[conformance/input-capture-boundary-reset/cases.tsv](../conformance/input-capture-boundary-reset/cases.tsv)

### classic-propagation

**対応済み（記載した共通契約）**。MatchOnly と consume/invert stopper の実 leaf 伝播・両cursor・CST・所有stateの復元は共通 corpus の契約。PEG先読みとは区別する。

利用経路: `expr.match_only()/invert_match()/stop_invert()/stop_consume()` と `ParseContext::parse_classic`。Java NotPropagatableSource は invert_match。

実装: [rust/unlaxer-runtime/src/lib.rs#L538](../rust/unlaxer-runtime/src/lib.rs#L538)、[rust/unlaxer-runtime/src/token.rs#L1](../rust/unlaxer-runtime/src/token.rs#L1)、[unlaxer-common/src/main/java/org/unlaxer/parser/combinator/MatchOnly.java#L10](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/MatchOnly.java#L10)

テスト: [unlaxer-common/src/test/java/org/unlaxer/context/GeneralTokenPropagationTest.java](../unlaxer-common/src/test/java/org/unlaxer/context/GeneralTokenPropagationTest.java)、[rust/unlaxer-runtime/tests/token_propagation.rs](../rust/unlaxer-runtime/tests/token_propagation.rs)

共通 fixture: [conformance/token-propagation/cases.tsv](../conformance/token-propagation/cases.tsv)、[conformance/token-propagation/state.tsv](../conformance/token-propagation/state.tsv)

### propagation-markers

**対応済み（記載した共通契約）**。利用者定義の AbstractPropagatableSource（反転を合成し委譲するだけ）と InvertMatchPropagationStopper（PropagationStopper）を rule 参照越しに使い、Rust の Custom parser（token_mode を反転して子を parse）・invert_match・stop_invert と cases.tsv の consumed/matchOnly×invert で cursor・CST・失敗CPが一致する。PropagatableDestination の親 source 読み出し（source なし false、parse 後 true）は Rust の context.token_mode().invert と比較する。Java は計算済み反転を共有 parser instance に保存し parse 前の読み出しは IllegalStateException、Rust は context ごとの token_mode に置き共有 graph に状態を持たない（construction.tsv）。marker の付与だけでは Rust の安全宣言にしない。

利用経路: `expr.invert_match()` / `expr.stop_invert()` / `stop_consume()`、利用者定義は `Expr::Custom(f)` で `let mode = context.token_mode(); context.parse_with_token_mode(&child, mode.kind, !mode.invert)`。

実装: [unlaxer-common/src/main/java/org/unlaxer/parser/combinator/AbstractPropagatableSource.java#L36](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/AbstractPropagatableSource.java#L36)、[unlaxer-common/src/main/java/org/unlaxer/parser/PropagatableDestination.java#L20](../unlaxer-common/src/main/java/org/unlaxer/parser/PropagatableDestination.java#L20)、[rust/unlaxer-runtime/src/lib.rs#L154](../rust/unlaxer-runtime/src/lib.rs#L154)、[rust/unlaxer-runtime/src/token.rs#L234](../rust/unlaxer-runtime/src/token.rs#L234)

テスト: [unlaxer-common/src/test/java/org/unlaxer/parser/ParserConstructionParityTest.java](../unlaxer-common/src/test/java/org/unlaxer/parser/ParserConstructionParityTest.java)、[rust/unlaxer-runtime/tests/parser_construction.rs](../rust/unlaxer-runtime/tests/parser_construction.rs)、[rust/unlaxer-runtime/tests/token_propagation.rs](../rust/unlaxer-runtime/tests/token_propagation.rs)

共通 fixture: [conformance/parser-construction/cases.tsv](../conformance/parser-construction/cases.tsv)、[conformance/parser-construction/construction.tsv](../conformance/parser-construction/construction.tsv)、[conformance/token-propagation/cases.tsv](../conformance/token-propagation/cases.tsv)

追跡: [#845](https://github.com/opaopa6969/unlaxer-parser/issues/845)

### peg-assertion

**対応済み（記載した共通契約）**。PegLookahead は両cursor・CST・登録stateを復元し、極性をClassic invertから分離する。両言語が共通 cases/state を実行する。

利用経路: `expr.ahead()` / `expr.not_ahead()`。Java Not（match-only child）とは別契約。

実装: [unlaxer-common/src/main/java/org/unlaxer/parser/combinator/PegLookahead.java#L17](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/PegLookahead.java#L17)、[rust/unlaxer-runtime/src/lib.rs#L532](../rust/unlaxer-runtime/src/lib.rs#L532)

テスト: [unlaxer-common/src/test/java/org/unlaxer/parser/combinator/PegLookaheadTest.java](../unlaxer-common/src/test/java/org/unlaxer/parser/combinator/PegLookaheadTest.java)、[rust/unlaxer-runtime/tests/peg_assertion.rs](../rust/unlaxer-runtime/tests/peg_assertion.rs)

共通 fixture: [conformance/peg-assertion/cases.tsv](../conformance/peg-assertion/cases.tsv)、[conformance/peg-assertion/state.tsv](../conformance/peg-assertion/state.tsv)

### legacy-not

**対応あり・全API共通検証なし**。Java Not は child を matchOnly で呼んで結果を反転し、成功側の所有state/診断を戻す。Rust JavaLookahead(false) は固定pattern、not_ahead は純PEGであり任意childのClassic版と自動的には等価でない。固定pattern の Not(Word) を Occurs terminator にした場合の二重収集（空の consumed token）は terminated-repeat の wrappers.tsv で両言語を比較済みで、任意 child は未検証のまま。

利用経路: 固定patternは `Expr::JavaLookahead { pattern, positive: false }`。任意childは Classic 引数・raised failure・cursorを保つ組合せの共通例を追加する。

実装: [unlaxer-common/src/main/java/org/unlaxer/parser/combinator/Not.java#L9](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/Not.java#L9)、[rust/unlaxer-runtime/src/lib.rs#L261](../rust/unlaxer-runtime/src/lib.rs#L261)、[rust/unlaxer-runtime/src/lib.rs#L550](../rust/unlaxer-runtime/src/lib.rs#L550)

テスト: [unlaxer-common/src/test/java/org/unlaxer/context/GeneralTokenPropagationTest.java](../unlaxer-common/src/test/java/org/unlaxer/context/GeneralTokenPropagationTest.java)

共通 fixture: 全API動作の共通根拠は未整備。

追跡: [#853](https://github.com/opaopa6969/unlaxer-parser/issues/853)

### cut-labels

**対応済み（記載した共通契約）**。通常失敗のcommit/label昇格、指定labelのcatch、原位置とstate rollbackを共通 corpus で比較する。資源失敗を通常の選択へ吸収しない。

利用経路: `expr.commit()/label("x")/catch_labels(other, ["x"])`。UBNF `COMMIT/LABEL/CATCH`。

実装: [rust/unlaxer-runtime/src/lib.rs#L4760](../rust/unlaxer-runtime/src/lib.rs#L4760)、[unlaxer-common/src/main/java/org/unlaxer/parser/combinator/Commit.java#L22](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/Commit.java#L22)、[unlaxer-common/src/main/java/org/unlaxer/parser/combinator/Label.java#L22](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/Label.java#L22)、[unlaxer-common/src/main/java/org/unlaxer/parser/combinator/CatchLabels.java#L28](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/CatchLabels.java#L28)

テスト: [unlaxer-common/src/test/java/org/unlaxer/parser/combinator/CutLabeledFailureTest.java](../unlaxer-common/src/test/java/org/unlaxer/parser/combinator/CutLabeledFailureTest.java)、[rust/unlaxer-runtime/tests/cut_labeled_failure.rs](../rust/unlaxer-runtime/tests/cut_labeled_failure.rs)

共通 fixture: [conformance/cut-labeled-failure/cases.tsv](../conformance/cut-labeled-failure/cases.tsv)、[conformance/cut-labeled-failure/state.tsv](../conformance/cut-labeled-failure/state.tsv)

### core-combinators

**対応あり・全API共通検証なし**。sequence/ordered/choice/longest/unique-longest/predictive/zero は Rust に専用経路があり個別テストもある。ただし全 constructed/lazy overload と collect/createMetaToken、chosen抽出、手書き graph の共通API行ごとの検証は未完了。

利用経路: `Expr::sequence/choice/longest_choice/unique_longest_choice/predictive_choice/zero_match`。Lazy版は構築済み Rule参照へ移す。min/max反復の terminator は別行。

実装: [rust/unlaxer-runtime/src/lib.rs#L375](../rust/unlaxer-runtime/src/lib.rs#L375)、[rust/unlaxer-runtime/src/lib.rs#L5713](../rust/unlaxer-runtime/src/lib.rs#L5713)、[unlaxer-common/src/main/java/org/unlaxer/parser/combinator/ChainInterface.java#L11](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/ChainInterface.java#L11)、[unlaxer-common/src/main/java/org/unlaxer/parser/combinator/ChoiceInterface.java#L14](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/ChoiceInterface.java#L14)

テスト: [unlaxer-common/src/test/java/org/unlaxer/parser/combinator/LongestChoiceTest.java](../unlaxer-common/src/test/java/org/unlaxer/parser/combinator/LongestChoiceTest.java)、[unlaxer-common/src/test/java/org/unlaxer/parser/combinator/UniqueLongestChoiceTest.java](../unlaxer-common/src/test/java/org/unlaxer/parser/combinator/UniqueLongestChoiceTest.java)、[unlaxer-common/src/test/java/org/unlaxer/parser/combinator/PredictiveChoiceTest.java](../unlaxer-common/src/test/java/org/unlaxer/parser/combinator/PredictiveChoiceTest.java)、[rust/unlaxer-runtime/tests/longest_choice.rs](../rust/unlaxer-runtime/tests/longest_choice.rs)、[rust/unlaxer-runtime/tests/unique_longest.rs](../rust/unlaxer-runtime/tests/unique_longest.rs)、[rust/unlaxer-runtime/tests/zero_repeat.rs](../rust/unlaxer-runtime/tests/zero_repeat.rs)

共通 fixture: 全API動作の共通根拠は未整備。

追跡: [#853](https://github.com/opaopa6969/unlaxer-parser/issues/853)

### graph-utilities

**対応あり・全API共通検証なし**。Flatten は child.getChildren を Chain化し、Reverse は一部 constructor だけ元の Parsers を反転する。ParserHolder/MappedSingleCharacterParserHolder は引数を捨て child.parse(context)、ParserWrapper は保存した kind/invert で呼ぶ。ParserWrapper/ParserHolder/MappedSingleCharacterParserHolder の既定形・matchOnly 固定・matchOnly+invert 固定・ParserHolder(MatchOnly) は、#846 の holders.tsv（900行・9 scenario、Java21 実測）で terminator・body・Chain 要素の位置を Java は全 constructor、Rust は既存公開 API の recipe で全列比較済み。残る未検証: Flatten/Reverse の各 overload、ParserWrapper(p, consumed, true)（Rust invert_match は自分の node を持ち CST が1段深い）、virtual kind 固定の ParserWrapper（Rust に kind を virtual に固定する式がない）、createMetaToken=false（meta-function の境界）、custom parser 経路。

利用経路: `ParserWrapper(p)` / `ParserHolder(p)` / `MappedSingleCharacterParserHolder(c)` ⇔ `p.stop_consume().stop_invert()`（Chain なら素の `p` も可）、`ParserWrapper(p, matchOnly, false)` / `ParserWrapper(MatchOnly(p))` ⇔ `p.match_only()`、`ParserWrapper(p, matchOnly, true)` ⇔ `p.invert_match().match_only()`、`ParserHolder(MatchOnly(p))` ⇔ `p.match_only().stop_consume().stop_invert()`。素の `p` と `Expr::sequence([p])` は CST の段数がずれるので写しにならない。Flatten は `Expr::sequence(children)`、Reverse は明示 `Vec::reverse`。Reverse の varargs は Java でも反転しないので、名前から全constructorを逆順扱いしない。

実装: [unlaxer-common/src/main/java/org/unlaxer/parser/combinator/Reverse.java#L10](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/Reverse.java#L10)、[unlaxer-common/src/main/java/org/unlaxer/parser/combinator/Flatten.java#L13](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/Flatten.java#L13)、[unlaxer-common/src/main/java/org/unlaxer/parser/combinator/ParserHolder.java#L10](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/ParserHolder.java#L10)、[unlaxer-common/src/main/java/org/unlaxer/parser/combinator/ParserWrapper.java#L10](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/ParserWrapper.java#L10)、[rust/unlaxer-runtime/src/token.rs#L1](../rust/unlaxer-runtime/src/token.rs#L1)

テスト: [unlaxer-common/src/test/java/org/unlaxer/parser/combinator/TerminatedRepeatParityTest.java](../unlaxer-common/src/test/java/org/unlaxer/parser/combinator/TerminatedRepeatParityTest.java)、[rust/unlaxer-runtime/tests/terminated_repeat.rs](../rust/unlaxer-runtime/tests/terminated_repeat.rs)

共通 fixture: [conformance/terminated-repeat/holders.tsv](../conformance/terminated-repeat/holders.tsv)、[conformance/terminated-repeat/README.md](../conformance/terminated-repeat/README.md)

追跡: [#846](https://github.com/opaopa6969/unlaxer-parser/issues/846)、[#853](https://github.com/opaopa6969/unlaxer-parser/issues/853)

### trivia-recipes

**対応あり・全API共通検証なし**。SpaceDelimitor/CStyleDelimitor と whitespace-delimited chain は既存 Rust trivia policyに対応する部分がある。独立comment parserのCST、全lazy overload、正確な空白集合と終端条件を共通 fixture で網羅していない。

利用経路: `Expr::sequence(...).trivia_scope(true)`、`line_comment_scope()`、UBNF `@whitespace/@comment/@interleave`。コメントだけの CPPComment は line_comment_scope。直接 comment leafは専用 lexical式/adapterで抽出する。

実装: [rust/unlaxer-runtime/src/lib.rs#L4098](../rust/unlaxer-runtime/src/lib.rs#L4098)、[unlaxer-common/src/main/java/org/unlaxer/parser/combinator/WhiteSpaceDelimitedLazyChain.java#L16](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/WhiteSpaceDelimitedLazyChain.java#L16)、[unlaxer-common/src/main/java/org/unlaxer/parser/clang/BlockComment.java#L10](../unlaxer-common/src/main/java/org/unlaxer/parser/clang/BlockComment.java#L10)、[unlaxer-common/src/main/java/org/unlaxer/parser/clang/CPPComment.java#L15](../unlaxer-common/src/main/java/org/unlaxer/parser/clang/CPPComment.java#L15)

テスト: [unlaxer-dsl/src/test/java/org/unlaxer/dsl/codegen/RuleTriviaConformanceTest.java](../unlaxer-dsl/src/test/java/org/unlaxer/dsl/codegen/RuleTriviaConformanceTest.java)

共通 fixture: [conformance/token-propagation/sequence-trivia.tsv](../conformance/token-propagation/sequence-trivia.tsv)

追跡: [#849](https://github.com/opaopa6969/unlaxer-parser/issues/849)

### wildcard-interleave

**対応あり・全API共通検証なし**。WildCardInterleaveParser は指定 parser の choice＋wildcard を反復し、補助メソッドで連続文字 token を再結合する。Rust choice/repeatで認識できるが、getParsedWithConcattedCharcter の detached source と末尾処理まで同じ corpus では検証していない。

利用経路: `Expr::choice([... , Expr::Any]).repeat_java(0, None)` と明示 owned token projector。文字再結合は user mapper として原文位置を保つ契約を追加する。

実装: [unlaxer-common/src/main/java/org/unlaxer/parser/elementary/WildCardInterleaveParser.java#L20](../unlaxer-common/src/main/java/org/unlaxer/parser/elementary/WildCardInterleaveParser.java#L20)、[rust/unlaxer-runtime/src/lib.rs#L435](../rust/unlaxer-runtime/src/lib.rs#L435)

テスト: 全API動作の共通根拠は未整備。

共通 fixture: 全API動作の共通根拠は未整備。

追跡: [#849](https://github.com/opaopa6969/unlaxer-parser/issues/849)

### empty-eof

**対応あり・全API共通検証なし**。EmptyParser は非EOFで matchedを1進める JavaEmpty、EOF は Eof。既存の境界検証はあるが直接公開parserと全kind/invert/初期cursorの行列はAPI単位で未整理。Empty を純PEG Empty と置換しない。

利用経路: `Expr::JavaEmpty` / `Expr::Eof`。UBNF `EMPTY/EOF`。

実装: [unlaxer-common/src/main/java/org/unlaxer/parser/elementary/EmptyParser.java#L12](../unlaxer-common/src/main/java/org/unlaxer/parser/elementary/EmptyParser.java#L12)、[unlaxer-common/src/main/java/org/unlaxer/parser/elementary/EndOfSourceParser.java#L11](../unlaxer-common/src/main/java/org/unlaxer/parser/elementary/EndOfSourceParser.java#L11)、[rust/unlaxer-runtime/src/lib.rs#L266](../rust/unlaxer-runtime/src/lib.rs#L266)

テスト: [rust/unlaxer-runtime/tests/nonordered.rs](../rust/unlaxer-runtime/tests/nonordered.rs)、[unlaxer-common/src/test/java/org/unlaxer/parser/combinator/NonOrderedParityTest.java](../unlaxer-common/src/test/java/org/unlaxer/parser/combinator/NonOrderedParityTest.java)

共通 fixture: [spec-corpus/nonordered/cases.tsv](../spec-corpus/nonordered/cases.tsv)

追跡: [#853](https://github.com/opaopa6969/unlaxer-parser/issues/853)

### unicode-ci

**対応済み（記載した共通契約）**。UnicodeIgnoreCaseWordParser の固定Unicode17 simple fold、原文CP、invert/match-only、派生wordの契約。JDK依存の旧IgnoreCase版は別行。

利用経路: `Expr::case_insensitive("text")`、UBNF `CI(...)`。

実装: [rust/unlaxer-runtime/src/lib.rs#L365](../rust/unlaxer-runtime/src/lib.rs#L365)、[unlaxer-common/src/main/java/org/unlaxer/parser/elementary/UnicodeIgnoreCaseWordParser.java#L15](../unlaxer-common/src/main/java/org/unlaxer/parser/elementary/UnicodeIgnoreCaseWordParser.java#L15)

テスト: [unlaxer-common/src/test/java/org/unlaxer/parser/elementary/UnicodeIgnoreCaseWordParserTest.java](../unlaxer-common/src/test/java/org/unlaxer/parser/elementary/UnicodeIgnoreCaseWordParserTest.java)、[rust/unlaxer-runtime/tests/case_insensitive.rs](../rust/unlaxer-runtime/tests/case_insensitive.rs)

共通 fixture: [spec-corpus/case-insensitive/runtime.tsv](../spec-corpus/case-insensitive/runtime.tsv)

### grapheme

**対応済み（記載した共通契約）**。Unicode17 拡張書記素境界で1clusterを認識し、原文CP span・rollbackを共通 oracle で検証する。

利用経路: `Expr::Grapheme` / UBNF `GRAPHEME`。

実装: [rust/unlaxer-runtime/src/grapheme.rs#L1](../rust/unlaxer-runtime/src/grapheme.rs#L1)、[unlaxer-common/src/main/java/org/unlaxer/parser/elementary/GraphemeParser.java#L12](../unlaxer-common/src/main/java/org/unlaxer/parser/elementary/GraphemeParser.java#L12)

テスト: [unlaxer-common/src/test/java/org/unlaxer/source/GraphemeTest.java](../unlaxer-common/src/test/java/org/unlaxer/source/GraphemeTest.java)、[rust/unlaxer-runtime/tests/grapheme.rs](../rust/unlaxer-runtime/tests/grapheme.rs)

共通 fixture: [spec-corpus/grapheme/parser.tsv](../spec-corpus/grapheme/parser.tsv)

### name-snapshots

**対応済み（記載した共通契約）**。明示 immutable name snapshot の predicate と必要snapshotの entry boundaryは既存共通corpusで位置・失敗・rollbackを比較する。任意Token predicateとは別。

利用経路: `Expr::NamePredicate` / `NameResolutionScope`、`ParseContext::with_name_snapshots`。

実装: [rust/unlaxer-runtime/src/lib.rs#L174](../rust/unlaxer-runtime/src/lib.rs#L174)、[rust/unlaxer-runtime/src/names.rs#L1](../rust/unlaxer-runtime/src/names.rs#L1)、[unlaxer-common/src/main/java/org/unlaxer/parser/combinator/NamePredicateParser.java#L9](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/NamePredicateParser.java#L9)

テスト: [unlaxer-dsl/src/test/java/org/unlaxer/dsl/codegen/NamePredicateConformanceTest.java](../unlaxer-dsl/src/test/java/org/unlaxer/dsl/codegen/NamePredicateConformanceTest.java)、[rust/unlaxer-runtime/tests/name_scope_token_mode_reset.rs](../rust/unlaxer-runtime/tests/name_scope_token_mode_reset.rs)

共通 fixture: [unlaxer-dsl/src/test/resources/name-predicate/corpus.json](../unlaxer-dsl/src/test/resources/name-predicate/corpus.json)

### recovery

**対応あり・全API共通検証なし**。SyncPointRecoveryParser と Rust Recovery は sync/auto/skip、CST marker・state rollbackに既存共通 corpus がある。RecoveryDiagnostic.from(Token/Context) の全公開抽出入口と任意手書きchildの設定網羅は残る。

利用経路: `expr.recover(mode, [";"], "message")`、`Tree.recoveries` / `ParseContext::recoveries`、UBNF `@recovery`。

実装: [rust/unlaxer-runtime/src/lib.rs#L519](../rust/unlaxer-runtime/src/lib.rs#L519)、[rust/unlaxer-runtime/src/lib.rs#L4672](../rust/unlaxer-runtime/src/lib.rs#L4672)、[unlaxer-common/src/main/java/org/unlaxer/parser/combinator/RecoveryDiagnostic.java#L11](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/RecoveryDiagnostic.java#L11)

テスト: [unlaxer-dsl/src/test/java/org/unlaxer/dsl/codegen/RecoveryConformanceTest.java](../unlaxer-dsl/src/test/java/org/unlaxer/dsl/codegen/RecoveryConformanceTest.java)、[rust/unlaxer-runtime/tests/recovery.rs](../rust/unlaxer-runtime/tests/recovery.rs)

共通 fixture: [spec-corpus/recovery/corpus.json](../spec-corpus/recovery/corpus.json)

追跡: [#854](https://github.com/opaopa6969/unlaxer-parser/issues/854)

### context-results

**対応あり・全API共通検証なし**。ParseContext/TransactionElement/Parsed/Token/TokenList/ParserCursor は Rust context/ParseResult/Fragment/Tree/Span として利用できる。既存の両cursor・所有state・portable metadataの共通 corpusはあるが、Java stopped/失敗結果、全static factory、抽出・走査・listener・raw Object metadataまで全API同値とは扱わない。 ParseContext が実装する Transaction/ParseContextBase/ParserContextScopeTree の default method、ParseContextEffector、TransactionMetrics、TypedToken/TokenEffecterWithMatcher も含む。chosen parser/ordered parsers の保存は selected-choice/selected-order 行で別に追跡する。

利用経路: `ParseContext::parse/parse_classic/transaction`、`position()/matched_position()`、`state/with_scope`、`Tree` と token metadata。任意外部副作用は登録state以外rollback対象にならない。

実装: [rust/unlaxer-runtime/src/lib.rs#L2428](../rust/unlaxer-runtime/src/lib.rs#L2428)、[rust/unlaxer-runtime/src/lib.rs#L2523](../rust/unlaxer-runtime/src/lib.rs#L2523)、[rust/unlaxer-runtime/src/lib.rs#L2650](../rust/unlaxer-runtime/src/lib.rs#L2650)、[unlaxer-common/src/main/java/org/unlaxer/context/ParseContext.java#L39](../unlaxer-common/src/main/java/org/unlaxer/context/ParseContext.java#L39)、[rust/unlaxer-runtime/src/token.rs#L1](../rust/unlaxer-runtime/src/token.rs#L1)、[unlaxer-common/src/main/java/org/unlaxer/context/Transaction.java#L31](../unlaxer-common/src/main/java/org/unlaxer/context/Transaction.java#L31)

テスト: [unlaxer-common/src/test/java/org/unlaxer/context/PortableTokenMetadataTest.java](../unlaxer-common/src/test/java/org/unlaxer/context/PortableTokenMetadataTest.java)、[rust/unlaxer-runtime/tests/token_metadata.rs](../rust/unlaxer-runtime/tests/token_metadata.rs)、[rust/unlaxer-runtime/tests/scopes.rs](../rust/unlaxer-runtime/tests/scopes.rs)

共通 fixture: [conformance/token-metadata/metadata.tsv](../conformance/token-metadata/metadata.tsv)、[conformance/token-metadata/words.tsv](../conformance/token-metadata/words.tsv)

追跡: [#854](https://github.com/opaopa6969/unlaxer-parser/issues/854)

### parse-bindings

**対応済み（記載した共通契約）**。withBindings は map/listを固定し、文字列の順序・重複・空を保持する共通契約。Rustは所有権、Javaはcopyで保持する。

利用経路: `ParseContext::with_bindings(source, map, options)`。

実装: [rust/unlaxer-runtime/src/lib.rs#L2248](../rust/unlaxer-runtime/src/lib.rs#L2248)、[unlaxer-common/src/main/java/org/unlaxer/context/ParseContext.java#L39](../unlaxer-common/src/main/java/org/unlaxer/context/ParseContext.java#L39)

テスト: [unlaxer-dsl/src/test/java/org/unlaxer/dsl/codegen/ParseBindingsConformanceTest.java](../unlaxer-dsl/src/test/java/org/unlaxer/dsl/codegen/ParseBindingsConformanceTest.java)

共通 fixture: [spec-corpus/parse-bindings/runtime.json](../spec-corpus/parse-bindings/runtime.json)

### error-container

**対応あり・全API共通検証なし**。ErrorMessageParser.expected は失敗hint、通常constructorは成功する空token Container（token source は入力上の幅0 view、#1006）。Rust Error は失敗であり通常constructorの代替ではない。Container.getRangedContents は Java classでtokenを探す。

利用経路: 失敗hintは `Expr::Error("expected")`。成功containerは `add_token_node` と portable metadata/owned値、typed抽出は明示 Rule ID に移す。

実装: [unlaxer-common/src/main/java/org/unlaxer/parser/ErrorMessageParser.java#L15](../unlaxer-common/src/main/java/org/unlaxer/parser/ErrorMessageParser.java#L15)、[unlaxer-common/src/main/java/org/unlaxer/parser/combinator/ContainerParser.java#L16](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/ContainerParser.java#L16)、[rust/unlaxer-runtime/src/lib.rs#L275](../rust/unlaxer-runtime/src/lib.rs#L275)、[rust/unlaxer-runtime/src/token.rs#L1](../rust/unlaxer-runtime/src/token.rs#L1)

テスト: 全API動作の共通根拠は未整備。

共通 fixture: 全API動作の共通根拠は未整備。

追跡: [#854](https://github.com/opaopa6969/unlaxer-parser/issues/854)

### first-predictors

**未対応（記載した動作）**。FirstSet/FirstSets は Java parser graph の保守的FIRST。Rust first::FirstSet/compute は Expr/Ruleを内部分析するが pub(crate) で、公開FIRST集合照会の同等経路はない。表現差だけでなくunknown/nullable/非ASCII・独自parserの除外を両言語の共通oracleで固定する必要がある。性能改善は行わない。

利用経路: `SharedGrammar` 構築時のFIRST分析、明示 `Predictor`。新たなmemo安全宣言は#184へ回す。

実装: [rust/unlaxer-runtime/src/first.rs#L1](../rust/unlaxer-runtime/src/first.rs#L1)、[unlaxer-common/src/main/java/org/unlaxer/parser/FirstSet.java#L19](../unlaxer-common/src/main/java/org/unlaxer/parser/FirstSet.java#L19)、[unlaxer-common/src/main/java/org/unlaxer/parser/FirstSets.java#L33](../unlaxer-common/src/main/java/org/unlaxer/parser/FirstSets.java#L33)

テスト: [unlaxer-common/src/test/java/org/unlaxer/parser/FirstSetExclusionTest.java](../unlaxer-common/src/test/java/org/unlaxer/parser/FirstSetExclusionTest.java)

共通 fixture: 全API動作の共通根拠は未整備。

追跡: [#853](https://github.com/opaopa6969/unlaxer-parser/issues/853)

### rule-diagnostics

**対応済み（記載した共通契約）**。GrammarRule の明示名（NamedChain/Choice・Supplier 経路）とクラス名既定（ItemParser→Item）の両方で、cases.tsv の失敗 RuleStack（DETAILED/AUTO、DETAILED_ON_FAILURE は記録なし）が Rust Rule.name の RuleFrame と一致する。nameOf の null/空白 override の class fallback・WordParser→Word と ParserPrinter の simple/detail/mostDetail は Java 固有の表示で construction.tsv の Java 列に固定し、Rust は常に明示の Rule.name と RuleStack を使う。

利用経路: `Rule { name: "Item", expression }` に名前を明示し、`ParseContext::rule_stack()` / `parse_detailed_shared_with_rule_stack` で frame を読む。

実装: [unlaxer-common/src/main/java/org/unlaxer/parser/GrammarRule.java#L26](../unlaxer-common/src/main/java/org/unlaxer/parser/GrammarRule.java#L26)、[unlaxer-common/src/main/java/org/unlaxer/parser/ParserPrinter.java#L7](../unlaxer-common/src/main/java/org/unlaxer/parser/ParserPrinter.java#L7)、[rust/unlaxer-runtime/src/rule_stack.rs#L198](../rust/unlaxer-runtime/src/rule_stack.rs#L198)

テスト: [unlaxer-common/src/test/java/org/unlaxer/parser/ParserConstructionParityTest.java](../unlaxer-common/src/test/java/org/unlaxer/parser/ParserConstructionParityTest.java)、[rust/unlaxer-runtime/tests/parser_construction.rs](../rust/unlaxer-runtime/tests/parser_construction.rs)、[rust/unlaxer-runtime/tests/rule_stack.rs](../rust/unlaxer-runtime/tests/rule_stack.rs)

共通 fixture: [conformance/parser-construction/cases.tsv](../conformance/parser-construction/cases.tsv)、[conformance/parser-construction/construction.tsv](../conformance/parser-construction/construction.tsv)、[conformance/rule-stack/cases.tsv](../conformance/rule-stack/cases.tsv)

追跡: [#845](https://github.com/opaopa6969/unlaxer-parser/issues/845)

### incremental

**対応あり・全API共通検証なし**。SnapshotParseCache/IncrementalParseCache は Rust incremental の snapshot/chunk cacheへ対応する。既存編集corpusはあるが、Java公開 nested result/stat型の全consumer・cache identity条件をAPI単位で網羅した共通表はまだない。部分CSTの再配置を対応済みとしない。

利用経路: `incremental::SnapshotParseCache` と chunk cache、UBNF生成consumer。全再解析との一致を既存cache契約に沿って追加する。

実装: [rust/unlaxer-runtime/src/incremental.rs#L1](../rust/unlaxer-runtime/src/incremental.rs#L1)、[unlaxer-common/src/main/java/org/unlaxer/parser/incremental/IncrementalParseCache.java#L21](../unlaxer-common/src/main/java/org/unlaxer/parser/incremental/IncrementalParseCache.java#L21)

テスト: [unlaxer-dsl/src/test/java/org/unlaxer/dsl/codegen/IncrementalCacheConformanceTest.java](../unlaxer-dsl/src/test/java/org/unlaxer/dsl/codegen/IncrementalCacheConformanceTest.java)、[rust/unlaxer-runtime/tests/incremental.rs](../rust/unlaxer-runtime/tests/incremental.rs)

共通 fixture: [spec-corpus/incremental-cache/edits.json](../spec-corpus/incremental-cache/edits.json)、[spec-corpus/incremental-cache/chunks.tsv](../spec-corpus/incremental-cache/chunks.tsv)

追跡: [#854](https://github.com/opaopa6969/unlaxer-parser/issues/854)

### token-memo

**言語固有（移植境界あり）**。Token.MemoSnapshot と prepareMemoSnapshot は Java object graphのcopy/予算境界。Rustのowned memo payloadは別表現。parser identityと任意host objectの安全宣言は既存#184で扱い重複issueを作らない。 公開 API 行の利用経路と既存 memo corpus への接続は #854 で追跡する。

利用経路: 既存 memo options と pure-success-memo 契約。利用者が任意stateful parserを成功cacheへ無条件登録する機能ではない。

実装: [unlaxer-common/src/main/java/org/unlaxer/Token.java#L23](../unlaxer-common/src/main/java/org/unlaxer/Token.java#L23)、[rust/unlaxer-runtime/src/lib.rs#L1](../rust/unlaxer-runtime/src/lib.rs#L1)

テスト: [rust/unlaxer-runtime/tests/pure_success_memo.rs](../rust/unlaxer-runtime/tests/pure_success_memo.rs)

共通 fixture: [conformance/pure-success-memo/generated-cases.json](../conformance/pure-success-memo/generated-cases.json)

追跡: [#854](https://github.com/opaopa6969/unlaxer-parser/issues/854)、[#184](https://github.com/opaopa6969/unlaxer-parser/issues/184)

### start-of-source

**対応済み（記載した共通契約）**。tokenKindの参照cursorがCP0かをinvertとXORして認識する。独立した line-boundary corpus で4 token kind（virtual consumedはmatchedを参照）・invert・両cursor・CST・失敗CP・二重rollbackと同一instance再試行をmemo2×診断3モードで比較する。新しいruntime APIは追加せず、Classicはtokenを作らないadapterを使う。関連static factoryの4経路とcache/独立instanceも実行する。

利用経路: 既存UBNF `token Boundary ::= BOF;` / `Expr::Lexical(.., LexicalExpression { op: Op::BOF, .. })`。Classicは `conformance/line-boundary/probe.rs` の `Expr::Custom` adapterがtoken_modeとposition/matched_positionを読み、同じLexicalExpression境界を検査する。語彙tokenとは零幅tokenの生成とinvertの契約を分ける。

実装: [unlaxer-common/src/main/java/org/unlaxer/parser/elementary/StartOfSourceParser.java#L13](../unlaxer-common/src/main/java/org/unlaxer/parser/elementary/StartOfSourceParser.java#L13)、[rust/unlaxer-runtime/src/lexical.rs#L63](../rust/unlaxer-runtime/src/lexical.rs#L63)、[rust/unlaxer-runtime/src/lib.rs#L104](../rust/unlaxer-runtime/src/lib.rs#L104)、[conformance/line-boundary/probe.rs#L20](../conformance/line-boundary/probe.rs#L20)

テスト: [unlaxer-dsl/src/test/java/org/unlaxer/dsl/codegen/DeclarativeTokenConformanceTest.java](../unlaxer-dsl/src/test/java/org/unlaxer/dsl/codegen/DeclarativeTokenConformanceTest.java)、[unlaxer-dsl/src/test/java/org/unlaxer/dsl/codegen/ContextualLexingConformanceTest.java](../unlaxer-dsl/src/test/java/org/unlaxer/dsl/codegen/ContextualLexingConformanceTest.java)、[unlaxer-dsl/src/test/java/org/unlaxer/dsl/codegen/LineBoundaryConformanceTest.java](../unlaxer-dsl/src/test/java/org/unlaxer/dsl/codegen/LineBoundaryConformanceTest.java)、[rust/unlaxer-runtime/tests/line_boundary.rs](../rust/unlaxer-runtime/tests/line_boundary.rs)

共通 fixture: [spec-corpus/declarative-tokens/tokens.ubnf](../spec-corpus/declarative-tokens/tokens.ubnf)、[spec-corpus/declarative-tokens/runtime.json](../spec-corpus/declarative-tokens/runtime.json)、[conformance/line-boundary/cases.tsv](../conformance/line-boundary/cases.tsv)、[conformance/line-boundary/transactions.tsv](../conformance/line-boundary/transactions.tsv)、[conformance/line-boundary/lexical.tsv](../conformance/line-boundary/lexical.tsv)、[conformance/line-boundary/README.md](../conformance/line-boundary/README.md)

追跡: [#848](https://github.com/opaopa6969/unlaxer-parser/issues/848)

### portable-metadata

**対応済み（記載した共通契約）**。PortableTokenMetadata の register/registerGenerated、extra/related の追加・削除、snapshot と transaction rollback は Rust token metadata（NodeId/TokenInfo、put_token_extra/put_related_token/add_token_node）と共通 metadata.tsv/words.tsv で比較する。任意 Java Object を持つ Token の extraObject は context-results の別行。

利用経路: `ParseContext::token_node_id/token_info/put_token_extra/remove_token_extra/put_related_token/add_token_node`。

実装: [unlaxer-common/src/main/java/org/unlaxer/context/PortableTokenMetadata.java#L15](../unlaxer-common/src/main/java/org/unlaxer/context/PortableTokenMetadata.java#L15)、[rust/unlaxer-runtime/src/token.rs#L51](../rust/unlaxer-runtime/src/token.rs#L51)、[rust/unlaxer-runtime/src/token.rs#L322](../rust/unlaxer-runtime/src/token.rs#L322)

テスト: [unlaxer-common/src/test/java/org/unlaxer/context/PortableTokenMetadataTest.java](../unlaxer-common/src/test/java/org/unlaxer/context/PortableTokenMetadataTest.java)、[rust/unlaxer-runtime/tests/token_metadata.rs](../rust/unlaxer-runtime/tests/token_metadata.rs)

共通 fixture: [conformance/token-metadata/metadata.tsv](../conformance/token-metadata/metadata.tsv)、[conformance/token-metadata/words.tsv](../conformance/token-metadata/words.tsv)

### name-snapshot-api

**対応あり・全API共通検証なし**。NameSnapshot/Requirement と NameResolution の begin/end/matches/reject/failure は、withBindings 経由の解決・未解決・snapshot 欠落を memo/diagnostics 設定ごとに共通 name-snapshots corpus で両 host が比較する。validateId/Version/Name・bindingPrefix・bindingsOf の形式検証・上限・重複と fromContext の直接呼出しは Java 単体テストだけで、Rust names.rs との共通 oracle はない。

利用経路: `ParseContext::with_name_snapshots` と `names::Snapshot`、`Expr::NamePredicate`/`NameResolutionScope`。生成 parser から呼ばれる static は Rust runtime 内部の解決処理に対応する。

実装: [unlaxer-common/src/main/java/org/unlaxer/context/NameSnapshot.java#L6](../unlaxer-common/src/main/java/org/unlaxer/context/NameSnapshot.java#L6)、[unlaxer-common/src/main/java/org/unlaxer/context/NameResolution.java#L7](../unlaxer-common/src/main/java/org/unlaxer/context/NameResolution.java#L7)、[rust/unlaxer-runtime/src/names.rs#L27](../rust/unlaxer-runtime/src/names.rs#L27)、[rust/unlaxer-runtime/src/names.rs#L157](../rust/unlaxer-runtime/src/names.rs#L157)

テスト: [unlaxer-dsl/src/test/java/org/unlaxer/dsl/codegen/NameSnapshotConformanceTest.java](../unlaxer-dsl/src/test/java/org/unlaxer/dsl/codegen/NameSnapshotConformanceTest.java)、[unlaxer-common/src/test/java/org/unlaxer/parser/combinator/NameSnapshotTest.java](../unlaxer-common/src/test/java/org/unlaxer/parser/combinator/NameSnapshotTest.java)

共通 fixture: [spec-corpus/name-snapshots/runtime.json](../spec-corpus/name-snapshots/runtime.json)

追跡: [#854](https://github.com/opaopa6969/unlaxer-parser/issues/854)

### parse-options-diagnostics

**対応あり・全API共通検証なし**。ParseOptions の Diagnostics 設定、DETAILED の最遠失敗・期待候補・rule stack、COMMIT/LABEL の RaisedFailure、深さ上限の ParseLimitException は、Rust の ParseOptions/Diagnostics・ParseError・RaisedFailure・RuleStack と既存共通 corpus（rule-stack、prior-matched-diagnostics、resource-abort）で部分的に比較される。ParseFailureDiagnostics の TrialRecord/ParseStackElement/ExpectedHintCandidate の全項目、DiagnosticFormatter の文字列、DiagnosticsSafety/DiagnosticsAgnostic の再実行可否判定は全API共通検証がない。

利用経路: `ParseOptions { diagnostics: Diagnostics::Detailed, .. }`、`ParseResult` の失敗 `ParseError`、`RaisedFailure`、`rule_stack::RuleStack`。Java の型 graph を辿る DiagnosticsSafety は custom parser の明示宣言へ移す。

実装: [unlaxer-common/src/main/java/org/unlaxer/context/ParseOptions.java#L6](../unlaxer-common/src/main/java/org/unlaxer/context/ParseOptions.java#L6)、[unlaxer-common/src/main/java/org/unlaxer/context/ParseFailureDiagnostics.java#L9](../unlaxer-common/src/main/java/org/unlaxer/context/ParseFailureDiagnostics.java#L9)、[unlaxer-common/src/main/java/org/unlaxer/context/RuleStack.java#L21](../unlaxer-common/src/main/java/org/unlaxer/context/RuleStack.java#L21)、[unlaxer-common/src/main/java/org/unlaxer/context/RaisedFailure.java#L19](../unlaxer-common/src/main/java/org/unlaxer/context/RaisedFailure.java#L19)、[rust/unlaxer-runtime/src/lib.rs#L624](../rust/unlaxer-runtime/src/lib.rs#L624)、[rust/unlaxer-runtime/src/lib.rs#L642](../rust/unlaxer-runtime/src/lib.rs#L642)、[rust/unlaxer-runtime/src/lib.rs#L762](../rust/unlaxer-runtime/src/lib.rs#L762)、[rust/unlaxer-runtime/src/lib.rs#L790](../rust/unlaxer-runtime/src/lib.rs#L790)、[rust/unlaxer-runtime/src/rule_stack.rs#L29](../rust/unlaxer-runtime/src/rule_stack.rs#L29)

テスト: [unlaxer-common/src/test/java/org/unlaxer/context/RuleStackTest.java](../unlaxer-common/src/test/java/org/unlaxer/context/RuleStackTest.java)、[rust/unlaxer-runtime/tests/rule_stack.rs](../rust/unlaxer-runtime/tests/rule_stack.rs)、[unlaxer-common/src/test/java/org/unlaxer/context/PriorMatchedDiagnosticsTest.java](../unlaxer-common/src/test/java/org/unlaxer/context/PriorMatchedDiagnosticsTest.java)、[rust/unlaxer-runtime/tests/prior_matched_diagnostics.rs](../rust/unlaxer-runtime/tests/prior_matched_diagnostics.rs)、[rust/unlaxer-runtime/tests/diagnostics_policy.rs](../rust/unlaxer-runtime/tests/diagnostics_policy.rs)、[unlaxer-common/src/test/java/org/unlaxer/context/ResourcePublicationTest.java](../unlaxer-common/src/test/java/org/unlaxer/context/ResourcePublicationTest.java)、[rust/unlaxer-runtime/tests/resource_publication.rs](../rust/unlaxer-runtime/tests/resource_publication.rs)

共通 fixture: [conformance/rule-stack/cases.tsv](../conformance/rule-stack/cases.tsv)、[conformance/prior-matched-diagnostics/cases.tsv](../conformance/prior-matched-diagnostics/cases.tsv)、[conformance/resource-abort/cases.tsv](../conformance/resource-abort/cases.tsv)

追跡: [#856](https://github.com/opaopa6969/unlaxer-parser/issues/856)

### memo-policy

**対応あり・全API共通検証なし**。Memoization の設定、SafeFailureMemoizable/SafeSuccessMemoizable marker、PackratMemoTable の lookup/replay/commit は、Rust の Memoization と memo 実装に既存共通 corpus（memo-reset-policy、classic-memo、pure-success-memo）で設定別の受理・cursor・状態を比較する。PackratMemoTable の公開 static を直接呼ぶ利用と、exact class 単位の marker の移植は全API共通検証がない。memo 安全宣言と context-aware memo 方針は #184 が正本で、#854 はこの API 行の利用経路と既存 corpus への接続だけを扱う。

利用経路: `ParseOptions { memoization: Memoization::.., .. }`。Rust は生成 rule の安全性を generator/runtime が判定し、利用者が任意 custom parser を成功 cache へ登録する公開 API はない。

実装: [unlaxer-common/src/main/java/org/unlaxer/context/Memoization.java#L4](../unlaxer-common/src/main/java/org/unlaxer/context/Memoization.java#L4)、[unlaxer-common/src/main/java/org/unlaxer/context/PackratMemoTable.java#L37](../unlaxer-common/src/main/java/org/unlaxer/context/PackratMemoTable.java#L37)、[rust/unlaxer-runtime/src/lib.rs#L612](../rust/unlaxer-runtime/src/lib.rs#L612)

テスト: [unlaxer-common/src/test/java/org/unlaxer/context/MemoResetPolicyTest.java](../unlaxer-common/src/test/java/org/unlaxer/context/MemoResetPolicyTest.java)、[rust/unlaxer-runtime/tests/memo_reset_policy.rs](../rust/unlaxer-runtime/tests/memo_reset_policy.rs)、[unlaxer-common/src/test/java/org/unlaxer/context/ClassicMemoizationConformanceTest.java](../unlaxer-common/src/test/java/org/unlaxer/context/ClassicMemoizationConformanceTest.java)、[rust/unlaxer-runtime/tests/classic_memo.rs](../rust/unlaxer-runtime/tests/classic_memo.rs)

共通 fixture: [conformance/memo-reset-policy/cases.tsv](../conformance/memo-reset-policy/cases.tsv)、[conformance/classic-memo/cases.tsv](../conformance/classic-memo/cases.tsv)

追跡: [#854](https://github.com/opaopa6969/unlaxer-parser/issues/854)、[#184](https://github.com/opaopa6969/unlaxer-parser/issues/184)

### debug-observers

**言語固有（移植境界あり）**。ParserListener/TransactionListener と listener container、breakpoint holder、debug specifier、ParserContextPrinter は JVM の parser instance とログ出力に結び付くホストの観測機構。Rust は上限付きの実 token trace と DAP の breakpoint/変数 hook を別契約で持つ。observer 例外の伝播と状態復元は observer-failures corpus で両 host を比較するが、listener の呼出し順・出力文字列・breakpoint の全API同値は検証しない。

利用経路: `token_trace` による trace 取得、生成 host の `--dap`。任意 listener は ParseContext の外の明示 callback/adapter へ移す。

実装: [unlaxer-common/src/main/java/org/unlaxer/listener/ParserListener.java#L8](../unlaxer-common/src/main/java/org/unlaxer/listener/ParserListener.java#L8)、[unlaxer-common/src/main/java/org/unlaxer/context/TransactionListenerContainer.java#L12](../unlaxer-common/src/main/java/org/unlaxer/context/TransactionListenerContainer.java#L12)、[rust/unlaxer-runtime/src/token_trace.rs#L9](../rust/unlaxer-runtime/src/token_trace.rs#L9)

テスト: [unlaxer-common/src/test/java/org/unlaxer/context/ObserverFailureTest.java](../unlaxer-common/src/test/java/org/unlaxer/context/ObserverFailureTest.java)、[rust/unlaxer-runtime/tests/observer_failures.rs](../rust/unlaxer-runtime/tests/observer_failures.rs)、[unlaxer-dsl/src/test/java/org/unlaxer/dsl/codegen/TokenTraceConformanceTest.java](../unlaxer-dsl/src/test/java/org/unlaxer/dsl/codegen/TokenTraceConformanceTest.java)、[rust/unlaxer-runtime/tests/token_trace.rs](../rust/unlaxer-runtime/tests/token_trace.rs)

共通 fixture: [conformance/observer-failures/cases.tsv](../conformance/observer-failures/cases.tsv)、[conformance/token-trace/leaves.tsv](../conformance/token-trace/leaves.tsv)

追跡: [#854](https://github.com/opaopa6969/unlaxer-parser/issues/854)

### line-terminator

**対応済み（記載した共通契約）**。LineTerminatorParser / EndOfLineParserのCRLF→CR→LF→EOFの順序、Classicの4kind/invert、両cursor/reset、原CST/reduced CST、失敗CP、isEndOfSourceとstatic構築経路を独立corpusで比較する。memo OFF/SAFE_FAILURESと診断3モード、choice敗者・先読み・外側失敗後の再試行も比較。候補begin前のresetを反映しないJava FIRST除外は#861で修正し、旧Java専用例外30行を除いて全診断modeを主の共通oracleへ照合する。Rustは既存の正しいClassic経路を維持する。

利用経路: `Expr::choice([Literal("\r\n"), Literal("\r"), Literal("\n"), Eof])`。`probe.rs::line_end` をそのまま使い、診断モードで分岐するadapterは置かない。既存の語彙EOLは非消費の別契約としてlexical.tsvで比較する。

実装: [unlaxer-common/src/main/java/org/unlaxer/parser/elementary/LineTerminatorParser.java#L12](../unlaxer-common/src/main/java/org/unlaxer/parser/elementary/LineTerminatorParser.java#L12)、[unlaxer-common/src/main/java/org/unlaxer/parser/elementary/EndOfLineParser.java#L3](../unlaxer-common/src/main/java/org/unlaxer/parser/elementary/EndOfLineParser.java#L3)、[rust/unlaxer-runtime/src/lib.rs#L378](../rust/unlaxer-runtime/src/lib.rs#L378)、[conformance/line-boundary/probe.rs#L58](../conformance/line-boundary/probe.rs#L58)、[conformance/line-boundary/probe.rs#L66](../conformance/line-boundary/probe.rs#L66)、[unlaxer-common/src/main/java/org/unlaxer/parser/combinator/ChoiceInterface.java#L39](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/ChoiceInterface.java#L39)

テスト: [unlaxer-dsl/src/test/java/org/unlaxer/dsl/codegen/LineBoundaryConformanceTest.java](../unlaxer-dsl/src/test/java/org/unlaxer/dsl/codegen/LineBoundaryConformanceTest.java)、[rust/unlaxer-runtime/tests/line_boundary.rs](../rust/unlaxer-runtime/tests/line_boundary.rs)

共通 fixture: [conformance/line-boundary/cases.tsv](../conformance/line-boundary/cases.tsv)、[conformance/line-boundary/transactions.tsv](../conformance/line-boundary/transactions.tsv)、[conformance/line-boundary/lexical.tsv](../conformance/line-boundary/lexical.tsv)、[conformance/line-boundary/README.md](../conformance/line-boundary/README.md)

追跡: [#848](https://github.com/opaopa6969/unlaxer-parser/issues/848)、[#861](https://github.com/opaopa6969/unlaxer-parser/issues/861)

### semantic-predicates

**対応済み（記載した共通契約）**。#1006 の UBNF `@predicate` 用 runtime。Java の install（registry 所有コピー・64件・名前形式）/ Scope（全 requirement の entry 前検証・memo/FIRST 停止）/ Parser（CST 透過・false の rollback・sticky な predicate_missing/error/source/scope_missing）/ Candidate（原文 text と code point span）/ failure は、生成 Java・Java frontend の Rust・native Rust を別々に実行する共通 corpus で候補集合・prefix cursor・AST・診断を各診断 mode と memo OFF/ON で比較する。begin/end/test は生成 wrapper と Scope が呼ぶ内部手順で、直接呼出しの均衡契約は Java 単体だけ。Rust の低水準 context に registry を install しない場合は sticky 失敗を残さない（Java は predicate_missing）差を文書化した。任意 CST predicate（#851）は対象外。

利用経路: UBNF `@predicate('name')` と生成 `parse_tree_detailed_with_semantic_predicates`、runtime の `semantic_predicates::Bindings` / `ParseContext::with_semantic_predicates` / `Expr::SemanticPredicate` / `Expr::SemanticPredicateScope` / `parse_detailed_shared_with_semantic_predicates`。

実装: [unlaxer-common/src/main/java/org/unlaxer/context/SemanticPredicateBindings.java#L14](../unlaxer-common/src/main/java/org/unlaxer/context/SemanticPredicateBindings.java#L14)、[unlaxer-common/src/main/java/org/unlaxer/parser/combinator/SemanticPredicateParser.java#L19](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/SemanticPredicateParser.java#L19)、[unlaxer-common/src/main/java/org/unlaxer/parser/combinator/SemanticPredicateScope.java#L16](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/SemanticPredicateScope.java#L16)、[rust/unlaxer-runtime/src/semantic_predicates.rs#L46](../rust/unlaxer-runtime/src/semantic_predicates.rs#L46)、[rust/unlaxer-runtime/src/lib.rs#L188](../rust/unlaxer-runtime/src/lib.rs#L188)、[rust/unlaxer-runtime/src/lib.rs#L2025](../rust/unlaxer-runtime/src/lib.rs#L2025)

テスト: [unlaxer-dsl/src/test/java/org/unlaxer/dsl/codegen/SemanticPredicateConformanceTest.java](../unlaxer-dsl/src/test/java/org/unlaxer/dsl/codegen/SemanticPredicateConformanceTest.java)、[unlaxer-common/src/test/java/org/unlaxer/parser/combinator/SemanticPredicateParserTest.java](../unlaxer-common/src/test/java/org/unlaxer/parser/combinator/SemanticPredicateParserTest.java)、[rust/unlaxer-runtime/src/semantic_predicates.rs](../rust/unlaxer-runtime/src/semantic_predicates.rs)、[rust/unlaxer-generator/tests/semantic_predicate.rs](../rust/unlaxer-generator/tests/semantic_predicate.rs)、[rust/unlaxer-runtime/tests/semantic_predicate_source.rs](../rust/unlaxer-runtime/tests/semantic_predicate_source.rs)

共通 fixture: [unlaxer-dsl/src/test/resources/semantic-predicate/corpus.json](../unlaxer-dsl/src/test/resources/semantic-predicate/corpus.json)、[unlaxer-dsl/src/test/resources/semantic-predicate/invalid.json](../unlaxer-dsl/src/test/resources/semantic-predicate/invalid.json)

## 全公開 API（機械列挙との一致を検査）

各行は上の判定へ対応する。static は factory 以外の公開 utility も保守的に含める。

| Java API / 宣言位置 | 種別 | 状態 | Rust 経路・根拠 |
|---|---|---|---|
| [`CapturedValue`](../unlaxer-common/src/main/java/org/unlaxer/CapturedValue.java#L6) | type | 対応済み（記載した共通契約） | [owned-capture](#owned-capture) |
| [`CapturedValue#from(Source,Source,int)`](../unlaxer-common/src/main/java/org/unlaxer/CapturedValue.java#L32) | static | 対応済み（記載した共通契約） | [owned-capture](#owned-capture) |
| [`CapturedValue#provenanceOf(Source,Source)`](../unlaxer-common/src/main/java/org/unlaxer/CapturedValue.java#L20) | static | 対応済み（記載した共通契約） | [owned-capture](#owned-capture) |
| [`CapturedValue.Provenance`](../unlaxer-common/src/main/java/org/unlaxer/CapturedValue.java#L7) | type | 対応済み（記載した共通契約） | [owned-capture](#owned-capture) |
| [`Committed`](../unlaxer-common/src/main/java/org/unlaxer/Committed.java#L15) | type | 言語固有（移植境界あり） | [meta-function](#meta-function) |
| [`Name`](../unlaxer-common/src/main/java/org/unlaxer/Name.java#L5) | type | 対応済み（記載した共通契約） | [construction](#construction) |
| [`Name#classBaseOf(Object)`](../unlaxer-common/src/main/java/org/unlaxer/Name.java#L49) | static | 対応済み（記載した共通契約） | [construction](#construction) |
| [`Name#of(Class<?>)`](../unlaxer-common/src/main/java/org/unlaxer/Name.java#L37) | static | 対応済み（記載した共通契約） | [construction](#construction) |
| [`Name#of(Class<?>,Specifier<?>)`](../unlaxer-common/src/main/java/org/unlaxer/Name.java#L41) | static | 対応済み（記載した共通契約） | [construction](#construction) |
| [`Name#of(Class<?>,String)`](../unlaxer-common/src/main/java/org/unlaxer/Name.java#L45) | static | 対応済み（記載した共通契約） | [construction](#construction) |
| [`Name#of(Enum<?>)`](../unlaxer-common/src/main/java/org/unlaxer/Name.java#L33) | static | 対応済み（記載した共通契約） | [construction](#construction) |
| [`Name#of(String)`](../unlaxer-common/src/main/java/org/unlaxer/Name.java#L29) | static | 対応済み（記載した共通契約） | [construction](#construction) |
| [`Parsed`](../unlaxer-common/src/main/java/org/unlaxer/Parsed.java#L3) | type | 対応あり・全API共通検証なし | [context-results](#context-results) |
| [`Parsed.Status`](../unlaxer-common/src/main/java/org/unlaxer/Parsed.java#L7) | type | 対応あり・全API共通検証なし | [context-results](#context-results) |
| [`ParserCursor`](../unlaxer-common/src/main/java/org/unlaxer/ParserCursor.java#L6) | type | 対応あり・全API共通検証なし | [context-results](#context-results) |
| [`ParserFinder`](../unlaxer-common/src/main/java/org/unlaxer/ParserFinder.java#L3) | type | 対応済み（記載した共通契約） | [construction](#construction) |
| [`ParserFinderFromRoot`](../unlaxer-common/src/main/java/org/unlaxer/ParserFinderFromRoot.java#L10) | type | 対応済み（記載した共通契約） | [construction](#construction) |
| [`ParserFinderToChild`](../unlaxer-common/src/main/java/org/unlaxer/ParserFinderToChild.java#L10) | type | 対応済み（記載した共通契約） | [construction](#construction) |
| [`ParserFinderToRoot`](../unlaxer-common/src/main/java/org/unlaxer/ParserFinderToRoot.java#L10) | type | 対応済み（記載した共通契約） | [construction](#construction) |
| [`ParserHierarchy`](../unlaxer-common/src/main/java/org/unlaxer/ParserHierarchy.java#L14) | type | 対応済み（記載した共通契約） | [construction](#construction) |
| [`ParserHierarchy.NameKind`](../unlaxer-common/src/main/java/org/unlaxer/ParserHierarchy.java#L29) | type | 対応済み（記載した共通契約） | [construction](#construction) |
| [`ParserNameContext`](../unlaxer-common/src/main/java/org/unlaxer/ParserNameContext.java#L9) | type | 対応済み（記載した共通契約） | [construction](#construction) |
| [`ParserPath`](../unlaxer-common/src/main/java/org/unlaxer/ParserPath.java#L11) | type | 対応済み（記載した共通契約） | [construction](#construction) |
| [`ParserTaggable`](../unlaxer-common/src/main/java/org/unlaxer/ParserTaggable.java#L8) | type | 未対応（記載した動作） | [tag-wrapper](#tag-wrapper) |
| [`PropagatedTag`](../unlaxer-common/src/main/java/org/unlaxer/PropagatedTag.java#L6) | type | 未対応（記載した動作） | [tag-wrapper](#tag-wrapper) |
| [`RecursiveTaggable`](../unlaxer-common/src/main/java/org/unlaxer/RecursiveTaggable.java#L6) | type | 未対応（記載した動作） | [tag-wrapper](#tag-wrapper) |
| [`RecursiveTaggable.PropagatedTaggable`](../unlaxer-common/src/main/java/org/unlaxer/RecursiveTaggable.java#L52) | type | 未対応（記載した動作） | [tag-wrapper](#tag-wrapper) |
| [`Tag`](../unlaxer-common/src/main/java/org/unlaxer/Tag.java#L5) | type | 未対応（記載した動作） | [tag-wrapper](#tag-wrapper) |
| [`Tag#classBaseOf(Object)`](../unlaxer-common/src/main/java/org/unlaxer/Tag.java#L49) | static | 未対応（記載した動作） | [tag-wrapper](#tag-wrapper) |
| [`Tag#of(Class<?>)`](../unlaxer-common/src/main/java/org/unlaxer/Tag.java#L37) | static | 未対応（記載した動作） | [tag-wrapper](#tag-wrapper) |
| [`Tag#of(Class<?>,Specifier<?>)`](../unlaxer-common/src/main/java/org/unlaxer/Tag.java#L41) | static | 未対応（記載した動作） | [tag-wrapper](#tag-wrapper) |
| [`Tag#of(Class<?>,String)`](../unlaxer-common/src/main/java/org/unlaxer/Tag.java#L45) | static | 未対応（記載した動作） | [tag-wrapper](#tag-wrapper) |
| [`Tag#of(Enum<?>)`](../unlaxer-common/src/main/java/org/unlaxer/Tag.java#L33) | static | 未対応（記載した動作） | [tag-wrapper](#tag-wrapper) |
| [`Tag#of(String)`](../unlaxer-common/src/main/java/org/unlaxer/Tag.java#L29) | static | 未対応（記載した動作） | [tag-wrapper](#tag-wrapper) |
| [`TaggableAccessor`](../unlaxer-common/src/main/java/org/unlaxer/TaggableAccessor.java#L3) | type | 未対応（記載した動作） | [tag-wrapper](#tag-wrapper) |
| [`Token`](../unlaxer-common/src/main/java/org/unlaxer/Token.java#L23) | type | 対応あり・全API共通検証なし | [context-results](#context-results) |
| [`Token#empty(TokenKind,EndExclusiveCursor,Parser)`](../unlaxer-common/src/main/java/org/unlaxer/Token.java#L441) | static | 対応あり・全API共通検証なし | [context-results](#context-results) |
| [`Token#prepareMemoSnapshot(TokenList,List<Token>,long)`](../unlaxer-common/src/main/java/org/unlaxer/Token.java#L159) | static | 言語固有（移植境界あり） | [token-memo](#token-memo) |
| [`Token#prepareMemoSnapshot(TokenList,long)`](../unlaxer-common/src/main/java/org/unlaxer/Token.java#L148) | static | 言語固有（移植境界あり） | [token-memo](#token-memo) |
| [`Token.AbstractDescendantFinder`](../unlaxer-common/src/main/java/org/unlaxer/Token.java#L721) | type | 対応あり・全API共通検証なし | [context-results](#context-results) |
| [`Token.ChildrenKind`](../unlaxer-common/src/main/java/org/unlaxer/Token.java#L56) | type | 対応あり・全API共通検証なし | [context-results](#context-results) |
| [`Token.MemoSnapshot`](../unlaxer-common/src/main/java/org/unlaxer/Token.java#L335) | type | 言語固有（移植境界あり） | [token-memo](#token-memo) |
| [`Token.MemoSnapshot.Replay`](../unlaxer-common/src/main/java/org/unlaxer/Token.java#L374) | type | 言語固有（移植境界あり） | [token-memo](#token-memo) |
| [`Token.ScanDirection`](../unlaxer-common/src/main/java/org/unlaxer/Token.java#L562) | type | 対応あり・全API共通検証なし | [context-results](#context-results) |
| [`TokenEffecterWithMatcher`](../unlaxer-common/src/main/java/org/unlaxer/TokenEffecterWithMatcher.java#L6) | type | 対応あり・全API共通検証なし | [context-results](#context-results) |
| [`TokenKind`](../unlaxer-common/src/main/java/org/unlaxer/TokenKind.java#L7) | type | 対応あり・全API共通検証なし | [context-results](#context-results) |
| [`TokenKind#of(boolean)`](../unlaxer-common/src/main/java/org/unlaxer/TokenKind.java#L14) | static | 対応あり・全API共通検証なし | [context-results](#context-results) |
| [`TokenList`](../unlaxer-common/src/main/java/org/unlaxer/TokenList.java#L19) | type | 対応あり・全API共通検証なし | [context-results](#context-results) |
| [`TokenList#combinedCursorRange(TokenList,PositionResolver)`](../unlaxer-common/src/main/java/org/unlaxer/TokenList.java#L259) | static | 対応あり・全API共通検証なし | [context-results](#context-results) |
| [`TokenList#of(List<Token>)`](../unlaxer-common/src/main/java/org/unlaxer/TokenList.java#L78) | static | 対応あり・全API共通検証なし | [context-results](#context-results) |
| [`TokenList#of(Token[])`](../unlaxer-common/src/main/java/org/unlaxer/TokenList.java#L82) | static | 対応あり・全API共通検証なし | [context-results](#context-results) |
| [`TokenList#toSource(TokenList,SourceKind)`](../unlaxer-common/src/main/java/org/unlaxer/TokenList.java#L305) | static | 対応あり・全API共通検証なし | [context-results](#context-results) |
| [`TokenPredicators`](../unlaxer-common/src/main/java/org/unlaxer/TokenPredicators.java#L7) | type | 未対応（記載した動作） | [parsed-predicate](#parsed-predicate) |
| [`TokenPredicators#afterToken(Token)`](../unlaxer-common/src/main/java/org/unlaxer/TokenPredicators.java#L54) | static | 未対応（記載した動作） | [parsed-predicate](#parsed-predicate) |
| [`TokenPredicators#allMatch()`](../unlaxer-common/src/main/java/org/unlaxer/TokenPredicators.java#L50) | static | 未対応（記載した動作） | [parsed-predicate](#parsed-predicate) |
| [`TokenPredicators#beforeToken(Token)`](../unlaxer-common/src/main/java/org/unlaxer/TokenPredicators.java#L59) | static | 未対応（記載した動作） | [parsed-predicate](#parsed-predicate) |
| [`TokenPredicators#hasTag(Tag)`](../unlaxer-common/src/main/java/org/unlaxer/TokenPredicators.java#L69) | static | 未対応（記載した動作） | [parsed-predicate](#parsed-predicate) |
| [`TokenPredicators#hasTagInParent(Tag)`](../unlaxer-common/src/main/java/org/unlaxer/TokenPredicators.java#L73) | static | 未対応（記載した動作） | [parsed-predicate](#parsed-predicate) |
| [`TokenPredicators#noMatch()`](../unlaxer-common/src/main/java/org/unlaxer/TokenPredicators.java#L46) | static | 未対応（記載した動作） | [parsed-predicate](#parsed-predicate) |
| [`TokenPredicators#parentPathEndsWith(String)`](../unlaxer-common/src/main/java/org/unlaxer/TokenPredicators.java#L82) | static | 未対応（記載した動作） | [parsed-predicate](#parsed-predicate) |
| [`TokenPredicators#parserImplements(Class<?>[])`](../unlaxer-common/src/main/java/org/unlaxer/TokenPredicators.java#L9) | static | 未対応（記載した動作） | [parsed-predicate](#parsed-predicate) |
| [`TokenPredicators#parsers(Class<? extends Parser>[])`](../unlaxer-common/src/main/java/org/unlaxer/TokenPredicators.java#L21) | static | 未対応（記載した動作） | [parsed-predicate](#parsed-predicate) |
| [`TokenPredicators#parsersMatchWithClass(Parser[])`](../unlaxer-common/src/main/java/org/unlaxer/TokenPredicators.java#L34) | static | 未対応（記載した動作） | [parsed-predicate](#parsed-predicate) |
| [`TokenPredicators#pathEndsWith(String)`](../unlaxer-common/src/main/java/org/unlaxer/TokenPredicators.java#L78) | static | 未対応（記載した動作） | [parsed-predicate](#parsed-predicate) |
| [`TokenPredicators#relation(Token,RangesRelation)`](../unlaxer-common/src/main/java/org/unlaxer/TokenPredicators.java#L64) | static | 未対応（記載した動作） | [parsed-predicate](#parsed-predicate) |
| [`TransactionElement`](../unlaxer-common/src/main/java/org/unlaxer/TransactionElement.java#L18) | type | 対応あり・全API共通検証なし | [context-results](#context-results) |
| [`TypedToken`](../unlaxer-common/src/main/java/org/unlaxer/TypedToken.java#L10) | type | 対応あり・全API共通検証なし | [context-results](#context-results) |
| [`capture.CaptureSnapshot`](../unlaxer-common/src/main/java/org/unlaxer/capture/CaptureSnapshot.java#L10) | type | 対応済み（記載した共通契約） | [capture-boundaries](#capture-boundaries) |
| [`capture.CaptureSnapshot#from(Token)`](../unlaxer-common/src/main/java/org/unlaxer/capture/CaptureSnapshot.java#L31) | static | 対応済み（記載した共通契約） | [capture-boundaries](#capture-boundaries) |
| [`capture.CaptureSnapshot.Element`](../unlaxer-common/src/main/java/org/unlaxer/capture/CaptureSnapshot.java#L12) | type | 対応済み（記載した共通契約） | [capture-boundaries](#capture-boundaries) |
| [`capture.CaptureSnapshot.Error`](../unlaxer-common/src/main/java/org/unlaxer/capture/CaptureSnapshot.java#L22) | type | 対応済み（記載した共通契約） | [capture-boundaries](#capture-boundaries) |
| [`capture.CaptureSnapshot.SnapshotException`](../unlaxer-common/src/main/java/org/unlaxer/capture/CaptureSnapshot.java#L23) | type | 対応済み（記載した共通契約） | [capture-boundaries](#capture-boundaries) |
| [`context.BreakPointSpecifier`](../unlaxer-common/src/main/java/org/unlaxer/context/BreakPointSpecifier.java#L10) | type | 言語固有（移植境界あり） | [debug-observers](#debug-observers) |
| [`context.BreakPointSpecifierWithParser`](../unlaxer-common/src/main/java/org/unlaxer/context/BreakPointSpecifierWithParser.java#L10) | type | 言語固有（移植境界あり） | [debug-observers](#debug-observers) |
| [`context.CombinedDebugSpecifier`](../unlaxer-common/src/main/java/org/unlaxer/context/CombinedDebugSpecifier.java#L6) | type | 言語固有（移植境界あり） | [debug-observers](#debug-observers) |
| [`context.CreateMetaTokenSpecifier`](../unlaxer-common/src/main/java/org/unlaxer/context/CreateMetaTokenSpecifier.java#L3) | type | 言語固有（移植境界あり） | [meta-function](#meta-function) |
| [`context.CreateMetaTokenSpecifier#of(boolean)`](../unlaxer-common/src/main/java/org/unlaxer/context/CreateMetaTokenSpecifier.java#L19) | static | 言語固有（移植境界あり） | [meta-function](#meta-function) |
| [`context.CreateMetaTokenSprcifier`](../unlaxer-common/src/main/java/org/unlaxer/context/CreateMetaTokenSprcifier.java#L7) | type | 言語固有（移植境界あり） | [meta-function](#meta-function) |
| [`context.CreateMetaTokenSprcifier#of(boolean)`](../unlaxer-common/src/main/java/org/unlaxer/context/CreateMetaTokenSprcifier.java#L25) | static | 言語固有（移植境界あり） | [meta-function](#meta-function) |
| [`context.DiagnosticFormatter`](../unlaxer-common/src/main/java/org/unlaxer/context/DiagnosticFormatter.java#L22) | type | 対応あり・全API共通検証なし | [parse-options-diagnostics](#parse-options-diagnostics) |
| [`context.DiagnosticFormatter#format(Source,ParseFailureDiagnostics)`](../unlaxer-common/src/main/java/org/unlaxer/context/DiagnosticFormatter.java#L74) | static | 対応あり・全API共通検証なし | [parse-options-diagnostics](#parse-options-diagnostics) |
| [`context.DiagnosticFormatter#format(Source,int,String)`](../unlaxer-common/src/main/java/org/unlaxer/context/DiagnosticFormatter.java#L34) | static | 対応あり・全API共通検証なし | [parse-options-diagnostics](#parse-options-diagnostics) |
| [`context.DiagnosticFormatter#format(Source,int,int,String)`](../unlaxer-common/src/main/java/org/unlaxer/context/DiagnosticFormatter.java#L60) | static | 対応あり・全API共通検証なし | [parse-options-diagnostics](#parse-options-diagnostics) |
| [`context.DiagnosticsAgnostic`](../unlaxer-common/src/main/java/org/unlaxer/context/DiagnosticsAgnostic.java#L10) | type | 対応あり・全API共通検証なし | [parse-options-diagnostics](#parse-options-diagnostics) |
| [`context.DiagnosticsSafety`](../unlaxer-common/src/main/java/org/unlaxer/context/DiagnosticsSafety.java#L12) | type | 対応あり・全API共通検証なし | [parse-options-diagnostics](#parse-options-diagnostics) |
| [`context.DiagnosticsSafety#isDeferredDiagnosticsSafe(Parser)`](../unlaxer-common/src/main/java/org/unlaxer/context/DiagnosticsSafety.java#L21) | static | 対応あり・全API共通検証なし | [parse-options-diagnostics](#parse-options-diagnostics) |
| [`context.Memoization`](../unlaxer-common/src/main/java/org/unlaxer/context/Memoization.java#L4) | type | 対応あり・全API共通検証なし | [memo-policy](#memo-policy) |
| [`context.MutationAwareTransactionalState`](../unlaxer-common/src/main/java/org/unlaxer/context/MutationAwareTransactionalState.java#L12) | type | 対応あり・全API共通検証なし | [context-results](#context-results) |
| [`context.NameResolution`](../unlaxer-common/src/main/java/org/unlaxer/context/NameResolution.java#L7) | type | 対応あり・全API共通検証なし | [name-snapshot-api](#name-snapshot-api) |
| [`context.NameResolution#begin(ParseContext,List<NameSnapshot.Requirement>)`](../unlaxer-common/src/main/java/org/unlaxer/context/NameResolution.java#L23) | static | 対応あり・全API共通検証なし | [name-snapshot-api](#name-snapshot-api) |
| [`context.NameResolution#end(ParseContext,Frame)`](../unlaxer-common/src/main/java/org/unlaxer/context/NameResolution.java#L59) | static | 対応あり・全API共通検証なし | [name-snapshot-api](#name-snapshot-api) |
| [`context.NameResolution#failure(ParseContext)`](../unlaxer-common/src/main/java/org/unlaxer/context/NameResolution.java#L90) | static | 対応あり・全API共通検証なし | [name-snapshot-api](#name-snapshot-api) |
| [`context.NameResolution#matches(ParseContext,String,String,String,String,int,int)`](../unlaxer-common/src/main/java/org/unlaxer/context/NameResolution.java#L65) | static | 対応あり・全API共通検証なし | [name-snapshot-api](#name-snapshot-api) |
| [`context.NameResolution#reject(ParseContext,String,int,int,String)`](../unlaxer-common/src/main/java/org/unlaxer/context/NameResolution.java#L84) | static | 対応あり・全API共通検証なし | [name-snapshot-api](#name-snapshot-api) |
| [`context.NameResolution.Failure`](../unlaxer-common/src/main/java/org/unlaxer/context/NameResolution.java#L10) | type | 対応あり・全API共通検証なし | [name-snapshot-api](#name-snapshot-api) |
| [`context.NameResolution.Frame`](../unlaxer-common/src/main/java/org/unlaxer/context/NameResolution.java#L11) | type | 対応あり・全API共通検証なし | [name-snapshot-api](#name-snapshot-api) |
| [`context.NameSnapshot`](../unlaxer-common/src/main/java/org/unlaxer/context/NameSnapshot.java#L6) | type | 対応あり・全API共通検証なし | [name-snapshot-api](#name-snapshot-api) |
| [`context.NameSnapshot#bindingPrefix(String)`](../unlaxer-common/src/main/java/org/unlaxer/context/NameSnapshot.java#L33) | static | 対応あり・全API共通検証なし | [name-snapshot-api](#name-snapshot-api) |
| [`context.NameSnapshot#bindingsOf(List<NameSnapshot>)`](../unlaxer-common/src/main/java/org/unlaxer/context/NameSnapshot.java#L35) | static | 対応あり・全API共通検証なし | [name-snapshot-api](#name-snapshot-api) |
| [`context.NameSnapshot#fromContext(ParseContext,String)`](../unlaxer-common/src/main/java/org/unlaxer/context/NameSnapshot.java#L53) | static | 対応あり・全API共通検証なし | [name-snapshot-api](#name-snapshot-api) |
| [`context.NameSnapshot#validateId(String)`](../unlaxer-common/src/main/java/org/unlaxer/context/NameSnapshot.java#L19) | static | 対応あり・全API共通検証なし | [name-snapshot-api](#name-snapshot-api) |
| [`context.NameSnapshot#validateName(String)`](../unlaxer-common/src/main/java/org/unlaxer/context/NameSnapshot.java#L28) | static | 対応あり・全API共通検証なし | [name-snapshot-api](#name-snapshot-api) |
| [`context.NameSnapshot#validateVersion(String)`](../unlaxer-common/src/main/java/org/unlaxer/context/NameSnapshot.java#L23) | static | 対応あり・全API共通検証なし | [name-snapshot-api](#name-snapshot-api) |
| [`context.NameSnapshot.Kind`](../unlaxer-common/src/main/java/org/unlaxer/context/NameSnapshot.java#L7) | type | 対応あり・全API共通検証なし | [name-snapshot-api](#name-snapshot-api) |
| [`context.NameSnapshot.Requirement`](../unlaxer-common/src/main/java/org/unlaxer/context/NameSnapshot.java#L8) | type | 対応あり・全API共通検証なし | [name-snapshot-api](#name-snapshot-api) |
| [`context.PackratMemoTable`](../unlaxer-common/src/main/java/org/unlaxer/context/PackratMemoTable.java#L37) | type | 対応あり・全API共通検証なし | [memo-policy](#memo-policy) |
| [`context.PackratMemoTable#beginFailure(ParseContext,Parser)`](../unlaxer-common/src/main/java/org/unlaxer/context/PackratMemoTable.java#L314) | static | 対応あり・全API共通検証なし | [memo-policy](#memo-policy) |
| [`context.PackratMemoTable#commitSuccess(ParseContext,Parser,TokenKind,boolean,PositionKey,ParseContext.FailureDiagnostic,Parser,Parsed)`](../unlaxer-common/src/main/java/org/unlaxer/context/PackratMemoTable.java#L210) | static | 対応あり・全API共通検証なし | [memo-policy](#memo-policy) |
| [`context.PackratMemoTable#lookup(ParseContext,Parser,TokenKind,boolean)`](../unlaxer-common/src/main/java/org/unlaxer/context/PackratMemoTable.java#L117) | static | 対応あり・全API共通検証なし | [memo-policy](#memo-policy) |
| [`context.PackratMemoTable#memoizeFailure(ParseContext,Parser,TokenKind,boolean,ParseContext.FailureDiagnostic)`](../unlaxer-common/src/main/java/org/unlaxer/context/PackratMemoTable.java#L296) | static | 対応あり・全API共通検証なし | [memo-policy](#memo-policy) |
| [`context.PackratMemoTable#memoizeSuccess(ParseContext,ParseContext.FailureDiagnostic)`](../unlaxer-common/src/main/java/org/unlaxer/context/PackratMemoTable.java#L321) | static | 対応あり・全API共通検証なし | [memo-policy](#memo-policy) |
| [`context.PackratMemoTable#positionKeyOf(ParseContext,TokenKind,boolean)`](../unlaxer-common/src/main/java/org/unlaxer/context/PackratMemoTable.java#L361) | static | 対応あり・全API共通検証なし | [memo-policy](#memo-policy) |
| [`context.PackratMemoTable#replay(ParseContext,Parser,TokenKind,boolean,Entry)`](../unlaxer-common/src/main/java/org/unlaxer/context/PackratMemoTable.java#L154) | static | 対応あり・全API共通検証なし | [memo-policy](#memo-policy) |
| [`context.PackratMemoTable#successKey(ParseContext,Parser,TokenKind,boolean,ParseContext.FailureDiagnostic)`](../unlaxer-common/src/main/java/org/unlaxer/context/PackratMemoTable.java#L197) | static | 対応あり・全API共通検証なし | [memo-policy](#memo-policy) |
| [`context.PackratMemoTable.Entry`](../unlaxer-common/src/main/java/org/unlaxer/context/PackratMemoTable.java#L51) | type | 対応あり・全API共通検証なし | [memo-policy](#memo-policy) |
| [`context.PackratMemoTable.PositionKey`](../unlaxer-common/src/main/java/org/unlaxer/context/PackratMemoTable.java#L40) | type | 対応あり・全API共通検証なし | [memo-policy](#memo-policy) |
| [`context.ParseContext`](../unlaxer-common/src/main/java/org/unlaxer/context/ParseContext.java#L39) | type | 対応あり・全API共通検証なし | [context-results](#context-results) |
| [`context.ParseContext#getParseContextWithCurrentThread()`](../unlaxer-common/src/main/java/org/unlaxer/context/ParseContext.java#L1943) | static | 対応あり・全API共通検証なし | [context-results](#context-results) |
| [`context.ParseContext#memoize()`](../unlaxer-common/src/main/java/org/unlaxer/context/ParseContext.java#L804) | static | 対応あり・全API共通検証なし | [context-results](#context-results) |
| [`context.ParseContext#withBindings(Source,Map<String, ? extends List<String>>,ParseOptions,ParseContextEffector[])`](../unlaxer-common/src/main/java/org/unlaxer/context/ParseContext.java#L678) | static | 対応済み（記載した共通契約） | [parse-bindings](#parse-bindings) |
| [`context.ParseContext#withNameSnapshots(Source,List<NameSnapshot>,ParseOptions,ParseContextEffector[])`](../unlaxer-common/src/main/java/org/unlaxer/context/ParseContext.java#L684) | static | 対応済み（記載した共通契約） | [name-snapshots](#name-snapshots) |
| [`context.ParseContext#withOptions(Source,ParseOptions,ParseContextEffector[])`](../unlaxer-common/src/main/java/org/unlaxer/context/ParseContext.java#L669) | static | 対応あり・全API共通検証なし | [context-results](#context-results) |
| [`context.ParseContext.DiagnosticSpeculation`](../unlaxer-common/src/main/java/org/unlaxer/context/ParseContext.java#L1158) | type | 対応あり・全API共通検証なし | [context-results](#context-results) |
| [`context.ParseContext.FailureDiagnostic`](../unlaxer-common/src/main/java/org/unlaxer/context/ParseContext.java#L908) | type | 対応あり・全API共通検証なし | [context-results](#context-results) |
| [`context.ParseContext.LocalDiagnostic`](../unlaxer-common/src/main/java/org/unlaxer/context/ParseContext.java#L1510) | type | 対応あり・全API共通検証なし | [context-results](#context-results) |
| [`context.ParseContextBase`](../unlaxer-common/src/main/java/org/unlaxer/context/ParseContextBase.java#L13) | type | 対応あり・全API共通検証なし | [context-results](#context-results) |
| [`context.ParseContextEffector`](../unlaxer-common/src/main/java/org/unlaxer/context/ParseContextEffector.java#L3) | type | 対応あり・全API共通検証なし | [context-results](#context-results) |
| [`context.ParseFailureDiagnostics`](../unlaxer-common/src/main/java/org/unlaxer/context/ParseFailureDiagnostics.java#L9) | type | 対応あり・全API共通検証なし | [parse-options-diagnostics](#parse-options-diagnostics) |
| [`context.ParseFailureDiagnostics.ExpectedHintCandidate`](../unlaxer-common/src/main/java/org/unlaxer/context/ParseFailureDiagnostics.java#L84) | type | 対応あり・全API共通検証なし | [parse-options-diagnostics](#parse-options-diagnostics) |
| [`context.ParseFailureDiagnostics.ParseStackElement`](../unlaxer-common/src/main/java/org/unlaxer/context/ParseFailureDiagnostics.java#L43) | type | 対応あり・全API共通検証なし | [parse-options-diagnostics](#parse-options-diagnostics) |
| [`context.ParseFailureDiagnostics.TrialRecord`](../unlaxer-common/src/main/java/org/unlaxer/context/ParseFailureDiagnostics.java#L15) | type | 対応あり・全API共通検証なし | [parse-options-diagnostics](#parse-options-diagnostics) |
| [`context.ParseLimitException`](../unlaxer-common/src/main/java/org/unlaxer/context/ParseLimitException.java#L7) | type | 対応あり・全API共通検証なし | [parse-options-diagnostics](#parse-options-diagnostics) |
| [`context.ParseOptions`](../unlaxer-common/src/main/java/org/unlaxer/context/ParseOptions.java#L6) | type | 対応あり・全API共通検証なし | [parse-options-diagnostics](#parse-options-diagnostics) |
| [`context.ParseOptions#defaults()`](../unlaxer-common/src/main/java/org/unlaxer/context/ParseOptions.java#L39) | static | 対応あり・全API共通検証なし | [parse-options-diagnostics](#parse-options-diagnostics) |
| [`context.ParseOptions#withMemoization(Memoization)`](../unlaxer-common/src/main/java/org/unlaxer/context/ParseOptions.java#L43) | static | 対応あり・全API共通検証なし | [memo-policy](#memo-policy) |
| [`context.ParseOptions.Diagnostics`](../unlaxer-common/src/main/java/org/unlaxer/context/ParseOptions.java#L8) | type | 対応あり・全API共通検証なし | [parse-options-diagnostics](#parse-options-diagnostics) |
| [`context.ParserContextPrinter`](../unlaxer-common/src/main/java/org/unlaxer/context/ParserContextPrinter.java#L11) | type | 言語固有（移植境界あり） | [debug-observers](#debug-observers) |
| [`context.ParserContextPrinter#get(ParseContext,OutputLevel)`](../unlaxer-common/src/main/java/org/unlaxer/context/ParserContextPrinter.java#L13) | static | 言語固有（移植境界あり） | [debug-observers](#debug-observers) |
| [`context.ParserContextScopeTree`](../unlaxer-common/src/main/java/org/unlaxer/context/ParserContextScopeTree.java#L12) | type | 対応あり・全API共通検証なし | [context-results](#context-results) |
| [`context.ParserDebugSpecifier`](../unlaxer-common/src/main/java/org/unlaxer/context/ParserDebugSpecifier.java#L6) | type | 言語固有（移植境界あり） | [debug-observers](#debug-observers) |
| [`context.PortableTokenMetadata`](../unlaxer-common/src/main/java/org/unlaxer/context/PortableTokenMetadata.java#L15) | type | 対応済み（記載した共通契約） | [portable-metadata](#portable-metadata) |
| [`context.PortableTokenMetadata.Info`](../unlaxer-common/src/main/java/org/unlaxer/context/PortableTokenMetadata.java#L26) | type | 対応済み（記載した共通契約） | [portable-metadata](#portable-metadata) |
| [`context.PortableTokenMetadata.NodeId`](../unlaxer-common/src/main/java/org/unlaxer/context/PortableTokenMetadata.java#L16) | type | 対応済み（記載した共通契約） | [portable-metadata](#portable-metadata) |
| [`context.PortableTokenMetadata.Snapshot`](../unlaxer-common/src/main/java/org/unlaxer/context/PortableTokenMetadata.java#L34) | type | 対応済み（記載した共通契約） | [portable-metadata](#portable-metadata) |
| [`context.RaisedFailure`](../unlaxer-common/src/main/java/org/unlaxer/context/RaisedFailure.java#L19) | type | 対応あり・全API共通検証なし | [parse-options-diagnostics](#parse-options-diagnostics) |
| [`context.RaisedFailure#commit(String,int)`](../unlaxer-common/src/main/java/org/unlaxer/context/RaisedFailure.java#L44) | static | 対応あり・全API共通検証なし | [parse-options-diagnostics](#parse-options-diagnostics) |
| [`context.RaisedFailure#label(String,int)`](../unlaxer-common/src/main/java/org/unlaxer/context/RaisedFailure.java#L48) | static | 対応あり・全API共通検証なし | [parse-options-diagnostics](#parse-options-diagnostics) |
| [`context.RaisedFailure.Kind`](../unlaxer-common/src/main/java/org/unlaxer/context/RaisedFailure.java#L22) | type | 対応あり・全API共通検証なし | [parse-options-diagnostics](#parse-options-diagnostics) |
| [`context.RuleStack`](../unlaxer-common/src/main/java/org/unlaxer/context/RuleStack.java#L21) | type | 対応あり・全API共通検証なし | [parse-options-diagnostics](#parse-options-diagnostics) |
| [`context.RuleStack.Frame`](../unlaxer-common/src/main/java/org/unlaxer/context/RuleStack.java#L32) | type | 対応あり・全API共通検証なし | [parse-options-diagnostics](#parse-options-diagnostics) |
| [`context.SafeFailureMemoizable`](../unlaxer-common/src/main/java/org/unlaxer/context/SafeFailureMemoizable.java#L8) | type | 対応あり・全API共通検証なし | [memo-policy](#memo-policy) |
| [`context.SafeSuccessMemoizable`](../unlaxer-common/src/main/java/org/unlaxer/context/SafeSuccessMemoizable.java#L9) | type | 対応あり・全API共通検証なし | [memo-policy](#memo-policy) |
| [`context.SemanticPredicateBindings`](../unlaxer-common/src/main/java/org/unlaxer/context/SemanticPredicateBindings.java#L14) | type | 対応済み（記載した共通契約） | [semantic-predicates](#semantic-predicates) |
| [`context.SemanticPredicateBindings#begin(ParseContext,List<String>)`](../unlaxer-common/src/main/java/org/unlaxer/context/SemanticPredicateBindings.java#L58) | static | 対応済み（記載した共通契約） | [semantic-predicates](#semantic-predicates) |
| [`context.SemanticPredicateBindings#end(ParseContext,Frame)`](../unlaxer-common/src/main/java/org/unlaxer/context/SemanticPredicateBindings.java#L121) | static | 対応済み（記載した共通契約） | [semantic-predicates](#semantic-predicates) |
| [`context.SemanticPredicateBindings#failure(ParseContext)`](../unlaxer-common/src/main/java/org/unlaxer/context/SemanticPredicateBindings.java#L127) | static | 対応済み（記載した共通契約） | [semantic-predicates](#semantic-predicates) |
| [`context.SemanticPredicateBindings#install(ParseContext,Map<String, Predicate<Candidate>>)`](../unlaxer-common/src/main/java/org/unlaxer/context/SemanticPredicateBindings.java#L35) | static | 対応済み（記載した共通契約） | [semantic-predicates](#semantic-predicates) |
| [`context.SemanticPredicateBindings#requireName(String)`](../unlaxer-common/src/main/java/org/unlaxer/context/SemanticPredicateBindings.java#L45) | static | 対応済み（記載した共通契約） | [semantic-predicates](#semantic-predicates) |
| [`context.SemanticPredicateBindings#test(ParseContext,String,Source,int)`](../unlaxer-common/src/main/java/org/unlaxer/context/SemanticPredicateBindings.java#L79) | static | 対応済み（記載した共通契約） | [semantic-predicates](#semantic-predicates) |
| [`context.SemanticPredicateBindings.Candidate`](../unlaxer-common/src/main/java/org/unlaxer/context/SemanticPredicateBindings.java#L19) | type | 対応済み（記載した共通契約） | [semantic-predicates](#semantic-predicates) |
| [`context.SemanticPredicateBindings.Failure`](../unlaxer-common/src/main/java/org/unlaxer/context/SemanticPredicateBindings.java#L20) | type | 対応済み（記載した共通契約） | [semantic-predicates](#semantic-predicates) |
| [`context.SemanticPredicateBindings.Frame`](../unlaxer-common/src/main/java/org/unlaxer/context/SemanticPredicateBindings.java#L21) | type | 対応済み（記載した共通契約） | [semantic-predicates](#semantic-predicates) |
| [`context.Transaction`](../unlaxer-common/src/main/java/org/unlaxer/context/Transaction.java#L31) | type | 対応あり・全API共通検証なし | [context-results](#context-results) |
| [`context.Transaction.AdditionalCommitAction`](../unlaxer-common/src/main/java/org/unlaxer/context/Transaction.java#L224) | type | 対応あり・全API共通検証なし | [context-results](#context-results) |
| [`context.Transaction.AdditionalPostCommitAction`](../unlaxer-common/src/main/java/org/unlaxer/context/Transaction.java#L230) | type | 対応あり・全API共通検証なし | [context-results](#context-results) |
| [`context.Transaction.AdditionalPreCommitAction`](../unlaxer-common/src/main/java/org/unlaxer/context/Transaction.java#L226) | type | 対応あり・全API共通検証なし | [context-results](#context-results) |
| [`context.TransactionDebugSpecifier`](../unlaxer-common/src/main/java/org/unlaxer/context/TransactionDebugSpecifier.java#L6) | type | 言語固有（移植境界あり） | [debug-observers](#debug-observers) |
| [`context.TransactionListenerContainer`](../unlaxer-common/src/main/java/org/unlaxer/context/TransactionListenerContainer.java#L12) | type | 言語固有（移植境界あり） | [debug-observers](#debug-observers) |
| [`context.TransactionMetrics`](../unlaxer-common/src/main/java/org/unlaxer/context/TransactionMetrics.java#L4) | type | 対応あり・全API共通検証なし | [context-results](#context-results) |
| [`context.TransactionalState`](../unlaxer-common/src/main/java/org/unlaxer/context/TransactionalState.java#L7) | type | 対応あり・全API共通検証なし | [context-results](#context-results) |
| [`listener.BreakPointHolder`](../unlaxer-common/src/main/java/org/unlaxer/listener/BreakPointHolder.java#L8) | type | 言語固有（移植境界あり） | [debug-observers](#debug-observers) |
| [`listener.BreakPointHolder.BreakPointMethod`](../unlaxer-common/src/main/java/org/unlaxer/listener/BreakPointHolder.java#L11) | type | 言語固有（移植境界あり） | [debug-observers](#debug-observers) |
| [`listener.CombinedDebugListener`](../unlaxer-common/src/main/java/org/unlaxer/listener/CombinedDebugListener.java#L16) | type | 言語固有（移植境界あり） | [debug-observers](#debug-observers) |
| [`listener.DebugParserListener`](../unlaxer-common/src/main/java/org/unlaxer/listener/DebugParserListener.java#L16) | type | 言語固有（移植境界あり） | [debug-observers](#debug-observers) |
| [`listener.DebugTransactionListener`](../unlaxer-common/src/main/java/org/unlaxer/listener/DebugTransactionListener.java#L19) | type | 言語固有（移植境界あり） | [debug-observers](#debug-observers) |
| [`listener.ExplicitBreakPointHolder`](../unlaxer-common/src/main/java/org/unlaxer/listener/ExplicitBreakPointHolder.java#L13) | type | 言語固有（移植境界あり） | [debug-observers](#debug-observers) |
| [`listener.ExplicitBreakPointHolderWithParser`](../unlaxer-common/src/main/java/org/unlaxer/listener/ExplicitBreakPointHolderWithParser.java#L14) | type | 言語固有（移植境界あり） | [debug-observers](#debug-observers) |
| [`listener.LogOutputCountListener`](../unlaxer-common/src/main/java/org/unlaxer/listener/LogOutputCountListener.java#L3) | type | 言語固有（移植境界あり） | [debug-observers](#debug-observers) |
| [`listener.LogTriggeredParserListener`](../unlaxer-common/src/main/java/org/unlaxer/listener/LogTriggeredParserListener.java#L11) | type | 言語固有（移植境界あり） | [debug-observers](#debug-observers) |
| [`listener.OutputLevel`](../unlaxer-common/src/main/java/org/unlaxer/listener/OutputLevel.java#L3) | type | 言語固有（移植境界あり） | [debug-observers](#debug-observers) |
| [`listener.ParserListener`](../unlaxer-common/src/main/java/org/unlaxer/listener/ParserListener.java#L8) | type | 言語固有（移植境界あり） | [debug-observers](#debug-observers) |
| [`listener.ParserListener.ParseParameters`](../unlaxer-common/src/main/java/org/unlaxer/listener/ParserListener.java#L37) | type | 言語固有（移植境界あり） | [debug-observers](#debug-observers) |
| [`listener.ParserListenerContainer`](../unlaxer-common/src/main/java/org/unlaxer/listener/ParserListenerContainer.java#L13) | type | 言語固有（移植境界あり） | [debug-observers](#debug-observers) |
| [`listener.ParserListenerSpecifier`](../unlaxer-common/src/main/java/org/unlaxer/listener/ParserListenerSpecifier.java#L10) | type | 言語固有（移植境界あり） | [debug-observers](#debug-observers) |
| [`listener.TransactionListener`](../unlaxer-common/src/main/java/org/unlaxer/listener/TransactionListener.java#L12) | type | 言語固有（移植境界あり） | [debug-observers](#debug-observers) |
| [`parser.AbstractParser`](../unlaxer-common/src/main/java/org/unlaxer/parser/AbstractParser.java#L20) | type | 対応済み（記載した共通契約） | [construction](#construction) |
| [`parser.AfterParse`](../unlaxer-common/src/main/java/org/unlaxer/parser/AfterParse.java#L7) | type | 対応あり・全API共通検証なし | [context-results](#context-results) |
| [`parser.ChainParsers`](../unlaxer-common/src/main/java/org/unlaxer/parser/ChainParsers.java#L5) | type | 対応済み（記載した共通契約） | [construction](#construction) |
| [`parser.ChildOccurs`](../unlaxer-common/src/main/java/org/unlaxer/parser/ChildOccurs.java#L3) | type | 対応済み（記載した共通契約） | [construction](#construction) |
| [`parser.ChoiceParsers`](../unlaxer-common/src/main/java/org/unlaxer/parser/ChoiceParsers.java#L5) | type | 対応済み（記載した共通契約） | [construction](#construction) |
| [`parser.CollectingParser`](../unlaxer-common/src/main/java/org/unlaxer/parser/CollectingParser.java#L12) | type | 言語固有（移植境界あり） | [meta-function](#meta-function) |
| [`parser.ConstructedAbstractParser`](../unlaxer-common/src/main/java/org/unlaxer/parser/ConstructedAbstractParser.java#L5) | type | 対応済み（記載した共通契約） | [construction](#construction) |
| [`parser.ErrorMessageParser`](../unlaxer-common/src/main/java/org/unlaxer/parser/ErrorMessageParser.java#L15) | type | 対応あり・全API共通検証なし | [error-container](#error-container) |
| [`parser.ErrorMessageParser#expected(String)`](../unlaxer-common/src/main/java/org/unlaxer/parser/ErrorMessageParser.java#L47) | static | 対応あり・全API共通検証なし | [error-container](#error-container) |
| [`parser.ErrorMessageParser#expected(String,Name)`](../unlaxer-common/src/main/java/org/unlaxer/parser/ErrorMessageParser.java#L51) | static | 対応あり・全API共通検証なし | [error-container](#error-container) |
| [`parser.FirstSet`](../unlaxer-common/src/main/java/org/unlaxer/parser/FirstSet.java#L19) | type | 未対応（記載した動作） | [first-predictors](#first-predictors) |
| [`parser.FirstSet#ofCodePoints(int[])`](../unlaxer-common/src/main/java/org/unlaxer/parser/FirstSet.java#L48) | static | 未対応（記載した動作） | [first-predictors](#first-predictors) |
| [`parser.FirstSets`](../unlaxer-common/src/main/java/org/unlaxer/parser/FirstSets.java#L33) | type | 未対応（記載した動作） | [first-predictors](#first-predictors) |
| [`parser.FirstSets#of(Parser)`](../unlaxer-common/src/main/java/org/unlaxer/parser/FirstSets.java#L60) | static | 未対応（記載した動作） | [first-predictors](#first-predictors) |
| [`parser.GlobalScopeTree`](../unlaxer-common/src/main/java/org/unlaxer/parser/GlobalScopeTree.java#L8) | type | 対応あり・全API共通検証なし | [context-results](#context-results) |
| [`parser.GrammarRule`](../unlaxer-common/src/main/java/org/unlaxer/parser/GrammarRule.java#L12) | type | 対応済み（記載した共通契約） | [rule-diagnostics](#rule-diagnostics) |
| [`parser.GrammarRule#nameOf(Object)`](../unlaxer-common/src/main/java/org/unlaxer/parser/GrammarRule.java#L26) | static | 対応済み（記載した共通契約） | [rule-diagnostics](#rule-diagnostics) |
| [`parser.HasChildParser`](../unlaxer-common/src/main/java/org/unlaxer/parser/HasChildParser.java#L3) | type | 対応済み（記載した共通契約） | [construction](#construction) |
| [`parser.HasChildrenParser`](../unlaxer-common/src/main/java/org/unlaxer/parser/HasChildrenParser.java#L8) | type | 対応済み（記載した共通契約） | [construction](#construction) |
| [`parser.Initializable`](../unlaxer-common/src/main/java/org/unlaxer/parser/Initializable.java#L3) | type | 対応済み（記載した共通契約） | [construction](#construction) |
| [`parser.LazyAbstractParser`](../unlaxer-common/src/main/java/org/unlaxer/parser/LazyAbstractParser.java#L5) | type | 対応済み（記載した共通契約） | [construction](#construction) |
| [`parser.LazyInstance`](../unlaxer-common/src/main/java/org/unlaxer/parser/LazyInstance.java#L3) | type | 言語固有（移植境界あり） | [meta-function](#meta-function) |
| [`parser.LazyOccursParserSpecifier`](../unlaxer-common/src/main/java/org/unlaxer/parser/LazyOccursParserSpecifier.java#L6) | type | 対応済み（記載した共通契約） | [construction](#construction) |
| [`parser.LazyParserChildSpecifier`](../unlaxer-common/src/main/java/org/unlaxer/parser/LazyParserChildSpecifier.java#L5) | type | 対応済み（記載した共通契約） | [construction](#construction) |
| [`parser.LazyParserChildrenSpecifier`](../unlaxer-common/src/main/java/org/unlaxer/parser/LazyParserChildrenSpecifier.java#L8) | type | 対応済み（記載した共通契約） | [construction](#construction) |
| [`parser.MetaFunctionParser`](../unlaxer-common/src/main/java/org/unlaxer/parser/MetaFunctionParser.java#L3) | type | 言語固有（移植境界あり） | [meta-function](#meta-function) |
| [`parser.NodeReduceMarker`](../unlaxer-common/src/main/java/org/unlaxer/parser/NodeReduceMarker.java#L7) | type | 言語固有（移植境界あり） | [meta-function](#meta-function) |
| [`parser.NonTerminalSymbol`](../unlaxer-common/src/main/java/org/unlaxer/parser/NonTerminalSymbol.java#L6) | type | 言語固有（移植境界あり） | [meta-function](#meta-function) |
| [`parser.NonTerminallSymbol`](../unlaxer-common/src/main/java/org/unlaxer/parser/NonTerminallSymbol.java#L7) | type | 言語固有（移植境界あり） | [meta-function](#meta-function) |
| [`parser.ParseException`](../unlaxer-common/src/main/java/org/unlaxer/parser/ParseException.java#L3) | type | 対応あり・全API共通検証なし | [context-results](#context-results) |
| [`parser.Parser`](../unlaxer-common/src/main/java/org/unlaxer/parser/Parser.java#L13) | type | 対応済み（記載した共通契約） | [construction](#construction) |
| [`parser.Parser#checkTokenParsedBySpecifiedParser(Token,Class<? extends Parser>)`](../unlaxer-common/src/main/java/org/unlaxer/parser/Parser.java#L89) | static | 対応済み（記載した共通契約） | [construction](#construction) |
| [`parser.Parser#get(Class<T>)`](../unlaxer-common/src/main/java/org/unlaxer/parser/Parser.java#L54) | static | 対応済み（記載した共通契約） | [construction](#construction) |
| [`parser.Parser#get(Supplier<T>)`](../unlaxer-common/src/main/java/org/unlaxer/parser/Parser.java#L66) | static | 対応済み（記載した共通契約） | [construction](#construction) |
| [`parser.Parser#getSupplier(Class<T>)`](../unlaxer-common/src/main/java/org/unlaxer/parser/Parser.java#L58) | static | 対応済み（記載した共通契約） | [construction](#construction) |
| [`parser.Parser#isTokenParsedBySpecifiedParser(Token,Class<? extends Parser>)`](../unlaxer-common/src/main/java/org/unlaxer/parser/Parser.java#L84) | static | 対応済み（記載した共通契約） | [construction](#construction) |
| [`parser.Parser#newInstance(Class<T>)`](../unlaxer-common/src/main/java/org/unlaxer/parser/Parser.java#L70) | static | 対応済み（記載した共通契約） | [construction](#construction) |
| [`parser.ParserFactoryByClass`](../unlaxer-common/src/main/java/org/unlaxer/parser/ParserFactoryByClass.java#L11) | type | 対応済み（記載した共通契約） | [construction](#construction) |
| [`parser.ParserFactoryByClass#get(ASTNodeKind,Class<T>)`](../unlaxer-common/src/main/java/org/unlaxer/parser/ParserFactoryByClass.java#L33) | static | 対応済み（記載した共通契約） | [construction](#construction) |
| [`parser.ParserFactoryByClass#get(Class<T>)`](../unlaxer-common/src/main/java/org/unlaxer/parser/ParserFactoryByClass.java#L23) | static | 対応済み（記載した共通契約） | [construction](#construction) |
| [`parser.ParserFactoryByClass#newInstance(Class<T>)`](../unlaxer-common/src/main/java/org/unlaxer/parser/ParserFactoryByClass.java#L62) | static | 対応済み（記載した共通契約） | [construction](#construction) |
| [`parser.ParserFactoryByClass.ClassAndKind`](../unlaxer-common/src/main/java/org/unlaxer/parser/ParserFactoryByClass.java#L82) | type | 対応済み（記載した共通契約） | [construction](#construction) |
| [`parser.ParserFactoryBySupplier`](../unlaxer-common/src/main/java/org/unlaxer/parser/ParserFactoryBySupplier.java#L9) | type | 対応済み（記載した共通契約） | [construction](#construction) |
| [`parser.ParserFactoryBySupplier#get(Supplier<? extends Parser>)`](../unlaxer-common/src/main/java/org/unlaxer/parser/ParserFactoryBySupplier.java#L21) | static | 対応済み（記載した共通契約） | [construction](#construction) |
| [`parser.ParserInitializer`](../unlaxer-common/src/main/java/org/unlaxer/parser/ParserInitializer.java#L3) | type | 対応済み（記載した共通契約） | [construction](#construction) |
| [`parser.ParserPrinter`](../unlaxer-common/src/main/java/org/unlaxer/parser/ParserPrinter.java#L5) | type | 対応済み（記載した共通契約） | [rule-diagnostics](#rule-diagnostics) |
| [`parser.ParserPrinter#get(Parser,OutputLevel)`](../unlaxer-common/src/main/java/org/unlaxer/parser/ParserPrinter.java#L7) | static | 対応済み（記載した共通契約） | [rule-diagnostics](#rule-diagnostics) |
| [`parser.Parsers`](../unlaxer-common/src/main/java/org/unlaxer/parser/Parsers.java#L11) | type | 対応済み（記載した共通契約） | [construction](#construction) |
| [`parser.Parsers#of(List<Parser>)`](../unlaxer-common/src/main/java/org/unlaxer/parser/Parsers.java#L110) | static | 対応済み（記載した共通契約） | [construction](#construction) |
| [`parser.Parsers#of(Parser[])`](../unlaxer-common/src/main/java/org/unlaxer/parser/Parsers.java#L116) | static | 対応済み（記載した共通契約） | [construction](#construction) |
| [`parser.ParsersSpecifier`](../unlaxer-common/src/main/java/org/unlaxer/parser/ParsersSpecifier.java#L8) | type | 対応済み（記載した共通契約） | [construction](#construction) |
| [`parser.PositionedElements`](../unlaxer-common/src/main/java/org/unlaxer/parser/PositionedElements.java#L7) | type | 対応あり・全API共通検証なし | [context-results](#context-results) |
| [`parser.PropagatableDestination`](../unlaxer-common/src/main/java/org/unlaxer/parser/PropagatableDestination.java#L9) | type | 対応済み（記載した共通契約） | [propagation-markers](#propagation-markers) |
| [`parser.PropagatableSource`](../unlaxer-common/src/main/java/org/unlaxer/parser/PropagatableSource.java#L3) | type | 対応済み（記載した共通契約） | [propagation-markers](#propagation-markers) |
| [`parser.PseudoRootParser`](../unlaxer-common/src/main/java/org/unlaxer/parser/PseudoRootParser.java#L10) | type | 対応済み（記載した共通契約） | [construction](#construction) |
| [`parser.RootParserIndicator`](../unlaxer-common/src/main/java/org/unlaxer/parser/RootParserIndicator.java#L3) | type | 対応済み（記載した共通契約） | [construction](#construction) |
| [`parser.StaticParser`](../unlaxer-common/src/main/java/org/unlaxer/parser/StaticParser.java#L3) | type | 対応済み（記載した共通契約） | [construction](#construction) |
| [`parser.Suggest`](../unlaxer-common/src/main/java/org/unlaxer/parser/Suggest.java#L6) | type | 未対応（記載した動作） | [suggest](#suggest) |
| [`parser.SuggestableParser`](../unlaxer-common/src/main/java/org/unlaxer/parser/SuggestableParser.java#L17) | type | 未対応（記載した動作） | [suggest](#suggest) |
| [`parser.Suggests`](../unlaxer-common/src/main/java/org/unlaxer/parser/Suggests.java#L8) | type | 未対応（記載した動作） | [suggest](#suggest) |
| [`parser.SuggestsCollectorParser`](../unlaxer-common/src/main/java/org/unlaxer/parser/SuggestsCollectorParser.java#L16) | type | 未対応（記載した動作） | [suggest](#suggest) |
| [`parser.TerminalSymbol`](../unlaxer-common/src/main/java/org/unlaxer/parser/TerminalSymbol.java#L8) | type | 言語固有（移植境界あり） | [meta-function](#meta-function) |
| [`parser.ascii.BackSlashParser`](../unlaxer-common/src/main/java/org/unlaxer/parser/ascii/BackSlashParser.java#L6) | type | 対応あり・全API共通検証なし | [character-recipes](#character-recipes) |
| [`parser.ascii.DivisionParser`](../unlaxer-common/src/main/java/org/unlaxer/parser/ascii/DivisionParser.java#L6) | type | 対応あり・全API共通検証なし | [character-recipes](#character-recipes) |
| [`parser.ascii.DoubleQuoteParser`](../unlaxer-common/src/main/java/org/unlaxer/parser/ascii/DoubleQuoteParser.java#L6) | type | 対応あり・全API共通検証なし | [character-recipes](#character-recipes) |
| [`parser.ascii.EqualParser`](../unlaxer-common/src/main/java/org/unlaxer/parser/ascii/EqualParser.java#L6) | type | 対応あり・全API共通検証なし | [character-recipes](#character-recipes) |
| [`parser.ascii.GreaterThanParser`](../unlaxer-common/src/main/java/org/unlaxer/parser/ascii/GreaterThanParser.java#L6) | type | 対応あり・全API共通検証なし | [character-recipes](#character-recipes) |
| [`parser.ascii.LeftParenthesisParser`](../unlaxer-common/src/main/java/org/unlaxer/parser/ascii/LeftParenthesisParser.java#L6) | type | 対応あり・全API共通検証なし | [character-recipes](#character-recipes) |
| [`parser.ascii.LessThanParser`](../unlaxer-common/src/main/java/org/unlaxer/parser/ascii/LessThanParser.java#L6) | type | 対応あり・全API共通検証なし | [character-recipes](#character-recipes) |
| [`parser.ascii.MinusParser`](../unlaxer-common/src/main/java/org/unlaxer/parser/ascii/MinusParser.java#L5) | type | 対応あり・全API共通検証なし | [character-recipes](#character-recipes) |
| [`parser.ascii.PlusParser`](../unlaxer-common/src/main/java/org/unlaxer/parser/ascii/PlusParser.java#L5) | type | 対応あり・全API共通検証なし | [character-recipes](#character-recipes) |
| [`parser.ascii.PointParser`](../unlaxer-common/src/main/java/org/unlaxer/parser/ascii/PointParser.java#L6) | type | 対応あり・全API共通検証なし | [character-recipes](#character-recipes) |
| [`parser.ascii.RightParenthesisParser`](../unlaxer-common/src/main/java/org/unlaxer/parser/ascii/RightParenthesisParser.java#L6) | type | 対応あり・全API共通検証なし | [character-recipes](#character-recipes) |
| [`parser.ascii.SlashParser`](../unlaxer-common/src/main/java/org/unlaxer/parser/ascii/SlashParser.java#L6) | type | 対応あり・全API共通検証なし | [character-recipes](#character-recipes) |
| [`parser.clang.BlockComment`](../unlaxer-common/src/main/java/org/unlaxer/parser/clang/BlockComment.java#L10) | type | 対応あり・全API共通検証なし | [trivia-recipes](#trivia-recipes) |
| [`parser.clang.CPPComment`](../unlaxer-common/src/main/java/org/unlaxer/parser/clang/CPPComment.java#L15) | type | 対応あり・全API共通検証なし | [trivia-recipes](#trivia-recipes) |
| [`parser.clang.CStyleDelimitedLazyChain`](../unlaxer-common/src/main/java/org/unlaxer/parser/clang/CStyleDelimitedLazyChain.java#L16) | type | 対応あり・全API共通検証なし | [trivia-recipes](#trivia-recipes) |
| [`parser.clang.CStyleDelimitor`](../unlaxer-common/src/main/java/org/unlaxer/parser/clang/CStyleDelimitor.java#L9) | type | 対応あり・全API共通検証なし | [trivia-recipes](#trivia-recipes) |
| [`parser.clang.CStyleDelimitorElements`](../unlaxer-common/src/main/java/org/unlaxer/parser/clang/CStyleDelimitorElements.java#L7) | type | 対応あり・全API共通検証なし | [trivia-recipes](#trivia-recipes) |
| [`parser.clang.IdentifierParser`](../unlaxer-common/src/main/java/org/unlaxer/parser/clang/IdentifierParser.java#L11) | type | 対応あり・全API共通検証なし | [lexical-composites](#lexical-composites) |
| [`parser.combinator.ASTNode`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/ASTNode.java#L8) | type | 未対応（記載した動作） | [not-ast-node](#not-ast-node) |
| [`parser.combinator.AbstractPropagatableSource`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/AbstractPropagatableSource.java#L12) | type | 対応済み（記載した共通契約） | [propagation-markers](#propagation-markers) |
| [`parser.combinator.CaptureBoundaryParser`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/CaptureBoundaryParser.java#L7) | type | 対応済み（記載した共通契約） | [capture-boundaries](#capture-boundaries) |
| [`parser.combinator.CaptureBoundaryParser#input(Kind,Parser)`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/CaptureBoundaryParser.java#L44) | static | 対応済み（記載した共通契約） | [capture-boundaries](#capture-boundaries) |
| [`parser.combinator.CaptureBoundaryParser#list(Parser)`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/CaptureBoundaryParser.java#L80) | static | 対応済み（記載した共通契約） | [capture-boundaries](#capture-boundaries) |
| [`parser.combinator.CaptureBoundaryParser#list(Parser,int,int)`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/CaptureBoundaryParser.java#L77) | static | 対応済み（記載した共通契約） | [capture-boundaries](#capture-boundaries) |
| [`parser.combinator.CaptureBoundaryParser#optional(Parser)`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/CaptureBoundaryParser.java#L73) | static | 対応済み（記載した共通契約） | [capture-boundaries](#capture-boundaries) |
| [`parser.combinator.CaptureBoundaryParser#originOf(org.unlaxer.Token)`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/CaptureBoundaryParser.java#L17) | static | 対応済み（記載した共通契約） | [capture-boundaries](#capture-boundaries) |
| [`parser.combinator.CaptureBoundaryParser#value(Parser)`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/CaptureBoundaryParser.java#L66) | static | 対応済み（記載した共通契約） | [capture-boundaries](#capture-boundaries) |
| [`parser.combinator.CaptureBoundaryParser#wrap(Kind,Parser)`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/CaptureBoundaryParser.java#L61) | static | 対応済み（記載した共通契約） | [capture-boundaries](#capture-boundaries) |
| [`parser.combinator.CaptureBoundaryParser.Kind`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/CaptureBoundaryParser.java#L9) | type | 対応済み（記載した共通契約） | [capture-boundaries](#capture-boundaries) |
| [`parser.combinator.CaptureBoundaryParser.Origin`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/CaptureBoundaryParser.java#L14) | type | 対応済み（記載した共通契約） | [capture-boundaries](#capture-boundaries) |
| [`parser.combinator.CaptureScope`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/CaptureScope.java#L15) | type | 対応済み（記載した共通契約） | [owned-capture](#owned-capture) |
| [`parser.combinator.CatchLabels`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/CatchLabels.java#L28) | type | 対応済み（記載した共通契約） | [cut-labels](#cut-labels) |
| [`parser.combinator.Chain`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/Chain.java#L15) | type | 対応あり・全API共通検証なし | [core-combinators](#core-combinators) |
| [`parser.combinator.ChainInterface`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/ChainInterface.java#L11) | type | 対応あり・全API共通検証なし | [core-combinators](#core-combinators) |
| [`parser.combinator.ChildOccursWithTerminator`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/ChildOccursWithTerminator.java#L12) | type | 対応済み（記載した共通契約） | [terminated-repeat](#terminated-repeat) |
| [`parser.combinator.Choice`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/Choice.java#L12) | type | 対応あり・全API共通検証なし | [core-combinators](#core-combinators) |
| [`parser.combinator.ChoiceCommitAction`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/ChoiceCommitAction.java#L7) | type | 未対応（記載した動作） | [selected-choice](#selected-choice) |
| [`parser.combinator.ChoiceInterface`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/ChoiceInterface.java#L14) | type | 対応あり・全API共通検証なし | [core-combinators](#core-combinators) |
| [`parser.combinator.ChoiceInterface#choiced(Token)`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/ChoiceInterface.java#L71) | static | 対応あり・全API共通検証なし | [core-combinators](#core-combinators) |
| [`parser.combinator.ChoicePredictor`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/ChoicePredictor.java#L9) | type | 対応あり・全API共通検証なし | [core-combinators](#core-combinators) |
| [`parser.combinator.ChoicePredictor#any()`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/ChoicePredictor.java#L15) | static | 対応あり・全API共通検証なし | [core-combinators](#core-combinators) |
| [`parser.combinator.ChoicePredictor#anyOf(ChoicePredictor[])`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/ChoicePredictor.java#L25) | static | 対応あり・全API共通検証なし | [core-combinators](#core-combinators) |
| [`parser.combinator.ChoicePredictor#identifier()`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/ChoicePredictor.java#L21) | static | 対応あり・全API共通検証なし | [core-combinators](#core-combinators) |
| [`parser.combinator.ChoicePredictor#literal(String)`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/ChoicePredictor.java#L17) | static | 対応あり・全API共通検証なし | [core-combinators](#core-combinators) |
| [`parser.combinator.ChoicePredictor#number()`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/ChoicePredictor.java#L19) | static | 対応あり・全API共通検証なし | [core-combinators](#core-combinators) |
| [`parser.combinator.ChoicePredictor#quoted(char)`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/ChoicePredictor.java#L23) | static | 対応あり・全API共通検証なし | [core-combinators](#core-combinators) |
| [`parser.combinator.ChoicePredictor.Any`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/ChoicePredictor.java#L30) | type | 対応あり・全API共通検証なし | [core-combinators](#core-combinators) |
| [`parser.combinator.ChoicePredictor.AnyOf`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/ChoicePredictor.java#L104) | type | 対応あり・全API共通検証なし | [core-combinators](#core-combinators) |
| [`parser.combinator.ChoicePredictor.Identifier`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/ChoicePredictor.java#L82) | type | 対応あり・全API共通検証なし | [core-combinators](#core-combinators) |
| [`parser.combinator.ChoicePredictor.Literal`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/ChoicePredictor.java#L50) | type | 対応あり・全API共通検証なし | [core-combinators](#core-combinators) |
| [`parser.combinator.ChoicePredictor.Number`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/ChoicePredictor.java#L69) | type | 対応あり・全API共通検証なし | [core-combinators](#core-combinators) |
| [`parser.combinator.ChoicePredictor.Quoted`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/ChoicePredictor.java#L95) | type | 対応あり・全API共通検証なし | [core-combinators](#core-combinators) |
| [`parser.combinator.Commit`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/Commit.java#L22) | type | 対応済み（記載した共通契約） | [cut-labels](#cut-labels) |
| [`parser.combinator.ConstructedCombinatorParser`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/ConstructedCombinatorParser.java#L13) | type | 対応済み（記載した共通契約） | [construction](#construction) |
| [`parser.combinator.ConstructedOccurs`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/ConstructedOccurs.java#L11) | type | 対応済み（記載した共通契約） | [terminated-repeat](#terminated-repeat) |
| [`parser.combinator.ConstructedSingleChildParser`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/ConstructedSingleChildParser.java#L10) | type | 対応済み（記載した共通契約） | [construction](#construction) |
| [`parser.combinator.ContainerParser`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/ContainerParser.java#L16) | type | 対応あり・全API共通検証なし | [error-container](#error-container) |
| [`parser.combinator.ContainerParser#getRangedContents(Token)`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/ContainerParser.java#L45) | static | 対応あり・全API共通検証なし | [error-container](#error-container) |
| [`parser.combinator.ContainerParser#getRangedContents(Token,Class<? extends ContainerParser<T>>)`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/ContainerParser.java#L52) | static | 対応あり・全API共通検証なし | [error-container](#error-container) |
| [`parser.combinator.DoConsumePropagationStopper`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/DoConsumePropagationStopper.java#L18) | type | 対応済み（記載した共通契約） | [classic-propagation](#classic-propagation) |
| [`parser.combinator.Flatten`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/Flatten.java#L13) | type | 対応あり・全API共通検証なし | [graph-utilities](#graph-utilities) |
| [`parser.combinator.InvertMatchPropagationStopper`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/InvertMatchPropagationStopper.java#L9) | type | 対応済み（記載した共通契約） | [classic-propagation](#classic-propagation) |
| [`parser.combinator.Label`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/Label.java#L22) | type | 対応済み（記載した共通契約） | [cut-labels](#cut-labels) |
| [`parser.combinator.LazyChain`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/LazyChain.java#L14) | type | 対応あり・全API共通検証なし | [core-combinators](#core-combinators) |
| [`parser.combinator.LazyChoice`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/LazyChoice.java#L14) | type | 対応あり・全API共通検証なし | [core-combinators](#core-combinators) |
| [`parser.combinator.LazyCombinatorParser`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/LazyCombinatorParser.java#L11) | type | 対応済み（記載した共通契約） | [construction](#construction) |
| [`parser.combinator.LazyLongestChoice`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/LazyLongestChoice.java#L15) | type | 対応あり・全API共通検証なし | [core-combinators](#core-combinators) |
| [`parser.combinator.LazyOccurs`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/LazyOccurs.java#L15) | type | 対応済み（記載した共通契約） | [terminated-repeat](#terminated-repeat) |
| [`parser.combinator.LazyOneOrMore`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/LazyOneOrMore.java#L6) | type | 対応済み（記載した共通契約） | [terminated-repeat](#terminated-repeat) |
| [`parser.combinator.LazyOptional`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/LazyOptional.java#L5) | type | 対応済み（記載した共通契約） | [terminated-repeat](#terminated-repeat) |
| [`parser.combinator.LazyPredictiveChoice`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/LazyPredictiveChoice.java#L16) | type | 対応あり・全API共通検証なし | [core-combinators](#core-combinators) |
| [`parser.combinator.LazyRepeat`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/LazyRepeat.java#L5) | type | 対応済み（記載した共通契約） | [terminated-repeat](#terminated-repeat) |
| [`parser.combinator.LazyUniqueLongestChoice`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/LazyUniqueLongestChoice.java#L15) | type | 対応あり・全API共通検証なし | [core-combinators](#core-combinators) |
| [`parser.combinator.LazyZero`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/LazyZero.java#L5) | type | 対応あり・全API共通検証なし | [core-combinators](#core-combinators) |
| [`parser.combinator.LazyZeroOrMore`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/LazyZeroOrMore.java#L5) | type | 対応済み（記載した共通契約） | [terminated-repeat](#terminated-repeat) |
| [`parser.combinator.LazyZeroOrOne`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/LazyZeroOrOne.java#L5) | type | 対応済み（記載した共通契約） | [terminated-repeat](#terminated-repeat) |
| [`parser.combinator.LongestChoice`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/LongestChoice.java#L13) | type | 対応あり・全API共通検証なし | [core-combinators](#core-combinators) |
| [`parser.combinator.LongestChoiceInterface`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/LongestChoiceInterface.java#L19) | type | 対応あり・全API共通検証なし | [core-combinators](#core-combinators) |
| [`parser.combinator.MappedSingleCharacterParserHolder`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/MappedSingleCharacterParserHolder.java#L10) | type | 対応あり・全API共通検証なし | [graph-utilities](#graph-utilities) |
| [`parser.combinator.MatchOnly`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/MatchOnly.java#L10) | type | 対応済み（記載した共通契約） | [classic-propagation](#classic-propagation) |
| [`parser.combinator.NamePredicateParser`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/NamePredicateParser.java#L9) | type | 対応済み（記載した共通契約） | [name-snapshots](#name-snapshots) |
| [`parser.combinator.NameResolutionScope`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/NameResolutionScope.java#L9) | type | 対応済み（記載した共通契約） | [name-snapshots](#name-snapshots) |
| [`parser.combinator.NamedCapture`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/NamedCapture.java#L15) | type | 対応済み（記載した共通契約） | [owned-capture](#owned-capture) |
| [`parser.combinator.NonOrdered`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/NonOrdered.java#L22) | type | 対応済み（記載した共通契約） | [nonordered](#nonordered) |
| [`parser.combinator.NonOrdered.NonOrderedCommitAction`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/NonOrdered.java#L135) | type | 未対応（記載した動作） | [selected-order](#selected-order) |
| [`parser.combinator.NoneChildParser`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/NoneChildParser.java#L9) | type | 対応済み（記載した共通契約） | [construction](#construction) |
| [`parser.combinator.Not`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/Not.java#L9) | type | 対応あり・全API共通検証なし | [legacy-not](#legacy-not) |
| [`parser.combinator.NotASTNode`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/NotASTNode.java#L8) | type | 未対応（記載した動作） | [not-ast-node](#not-ast-node) |
| [`parser.combinator.NotPropagatableSource`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/NotPropagatableSource.java#L10) | type | 対応済み（記載した共通契約） | [classic-propagation](#classic-propagation) |
| [`parser.combinator.Occurs`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/Occurs.java#L16) | type | 対応済み（記載した共通契約） | [terminated-repeat](#terminated-repeat) |
| [`parser.combinator.OneOrMore`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/OneOrMore.java#L7) | type | 対応済み（記載した共通契約） | [terminated-repeat](#terminated-repeat) |
| [`parser.combinator.Optional`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/Optional.java#L7) | type | 対応済み（記載した共通契約） | [terminated-repeat](#terminated-repeat) |
| [`parser.combinator.Ordered`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/Ordered.java#L11) | type | 対応あり・全API共通検証なし | [core-combinators](#core-combinators) |
| [`parser.combinator.ParserHolder`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/ParserHolder.java#L10) | type | 対応あり・全API共通検証なし | [graph-utilities](#graph-utilities) |
| [`parser.combinator.ParserWrapper`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/ParserWrapper.java#L10) | type | 対応あり・全API共通検証なし | [graph-utilities](#graph-utilities) |
| [`parser.combinator.PegLookahead`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/PegLookahead.java#L17) | type | 対応済み（記載した共通契約） | [peg-assertion](#peg-assertion) |
| [`parser.combinator.PredicateAnyMatchForParsedParser`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/PredicateAnyMatchForParsedParser.java#L13) | type | 未対応（記載した動作） | [parsed-predicate](#parsed-predicate) |
| [`parser.combinator.PredictiveChoice`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/PredictiveChoice.java#L15) | type | 対応あり・全API共通検証なし | [core-combinators](#core-combinators) |
| [`parser.combinator.PredictiveChoiceInterface`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/PredictiveChoiceInterface.java#L12) | type | 対応あり・全API共通検証なし | [core-combinators](#core-combinators) |
| [`parser.combinator.PropagationStopper`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/PropagationStopper.java#L3) | type | 対応済み（記載した共通契約） | [propagation-markers](#propagation-markers) |
| [`parser.combinator.RecoveryDiagnostic`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/RecoveryDiagnostic.java#L11) | type | 対応あり・全API共通検証なし | [recovery](#recovery) |
| [`parser.combinator.RecoveryDiagnostic#from(ParseContext)`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/RecoveryDiagnostic.java#L13) | static | 対応あり・全API共通検証なし | [recovery](#recovery) |
| [`parser.combinator.RecoveryDiagnostic#from(Token)`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/RecoveryDiagnostic.java#L20) | static | 対応あり・全API共通検証なし | [recovery](#recovery) |
| [`parser.combinator.RecoveryDiagnostic.Marker`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/RecoveryDiagnostic.java#L36) | type | 対応あり・全API共通検証なし | [recovery](#recovery) |
| [`parser.combinator.Repeat`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/Repeat.java#L7) | type | 対応済み（記載した共通契約） | [terminated-repeat](#terminated-repeat) |
| [`parser.combinator.Reverse`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/Reverse.java#L10) | type | 対応あり・全API共通検証なし | [graph-utilities](#graph-utilities) |
| [`parser.combinator.SemanticPredicateParser`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/SemanticPredicateParser.java#L19) | type | 対応済み（記載した共通契約） | [semantic-predicates](#semantic-predicates) |
| [`parser.combinator.SemanticPredicateScope`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/SemanticPredicateScope.java#L16) | type | 対応済み（記載した共通契約） | [semantic-predicates](#semantic-predicates) |
| [`parser.combinator.SingleChildCollectingParser`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/SingleChildCollectingParser.java#L7) | type | 言語固有（移植境界あり） | [meta-function](#meta-function) |
| [`parser.combinator.SyncPointRecoveryParser`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/SyncPointRecoveryParser.java#L34) | type | 対応あり・全API共通検証なし | [recovery](#recovery) |
| [`parser.combinator.SyncPointRecoveryParser#withMessage(Parser,String,String[])`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/SyncPointRecoveryParser.java#L95) | static | 対応あり・全API共通検証なし | [recovery](#recovery) |
| [`parser.combinator.SyncPointRecoveryParser.Mode`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/SyncPointRecoveryParser.java#L36) | type | 対応あり・全API共通検証なし | [recovery](#recovery) |
| [`parser.combinator.TagWrapper`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/TagWrapper.java#L10) | type | 未対応（記載した動作） | [tag-wrapper](#tag-wrapper) |
| [`parser.combinator.TagWrapper.TagWrapperAction`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/TagWrapper.java#L26) | type | 未対応（記載した動作） | [tag-wrapper](#tag-wrapper) |
| [`parser.combinator.UniqueLongestChoice`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/UniqueLongestChoice.java#L13) | type | 対応あり・全API共通検証なし | [core-combinators](#core-combinators) |
| [`parser.combinator.WhiteSpaceDelimitedChain`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/WhiteSpaceDelimitedChain.java#L17) | type | 対応あり・全API共通検証なし | [trivia-recipes](#trivia-recipes) |
| [`parser.combinator.WhiteSpaceDelimitedLazyChain`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/WhiteSpaceDelimitedLazyChain.java#L16) | type | 対応あり・全API共通検証なし | [trivia-recipes](#trivia-recipes) |
| [`parser.combinator.Zero`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/Zero.java#L7) | type | 対応あり・全API共通検証なし | [core-combinators](#core-combinators) |
| [`parser.combinator.ZeroOrMore`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/ZeroOrMore.java#L7) | type | 対応済み（記載した共通契約） | [terminated-repeat](#terminated-repeat) |
| [`parser.combinator.ZeroOrOne`](../unlaxer-common/src/main/java/org/unlaxer/parser/combinator/ZeroOrOne.java#L7) | type | 対応済み（記載した共通契約） | [terminated-repeat](#terminated-repeat) |
| [`parser.elementary.AbstractTokenParser`](../unlaxer-common/src/main/java/org/unlaxer/parser/elementary/AbstractTokenParser.java#L15) | type | 対応済み（記載した共通契約） | [construction](#construction) |
| [`parser.elementary.DoubleQuotedParser`](../unlaxer-common/src/main/java/org/unlaxer/parser/elementary/DoubleQuotedParser.java#L6) | type | 対応あり・全API共通検証なし | [lexical-composites](#lexical-composites) |
| [`parser.elementary.EParser`](../unlaxer-common/src/main/java/org/unlaxer/parser/elementary/EParser.java#L5) | type | 対応あり・全API共通検証なし | [character-recipes](#character-recipes) |
| [`parser.elementary.EmptyLineParser`](../unlaxer-common/src/main/java/org/unlaxer/parser/elementary/EmptyLineParser.java#L12) | type | 対応あり・全API共通検証なし | [line-composition](#line-composition) |
| [`parser.elementary.EmptyParser`](../unlaxer-common/src/main/java/org/unlaxer/parser/elementary/EmptyParser.java#L12) | type | 対応あり・全API共通検証なし | [empty-eof](#empty-eof) |
| [`parser.elementary.EndOfLineParser`](../unlaxer-common/src/main/java/org/unlaxer/parser/elementary/EndOfLineParser.java#L3) | type | 対応済み（記載した共通契約） | [line-terminator](#line-terminator) |
| [`parser.elementary.EndOfSourceParser`](../unlaxer-common/src/main/java/org/unlaxer/parser/elementary/EndOfSourceParser.java#L11) | type | 対応あり・全API共通検証なし | [empty-eof](#empty-eof) |
| [`parser.elementary.EscapeInQuotedParser`](../unlaxer-common/src/main/java/org/unlaxer/parser/elementary/EscapeInQuotedParser.java#L9) | type | 対応あり・全API共通検証なし | [escape-in-quoted](#escape-in-quoted) |
| [`parser.elementary.ExponentParser`](../unlaxer-common/src/main/java/org/unlaxer/parser/elementary/ExponentParser.java#L12) | type | 対応あり・全API共通検証なし | [lexical-composites](#lexical-composites) |
| [`parser.elementary.GraphemeParser`](../unlaxer-common/src/main/java/org/unlaxer/parser/elementary/GraphemeParser.java#L12) | type | 対応済み（記載した共通契約） | [grapheme](#grapheme) |
| [`parser.elementary.IgnoreCaseWordParser`](../unlaxer-common/src/main/java/org/unlaxer/parser/elementary/IgnoreCaseWordParser.java#L3) | type | 対応あり・全API共通検証なし | [word-transforms](#word-transforms) |
| [`parser.elementary.LineTerminatorParser`](../unlaxer-common/src/main/java/org/unlaxer/parser/elementary/LineTerminatorParser.java#L12) | type | 対応済み（記載した共通契約） | [line-terminator](#line-terminator) |
| [`parser.elementary.MappedSingleCharacterParser`](../unlaxer-common/src/main/java/org/unlaxer/parser/elementary/MappedSingleCharacterParser.java#L10) | type | 対応あり・全API共通検証なし | [character-recipes](#character-recipes) |
| [`parser.elementary.MultipleParser`](../unlaxer-common/src/main/java/org/unlaxer/parser/elementary/MultipleParser.java#L5) | type | 対応あり・全API共通検証なし | [character-recipes](#character-recipes) |
| [`parser.elementary.NamedParenthesesParser`](../unlaxer-common/src/main/java/org/unlaxer/parser/elementary/NamedParenthesesParser.java#L12) | type | 対応あり・全API共通検証なし | [parentheses](#parentheses) |
| [`parser.elementary.NamedParenthesesParser#getInnerParserParsed(Token)`](../unlaxer-common/src/main/java/org/unlaxer/parser/elementary/NamedParenthesesParser.java#L29) | static | 対応あり・全API共通検証なし | [parentheses](#parentheses) |
| [`parser.elementary.NumberParser`](../unlaxer-common/src/main/java/org/unlaxer/parser/elementary/NumberParser.java#L15) | type | 対応あり・全API共通検証なし | [lexical-composites](#lexical-composites) |
| [`parser.elementary.ParenthesesParser`](../unlaxer-common/src/main/java/org/unlaxer/parser/elementary/ParenthesesParser.java#L12) | type | 対応あり・全API共通検証なし | [parentheses](#parentheses) |
| [`parser.elementary.ParenthesesParser#getParenthesesed(Token)`](../unlaxer-common/src/main/java/org/unlaxer/parser/elementary/ParenthesesParser.java#L38) | static | 対応あり・全API共通検証なし | [parentheses](#parentheses) |
| [`parser.elementary.QuotedParser`](../unlaxer-common/src/main/java/org/unlaxer/parser/elementary/QuotedParser.java#L19) | type | 対応あり・全API共通検証なし | [lexical-composites](#lexical-composites) |
| [`parser.elementary.QuotedParser#contents(Token)`](../unlaxer-common/src/main/java/org/unlaxer/parser/elementary/QuotedParser.java#L101) | static | 対応あり・全API共通検証なし | [lexical-composites](#lexical-composites) |
| [`parser.elementary.QuotedParser.Parts`](../unlaxer-common/src/main/java/org/unlaxer/parser/elementary/QuotedParser.java#L21) | type | 対応あり・全API共通検証なし | [lexical-composites](#lexical-composites) |
| [`parser.elementary.QuotedParser.QuotedContentsParser`](../unlaxer-common/src/main/java/org/unlaxer/parser/elementary/QuotedParser.java#L75) | type | 対応あり・全API共通検証なし | [lexical-composites](#lexical-composites) |
| [`parser.elementary.ReplayParser`](../unlaxer-common/src/main/java/org/unlaxer/parser/elementary/ReplayParser.java#L18) | type | 対応済み（記載した共通契約） | [owned-capture](#owned-capture) |
| [`parser.elementary.SignParser`](../unlaxer-common/src/main/java/org/unlaxer/parser/elementary/SignParser.java#L5) | type | 対応あり・全API共通検証なし | [character-recipes](#character-recipes) |
| [`parser.elementary.SingleCharacterParser`](../unlaxer-common/src/main/java/org/unlaxer/parser/elementary/SingleCharacterParser.java#L14) | type | 対応あり・全API共通検証なし | [character-recipes](#character-recipes) |
| [`parser.elementary.SingleQuoteParser`](../unlaxer-common/src/main/java/org/unlaxer/parser/elementary/SingleQuoteParser.java#L5) | type | 対応あり・全API共通検証なし | [character-recipes](#character-recipes) |
| [`parser.elementary.SingleQuotedParser`](../unlaxer-common/src/main/java/org/unlaxer/parser/elementary/SingleQuotedParser.java#L3) | type | 対応あり・全API共通検証なし | [lexical-composites](#lexical-composites) |
| [`parser.elementary.SingleStringParser`](../unlaxer-common/src/main/java/org/unlaxer/parser/elementary/SingleStringParser.java#L15) | type | 対応あり・全API共通検証なし | [character-recipes](#character-recipes) |
| [`parser.elementary.SpaceDelimitor`](../unlaxer-common/src/main/java/org/unlaxer/parser/elementary/SpaceDelimitor.java#L12) | type | 対応あり・全API共通検証なし | [trivia-recipes](#trivia-recipes) |
| [`parser.elementary.StartOfLineParser`](../unlaxer-common/src/main/java/org/unlaxer/parser/elementary/StartOfLineParser.java#L15) | type | 対応済み（記載した共通契約） | [start-of-line](#start-of-line) |
| [`parser.elementary.StartOfSourceParser`](../unlaxer-common/src/main/java/org/unlaxer/parser/elementary/StartOfSourceParser.java#L13) | type | 対応済み（記載した共通契約） | [start-of-source](#start-of-source) |
| [`parser.elementary.UnicodeIgnoreCaseWordParser`](../unlaxer-common/src/main/java/org/unlaxer/parser/elementary/UnicodeIgnoreCaseWordParser.java#L15) | type | 対応済み（記載した共通契約） | [unicode-ci](#unicode-ci) |
| [`parser.elementary.WildCardCharacterParser`](../unlaxer-common/src/main/java/org/unlaxer/parser/elementary/WildCardCharacterParser.java#L5) | type | 対応あり・全API共通検証なし | [character-recipes](#character-recipes) |
| [`parser.elementary.WildCardInterleaveParser`](../unlaxer-common/src/main/java/org/unlaxer/parser/elementary/WildCardInterleaveParser.java#L20) | type | 対応あり・全API共通検証なし | [wildcard-interleave](#wildcard-interleave) |
| [`parser.elementary.WildCardLineParser`](../unlaxer-common/src/main/java/org/unlaxer/parser/elementary/WildCardLineParser.java#L7) | type | 対応あり・全API共通検証なし | [line-composition](#line-composition) |
| [`parser.elementary.WildCardStringParser`](../unlaxer-common/src/main/java/org/unlaxer/parser/elementary/WildCardStringParser.java#L6) | type | 対応あり・全API共通検証なし | [character-recipes](#character-recipes) |
| [`parser.elementary.WildCardStringTerminatorParser`](../unlaxer-common/src/main/java/org/unlaxer/parser/elementary/WildCardStringTerminatorParser.java#L17) | type | 対応済み（記載した共通契約） | [until-variants](#until-variants) |
| [`parser.elementary.WordParser`](../unlaxer-common/src/main/java/org/unlaxer/parser/elementary/WordParser.java#L21) | type | 対応あり・全API共通検証なし | [word-transforms](#word-transforms) |
| [`parser.elementary.WordParser.BeginSpecifier`](../unlaxer-common/src/main/java/org/unlaxer/parser/elementary/WordParser.java#L130) | type | 対応あり・全API共通検証なし | [word-transforms](#word-transforms) |
| [`parser.elementary.WordParser.EndSpecifier`](../unlaxer-common/src/main/java/org/unlaxer/parser/elementary/WordParser.java#L131) | type | 対応あり・全API共通検証なし | [word-transforms](#word-transforms) |
| [`parser.elementary.WordParser.RangeSpecifier`](../unlaxer-common/src/main/java/org/unlaxer/parser/elementary/WordParser.java#L132) | type | 対応あり・全API共通検証なし | [word-transforms](#word-transforms) |
| [`parser.elementary.WordParser.WordEffector`](../unlaxer-common/src/main/java/org/unlaxer/parser/elementary/WordParser.java#L133) | type | 対応あり・全API共通検証なし | [word-transforms](#word-transforms) |
| [`parser.incremental.IncrementalParseCache`](../unlaxer-common/src/main/java/org/unlaxer/parser/incremental/IncrementalParseCache.java#L21) | type | 対応あり・全API共通検証なし | [incremental](#incremental) |
| [`parser.incremental.IncrementalParseCache.CacheStats`](../unlaxer-common/src/main/java/org/unlaxer/parser/incremental/IncrementalParseCache.java#L170) | type | 対応あり・全API共通検証なし | [incremental](#incremental) |
| [`parser.incremental.IncrementalParseCache.CachedChunk`](../unlaxer-common/src/main/java/org/unlaxer/parser/incremental/IncrementalParseCache.java#L40) | type | 対応あり・全API共通検証なし | [incremental](#incremental) |
| [`parser.incremental.SnapshotParseCache`](../unlaxer-common/src/main/java/org/unlaxer/parser/incremental/SnapshotParseCache.java#L14) | type | 対応あり・全API共通検証なし | [incremental](#incremental) |
| [`parser.incremental.SnapshotParseCache.Revisions`](../unlaxer-common/src/main/java/org/unlaxer/parser/incremental/SnapshotParseCache.java#L15) | type | 対応あり・全API共通検証なし | [incremental](#incremental) |
| [`parser.incremental.SnapshotParseCache.Stats`](../unlaxer-common/src/main/java/org/unlaxer/parser/incremental/SnapshotParseCache.java#L18) | type | 対応あり・全API共通検証なし | [incremental](#incremental) |
| [`parser.posix.AlphabetNumericParser`](../unlaxer-common/src/main/java/org/unlaxer/parser/posix/AlphabetNumericParser.java#L5) | type | 対応あり・全API共通検証なし | [character-recipes](#character-recipes) |
| [`parser.posix.AlphabetNumericUnderScoreParser`](../unlaxer-common/src/main/java/org/unlaxer/parser/posix/AlphabetNumericUnderScoreParser.java#L5) | type | 対応あり・全API共通検証なし | [character-recipes](#character-recipes) |
| [`parser.posix.AlphabetParser`](../unlaxer-common/src/main/java/org/unlaxer/parser/posix/AlphabetParser.java#L6) | type | 対応あり・全API共通検証なし | [character-recipes](#character-recipes) |
| [`parser.posix.AlphabetUnderScoreParser`](../unlaxer-common/src/main/java/org/unlaxer/parser/posix/AlphabetUnderScoreParser.java#L6) | type | 対応あり・全API共通検証なし | [character-recipes](#character-recipes) |
| [`parser.posix.AsciiParser`](../unlaxer-common/src/main/java/org/unlaxer/parser/posix/AsciiParser.java#L6) | type | 対応あり・全API共通検証なし | [character-recipes](#character-recipes) |
| [`parser.posix.BlankParser`](../unlaxer-common/src/main/java/org/unlaxer/parser/posix/BlankParser.java#L5) | type | 対応あり・全API共通検証なし | [character-recipes](#character-recipes) |
| [`parser.posix.ColonParser`](../unlaxer-common/src/main/java/org/unlaxer/parser/posix/ColonParser.java#L5) | type | 対応あり・全API共通検証なし | [character-recipes](#character-recipes) |
| [`parser.posix.CommaParser`](../unlaxer-common/src/main/java/org/unlaxer/parser/posix/CommaParser.java#L5) | type | 対応あり・全API共通検証なし | [character-recipes](#character-recipes) |
| [`parser.posix.ControlParser`](../unlaxer-common/src/main/java/org/unlaxer/parser/posix/ControlParser.java#L5) | type | 対応あり・全API共通検証なし | [character-recipes](#character-recipes) |
| [`parser.posix.DigitParser`](../unlaxer-common/src/main/java/org/unlaxer/parser/posix/DigitParser.java#L10) | type | 対応あり・全API共通検証なし | [character-recipes](#character-recipes) |
| [`parser.posix.DotParser`](../unlaxer-common/src/main/java/org/unlaxer/parser/posix/DotParser.java#L5) | type | 対応あり・全API共通検証なし | [character-recipes](#character-recipes) |
| [`parser.posix.GraphParser`](../unlaxer-common/src/main/java/org/unlaxer/parser/posix/GraphParser.java#L6) | type | 対応あり・全API共通検証なし | [character-recipes](#character-recipes) |
| [`parser.posix.HashParser`](../unlaxer-common/src/main/java/org/unlaxer/parser/posix/HashParser.java#L5) | type | 対応あり・全API共通検証なし | [character-recipes](#character-recipes) |
| [`parser.posix.LowerParser`](../unlaxer-common/src/main/java/org/unlaxer/parser/posix/LowerParser.java#L5) | type | 対応あり・全API共通検証なし | [character-recipes](#character-recipes) |
| [`parser.posix.PrintParser`](../unlaxer-common/src/main/java/org/unlaxer/parser/posix/PrintParser.java#L6) | type | 対応あり・全API共通検証なし | [character-recipes](#character-recipes) |
| [`parser.posix.PunctuationParser`](../unlaxer-common/src/main/java/org/unlaxer/parser/posix/PunctuationParser.java#L5) | type | 対応あり・全API共通検証なし | [character-recipes](#character-recipes) |
| [`parser.posix.SemiColonParser`](../unlaxer-common/src/main/java/org/unlaxer/parser/posix/SemiColonParser.java#L5) | type | 対応あり・全API共通検証なし | [character-recipes](#character-recipes) |
| [`parser.posix.SpaceParser`](../unlaxer-common/src/main/java/org/unlaxer/parser/posix/SpaceParser.java#L5) | type | 対応あり・全API共通検証なし | [character-recipes](#character-recipes) |
| [`parser.posix.UpperParser`](../unlaxer-common/src/main/java/org/unlaxer/parser/posix/UpperParser.java#L5) | type | 対応あり・全API共通検証なし | [character-recipes](#character-recipes) |
| [`parser.posix.WordParser`](../unlaxer-common/src/main/java/org/unlaxer/parser/posix/WordParser.java#L5) | type | 対応あり・全API共通検証なし | [character-recipes](#character-recipes) |
| [`parser.posix.XDigitParser`](../unlaxer-common/src/main/java/org/unlaxer/parser/posix/XDigitParser.java#L5) | type | 対応あり・全API共通検証なし | [character-recipes](#character-recipes) |
| [`parser.referencer.MatchedChoiceParser`](../unlaxer-common/src/main/java/org/unlaxer/parser/referencer/MatchedChoiceParser.java#L13) | type | 未対応（記載した動作） | [selected-choice](#selected-choice) |
| [`parser.referencer.MatchedNonOrderedParser`](../unlaxer-common/src/main/java/org/unlaxer/parser/referencer/MatchedNonOrderedParser.java#L16) | type | 未対応（記載した動作） | [selected-order](#selected-order) |
| [`parser.referencer.MatchedTokenParser`](../unlaxer-common/src/main/java/org/unlaxer/parser/referencer/MatchedTokenParser.java#L25) | type | 対応あり・全API共通検証なし | [historical-replay](#historical-replay) |
| [`parser.referencer.MatchedTokenParser.ScopeVariable`](../unlaxer-common/src/main/java/org/unlaxer/parser/referencer/MatchedTokenParser.java#L95) | type | 対応あり・全API共通検証なし | [historical-replay](#historical-replay) |
| [`parser.referencer.OldMatchedTokenParser`](../unlaxer-common/src/main/java/org/unlaxer/parser/referencer/OldMatchedTokenParser.java#L19) | type | 対応あり・全API共通検証なし | [historical-replay](#historical-replay) |
| [`parser.referencer.OldMatchedTokenParser.ScopeVariable`](../unlaxer-common/src/main/java/org/unlaxer/parser/referencer/OldMatchedTokenParser.java#L77) | type | 対応あり・全API共通検証なし | [historical-replay](#historical-replay) |
| [`parser.referencer.ReferenceByNameParser`](../unlaxer-common/src/main/java/org/unlaxer/parser/referencer/ReferenceByNameParser.java#L12) | type | 対応済み（記載した共通契約） | [reference-by-name](#reference-by-name) |
| [`parser.referencer.ReferenceParser`](../unlaxer-common/src/main/java/org/unlaxer/parser/referencer/ReferenceParser.java#L15) | type | 対応済み（記載した共通契約） | [reference-predicate](#reference-predicate) |
| [`parser.referencer.ReferenceParser#of(Name)`](../unlaxer-common/src/main/java/org/unlaxer/parser/referencer/ReferenceParser.java#L68) | static | 対応済み（記載した共通契約） | [reference-predicate](#reference-predicate) |
| [`parser.referencer.Referencer`](../unlaxer-common/src/main/java/org/unlaxer/parser/referencer/Referencer.java#L9) | type | 対応済み（記載した共通契約） | [reference-predicate](#reference-predicate) |
| [`parser.referencer.Referencer.AbstractReference`](../unlaxer-common/src/main/java/org/unlaxer/parser/referencer/Referencer.java#L14) | type | 対応済み（記載した共通契約） | [reference-predicate](#reference-predicate) |
| [`reducer.AbstractTokenReducer`](../unlaxer-common/src/main/java/org/unlaxer/reducer/AbstractTokenReducer.java#L15) | type | 言語固有（移植境界あり） | [meta-function](#meta-function) |
| [`reducer.CommittedReducer`](../unlaxer-common/src/main/java/org/unlaxer/reducer/CommittedReducer.java#L6) | type | 言語固有（移植境界あり） | [meta-function](#meta-function) |
| [`reducer.MetaFunctionTokenReducer`](../unlaxer-common/src/main/java/org/unlaxer/reducer/MetaFunctionTokenReducer.java#L8) | type | 言語固有（移植境界あり） | [meta-function](#meta-function) |
| [`reducer.TagBasedReducer`](../unlaxer-common/src/main/java/org/unlaxer/reducer/TagBasedReducer.java#L7) | type | 未対応（記載した動作） | [not-ast-node](#not-ast-node) |
| [`reducer.TagBasedReducer.NodeKind`](../unlaxer-common/src/main/java/org/unlaxer/reducer/TagBasedReducer.java#L10) | type | 未対応（記載した動作） | [not-ast-node](#not-ast-node) |
