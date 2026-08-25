// 第十章「指数の影」/第十一章「記憶という魔法」の、実際に動くコード。
//   CP=$HOME/.m2/repository/org/unlaxer/unlaxer-common/3.0.11/unlaxer-common-3.0.11.jar
//   javac -cp "$CP" -d out story/code/PackratDemo.java && java -cp "out:$CP" PackratDemo
// 実測例（環境による）:
//   深さ      メモ化OFF      メモ化ON
//   8           149ms          25ms
//   12          691ms           2ms
//   16         4087ms           0ms   ← +2深さでOFFは約2倍＝指数。ONは平ら。
//   深さ40（OFFなら3^40で実行不能）も、メモ化ONなら 1ms。

// 第十章「指数の影」/第十一章「記憶という魔法」の、実際に動くコード。
// 曖昧な入れ子文法（各 '(' で「数式か?三項か?」と全部試す）。終端の無い深いネスト入力は、
// メモ化なしだと同じ行き止まりを何度も歩いて 3^深さ で爆発。メモ化ありだと線形。
public class PackratDemo {
  public static class Expr extends LazyChoice { public Parsers getLazyParsers(){ return new Parsers(Parser.get(A.class), Parser.get(B.class)); } }
  public static class A    extends LazyChain  { public Parsers getLazyParsers(){ return new Parsers(Parser.get(Inner.class), new WordParser("!")); } }
  public static class B    extends LazyChain  { public Parsers getLazyParsers(){ return new Parsers(Parser.get(Inner.class), new WordParser("?")); } }
  public static class Inner extends LazyChoice{ public Parsers getLazyParsers(){ return new Parsers(Parser.get(Paren.class), new WordParser("x")); } }
  public static class Paren extends LazyChain { public Parsers getLazyParsers(){ return new Parsers(new WordParser("("), Parser.get(Expr.class), new WordParser(")")); } }

  static String nested(int depth){
    StringBuilder b=new StringBuilder();
    for(int i=0;i<depth;i++) b.append('(');
    b.append('x');
    for(int i=0;i<depth;i++) b.append(')');
    return b.toString();  // (^depth x )^depth … 終端(! or ?)が無いので必ず失敗→全探索を誘発
  }
  static long timeParse(String src, boolean memoize){
    long start=System.nanoTime();
    ParseContext ctx = memoize
        ? new ParseContext(StringSource.createRootSource(src), ParseContext.memoize())
        : new ParseContext(StringSource.createRootSource(src));
    try(ctx){ Parser.get(Expr.class).parse(ctx); }
    return (System.nanoTime()-start)/1_000_000;
  }
  public static void main(String[] a){
    System.out.printf("%-7s %14s %14s%n","深さ","メモ化OFF","メモ化ON");
    for(int d : new int[]{8,10,12,14,16}){
      String s=nested(d);
      long off=timeParse(s,false);
      long on =timeParse(s,true);
      System.out.printf("%-7d %12dms %12dms%n", d, off, on);
    }
    System.out.println("\n深さ40（OFFなら 3^40 で実行不能）を、メモ化ONだけで:");
    System.out.printf("  深さ40  メモ化ON = %dms%n", timeParse(nested(40), true));
  }
}
