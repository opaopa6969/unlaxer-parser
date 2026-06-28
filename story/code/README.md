# story/code — 物語に対応する「実際に動くコード」

各章の技術を、**実行可能な最小コード**で確かめられる付録。物語（`story/*.ja.md`）と対で読むと、
比喩が本物のコードに着地し、初心者が「踏み抜く床」を自分の手で踏める。

## 共通の準備
```bash
COMMON=$HOME/.m2/repository/org/unlaxer/unlaxer-common/3.0.11/unlaxer-common-3.0.11.jar
DSL=$HOME/.m2/repository/org/unlaxer/unlaxer-dsl/3.0.11/unlaxer-dsl-3.0.11.jar
# unlaxer-common / unlaxer-dsl はローカル mvn install 済みであること（バージョンは適宜）。
# UBNF 生成系は dsl の依存(vavr/lsp4j)も要るので、tinyexpression のクラスパスを足すのが簡単。
```

## 索引（章 → コード → 何を学ぶ / どの床を踏み抜くか）

| 章 | ファイル | 学べること / 踏み抜く床 |
|---|---|---|
| 第2章 | `Ch02Arithmetic.java` | 優先順位＝形の深さ。Chain/Choice/OneOrMore/ZeroOrMore/DigitParser で三階建てを手組み。平らな文法だと `1+2*3=9` |
| 第3章 | `Ch03TreeWalk.java` | **どぶ板①**：手組み木の公開ASTビュー(filteredChildren)は平ら→歩くと「また9」 |
| 第5章 | `calc.ubnf` / `TinyCalcRun.java` | 一枚のUBNFから Parser/AST/Mapper を**生成**。構造つきAST(AddExpr⊃MulExpr)を歩いて 7 |
| 〃 | `calc-leftrec.ubnf` | **どぶ板②**：左再帰 `Expression ::= Expression …`。生成は通るのに parse で `transaction nest is illegal` |
| 第7章 | `calc-var.ubnf` / `TinyCalcVarRun.java` | 評価器＝木をその場で歩く。文脈(context)から `$price` を引く。同じ式・多数の値。**どぶ板③**：未定義変数 |
| 第8章 | `TinyCalcEmit.java` | Javaコード生成（鍛える剣）。**どぶ板④**：葉を写すと `$price` が残りコンパイル不能→`ctx.get(...)` に翻訳 |
| 第10-11章 | `PackratDemo.java` | **指数爆発を実測**：曖昧入れ子で深さ別 memoize OFF(8:149ms→16:4087ms＝指数) vs ON(0〜3ms, 深さ40でも1ms) |

## 各ファイルの実行手順
- **コンビネータ系（手組み）**: `Ch02Arithmetic` / `Ch03TreeWalk` / `PackratDemo`
  ```bash
  javac -cp "$COMMON" -d out story/code/Ch02Arithmetic.java && java -cp "out:$COMMON" Ch02Arithmetic
  ```
- **UBNF生成系**: `calc*.ubnf` → 生成 → walker/emitter をコンパイル（各 .java 冒頭にコマンド）
  ```bash
  java -cp "$DSL:$COMMON:<deps>" org.unlaxer.dsl.CodegenMain \
       --grammar story/code/calc.ubnf --output gen --generators Parser,AST,Mapper
  javac -cp "$COMMON" -d out $(find gen -name '*.java') story/code/TinyCalcRun.java
  java  -cp "out:$COMMON" TinyCalcRun
  ```

## 設計メモ（手組み vs 生成）
Unlaxer の公開 AST ビューは構造コンビネータを畳む（`Ch03TreeWalk` の「平ら」）。だから手組みは
**評価のために構造を保つのが大変**。UBNF から生成すると、構造つき AST と歩く係が一緒に出てくる
（`TinyCalcRun`）。「手で潰した経験」があるほど、生成のありがたみが骨身にしみる——という順序で
物語と対応している（第三章 → 第五章）。

> 技術的背骨は現実の unlaxer-parser #19/#40/#49 と対応（指数バックトラック→packrat→mapTokenメモ化の根治）。
