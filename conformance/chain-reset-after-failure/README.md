# Chain・frame 開始・name frame・値投影境界の owner reset (#809 / #814 / #820 / #829)

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
| 子の transaction 開始 | `Transaction.begin` → `TransactionElement.createNew` → `ParserCursor(..., reset)` | `expression` の `java_transaction`、`token.rs` の `classic_longest_choice` | true なら matched を consumed へ戻す。#829 で通常の呼出も全対応 frame に適用する |
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

#829 で直接の `JavaLookahead` / `JavaEmpty` を含む通常の frame 開始も Java に揃えた。
既存の Chain 失敗 corpus は NonOrdered 内の4種の matched reader を使い、
新しい frame-start corpus は先読み後の通常の呼出を直接比較する。

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
  Root 全体の結果は両言語で一致する。この8行は Root 全体だけを照合し、候補単独の差は下記 #823 の調査結果に記録する。
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

#809 / #814 / #820 の性能方針は #197 の凍結に従う。変更は失敗の復元・再生、候補境界の bool 保存/失敗時復元、
SAFE_FAILURES の既存 lookup 正規化における透過 wrapper の確認、name frame の既存 policy 保存/復元に限定する。
#814 の成功経路は既存の `parent_policy` と `java_transaction` を再利用する scalar の保存/復元だけで、
entry field・cache・allocation は追加しない。
性能改善・JMH の再計測は行わない。公開済み3.3.0は変更しない。

## nested mixed 候補と外部経路の調査 (#823)

**結論:** 候補単独の owner reset 差は既知の内部差として残す。今回調べた生成文法の外部経路では、
その候補に Java 相当の Chain 境界を加えても観測は変わらなかった。PR #828 では runtime / IR / 公開型を変更しなかった。
ただし、**Java / Rust の診断位置と memo hit がすべて一致したという意味ではない**。
調査で見つかった診断差は #826 で解消し、残る memo 会計差は #827 で host 依存の内部会計という契約にした（末尾参照）。

### lowering と frame の根拠

- `rust/unlaxer-generator/src/lowering.rs` の `body()` は、nested rule かつ
  `nested_captures::body_shape == Kind::Value` のときだけ mixed 候補を包む。
  Text は `TextValue`、Integer は `ValueBoundary`、それ以外はそのまま。
  **候補数が2以上なら必ず Choice を残す。** Group は `atomic()` から同じ `body()` に戻る。
- 候補が1つなら Choice を省く分岐自体はある。しかし1要素の候補が Text / Integer なら body の型も
  同じで `mixed_nested=false`。Value なら候補も Value で wrapper の腕へ入らない。
  複数要素の単一候補は `body_shape_in()` により Text（node/container/value を含めると拒否）なので
  やはり mixed にならない。`Single` / `SingleInteger` は単一候補の括弧を重ねた実生成例で、
  `Sequence([Choice(...)])` となり、内側の mixed Choice が残ることを両言語で検査する。
- `@longestChoice` / `@uniqueLongestChoice` / `@predictiveChoice` の変換も候補リストを保持する Choice 系
  expression への置換であり、候補を単独で外へ出さない。これらも runtime の `java_transaction` 対象。
  生成 Java も `LazyLongestChoice` / `LazyUniqueLongestChoice` / `LazyPredictiveChoice` の候補を
  `__CaptureSite` にする。この3 profile は `Longest` / `Unique` / `Predictive` として実行掃引にも含める（#828 レビュー）。
- `project_text_values()` は Choice 系の候補か Capture の子に値投影 wrapper を加える。
  `project_helper_value()` が追加する本体直下の ValueBoundary は値投影であり、nested 候補境界とは別物。
  Java の `CaptureBindingPlan.markNestedMixed()` も Choice の Text / Integer 候補へ binding を付ける。
  `ParserRuleEmitter` はそれを `__CaptureSite extends LazyChain` にする。
- Java `ChoiceInterface.parse()` は候補ごとに `begin` し、成功時 commit、失敗時 rollback で owner に戻る。
  Chain が変更した reset は候補の owner に閉じる。Rust の Choice 系は候補失敗時と終了時の復元を持つ。
  rule 参照や外側 Optional / Repeat / NonOrdered / MatchOnly はこの内側 Choice を取り除かない。
- 候補の中で Rust が owner reset を読むのは、(1) Java に対応する frame の開始（#829 で通常の呼出も含む）、
  (2) rule の memo key、(3) 失敗の owner への伝播である。
  (1) が matched を巻き戻すのは matched が consumed より先にあるときだけである。corpus の経路では、
  外側の Choice 系（Classic では候補ごと）か NonOrdered / MatchOnly の開始がすでに巻き戻しており、
  内側 Choice の候補は失敗ごとに cursor を戻す。手組みの NonOrdered で先読みの兄弟の後に呼ぶ経路などは対象外。
  (3) は外側の Choice 系が成否どちらでも入口の policy に戻す。(2) について、alias でない helper rule を
  候補に含む `(Helper | 'q') | N` 形は mixed と判定されず、両言語とも binding を付けない（`__CaptureSite` / TextValue のどちらもない）。
  これは構造上の説明であり、実行で確かめた範囲は下記の corpus に限る。
- 先読みを候補の中に持つ `Ahead`（`(B | 'q') | N`）と `AheadToken`（`B | N`、`B = LOOKAHEAD('b')`）も実行する。
  Java の候補はそれぞれ `__CaptureSite(LazyChoice)` / `__CaptureSite(MatchOnly)`。通常の呼出で先読みの後に
  直接呼ぶ経路は nested と無関係な frame 開始時の matched 巻き戻しの差（#829。`@nestedCaptures` なしでも出て、
  候補の Chain 対照でも変わらない）に当たるため、corpus では NonOrdered / MatchOnly の中から呼ぶ行と
  先読みなしで直接呼ぶ行に分ける。この除外経路は #829 の `frame-start-*` corpus に追加した。

### 実行した外部経路と oracle

