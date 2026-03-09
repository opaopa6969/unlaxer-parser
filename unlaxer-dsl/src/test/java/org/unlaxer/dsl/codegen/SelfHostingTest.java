package org.unlaxer.dsl.codegen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
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
import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.bootstrap.UBNFMapper;

/**
 * 自己ホスティングテスト: grammar/ubnf.ubnf を ParserGenerator で処理し、
 * 生成される UBNF パーサークラス群の構造を検証する。
 */
public class SelfHostingTest {

    private static String generatedSource;
    private static CodeGenerator.GeneratedSource generatedResult;

    @BeforeClass
    public static void setUp() throws IOException {
        Path grammarPath = Path.of("grammar/ubnf.ubnf");
        String grammarSource = Files.readString(grammarPath);
        GrammarDecl grammar = UBNFMapper.parse(grammarSource).grammars().get(0);
        ParserGenerator gen = new ParserGenerator();
        generatedResult = gen.generate(grammar);
        generatedSource = generatedResult.source();
    }

    // =========================================================================
    // パッケージ名・クラス名
    // =========================================================================

    @Test
    public void testPackageName() {
        assertEquals("org.unlaxer.dsl.bootstrap.generated", generatedResult.packageName());
    }

    @Test
    public void testClassName() {
        assertEquals("UBNFParsers", generatedResult.className());
    }

    @Test
    public void testPackageDeclaration() {
        assertTrue("should contain package declaration",
            generatedSource.contains("package org.unlaxer.dsl.bootstrap.generated;"));
    }

    @Test
    public void testOuterClassDeclaration() {
        assertTrue("should contain outer class UBNFParsers",
            generatedSource.contains("class UBNFParsers"));
    }

    // =========================================================================
    // 主要パーサークラスの存在
    // =========================================================================

    @Test
    public void testGrammarDeclParserExists() {
        assertTrue("should contain GrammarDeclParser",
            generatedSource.contains("class GrammarDeclParser"));
    }

    @Test
    public void testRuleDeclParserExists() {
        assertTrue("should contain RuleDeclParser",
            generatedSource.contains("class RuleDeclParser"));
    }

    @Test
    public void testAnnotationParserExists() {
        assertTrue("should contain AnnotationParser",
            generatedSource.contains("class AnnotationParser"));
    }

    @Test
    public void testChoiceBodyParserExists() {
        assertTrue("should contain ChoiceBodyParser",
            generatedSource.contains("class ChoiceBodyParser"));
    }

    @Test
    public void testSequenceBodyParserExists() {
        assertTrue("should contain SequenceBodyParser",
            generatedSource.contains("class SequenceBodyParser"));
    }

    @Test
    public void testTerminalElementParserExists() {
        assertTrue("should contain TerminalElementParser",
            generatedSource.contains("class TerminalElementParser"));
    }

    @Test
    public void testRuleRefElementParserExists() {
        assertTrue("should contain RuleRefElementParser",
            generatedSource.contains("class RuleRefElementParser"));
    }

    @Test
    public void testDottedIdentifierParserExists() {
        assertTrue("should contain DottedIdentifierParser",
            generatedSource.contains("class DottedIdentifierParser"));
    }

    @Test
    public void testMappingAnnotationParserExists() {
        assertTrue("should contain MappingAnnotationParser",
            generatedSource.contains("class MappingAnnotationParser"));
    }

    // =========================================================================
    // デリミタ・チェーン
    // =========================================================================

    @Test
    public void testUBNFSpaceDelimitorExists() {
        assertTrue("should contain UBNFSpaceDelimitor",
            generatedSource.contains("UBNFSpaceDelimitor"));
    }

    @Test
    public void testUBNFLazyChainExists() {
        assertTrue("should contain UBNFLazyChain",
            generatedSource.contains("UBNFLazyChain"));
    }

    // =========================================================================
    // ルートパーサー
    // =========================================================================

    @Test
    public void testGetRootParserExists() {
        assertTrue("should contain getRootParser method",
            generatedSource.contains("getRootParser()"));
    }

    @Test
    public void testGetRootParserReturnsUBNFFileParser() {
        assertTrue("getRootParser should return UBNFFileParser",
            generatedSource.contains("Parser.get(UBNFFileParser.class)"));
    }

    // =========================================================================
    // @comment: { line: "//" } → CPPComment import
    // =========================================================================

    @Test
    public void testCPPCommentImportExists() {
        assertTrue("should import CPPComment for @comment: { line: \"//\" }",
            generatedSource.contains("import org.unlaxer.parser.clang.CPPComment;"));
    }

    // =========================================================================
    // 基本インポート
    // =========================================================================

    @Test
    public void testCombinatorImport() {
        assertTrue("should import combinator package",
            generatedSource.contains("import org.unlaxer.parser.combinator.*;"));
    }

    // =========================================================================
    // コンパイル検証: 生成した UBNFParsers が実際にコンパイルできるか
    // =========================================================================

    @Test
    public void testGeneratedUBNFParsersCompiles() {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, null);

        String uriPath = "/" + generatedResult.packageName().replace('.', '/')
            + "/" + generatedResult.className() + ".java";
        JavaFileObject src = new SimpleJavaFileObject(
            URI.create("string://" + uriPath), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignore) { return generatedSource; }
        };

        String classpath = System.getProperty("java.class.path");
        String tmpDir;
        try {
            tmpDir = Files.createTempDirectory("compile-verify").toString();
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
        List<String> options = List.of("--enable-preview", "--release", "21", "-classpath", classpath, "-d", tmpDir);

        StringWriter diag = new StringWriter();
        boolean ok = compiler.getTask(new PrintWriter(diag), fm, null, options, null, List.of(src)).call();

        assertTrue("Generated UBNFParsers should compile:\n" + diag, ok);
    }
}
