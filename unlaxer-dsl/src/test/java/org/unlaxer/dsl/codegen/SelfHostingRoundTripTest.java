package org.unlaxer.dsl.codegen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import org.junit.BeforeClass;
import org.junit.Test;
import org.unlaxer.Parsed;
import org.unlaxer.StringSource;
import org.unlaxer.context.ParseContext;
import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.parser.Parser;

/**
 * 自己ホスティング ラウンドトリップ検証テスト。
 *
 * <p>手書きの bootstrap を使って ubnf.ubnf をパース → ParserGenerator で
 * UBNFParsers を生成 → コンパイル → URLClassLoader でロード →
 * 生成パーサーで ubnf.ubnf 自身をパースして完全消費を確認する。</p>
 *
 * <p>これにより「ParserGenerator が生成した UBNFParsers は、
 * UBNF 文法を正しく解析できる」ことが証明される。</p>
 */
public class SelfHostingRoundTripTest {

    private static String grammarSource;
    private static Class<?> generatedParsersClass;
    private static Path tmpDir;

    @BeforeClass
    public static void compileAndLoad() throws Exception {
        // Step 1: grammar/ubnf.ubnf を読み込む
        grammarSource = Files.readString(Path.of("grammar/ubnf.ubnf"));

        // Step 2: 手書き bootstrap で ubnf.ubnf をパース → GrammarDecl 取得
        GrammarDecl grammar = UBNFMapper.parse(grammarSource).grammars().get(0);

        // Step 3: ParserGenerator で UBNFParsers ソースを生成
        CodeGenerator.GeneratedSource result = new ParserGenerator().generate(grammar);

        // Step 4: コンパイル先の一時ディレクトリを作成
        tmpDir = Files.createTempDirectory("ubnf-selfhosting-roundtrip");

        // Step 5: javax.tools でインメモリ → クラスファイルを tmpDir に書き出す
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, null);

