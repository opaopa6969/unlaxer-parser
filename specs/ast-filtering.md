# AST フィルタリング仕様

> ステータス: draft
> 最終更新: 2026-03-01

## スコープ

このドキュメントは Token 木から AST（抽象構文木）への射影メカニズムを定義する。ASTNode / ASTNodeRecursive ラッパーによるノードマーキングと、Token の filteredChildren によるフィルタリングを含む。

このドキュメントが **扱わない** 範囲:
- Token の基本構造（→ [core-types.md](core-types.md)）
- コンビネータの動作（→ [combinators.md](combinators.md)）

## 関連ドキュメント

- [core-types.md](core-types.md) — Token, TokenKind
- [combinators.md](combinators.md) — ASTNode, ASTNodeRecursive の位置づけ

## 用語定義

- **Token 木**: パーサーが生成するすべてのトークンからなる完全な木構造
- **AST**: Token 木から NodeKind.node タグを持つノードのみを射影した部分木
- **射影**: Token.filteredChildren によるフィルタリング操作

---

## NodeKind

**enum**: `org.unlaxer.reducer.TagBasedReducer.NodeKind`

| 値 | Tag | 意味 |
|----|-----|------|
| `node` | `Tag.of(NodeKind.node)` | AST に含まれるノード |
| `notNode` | `Tag.of(NodeKind.notNode)` | AST から除外されるノード |

### デフォルト動作

- パーサーに `NodeKind` タグが設定されていない場合、そのパーサーは **AST に含まれる** 扱いとなる
- `TagBasedReducer.doReduce()` は `notNode` タグを持つパーサーに対して `true` を返す
- Token の AST フィルタリング（`AST_NODES` Predicate）は `notNode` タグを持た **ない** トークンを通す

```java
Predicate<Token> AST_NODES = token -> false == token.parser.hasTag(NodeKind.notNode.getTag());
```

---

## ASTNode

**クラス**: `org.unlaxer.parser.combinator.ASTNode`
**継承**: `TagWrapper`

### セマンティクス

子パーサーに `NodeKind.node` タグを **追加** する。このラッパーで囲まれたパーサーが生成するトークンは AST に含まれる。

### 動作

- `getTag()` → `NodeKind.node.getTag()`
- `getAction()` → `TagWrapperAction.add`
- `getThisParser()` → 子パーサーを返す（タグは子パーサーに設定される）

### 使用例

```java
// ExpressionParser のトークンを AST ノードとしてマーク
new ASTNode(new ExpressionParser())
```

---

## ASTNodeRecursive

**クラス**: `org.unlaxer.parser.combinator.ASTNodeRecursive`
**継承**: `RecursiveTagWrapper`

### セマンティクス

子パーサーとその **すべての子孫パーサー** に対して `NodeKind.node` タグを **再帰的に追加** する。

### 動作

- `getTag()` → `NodeKind.node.getTag()`
- `getAction()` → `TagWrapperAction.add`
- `getRecursiveMode()` → `RecursiveMode.containsRoot`（自身を含む再帰）

### ASTNode との違い

| 特性 | ASTNode | ASTNodeRecursive |
|------|---------|-----------------|
| タグ設定範囲 | 子パーサーのみ | 子パーサーとすべての子孫 |
| 用途 | 特定のパーサーのみ AST に含める | サブツリー全体を AST に含める |

---

## ASTNodeRecursiveGrandChildren

**クラス**: `org.unlaxer.parser.combinator.ASTNodeRecursiveGrandChildren`

### セマンティクス

`ASTNodeRecursive` と同様だが、`RecursiveMode.excludeRoot` を使用し、自身は含まず **孫以降の子孫パーサー** にのみタグを設定する。

---

## NotASTNode / NotASTNodeRecursive / NotASTNodeRecursiveGrandChildren

AST からの **除外** を行うラッパー。

| クラス | 動作 |
|--------|------|
| `NotASTNode` | 子パーサーに `NodeKind.notNode` タグを追加 |
| `NotASTNodeRecursive` | 子パーサーとすべての子孫に `NodeKind.notNode` タグを再帰的に追加 |
| `NotASTNodeRecursiveGrandChildren` | 孫以降の子孫に `NodeKind.notNode` タグを再帰的に追加 |

---

## Token の2つのビュー

Token は2つの子トークンリストを保持する（[core-types.md](core-types.md) 参照）:

### originalChildren

パーサーが生成したすべての子トークンのリスト。構文解析の完全な結果を表す。

### filteredChildren（AST ノード）

`AST_NODES` Predicate によりフィルタされた子トークンリスト。`notNode` タグを持たないパーサーが生成したトークンのみを含む。

### アクセス

```java
// Token 木の全子トークン
token.getChildren(ChildrenKind.original)

// AST ノードのみ
token.getChildren(ChildrenKind.astNodes)
token.getChildFromAstNodes(index)
```

---

## TagBasedReducer

**クラス**: `org.unlaxer.reducer.TagBasedReducer`

Token 木を走査し、`notNode` タグを持つパーサーのトークンを除去する縮約器。

### doReduce 判定

```java
public boolean doReduce(Parser parser) {
    return parser.hasTag(NodeKind.notNode.getTag());
}
```

---

## 射影の流れ

1. パーサーに `ASTNode` / `NotASTNode` 等のラッパーでタグを設定する
2. パース実行により Token 木が生成される
3. Token のコンストラクタで `AST_NODES` Predicate により `filteredChildren` が計算される
4. `filteredChildren` を通じて AST にアクセスする

---

## 現在の制限事項

- `filteredChildren` はトークン生成時に計算され、後からの変更は反映されない
- `AST_NODES` Predicate のコメントに「TODO too specialized」とあり、フィルタリングロジックの一般化が検討されている

## 変更履歴

- 2026-03-01: 初版作成