`nested-boundary.ubnf` の Root は `('a' 'z' | 'q') | N`、Integer は `(N | N) | 't'`。
両方の第1候補の子は Chain でない Choice で、Integer 側は実際に `Kind::Integer` の ValueBoundary になる。
Java の対応候補はどちらも `__CaptureSite(LazyChoice)`。ソースの文字列だけでなく、生成 parser の型も確認する。
`Ahead` / `AheadToken` / `Longest` / `Unique` / `Predictive` も、生成 Java の parser 型と候補の `__CaptureSite`、
Rust の候補が Choice 系の直下にあることを検査する。

`nested-boundary-inputs.tsv` の75行を、通常 / Classic × 初期 reset false/true × memo OFF/SAFE_FAILURES
× 診断3設定で実行する。失敗は同じ context で3回呼び、毎回の成否・cursor・reset の不変を確認する。
成功は消費位置を巻き戻さず1回で観測するため、成功 memo hit の強制再生は対象外。
生成 Java の実測1800行が `nested-boundary.tsv`。Rust は Java-host / native × 通常 / mapping grammar の
7200行を検証し、さらに各生成グラフの Chain 対照実験7200行も照合する。

- Root 全体（Java の `getRootParser()`）、Integer 全体、生成 alias rule。
- 生成 Optional / Repeat の中から mixed rule を参照する経路。
- 生成 mixed rule を外側の runtime NonOrdered / MatchOnly（LOOKAHEAD）/ Choice へ渡す経路。
  NonOrdered と任意 rule の MatchOnly には対応する UBNF 構文がないため、ここだけ外側を手組みする。
  候補を本体へ移した合成 rule は使わない。Rust の Custom は同じ grammar session を保持する観測 driver。
- 単一候補の group、空入力、Unicode `a😀b`、数値・text 成功、途中失敗。
- 候補の中の先読み（`Ahead` / `AheadToken`）と3つの Choice profile（`Longest` / `Unique` / `Predictive`）。
- 同じ context の後続 `NonOrdered(LOOKAHEAD('b'))` と失敗する `NonOrdered(LOOKAHEAD('x'))`。
  成否、consumed/matched CP、reset、最遠診断 CP、failure/success memo hit を採取する。

`NestedCandidateBoundaryConformanceTest` は両 host の生成物全ファイルの一致も確認して個別に compile/実行する。
生成ソースと観測 TSV は `unlaxer-dsl/target/nested-boundary-*` に残る。
CI と `.github/optin-test-contracts.json` に2メソッドを登録している。

### 対照実験と内部差

テスト専用 `report_control()` は9つの nested rule にある18の候補境界について、
`TextValue(child)` / `ValueBoundary(child)` の child を `Sequence([child])` にする。
Java の Chain の owner reset 動作を与えるだけの対照であり、CST / mapper の変更案ではない。
候補が Choice 直下にあることと変換個数を assert し、元の生成グラフを別に実行する。
外部観測は変換の前後で全項目一致する（下記の既存差も変わらない）。

対照実験が空振りでないことは、生成候補を単独で呼ぶ検査で確かめる。
Root / Integer の第1候補、`ab`、先行 `LOOKAHEAD('a')`、初期 reset=true のとき、
通常 / Classic × memo 2設定 × 診断3設定で次の結果になる。

| 観測 | 生成 Java | 生成 Rust | Chain 対照の Rust |
| --- | --- | --- | --- |
| 候補単独の失敗後 | reset=false、CP0/1 | reset=true、CP0/1 | reset=false、CP0/1 |
| 後続 LOOKAHEAD(b) | 成功、CP0/2 | 失敗、CP0/1 | 成功、CP0/2 |

外側の復元に対する負の対照も、隔離した runtime コピーで実行した。
`java_transaction` から Choice を外す mutation は `Root-success`（通常・初期 true）で
外部観測の reset が true → false となり、共通 oracle との比較が失敗する。
同様に `java_transaction` から LongestChoice / UniqueLongestChoice / PredictiveChoice を外す mutation は
`Longest-success` / `Unique-success` / `Predictive-success` の外部 reset を変え、追加前の40行では検出できない（#828 レビュー）。
元の runtime は変更していない。nested の Chain 付与は挙動修正として採用していないため、
その付与を外して外部 corpus が落ちるとは主張しない。

この内部差を `nested-candidate-internal-reset` として parity 台帳に残す。
候補単独の一致や任意の手組み IR での一致は主張しない。
値投影 TextValue の一律 Chain 化は Java に対応 parser がない経路を変えるため採用しない。

### 別に見つかった診断・memo 差

`nested-boundary-differences.tsv` は #826 時点で232行（#838 で456→288行、#826 で288→232行、#827 の追加後は268行）の**全行キー付きの明示差分**であり、
Java oracle の該当値も照合してから Rust の値に適用する。#827 では診断2列の同値を必須にし、host 差を failure / success hit の2列だけに限定した。
他の列や行を除外しない。
通常 / mapping、両 host、Chain 対照に同じ値を要求する。PBT の known-differences は変更しない。

- **#826 診断位置（修正済み）:** #838 後の残り56行はすべて通常呼出の成功時の到達位置で、
  正の先読みも診断 fallback を開始するようにして解消した。後続診断と失敗時の診断は #838 の値を維持する。
  `Integer-nonordered-failure` の初期 false の CP1/0 は #838 後には既に一致している。
  `Ahead-nonordered-failure` / `AheadToken-nonordered-failure` は名前に failure を含むが、初期 false では
  先行先読みの次の `b` に成功する行（CP0/2）であり、この成功時診断も CP2 に揃えた。
  全1800行の診断2列は共通の Java oracle と一致し、残る232行は #827 の memo 会計差だけである。
  DETAILED_ON_FAILURE の低水準入口は双方 CP0 のまま。実装・受け入れ範囲は後述の #826 を参照する。
- **#827 memo 会計:** Integer などの生成 Java rule/group/__CaptureSite は安全 marker がなく、
  failure hit 0。一方 Rust は rule 全体を cache し、3回呼出で2 hit するケースがある。
  Root の text group は Java でも安全 marker を持つので、多くの経路で2 hit となるが、
  空入力や MatchOnly 経由では0/2が残る。cache の単位まで同じとは主張しない。
  `Longest` / `Unique` の text・途中失敗の経路では、1回の呼出で Java が success hit 1、Rust が0になる。

