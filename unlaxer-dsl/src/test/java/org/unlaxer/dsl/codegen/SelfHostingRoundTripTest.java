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
            "--enable-preview", "--release", "21",
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
}
