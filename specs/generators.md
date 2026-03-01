# ジェネレータ仕様

> ステータス: draft
> 最終更新: 2026-03-01

## スコープ

このドキュメントは8つのコードジェネレータの入力・出力・生成 API 面・命名規則を定義する。

このドキュメントが **扱わない** 範囲:
- CLI オプション（→ [cli.md](cli.md)）
- バリデーション（→ [validation.md](validation.md)）

## 関連ドキュメント

- [overview.md](overview.md) — ジェネレータ一覧
- [annotations.md](annotations.md) — アノテーションがジェネレータに与える影響
- [cli.md](cli.md) — `--generators` オプション

---

## 共通インタフェース

**インタフェース**: `org.unlaxer.dsl.codegen.CodeGenerator`

すべてのジェネレータは `CodeGenerator` インタフェースを実装する。

```java
public interface CodeGenerator {
    GeneratedSource generate(GrammarDecl grammar);
}
```

### 入力

- `GrammarDecl`: バリデーション済みの UBNF 文法 AST

### 出力

- `GeneratedSource`: 生成された Java ソースコード（クラス名、パッケージ名、ソーステキスト）

### 命名規則

生成されるクラス名は以下のパターンに従う（MUST）:

```
{GrammarName}{GeneratorSuffix}.java
```

---

## ParserGenerator

**クラス**: `org.unlaxer.dsl.codegen.ParserGenerator`

### 出力

`{GrammarName}Parsers.java`

### 生成内容

- 各ルールに対応するパーサーインナークラス
- unlaxer-common の `LazyChain`, `LazyChoice`, `ZeroOrMore` 等を使用
- `@whitespace` 設定に基づくスペースデリミタ自動挿入（`WhiteSpaceDelimitedLazyChain`）
- `@precedence` / `@leftAssoc` / `@rightAssoc` に基づく演算子メタデータ API
- `@scopeTree` に基づくスコープツリーメタデータ API
- `@rightAssoc` ルールの右再帰 Choice 構造

### 演算子メタデータ API（@precedence 使用時）

- `PRECEDENCE_{RULE_NAME}` 定数
- `getPrecedence(String)`, `getAssociativity(String)`
- `getOperatorSpecs()`, `getOperatorSpec(String)`, `isOperatorRule(String)`
- `getNextHigherPrecedence(String)`
- `getOperatorParser(String)`, `getLowestPrecedenceOperator()`, `getLowestPrecedenceParser()`
- `getPrecedenceLevels()`, `getOperatorsAtPrecedence(int)`, `getOperatorParsersAtPrecedence(int)`

---

## ASTGenerator

**クラス**: `org.unlaxer.dsl.codegen.ASTGenerator`

### 出力

`{GrammarName}AST.java`

### 生成内容

- sealed interface として AST のルートインタフェース
- `@mapping` 付きルールごとに record クラスを内部型として生成
- record のフィールドは `@mapping` の `params` に対応
- 繰り返しキャプチャ（`{ ... @name }` で同じ名前が複数回）は `List<T>` 型
- 省略可能キャプチャは `Optional<T>` 型

---

## MapperGenerator

**クラス**: `org.unlaxer.dsl.codegen.MapperGenerator`

### 出力

`{GrammarName}Mapper.java`

### 生成内容

- Token 木から AST（record）へのマッピングメソッド群
- 各 `@mapping` ルールに対応する `mapXxx(Token)` メソッド
- `@rightAssoc` ルール用の `foldRightAssoc{ClassName}` ヘルパースケルトン

---

## EvaluatorGenerator

**クラス**: `org.unlaxer.dsl.codegen.EvaluatorGenerator`

### 出力

`{GrammarName}Evaluator.java`

### 生成内容

- AST を評価するスケルトンクラス
- 各 AST ノード型に対応する `evaluate(XxxNode)` メソッドスケルトン

---

## LSPGenerator

**クラス**: `org.unlaxer.dsl.codegen.LSPGenerator`

### 出力

`{GrammarName}LSP.java`

### 生成内容

- Language Server Protocol サーバー実装
- 文法固有の診断、ホバー、補完を提供

---

## LSPLauncherGenerator

**クラス**: `org.unlaxer.dsl.codegen.LSPLauncherGenerator`

### 出力

`{GrammarName}LSPLauncher.java`

### 生成内容

- LSP サーバーの起動クラス（`main` メソッド）

---

## DAPGenerator

**クラス**: `org.unlaxer.dsl.codegen.DAPGenerator`

### 出力

`{GrammarName}DAP.java`

### 生成内容

- Debug Adapter Protocol サーバー実装
- パーストークンストリームに基づくブレークポイント・ステッピング

---

## DAPLauncherGenerator

**クラス**: `org.unlaxer.dsl.codegen.DAPLauncherGenerator`

### 出力

`{GrammarName}DAPLauncher.java`

### 生成内容

- DAP サーバーの起動クラス（`main` メソッド）

---

## パッケージ名の決定

生成コードのパッケージ名は、文法のグローバル設定 `@package` から決定される:

```
@package: org.example.generated
```

`@package` が未指定の場合のデフォルト動作は実装依存。

---

## 現在の制限事項

- Evaluator ジェネレータはスケルトンのみ生成し、評価ロジックはユーザーが実装する必要がある
- LSP / DAP ジェネレータの生成コードは限定的な機能セットを提供する

## 変更履歴

- 2026-03-01: 初版作成
