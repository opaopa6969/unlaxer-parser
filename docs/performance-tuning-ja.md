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
  1 層と 9 層で比較し、allocation 数が一致することを
  `rust/unlaxer-alloc-audit/tests/checkpoint_allocation.rs` で検証した。workspace は外部 crate を
  持たず `unsafe_code = "forbid"` なので、counting allocator はこの監査専用 member crate に閉じ込め、
  計数が process-wide であるため `harness = false` で直列実行する。

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

## ケース5: 失敗診断の bookkeeping から「結果に影響しない allocation」を外す

### 仮説

ケース4の監査で、Java の public facade 1 parse あたり約 1,176 MB の allocation のうち 75% 以上が
transaction frame ではなく失敗診断の進捗追跡だと分かった。`trackCursorProgress` は
`startParse` / `endParse` / `consume` のたびに parse stack 全体を `ParseStackElement` のリストへ
snapshot してから「今のほうが深いか」を比べ、`registerFailureCandidate` は失敗した frame ごとに
hint 候補の収集と snapshot を行ってから「frontier に届くか」を比べていた。比較の結果が「捨てる」なら、
その snapshot は最初から要らない。

Rust でも同じ構図があった。`FailureDiagnostic::record` は同じ位置に同じ expected を記録するたびに
`String` を確保して `BTreeSet` へ挿入し（重複なので set は変わらない）、memo hit のたびに保存済み診断を
`clone` して replay していた。complex.tiny 1 parse の 789,084 allocation のうち 86% が expected 保存、
5% が memo hit の clone だった。

診断の**出力**（farthest offset、expected hints、parse stack、memo diagnostic の replay、
`ParseError.expected` の内容と順序）は一切変えず、「比較して捨てるもの」と「既にあるものの再確保」だけを
やめる、というのが unlaxer-parser#215 の仮説である。

### 実装

- Java: snapshot は open な parse frame と同じ要素数なので、深さ（`parseFrames.size()`、memo frame では
  base depth を引いた局所深さ）を先に比べ、採用される場合だけ `snapshotStackElements()` を呼ぶ。
  1 回の snapshot を global と memo frame の局所 view で共有する。どの frontier にも届かない失敗は
  hint 収集の前に return し、memo diagnostic frame が空なら iterator を作らない。
  `snapshotStackElements()` は `ArrayDeque` の逆順 iterator で直接構築し、中間 `ArrayList` copy を省く。
- Rust: expected 集合を `BTreeSet<Rc<str>>` にし、`contains` を先に見て重複時は文字列も node も確保しない。
  1 回の失敗を複数の memo frame と top-level 集合へ記録するときは `Rc` を共有する。memo hit は
  `HashMap::remove` → 参照で replay → 再挿入とし、診断全体の clone を除去した。
  `ParseError.expected: Vec<String>` は据え置き、custom parser が渡す動的文字列も所有し続ける。

### 観測

allocation（TinyExpression public facade 1 parse、baseline `c243638`）:

| Runtime | Fixture | before | after |
|---|---|---:|---:|
| Java（JMH `gc.alloc.rate.norm`） | complex.tiny | 1,176 MB | 299 MB（-74.6%） |
| Java | comparison-heavy.tiny | 432 MB | 132 MB（-69.5%） |
| Rust（allocation 回数） | complex.tiny | 789,084 | 89,265（-88.7%） |
| Rust | comparison-heavy.tiny | 82,094 | 13,478（-83.6%） |

Java で残った allocation の最大項目は `CollectingParser.collect` の `TokenList.stream()`（残量の約 43%）で、
これは commit 時の token 収集という別の仮説になる。Rust で残ったのは Fragment / CST の `Vec` で、#211 の範囲である。

public facade の 3-run 中央値（ms/op、同一ホスト・直列・他負荷なし）:

| Runtime | Fixture | Baseline runs | Baseline median | Candidate runs | Candidate median | 変化 |
|---|---|---:|---:|---:|---:|---:|
| Java | complex | 324.001 / 321.120 / 315.319 | **321.120** | 178.221 / 178.669 / 178.662 | **178.662** | **-44.36%** |
| Java | comparison-heavy | 144.009 / 144.538 / 134.637 | **144.009** | 88.862 / 88.941 / 91.975 | **88.941** | **-38.24%** |
| Rust | complex | 25.784 / 25.916 / 27.139 | **25.916** | 19.378 / 18.357 / 16.966 | **18.357** | **-29.17%** |
| Rust | comparison-heavy | 3.786 / 3.500 / 3.469 | **3.500** | 2.611 / 2.844 / 2.565 | **2.611** | **-25.40%** |

