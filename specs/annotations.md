# アノテーション仕様

> ステータス: draft
> 最終更新: 2026-03-01

## スコープ

このドキュメントは UBNF 文法で使用可能なすべてのアノテーションの完全仕様を定義する。各アノテーションの契約、バリデーションルール、エラーコード、実装状況を含む。

このドキュメントは既存の `SPEC.md` のアノテーション節を拡充したものである。

このドキュメントが **扱わない** 範囲:
- UBNF 構文仕様（→ [ubnf-syntax.md](ubnf-syntax.md)）
- バリデーションエラーコード一覧（→ [validation.md](validation.md)）

## 関連ドキュメント

- [ubnf-syntax.md](ubnf-syntax.md) — アノテーションの構文
- [validation.md](validation.md) — エラーコード詳細
- [generators.md](generators.md) — アノテーションが生成コードに与える影響

---

## @root

### 構文

```
@root
RuleName ::= body ;
```

### セマンティクス

- 文法のエントリーポイント（ルートルール）を宣言する
- 1つの grammar ブロック内で最大1つのルールに付与すべき（SHOULD）
- パーサージェネレータはルートルールを起点としたパース構造を生成する

### 実装状況

完全実装済み。

---

## @mapping(ClassName, params=[...])

### 構文

```
@mapping(ClassName)
@mapping(ClassName, params=[param1, param2, ...])
```

### セマンティクス

- ルールに対応する AST ノード型を宣言する
- `ClassName` は生成される record クラス名
- `params` はキャプチャ名のリスト。record のフィールドに対応する

### 契約

1. `params` 内のすべてのパラメータ名は、同じルール本体内のキャプチャ名（`@name`）に一致する（MUST）
2. ルール本体内のすべてのキャプチャ名は `params` に列挙される（MUST）
3. 重複するパラメータ名は不正（MUST NOT）

### バリデーション

| エラーコード | 条件 |
|------------|------|
| `E-MAPPING-MISSING-CAPTURE` | `params` に記載されたキャプチャ名がルール本体に存在しない |
| `E-MAPPING-EXTRA-CAPTURE` | ルール本体のキャプチャ名が `params` に含まれていない |
| `E-MAPPING-DUPLICATE-PARAM` | `params` に重複するパラメータ名がある |

### 実装状況

完全実装済み。`GrammarValidator.validateOrThrow` で検証。

---

## @leftAssoc

### 構文

```
@leftAssoc
RuleName ::= body ;
```

### セマンティクス

- ルールが左結合演算子であることを宣言する
- `@precedence` と組み合わせて使用する（MUST）
- `@rightAssoc` と同一ルールに共存できない（MUST NOT）

### 契約

1. `@leftAssoc` を使用するルールは `@precedence` も宣言する（MUST）
2. `@rightAssoc` と同一ルールに使用できない（MUST NOT）
3. 同一優先度レベルで `@leftAssoc` と `@rightAssoc` を混在させてはならない（MUST NOT）

### 実装状況

契約バリデーション済みメタデータ。パーサー生成での直接的な消費は未実装。

---

## @rightAssoc

### 構文

```
@rightAssoc
RuleName ::= body ;
```

### セマンティクス

- ルールが右結合演算子であることを宣言する
- 正規形 `Base { Op Self }` は右再帰 Choice（`Base Op Self | Base`）として生成される
- 非正規形はバリデーターにより拒否される
- マッパージェネレータは `foldRightAssoc<Class>` ヘルパーを生成する

### 契約

`@leftAssoc` と同じ契約に加えて:

1. 正規形のルール構造に従う（MUST）
2. `@leftAssoc` と同一ルールに使用できない（MUST NOT）

### 実装状況

部分的にパーサー生成で消費。マッパーヘルパー生成済み。

---

## @precedence(level=...)

### 構文

```
@precedence(level=N)
```

### セマンティクス

- ルールの演算子優先度レベルを宣言する
- `level` は0以上の整数（MUST）
- 数値が小さいほど低い優先度（結合が遅い）

### 契約

