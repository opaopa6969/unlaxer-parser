# 文法バリデーション仕様

> ステータス: draft
> 最終更新: 2026-03-01

## スコープ

このドキュメントは UBNF 文法のバリデーション仕様を定義する。すべてのエラーコード、重大度、カテゴリ、ValidationIssue の形式を含む。

このドキュメントが **扱わない** 範囲:
- アノテーションのセマンティクス詳細（→ [annotations.md](annotations.md)）
- CLI のバリデーションモード（→ [cli.md](cli.md)）

## 関連ドキュメント

- [annotations.md](annotations.md) — 各アノテーションの契約
- [cli.md](cli.md) — `--validate-only`, `--strict` 等の CLI オプション

---

## GrammarValidator

**クラス**: `org.unlaxer.dsl.codegen.GrammarValidator`

文法レベルのセマンティック制約を検証する。ジェネレータが依存する制約を事前に検証し、明確なエラーを報告する。

### API

| メソッド | 動作 |
|---------|------|
| `validate(GrammarDecl)` | `List<ValidationIssue>` を返す（スローしない） |
| `validateOrThrow(GrammarDecl)` | バリデーションエラーがある場合、例外をスローする |

---

## ValidationIssue

**record**: `GrammarValidator.ValidationIssue`

| フィールド | 型 | 説明 |
|-----------|-----|------|
| `code` | `String` | エラーコード（例: `E-MAPPING-MISSING-CAPTURE`） |
| `message` | `String` | 人間可読なエラーメッセージ |
| `hint` | `String` | 修正ヒント |
| `rule` | `String` | 対象ルール名（nullable） |

### 導出プロパティ

| メソッド | ロジック |
|---------|--------|
| `severity()` | コードが `W-` で始まる場合 `"WARNING"`、それ以外 `"ERROR"` |
| `category()` | コードのプレフィックスに基づくカテゴリ判定 |
| `format()` | `message [code: ...] [hint: ...]` 形式の文字列 |

---

## 重大度（Severity）

| 値 | 条件 | 意味 |
|----|------|------|
| `ERROR` | コードが `E-` で始まる | コード生成を中断すべきエラー |
| `WARNING` | コードが `W-` で始まる | コード生成は可能だが注意が必要 |

---

## カテゴリ（Category）

| カテゴリ | コードプレフィックス | 対象 |
|---------|-------------------|------|
| `MAPPING` | `E-MAPPING-` | @mapping アノテーションの制約 |
| `ASSOCIATIVITY` | `E-ASSOC-`, `E-RIGHTASSOC-` | @leftAssoc / @rightAssoc の制約 |
| `WHITESPACE` | `E-WHITESPACE-` | @whitespace の制約 |
| `PRECEDENCE` | `E-PRECEDENCE-` | @precedence の制約 |
| `ANNOTATION` | `E-ANNOTATION-` | アノテーション全般の制約 |
| `GENERAL` | その他 | 一般的な制約 |

---

## エラーコード一覧

### MAPPING エラー

| コード | 条件 |
|--------|------|
| `E-MAPPING-MISSING-CAPTURE` | `@mapping` の `params` に記載されたキャプチャ名がルール本体に存在しない |
| `E-MAPPING-EXTRA-CAPTURE` | ルール本体のキャプチャ名が `@mapping` の `params` に含まれていない |
| `E-MAPPING-DUPLICATE-PARAM` | `@mapping` の `params` に重複するパラメータ名がある |

### ASSOCIATIVITY エラー

| コード | 条件 |
|--------|------|
| `E-ASSOC-BOTH` | `@leftAssoc` と `@rightAssoc` が同一ルールに使用されている |
| `E-ASSOC-WITHOUT-PRECEDENCE` | `@leftAssoc` / `@rightAssoc` が `@precedence` なしで使用されている |
| `E-RIGHTASSOC-NON-CANONICAL` | `@rightAssoc` ルールが非正規形（`Base { Op Self }` でない） |

### PRECEDENCE エラー

| コード | 条件 |
|--------|------|
| `E-PRECEDENCE-WITHOUT-ASSOC` | `@precedence` が `@leftAssoc` / `@rightAssoc` なしで使用されている |
| `E-PRECEDENCE-DUPLICATE` | 同一ルールに `@precedence` が複数回宣言されている |
| `E-PRECEDENCE-MIXED-LEVEL` | 同一優先度レベルで左結合と右結合が混在している |
| `E-PRECEDENCE-ORDER` | 演算子ルールの参照先が適切な優先度順序になっていない |

### ANNOTATION エラー

| コード | 条件 |
|--------|------|
| `E-ANNOTATION-DUPLICATE` | 同一アノテーションが1つのルールに複数回宣言されている |
| `E-ANNOTATION-INVALID-PROFILE` | `@interleave` の `profile` が不正な値 |
| `E-ANNOTATION-INVALID-MODE` | `@scopeTree` の `mode` が不正な値 |

---

## バリデーション結果の集約

- バリデーションは grammar ブロック単位で実行される
- 複数の grammar ブロックのバリデーションエラーは集約されて1つのエラーとして報告される
- JSON レポートでは `issues[]` エントリが構造化メタデータを含む:
  - `rule`, `code`, `severity`, `category`, `message`, `hint`, `grammar`
- `issues[]` の順序は決定的（`grammar`, `rule`, `code`, `message` でソート）（MUST）
- レポートには集約サマリーが含まれる: `severityCounts`, `categoryCounts`

---

## 現在の制限事項

- 警告コードは限定的（大部分がエラーコード）
- トークン解決のバリデーション（パーサークラスの存在確認）は未実装

## 変更履歴

- 2026-03-01: 初版作成
