# Rust共有grammar graph

Issue #185では、生成parserがparseごとに`Vec<Rule>`を構築し、runtimeがさらに
rule sliceを`Arc<[Rule]>`へ複製していた経路を分離した。生成コードは
`OnceLock<SharedGrammar>`をprocess内で一度だけ初期化し、通常の
`parse_tree*`と`parse_context`は共有graphを使う。

## 公開契約

- `unlaxer_runtime::SharedGrammar`は`Arc<[Rule]>`の別名で、不変なrule graphだけを共有する。
- `share_grammar`、`parse_shared`、`parse_detailed_shared`、
  `ParseContext::parse_shared_grammar`が共有graphを受け取る。
- 生成`parser::grammar()`は`&'static SharedGrammar`を返す。`OnceLock`の初期化後、
  parseごとのコストは`Arc`の参照カウント操作だけである。
- 既存利用とのsource互換性のため、生成`parser::rules() -> Vec<Rule>`は残す。
  これは検査・独自実行用のsnapshotであり、呼ぶたびにgraph全体をcloneする。
  通常のparse入口は`rules()`を呼ばない。
- cursor、matched cursor、CST node、capture、diagnostic、scope、user stateは
  従来どおり`ParseContext`ごとに所有され、grammarと一緒に共有されない。

旧`parse`/`parse_detailed`と`ParseContext::parse_grammar(Vec<Rule>, ...)`も互換入口として
残る。手書きparserでgraphを繰り返し使う場合は共有APIを選ぶ。

## Java parser構築からの移植（#845）

