# ParseContext 仕様

> ステータス: draft
> 最終更新: 2026-03-01

## スコープ

このドキュメントは `ParseContext` のライフサイクル、トランザクション（begin/commit/rollback）のセマンティクス、バックトラッキング保証、スコープツリーの仕様を定義する。

このドキュメントが **扱わない** 範囲:
- コアデータ型の詳細（→ [core-types.md](core-types.md)）
- デバッグリスナーの出力形式（→ [debug-system.md](debug-system.md)）

## 関連ドキュメント

- [core-types.md](core-types.md) — Parsed, Token, Cursor, Source の仕様
- [combinators.md](combinators.md) — 各コンビネータのトランザクション利用
- [debug-system.md](debug-system.md) — リスナーインタフェース

## 用語定義

- **TransactionElement**: トランザクションスタックの一要素。カーソル位置とトークンリストを保持する
- **ParseFrame**: パースフレーム。パーサーの入れ子呼び出しの深さと位置を追跡する

---

## ParseContext

**クラス**: `org.unlaxer.context.ParseContext`
**実装**: `Closeable`, `Transaction`, `ParserListenerContainer`, `GlobalScopeTree`, `ParserContextScopeTree`

### ライフサイクル

ParseContext はパース全体のライフサイクルを管理する。

#### 生成

```
ParseContext context = new ParseContext(source, effectors...);
```

- `source` は `SourceKind.root` でなければならない（MUST）。それ以外の場合 `IllegalArgumentException` がスローされる
- コンストラクタは以下を行う:
  1. ThreadLocal にコンテキストを設定
  2. 初期 `TransactionElement`（ルートカーソル付き）をトークンスタックにプッシュ
  3. `ParseContextEffector` の `effect()` を順次適用
  4. `onOpen()` コールバックを発火

#### クローズ

```
context.close();
```

- ThreadLocal をクリアする（MUST）
- トークンスタックのサイズが1でない場合、`IllegalStateException` をスローする（MUST）
- 未コミットのトランザクションが残っている状態でのクローズは不正状態を示す
- `onClose()` コールバックを発火

### 状態

| フィールド | 型 | 説明 |
|-----------|-----|------|
| `source` | `Source` | パース対象のルートソース |
| `tokenStack` | `Deque<TransactionElement>` | トランザクションスタック |
| `doMemoize` | `boolean` | メモ化フラグ（現在未完全実装） |
| `createMetaToken` | `boolean` | メタトークン生成フラグ（デフォルト: `true`） |
| `chosenParserByChoice` | `Map` | Choice コンビネータの選択結果キャッシュ |
| `orderedParsersByNonOrdered` | `Map` | NonOrdered コンビネータの順序決定キャッシュ |

### エラー診断用フィールド

| フィールド | 説明 |
|-----------|------|
| `farthestConsumedOffset` | パース中に到達した最も遠い消費位置 |
| `farthestMatchedOffset` | パース中に到達した最も遠いマッチ位置 |
| `maxReachedOffset` | パース中に到達した最大オフセット |
| `farthestFailureOffset` | 最も遠い失敗位置 |
| `maxReachedStackElements` | 最大到達位置でのパーサースタック要素 |
| `farthestFailureStackElements` | 最も遠い失敗位置でのパーサースタック要素 |
| `expectedParsersAtFarthestFailure` | 最も遠い失敗位置で期待されていたパーサー名 |
| `expectedHintCandidatesAtFarthestFailure` | 最も遠い失敗位置でのヒント候補 |

---

## トランザクション

**インタフェース**: `org.unlaxer.context.Transaction`

### 概要

Transaction はパーサーの状態変更を安全に管理するためのインタフェースである。すべてのコンビネータパーサーは begin → (commit | rollback) のトランザクションパターンに従う（MUST）。

### begin

```java
void begin(Parser parser)
```

- 現在の `TransactionElement` のコピーを新規作成してスタックにプッシュする
- 新しいトランザクション要素は現在のカーソル位置を引き継ぐ
- `onBegin()` リスナーコールバックを発火する

### commit

```java
Committed commit(Parser parser, TokenKind tokenKind, AdditionalCommitAction... actions)
```

コミットは以下の手順で実行される（MUST この順序に従う）:

1. **PreCommitAction の実行**: 登録された `AdditionalPreCommitAction` を順次実行する
2. **スタック操作**: 現在のトランザクション要素をスタックからポップする
3. **カーソル更新**: ポップした要素のカーソル位置を親要素に反映する
4. **トークンマージ**:
   - パーサーが `CollectingParser` の場合: 子トークンを集約した単一の `Token` を親要素に追加する
   - それ以外の場合: 子トークンリストをそのまま親要素に追加する
5. **onCommit() コールバック**: リスナーに通知する
6. **PostCommitAction の実行**: 登録された `AdditionalPostCommitAction` を順次実行する
7. **Committed オブジェクトを返す**

### rollback

```java
void rollback(Parser parser)
```

- 現在のトランザクション要素をスタックからポップする
- カーソル位置は **復元されない**（スタックのポップにより、前のトランザクション要素が現在に戻る）
- `ChoiceInterface` パーサーの場合、選択キャッシュをクリアする
- `NonOrdered` パーサーの場合、順序キャッシュをクリアする
- `onRollback()` リスナーコールバックを発火する