これらは候補への Chain 追加で変わらないため、#823 の owner reset 修正で解決したことにしない。
PR #828 は調査・回帰検証・台帳のみで挙動を変えていない。後続 #829 の変更と検証は以下に記録する。

## 通常の frame 開始 (#829)

Java を参照実装とし、公開 API で先読みした後に同一 context から rule を呼ぶ契約を揃える。
`Transaction.begin` は `TransactionElement.createNew` → `ParserCursor(cursor, reset)` により、
owner reset=true なら matched を consumed へ戻す。Rust の通常経路でもこの判定を行う。
`Sequence` は Java `ChainInterface.parse` と同じく **begin より先に** owner を false にする。
ルール参照・metadata・値投影の透過 wrapper 自体に frame は追加しない。

### Java から確認した frame 対応

| Rust の `java_transaction` | Java の begin / commit / rollback の所有者 |
| --- | --- |
| NonOrdered | `NonOrdered.parse`（本体と各候補） |
| OwnedCapture / CaptureScope | `NamedCapture` / `CaptureScope` → `ParseContext.parseCaptureBoundary` |
| MatchOnly / InvertMatch | `ParseContext.parseMatchOnly` / `NotPropagatableSource.parseDelegated` |
| JavaEmpty | `EmptyParser extends LazyZeroOrOne` → `Occurs.parse`（子は MatchOnly） |
| JavaLookahead（正 / 負） | `MatchOnly` / `Not.parse` |
| JavaUntil | `WildCardStringTerminatorParser` → `Occurs.parse`（terminator も frame を持つ） |
| JavaOptional / JavaRepeat / ZeroMatch | `Optional` / `Repeat`・`ZeroOrMore` / `Zero` → `Occurs.parse` |
| Choice / PredictiveChoice | `ChoiceInterface.parse` / `PredictiveChoiceInterface.parse` の候補ごとの begin |
| LongestChoice / UniqueLongestChoice | `LongestChoiceInterface.parse` の各候補・winner 再実行の begin |
| Recovery / CatchLabels | `SyncPointRecoveryParser.parse` / `CatchLabels.parse` |
| NameResolutionScope / NamePredicate | `NameResolutionScope.parse` / `NamePredicateParser.parse`（#814 の corpus も継続実行） |

回数0・不正 bounds の通常反復は `Occurs.beginPreservingMatched` に対応するため巻き戻さない。
`ZeroMatch` は子を試すため巻き戻す。Longest 系は候補ごとに reset 判定を行い、
Classic の既存実装 `token.rs::classic_longest_choice` は維持する。
Chain の frame はこの一覧とは別に owner を false にするため、開始時の matched 代入は不要。
PEG `Lookahead` / `Optional` / `Repeat` には Java 互換 variant と異なる既存契約がある。

### 共通 corpus と残差

`frame-start.ubnf` は issue の Root / PlainTok / PlainGroup / PlainSeq、負の先読み、
3 Choice profile、Optional / Repeat / Separated / Capture / COMMIT / LABEL、nested 対照を生成する。
`frame-start-inputs.tsv` の104経路 × 通常 / Classic × 初期 reset false/true × memo 2設定 × 診断3設定。
生成 Java の実行結果2,496行が `frame-start.tsv`。Rust は両生成 host × 通常 / mapping grammar の
9,984行を照合する。両 host の全生成ファイルが一致すること、実生成された frame の型も検査する。
失敗を同じ context で3回呼び、成否・cursor・reset の不変と memo hit を採取する。
後続 `NonOrdered(LOOKAHEAD('b'))` と `NonOrdered(LOOKAHEAD('x'))` も同じ context で観測する。

外側を runtime API で包むケースは NonOrdered / MatchOnly / Choice / Optional / Repeat /
Longest / Unique / Predictive / owned capture / capture scope / CatchLabels / recovery / invert を含む。
直接の EMPTY と正負の先読み、Unicode、EOF、回数0、不正 bounds、ZeroMatch も固定する。
Java で transaction を持たない `Commit` / `Label` / `DoConsumePropagationStopper` /
`InvertMatchPropagationStopper` で包む8経路は対照で、Rust の `commit` / `label` / `stop_consume` /
`stop_invert` が開始時に巻き戻さない（`PlainSeq` は成功 CP0/2 のまま）ことを固定する（PR #837 のレビューで追加）。
`until-frame` は Java の `WildCardStringTerminatorParser.parse(context, consumed, false)` を呼ぶ。
引数なし overload が自身で false にする契約と区別し、Occurs の begin を観測するためである。

| `ab`、先行 `LOOKAHEAD('a')`、通常・初期 true | Java / 修正後 Rust | 修正前 Rust |
| --- | --- | --- |
| Root / PlainTok / PlainGroup | 失敗 CP0/1 | 成功 CP0/2 |
| PlainSeq | 成功 CP0/2、reset=false | 同じ |
| Choice(PlainSeq, fallback) | 失敗 CP0/1 | 成功 CP0/2 |
| 直接の EMPTY | 成功 CP0/1 | 成功 CP0/2 |

全行で成否・cursor・reset・後続の成否を Java oracle へ直接照合する。
この時点では診断 CP と failure / success memo hit に #826 / #827 / #838 の残差があり、606行について
`frame-start-differences.tsv` に Java / Rust の値を行キー付きで記録する。
Java 側の値も照合してからこの4列だけを差し替え、行全体や設定を除外しない。
このうち42行は、修正前の Rust が先読みの進めた位置で照合して失敗していたため**たまたま** Java と同じ値だった。
36行は診断 CP（`positive-eof`、`negative-*` / `Negative-*`、`*-longest` / `*-unique` の通常・初期 true・AUTO/DETAILED）で、
Java の `startParse` が begin の reset **前**に frame 開始を `max(consumed, matched)` として記録するのに対し、
Rust は rule を介さない frame の失敗でこの位置を記録しなかったことによる。#838 で以下のとおり解消した。
残る6行は `PlainTok-ahead` / `PlainTok-unicode` の SAFE_FAILURES の failure hit（Java 0 / Rust 2）で、#827 の型である。
既存 nested corpus では `Longest-partial` / `Unique-partial` の通常・初期 true・AUTO/DETAILED の8行で、
開始時の巻き戻しに伴い Rust の診断 CP が1から0へ変わり、この列は Java の CP0と一致する。
後続の診断 CP3/2 などの残差は #826 に残し、差分表を更新する。既存456差分行の数、Java oracle、成否・cursor・reset、Chain 対照の一致は維持する。
`PropertyBasedParityTest` の known-differences は追加しない。

