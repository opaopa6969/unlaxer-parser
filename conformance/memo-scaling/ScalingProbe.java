import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.net.URLClassLoader;
import java.lang.reflect.*;
import org.unlaxer.*;
import org.unlaxer.context.*;
import org.unlaxer.dsl.codegen.ParserScalingSmokeTest;
import org.unlaxer.parser.Parser;

public class ScalingProbe {
    static long optional(Object target, String name) {
        if (target == null) return 0;
        try { return ((Number) target.getClass().getMethod(name).invoke(target)).longValue(); }
        catch (NoSuchMethodException absent) { return -1; }
        catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    static String run(Parser parser, int scale, Memoization memo) {
        String input = "let abc = 1+2*3-(4+5)/6; ".repeat(16 * scale);
        var bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long allocatedBefore = bean.getThreadAllocatedBytes(Thread.currentThread().getId());
        long before = System.nanoTime();
        try (var c = ParseContext.withOptions(StringSource.createRootSource(input),
                ParseOptions.withMemoization(memo).withDiagnostics(ParseOptions.Diagnostics.DETAILED_ON_FAILURE))) {
            c.enableTransactionMetrics();
            Parsed parsed = parser.parse(c);
            if (!parsed.isSucceeded() || c.position() != input.length() || c.matchedPosition() != input.length())
                throw new AssertionError("parse/EOF mismatch: " + scale + " " + parsed + " " + c.position() + "/" + c.matchedPosition());
            long nanos = System.nanoTime() - before;
            long allocated = bean.getThreadAllocatedBytes(Thread.currentThread().getId()) - allocatedBefore;
            var tx = c.snapshotTransactionMetrics(); var t = c.getPackratMemoTable();
            return String.join("\t", ""+scale, ""+input.length(), memo.toString(), ""+tx.opened(), ""+tx.rolledBack(),
                ""+optional(t,"successHits"), ""+optional(t,"failureHits"), ""+optional(t,"maxEntryCount"),
                ""+optional(t,"entryCount"), ""+optional(t,"successEntryCount"), ""+optional(t,"successRetainedBytes"),
                ""+optional(t,"successBudgetMisses"), ""+optional(t,"failureRetainedBytes"), ""+optional(t,"failureBudgetMisses"),
                ""+optional(t,"evictedCount"), ""+optional(t,"evictionUnderruns"), ""+optional(t,"deadOnArrival"),
                ""+optional(t,"watermark"), ""+optional(t,"window"), ""+allocated, ""+nanos);
        }
    }
    public static void main(String[] args) throws Exception {
        var test = new ParserScalingSmokeTest(); test.temporary.create();
        Method compile = ParserScalingSmokeTest.class.getDeclaredMethod("compile"); compile.setAccessible(true);
        try (var loader = (URLClassLoader) compile.invoke(test)) {
            var parser = (Parser) loader.loadClass("org.unlaxer.scaling.generated.ScaleParsers").getMethod("getRootParser").invoke(null);
            System.out.println("scale\tbytes\tmemo\topened\trolledBack\tsuccessHits\tfailureHits\tmaxEntries\tentries\tsuccessEntries\tsuccessCharge\tsuccessBudgetMisses\tfailureCharge\tfailureBudgetMisses\tevictions\tunderruns\tdeadOnArrival\twatermark\twindow\tallocatedBytes\tparseNanos");
            for (Memoization memo : Memoization.values()) for (String raw : args) {
                int scale = Integer.parseInt(raw); run(parser,scale,memo);
                System.out.println(run(parser,scale,memo));
            }
        } finally { test.temporary.delete(); }
    }
}
