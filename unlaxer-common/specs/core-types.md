# コアデータ型仕様

> ステータス: draft
> 最終更新: 2026-03-01

## スコープ

このドキュメントは unlaxer-common のコアデータ型（`Parsed`, `Cursor`, `Token`, `Source`, `TokenKind`）の仕様を定義する。

このドキュメントが **扱わない** 範囲:
- ParseContext のライフサイクル（→ [parse-context.md](parse-context.md)）
- コンビネータの動作（→ [combinators.md](combinators.md)）

## 関連ドキュメント

- [overview.md](overview.md) — プロジェクト概要・用語集
- [parse-context.md](parse-context.md) — ParseContext 仕様

## 用語定義

- **CodePointIndex**: ソース内のコードポイント位置（0始まり）
- **CodePointLength**: コードポイント数による長さ
- **CodePointOffset**: コードポイント数によるオフセット
- **CursorRange**: 開始カーソルと終了カーソルのペア

---

## Parsed

**クラス**: `org.unlaxer.Parsed`
**継承**: `Committed` を継承

### ステータスモデル

`Parsed` は以下の3つのステータスを持つ enum `Parsed.Status` により状態を表す:

| ステータス | 意味 | `isSucceeded()` | `isFailed()` |
|-----------|------|-----------------|--------------|
| `succeeded` | パースが完全に成功した | `true` | `false` |
| `stopped` | パースは成功したが、停止シグナルにより後続処理を中断すべき | `true` | `false` |
| `failed` | パースが失敗した | `false` | `true` |

### セマンティクス

- `succeeded` と `stopped` はいずれも「成功」として扱われる（`isSucceeded()` が `true`）
- `stopped` は **「パース自体は成功したが、親コンビネータの後続処理を中断すべき」という早期終了シグナル** である
- `failed` はパースの失敗を示す。コンビネータは `failed` を受け取ると適切にバックトラックする

### stopped の設計意図

`stopped` はコード補完/サジェスション収集のための制御フロー信号として設計された。主な生成元は `SuggestsCollectorParser` であり、入力途中のソースを「ここまでは成功」として扱いつつ、後続のパースを中断する。

#### 各コンビネータでの stopped の扱い

| コンビネータ | stopped の扱い | 結果 |
|-------------|---------------|------|
| **Chain** | 残りの子パーサーをスキップし、即座にコミットする | `succeeded`（部分成功） |
| **Choice** | `isSucceeded() == true` なので成功として採用される | `succeeded` |
| **Occurs**（ZeroOrMore, OneOrMore 等） | `failed` と同様にループを中断する。それまでの matchCount に基づいて成否が判定される | matchCount が min-max 範囲内なら `succeeded` |

#### 使用例

```
// SuggestsCollectorParser は Choice の最後のオプションとして配置される
Choice(CosParser, SinParser, SqrtParser, SuggestsCollectorParser)

// この Choice が Chain の子である場合:
Chain(PrefixParser, above_choice, SuffixParser)

// SuggestsCollectorParser がマッチすると:
// 1. Choice は SuggestsCollectorParser の成功を採用
// 2. SuggestsCollectorParser が結果を stopped に設定
// 3. Chain は stopped を受け取り、SuffixParser をスキップしてコミット
```

#### stopped の応用可能性

現在の実装では `SuggestsCollectorParser` が唯一の `stopped` 生成元だが、「成功＋後続中断」というセマンティクスは以下のようなパターンにも応用できる:

