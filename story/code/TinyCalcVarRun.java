// 第七章「二つの剣・其の一（評価器）」の、実際に動くコード。
// 文法に変数 $name を足し、実行のたびに「文脈(context)」から値を引いて評価する。
// 生成: CodegenMain --grammar story/code/calc-var.ubnf --output gen --generators Parser,AST,Mapper
// 実行: javac -cp "$COMMON" -d out $(find gen -name '*.java') story/code/TinyCalcVarRun.java && java -cp out:$COMMON TinyCalcVarRun
//   $price*2 = 200.0 / 同じ式に price=100,tax=8 なら 108、price=250,tax=10 なら 275（一本の木・多数の値）
//   $unknown+1 → 変数が未定義（よくある落とし穴）
import java.util.List;
import java.util.Map;
import story.calcvar.TinyCalcVarAST;
import story.calcvar.TinyCalcVarAST.AddExpr;
import story.calcvar.TinyCalcVarAST.MulExpr;
import story.calcvar.TinyCalcVarMapper;
public class TinyCalcVarRun {
  // 文脈：変数の「いまの値」。実行のたびに、ちがう値が流れこむ。
  static Map<String,Double> context;
  static double leaf(String t){
    t=t.strip();
    if(t.startsWith("$")){
      Double v=context.get(t.substring(1));
      if(v==null) throw new RuntimeException("変数が未定義: "+t);  // よくある落とし穴
      return v;
    }
    return Double.parseDouble(t);
  }
  static double evalMul(MulExpr e){
    double acc=leaf(e.left()); List<String> ops=e.op(); List<String> rs=e.right();
    for(int i=0;i<ops.size();i++){ double r=leaf(rs.get(i)); acc=ops.get(i).equals("*")?acc*r:acc/r; }
    return acc;
  }
  static double evalAdd(AddExpr e){
    double acc=evalMul(e.left()); List<String> ops=e.op(); List<MulExpr> rs=e.right();
    for(int i=0;i<ops.size();i++){ double r=evalMul(rs.get(i)); acc=ops.get(i).equals("+")?acc+r:acc-r; }
    return acc;
  }
  static void run(String s){
    TinyCalcVarAST ast=TinyCalcVarMapper.parse(s);
    double v=(ast instanceof AddExpr a)?evalAdd(a):Double.NaN;
    System.out.println(s+" = "+v);
  }
  public static void main(String[] x){
    context=Map.of("price",100.0,"tax",8.0);
    run("$price*2");          // 200
    run("$price+$price*$tax/100"); // 100 + 100*8/100 = 108
    context=Map.of("price",250.0,"tax",10.0);
    run("$price+$price*$tax/100"); // 250 + 25 = 275  ← 同じ式・ちがう値
    try { run("$unknown+1"); } catch(RuntimeException e){ System.out.println("$unknown+1 → "+e.getMessage()); }
  }
}
