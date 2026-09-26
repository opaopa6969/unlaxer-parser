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
 * {@code getThreadAllocatedBytes} の割当）を x1 / x4 / x16 で取り、**1 byte あたりの値が
 * ×1.3 を超えて増えないこと**を突き合わせる。超線形に戻ったらここで落ちる。
 *
 * <p>×1.3 は測定誤差ではなく余裕。ケース 32〜36 の実測は µs/byte で ×1.04〜1.08、
 * 割当は入力長にほぼ比例（x1→x64 で ×1.0 前後）だった。二次の項が入れば x1→x16 で
 * 16 倍近くになるので、この閾値は「線形か否か」を十分に切り分ける。
 */
public class ParserScalingSmokeTest {

    /** 1 byte あたりの値が x1 から何倍まで増えてよいか。 */
    private static final double LIMIT = 1.3;

    private static final String PACKAGE = "org.unlaxer.scaling.generated";

    /** 組み込みトークンだけを使うので、生成規則すべてに SafeFailureMemoizable が付く。 */
    private static final String GRAMMAR = """
        grammar Scale {
          @package: %s
          @whitespace: javaStyle

          token DIGIT  = CHAR_RANGE('0', '9')
          token LETTER = CHAR_RANGE('a', 'z')

          @root
          Program ::= { Statement } ;
          Statement ::= 'let' Name '=' Expression ';' ;
          Expression ::= Term { ( '+' | '-' ) Term } ;
          Term ::= Factor { ( '*' | '/' ) Factor } ;
          Factor ::= '(' Expression ')' | Number | Name ;
          Number ::= DIGIT { DIGIT } ;
          Name ::= LETTER { LETTER } ;
        }
        """.formatted(PACKAGE);

    /** x1 が 16 文、x4 が 64 文、x16 が 256 文。 */
    private static final String STATEMENT = "let abc = 1+2*3-(4+5)/6; ";
    private static final int BASE_STATEMENTS = 16;

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
            int memoSuccessHits, int memoFailureHits, int memoMaxEntryCount, long allocatedBytes) {}

    // =========================================================================
    // 生成パーサ（memo あり）
    // =========================================================================

    @Test
    public void generatedParserCountersStayLinearPerByte() throws Exception {
        try (URLClassLoader loader = compile()) {
            Parser root = (Parser) loader.loadClass(PACKAGE + ".ScaleParsers")
                .getMethod("getRootParser").invoke(null);
            Map<String, Counters> byScale = new LinkedHashMap<>();
            for (int scale : List.of(1, 4, 16)) {
                String input = STATEMENT.repeat(BASE_STATEMENTS * scale);
                byScale.put("x" + scale, measure(root, input, Memoization.SAFE_FAILURES));
            }
            assertTrue("memo が効いていないので memo 次元の比較にならない",
                byScale.get("x1").memoSuccessHits() > 0);
            assertPerByteGrowth(byScale, "生成パーサ (Scale, SAFE_FAILURES)");
        }
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
            check(failures, label, entry.getKey(), "transactions.rolledBack",
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
            counters.memoMaxEntryCount(), allocated);
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
            var metrics = context.snapshotTransactionMetrics();
            PackratMemoTable table = context.getPackratMemoTable();
            return new Counters(input.length(), metrics.opened(), metrics.rolledBack(),
                table == null ? 0 : table.successHits(),
                table == null ? 0 : table.failureHits(),
                table == null ? 0 : table.maxEntryCount(), 0L);
        }
    }

    // =========================================================================
    // 文法を生成してコンパイルする
    // =========================================================================

    private URLClassLoader compile() throws Exception {
        var grammar = UBNFMapper.parse(GRAMMAR).grammars().get(0);
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
