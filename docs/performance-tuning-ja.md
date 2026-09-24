# unlaxer パフォーマンスチューニング実践ノート

> 対象: parser combinator、`ParseContext`、生成 parser を実装・改善する読者

unlaxer は最短の parse loop を目標にしたライブラリではない。source position、token/CST、
capture、user state、scope、診断、LSP/DAP へ続く情報を一つの parse で保持する。そのため
チューニングでは、単に transaction を削るのではなく、これらの rollback 不変条件を
壊していないことを速度と同時に測る。

設計そのものの費用とユーザーの利益、機能指定による実行方式の選択については
[パーサの設計コストと機能選択 — 問いと回答](parser-execution-design-qa-ja.md) を参照。
こちらは対話に基づく設計案であり、以下の実測記録とは区別する。

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

## ケース8: Java の memo diagnostic frame を innermost だけ更新し pop 時に merge する

### 仮説

ケース7の後も Java CPU の約 40%（complex.tiny）は `registerFailureCandidate` に残っていた。frontier に届く失敗、
progress、trial、memo replay のたびに **全 open memo frame** を更新しており、コストが memo 化 rule のネスト深さに比例する。
Rust ではケース7で「innermost frame と top-level だけを更新し、frame の pop 時に親へ max-merge する」形にして
complex で -53% だった。Java も同じ形にできるはずである。

### 実装

- 失敗・progress・trial・memo replay は global（`getParseFailureDiagnostics()` が途中で呼ばれうるので遅延しない）と
  `memoDiagnosticFrames.peekFirst()` だけを更新する
- `discardMemoDiagnosticFrame` が popped frame を親へ merge する: farthest / maxReached は max、同点なら深い stack、
  出所集合は `ExpectedSources.addAll`、trials は append
- frame の stack を absolute snapshot（失敗時点の `snapshotStackElements()` を共有）で保持し、rule-local な suffix は
  memo 保存後の replay 時に `stackBaseDepth` からの subList で得る。子の stack をそのまま親へ渡せる
- relevance の事前判定は global と innermost の farthest だけを見る（outer の farthest は常に inner 以上）

等価性: frame 内容は max-union で結合的・可換。子 frame 内の事象は親から見て連続区間なので、pop 時 merge でも
挿入順は「全 frame へ即時記録」と一致する。成功 pop と失敗 pop を含む 3 層ネストの test で、outer frame の内容と
memo hit の replay 結果が初回の診断と一致することを固定した。Rust はケース7で同設計済みのため変更なし。

### 観測

allocation（public facade 1 parse）: Java complex.tiny 181 MB → 165 MB、comparison-heavy.tiny 92 MB → 86 MB。

CPU 分布（JFR ExecutionSample、depth 8、baseline はケース7適用後）:

| Fixture | 診断 | commit の token 収集・listener | transaction bookkeeping | dispatch 等 |
|---|---:|---:|---:|---:|
| complex（before） | 56.6% | 20.5% | 9.0% | 13.9% |
| complex（after） | 35.3% | 29.4% | 9.8% | 25.5% |
| comparison-heavy（before） | 28.6% | 23.8% | 9.5% | 38.1% |
| comparison-heavy（after） | 21.2% | 25.0% | 7.7% | 46.2% |

診断は最大項目ではなくなり、commit 時の token 収集（`CollectingParser.collect` と `TokenList.toSource` の Stream、
`TokenList.isEmpty`）と parser dispatch が並ぶ。#218 は allocation 基準で不採用だったが、CPU 基準では再評価の対象になる。

public facade の 3-run 中央値（Java、ms/op、同一ホスト・直列・他負荷なし、baseline はケース7適用後。Rust runtime は変更なし）:

| Runtime | Fixture | Baseline runs | Baseline median | Candidate runs | Candidate median | 変化 |
|---|---|---:|---:|---:|---:|---:|
| Java | complex | 102.182 / 103.553 / 99.720 | **102.182** | 73.519 / 74.945 / 78.742 | **74.945** | **-26.66%** |
| Java | comparison-heavy | 47.588 / 46.877 / 48.770 | **47.588** | 40.684 / 39.708 / 39.473 | **39.708** | **-16.56%** |