### トランザクションのネスト

トランザクションはネスト可能である。`tokenStack` は `Deque` として実装され、各 `begin()` でスタックが深くなり、`commit()` または `rollback()` で浅くなる。

**不変条件**:
- パースの開始時、スタックサイズは1（ルート要素のみ）（MUST）
- パースの終了時（`close()`）、スタックサイズは1でなければならない（MUST）
- すべての `begin()` は対応する `commit()` または `rollback()` とペアになる（MUST）

### バックトラッキング保証

コンビネータが `rollback()` を呼び出すと:
- カーソル位置は `begin()` 呼び出し時点に復元される
- ロールバックされたトランザクション内で生成されたトークンは破棄される
- 上位トランザクションの状態は影響を受けない

---

## スコープツリー

ParseContext は2種類のスコープツリーを提供する:

### GlobalScopeTree

```java
Map<Name, Object> globalScopeTreeMap
```

- パース全体で共有されるグローバルスコープ
- 名前をキーとして任意のオブジェクトを格納できる

### ParserContextScopeTree

```java
Map<Parser, Map<Name, Object>> scopeTreeMapByParser
```

- パーサーごとに独立したスコープ
- 特定のパーサーに関連付けられたデータを格納する

---

## AdditionalCommitAction

コミット時に追加のアクションを実行するためのインタフェース。

| インタフェース | タイミング | シグネチャ |
|--------------|-----------|-----------|
| `AdditionalPreCommitAction` | コミット前 | `effect(Parser, ParseContext)` |
| `AdditionalPostCommitAction` | コミット後 | `effect(Parser, ParseContext, Committed)` |

### ChoiceCommitAction

`ChoiceInterface` のコミット時に使用される特殊な `AdditionalCommitAction`。選択されたパーサーを `chosenParserByChoice` マップに記録する。

---

## 位置アクセスメソッド

| メソッド | 説明 |
|---------|------|
| `getConsumedPosition()` | 現在の消費位置を返す |
| `getMatchedPosition()` | 現在のマッチ位置を返す |
| `getPosition(TokenKind)` | 指定された TokenKind に応じた位置を返す |
| `getRemain(TokenKind)` | 未処理の残りソースを返す |
| `getConsumed(TokenKind)` | 消費済みソースを返す |
| `allMatched()` | マッチ位置がソース末尾に達しているかを返す |
| `allConsumed()` | 消費位置がソース末尾に達しているかを返す |

---

## 現在の制限事項

- `chosenParserByChoice` と `orderedParsersByNonOrdered` は ScopeTree に移行予定（ソース内 FIXME コメントあり）
- メモ化機能（`doMemoize`）は完全には実装されていない

---

## 設計上の既知問題: ChainInterface と TransactionListener の自己呼び出し欠如

### 問題の概要

`ChainInterface.parse()` は `parseContext.begin(this)` / `parseContext.commit(this, tokenKind)` を呼ぶが、
これは `TransactionListenerContainer.onBegin/onCommit/onRollback` を通じて **`listenerByName` に登録されたリスナーのみ** に転送される。

UBNF のコード生成器（`LSPGenerator`、`ParserGenerator`）が生成するパーサークラスは
`@declares` / `@backref` / `@scopeTree` の実装として `TransactionListener` を実装しているが、
これらのパーサーは `listenerByName` に **自動登録されない**。

その結果、生成パーサーの `onBegin` / `onCommit` / `onRollback` はフレームワーク内部では **一度も呼ばれない**。
これにより `ScopeStore.declare()` 等が実行されず、LSP の go-to-definition や補完に必要なシンボル情報が蓄積されないという問題が生じる。

### 根本修正案（unlaxer-common 側）

`ChainInterface.parse()` 内で以下の自己呼び出しを追加することで解決できる:

```java
// begin 直後
if (this instanceof TransactionListener tl) {
    tl.onBegin(parseContext, this);
}

// commit 直後
if (this instanceof TransactionListener tl) {
    tl.onCommit(parseContext, this, committedTokens);
}

// rollback 直後
if (this instanceof TransactionListener tl) {
    tl.onRollback(parseContext, this, rollbackedTokens);
}
```

**注意**: パーサーが `listenerByName` にも登録されている場合、この変更により二重呼び出しが発生する。
既存の利用パターンを調査した上で適用すること（SHOULD）。

### 現在の回避策（unlaxer-dsl 側）

`ScopeStore.registerDispatcher(ParseContext ctx)` を `parseDocument()` 実行前に呼ぶことで、
グローバルリスナーとしてディスパッチャーを登録し、生成パーサーへイベントを転送している。

`LSPGenerator` は `@declares` / `@backref` / `@scopeTree` を持つ文法に対して
`parseDocument()` 内でこの登録コードを自動生成する（unlaxer-dsl 2.7.0 以降）。

## 変更履歴

- 2026-03-20: ChainInterface/TransactionListener 設計問題と回避策を追記
- 2026-03-01: 初版作成
