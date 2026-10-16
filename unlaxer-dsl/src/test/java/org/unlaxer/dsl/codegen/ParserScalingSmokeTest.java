package org.unlaxer.dsl.codegen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.unlaxer.Parsed;
import org.unlaxer.StringSource;
import org.unlaxer.context.Memoization;
import org.unlaxer.context.PackratMemoTable;
import org.unlaxer.context.ParseContext;
import org.unlaxer.context.ParseOptions;
import org.unlaxer.context.ParseOptions.Diagnostics;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.bootstrap.UBNFParsers;
import org.unlaxer.parser.Parser;

/**
 * 「パーサ本体は線形」（{@code docs/performance-tuning-ja.md} ケース 32〜36）を数で固定する smoke test。
 *
 * <p>ケース 32〜36 は毎回「GC pause を引いた µs/byte 倍率は ×1.04〜1.08 で、パーサ本体は線形」と
 * 結論したが、根拠は手元の 1 回きりのベンチだった。時間は共有ホストの負荷で 4 倍も振れるので
 * CI では測れない。代わりに **負荷に依らない量**（transaction 数・memo hit 数・memo entry 数・
 * {@code getThreadAllocatedBytes} の割当）を x1 / x4 / x16（生成系は x64 まで）で取り、**1 byte あたりの値が
 * ×1.3 を超えて増えないこと**を突き合わせる。超線形に戻ったらここで落ちる。
 *
 * <p>×1.3 は測定誤差ではなく余裕。ケース 32〜36 の実測は µs/byte で ×1.04〜1.08、
 * 割当は入力長にほぼ比例（x1→x64 で ×1.0 前後）だった。二次の項が入れば x1→x16 で
 * 16 倍近くになるので、回帰を検出する smoke 条件になる。
 *
 * <p>有限 success memo の予算が飽和すると再評価が増え、rollback/byte は小入力と
 * 同じ係数にはならない。ON の rollback と transaction 数は、独立に線形性を検証した
 * 同入力 OFF の値以下として直接制限する。他の量の ×1.3 条件は維持する。
 */
public class ParserScalingSmokeTest {

    /** 1 byte あたりの値が x1 から何倍まで増えてよいか。 */
    private static final double LIMIT = 1.3;

    private static final String PACKAGE = "org.unlaxer.scaling.generated";

    /** 組み込みトークンだけを使うので、生成規則すべてに SafeFailureMemoizable が付く。 */
    private static final java.nio.file.Path CORPUS =
        java.nio.file.Path.of("..", "conformance", "memo-scaling");

    /** x1 が 16 文、x4 が 64 文、x16 が 256 文、x64 が 1024 文。 */
    private static final String STATEMENT = "let abc = 1+2*3-(4+5)/6; ";

    /**
     * UBNF 自身は文法ブロックを並べれば厳密に x 倍になるので、連結 fixture に向く。
     * 連番は 3 桁に揃える。桁が増えるとブロック長が変わり、byte で厳密な x 倍にならない。
     */
    private static final String UBNF_BLOCK = """
        grammar Block%1$03d {
          token WORD = org.unlaxer.parser.clang.IdentifierParser
          @root Root%1$03d ::= 'a' { 'b' | '(' Root%1$03d ')' } WORD ;
          Other%1$03d ::= 'x' [ 'y' ] { 'z' } ;
        }
        """;

    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    /** 1 回の parse で観測する、負荷に依らない量。 */
    private record Counters(int bytes, long transactionsOpened, long transactionsRolledBack,
            int memoSuccessHits, int memoFailureHits, int memoMaxEntryCount, long allocatedBytes,
            int successEntries, long successCharge, long successBudgetMisses,
            int memoEvictions, int memoUnderruns) {}

    // =========================================================================
    // 生成パーサ（memo あり）
    // =========================================================================

