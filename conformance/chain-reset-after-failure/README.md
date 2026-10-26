# Chain と name frame の owner reset (#809 / #814)

Java を参照実装とする。`grammar.ubnf` の生成 Root (`'a' 'z'`) は通常の consumed 経路で失敗する。
同じ context を再利用すると、Java は owner の `resetMatchedWithConsumed=false` を残す。
従来の Rust は呼び出し前の値へ戻すため、次の parser の結果も変わった。

`ab` に `LOOKAHEAD('a')` → Root の失敗 → `NonOrdered(LOOKAHEAD('b'))` を実行すると、
Java は成功、consumed CP0 / matched CP2、旧 Rust は失敗、CP0 / CP1。
この差は memo OFF と SAFE_FAILURES の両方にある。続けて `LOOKAHEAD('x')` を試すケースでは、
最遠失敗位置も Java CP2 / 旧 Rust CP1 になる。`a😀b` は UTF-8 byte と CP の混同を防ぐ対照。

## 読み取り経路

| 経路 | Java | Rust | 失敗後の値の影響 |
| --- | --- | --- | --- |
| 子の transaction 開始 | `Transaction.begin` → `TransactionElement.createNew` → `ParserCursor(..., reset)` | `expression` の `java_transaction`、`token.rs` の `classic_longest_choice` | true なら matched を consumed へ戻す。NonOrdered / MatchOnly / InvertMatch は通常の呼出からも到達する |
| LOOKAHEAD | `MatchOnly` → `parseMatchOnly` → `WordParser` | `JavaLookahead` / `MatchOnly` | transaction が保持した matched から照合し、成功時に matched を進める |
| NEGATIVE_LOOKAHEAD | `Not` の transaction と matchOnly の子 | `JavaLookahead { positive: false }` | matched の位置で成功/失敗が変わる |
| UNTIL | `WildCardStringTerminatorParser` と terminator の MatchOnly | `JavaUntil` | matched 上の終端検出が consumed の進み方を決める。Java の引数なし `parse(context)` は自身でも false を設定するが、NonOrdered から呼ぶ三引数 overload はその経路を通らない |
| EMPTY | `EmptyParser` → Optional / MatchOnly / WildCardCharacterParser | `JavaEmpty` | matched を最大1 CP進める位置が変わる |
| memo key / replay | `PackratMemoTable.positionKeyOf` が owner reset を含む。Chain は lookup 前に false | `FailureMemoKey.with_match_policy`、`memoized_rule`、`probe_rule_memo` | Chain の key 正規化は #794 のまま。失敗 miss と hit の後にも false が必要。Chain 以外の rule の reset key は区別する |
| 診断位置 | `startParse` の frame 開始、子の失敗と `endParse` | `frame_start` / `frontier` / `fail_rule_frame` / `fail_at` | reset を直接読むのでなく、上記で決まる matched と consumed を読む。先読みが成功するかと、その後の失敗位置・候補が変わる |
| owner の破棄・復元 | Choice / NonOrdered の候補 rollback、明示 transaction rollback、`ParseContext` の例外/資源中断時 publication 復元 | candidate の reset 復元、`restore` / `transaction`、resource failure | Chain の変更はその owner だけ。候補や外側の明示 transaction が rollback すれば元の値に戻る |
| name frame の開始・終了 | `NameResolutionScope` / `NamePredicateParser` は `begin` → `commit` / `rollback` | 両式を `java_transaction` として扱う | 開始時に true なら matched を consumed へ戻す。子の Chain が false にしても成否どちらでも親の reset を復元する (#814) |
| Classic の rule 参照・透過 wrapper | 生成 `RootParser` の `parse(context, TokenKind.consumed, false)` → `LazyChain` / `ChainInterface.parse` | `parse_with_token_mode(Expr::Rule)` と生成 rule body | 参照自体の frame はない。Chain が owner に残す false を通常経路と同じく伝える (#814) |
| 公開 getter | `TransactionElement.isResetMatchedWithConsumed` | `ParseContext.reset_matched_with_consumed` | 同じ context を使う caller も値を観測できる |

通常の Rust は全ての Java frame を作るわけではなく、直接の `JavaLookahead` / `JavaEmpty` では
reset を参照しない最適化経路も持つ。したがって corpus では、両言語で owner reset を読む
NonOrdered の transaction を明示し、その内側で4種の matched reader を比較する。
値が異なるだけで「観測不能」とは結論しない。

## 修正と検証

- Chain 相当の式（Sequence / Number / Identifier / Quoted / CodeStart / CodeEnd / Capture /
  CaptureBoundary / InputCaptureBoundary）は通常の構文失敗後にも false を残す。
  通常 / Classic の rule 参照は Java の parser 呼出を覆う新しい transaction ではないため、その policy を伝える。
  MetadataCapture、rule ごとの TriviaScope / LexicalTriviaScope / LexicalContextScope、
  rule 単位の `@scopeTree` / `@declares`（RuleEffects）と `@backref`（CaptureEquality）、
  Java の `Commit` / `Label` に当たる COMMIT / LABEL も透過する。どれも子を owner の transaction の
  まま呼び、Java の parser owner を追加しないため、lookup 前の Chain 正規化でも同じように透過する。
  rule 単位の NamePredicate / NameResolutionScope は Java でも `begin` する（frame を持つ）ので透過対象外。
  #814 で成功時も親の policy を復元し、開始時の matched の復元も Java と揃える。
- failure memo は既存の終端 cursor/policy payload を通常経路でも保存・再生する。
  新しい entry field・cache・allocation は足さない。alias rule の実 hit も Rust 単体テストで確認する。
- Choice / NonOrdered / CatchLabels の失敗候補は owner の reset を戻す。
  resource failure と明示 `transaction` は全 rollback のまま。
  `ParseContext::parse` は明示的な transaction を持つ公開 API であり、その復元契約は変更しない。
  Java と比較する入口は生成 grammar の `parse_shared_grammar` と parser の直接呼出。
- `cases.tsv` は10行。両 memo × 3診断設定 × 初期 reset true/false で、成否、両 cursor、最遠失敗 CP、
  reset、診断ケースの候補 `x` を比較する。DETAILED_ON_FAILURE の低水準入口は診断 CP0 のまま。
- `ChainResetAfterFailureConformanceTest` は生成 Java と両 host の生成 Rust を compile/実行する。
  通常・rule-local 空白・名前付き空白・lexical context・`@scopeTree`/`@declares`・`@backref` の6文法で、
  Rust は通常 grammar と mapping grammar の両方を使う。生成 Root を同一 session で3回呼び、初期 reset が
  true/false のどちらでも false と後続 parser の成功を保つことを両言語で確認する。
  通常・rule-local 空白・名前付き空白・`@scopeTree`/`@declares` は両言語で2回の failure hit。lexical context の Java は
  `Lexing.withContext` が入退場ごとに state epoch を更新するので0回、Rust は2回となる。
  `@backref` は Rust の CaptureEquality が memo 対象外なので Java 2回・Rust 0回となる。
  この2つは既存の会計差として明示し、成否・cursor・診断・reset の期待値は共通に保つ。
  Choice / NonOrdered 候補の隔離と外側 rollback も
  両言語で比較する。手組みの `Commit(Chain)` / `Label(Chain)` の失敗後に false が残り後続先読みが成功することも
  両言語で確認する（`raisingWrappersKeepTheFailedChainPolicy` と `raising_wrappers_keep_the_failed_chain_policy`）。
  Rust workspace の integration test も同じ oracle を読む。
- 既存の `memoized_rule_leaves_the_unmemoized_cursor_policy` は Sequence / Number / Identifier の
  成功・失敗、通常/Classic、true/false の key 合流と実 hit を検証し、失敗後 false を明示的に確認する。
  `native_failure_memo_replays_policy_through_rule_references` は alias の実 failure hit と policy を比較する。

## name frame と Classic の実測 (#814)

`owner-reset.tsv` は生成 Java の実行で確認した38行。名前用の3文法と既存の6文法を使い、
`NameScopeTokenModeResetConformanceTest` が memo OFF / SAFE_FAILURES × 診断3設定で比較する。
Java は228観測、Rust は通常 / mapping grammar × Java-host / native の912観測。
両 host の生成物は一致を確認してから、それぞれを compile・実行する。
失敗ケースは同じ session で3回呼び、毎回同じ結果・reset・cursor であることも検査する。
Classic の通常 Chain rule では SAFE_FAILURES の実 failure hit 2回を両言語で確認する。

| 境界（初期 reset=true） | Java | 修正前の両 Rust host | 修正後 |
| --- | --- | --- | --- |
| scope / predicate の成功（`aab`、`'a' LOOKAHEAD('a')`） | reset=true、CP1/2。後続先読みは失敗 CP1/2 | reset=false、CP1/2。後続は成功 CP1/3 | Java と一致 |
| scope / predicate の構文失敗（`ab`） | reset=true、CP0/1。後続は失敗 CP0/1 | 同じ | 一致を維持 |
| Classic rule 参照の失敗（`ab`、`'a' 'z'`） | reset=false、CP0/1。後続は成功 CP0/2 | reset=true、CP0/1。後続は失敗 CP0/1 | Java と一致 |
| Classic の透過 wrapper body の失敗 | reset=false、CP0/1。後続は成功 CP0/2 | reset=true、CP0/1。後続は失敗 CP0/1 | Java と一致 |

CP は consumed / matched。各ケースは最初に `LOOKAHEAD('a')` を実行し、
最後に同じ context の `NonOrdered(LOOKAHEAD('b'))` を呼ぶ。初期 reset=false、
既知の value 名による predicate 拒否、scope と predicate の二重 frame も共通期待値で固定する。
`name-scope-lookahead.ubnf` は consuming leaf に隠されない frame 開始の検査で、
`Root ::= Ahead` を初期 reset=true で呼ぶと、開始時に matched が CP1 → CP0 へ戻り、
`LOOKAHEAD('a')` が成功する。false なら CP1 から読んで失敗する。

### 実際の生成位置と参照先

- `name-scope.ubnf`: `getRootParser()` は `NameResolutionScope(RootParser)`。
  predicate のある未使用 rule が grammar の snapshot 要件を有効にするので、root の scope だけを観測できる。
- `name-predicate.ubnf`: `getRootParser()` は
  `NameResolutionScope(RootNamePredicateParser(RootParser))`。
  `RootParser` は `NameResetPlainLazyChain extends LazyChain` で、両 wrapper は Chain の外側。
  root 全体に加え、生成 `RootNamePredicateParser` を単独で観測する。
  Java は `NameResolution.begin/end` で名前環境だけを用意し、Rust は生成 scope の子に観測 callback を置く。
  どちらも predicate の結果・reset・後続先読みを外側 scope の終了**前**に読むため、
  scope の復元が predicate の不具合を隠さない。実際の snapshot API
  (`ParseContext.withNameSnapshots` / `with_name_snapshots`) に同じ `names@v1` を渡す。
- Classic の `Expr::Rule(0)` は生成 Java の `RootParser.parse(context, consumed, false)` に対応する。
  通常 / local-trivia は `ChainResetPlainLazyChain`、named-trivia は `ChainResetGAPLazyChain`。
  いずれも `LazyChain` → `ChainInterface.parse` が自分の `begin` **前**に owner の reset を false にする。
  lexical-context の override は `Lexing.withContext(..., () -> super.parse(...))`、
  `@scopeTree` / `@declares` / `@backref` は同じ Chain の transaction listener であり、外側の frame を足さない。
  corpus の `classic-rule` は実 rule 参照、`classic-body` は生成された式そのものを呼び、
  `Expr::Rule` の伝播に隠されない透過 wrapper の伝播も検査する。
  元から直接の Sequence は一致し、透過 wrapper の条件 `self.token_mode.is_none() &&` が差を生んでいた。

### mutation

`java_transaction` から `NameResolutionScope` / `NamePredicate` を個別に外す、
Classic の透過条件に `self.token_mode.is_none() &&` を戻す、
name scope の開始時 matched 復元を外す、の4変異を個別に実行する。
どれも Rust の `name_scope_token_mode_reset` と生成両 host の conformance が失敗する。
新しいテストクラスの2メソッドを Cross-language CI と `.github/optin-test-contracts.json` に登録する。

性能は #197 の凍結に従う。変更は失敗の復元・再生、候補境界の bool 保存/失敗時復元、
SAFE_FAILURES の既存 lookup 正規化における透過 wrapper の確認、name frame の既存 policy 保存/復元に限定する。
#814 の成功経路は既存の `parent_policy` と `java_transaction` を再利用する scalar の保存/復元だけで、
entry field・cache・allocation は追加しない。
性能改善・JMH の再計測は行わない。公開済み3.3.0は変更しない。
