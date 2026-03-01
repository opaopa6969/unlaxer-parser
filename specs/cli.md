# CLI 仕様

> ステータス: draft
> 最終更新: 2026-03-01

## スコープ

このドキュメントは `CodegenMain` CLI ツールの完全仕様を定義する。すべてのフラグ、終了コード、レポート形式、マニフェスト、各種動作モードを含む。

このドキュメントは既存の `SPEC.md` の CLI 節から抽出・拡充したものである。

このドキュメントが **扱わない** 範囲:
- バリデーションルール詳細（→ [validation.md](validation.md)）
- ジェネレータの生成内容（→ [generators.md](generators.md)）

## 関連ドキュメント

- [validation.md](validation.md) — バリデーションエラーコード
- [generators.md](generators.md) — ジェネレータ一覧
- [parser-ir.md](parser-ir.md) — Parser IR のエクスポート・バリデーション

---

## エントリーポイント

**クラス**: `org.unlaxer.dsl.CodegenMain`

```bash
java -cp ... org.unlaxer.dsl.CodegenMain [options]
```

---

## 必須オプション

| オプション | 説明 |
|-----------|------|
| `--grammar <path>` | UBNF 文法ファイルパス |
| `--output <path>` | 出力ディレクトリパス |
| `--generators <list>` | カンマ区切りのジェネレータ名 |

- `--grammar`, `--output`, `--generators` のいずれかが欠けている場合は CLI エラー（MUST）
- `--generators` の値はカンマで分割され、空白はトリムされる。空エントリは CLI エラー（MUST）

---

## オプション一覧

### 情報表示

| オプション | 説明 |
|-----------|------|
| `--help`, `-h` | 使用方法を表示して終了コード `0` で終了 |
| `--version`, `-v` | ツールバージョンを表示して終了コード `0` で終了 |

### バリデーション

| オプション | 説明 |
|-----------|------|
| `--validate-only` | バリデーションのみ実行（コード生成しない） |
| `--strict` | バリデーション警告を失敗として扱う（終了コード `5`） |

### コード生成制御

| オプション | 説明 |
|-----------|------|
| `--dry-run` | 生成ファイルパスをプレビュー（書き込みなし） |
| `--clean-output` | 生成前にターゲットファイルを削除 |
| `--overwrite <mode>` | 上書きモード: `never`, `if-different`, `always` |
| `--fail-on <policy>` | 追加の失敗ポリシー（後述） |

### Parser IR

| オプション | 説明 |
|-----------|------|
| `--validate-parser-ir <path>` | Parser IR JSON を直接バリデーション（`.ubnf` パースなし） |
| `--export-parser-ir <path>` | `.ubnf` から Parser IR JSON をエクスポート |

### レポート

| オプション | 説明 |
|-----------|------|
| `--report-format <format>` | レポート形式: `text`, `json`, `ndjson` |
| `--report-file <path>` | レポートをファイルに書き出す |
| `--report-version <N>` | JSON スキーマバージョン（現在は `1` のみ） |
| `--report-schema-check` | JSON ペイロードのスキーマ検証を有効化 |
| `--warnings-as-json` | `text` 形式でも警告を JSON で stderr に出力 |

### マニフェスト

| オプション | 説明 |
|-----------|------|
| `--output-manifest <path>` | アクションマニフェスト JSON を書き出す |
| `--manifest-format <format>` | マニフェスト形式: `json`, `ndjson` |

---

## --fail-on ポリシー

| 値 | 動作 |
|----|------|
| `none` | 追加の失敗チェックなし |
| `warning` | 警告がある場合に失敗 |
| `skipped` | スキップされたファイルがある場合に失敗 |
| `conflict` | コンフリクトがある場合に失敗 |
| `cleaned` | クリーンされたファイルがある場合に失敗 |
| `warnings-count>=N` | 警告数が N 以上の場合に失敗 |

---

## 終了コード

| コード | 意味 |
|--------|------|
| `0` | 成功 |
| `2` | CLI 使用方法エラー（引数不正） |
| `3` | バリデーションエラー |
| `4` | 生成/ランタイムエラー |
| `5` | strict バリデーションエラー（警告を失敗として扱った場合） |

