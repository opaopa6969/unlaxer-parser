# コンビネータ仕様

> ステータス: draft
> 最終更新: 2026-03-01

## スコープ

このドキュメントはすべてのコンビネータパーサーの入出力契約およびバックトラッキング動作を定義する。

このドキュメントが **扱わない** 範囲:
- 端末パーサー（→ [terminal-parsers.md](terminal-parsers.md)）
- AST フィルタリング（→ [ast-filtering.md](ast-filtering.md)）

## 関連ドキュメント

- [core-types.md](core-types.md) — Parsed, Token, TokenKind
- [parse-context.md](parse-context.md) — トランザクションモデル
- [ast-filtering.md](ast-filtering.md) — ASTNode, ASTNodeRecursive

## 用語定義

- **Constructed**: コンストラクタで子パーサーを受け取る即時構築型
- **Lazy**: `getLazyParsers()` で子パーサーを遅延生成する型（循環参照を許容）

---

## 共通パターン

すべてのコンビネータは以下のパターンに従う（MUST）:

1. `parseContext.startParse(this, ...)` でパース開始を通知
2. `parseContext.begin(this)` でトランザクションを開始
3. 子パーサーを呼び出す
4. 成功時: `parseContext.commit(this, tokenKind)` でコミット
5. 失敗時: `parseContext.rollback(this)` でロールバック
6. `parseContext.endParse(this, result, ...)` でパース終了を通知

---

## Chain / LazyChain

**クラス**: `org.unlaxer.parser.combinator.Chain` / `LazyChain`
**インタフェース**: `ChainInterface`
**RELAX NG 相当**: `<group>`

### セマンティクス

子パーサーを **順序通り** にすべて適用する。

### アルゴリズム

```
begin()
for each child in children:
    result = child.parse()
    if result == stopped:
        break              // 残りの子をスキップしてコミット
    if result == failed:
        rollback()
        return FAILED
commit()
return succeeded
```

### 入出力契約

| 条件 | 結果 |
|------|------|
| すべての子が succeeded | `succeeded` |
| いずれかの子が stopped | 残りの子をスキップし `succeeded`（コミットされる） |
| いずれかの子が failed | `failed`（ロールバックされる） |

### バックトラッキング

- いずれかの子が `failed` を返した場合、それ以前の子のトークンを含めてすべてロールバックする（MUST）
- カーソル位置は Chain の `begin()` 呼び出し時点に復元される

### matchedWithConsumed リセット

Chain は `parseContext.getCurrent().setResetMatchedWithConsumed(false)` を呼び出す。これにより、consumed カーソルと matched カーソルが独立して追跡される。

---

## Choice / LazyChoice

**クラス**: `org.unlaxer.parser.combinator.Choice` / `LazyChoice`
**インタフェース**: `ChoiceInterface`
**RELAX NG 相当**: `<choice>`

### セマンティクス

子パーサーを順番に試行し、最初に成功したものを採用する。

### アルゴリズム

```
for each child in children:
    begin()
    result = child.parse()
    if result == succeeded:
        commit(ChoiceCommitAction(child))
        return succeeded
    rollback()
return FAILED
```

### 入出力契約

| 条件 | 結果 |
|------|------|
| いずれかの子が succeeded | `succeeded`（最初に成功した子を採用） |
| すべての子が failed | `failed` |

### バックトラッキング

- 各子パーサーは個別のトランザクションで実行される（MUST）
- 子パーサーが失敗した場合、その子の消費はロールバックされ、次の子が同じ位置から試行される
- 成功時、`ChoiceCommitAction` により選択されたパーサーが `chosenParserByChoice` マップに記録される

### ASTNodeKind

`Choice` のデフォルトコンストラクタは `ASTNodeKind.ChoicedOperator` タグを設定する。

---

## ZeroOrMore / LazyZeroOrMore

