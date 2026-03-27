[English](./quickstart-dialogue.en.md) | [日本語](./quickstart-dialogue.ja.md) | [Index](./INDEX.ja.md)

---

# 5分で始める unlaxer-parser -- 会話で学ぶクイックスタート

> **登場人物**
> - **先輩**: unlaxer-parser の作者。文法を書くだけで言語処理系が丸ごと生成されるフレームワークを設計した人
> - **後輩**: Java の基本は分かるが、パーサージェネレータは初めて。「パーサーって何？」から始まる

---

## 目次

- [Part 1: 何ができるの？](#part-1-何ができるの)
- [Part 2: 環境構築](#part-2-環境構築)
- [Part 3: 最初の文法を書く](#part-3-最初の文法を書く)
- [Part 4: 生成して動かす](#part-4-生成して動かす)
- [Part 5: Evaluator を書く](#part-5-evaluator-を書く)
- [Part 6: 次のステップ](#part-6-次のステップ)

---

## Part 1: 何ができるの？

**後輩:** 先輩、「パーサージェネレータ」って何ですか？名前は聞いたことあるんですけど、正直よく分かってなくて。

**先輩:** いい質問。じゃあ逆に聞くけど、`"1+2*3"` っていう文字列が入力されたら、答えはいくつ？

**後輩:** 7ですよね。掛け算が先だから。`2*3=6` で、`1+6=7`。

**先輩:** そう。じゃあ、それをプログラムで計算するにはどうする？

**後輩:** えーと……`String.split` で `+` で分割して……いや、でもそうすると `2*3` が先に計算されない……

**先輩:** そうなんだよ。素朴にやるとこうなる。

```java
// 素朴なアプローチ：split で頑張る
String input = "1+2*3";
String[] parts = input.split("\\+");
// parts = ["1", "2*3"]
// "2*3" をさらに split("\\*") して...
// でも "1-2+3" だったら？ "-" と "+" の両方で split？
// "(1+2)*3" だったら？ カッコは？
// "1+-2" （負の数）だったら？
```

**後輩:** うわ、これ地獄じゃないですか。

**先輩:** そう、地獄。`split` でやろうとすると、演算子の優先順位、カッコ、負の数、空白……全部自分で処理しなきゃいけない。100行書いても全パターンをカバーできない。

**後輩:** じゃあプロはどうやってるんですか？

**先輩:** 「パーサー」を書く。パーサーは文字列を構造化されたデータ（木構造）に変換するプログラム。`"1+2*3"` をこんな木に変換する。

```
    +
   / \
  1   *
     / \
    2   3
```

**後輩:** あ、これなら木の下から計算していけば自然に `2*3=6` が先に計算されますね。

**先輩:** そのとおり。で、このパーサーを書くのに2つのアプローチがある。

1. **手書き** -- 再帰下降パーサーを自分で書く。数百行。バグりやすい
2. **パーサージェネレータ** -- 文法規則を書くと、パーサーのコードが自動生成される

**後輩:** パーサージェネレータのほうが良さそうですね。で、unlaxer は？

**先輩:** unlaxer は「パーサージェネレータ」を超えてる。こういう違い。

| ツール | 生成するもの |
|--------|-------------|
| 一般的なパーサージェネレータ (ANTLR 等) | パーサーだけ |
| **unlaxer** | パーサー + AST + マッパー + エバリュエータ + LSP + DAP |

**後輩:** LSP って……VS Code の補完とかのアレですか？

**先輩:** そう。文法を書くだけで、エディタの補完、エラー表示、ホバー情報、定義ジャンプまで全部ついてくる。

**後輩:** えっ、そこまで？ 文法書くだけで？

**先輩:** 文法書くだけで。まあ、実際にやってみようか。

---

## Part 2: 環境構築

**後輩:** 何が必要ですか？

**先輩:** Java 21 以上と Maven。それだけ。

**後輩:** あ、Java は 21 入れてます。Maven も入ってます。

**先輩:** じゃあ新しいプロジェクトを作ろう。まず `pom.xml`。

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                             http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.example</groupId>
    <artifactId>tinycalc</artifactId>
    <version>1.0.0-SNAPSHOT</version>

    <properties>
        <maven.compiler.source>21</maven.compiler.source>
        <maven.compiler.target>21</maven.compiler.target>
        <unlaxer.version>2.5.0</unlaxer.version>
    </properties>

    <dependencies>
        <!-- unlaxer コアライブラリ -->
        <dependency>
            <groupId>org.unlaxer</groupId>
            <artifactId>unlaxer-common</artifactId>
            <version>${unlaxer.version}</version>
        </dependency>
        <!-- unlaxer コードジェネレータ（コンパイル時のみ使用） -->
        <dependency>
            <groupId>org.unlaxer</groupId>
            <artifactId>unlaxer-dsl</artifactId>
            <version>${unlaxer.version}</version>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <!-- UBNF からコード生成するプラグイン -->
            <plugin>
                <groupId>org.codehaus.mojo</groupId>
                <artifactId>exec-maven-plugin</artifactId>
                <version>3.1.0</version>
                <executions>
                    <execution>
                        <id>generate-parser</id>
                        <phase>generate-sources</phase>
                        <goals>
                            <goal>java</goal>
                        </goals>
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

            <!-- 生成コードをコンパイルパスに追加 -->
            <plugin>
                <groupId>org.codehaus.mojo</groupId>
                <artifactId>build-helper-maven-plugin</artifactId>
                <version>3.4.0</version>
                <executions>
                    <execution>
                        <id>add-generated-sources</id>
                        <phase>generate-sources</phase>
                        <goals>
                            <goal>add-source</goal>
                        </goals>
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
</project>
```

**後輩:** 結構長いですね……

**先輩:** 長く見えるけど、やってることは3つだけ。

1. **依存関係**: `unlaxer-common`（パーサーライブラリ）と `unlaxer-dsl`（コードジェネレータ）
2. **exec-maven-plugin**: `mvn compile` 時に `.ubnf` ファイルからコードを自動生成
3. **build-helper-maven-plugin**: 生成されたコードをコンパイル対象に追加

**後輩:** なるほど。で、文法ファイルはどこに置くんですか？

**先輩:** `src/main/resources/` の下。こんなディレクトリ構成になる。

```
tinycalc/
  pom.xml
  src/
    main/
      resources/
        TinyCalc.ubnf          <-- 文法ファイル（ここに書く）
      java/
        com/example/tinycalc/
          CalcEvaluator.java    <-- 自分で書くのはこれだけ
          Main.java
  target/
    generated-sources/
      ubnf/
        com/example/tinycalc/
          TinyCalcParsers.java  <-- 自動生成
          TinyCalcAST.java      <-- 自動生成
          TinyCalcMapper.java   <-- 自動生成
          TinyCalcEvaluator.java <-- 自動生成
```

**後輩:** 手で書くのは文法ファイルと `CalcEvaluator.java` と `Main.java` だけ？

**先輩:** そう。残りは全部生成される。じゃあ文法を書こう。

---

## Part 3: 最初の文法を書く

**先輩:** `src/main/resources/TinyCalc.ubnf` を作る。一気に見せると混乱するから、上から順に説明するね。

### ヘッダー部分

```ubnf
grammar TinyCalc {
  @package: com.example.tinycalc
```

**後輩:** `grammar` で言語名を宣言して、`@package` で生成先の Java パッケージを指定するんですね。

**先輩:** そのとおり。`grammar` の名前がそのままクラス名のプレフィックスになる。だから `TinyCalc` と書くと、`TinyCalcParsers`、`TinyCalcAST`……というクラスが生成される。

### トークン宣言

```ubnf
  token NUMBER = NumberParser
  token EOF    = EndOfSourceParser
```

**後輩:** `token` って何ですか？

**先輩:** 字句（レキサー）レベルのパーサー。文字列を読み取る最小単位。`NumberParser` は `123` や `3.14` みたいな数値リテラルを読むクラスで、unlaxer-common に組み込まれてる。

**後輩:** `EndOfSourceParser` は？

**先輩:** 入力の終端を検出するパーサー。ここまで来たら正常に全部読めたよ、という意味。

### ルートルール

```ubnf
  @root
  Formula ::= Expression EOF ;
```

**後輩:** `@root` はパースの起点ですよね？

**先輩:** そう。ユーザーが入力した文字列は、まず `Formula` ルールでパースされる。`Formula` は `Expression` の後に `EOF` が来る、と宣言してる。つまり「式を1つ書いて、それで入力が終わり」というルール。

### 算術式のルール -- ここが核心

**先輩:** ここが一番大事なところ。四則演算の優先順位をどう表現するか。

```ubnf
  @mapping(BinaryExpr, params=[left, op, right])
  @leftAssoc
  Expression ::= Term @left { AddOp @op Term @right } ;
```

**後輩:** うわ、アノテーションがいっぱいついてますね。`@mapping` って何ですか？

**先輩:** `@mapping(BinaryExpr, params=[left, op, right])` は「このルールにマッチしたら、`BinaryExpr` という AST ノードを作れ」という指示。

ちょっと分解して説明しよう。

| 部分 | 意味 |
|------|------|
| `@mapping(BinaryExpr, ...)` | マッチした結果を `BinaryExpr` レコードに変換する |
| `params=[left, op, right]` | レコードのフィールド名 |
| `@left` | ルール本体の中で、この位置の値を `left` フィールドに入れる |
| `@op` | この位置の値を `op` フィールドに入れる |
| `@right` | この位置の値を `right` フィールドに入れる |

**後輩:** あー、つまりパースしながら「ここが left」「ここが op」「ここが right」って印をつけて、自動的に AST ノードにまとめてくれるんですね。

**先輩:** そのとおり。手書きだったら自分でノードを new して、フィールドをセットして……というコードを書くところが全部自動化される。

**後輩:** で、`@leftAssoc` は何ですか？

**先輩:** 左結合（left associative）の宣言。これがないと `3-2-1` の解釈がおかしくなる。

```
左結合（正しい）：       右結合（間違い）：
    -                       -
   / \                     / \
  -   1                   3   -
 / \                         / \
3   2                       2   1

(3-2)-1 = 0               3-(2-1) = 2
```

**後輩:** あ、引き算は左から計算しないとダメですもんね。`@leftAssoc` をつけるだけで正しく処理されるんですか？

**先輩:** そう。unlaxer が繰り返し部分 `{ AddOp @op Term @right }` を左結合の木に組み立ててくれる。

### ルール本体の構文

**先輩:** ルール本体の `::=` 以降の記法をまとめておこう。

```ubnf
  Expression ::= Term @left { AddOp @op Term @right } ;
```

| 記法 | 意味 | 例 |
|------|------|-----|
| `A B` | 連接（A の後に B） | `Term AddOp Term` |
| `A \| B` | 選択（A または B） | `'+' \| '-'` |
| `{ A }` | 0回以上の繰り返し | `{ AddOp Term }` |
| `[ A ]` | 省略可能（0回または1回） | `[ '-' ]` |
| `( A )` | グルーピング | `( '+' \| '-' )` |
| `'+'` | リテラル文字列 | `'+'` |
| `@name` | キャプチャ（AST フィールドへの紐づけ） | `@left`, `@op` |

**後輩:** EBNF に似てますね。

**先輩:** そう、EBNF がベース。`@name` のキャプチャが追加されてるのが UBNF の特徴。

### 乗算と項

```ubnf
  @mapping(BinaryExpr, params=[left, op, right])
  @leftAssoc
  Term ::= Factor @left { MulOp @op Factor @right } ;
```

**後輩:** `Expression` と同じパターンですね。`Term` は掛け算と割り算を処理する。

**先輩:** そう。`Expression` → `Term` → `Factor` の順に優先順位が高くなる。これは文法でよく使われるパターンで、**演算子の優先順位を構文規則のネスト（階層）で表現する**というテクニック。

```
Expression = Term   { (+|-) Term }      ← 優先順位：低
Term       = Factor { (*|/) Factor }    ← 優先順位：高
Factor     = NUMBER | '(' Expression ')'← 最高（アトム）
```

**後輩:** なるほど。`Factor` が一番深いから、掛け算・割り算が加減算より先に評価される。

**先輩:** 正解。

### 因子と演算子

```ubnf
  Factor ::= NUMBER | '(' Expression ')' ;

  AddOp ::= '+' | '-' ;
  MulOp ::= '*' | '/' ;
}
```

**後輩:** `Factor` は数値リテラルか、カッコで囲まれた式。`AddOp` と `MulOp` は演算子の選択。

**先輩:** そう。これで文法は完成。全体をもう一度見てみよう。

### 完成した文法（全体）

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

**後輩:** 全部合わせても20行くらいですね。

**先輩:** うん。これだけで四則演算パーサーの全機能が定義されてる。

---

## Part 4: 生成して動かす

**先輩:** じゃあ生成しよう。

```bash
mvn compile
```

**後輩:** ……あれ、もうコンパイル終わりました。早いですね。

**先輩:** `exec-maven-plugin` が `generate-sources` フェーズで自動的にコードを生成してる。`target/generated-sources/ubnf/` の下を見てみよう。

```bash
ls target/generated-sources/ubnf/com/example/tinycalc/
```

```
TinyCalcAST.java
TinyCalcEvaluator.java
TinyCalcMapper.java
TinyCalcParsers.java
```

**後輩:** おー、4つのファイルが生成されてる！ 中身はどうなってるんですか？

### 生成ファイル 1: TinyCalcAST.java

**先輩:** まず AST。文法の `@mapping` で指定したノード型がレコードとして生成される。

```java
// TinyCalcAST.java（抜粋・簡略化）
public sealed interface TinyCalcAST {

    // @mapping(BinaryExpr, params=[left, op, right]) から生成
    record BinaryExpr(
        TinyCalcAST left,
        String op,
        TinyCalcAST right
    ) implements TinyCalcAST {}

    // NUMBER トークンから生成
    record NumberLiteral(
        String value
    ) implements TinyCalcAST {}
}
```

**後輩:** `sealed interface` と `record`！ Java 21 の機能をフル活用してますね。

**先輩:** そう。sealed interface だから `switch` 式でパターンマッチできる。record だからイミュータブルで、`equals`/`hashCode`/`toString` も自動。

### 生成ファイル 2: TinyCalcParsers.java

**先輩:** パーサー。文法の各ルールがパーサーコンビネータのチェーンに変換される。

```java
// TinyCalcParsers.java（概念的な構造）
public class TinyCalcParsers {
    // Expression ::= Term @left { AddOp @op Term @right }
    public Parser expression() {
        return sequence(
            term(),
            zeroOrMore(sequence(addOp(), term()))
        );
    }
    // ... 他のルールも同様
}
```

**後輩:** 文法の構造がそのまま Java コードになってる。

### 生成ファイル 3: TinyCalcMapper.java

**先輩:** マッパーは、パーサーが出力する生のパースツリーを、きれいな AST に変換するクラス。`@left`、`@op`、`@right` のキャプチャ情報を使って、正しいフィールドにマッピングする。

**後輩:** これも全部自動ですか？

**先輩:** 全部自動。手で触る必要はない。

### 生成ファイル 4: TinyCalcEvaluator.java

**先輩:** エバリュエータ。これが君が拡張するクラス。

```java
// TinyCalcEvaluator.java（抜粋・簡略化）
public abstract class TinyCalcEvaluator<T> {

    public T eval(TinyCalcAST node) {
        return switch (node) {
            case BinaryExpr n -> evalBinaryExpr(n);
            case NumberLiteral n -> evalNumber(n);
        };
    }

    protected abstract T evalBinaryExpr(BinaryExpr node);
    protected abstract T evalNumber(NumberLiteral node);
}
```

**後輩:** `eval` メソッドが `switch` でノード型ごとに振り分けて、各 `evalXxx` を呼ぶんですね。

**先輩:** そう。そして `evalXxx` メソッドは `abstract` だから、自分で実装する。でも、やることは「左を評価して、右を評価して、演算子で計算する」だけ。

**後輩:** もう動くんですか！？

**先輩:** あと1つだけ。エバリュエータの実装を書けば動く。

---

## Part 5: Evaluator を書く

**先輩:** `src/main/java/com/example/tinycalc/CalcEvaluator.java` を作る。

```java
package com.example.tinycalc;

import com.example.tinycalc.TinyCalcAST.BinaryExpr;
import com.example.tinycalc.TinyCalcAST.NumberLiteral;

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
            default -> throw new IllegalArgumentException("Unknown operator: " + node.op());
        };
    }

    @Override
    protected Double evalNumber(NumberLiteral node) {
        return Double.parseDouble(node.value());
    }
}
```

**後輩:** これだけ？

**先輩:** これだけ。

**後輩:** えっ……本当にこれだけですか？

**先輩:** 本当にこれだけ。ポイントを整理しよう。

| メソッド | やっていること |
|----------|---------------|
| `evalBinaryExpr` | 左辺を再帰的に `eval()`、右辺を再帰的に `eval()`、演算子で計算 |
| `evalNumber` | 数値リテラルの文字列を `Double` にパース |

**後輩:** `eval(node.left())` が再帰的にサブツリーを評価するんですね。木構造だから自然に再帰になる。

**先輩:** そのとおり。`1+2*3` の場合、こうなる。

```
eval(BinaryExpr(left=1, op="+", right=BinaryExpr(left=2, op="*", right=3)))
  → eval(NumberLiteral("1")) = 1.0
  → eval(BinaryExpr(left=2, op="*", right=3))
      → eval(NumberLiteral("2")) = 2.0
      → eval(NumberLiteral("3")) = 3.0
      → 2.0 * 3.0 = 6.0
  → 1.0 + 6.0 = 7.0
```

**後輩:** おお、きれいに動きますね。

### テスト用の Main クラス

**先輩:** テストしてみよう。`Main.java` を書く。

```java
package com.example.tinycalc;

public class Main {
    public static void main(String[] args) {
        // 1. パーサーでパース
        var parsers = new TinyCalcParsers();
        var parseResult = parsers.parse("1 + 2 * 3");

        // 2. パースツリーを AST に変換
        var ast = new TinyCalcMapper().map(parseResult);

        // 3. AST を評価
        var result = new CalcEvaluator().eval(ast);

        System.out.println("1 + 2 * 3 = " + result);
    }
}
```

**先輩:** 実行。

```bash
mvn compile exec:java -Dexec.mainClass=com.example.tinycalc.Main
```

```
1 + 2 * 3 = 7.0
```

**後輩:** 動いた！！

**先輩:** いろんな式で試してみよう。

```
"10 - 3 - 2"     → 5.0    （左結合：(10-3)-2）
"(1 + 2) * 3"    → 9.0    （カッコが効いてる）
"100 / 10 / 2"   → 5.0    （左結合：(100/10)/2）
"1 + 2 + 3 + 4"  → 10.0
```

**後輩:** 全部正しい。優先順位もカッコも左結合も、全部文法の20行で処理されてるんですね。

**先輩:** そう。自分で書いたのは、文法ファイル20行とエバリュエータ20行。合計40行。split で頑張ったら何百行書いても正しく動かないのに。

**後輩:** パーサージェネレータ、すごいですね……

**先輩:** いや、これはまだ序の口だよ。

---

## Part 6: 次のステップ

**後輩:** え、まだ何かあるんですか？

**先輩:** 今作った電卓は数値と四則演算だけ。実際の DSL には変数、条件分岐、関数、文字列……いろんな機能が必要になる。unlaxer なら、文法にルールを追加するだけで全部対応できる。

### 変数を追加する

**後輩:** たとえば `$x + 1` みたいに変数を使いたいとしたら？

**先輩:** 文法にトークンとルールを追加するだけ。

```ubnf
  token IDENTIFIER = IdentifierParser

  @mapping(VariableRef, params=[name])
  VariableReference ::= '$' IDENTIFIER @name ;

  // Factor に VariableReference を追加
  Factor ::= NUMBER | VariableReference | '(' Expression ')' ;
```

**後輩:** `@mapping(VariableRef, params=[name])` で、`VariableRef` という AST ノードが生成されるんですね。

**先輩:** そう。で、エバリュエータに `evalVariableRef` を追加する。

```java
@Override
protected Double evalVariableRef(VariableRef node) {
    return variables.get(node.name());  // Map<String, Double> から取得
}
```

**後輩:** 文法に3行追加して、エバリュエータに3行追加するだけ……

### if 式を追加する

**先輩:** `if($x > 0, $x, -$x)` みたいな条件式も簡単。

```ubnf
  @mapping(IfExpr, params=[condition, thenExpr, elseExpr])
  IfExpression ::= 'if' '(' BooleanExpression @condition ','
                       Expression @thenExpr ','
                       Expression @elseExpr ')' ;
```

**後輩:** パターンが同じですね。`@mapping` でノード型を宣言して、`@param` でフィールドを紐づける。

**先輩:** そう。新しい構文を追加するときのパターンは常に同じ。

1. 文法にルールを追加
2. `@mapping` で AST ノードを宣言
3. `mvn compile` で再生成
4. エバリュエータに `evalXxx` を追加

### LSP を有効にする

**後輩:** さっき LSP の話がありましたよね。補完とか。

**先輩:** unlaxer で生成したパーサーは、そのまま LSP サーバーのバックエンドとして使える。文法に `@completion` アノテーションを追加すると、キーワード補完が自動で効く。

```ubnf
  @completion(keywords=["if", "sin", "cos", "sqrt", "min", "max"])
  Factor ::= NUMBER | VariableReference | IfExpression
           | SinFunction | CosFunction | SqrtFunction
           | MinFunction | MaxFunction
           | '(' Expression ')' ;
```

**後輩:** これだけで VS Code で補完が出るんですか？

**先輩:** LSP サーバーの配線は必要だけど、パーサー側の対応はこれだけ。詳しくは [UBNF から LSP/DAP まで](./tutorial-ubnf-to-lsp-dap-dialogue.ja.md) チュートリアルで解説してる。

### tinyexpression を見る

**先輩:** 今日やった TinyCalc は入門用のミニ言語。本格的な例を見たいなら [tinyexpression](https://github.com/opaopa6969/tinyexpression) を見てほしい。

**後輩:** tinyexpression って何ですか？

**先輩:** unlaxer で作った本格的な式言語。こんな機能がある。

| 機能 | 例 |
|------|-----|
| 四則演算 | `1 + 2 * 3` |
| 変数 | `$price * $tax` |
| 文字列 | `'Hello ' + $name` |
| 比較・論理 | `$x > 0 && $y < 100` |
| 三項演算子 | `$x > 0 ? $x : -$x` |
| 組み込み関数 | `sin($angle)`, `sqrt($x)`, `min($a, $b)` |
| メソッド定義 | `number abs(x) { x > 0 ? x : -x }` |
| インポート | `import java.lang.Math#abs as abs;` |
| Java コードブロック | バッククォート内に Java コードを埋め込める |

**後輩:** すごい。これが文法ファイル300行から生成されるんですか。

**先輩:** そう。文法300行 + エバリュエータ200行くらい。合計500行で、変数・関数・型チェック・LSP・DAP 全部ついてくる。

### チュートリアル一覧

**先輩:** もっと深く知りたくなったら、以下のチュートリアルを読んでみて。

| チュートリアル | 内容 | リンク |
|---------------|------|--------|
| パーサーの基礎 | パーサーコンビネータの仕組み (unlaxer-common) | [JA](../../unlaxer-common/docs/tutorial-parser-fundamentals-dialogue.ja.md) |
| UBNF から LSP/DAP まで | 文法から IDE サポートまでの全パイプライン (unlaxer-dsl) | [EN](./tutorial-ubnf-to-lsp-dap-dialogue.en.md) / [JA](./tutorial-ubnf-to-lsp-dap-dialogue.ja.md) |
| LLM 時代と Unlaxer | LLM の時代にフレームワークがなぜ必要か | [JA](./llm-era-and-unlaxer-dialogue.ja.md) |
| tinyexpression 実装ガイド | 完全な式言語の実装例 | [tinyexpression リポジトリ](https://github.com/opaopa6969/tinyexpression) |

---

**後輩:** 先輩、ありがとうございます。`split` で四則演算を実装しようとして絶望してたのが嘘みたいです。

**先輩:** でしょ。文法を書けば言語が手に入る。それが unlaxer。

**後輩:** あ、最後に一つ。unlaxer の名前の由来は？

**先輩:** "un-" + "laxer"。RELAX NG っていう XML スキーマ言語があるんだけど、そこからインスピレーションを受けてる。"relaxer" の逆、つまり「緩くない」パーサー。厳密に、でも簡単に。

**後輩:** なるほど。じゃあ早速 tinyexpression のコード読んでみます！

**先輩:** いいね。何か分からないことがあったら聞いて。

---

## まとめ

この Quick Start で学んだこと:

| ステップ | やったこと | 行数 |
|----------|-----------|------|
| 文法を書く | `TinyCalc.ubnf` | ~20行 |
| コード生成 | `mvn compile` | 0行（自動） |
| エバリュエータ実装 | `CalcEvaluator.java` | ~20行 |
| テスト | `Main.java` | ~10行 |
| **合計** | | **~50行** |

50行で、演算子の優先順位・左結合・カッコを正しく処理する四則演算言語が完成。

---

## 関連リンク

- [unlaxer-parser GitHub](https://github.com/opaopa6969/unlaxer-parser) -- 本リポジトリ
- [tinyexpression GitHub](https://github.com/opaopa6969/tinyexpression) -- 完全な実装例
- [unlaxer-common](../../unlaxer-common/) -- コアパーサーコンビネータライブラリ
- [unlaxer-dsl](../) -- コードジェネレータ

---

[English](./quickstart-dialogue.en.md) | [日本語](./quickstart-dialogue.ja.md) | [Index](./INDEX.ja.md)
