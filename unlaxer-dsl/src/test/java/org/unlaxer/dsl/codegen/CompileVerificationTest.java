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

import org.junit.Test;
import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.bootstrap.UBNFMapper;

/**
 * ParserGenerator / ASTGenerator / MapperGenerator / EvaluatorGenerator が
 * 生成する Java ソースが実際にコンパイルできることを検証するテスト。
 *
 * <p>unlaxer-common は --enable-preview でビルドされているため、
 * javax.tools.JavaCompiler を直接使い、--enable-preview --release 21 を
 * 明示的に渡してコンパイルする。</p>
 */
public class CompileVerificationTest {

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

    // =========================================================================
    // 各ジェネレーター個別コンパイル検証
    // =========================================================================

    @Test
    public void testGeneratedASTCompiles() {
        GrammarDecl grammar = parseGrammar(TINYCALC_GRAMMAR);
        CodeGenerator.GeneratedSource result = new ASTGenerator().generate(grammar);
        assertCompiles(result);
    }

    @Test
    public void testGeneratedParserCompiles() {
        GrammarDecl grammar = parseGrammar(TINYCALC_GRAMMAR);
        CodeGenerator.GeneratedSource result = new ParserGenerator().generate(grammar);
        assertCompiles(result);
    }

    @Test
    public void testGeneratedEvaluatorCompiles() {
        GrammarDecl grammar = parseGrammar(TINYCALC_GRAMMAR);
        // Evaluator は AST 型を参照するため AST を先にコンパイルする
        CodeGenerator.GeneratedSource astResult  = new ASTGenerator().generate(grammar);
        CodeGenerator.GeneratedSource evalResult = new EvaluatorGenerator().generate(grammar);
        assertCompiles(astResult, evalResult);
    }

    @Test
    public void testGeneratedMapperCompiles() {
        GrammarDecl grammar = parseGrammar(TINYCALC_GRAMMAR);
        CodeGenerator.GeneratedSource astResult    = new ASTGenerator().generate(grammar);
        CodeGenerator.GeneratedSource parserResult = new ParserGenerator().generate(grammar);
        CodeGenerator.GeneratedSource mapperResult = new MapperGenerator().generate(grammar);
        assertCompiles(astResult, parserResult, mapperResult);
    }

    // =========================================================================
    // 全ジェネレーター統合コンパイル検証
    // =========================================================================

    @Test
    public void testAllGeneratorsCompileInDependencyOrder() {
        GrammarDecl grammar = parseGrammar(TINYCALC_GRAMMAR);
        // 依存関係順: AST → Parser → Mapper → Evaluator
        CodeGenerator.GeneratedSource astResult    = new ASTGenerator().generate(grammar);
        CodeGenerator.GeneratedSource parserResult = new ParserGenerator().generate(grammar);
        CodeGenerator.GeneratedSource mapperResult = new MapperGenerator().generate(grammar);
        CodeGenerator.GeneratedSource evalResult   = new EvaluatorGenerator().generate(grammar);
        assertCompiles(astResult, parserResult, mapperResult, evalResult);
    }

    // =========================================================================
    // ヘルパー
    // =========================================================================

    private GrammarDecl parseGrammar(String source) {
        return UBNFMapper.parse(source).grammars().get(0);
    }

    /**
     * 複数のソースを一度に渡してコンパイルする。
     * --enable-preview を明示して unlaxer-common（preview ビルド）を参照できるようにする。
     */
    private void assertCompiles(CodeGenerator.GeneratedSource... sources) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null);

        List<JavaFileObject> sourceObjects = Arrays.stream(sources)
            .map(CompileVerificationTest::toJavaFileObject)
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