Java の `Parser.get(Class)` / `Parser.get(Supplier)` / `newInstance`、lazy children、
`ReferenceByNameParser` / `ReferenceParser` は、Rust では構築時に rule ID を決める。
対応する Java 公開型の判定は [Java API 対応表](java-api-rust-parity.md#construction) にある。

- `RuleNames::new(["Root", "Item"])` で名前を宣言順に登録し、`reference("Item")` で
  `Expr::Rule(1)` を得る。宣言を先に済ませるので、再帰・前方参照も lazy 初期化なしで書ける。
  `share(rules)` は宣言と異なる名前・順序・件数を `RuleNameError::Mismatch` で拒否する。
  規則本体の `Expr::Rule(index)` が rules の範囲外なら `RuleNameError::OutOfRange` で拒否する
  （検査しない `share_grammar` では、範囲外の参照は通常の失敗になり、choice が黙って次の候補へ進む）。
  本体の走査は再帰しないので、深い式でも stack を使い切らない。`Expr::Custom` の callback の中で
  使う index は静的に検査できないので、callback 側の責任になる。
- Rule ID は、発行した `RuleNames` で共有した grammar にだけ有効。rules を足す・消す・並べ替えるときは
  table を作り直し、参照もすべて作り直す。古い table の ID を流用すると、範囲内なら検査を通り、
  その位置にある別の rule を指す（construction.tsv の `rule-names-stale-id`）。
- 重複名は `RuleNameError::Duplicate`（Java `ParserNameContext` の別 parser 同名拒否と同じ）。
  一つの rule を複数箇所から `Expr::Rule(id)` で参照しても重複ではない。
- 未登録名は `reference` が `Unregistered` を返す。Java と同じく parse 時の失敗として扱う場合は
  `reference_or_never`（反転しても一致しない `Expr::JavaEmptyWord`）を使い、choice は次の候補へ進む。
- Java `ReferenceParser` の predicate は最初の解析で一度だけ評価され、miss も cache される。
  Rust では predicate を構築時に一度だけ評価し、解決済みの式を保持する。rules を新しい table で
  作り直すと名前は再解決される（`ReferenceByNameParser` が miss を再探索するのに相当）。
- Java の `ReferenceByNameParser` は、見つけた parser を instance ごとに保持し（`parserByName`）、
  root も最初の `getRoot()` で保持する。そのため、graph に手前の同名 parser を足しても、
  解析済みの graph を別の root の下へ付け替えても、自動では解決し直さない。Rust でも共有済みの
  grammar は Rule ID を変えないので、変更は新しい grammar を作って反映する
  （`by-name-hit-cached` / `root-cache-after-reparent`）。
- Java の `RootParserIndicator` は名前参照・親走査・path の範囲を区切る。Rust では scope ごとに
  `RuleNames` と `SharedGrammar` を別に作り、外側の rule から `Expr::Custom` の中で
  `context.parse_shared_grammar(scope, 0, false)` を呼ぶ。scope の table に外側の名前は登録されないので、
  scope 内の参照は外側の rule へ届かない。失敗時の `RuleStack` には、Java と同じく呼び出し元の frame も残る。
- 共有 instance は `OnceLock<SharedGrammar>`、都度構築は `share_grammar`、同一性は
  `Arc::ptr_eq`。失敗し得る factory は成功した値だけを cell に入れ、失敗後に再試行できるようにする。
  `OnceLock::get_or_init` の中から同じ cell を再初期化しない（std では未規定で、現在の実装は
  deadlock する）。循環は `Expr::Rule(id)` で書く。
- capture・scope・user state は `ParseContext` が持ち、共有 grammar には残らない。新しい context は
  前の解析の state を引き継がない（Java の global scope map も同じ）。
- Java の共有 graph は、初回の解析で遅延 cache を埋める（`AbstractParser.root`・`parserByName`、
  `ReferenceParser` などの参照先、lazy children、`ParserFactoryByClass` の初期化 map）。#1007 から、
  これらは別々の context が同時に解析しても安全に公開される。参照先と root は不変の `Optional` を
  1 本の field で公開し、名前の map は `ConcurrentHashMap`、lazy children は従来どおり同期する。
  初回を同時に迎えた thread は、それぞれ同じ結果を計算して公開する。解決中に lock は持たない。
  `shared-graph.tsv` と cases.tsv を、凍結した graph（Rust は不変の `Arc` grammar）上で
  8 thread × 40 round 実行し、各行の期待値と照合する。対象外は二つある。
  一つは同じ `ParseContext` の同時利用、もう一つは利用者が解析中に行う graph の変更（子の追加・付け替え）である。
  後者は従来どおり一つの thread で行う契約で、次項の委譲先の初回の追加だけは library が並行時にも安全に行う。
  `AbstractPropagatableSource.computedInvertMatch` は解析に使わない。読むのは TokenPrinter の
  `(inverted)` 表示だけで、その値は直前の呼び出しのものであり、token の性質ではない
  （別の context が上書きし得る）。Rust には対応する共有状態がない。
- `AbstractParser.parse` を継承する利用者の parser は、`createParser()` が返す委譲先を初回に作る。
  library と生成器の parser はどれも `parse` を override し、`createParser()` は `this` を返すので、
  この経路を通らない。委譲先がまだ子でなければ、初回は委譲先を子に足す graph の変更になる。
  そのため初回は、子を準備するのと同じ parser の monitor の下で一度だけ行い、委譲先の親を設定して
  子に足してから field（volatile）に公開する。作成中の別 context は、2 つ目の委譲先を作らずに
  monitor で待つ（Rust の `OnceLock::get_or_init` と同じ）。作成が例外で終われば何も公開せず、
  次の呼び出しが作り直す。子の list は書き換えずに差し替えるので、走査中の別 thread は
  付け足す前か後の list を最後まで見る。`createParser()` の中から自分の `getChildren()`・
  `getRoot()`・名前検索は呼べるが、自分の `getParser()` を呼ぶと従来どおり再帰する。
  `createParserAndThen` は作成後の一度ではなく、`getParser()` の呼び出しごとに走る（従来どおり）。
  初回の `createParser()` は所有 parser の monitor 内で動く。別 parser の初期化を相互に呼ぶ場合は、
  複数 thread で初回構築を始めると lock の循環が起こり得るため、共有前に単一 thread で構築を終える。
  利用者の `createParserAndThen` も並行して呼ばれるため、共有 graph を変更せず thread ごとの状態を使う。
  `Parsers.list()`・`subList()`・`listIterator()` など、接続前に取り出した view は以前の list を指し、
  後から付いた子を反映しない。通常の graph の変更と view の併用は単一 thread の契約のままである。
- 委譲先の部分木にある名前は、委譲先を作るまで検索に見えない。名前参照は miss を cache しないが、
  `ReferenceParser` は miss を cache するので、凍結して共有する graph は、構築を終えたあと共有する前に、
  委譲型の parser それぞれで `getParser()` を呼んで委譲先を作っておく（下の例）。`parse` を
  override する parser（`LazyChain` など）で呼ぶと、使われない `createParser()` の結果を子に足して
  しまうので、graph 全体へ一律には呼ばない。
  ```java
  LazyDelegate body = new LazyDelegate();          // AbstractParser.parse を継承する利用者 parser
  Parser root = new PseudoRootParser(new Parsers(new NamedChain("List", body), elem, top));
  body.getParser();                                 // 凍結: 共有する前に委譲先を作って子に足す
  executor.submit(() -> root.parse(new ParseContext(source)));
  ```
  作らずに共有しても、解析の結果と委譲先の一意性は保たれる（`concurrentFirstUsesOfALazyDelegateCreateAndAttachOneBody`
  の first-use）。凍結が要るのは、委譲先の部分木を対象にする検索の結果を、解析の順序に依らせないためである。
  Rust の grammar は不変なので、委譲先は grammar の外の `OnceLock<Expr>` に置き、同じ graph を解析する
  context が user state で共有する（`Expr::Custom` が初回に作る）。両言語とも shared-graph.tsv の行を
  first-use と凍結済みの両方で 8 thread × 各行 2 context × 40 round 照合し、作成が 1 回であること、
  作成中に止めた thread を別 context が待つことを検査する。
- 並行 fixture の Rust 側は、Java の `parser.parse(context)` に合わせて最も外側を `ParseContext::parse`
  にし、その中で Classic の consumed mode に入る。最も外側を `parse_with_token_mode` にすると、成功した
  解析でも `rule_stack()` が投機的な失敗の stack を返す（#856 の残差。この迂回は共通 API の完了を
  意味しない）。
- Classic の反転は共有 graph ではなく `ParseContext::token_mode()` にある。利用者定義の
  `AbstractPropagatableSource` 相当は、`Expr::Custom` の中で mode を反転して子を
  `parse_with_token_mode` する。

Java/Rust 共通の期待値は `conformance/parser-construction/cases.tsv`（解析結果）と
`construction.tsv`（構築・同一性の観測、Java 固有の行は理由と代替の不変条件つき）。
cases.tsv が言語間で比べる CST は、根の token・`getOriginalTokens` 相当・葉 token だけである。
中間 node は言語間で比べない（Java は combinator の token を持ち、Rust の Fragment は省く。#851）。
その代わり、各言語のテストは中間 node を含む原 CST 全体が構築経路の間で等しいことを検査する。
Rust の `Expr::Custom` で作った反転 source は CST に node を残さず、`invert_match` は
`NotPropagatableSource` の node を残すため、この 2 つは別の群として比べる。

## 受け入れテスト

生成exampleは、複数threadから同じ`Arc` allocationを参照しながらparse・mapできること、
grammar初期化回数が1であることを検証する。初期化counterは生成moduleが`cfg(test)`で
コンパイルされるunit test専用で、release成果物には入らない。runtime testは、共有graphを
使う複数contextのtyped state、capture、CST nodeが独立すること、従来slice APIとAST・
diagnosticが一致することを検証する。

## 小規模A/B計測

`unlaxer-evolution-example`の6-rule文法をrelease buildし、同じprocessで100回warmup後に
各100,000回を5 run測定した中央値。`legacy-clone`は互換`rules()` snapshotと従来slice APIを
使い、`shared-arc`は生成parserの共有経路を使う。2026-09-20、rustc 1.85.0、
Linux WSL2、AMD Ryzen 9 7950Xで測定した。

| 区間 | legacy-clone | shared-arc | 差 |
|---|---:|---:|---:|
| setup-only | 364.1 ns/op | 2.4 ns/op | 99.3%減 |
| parse-only | 17,885.0 ns/op | 16,746.9 ns/op | 6.4%減 |
| parse+map | 18,810.7 ns/op | 18,057.1 ns/op | 4.0%減 |

再実行:

```sh
UNLAXER_BENCH_ITERATIONS=100000 cargo +1.85.0 run --release \
  --manifest-path rust/Cargo.toml \
  -p unlaxer-evolution-example --example shared_grammar_benchmark
```

これはgrammar graph共有だけを測る小規模microbenchmarkであり、packrat memoizationや
backtracking改善を含まない。124-ruleのtinyexpression benchmarkは、このrevisionをpinした
downstreamで別途再測定し、探索コストとsetupコストを混同しない。
