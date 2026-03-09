# Parser IR 仕様

> ステータス: draft
> 最終更新: 2026-03-01

## スコープ

このドキュメントは Parser IR（Intermediate Representation）の仕様を定義する。JSON スキーマ、ノードモデル、スコープイベント、バリデーションルールを含む。`docs/PARSER-IR-DRAFT.md` の形式化。

このドキュメントが **扱わない** 範囲:
- CLI の `--validate-parser-ir` / `--export-parser-ir`（→ [cli.md](cli.md)）

## 関連ドキュメント

- [cli.md](cli.md) — Parser IR の CLI 操作
- [annotations.md](annotations.md) — @scopeTree とスコープイベントの関係
- [docs/PARSER-IR-DRAFT.md](../docs/PARSER-IR-DRAFT.md) — 設計メモ（参考）
- [docs/schema/parser-ir-v1.draft.json](../docs/schema/parser-ir-v1.draft.json) — JSON スキーマ

---

## 設計目標

Parser IR は以下を目的とする:

1. パーサーの動作を CFG（文脈自由文法）では表現しにくい高度な機能で拡張する
2. LSP/DAP や後段パイプラインとの互換性を維持する
3. UBNF 以外のパーサーも同じ下流パイプラインに接続可能にする

---

## 配置ルール

| 種別 | 配置先 | 例 |
|------|--------|-----|
| 認識セマンティクスに影響 | BNF 拡張（文法レベル） | interleave, backreference |
| ポストパースの意味解釈 | アノテーション | symbol definition/use, scope policy |

---

## ノードモデル

各ノードは以下のフィールドを持つ:

| フィールド | 型 | 必須 | 説明 |
|-----------|-----|------|------|
| `id` | `string` | はい | ノードの一意識別子（ドキュメント内で重複不可） |
| `kind` | `string` | はい | ノード種別 |
| `span` | `object` | はい | ソース位置（`start`, `end` オフセット） |
| `parentId` | `string` | いいえ | 親ノードの ID |
| `children` | `string[]` | いいえ | 子ノードの ID リスト |

### span

- `start`: 開始オフセット（inclusive）
- `end`: 終了オフセット（exclusive）
- `start < end`（MUST）

### 親子関係の整合性

- `parentId` で参照されるノードは存在する（MUST）
- `children` で参照されるノードは存在する（MUST）
- 自己参照は不可（MUST NOT）
- 親子関係は双方向で整合する（MUST）: 子の `parentId` が親を指し、親の `children` が子を含む

---

## スコープイベント

| イベント種別 | 必須フィールド | 禁止フィールド |
|------------|--------------|--------------|
| `enterScope` | — | `symbol`, `kind`, `targetScopeId` |
| `leaveScope` | — | `symbol`, `kind`, `targetScopeId` |
| `define` | `symbol`, `kind` | — |
| `use` | `symbol` | `kind` |

### スコープイベントのルール

- `scopeMode` は `enterScope` / `leaveScope` のみに許可（MUST）
- `leaveScope` の順序はネスト構造（LIFO）に従う（MUST）
- スコープイベントは unbalanced であってはならない（MUST NOT）
- 同一ストリーム内で重複する `enterScope` は不可（MUST NOT）
- `targetScopeId` が参照するスコープは存在する（MUST）

---

## アノテーション

| フィールド | 型 | 説明 |
|-----------|-----|------|
| `targetId` | `string` | 対象ノードの ID |
| `name` | `string` | アノテーション名 |
| `payload` | `object` | アノテーションデータ（1つ以上のプロパティが必要） |

### ルール

- 命名規約: `^[a-z][a-zA-Z0-9-]*$`（MUST）
- `(targetId, name)` ペアはドキュメント内で一意（MUST）
- `payload` は少なくとも1つのプロパティを持つオブジェクト（MUST）

---

## 診断

| フィールド | 型 | 説明 |
|-----------|-----|------|
| `code` | `string` | 診断コード |
| `span` | `object` | ソース位置 |
| `message` | `string` | メッセージ |
| `related` | `object[]` | 関連情報（optional） |

### ルール

- `(code, span.start, span.end, message)` タプルはドキュメント内で一意（MUST）
- `related` 内の `(span.start, span.end, message)` タプルは各診断内で一意（MUST）
- `span` はソース範囲内（MUST）
- `related` の `span` もソース範囲内（MUST）

---

## CLI との連携

| CLI オプション | 動作 |
|--------------|------|
| `--export-parser-ir <path>` | `.ubnf` から Parser IR JSON をエクスポート |
| `--validate-parser-ir <path>` | Parser IR JSON を直接バリデーション |

NDJSON モードでは `parser-ir-export` イベントが出力される:
- `source`: 入力ファイルパス
- `output`: 出力ファイルパス
- `grammarCount`: grammar ブロック数
- `nodeCount`: ノード数
- `annotationCount`: アノテーション数

---

## テストフィクスチャ

`src/test/resources/schema/parser-ir/` に配置:

- `valid-minimal.json` — 最小有効ペイロード
- `valid-rich.json` — オプションフィールドを含む有効ペイロード
- `invalid-*.json` — 各種バリデーションエラーの負のフィクスチャ

---

## 現在の制限事項

- Parser IR は Draft ステータス
- UBNF → Parser IR のエクスポートは基本的なノード構造のみ
- 非 UBNF パーサーとの統合は未実装

## 変更履歴

- 2026-03-01: PARSER-IR-DRAFT.md を形式化
