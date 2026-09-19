# Rust full-spec対応表

親issue: [#111](https://github.com/opaopa6969/unlaxer-parser/issues/111)。限定デモの完成とfull-specの完成を区別する。以下は初期の機能群別台帳であり、全メソッドを監査した完全互換宣言ではない。各群の実装前に受け入れcorpusを追加し、差を明記する。

## 基準と判定

- unlaxer-parser基準: `72de487020cd6321660dc7114f31ae008c629416`。構文・annotationの列挙元は`unlaxer-dsl/src/main/java/org/unlaxer/dsl/bootstrap/UBNFAST.java`、仕様案は`unlaxer-dsl/specs/`。文書と実装が異なる場合はテストで差を確認し、意図を記録する。
- tinyexpression基準: `6c8196b7879abe58d6b739d3e63a16e853745ced`。実言語仕様の棚卸し・バックエンド間の差の確定は未完了。
- 「生成済み」は現在の限定UBNFから生成・コンパイル・実行できる範囲。「runtimeのみ」は手書きAPIで使えるがUBNF経路は未対応。「未対応」は今後の作業で、対応済みとして数えない。
- 共通corpusは既存`evolution/conformance.json`の37入力×4段階、診断境界は`evolution/diagnostics.json`の8入力。full-spec用corpusは各行の拡張とともに追加する。現在の有限corpusは全言語の互換性証明ではない。

## 対応表

| 機能群 | 現状 | 追加の受け入れ条件 |
|---|---|---|
| Java UBNF frontend → Rust generator | 限定範囲で生成済み | 全構文・annotationのpositive/negative fixture、未対応の明示拒否、再生成一致 |
| 公開ParseContext・custom parser | runtimeと生成入口を実装 | 共有入力・Unicode位置・typed状態・CST/captureのrollback、先読み、生成parser混在を継続検証 |
| literal・参照・sequence・ordered choice・group | 生成済み | optional経由の再帰等も検証し、非消費ループを拒否 |
| optional・0/1回以上・bounded repeat・separated | UBNF生成・Option/Vec AST・mapper・Semantics実装 | 70ケースの受理一致、Rust AST/位置/値の独立oracle。Java capture不具合#116は未修正、入れ子container型は未対応 |
| ANY/EOF/EMPTY/CHAR_RANGE/NEGATION/UNTIL/LOOKAHEAD/NEGATIVE_LOOKAHEAD・error | runtimeのみ | UBNF接続とJava比較。Untilの終端欠損・空終端等は未比較で同値としない |
| Number token | 限定生成済み | 不完全指数の診断差を記録済み。数値型・overflow・triviaを実言語仕様に合わせる |
| Identifier・引用文字列・CASE_INSENSITIVE・REGEX・外部token | 未対応 | Unicode/escape/regex方言を確定。任意Java parserクラスはRust実装または明示adapterを要求 |
| imports・複数grammar・namespace・global/rule trivia・interleave | 一部のみ | 現在は単一grammarとglobal javaStyle/none。依存解決・循環・文法別ID・局所設定を検証 |
| mapping・capture・source-preserving AST | scalar/optional/list/group生成済み | Javaとのcapture意味論差#116、入れ子container型、再帰的unmapped rule、異種choice、typeof/commonField/enum |
| evaluator dispatch・網羅性 | 生成済み | 新nodeのE0004/E0046検証を拡張。eval annotation、型境界、短絡評価を追加 |
| leftAssoc/rightAssoc/precedence | 未対応 | ASTの結合方向・演算順・位置・不正文法をJavaと比較 |
| backref・MatchedToken相当 | context-wide replayのみ | UBNF annotation、名前の寿命・入れ子・伝播、コピー言語のpositive/negative test |
| PropagationStopper・consume/invert・virtual token・metadata | 未対応 | 有限状態の全合成検査、8元モデルとの対応、実parserとの統合試験 |
| scopeTree/declares/catalog/doc/skip/simple等 | 未対応 | 各annotationのJava実動作を確認し、生成metadataと利用先を検証 |
| recovery・incremental cache | 未対応 | 編集差分と全再解析の一致、位置・診断・利用者状態の無効化、回復後の評価境界 |
| LSP/DAP | 未対応 | UTF-16変換、diagnostics/completion、breakpoint/step/変数表示を実protocolで検証 |
| tinyexpression-rs | 未対応 | 値・null/欠損・変数・演算子・関数・外部呼出し・日時/数値仕様を棚卸しし、同一入力で値/失敗分類を比較 |
| rustcodeblock | 未対応 | 既定無効・明示許可付きAOT、元の位置へのcompiler診断、通常parse/LSPの非実行保証。Javaソースの自動翻訳はしない |
| ネイティブ配布 | 縮小文法CLIのみ | tinyexpression CLI、対象OS別artifact、stdin/file/終了コード・制限のsmoke |
| Rust製UBNF frontend・入力DSLの機械語生成 | 未対応・設計未確定 | 前者はgenerator自体のJVM不要化。後者は評価器バイナリと区別し、必要な意味論・成果物を別ADRで確定 |

## 完了の扱いと順序

公開context/combinator、optional/repeatとtyped ASTを基盤として、次はtoken/annotationとcapture互換性を拡げる。追加実験で見つけたJava numeric capture #115・capture選択 #116も未完了事項として追跡する。次に実tinyexpressionの仕様corpusと評価器、信頼されたrustcodeblock、IDE/debuggerを進める。各行を小さなPRに分け、テスト・CI・merge・子issue closeまで行う。全体issueは未対応行を残したままcloseしない。

Javaの継承階層を一対一に移植するのではなく、文法と観測可能な振る舞いを対象とする。JVM任意オブジェクト・reflection・bytecodeのnative直接実行はできないため、Rust側のhost interfaceと移植コードの境界を明記する。差を消して比較を通したことにせず、意図的な差は独立したfixtureにする。

parser生成は当面`Expr`combinator定義を生成し、共通runtimeで実行する。直接parser関数を出力する高速化backendは、その意味論との同値性を測定できてから検討する。Rustでビルドされた実行ファイルであることは、入力式を機械語にコンパイルしていることを意味しない。