| パターン | 説明 | 例 |
|---------|------|-----|
| **エラーリカバリ/部分パース** | 入力が途中で切れたソースをパースする際、「ここまでは正しい」として Chain の残りをスキップし、部分的な構造情報（シンタックスハイライト等）を提供する | IDE で `if (x >` まで入力された状態で、`if` と `(x >` までのトークンを保全する |
| **ガード/センチネル** | Chain 内の子パーサーが「ここで終わるべき」というコンテキスト条件を検出した場合に後続をスキップする。failed にはしたくないが後続は不要なケース | 行末到達や特定キーワードの先読み成功をトリガーとする |
| **リソース制限/タイムアウト** | パースが時間やメモリの制約に近づいた際、部分結果をコミットしつつ打ち切る。failed だと全ロールバックされるが、stopped なら蓄積済みの結果が保全される | 巨大入力のパースで制限時間内に処理可能な範囲だけをコミットする |
| **ストリーミング/インクリメンタルパース** | 全入力がまだ到着していないストリームの逐次パース。「現時点の到着分は成功、ただし続きはまだ来ていない」を表現する | ネットワーク経由で分割到着するデータの段階的パース |
| **デバッグブレークポイント** | 特定のパーサー地点に到達したときに stopped を返すデバッグ用パーサーを Chain に挿入し、その時点のトークン状態をキャプチャする | パース途中の内部状態を観察するための計装 |

### 定数インスタンス

以下のシングルトンインスタンスが提供される:

- `Parsed.FAILED` — `Status.failed` のインスタンス
- `Parsed.STOPPED` — `Status.stopped` のインスタンス
- `Parsed.SUCCEEDED` — `Status.succeeded` のインスタンス

### 操作

| メソッド | 動作 |
|---------|------|
| `negate()` | succeeded/stopped → failed、failed → succeeded に反転した新しい `Parsed` を返す |
| `setMessage(String)` | メッセージを設定して自身を返す |
| `getMessage()` | ステータス名とメッセージを連結した文字列を返す |

### Committed 基底クラス

`Parsed` は `Committed` を継承する。`Committed` はコミット時に生成されたトークンと元のトークンリストを保持する:

- `token` — コミット時に `CollectingParser` が生成した集約トークン（存在する場合）
- `originalTokens` — コミット前の個別トークンリスト（`TokenList`）

---

## Cursor

**インタフェース**: `org.unlaxer.Cursor<T>`

### 位置モデル

Cursor はソース内の位置を **Unicode コードポイント単位** で追跡する。Java の `char` 単位ではなく、サロゲートペアを含む文字を正しく1文字として扱う。

### カーソル種別

| 種別 | インタフェース | 意味 |
|------|--------------|------|
| `startInclusive` | `Cursor.StartInclusiveCursor` | 範囲の開始位置（その位置を含む） |
| `endExclusive` | `Cursor.EndExclusiveCursor` | 範囲の終了位置（その位置を含まない） |

### 位置プロパティ

| メソッド | 戻り値 | 説明 |
|---------|--------|------|
| `position()` | `CodePointIndex` | 現在のソース内の位置 |
| `positionInSub()` | `CodePointIndex` | サブソース内の位置 |
| `positionInRoot()` | `CodePointIndex` | ルートソース内の絶対位置 |
| `lineNumber()` | `LineNumber` | 行番号 |
| `positionInLine()` | `CodePointIndexInLine` | 行内の位置 |
| `offsetFromRoot()` | `CodePointOffset` | ルートからのオフセット |

### 操作

| メソッド | 動作 |
|---------|------|
| `setPosition(CodePointIndex)` | 位置を設定する |
| `incrementPosition()` | 位置を1コードポイント進める |
| `addPosition(CodePointOffset)` | 位置をオフセット分進める |
| `newWithAddPosition(CodePointOffset)` | 新しいカーソルを位置加算して生成する |
| `copy()` | カーソルのコピーを生成する |

---

## Token

**クラス**: `org.unlaxer.Token`
**実装**: `Serializable`

### 概要

Token はパーサーが生成するトークンを表し、**木構造** を形成する。各トークンは生成元のパーサーへの参照とソーステキストを保持する。

### フィールド

| フィールド | 型 | 説明 |
|-----------|-----|------|
| `source` | `Source` | このトークンに対応するソーステキスト |
| `parser` | `Parser` | このトークンを生成したパーサー |
| `parent` | `Optional<Token>` | 親トークン |
| `originalChildren` | `TokenList` | 元の子トークンリスト（すべての子） |
| `filteredChildren` | `TokenList` | フィルタ済み子トークンリスト（AST ノードのみ） |
| `tokenKind` | `TokenKind` | トークンの種別 |

