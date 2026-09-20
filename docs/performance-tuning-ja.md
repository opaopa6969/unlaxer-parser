# unlaxer パフォーマンスチューニング実践ノート

> 対象: parser combinator、`ParseContext`、生成 parser を実装・改善する読者

unlaxer は最短の parse loop を目標にしたライブラリではない。source position、token/CST、
capture、user state、scope、診断、LSP/DAP へ続く情報を一つの parse で保持する。そのため
チューニングでは、単に transaction を削るのではなく、これらの rollback 不変条件を
壊していないことを速度と同時に測る。

## 測定の原則

一つの施策につき、次を同じ入力・同じ JVM/Rust 設定で記録する。

1. 変更前 commit と変更後 commit
2. parse-only と parse+map（該当する場合）
3. memoization の `OFF` と `SAFE_FAILURES`
4. warmup、測定回数、中央値、ばらつき
5. AST/CST、source span、診断、評価結果の一致
6. raw result と再現コマンド

速くならなかった施策も記録する。負の結果は「どこへ適用してはいけないか」を示す
再利用可能な設計資料である。

## ケース1: ordered choice と longest choice

### 問題

PEG 型の `Choice` は最初の成功を返す。たとえば `'a' | 'abc'` で入力が `abc` なら、
結果は `a` である。これはバグではなく ordered choice の仕様である。しかし frontend が
短い root parse の後に残余入力を見つけ、別の root parser で最初から再試行すると、
`ParseContext` の構築、Formula prefix、診断収集などを重複して実行する。

### 選択した設計

通常の `Choice` は変更せず、明示的な `@longestChoice` を追加する。注釈は複数の代替を持つ
rule にだけ指定でき、`@leftAssoc` / `@rightAssoc` とは併用できない。生成先は次になる。

```text
Java: LazyLongestChoice
Rust: Expr::LongestChoice
```

候補はすべて同じ `ParseContext` 状態から開始し、最大消費を選ぶ。同長は宣言順で決める。
losing candidate が変更した cursor、token/CST、capture、scope、user state、選択 metadata は
残してはいけない。

### Java と Rust の違い

Java の `TransactionalState` は「過去状態へ戻す action」を返す契約で、成功した未来状態を
汎用的に複製できない。そのため勝者を再実行して commit する。Rust runtime の context state
は複製可能なので、勝者 snapshot を保存し、再実行せず復元する。

この違いから、Java の custom parser/listener は speculative trial を観測し、勝者を2回
呼ばれ得る。context 外の I/O や global mutation は rollback 不能なので避ける。Rust でも
custom parser の外部副作用は rollback できないため、同じ制約を推奨する。

### 発見した既存バグ

nested `Choice` と `NonOrdered` の選択 metadata は以前は token transaction の外にあり、
内側が成功した後に外側が失敗すると metadata だけが残った。選択変更を小さな undo journal
へ記録し、子 transaction の commit 時に親へ伝播、外側 rollback 時に復元するようにした。
Map 全体を `begin()` ごとに clone しないため、通常経路の追加コストを mutation 箇所へ限定する。

### 適用判断

`@longestChoice` 自体は一般的な高速化ではない。候補数 `N` に対して全候補を試すため、再帰的に
呼ばれる `Expression` 全体へ付けると悪化しやすい。top-level 専用の dispatch rule に限定し、
外側の root retry を置き換えた差し引きを benchmark する。差し引きが負なら注釈は正確な
disambiguation 機能として残し、性能改善には token/先頭文字による predictive dispatch を使う。

### 検証項目

- short prefix と最長候補
- 同長時の宣言順
- Unicode code-point cursor
- nested choice、CST node、capture、scope、user state の rollback
- memoization `OFF` / `SAFE_FAILURES`
- 全候補失敗時の farthest diagnostic
- 通常の ordered `Choice` が変わらないこと

測定値と raw result は tinyexpression の root retry 置換後に、この文書と benchmark artifactへ
追記する。