実装は既存 frame の bool 判定と cursor 代入に限り、新しい確保・cache・entry field は追加しない。
#197 に従い性能改善は行わず、既存 `rust/examples/evolution/examples/shared_grammar_benchmark.rs`
を修正前後で各3回交互に実行して退行を確認する。計測値と mutation の結果は PR 本文に記録する。
`FrameStartMatchedResetConformanceTest` の2メソッドは Cross-language CI と
`.github/optin-test-contracts.json` に登録する。

### #829 の mutation

修正済み runtime を保存して、各変異を単独で `FrameStartMatchedResetConformanceTest` にかけ、
最後に保存した内容と byte 一致するよう復元した。

- 修正全体を外すと `Root-ahead / 通常 / 初期true / OFF / AUTO` で、期待の失敗 CP0/1 が成功 CP0/2となり失敗する。
- `ordered_choice` の判定に旧 `token_mode.is_some()` を戻すと `PlainSeq-choice` の同設定で同じ受理差を検出する。
- 通常 `longest_choice` の候補開始の reset を外すと `Root-longest` の診断列で失敗する。
  出力全行には `PlainSeq-longest` の同設定が失敗 CP0/1から成功 CP0/2になる差もあり、成否の検証にも効く。

Java は変更せず参照実装として再実行し、修正を外した状態を期待値へ取り込まない。


## reset 前の frame 開始診断 (#838)

Java の runtime は変更しない。`ParseContext.startParse` は `begin` より先に
`max(consumed, matched)` を記録する。低水準入口では AUTO は DETAILED に解決され、
`ParseFrame` の開始値・最大値が `registerFailureCandidate` の候補になる。
DETAILED_ON_FAILURE（この corpus の診断記録 OFF）は `deferredFrameStarts` に開始値を積むが、
`endDeferredParse` は明示的な trial recording 以外の診断を作らず、取得値は CP0 のまま。
この区別は memo OFF / SAFE_FAILURES に共通であり、memo の入出力にも診断記録を含める。

Rust は失敗後に既存 checkpoint を復元した位置を `fail_parser_frame` へ渡す。
従来の Sequence / Choice / PredictiveChoice / Repeat / JavaRepeat に加えて、
Java の診断 frame を持つ `java_transaction` の式（MatchOnly / JavaLookahead、
LongestChoice / UniqueLongestChoice、NonOrdered、capture / name frame など）にも適用する。
`CatchLabels` は transaction を持つ一方、Java では `startParse` / `endParse` を呼ばないので除外する。
資源中断、Classic 自身の frame 記録、token stream / expression entry の frontier 契約は維持する。

負の先読みの成功は別扱いである。Java の `Not` は子の失敗を
`discardDiagnosticSpeculation` で捨てるため、失敗候補がなければ frame 開始を含む
`maxReachedOffset` が診断値になる。Rust は成功した負の先読みから既存の到達位置 fallback を有効にし、
それを持つ外側の native frame（候補開始で reset した Choice など）へ開始・終了位置を反映する。
`MatchOnly(Not(b))` のように Not が MatchOnly の直下にあると、Rust の通常呼出は入口で暗黙に Classic の token mode へ入る。
このとき `parse_with_token_mode` と同じく入口の `max(consumed, matched)` を到達位置 fallback に記録し、
Not 自身も token mode のまま fallback を開始する（Java の MatchOnly の `startParse` に当たる）。
これは失敗候補ではない。後続の本当の失敗が CP0 にあれば、以前の fallback が CP1 / CP2 でも CP0 を返す。
既存の `classic_max_reached` と memo diagnostic の同フィールドを再利用し、記録のための確保や新規 field は足さない。
これは #838 時点の範囲であり、正の先読みからの成功時到達位置は後述の #826 で追加する。

#197 に対する成功側の例外は `finish_expression` のこの fallback 記録だけである。
成功した `java_transaction` の frame でだけ、診断設定と「負の先読み、または native で fallback が有効」かを判定し
（#872 / #876 で後者は「native」に変わった。[後述](#plain-success-diagnostics)）、
記録本体（CatchLabels・frontier・token stream の除外と最大値更新）は `#[cold]` / `#[inline(never)]` の
`note_success_frame_start` に置く。診断 OFF では transaction 以外の成功に追加の判定はなく、
transaction の成功でも診断設定の比較1つだけで、保存・確保・最大値計算・呼出しは行わない。
暗黙の Classic 入口の記録は `parse_expression` の既存分岐（token mode へ入る呼出しごとに1回）内の DETAILED だけで行う。
失敗側の追加は既存の失敗分岐内と cold な `fail_parser_frame` に限定し、
reset・入力照合・候補選択・memo key / hit 会計を変更しない。性能の再計測は行わない。

`FrameStartMatchedResetConformanceTest` の同じ2メソッドで Java の2,688観測と
両 Rust host × 通常 / mapping grammar の10,752観測を比較する。
issue の36行はすべて一致し、`frame-start-differences.tsv` から除いた。
同じ記録処理により frame-start corpus の診断値は計224行で Java に一致する方向へ変わった。
156行は全列一致のため除去し、68行は残る診断差または memo 会計差を保持する。
既存104経路の Java oracle・入力・成否・両 cursor・reset・後続成否・memo hit の各値は変更しない。
`PlainTok-ahead` / `PlainTok-unicode` の #827 型の6行も維持する。