1. `level` は0以上の整数（MUST）
2. 1つのルールに最大1回宣言可能（MUST）
3. `@precedence` は `@leftAssoc` または `@rightAssoc` を伴う（MUST）
4. `@leftAssoc` / `@rightAssoc` ルールは `@precedence` を宣言する（MUST）
5. 演算子ルールが他の演算子ルールを参照する場合、参照先はより高い優先度レベルを持つ（MUST）
6. 同一優先度レベルで左結合と右結合を混在させない（MUST NOT）

### 生成コードへの影響

パーサージェネレータは以下を生成する:
- `PRECEDENCE_<RULE_NAME>` 定数
- `getPrecedence(ruleName)` / `getAssociativity(ruleName)` メソッド
- `getOperatorSpecs()` — ソート済み演算子仕様リスト
- `getOperatorSpec(ruleName)` / `isOperatorRule(ruleName)` — ルックアップヘルパー
- `getNextHigherPrecedence(ruleName)` — 優先度ステップヘルパー
- `getOperatorParser(ruleName)` / `getLowestPrecedenceOperator()` / `getLowestPrecedenceParser()` — パーサー解決ヘルパー
- `getPrecedenceLevels()` / `getOperatorsAtPrecedence(level)` / `getOperatorParsersAtPrecedence(level)` — 優先度レベルヘルパー

### 実装状況

完全実装済み。

---

## @whitespace

### 構文

```
// グローバル設定
@whitespace: javaStyle

// ルールレベル
@whitespace
@whitespace(none)
@whitespace(javaStyle)
```

### セマンティクス

- グローバル `@whitespace` は生成パーサーのデリミタ挿入を制御する
- ルールレベル `@whitespace` はグローバル設定をオーバーライドする
  - `@whitespace(none)` — 自動デリミタを無効化
  - `@whitespace` または `@whitespace(javaStyle)` — 自動デリミタを有効化

### 実装状況

完全実装済み。

---

## @interleave(profile=...)

### 構文

```
@interleave(profile=javaStyle)
@interleave(profile=commentsAndSpaces)
```

### セマンティクス

- ルールのインターリーブポリシーを宣言する
- Parser IR アダプターおよび下流ツーリング向けのメタデータ

### 契約

1. 1つのルールに最大1回（MUST）
2. `profile` は `javaStyle` または `commentsAndSpaces`（MUST）

### 実装状況

メタデータとして受理。パーサー動作は未変更。

---

## @backref(name=...)

### 構文

```
@backref(name=symbolName)
```

### セマンティクス

- 後方参照のインテントを宣言する
- セマンティック制約および診断向けのメタデータ

### 契約

1. 1つのルールに最大1回（MUST）

### 実装状況

メタデータとして受理。パーサー動作は未変更。

---

## @scopeTree(mode=...)

### 構文

```
@scopeTree(mode=lexical)
@scopeTree(mode=dynamic)
```

### セマンティクス

- スコープツリー処理のモードを宣言する
- シンボル/ツーリングフェーズ向けのメタデータ

### 契約

1. 1つのルールに最大1回（MUST）
2. `mode` は `lexical` または `dynamic`（MUST）

### 生成コードへの影響

パーサージェネレータは以下を生成する:
- `getScopeTreeMode(ruleName)` / `getScopeTreeModeEnum(ruleName)` — モード取得
- `getScopeTreeRules()` — スコープツリー対象ルール一覧
- `getScopeTreeModeByRule()` — ルール→モードのマップ
- `getScopeTreeSpec(ruleName)` / `getScopeTreeSpecs()` — スコープツリー仕様

### Parser IR への影響

`ParserIrScopeEvents.emitSyntheticEnterLeaveEventsForRules()` によりルールレベルのスコープメタデータが Parser IR の `scopeEvents` に変換される。

### 実装状況

メタデータとして受理。パーサー生成でスコープメタデータ API を出力済み。パーサー動作は未変更。

---

## SimpleAnnotation

### 構文

```
@annotationName
```

### セマンティクス

- 上記の特定アノテーションに該当しない汎用アノテーション
- メタデータとして保持される

---

## 現在の制限事項

- `@interleave`, `@backref`, `@scopeTree` はメタデータのみで、パーサーの認識動作には影響しない
- `@leftAssoc` はバリデーション対象だが、パーサー生成での直接的な消費は限定的

## 変更履歴

- 2026-03-01: 既存 SPEC.md から移動・拡充
