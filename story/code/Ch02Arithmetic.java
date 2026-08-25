// 第二章「1足す2かける3」の、実際に動くコード。
//   $ CP=$HOME/.m2/repository/org/unlaxer/unlaxer-common/3.0.11/unlaxer-common-3.0.11.jar
//   $ javac -cp "$CP" -d out story/code/Ch02Arithmetic.java
//   $ java  -cp "out:$CP" Ch02Arithmetic
//
// 灯里が学んだ「優先順位＝形の深さ（三階建て）」を、本物の Unlaxer コンビネータで組む。
//   Expression(3階・＋−) ⊃ Term(2階・×÷) ⊃ Factor(1階・数字)
// と入れ子にすると、1+2*3 の木は「＋ の右の枝が ×」になり、× が先に組み上がる
// ＝先に計算される。平らな一階建ての文法だと、これが起きずに 9 になってしまう。
//
// ※ Factor ::= '(' Expression ')' のカッコ再帰は、Unlaxer では LazyChoice / LazyChain
//   （遅延コンビネータ）で自分自身を参照して書く。ここでは優先順位の核を見せるため
//   数字のみの Factor にしている（第六章のカッコは、その遅延参照の話）。
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

public class Ch02Arithmetic {

  // Factor ::= 数字
  static Parser factor() {
    return new OneOrMore(DigitParser.class);
  }

  // Term ::= Factor { (*|/) Factor }      ← 2階：かけ算・割り算
  static Parser term() {
    return new Chain(factor(),
        new ZeroOrMore(new Chain(
            new Choice(MultipleParser.class, DivisionParser.class), factor())));
  }

  // Expression ::= Term { (+|-) Term }    ← 3階：足し算・引き算（いちばん上）
  static Parser expression() {
    return new Chain(term(),
        new ZeroOrMore(new Chain(
            new Choice(PlusParser.class, MinusParser.class), term())));
  }

  // 比較用：平らな一階建て（優先順位を表現できない文法）
  //   Flat ::= 数字 { (+|-|*|/) 数字 }
  static Parser flat() {
    return new Chain(new OneOrMore(DigitParser.class),
        new ZeroOrMore(new Chain(
            new Choice(PlusParser.class, MinusParser.class,
                       MultipleParser.class, DivisionParser.class),
            new OneOrMore(DigitParser.class))));
  }

  public static void main(String[] args) {
    System.out.println("===== 三階建て（Expression ⊃ Term ⊃ Factor）=====");
    for (String src : new String[] {"1+2*3", "2*3+4*5"}) {
      printParse("三階建て", expression(), src);
    }
    System.out.println("\n===== 平らな一階建て（同じ式でも、× が + の下に入らない）=====");
    printParse("一階建て", flat(), "1+2*3");
  }

  static void printParse(String label, Parser parser, String src) {
    ParseContext ctx = new ParseContext(StringSource.createRootSource(src));
    Token root;
    try {
      root = parser.parse(ctx).getRootToken(true);
    } finally {
      ctx.close();
    }
    System.out.println("\n[" + label + "] 式: " + src);
    printTree(root, 0);
  }

  // 木の中を歩いて、構造を表示する（第三章「木の中を歩く」の前ふり）。
  static void printTree(Token t, int depth) {
    String name = t.getParser().getClass().getSimpleName();
    String text = t.getToken().orElse("");
    System.out.println("  ".repeat(depth) + "・" + name
        + (text.isBlank() ? "" : "  「" + text + "」"));
    for (Token child : t.filteredChildren) {
      printTree(child, depth + 1);
    }
  }
}