入れ子の8経路を追加した（各192観測の Java oracle を追記）。`lookahead-longest`（MatchOnly の中の LongestChoice）、
`unique-lookahead`（UniqueLongestChoice の中の MatchOnly(rule)）、`lookahead-negative`（MatchOnly の中の Not）、
`longest-negative`（LongestChoice(Not, rule)。Root と Negative）、`negative-then`（Chain(Not, rule)。成功した frame の後の rule frame の失敗）、
`choice-negative-seq`（Choice(Chain(Not, 'zz'), rule)。成功した Not の後の失敗と rule frame）。
Java の `Not` は任意の子を取れるが、Rust の生成 IR の Not は語の `JavaLookahead` だけなので、Not の中の Not は比較できない。
`lookahead-negative` は PR の初版で Java CP1 / Rust CP0 だった（上記の暗黙 Classic 入口で解消）。
`Root-longest-negative` の SAFE_FAILURES・初期 false の3行は success hit が Java 1 / Rust 0 で、
外側 Longest の success hit と同じ #827 の型であり、差分表に追加した。差分表は453行となる。

nested corpus の診断修正は #826 に残したが、#838 でも
同じ記録処理で初期 reset=true の後続診断 CP（3/2 → 3/3 など）と、
`Integer-nonordered-failure` / `Ahead-nonordered-failure` / `AheadToken-nonordered-failure` の通常呼出の診断 CP が Java に一致した。
差分表を実測に合わせ、全列一致の168行を除き、44行の Rust 値を更新した（456行 → 288行）。
テスト側で列を読み替えて差分表を据え置く方式は採らない。差分表の Rust 値が実測と異なるまま残るためである。

mutation は別の隔離 worktree で修正前 runtime と更新後の差分表を組み合わせ、
`FrameStartMatchedResetConformanceTest` が `Root-no-prefix / 通常 / 初期true / OFF / AUTO` の
後続診断 CP1 → CP0 で失敗することを確認した。Java oracle のテストは成功する。
入れ子経路を加えた Java-host の通常 / mapping 合計5,376観測では、修正済みの不一致0、
修正全体を除去して464、失敗時記録だけ除去して400、成功時 fallback だけ除去して56、
暗黙 Classic 入口の記録だけ除去して8（`Negative-lookahead-negative`）。
成功側だけの変異は `Negative-ahead` の同設定で診断 CP1 → CP0 を検出する。
`NestedCandidateBoundaryConformanceTest` も修正全体・失敗時記録の除去で後続診断 CP3 → CP2 により失敗する。


## 正の先読み後の成功診断と投機的候補 (#826)

#844 merge 後の master を基準に、Java の製品コードは変更せず、既存の入力・oracle 行を維持する。
Java の `startParse` / `endParse` / `trackCursorProgress` は成功した frame の到達位置も保存し、
失敗候補が1つもなければ `maxReachedOffset` を返す。一方、失敗候補があれば、成功時の到達位置より
手前でも `farthestFailureOffset` が優先する。候補を rollback しても診断は残るが、成功した
PEG assertion / Not が明示的に捨てる投機的診断は残らない。

Rust では #838 の `note_success_frame_start` が負の先読みからしか fallback を開始していなかった。
正の `JavaLookahead` にも同じ cold な記録を適用し、既存の native frame 終了処理・memo diagnostic に
開始位置と終了時の `max(consumed, matched)` を伝える。cursor や reset は変更しない。
これにより nested の残る56行（通常 × memo 2設定 × AUTO/DETAILED）が一致する。
初期 true の行も含み、`Root-partial` / `Predictive-partial` の成功 CP2/2 と、
`Ahead` / `AheadToken` の直接・NonOrdered 内での成功 CP0/1 または CP0/2 を覆う。
後続 MatchOnly の診断値と既存の失敗時診断は変わらない。

関連する frame-start corpus は次の2点も検査する。

- 正の先読みの記録により340行の成功時診断が一致する。`empty-unicode` は開始CP2・終了CP1であり、
  最終 cursor だけから診断を作る案では不足する。開始位置を含む履歴の fallback が必要である。
- `Root` / `PlainSeq` を `OwnedCapture` / `CaptureScope` で包む初期 true の32行では、
  Java の `parseCaptureBoundary` が begin 前のCP1を失敗候補にする。Rust は Classic 内のこれらの frame を
  失敗記録の一覧に含めていなかった。既存の失敗分岐に2型を足し、復元した checkpoint の開始CP1を記録する。
  通常呼出も owned capture の原文処理のため暗黙の Classic に入るので、通常 / Classic の双方を修正する。

正の先読みの fallback を加えるだけだと、`PlainSeq-until-frame` の通常・初期 true の4行が
Java CP0 に対して Rust CP2 になる。Java の UNTIL は最初の終端 `b` をCP0で試して失敗してから進む。
この失敗候補が成功時の最大CP2より優先するが、Rust の native `JavaUntil` は失敗した終端試行を記録していなかった。
そこで既存の診断用 cold 関数内で `note_until_failures` を呼び、失敗した終端候補の最大位置を再構成する。
終端成功なら最後の不一致は最終 consumed の1 CP手前、EOFなら最終 consumed 自身である。
初回の試行位置も含めるため、先行先読みが後で見つかる終端より先へ進んでいた場合を落とさない。
走査ループ・診断 OFF の経路には処理を追加せず、UTF-8 byte ではなく CP 境界から前の位置を求める。
後続の本当の失敗を優先する規則を維持し、この4行は修正前と同じCP0を保つ。
Longest / Unique の成功候補の試行中に記録された実失敗も fallback で上書きしない。

### 受け入れ範囲・差分表

- 既存75入力・Java1800行の nested corpus と、112入力・Java2688行の frame-start corpus を維持する。
  frame-start に UNTIL の Unicode・終端なしEOF・先読みより手前の終端の3経路72行を追加し、115入力2760行とする。
  追加 oracle も Java を実行して採取し、既存2688行は byte 単位で変更しない。
  両 Rust host × 通常 / mapping grammar、通常 / Classic、初期 reset 2設定、memo OFF / SAFE_FAILURES、診断3設定を比較する。
  nested はさらに18候補の Chain 対照を含め、各 host 7200観測を検査する。
