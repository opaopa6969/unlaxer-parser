// 第八章「二つの剣・其の二（Javaコード生成）」の、実際に動くコード。
// 木を「その場で歩く」のでなく、いちど Java の式に“翻訳”する＝二本目の剣。
// 肝は、葉(leaf)をそのまま写さず Java に翻訳すること：$price は Java の変数ではない。
// 生成(calc-var.ubnf)→ javac → java TinyCalcEmit で、ダメな生成と正しい生成を並べて見られる。
//   ダメ : (($price)+($price*$tax/100))            ← コンパイル不能
//   正   : ((ctx.get("price"))+(ctx.get("price")*ctx.get("tax")/100))
import java.util.List;
import story.calcvar.TinyCalcVarAST;
import story.calcvar.TinyCalcVarAST.AddExpr;
import story.calcvar.TinyCalcVarAST.MulExpr;
import story.calcvar.TinyCalcVarMapper;
public class TinyCalcEmit {
  // 葉を Java 式に翻訳する。ここが二本目の剣の肝。
  static String leafNaive(String t){ return t.strip(); }                 // ダメ：そのまま写す
  static String leafGood(String t){                                      // 正：$x を文脈アクセスに翻訳
    t=t.strip();
    return t.startsWith("$") ? "ctx.get(\""+t.substring(1)+"\")" : t;
  }
  interface Leaf { String of(String t); }
  static String emitMul(MulExpr e, Leaf leaf){
    StringBuilder sb=new StringBuilder("(").append(leaf.of(e.left()));
    List<String> ops=e.op(); List<String> rs=e.right();
    for(int i=0;i<ops.size();i++) sb.append(ops.get(i)).append(leaf.of(rs.get(i)));
    return sb.append(")").toString();
  }
  static String emitAdd(AddExpr e, Leaf leaf){
    StringBuilder sb=new StringBuilder("(").append(emitMul(e.left(),leaf));
    List<String> ops=e.op(); List<MulExpr> rs=e.right();
    for(int i=0;i<ops.size();i++) sb.append(ops.get(i)).append(emitMul(rs.get(i),leaf));
    return sb.append(")").toString();
  }
  static String emit(String src, Leaf leaf){
    TinyCalcVarAST ast=TinyCalcVarMapper.parse(src);
    return (ast instanceof AddExpr a)? emitAdd(a,leaf) : "??";
  }
  public static void main(String[] x){
    String f="$price+$price*$tax/100";
    System.out.println("式      : "+f);
    System.out.println("ダメな生成: "+emit(f, TinyCalcEmit::leafNaive)+"   ← Java に $price なんて変数はない（コンパイル不能）");
    System.out.println("正しい生成: "+emit(f, TinyCalcEmit::leafGood)+"   ← 葉を ctx.get(...) に翻訳");
  }
}