### 子トークンの2つのビュー

Token は2種類の子トークンリストを保持する:

1. **`original`**: パーサーが生成したすべての子トークン
2. **`astNodes`（filteredChildren）**: `NodeKind.node` タグを持つパーサーが生成した子トークンのみ

`ChildrenKind` enum でアクセスするビューを選択できる。

### 木構造の形成

- トークンが子トークンリストを受け取って生成されると、各子トークンの `parent` がこのトークンに設定される
- `flatten()` メソッドにより、木全体をフラットなリストとして走査できる

### 付随データ

| フィールド | 型 | 説明 |
|-----------|-----|------|
| `extraObjectByName` | `Map<Name, Object>` | 名前付きの追加オブジェクト |
| `relatedTokenByName` | `Map<Name, Token>` | 名前付きの関連トークン |

---

## TokenKind

**enum**: `org.unlaxer.TokenKind`

| 値 | 説明 |
|----|------|
| `consumed` | 入力を消費するトークン。カーソル位置が進む |
| `matchOnly` | マッチのみで入力を消費しない。先読み（lookahead）に使用 |
| `virtualTokenConsumed` | ソース上に存在しない仮想トークン（消費扱い） |
| `virtualTokenMatchOnly` | ソース上に存在しない仮想トークン（マッチのみ） |

### 判定メソッド

| メソッド | 条件 |
|---------|------|
| `isConsumed()` | `consumed` または `virtualTokenConsumed` |
| `isMatchOnly()` | `matchOnly` または `virtualTokenMatchOnly` |
| `isReal()` | `consumed` または `matchOnly` |
| `isVirtual()` | `virtualTokenConsumed` または `virtualTokenMatchOnly` |

### フィルタ

各 `TokenKind` 値は `passFilter` と `cutFilter` の `Predicate<Token>` を提供する:
- `passFilter`: 指定された TokenKind のトークンのみを通す
- `cutFilter`: 指定された TokenKind のトークンを除外する

---

## Source

**インタフェース**: `org.unlaxer.Source`
**継承**: `CodePointAccessor`, `PositionResolver`

### ソースカインド

| 値 | 説明 |
|----|------|
| `root` | ルートソース。パースの起点となるトップレベルの入力テキスト |
| `detached` | 独立したソース。ルートとの位置関係を持たない |
| `attached` | 親ソースに接続されたソース |
| `subSource` | 親ソースの部分範囲を表すサブソース |

### 制約

- `ParseContext` のコンストラクタは `SourceKind.root` のソースのみを受け付ける（MUST）
- `root` ソースの `offsetFromRoot()` は常に `CodePointOffset.ZERO` を返す（MUST）

### 主要メソッド

| メソッド | 戻り値 | 説明 |
|---------|--------|------|
| `sourceKind()` | `SourceKind` | ソースの種別 |
| `cursorRange()` | `CursorRange` | ソースの範囲をカーソルペアで返す |
| `linesAsSource()` | `Stream<Source>` | 行ごとのソースストリーム |
| `reRoot()` | `Source` | 新しいルートソースとして再生成する |
| `origin()` | `Optional<Origin>` | ルートソースとその中での範囲を返す |
| `offsetFromParent()` | `CodePointOffset` | 親ソースからのオフセット |
| `offsetFromRoot()` | `CodePointOffset` | ルートからのオフセット |

### StringSource

`StringSource` は `Source` の主要な実装クラスである。ファクトリメソッドとして `createRootSource(String)` を提供する。

---

## 現在の制限事項

- `Token` クラスの `tokenString` および `tokenRange` フィールドは `@Deprecated` マーク付きで、将来削除される可能性がある
- 一部の `Token` コンストラクタ（`position` 引数を取るもの）は `@Deprecated` マーク付き

## 変更履歴

- 2026-03-01: 初版作成
