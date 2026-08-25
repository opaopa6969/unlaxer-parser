[English](./getting-started.md) | [日本語](./getting-started-ja.md)

---

# unlaxer-parser 入門ガイド

**バージョン**: 3.0.10

このガイドでは、unlaxer-parser を使って完全な計算機言語をゼロから構築する手順を説明します — Maven の設定、文法の記述、コード生成、そして動作するエバリュエーターの実装まで。

---

## 目次

- [前提条件](#前提条件)
- [ステップ1: Maven プロジェクトの作成](#ステップ1-maven-プロジェクトの作成)
- [ステップ2: 依存関係の追加](#ステップ2-依存関係の追加)
- [ステップ3: コードジェネレーターの設定](#ステップ3-コードジェネレーターの設定)
- [ステップ4: 最初の文法を書く](#ステップ4-最初の文法を書く)
- [ステップ5: コードを生成する](#ステップ5-コードを生成する)
- [ステップ6: 生成されたコードを確認する](#ステップ6-生成されたコードを確認する)
- [ステップ7: エバリュエーターを書く](#ステップ7-エバリュエーターを書く)
- [ステップ8: テストを書く](#ステップ8-テストを書く)
- [ステップ9: 変数を追加する](#ステップ9-変数を追加する)
- [次のステップ](#次のステップ)

---

## 前提条件

- Java 21 以降
- Maven 3.8 以降
- Java の基本知識

---

## ステップ1: Maven プロジェクトの作成

```bash
mvn archetype:generate \
  -DgroupId=com.example \
  -DartifactId=tinycalc \
  -DarchetypeArtifactId=maven-archetype-quickstart \
  -DarchetypeVersion=1.4 \
  -DinteractiveMode=false
cd tinycalc
```

---

## ステップ2: 依存関係の追加

`pom.xml` を編集して Java 21 を要求し、unlaxer を追加します：

```xml
<properties>
    <maven.compiler.source>21</maven.compiler.source>
    <maven.compiler.target>21</maven.compiler.target>
</properties>

<dependencies>
    <dependency>
        <groupId>org.unlaxer</groupId>
        <artifactId>unlaxer-common</artifactId>
        <version>3.0.10</version>
    </dependency>
    <dependency>
        <groupId>org.unlaxer</groupId>
        <artifactId>unlaxer-dsl</artifactId>
        <version>3.0.10</version>
    </dependency>

    <!-- テスト用 -->
    <dependency>
        <groupId>junit</groupId>
        <artifactId>junit</artifactId>
        <version>4.13.2</version>
        <scope>test</scope>
    </dependency>
</dependencies>
```

---

## ステップ3: コードジェネレーターの設定

`generate-sources` フェーズ中に unlaxer コードジェネレーターを実行するため、`exec-maven-plugin` を追加します：

```xml
<build>
    <plugins>
        <plugin>
            <groupId>org.codehaus.mojo</groupId>
            <artifactId>exec-maven-plugin</artifactId>
            <version>3.1.0</version>
            <executions>
                <execution>
                    <id>generate-ubnf</id>
                    <phase>generate-sources</phase>
                    <goals><goal>java</goal></goals>
                    <configuration>
                        <mainClass>org.unlaxer.dsl.CodegenMain</mainClass>
                        <arguments>
                            <argument>--grammar</argument>
                            <argument>${project.basedir}/src/main/resources/tinycalc.ubnf</argument>
                            <argument>--output</argument>
                            <argument>${project.build.directory}/generated-sources/ubnf</argument>
                            <argument>--generators</argument>
                            <argument>AST,Parser,Mapper,Evaluator</argument>
                        </arguments>
                    </configuration>
                </execution>
            </executions>
        </plugin>
        <plugin>
            <groupId>org.codehaus.mojo</groupId>
            <artifactId>build-helper-maven-plugin</artifactId>
            <version>3.4.0</version>
            <executions>
                <execution>
                    <id>add-source</id>
                    <phase>generate-sources</phase>
                    <goals><goal>add-source</goal></goals>
                    <configuration>
                        <sources>
                            <source>${project.build.directory}/generated-sources/ubnf</source>
                        </sources>
                    </configuration>
                </execution>
            </executions>
        </plugin>
    </plugins>
</build>
```

---

## ステップ4: 最初の文法を書く

`src/main/resources/tinycalc.ubnf` を作成します：

```ubnf
grammar TinyCalc {
  @package: com.example.tinycalc

  // トークン間で Java スタイルの空白スキップを使用
  @whitespace: javaStyle

  // トークン宣言: Java パーサークラスを参照
  token NUMBER     = org.unlaxer.parser.elementary.NumberParser
  token IDENTIFIER = org.unlaxer.parser.clang.IdentifierParser
  token EOF        = org.unlaxer.parser.elementary.EndOfSourceParser

  // 文法のエントリポイント
  @root
  @mapping(Program, params=[expression])
  Program ::= Expression @expression EOF ;

  // 左結合の加算・減算
  @mapping(BinaryExpr, params=[left, op, right])
  @leftAssoc
  Expression ::= Term @left { AddOp @op Term @right } ;

  // 左結合の乗算・除算
  @mapping(BinaryExpr, params=[left, op, right])
  @leftAssoc
  Term ::= Factor @left { MulOp @op Factor @right } ;

  // アトム: 数値、括弧
  Factor ::= NUMBER | '(' Expression ')' ;

  // 演算子トークン
  @enum
  AddOp ::= '+' | '-' ;

  @enum
  MulOp ::= '*' | '/' ;
}
```

### この文法が表すもの

- **`@root`**: `Program` がパースの開始点
- **`@mapping(Program, params=[expression])`**: AST にレコード `Program(TinyCalcNode expression)` を生成
- **`@leftAssoc`**: `1+2+3` は `1+(2+3)` ではなく `(1+2)+3` としてパース
- **`@enum`**: `AddOp { PLUS, MINUS }` と `MulOp { STAR, SLASH }` の enum を生成
- **`@whitespace: javaStyle`**: トークン間のスペースと改行は無視される

---

## ステップ5: コードを生成する

```bash
mvn generate-sources
```

成功すると、`target/generated-sources/ubnf/com/example/tinycalc/` に4つの生成ファイルが作成されます：

```
TinyCalcParsers.java    -- 文法ルールごとに1つのパーサークラス
TinyCalcAST.java        -- AST の sealed interface + レコード
TinyCalcMapper.java     -- 生のパースツリーを型付き AST ノードに変換
TinyCalcEvaluator.java  -- AST ノード型ごとに1つのメソッドを持つ抽象ビジター
```

---

## ステップ6: 生成されたコードを確認する

### TinyCalcAST.java（抜粋）

```java
public sealed interface TinyCalcNode permits
    TinyCalcAST.Program, TinyCalcAST.BinaryExpr, ... {}

public record Program(TinyCalcNode expression) implements TinyCalcNode {}

public record BinaryExpr(TinyCalcNode left, String op, TinyCalcNode right)
    implements TinyCalcNode {}
```

### TinyCalcEvaluator.java（抜粋）

```java
public abstract class TinyCalcEvaluator<T> {
    public T eval(TinyCalcNode node) {
        return switch (node) {
            case TinyCalcAST.Program n    -> evalProgram(n);
            case TinyCalcAST.BinaryExpr n -> evalBinaryExpr(n);
            // ... AST ノード型ごとに1ケース
        };
    }

    protected abstract T evalProgram(TinyCalcAST.Program node);
    protected abstract T evalBinaryExpr(TinyCalcAST.BinaryExpr node);
    // ...
}
```

---

## ステップ7: エバリュエーターを書く

`src/main/java/com/example/tinycalc/CalcEvaluator.java` を作成します：

```java
package com.example.tinycalc;

public class CalcEvaluator extends TinyCalcEvaluator<Double> {

    @Override
    protected Double evalProgram(TinyCalcAST.Program node) {
        return eval(node.expression());
    }

    @Override
    protected Double evalBinaryExpr(TinyCalcAST.BinaryExpr node) {
        double left  = eval(node.left());
        double right = eval(node.right());
        return switch (node.op()) {
            case "+" -> left + right;
            case "-" -> left - right;
            case "*" -> left * right;
            case "/" -> left / right;
            default  -> throw new IllegalArgumentException("Unknown op: " + node.op());
        };
    }

    // NUMBER トークンは NumberLiteral ノードとして到着; value はそのテキスト
    @Override
    protected Double evalNumberLiteral(TinyCalcAST.NumberLiteral node) {
        return Double.parseDouble(node.value());
    }
}
```

---

## ステップ8: テストを書く

`src/test/java/com/example/tinycalc/CalcTest.java` を作成します：

```java
package com.example.tinycalc;

import org.junit.Test;
import static org.junit.Assert.*;

public class CalcTest {

    private double calc(String input) {
        var parsers = new TinyCalcParsers();
        var parseTree = parsers.parse(input);
        var ast = new TinyCalcMapper().map(parseTree);
        return new CalcEvaluator().eval(ast);
    }

    @Test
    public void testAddition() {
        assertEquals(3.0, calc("1 + 2"), 0.001);
    }

    @Test
    public void testPrecedence() {
        // 乗算が加算より先
        assertEquals(7.0, calc("1 + 2 * 3"), 0.001);
    }

    @Test
    public void testParentheses() {
        assertEquals(9.0, calc("(1 + 2) * 3"), 0.001);
    }

    @Test
    public void testLeftAssociativity() {
        // 10 - 3 - 2 = (10 - 3) - 2 = 5
        assertEquals(5.0, calc("10 - 3 - 2"), 0.001);
    }
}
```

以下で実行します：

```bash
mvn test
```

---

## ステップ9: 変数を追加する

文法に変数サポートを追加するには、以下のルールを追加します：

```ubnf
  // 変数宣言
  @mapping(VarDecl, params=[name, value])
  VarDecl ::= 'var' IDENTIFIER @name '=' Expression @value ';' ;

  // 変数参照（Factor を拡張）
  Factor ::= NUMBER | IDENTIFIER | '(' Expression ')' ;
```

次にエバリュエーターを拡張します：

```java
private final Map<String, Double> variables = new HashMap<>();

@Override
protected Double evalVarDecl(TinyCalcAST.VarDecl node) {
    double value = eval(node.value());
    variables.put(node.name(), value);
    return value;
}

@Override
protected Double evalIdentifier(TinyCalcAST.IdentifierNode node) {
    return variables.getOrDefault(node.name(), 0.0);
}
```

---

## 次のステップ

| 内容 | 場所 |
|------|------|
| UBNF 構文リファレンス全体 | [docs/ubnf-guide-ja.md](./ubnf-guide-ja.md) |
| アーキテクチャ詳細 | [docs/architecture-ja.md](./architecture-ja.md) |
| LSP と DAP の生成 | [unlaxer-dsl/docs/tutorial-ubnf-to-lsp-dap-dialogue.ja.md](../unlaxer-dsl/docs/tutorial-ubnf-to-lsp-dap-dialogue.ja.md) |
| 実世界の例 | [tinyexpression](https://github.com/opaopa6969/tinyexpression) |
| パーサーコンビネータの概念 | [unlaxer-common/docs/tutorial-parser-fundamentals-dialogue.ja.md](../unlaxer-common/docs/tutorial-parser-fundamentals-dialogue.ja.md) |