    @Test
    public void generatedParserCountersStayLinearPerByte() throws Exception {
        try (URLClassLoader loader = compile()) {
            Parser root = (Parser) loader.loadClass(PACKAGE + ".ScaleParsers")
                .getMethod("getRootParser").invoke(null);
            Map<String, Counters> control = new LinkedHashMap<>();
            Map<String, Counters> byScale = new LinkedHashMap<>();
            var cases = java.nio.file.Files.readAllLines(CORPUS.resolve("cases.tsv"));
            assertEquals("all handwritten scales must be exercised", 5, cases.size());
            for (String line : cases.subList(1, cases.size())) {
                String[] columns = line.split("\t");
                int scale = Integer.parseInt(columns[0]);
                String input = STATEMENT.repeat(Integer.parseInt(columns[1]));
                assertEquals(Integer.parseInt(columns[2]), input.length());
                assertEquals(columns[2], columns[3]);
                assertEquals("0", columns[4]);
                assertEquals(columns[2], columns[5]);
                control.put("x" + scale, measure(root, input, Memoization.OFF));
                byScale.put("x" + scale, measure(root, input, Memoization.SAFE_FAILURES));
            }
            assertTrue("memo が効いていないので memo 次元の比較にならない",
                byScale.get("x1").memoSuccessHits() > 0);
            assertPerByteGrowth(control, "生成パーサ (Scale, OFF)");
            assertPerByteGrowth(byScale, "生成パーサ (Scale, SAFE_FAILURES)", false);
            assertMemoWorkBound(control, byScale);
            Counters saturated = byScale.get("x64");
            assertTrue("default budget saturation must be exercised: " + byScale,
                saturated.successBudgetMisses() > 0);
            assertTrue(saturated.successEntries() <= 65536);
            assertTrue(saturated.successCharge() <= 16L * 1024 * 1024);
            assertTrue("rolling eviction must be exercised", saturated.memoEvictions() > 0);
            var report = new ArrayList<String>(List.of(
                "scale\tmemo\tconsumed_cp\tmatched_cp\tcst_start_cp\tcst_end_cp\topened\trolled_back\tactual_success_hits\tfailure_hits\tmax_entries\tsuccess_entries\tsuccess_charge\tbudget_misses\tevictions\tunderruns\tallocated_bytes"));
            for (String scale : control.keySet()) for (var mode : List.of(control, byScale)) {
                Counters c = mode.get(scale);
                report.add(String.join("\t", scale, mode == control ? "OFF" : "ON",
                    "" + c.bytes(), "" + c.bytes(), "0", "" + c.bytes(),
                    "" + c.transactionsOpened(), "" + c.transactionsRolledBack(),
                    "" + c.memoSuccessHits(), "" + c.memoFailureHits(), "" + c.memoMaxEntryCount(),
                    "" + c.successEntries(), "" + c.successCharge(), "" + c.successBudgetMisses(),
                    "" + c.memoEvictions(), "" + c.memoUnderruns(), "" + c.allocatedBytes()));
            }
            java.nio.file.Files.write(java.nio.file.Path.of("target/memo-scaling-java.tsv"), report);
        }
    }

    @Test
    public void scalingOracleRejectsInjectedQuadraticWorkAndAllocation() {
        var control = new LinkedHashMap<String, Counters>();
        var quadraticWork = new LinkedHashMap<String, Counters>();
        var quadraticAllocation = new LinkedHashMap<String, Counters>();
        for (int scale : List.of(1, 4, 16, 64)) {
            Counters linear = synthetic(scale, 100L * scale, 1000L * scale);
            control.put("x" + scale, linear);
            quadraticWork.put("x" + scale, synthetic(scale, 50L * scale * scale, 1000L * scale));
            quadraticAllocation.put("x" + scale, synthetic(scale, 100L * scale, 1000L * scale * scale));
        }
        assertPerByteGrowth(control, "linear control");
        assertMemoWorkBound(control, control);
        org.junit.Assert.assertThrows(AssertionError.class,
            () -> assertMemoWorkBound(control, quadraticWork));
        org.junit.Assert.assertThrows(AssertionError.class,
            () -> assertPerByteGrowth(quadraticAllocation, "injected quadratic allocation", false));
    }

    private static Counters synthetic(int scale, long rollbacks, long allocation) {
        return new Counters(400 * scale, 1000L * scale, rollbacks,
            100 * scale, 0, 100 * scale, allocation, 0, 0, 0, 0, 0);
    }

    // =========================================================================
    // UBNF bootstrap（このリポジトリが実際に出荷する手書き combinator）
    // =========================================================================