Java は allocation を 7 割以上削った分が young GC と allocation 帯域の削減としてほぼそのまま時間に現れ、
Rust は `BTreeSet<String>` 挿入と memo hit の clone が消えた分が効いた。採用した。測定条件と raw data は
[TinyExpression の実験レポート](https://github.com/opaopa6969/tinyexpression/blob/master/benchmarks/results/2026-09-21-diagnostic-tracking-alloc-experiment.md)
に保存している。

### 正確性の固定

- Java: 同一 offset で失敗する 2 つの選択肢を、浅い方 → 深い方、深い方 → 浅い方の両順で parse し、報告される
  parse stack が常に深い方で hints が同じ和集合になることを test に固定した（`ParseFailureDiagnosticsTest`）。
  既存の memo diagnostic frame の replay / rebase test、TinyExpression の parity / source mapping test も変更なしで通る。
- Rust: 同一位置・同一 expected の失敗を 1 回と 1,024 回で比較し allocation 数が一致する test
  （`unlaxer-alloc-audit/tests/diagnostic_allocation.rs`）と、custom parser の動的 expected が所有され続ける test を追加した。

### 教材としての要点

「比較のために作って、比較の結果捨てる」オブジェクトは、profile で見ると最も大きな allocation 源になりうる。
snapshot の要素数が既知の量（open frame 数）と一致するなら、まず量で比較し、必要なときだけ実体を作る。
Rust 側の `to_owned()` も同じ形で、set が変わらない挿入のために文字列を確保していた。いずれも診断の
出力契約は変えず、既存の test を「出力が変わっていない」証拠として使う。

## ケース6: 失敗診断の hint 収集を incremental にする

### 仮説

ケース5で診断の allocation を止めた後、TinyExpression public facade の Java CPU を JFR で見ると、
77%（complex.tiny）〜84%（comparison-heavy.tiny）が失敗診断の hint 収集だった。transaction bookkeeping は
6% 未満、parser dispatch は 5% 未満で、unlaxer-parser#209（effect summary）と #210（direct-rule-call）の
上限効果はこの時点では小さい（#209 は着手前 profile を根拠に不採用として記録した）。

hint 収集の内訳は、`addExpectedHintCandidate` が候補リストを線形走査して `String.equals` で重複判定する
コスト、失敗した parser ごとに深さ 2 の BFS（`expectedHintCandidatesFor`）をやり直すコスト、
innermost の `TerminalSymbol` を探して parse stack を走査するコスト、memo hit ごとに診断を copy して
merge するコストだった。これらは「同じ答えを毎回計算し直している」だけで、診断の出力には影響しない。

### 実装

- 順序を保つ `expectedParsers` / `expectedHints` リストの横に key 集合（display hint、`hint|qualifiedClass`）を置き、
  重複判定を O(1) にした。global と memo frame の両方で、clear / restore / merge も同じ helper を通す
- `expectedHintCandidatesFor(parser)` は parser ごとに 1 回だけ計算して cache する。parser graph と
  `TerminalSymbol.expectedDisplayTexts()` は 1 parse の間は不変、という契約を明文化した
- `startParse` / `endParse` で open な `TerminalSymbol` frame の stack を並行して保守し、`deepestTerminalHintCandidate`
  はその stack（通常 0〜2 要素）だけを見る。terminal ごとの候補も cache する
- memo hit の replay は保存済み診断を copy せず、offset と size を先に比較して採用される場合だけ rebased stack を作り、
  expected リストは参照のまま merge する

Rust には hint の BFS は無いが、一時 timer による CPU attribution（complex.tiny、計測付き 29.9 ms/op）では
失敗診断 `fail_at` / `failure()` が 43.7%、memo lookup / replay が 45.9%（inclusive、重複計上あり）で、
Java と同じ「失敗のたびに全 open frame の集合へ記録し、memo hit のたびに remove → replay → insert する」構図だった。
Rust では frame の expected を `BTreeSet<Rc<str>>` から遅延生成の小さな `Rc<Vec<Rc<str>>>` にし、
既に先の位置で失敗している frame は即 skip、重複判定は `Rc::ptr_eq` を先に見てから文字列比較、memo hit は
保存済み診断を O(1) で clone して共有文字列のまま replay する。`ParseError.expected` は生成時に sort + dedup し、
従来の `BTreeSet` 順（文字列昇順）と byte 単位で一致させた。nested frame・負の lookahead・memo hit を含む
grammar で `expected` が sort 済み・重複なしになる test を追加した。

### 観測

allocation（public facade 1 parse）: Java complex.tiny 299 MB → 184 MB（-38.5%）、comparison-heavy.tiny 132 MB → 94 MB（-28.5%）。

CPU 分布（JFR ExecutionSample、depth 8 で分類）:

| Fixture | 診断 | commit の token 収集・listener | transaction bookkeeping | dispatch 等 |
|---|---:|---:|---:|---:|
| complex（before） | 77.3% | 12.7% | 6.0% | 4.1% |
| complex（after） | 66.1% | 13.8% | 11.1% | 9.0% |
| comparison-heavy（before） | 84.4% | 7.7% | 2.7% | 5.3% |
| comparison-heavy（after） | 66.2% | 13.5% | 6.1% | 14.2% |

after でも診断が最大項目で、self frame の上位は `HashMap.putVal`（frontier に届く失敗ごとに、全 open memo frame の
集合へ同じ hint を再登録する）と `registerFailureCandidate` 本体になった。「frontier では (parser, terminal) の pair だけを
記録し、hint への展開は診断が要求されたときに行う」次段を unlaxer-parser#220 として切り出した。

public facade の 3-run 中央値（ms/op、同一ホスト・直列・他負荷なし、baseline はケース5適用後）:

| Runtime | Fixture | Baseline runs | Baseline median | Candidate runs | Candidate median | 変化 |
|---|---|---:|---:|---:|---:|---:|
| Java | complex | 175.103 / 177.431 / 185.600 | **177.431** | 123.616 / 122.938 / 117.131 | **122.938** | **-30.71%** |
| Java | comparison-heavy | 90.602 / 88.884 / 87.172 | **88.884** | 58.933 / 59.413 / 58.704 | **58.933** | **-33.70%** |
| Rust | complex | 16.856 / 17.069 / 17.347 | **17.069** | 10.943 / 11.518 / 10.725 | **10.943** | **-35.89%** |
| Rust | comparison-heavy | 2.476 / 2.507 / 2.667 | **2.507** | 1.845 / 1.906 / 2.017 | **1.906** | **-23.98%** |

Java は hint の再計算・線形走査・stack 走査が消えた分、Rust は frame ごとの `BTreeSet` 操作と memo hit の
map 更新が消えた分が効いた。ケース5と合わせると、#207 時点から Java complex は 321 → 123 ms/op、Rust complex は
27.2 → 10.9 ms/op になった。採用した。測定条件と raw data は
[TinyExpression の実験レポート](https://github.com/opaopa6969/tinyexpression/blob/master/benchmarks/results/2026-09-21-diagnostic-hint-cpu-experiment.md)
に保存している。

### 教材としての要点

allocation を止めても CPU の hotspot は残る。次に見るのは「同じ入力から同じ答えを繰り返し計算している場所」で、
ここでは parser ごとの hint 候補、stack 走査、線形の重複判定だった。cache と集合化は出力を変えないので、既存の
診断 test をそのまま等価性の証拠にできる。ただし cache は「何が不変か」の契約を必ず文書化する
（ここでは parser graph と TerminalSymbol の display text）。

## ケース7: 失敗診断の hint を「出所」だけ記録して遅延展開する

### 仮説

ケース6の後も、Java CPU の約 66% は診断だった。self frame の上位は `HashMap.putVal` で、frontier に届く失敗のたびに、
その parser の全 hint 候補を global と全 open memo frame の key 集合へ再登録し（重複でも hash を計算する）、frontier が
進むたびに clear → 再登録を繰り返していた。

frontier で本当に必要な情報は「どの parser が、どの innermost `TerminalSymbol` の下で失敗したか」の順序付き集合だけである。
hint 候補は parser ごとに固定（ケース6の cache）、terminal hint も terminal ごとに固定なので、この集合から候補リストを
決定的に展開できる。展開順 = 出所の first-seen 順 = 従来の hint の first-seen 順で、重複排除は順序と可換だから、
「診断が要求されたときに展開する」実装は従来と同じリストを返す。

### 実装

- Java: `ExpectedSources`（parser の identity set × failed/terminal の 2 種、first-seen 順）を global と各
  `FailureDiagnostic` が持つ。frontier での失敗は `addFailed(parser)` と `addTerminal(terminalParser)` の 2 回の identity
  set 挿入だけ。memo 化された失敗は出所集合をそのまま保存し、replay は集合の merge。`getParseFailureDiagnostics()` /
  `computeExpectedTokens()` / 負の lookahead の snapshot で要求されたときだけ、cache 済み候補を出所順に展開して
  重複排除する。
- Rust: `fail_at` は innermost の frame と top-level（`ParseContext::failure()` が途中で呼べるので遅延しない）だけを
  更新し、memo 化 rule の frame は pop 時（成功・失敗とも）に親へ max-merge する。子 frame 内の失敗は親から見て
  連続区間なので挿入順は従来と一致し、より遠い子の `Rc<Vec<Rc<str>>>` は共有して親の次回書き込みで copy-on-write
  するため memo 保存内容は変わらない。4 層ネストで成功 pop と失敗 pop を含む test で memo 保存順と
  `ParseError.expected` を固定した。

### 観測

allocation（public facade 1 parse）: Java complex.tiny 184 MB → 181 MB、comparison-heavy.tiny 94 MB → 92 MB（ほぼ不変。
ケース6で既に allocation 源は除かれており、ここは CPU の施策）。

CPU 分布（JFR ExecutionSample、depth 8 で分類、baseline はケース6適用後）:

| Fixture | 診断 | commit の token 収集・listener | transaction bookkeeping | dispatch 等 |
|---|---:|---:|---:|---:|
| complex（before） | 66.1% | 13.8% | 11.1% | 9.0% |
| complex（after） | 56.6% | 20.5% | 9.0% | 13.9% |
| comparison-heavy（before） | 66.2% | 13.5% | 6.1% | 14.2% |
| comparison-heavy（after） | 28.6% | 23.8% | 9.5% | 38.1% |

after の診断 self frame 上位は `IdentityHashMap.put` と `ExpectedSources.addAll`（memo replay の merge と、
frontier 失敗ごとの全 open frame への出所登録）で、「innermost frame にだけ記録し、frame の pop 時に親へ merge する」
（Rust 側でこのケースに採った形）が Java の次段候補になる。

public facade の 3-run 中央値（ms/op、同一ホスト・直列・他負荷なし、baseline はケース6適用後）:

| Runtime | Fixture | Baseline runs | Baseline median | Candidate runs | Candidate median | 変化 |
|---|---|---:|---:|---:|---:|---:|
| Java | complex | 118.594 / 124.915 / 119.034 | **119.034** | 96.369 / 97.635 / 108.313 | **97.635** | **-17.98%** |
| Java | comparison-heavy | 59.471 / 59.703 / 61.601 | **59.703** | 55.029 / 52.264 / 51.858 | **52.264** | **-12.46%** |
| Rust | complex | 12.004 / 12.073 / 11.135 | **12.004** | 5.782 / 5.616 / 5.118 | **5.616** | **-53.22%** |
| Rust | comparison-heavy | 1.895 / 1.918 / 1.955 | **1.918** | 1.398 / 1.297 / 1.316 | **1.316** | **-31.37%** |

Rust の効果が大きいのは、failure ごとの frame 走査が消えたことに加え、memo hit の replay が innermost frame 1 つへの
merge になったためである。Java は memo replay の出所 merge と全 open frame への出所登録が残っており、Rust と同形の
「innermost だけに記録して pop 時 merge」が次段候補になる。採用した。#207 時点からの累積は Java complex 321 → 98 ms/op、
Rust complex 27.2 → 5.6 ms/op。測定条件と raw data は
[TinyExpression の実験レポート](https://github.com/opaopa6969/tinyexpression/blob/master/benchmarks/results/2026-09-21-lazy-hint-materialization-experiment.md)
に保存している。

### 教材としての要点

「毎回同じ答えに展開する」処理は、展開の入力（ここでは出所）だけを記録して展開を後ろへ送ると、記録側は
入力サイズに比例した定数コストになる。等価性は「展開が決定的であること」「展開順が記録順と一致すること」
「重複排除が順序と可換であること」の 3 点で示せ、いずれも既存の診断 test で確認できる。