採用した。#207 時点からの累積は Java complex 321 → 75 ms/op（-77%）、comparison-heavy 135 → 40 ms/op（-71%）。
測定条件と raw data は
[TinyExpression の実験レポート](https://github.com/opaopa6969/tinyexpression/blob/master/benchmarks/results/2026-09-21-innermost-frame-merge-experiment.md)
に保存している。

### 教材としての要点

「全部の frame に即時記録する」のは実装が素直だが、記録先の数がネスト深さに比例する。結合的・可換な merge で
表せる情報なら、innermost にだけ記録して境界（pop）で親へ畳み込めば、正しさは merge の代数で示せる。
Java/Rust で同じ設計を採ると、片側で書いた等価性の議論と test をもう片側にそのまま流用できる。

## ケース9: commit 時と parse 後の token 経路から Stream pipeline を外す

### 仮説

ケース8の後、Java CPU の 25〜30% は「commit の token 収集・listener」に分類された。JFR の呼び出し元を深い stack で
見ると、`CollectingParser.collect`（毎 commit の Stream + 中間 List + コピー）、`Token` のコンストラクタ（子の parent 設定と
AST 子フィルタで Stream 2 本）、`TokenList.toSource`（防御的コピーと Stream join）、そして parse 後に毎 token を訪れる
`AbstractTokenReducer.reduce` の Stream だった。#218 は allocation 基準（差 1%）で一度不採用にしたが、
Stream pipeline の CPU コストは allocation とは別に効くため、CPU 基準で再評価した。

### 実装

4 箇所を事前サイズ付きの `TokenList` と添字ループに置き換えた。Token の内容・順序・kind・parent・source は不変で、
既存の Token/CST test と TinyExpression の parity / source mapping test をそのまま等価性の証拠にした。
Rust の commit 経路は `Fragment.nodes.append` で中間物が無く、reduce に相当する段も無いため変更なし。

### 観測

allocation（public facade 1 parse）: complex.tiny 165.0 MB → 151.3 MB（-8.3%）、comparison-heavy.tiny 86.3 MB → 78.3 MB（-9.3%）（JMH `gc.alloc.rate.norm`、精密）。

public facade の 3-run 中央値（Java、ms/op、同一ホスト・直列、baseline はケース8適用後）:

| Runtime | Fixture | Baseline runs | Baseline median | Candidate runs | Candidate median | 変化 |
|---|---|---:|---:|---:|---:|---:|
| Java | complex | 77.836 / 76.848 / 75.458 | **76.848** | 71.632 / 71.858 / 77.740 | **71.858** | **-6.49%** |
| Java | comparison-heavy | 40.461 / 40.613 / 41.461 | **40.613** | 37.474 / 39.221 / 43.641 | **39.221** | **-3.43%** |

同じ candidate の前段（`collect` と `toSource` のみ変更）を別セッションで測った 1 回目の A/B は complex -10.20%、
comparison-heavy -6.23% だった。run 間の幅（baseline 75.5〜83.2 ms）に対して差は小さいが、2 セッション計 12 run で方向が
一貫し、allocation は精密に 8〜9% 減っているため採用した。timing の効果は「5% 前後」と見るのが妥当で、
ケース5〜8のような大きな短縮ではない。

### 教材としての要点

allocation 基準で「差が無い」と判断した候補でも、CPU 基準では別の結果になりうる。Stream pipeline は
1 回あたりの allocation は小さいが、per-commit / per-token の hot path では lambda 呼び出しと spliterator の
オーバーヘッドが積み上がる。採否の指標を切り替えるときは、その理由（どの profile がどう変わったか）を issue に残す。

## ケース10: direct-rule-call execution tier（不採用）

### 仮説

生成 parser も汎用 combinator graph（Java）/ `Expr` interpreter（Rust）を通るため、rule ごとに専用関数を生成すれば
dispatch のコストを減らせる、というのが unlaxer-parser#210 の仮説だった。ケース9までの profile で Rust の残余
（dispatch・再帰）は 47%、その self-time の 40% が `Expr::Rule` / `rule()` だった。

### 実装（Rust、opt-in、参照用 branch `perf/direct-rule-tier-210`）

- runtime: `ExecutionTier::{Combinator, Direct}`（既定 Combinator）、`DirectRuleTable`、opaque `DirectFragment`、
  `parse_shared_grammar_with_direct`。rule wrapper は depth・memo・diagnostic frame・CST node を既存 `rule()` と同順序で
  行い、body だけを生成関数へ委譲する
- codegen: native と Java `RustBackend` が同一出力（byte parity）。rule 1 つ = 関数 1 つ、入れ子式はインライン展開、
  512 式で分割。未対応要素（`RuleEffects`、longest/predictive choice、separated、lookahead token、custom）は rule 単位で
  interpreter へ fallback し、生成物冒頭に `directRules` / `fallbackRules[{rule, element, reason}]` を出力
- test: combinator と direct の観測等価（結果・cursor・`expected`・CST・capture・checkpoint metrics、memo OFF / SafeFailures、
  fallback 往復）

### 観測

TinyExpression public facade（Criterion、tier ごとに 3 run の中央値）: complex 5.116 → 4.972 ms（-2.8%）、
comparison-heavy 1.297 → 1.297 ms（±0）。生成 `parser.rs` は 44 KB → 209 KB（関数 9 → 134）、cold release build は
3.5 s → 7.8 s。第 1 段（sub-expression ごとに関数を切り出す形）は complex で +11% 悪化、生成物 356 KB だった。

### 判断

不採用。memo 25%、checkpoint 12%、CST 8% は direct 化でも不変で、削れるのは enum dispatch と間接参照だけだった。
生成量と build 時間のコストに対して timing 効果は run 間ノイズ相当。Java 側は JIT が virtual dispatch を最適化するため、
Rust の結果から期待効果が小さいと判断して着手しなかった。実装と等価性 test は branch に残し、profile が変わった場合に
再評価する。

### 教材としての要点

「dispatch を消せば速くなる」は、dispatch の下にある仕事（memo・checkpoint・CST 構築）が小さいときだけ成り立つ。
着手前に self-time の内訳を見て、direct 化で**消える部分**だけの上限を見積もる。生成コードを増やす施策は、
i-cache と compile 時間という別のコストを同時に測る。
## ケース11: safe-failure memo の probe コスト

### 仮説

ケース7の後の Rust CPU attribution で、memo の lookup / insert / frame / replay が 24.9%（complex.tiny）、22.0%
（comparison-heavy.tiny）と最大の runtime カテゴリになり、ケース10の direct tier でも「direct 化しても残るコスト」として
現れた。内訳を一時 timer で分けると、7 フィールドの `FailureMemoKey` を SipHash で hash する時間と insert が主だった。
Java 側は `PackratMemoTable.isExactSafeClass` が `Class.getInterfaces()`（呼び出しごとに配列を複製）を memo 化 rule
1 回につき最大 3 回呼び、`isMemoizationSessionSafe` と `checkpointTransactionalState` が probe ごとに Stream pipeline を
作っていた。

### 実装

- Rust: key を `rule` / `position` / `matched_position` / `depth*2+whitespace` の 4 ワードに縮小した。`grammar` と
  `session` は `parse_shared_grammar` が memo table を `std::mem::take` で差し替えて復元するため、key に無くても
  entry が混ざらない。hasher は外部 crate なしの軽量 mixer を `HashMap` に指定。`depth`（再帰上限で同位置でも結果が
  変わる）と `whitespace`（`TriviaScope` で変わる）は残した。残る各次元が entry を区別する test を追加
- Java: exact-class 判定を `ClassValue` で class ごとに cache し、Stream をループ化

### 観測

Rust の memo カテゴリ（一時 timer、1 parse）: complex 4.32 → 3.83 ms（-11%）、comparison-heavy 1.41 → 1.05 ms（-25%）。
hash が 852 → 479 µs、insert が 929 → 751 µs。memo hit 数は before/after で一致（complex 6,440、comparison-heavy 496）。
Java allocation: complex 151 → 133 MB/op、comparison-heavy 78 → 68 MB/op（`getInterfaces()` の配列複製が消えた分）。

public facade の 3-run 中央値（ms/op、同一ホスト・直列・他負荷なし、baseline はケース9適用後）:

| Runtime | Fixture | Baseline runs | Baseline median | Candidate runs | Candidate median | 変化 |
|---|---|---:|---:|---:|---:|---:|
| Java | complex | 74.840 / 71.466 / 72.681 | **72.681** | 74.318 / 78.513 / 68.789 | **74.318** | **+2.25%** |
| Java | comparison-heavy | 38.942 / 39.006 / 39.853 | **39.006** | 36.788 / 36.313 / 35.468 | **36.313** | **-6.90%** |
| Rust | complex | 5.157 / 5.702 / 5.886 | **5.702** | 4.250 / 4.537 / 4.368 | **4.368** | **-23.40%** |
| Rust | comparison-heavy | 1.332 / 1.351 / 1.480 | **1.351** | 1.056 / 1.074 / 1.027 | **1.056** | **-21.85%** |

Rust は両 fixture で 20% 以上短縮した。Java は comparison-heavy で -6.9%、complex は +2.3%（candidate の run 幅 68.8〜78.5 ms
に対してノイズ内）で、timing は中立と見る。Java 側の変更は allocation -12% と reflection の配列複製除去が主で、
採用したのは Rust の効果と Java の allocation 削減による。測定条件と raw data は
[TinyExpression の実験レポート](https://github.com/opaopa6969/tinyexpression/blob/master/benchmarks/results/2026-09-21-memo-lookup-cost-experiment.md)

## ケース12: StringSource の構築コスト

### 仮説

ケース9の後の Java allocation（約 151 MB/op）の 26% が `String.codePoints()` の IntStream で、呼び出し元は
`StringSource` のコンストラクタだった。commit ごとの sub-source 生成と、空 token の detached source 生成のたびに
IntStream pipeline を作り、空文字列でも `PositionResolverImpl`（code point ごとの HashMap 群）を新規に構築していた。
CST 表現の compact 化（#211）を考える前に、source 構築そのものの無駄を外せるはずである。

### 実装

- `codePoints()` の IntStream を、`codePointCount` で exact-size の配列を確保して `codePointAt` で埋めるループに置換
- 空文字列の root/detached source は不変な resolver を 1 つ共有（構築後に変更されないことを確認）
- 空配列の定数は holder class に置く。`Source.EMPTY` が `StringSource` の静的初期化中に構築されるため、`StringSource`
  自身の static field ではまだ null になる（unlaxer-dsl の全 test が `ExceptionInInitializerError` で落ちて発見）

Rust は `&str` スライスと `byte_offsets` の binary search で相当処理が allocation-free のため変更なし。

### 観測

allocation（public facade 1 parse）: Java complex.tiny 151 MB → 130 MB（-14%）、comparison-heavy.tiny 78 MB → 64 MB（-17%）。
残りの最大項目は診断 stack snapshot の `ParseStackElement`（44%、unlaxer-parser#229）。

public facade の 3-run 中央値（Java、ms/op、同一ホスト・直列、baseline はケース9適用後）:

| Runtime | Fixture | Baseline runs | Baseline median | Candidate runs | Candidate median | 変化 |
|---|---|---:|---:|---:|---:|---:|
| Java | complex | 68.846 / 68.094 / 69.997 | **68.846** | 58.853 / 63.884 / 62.837 | **62.837** | **-8.73%** |
| Java | comparison-heavy | 37.020 / 36.335 / 38.193 | **37.020** | 32.667 / 32.526 / 31.444 | **32.526** | **-12.14%** |

採用した。測定条件と raw data は
[TinyExpression の実験レポート](https://github.com/opaopa6969/tinyexpression/blob/master/benchmarks/results/2026-09-21-string-source-construction-experiment.md)
に保存している。

### 教材としての要点

hash key に「実質的に定数」のフィールドを入れると、hash コストは払うのに識別には寄与しない。境界（ここでは
grammar / session ごとの table 差し替え）が既に分離を保証しているなら key から外せる。ただし外す前に「同じ位置で
結果が変わり得る次元」（depth、whitespace）を列挙して test に固定する。Java の `getInterfaces()` のように、
呼び出しごとに配列を複製する reflection API は hot path では cache する。
Stream API は「1 回」なら安いが、per-token の構築経路では pipeline オブジェクトと spliterator が allocation の
主役になる。allocation-by-site で `IntPipeline$Head` のような Stream 内部クラスが上位に来たら、呼び出し元の
ループ化を疑う。静的初期化の循環（interface の定数が実装クラスを構築する）は unit test の初期化順で隠れることがあり、
別モジュールの test で初めて現れる。

## ケース13: 診断 stack snapshot をプリミティブ配列で保持する

### 仮説

ケース12の後の Java allocation（約 130 MB/op）の 44% が `ParseStackElement` だった。frontier が進むたびに
`snapshotStackElements()` が open frame ごとに不変オブジェクトを生成し、その snapshot の大半は次の前進で捨てられる。
ケース5で「比較して捨てる snapshot」は無くしたが、「採用したのに読まれない snapshot」が残っていた。

### 実装

snapshot を `StackSnapshot`（`Parser[]` と frame ごと 3 つの offset を持つ `int[]`、root first）にし、memo の rebase は
配列の連結、`ParseStackElement` への展開は `getParseFailureDiagnostics()` が呼ばれたときだけ行う。depth は index、
offset は snapshot 時点の値をコピーするので、報告される stack は従来と同一である。Rust はケース7で snapshot 自体を
持たない設計にしているため変更なし。

### 観測

allocation（public facade 1 parse、baseline はケース9適用後）: complex.tiny 151 MB → 131 MB、comparison-heavy.tiny 78 MB → 71 MB。
適用後の最大項目は transaction frame の cursor（`EndExclusiveCursorImpl` ← `ParserCursor` ← `TransactionElement.createNew`、27%）で、
ケース4で 1.4% だった frame allocation が他の削減の結果として相対的に最大になった（unlaxer-parser#208 の再評価対象）。

public facade の 3-run 中央値（Java、ms/op、同一ホスト・直列、baseline はケース12適用後）:

| Runtime | Fixture | Baseline runs | Baseline median | Candidate runs | Candidate median | 変化 |
|---|---|---:|---:|---:|---:|---:|
| Java | complex | 63.133 / 61.312 / 62.163 | **62.163** | 62.168 / 61.212 / 60.303 | **61.212** | **-1.53%** |
| Java | comparison-heavy | 32.486 / 34.520 / 33.923 | **33.923** | 31.832 / 31.203 / 30.015 | **31.203** | **-8.02%** |

comparison-heavy は baseline（32.5〜34.5 ms）と candidate（30.0〜31.8 ms）の run が分離し -8%、complex は -1.5% でノイズ内。
allocation は精密に -13% 減っており、採用した。timing 効果は fixture により 0〜8% と見積もる。測定条件と raw data は
[TinyExpression の実験レポート](https://github.com/opaopa6969/tinyexpression/blob/master/benchmarks/results/2026-09-21-lazy-stack-snapshot-experiment.md)
に保存している。

### 教材としての要点

同じ情報でも「オブジェクトの列」と「配列 2 本」では allocation 数が frame 数倍違う。読まれるまで展開を遅らせると、
捨てられる snapshot のコストは配列 2 本分になる。allocation の主役は削るたびに入れ替わるので、1 施策ごとに
allocation-by-site を取り直して次を決める。

## ケース14: transaction frame pool（再評価、不採用）

### 仮説

ケース13の後の JFR allocation-by-site で、transaction frame の cursor（`EndExclusiveCursorImpl` ← `ParserCursor` ←
`TransactionElement.createNew`）が weight 27% と最大に見えた。ケース4では 1.4% で不採用にした frame 再利用を、
popped frame を ParseContext ごとの pool で再利用する形で再評価した（branch `perf/transaction-frame-pool-208`）。

### 観測

master `1da37ce` を baseline にした Java public facade の 3-run 中央値は complex +1.1%、comparison-heavy +3.6%（ノイズ内、改善なし）。
精密な `gc.alloc.rate.norm` の前後差は complex -5.5%、comparison-heavy -7.3% で、JFR の 27% とは大きく違った。
Java 672 + 987 tests と TinyExpression p4-smoke は成功。

### 判断

不採用。frame 生成は JIT の escape analysis と TLAB で十分安く、pool の分岐・reset コストと相殺した。
ケース4の判断（frame allocation は小さい）は正しく、JFR の比率が 2 度目の過大評価だった。

### 教材としての要点

allocation-by-site の比率は「候補の発見」に使い、採否は必ず精密な `gc.alloc.rate.norm` の前後差と timing の A/B で決める。
同じ罠に 2 回かかったので、以後は候補 issue を立てる前に前後差を取る。

## ケース15: scope 状態の rollback を mutation journal にする

### 仮説

ケース3の copy-on-write は「最初の mutation まで snapshot を遅らせる」設計だが、mutation が起きた frame では
scope state 全体（Java: scope map の stack、global map、宣言・参照・診断の 3 リスト。Rust: `ScopeStore` の deep copy）を
複製していた。TinyExpression complex.tiny 1 parse で Java 約 1,300 回、Rust 701 回。ケース14の profile で
`HashMap.putMapEntries` ← `ScopeStore$State.checkpoint` が最大項目に見えたため、#214 Phase 5 の条件が成立したと判断し、
unlaxer-parser#213 の「1 領域だけ」の規約に従って scope だけを journal 化した。

### 実装

- Java `ScopeStore.State`: 各 mutation（enter / leave / declare / addDiagnostic / clearDiagnostics / addReference）が
  逆操作を journal に append する。`checkpoint()` は journal 長の mark を持つ Runnable を返し、rollback は mark まで
  逆順に undo して切り詰める。nested commit は entry を残すので外側の rollback が undo できる。undo は live な
  map / list を in-place で戻すため、既存の unmodifiable view は従来どおり rollback を観測する
- Rust: `ParseContext.scopes` を `Rc<ScopeStore>` から直接所有にし、`Checkpoint.scopes` の `Rc` clone を journal mark に置換。
  entry は `PopScope` / `PushScope` / `Declare{previous}` / `TruncateReferences` / `TruncateDiagnostics` / `RestoreDiagnostics`。
  journal は最外の checkpoint が終わると破棄する（長寿命 ParseContext で無制限に増えない）。`LongestChoice` は候補ごとに
  journal 込みで store を clone し、勝者を丸ごと採用する。capture / user state は COW のまま、`scope_journal_entries`
  counter を追加（既存 metric の意味は不変）

### 観測

Rust counter（complex.tiny 1 parse）: COW deep copy 1,621 → 920（scope 由来 701 → 0）、journal entry 709。他の checkpoint metrics は一致。
Java allocation（public facade 1 parse）: complex.tiny 89.0 MB → 83.0 MB（-6.8%）、comparison-heavy.tiny は scope が無く ±0。

Timing（public facade、3-run 中央値、ms/op。Java は 2 セッション実施）:

| Runtime | Fixture | Baseline | Candidate | 変化 |
|---|---|---:|---:|---:|
| Rust | complex | 4.988 | 4.089 | **-18.0%** |
| Rust | comparison-heavy | 1.318 | 1.034 | **-21.5%** |
| Java（session 1） | complex | 51.436 | 50.510 | -1.8% |
| Java（session 1） | comparison-heavy | 28.370 | 30.790 | +8.5% |
| Java（session 2） | complex | 51.871 | 50.324 | -3.0% |
| Java（session 2） | comparison-heavy | 28.815 | 29.177 | +1.3% |

Rust は両 fixture で 18〜22% 短縮。comparison-heavy.tiny は scope を使わないのに速くなっているのは、
`Rc<ScopeStore>` の clone / drop と `Checkpoint` の payload が checkpoint ごとに消えたためで、journal の効果は
mutation 頻度だけでなく「checkpoint の固定費」にも及ぶ。Java は complex.tiny で allocation -6.8% に対し timing は
-2〜-3% と小さく、comparison-heavy.tiny は session 1 の +8.5% が session 2 で +1.3% に収まった（scope に触らない
fixture で変化する経路が無いため noise と判断）。採用: Rust は timing、Java は allocation と complex.tiny timing を根拠とする。

### 教材としての要点

copy-on-write は「変更が無い frame」を安くするが、「変更がある frame」のコストは snapshot サイズに比例する。
mutation journal は逆に「変更の数」に比例する。どちらが有利かは mutation 頻度と状態サイズで決まり、profile で
deep copy 回数と状態サイズを見てから選ぶ。journal は nested commit / outer rollback、longest choice の勝者採用、
長寿命 context での履歴破棄という 3 つの境界条件を test に固定してから速度を測る。

## ケース16: parse stack snapshot を persistent な親リンク構造にする

### 仮説

ケース13で snapshot を `Parser[]` + `int[]` にしたが、frontier が前進する・同じ offset でより深くなるたびに open frame 全部を
配列に写していた。#213 後の再 profile では JFR の site 比率が comparison-heavy.tiny で 47.6% と出たが、ケース14の教訓どおり
一時カウンタで精密に数えると、complex.tiny で 25,224 回・平均深さ 41.9・約 19.5 MB（総 allocation の 23.5%）、
comparison-heavy.tiny で 17,269 回・平均深さ 21.7・約 7.1 MB（15.3%）だった。呼び元の大半は memo diagnostic frame 側の
「同 offset でより深い」（complex 11,396 回）で、`int[]`（frame ごとに 3 int）が `Parser[]` の約 2.8 倍を占めていた。
連続する snapshot は外側の frame がほとんど共通なので、frame ごとの不変 node を親リンクで繋げば割当は「変化した frame の数」に
比例するはずだと考えた。

### 実装

- `StackSnapshot` を `{parent, parser, startOffset, maxConsumedOffset, maxMatchedOffset, size}` の不変 node にし、`EMPTY` を
  size 0 の終端にする。`concat(suffix, base)` は suffix の base 以降の node を再親付け、`materialize()` は chain を root 先頭に展開する
- `ParseFrame` に下の frame への参照 `below` と cached `snapshot` を持たせ、`updateMax` で値が変わったときだけキャッシュを捨てる。
  `updateMax` は `startParse` / `endParse` / `trackCursorProgress` のいずれも stack top の frame にしか適用しないので、外側 frame の
  キャッシュが内側の node から参照されたまま無効になることは無い
- `snapshotStackElements()` は top frame の `snapshot()` を返すだけになり、再帰は「前回の変更以降 node を作っていない frame の数」で止まる

### 観測

Java allocation（JMH `gc.alloc.rate.norm`、public facade 1 parse）: complex.tiny 82,964,099 → 65,641,335 B（-20.9%）、
comparison-heavy.tiny 46,361,449 → 39,651,879 B（-14.5%）。精密計測の見積もり（23.5% / 15.3%）とほぼ一致した。

Timing（Java public facade、3-run 中央値、ms/op、baseline = master `11d3239`）:

| Runtime | Fixture | Baseline runs | Baseline median | Candidate runs | Candidate median | 変化 |
|---|---|---:|---:|---:|---:|---:|
| Java | complex | 50.725 / 48.978 / 49.919 | **49.919** | 44.610 / 42.016 / 41.936 | **42.016** | **-15.83%** |
| Java | comparison-heavy | 28.650 / 28.927 / 27.972 | **28.650** | 25.770 / 25.797 / 25.627 | **25.770** | **-10.05%** |

両 fixture で baseline と candidate の run が完全に分離した（complex 49.0〜50.7 ms 対 41.9〜44.6 ms、comparison-heavy 28.0〜28.9 ms 対
25.6〜25.8 ms）。allocation -21% / -15% に対して timing -16% / -10% と、ケース13（allocation -13% で timing -8%）と同じく
割当削減がほぼ比例して時間に効いた。採用。

### 教材としての要点

「配列に写す」snapshot は O(深さ) の割当を毎回払う。stack のように変化が top に限られる構造では、不変 node の親リンク
（persistent stack）にすると共有部分を再利用でき、割当は差分だけになる。このとき「誰が値を変えるか」を洗い出して、キャッシュを
無効化する箇所が top 以外に無いことを確認してから採用する。JFR の site 比率は 3 度目も過大で、精密カウンタの見積もりは実測と一致した。

## ケース17: memo hit 時の失敗診断を 1 件ずつでなく一括で replay する（Rust）

### 仮説

#213 後の Rust runtime を領域別に計測すると（`Instant` の一時 instrumentation、非計測 wall time を分母）、failure diagnostic の
record / merge / replay が complex.tiny で 29.4%、comparison-heavy.tiny で 28.6% と最大の直接計測領域だった。1 parse あたり
complex の diagnostic record 42,358 件のうち 33,873 件（約 80%）が safe-failure memo hit 時の replay で、memoized
`FailureDiagnostic` の `expected` を 1 件ずつ `fail_at_shared` に戻し、そのたびに farthest の比較と最内 diagnostic frame の
線形走査をやり直していた。memoized の `expected` は初出順・重複なしで確定しているので、diagnostic 単位でまとめて merge
できるはずだと考えた。

### 実装

- `FailureDiagnostic::merge` を一括 merge にする: memoized の farthest が先なら `Rc<Vec<Rc<str>>>` を共有して置き換え、同じ offset なら
  既存順を保って不足分だけ追記（`append_missing_expected`）、手前なら何もしない。同じ `Rc` を指していれば短絡する
- `ParseContext::replay_failure` を追加し、memo hit 時に global 診断と最内の開いている memo frame をそれぞれ 1 回ずつ更新する。
  1 件ごとの `fail_at_shared` 呼び出しは無くなった
- 公開 API は不変。`ParseError.expected` の内容・初出順・重複除去を、farthest が前進する hit / 同位置の hit / 手前の hit / 順序保持の
  4 test で固定した。`unlaxer-alloc-audit` の診断 allocation 契約（1 failure / 1024 identical failures とも 11 allocations）は維持

### 観測

Timing（Rust public facade、Criterion 3-run 中央値、ms/op、baseline = master `11d3239` の pin、candidate = worktree path patch）:

| Runtime | Fixture | Baseline runs | Baseline median | Candidate runs | Candidate median | 変化 |
|---|---|---:|---:|---:|---:|---:|
| Rust | complex | 4.030 / 4.058 / 4.347 | **4.058** | 3.703 / 3.690 / 3.673 | **3.690** | **-9.08%** |
| Rust | comparison-heavy | 1.092 / 1.031 / 1.031 | **1.031** | 0.956 / 0.998 / 0.968 | **0.968** | **-6.09%** |

両 fixture で run が分離した（complex 4.03〜4.35 ms 対 3.67〜3.70 ms、comparison-heavy 1.03〜1.09 ms 対 0.96〜1.00 ms）。
memo hit 率が高い complex.tiny（46%）の方が効果が大きい。診断領域 29% のうち replay 分（record の約 80%）を潰して 9% なので、
残りは record / merge 本体と memo miss 側の記録コスト。採用。

### 教材としての要点

memo に「結果」を保存しているのに、hit 時にそれを「もう一度 1 件ずつ起こし直す」と、保存した分の仕事を毎回やり直すことになる。
保存済みデータがすでに正規形（順序・重複なし）であることを test で固定できるなら、replay は集合演算 1 回に潰せる。
`Rc` の共有で「同じ列をもう一度持つ」コピーも避けられるが、共有した列に後から追記するときの COW（`Rc::make_mut`）が
allocation 契約を壊さないかは audit crate で確認する。

## ケース18: 失敗時に状態を変えない原子式では checkpoint を開かない（Rust、不採用）

### 仮説

#213 後の領域別 CPU 計測で checkpoint / commit / rollback は complex.tiny 7.0%、comparison-heavy.tiny 3.5%。1 parse あたり
checkpoint は 53,984 回（complex）/ 15,133 回（comparison-heavy）で、`ParseContext::expression` が全ての `Expr` に対して
`checkpoint()` → `expression_inner()` → `restore()` / `commit_checkpoint()` を行っていた。`Literal` / `Number` / `Identifier` /
`Eof` / 文字クラス（`Any` / `CharRange` / `Except`）/ `Until` / `Backreference` / `Error` / `JavaLookahead` など、失敗経路で
position・matched_position・nodes・captures・state・scopes のどれにも触らない原子式では、checkpoint は「何も戻さない」ための
固定費（captures / state の `Rc` clone、scope journal の mark、metrics）になっている。

### 実装

- `Expr::fails_without_side_effects()` を追加し、`expression()` の先頭でこれに該当する式は `expression_inner()` を直接呼ぶ
- 対象外: `Quoted` / `CodeStart` / `CodeEnd`（途中まで position を進めてから失敗し得る）、`Custom`（自前で transaction を開く）、
  複合式（子が復元を要する）
- differential test: 各対象式について「失敗後の `ParseError`、position、matched_position、user state、scope 深さ」が
  同じ式を `Sequence` で包んで checkpoint 経由にした場合と一致することを固定。`Quoted` が wrapper を保っている（position が 0 に戻る）
  ことも固定。`CheckpointMetrics.opened` は実際に開いた数として意味を保ち、対象式では増えない

### 観測

Timing（Rust public facade、Criterion 3-run 中央値、ms/op、baseline = master `b21a965` の pin、candidate = worktree path patch、2 セッション）:

| Runtime | Fixture | Baseline runs | Baseline median | Candidate runs | Candidate median | 変化 |
|---|---|---:|---:|---:|---:|---:|
| Rust（session 1） | complex | 3.869 / 3.847 / 4.077 | **3.869** | 3.845 / 3.744 / 3.740 | **3.744** | -3.23% |
| Rust（session 1） | comparison-heavy | 1.025 / 1.042 / 0.988 | **1.025** | 1.002 / 0.986 / 1.015 | **1.002** | -2.27% |
| Rust（session 2） | complex | 3.800 / 3.818 / 3.748 | **3.800** | 3.829 / 3.762 / 3.818 | **3.818** | +0.47% |
| Rust（session 2） | comparison-heavy | 1.002 / 1.004 / 1.002 | **1.002** | 1.032 / 1.000 / 0.986 | **1.000** | -0.18% |

session 1 の -3.2% / -2.3% は run の範囲が重なっており、session 2 では +0.5% / -0.2% と消えた。改善なし。
差分は小さく test も揃っているが、「効果が測れない変更は入れない」の規約に従って**不採用**とし、実装は branch
`perf/elide-atom-checkpoint-239` に残す。

原子式 1 回の checkpoint は、captures / state が空なら `Rc` clone も無く、scope journal の mark は整数 1 つ、metrics は無効時 `None`
なので、コンパイル後はほぼ構造体の初期化だけになっていた。着手前計測の「checkpoint 領域 7.0%」は `Instant` 区間の固定費（16〜18 ns）が
数 ns の処理に対して相対的に大きく、控除しきれずに過大に見えていたと考えられる。

### 教材としての要点

「全ての式を transaction で包む」は安全側の既定として正しく、失敗経路を読めば「戻すものが無い」式は列挙できる。だが省いて得られる
のは checkpoint 1 回の固定費で、それが数 ns まで最適化されていれば 5 万回省いても測れない。`Instant` による領域別計測は、
区間の処理が計測固定費と同じオーダーのとき（checkpoint のような小さな操作）に比率を過大に見せる。JFR の allocation 比率と同じく、
小さな操作の比率は「候補の発見」に留め、採否は必ず timing の A/B（できれば 2 セッション）で決める。包んだ場合と包まない場合の
結果一致を固定した differential test と、包んだままにする式の境界を残した test は、branch に保存して将来の判断材料にする。

## ケース19: capture の rollback を mutation journal にする（Rust、入力サイズに対する超線形の修正）

### 仮説

TinyExpression の fixture を 4 / 16 / 64 倍に伸ばして public facade を測ると、Rust は入力 63〜64 倍に対して 172 倍 / 193 倍の
時間がかかった（x16 → x64 区間で n^1.3〜1.6）。コードを読むと `Expr::Capture` が `captures_mut_map()`（`Rc::make_mut`）で
capture 全体の `HashMap<String, Vec<Span>>` を deep copy しており、`Sequence` の各要素が checkpoint で captures の `Rc` を掴むため、
capture 追加のほぼ毎回「それまでの全 capture 数」に比例するコピーが起きていた。ケース15で scope に使った mutation journal を
capture にも適用すれば、コピーは消えて rollback は「取り消す capture の数」に比例するはずだと考えた。

### 実装

- `CaptureStore { values, journal: Vec<&'static str>, checkpoint_depth }` を直接所有し、`push` は checkpoint 内なら name を journal に積む。
  `checkpoint()` は journal 長の mark、`rollback_checkpoint(mark)` は mark まで逆順に最後の span を pop（空になれば entry を消す）、
  `commit_checkpoint()` は最外で journal を破棄
- `Checkpoint.captures` の `Rc` clone を `captures_journal_mark` に置換。`LongestChoice` はケース15と同じく候補ごとに store を clone し勝者を採用
- 追加 test: 失敗 transaction 内の capture が rollback で消える、nested commit → outer rollback、同名 capture の順序、LongestChoice の敗者候補の
  capture が残らない。`copy_on_write_deep_copies` は capture 由来が 0 になる

### 観測

Timing（Rust public facade、Criterion、baseline = master `b21a965` の pin、candidate = worktree path patch）。base fixture は 3-run 中央値:

| Runtime | Fixture | Baseline runs | Baseline median | Candidate runs | Candidate median | 変化 |
|---|---|---:|---:|---:|---:|---:|
| Rust | complex | 3.710 / 3.862 / 4.101 | **3.862** | 3.013 / 3.122 / 3.106 | **3.106** | **-19.58%** |
| Rust | comparison-heavy | 1.005 / 1.010 / 0.994 | **1.005** | 0.932 / 0.883 / 0.881 | **0.883** | **-12.18%** |

x4 / x16 / x64（candidate 1 run、baseline はスケーリング計測の値）:

| Fixture | bytes | サイズ倍率 | baseline ms/op（倍率） | candidate ms/op（倍率） | 変化 |
|---|---:|---:|---:|---:|---:|
| complex | 332 | 1.0x | 3.794（1.0x） | 3.013（1.0x） | -20.6% |
| complex-x4 | 1,293 | 3.9x | 18.829（5.0x） | 14.653（4.9x） | -22.2% |
| complex-x16 | 5,179 | 15.6x | 110.569（29.1x） | 77.340（25.7x） | -30.1% |
| complex-x64 | 20,923 | 63.0x | 654.400（172.5x） | 470.704（156.2x） | -28.1% |
| comparison-heavy | 179 | 1.0x | 0.991（1.0x） | 0.932（1.0x） | -6.0% |
| comparison-heavy-x4 | 716 | 4.0x | 4.030（4.1x） | 3.720（4.0x） | -7.7% |
| comparison-heavy-x16 | 2,864 | 16.0x | 21.968（22.2x） | 19.022（20.4x） | -13.4% |
| comparison-heavy-x64 | 11,456 | 64.0x | 191.505（193.2x） | 150.774（161.8x） | -21.3% |

base fixture で -19.6% / -12.2% と明確に短縮し、x64 では -28% / -21%。**採用**。ただし倍率は complex 172 → 156 倍、comparison-heavy 193 → 162 倍で
まだ入力倍率（63〜64）より大きく、capture の COW 以外にも超線形の要因が残っている（生成 mapper はノード単位の線形 dispatch なので runtime 側。
#245 で領域別に n=1/4/16/64 を計測して切り分ける）。

### 教材としての要点

copy-on-write は「checkpoint を取る側」を安くするが、「変更する側」が状態サイズに比例するコピーを毎回払う。変更が入力長に比例して
増える状態（capture、宣言、token）を COW にすると、parse 全体が O(n²) になる。小さな fixture の A/B では見えないので、
線形性は「サイズを 4 / 16 / 64 倍にした fixture で倍率を見る」という別の計測で確認する。

## ケース20: 生成 mapper の選択処理を token 木のサイズに対して線形にする（Java、入力サイズに対する超線形の修正）

### 仮説

4 / 16 / 64 倍 fixture で Java public facade は入力 63 倍で 86 倍、64 倍で 122 倍の時間がかかった。parser 単体（`parseOnlySafe`）は
70.5 倍でほぼ線形、mapper 単体（`mapOnly`）は 278 倍で、超線形は生成 mapper と候補選択にある。x64 の JFR では CPU の 80% / 59%
（inclusive）が `TinyExpressionP4Mapper.findBestMappedToken` にあり（x1 では 18%）、メモ化後の再計測では `SourceMappedAst.<init>` の
全 span コピー（`IdentityHashMap.forEach` / `put`）が 30% を占めた。

- `findBestMappedToken` は部分木全体を preorder で走査して最良候補（preferred、depth 昇順、startOffset 降順、同値は後勝ち）を選ぶが、
  `mapTransparentValue`（@value の異種ノード解決）が入れ子の wrapper ごとにこれを呼ぶため、同じ部分木を何度も走査していた
- `SourceMappedAst` は生成時に `NODE_SOURCE_SPANS` 全体を private map に複製していた。TinyExpression の facade は候補型ごとに
  `selectSubtreeTokenWithSourceMap` を呼ぶので、候補数 × 全ノード数のコピーになる

### 実装

- `MapperRuleEmitter.emitFindBestMappedToken`: preferred 名が無い経路では部分木内の最良候補を「部分木 root からの相対 depth」で求め、
  mapping 呼び出し単位の `BEST_MEMO`（`MAP_MEMO` と同じ IdentityHashMap、同じ箇所でリセット）にメモ化し、呼び出し側は `atDepth(depth)`
  で平行移動して fold する。比較が「(preferred, depth, startOffset) の最後の最大値」なので、階層的に fold しても preorder の逐次 fold と
  同じ token が選ばれる。preferred 名付きの経路（呼び出しごとに 1 回）は従来の走査のまま
- span は `SpanLayer`（IdentityHashMap + 親レイヤ + frozen フラグ）に持つ。`SourceMappedAst` は参照しているレイヤを**凍結**するだけで
  コピーしない。以後の `registerNodeSourceSpan` は新しいレイヤを上に開き、次の mapping 呼び出しは空のレイヤから始める。凍結された
  レイヤは変化しないので、snapshot は mapper のロック無しに別スレッドから読める
- 試した「同じ root token の再 mapping では memo を再利用する」案は取り下げた。`SelectedSourceSnapshotRuntimeTest` が
  「公開 mapping 呼び出しごとに AST は別インスタンス、snapshot は自分の mapping のノードだけを解決する」契約を固定しているため。
  候補ごとの再 mapping は残る（候補数 × O(n)）
- golden snapshot を再生成。生成コードのみの変更で runtime は不変

### 観測

Timing（Java public facade、baseline = master `b21a965` 相当の Java runtime、candidate = 本ケース。いずれも isolated Maven repo）。base fixture は 3-run 中央値:

| Runtime | Fixture | Baseline runs | Baseline median | Candidate runs | Candidate median | 変化 |
|---|---|---:|---:|---:|---:|---:|
| Java | complex | 42.198 / 44.099 / 43.559 | **43.559** | 40.727 / 42.377 / 42.989 | **42.377** | **-2.71%** |
| Java | comparison-heavy | 26.532 / 25.571 / 27.666 | **26.532** | 26.410 / 25.012 / 25.664 | **25.664** | **-3.27%** |

x4 / x16 / x64（candidate 1 run、baseline はスケーリング計測の値）:

| Fixture | bytes | サイズ倍率 | baseline ms/op（倍率） | candidate ms/op（倍率） | 変化 |
|---|---:|---:|---:|---:|---:|
| complex | 332 | 1.0x | 44.711（1.0x） | 40.814（1.0x） | -8.7% |
| complex-x4 | 1,293 | 3.9x | 179.977（4.0x） | 169.136（4.1x） | -6.0% |
| complex-x16 | 5,179 | 15.6x | 788.081（17.6x） | 700.864（17.2x） | -11.1% |
| complex-x64 | 20,923 | 63.0x | 3859.322（86.3x） | 3424.045（83.9x） | -11.3% |
| comparison-heavy | 179 | 1.0x | 26.369（1.0x） | 25.577（1.0x） | -3.0% |
| comparison-heavy-x4 | 716 | 4.0x | 117.931（4.5x） | 111.024（4.3x） | -5.9% |
| comparison-heavy-x16 | 2,864 | 16.0x | 559.655（21.2x） | 480.079（18.8x） | -14.2% |
| comparison-heavy-x64 | 11,456 | 64.0x | 3230.332（122.5x） | 3022.909（118.2x） | -6.4% |

mapper 単体（`P4ParserBenchmark.mapOnly`、1 fork、5 iteration）:

| Fixture | mapOnly baseline ms（倍率） | mapOnly candidate ms（倍率） | 変化 |
|---|---:|---:|---:|
| complex | 0.138（1.0x） | 0.097（1.0x） | -29.7% |
| complex-x4 | 0.740（5.4x） | 0.480（4.9x） | -35.1% |
| complex-x16 | 3.276（23.7x） | 2.259（23.3x） | -31.0% |
| complex-x64 | 38.412（278.0x） | 33.875（348.8x） | -11.8% |

base fixture で -2.7% / -3.3%、x64 の facade で -11% / -6%、mapper 単体で -30%（x1〜x16）。選ばれる token・AST・span は不変で、unlaxer-dsl 987 tests
（golden 再生成）と TinyExpression p4-smoke 85 が通る。**採用**。

ただし倍率は facade で 86 → 84 倍、122 → 118 倍と大きくは変わらない。分離計測とこの後の JFR から、残る超線形は次の 3 つで、いずれも
「部分木の再走査」ではない:

1. parser 自体が 63 倍入力で 70.5 倍（1.12 倍の超線形。Rust 側 #245 と同じく memo 表の局所性が疑われる）
2. mapper 単体が x16 → x64 で 4 倍の入力に対して 15 倍（2.3 → 34 ms）。JFR では 99% が `findBestMappedToken` 配下だが内訳は O(n) 走査と
   `IdentityHashMap` の hash / put で、回数の二乗ではなく **表がキャッシュを超える崖**（#245 の Rust memo 表と同型）
3. TinyExpression の facade（`P4PreferredAstMapper.mapCandidates`）が候補型ごとに公開 mapping を呼び直す。x64 では facade 3,424 ms − parser
   2,465 ms ≈ 960 ms がこの分で、1 回 34 ms の mapping を 20〜30 回繰り返している計算。「呼び出しごとに新しい AST」の契約により
   generator 側では再利用できず、候補列を絞るのは tinyexpression 側の設計判断

### 教材としての要点

parser を速くしても、後段の mapper が O(n²) なら大きな入力では mapper が支配する。profile は「小さい入力」と「大きい入力」の両方で
取り、比率が伸びている関数を探す（x1 で 18% → x64 で 80%）。木の走査結果を親から何度も要求する構造は部分木ごとのメモ化で、
「snapshot のための全体コピー」は凍結レイヤ（生成後に変化しないことを構造で保証）で、いずれも線形に戻せる。ただし何を再利用できるかは
契約テストが決める。今回は「呼び出しごとに新しい AST」という契約があったので、memo の再利用ではなくコピーの排除で線形化した。

## ケース21: 失敗 memo を position バケットに分け、code point 変換を O(1) にする（Rust、入力サイズに対する超線形の修正）

### 仮説

ケース19（capture journal）の後も Rust parser 単体は 63 倍入力で 168 倍、facade で 156 倍だった。回数カウンタを n = 1 / 4 / 16 / 64 で
取ると **全て入力倍率どおり（約 60〜63 倍）**で、`LongestChoice` / `Lookahead` は 0 回、expected の最大リスト長は 38 で一定、
唯一 `code_point()` の二分探索比較だけが 97.8 倍（O(N log N)）だった。時間を区間に分けると、parse 本体が 85 倍、**parse 終了時の失敗 memo
表の破棄が 541 倍**（0.345 → 186.7 ms）。x64 では memo 表が 345,583 エントリ・capacity 458,752、保持診断と合わせて約 53 MiB で、
hash 参照と解放がキャッシュミスの連続になる。アルゴリズムの二乗ではなく、表の大きさに起因する局所性の問題と判断した。

### 実装

- 失敗 memo を position（code point offset）256 単位のバケットに分割（`FailureMemoBuckets`）。先頭バケットはインラインで持ち、小さな入力では
  割当が増えない。キー項目（rule id、position、matched_position、whitespace、depth）・保持期間・hit 時の replay 順序・metric は不変
- 非 ASCII 入力では byte → code point の逆引き表を入力構築時に O(N) で作り、`code_point()` / `span()` を O(1) に。ASCII 入力は
  byte == code point なので表を持たない。既存の `byte_offsets` と境界外の拒否は維持
- test: バケット境界（255 / 256 / 257）をまたぐ memo hit / miss、バケットの割当が使った範囲だけであること、非 ASCII の `span` が二分探索と
  全境界で一致すること、境界外の拒否。`unlaxer-alloc-audit` は小入力の allocation を 11 回以下に固定

### 観測

Timing（Rust、Criterion、baseline = master `fcfd7c5`（#241 適用後）、candidate = 本ケース、いずれも worktree の path patch）。base fixture は 3-run 中央値:

| Runtime | Fixture | Baseline runs | Baseline median | Candidate runs | Candidate median | 変化 |
|---|---|---:|---:|---:|---:|---:|
| Rust | complex | 3.077 / 2.977 / 3.072 | **3.072** | 2.949 / 3.011 / 2.892 | **2.949** | **-4.01%** |
| Rust | comparison-heavy | 0.943 / 0.948 / 0.913 | **0.943** | 0.872 / 0.878 / 0.893 | **0.878** | **-6.94%** |

public facade（x1 は run1、x4 以上は 1 run）:

| Fixture | サイズ倍率 | baseline ms（倍率） | candidate ms（倍率） | 変化 |
|---|---:|---:|---:|---:|
| complex | 1.0x | 3.077（1.0x） | 2.949（1.0x） | -4.2% |
| complex-x4 | 3.9x | 15.755（5.1x） | 12.607（4.3x） | -20.0% |
| complex-x16 | 15.6x | 81.427（26.5x） | 52.414（17.8x） | -35.6% |
| complex-x64 | 63.0x | 435.675（141.6x） | 222.657（75.5x） | -48.9% |
| comparison-heavy | 1.0x | 0.943（1.0x） | 0.872（1.0x） | -7.5% |
| comparison-heavy-x4 | 4.0x | 3.531（3.7x） | 3.351（3.8x） | -5.1% |
| comparison-heavy-x16 | 16.0x | 18.083（19.2x） | 15.413（17.7x） | -14.8% |
| comparison-heavy-x64 | 64.0x | 141.636（150.1x） | 77.469（88.8x） | -45.3% |

parse-only-safe（parser 単体、1 run）:

| Fixture | サイズ倍率 | baseline ms（倍率） | candidate ms（倍率） | 変化 |
|---|---:|---:|---:|---:|
| complex | 1.0x | 3.056（1.0x） | 2.821（1.0x） | -7.7% |
| complex-x4 | 3.9x | 13.177（4.3x） | 12.512（4.4x） | -5.1% |
| complex-x16 | 15.6x | 82.669（27.1x） | 51.782（18.4x） | -37.4% |
| complex-x64 | 63.0x | 443.931（145.3x） | 218.026（77.3x） | -50.9% |

base fixture で -4.0% / -6.9%、x64 で **-49% / -45%**、parser 単体の倍率は 145 → 77（入力 63 倍）。CST・失敗診断・`CheckpointMetrics` の fingerprint は
不変で 147 tests が通る。**採用**。残る 1.2〜1.4 倍（75〜89 倍 / 63〜64 倍）は parse 終了時の memo 破棄（分割後も 44 ms、x1 の 151 倍）が主で、
保持診断を連続 arena に置いてまとめて解放する案が次の候補。非 ASCII 入力では逆引き表の分だけメモリが増える（入力 byte 長 × usize）。

### 教材としての要点

倍率が悪いのに回数カウンタが線形なら、疑うのは「表の大きさ」と「解放」である。hash 表が L2 / L3 を超えると 1 参照が
キャッシュミスになり、parse 終了時の drop は全エントリを触るので同じ崖を踏む。位置で分割すると同時に触る範囲が入力の一部に収まり、
局所性が戻る。計測は parse 本体だけでなく drop の区間も測る。JFR や `Instant` の比率と同じく、この種の崖は小さな fixture では見えない。

## ケース22: packrat memo を position ブロックに分ける（Java、不採用）

### 仮説

分離計測で Java parser 単体（`parseOnlySafe`）は 63 倍入力で 70.5 倍。complex-x64 の JFR（50 サンプル）では parser CPU の上位が
`HashMap.resize` 20%、`HashMap.getNode` 8% で、Rust のケース21（失敗 memo 表の巨大化）と同型だと考えた。`PackratMemoTable` は parser
ごとに 1 つの `HashMap<PositionKey, Entry>` を持つ。

### 実装

parser ごとの表を consumed position 256 単位の `ArrayList<HashMap<PositionKey, Entry>>` に分割（使ったブロックだけ生成）。キー・エントリ・
hit の意味は不変。Java 672 + 987 tests、TinyExpression p4-smoke 85 は成功。

### 観測

Timing（Java、baseline = master `3c64061`、candidate = branch `perf/memo-position-buckets-252` `522e813`、isolated Maven repo）。base fixture は 3-run 中央値:

| Runtime | Fixture | Baseline runs | Baseline median | Candidate runs | Candidate median | 変化 |
|---|---|---:|---:|---:|---:|---:|
| Java | complex | 46.283 / 42.047 / 44.200 | **44.200** | 44.649 / 43.062 / 42.614 | **43.062** | **-2.58%** |
| Java | comparison-heavy | 26.240 / 27.066 / 25.597 | **26.240** | 25.231 / 27.057 / 26.794 | **26.794** | **+2.11%** |

x1 / x4 / x16 / x64（`parseOnlySafe` と `publicFacade`、1 fork × 5 iteration、各 1 run）:

parseOnlySafe:

| Fixture | サイズ倍率 | baseline ms（倍率） | candidate ms（倍率） | 変化 |
|---|---:|---:|---:|---:|
| complex | 1.0x | 36.8（1.0x） | 34.9（1.0x） | -5.0% |
| complex-x4 | 3.9x | 143.4（3.9x） | 158.4（4.5x） | +10.4% |
| complex-x16 | 15.6x | 618.2（16.8x） | 599.8（17.2x） | -3.0% |
| complex-x64 | 63.0x | 2293.5（62.4x） | 2711.4（77.6x） | +18.2% |

publicFacade:

| Fixture | サイズ倍率 | baseline ms（倍率） | candidate ms（倍率） | 変化 |
|---|---:|---:|---:|---:|
| complex | 1.0x | 42.1（1.0x） | 42.1（1.0x） | -0.1% |
| complex-x4 | 3.9x | 192.4（4.6x） | 164.3（3.9x） | -14.6% |
| complex-x16 | 15.6x | 760.2（18.1x） | 675.0（16.0x） | -11.2% |
| complex-x64 | 63.0x | 3774.2（89.6x） | 3890.9（92.5x） | +3.1% |

改善なし。base は -2.6% / +2.1% でノイズ内、x64 は parser 単体 +18%、facade +3%、x4 / x16 の facade -11〜-15% と方向が揃わず、
1 run の JMH のばらつき（x64 で ±10%）の範囲。**不採用**（実装は branch に保存）。

この計測で baseline の parser 単体倍率は 62.4 倍（入力 63 倍）で、分離計測の 70.5 倍は run 間の揺れだったと分かった。つまり Java parser は
すでにほぼ線形で、JFR の `HashMap.resize` 20% は 50 サンプルの偶然。Java の facade の超線形（86 倍）はケース20 に書いた候補ごとの再 mapping が主。

### 教材としての要点

同じ構造でも、崖の位置は言語と実装で違う。Rust の memo 表は 1 parse で 34 万エントリの 1 表だったが、Java は parser ごとに表が分かれて
おり（数百表）、1 表あたりは小さく崖に達していなかった。「片方で効いた施策をもう片方に当てる」のは候補発見としては正しいが、採否は
やはり A/B で決める。50 サンプルの JFR CPU 比率は候補発見にも弱い。倍率の比較は 1 run では ±10% 揺れるので、線形性の判断は
複数 run の中央値か、JMH の fork / iteration を増やして行う。

## ケース23: expected 名を intern して失敗 memo を `Vec<u32>` にする（Rust、memo 破棄コスト）

### 仮説

ケース21（memo の position バケット化）の後も complex-x64 の parse 224 ms のうち parse 終了時の失敗 memo 破棄が約 44 ms（x1 の 151 倍、入力は
63 倍）。memo は 345,583 エントリで、保持する `expected` の `Vec<Rc<str>>` は 283,762 本・capacity 合計 1,858,728 要素。破棄は要素ごとに
`Rc` の参照カウントを減らし、離れたキャッシュラインを順に触る。名前を `ParseContext` ごとの interner で `u32` にすれば、破棄は `Vec` の
バッファ解放だけになり、エントリも小さくなって局所性が上がるはずだと考えた。

### 実装

- `ExpectedNames`（先頭の名前をインラインに持ち、2 つ目以降を `Vec<Rc<str>>` + `HashMap<Rc<str>, u32>` で管理）を `ParseContext` に追加。
  `fail` / `fail_at` / `record` は名前を `u32` に intern してから記録する
- `FailureDiagnostic.expected` を `Option<Rc<Vec<u32>>>`、`ParseContext.expected` を `Vec<u32>` に。ケース17の一括 replay での `Rc` 共有、初出順、
  重複除去（`contains` が `u32` 比較になる）はそのまま。`ParseError.expected` を作る時点で名前へ戻す（従来どおり sort + dedup）
- allocation 契約: 登録済みの名前の再記録は allocation 0（audit crate に契約 test を追加）。実装は codex に委譲し、差分レビューと A/B を行った

### 観測

Timing（Rust、Criterion、baseline = master `3c64061`、candidate = 本ケース、path patch）。base fixture は 3-run 中央値:

| Runtime | Fixture | Baseline runs | Baseline median | Candidate runs | Candidate median | 変化 |
|---|---|---:|---:|---:|---:|---:|
| Rust | complex | 2.911 / 3.091 / 3.130 | **3.091** | 2.421 / 2.557 / 2.362 | **2.421** | **-21.66%** |
| Rust | comparison-heavy | 0.876 / 0.890 / 0.966 | **0.890** | 0.736 / 0.749 / 0.761 | **0.749** | **-15.91%** |

public facade（x1 は run1、x4 以上は 1 run）:

| Fixture | サイズ倍率 | baseline ms（倍率） | candidate ms（倍率） | 変化 |
|---|---:|---:|---:|---:|
| complex | 1.0x | 2.911（1.0x） | 2.421（1.0x） | -16.8% |
| complex-x4 | 3.9x | 15.038（5.2x） | 9.370（3.9x） | -37.7% |
| complex-x16 | 15.6x | 54.521（18.7x） | 44.638（18.4x） | -18.1% |
| complex-x64 | 63.0x | 229.409（78.8x） | 168.236（69.5x） | -26.7% |
| comparison-heavy | 1.0x | 0.876（1.0x） | 0.736（1.0x） | -16.0% |
| comparison-heavy-x4 | 4.0x | 3.564（4.1x） | 2.907（3.9x） | -18.4% |
| comparison-heavy-x16 | 16.0x | 18.292（20.9x） | 13.322（18.1x） | -27.2% |
| comparison-heavy-x64 | 64.0x | 82.326（93.9x） | 62.095（84.3x） | -24.6% |

parse-only-safe（parser 単体、1 run）:

| Fixture | サイズ倍率 | baseline ms（倍率） | candidate ms（倍率） | 変化 |
|---|---:|---:|---:|---:|
| complex | 1.0x | 3.012（1.0x） | 2.452（1.0x） | -18.6% |
| complex-x4 | 3.9x | 14.007（4.7x） | 10.117（4.1x） | -27.8% |
| complex-x16 | 15.6x | 58.395（19.4x） | 42.307（17.3x） | -27.6% |
| complex-x64 | 63.0x | 244.341（81.1x） | 173.604（70.8x） | -28.9% |

base fixture で **-21.7% / -15.9%**、x64 で -27% / -25%（倍率 79 → 70、94 → 84）、parser 単体 81 → 71 倍。x1 でも大きく効いたのは、
破棄だけでなく `contains_expected` の文字列比較が `u32` 比較になり、`Rc` の clone / drop が消えたため。150 tests、fingerprint 一致。**採用**。

### 教材としての要点

大量に保持する小さな値（ここでは `Rc<str>`）は、参照カウントの増減と解放が「本体の処理」に見えない固定費として積み上がる。
文字列を整数 id に変えると、比較・複製・破棄のすべてが安くなり、表も小さくなる。名前へ戻すのは結果を返す 1 回だけで済む。

## ケース24: 失敗診断まわりの割当を削る（Rust: 単一 expected の inline 化、capture 名の借用、`Arc::clone` の後送り）

### 仮説

master `ab6a368` の差分計測（機能を no-op にした build との差）で、失敗診断の記録を止めると x1 で 38.7%、x64 で 44.9% 短縮する。
診断が依然最大のコストで、memo 破棄は x1 → x64 で 146.6 倍（他の回数は 60〜62 倍）と局所性の問題が残る。memo が保持する
`Rc<Vec<u32>>` は x64 で 283,762 本、**その 75% が要素 1 個**。他に `CaptureStore.push` が毎回 `name.to_owned()`（x64 で 125,945 回）、
`rule()` が memo lookup 前に `Arc::clone(&rules)`（hit 46%）していた。

### 実装（項目ごとに 1 commit、codex に委譲しレビュー）

1. `FailureDiagnostic.expected` を `ExpectedIds { Empty, Single(u32), Multiple(Rc<Vec<u32>>) }` に。単一要素では `Vec` も `Rc` も作らない。
   farthest の選択、初出順、重複除去、一括 replay の `Rc` 共有、`ParseError.expected` は不変
2. `CaptureStore.values` を `HashMap<&'static str, Vec<Span>>` に（capture 名は `&'static str`）。名前での検索・span 順・journal は不変
3. `rule()` の `Arc::clone(&self.rules)` を memo lookup の後に移す。`depth >= 256` と不正 rule id の診断順序は test で固定

alloc-audit: 診断 allocation 11 → 9、capture 512 回の allocation 1,034 → 522。

### 観測

| 段階（codex 簡易計測、CPU 時間中央値） | x1 | x64 |
|---|---:|---:|
| base `ab6a368` | 2.500 ms | 170.0 ms |
| +1 単一 expected の inline 化 | 2.468（-1.3%） | 149.0（**-12.4%**） |
| +2 capture 名の `to_owned()` 排除 | 2.500（+1.3%） | 145.0（-2.7%） |
| +3 memo hit 前の `Arc::clone` 排除 | 2.500（±0） | 149.0（+2.8%、ばらつき内） |

Timing（Rust、Criterion、baseline = master `ab6a368`、candidate = 3 項目、path patch）。base fixture は 3-run 中央値:

| Runtime | Fixture | Baseline runs | Baseline median | Candidate runs | Candidate median | 変化 |
|---|---|---:|---:|---:|---:|---:|
| Rust | complex | 2.454 / 2.495 / 2.492 | **2.492** | 2.252 / 2.267 / 2.330 | **2.267** | **-9.02%** |
| Rust | comparison-heavy | 0.856 / 0.746 / 0.774 | **0.774** | 0.626 / 0.627 / 0.629 | **0.627** | **-19.00%** |

public facade（x1 は run1、x4 以上は 1 run）:

| Fixture | サイズ倍率 | baseline ms（倍率） | candidate ms（倍率） | 変化 |
|---|---:|---:|---:|---:|
| complex | 1.0x | 2.454（1.0x） | 2.252（1.0x） | -8.3% |
| complex-x4 | 3.9x | 9.283（3.8x） | 9.054（4.0x） | -2.5% |
| complex-x16 | 15.6x | 43.358（17.7x） | 38.426（17.1x） | -11.4% |
| complex-x64 | 63.0x | 170.736（69.6x） | 148.416（65.9x） | -13.1% |
| comparison-heavy | 1.0x | 0.856（1.0x） | 0.626（1.0x） | -26.9% |
| comparison-heavy-x4 | 4.0x | 2.861（3.3x） | 2.341（3.7x） | -18.2% |
| comparison-heavy-x16 | 16.0x | 11.979（14.0x） | 10.268（16.4x） | -14.3% |
| comparison-heavy-x64 | 64.0x | 66.312（77.5x） | 45.096（72.0x） | -32.0% |

parse-only-safe（parser 単体、1 run）:

| Fixture | サイズ倍率 | baseline ms（倍率） | candidate ms（倍率） | 変化 |
|---|---:|---:|---:|---:|
| complex | 1.0x | 2.457（1.0x） | 2.343（1.0x） | -4.6% |
| complex-x4 | 3.9x | 9.204（3.7x） | 8.597（3.7x） | -6.6% |
| complex-x16 | 15.6x | 41.107（16.7x） | 36.149（15.4x） | -12.1% |
| complex-x64 | 63.0x | 172.424（70.2x） | 150.355（64.2x） | -12.8% |

base fixture で **-9.0% / -19.0%**、x64 で -13% / -32%（倍率 70 → 66、78 → 72）。効果はほぼ項目 1 で、項目 2 は allocation を半減させるが
時間はばらつき内、項目 3 は時間で判別できない（無害なので残す）。154 tests、fingerprint 一致。**採用**。

### 教材としての要点

「表の 75% が要素 1 個」のような分布はカウンタで数えないと分からない。小さい可変長データは `Vec` + `Rc` の 2 段の割当と破棄が支配的で、
inline 表現にすると割当・破棄・局所性の三つが同時に良くなる。一方、回数が多くても 1 回が数 ns の操作（`Arc::clone`）は外しても測れない。

## ケース25: 詳細診断を失敗時に作るモード `Diagnostics::DetailedOnFailure`（Rust、opt-in の実験）

### 仮説

[設計問答](parser-execution-design-qa-ja.md)の提案1。PEG の分岐失敗は正常な制御フローで、成功する入力でも `fail_at` は complex x1 で 8,485 回、
x64 で 522,124 回起きる。ケース24 後の差分計測でも失敗診断の記録を止めると x1 38.7%、x64 44.9% 短縮する。成功時に返さない expected 集合を
維持する仕事を止め、失敗したときだけ同じ入力を `Detailed` で再解析すれば、成功経路の短縮と失敗時の同一 `ParseError` を両立できるはずだと考えた。

### 実装（opt-in、既定は不変。codex に委譲しレビュー）

- `ParseOptions.diagnostics: Diagnostics { Detailed（既定）, DetailedOnFailure }` と `with_diagnostics()` を追加（additive）
- `DetailedOnFailure` の初回 parse では `fail_at*` が farthest / expected / diagnostic_frames を更新せず、memo entry は空診断、hit 時 replay は no-op、
  memo 判定・キー・hit / miss は不変。全入力解析の entry point は失敗時に新しい context で `Detailed` 再解析し、その `ParseError` / `ParseDiagnostic` を返す
- 低水準 API（`ParseContext` 直接使用）は再解析せず診断が空になる。解析中に診断を読んで受理判定を変える custom parser、再実行できない副作用を持つ
  parser には不適（README に記載）。Java 側は未実装（Rust 限定実験）
- test: 成功・切断・末尾追加・括弧不一致の入力で `Detailed` と `DetailedOnFailure` の受理結果・CST・capture・scope・`ParseError` が一致。
  alloc-audit: 1024 回の同一失敗で allocation 2 → **0**

### 観測

モード比較（同じ候補 runtime、Criterion、warm-up 3 s / measurement 6 s / 50 samples、`parse_tree_detailed_with_options(SafeFailures)`）:

| Fixture | 入力 | Detailed ms | DetailedOnFailure ms | 変化 |
|---|---|---:|---:|---:|
| complex | 成功（そのまま） | 2.875 | 1.411 | -50.9% |
| complex | 失敗（前半で切断） | 4.932 | 8.548 | +73.3% |
| complex | 失敗（末尾に `@`） | 2.472 | 3.979 | +61.0% |
| complex-x4 | 成功（そのまま） | 9.842 | 6.218 | -36.8% |
| complex-x4 | 失敗（前半で切断） | 5.023 | 7.421 | +47.7% |
| complex-x4 | 失敗（末尾に `@`） | 10.196 | 14.790 | +45.1% |
| complex-x16 | 成功（そのまま） | 39.266 | 27.253 | -30.6% |
| complex-x16 | 失敗（前半で切断） | 16.048 | 27.764 | +73.0% |
| complex-x16 | 失敗（末尾に `@`） | 57.304 | 65.773 | +14.8% |
| complex-x64 | 成功（そのまま） | 161.297 | 104.725 | -35.1% |
| complex-x64 | 失敗（前半で切断） | 70.233 | 102.727 | +46.3% |
| complex-x64 | 失敗（末尾に `@`） | 160.116 | 242.633 | +51.5% |

成功入力で **-48%（x1）〜 -35%（x64）**、失敗入力は 2 回 parse になるため **+46〜+73%**。codex の CPU 時間計測でも成功 complex -35% / -38.5%、
comparison-heavy（`BooleanExpression` 入口）-28% / -35%、失敗 +52〜+75% で一致。

既定モード（`Detailed`）の退行確認（baseline = master `81a960a`、candidate = 本ケース、public facade 3-run 中央値）:

| Runtime | Fixture | Baseline runs | Baseline median | Candidate runs | Candidate median | 変化 |
|---|---|---:|---:|---:|---:|---:|
| Rust | complex | 2.381 / 2.393 / 2.324 | **2.381** | 2.353 / 2.255 / 2.225 | **2.255** | **-5.29%** |
| Rust | comparison-heavy | 0.658 / 0.662 / 0.638 | **0.658** | 0.634 / 0.636 / 0.637 | **0.636** | **-3.28%** |

退行なし（x64 も complex -8.5%、comparison-heavy -3.2%）。

### 判断

成功が多い用途（式の評価・コンパイル）では大きく速くなり、失敗が多い用途（編集途中の入力を逐次解析する LSP）では遅くなる。
既定は `Detailed` のまま、利用者が `ParseOptions` で選ぶ opt-in として**採用**。次の段階は提案2（要求する結果と parser の性質から実行方式を選ぶ）と、
Java 側の同等モード。

### 教材としての要点

「常時収集」と「必要になったら作る」の切り替えは、収集コストが成功経路の 35〜48% を占める場合に効く。代償は失敗時の再解析（約 1.5〜1.75 倍）で、
用途ごとに損益が逆転する。だから runtime が既定を決めるのではなく、要求する結果（Features）として利用者が選ぶ形にする。
差分計測で上限（39〜45%）を先に測っていたので、実装後の結果（35〜48%）が妥当かをすぐ判断できた。

## ケース26: Java 版 `Diagnostics.DETAILED_ON_FAILURE`（opt-in の実験、ケース25 の Java 対応）

### 仮説

Java でも診断記録（`trackCursorProgress` / `registerFailureCandidate` / `replayFailureDiagnostic` / `mergeFrame`）を no-op にした計測専用 build で
`parseOnlySafe` が complex 40.7 → 27.9 ms（-31.6%）、complex-x64 2,800 → 1,998 ms（-28.7%）。Rust と同じ「成功経路では記録せず、失敗時だけ
`DETAILED` で再解析する」opt-in が Java でも成り立つはずだと考えた。

### 実装（opt-in、既定は不変。codex に委譲しレビュー）

- `ParseOptions.Diagnostics { DETAILED（既定）, DETAILED_ON_FAILURE }`、`diagnostics()`、`withDiagnostics()` を additive に追加
- `DETAILED_ON_FAILURE` では frontier 追跡、失敗候補の登録（`ExpectedSources` / `StackSnapshot`）、memo diagnostic frame の記録と replay を省く。
  memo hit 時の **transaction replay（状態復元）は意味論なので維持**し、そのために診断 frame と transaction frame の管理を分けた
  （`memoTransactionFrames`）。`getParseFailureDiagnostics()` は空の診断を返す（Javadoc に明記）
- 生成 parser の entry point（`MapperGenerator.emitEntryPoint`）は構文失敗・末尾未消費時に、初回 context を閉じてから新しい `DETAILED` context
  で再解析し、その診断で `ParseDiagnostic` / 例外文言を作る。golden 2 件を再生成。`docs/java-diagnostics-policy.md` に Java / Rust の対応表と制約
- test: `DetailedOnFailureTest`（7 件: Token 木・cursor・memo hit 数・listener callback の一致、transaction replay の維持、legacy `memoize()` 経路）、
  `JavaDetailedOnFailureRuntimeTest`（3 件: 生成 entry point の再解析で診断が `DETAILED` と一致）。Java 679 + 990、p4-smoke 85、`RustNativeEmitterTest`

### 観測

モード比較（同じ候補 runtime、JMH 1 fork × 5 iteration、`SAFE_FAILURES`。parser 単体は `ParseContext` 直呼び、entry は生成 `parse(source, null, options)`
= parse + mapping）:

| 経路 | Fixture | DETAILED ms | DETAILED_ON_FAILURE ms | 変化 |
|---|---|---:|---:|---:|
| parser 単体（成功） | complex | 34.3 | 28.4 | -17.1% |
| parser 単体（成功） | complex-x64 | 2585.0 | 1951.4 | -24.5% |
| entry（成功） | complex | 36.5 | 33.8 | -7.4% |
| entry（失敗: 前半で切断） | complex-half | 16.5 | 26.1 | +58.6% |
| entry（失敗: 末尾 `@`） | complex-tail | 36.6 | 65.6 | +79.4% |

parser 単体で **-17%（x1）/ -24.5%（x64）**、entry では mapping 分が薄めて -7.4%。失敗入力は 2 回 parse で +59〜+79%。差分計測の上限
（-31.6% / -28.7%）より小さいのは、`DETAILED_ON_FAILURE` でも memo の transaction frame 管理と `ParseFrame` の push / pop（stack の維持）が残るため。

既定モード（`DETAILED`）の退行確認（baseline = master `516b809`、public facade 3-run 中央値）:

| Runtime | Fixture | Baseline runs | Baseline median | Candidate runs | Candidate median | 変化 |
|---|---|---:|---:|---:|---:|---:|
| Java | complex | 42.983 / 42.850 / 42.344 | **42.850** | 40.970 / 41.181 / 45.126 | **41.181** | **-3.90%** |
| Java | comparison-heavy | 25.625 / 25.556 / 25.876 | **25.625** | 24.842 / 25.460 / 24.946 | **24.946** | **-2.65%** |

退行なし。**採用**（opt-in）。

### 教材としての要点

Rust（-35〜-51%）より Java の効果（-17〜-25%）が小さいのは、Java の失敗診断が「記録」だけでなく frame の維持と listener 連携に組み込まれており、
記録を止めても骨格が残るから。同じ設計案でも runtime の構造で回収できる割合が違うので、実装前の差分計測（上限）と実装後の実測を
両方記録して、差の理由（ここでは frame 管理）を次の候補にする。

## ケース27: 診断方針 `Auto` を既定にする（設計問答 提案2 の最小案、Java / Rust）

### 仮説

ケース25 / 26 の `DetailedOnFailure` は利用者が選ぶ opt-in で、「その文法・その parser で安全に選べるか」は利用者の責任だった。
要求する結果と parser の性質から実行方式を選ぶ準備層（提案2）の最初の形として、既定を `Auto` にし、準備時に 1 回だけ
`DetailedOnFailure` か `Detailed` に解決する。低水準 API の利用者（失敗後に診断を読む既存コード）の観測結果を変えないことが条件。

### 実装（Rust PR #265、Java PR #266。codex に並行で委譲しレビュー）

| 入口 | grammar / parser 木 | 解決先 |
|---|---|---|
| 生成 entry point（失敗時に `Detailed` で再解析できる） | 未宣言の custom / 手書き parser を含まない | `DetailedOnFailure` |
| 同 entry point | 含む | `Detailed` |
| 低水準 API（`ParseContext` を直接使う、再解析なし） | いずれも | `Detailed` |

- 性質の宣言: Rust `Expr::CustomWith { parser, reads_diagnostics, replayable }`、Java marker interface `DiagnosticsAgnostic`。未宣言は保守的に `Detailed`。
  生成 parser は marker を実装し、Java の生成 mapper は root の走査結果（`DiagnosticsSafety.isDeferredDiagnosticsSafe`）を文法ごとに `static final` でキャッシュ
  （generator が「全て library」と断言できるとは限らない。生成文法は custom token に手書き parser を指定できる）
- 明示で `Detailed` / `DetailedOnFailure` を選べば従来どおり固定。失敗が多い用途は `Detailed` を明示する（README / `docs/java-diagnostics-policy.md`）

### 観測

Rust（Criterion、既定同士: baseline = master `5f70015`（`Detailed`）、candidate = 既定 `Auto`。tinyexpression の生成 grammar は custom を含まないので
`DetailedOnFailure` に解決）:

| Runtime | Fixture | Baseline runs | Baseline median | Candidate runs | Candidate median | 変化 |
|---|---|---:|---:|---:|---:|---:|
| Rust | complex | 2.152 / 2.155 / 2.164 | **2.155** | 1.400 / 1.426 / 1.569 | **1.426** | **-33.83%** |
| Rust | comparison-heavy | 0.622 / 0.618 / 0.615 | **0.618** | 0.473 / 0.473 / 0.471 | **0.473** | **-23.49%** |

x64: complex 146.9 → 92.6 ms（-36.9%）、comparison-heavy 45.5 → 31.3 ms（-31.3%）。失敗入力は +69〜+72%。

Java（JMH、既定同士、public facade 3-run 中央値）:

| Runtime | Fixture | Baseline runs | Baseline median | Candidate runs | Candidate median | 変化 |
|---|---|---:|---:|---:|---:|---:|
| Java | complex | 40.675 / 42.814 / 40.761 | **40.761** | 41.862 / 43.481 / 41.988 | **41.988** | **+3.01%** |
| Java | comparison-heavy | 26.293 / 25.063 / 26.366 | **26.293** | 25.093 / 24.825 / 26.613 | **25.093** | **-4.56%** |

Java は変化なし（ノイズ内）。tinyexpression の facade は低水準 `new ParseContext(...)` を使い、生成 entry には marker 未宣言の手書き
`StringLiteralParser` が含まれるため、どちらも `DETAILED` に解決される。生成 entry で既定（Auto）と明示 `DETAILED` を比べても一致:

| Fixture | entryDetailed ms | entryAuto ms（既定） | 差 |
|---|---:|---:|---:|
| complex-x64 | 2440.9 | 2342.6 | -4.0% |
| complex-half | 15.1 | 14.8 | -2.0% |

つまり **Java の tinyexpression は既定変更だけでは速くならない**。marker の付与と facade の経路変更は tinyexpression #168 で行う。
設計どおり、低水準 API 利用者の観測結果は変わっていない。

### 教材としての要点

「既定を速い方に変える」は、その速さを受け取れる入口と受け取れない入口を分けて初めて安全になる。低水準 API では従来どおり、
再解析できる入口だけで自動選択する、という規則にしたので、既存コードは何も変わらず、生成 parser の利用者は何もしなくても速くなる。
一方で、手書き parser が 1 つでも未宣言だと文法全体が `Detailed` に落ちる。性質の宣言は利用者の自己申告なので、宣言の意味
（解析中に診断を読まない、再実行できる）を文書に固定し、差分テストで両モードの一致を守る。

## ケース28: 候補型ごとの再 mapping を root 1 回の mapping に置き換える（Java、生成 mapper #267 + tinyexpression #167）

### 仮説

ケース22 の x64 計測で、Java の public facade（3,424 ms）から parser 単体（2,465 ms）を引いた約 960 ms は、tinyexpression の
`P4PreferredAstMapper.mapCandidates` が候補型名（約 45 個）ごとに `P4SourceMapping.select` → 生成 mapper の `mapTokenTree` を呼び直す分だった
（1 回約 34 ms × 20〜30 回）。生成 mapper には「公開 mapping 呼び出しごとに AST は別インスタンス、snapshot は自分の mapping だけを解決する」契約
（`SelectedSourceSnapshotRuntimeTest`）があり、generator 側で勝手に再利用はできない。契約を壊さずに「1 回 map して何度でも選ぶ」入口を
別に用意すれば、facade の候補数依存が消えるはず。

### 実装（unlaxer PR #268、tinyexpression PR #171。codex に委譲しレビュー）

- 生成 mapper に `mapParsedTree(Token)` / `mapSubtreeTree(Token)` を追加。返る `MappedTree` は 1 回の mapping で得た preorder の候補列・AST・
  凍結した `SpanLayer` を保持し、`select(String preferredAstSimpleName)` / `selectDefault()` が `findBestMappedToken` と同じ fold
  （preferred 優先 → 浅い depth → 大きい start offset → 同点は後勝ち）を再 mapping せずに行う。`MAP_MEMO` により各ノードの mapping は 1 回。
- 既存入口の契約は不変（呼び出しごとに memo をリセットして別 AST）。AST 共有は「同じ `MappedTree` 内」に限る契約として Javadoc に明記。
- tinyexpression 側は `P4SourceMapping.mapOnce(...)` で新 API をリフレクション解決し、無ければ候補ごとに既存 `select` を呼ぶ fallback ハンドルを返す
  （Maven Central 公開版 3.0.15 互換）。候補順序・`coversWholeSource`・返す `ParsedAst`・例外経路は不変。

### 観測

生成 mapper 単体（Java 21、warm-up 20 回後 9 回の中央値、parse 時間は除く）: 型名一致だけなら 1 候補目で決まるので差は無い（0.80〜1.01 倍）が、
facade と同じ「型名 + 全ソース範囲」条件では complex 6.97 → 0.367 ms（19.0 倍）、complex-x64 1,430 → 33.0 ms（43.3 倍）。

tinyexpression public facade（JMH、unlaxer master `cb128f7` を両側で使用、tinyexpression `4bcd3657` vs `2e2da9a2`、3-run 中央値）:

| Runtime | Fixture | Baseline runs | Baseline median | Candidate runs | Candidate median | 変化 |
|---|---|---:|---:|---:|---:|---:|
| Java | complex | 35.346 / 34.873 / 33.777 | **34.873** | 31.066 / 30.393 / 30.130 | **30.393** | **-12.85%** |
| Java | comparison-heavy | 21.830 / 23.422 / 23.143 | **23.143** | 17.843 / 18.577 / 19.540 | **18.577** | **-19.73%** |

倍率（`publicFacade`、1 fork × 5 iteration。入力は complex 63 倍、comparison-heavy 64 倍）:

| Fixture | baseline ms（倍率） | candidate ms（倍率） | 変化 |
|---|---:|---:|---:|
| complex-x4 | 138.6（4.0） | 121.4（4.0） | -12.4% |
| complex-x16 | 613.8（17.6） | 529.6（17.4） | -13.7% |
| complex-x64 | 2986.2（85.6） | 2009.7（66.1） | -32.7% |
| comparison-heavy-x4 | 98.8（4.3） | 77.9（4.2） | -21.2% |
| comparison-heavy-x16 | 441.7（19.1） | 295.7（15.9） | -33.1% |
| comparison-heavy-x64 | 2907.6（125.6） | 1166.0（62.8） | -59.9% |

失敗入力（`facadeAny`）は complex-half +1.0%、complex-tail -17.9%。Java facade の超線形（ケース22 で 86 倍）はこれで入力倍率の 1.05 倍に収まった。

### 教材としての要点

「契約があるから再利用できない」は、その契約を守る既存入口を残したまま、再利用を前提にした別の入口を足せば解ける。新入口の契約
（AST 共有の範囲、凍結した span 層、保持コスト）を Javadoc とテストで固定し、下流は新 API が無い版では従来経路に落ちる二段構えにする。
効果は候補数に比例する場所（comparison-heavy の x64 で -60%）に集中し、候補 1 つで決まる入力では出ない。倍率の改善（86 → 66 倍）は
「facade の残りの超線形は parser 側ではなく呼び出し側の回数にあった」ことの確認になる。

tinyexpression の全テストを unlaxer 開発版で回すと、深いネストの if 式（fraud formula #5）の parse が公開版 3.0.15 の 25 ms から 4〜6 秒に
退行していることが同時に見つかった（#194 で成功 memo が無くなったため。unlaxer #269）。tinyexpression の Java CI は公開版 jar でしか
走らないので CI には出ない。

## ケース29: `IntegerValue` のアノテーション反射と `NodeKind.getTag()` の毎回検索をやめる（Java）

### 仮説

ケース28 のあとに tinyexpression の全テストを開発版で回して見つけた深いネスト if 式（#269）を JFR で採ると、CPU の 15.8% が
`HashMap.getNode ← LinkedHashMap.get ← AnnotationInvocationHandler.invoke ← $Proxy.value ← MinIntegerValue.minIntegerValue`、
6.2% が `MinLength.minLength`、2.3% が `MaxIntegerValue`、9.3% が `ConcurrentHashMap.get ← FactoryBoundCache.get ← Tag.of ← NodeKind.getTag` だった。
`IntegerValue`（`CodePointIndex` / `CodePointOffset` / `CodePointLength` / `StringLength` / `Depth`）は cursor 演算のたびに生成され、
コンストラクタが 4 つの境界値を毎回 `getClass().getAnnotation(...)` で読む。`NodeKind.getTag()` は `Token.AST_NODES`（`filteredChildren`）や
`TagBasedReducer.doReduce` から token ごとに呼ばれ、毎回 cache map を検索していた。どちらも class（enum 定数）の定数なので 1 回読めばよい。
これまでの JFR では見えなかったのは、サンプル数が少なかった（数十 ms の parse）ため。5 秒の parse なら 259 サンプルで十分に出る。

### 実装（PR #271）

- `MinIntegerValue` / `MaxIntegerValue` / `MinLength` / `MaxLength` / `Nullable` の default メソッドを `ClassValue` による class 単位の
  キャッシュにする。mixin interface（`OneOrMoreOfIntegerValue` 等）の override は仮想呼び出しのまま優先される
- `IntegerValue` のコンストラクタは境界値を 1 回ずつ取り、digit 境界が既定（0 .. `Integer.MAX_VALUE`）なら `Math.log10` を省く。
  受理される値・例外文言は不変
- `NodeKind` の定数に `Tag` を保持（`Tag.of(this)` と同一インスタンス）

### 観測

tinyexpression（JMH、同じ tinyexpression コード `c70416e1`、unlaxer master `cb128f7` vs 本 PR、交互 3-run 中央値）:

| Runtime | Bench | Fixture | Baseline runs | Baseline median | Candidate runs | Candidate median | 変化 |
|---|---|---|---:|---:|---:|---:|---:|
| Java | publicFacade | complex | 31.489 / 29.915 / 31.376 | **31.376** | 27.025 / 27.585 / 25.328 | **27.025** | **-13.87%** |
| Java | publicFacade | comparison-heavy | 18.142 / 17.902 / 18.115 | **18.115** | 15.213 / 16.263 / 16.087 | **16.087** | **-11.19%** |
| Java | parseOnlySafe | complex | 35.084 / 37.185 / 35.278 | **35.278** | 30.551 / 30.409 / 30.036 | **30.409** | **-13.80%** |

x64（1 fork × 5 iteration）: publicFacade 1960.0 → 1723.2 ms（-12.1%）、parseOnlySafe 2372.7 → 2358.1 ms（-0.6%）。
parseOnlySafe の x64 が動かないのは、その経路の x64 では memo 表と診断の局所性（ケース21〜24）が支配的で、値オブジェクト生成の比率が下がるため。

### 教材としての要点

「値オブジェクトを不変にして安全にする」設計が、実装の細部（アノテーション反射を毎回）で hot path の 1/4 を食っていた。
プロファイラは入力が小さいとサンプルが足りず、この種の「薄く広い」コストを見せない。数秒かかる入力（病的な式でよい）で採ると
すぐ出る。修正は class 単位のキャッシュだけで、意味論に触らない。同じ形（`getClass().getAnnotation` を default メソッドで毎回）は
`Nullable` にもあったので一緒に直した。

## ケース30: 参照・診断の記録で memo の state version を進めない（Java、#269）

### 仮説

tinyexpression の全テストを unlaxer 開発版で回すと、5 段ネストの if 式（fraud formula #5）の parse が公開版 3.0.15 の 25 ms から 4〜6 秒に
退行していた（tinyexpression の Java CI は公開版 jar でしか走らないので見えていなかった）。transparent listener で数えると
begins 427 万、失敗 memo hit 22.9 万、空白 delimitor は 91 位置で 49.9 万回 commit。最初は「#194 で成功 memo が消えたため」と考え、
安全な成功 memo（ケース31）を codex に実装させたが、式 #5 は 7.75 → 4.74 秒で目標未達。失敗 memo の hit はあるのに再導出が止まらない理由を
追うと、`ScopeStore.addReference` / `addDiagnostic` が毎回 `markMemoizationStateChanged()` を呼んでいた。memo の key は `stateVersion` を
含むので、`$var` の参照を 1 つ commit するごとに以降の lookup が全て miss になる（rollback で version は戻るが、次の試行の参照で新しい
version が払い出される）。参照と semantic diagnostic は parse 中には読まれない（LSP / evaluator が parse 後に読む）ので、記録しても
memoized rule の結果は変わらない。

### 実装（PR #273）

`addReference` / `addDiagnostic` から version 更新を外す 2 行の変更。scope の enter / leave、`declare`、diagnostics の clear は従来どおり進める。
`ScopeStoreTransactionTest` の期待値を新契約に更新（参照・診断は version を変えず、rollback で state と version が戻る）。

### 観測

tinyexpression `P4PackratFraudFormulaTest`（cold JVM、isolated repo、unlaxer master `cb128f7` vs 本 PR）: 式 #4 445 → 82 ms、式 #5 **4,680 → 86 ms**。
unlaxer 開発版でこのテストが通るようになった。

tinyexpression（JMH、同じ tinyexpression コード `c70416e1`、交互 3-run 中央値。「+成功 memo」はケース31 を重ねた値）:

| Bench | Fixture | Baseline runs | median | 本 PR runs | median | 変化 | +成功 memo runs | median | 変化 |
|---|---|---|---:|---|---:|---:|---|---:|---:|
| publicFacade | complex | 30.623 / 31.662 / 30.172 | **30.623** | 22.980 / 23.337 / 21.935 | **22.980** | **-24.96%** | 12.193 / 13.008 / 12.320 | **12.320** | **-59.77%** |
| publicFacade | comparison-heavy | 17.838 / 17.811 / 19.155 | **17.838** | 18.221 / 18.654 / 18.069 | **18.221** | +2.15% | 9.904 / 9.396 / 9.314 | **9.396** | **-47.32%** |
| parseOnlySafe | complex | 37.072 / 35.511 / 39.809 | **37.072** | 25.991 / 26.920 / 27.486 | **26.920** | **-27.38%** | 15.049 / 14.974 / 17.214 | **15.049** | **-59.41%** |

x64 / 失敗入力（1 fork × 5 iteration）:

| Bench | Fixture | baseline ms | 本 PR ms | 変化 | +成功 memo ms | 変化 |
|---|---|---:|---:|---:|---:|---:|
| publicFacade | complex-x64 | 2008.2 | 1426.0 | -29.0% | 958.1 | -52.3% |
| parseOnlySafe | complex-x64 | 2413.4 | 1825.3 | -24.4% | 973.8 | -59.7% |
| facadeAny | complex-half（失敗） | 31.5 | 25.9 | -17.6% | 16.1 | -49.0% |
| facadeAny | complex-tail（失敗） | 82.9 | 51.6 | -37.8% | 28.6 | -65.5% |

comparison-heavy は変数参照が少なく（比較の両辺が literal 中心）、version 更新の影響を受けていなかったのでノイズ内。complex 系は
`$var` を多く含み、失敗 memo が初めて効くようになって -25%。

### 教材としての要点

memo の key に入れる「状態」は、その状態を**読む**処理があるものだけでよい。書くだけの蓄積（参照リスト、診断）まで version に
含めると、key が毎回変わって memo 表は「hit する形をしているのに hit しない」表になる。hit 数のカウンタは非ゼロだったので、
カウンタだけ見ていると気づかない。位置ごとの重複 commit 数（「91 位置で 49.9 万回」）のように**再導出そのもの**を数えて初めて、
memo が効いていない区間が見えた。仮説（成功 memo の欠落）を実装で確かめてから真因に至ったので、回り道の実装も無駄ではなく、
ケース31 でそのまま採用できた。

## ケース31: 安全な rule に限定した成功 memoization を戻す（Java、#269 の続き）

### 仮説

ケース30 の真因（version 更新）を直しても、8 択 × 5 段の BranchExpression が同じ内側の `if` を成功として再導出する構造は残る。
3.0.15 には listener を含まない部分木の成功 memo があり、#194 で「安全な再設計は別課題」として外されていた。#194 の fail-closed 契約
（生成器が exact class 単位で安全を証明する）を保ったまま、成功も replay できれば、複数の選択肢が同じ部分木を成功させる文法で
再導出が消えるはず。

### 実装（PR #274。codex に委譲しレビュー）

- 新 marker `SafeSuccessMemoizable extends SafeFailureMemoizable`。生成器は「失敗 memo 安全 かつ 自身が TransactionListener でない
  （`@scopeTree` / `@declares` / `@backref` なし）かつ 推移閉包に listener rule・custom token・recovery wrapper を含まない」rule と、
  空白 delimitor（`SpaceParser` / `CPPComment` / `BlockComment` の `ZeroOrMore`）に付ける。helper は自身の body で判定。tinyexpression では 50 class
- runtime: 成功時に commit 前の token 列を deep copy して保持（終了 consumed / matched、選択した child、内側 Choice の選択、rule-local の
  `FailureDiagnostic` frame も保持）。hit 時は `begin` → deep copy を splice → cursor を進める → `commit` の通常経路で replay し、
  診断 frame を外側へ流す。transaction イベントの再実行は行わない（対象 class は transactional state を変えないため。二重に hook が走るのを避ける）
- 入口は `AbstractParser` / `Chain` / `Choice` / `LongestChoice` / `PredictiveChoice` の 5 つに加え、空白 delimitor が通る `Occurs` にも接続
- `Memoization` の enum は増やさず、`SAFE_FAILURES` で成功 replay も有効になる（既定 OFF は不変）。Rust は不変

### 観測

ケース30 の表の「+成功 memo」列（unlaxer `cb128f7` → #273 + 本 PR、tinyexpression `c70416e1`、交互 3-run 中央値）:
publicFacade complex 30.62 → **12.32 ms（-59.8%）**、comparison-heavy 17.84 → **9.40 ms（-47.3%）**、parseOnlySafe complex 37.07 → 15.05 ms（-59.4%）。
x64 facade 2008 → 958 ms（-52.3%）、失敗入力 -49〜-66%。fraud 式 #5 は 86 → 59 ms、#4 は 82 → 57 ms。
#273 単体（-25% / +2%）に対して、成功 memo は comparison-heavy にも効く（変数参照の有無に関係なく、literal 中心の部分木が再導出されていた）。

成功 memo だけ（ケース30 の修正なし）では式 #5 は 7.75 → 4.74 秒で目標未達だった。version が進み続ける状態では、成功 entry も
失敗 entry と同様に hit しない。

### 教材としての要点

「安全性のために外した最適化」を戻すときは、外した理由（listener・状態依存・診断の一致）を個別に条件化して、条件を満たす部分だけに
限定して戻す。exact class の marker という既存の枝（#194）に乗せたので、runtime の判定は `ClassValue` 1 回で済み、subclass が override
しても暗黙に安全にならない。効果は真因の修正（ケース30）と重ねて初めて出た。単独で測って捨てていたら、-60% を取り逃していた。
ケース30 の教訓（hit カウンタではなく再導出を数える）と合わせ、施策の順序を間違えたときに「結果が出ない＝施策が無意味」と
結論しないための例になる。

## ケース32: 入力長に対する超線形の正体は GC だった（Java、#276。空コンテナの遅延化で割当 -12%）

### 仮説

別 repo `ubnfc` の実装横断比較（`ubnfc/docs/reports/2026-09-23-parser-speed-comparison.md` Family A）で、
tinyexpression P4 文法の 4 実装のうち **unlaxer コンビネータ（Java）だけが超線形**だった。認識 µs/byte は
complex 332 B の 41.5 から complex-x64 20,923 B の 77.1（**×1.86**）で、他の 3 実装は ×0.84〜1.17。
x64 は complex を `+` で 64 回つないだ 1 本の式なので、文法上は線形であるべき。
ケース21（Rust）と同じく「memo 表の走査」「Token スタックの複製」「診断の集約」のどれかが超線形だと考え、
[[scaling-check-and-cache-locality]] の手順どおり **まず 1 byte あたりの操作回数を数えた**。

### 計測（回数カウンタ、1 parse、cold、`SAFE_FAILURES` / `Diagnostics` は DETAILED）

`ParseContext` / `PackratMemoTable` / `Token` / `TokenList` / `StringSource` に一時カウンタを入れた
build（merge していない）で x1 / x4 / x16 / x64 を 1 回ずつ解析した。入力倍率は 1.00 / 3.89 / 15.60 / **63.02**。

| カウンタ | complex | x4 | x16 | x64 | x64/x1 | byte あたり |
|---|---:|---:|---:|---:|---:|---:|
| rule 評価（`startParse`） | 14,487 | 56,119 | 222,163 | 888,979 | 61.4 | **0.97** |
| transaction begin | 13,986 | 53,606 | 211,382 | 842,486 | 60.2 | 0.96 |
| commit / rollback | 5,946 / 8,040 | 23,002 / 30,604 | 90,874 / 120,508 | 362,362 / 480,124 | 60.9 / 59.7 | 0.97 / 0.95 |
| memo lookup | 11,810 | 45,417 | 179,493 | 715,797 | 60.6 | 0.96 |
| memo hit（失敗 / 成功） | 2,044 / 3,172 | 7,828 / 12,304 | 30,964 / 48,832 | 123,508 / 194,944 | 60.4 / 61.5 | 0.96 / 0.98 |
| memo 表エントリ数（終了時） | 1,695 | 6,474 | 25,590 | 102,054 | 60.2 | 0.96 |
| memo 診断 frame 生成 | 2,336 | 8,978 | 35,546 | 141,818 | 60.7 | 0.96 |
| memo transaction frame 走査 | 574,204 | 2,307,220 | 9,216,628 | 36,854,260 | 64.2 | 1.02 |
| Token 生成 | 14,317 | 55,874 | 221,798 | 888,134 | 62.0 | 0.98 |
| Token `deepCopy` node | 474 | 1,899 | 7,599 | 30,399 | 64.1 | 1.02 |
| `StackSnapshot` node | 30,013 | 116,539 | 462,403 | 1,848,499 | 61.6 | 0.98 |
| expected `addAll` 反復 | 171,196 | 668,023 | 2,655,331 | 10,604,563 | 61.9 | 0.98 |
| `registerFailureCandidate` | 7,079 | 26,964 | 106,152 | 422,904 | 59.7 | 0.95 |
| `codePointsOf` 文字数 | 26,120 | 104,963 | 426,016 | 1,737,088 | 66.5 | 1.06 |
| `TokenList.toSource` 連結文字数 | 15,251 | 65,629 | 269,720 | 1,110,104 | 72.8 | 1.16 |
| **割当 byte / parse** | 27.5 MB | 106.0 MB | 419.7 MB | 1,679.8 MB | 61.1 | **0.97** |

**どのカウンタも線形**（byte あたり 0.95〜1.16）。割当**量**すら byte あたり一定（約 80 KB/byte）。
つまり超線形な「回数」は存在しない。

### 真因: GC（`-Xms2g -Xmx2g`、`taskset` で 1 CPU に固定 → Serial GC）

同じ計測に `-Xlog:gc` を足して区間で見ると、正体が出た。

| fixture | young GC 回数 | pause 合計 | プロセス wall | **GC 比率** | 1 回の平均 pause | GC 後の live |
|---|---:|---:|---:|---:|---:|---:|
| complex | 297 | 1.91 s | 118.9 s | **1.6%** | 6.4 ms | 5.6 MB |
| complex-x4 | 289 | 5.95 s | 96.5 s | **6.2%** | 20.6 ms | 14.1 MB |
| complex-x16 | 136 | 13.78 s | 61.1 s | **22.6%** | 101.3 ms | 349.6 MB |
| complex-x64 | 457 | 124.0 s | 304.6 s | **40.7%** | 271.2 ms | 908.4 MB |

機構は掛け算である。**1 parse の割当が入力長に比例する**（1.67 GB @ x64、young 世代 約 550 MB）ので
**1 parse あたりの GC 回数が N に比例**し、同時に **1 parse の live 集合（構築中の CST と packrat memo 表）も N に比例**
するので **1 回の GC の copy/promote コストも N に比例**する。積で O(N²)。

ヒープだけ変えた対照実験（同じ binary・同じコア、`-Xms16g -Xmx16g`）がこれを裏づける。

| fixture | 2 GB の ms | 16 GB の ms | 16 GB の µs/byte | 16 GB の GC 比率 | **GC を除いた µs/byte** |
|---|---:|---:|---:|---:|---:|
| complex | 19.14 | 15.02 | 45.25 | 0.28% | 45.1 |
| complex-x4 | 58.66 | 55.46 | 42.90 | 0.81% | 42.5 |
| complex-x16 | 315.74 | 269.82 | 52.10 | 3.06% | 50.5 |
| complex-x64 | 1,926.14 | 1,350.85 | 64.56 | 16.75% | 53.7 |

x64 はヒープを増やすだけで **-30%**。**GC pause を引くと complex → x64 は ×1.19 で、目標の ×1.2 に収まる。**
つまりパーサ本体（rule 評価・memo・診断・Token 構築）は入力長に対して線形で、超線形なのは GC だけである。

### 実装（PR #277）: 常に空の入れ物を作るのをやめる

主因が「1 byte あたり 80 KB の割当と、それに比例して伸びる live 集合」なので、
**必ず確保されるのに大半が空のまま捨てられるコンテナ**を潰した。JFR の割当プロファイル（x64）で上位に出たもののうち、
意味を変えずに消せるものだけを選んでいる（比率は候補発見にのみ使用、[[jfr-allocation-attribution-caveat]]）。

- `Token.extraObjectByName` / `relatedTokenByName` を**遅延生成**にした。1 commit につき 1 Token（x64 で 888k）作られ、
  そのたびに `NullSafetyConcurrentHashMap` 2 個と null 番兵 `Object` 4 個を確保していた（割当の約 11%）。
  しかも木が生きている間ずっと到達可能なので live 集合にも効く。`null` の map は空の map と同じように読める。
  `put` は元々 null key/value を捨てる実装なので、null 引数では map を作らない。
  map 自体は concurrent なままなので、生成も `volatile` + double-checked locking で安全に publish する
  （書き込み経路だけを通る。parse 本体は 1 度も触らない）
- `ParseContext.ExpectedSources` の重複除去索引（`IdentityHashMap` 2 個、既定で `Object[64]` = 272 B 各）を
  **初回の add まで遅延**し、memo 表へ格納する時点で `releaseIndexes()` で捨てるようにした。
  診断 frame は memo 可能 rule 呼び出しごとに作られ（x64 で 141,818 個）、memo された分（90,907 個）は
  parse が終わるまで生き残る。格納後の frame は読むだけ（`mergeFrame` と `replayFailureDiagnostic` は
  `parsers` / `terminal` の 2 リストしか見ない）なので索引は不要。あとから add されても
  リストから索引を作り直すので、捨てることは観測できない
- `TransactionElement` の `IdentityHashMap`（checkpoint / choice / interleave の undo）を初期容量 4 で作るようにした。
  既定容量は `Object[64]`、実際の要素は数個
- `Token` の `Optional.of(this)` は子がある時だけ作る

### 観測

A/B の条件: `legacy-bench.sh root`、`SAFE_FAILURES`、`-Xss512m -Xms2g -Xmx2g`、`taskset -c 24`、
jar-first classpath、tinyexpression `c70416e1` を isolated maven repo で再ビルド。

base と candidate を **fixture ごとに交互**に実行した（共有機で load average 9〜49 と荒れたため）。
時間は全 run の中央値と最小値の両方を出す。割当は `ThreadMXBean` の 1 parse あたり byte。

| fixture | run 数 | base 中央値 | cand 中央値 | Δ | base 最小 | cand 最小 | Δ | base 割当 | cand 割当 | **Δ割当** |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| complex | 15 | 19.03 ms | 16.53 ms | -13.1% | 14.38 ms | 14.54 ms | +1.1% | 27.71 MB | 24.06 MB | **-13.2%** |
| complex-x4 | 6 | 68.66 ms | 58.84 ms | -14.3% | 53.99 ms | 50.75 ms | -6.0% | 106.13 MB | 93.11 MB | **-12.3%** |
| complex-x16 | 6 | 300.10 ms | 297.11 ms | -1.0% | 280.51 ms | 272.26 ms | -2.9% | 417.72 MB | 369.30 MB | **-11.6%** |
| complex-x64 | 15 | 2,098.13 ms | 1,669.22 ms | -20.4% | 1,552.84 ms | 1,390.95 ms | -10.4% | 1,679.38 MB | 1,471.91 MB | **-12.4%** |

同じ session の GC ログ（complex と x64、各 3 session 合算）:

| | GC 回数 | pause 合計 | GC 比率 |
|---|---:|---:|---:|
| base complex | 461 | 3.25 s | 1.60% |
| cand complex | 402 | 2.09 s | 1.08% |
| base complex-x64 | 897 | 300.6 s | 45.99% |
| cand complex-x64 | 769 | **178.3 s** | **37.22%** |

GC 回数は割当と同じ -14% だが、**pause 合計は -41%** 落ちた。消したのが（毎回作って捨てる分だけでなく）
**parse 中ずっと生きていた分**＝ Token の side map と memo 表の索引だったので、1 回あたりのコストも下がっている。

µs/byte の倍率（complex → complex-x64）は **中央値 ×1.75 → ×1.60、最小値 ×1.71 → ×1.52**。
**目標の ×1.2 には届いていない。** 残りは同じ機構（1 byte あたり 70 KB の割当）で、線形化するには
割当を桁で削る必要がある。#276 は open のままにした。

テスト: `unlaxer-common` 694 / `unlaxer-dsl` 1,004（skip 23）が緑。isolated repo で tinyexpression 全テスト
**769 件（skip 10）が緑**（base も同じ）。Rust は対象外 — `unlaxer-runtime` には「空でも確保される入れ物」が無く
（`Vec::new` / `HashMap::new` は確保しない、expected は ケース23 で interned `u32`）、GC も無い。
同じ比較で tinyexpression-rs は ×0.94 で平坦だった。

### 教材としての要点

- **「回数カウンタが全部線形なら、次に疑うのは表の大きさと解放」**（ケース21 の教訓）を GC のある言語へ引き写すと、
  **「GC 回数 × live 集合」**になる。どちらも N に比例するので積が O(N²) になる。
  この項は `-Xlog:gc` の pause 合計と wall の比で 1 行で出る。**時間を区間に分けるのは Rust の drop 区間だけの話ではない。**
- **ヒープサイズを変えるだけの対照実験は、GC が主因かどうかの一番安い判定法**である。
  同じ binary・同じコアで 2 GB → 16 GB にして x64 が -30%、GC を引いた µs/byte が ×1.19 になった時点で、
  「パーサのアルゴリズムは線形、遅いのは GC」が確定した。実装に手を付ける前にこれをやるべきだった。
- **Java で「空の入れ物」はタダではない。** `new ConcurrentHashMap<>()` は 1 個で数十 byte、
  `new IdentityHashMap<>()` は中身が 0 個でも `Object[64]`（272 B）を確保する。1 parse で 88 万個作られる Token が
  空の map を 2 個ずつ持つと、それだけで割当の 11% になり、木が生きている間ずっと live に乗る。
  Rust の `Vec::new()` / `HashMap::new()` は確保しないので、この種の差は**移植すると消える**。言語差を対応表に書く価値がある。
- 共有機で load average が 9〜49 に振れる状況では、**時間の中央値だけでは採否を決められない**。
  A/B を fixture ごとに交互実行し、中央値と最小値の両方、および `ThreadMXBean` の割当（負荷に依らない）を並べる。
  今回も採否の根拠は割当 -12%（4 サイズで一致）と GC pause -41% で、時間はそれを支持する位置にある。


## ケース33: 「割当 × live 集合」の両方を削る — 位置オブジェクトと診断の索引（Java、#276 round 2。割当 -27%）

### 出発点

ケース32 で、複合実装（Java）の超線形は **GC** だと分かった。1 parse の割当が入力長 N に比例し、
同時に live 集合（構築中の CST と packrat memo 表）も N に比例するので、
「GC 回数 × 1 回のコスト」の積が O(N²) になる。PR #281 は「必ず確保されるのに大半が空の入れ物」を潰して
割当 -12% を得たが、`complex → complex-x64` の µs/byte 倍率は ×1.60 で、受け入れ条件の ×1.2 には届かなかった。

round 2 では**積の 2 因子を両方**攻めた。すなわち、(a) 1 parse の割当量そのもの、(b) parse 中ずっと到達可能な live 集合。

### まず live 集合の中身を数える

`complex-x64` を解析中の JVM に `jcmd <pid> GC.class_histogram` を撃つ（既定で full GC を伴うので **live だけ**が出る）。
parse の進行に応じて 60〜171 MB の間で動き、終盤が最大になる。最大サンプル（163 MB、`FillerElement` は Serial GC の詰め物）:

| クラス | 個数 | live | 比率 |
|---|---:|---:|---:|
| `[Ljdk.internal.vm.FillerElement;` | 28 | 26.51 MB | 16.3% |
| `[Ljava.lang.Object;` | 340029 | 25.08 MB | 15.4% |
| `org.unlaxer.context.ParseContext$StackSnapshot` | 516194 | 19.69 MB | 12.1% |
| `java.util.ArrayList` | 563854 | 12.91 MB | 7.9% |
| `org.unlaxer.Token` | 142879 | 7.63 MB | 4.7% |
| `org.unlaxer.StringSource` | 134378 | 7.18 MB | 4.4% |
| `java.util.HashMap$Node` | 195420 | 5.96 MB | 3.7% |
| `org.unlaxer.CodePointIndex` | 314241 | 4.79 MB | 2.9% |
| `[I` | 90458 | 4.76 MB | 2.9% |
| `org.unlaxer.TokenList` | 292946 | 4.47 MB | 2.7% |
| `org.unlaxer.EndExclusiveCursorImpl` | 136565 | 4.17 MB | 2.6% |
| `org.unlaxer.StartInclusiveCursorImpl` | 136519 | 4.17 MB | 2.6% |
| （以下 `FailureDiagnostic` 67,192 / `String` 145,213 / `Range` 142,880 / `CodePointOffset` 208,168 / `CursorRange` 136,519 / `PackratMemoTable$Entry` 67,180 / `PositionKey` 67,180 / `Optional` 163,252 / `ExpectedSources` 67,193 / `Depth` 134,378） | | 約 36 MB | 22% |

**live 集合のおよそ半分が診断の payload** だった。`StackSnapshot` 516,194 ノード（12.1%）、
`ArrayList` 563,854 個 + その `Object[]`（合計 23%）、`FailureDiagnostic` 67,192、`ExpectedSources` 67,193。
これらは memo 表に入った失敗診断（x64 で約 9 万件）が parse 終了まで抱えている。
`CursorRange` 136,519 + 2 種の cursor 273,084 + `CodePointIndex` 314,241（合計 7.4%）は
**Token 1 個につき 1 組**で、これは後述のとおり「捨てられる値」だった。

### 次に割当元を数える（JFR、比率は候補発見のみ）

x64 の `ObjectAllocationSample` を型と割当元で集計すると、上位は次のとおり（master #281 時点）。

| 割当元 | 比率 |
|---|---:|
| `Object[]` 合計 | **31.2%** |
| ├ `IdentityHashMap.resize` / `init` ← `ExpectedSources.addFailed` / `addTerminal` | **12.8%** |
| ├ `ArrayList.grow` ← `ArrayList.add` | 13.2% |
| └ その他 | 5.2% |
| `ArrayList`（`TokenList.<init>` ほか） | 10.0% |
| `EndExclusiveCursorImpl`（`StringSource.<init>` 経由を含む） | 6.0% |
| `StackSnapshot` | 5.6% |
| `StringSource` | 5.0% |
| `TokenList` / `Token` | 9.0% |

[[jfr-allocation-attribution-caveat]] のとおり比率は候補発見にだけ使い、採否は
`ThreadMXBean` の 1 parse あたり割当 byte と時間の前後差で決めた。

### 施策1: `cursorRange` を遅延化し、Token の range は直接作る

`StringSource` は構築時に必ず `CursorRange`（+ `StartInclusiveCursorImpl` + `EndExclusiveCursorImpl` +
`CodePointIndex` ×2 + `CodePointOffset`）を作っていた。1 parse で sub-source は x64 で 123 万個作られるが、
その大半は「一致しなかった peek」で、cursor range を一度も読まれない。

素直に遅延化するだけでは効かない。`Token` のコンストラクタが
`token.cursorRange().toRange()` を**毎 Token 呼ぶ**ので、結局 88 万個ぶん作られるからである。
ここで cursor の `position()` は **その source 内の位置**（`position - offsetFromRoot`）であり、
`StringSource` はどの種類（root / detached / subSource）でも cursor を `offsetFromRoot` に置いて
cursor 自身の `offsetFromRoot` も同じ値にしている。つまり **`cursorRange().toRange()` は常に `[0, codePointLength)`** である。
そこで `Source.sourceRange()` を default メソッドとして足し（既定実装は `cursorRange().toRange()` のまま）、
`StringSource` では `new Range(0, codePoints.length)` を直接返す。`Token` はこちらを使う。

等価性は推論だけで済ませず、`sourceRange()` の中で `cursorRange().toRange()` と突き合わせて
食い違ったら例外を投げる一時ビルドで確認した。`unlaxer-common` 694 + `unlaxer-dsl` 1,009 の全テストと、
`complex` / `complex-x64` の実 parse（Token 88 万個）で **不一致 0 件**。

**割当 -13.5%**（complex 23.08 → 20.01 MB、x64 1,403.7 → 1,213.2 MB）。

### 施策2: 期待集合の重複除去を `IdentityHashMap` からオープンアドレス索引へ

`ParseContext.ExpectedSources` は「失敗の先端で期待された parser」を初出順・重複なしで持つ。
PR #281 で索引を遅延生成＋memo 格納時に破棄するようにしたが、それでも
**`IdentityHashMap` の table が割当の 12.8%** を占めていた。`IdentityHashMap` は key と value を隣接スロットに置くので
1 エントリ 2 スロットを使い、既定容量から先端 parser 集合が育つたびに resize する。

`List<Parser>` + `List<Boolean>` + `Set<Parser>` ×2 を、次の 3 つに置き換えた。

- `Parser[] parsers` + `boolean[] terminal`（初出順、容量 4 から倍々）
- `int[] index`: `(parser, kind)` に対するオープンアドレス索引。スロットには**エントリ番号 + 1** を入れる
  （0 が空）。負荷率 1/2。**16 エントリ未満の frame は索引を作らない**（連続した参照の線形走査のほうが速い）
- `addAll` の 2 つの近道: 移し先が空なら（`mergeFrame` で「子のほうが先へ進んだ」分岐が毎回 `clear()` してから
  呼ぶので、これが支配的）**重複除去なしで `System.arraycopy`**。空でなければ最終サイズを先に確保して、
  1 回の伸長・1 回の索引構築で済ませる

Rust 版（[[ケース23]]）が `expected: Vec<u32>`（interned id）+ 線形 `contains` で済ませているのと同じ形である。

**割当 さらに -14.1%**（complex 20.01 → 16.70 MB、x64 1,213.2 → 1,014.1 MB）。
2 つ合わせて **-27%**（4 サイズで一致）。

### 観測（A/B）

条件: `ubnfc/examples/p4-java/scripts/legacy-bench.sh root`（= JMH `parseFreshToken(memoized)` と同じ経路）、
`Memoization.SAFE_FAILURES` / `Diagnostics` は既定の `DETAILED`、`-Xss512m -Xms2g -Xmx2g`、`taskset -c 24`（1 CPU → Serial GC）、
jar-first classpath、tinyexpression `c70416e1` を isolated maven repo で再ビルド。
base（master `b3cbc6c` = #281 込み）と candidate を **fixture ごとに交互**に実行し、中央値と最小値の両方を出す。
共有機のため load average は 5〜22 で動いた。割当は `ThreadMXBean` の 1 parse あたり byte（負荷に依らない）。

**全セッション（fixture ごとに交互実行、load 5〜22）**
| fixture | run 数 base/cand | base 中央値 | cand 中央値 | Δ | base 最小 | cand 最小 | Δ | base 割当 | cand 割当 | **Δ割当** |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `complex` | 10/7 | 12.64 ms | 10.81 ms | -14.5% | 12.28 ms | 9.95 ms | -18.9% | 22.96 MB | 16.68 MB | **-27.4%** |
| `complex-x4` | 10/7 | 55.59 ms | 44.59 ms | -19.8% | 48.44 ms | 42.21 ms | -12.9% | 88.87 MB | 64.55 MB | **-27.4%** |
| `complex-x16` | 10/7 | 234.21 ms | 202.71 ms | -13.5% | 225.20 ms | 170.26 ms | -24.4% | 351.29 MB | 253.27 MB | **-27.9%** |
| `complex-x64` | 15/12 | 1394.05 ms | 1091.55 ms | -21.7% | 1276.26 ms | 989.86 ms | -22.4% | 1402.09 MB | 1022.49 MB | **-27.1%** |

| fixture | byte | base µs/byte 中央 | cand µs/byte 中央 | base µs/byte 最小 | cand µs/byte 最小 |
|---|---:|---:|---:|---:|---:|
| `complex` | 332 | 38.07 | 32.56 | 36.99 | 29.98 |
| `complex-x4` | 1293 | 42.99 | 34.48 | 37.47 | 32.65 |
| `complex-x16` | 5179 | 45.22 | 39.14 | 43.48 | 32.88 |
| `complex-x64` | 20923 | 66.63 | 52.17 | 61.00 | 47.31 |
- base: `complex` → `complex-x64` の µs/byte 倍率 中央値 **x1.75** / 最小 **x1.65**
- cand: `complex` → `complex-x64` の µs/byte 倍率 中央値 **x1.60** / 最小 **x1.58**

**x64 だけの静穏セッション（他の負荷を止め、GC ログ付きで runs=3 を 2 セッション）**
| fixture | run 数 base/cand | base 中央値 | cand 中央値 | Δ | base 最小 | cand 最小 | Δ | base 割当 | cand 割当 | **Δ割当** |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `complex-x64` | 6/6 | 1311.39 ms | 1059.08 ms | -19.2% | 1276.26 ms | 989.86 ms | -22.4% | 1402.90 MB | 1022.49 MB | **-27.1%** |

| fixture | byte | base µs/byte 中央 | cand µs/byte 中央 | base µs/byte 最小 | cand µs/byte 最小 |
|---|---:|---:|---:|---:|---:|
| `complex-x64` | 20923 | 62.68 | 50.62 | 61.00 | 47.31 |

**GC ログ（`-Xlog:gc`、1 セッション・runs=1、プロセス全体の pause 合計 / wall）で分解する。**

| fixture | byte | base µs/byte | base GC 比率 | **base GC を引いた µs/byte** | cand µs/byte | cand GC 比率 | **cand GC を引いた µs/byte** |
|---|---:|---:|---:|---:|---:|---:|---:|
| `complex` | 332 | 38.07 | 1.05% | **37.67** | 32.56 | 0.90% | **32.27** |
| `complex-x4` | 1293 | 42.99 | 3.78% | **41.36** | 34.48 | 3.08% | **33.42** |
| `complex-x16` | 5179 | 45.22 | 14.99% | **38.44** | 39.14 | 11.73% | **34.55** |
| `complex-x64` | 20923 | 66.63 | 38.38% | **41.06** | 52.17 | 34.39% | **34.23** |

**GC pause を引いた µs/byte の倍率（`complex` → `complex-x64`）は base ×1.09 / cand ×1.06。**

### GC 設定は「対照」であって「修正」ではない

同じ candidate を x64 で JVM 設定だけ変えて測った（採否の根拠にはしない。
どこまでがパーサの費用で、どこからが collector の挙動かを読者が切り分けられるようにするため）。

| x64 の条件 | 中央値 | µs/byte | GC 回数 | pause 合計 | GC 比率 |
|---|---:|---:|---:|---:|---:|
| base、Serial、2 GB（issue の条件） | 1,311.39 ms | 62.68 | 643 | 126.9 s | 39.00% |
| **cand、Serial、2 GB** | **1,059.08 ms** | **50.62** | 497 | 97.0 s | 34.34% |
| cand、**ParallelGC**、2 GB | 915.99 ms | 43.78 | 358 | 53.1 s | 31.26% |
| cand、Serial、**16 GB** | 795.68 ms | 38.03 | 45 | 11.3 s | 6.91% |
| base、Serial、16 GB | 907.81 ms | 43.39 | 57 | 16.8 s | 9.89% |

`taskset -c 24` で 1 CPU に固定すると JVM は Serial GC を選ぶ。**同じ binary のまま collector を替えるだけで -13.5%、
ヒープを 2 GB → 16 GB にするだけで -24.9%** 動く。つまり issue の計測条件そのものが
「live 集合 × GC 回数」を最大化する設定である。実運用の比較では
`-XX:ActiveProcessorCount` か明示的な GC 指定を条件に書いたほうがよい（ケース32 の 5 番と同じ指摘）。

### 到達点と残り

- **割当は 4 サイズすべてで -27%**（`ThreadMXBean`、負荷に依らない）。GC 回数 -22%、pause 合計 -23%、
  x64 の GC 比率 39.0% → 34.3%。時間は中央値 -13〜-22%。
- **受け入れ条件の ×1.2 には届いていない。** `complex` → `complex-x64` の µs/byte 倍率は
  **×1.75 → ×1.60**（中央値、2 GB / Serial）。
- ただし**分解すると、パーサ本体は既に線形**である。GC pause を引いた µs/byte の倍率は
  **base ×1.09 / cand ×1.06**。ヒープを 16 GB にした対照でも候補は **38.03 µs/byte**（`complex` の 32.56 に対して **×1.17**）で、
  **GC が制約でない条件なら目標を満たす**。
- 残る ×1.6 は全部 collector である。機構はケース32 のとおり「GC 回数（∝ 割当）× 1 回のコスト（∝ live 集合）」で、
  今回削ったのは**割当（第 1 因子）だけ**。live 集合は診断 payload が約半分を占めたまま減っていないので、
  積は線形にしか改善しない。**×1.2 を既定経路で満たすには第 2 因子（live 集合）を削る必要がある** = 診断の 2 モード化（#263）。

### 次の一歩（測った上での順序）

施策後の x64 割当プロファイルで残っている上位は次のとおり。

| 割当元 | 比率 | 備考 |
|---|---:|---|
| `ArrayList` + その `Object[]`（`TokenList.<init>` 経由が最大） | 約 20% | Token 1 個につき `originalChildren` / `filteredChildren` の 2 本。葉 token では両方とも空のまま |
| `int[]` / `Parser[]`（`ExpectedSources` の索引と入れ物） | 約 12% | 先端 parser 集合が大きい frame が残る |
| `StringSource` + `CodePointOffset` + `Depth` | 約 11% | sub-source を root の view にする案（#276 の 1 番）。ただし `int[] codePoints` の再生成は割当の 1.4% しかなく、見積もりより小さい |
| `StackSnapshot` | 6.7% | memo 済み診断が抱える分。`rebaseMemoStack` の `concat` が 4.1% |
| `EndExclusiveCursorImpl` + `ParserCursor` | 10% | `TransactionElement.createNew`（rule 評価ごと） |

**最大の残りは `TokenList` の裏の `ArrayList`** で、葉 token の空リストを遅延化すれば約 6% 減る見込みだが、
`TokenList` は `List<Token>` を実装した可変クラスで変更メソッドが 15 以上あり、1 つでも取りこぼすと
実行時に `UnsupportedOperationException` になる。**1 PR 1 施策**の原則に従い、本 PR には入れず記録に留める。

本質的な残りは **診断そのもの**である。`Diagnostics.DETAILED` は成功経路でも失敗診断を作り続けるので、
memo された診断が parse 終了まで live に乗る（live 集合の約半分）。`DETAILED_ON_FAILURE` は
`discardMemoDiagnosticFrame` / `replayFailureDiagnostic` を丸ごと早期 return するので、この分がまるごと消える。
2 モード化は #263 の担当で、本 issue の受け入れ条件（既定経路の µs/byte）を満たす唯一の残り手段でもある。

### 教材としての要点

- **「live 集合が原因かも」と思ったら、まず `jcmd GC.class_histogram` を parse 中に撃つ。** 既定で full GC を伴うので
  live だけが出る。x64 では 163 MB のうち診断 payload が約半分で、CST 本体（Token + StringSource + TokenList）は 12% しかなかった。
  **どこを削れば GC が軽くなるかは、割当プロファイルではなく live のヒストグラムが答える。**
- **「常に同じ値になる派生値」は作らないで済む。** `cursorRange().toRange()` が全ての `StringSource` で
  `[0, length)` だと分かった時点で、Token ごとの `CursorRange` + cursor 2 個 + index 2 個が不要になった。
  こういう等価性は**推論で済ませず、両方を計算して突き合わせる一時ビルド**で確かめる（今回は全テスト + 実 parse で不一致 0）。
- **`IdentityHashMap` を「小さな集合」に使うのは高い。** key と value で 2 スロット、既定容量から resize、
  `Collections.newSetFromMap` の包みも 1 個。**数十個までの identity 集合なら、オープンアドレスの `int[]` 索引か、
  そもそも配列の線形走査のほうが速くて小さい。** Rust 版が `Vec<u32>` + `contains` で済ませていたのは正しかった。
- **「移し先が空なら重複除去は要らない」** — 集合の合流は、片方が空なら `arraycopy` である。
  合流が支配的な処理では、この 1 分岐が索引構築を丸ごと消す。
- 割当を -27% にしても **µs/byte の倍率は ×1.75 → ×1.60 にしかならない**。
  GC 回数は割当に比例して減るが、**1 回のコストを決める live 集合は減っていない**からである。
  積の片方だけを削ると効果は線形にしか効かない。ケース32 の式（GC 回数 × live 集合）は、
  どちらを削っているのかを常に確認するために使う。

## ケース34: `DETAILED_ON_FAILURE` が読まない観測を作らない・持ち続けない（Java、#263）

### 出発点

ケース33（#276 round 2）で、複合実装（Java）の超線形は「GC 回数（∝ 1 parse の割当）× 1 回のコスト（∝ live 集合）」の
積であり、**残差は全部 collector** だと分かった。既定の `Diagnostics.DETAILED` では live 集合の約半分が診断 payload で、
`DETAILED_ON_FAILURE`（ケース26）はその記録を行わない。残る問いは #263 のもの、すなわち
**「診断を記録しないモードでも払い続けている upkeep は何か」**である。#263 はそれを (a) `ParseFrame` の push / pop、
(b) memo hit の transaction replay のための frame 管理、(c) `trackCursorProgress` 相当の cursor 参照、と見立てていた。

### まず数える（カウンタだけを足した計測専用 build、`complex-x64` を 1 parse）

| 数えたもの | base | 施策後 | 備考 |
|---|---:|---:|---|
| `ParseFrame` 生成（= `startParse`） | **888,979** | **0** | 生成した frame を読む経路が 1 つも無い |
| `terminalFrames` への push | 274,169 | **0** | 読み手は `registerFailureCandidate` だけ（早期 return） |
| `StackSnapshot` 生成 | **0** | 0 | (c) は既に無い（`DETAILED` では 1,848,499） |
| memo 診断 frame（`FailureDiagnostic`）生成 | 141,818 | 141,818 | transaction event の記録先として必要 |
| transaction event の記録呼び出し（begin + finish） | 1,684,972 | 1,684,972 | transaction ごとに 2 回 |
| ↳ **走査した frame スロット数** | **73,708,520** | **2,375,772** | 1 呼び出しあたり 43.7 → **1.41** |
| ↳ そのために割り当てた iterator | **1,684,972** | **0** | `ArrayDeque` の走査 1 回につき 1 個 |
| ↳ 実際に event を足した回数 | 690,802 | 690,802 | 記録内容は不変 |
| memo 表に載った frame | 102,054 | 102,054 | parse 終了まで live |
| ↳ それが抱える transaction event | 374,120 | 374,120 | 1 frame あたり 3.67。`ArrayList` → `byte[]` |
| ↳ **`ExpectedSources` を抱えた frame** | **102,054** | **0** | このモードでは 1 件も入らない（expected 総数 0） |
| ↳ **`trials` を抱えた frame** | **102,054** | **0** | memo と trial 記録は両立しない（`DETAILED` でも 0） |

`fixture` を変えても比は変わらない（`complex` は各 14,487 / 4,294 / 2,336 / 27,972 / 1,695 …、
入力 63 倍に対して 60〜62 倍）。

結論は #263 の見立てのうち **(a) と (b) が当たり、(c) は既に無い**。加えて、
**空のまま memo 表に載り続ける `ExpectedSources` と `trials`** というケース33 と同じ形の無駄が残っていた。
とくに `trials` は `DETAILED` でも 1 件も使われない（`isMemoizationSessionSafe` が trial 記録中は false を返すので、
memo frame に trial が入ることは構造上ありえない）。

### 施策1 (a): `DETAILED_ON_FAILURE` では `ParseFrame` を作らない

`ParseFrame` の読み手は `trackCursorProgress` / `registerFailureCandidate` / `replayFailureDiagnostic` /
`snapshotStackElements` / `deepestTerminalParser` の 5 つで、`DETAILED_ON_FAILURE` ではすべて早期 return するか、
到達しない。残る唯一の読み手は **明示的な trial 記録**（`startTrialRecording`。このモードでも使える）で、
そこが frame から取るのは `startOffset` 1 つだけである。

そこで、このモードでは frame の代わりに `int[]` の stack へ開始 offset だけを積む。倍々で伸びるので
rule 評価あたりの割当は 0 になる。`trials` の内容が `DETAILED` と一致することは
`DetailedOnFailureTest.trialRecordsAreIdenticalWithoutParseFrames` で固定した
（同じ入力・同じ parser 木で、parser 名・開始/終了位置・成否・消費数の列が完全一致）。
`terminalFrames` も同時に空のままになる（読み手が `registerFailureCandidate` だけなので）。

モードの判定は `options.diagnostics()` の enum 比較ではなく、constructor で 1 回だけ決める `final boolean` にした
（`startParse` / `endParse` / `consume` / `matchOnly` の全てで走るため）。

### 施策2 (b): 開いている memo transaction frame を配列で持ち、深さで打ち切る

`recordMemoTransactionBegin` / `Finish` は transaction の begin と finish のたびに
`Deque<FailureDiagnostic>` を走査する。`complex-x64` では 842,486 + 842,486 回、しかも**毎回非空**なので
走査のたびに `ArrayDeque` の iterator が 1 個割り当たり、さらに条件に合わない frame も最後まで見ていた。

frame の `transactionBaseDepth` は「その frame を開いた時点の transaction 深さ」で、frame が開いている間に
深さがそれを下回ることはない。つまり配列に積むと **深さで整列している**。そこで
`FailureDiagnostic[]` + 件数に置き換え、内側から外側へ走査して
`transactionBaseDepth < 記録する深さ` になった時点で打ち切る。iterator は消え、走査長は実質 1〜2 になる。

深さ 24 の入れ子（初期容量 16 を超える）で memo hit の state hook 回数が `DETAILED` と一致することを
`deeplyNestedMemoFramesReplayAfterTheFrameArrayGrows` で固定した。

### 施策3: memo frame の payload を「使うときに作る」

`FailureDiagnostic` は `ExpectedSources` と `List<TrialRecord>` と `List<MemoTransactionEvent>` を
**必ず** 3 つとも構築していた。memo 表に載った frame は parse 終了まで live なので、
`complex-x64` では 102,054 個ぶんが最後まで残る。実際には

- `expected` は `DETAILED_ON_FAILURE` では **1 件も入らない**（カウンタで 0 を確認）
- `trials` は memo と両立しない（`isMemoizationSessionSafe` が trial 記録中は false を返すので、
  memo frame に trial が入ることはない）
- `transactionEvents` だけが実際に使われ、1 frame あたり平均 3.7 件

なので、`expected` と `trials` は最初の書き込みで作る遅延生成にし（読み側は共有の空インスタンスを見る）、
`transactionEvents` は `ArrayList`（自身のヘッダ + 10 スロットの `Object[]`）をやめて
容量 4 から倍々に伸びる `byte[]`（enum の ordinal）にした。`MemoTransactionEvent.values()` は
呼ぶたびに配列を複製するので、replay 用に `static final` で 1 本持つ。

### (a)(b) の上限を no-op build で測る

施策を入れる前に、#263 の (a) と (b) を**丸ごと no-op にした計測専用 build**（transaction replay の意味論は壊れるので
計測にしか使えない）を base / 実装済み候補と同じ session で交互に走らせた。

| build | `complex` 中央値 / 最小 / 割当 | `complex-x64` 中央値 / 最小 / 割当 |
|---|---:|---:|
| base | 8.222 / 7.890 ms / 14.30 MB | 717.997 / 687.246 ms / 870.98 MB |
| (a)+(b) を no-op（上限） | 6.182 / 5.406 ms / 12.85 MB | 636.621 / 580.008 ms / 789.26 MB |
| 実装済み候補（施策1+2+3） | **5.715 / 5.653 ms / 13.31 MB** | **513.038 / 501.563 ms / 818.61 MB** |

上限は `complex` -24.8% / `complex-x64` -11.3%。**候補はその上限をほぼ回収している**
（`complex` -30.5%、`complex-x64` -28.5%。候補が上限より速いのは、no-op build には施策3
—— memo frame の payload 遅延化 —— が入っていないため。逆に no-op build のほうが割当が小さいのは、
transaction event を一切記録しないので `byte[]` すら作らないからで、これは意味論として採れない）。

### live 集合（`jcmd GC.class_histogram` を `complex-x64` の parse 中に 8 回）

memo 表のエントリ数が同じ時点どうしを比べる（parse の進行でどちらも単調に増えるので、
エントリ数を横軸にすると 2 つの build を直接比較できる）。

| 標本 | memo entry | `ArrayList` | `ExpectedSources` | `Object[]` | live（`FillerElement` 除く） |
|---|---:|---:|---:|---:|---:|
| base | 45,465 | 350,030 | 45,488 | 167,522 | 71.2 MB |
| **cand** | 45,212 | **258,037（-26.3%）** | **0** | **121,558（-27.4%）** | **66.2 MB（-7.0%）** |
| base | 100,501 | 561,197 | 100,524 | 270,196 | 106.6 MB |
| cand（8 標本の線形あてはめで同じ entry 数へ外挿） | 100,501 | — | 0 | — | **94.7 MB（-11.2%）** |

**memo entry 1 個あたりの限界 live コストは 634 byte → 532 byte（-16%）**
（8 標本の傾き。base 6.34e-4 MB/entry、cand 5.32e-4 MB/entry）。
`ExpectedSources` は live 集合から**完全に消えた**（100,524 個 → 0 個）。
残っているのは CST 本体（`Token` / `StringSource` / `TokenList`）と memo の `Entry` / `PositionKey` /
`HashMap$Node`、そして成功 memo が抱える token の deep copy で、これらは**意味論として必要なもの**である。

### 施策ごとの A/B（`legacy-bench.sh root-deferred`、`SAFE_FAILURES`、`-Xss512m -Xms2g -Xmx2g`、`taskset -c 24` → Serial GC）

`base` → `+施策1` → `+施策2` → `+施策3` を同じ session で fixture ごとに続けて実行した中央値 / 最小値 / 1 parse の割当。

| build | `complex` 中央値 | 最小 | 割当 | `complex-x64` 中央値 | 最小 | 割当 |
|---|---:|---:|---:|---:|---:|---:|
| base | 8.063 ms | 7.527 | 14.30 MB | 665.05 ms | 649.88 | 861.93 MB |
| +施策1（`ParseFrame` を作らない） | 6.836 ms | 6.585 | 13.67 MB | （外れ値）1158.92 | 690.76 | 826.91 MB |
| +施策2（frame 配列と打ち切り） | 5.674 ms | 5.549 | 13.72 MB | 592.12 ms | 550.20 | 823.37 MB |
| +施策3（payload の遅延生成） | **5.464 ms** | **5.352** | **13.36 MB** | **606.02 ms** | **575.41** | **810.67 MB** |

- `complex`: **-32.2%（中央値）/ -28.9%（最小）**、割当 **-6.6%**。
- `complex-x64`: **-8.9%（中央値）/ -11.5%（最小）**、割当 **-5.9%**。
- 施策1 の `complex-x64` 行は他エージェントの Rust ビルドが同じホストで走った窓に当たり、
  5 run 中 3 run が 2 倍近くに振れた（1216 / 1159 / 1175 / 871 / 691 ms）。最小値 690.76 ms を採り、
  この 1 マスだけ**施策の効果として読まない**。他のマスは同じ session の前後関係で一貫している。
- **時間の改善の大半は割当ではなく CPU である。** 割当は -6.6% しか減っていないのに `complex` は -32% になった。
  内訳はカウンタのとおりで、`complex-x64` 1 parse あたり **7,370 万回**あった frame スロットの走査が
  **238 万回**に、iterator 168 万個が 0 になったことが効いている。

### スケーリング（base → 候補、fixture ごとに交互実行、GC ログ付き）

`root-deferred`（`Diagnostics.DETAILED_ON_FAILURE`）:

| fixture | base 中央値 | cand 中央値 | Δ | base 割当 | cand 割当 | Δ | base GC 比率 | cand GC 比率 | base µs/byte | cand µs/byte |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `complex` | 8.109 ms | **6.294 ms** | **-22.4%** | 14.17 MB | 13.44 MB | -5.2% | 0.65% | 0.79% | 24.42 | 18.96 |
| `complex-x4` | 32.764 ms | **23.781 ms** | **-27.4%** | 54.20 MB | 50.83 MB | -6.2% | 2.27% | 2.45% | 25.34 | 18.39 |
| `complex-x16` | 135.199 ms | **107.691 ms** | **-20.3%** | 215.11 MB | 201.96 MB | -6.1% | 9.18% | 9.95% | 26.11 | 20.79 |
| `complex-x64` | 664.836 ms | **568.791 ms** | **-14.4%** | 863.05 MB | 819.51 MB | -5.0% | 26.67% | 27.90% | 31.78 | 27.18 |

`root`（既定の `Diagnostics.DETAILED`、退行確認）:

| fixture | base 中央値 | cand 中央値 | Δ | base 割当 | cand 割当 | base GC 比率 | cand GC 比率 |
|---|---:|---:|---:|---:|---:|---:|---:|
| `complex` | 10.341 ms | 10.569 ms | +2.2% | 17.50 MB | 17.17 MB | 0.76% | 0.81% |
| `complex-x4` | 43.918 ms | 40.034 ms | -8.8% | 67.08 MB | 66.37 MB | 2.97% | 3.06% |
| `complex-x16` | 180.933 ms | 170.266 ms | -5.9% | 264.69 MB | 260.57 MB | 11.74% | 12.53% |
| `complex-x64` | 1080.802 ms | 1045.492 ms | -3.3% | 1068.62 MB | 1052.18 MB | 34.49% | 36.09% |

既定モードに退行はない（`complex` の +2.2% は最小値では -8.2% に反転するノイズ）。施策2 と
施策3 の `trials` 遅延化は既定モードでも効くので、`complex-x4` 以降では -3〜-9% 速くなっている。

### #276 の ×1.2 について: **未達**、しかも比は悪化する

| モード | `complex` µs/byte | `complex-x64` µs/byte | **倍率** |
|---|---:|---:|---:|
| base `DETAILED` | 31.15 | 51.66 | ×1.658 |
| cand `DETAILED` | 31.83 | 49.97 | ×1.570 |
| base `DETAILED_ON_FAILURE` | 24.42 | 31.78 | ×1.301 |
| **cand `DETAILED_ON_FAILURE`** | **18.96** | **27.18** | **×1.434** |

**すべての絶対値が改善したのに倍率は 1.301 → 1.434 に悪化した。** 理由は分解すると明らかである。
GC pause を引いた µs/byte は **base ×0.96 / cand ×1.04** で、どちらもパーサ本体は線形。
比を決めているのは `complex-x64` の GC 比率（26.7% / 27.9%）だけで、
`complex` はほぼ GC ゼロ（0.65% / 0.79%）。**この施策は CPU を削るので GC がほぼ無い小入力ほど大きく効き、
GC 律速の大入力では効きが小さい。だから「小入力を速くすると倍率は上がる」。**

倍率 ×1.2 は「入力長に対する µs/byte の平坦さ」の指標なので、**GC が 27% を占める条件では
GC 回数（∝ 割当）か live 集合を桁で削らない限り満たせない**。今回の施策は割当を -5〜6% しか動かさない
（時間の改善は主に CPU）。ケース32 / 33 の結論（残差は全部 collector）は変わっていない。

### Java / Rust の対称性

Rust 側（`rust/unlaxer-runtime`）は**変更不要**である。今回の 3 施策に対応する構造をそれぞれ確認した。

- **(a) `ParseFrame` に相当するオブジェクトが無い。** Rust の parser は再帰関数で、parse stack は機械のスタックそのもの。
  `ParseFrame` / `terminalFrames` / `StackSnapshot` のような per-rule のヒープ構造を持たない。
- **memo entry の payload。** `failure_memo` の値は `FailureDiagnostic { farthest: Option<usize>, expected: ExpectedIds }`
  で、`Diagnostics::DetailedOnFailure` のときは `record_diagnostics == false` なので
  **診断 frame をそもそも push せず**、entry には `FailureDiagnostic::default()`（`farthest: None`、
  `ExpectedIds::Empty`）が入る。空のコンテナを確保して抱え続ける、という Java 側の無駄が構造的に存在しない
  （`ExpectedIds` は `Empty` / `Single(u32)` / `Multiple(Rc<Vec<u32>>)` の enum。ケース23）。
- **(b) transaction event の journal に相当するものが無い。** Rust の memo 安全判定（`expression_is_memo_safe`）は
  scope / user state に触れる rule を memo 対象から外すので、memo hit で state hook を replay する必要がない。
  Java は「安全な rule でも直下の transaction は開く」設計なので、この journal が要る。この差は
  `docs/java-diagnostics-policy.md` の対応表どおりで、今回も解消していない。

つまり「`DETAILED_ON_FAILURE` が読まないものを作らない」という本件の設計は、Rust では最初からそうなっていた。
Java 側をその形に寄せた変更であり、観測可能な振る舞いの差は生じない。

### 検証

- `unlaxer-common` 696（新規 2 件を含む）/ `unlaxer-dsl` 1,012（skip 23）緑。
- isolated maven repo で tinyexpression 全テスト **769 件（skip 10）緑**（`c70416e1` を候補 jar で再ビルド）。
- 失敗診断の同値性: 失敗入力 18 件 × `DETAILED` / `DETAILED_ON_FAILURE` × memo `OFF` / `SAFE_FAILURES` の
  72 通りで `ParseFailureDiagnostics` の全項目をテキスト化し、base / cand で **byte 一致**。
- 計測条件: `ubnfc/examples/p4-java/scripts/legacy-bench.sh`、`Memoization.SAFE_FAILURES`、
  `-Xss512m -Xms2g -Xmx2g`、`taskset -c 24`（1 CPU → Serial GC）、jar-first classpath、
  tinyexpression `c70416e1` を build ごとの isolated maven repo で再ビルド、fixture ごとに base/cand 交互。
  ホストは共有で、他エージェントの Rust ビルドが並走する窓があった（該当マスは本文で明示）。

### 教材としての要点

- **「モードを足す」と「モードが払う費用を消す」は別の作業である。** ケース26 で
  `DETAILED_ON_FAILURE` を入れた時点で記録は止まったが、記録の**器**（`ParseFrame`、memo frame の
  空コンテナ、frame 走査）はそのまま残っていた。差分計測の上限（-31.6%）に実測（-17.1%）が届かない、
  という当時の残差の中身がこれである。**モードを足したら、そのモードで「誰も読まないのに作っているもの」を
  カウンタで数え直す。**
- **「全部の open frame を走査する」は、整列が言える場所では打ち切れる。** 今回の `transactionBaseDepth` のように、
  スタック構造から単調性が言えるなら、走査は「合わなくなった時点で終わり」にできる。
  `Deque` のままでは iterator の割当が残るので、**打ち切りと iterator 除去はセットで**配列に置き換える。
- **memo 表に入るものは「1 parse ぶんずっと live」である。** だから memo entry が抱える空コンテナは、
  一時割当ではなく **live 集合**の話になる。ケース33 の教訓（どこを削れば GC が軽くなるかは
  割当プロファイルではなく live のヒストグラムが答える）と同じで、
  **「必ず作るが空のことが多い」入れ物は、memo に載る側から先に潰す。**
- **同値性は差分ダンプで固定する。** 失敗入力 18 件 × `DETAILED`/`DETAILED_ON_FAILURE` × memo `OFF`/`SAFE_FAILURES`
  の 72 通りについて `ParseFailureDiagnostics` の全項目（offset / line / column / stack / expected / hints /
  expectedTokens / deepestRule / trials）をテキストへ落とし、変更前後で **byte 一致**を確認した。
  「テストが緑」より強い証拠が要る変更では、この形の差分ダンプを作る。

## ケース35: memo 表の live 集合を入力長から切り離す（Java、#276 round 4）

### 出発点

ケース32〜34 で機構は確定している。**超線形 = GC 回数（∝ 1 parse の割当）× 1 回のコスト（∝ live 集合）**で、
GC pause を引いた µs/byte はどの build でも ×0.96〜×1.09、つまり**パーサ本体は線形**。round 2 は第 1 因子（割当 -27%）、
round 3 は entry 1 個あたりの live（634→532 byte、-16%）を削ったが、**第 2 因子の「entry 個数」は手つかず**で、
`complex-x64` では parse 終了まで **102,054 件**が live に残っていた（532 byte/件 ≒ 53 MB）。

round 4 の狙いは「1 件を小さくする」ではなく「**件数を入力長から切り離す**」である。

### 不変条件を先に書く（そして、それが役に立たないことを確かめる）

packrat の entry が再び読まれるのは、その開始位置へ parse が戻ってきたときだけである。
戻れる位置は「**開いている transaction のどれかの consumed cursor**」に限られる。
`Transaction.begin` は親の cursor を複製するので、開いている transaction の cursor は
スタックの下から上へ**単調非減少**であり、rollback は必ずどれかの開いた transaction の cursor へ戻り、
consume は前へしか進まない。したがって

> **committed frontier**（= 開いている transaction の cursor の最小値）より前の位置を持つ entry は、二度と参照されない。

これは厳密に正しい。**ところが、この最小値はこの実装では常に 0 である。** 最下段は `ParseContext` 自身が
コンストラクタで積む root `TransactionElement` で、位置 0 に置かれ、pop されることがなく、
最外 parser が commit する parse の最後にしか動かない。`tokenStack` を 1 ms ごとに覗く計測を入れて確かめた:

| fixture | frontier の最大値 | cursor - frontier（最大 / 平均） | root を除いた最外 parser の cursor - それ（最大 / 平均） | stack 深さ最大 |
|---|---:|---:|---:|---:|
| `complex` (326 B) | **0** | 325 / 78.2 | 185 / 35.1 | 78 |
| `complex-x16` (5,179 B) | **0**（最後の 1 標本のみ 5,179） | 5,179 / 1,444.4 | 5,180 / 575.1 | 79 |
| `complex-x64` (20,923 B) | **0** | 20,923 / 9,273.8 | **13,292 / 4,621.7** | 83 |

**厳密な frontier は正しいが、parse 中ずっと 0 である。** 最下段を除外すれば最外 parser の transaction は前へ動くが、
`complex-x64` で cursor から**平均 4,622 位置・最大 13,292 位置**遅れる（`commit` は親の cursor に子の終端を入れるので、
**親が動くのは直下の子が commit したときだけ**。`Program := Statement*` 型の文法では、これは「いま読んでいる文の始まり」ではなく
「最外規則の直下の子の始まり」である）。つまりこの frontier で切っても表の 22〜64% が残り、依然として O(入力) のままである。

一方、次節のとおり **hit が実際に戻る距離は入力長によらず 78 位置**だった。安全側の frontier は正しいが、
必要な窓より **60 倍**ゆるい。

### 進むのは cursor そのものなので、窓で持つ

そこで entry の保持を「cursor の到達最大点から **W 位置**ぶん後ろまで」に限る。W は文法依存の見積もりなので、
**証明ではなく計測＋実行時ガード**で扱う。

- **evict は結果を変えない。** `PackratMemoTable.lookup` は純粋なキャッシュ照会で、miss は普通に再 parse するだけである。
  `replayMemoTransactionEvents` / `replayFailureDiagnostic` は「hit を再 parse と区別できなくする」ために存在するので、
  entry を捨てれば本物の transaction event と診断が記録される。表の外から entry を参照するものは無い。
  遅延 `registerTransactionalState` も同じ理由で影響を受けない。
- **危ないのは hit 率だけ。** そこで watermark より前への照会（under-run）を数え、
  1 件でも起きたら窓を「観測した look-back の 2 倍」以上へ広げる。窓は増える一方なので、
  窓より遠くまで backtrack する文法は**等比級数ぶんの再 parse を払ったあとは従来と同じ挙動**に戻る。
  packrat の線形保証を黙って失うことはない。
- **失敗 entry と成功 entry を区別しない。** key の matched 位置と state version も見ない。比較するのは consumed 開始位置だけで、
  照会側の consumed 位置は cursor そのものである。
- **入力が窓より短ければ 1 件も捨てない**（`complex` 326 byte、tinyexpression の実用式はすべてここに入る）。

実装は `PackratMemoTable`:

- entry 自身に `indexedParser` / `indexedKey` / `nextAtPosition` を持たせ、**開始位置ごとの単方向リスト**に繋ぐ
  （索引ノードを別に確保しないので put 側の割当は増えない）。
- `ParseContext.checkpointTransactionalState`（= 全 transaction begin）から `observeCursor` を呼び、
  `highWater - W` が watermark より 256 以上進んだら、その範囲の位置を 1 回ずつ走査して外す。
  **位置は parse 全体で 1 回ずつ、entry も 1 回ずつしか触らない**ので O(入力長 + entry 数)。
  commit ごとに表を全走査したら超線形が戻ってくる。
- evict の hook を `begin` に置くのは、**直前に終わった rule の `put` がまだ残っている唯一の場所を避ける**ため
  （`commitSuccess` は commit → endParse → put の順で、commit の finally で evict すると自分の entry を先に捨ててしまう）。

既定の窓は **1,024 位置**（`-Dunlaxer.memo.window`）、evict 自体は `-Dunlaxer.memo.evictBelowFrontier=false` で止められる。

### 必要な窓を測る（= 実際の look-back）

計測用カウンタ `maxHitLookback`（hit した照会が cursor 到達最大点からどれだけ戻った位置か）を入れて 1 parse ずつ数えた。

| fixture | byte | 最大 look-back |
|---|---:|---:|
| `complex` | 326 | 74 |
| `complex-x4` | 1,293 | 76 |
| `complex-x16` | 5,179 | 78 |
| `complex-x64` | 20,923 | **78** |
| `large-match` | 1,380 | 7 |
| `flat-arithmetic` | 1,426 | 0 |

**入力を 64 倍にしても look-back は 78 位置で頭打ちになる。** これが「memo の live 集合が O(入力) である必要はない」ことの実測根拠で、
既定の窓 1,024 はその **13 倍**の余裕を取っている。

### memo entry 数（evict 前 / 後、1 parse）

| fixture | before: 最大 entry | after: 最大 entry | after: 最終 entry | evict 件数 | 削減 |
|---|---:|---:|---:|---:|---:|
| `complex` | 1,695 | 1,695 | 1,695 | 0 | 0%（窓より短い） |
| `complex-x4` | 6,474 | 6,369 | 6,132 | 342 | -1.6% |
| `complex-x16` | 25,590 | 8,439 | 4,343 | 21,247 | **-67%** |
| `complex-x64` | **102,054** | **8,424** | 3,989 | 98,065 | **-91.7%** |

窓を変えたときの `complex-x64` の最大 entry: W=256 → 3,334 / W=512 → 5,068 / W=1,024 → 8,424 / W=4,096 → 27,848。
**W を 1,024 より小さくしても live 集合の残りは CST 本体が支配する**（ケース34 の内訳）ので、安全余裕のほうを取った。

### hit 数の同一性（受け入れ条件）

evict あり / なしを同じ build で切り替え、`root`（DETAILED）と `root-deferred`（DETAILED_ON_FAILURE）の両方、
root parser が受理する 9 fixture すべてで **失敗 hit / 成功 hit が 1 件も違わない**。
`complex-x64` は両方とも 123,508 / 194,944。**under-run 0 件、watermark 通過後の put（dead on arrival）0 件。**

### 診断の同値性

失敗入力 18 件 × `DETAILED` / `DETAILED_ON_FAILURE` × memo `OFF` / `SAFE_FAILURES` の 72 通りで
`ParseFailureDiagnostics` の全 getter をテキスト化し、base / cand で **byte 一致**。
さらに窓を 8 位置まで縮めて（= この短い入力でも必ず evict が起きる条件で）同じダンプを取り、やはり **byte 一致**だった。

### live 集合（`jcmd GC.class_histogram`、`complex-x64` の parse 中と parse 直後）

| 標本 | evict なし: entry / live | evict あり: entry / live |
|---|---:|---:|
| 序盤 | 80 / 12.19 MB | 131 / 10.80 MB |
| 中盤 | 15,595 / 23.06 MB | 8,235 / 18.31 MB |
| 中盤 | 53,854 / 43.26 MB | 7,789 / 27.47 MB |
| 終盤 | 94,043 / 61.80 MB | 6,591 / 35.90 MB |
| **parse 直後** | **102,054 / 61.76 MB**（1,997,580 個） | **3,989 / 37.87 MB**（1,365,500 個） |

**entry 数は evict ありでは増え続けずに窓のところで頭打ちになる**（8,235 → 7,789 → 6,591 → 3,989）。
一方 live 全体は CST が伸びるぶん増え続けるが、終端で **61.76 → 37.87 MB（-38.7%）**、オブジェクト数 -31.6%。

parse 直後のヒストグラム上位の差（`complex-x64`）:

| クラス | evict なし | evict あり |
|---|---:|---:|
| `ParseContext$FailureDiagnostic` | 102,054（6.53 MB） | 上位から消滅 |
| `PackratMemoTable$Entry` | 102,054（4.90 MB） | 3,989（0.19 MB） |
| `PackratMemoTable$PositionKey` | 102,054（4.08 MB） | 3,989（0.16 MB） |
| `Token` | 94,459（5.29 MB） | 81,676（4.57 MB） |
| `TokenList` | 200,070（3.20 MB） | 164,044（2.62 MB） |
| 位置索引 `Entry[]` | — | 1 個（0.13 MB） |

`Token` / `TokenList` も減っているのは、成功 memo が抱えていた deep copy ごと落ちるからである。
**evict のために足した索引は `Entry[]` 1 本（131 KB）だけ**で、entry 側は既存フィールド 3 本の追加で済んでいる。

### スケーリング（`legacy-bench.sh root-deferred`、`SAFE_FAILURES`、`-Xss512m -Xms2g -Xmx2g`、`taskset -c 24` → Serial GC）

ホストが他エージェントの Rust ビルドと共有で load average が 8〜47 の間を動いたため、
**fixture ごとに base / cand を交互に、4 ラウンド（各 3 run）**回し、12 run の最小値と中央値の両方を出す。

| fixture | base 最小 | cand 最小 | base 中央値 | cand 中央値 | base 割当 | cand 割当 | base GC 比率 | cand GC 比率 | base µs/byte | cand µs/byte |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `complex` | 5.920 ms | 5.998 ms | 7.739 | 7.204 | 12.79 MB | 12.77 MB | 1.03% | 0.90% | 18.16 | 18.40 |
| `complex-x4` | 23.666 ms | 24.678 ms | 31.044 | 30.234 | 48.91 MB | 49.25 MB | 2.48% | 2.50% | 18.30 | 19.09 |
| `complex-x16` | 104.550 ms | 114.178 ms | 140.365 | 137.441 | 194.79 MB | 193.79 MB | 9.55% | **7.87%** | 20.19 | 22.05 |
| `complex-x64` | 558.187 ms | **498.397 ms** | 673.359 | 656.229 | 772.26 MB | 760.42 MB | 28.99% | **20.71%** | 26.68 | **23.82** |

- **`complex-x64` は最小値で -10.7%、GC 比率 28.99% → 20.71%。** GC ログでは pause 合計が 34.1→23.6 s / 42.3→28.1 s
  （ラウンドごと、-31〜-33%）、1 回あたりの pause が 137 ms → 76 ms（**-45%**、= live 集合の効果）。
- **`complex` は窓（1,024）より短いので evict が 1 件も起きない。** 差分は `observeCursor` の 1 呼び出し
  （transaction begin あたり、`complex` で 13,986 回）だけで、最小値 +1.3%。
- `complex-x4` / `complex-x16` の最小値が悪化しているのは負荷の当たり外れで、同じラウンド内で比べると逆転する
  （x16 ラウンド2: base 104.5/127.7/124.0、cand 127.4/115.4/114.2）。中央値と GC 比率は両方とも cand が良い。
- **µs/byte 倍率（`complex` → `complex-x64`）: ×1.469 → ×1.295。受け入れ条件の ×1.2 は未達。**
- GC pause を引いた µs/byte 倍率は **base ×1.054 / cand ×1.036** で、ケース32〜34 と同じく**パーサ本体は線形**。

既定モード（`legacy-bench.sh root`、`Diagnostics.DETAILED`）の退行確認。**このパスだけは load average 33〜59 の窓に当たり、
2 ラウンド（各 3 run）しか取れていない**ので、中央値は信用できない。GC 比率と割当は負荷に依らないので、そちらを見る。

| fixture | base 最小 | cand 最小 | base 中央値 | cand 中央値 | base 割当 | cand 割当 | base GC 比率 | cand GC 比率 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| `complex` | 10.590 ms | 11.926 ms | 14.704 | 14.025 | 16.46 MB | 16.48 MB | 0.95% | 1.89% |
| `complex-x4` | 49.665 ms | 46.847 ms | 60.615 | 53.121 | 62.92 MB | 63.27 MB | 3.07% | 2.91% |
| `complex-x16` | 217.333 ms | 234.533 ms | 344.379 | 347.621 | 248.58 MB | 248.97 MB | 12.70% | **8.98%** |
| `complex-x64` | 1366.972 ms | **927.720 ms** | 1513.526 | 1712.868 | 997.54 MB | 990.95 MB | 35.74% | **16.90%** |

**割当は全サイズで ±0.6% 以内**（施策は割当を動かさない）、**GC 比率は大きい入力で確実に下がる**（x64 で 35.74% → 16.90%）。
`complex-x64` の倍率はこのパスだと base ×2.011 / cand ×1.212 になるが、base の ×2.011 は round 3 の ×1.570 より悪く、
**負荷の混入ぶんである**。`DETAILED` の数字は退行が無いことの確認にとどめ、受け入れ条件の判定には
静穏側の `root-deferred`（4 ラウンド）を使う。

### #276 の ×1.2 について: **未達**。ただし残差の分解は round 3 から変わった

| round | `complex` µs/byte | `complex-x64` µs/byte | 倍率 | x64 の GC 比率 |
|---|---:|---:|---:|---:|
| round 3 base（`DETAILED_ON_FAILURE`） | 24.42 | 31.78 | ×1.301 | 26.67% |
| round 3 cand | 18.96 | 27.18 | ×1.434 | 27.90% |
| **round 4 base（= round 3 cand）** | 18.16 | 26.68 | ×1.469 | 28.99% |
| **round 4 cand** | **18.40** | **23.82** | **×1.295** | **20.71%** |

round 3 は CPU を削ったので「GC がほぼ無い小入力ほど効いて倍率が悪化した」。
**round 4 は逆に、GC 律速の大入力だけに効く施策なので倍率が改善する。** 残っている ×1.295 の内訳は

- `complex-x64` の GC 比率 **20.71%**（`complex` は 0.90%）。GC pause を引くと ×1.036。
- **つまり残りはまだ全部 collector である。** live 集合のうち memo が抱えていた 24 MB は消えたが、
  **残り 37.9 MB は CST 本体（`Token` 81,676 + `StringSource` 81,229 + `TokenList` 164,044）で、これは構文木そのもの**である。
  1 parse が入力長ぶんの木を作る以上 O(N) は避けられず、これを削るには
  「sub-source を root の view にする」（ケース32 の 1 番、`StringSource` 81,229 個分）か、
  `TokenList` の裏の空 `ArrayList` 2 本の遅延化（ケース33 / 34 の残件）しかない。
- 割当（第 1 因子）は -1.5% しか動いていない。**今回の施策は純粋に第 2 因子（live 集合）だけを動かした**もので、
  ケース32 の式のどちらを削ったかがそのまま結果に出ている。

### 検証

- `unlaxer-common` 699 件（新規 3 件を含む）/ `unlaxer-dsl` 1,012 件（skip 23）緑。
- isolated maven repo で tinyexpression 全テスト **769 件（skip 10）緑**（`c70416e1` を候補 jar で再ビルド）。
  深い入れ子式の `P4PackratFraudFormulaTest` もここに含まれる（入力が窓より短いので evict が 1 件も起きない）。
- hit 数の同一性: 9 fixture × `root` / `root-deferred` × evict on/off で失敗 hit・成功 hit とも完全一致、under-run 0。
- 診断の同値性: 72 通りのダンプが base / cand で byte 一致。窓 8 位置（強制 evict）でも一致。
- 新規テスト: `MemoEvictionTest`（hit 数が evict の有無で変わらず entry 数は頭打ちになる /
  watermark より前への照会が窓を広げる / 窓より短い入力では 1 件も捨てない）。
- 計測条件: `ubnfc/examples/p4-java/scripts/legacy-bench.sh`、`Memoization.SAFE_FAILURES`、
  `-Xss512m -Xms2g -Xmx2g`、`taskset -c 24`（1 CPU → Serial GC）、jar-first classpath、
  build ごとの isolated maven repo で tinyexpression `c70416e1` を再ビルド、fixture ごとに base/cand を交互。
  **ホストは共有で load average 8〜59。`root-deferred` は 4 ラウンド、`root` は 2 ラウンド。**

### Java / Rust の対称性

`rust/unlaxer-runtime` の `FailureMemoBuckets` も**同じく入力長に比例して増え続ける**。
`insert` は `remaining` を必要なだけ `resize_with` で伸ばすだけで、どのバケットも parse 終了まで解放されない。
つまり live 集合が O(入力) なのは Java 固有の設計ミスではなく、両実装に共通の性質である。

違うのは**その代償**のほうである。Rust には GC が無いので「live 集合 × GC 回数」の積が生じず、
効くのはピーク常駐量とハッシュ表の局所性だけである（同じ比較で tinyexpression-rs は ×1.17 で、
Java の ×1.4〜1.6 のような超線形は観測されていない）。値も軽く、`Diagnostics::DetailedOnFailure` では
`FailureDiagnostic::default()` しか入らず、成功 memo（CST の deep copy）に相当するものが無い。
バケットは既に scalar 位置 / 256 で整列しているので、同じ窓方式は `remaining[..k]` を差し替えるだけで入る。

**緊急性が違うので本 PR では実装せず、同じ梃子で Rust 側の issue を立てた**（受け入れ条件は
「窓より前のバケットを落とす」「watermark より前への照会で窓を広げる」「hit 数が evict の有無で変わらない」）。

### 教材としての要点

- **「証明できる不変条件」と「役に立つ不変条件」は別である。** committed frontier は厳密に正しいのに、
  最下段の transaction が位置 0 に居座るせいで**一度も進まない**。
  不変条件を書いたら、**それが実際にどう動くかを 1 回測る**（今回は 0 のままだった）。
  そこで諦めずに、「戻ってくる距離」を直接測って窓にした。
- **キャッシュの正しさと、キャッシュの効き目は別の証明でよい。** memo 表は純粋なキャッシュで、
  捨てても結果は変わらない（miss は再 parse するだけ）。だから**厳密な証明が要るのは「結果」ではなく「hit 率」だけ**で、
  hit 率は実行時カウンタで測れる。今回は「watermark より前への照会を数え、起きたら窓を倍以上に広げる」
  という自己修復を入れたので、窓の見積もりを外しても**等比級数ぶんの損で従来の挙動へ戻る**。
  最悪ケースが「遅くなる」ではなく「元に戻る」で抑えられるなら、ヒューリスティックは採用してよい。
- **「どこまで戻るか」は入力長に比例しない。** 入力を 64 倍にしても最大 look-back は 78 位置のままだった。
  packrat の表を入力長ぶん持つのは、この距離を測っていないからである。
  **表のサイズを決めるのは入力長ではなく文法の backtrack 距離**で、後者は測れる。
- **evict の hook を置く場所は「自分の entry がまだ書かれていない所」。** `commitSuccess` は
  commit → endParse → put の順なので、commit の finally で evict すると直前の rule の entry を自分で捨ててしまう。
  transaction の `begin` はその隙間が無い唯一の点だった。
- **索引ノードを別に作らない。** 位置ごとの連結リストは entry 自身のフィールド 3 本で足りる。
  「evict のために索引を足したら割当が増えた」では本末転倒になる。

---

## ケース36: CST そのものを小さくする — view・共有空リスト・値オブジェクトの非ボックス化・位置索引の配列化（Java、#276 round 5）

### 出発点

ケース32〜35 で機構は確定している。**超線形 = GC 回数（∝ 1 parse の割当）× 1 回のコスト（∝ live 集合）**で、
GC pause を引いた µs/byte はどの build でも ×0.96〜×1.09、つまり**パーサ本体は線形**。
round 4 で memo 表の live を入力長から切り離した結果、`complex-x64` の parse 直後に残る live 37.9 MB は
**ほぼ全部が CST 本体**（`Token` 81,676 / `StringSource` 81,229 / `TokenList` 164,044）になった。
（round 4 の数は `jcmd GC.class_histogram`、つまり JVM 全体の live である。本ケースの数は「root token から
到達できるものだけ」なのでわずかに小さい: `Token` 80,892 / `StringSource` 80,893 / `TokenList` 161,784。）
round 5 は、その CST を数えてから削る。

### まず CST を数える（reflection で「root token から到達できるもの」だけを歩く）

`jcmd GC.class_histogram` は JVM 全体の live を返すので、CST の内訳には使えない。
root token から参照グラフを歩き（文法 = `org.unlaxer.parser.*` は共有なので境界にする）、
クラスごとに個数と shallow size（12 byte header、compressed oops）を積む計測を書いた。`complex-x64`（20,923 code point）:

| クラス | 個数 | live | 何のためか |
|---|---:|---:|---|
| `StringSource` | 80,893 | 4,530,008 | token 1 個につき 1 個 |
| `Token` | 80,892 | 4,529,952 | |
| `java.util.ArrayList` | 161,785 | 3,882,840 | token 1 個につき 2 本（original / filtered） |
| `[I` | 53,537 | 3,072,568 | **source ごとの code point 配列のコピー** |
| `TokenList` | 161,784 | 2,588,544 | 上の 2 本の wrapper |
| `[Ljava.lang.Object;` | 73,675 | 2,206,616 | 空でない ArrayList の裏 |
| `java.util.HashMap$Node` | 62,771 | 2,008,672 | **root の位置索引**（3 表 × code point 数） |
| `CodePointOffset` | 124,952 | 1,999,232 | source ごとの offsetFromParent / offsetFromRoot |
| `java.lang.String` | 81,092 | 1,946,208 | **source ごとの文字列のコピー** |
| `Range` | 80,892 | 1,941,408 | `Token.tokenRange`（deprecated） |
| `[B` | 53,805 | 1,681,344 | 上の String の中身 |
| `java.util.Optional` | 90,459 | 1,447,344 | `Token.tokenString`（deprecated）と `Token.parent` |
| `Depth` | 80,893 | 1,294,288 | source ごとの深さ |
| `[Ljava.util.HashMap$Node;` | 4 | 393,344 | 位置索引の表 |
| `CodePointIndex` / `CodePointIndexInLine` / `StringIndex` | 21,823 / 20,924 / 20,923 | 1,018,720 | 位置索引の key / value |
| **合計** | **1,256,693** | **34,714,800** | **429 byte / token** |

重複はここで 4 つ見つかる。

1. **すべての sub-source が自分の `String` と `int[]` を持つ。** 木に生き残った 53,537 本の配列が
   **515,595 code point** を抱えている。入力は 20,923 code point なので **24.6 倍**である。
   `TokenList.toSource` が commit のたびに子の文字列を `StringBuilder` で連結し、
   `createSubSource` がそれを decode し直すのが原因。
2. **葉 token の子リスト 2 本が両方とも空のまま。** 葉 44,056 個 × 2 = **88,112 本の空 `ArrayList`**。
3. **source ごとに深さと 2 つの offset を box している**（3 オブジェクト / source）。
4. **root の `PositionResolverImpl` が code point 1 個につき HashMap entry 3 個と box 3 個を作る。**
   入力長に比例する live が CST とは別に 3.4 MB ある。

### 理論下限との比較

同じ木を「1 本の共有 code point 配列 + N 個の node（開始・終了・種別・parser・親・子リスト）」で持つとどうなるか:

- 共有 `int[]`: 20,923 × 4 + 16 = **83,708 byte**
- node 80,892 個 × (header 12 + start 4 + end 4 + kind 4 + parser 4 + parent 4 + children 4 → 40) = **3,235,680 byte**
- 子配列 36,836 個（葉でない node）× (16 + 4 × 平均 3.4) ≈ **1,178,752 byte**
- 合計 **約 4.50 MB**

実測 34.71 MB は下限の **7.7 倍**だった。`rust/unlaxer-runtime` の `Tree` はまさにこの形
（`source: String` 1 本 + `nodes: Vec<Node>` の arena、`Node.span: Span { start, end }`、`text(span)` は `&str` の slice）なので、
下限の見積もりは机上の値ではなく**もう一方の実装が実際に取っている形**である。

### 施策（クラスが重ならないので live の帰属は一意に決まる）

**施策A: sub-source を root の code point 配列への view にする。**
`StringSource` に `codePointOffsetInArray` / `codePointLength` を持たせ、`subSource` / `peek` は
親の配列をそのまま共有する。`sourceString` は `sourceAsString()` が初めて呼ばれたときに作る
（final フィールドだけから導出するので競合しても同じ String を作り直すだけ）。
`TokenList.toSource` は**子が root の連続 slice かどうかを構造的に確かめてから** view を作り、
そうでなければ従来の連結経路に落ちる（trivia の扱いや書き換えられた source で連続でなくなり得るため）。
実測では `complex-x64` の 53,536 本すべてが連続 slice だった。
`subSource(start, end)` の範囲外は、従来 `new String` が即座に投げていたので、view の生成時に同じ位置で投げる。

**施策B: 空の子リストは 1 つの不変リストを共有する。** `TokenList` の裏を `List.of()` にし、
最初の書き込みで `ArrayList` に差し替える。読み取り 20 メソッドは不変リストでも同じ結果を返す。
`set(int,…)` / `remove(int)` / `addAll(int,…)` は空の `ArrayList` なら `IndexOutOfBoundsException` を投げるので
その振る舞いを明示的に維持し、`listIterator()` / `subList()` は可変 view を返す契約なので先に差し替える。

**施策C: `Depth` / `CodePointOffset` を int フィールドにする。** アクセサは今までどおり値オブジェクトを返すが、
**聞いた人だけが 1 個払う**。`StringSource` は 56 → 64 byte になるが（施策Aの int 2 本ぶん）、
source ごとに 3 オブジェクト消える。

**施策D: 位置索引を `int[]` にする。** `PositionResolverImpl` の 3 つの `HashMap` を
`int[]`（欠損は -1）に置き換える。返り値は必要になったときだけ box する。

### live の内訳（`complex-x64`、施策ごとにクラスが重ならないので差分がそのまま帰属になる）

| 施策 | 消えた / 増えたもの | live 差 |
|---|---|---:|
| A: view | `[I` 53,537本 → 1本 (−2,988,856)、`String` 81,092 → 53,735 (−656,568)、`StringSource` +8 byte/個 (+647,144) | **−2,998,280** |
| B: 空リスト共有 | `ArrayList` 161,785 → 73,673 | **−2,114,704** |
| C: int フィールド | `CodePointOffset` 124,952 → 0、`Depth` 80,893 → 0 | **−3,293,520** |
| D: 位置索引の配列化 | `HashMap$Node` 62,771 → 0、`Node[]`、`CodePointIndex`/`InLine`/`StringIndex` → 索引 `int[]` 3 本 (+251,144) | **−3,155,152** |
| **合計** | | **−11,561,656（−33.3%）** |

| fixture | base live / 個数 | cand live / 個数 | 差 |
|---|---:|---:|---:|
| `complex` (326 B) | 627,296 / 22,009 | 445,720 / 13,949 | −28.9% / −36.6% |
| `complex-x16` (5,179 B) | 8,711,928 / 315,077 | 5,839,040 / 190,383 | −33.0% / −39.6% |
| `complex-x64` (20,923 B) | 34,714,800 / 1,256,693 | **23,153,144 / 756,303** | **−33.3% / −39.8%** |

`complex-x64` は下限 4.50 MB の 7.7 倍 → **5.1 倍**になった。

### 採らなかったもの: `Token` の deprecated フィールド

round 1 で挙がっていた `Token.tokenString`（`Optional<String>`）と `Token.tokenRange`（`Range`）は
**public final フィールド**なので、遅延化するには型か可視性を変えるしかない。残っている live のうち

- `Range` 1,941,408 + `[B` 1,681,344 + `String` 1,289,640 + `Optional` の `tokenString` ぶん ≈ **5.6 MB（残り 23.2 MB の 24%）**

がこの 2 つに縛られている。とくに `tokenString` は **view にした source の `String` を毎 token 実体化させる**ので、
施策Aが `[I` を消せても `[B` / `String` は消せない。これは 3.x の公開 API の削除になるため本 PR には入れない
（受け入れ条件の「観測可能な振る舞いを変えない」に反する）。**4.x の候補として数値ごと記録する。**

`originalChildren` と `filteredChildren` の内容が一致する token が 36,836 個中 **29,919 個**あり、
リストを共有すれば更に約 2.1 MB 減る。ただし `filteredChildren` は public で可変
（`anchorCollectedEmptySource` が `clear()` / `addAll()` する）なので、共有すると一方の変更が他方に見える。
copy-on-write を被せる案はあるが、**「観測可能な振る舞いを変えない」を保証する費用が利得に見合わない**ので採らない。

### 等価性（受け入れ条件）

`root` parser を 16 fixture × {`DETAILED`, `DETAILED_ON_FAILURE`} × {memo `OFF`, `SAFE_FAILURES`} と
不正入力 19 件 × 同じ 4 通り、**計 132 通り**走らせ、次を 1 つのテキストに落として base / cand で比較した:

- 受理した fixture の **CST 全体**（token ごとに tokenKind / parser / sourceKind / offsetFromRoot / offsetFromParent /
  depth / code point 長 / char 長 / `tokenRange` / `tokenString` / `cursorRange().toRange()` / **source の文字列そのもの** /
  子の数）
- memo カウンタ（failure hit / success hit / 最大 entry 数 / evict 数 / under-run / dead-on-arrival / 最大 look-back）
- 失敗時の `ParseFailureDiagnostics` 全 getter（`ExpectedHintCandidate` は `toString()` が無く identity hash しか出ないので、
  フィールドを展開して比較する）

**109,940 行が byte 一致（md5 `cf4d3ea9…` で一致）。** memo hit 数も 132 通りすべてで一致している。

### スケーリング（`legacy-bench.sh root-deferred`、`SAFE_FAILURES`、`-Xss512m -Xms2g -Xmx2g`、`taskset -c 24` → Serial GC）

ホストは他エージェントと共有で load average 8〜30（x64 のラウンドで 1 run が 466 ms から 2,013 ms まで振れる）。
そこで **fixture ごとに base / cand を交互に 4 ラウンド（各 3 run）= 各 12 run** 取り、**最小値**を主の指標にする。
割当（`getThreadAllocatedBytes`）と GC 比率（GC ログの pause 合計 / プロセス wall）は負荷に依らない。

| fixture | base 最小 | cand 最小 | Δ | base 割当 | cand 割当 | Δ割当 | base GC 比率 | cand GC 比率 | base µs/byte | cand µs/byte |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `complex` (326 B) | 6.673 ms | **5.794 ms** | **-13.2%** | 13.46 MB | 11.16 MB | **-17.1%** | 0.77% | 0.61% | 20.53 | 17.83 |
| `complex-x4` (1,293 B) | 23.667 ms | **21.505 ms** | **-9.1%** | 51.63 MB | 43.25 MB | **-16.2%** | 2.25% | 1.66% | 18.30 | 16.63 |
| `complex-x16` (5,179 B) | 119.377 ms | **97.077 ms** | **-18.7%** | 201.93 MB | 168.43 MB | **-16.6%** | 7.97% | 5.35% | 23.05 | 18.74 |
| `complex-x64` (20,923 B) | 550.735 ms | **466.352 ms** | **-15.3%** | 792.94 MB | 659.03 MB | **-16.9%** | 19.84% | **13.96%** | 26.32 | **22.29** |

- **絶対値は全サイズで改善**（最小値 -9〜-19%）、**割当は全サイズで -16〜-17%**、
  **x64 の GC 比率 19.84% → 13.96%**（GC pause 合計 203.8 s → 136.7 s、-32.9%）。
- **µs/byte 倍率（`complex` → `complex-x64`）: ×1.282 → ×1.250。受け入れ条件の ×1.2 は未達。**
- **GC pause を引いた µs/byte 倍率は base ×1.036 / cand ×1.082** — ケース32〜35 と同じく**パーサ本体は線形**。
  倍率が 1.25 までしか下がらないのは、round 3 と同じ理由である:
  **施策は割当（-17%）と live（-33%）の両方を削ったので、GC がほとんど無い `complex`（GC 比率 0.61%）でも
  CPU ぶんだけ速くなる**。分母が小さくなれば比は下がらない。
### 割当の施策ごとの寄与（`complex-x64`、warmup 20 / 5 parse、cold。割当は負荷に依らない）

live と違って割当はクラスで切り分けられないので、**候補から施策を 1 つずつ戻した build** を作って測った。

| build | 割当 / parse | 完全候補との差 |
|---|---:|---:|
| base（施策なし） | 810,462,752 | +151,424,328 |
| **候補（4 施策）** | **659,038,424** | — |
| 候補 − A（view をやめる） | 682,561,736 | +23,523,312 |
| 候補 − B（空リスト共有をやめる） | 721,248,728 | +62,210,304 |
| 候補 − C（値オブジェクトに戻す） | 724,151,408 | +65,112,984 |
| 候補 − D（位置索引を HashMap に戻す） | 680,161,064 | +21,122,640 |

限界寄与の和（172.0 MB）が全体差（151.4 MB）より大きいのは、`− C` の build が
**int フィールドを残したまま値オブジェクトを足した**ため `StringSource` が 64 → 80 byte になり、
約 16 MB ぶん過大に出るからである（施策Cの真の寄与は約 49 MB）。
**割当を最も削るのは B と C**、**live を最も削るのは C と D**で、同じ施策でも効く因子が違う。
### 参考条件（採否の根拠ではない。collector が制約かどうかを切り分けるため）

| 条件 | `complex` 最小 | `complex-x64` 最小 | x64 の GC 比率 | µs/byte 倍率 |
|---|---:|---:|---:|---:|
| base、Serial、2 GB（この issue の条件） | 6.673 ms | 550.735 ms | 19.84% | **×1.282** |
| **候補、Serial、2 GB** | **5.794 ms** | **466.352 ms** | **13.96%** | **×1.250** |
| base、Serial、**16 GB** | 6.727 ms | 477.570 ms | 4.19% | **×1.106** |
| **候補、Serial、16 GB** | 6.128 ms | **412.930 ms** | **2.83%** | **×1.050** |
| base、**ParallelGC**、2 GB | 5.946 ms | 806.275 ms | 14.23% | ×2.113 |
| 候補、**ParallelGC**、2 GB | 5.496 ms | 854.886 ms | 11.27% | ×2.424 |

- **ヒープを 16 GB にすると候補は ×1.05、base でも ×1.106** で、どちらも受け入れ条件を満たす。
  x64 の GC 比率が 2.83% まで落ちるからで、**×1.25 の残りは collector そのものである**。
- **`taskset -c 24`（1 CPU 固定）で ParallelGC を選ぶと逆に遅くなる**（x64 で 806 / 855 ms）。
  GC スレッドが 1 コアを奪い合うためで、**1 CPU に固定したまま collector だけ替えるのは無意味**。
  この issue の計測条件（1 CPU 固定 → Serial GC が選ばれる）は「live 集合 × GC 回数」を最大化する条件である。
### #276 の受け入れ条件（×1.2）について: **未達。そして、この条件のままでは今後も達成できない**

| round | `complex` µs/byte | `complex-x64` µs/byte | 倍率 | x64 の GC 比率 | GC を引いた倍率 |
|---|---:|---:|---:|---:|---:|
| round 3 cand | 18.96 | 27.18 | ×1.434 | 27.90% | ×1.04 |
| round 4 cand | 18.40 | 23.82 | ×1.295 | 20.71% | ×1.036 |
| **round 5 base（= round 4 cand）** | 20.53 | 26.32 | ×1.282 | 19.84% | ×1.036 |
| **round 5 cand** | **17.83** | **22.29** | **×1.250** | **13.96%** | ×1.082 |

（round 5 の絶対値が round 4 より大きいのは、同じ 2 GB / Serial / 1 CPU の条件でホストの load average が高かったため。
倍率と GC 比率は base / cand を交互に取った同一セッションの値なので比較できる。）

5 round で確定したことは次のとおりである。

1. **パーサ本体（rule 評価・memo・診断・Token 構築）は入力長に対して線形である。**
   GC pause を引いた µs/byte 倍率は round 2 以降どの build でも ×1.03〜×1.09（今回の候補は ×1.082）。
2. **残る超線形は collector である。** 2 GB 固定ヒープでは「GC 回数（∝ 1 parse の割当）×
   1 回のコスト（∝ live 集合）」の積が O(N²) を生む。round 2 は第 1 因子を −27%、round 4 は第 2 因子（memo）を −39%、
   round 5 は第 2 因子（CST）を −33% と第 1 因子を −17% 削った。
3. **これ以上 live を削るには公開 API を壊すしかない。** 残り 23.15 MB のうち
   **約 5.6 MB（24%）は `Token.tokenString` / `Token.tokenRange`**（deprecated な public final フィールド）で、
   残りは `Token` + `StringSource` + 子リスト 2 本という**構文木そのもの**である。理論下限 4.50 MB に対して 5.1 倍まで来た。
   parse が入力長ぶんの木を作って**返す**以上、live が O(N) なのは API の要求であって実装の無駄ではない。
4. **施策が両因子を削ると、倍率はかえって下がりにくい。** GC がほぼ無い小入力（`complex` は GC 比率 0.61%）も
   CPU ぶん速くなるので、分母が一緒に下がる。round 3 で観測したのと同じ現象である。

したがって **「既定 2 GB ヒープ・1 CPU 固定（= Serial GC）で ×1.2」は、パーサの実装ではなく実行条件が決めている**。
同じ binary が 16 GB では **×1.05**（base ですら ×1.106）である。#276 はここで **decision として close** し、
残りは「大きい入力のための実行条件」（下の付録）と、**構造的な代替である ubnfc 生成器**へ引き継ぐ。

### Java / Rust の対称性

**`rust/unlaxer-runtime` の CST は、この PR が Java を近づけた形をもともと取っている。**

- `Tree { source: String, nodes: Vec<Node>, root: usize, byte_offsets: Vec<usize>, scopes }`、
  `Node { rule: usize, span: Span { start, end }, children: Vec<usize>, captures: Vec<Capture> }`。
  `Tree::text(span)` は所有している 1 本の `String` への `&str` slice なので、
  **施策Aと施策Cは Rust では設計上すでに成立している**（node は span の 2 つの整数しか持たず、部分文字列を所有しない）。
- `Vec::new()` は確保しないので、**施策Bに相当する無駄が無い**（葉の `children` は空 `Vec` で 0 byte の heap）。
- 位置索引は `byte_offsets: Vec<usize>` で、**施策Dと同じ配列表現**である。さらに `code_point_offsets` は
  ASCII のとき空のまま（Java 側も BMP なら恒等写像だが、今回は素直に `int[]` を 3 本持つ形にした）。

よって round 5 は **Rust 側に対応する変更を必要としない**（機能差ではなく、Java 側が Rust の表現に追いついた回である）。
入力長に比例して増え続ける `FailureMemoBuckets` は別問題で、#290 に切ってある。

### 検証

- `unlaxer-common` 699 件 / `unlaxer-dsl` 1,012 件（skip 23）緑。
- isolated maven repo で tinyexpression `c70416e1` を候補 jar で再ビルドし、全テスト **769 件（skip 10）緑**。
- 132 通りの等価性ダンプが byte 一致（上記）。
- 計測条件: `ubnfc/examples/p4-java/scripts/legacy-bench.sh`、`Memoization.SAFE_FAILURES`、
  `-Xss512m -Xms2g -Xmx2g`、`taskset -c 24`（1 CPU → Serial GC）、jar-first classpath、
  build ごとの isolated maven repo、fixture ごとに base/cand を交互に 4 ラウンド（各 3 run）。
  ホストは共有で load average 8〜30（x64 のラウンドで 1 run が 466 ms から 2,013 ms まで振れた）。

### 教材としての要点

- **「live を減らす」は「割当を減らす」とは別の作業で、別の数え方が要る。** `jcmd GC.class_histogram` は
  JVM 全体しか返さないので、**root から参照グラフを歩いてクラスごとに積む 100 行の計測**を先に書いた。
  そこで初めて「入力 20,923 code point に対して木が 515,595 code point を抱えている」が見えた。
- **理論下限を先に計算する。** 「node 1 個 = 開始・終了・種別・親・子」で 4.5 MB、実測 34.7 MB、つまり 7.7 倍。
  この比を出しておくと、施策を 4 つ足して 5.1 倍まで来たところで「次は何が縛っているか」（= deprecated な public フィールド）が
  数字で言える。
- **同じ問題を解いた別実装があるなら、それが下限の実在証明になる。** Rust 側の `Tree` は
  span の arena + 1 本のソースで、Java 側の 4 施策はどれも「Rust ではすでにそうなっている」ことの後追いだった。
  対称性の節は「両方に入れたか」の確認だけでなく、**設計の目標値をどこから取るか**にも使える。
- **view にするときに危ないのは「連続でない連結」。** `TokenList.toSource` は子の文字列を連結するので、
  trivia を挟めば root の連続 slice にならない可能性がある。**連続かどうかを構造的に確かめて、
  駄目なら従来経路に落ちる**ようにすれば、正しさは文法に依存しなくなる（実測では 100% 連続だった）。
- **不変な空コンテナの共有は、可変メソッドの数だけ落とし穴がある。** 空の `ArrayList` と `List.of()` は
  読み取りでは同じでも、`set` / `remove(int)` / `addAll(int,…)` の例外型と `listIterator()` / `subList()` の
  可変性が違う。**「読み取りは共有、書き込みで差し替え」だけでは足りず、例外の同値まで見る**。

---

## 付録: 大きい入力を Java で parse するときの実行条件（#276 の decision）

ケース32〜36 で確定したとおり、**パーサ本体は入力長に対して線形**で、大きい入力で観測される超線形は
**固定ヒープの collector**が生む。1 parse は入力長に比例した構文木を作って返すので live 集合は O(入力長) であり、
これは API の要求である。したがって数 KB を超える入力では、次を実行条件として明示することを勧める。

| 設定 | 何をするか | 効果（`complex-x64` = 20,923 byte、tinyexpression P4 文法） |
|---|---|---|
| **ヒープを増やす**（`-Xmx16g` など） | live 集合の数倍〜十数倍を young 世代に取らせる | GC 比率 13.96% → **2.83%**、µs/byte 倍率 ×1.250 → **×1.050**、x64 は 466 → 413 ms |
| **JVM を 1 CPU に固定しない** | `taskset -c N` / cgroup で 1 CPU にすると JVM は Serial GC を選ぶ | 1 CPU のまま collector だけ替えても逆効果（ParallelGC は x64 で 855 ms）。**コアを与えてから** collector を選ぶ |
| `ParseOptions.Diagnostics.DETAILED_ON_FAILURE` | 成功経路で失敗診断を作らない・持たない（ケース34） | `complex` 8.1 → 6.3 ms、x64 665 → 569 ms |
| `Memoization.SAFE_FAILURES` | 安全な rule に限定した失敗/成功 memo（ケース31） | 深い入れ子式で桁違い |
| `-Dunlaxer.memo.window`（既定 1,024） | memo 表の保持を文法の backtrack 距離で切る（ケース35） | x64 の memo entry 102,054 → 8,424 |

**逆に、既定の 2 GB ヒープ・1 CPU 固定のまま「入力を 64 倍にしても µs/byte が 1.2 倍以内」を要求するのは、
パーサではなく collector に対する要求になる。** 受け入れ条件を書くときは collector とヒープを条件に含めること。

### 構造的な代替: ubnfc 生成器

同じ文法・同じ入力を [ubnfc](https://github.com/opaopa6969/ubnfc) の Rust backend で走らせると、
AST 生成まで込みで `complex` 0.54 µs/byte / `complex-x64` 0.78 µs/byte である
（ubnfc `docs/reports/2026-09-23-p4-rust-diag-two-mode.md`）。Java 側の候補が 17.8 / 22.3 µs/byte なので
**1 byte あたり約 30 倍安く、GC が無いので「live 集合 × GC 回数」の積も生じない**。
unlaxer 自身の Rust runtime（tinyexpression-rs）でも同じ比較で µs/byte 倍率は ×0.94〜×1.17 で平坦だった（ケース32 / 35）。
**Java 実装で下限に近づける作業はここで一区切りとし、桁で速くする必要があるなら生成器側へ行く**、というのが #276 の結論である。
