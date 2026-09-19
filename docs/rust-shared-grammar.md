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