## ケース2: FIRST prefix による予測的 ordered choice

### 目的と意味論

`@predictiveChoice` は通常の ordered `Choice` の前に、生成時に計算した保守的な FIRST prefix
フィルタを置く。これは「成功する候補を決める」最適化ではない。現在位置の入力と一致し得ないと
証明できた候補だけを除外し、残った候補は宣言順のまま、従来と同じ transaction 内で試す。

```ubnf
@predictiveChoice
RootExpression ::= 'number:' Number | 'string:' String | Fallback ;
```

初期実装が生成する predictor は literal prefix、組み込みの number / identifier / quoted token、
それらの有限和と `Any` である。rule 参照は再帰的に FIRST を求めるが、nullable prefix、再帰 cycle、
custom token/parser、lookahead、または解析できない要素に達した時点で `Any` とする。`Any` は候補を
除外しない。このため最適化機会を捨てる場合はあっても、文法の受理範囲を狭めてはいけない。

予測された候補がすべて失敗した場合は、全候補を元の順序で再試行する。これは生成器の FIRST
解析が将来拡張されたときにも false negative を parse failure にしない安全弁であり、失敗時の
診断を通常の `Choice` と同じに保つ。従って cost model は次のようになる。

- 一意な literal prefix の成功経路: 不可能な transaction を省略できる
- prefix が重なる、または `Any` を含む経路: 通常の ordered `Choice` とほぼ同じ
- 予測候補がすべて失敗する経路: 安全な全候補 retry の分だけ通常より高コストになり得る

先頭の trivia を呼出側がまだ消費していない場合も、判定不能として全候補へ戻す。従って
`@interleave` や whitespace policy の受理動作は変えない。高速化を確認するときは成功入力だけで
なく、invalid input の診断 kind・位置・expected token も比較する。

### 適用判断

候補の多くが異なる固定 prefix を持ち、通常入力が成功経路へ偏る dispatch rule に向く。
数値式と比較式のように同じ prefix を共有する候補、nullable が多い rule、深い再帰の内部へ
無差別に付けても効果は小さい。`@longestChoice` は最長一致という意味論を選ぶ機能、
`@predictiveChoice` は ordered choice の意味論を保つ最適化であり、相互の代替ではない。

### 検証項目

- disjoint literal、overlapping literal、`Any` を含む選択
- nullable prefix、rule 参照、再帰 cycle、interleave
- 全候補失敗時の診断と memoization `OFF` / `SAFE_FAILURES`
- CST、source span、capture、scope、user state、選択 metadata
- Java と Rust の生成 IR・runtime semantics の一致
- 実アプリケーションでの候補削減数と、変更前後それぞれ3回以上の benchmark

### TinyExpression P4での測定結果