---

## レポート形式

### text

デフォルト。人間可読なテキスト出力。

### json

機械可読な JSON 出力。

トップレベルフィールド:
- `reportVersion`: `1`
- `schemaVersion`: `"1.0"`
- `schemaUrl`: スキーマ URL
- `toolVersion`: ツールバージョン（`Implementation-Version` から取得、フォールバック `dev`）
- `argsHash`: 正規化されたセマンティック CLI 設定の SHA-256
- `generatedAt`: UTC ISO-8601 タイムスタンプ
- `mode`: `"validate"` または `"generate"`
- `ok`: 成否
- `warningsCount`: 警告数
- `issues[]`: バリデーション問題（`rule`, `code`, `severity`, `category`, `message`, `hint`, `grammar`）

生成モード追加フィールド:
- `generatedCount`, `generatedFiles`
- `writtenCount`, `skippedCount`, `conflictCount`, `dryRunCount`
- `failReasonCode`（fail-on トリガー時）

### ndjson

改行区切り JSON イベント。

イベント種別:
- `file` — ファイル生成イベント
- `validate` — バリデーション結果イベント
- `parser-ir-export` — Parser IR エクスポートイベント（`source`, `output`, `grammarCount`, `nodeCount`, `annotationCount`）
- `cli-error` — CLI エラーイベント（`code`, `message`, `detail`, `availableGenerators`）

ndjson モードでは:
- `stdout` は JSON-lines のみ（人間可読テキストは抑制）（MUST）
- `stderr` も JSON-lines のみ（バリデーション失敗時）
- `--report-file` 使用時、永続化されるのは NDJSON イベントラッパーなしの生 JSON ペイロード

---

## argsHash

- 正規化されたセマンティック CLI 設定の SHA-256 ハッシュ
- **含まれる設定**: `grammar`, `output`, `generators`, `validate-only`, `dry-run`, `clean-output`, `strict`, `validate-parser-ir`, `export-parser-ir`, `report-format`, `manifest-format`, `report-version`, `report-schema-check`, `warnings-as-json`, `overwrite`, `fail-on`, warnings threshold
- **含まれない設定**: `--report-file`, `--output-manifest`, `--help`, `--version`（宛先パスと非実行フラグ）
- 正規化はバージョニングされている（`version=1`）

---

## cli-error コード

| コード | 条件 |
|--------|------|
| `E-CLI-USAGE` | CLI 使用方法エラー |
| `E-CLI-UNKNOWN-GENERATOR` | 不明なジェネレータ名 |
| `E-CLI-UNSAFE-CLEAN-OUTPUT` | 安全でない `--clean-output` 操作 |
| `E-PARSER-IR-EXPORT` | Parser IR エクスポートエラー |
| `E-IO` | I/O エラー |
| `E-RUNTIME` | ランタイムエラー |
| `E-REPORT-SCHEMA-*` | スキーマ検証失敗 |

---

## スキーマ定義ファイル

| パス | 内容 |
|------|------|
| `docs/schema/report-v1.json` | JSON レポートスキーマ |
| `docs/schema/report-v1.ndjson.json` | NDJSON イベントスキーマ |
| `docs/schema/manifest-v1.json` | マニフェスト JSON スキーマ |
| `docs/schema/manifest-v1.ndjson.json` | マニフェスト NDJSON スキーマ |

---

## 処理フロー

1. CLI 引数をパース
2. 各 grammar ブロックに対してバリデーション実行
3. バリデーションエラーを集約
4. `--validate-only` の場合、レポートを出力して終了
5. ジェネレータを実行してソースコードを生成
6. `--fail-on` ポリシーを適用
7. レポート/マニフェストを出力
8. 適切な終了コードで終了

---

## 現在の制限事項

- `--report-version` は `1` のみサポート
- `toolVersion` が取得できない場合は `dev` にフォールバック

## 変更履歴

- 2026-03-01: 既存 SPEC.md から抽出・拡充
