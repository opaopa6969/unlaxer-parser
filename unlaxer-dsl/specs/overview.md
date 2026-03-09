# unlaxer-dsl 概要

> ステータス: draft
> 最終更新: 2026-03-01

## スコープ

このドキュメントは unlaxer-dsl プロジェクト全体の目的、unlaxer-common との関係、UBNF 概念、セルフホスティング特性、および8つのジェネレータの概要を定義する。

このドキュメントが **扱わない** 範囲:
- unlaxer-common のパーサーコンビネータ仕様（→ unlaxer-common/specs/）
- TinyExpression 言語仕様（→ tinyexpression/specs/）

## 関連ドキュメント

- [ubnf-syntax.md](ubnf-syntax.md) — UBNF 文法の形式仕様
- [annotations.md](annotations.md) — アノテーション仕様
- [validation.md](validation.md) — バリデーション仕様
- [generators.md](generators.md) — ジェネレータ仕様
- [cli.md](cli.md) — CLI 仕様
- [token-resolution.md](token-resolution.md) — トークン解決仕様
- [parser-ir.md](parser-ir.md) — Parser IR 仕様
- [lsp-dap.md](lsp-dap.md) — LSP/DAP 動作仕様

## プロジェクト目的

unlaxer-dsl は **UBNF（Unlaxer BNF）文法定義から Java ソースコードを自動生成する DSL ジェネレータ** である。UBNF ファイルに文法を宣言的に記述し、パーサー、AST、マッパー、エバリュエータ、LSP サーバー、DAP サーバーの Java ソースコードを生成する。

### unlaxer-common との関係

- unlaxer-common はパーサーコンビネータのランタイムライブラリ
- unlaxer-dsl は UBNF 文法から unlaxer-common ベースのパーサーを **生成** する
- 生成されたパーサーは unlaxer-common の `Parser`, `Chain`, `Choice` 等のクラスを使用する

### セルフホスティング特性

UBNF 文法自体が UBNF で記述されている（`grammar/ubnf.ubnf`）。Bootstrap 完了時には、unlaxer-dsl 自身で UBNF パーサーを生成できることを目標とする。

現在は Bootstrap パーサー（`org.unlaxer.dsl.bootstrap` パッケージ）がハンドコードされており、UBNF 文法ファイルのパースに使用されている。

## UBNF 概要

UBNF は以下の要素で構成される:

| 要素 | 説明 |
|------|------|
| `grammar` ブロック | 文法全体を囲むコンテナ |
| グローバル設定 | `@key: value` 形式の設定（パッケージ名、空白処理等） |
| トークン宣言 | `token NAME = ParserClass` でランタイムパーサーを参照 |
| ルール宣言 | `RuleName ::= body ;` で文法規則を定義 |
| アノテーション | `@root`, `@mapping(...)` 等でメタデータを付与 |
| キャプチャ | `@name` で要素に名前を付けてマッピングに使用 |

## 8 ジェネレータ

| ジェネレータ | クラス名 | 生成物 |
|------------|---------|--------|
| Parser | `ParserGenerator` | `XxxParsers.java` — unlaxer-common ベースのパーサークラス群 |
| AST | `ASTGenerator` | `XxxAST.java` — sealed interface + record による型安全な AST |
| Mapper | `MapperGenerator` | `XxxMapper.java` — Token 木から AST へのマッピング |
| Evaluator | `EvaluatorGenerator` | `XxxEvaluator.java` — AST の評価スケルトン |
| LSP | `LSPGenerator` | `XxxLSP.java` — Language Server Protocol サーバー実装 |
| LSPLauncher | `LSPLauncherGenerator` | `XxxLSPLauncher.java` — LSP サーバーのランチャー |
| DAP | `DAPGenerator` | `XxxDAP.java` — Debug Adapter Protocol サーバー実装 |
| DAPLauncher | `DAPLauncherGenerator` | `XxxDAPLauncher.java` — DAP サーバーのランチャー |

## ツール

| ツール | クラス名 | 説明 |
|-------|---------|-----|
| Railroad Diagram | `RailroadMain` | UBNF 文法を視覚化する SVG を生成。論理的 RTL 描画および動的デバッグ表示に対応。 |
| BNF Converter | `UBNFToBNFMain` | UBNF を標準的な BNF 形式に変換。 |

## パイプライン

```
.ubnf ファイル
    ↓ パース（Bootstrap パーサー）
Token 木
    ↓ マッピング（UBNFMapper）
UBNFAST（AST）
    ↓ バリデーション（GrammarValidator）
バリデーション済み AST
    ↓ コード生成（8 ジェネレータ）
Java ソースファイル群
```

## 現在の制限事項

- セルフホスティングは未完了（Bootstrap パーサーがハンドコード）
- `@interleave`, `@backref`, `@scopeTree` はメタデータとして受理されるが、パーサー動作には未反映

## 変更履歴

- 2026-03-01: 初版作成
