import experiment.reuse.ReuseMapper;
import java.nio.file.*;
import org.unlaxer.context.ParseOptions;

public final class ClassicProbe {
    public static void main(String[] args) throws Exception {
        String source = Files.readString(Path.of(args[0]));
        if (args.length == 3) System.out.println("SNAPSHOT\t" + args[1] + "\t" + args[2] + "\t" + Canonical.hex(source));
        var diagnostic = ReuseMapper.diagnose(source, ParseOptions.DEFAULT.withDiagnostics(ParseOptions.Diagnostics.DETAILED));
        if (diagnostic.isPresent()) {
            var d = diagnostic.orElseThrow();
            System.out.println("REJECT\t" + d.offset() + "\t" + d.farthestOffset() + "\t" + Canonical.hex(String.join("|", d.expected())) + "\t" + Canonical.hex(String.join("|", d.farthestExpected())));
            return;
        }
        var owned = ReuseMapper.parseWithSourceMap(source);
        String before = Canonical.ast(owned.ast(), source, n -> owned.sourceSpanOf(n).orElseThrow());
        ReuseMapper.parseWithSourceMap("p<a><b>[]!");
        String after = Canonical.ast(owned.ast(), source, n -> owned.sourceSpanOf(n).orElseThrow());
        if (!before.equals(after)) throw new AssertionError("retained source map changed");
        System.out.println("ACCEPT\t" + before);
    }
}