**クラス**: `org.unlaxer.parser.combinator.ZeroOrMore` / `LazyZeroOrMore`
**基底**: `ChildOccursWithTerminator` → `Occurs` インタフェース
**RELAX NG 相当**: `<zeroOrMore>`

### セマンティクス

子パーサーを **0回以上** 繰り返し適用する。

### パラメータ

- `min() = 0`
- `max() = Integer.MAX_VALUE`

### 入出力契約

| 条件 | 結果 |
|------|------|
| 0回マッチ | `succeeded`（空のトークンリスト） |
| N回マッチ（N ≥ 1） | `succeeded` |

---

## OneOrMore / LazyOneOrMore

**クラス**: `org.unlaxer.parser.combinator.OneOrMore` / `LazyOneOrMore`
**基底**: `ChildOccursWithTerminator` → `Occurs` インタフェース
**RELAX NG 相当**: `<oneOrMore>`

### セマンティクス

子パーサーを **1回以上** 繰り返し適用する。

### パラメータ

- `min() = 1`
- `max() = Integer.MAX_VALUE`

### 入出力契約

| 条件 | 結果 |
|------|------|
| 0回マッチ | `failed` |
| N回マッチ（N ≥ 1） | `succeeded` |

---

## Optional / LazyOptional

**クラス**: `org.unlaxer.parser.combinator.Optional` / `LazyOptional`
**基底**: `ChildOccursWithTerminator` → `Occurs` インタフェース
**RELAX NG 相当**: `<optional>`

### セマンティクス

子パーサーを **0回または1回** 適用する。

### パラメータ

- `min() = 0`
- `max() = 1`

### 入出力契約

| 条件 | 結果 |
|------|------|
| マッチしない | `succeeded`（空のトークンリスト） |
| 1回マッチ | `succeeded` |

---

## Occurs（共通繰り返しインタフェース）

**インタフェース**: `org.unlaxer.parser.combinator.Occurs`

ZeroOrMore, OneOrMore, Optional, Repeat の共通動作を定義する。

### アルゴリズム

```
begin()
matchCount = 0
while true:
    if terminator exists:
        begin()
        terminatorResult = terminator.parse()
        if terminatorResult == succeeded:
            commit(terminator) // consumed の場合のみ
            break
        else:
            rollback()

    childResult = child.parse()
    if childResult == failed or stopped:
        break

    matchCount++
    if position unchanged:  // 無限ループ防止
        break
    if matchCount >= max:
        break

if min <= matchCount <= max:
    commit()
    return succeeded
else:
    rollback()
    return FAILED
```

### ターミネータ

`ChildOccursWithTerminator` はオプションのターミネータパーサーを持つ。ターミネータが成功すると繰り返しを終了する。

### 無限ループ防止

子パーサーが成功してもカーソル位置が変わらない場合（空マッチ）、ループを中断する（MUST）。

---

## Repeat / LazyRepeat

**クラス**: `org.unlaxer.parser.combinator.Repeat` / `LazyRepeat`
**基底**: `ChildOccursWithTerminator` → `Occurs` インタフェース

### セマンティクス

子パーサーを **指定回数の範囲** で繰り返し適用する。

### パラメータ

- `min() = minInclusive`（コンストラクタで指定）
- `max() = maxInclusive`（コンストラクタで指定）

### 入出力契約

| 条件 | 結果 |
|------|------|
| matchCount < minInclusive | `failed` |
| minInclusive <= matchCount <= maxInclusive | `succeeded` |

---

## NonOrdered

**クラス**: `org.unlaxer.parser.combinator.NonOrdered`
**RELAX NG 相当**: `<interleave>`

### セマンティクス

すべての子パーサーを **任意の順序** で適用する。各子パーサーは正確に1回成功しなければならない。

### アルゴリズム

```
begin()
remain = children.size
consumed[] = new boolean[size]
while remain != 0:
    start = remain
    for i = 0 to size-1:
        if consumed[i]: continue
        result = children[i].parse()
        if result == succeeded:
            remain--
            consumed[i] = true
    if remain == start:  // 1つも進まなかった
        rollback()
        return FAILED
commit()
return succeeded
```