    @Test
    public void ubnfBootstrapCountersStayLinearPerByte() {
        Parser root = UBNFParsers.getRootParser();
        Map<String, Counters> byScale = new LinkedHashMap<>();
        for (int scale : List.of(1, 4, 16)) {
            StringBuilder input = new StringBuilder();
            for (int i = 0; i < 4 * scale; i++) {
                input.append(UBNF_BLOCK.formatted(i));
            }
            // 連結した入力が本当に受理されること（= 測っているのが実際の parse であること）。
            assertEquals(4 * scale, UBNFMapper.parse(input.toString()).grammars().size());
            byScale.put("x" + scale, measure(root, input.toString(), Memoization.OFF));
        }
        assertPerByteGrowth(byScale, "UBNF bootstrap (memo OFF)");
    }

    // =========================================================================
    // 突き合わせ
    // =========================================================================

    private static void assertPerByteGrowth(Map<String, Counters> byScale, String label) {
        assertPerByteGrowth(byScale, label, true);
    }

    private static void assertMemoWorkBound(Map<String, Counters> control,
            Map<String, Counters> memo) {
        assertEquals(control.keySet(), memo.keySet());
        for (var entry : memo.entrySet()) {
            Counters bound = control.get(entry.getKey());
            Counters actual = entry.getValue();
            assertEquals(bound.bytes(), actual.bytes());
            assertTrue(entry.getKey() + " transaction work exceeds linear OFF control: " + actual,
                actual.transactionsOpened() <= bound.transactionsOpened());
            assertTrue(entry.getKey() + " rollback work exceeds linear OFF control: " + actual,
                actual.transactionsRolledBack() <= bound.transactionsRolledBack());
        }
    }

    private static void assertPerByteGrowth(Map<String, Counters> byScale, String label,
            boolean checkRollbacks) {
        Counters base = byScale.get("x1");
        // 比が取れないほど小さい / 0 のままなら、このテストは何も見ていない。
        assertTrue(label + ": transaction を数えられていない", base.transactionsOpened() > 100);
        assertTrue(label + ": 割当を数えられていない", base.allocatedBytes() > 0);
        assertEquals(label + ": x16 の入力長が x1 の 16 倍になっていない",
            base.bytes() * 16, byScale.get("x16").bytes());
        List<String> failures = new ArrayList<>();
        for (Map.Entry<String, Counters> entry : byScale.entrySet()) {
            Counters counters = entry.getValue();
            check(failures, label, entry.getKey(), "transactions.opened",
                base.transactionsOpened(), base.bytes(), counters.transactionsOpened(), counters.bytes());
            if (checkRollbacks) check(failures, label, entry.getKey(), "transactions.rolledBack",
                base.transactionsRolledBack(), base.bytes(),
                counters.transactionsRolledBack(), counters.bytes());
            check(failures, label, entry.getKey(), "memo.successHits",
                base.memoSuccessHits(), base.bytes(), counters.memoSuccessHits(), counters.bytes());
            check(failures, label, entry.getKey(), "memo.failureHits",
                base.memoFailureHits(), base.bytes(), counters.memoFailureHits(), counters.bytes());
            check(failures, label, entry.getKey(), "memo.maxEntryCount",
                base.memoMaxEntryCount(), base.bytes(), counters.memoMaxEntryCount(), counters.bytes());
            check(failures, label, entry.getKey(), "allocatedBytes",
                base.allocatedBytes(), base.bytes(), counters.allocatedBytes(), counters.bytes());
        }
        assertEquals(label + ": 1 byte あたりの値が ×" + LIMIT + " を超えて増えた"
            + "（超線形に戻っていないか docs/performance-tuning-ja.md ケース 32〜36 と突き合わせる）"
            + " 実測=" + byScale, List.of(), failures);
    }

    private static void check(List<String> failures, String label, String scale, String name,
            long baseValue, int baseBytes, long value, int bytes) {
        if (baseValue == 0) {
            // x1 で 0 なら比が取れない。その代わり x16 でも 0 のままであることだけ求める。
            if (value != 0) {
                failures.add(label + " " + scale + " " + name + ": x1 が 0 なのに " + value);
            }
            return;
        }
        double basePerByte = (double) baseValue / baseBytes;
        double perByte = (double) value / bytes;
        double ratio = perByte / basePerByte;
        if (ratio > LIMIT) {
            failures.add(String.format("%s %s %s: %.3f/byte -> %.3f/byte (×%.3f)",
                label, scale, name, basePerByte, perByte, ratio));
        }
    }

