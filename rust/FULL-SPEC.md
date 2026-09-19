# Rust full-spec対応表

親issue: [#111](https://github.com/opaopa6969/unlaxer-parser/issues/111)。限定デモの完成とfull-specの完成を区別する。以下は初期の機能群別台帳であり、全メソッドを監査した完全互換宣言ではない。各群の実装前に受け入れcorpusを追加し、差を明記する。

## 基準と判定

- unlaxer-parser基準: `72de487020cd6321660dc7114f31ae008c629416`。構文・annotationの列挙元は`unlaxer-dsl/src/main/java/org/unlaxer/dsl/bootstrap/UBNFAST.java`、仕様案は`unlaxer-dsl/specs/`。文書と実装が異なる場合はテストで差を確認し、意図を記録する。
- tinyexpression基準: `6c8196b7879abe58d6b739d3e63a16e853745ced`。実言語仕様の棚卸し・バックエンド間の差の確定は未完了。
- 「生成済み」は現在の限定UBNFから生成・コンパイル・実行できる範囲。「runtimeのみ」は手書きAPIで使えるがUBNF経路は未対応。「未対応」は今後の作業で、対応済みとして数えない。
- 共通機能はJava/Rust双方で実装・検証する。片側だけの完了は共通機能の完了とせず、言語固有の表現差・制約を対応表とissueに残す。
- 共通corpusは既存`evolution/conformance.json`の37入力×4段階、診断境界は`evolution/diagnostics.json`の8入力。full-spec用corpusは各行の拡張とともに追加する。現在の有限corpusは全言語の互換性証明ではない。

## 対応表

| 機能群 | 現状 | 追加の受け入れ条件 |
|---|---|---|
| Java UBNF frontend → Rust generator | 限定範囲で生成済み。隣接参照の識別子境界 #131 修正 | 全構文・annotationのpositive/negative fixture、未対応の明示拒否、再生成一致 |
| 公開ParseContext・custom parser | runtimeと生成入口を実装 | 共有入力・Unicode位置・typed状態・CST/captureのrollback、先読み、生成parser混在を継続検証 |
| literal・参照・sequence・ordered choice・group | 生成済み | optional経由の再帰等も検証し、非消費ループを拒否 |
| optional・0/1回以上・bounded repeat・separated | UBNF生成・Option/Vec AST・mapper・Semantics実装 | 70ケースの受理一致、20成功ケースのJava/Rust AST全field・全span一致、Rust評価値oracle。外側captureの入れ子container型は両backendとも未完了 |
| ANY/EOF/EMPTY/CHAR_RANGE/NEGATION/UNTIL/LOOKAHEAD/NEGATIVE_LOOKAHEAD | UBNF生成とJava互換Expr・両cursorを実装 | 48文法・109入力でprefix受理/両cursorと全入力受理が一致、受理56入力のAST/spanも独立fixtureに一致。汎用consume/invert伝播と全CST同値は未完了 |
| error | runtimeのみ | UBNF接続・診断位置/候補と回復境界の比較 |
| Number token | 限定生成済み | 不完全指数の診断差を記録済み。数値型・overflow・triviaを実言語仕様に合わせる |
| Identifier・Single/DoubleQuoted・EndOfSource token binding | UBNF生成・runtime実装 | 22文法78入力の受理/両cursor比較と受理48 AST/spanのfixture。ASCII identifier、生escape、single quoteだけ除去するJava mapper契約。tinyexpression文字列評価は別途検証 |
| tinyexpression StringLiteral token binding | exact FQNを両生成経路で対応（#168） | 固定した実tinyexpressionクラスと共通corpusで字句・両cursor・AST/spanを比較。文字列評価の意味論は別途検証 |
| tinyexpression CodeStart/CodeEnd token binding | exact FQNを両生成経路で対応（#170）。内部triviaなしの原子的字句 | 実tinyexpressionクラスと共通corpusで行頭/行末・両cursor・AST/spanを比較。codeblockの実行やJavaのparser tag/CST構造同値は含まない |
| CASE_INSENSITIVE・REGEX・任意外部token | 未対応（上記の明示bindingを除く） | Unicode/regex方言を確定。任意Java parserクラスはRust実装または明示adapterを要求 |
| global/rule whitespace・interleave | javaStyle/none と interleave 両profileを両生成経路で対応（#172） | 親子の独立設定・明示override・連接/choice/量指定子の境界を共通corpusで比較。任意triviaとglobal comment設定は未対応 |
| imports・複数grammar・namespace | 未対応 | 現在は単一grammar。依存解決・循環・文法別IDを検証 |
| mapping・capture・source-preserving AST | scalar/optional/list/groupと混在Text/Node値を生成。Rustはspan付きAstValue、JavaはObject系。shared mappingの型joinは宣言順非依存。単一capture内の複数semantic子とhelper内部optional/repeatのcardinality・全値収集を両言語で実装（#160）。純mapped aliasもNodeを保持し、直接/多段/group/delimiter・複数targetの値と位置を両backendで比較（#163）。Java位置binding #116・zero-field生成 #129・複合text capture #132・混在値 #156を修正 | 入れ子container型、再帰的unmapped rule、typeof/commonField/enum、全Java capture規則との互換性 |
| evaluator dispatch・網羅性 | 限定範囲で生成済み | 新nodeのE0004/E0046検証を拡張。eval annotation、型境界、短絡評価を追加。Java sum/dotted evaluatorの不具合 #130 は修正済み |
| leftAssoc/rightAssoc/precedence | canonical leftAssocとrightAssoc、precedence metadata、schemaを統合したshared mapping、混在factorを生成 | 左辺＋op/right列と右再帰、文法階層による優先順位を検証。非canonical右結合形、Javaの特殊null/literal leafとRust AstValueの構造互換は未完了。Java raw CST反復欠落 #138・右結合 #139 は独立修正 |
| backref・MatchedToken相当 | context-wide replayとscope付き文法の参照検証を区別して対応 | scopeなしUBNF backref、名前の寿命・入れ子・伝播、コピー言語のpositive/negative test |
| PropagationStopper・consume/invert・virtual token・metadata | 未対応 | 有限状態の全合成検査、8元モデルとの対応、実parserとの統合試験 |
| lexical scope store・宣言/参照/semantic diagnostics | Java rollback修正とRust公開ParseContextへのtransactional store接続（#174）、owned Treeへのsnapshot | scope depth/lookup/shadowing/イベント履歴/失敗時復元を共通operation corpusで比較。AST/IDEへのmetadata搬送は未完了 |
| scopeTree/declares/スコープ参照 | 両frontendから生成、Java capture-site選択も修正（#176）。mode/description metadata保持、CP位置、nested/repeated captureとrollbackを比較 | 両modeは解析時stack。評価時dynamic環境やclosure、LSP/DAP利用は未対応 |
| catalog/doc/skip/simple等 | Rust生成annotation未対応 | 各annotationのJava実動作を確認し、生成metadataと利用先を検証 |
| recovery・incremental cache | 未対応 | 編集差分と全再解析の一致、位置・診断・利用者状態の無効化、回復後の評価境界 |
| LSP/DAP | 未対応 | UTF-16変換、diagnostics/completion、breakpoint/step/変数表示を実protocolで検証 |
| tinyexpression-rs | 未対応 | 値・null/欠損・変数・演算子・関数・外部呼出し・日時/数値仕様を棚卸しし、同一入力で値/失敗分類を比較 |
| rustcodeblock | 未対応 | 既定無効・明示許可付きAOT、元の位置へのcompiler診断、通常parse/LSPの非実行保証。Javaソースの自動翻訳はしない |
| ネイティブ配布 | 縮小文法CLIのみ | tinyexpression CLI、対象OS別artifact、stdin/file/終了コード・制限のsmoke |
| Rust製UBNF frontend・native generator | syntax frontend全18annotation/11token/9element種、対応範囲のlowering/CLI/5module emitterを実装 | Java/nativeの既存・混在値文法で全5file一致、空PATHの生成/check、手書き/symlink保護。全backend機能の生成完了とは区別。frontend既知差と構造分析上限を文書化 |
| 入力DSLの機械語生成 | 未対応・設計未確定 | generatorや評価器のnativeバイナリ化と区別し、必要な意味論・成果物を別ADRで確定 |

## 完了の扱いと順序

公開context/combinator、optional/repeatとtyped ASTを基盤として、次はtoken/annotationとcapture互換性を拡げる。追加実験で見つけたJava numeric capture #115とcapture選択 #116の生成コードは修正した。int変換とRustの字句保持の差は共有corpusで固定し、数値意味論のbackend間統一と外側captureの入れ子container型は未完了事項とする。次に実tinyexpressionの仕様corpusと評価器、信頼されたrustcodeblock、IDE/debuggerを進める。各行を小さなPRに分け、テスト・CI・merge・子issue closeまで行う。全体issueは未対応行を残したままcloseしない。

Javaの継承階層を一対一に移植するのではなく、文法と観測可能な振る舞いを対象とする。JVM任意オブジェクト・reflection・bytecodeのnative直接実行はできないため、Rust側のhost interfaceと移植コードの境界を明記する。差を消して比較を通したことにせず、意図的な差は独立したfixtureにする。

純mapped aliasの型移行（#163）は[API変更と利用側の移行文書](../docs/pure-mapped-alias-migration.md)を参照。
Javaでは従来のStringからObject系のNode保持へ変わるため、文字列依存の利用者は更新が必要。
先行する#165はJavaのpreferred選択Tokenとsource snapshotを原子的に取得する追加APIで、
Rustでは既存のowned AST/spanの後続・並行mapping耐性を検証する。
tinyexpressionはowned source resolverで従来のslice字句処理を維持する。
Javaのpreferred型候補探索自体のRust移植は未対応。

tinyexpressionのStringLiteral対応（#168）は[実クラスとの比較・字句契約](../docs/tiny-string-token.md)を参照。
これは既存FQN bindingの移行であり、汎用token adapter契約 #158 の完了ではない。
CodeStart/CodeEnd対応（#170）の[行境界・字句契約と実行機能との区別](../docs/tiny-code-fence.md)も参照。
rule-level trivia（#172）の[契約と Java global none の移行](../docs/rule-trivia.md)も参照。
transactional scope store（#174）の[rollback契約とruntime API](../docs/transactional-scopes.md)も参照。
生成scope annotation（#176）の[capture・metadata契約とJava移行](../docs/generated-scope-effects.md)も参照。
同名nested/並列captureのAST型・全出現収集の修正（#177）は
[cardinality契約とJava API移行](../docs/nested-capture-migration.md)を参照。
共存するcaptureはlist、排他的choiceはscalar、欠損枝はoptionalとして両言語で比較する。

parser生成は当面`Expr`combinator定義を生成し、共通runtimeで実行する。直接parser関数を出力する高速化backendは、その意味論との同値性を測定できてから検討する。Rustでビルドされた実行ファイルであることは、入力式を機械語にコンパイルしていることを意味しない。

移植に伴う追加提案は、両言語を対象とする受け入れ条件付きで追跡する。未実装の計画であり、上記の対応済み件数には含めない。

- [#157](https://github.com/opaopa6969/unlaxer-parser/issues/157): portability検査と機械可読な診断レポート。
- [#158](https://github.com/opaopa6969/unlaxer-parser/issues/158): target-neutralなtoken adapter契約。
- [#159](https://github.com/opaopa6969/unlaxer-parser/issues/159): 文法進化に伴う生成API影響レポート。
