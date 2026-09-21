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