- nested の差分表から診断差56行を除去（288→232）、frame-start から372行を除去（453→81）。
  残る行は #827 の memo 会計差だけで、各行は byte 単位で従来どおりである。
  既存の Java oracle、成否・両cursor・reset・後続成否・failure/success hit は変更しない。
  診断 OFF の値はすべてCP0のまま、後続診断の値も全行で維持する。
- テストの読み替え・除外は加えない。差分表のすべての値を実際の Rust 出力と照合する既存テストを維持する。
  この2 corpus に未解決の診断差は残らない。候補単独の owner reset（#823 の内部差）と #827 の会計差は対象外。
  任意の手組み IR や、成功履歴全般の全面一致を主張するものではない。
- #826 時点では UNTIL の集約が先行先読みなどで `classic_max_reached` を記録した後に限られていた。
  先読みのない `aab` / `bb` の成功診断は #872 / #876 で同じ corpus に追加し、Java CP1 に揃えた。
  先読みなしの通常呼出の範囲と native PEG との区別は [後述](#plain-success-diagnostics) を参照する。

### #197 と mutation

成功経路の差分は、既存の `java_transaction && Diagnostics::Detailed` ガードの内側から
`JavaLookahead` の `positive: false` 制限を取り除き、checkpoint の開始位置の最大値計算を cold 関数内へ移すだけである。
新しい条件分岐・確保・field・cache は足さない。
正の先読みとその後の native frame は既存の cold 関数を使い、診断 OFF ではその呼出しも増えない。
capture の追加記録は既存の失敗分岐内、UNTIL の失敗候補の集約は既存の診断用 cold 関数内に置く。
入力照合・候補選択・memo key / hit 会計は変えず、性能の再計測は行わない。

mutation は同じ生成 parser・support driver・更新後 oracle で、修正全体、正の先読みの記録、
UNTIL の失敗記録、その初回試行位置、Classic capture の失敗記録を個別に除去して検出する。
修正全体と正の先読みの除去は nested の `Root-partial` CP2→0、UNTIL の除去は
`PlainSeq-until-frame` CP0→2、capture の除去は `Root-capture-scope` CP1→0 で失敗する。

初回試行位置だけを外す変異は追加した `PlainSeq-until-earlier` の初期 false で CP2→0 を検出する。
同じ生成 parser の通常 / mapping（nested は Chain 対照も含む）での不一致観測数は次のとおり。

| 変異 | nested（7200観測） | frame-start（5520観測） |
| --- | ---: | ---: |
| 修正済み | 0 | 0 |
| 修正全体を除去 | 224 | 784 |
| 正の先読みの記録だけ除去 | 224 | 720 |
| UNTIL の失敗記録だけ除去 | 0 | 32 |
| UNTIL の初回試行位置だけ除去 | 0 | 8 |
| Classic capture の失敗記録だけ除去 | 0 | 64 |

さらに origin/master 起点の隔離 worktree に更新後の corpus を置き、修正前 runtime のまま
`NestedCandidateBoundaryConformanceTest` / `FrameStartMatchedResetConformanceTest` を実行した。
Java oracle の2テストは成功し、Rust 照合の2テストはそれぞれ `Root-partial` / `Root-ahead` の
CP2→0で失敗する。最終版の両 host の照合と同じテストで修正除去を検出する。

<a id="memo-hit-accounting"></a>

## failure / success hit の契約 (#827)

**結論:** hit 数は host 依存の内部会計であり、同じ文法・入力・設定でも Java/Rust 間の
数値の一致を契約しない。成否・consumed / matched cursor・診断・reset・後続解析の一致は
引き続き検証する。hit 数を揃えるために cache を追加したり、Java の安全 marker や Rust の
rule 単位 cache を変更したりしない。これらは安全性・性能の作業であり、#197 の凍結対象になる。
今回は runtime / generator / memo key / 計数処理を変更せず、性能の再計測もしない。

### 公開される値と利用範囲

- Java: `ParseContext.getPackratMemoTable().failureHits() / successHits()`。
  memo OFF では table が null の場合がある。
- Rust: `ParseContext::memoized_failure_hits() / memoized_success_hits()`。
  通常 / Classic とも context の pass 内の実 hit で、別 context の詳細診断 retry を合算しない。
- `benchmarks/tiny-classic/consumers/` の Java / Rust consumer が返す結果 JSON の
  `failure_hits` / `success_hits` も上記 getter の値であり、同じ host 依存の契約になる。
  Java の `Parsed`、Rust の `ParseMatch` / `ParseDiagnostic` / Classic `TokenInfo` 自体には
  hit フィールドはない。hit は構文診断の位置や成否ではなく、cache の再利用を調べる統計である。
- Java の `PackratMemoTable.failureHits()` / `successHits()` と Rust の両 getter の Javadoc / rustdoc にも、
  host 依存で言語間の比較に使えないことを記す。

公開されているため「利用者には見えない値」とは扱わない。host・生成器・安全判定・診断設定・
候補除外・cache 予算・trace の条件が変われば hit 数も変わり得る。言語間の互換性判定や性能順位に
hit の絶対値を使わない。固定 corpus では回帰検知のため、各 host の現在の生の値を厳密に残す。

### marker と cache 対象の対応

`nested-boundary.ubnf` の生成 Java を compile し、次の直接宣言 marker をテストで照合する。
Java の `PackratMemoTable` は継承した marker ではなく exact class の直接宣言を確認し、
parser identity と両 cursor 等を key にする。Rust の `rule → memoized_rule → probe_rule_memo` は
安全性を確認した `Rule` 全体を key にし、inline の Choice / Sequence / 値投影に独立した entry は作らない。

| 生成 Java のクラス | failure / success marker | Rust の対応する cache 単位 |
| --- | --- | --- |
| RootParser / IntegerParser / LongestParser / UniqueParser | なし / なし | 各 Rule 全体（Number を含む既知の式として安全） |
| IntegerGroup0Parser / __CaptureSite | なし / なし | inline Choice / TextValue / ValueBoundary。独立 cache なし |
| RootGroup0Parser / LongestGroup0Parser / UniqueGroup0Parser | あり / あり | literal だけの inline Choice。独立 cache なし |
| OuterParser / MixParser | なし / なし | Outer / Mix の各 Rule 全体 |
| HelperParser | あり / あり | Helper Rule 全体 |
| MixGroup0Parser | あり / あり | Helper を参照する inline Choice。独立 cache なし |

Java の `ParserGenerator.collectMemoDependencies` は `token N = NumberParser` のような
`TokenDecl.Simple` を名前だけで安全とみなさない。N に依存する rule は marker を持たず、
literal のみ、または安全な Helper だけに依存する group は `ParserRuleEmitter.generateHelperCode`
で両 marker を得る。Rust の lowering は N を既知の `Expr::Number` にし、
`expression_is_memo_safe` が rule の依存先まで確認する。この安全判定と粒度の違いを
計数の誤りとして補正する根拠はない。

一時コピーだけに hit の trace を加えた対照でも、`Outer` の Java の内訳は初期 false で
初回 Helper 1回、2・3回目に MixGroup0 と Helper 各1回の計5回、初期 true は初回0・以後各2の計4回だった。
Rust は両設定とも2・3回目に Outer 全体を再生して計2回だった。
Longest / Unique の Java success hit 1回は勝者を再実行するときの Group0 再生であり、
Rust の inline group には対応する cache probe がない。trace 用コードは製品・テストへ組み込まない。

### 共通 corpus と owner reset の切り分け

既存75経路・Java1800行を維持し、nested を使わない
`Helper ::= 'a' 'z' | 'h'; Mix ::= (Helper | 'q') | N; Outer ::= Mix | Helper 'x';`
の4経路96行を追加した。生成 Java の候補に __CaptureSite がないことも検査する。
両 Rust host の生成物は byte 一致を確認し、それぞれを compile・実行する。

| 入力経路 | SAFE_FAILURES の実 hit（Java / Rust） |
| --- | --- |
| Integer-failure（ab、先読み a、3回失敗） | failure 0 / 2 |
| Longest-text / Unique-text（qb）、同 partial（azb、先読み a） | 成功候補の再実行で success 1 / 0 となる行 |
| Outer-nonstarter（+b、先読み +、3回失敗） | AUTO / DETAILED は初期 false: 5 / 2、true: 4 / 2。DETAILED_ON_FAILURE は 0 / 2 |
| Outer-failure（ab、先読み a、3回失敗） | 診断3設定とも初期 false: 5 / 2、true: 4 / 2 |
| Outer-no-prefix（ab、先読みなし、3回失敗） | 先読みなしの失敗の対照。診断3設定とも初期 false: 5 / 2、true: 4 / 2 |
| Outer-success（hxb） | 成功の対照。hit は両 host とも 0 / 0 で差分表に行はない |
| frame-start の Root-longest-negative（ab、先読み a、通常、初期 false） | 診断3設定とも success 1 / 0 |

Outer の遅延診断0 hit は、候補の FIRST 除外で probe 自体を省く入力の値である。
ab は Helper の開始文字 a を含むため同じ0にはならず、追加した +b で issue コメントの値を再現する。
これは診断設定による既存の候補除外で、成否・cursor を変える cache 再生ではない。

nested の18候補だけに Sequence([child]) を付ける既存の Chain 対照も継続する。
候補単独の reset が true→false、後続先読みが失敗→成功になることを正の対照とし、
rule 全体からの観測は hit を含む全列が元の Rust と一致する。
さらに Outer には nested 境界がなくても差が出る。したがって #823 の owner reset 内部差を
正規化すればこの会計差が消える、という説明は成立しない。初期 reset による同一 host 内の
key の区別は正しい動作であり、異なる cache 対象間の hit 数を揃える理由にはならない。

#827 時点の検査範囲は nested 79経路・Java1896行、両 host × 通常/mapping × Chain 対照の
15168観測と、frame-start 115経路・Java2760行、両 host × 通常/mapping の11040観測である。
全経路に通常/Classic、初期 reset 2設定、memo OFF/SAFE_FAILURES、診断3設定を含む。
差分表は nested 268行（既存232行＋Outer失敗36行）、frame-start 81行で、既存データ行は変更しない。
hit 列は削除せず Java 側の値・全キー・全行・全列を照合し、診断2列の host 差はテストが拒否する。
`MemoHitAccountingAssertions` は実測の OFF/SAFE_FAILURES の成否・両 cursor・reset・診断と
後続解析を直接比較し、OFF の両 hit が0であることも検査する。
この範囲では cache が結果を変える反例はなく、新しい不具合 issue に分離する項目はない。
任意文法・任意 IR の全面一致や #823 の候補単独の差の解消は主張しない。

### mutation

隔離した比較用 worktree で、同じ生成 parser・実測照合テストを実行した。変更なしの
nested 対照は成功し、次の4変異はいずれも失敗した。元の作業 worktree の oracle は変えていない。

- Outer の会計差36行を差分表から除去すると、Rust の実 failure hit 2 が Java の期待5と異なり失敗する。
- frame-start の Root-longest-negative の Rust success hit を0→1に変えると、実測0との不一致で失敗する。
- Outer-nonstarter の Rust 診断 CP を差分表で +1すると、診断差を許可しない検査で失敗する。
- Outer-nonstarter の Java failure hit の期待値を5→6に変えると、生成 Java の実測5との不一致で失敗する。


<a id="plain-success-diagnostics"></a>

## 先行先読みのない通常呼出の成功診断 (#872 / #876)

両 issue の原因は同じである。Java の `getParseFailureDiagnostics()` は実失敗があれば
`farthestFailureOffset` を優先し、なければ `maxReachedOffset` を返す。
`startParse` / `endParse` / `consume` / `matchOnly` が `trackCursorProgress` を呼ぶため、
成功した literal / MatchOnly の到達位置も fallback に含む。Java runtime は変更しない。

Rust の `finish_expression` は `classic_max_reached.is_some()` を成功記録の条件にしていた。
先行先読みがなければ `note_success_frame_start` に入らず、UNTIL の終端失敗の集約も呼ばれない。
この条件だけを除き、既存の Detailed transaction ガードと cold recorder を使う。
実失敗は引き続き成功到達位置より優先し、既存の memo diagnostic にも同じ値を保存する。

| 入力経路（通常呼出、AUTO / DETAILED） | Java oracle / 修正後 Rust | 修正前 Rust |
| --- | ---: | ---: |
| PlainSeq-until-noprefix、`aab` | CP1（CP0/1 の終端試行の失敗） | CP0 |
| PlainSeq-until-immediate、`bb` | CP1（失敗なし、終端 MatchOnly の到達） | CP0 |
| PlainSeq-until-noprefix-unicode、`a😀b` | CP1（byte 位置ではなく CP） | CP0 |
| PlainSeq-until-noprefix-eof、`a😀` | CP2（EOF の終端試行の失敗） | CP0 |
| Helper2、`azb`、direct / nonordered | CP2（失敗なし） | CP0 |
| Outer2、`azx`、direct / nonordered | CP3（失敗なし） | CP0 |

### native PEG の契約との境界

今回の Rust harness は `parse_shared_grammar` の driver 内で生成 rule / wrapper の
`Expr::parse` を呼ぶので、`grammar_frontier=true` の Java 互換経路である。
Java-host / native は生成器の違いであり、native 生成器の使用自体を native PEG 診断契約の
選択とはみなさない。そのため、この corpus の成功到達位置は Java に揃える。

独立した native PEG の `context.parse(&expr)` は、grammar 内から呼ばれた場合も
`grammar_frontier=false` に切り替える。cold recorder の既存ガードがこの入口と lexing session を除外する。
`peg-frontier` の Java 成功到達 / Rust `failure()=0` の別列は維持し、期待値を変更しない。
`CatchLabels` の通知なし transaction も従来どおり除外する。
[PEG の入口契約](../../docs/peg-assertions.md)と
[prior matched の契約](../../docs/prior-matched-diagnostics.md)を参照。

### corpus と不変条件

frame-start に5経路120行、nested-boundary に32経路768行を Java の実行値から追記した。
既存の Java oracle 2760 / 1896行は byte 単位で維持する。
nested は `Helper2` / `Outer2` のほか `[Helper] 'x' | Helper`、Longest / Unique の Helper、
`Outer 'y' | Outer | Mix 'x'` を direct / nonordered / lookahead / choice で比較する。
Unicode・EOF・空入力・先行先読み・候補失敗・optional 欠落の対照も含む。

各入力を通常 / Classic、初期 reset 2設定、memo OFF / SAFE_FAILURES、診断3設定で検査する。
Java は frame-start 2880行・nested 2664行、両 Rust host × 通常/mapping はそれぞれ
11520 / 21312観測（nested は既存18候補の Chain 対照込み）。
生成物の byte 一致だけで済ませず、両 host の生成物を個別に compile・実行する。

差分表の診断2列の差はともに0行。frame-start の81行は不変、nested は268→316行で、
増分48行は新規経路の #827 型の memo 会計差だけである。既存の差分行は byte 単位で維持する。
修正前後で成否・両 cursor・reset・後続成否・後続診断・failure/success hit は全観測不変。
変化するのは成功直後の診断 CP だけ（frame-start 32行、nested 152行／graph）。
`MemoHitAccountingAssertions` と差分表の診断同値検査を維持し、テスト側の除外や値の読み替えは足さない。

### #197 に対する費用

- 成功経路の既存 `java_transaction && Diagnostics::Detailed` の短絡順は維持する。
  その後の `classic_max_reached.is_some()` の判定1つを除く。新しい分岐・呼出し箇所・field・cache は0。
- AUTO / DETAILED では、先行先読みがなくても成功した native transaction frame ごとに
  既存の `#[cold] #[inline(never)] note_success_frame_start` が呼ばれる。呼出し頻度は増える。
  最大値計算・memo diagnostic 更新と UNTIL の失敗試行の集約は既存 helper 内に保つ。
  UNTIL は既存の expected 名の登録も実行するため、未登録なら従来の診断用領域を使う。
- DETAILED_ON_FAILURE の処理・呼出し数と UNTIL の走査ループは不変。新しい再帰経路はない。
  入力照合・候補選択・memo key / hit 会計も変えず、性能の再計測は行わない。

記録点は成功した Java transaction frame（Choice / NonOrdered / MatchOnly / UNTIL など）だけである。
最後の transaction frame の後に、transaction を持たない Chain / literal が消費した位置は入らない。
例: `'a' 'z'` を `azb` で direct に呼ぶと Java CP2 / Rust CP0、`('a' | 'h') 'z'` は Java CP2 / Rust CP1
（修正前は CP0）、先行先読み `a` の後の `'a' 'z'` は修正前から Java CP2 / Rust CP1。
外側に NonOrdered / Choice などがあればその frame が終端を記録するので一致する。
この残りは診断 OFF との境界の判断が要るため [#878](https://github.com/opaopa6969/unlaxer-parser/issues/878) で追跡し、corpus には含めない。
任意の手組み IR や成功履歴全般の全面一致、#823 の候補単独 reset 差の解消は主張しない。


### mutation

origin/master `91c691aa` の隔離比較 worktree に更新後の入力・Java oracle・memo 差分表を置き、
成功記録の条件を変更していない runtime で、同じ2つの conformance test を実行した。
先に照合する host で失敗が確定するため、比較用 test の host 順だけを逆にした実行も行い、両 host の mutation を確認した。
Java oracle の2メソッドは成功し、Rust 照合の2メソッドだけが失敗する。
frame-start は `PlainSeq-until-noprefix / false / false / OFF / AUTO` の CP1→0、
nested は `Helper2-direct-noprefix` の同設定の CP2→0 を検出した。
修正前 runtime の全出力と修正済みの対照を比較すると、不一致は診断 CP のみで、
frame-start は各 host 5760観測中64、nested は各 host 10656観測中608となる。
修正済みでは両 host とも0。元の作業 worktree の runtime と oracle は mutation で変更していない。
