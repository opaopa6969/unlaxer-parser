# 論文 & DGE セッション インデックス

> **本ドキュメント群はすべてフィクションです。**
>
> 架空の論文「From Grammar to IDE」を執筆し、それを DGE（Design Gap Exploration）手法で査読会話劇に仕立てたものです。
>
> 3人の架空レビュアー:
> - **R1** — 圏論原理主義の理論家
> - **R2** — ベンチマーク至上主義の実務家
> - **R3** — Haskell を神と崇める関数型信者
>
> と、すぐキレる**先輩**（著者）・なだめ役の**後輩**が、査読のやりとりを通じて設計上の穴を洗い出していきます。
>
> 笑いあり涙あり逆ギレありの、学会査読エンターテインメントとしてお楽しみください。

---

## 査読会話劇

| Ver | ドキュメント |
|-----|-------------|
| v1 | [査読会話劇: "From Grammar to IDE" v1 査読プロセス](./v1/review-dialogue-v1.ja.md) |
| v2 | [査読会話劇 第2ラウンド: "From Grammar to IDE" v2 査読プロセス](./v2/review-dialogue-v2.ja.md) |
| v4 | [査読会話劇 第3ラウンド: "From Grammar to IDE" v4 査読プロセス](./v4/review-dialogue-v4.ja.md) |
| v5 | [査読会話劇 最終ラウンド: "From Grammar to IDE" v5 査読プロセス](./v5/review-dialogue-v5.ja.md) |

## 論文バージョン

| Ver | ドキュメント |
|-----|-------------|
| v1 | [文法からIDEへ: 単一の文法仕様からのパーサ、AST、評価器、LSP、DAPの統一的生成 (JA)](./v1/from-grammar-to-ide.ja.md) |
| v1 | [From Grammar to IDE: Unified Generation of Parser, AST, Evaluator, LSP, and DAP from a Single Grammar Specification (EN)](./v1/from-grammar-to-ide.en.md) |
| v2 | [From Grammar to IDE: 単一の文法仕様からのパーサ、AST、エバリュエータ、LSP、DAPの統一生成 (JA)](./v2/from-grammar-to-ide.ja.md) |
| v2 | [From Grammar to IDE: Unified Generation of Parser, AST, Evaluator, LSP, and DAP from a Single Grammar Specification (EN)](./v2/from-grammar-to-ide.en.md) |
| v3 | [文法からIDEへ: 単一の文法仕様からのパーサー、AST、エバリュエータ、LSP、DAPの統一生成 (JA)](./v3/from-grammar-to-ide.ja.md) |
| v3 | [From Grammar to IDE: Unified Generation of Parser, AST, Evaluator, LSP, and DAP from a Single Grammar Specification (EN)](./v3/from-grammar-to-ide.en.md) |
| v4 | [文法からIDEへ：単一文法仕様からのパーサー、AST、エバリュエータ、LSP、DAPの統一生成 (JA)](./v4/from-grammar-to-ide.ja.md) |
| v4 | [From Grammar to IDE: Unified Generation of Parser, AST, Evaluator, LSP, and DAP from a Single Grammar Specification (EN)](./v4/from-grammar-to-ide.en.md) |
| v5 | [文法からIDEへ：単一文法仕様からのパーサー、AST、エバリュエータ、LSP、DAPの統一生成 (JA)](./v5/from-grammar-to-ide.ja.md) |
| v5 | [From Grammar to IDE: Unified Generation of Parser, AST, Evaluator, LSP, and DAP from a Single Grammar Specification (EN)](./v5/from-grammar-to-ide.en.md) |

## 補遺

| ドキュメント |
|-------------|
| [Addendum: Macro PEG Reference](./v2/ADDENDUM-macro-peg.md) |

## DGE（Design Gap Exploration）セッション

| ドキュメント |
|-------------|
| [Ternary 演算子の設計 — バグの温床を事前に潰す](./dge-ternary.md) |
| [Ternary の括弧省略問題 — 処理系の都合 vs ユーザーの幸せ](./dge-ternary-parens-in-context.md) |
| [Priority 1 文法マージ (Boolean And/Or/Xor + Math関数 + Ternary + toNum)](./dge-grammar-merge.md) |
| [String メソッドの設計 — 関数形式とドット形式の共存](./dge-string-methods.md) |
| [インクリメンタル構文解析の設計](./dge-incremental-parsing.md) |
| [UBNF パスと手書きパスの機能パリティ](./dge-ubnf-handwritten-parity.md) |
| [FormulaInfo LSP — 3層コンテキスト切り替えとJavaCode編集](./dge-formulainfo-lsp.md) |
| [PropagationStopper 第3軸 — syntaxContext による括弧省略](./dge-propagation-third-axis.md) |
| [unlaxer-parser Future Work の設計の穴](./dge-future-work.md) |
| [tinyexpression 専用 IDE & LLM ワークフロー](./dge-ide-and-llm-workflow.md) |
| [LSPGenerator の GGP 化 — monolithic 出力から拡張可能な2層アーキテクチャへ](./dge-lsp-generator-ggp.md) |
| [P4TypedAstEvaluator での MethodInvocation と External 呼び出しの実装](./dge-method-invocation-and-externals.md) |
| [残りの機能ギャップ — String連結, inTimeRange, String slice](./dge-remaining-gaps.md) |
| [UBNF 用 VSCode 拡張 (VSIX) の設計](./dge-ubnf-vsix.md) |

## バックログ

| ドキュメント |
|-------------|
| [PropagationStopper 第3軸 — syntaxContext (DEFERRED)](./backlog-propagation-third-axis.md) |
