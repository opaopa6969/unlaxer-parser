package org.unlaxer.dsl.codegen;

import static org.junit.Assert.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.nio.file.Files;
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
 * DAPGenerator / DAPLauncherGenerator が生成する Java ソースが
 * 実際にコンパイルできることを検証するテスト。
 */
public class DAPCompileVerificationTest {

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

    private static GrammarDecl grammar;
    private static CodeGenerator.GeneratedSource parserResult;
    private static CodeGenerator.GeneratedSource adapterResult;
    private static CodeGenerator.GeneratedSource launcherResult;

    @BeforeClass
    public static void setUp() {
        grammar        = UBNFMapper.parse(TINYCALC_GRAMMAR).grammars().get(0);
        parserResult   = new ParserGenerator().generate(grammar);
        adapterResult  = new DAPGenerator().generate(grammar);
        launcherResult = new DAPLauncherGenerator().generate(grammar);
    }

    @Test
    public void testDebugAdapterCompiles() {
        assertCompiles(parserResult, adapterResult);
    }

    @Test
    public void testDapLauncherCompiles() {
        assertCompiles(parserResult, adapterResult, launcherResult);
    }

    private void assertCompiles(CodeGenerator.GeneratedSource... sources) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null);

        List<JavaFileObject> sourceObjects = Arrays.stream(sources)
            .map(DAPCompileVerificationTest::toJavaFileObject)
            .toList();

        String classpath = System.getProperty("java.class.path");
        String tmpDir;
        try {
            tmpDir = Files.createTempDirectory("compile-verify").toString();
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
        List<String> options = List.of("--enable-preview", "--release", "21", "-classpath", classpath, "-d", tmpDir);

        StringWriter diagnostics = new StringWriter();
        JavaCompiler.CompilationTask task = compiler.getTask(
            new PrintWriter(diagnostics), fileManager, null, options, null, sourceObjects);

        boolean success = task.call();

        String names = Arrays.stream(sources)
            .map(s -> s.packageName() + "." + s.className())
            .reduce((a, b) -> a + ", " + b)
            .orElse("");

        assertTrue(
            "Compilation failed for [" + names + "]:\n" + diagnostics,
            success
        );
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
}
