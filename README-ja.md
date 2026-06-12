[English](./README.md) | [日本語](./README-ja.md)

---

```
                _
  _   _ _ __   | | __ ___  _____ _ __
 | | | | '_ \  | |/ _` \ \/ / _ \ '__|
 | |_| | | | | | | (_| |>  <  __/ |
  \__,_|_| |_| |_|\__,_/_/\_\___|_|
                              - parser
```

# unlaxer-parser

**文法を書くだけで言語が手に入る — Parser + AST + Evaluator + LSP + DAP をすべて自動生成**

[![Maven Central](https://img.shields.io/maven-central/v/org.unlaxer/unlaxer-common)](https://central.sonatype.com/artifact/org.unlaxer/unlaxer-common)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](./LICENSE)
[![Java 21+](https://img.shields.io/badge/Java-21%2B-orange.svg)]()
[![Version](https://img.shields.io/badge/version-3.0.4-blue)]()

---

> **バージョン通知 — 3.0.4**: このパッチリリースは、3.0.3 で `@scopeTree`/`@declares`/`@backref` のスコープ機能（LSP definition / linked editing / hover-set / completion）が静かに壊れていたリグレッションを修正します。tinyexpression の downstream 検証で発見されました。**3.0.2 は Maven Central に公開されていません** — 3.0.1 からは 3.0.3 または 3.0.4 へ直接アップグレードしてください。`unlaxer-common` または `unlaxer-dsl` の `2.x` に依存している場合は、[CHANGELOG](./CHANGELOG.md) と下記の[downstream ドリフト警告](#downstream-ドリフト警告)を参照してください。

---

## 目次

- [課題](#課題)
- [解決策](#解決策)
- [クイックサンプル](#クイックサンプル)
- [生成されるもの](#生成されるもの)
- [5分クイックスタート](#5分クイックスタート)
- [アーキテクチャ](#アーキテクチャ)
- [Bootstrap と自己ホスティング](#bootstrap-と自己ホスティング)
- [実例](#実例)
- [Downstream ドリフト警告](#downstream-ドリフト警告)
- [ドキュメント](#ドキュメント)
- [なぜ unlaxer なのか？](#なぜ-unlaxer-なのか)
- [プロジェクト構成](#プロジェクト構成)
- [foundation-poisonpills について](#foundation-poisonpills-について)
- [ライセンス](#ライセンス)

---

## 課題

DSL を構築するには、通常 **6つ以上のサブシステム** を手作業で書き、保守する必要があります：

| サブシステム | 行数（概算） |
|-----------|----------------|
| レキサー / パーサー | 2,000+ |
| AST ノード型 | 1,500+ |
| パースツリーから AST へのマッパー | 1,000+ |
| エバリュエータ / インタプリタ | 2,000+ |
| LSP サーバー（補完、診断、ホバー） | 2,500+ |
| DAP サーバー（ブレークポイント、ステッピング、変数） | 1,500+ |
| **合計** | **10,000+** |

これらのサブシステムは密結合しています。文法を1箇所変更するだけで、すべてに変更が波及します。

## 解決策

**UBNF 文法**（約300行）を書いて、ジェネレータを実行するだけ。すべてが手に入ります。

```mermaid
flowchart TD
    Grammar[.ubnf grammar file]
    DSL["<b>unlaxer-dsl</b><br/>code generator"]
    Parsers["Parsers.java<br/>(パーサーコンビネータ)"]
    AST["AST.java<br/>(sealed interface + record)"]
    Mapper["Mapper.java<br/>(パースツリー → AST)"]
    Eval["Evaluator.java<br/>(ビジターのスケルトン)"]
    LSP["LSP server<br/>(補完、診断、ホバー)"]
    DAP["DAP server<br/>(ブレークポイント、ステップ、変数)"]

    Grammar --> DSL
    DSL --> Parsers
    DSL --> AST
    DSL --> Mapper
    DSL --> Eval
    DSL --> LSP
    DSL --> DAP
```

あなたが書くのは**評価ロジックだけ** — 通常 50〜200 行の `evalXxx` メソッドです。

---

## クイックサンプル

以下は [tinyexpression](https://github.com/opaopa6969/tinyexpression) の UBNF 文法の一部です：

```ubnf
@mapping(BinaryExpr, params=[left, op, right])
@leftAssoc
NumberExpression ::= NumberTerm @left { AddOp @op NumberTerm @right } ;

@mapping(BinaryExpr, params=[left, op, right])
@leftAssoc
NumberTerm ::= NumberFactor @left { MulOp @op NumberFactor @right } ;

AddOp ::= '+' | '-' ;
MulOp ::= '*' | '/' ;
```

この文法から、unlaxer は以下を生成します：

- 演算子の優先順位と左結合性を正しく処理する**パーサー**
- 型付きの `left`、`op`、`right` フィールドを持つ **`BinaryExpr` AST レコード**
- フラットなパースツリーをネストされた AST に変換する**マッパー**
- エバリュエータスケルトン内の **`evalBinaryExpr`** フック

---

## 生成されるもの

| あなたが書くもの | unlaxer が生成するもの |
|-----------|-------------------|
| 文法規則 (`::=`) | パーサーコンビネータ (`Parsers.java`) |
| `@mapping` アノテーション | AST sealed interface + record (`AST.java`) |
| `@left`、`@right`、`@op` キャプチャ | パースツリーから AST へのマッパー (`Mapper.java`) |
| `@leftAssoc` / `@rightAssoc` | 正しい結合性の処理 |
| `@root` | エントリポイントパーサー |
| （あなたの文法） | `evalXxx` フック付きエバリュエータスケルトン (`Evaluator.java`) |
| （あなたの文法） | LSP サーバー（補完、診断、ホバー） |
| （あなたの文法） | DAP サーバー（ブレークポイント、ステッピング、変数） |

---

## 5分クイックスタート

### 1. Maven 依存関係を追加

```xml
<dependencies>
    <dependency>
        <groupId>org.unlaxer</groupId>
        <artifactId>unlaxer-common</artifactId>
        <version>3.0.4</version>
    </dependency>
    <dependency>
        <groupId>org.unlaxer</groupId>
        <artifactId>unlaxer-dsl</artifactId>
        <version>3.0.4</version>
    </dependency>
</dependencies>
```

### 2. 文法を書く

`src/main/resources/TinyCalc.ubnf` を作成します：

```ubnf
grammar TinyCalc {
  @package: com.example.tinycalc

  token NUMBER = NumberParser
  token EOF    = EndOfSourceParser

  @root
  Formula ::= Expression EOF ;

  @mapping(BinaryExpr, params=[left, op, right])
  @leftAssoc
  Expression ::= Term @left { AddOp @op Term @right } ;

  @mapping(BinaryExpr, params=[left, op, right])
  @leftAssoc
  Term ::= Factor @left { MulOp @op Factor @right } ;

  Factor ::= NUMBER | '(' Expression ')' ;

  AddOp ::= '+' | '-' ;
  MulOp ::= '*' | '/' ;
}
```

### 3. コードジェネレータプラグインを追加

```xml
<plugin>
    <groupId>org.codehaus.mojo</groupId>
    <artifactId>exec-maven-plugin</artifactId>
    <version>3.1.0</version>
    <executions>
        <execution>
            <phase>generate-sources</phase>
            <goals><goal>java</goal></goals>
            <configuration>
                <mainClass>org.unlaxer.dsl.CodegenMain</mainClass>
                <arguments>
                    <argument>${project.basedir}/src/main/resources/TinyCalc.ubnf</argument>
                    <argument>${project.build.directory}/generated-sources/ubnf</argument>
                </arguments>
            </configuration>
        </execution>
    </executions>
</plugin>
```

### 4. コードを生成

```bash
mvn compile
```

`target/generated-sources/ubnf/com/example/tinycalc/` 配下に4つのファイルが生成されます：

```
TinyCalcParsers.java    -- パーサーコンビネータ
TinyCalcAST.java        -- sealed interface + record (BinaryExpr など)
TinyCalcMapper.java     -- パースツリー -> AST 変換
TinyCalcEvaluator.java  -- evalXxx フック付き抽象エバリュエータ
```

### 5. エバリュエータを書く

```java
public class CalcEvaluator extends TinyCalcEvaluator<Double> {

    @Override
    protected Double evalBinaryExpr(BinaryExpr node) {
        Double left = eval(node.left());
        Double right = eval(node.right());
        return switch (node.op()) {
            case "+" -> left + right;
            case "-" -> left - right;
            case "*" -> left * right;
            case "/" -> left / right;
            default -> throw new IllegalArgumentException("Unknown op: " + node.op());
        };
    }

    @Override
    protected Double evalNumber(NumberLiteral node) {
        return Double.parseDouble(node.value());
    }
}
```

### 6. 実行する

```java
var parser = new TinyCalcParsers();
var tree = parser.parse("1 + 2 * 3");
var ast = new TinyCalcMapper().map(tree);
var result = new CalcEvaluator().eval(ast);
System.out.println(result);  // 7.0
```

詳細なウォークスルーは [docs/getting-started-ja.md](./docs/getting-started-ja.md) を参照してください。

---

## アーキテクチャ

```mermaid
flowchart TD
    G[.ubnf grammar]
    Gen["code generation<br/>(unlaxer-dsl)"]
    Parsers["Parsers<br/>(combinator chain)"]
    AST["AST<br/>(sealed records)"]
    Mapper["Mapper<br/>(parse tree → AST)"]
    PT[Parse Tree]
    AT[AST Tree]
    Eval[Evaluator]
    LSP[LSP Server]
    DAP[DAP Server]

    G --> Gen
    Gen --> Parsers
    Gen --> AST
    Gen --> Mapper
    Parsers --> PT --> AT --> Eval
    AST --> AT
    Mapper --> AT
    Eval --> LSP
    Eval --> DAP
```

詳細なパイプライン、コンビネータカタログ、ParserIR 設計については [docs/architecture-ja.md](./docs/architecture-ja.md) を参照してください。

---

## Bootstrap と自己ホスティング

3.0.x から、unlaxer-dsl は **bootstrap しきい値** を超えました：UBNF 文法ファイル `unlaxer-dsl/grammar/ubnf.ubnf` 自体が UBNF で記述されており、unlaxer-dsl 自身によって処理されて `UBNFParsers.java`、`UBNFAST.java`、`UBNFMapper.java` を再生成します。

これが意味すること：

- 手書きの bootstrap ファイルは今や検証対象であり、ソースオブトゥルースではない
- UBNF に追加されたすべての文法機能は `ubnf.ubnf` の自己ホスト済みパースによって検証される
- Bootstrap パスは `ubnf.ubnf` → codegen → `bootstrap/generated/` → 次の codegen サイクルで使用

詳細は [docs/architecture-ja.md — Bootstrap](./docs/architecture-ja.md#bootstrap-と自己ホスティング) を参照してください。

---

## 実例

**[tinyexpression](https://github.com/opaopa6969/tinyexpression)** は、unlaxer-parser で構築された完全な数式言語です。

- 約300行の UBNF 文法
- 変数、関数（`sin`、`cos`、`sqrt`、`min`、`max`）、三項演算子、if/else、メソッド宣言
- 完全な LSP サポート（補完、診断、ホバー、定義へのジャンプ）
- 完全な DAP サポート（ブレークポイント、ステッピング、変数インスペクション）
- 本番環境で使用中

---

## Downstream ドリフト警告

> **警告**: 以下の downstream プロジェクトの一部は元々 **unlaxer-parser 2.x** に対してビルドされたものです。`UBNFAST`、`UBNFMapper`、`CodegenMain` エントリポイントの API 変更によってビルドが失敗する可能性があるため、アップグレード前に各プロジェクトの最終検証バージョンを確認してください。

| Downstream プロジェクト | 最終検証バージョン | ステータス |
|----------------------|----------------|----------|
| [tinyexpression](https://github.com/opaopa6969/tinyexpression) | 3.0.4 | 検証済み。`p4-smoke`（86 テスト）+ LSP smoke（25 テスト）パス（[#28](https://github.com/opaopa6969/unlaxer-parser/issues/28), #38） |
| [onigiri-parser](https://github.com/opaopa6969/onigiri-parser) | 3.0.1 | `mvn compile` 成功（[#27](https://github.com/opaopa6969/unlaxer-parser/issues/27)） |
| [fraud-alert](https://github.com/opaopa6969/fraud-alert) | 2.8.0 | 3.x 未検証 |

アップグレード前に、[2.x → 3.x 移行ガイド](./docs/migration-2.x-to-3.x-ja.md)と [CHANGELOG](./CHANGELOG.md) の `2.8.0 → 3.0.0` 破壊的変更セクションを確認してください。

---

## ドキュメント

| ドキュメント | 説明 | 言語 |
|------------|------|------|
| [Getting Started](./docs/getting-started-ja.md) | Maven 設定、最初の文法、完全ウォークスルー | [EN](./docs/getting-started.md) / [JA](./docs/getting-started-ja.md) |
| [UBNF ガイド](./docs/ubnf-guide-ja.md) | UBNF 構文全体、全アノテーション、機能マトリックス | [EN](./docs/ubnf-guide.md) / [JA](./docs/ubnf-guide-ja.md) |
| [アーキテクチャ](./docs/architecture-ja.md) | Bootstrap パイプライン、コンビネータカタログ、ParserIR | [EN](./docs/architecture.md) / [JA](./docs/architecture-ja.md) |
| [パーサーの基礎](./unlaxer-common/docs/tutorial-parser-fundamentals-dialogue.ja.md) | コアとなるパーサーコンビネータの概念 | [EN](./unlaxer-common/docs/tutorial-parser-fundamentals-dialogue.en.md) / [JA](./unlaxer-common/docs/tutorial-parser-fundamentals-dialogue.ja.md) |
| [UBNF から LSP/DAP へのチュートリアル](./unlaxer-dsl/docs/tutorial-ubnf-to-lsp-dap-dialogue.ja.md) | 文法から IDE サポートまでの全パイプライン | [EN](./unlaxer-dsl/docs/tutorial-ubnf-to-lsp-dap-dialogue.en.md) / [JA](./unlaxer-dsl/docs/tutorial-ubnf-to-lsp-dap-dialogue.ja.md) |
| [クイックスタート（5分）](./unlaxer-dsl/docs/quickstart-dialogue.ja.md) | 対話形式の入門ガイド | [EN](./unlaxer-dsl/docs/quickstart-dialogue.en.md) / [JA](./unlaxer-dsl/docs/quickstart-dialogue.ja.md) |
| [LLM 時代と Unlaxer](./unlaxer-dsl/docs/llm-era-and-unlaxer-dialogue.ja.md) | LLM の時代にフレームワークが依然として重要な理由 | [EN](./unlaxer-dsl/docs/llm-era-and-unlaxer-dialogue.en.md) / [JA](./unlaxer-dsl/docs/llm-era-and-unlaxer-dialogue.ja.md) |

---

## なぜ unlaxer なのか？

| | ANTLR | tree-sitter | PEG.js | **unlaxer** |
|---|---|---|---|---|
| 言語 | Java, C#, Python, ... | C + バインディング | JavaScript | **Java** |
| パーサー種別 | ALL(*) | GLR | PEG | **PEG + コンビネータ** |
| AST 生成 | 手動 | 手動 | 手動 | **自動**（`@mapping` から） |
| エバリュエータスケルトン | なし | なし | なし | **あり** |
| LSP 生成 | なし | 部分的（クエリ） | なし | **あり** |
| DAP 生成 | なし | なし | なし | **あり** |
| 文法アノテーション | なし | なし | なし | **あり**（`@mapping`、`@leftAssoc`、`@eval` など） |
| 演算子の結合性 | 文法内 | 文法内 | 手動 | **`@leftAssoc` / `@rightAssoc`** |
| 依存関係ゼロ | なし | なし | なし | **あり** (unlaxer-common) |
| Bootstrap / 自己ホスティング | なし | あり | なし | **あり**（3.0.0 以降） |

unlaxer は、文法から実用的な IDE サポートまでを最小限のボイラープレートで実現したい Java チームのために設計されています。

---

## プロジェクト構成

```
unlaxer-parser/
  +-- unlaxer-common/     コアパーサーコンビネータライブラリ（依存関係ゼロ）
  |     +-- src/          コンビネータ ~50 個、エレメンタリーパーサー ~30 個
  |     +-- docs/         パーサーの基礎チュートリアル
  +-- unlaxer-dsl/        コードジェネレータ: UBNF -> Parsers + AST + Mapper + Evaluator + LSP + DAP
  |     +-- grammar/      ubnf.ubnf（自己ホスト済み文法定義）
  |     +-- src/          Bootstrap パーサー、codegen パイプライン、IR
  |     +-- docs/         UBNF チュートリアル、拡張ロードマップ、ParserIR 設計
  +-- docs/               トップレベルガイド（アーキテクチャ、UBNF ガイド、入門）
```

- **[unlaxer-common](./unlaxer-common/)** — RELAX NG にインスパイアされたパーサーコンビネータ。無制限の先読み、バックトラッキング、包括的なロギング。純粋な Java、依存関係ゼロ。
- **[unlaxer-dsl](./unlaxer-dsl/)** — `.ubnf` 文法ファイルを読み込み、必要な Java コードをすべて生成します。

---

## foundation-poisonpills について

一部の内部統合テストと codegen フィクスチャは、`foundation-poisonpills` という名前のモジュールを参照しています。このモジュールは Maven Central に公開されておらず、パブリック API の一部でもありません。その目的は、パーサーコンビネータのコアに対する内部的な敵対的テスト（最悪ケースのバックトラッキングを引き起こすように設計された入力）です。**unlaxer-parser を使用するためにこのモジュールは必要ありません**。`foundation-poisonpills` に関するビルドエラーが表示される場合は、プライベートモジュールがローカルのクラスパスに存在する必要がある完全な内部テストスイートを実行している可能性が高いです。

---

## ライセンス

MIT ライセンス。詳細は [LICENSE](./LICENSE) を参照してください。

## コントリビューション

コントリビューションを歓迎します。[GitHub](https://github.com/opaopa6969/unlaxer-parser) で Issue または Pull Request をお寄せください。

## 著者

[opaopa6969](https://github.com/opaopa6969)
