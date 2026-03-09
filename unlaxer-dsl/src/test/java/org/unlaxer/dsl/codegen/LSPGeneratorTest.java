package org.unlaxer.dsl.codegen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.BeforeClass;
import org.junit.Test;
import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.bootstrap.UBNFMapper;

public class LSPGeneratorTest {

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

    private static CodeGenerator.GeneratedSource result;

    @BeforeClass
    public static void setUp() {
        GrammarDecl grammar = UBNFMapper.parse(TINYCALC_GRAMMAR).grammars().get(0);
        result = new LSPGenerator().generate(grammar);
    }

    @Test
    public void testPackageName() {
        assertEquals("org.unlaxer.tinycalc.generated", result.packageName());
    }

    @Test
    public void testClassName() {
        assertEquals("TinyCalcLanguageServer", result.className());
    }

    @Test
    public void testContainsLanguageServerImpl() {
        assertTrue(result.source().contains("implements LanguageServer"));
    }

    @Test
    public void testContainsLanguageClientAware() {
        assertTrue(result.source().contains("LanguageClientAware"));
    }

    @Test
    public void testContainsInitializeMethod() {
        assertTrue(result.source().contains("initialize("));
    }

    @Test
    public void testContainsShutdownMethod() {
        assertTrue(result.source().contains("shutdown()"));
    }

    @Test
    public void testContainsTextDocumentService() {
        assertTrue(result.source().contains("TextDocumentService"));
    }

    @Test
    public void testContainsWorkspaceService() {
        assertTrue(result.source().contains("WorkspaceService"));
    }

    @Test
    public void testContainsDidOpen() {
        assertTrue(result.source().contains("didOpen("));
    }

    @Test
    public void testContainsCompletion() {
        assertTrue(result.source().contains("completion("));
    }

    @Test
    public void testContainsHover() {
        assertTrue(result.source().contains("hover("));
    }

    @Test
    public void testContainsSemanticTokensFull() {
        assertTrue(result.source().contains("semanticTokensFull("));
    }

    @Test
    public void testContainsKeywordVar() {
        assertTrue(result.source().contains("\"var\""));
    }

    @Test
    public void testContainsKeywordVariable() {
        assertTrue(result.source().contains("\"variable\""));
    }

    @Test
    public void testContainsParsersReference() {
        assertTrue(result.source().contains("TinyCalcParsers.getRootParser()"));
    }

    @Test
    public void testContainsLsp4jImport() {
        assertTrue(result.source().contains("import org.eclipse.lsp4j"));
    }

    @Test
    public void testContainsAnnotationCompletionKeywords() {
        assertTrue(result.source().contains("\"@interleave\""));
        assertTrue(result.source().contains("\"@backref\""));
        assertTrue(result.source().contains("\"@scopeTree\""));
        assertTrue(result.source().contains("\"@leftAssoc\""));
        assertTrue(result.source().contains("\"@rightAssoc\""));
        assertTrue(result.source().contains("\"@precedence\""));
    }
}