`Expression` の6候補へ適用した3-run中央値では、Java parse-onlyは2.25%短縮したが
parse+mapは10.27%増加し、Rust parse-onlyは1.75%増加、parse+mapは5.19%短縮した。
36件のroot fixtureに対するalternate-root fallbackも、適用前後とも10件で変わらなかった。
候補間でFIRST集合が広く重なるため、総合的な改善とは判定せずTinyExpressionへの注釈は
取り下げた。raw result、測定条件、生成式の膨張修正は
[TinyExpressionの実験レポート](https://github.com/opaopa6969/tinyexpression/blob/master/benchmarks/results/2026-09-20-predictive-choice-experiment.md)
に保存している。

この負の結果から、注釈の存在自体を既定の高速化と解釈してはいけない。prefixが分離した
dispatch ruleで候補削減が期待できる場合だけ有効化し、文法ごとに実測して採否を決める。

## ケース3: transaction payload の copy-on-write

### 仮説

`ParseContext` の transaction は cursor やtree長だけでなく、capture、scope、user stateなども
rollbackする。ところが多くの speculative branch は、それらのpayloadを変更せずにcommitまたは
rollbackする。transaction開始時に毎回deep copyすると、「戻すものがない失敗」まで高価になる。

そこでtransaction自体は削らず、payload snapshotだけを最初のmutationまで遅延した。

- Java: 既存の第三者製 `TransactionalState` は互換性のためeagerのままにし、
  `MutationAwareTransactionalState` を実装する組み込みstateだけがmutation直前に各open frameへ
  snapshotを設置する。memoization versionはMapではなくframe内のprimitive fieldへ保存する。
- Rust: capture、user state、scopeを `Rc` でcheckpointと共有し、最初のmutationで
  `Rc::make_mut` によりdeep copyする。空payloadは `Option::None` として保持する。

両runtimeはopt-in counterを持つ。`opened`、`committed`、`rolled_back`、
`nonempty_payload_snapshots`、`empty_payload_checkpoints`、`copy_on_write_deep_copies` の6項目を
同じ意味カテゴリで観測する。ただし内部表現が異なるため、Javaのpayload snapshotは登録された
`TransactionalState`、Rustはcapture/state/scopeの共有snapshotを数える。従って絶対値を言語間で
比較せず、各runtime内で「checkpoint数に対して実copyが何回必要だったか」を読む。

### TinyExpressionでの観測

production public facadeと同じFormula→Boolean→String→Objectのretry順、safe failure memoizationで
一度ずつ計測した。

| Runtime / fixture | Opened | Commit | Rollback | Nonempty snapshot | Empty checkpoint | Deep copy |
|---|---:|---:|---:|---:|---:|---:|
| Java / complex | 41,917 | 7,967 | 33,950 | 1,341 | 40,576 | 1,341 |
| Java / comparison-heavy | 24,830 | 4,082 | 20,748 | 11 | 24,819 | 11 |
| Rust / complex | 53,985 | 13,238 | 40,747 | 53,983 | 2 | 1,621 |
| Rust / comparison-heavy | 15,135 | 1,492 | 13,643 | 14,593 | 542 | 132 |

Javaでは95%以上のtransactionが組み込みstate snapshotを必要とせず、Rustではsnapshot handleを
持つcheckpointが多い一方、実deep copyはcomplexで約3.0%、comparison-heavyで約0.9%だった。
この差はcopy-on-writeの対象がhot pathに存在することを示す。

3-run中央値のpublic facade A/Bでは、Javaがcomplex 1.30%、comparison-heavy 3.71%短縮、Rustが
complex 66.06%、comparison-heavy 54.20%短縮した。受理結果、AST/source span、診断、nested
transactionの状態同値testも維持したため採用した。測定条件とraw dataは
[TinyExpressionの実験レポート](https://github.com/opaopa6969/tinyexpression/blob/master/benchmarks/results/2026-09-20-checkpoint-cow-experiment.md)
に保存している。

### 教材としての要点

copy-on-writeは「状態をなくしてpureにする」最適化ではない。`ParseContext` の状態fulな能力と
rollback境界を維持しつつ、状態が実際に変わるまで所有コストを払わない設計である。最初に
counterでtransaction数と実mutation数の差を確認し、次にnested commit/rollback、late
registration、listener、否定lookaheadを回帰testへ固定してから速度を測る。この順序なら、
速くなった代わりに意味論が弱くなる事故を避けられる。

## ケース4: transaction frame 再利用の allocation 監査（不採用）

### 仮説

ケース3で payload の deep copy を遅延しても、TinyExpression complex 1 parse で Java は 41,917
transaction、Rust は 53,985 checkpoint を開く。frame オブジェクトの生成、stack 操作、
commit/rollback の bookkeeping が残るなら、ParseContext-local の frame pool や配列 stack で
allocation を減らせるはずだ、というのが unlaxer-parser#208 の仮説だった。

#214 の規約どおり、実装の前に allocation profile で「frame が実際にどれだけ割り当てているか」を
確認した。

### 監査方法

- Java: TinyExpression `P4ParserBenchmark.publicFacade` を JMH `-prof gc`（`gc.alloc.rate.norm`）と
  `-prof jfr` で計測し、`jdk.ObjectAllocationSample` の allocation pressure を object class と
  呼び出し元 3 frame で集計した。frame 関連は `TransactionElement`、`ParserCursor`、
  `EndExclusiveCursorImpl`、`TokenList`、および `Transaction.begin/commit/rollback`、
  `checkpointTransactionalState`、`finishTransactionalState` 配下の allocation と定義した。
- Rust: `Checkpoint` は cursor と CST 長の scalar、および `Option<Rc<_>>` の payload handle だけを
  持つ stack value である。これを test で固定するため、同じ入力・同じ仕事に対して checkpoint 層だけを
  1 層と 9 層で比較し、allocation 数が一致することを `rust/unlaxer-runtime/tests/checkpoint_allocation.rs`
  で検証した（`stats_alloc` による process-wide 計数、`harness = false` で直列実行）。

### 観測

Java（baseline `5770ed3`、public facade 1 parse）:

| Fixture | allocation / op | frame 関連の割合 | 支配的な allocation site |
|---|---:|---:|---|
| complex.tiny | 約 1,176 MB | 1.42% | `ArrayDeque.iterator()` ← `trackCursorProgress` 48.8%、`snapshotStackElements` 12.9%、`localStackSnapshot` 系の `ArrayList` copy 22.3% |
| comparison-heavy.tiny | 約 432 MB | 3.00% | `localStackSnapshot` 40.6%、`snapshotStackElements` 16.7%、`ArrayList.<init>` / `copyOfRange` 21.0% |

frame オブジェクトそのものは両 fixture とも allocation pressure の 3% 未満で、pool や配列 stack で
削れる上限がそこにある。一方、`trackCursorProgress` は `startParse` / `endParse` / `consume` ごとに
`memoDiagnosticFrames` の iterator を生成し、frontier に達するたびに parse stack 全体を
`ParseStackElement` のリストへ snapshot している。これは失敗診断の bookkeeping であり、
transaction frame とは別の仮説になる。

Rust（baseline `74527e5`）:

- 8 層 × 512 反復 = 4,096 個の追加 checkpoint を、空 payload / 非空 payload（capture・typed state・
  scope 宣言あり）× commit / rollback の 4 シナリオで比較し、追加 allocation は 0 だった。
- TinyExpression complex.tiny の 1 parse は 789,084 allocation（17.7 MB）、comparison-heavy.tiny は
  82,094 allocation（4.0 MB）を行うが、上記から checkpoint 自体には帰属しない。`FailureDiagnostic::record`
  の `expected.to_owned()` や `Fragment` 生成が次の attribution 候補である。

### 適用判断

Java の frame pool / 配列 stack は不採用とした。削減上限が allocation の 1.4〜3.0% で、
ケース3の run 間ノイズ（±3〜5%）の中に埋もれる。counter・profile を残し、production コードは
変更していない。Rust は既に frame allocation がゼロであることを test で固定し、pool は導入しない。

代わりに、監査で見つかった診断 bookkeeping の allocation（Java 75% 前後）を unlaxer-parser#215 として
切り出し、#214 の次候補に提案した。測定条件と raw data は
[TinyExpression の監査レポート](https://github.com/opaopa6969/tinyexpression/blob/master/benchmarks/results/2026-09-20-frame-reuse-audit.md)
に保存している。

### 教材としての要点

「transaction が多いから frame を再利用すれば速くなる」は自然な仮説だが、JVM の若い世代の
allocation は安価で、数が多いことと bytes が多いことは別である。profile を先に取ると、
本当に bytes を消費しているのは frame ではなく、frame ごとに繰り返される付随処理（ここでは診断の
stack snapshot）だと分かる。負の結果でも profile と test を残せば、次の施策が同じ計測から始められる。
