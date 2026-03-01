# LSP/DAP 動作仕様

> ステータス: draft
> 最終更新: 2026-03-01

## スコープ

このドキュメントは生成される LSP（Language Server Protocol）サーバーおよび DAP（Debug Adapter Protocol）サーバーの動作仕様を定義する。

このドキュメントが **扱わない** 範囲:
- ジェネレータの実装詳細（→ [generators.md](generators.md)）

## 関連ドキュメント

- [generators.md](generators.md) — LSP/DAP ジェネレータ
- [overview.md](overview.md) — プロジェクト概要

---

## LSP サポート機能

### 実装済み

| 機能 | 説明 |
|------|------|
| Diagnostics | UBNF 文法のバリデーションエラー・警告をリアルタイム表示 |
| Hover | ルール名やキーワードのホバー情報 |
| Completion | キーワード補完およびアノテーション補完 |

### 補完対象

以下が補完候補として提供される:

- DSL コアキーワード
- アノテーションキーワード:
  - `@root`
  - `@mapping`
  - `@whitespace`
  - `@interleave`
  - `@backref`
  - `@scopeTree`
  - `@leftAssoc`
  - `@rightAssoc`
  - `@precedence`
- 文法ターミナル

### 制限事項

| 機能 | 状況 |
|------|------|
| Semantic Tokens | 空のトークンリストを返す（無効なトークンエンコーディングを回避するため） |

---

## DAP サポート機能

### ブレークポイント

- パーストークンストリームに基づくブレークポイント設定
- ソース位置とトークン位置のマッピングによりブレークポイントが解決される

### ステッピング

- ステップポイントはパースツリーのリーフから深さ優先で収集される（MUST）
- ステップイン / ステップオーバー / ステップアウトがサポートされる

---

## 生成クラス

| ジェネレータ | 生成クラス | 説明 |
|------------|-----------|------|
| LSP | `{GrammarName}LSP.java` | LSP サーバー実装 |
| LSPLauncher | `{GrammarName}LSPLauncher.java` | LSP サーバーの起動 `main()` |
| DAP | `{GrammarName}DAP.java` | DAP サーバー実装 |
| DAPLauncher | `{GrammarName}DAPLauncher.java` | DAP サーバーの起動 `main()` |

---

## 現在の制限事項

- セマンティックトークンは空リストを返すのみ
- LSP/DAP の機能セットは限定的
- ルールレベルの空白オーバーライドセマンティクスは未確定
- Go to Definition / Find References は未サポート

## 変更履歴

- 2026-03-01: 初版作成