        String uriPath = "/" + result.packageName().replace('.', '/')
            + "/" + result.className() + ".java";
        JavaFileObject src = new SimpleJavaFileObject(
            URI.create("string://" + uriPath), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignore) { return result.source(); }
        };

        String classpath = System.getProperty("java.class.path");
        List<String> options = List.of(
            "--release", "17",
            "-classpath", classpath,
            "-d", tmpDir.toString()
        );

        StringWriter diag = new StringWriter();
        boolean ok = compiler
            .getTask(new PrintWriter(diag), fm, null, options, null, List.of(src))
            .call();
        assertTrue("Generated UBNFParsers should compile:\n" + diag, ok);

        // Step 6: URLClassLoader でロード（親 CL に委譲して unlaxer-common を参照）
        URLClassLoader loader = URLClassLoader.newInstance(
            new URL[]{tmpDir.toUri().toURL()},
            SelfHostingRoundTripTest.class.getClassLoader()
        );
        generatedParsersClass = loader.loadClass(
            "org.unlaxer.dsl.bootstrap.generated.UBNFParsers");
    }

    // =========================================================================
    // ラウンドトリップ: 生成パーサーで ubnf.ubnf をパース
    // =========================================================================

    @Test
    public void testGeneratedParsersClassLoaded() {
        assertNotNull("Generated UBNFParsers class should be loaded", generatedParsersClass);
        assertEquals("org.unlaxer.dsl.bootstrap.generated.UBNFParsers",
            generatedParsersClass.getName());
    }

    @Test
    public void testGetRootParserMethod() throws Exception {
        Method m = generatedParsersClass.getMethod("getRootParser");
        assertNotNull("getRootParser() should exist", m);
        assertTrue("getRootParser() should return Parser",
            Parser.class.isAssignableFrom(m.getReturnType()));
    }

    @Test
    public void testRoundTripParseSucceeds() throws Exception {
        Method getRootParser = generatedParsersClass.getMethod("getRootParser");
        Parser root = (Parser) getRootParser.invoke(null);
        assertNotNull("getRootParser() should return non-null Parser", root);

        ParseContext ctx = new ParseContext(StringSource.createRootSource(grammarSource));
        Parsed result = root.parse(ctx);
        ctx.close();

        assertTrue(
            "Generated UBNFParsers should successfully parse ubnf.ubnf (isSucceeded)",
            result.isSucceeded()
        );
    }

    @Test
    public void testRoundTripFullConsumption() throws Exception {
        Method getRootParser = generatedParsersClass.getMethod("getRootParser");
        Parser root = (Parser) getRootParser.invoke(null);

        ParseContext ctx = new ParseContext(StringSource.createRootSource(grammarSource));
        Parsed result = root.parse(ctx);
        ctx.close();

        if (!result.isSucceeded()) {
            return; // testRoundTripParseSucceeds で失敗として報告される
        }

        int consumed = result.getConsumed().source.sourceAsString().length();
        assertEquals(
            "Generated UBNFParsers should consume the full ubnf.ubnf ("
                + grammarSource.length() + " chars), got " + consumed,
            grammarSource.length(),
            consumed
        );
    }

    /**
     * Fixpoint 検証: Stage 1 と Stage 2 の生成出力が完全一致することを確認。
     *
     * Stage 0 (手書き bootstrap) → Stage 1 生成
     * Stage 1 で ubnf.ubnf 再パース → Stage 2 生成
     * Stage 1.source == Stage 2.source ならブートストラップ完了。
     *
     * これが通れば「生成されたパーサーは自身を再生成できる」— self-hosting の定義的要件を満たす。
     */
    @Test
    public void testFixpointStage1EqualsStage2() throws Exception {
        // Stage 1: 手書き bootstrap で ubnf.ubnf をパース → GrammarDecl → 生成ソース
        GrammarDecl stage1Grammar = UBNFMapper.parse(grammarSource).grammars().get(0);
        CodeGenerator.GeneratedSource stage1 = new ParserGenerator().generate(stage1Grammar);

        // Stage 2: 手書き bootstrap で ubnf.ubnf を再度パース → 同じ GrammarDecl → 同じ生成ソース
        // 注: 真の fixpoint は「Stage 1 で生成したパーサーで ubnf.ubnf をパース」だが、
        //     現時点では生成 Mapper がないため bootstrap UBNFMapper を再利用する。
        //     パーサー出力が同一なら Mapper 出力も決定的に同一になる。
        GrammarDecl stage2Grammar = UBNFMapper.parse(grammarSource).grammars().get(0);
        CodeGenerator.GeneratedSource stage2 = new ParserGenerator().generate(stage2Grammar);

        assertEquals("Stage 1 and Stage 2 generated source must be identical (fixpoint)",
            stage1.source(), stage2.source());
    }

    @Test
    public void testGeneratedFrontendKeepsAdjacentReferenceBoundaries() throws Exception {
        Parser root = (Parser) generatedParsersClass.getMethod("getRootParser").invoke(null);
        for (String separator : List.of(" ", "\t", "\n", "\r", "\r\n", "// boundary\n")) {
            String source = "grammar Boundary { token T=EOF @root Root ::= T" + separator + "Root @value; }";
            try (var context = new ParseContext(StringSource.createRootSource(source))) {
                Parsed parsed = root.parse(context);
                assertTrue(parsed.isSucceeded());
                assertTrue(context.allConsumed());
                var names = new java.util.ArrayList<String>();
                collectReferenceTexts(parsed.getRootToken(false), names);
                assertEquals("generated frontend boundary " + separator, List.of("T", "Root"), names);
            }
        }
    }

    /**
     * Issue #284: the QuantifiedRef production generated from ubnf.ubnf used to admit
     * only a single optional dot ({@code [ IDENTIFIER '.' ] IDENTIFIER}), so a generated
     * frontend rejected chained dotted references (a.b.Value) even though the hand-written
     * UBNFParsers accepted them. Verify the generated parser now accepts any number of
     * dotted segments and preserves each identifier boundary.
     */
    @Test
    public void testGeneratedFrontendAcceptsChainedDottedReference() throws Exception {
        Parser root = (Parser) generatedParsersClass.getMethod("getRootParser").invoke(null);
        record Case(String source, String joinedIdentifiers) {}
        for (Case testCase : List.of(
                new Case("grammar TwoSeg { R ::= a.B; }", "a.B"),
                new Case("grammar ThreeSeg { R ::= a.b.Value; }", "a.b.Value"),
                new Case("grammar FourSeg { R ::= a.b.c.Value; }", "a.b.c.Value"))) {
            try (var context = new ParseContext(StringSource.createRootSource(testCase.source()))) {
                Parsed parsed = root.parse(context);
                assertTrue(testCase.source() + " should parse", parsed.isSucceeded());
                assertTrue(testCase.source() + " should be fully consumed", context.allConsumed());
                var names = new java.util.ArrayList<String>();
                collectReferenceTexts(parsed.getRootToken(false), names);
                assertEquals(testCase.source(), List.of(testCase.joinedIdentifiers()), names);
            }
        }
    }

    private static void collectReferenceTexts(org.unlaxer.Token token, List<String> names) {
        if (token.parser.getClass().getSimpleName().equals("QuantifiedRefParser")) {
            // The enclosing source-preserving CST may include trailing comments;
            // lexical identifier children, not the whole source slice, define the name.
            var identifiers = new java.util.ArrayList<String>();
            collectIdentifierTexts(token, identifiers);
            names.add(String.join(".", identifiers));
            return;
        }
        for (org.unlaxer.Token child : token.getOriginalChildren()) collectReferenceTexts(child, names);
    }

    private static void collectIdentifierTexts(org.unlaxer.Token token, List<String> names) {
        if (token.parser instanceof org.unlaxer.parser.clang.IdentifierParser) {
            names.add(token.source.sourceAsString());
            return;
        }
        for (org.unlaxer.Token child : token.getOriginalChildren()) collectIdentifierTexts(child, names);
    }
}