### 入出力契約

| 条件 | 結果 |
|------|------|
| すべての子が任意の順序で成功 | `succeeded` |
| いずれかの子が成功しない | `failed` |

### バックトラッキング

- 1ラウンドで1つも子パーサーが成功しなかった場合、ロールバックする
- コミット時に `AdditionalPreCommitAction` で決定された順序を `orderedParsersByNonOrdered` に記録する

---

## Not

**クラス**: `org.unlaxer.parser.combinator.Not`

### セマンティクス

子パーサーを先読み（matchOnly）で実行し、結果を **反転** する。入力は消費しない。

### アルゴリズム

```
begin()
result = child.parse(TokenKind.matchOnly)
if result == succeeded:
    rollback()
    return FAILED
commit(TokenKind.matchOnly)
return succeeded
```

### 入出力契約

| 子パーサー結果 | Not の結果 | カーソル位置 |
|--------------|-----------|-------------|
| succeeded | `failed` | 変化しない |
| failed | `succeeded` | 変化しない |

### 動作の詳細

- 子パーサーは常に `TokenKind.matchOnly` で実行される（MUST）。入力を消費しない
- トランザクション（`begin()`/`commit()`/`rollback()`）により状態が保護される
- Not 自体もトークンを消費しない（`TokenKind.matchOnly` でコミット）

---

## Flatten

**クラス**: `org.unlaxer.parser.combinator.Flatten`
**継承**: `Chain`

### セマンティクス

子パーサーの子パーサーリストを **展開（フラット化）** して Chain として実行する。

### 動作

コンストラクタで `child.getChildren()` を取得し、その子パーサーリストを自身の子パーサーとする。実行時の動作は Chain と同一。

---

## MatchOnly

**クラス**: `org.unlaxer.parser.combinator.MatchOnly`
**実装**: `MetaFunctionParser`

### セマンティクス

子パーサーを実行するが、入力を **消費しない**（先読み）。

### アルゴリズム

```
begin()
result = child.parse(TokenKind.matchOnly)
if result == failed:
    rollback()
    return FAILED
commit(TokenKind.matchOnly)
return succeeded
```

### 入出力契約

| 条件 | 結果 | カーソル位置 |
|------|------|-------------|
| 子が成功 | `succeeded` | 変化しない |
| 子が失敗 | `failed` | 変化しない |

### TokenKind

- `getTokenKind()` は常に `TokenKind.matchOnly` を返す（MUST）
- 子パーサーに `TokenKind.matchOnly` を渡す（MUST）

---

## ASTNode / ASTNodeRecursive

AST フィルタリング用のラッパーコンビネータ。詳細は [ast-filtering.md](ast-filtering.md) を参照。

---

## Lazy 系バリアント

各コンビネータに対応する Lazy バリアントが存在する:

| Constructed | Lazy |
|------------|------|
| `Chain` | `LazyChain` |
| `Choice` | `LazyChoice` |
| `ZeroOrMore` | `LazyZeroOrMore` |
| `OneOrMore` | `LazyOneOrMore` |
| `Optional` | `LazyOptional` |
| `Repeat` | `LazyRepeat` |
| `ZeroOrOne` | `LazyZeroOrOne` |
| `Zero` | `LazyZero` |

### Lazy の特徴

- `getLazyParsers()` メソッドをオーバーライドして子パーサーを返す
- 子パーサーの生成を初回呼び出しまで遅延する
- **循環参照** を許容する（再帰的な文法定義が可能）
- パース動作は Constructed バリアントと同一（MUST）

---

## 現在の制限事項

- `NonOrdered` は各子パーサーの失敗時にロールバックを行わず、バックトラッキングの粒度が粗い

## 変更履歴

- 2026-03-01: 初版作成
