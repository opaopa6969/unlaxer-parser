import target.ReuseParser;
import java.nio.file.*;

/** Calls a locally generated private engine; no generated source is checked in. */
public final class UbnfcProbe {
    public static void main(String[] args) throws Exception {
        String source = Files.readString(Path.of(args[0]));
        if (args.length == 3) System.out.println("SNAPSHOT\t" + args[1] + "\t" + args[2] + "\t" + Canonical.hex(source));
        var result = ReuseParser.parse(source, target.api.ParseOptions.DEFAULT.withDiagnostics());
        if (!result.ok()) {
            var d = result.diagnostics();
            System.out.println("REJECT\t" + d.offset() + "\t" + d.farthestCp() + "\t" + Canonical.hex(String.join("|", d.expected())) + "\t" + Canonical.hex(String.join("|", d.farthestExpected())));
            return;
        }
        var node = result.ast().orElseThrow();
        java.util.function.Function<Object,int[]> spans = n -> {
            var p = result.nodeSpans().get(n);
            if (p == null) throw new AssertionError("missing span");
            return new int[]{p.start(), p.end()};
        };
        String before = Canonical.ast(node, source, spans);
        ReuseParser.parse("p<a><b>[]!");
        if (!before.equals(Canonical.ast(node, source, spans))) throw new AssertionError("retained result changed");
        System.out.println("ACCEPT\t" + before);
    }
}
