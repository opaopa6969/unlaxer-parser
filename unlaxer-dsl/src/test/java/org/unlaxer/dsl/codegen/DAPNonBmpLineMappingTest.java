package org.unlaxer.dsl.codegen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import org.junit.BeforeClass;
import org.junit.Test;
import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.bootstrap.UBNFMapper;

/**
 * 生成された DAP アダプタが非BMP文字を含むドキュメントで行マッピングを正しく行うか検証する。
 *
 * <p>再現: {@code getLineForOffset} / {@code getLineForToken} が code-point offset を
 * UTF-16 index に変換せずに {@code String.charAt} ループを回すと、サロゲートペアの分だけ
 * ループが短く回り、非BMP文字より後の行が 1 行ずれてブレークポイントが発火しない。
 */
public class DAPNonBmpLineMappingTest {

    private static final String TINYCALC_GRAMMAR =
        "grammar TinyCalc {\n" +
        "  @package: org.unlaxer.tinycalc.generated\n" +
        "  @whitespace: javaStyle\n" +
        "\n" +
        "  token NUMBER     = NumberParser\n" +
        "  token IDENTIFIER = IdentifierParser\n" +
        "\n" +
        "  @root\n" +
        "  @mapping(TinyCalcProgram, params=[declarations, expression])\n" +
        "  TinyCalc ::=\n" +
        "    { VariableDeclaration } @declarations\n" +
        "    Expression @expression ;\n" +
        "\n" +
        "  @mapping(VarDecl, params=[keyword, name, init])\n" +
        "  VariableDeclaration ::=\n" +
        "    ( 'var' | 'variable' ) @keyword\n" +
        "    IDENTIFIER @name\n" +
        "    [ 'set' Expression @init ]\n" +
        "    ';' ;\n" +
        "\n" +
        "  @mapping(BinaryExpr, params=[left, op, right])\n" +
        "  @leftAssoc\n" +
        "  Expression ::= Term @left { ( '+' @op | '-' @op ) Term @right } ;\n" +
        "\n" +
        "  @mapping(BinaryExpr, params=[left, op, right])\n" +
        "  @leftAssoc\n" +
        "  Term ::= Factor @left { ( '*' @op | '/' @op ) Factor @right } ;\n" +
        "\n" +
        "  Factor ::=\n" +
        "      '(' Expression ')'\n" +
        "    | NUMBER\n" +
        "    | IDENTIFIER ;\n" +
        "}";

    private static Class<?> adapterClass;
    private static Path compileDir;

    @BeforeClass
    public static void setUp() throws Exception {
        GrammarDecl grammar = UBNFMapper.parse(TINYCALC_GRAMMAR).grammars().get(0);
        CodeGenerator.GeneratedSource parser = new ParserGenerator().generate(grammar);
        CodeGenerator.GeneratedSource adapter = new DAPGenerator().generate(grammar);

        compileDir = Files.createTempDirectory("dap-nonbmp");
        String classpath = System.getProperty("java.class.path");
        List<String> options = List.of("--enable-preview", "--release", "21",
            "-classpath", classpath, "-d", compileDir.toString());

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null);
        List<JavaFileObject> sources = Arrays.asList(
            toJavaFileObject(parser), toJavaFileObject(adapter),
            concreteAdapterSource());
        StringWriter diagnostics = new StringWriter();
        JavaCompiler.CompilationTask task = compiler.getTask(
            new PrintWriter(diagnostics), fileManager, null, options, null, sources);
        boolean ok = task.call();
        assertTrue("Compilation failed:\n" + diagnostics, ok);

        URLClassLoader loader = new URLClassLoader(new URL[] { compileDir.toUri().toURL() },
            DAPNonBmpLineMappingTest.class.getClassLoader());
        adapterClass = loader.loadClass("org.unlaxer.tinycalc.generated.ConcreteTinyCalcDebugAdapter");
    }

    @Test
    public void getLineForOffsetReturnsSecondLineAfterNonBmp() throws Exception {
        Object adapter = newAdapterInstance();
        String source = "𝄞\nx";
        setSourceContent(adapter, source);

        int line = (int) invokePrivate(adapter, "getLineForOffset", 2);
        assertEquals("x は2行目。code-point offset 2 → UTF-16 offset 3 → line 2", 2, line);
    }

    @Test
    public void codePointOffsetToStringOffsetConvertsSurrogatePair() throws Exception {
        Object adapter = newAdapterInstance();
        String source = "𝄞\nx";
        setSourceContent(adapter, source);

        int stringOffset = (int) invokePrivate(adapter, "codePointOffsetToStringOffset", 2);
        assertEquals("code-point 2 (x) → UTF-16 offset 3", 3, stringOffset);
    }

    @Test
    public void getLineForOffsetFirstLine() throws Exception {
        Object adapter = newAdapterInstance();
        String source = "𝄞\nx";
        setSourceContent(adapter, source);

        int line = (int) invokePrivate(adapter, "getLineForOffset", 0);
        assertEquals(1, line);
    }

    @Test
    public void getLineForOffsetHandlesAsciiOnlyDocument() throws Exception {
        Object adapter = newAdapterInstance();
        String source = "a\nb\nc";
        setSourceContent(adapter, source);

        assertEquals(2, (int) invokePrivate(adapter, "getLineForOffset", 2));
        assertEquals(3, (int) invokePrivate(adapter, "getLineForOffset", 4));
    }

    private static Object newAdapterInstance() throws Exception {
        return adapterClass.getDeclaredConstructor().newInstance();
    }

    private static void setSourceContent(Object adapter, String content) throws Exception {
        Field f = findField(adapterClass, "sourceContent");
        f.setAccessible(true);
        f.set(adapter, content);
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> cursor = type;
        while (cursor != null) {
            try {
                return cursor.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                cursor = cursor.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static Object invokePrivate(Object adapter, String methodName, int arg) throws Exception {
        Method m = findMethod(adapterClass, methodName, int.class);
        m.setAccessible(true);
        return m.invoke(adapter, arg);
    }

    private static Method findMethod(Class<?> type, String name, Class<?>... params) throws NoSuchMethodException {
        Class<?> cursor = type;
        while (cursor != null) {
            try {
                return cursor.getDeclaredMethod(name, params);
            } catch (NoSuchMethodException ignored) {
                cursor = cursor.getSuperclass();
            }
        }
        throw new NoSuchMethodException(name);
    }

    private static JavaFileObject toJavaFileObject(CodeGenerator.GeneratedSource source) {
        String uriPath = "/" + source.packageName().replace('.', '/') + "/" + source.className() + ".java";
        return new SimpleJavaFileObject(URI.create("string://" + uriPath), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source.source();
            }
        };
    }

    private static JavaFileObject concreteAdapterSource() {
        String uriPath = "/org/unlaxer/tinycalc/generated/ConcreteTinyCalcDebugAdapter.java";
        String body =
            "package org.unlaxer.tinycalc.generated;\n" +
            "public class ConcreteTinyCalcDebugAdapter extends TinyCalcDebugAdapter {}\n";
        return new SimpleJavaFileObject(URI.create("string://" + uriPath), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return body;
            }
        };
    }
}
