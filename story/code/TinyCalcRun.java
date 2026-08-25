// 第五章「文法を継ぎ足す」の、実際に動くコード（UBNF から生成した AST を歩く）。
//
//   DSL=$HOME/.m2/repository/org/unlaxer/unlaxer-dsl/3.0.11/unlaxer-dsl-3.0.11.jar
//   COMMON=$HOME/.m2/repository/org/unlaxer/unlaxer-common/3.0.11/unlaxer-common-3.0.11.jar
//   # dsl の依存(vavr/lsp4j)も要るので、tinyexpression のクラスパスを足すのが簡単。
//   # 1) 一枚の文法から Parser/AST/Mapper を生成
//   java -cp "$DSL:$COMMON:<deps>" org.unlaxer.dsl.CodegenMain \
//        --grammar story/code/calc.ubnf --output gen --generators Parser,AST,Mapper
//   # 2) 生成物 + この walker をコンパイルして実行
//   javac -cp "$COMMON" -d out $(find gen -name '*.java') story/code/TinyCalcRun.java
//   java  -cp "out:$COMMON" TinyCalcRun
//   →  1+2*3 = 7.0   2*3+4*5 = 26.0   10-2-3 = 5.0
//
// ポイント：手組み（第二〜三章）では公開ビューが平らで歩けなかったが、
// UBNF から生成した AST は「× が + の下」という構造を保っている＝歩けば優先順位どおりになる。
//   AddExpr(left: MulExpr, op:[...], right:[MulExpr...])   ← 足し算の階
//   MulExpr(left: String,  op:[...], right:[String...])    ← かけ算の階（葉は数の文字列）
import java.util.List;
import story.calc.TinyCalcAST;
import story.calc.TinyCalcAST.AddExpr;
import story.calc.TinyCalcAST.MulExpr;
import story.calc.TinyCalcMapper;

public class TinyCalcRun {
  static double evalAdd(AddExpr e) {
    double acc = evalMul(e.left());
    List<String> ops = e.op();
    List<MulExpr> rs = e.right();
    for (int i = 0; i < ops.size(); i++) {
      double r = evalMul(rs.get(i));
      acc = ops.get(i).equals("+") ? acc + r : acc - r;
    }
    return acc;
  }

  static double evalMul(MulExpr e) {
    double acc = Double.parseDouble(e.left());
    List<String> ops = e.op();
    List<String> rs = e.right();
    for (int i = 0; i < ops.size(); i++) {
      double r = Double.parseDouble(rs.get(i));
      acc = ops.get(i).equals("*") ? acc * r : acc / r;
    }
    return acc;
  }

  static void run(String s) {
    TinyCalcAST ast = TinyCalcMapper.parse(s);
    double v = (ast instanceof AddExpr a) ? evalAdd(a) : Double.NaN;
    System.out.println(s + " = " + v + "   (AST: " + ast.getClass().getSimpleName() + ")");
  }

  public static void main(String[] x) {
    run("1+2*3");
    run("2*3+4*5");
    run("10-2-3");
  }
}