    // =========================================================================
    // 計測
    // =========================================================================

    private static Counters measure(Parser root, String input, Memoization memoization) {
        var threads = ManagementFactory.getThreadMXBean();
        Assume.assumeTrue("getThreadAllocatedBytes が使えない JVM",
            threads instanceof com.sun.management.ThreadMXBean);
        var sunThreads = (com.sun.management.ThreadMXBean) threads;
        Assume.assumeTrue("スレッド割当計測が無効", sunThreads.isThreadAllocatedMemoryEnabled());
        long id = Thread.currentThread().getId(); // threadId() は Java 19+

        // 割当は負荷に依らないが、初回は classload とキャッシュ充填が混ざる。
        parseOnce(root, input, memoization);

        long before = sunThreads.getThreadAllocatedBytes(id);
        Counters counters = parseOnce(root, input, memoization);
        long allocated = sunThreads.getThreadAllocatedBytes(id) - before;
        return new Counters(input.length(), counters.transactionsOpened(),
            counters.transactionsRolledBack(), counters.memoSuccessHits(), counters.memoFailureHits(),
            counters.memoMaxEntryCount(), allocated, counters.successEntries(),
            counters.successCharge(), counters.successBudgetMisses(), counters.memoEvictions(),
            counters.memoUnderruns());
    }

    private static Counters parseOnce(Parser root, String input, Memoization memoization) {
        ParseOptions options =
            ParseOptions.withMemoization(memoization).withDiagnostics(Diagnostics.DETAILED_ON_FAILURE);
        try (ParseContext context =
                ParseContext.withOptions(StringSource.createRootSource(input), options)) {
            context.enableTransactionMetrics();
            Parsed parsed = root.parse(context);
            assertTrue("fixture が受理されない: " + input.substring(0, Math.min(60, input.length())),
                parsed.isSucceeded());
            assertEquals("full-input consumed cursor", input.length(), context.position());
            assertEquals("full-input matched cursor", input.length(), context.matchedPosition());
            var rootToken = context.getCurrent().getTokens().stream()
                .filter(token -> token.parser == root).findFirst().orElseThrow();
            assertEquals("CST input offset", 0, rootToken.source.offsetFromRoot().value());
            assertEquals("CST input text", input, rootToken.source.toString());
            var metrics = context.snapshotTransactionMetrics();
            PackratMemoTable table = context.getPackratMemoTable();
            return new Counters(input.length(), metrics.opened(), metrics.rolledBack(),
                table == null ? 0 : table.successHits(),
                table == null ? 0 : table.failureHits(),
                table == null ? 0 : table.maxEntryCount(), 0L,
                table == null ? 0 : table.successEntryCount(),
                table == null ? 0 : table.successRetainedBytes(),
                table == null ? 0 : table.successBudgetMisses(),
                table == null ? 0 : table.evictedCount(),
                table == null ? 0 : table.evictionUnderruns());
        }
    }

    // =========================================================================
    // 文法を生成してコンパイルする
    // =========================================================================

    private URLClassLoader compile() throws Exception {
        var grammar = UBNFMapper.parse(java.nio.file.Files.readString(CORPUS.resolve("scale.ubnf"))).grammars().get(0);
        var sources = List.of(new ParserGenerator().generate(grammar),
            new ASTGenerator().generate(grammar), new MapperGenerator().generate(grammar));
        String directory = PACKAGE.replace('.', '/');
        var units = sources.stream().map(source -> new SimpleJavaFileObject(
                URI.create("string:///" + directory + "/" + source.className() + ".java"),
                JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignored) {
                return source.source();
            }
        }).toList();
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var output = temporary.newFolder().toPath();
        try (var manager = compiler.getStandardFileManager(diagnostics, null, null)) {
            boolean success = compiler.getTask(null, manager, diagnostics,
                List.of("--release", "17",
                    "-classpath", System.getProperty("java.class.path"), "-d", output.toString()),
                null, units).call();
            assertTrue(diagnostics.getDiagnostics().toString(), success);
        }
        return new URLClassLoader(new URL[] {output.toUri().toURL()}, getClass().getClassLoader());
    }
}
