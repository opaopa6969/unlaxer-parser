# Chain・name frame・値投影境界の owner reset (#809 / #814 / #820)

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
| Classic の rule 参照・透過 wrapper | 生成 `RootParser` の `parse(context, TokenKind.consumed, false)` → `LazyChain` / `ChainInterface.parse`、`DoConsume` / `InvertMatch` の `PropagationStopper` | `parse_with_token_mode(Expr::Rule)` と生成 rule body、`StopConsume` / `StopInvert` | 参照自体の frame はない。Chain が owner に残す false を通常経路と同じく伝える (#814) |
| 値投影の失敗 | helper の `LazyChain`、nested mixed 候補の `__CaptureSite extends LazyChain` と子の `LazyChain` | `ValueBoundary` / `TextValue` の子の reset を伝播 | helper の本体で直接観測。TextValue は生成候補を単独で呼び、外側 Choice の復元前に観測する (#820) |
| lookup と失敗後の透過一覧 | Chain の `begin` 前の owner reset 設定 | 共通の `java_owner_transparent_child` | 値投影2式と StopConsume / StopInvert を含む同じ一覧を辿る。初期 true/false を切り替えても同じ failure memo に hit する (#820) |
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

`owner-reset.tsv` は生成 Java の実行で確認した42行。名前用の4文法と既存の6文法を使い、
`NameScopeTokenModeResetConformanceTest` が memo OFF / SAFE_FAILURES × 診断3設定で比較する。
Java は252観測、Rust は通常 / mapping grammar × Java-host / native の1008観測。
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
`name-predicate-lookahead.ubnf`（`Root ::= Ahead 'a' @name`）は同じ検査を predicate の frame で行う。
`predicate-entry-*` は外側 scope の開始後に matched を CP1 へ進めてから predicate を呼ぶので、
scope の開始時復元に隠されない。true なら CP1/1 で成功し後続は CP1/2、false なら失敗する。

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
- 手組みの Classic `StopConsume` / `StopInvert` は Java の `DoConsumePropagationStopper` /
  `InvertMatchPropagationStopper` に当たり、`startParse` / `endParse` だけで `begin` しない。
  Java は consumed / matchOnly、初期 reset true / false、memo OFF / SAFE_FAILURES のどれでも
  失敗後 reset=false・CP0/1、後続は成功 CP0/2。旧 Rust は初期 true で reset=true に戻していたので、
  透過 wrapper に加えた。Java の `propagationStoppersKeepTheFailedChainPolicy` と Rust の
  `classic_propagation_stoppers_retain_failed_chain_policy` が同じ観測行を固定し、Rust は rule 参照経由の
  failure memo hit（2回）も確認する。生成文法はこの2式を出さないため、`owner-reset.tsv` には入れない。

### mutation

`java_transaction` から `NameResolutionScope` / `NamePredicate` を個別に外す、
Classic の透過条件に `self.token_mode.is_none() &&` を戻す、
name scope / name predicate の開始時 matched 復元を個別に外す、の5変異を個別に実行する。
どれも Rust の `name_scope_token_mode_reset` と生成両 host の conformance が失敗する。
透過 wrapper から `StopConsume` / `StopInvert` を外す変異は Rust の `name_scope_token_mode_reset` が検出する。
新しいテストクラスの3メソッドを Cross-language CI と `.github/optin-test-contracts.json` に登録する。

## 値投影境界の実測 (#820)

`value-reset.tsv` の56行は生成 Java を実行して採取した期待値。
`ValueBoundaryResetConformanceTest` は memo OFF / SAFE_FAILURES × 診断3設定で Java の336観測と、
Java-host / native × 通常 / mapping grammar の Rust の1344観測を照合する。
生成物の一致、生成ソースの `.value_boundary()` / `.text_value()`、観測する式の位置と子の型も検査する。
両言語の生成ソースと観測結果は `unlaxer-dsl/target/value-reset-{java,rust}` および同名の TSV に保存する。

### 生成位置と Java の構造

- `value-boundary.ubnf`: `Root` が `Helper @value` を mapping し、`Helper ::= 'a' 'z' Mixed`、
  `Mixed ::= Leaf | 't'`（Leaf は mapped node）とする。mixed の scalar `Kind::Value` によって
  `needs_values` が有効になり、`project_helper_value` が mapping のない Helper の本体を
  `ValueBoundary(Sequence(...))` にする。`Wrapped` は同じ本体に rule-local TriviaScope を加える。
  Java の両 parser は `ValueResetPlainLazyChain extends LazyChain`。`prepareChildren` は子を直接並べ、
  値投影のための外側 parser を追加しない。通常 / Classic で helper parser を直接呼ぶ。
- `text-value.ubnf`: `@nestedCaptures @mapping(Node) Root ::= 'a' 'z' | N`（N は NumberParser）。
  nested の `body_shape` は Text と Integer を Value にまとめ、`body` は第1候補を
  `TextValue(Sequence('a','z'))`、第2候補を `ValueBoundary(Number)` にする。
  Java は `RootParser extends LazyChoice` の候補として
  `__CaptureSite(ValueResetPlainLazyChain('a','z'), "#nested:text")` を生成する。
  `__CaptureSite` 自身も LazyChain で、子の Chain と同様に `begin` 前に owner を false にする。
  候補を `getChildren().get(0)` で取得して単独で呼ぶので、期待値は手組み Java からではない。
- `text-value-choice.ubnf`: `Root ::= ('a' 'z' | 'q') | N`。第1候補は子が Chain でない
  `TextValue(Choice(...))` で、Java は `__CaptureSite(LazyChoice)` を生成する。
  `__CaptureSite` は子が Choice でも Chain なので、候補を単独で呼ぶと Java は失敗後 reset=false、
  Rust は子の Choice が復元した値（初期 true なら true）を返す（PR #821 のレビューで実測）。
  生成位置は必ず外側 Choice の候補で、Choice は Java の frame として成否どちらでも終了時に入口の policy を復元するため、
  Root 全体の結果は両言語で一致する。この8行は Root 全体だけを照合し、候補単独の差は #823 で扱う。
  値投影（`@nestedCaptures` なし）の mixed 候補の TextValue には Java の parser がない（`MixedParser` の候補は `WordParser`）ため、
  Rust で TextValue を一律に Chain 扱いにすることはできない。

**TextValue の生成上の制約:** 現在の lowering は TextValue を mixed Choice の候補か Capture の子に置く。
`body` の単一選択肢が Text なら `mixed_nested=false`、Value なら候補の kind も Value なので
Text の腕へ入らない。複数選択肢では必ず外側に Choice がある。`project_text_values` も
Choice/Capture の内側だけに TextValue を加え、`project_helper_value` が本体に加えるのは ValueBoundary。
したがって **UBNF から TextValue だけを rule 本体に生成したとは扱わない**。
両 host の実際の生成候補を変更せず取り出し、単独の式および観測用 rule の本体として呼ぶ。
後者は memo lookup の検査用にテストが追加した rule であり、生成 rule ではない。
同時に元の Root 全体も対照として呼び、外側 Choice が policy を復元する一致を固定する。
生成仕様を変えて本体に TextValue を作る変更は行わない。

| 入力 ab・初期 reset=true | Java と修正後 Rust | 修正前 Rust |
| --- | --- | --- |
| Helper / Wrapped または生成 TextValue 候補の失敗 | reset=false、CP0/1。後続成功、CP0/2 | reset=true、CP0/1。後続失敗、CP0/1 |
| nested mixed の Root 全体の失敗 | reset=true、CP0/1。後続失敗、CP0/1 | 同じ |

CP は consumed / matched。各ケースは先行 LOOKAHEAD と後続 `NonOrdered(LOOKAHEAD('b'))` を同じ context で呼ぶ。
初期 reset=false、Unicode `a😀b`（先行 `LOOKAHEAD('a😀')`）、通常 / Classic、rule / body を含める。
helper と候補は3回の呼出しで初期 reset を true/false に切り替え、毎回同じ結果を検査する。
helper は両言語とも SAFE_FAILURES の failure hit が2回、生成候補の観測用 Rust rule も2回。
Java の候補は memoizable rule ではないため、その hit 数は比較しない。

### 透過一覧と mutation

`java_owner_transparent_child` を lookup 前と `finish_expression` で共有する。
MetadataCapture、TriviaScope、LexicalTriviaScope、LexicalContextScope、RuleEffects、CaptureEquality、
Commit、Label、StopConsume、StopInvert、TextValue、ValueBoundary が同じ一覧に入る。
MetadataCapture は expression の先頭で専用処理へ進むが、lookup では同じ子を辿る。
Rule 参照の失敗も子の policy を伝えるが、lookup の正規化は参照先へ再帰しない。
既存の rule 深さ・再帰上限・key を保ち、参照先自身の lookup で正規化するためである。
NamePredicate / NameResolutionScope / Choice は Java の frame 境界なので透過させない。

StopConsume / StopInvert は、同じ初期値だけの反復では旧実装も hit して差が隠れた。
既存の `classic_propagation_stoppers_retain_failed_chain_policy` を初期値が交互になるよう強化し、
consumed / matchOnly、rule / body の結果を保ったまま rule の failure hit が2回であることを固定する。
この正規化は生成仕様を広げず、既存の手組み runtime API の同一 Chain owner を揃える。

ValueBoundary / TextValue を透過一覧から個別に外す mutation は共通 corpus の Rust 単体と生成コードの照合で検出する。
lookup だけで ValueBoundary / TextValue / StopConsume / StopInvert の走査を個別に止める mutation は、
初期値を切り替えた3回の呼出しが hit 2回にならないことを検出する。
CI の Cross-language テストと `.github/optin-test-contracts.json` に新クラスの2メソッドを登録する。

性能は #197 の凍結に従う。変更は失敗の復元・再生、候補境界の bool 保存/失敗時復元、
SAFE_FAILURES の既存 lookup 正規化における透過 wrapper の確認、name frame の既存 policy 保存/復元に限定する。
#814 の成功経路は既存の `parent_policy` と `java_transaction` を再利用する scalar の保存/復元だけで、
entry field・cache・allocation は追加しない。
性能改善・JMH の再計測は行わない。公開済み3.3.0は変更しない。
