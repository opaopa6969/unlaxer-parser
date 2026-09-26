package org.unlaxer.dsl.codegen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.unlaxer.Parsed;
import org.unlaxer.Source;
import org.unlaxer.StringSource;
import org.unlaxer.Token;
import org.unlaxer.context.DiagnosticsSafety;
import org.unlaxer.context.Memoization;
import org.unlaxer.context.PackratMemoTable;
import org.unlaxer.context.ParseContext;
import org.unlaxer.context.ParseFailureDiagnostics;
import org.unlaxer.context.ParseOptions;
import org.unlaxer.context.ParseOptions.Diagnostics;
import org.unlaxer.context.TransactionMetrics;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.parser.Parser;

/**
 * 診断・CST・memo カウンタの同値性を、一度きりのダンプではなく golden file で恒久的に固定する。
 *
 * <p>性能チューニングの第 3〜5 ラウンド（{@code docs/performance-tuning-ja.md} ケース 34〜36）は、
 * 受け入れ条件として「16 fixture + 不正入力 19 件 × {DETAILED, DETAILED_ON_FAILURE} ×
 * {memo OFF, SAFE_FAILURES} を base / cand で走らせ、CST 全体・memo カウンタ・
 * {@code ParseFailureDiagnostics} の全 getter が byte 一致すること」を使った。ただしその照合は
 * 一度きりのダンプで、リポジトリには残っていない。同じ観測量を commit 済みの golden と
 * 突き合わせる形にして、以後の実装変更が黙って振る舞いを変えないようにするのがこのテスト。
 *
 * <h2>どの文法を使うか</h2>
 *
 * <p>ケース 34〜36 の実測は tinyexpression の P4 文法で行ったが、P4 は downstream の
 * リポジトリにあり、その jar を CI で組み立てるには隔離した {@code ~/.m2} を作ってから
 * {@code mvn install} する手順が要る。そこで commit 済みのこのテストは **このリポジトリ自身の
 * 最大の文法** {@code tinycalc-vscode/grammar/tinycalc.ubnf} を codegen で生成・コンパイルして使う。
 * P4 での 132 通りは引き続き {@code docs/performance-tuning-ja.md} の一度きりの実測として残す。
 *
 * <p>その文法は {@code token NUMBER = ...NumberParser} のように外部パーサ別名を使うので、
 * 生成規則には {@code SafeFailureMemoizable} が付かない（memo カウンタが全部 0 になる）。
 * memo 次元が空回りしないよう、**同じ文法に {@code @memoSafeToken} を足しただけの変種**も
 * 併せて走らせる。CST は golden に入れず、「memo 安全化しても構文木は 1 byte も動かない」
 * ことをテスト内の不変条件として突き合わせる（golden を 2 倍にせずに同じ強さを得る）。
 *
 * <h2>golden の作り直し</h2>
 *
 * <pre>
 *   mvn -o -pl unlaxer-common,unlaxer-dsl -am test -Dtest=ParseEqualityGoldenTest \
 *       -Dunlaxer.golden.regenerate=true
 * </pre>
 *
 * <p>差分を {@code git diff} で読んでから commit すること。振る舞いを変えたつもりが無いのに
 * golden が動いたなら、それがこのテストの検出したかった退行である。
 */
public class ParseEqualityGoldenTest {

    private static final Path GOLDEN =
        Path.of("src/test/resources/golden/parse-equality-tinycalc.txt");
    private static final Path GRAMMAR = Path.of("tinycalc-vscode/grammar/tinycalc.ubnf");
    private static final String REGENERATE = "unlaxer.golden.regenerate";
    private static final String PACKAGE_SHIPPED = "org.unlaxer.golden.shipped";
    private static final String PACKAGE_MEMO_SAFE = "org.unlaxer.golden.memosafe";

