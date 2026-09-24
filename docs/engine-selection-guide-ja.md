# unlaxer Classic と ubnfc の選び方

org.unlaxer には UBNF 文法からパーサを得る経路が 2 つある。**両方とも同じ `.ubnf` を読む**ので、文法は共有できる。

| 名前 | 何か | 置き場所 |
|---|---|---|
| **unlaxer Classic**（`classic`） | コンビネータ実行系。`ParseContext` / transaction / Token 木の上で、`unlaxer-dsl` の生成器がパーサを組み立てる。3.0.x 系 | `unlaxer-parser`（`unlaxer-common`, `unlaxer-dsl`）|
| **ubnfc** | UBNF コンパイラ。Rust の front が Grammar IR を作り、Java / Rust の backend が**実行系に依存しないパーサ**（ubnfc parser）を書き出す | `ubnfc`（private）|

tinyexpression 2.0.0 からは ubnfc parser が既定エンジン、Classic は `classic` モードで 2.x の間だけ残る（3.0 で削除）。

## まず結論

- **新しく文法を書くなら ubnfc**。UBNF 2 の機能（左再帰・語境界・code point クラス・パラメータ化・到達不能検査）は ubnfc にしか無い。
- **既に Classic で動いていて、下の「Classic を選ぶ理由」に 1 つも当たらないなら ubnfc へ移す**。移行は facade（同じ公開面のクラスを差し替える）で済み、tinyexpression で実証済み。
- **Classic を選ぶ理由が 1 つでもあるなら Classic**。それらは ubnfc の設計上の割り切り（下記）なので、当面は解消しない。

## Classic を選ぶ理由（ubnfc に無いもの）

| 必要なもの | 中身 | ubnfc での代替 |
|---|---|---|
| **コード内でパーサを組み立てる** | 文法ファイル無しに Java で `Parsers.sequence(...)` のように合成する（例: 旧 `FormulaInfoBlockParser`） | 無い。`.ubnf` を書いて生成する |
| **解析中に利用者の状態を持つ** | `TransactionalState`・memo state hook で、利用者の状態を transaction と一緒に巻き戻す。インデント依存の字句、Forth 系の parsing word、解析中に育つ記号表をコードで操作 | 宣言的 scope（`@scopeTree` / `@declares` / `@backref`）で書ける範囲のみ。`@extern` scanner は純粋関数で状態を持てない |
| **完全な CST（Token 木）** | 全 rule 呼び出しのノードを持つ木。整形・railroad・旧 DAP が利用 | typed AST ＋ `lexical()`（字句列と span）。全ノードの木は無い |
| **増分解析** | 大きな文書を編集のたびに部分再解析 | 全文再解析（P4 20 KB で約 16 ms）。長期課題 |
| **memo 方針の実行時切替** | `Memoization.OFF / SAFE_FAILURES …` を実行時に選ぶ | front が静的に決める。切替は diag / tree / scan のみ |
| **全試行の診断** | DETAILED の trial 木・stack snapshot で「なぜ候補が落ちたか」を全部見る | 主位置・expected・最深 rule 経路・回復のみ。詳細は DAP の文法デバッガで |
| railroad 図、UBNF→BNF | 文書生成 | 未移植 |

## ubnfc を選ぶ理由（Classic に無いもの）

| 得るもの | 実測（P4 = tinyexpression の実用文法、`docs/reports` の出典つき） |
|---|---|
| **速度** | 認識 P4 complex: Classic 13.8 ms → ubnfc Java 0.10 ms / Rust 0.07 ms（100〜200 倍）。x64: 466 ms（Classic 6 ラウンド後）→ Java 6 ms / Rust 4.7 ms |
| **依存ゼロ** | 生成物は JDK 21 / Rust std のみ。Rust は `#![forbid(unsafe_code)]`、wasm32 でビルド可 |
| **2 実装の機械的突き合わせ** | 同じ文法から Java と Rust を生成し、corpus 1,212 件・差分ファジング・JSONTestSuite・仕様由来ケースで同値を検査 |
| **静的検査** | 到達不能な候補、語境界、`@recovery` の危険な配置、再入不能な memo、注釈の誤記（`@rooted`）を validate が警告 |
| **UBNF 2** | 左再帰（候補順＝優先順位）、語境界既定、code point クラス、パラメータ化ルール、中立 token 名 |
| **typed AST** | `@mapping` から型付き AST を生成。Classic は Token 木から mapper で組む |
| **IDE** | `--emit-lsp` / `--emit-dap`（opt-in）で LSP / VSIX / 文法デバッガ DAP を生成 |
| **JVM 不要** | Rust backend ＋ tinyexpression-rs（評価器込み、Java と 953 式で全件一致） |

## 判断の手順

1. 「Classic を選ぶ理由」の表に当たるものがあるか。あれば Classic（当たる項目を issue に書き、ubnfc 側に要望として残す）。
2. なければ ubnfc。既存コードは facade で差し替える（tinyexpression の `P4PreferredAstMapper` → `UbnfcP4PreferredAstMapper` が手本。公開面同一・パリティ 350 件・差分ファズ 1,072 件）。
3. 両方を一定期間並走させる場合は、同じ入力で受理・AST・診断位置を突き合わせる（tinyexpression の `UbnfcParityTest` の形）。

## 保守の方針（2026-09-25）

- Classic: 性能改善は第 6 ラウンド（unlaxer-parser #292）で打ち止め。以後は不具合修正と仕様追随のみ。3.1.0 以降も API は維持。
- ubnfc: 速度・言語・IDE の開発はこちら。ロードマップは ubnfc の issue #56〜#61。
- tinyexpression: 2.0.0 で ubnfc 既定、`classic` は 3.0 で削除。

## 出典

- 機能・正確性の比較: `ubnfc/docs/reports/2026-09-23-parser-feature-correctness-comparison.md`
- 速度の比較: `ubnfc/docs/reports/2026-09-23-parser-speed-comparison.md`、`2026-09-24-antlr-treesitter-comparison.md`、`2026-09-24-json-libraries-comparison.md`
- Classic の性能ケース: `docs/performance-tuning-ja.md` ケース 32〜37
- 移行の実例: `ubnfc/docs/reports/2026-09-24-te-facade.md`、tinyexpression `docs/reports/2026-09-24-ubnfc-default-engine.md`
- ubnfc の設計判断（D-001〜）: `ubnfc/docs/design/03-design-v1.md` §8
