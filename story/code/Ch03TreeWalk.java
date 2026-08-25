// 第三章「木の中を歩く」の、実際に動くコード。
//   CP=$HOME/.m2/repository/org/unlaxer/unlaxer-common/3.0.11/unlaxer-common-3.0.11.jar
//   javac -cp "$CP" -d out story/code/Ch03TreeWalk.java && java -cp "out:$CP" Ch03TreeWalk
//
// 灯里の落とし穴を、そのまま動かして体験できる。
// 第二章で組んだ三階建ての“手組みコンビネータ”をパースすると——Unlaxer の公開 AST ビュー
// (Token.filteredChildren) は、Chain/Choice などの構造コンビネータを畳んで、葉トークンだけの
// 「平らな列」を返す。だから木を歩いて評価しようとすると、左から順に計算して 1+2*3=9 になる。
// （構造を保ったまま評価したいなら、ルールに名前を付けるか、UBNF から生成する＝第五章へ。）
import org.unlaxer.StringSource;
import org.unlaxer.Token;
import org.unlaxer.context.ParseContext;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.ascii.DivisionParser;
import org.unlaxer.parser.ascii.MinusParser;
import org.unlaxer.parser.ascii.PlusParser;
import org.unlaxer.parser.combinator.Chain;
import org.unlaxer.parser.combinator.Choice;
import org.unlaxer.parser.combinator.OneOrMore;
import org.unlaxer.parser.combinator.ZeroOrMore;
import org.unlaxer.parser.elementary.MultipleParser;
import org.unlaxer.parser.posix.DigitParser;

public class Ch03TreeWalk {

  static Parser layeredExpression() {
    Parser factor = new OneOrMore(DigitParser.class);
    Parser term = new Chain(factor,
        new ZeroOrMore(new Chain(
            new Choice(MultipleParser.class, DivisionParser.class), factor)));
    return new Chain(term,
        new ZeroOrMore(new Chain(
            new Choice(PlusParser.class, MinusParser.class), term)));
  }

  public static void main(String[] args) {
    ParseContext ctx = new ParseContext(StringSource.createRootSource("1+2*3"));
    Token root;
    try {
      root = layeredExpression().parse(ctx).getRootToken(true);
    } finally {
      ctx.close();
    }

    System.out.println("=== filteredChildren（公開ASTビュー）は平ら ===");
    for (Token child : root.filteredChildren) {
      System.out.println("  " + child.getParser().getClass().getSimpleName()
          + "  「" + child.getToken().orElse("") + "」");
    }

    System.out.println("\n=== 平らな列を、左から順に評価すると…… ===");
    double leftToRight = walkFlatLeftToRight(root);
    System.out.println("  1+2*3 = " + leftToRight + "  ← かけ算が先にならない（落とし穴）");
  }

  // 平らな葉の列 [1, +, 2, *, 3] を、ただ左から計算する（＝優先順位が消える）。
  static double walkFlatLeftToRight(Token root) {
    Double acc = null;
    String pendingOp = null;
    for (Token leaf : root.filteredChildren) {
      String text = leaf.getToken().orElse("").strip();
      if (text.isEmpty()) {
        continue;
      }
      if (text.matches("[0-9]+")) {
        double n = Double.parseDouble(text);
        if (acc == null) {
          acc = n;
        } else {
          acc = switch (pendingOp) {
            case "+" -> acc + n;
            case "-" -> acc - n;
            case "*" -> acc * n;
            case "/" -> acc / n;
            default -> n;
          };
        }
      } else {
        pendingOp = text;
      }
    }
    return acc == null ? Double.NaN : acc;
  }
}