    /** 受理される入力。括弧の深さ・宣言の数・空白の入り方を散らす。 */
    private static final List<String> ACCEPTED = List.of(
        "1",
        "1+2",
        "1+2*3",
        "(1+2)*3",
        "a",
        "  1 + 2  ",
        "1*2/3-4+5",
        "((((1))))",
        "1+2+3+4+5+6+7+8",
        "1/(2+3)*(4-5)",
        "1+2*3-4/5+(6*7-8)/(9+10)",
        "var x set 1; x",
        "variable y set 2*3; y+1",
        "var a set 1; var b set 2; a*b+3",
        "var x; x+1",
        "var longName set (1+2)*(3-4)/5; longName*longName");

    /** 受理されない、または入力を使い切らない入力。診断の経路を通す。 */
    private static final List<String> REJECTED = List.of(
        "",
        "+",
        "*1",
        "1+",
        "(1",
        "1)",
        "()",
        "1**2",
        "1 2",
        "@",
        "1+@",
        "((1+2)",
        "var",
        "var ;",
        "var x 1; x",
        "var x set ; x",
        "var x set 1 x",
        "var 1 set 2; 1",
        "x set 1");

    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void diagnosticsCstAndMemoCountersStayByteIdenticalToTheGolden() throws Exception {
        // 生成物が test-classes の org.unlaxer.tinycalc.generated と衝突すると
        // URLClassLoader が親のクラスを返してしまうので、変種ごとに別パッケージへ出す。
        String shippedSource = Files.readString(GRAMMAR, StandardCharsets.UTF_8)
            .replace("@package: org.unlaxer.tinycalc.generated", "@package: " + PACKAGE_SHIPPED);
        assertTrue("@package を差し替えられなかった", shippedSource.contains(PACKAGE_SHIPPED));
        // 同じ文法に @memoSafeToken を足しただけの変種。生成規則に SafeFailureMemoizable が付く。
        String memoSafeSource = shippedSource
            .replace("@package: " + PACKAGE_SHIPPED, "@package: " + PACKAGE_MEMO_SAFE)
            .replace("@whitespace: javaStyle",
                "@whitespace: javaStyle\n  @memoSafeToken: NUMBER\n  @memoSafeToken: IDENTIFIER");
        assertTrue("@memoSafeToken を挿入できなかった", memoSafeSource.contains("@memoSafeToken"));

        try (URLClassLoader shipped = compile(shippedSource, PACKAGE_SHIPPED, "shipped");
                URLClassLoader memoSafe = compile(memoSafeSource, PACKAGE_MEMO_SAFE, "memosafe")) {
            Parser shippedRoot = rootParser(shipped, PACKAGE_SHIPPED);
            Parser memoSafeRoot = rootParser(memoSafe, PACKAGE_MEMO_SAFE);

            StringBuilder out = new StringBuilder();
            header(out, shippedRoot, memoSafeRoot);
            Map<String, String> shippedTrees = new LinkedHashMap<>();
            Map<String, String> memoSafeTrees = new LinkedHashMap<>();
            for (String input : inputs()) {
                for (Diagnostics diagnostics : List.of(Diagnostics.DETAILED,
                        Diagnostics.DETAILED_ON_FAILURE)) {
                    for (Memoization memoization : Memoization.values()) {
                        String key = input + '\u0000' + diagnostics + '\u0000' + memoization;
                        out.append("\n=== grammar=shipped input=").append(escape(input))
                            .append(" diagnostics=").append(diagnostics)
                            .append(" memoization=").append(memoization).append('\n');
                        shippedTrees.put(key,
                            dumpOneParse(out, shippedRoot, input, diagnostics, memoization, true));
                        out.append("\n=== grammar=memoSafe input=").append(escape(input))
                            .append(" diagnostics=").append(diagnostics)
                            .append(" memoization=").append(memoization).append('\n');
                        memoSafeTrees.put(key,
                            dumpOneParse(out, memoSafeRoot, input, diagnostics, memoization, false));
                    }
                }
            }

            // 不変条件（golden を 2 倍にせずに固定する）:
            // memo 安全化しても、memo を入れても、構文木は 1 byte も動かない。
            for (Map.Entry<String, String> entry : shippedTrees.entrySet()) {
                assertEquals("memoSafeToken を足すと構文木が変わる: " + entry.getKey(),
                    entry.getValue(), memoSafeTrees.get(entry.getKey()));
            }
            assertCstIsIndependentOfOptions(shippedTrees, "shipped");
            assertCstIsIndependentOfOptions(memoSafeTrees, "memoSafe");

            compareWithGolden(out.toString());
        }
    }

