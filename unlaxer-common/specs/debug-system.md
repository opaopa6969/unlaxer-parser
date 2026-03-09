# デバッグ/ログシステム仕様

> ステータス: draft
> 最終更新: 2026-03-01

## スコープ

このドキュメントはデバッグおよびログシステムの仕様を定義する。OutputLevel、パーサーリスナー、トランザクションリスナー、ブレークポイント、ParseFailureDiagnostics を含む。

このドキュメントが **扱わない** 範囲:
- ParseContext の基本構造（→ [parse-context.md](parse-context.md)）

## 関連ドキュメント

- [parse-context.md](parse-context.md) — ParseContext のリスナー登録
- [core-types.md](core-types.md) — Parsed, Token

---

## OutputLevel

**enum**: `org.unlaxer.listener.OutputLevel`

ログ出力の詳細度を制御する。

| 値 | 説明 |
|----|------|
| `none` | ログ出力なし |
| `simple` | 簡易ログ |
| `detail` | 詳細ログ |
| `mostDetail` | 最も詳細なログ |
| `withTag` | タグ情報を含むログ |

### テストでの使用

```java
ParserTestBase.setLevel(OutputLevel.detail);
```

---

## ParserListener

**インタフェース**: `org.unlaxer.listener.ParserListener`
**継承**: `BreakPointHolder`

パーサーの実行開始と終了を監視するリスナー。

### メソッド

| メソッド | タイミング | パラメータ |
|---------|----------|----------|
| `onStart(Parser, ParseContext, TokenKind, boolean)` | パーサー実行開始時 | パーサー、コンテキスト、トークン種別、反転フラグ |
| `onEnd(Parser, Parsed, ParseContext, TokenKind, boolean)` | パーサー実行終了時 | パーサー、結果、コンテキスト、トークン種別、反転フラグ |
| `setLevel(OutputLevel)` | 出力レベル設定 | — |

### 呼び出しタイミング

- `onStart()` は `parseContext.startParse()` から呼び出される
- `onEnd()` は `parseContext.endParse()` から呼び出される
- すべてのコンビネータはパース前後でこれらを呼び出す（MUST）

---

## TransactionListener

**インタフェース**: `org.unlaxer.listener.TransactionListener`
**継承**: `BreakPointHolder`

トランザクションのライフサイクルイベントを監視するリスナー。

### メソッド

| メソッド | タイミング | パラメータ |
|---------|----------|----------|
| `onOpen(ParseContext)` | ParseContext 生成時 | コンテキスト |
| `onBegin(ParseContext, Parser)` | トランザクション開始時 | コンテキスト、パーサー |
| `onCommit(ParseContext, Parser, TokenList)` | トランザクションコミット時 | コンテキスト、パーサー、コミットされたトークン |
| `onRollback(ParseContext, Parser, TokenList)` | トランザクションロールバック時 | コンテキスト、パーサー、ロールバックされたトークン |
| `onClose(ParseContext)` | ParseContext クローズ時 | コンテキスト |
| `setLevel(OutputLevel)` | 出力レベル設定 | — |

---

## BreakPointHolder

**インタフェース**: `org.unlaxer.listener.BreakPointHolder`

デバッグ用のブレークポイントメソッドを提供する。IDE のブレークポイントをこれらのメソッドに設定することで、特定のイベント発生時にデバッガを停止できる。

### ParserListener のブレークポイント

| メソッド | 用途 |
|---------|------|
| `onStartBreakPoint()` | パーサー開始時にブレーク |
| `onEndBreakPoint()` | パーサー終了時にブレーク |
| `onUpdateParseBreakPoint()` | パース更新時にブレーク |

### TransactionListener のブレークポイント

| メソッド | 用途 |
|---------|------|
| `onOpenBreakPoint()` | コンテキスト生成時にブレーク |
| `onBeginBreakPoint()` | トランザクション開始時にブレーク |
| `onCommitBreakPoint()` | コミット時にブレーク |
| `onRollbackBreakPoint()` | ロールバック時にブレーク |
| `onCloseBreakPoint()` | コンテキストクローズ時にブレーク |

---

## リスナー登録

ParseContext はリスナーを `Name` をキーとして管理する:

```java
Map<Name, ParserListener> parserListenerByName
Map<Name, TransactionListener> listenerByName
```

- 同じ `Name` で再登録すると、前のリスナーが上書きされる
- リスナーは登録順に呼び出される（`LinkedHashMap` による順序保証）

---

## ParseFailureDiagnostics

**クラス**: `org.unlaxer.context.ParseFailureDiagnostics`

パース失敗時の診断情報を提供する。

### ParseStackElement

パース失敗時のパーサースタックの各要素を表す。

| フィールド | 型 | 説明 |
|-----------|-----|------|
| `parserClassName` | `String` | パーサーのクラス名 |
| `depth` | `int` | ネストの深さ |
| `startOffset` | `int` | パース開始位置 |
| `maxConsumedOffset` | `int` | 最大消費位置 |
| `maxMatchedOffset` | `int` | 最大マッチ位置 |

### ExpectedHintCandidate

失敗位置で期待されていた入力のヒント候補を表す。

| フィールド | 型 | 説明 |
|-----------|-----|------|
| `displayHint` | `String` | 表示用のヒント文字列 |
| `parserClassName` | `String` | パーサーのクラス名（短縮） |
| `parserQualifiedClassName` | `String` | パーサーの完全修飾クラス名 |
| `parserDepth` | `int` | パーサーの深さ |
| `terminal` | `boolean` | 端末パーサーか否か |

### ParseContext の診断フィールド

ParseContext は以下の診断フィールドを追跡する:

| フィールド | 説明 |
|-----------|------|
| `farthestConsumedOffset` | パース中に到達した最も遠い消費位置 |
| `farthestMatchedOffset` | パース中に到達した最も遠いマッチ位置 |
| `maxReachedOffset` | パース中に到達した最大オフセット |
| `farthestFailureOffset` | 最も遠い失敗位置（初期値: -1） |
| `maxReachedStackElements` | 最大到達位置でのパーサースタック |
| `farthestFailureStackElements` | 最も遠い失敗位置でのパーサースタック |
| `expectedParsersAtFarthestFailure` | 最も遠い失敗位置で期待されていたパーサー名リスト |
| `expectedHintCandidatesAtFarthestFailure` | 最も遠い失敗位置でのヒント候補リスト |

### テスト出力

テスト実行時、以下の4種類のログが `build/parserTest/` に出力される:
- パースログ
- トランザクションログ
- トークンログ
- 結合ログ

---

## 現在の制限事項

- ブレークポイントメソッドはデフォルト実装が空で、IDE でのデバッグ専用
- `ParseFailureDiagnostics` は診断情報の構造体であり、フォーマット出力機能は別途必要

## 変更履歴

- 2026-03-01: 初版作成
