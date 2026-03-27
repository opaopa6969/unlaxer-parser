[English](./README.md) | [日本語](./README.ja.md)

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

**文法を書くだけで言語が手に入る -- Parser + AST + Evaluator + LSP + DAP をすべて自動生成**

[![Maven Central](https://img.shields.io/maven-central/v/org.unlaxer/unlaxer-common)](https://central.sonatype.com/artifact/org.unlaxer/unlaxer-common)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](./LICENSE)
[![Java 21+](https://img.shields.io/badge/Java-21%2B-orange.svg)]()

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

```
  .ubnf grammar file
        |
        v
  +-----------------+
  | unlaxer-dsl     |
  |  code generator |
  +-----------------+
        |
        +---> Parsers.java      (パーサーコンビネータ)
        +---> AST.java           (sealed interface + record)
        +---> Mapper.java        (パースツリー -> AST)
        +---> Evaluator.java     (ビジターのスケルトン)
        +---> LSP server         (補完、診断、ホバー)
        +---> DAP server         (ブレークポイント、ステップ、変数)
```

あなたが書くのは**評価ロジックだけ** -- 通常 50〜200 行の `evalXxx` メソッドです。

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
        <version>2.5.0</version>
    </dependency>
    <dependency>
        <groupId>org.unlaxer</groupId>
        <artifactId>unlaxer-dsl</artifactId>
        <version>2.5.0</version>
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
                <mainClass>org.unlaxer.dsl.UbnfCodeGenerator</mainClass>
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

---

## アーキテクチャ

```
                    +------------------+
                    |   .ubnf grammar  |
                    +--------+---------+
                             |
                      code generation
                             |
            +----------------+----------------+
            |                |                |
     +------v------+  +-----v------+  +------v------+
     |   Parsers   |  |    AST     |  |   Mapper    |
     | (combinator |  | (sealed    |  | (parse tree |
     |   chain)    |  |  records)  |  |  -> AST)    |
     +------+------+  +-----+------+  +------+------+
            |                |                |
            v                v                v
     +------+------+  +-----+------+  +------v------+
     |  Parse Tree |->|  AST Tree  |->|  Evaluator  |
     +-------------+  +------------+  +------+------+
                                             |
                                    +--------+--------+
                                    |                 |
                              +-----v-----+    +-----v-----+
                              | LSP Server|    | DAP Server|
                              +-----------+    +-----------+
```

---

## 実例

**[tinyexpression](https://github.com/opaopa6969/tinyexpression)** は、unlaxer-parser で構築された完全な数式言語です。

- 約300行の UBNF 文法
- 変数、関数（`sin`、`cos`、`sqrt`、`min`、`max`）、三項演算子、if/else、メソッド宣言
- 完全な LSP サポート（補完、診断、ホバー、定義へのジャンプ）
- 完全な DAP サポート（ブレークポイント、ステッピング、変数インスペクション）
- 本番環境で使用中

---

## ドキュメント

| チュートリアル | 説明 | 言語 |
|----------|-------------|----------|
| パーサーの基礎 | コアとなるパーサーコンビネータの概念 (unlaxer-common) | [JA](./unlaxer-common/docs/tutorial-parser-fundamentals-dialogue.ja.md) |
| UBNF から LSP/DAP へ | 文法から IDE サポートまでの全パイプライン (unlaxer-dsl) | [EN](./unlaxer-dsl/docs/tutorial-ubnf-to-lsp-dap-dialogue.en.md) / [JA](./unlaxer-dsl/docs/tutorial-ubnf-to-lsp-dap-dialogue.ja.md) |
| LLM 時代と Unlaxer | LLM の時代にフレームワークが依然として重要な理由 | [JA](./unlaxer-dsl/docs/llm-era-and-unlaxer-dialogue.ja.md) |
| クイックスタート（5分） | 対話形式の入門ガイド | [JA](./unlaxer-dsl/docs/quickstart-dialogue.ja.md) |
| 実装ガイド | tinyexpression のエンドツーエンド構築 | [tinyexpression リポジトリ](https://github.com/opaopa6969/tinyexpression) |

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

unlaxer は、文法から実用的な IDE サポートまでを最小限のボイラープレートで実現したい Java チームのために設計されています。

---

## プロジェクト構成

```
unlaxer-parser/
  +-- unlaxer-common/     コアパーサーコンビネータライブラリ（依存関係ゼロ）
  +-- unlaxer-dsl/         コードジェネレータ: UBNF -> Parsers + AST + Mapper + Evaluator + LSP + DAP
```

- **[unlaxer-common](./unlaxer-common/)** -- RELAX NG にインスパイアされたパーサーコンビネータ。無制限の先読み、バックトラッキング、包括的なロギング。純粋な Java、依存関係ゼロ。
- **[unlaxer-dsl](./unlaxer-dsl/)** -- `.ubnf` 文法ファイルを読み込み、必要な Java コードをすべて生成します。

---

## ライセンス

MIT ライセンス。詳細は [LICENSE](./LICENSE) を参照してください。

## コントリビューション

コントリビューションを歓迎します。[GitHub](https://github.com/opaopa6969/unlaxer-parser) で Issue または Pull Request をお寄せください。

## 著者

[opaopa6969](https://github.com/opaopa6969)