    /** 同じ入力なら診断レベルと memo 方針が何であれ構文木は同じ、を突き合わせる。 */
    private static void assertCstIsIndependentOfOptions(Map<String, String> trees, String label) {
        Map<String, String> byInput = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : trees.entrySet()) {
            String input = entry.getKey().substring(0, entry.getKey().indexOf('\u0000'));
            String previous = byInput.putIfAbsent(input, entry.getValue());
            if (previous != null) {
                assertEquals(label + ": 診断レベル / memo 方針で構文木が変わる: "
                    + escape(input), previous, entry.getValue());
            }
        }
    }

    private void compareWithGolden(String actual) throws Exception {
        if (Boolean.getBoolean(REGENERATE)) {
            Files.createDirectories(GOLDEN.getParent());
            Files.writeString(GOLDEN, actual, StandardCharsets.UTF_8);
            return;
        }
        assertTrue("golden が無い。-D" + REGENERATE + "=true で作る: " + GOLDEN,
            Files.isRegularFile(GOLDEN));
        List<String> expectedLines =
            Files.readString(GOLDEN, StandardCharsets.UTF_8).replace("\r\n", "\n").lines().toList();
        List<String> actualLines = actual.lines().toList();
        for (int i = 0; i < Math.min(expectedLines.size(), actualLines.size()); i++) {
            assertEquals("golden と食い違う行 " + (i + 1)
                + "（意図した変更なら -D" + REGENERATE + "=true で作り直して diff を読む）",
                expectedLines.get(i), actualLines.get(i));
        }
        assertEquals("golden と行数が違う", expectedLines.size(), actualLines.size());
    }

    private static List<String> inputs() {
        List<String> inputs = new ArrayList<>(ACCEPTED);
        inputs.addAll(REJECTED);
        return inputs;
    }

    private void header(StringBuilder out, Parser shippedRoot, Parser memoSafeRoot) {
        out.append("# parse equality golden — ").append(GRAMMAR).append('\n');
        out.append("# 受理される入力 ").append(ACCEPTED.size())
            .append(" 件 + 受理されない入力 ").append(REJECTED.size())
            .append(" 件 × {shipped, memoSafe} × {DETAILED, DETAILED_ON_FAILURE}")
            .append(" × {OFF, SAFE_FAILURES} = ")
            .append(inputs().size() * 8).append(" 通り\n");
        out.append("# deferredDiagnosticsSafe shipped=")
            .append(DiagnosticsSafety.isDeferredDiagnosticsSafe(shippedRoot))
            .append(" memoSafe=")
            .append(DiagnosticsSafety.isDeferredDiagnosticsSafe(memoSafeRoot)).append('\n');
        out.append("# cst 行の並び: #索引 木の深さ tokenKind parser sourceKind")
            .append(" offsetFromRoot/offsetFromParent chars/codePoints range")
            .append(" originalChildren/astChildren terminal text\n");
        out.append("# memoSafe の cst はテスト内の不変条件（shipped と byte 一致）で担保し、")
            .append("golden には入れない\n");
    }

    // =========================================================================
    // 1 回の parse
    // =========================================================================

    /** @return CST のダンプ（golden に入れるかどうかに関わらず、突き合わせ用に必ず返す）。 */
    private String dumpOneParse(StringBuilder out, Parser root, String input,
            Diagnostics diagnostics, Memoization memoization, boolean includeCstInGolden) {
        ParseOptions options = ParseOptions.withMemoization(memoization).withDiagnostics(diagnostics);
        StringBuilder cst = new StringBuilder();
        try (ParseContext context =
                ParseContext.withOptions(StringSource.createRootSource(input), options)) {
            context.enableTransactionMetrics();
            Parsed parsed = root.parse(context);
            out.append("succeeded=").append(parsed.isSucceeded())
                .append(" consumedChars=").append(consumedLength(parsed)).append('\n');
            if (parsed.isSucceeded() && parsed.getRootToken() != null) {
                dumpCst(cst, parsed.getRootToken(), 0, new int[] {0});
            }
            if (includeCstInGolden) {
                out.append("cst.lines=").append(cst.toString().lines().count()).append('\n')
                    .append(cst);
            }
            dumpMemo(out, context.getPackratMemoTable());
            dumpTransactions(out, context.snapshotTransactionMetrics());
            dumpDiagnostics(out, context.getParseFailureDiagnostics());
        }
        return cst.toString();
    }

    private static int consumedLength(Parsed parsed) {
        Token consumed = parsed.getConsumed();
        if (consumed == null || consumed.getSource() == null) {
            return 0;
        }
        String text = consumed.getSource().sourceAsString();
        return text == null ? 0 : text.length();
    }

    // =========================================================================
    // CST（深さ優先。親子の形は「木の深さ」と子の数で復元できる）
    // =========================================================================

    private void dumpCst(StringBuilder out, Token token, int depth, int[] index) {
        Source source = token.getSource();
        String text = source == null ? "" : source.sourceAsString();
        out.append("  #").append(index[0]++).append(' ').append(depth)
            .append(' ').append(token.getTokenKind())
            .append(' ').append(token.getParser() == null
                ? "null" : token.getParser().getClass().getSimpleName())
            .append(' ').append(source == null ? "null" : source.sourceKind())
            .append(' ').append(source == null ? "null" : source.offsetFromRoot())
            .append('/').append(source == null ? "null" : source.offsetFromParent())
            .append(' ').append(text.length())
            .append('/').append(text.codePointCount(0, text.length()))
            .append(' ').append(source == null ? "null" : source.cursorRange().toRange())
            .append(' ').append(token.getOriginalChildren().size())
            .append('/').append(token.getAstNodeChildren().size())
            .append(' ').append(token.isTerminalSymbol() ? 'T' : '-')
            .append(' ').append(escape(text))
            .append('\n');
        for (Token child : token.getOriginalChildren()) {
            dumpCst(out, child, depth + 1, index);
        }
    }

    // =========================================================================
    // memo / transaction カウンタ
    // =========================================================================

    private void dumpMemo(StringBuilder out, PackratMemoTable table) {
        if (table == null) {
            // memo OFF では表そのものが作られない。作られないこと自体を固定する。
            out.append("memo table=null\n");
            return;
        }
        out.append("memo failureHits=").append(table.failureHits())
            .append(" successHits=").append(table.successHits())
            .append(" entryCount=").append(table.entryCount())
            .append(" maxEntryCount=").append(table.maxEntryCount())
            .append(" evicted=").append(table.evictedCount())
            .append(" deadOnArrival=").append(table.deadOnArrival())
            .append(" underruns=").append(table.evictionUnderruns())
            .append(" maxHitLookback=").append(table.maxHitLookback())
            .append('\n');
    }

    private void dumpTransactions(StringBuilder out, TransactionMetrics metrics) {
        out.append("transactions opened=").append(metrics.opened())
            .append(" committed=").append(metrics.committed())
            .append(" rolledBack=").append(metrics.rolledBack())
            .append(" nonemptyPayloadSnapshots=").append(metrics.nonemptyPayloadSnapshots())
            .append(" emptyPayloadCheckpoints=").append(metrics.emptyPayloadCheckpoints())
            .append(" copyOnWriteDeepCopies=").append(metrics.copyOnWriteDeepCopies())
            .append('\n');
    }

    // =========================================================================
    // ParseFailureDiagnostics の全 getter
    // =========================================================================

    private void dumpDiagnostics(StringBuilder out, ParseFailureDiagnostics diagnostics) {
        if (diagnostics == null) {
            out.append("diagnostics=null\n");
            return;
        }
        out.append("diagnostics farthestOffset=").append(diagnostics.getFarthestOffset())
            .append(" farthestConsumedOffset=").append(diagnostics.getFarthestConsumedOffset())
            .append(" farthestMatchedOffset=").append(diagnostics.getFarthestMatchedOffset())
            .append(" line=").append(diagnostics.getLine())
            .append(" column=").append(diagnostics.getColumn())
            .append(" hasFailureCandidate=").append(diagnostics.hasFailureCandidate())
            .append(" deepestMatchedRule=").append(escape(diagnostics.getDeepestMatchedRule()))
            .append(" deepestConsumedPosition=").append(diagnostics.getDeepestConsumedPosition())
            .append('\n');
        out.append("  expectedParsers=").append(diagnostics.getExpectedParsers()).append('\n');
        out.append("  expectedTokens=")
            .append(diagnostics.getExpectedTokens().stream().sorted().toList()).append('\n');
        for (ParseFailureDiagnostics.ParseStackElement element
                : diagnostics.getMaxReachedStackElements()) {
            out.append("  stack parser=").append(element.getParserClassName())
                .append(" depth=").append(element.getDepth())
                .append(" start=").append(element.getStartOffset())
                .append(" maxConsumed=").append(element.getMaxConsumedOffset())
                .append(" maxMatched=").append(element.getMaxMatchedOffset())
                .append('\n');
        }
        // ExpectedHintCandidate は toString() を持たず identity hash しか出ないので展開する。
        for (ParseFailureDiagnostics.ExpectedHintCandidate candidate
                : diagnostics.getExpectedHintCandidates()) {
            out.append("  hint display=").append(escape(candidate.getDisplayHint()))
                .append(" parser=").append(candidate.getParserClassName())
                .append(" qualified=").append(candidate.getParserQualifiedClassName())
                .append(" depth=").append(candidate.getParserDepth())
                .append(" terminal=").append(candidate.isTerminal())
                .append('\n');
        }
        for (ParseFailureDiagnostics.TrialRecord trial : diagnostics.getTrialHistory()) {
            out.append("  trial parser=").append(trial.getParserName())
                .append(" start=").append(trial.getStartPosition())
                .append(" end=").append(trial.getEndPosition())
                .append(" succeeded=").append(trial.isSucceeded())
                .append(" consumed=").append(trial.getConsumed())
                .append('\n');
        }
    }

    private static String escape(String text) {
        if (text == null) {
            return "<null>";
        }
        StringBuilder out = new StringBuilder("\"");
        text.codePoints().forEach(c -> {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", c));
                    } else {
                        out.appendCodePoint(c);
                    }
                }
            }
        });
        return out.append('"').toString();
    }

    // =========================================================================
    // 文法を生成してコンパイルする
    // =========================================================================

    private static Parser rootParser(URLClassLoader loader, String packageName) throws Exception {
        return (Parser) loader.loadClass(packageName + ".TinyCalcParsers")
            .getMethod("getRootParser").invoke(null);
    }

    private URLClassLoader compile(String grammarSource, String packageName, String label)
            throws Exception {
        var grammar = UBNFMapper.parse(grammarSource).grammars().get(0);
        var sources = List.of(new ParserGenerator().generate(grammar),
            new ASTGenerator().generate(grammar), new MapperGenerator().generate(grammar));
        String directory = packageName.replace('.', '/');
        var units = sources.stream().map(source -> new SimpleJavaFileObject(
                URI.create("string:///" + directory + "/" + source.className() + ".java"),
                JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignored) {
                return source.source();
            }
        }).toList();
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var output = temporary.newFolder(label).toPath();
        try (var manager = compiler.getStandardFileManager(diagnostics, null, null)) {
            boolean success = compiler.getTask(null, manager, diagnostics,
                List.of("--release", "17",
                    "-classpath", System.getProperty("java.class.path"), "-d", output.toString()),
                null, units).call();
            assertTrue(label + ": " + diagnostics.getDiagnostics(), success);
        }
        return new URLClassLoader(new URL[] {output.toUri().toURL()}, getClass().getClassLoader());
    }
}
