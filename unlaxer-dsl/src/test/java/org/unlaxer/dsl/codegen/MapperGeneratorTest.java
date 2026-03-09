package org.unlaxer.dsl.codegen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.bootstrap.UBNFMapper;

public class MapperGeneratorTest {

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

    private static final String RIGHT_ASSOC_GRAMMAR =
        "grammar Pow {\n" +
        "  @package: org.example.pow\n" +
        "  token NUMBER = NumberParser\n" +
        "  @root\n" +
        "  @mapping(PowNode, params=[left, op, right])\n" +
        "  @rightAssoc\n" +
        "  @precedence(level=30)\n" +
        "  Expr ::= Atom @left { '^' @op Expr @right } ;\n" +
        "  Atom ::= NUMBER ;\n" +
        "}";

    @Test
    public void testGeneratedPackageName() {
        GrammarDecl grammar = parseGrammar(TINYCALC_GRAMMAR);
        MapperGenerator gen = new MapperGenerator();
        CodeGenerator.GeneratedSource result = gen.generate(grammar);
        assertEquals("org.unlaxer.tinycalc.generated", result.packageName());
    }

    @Test
    public void testGeneratedClassName() {
        GrammarDecl grammar = parseGrammar(TINYCALC_GRAMMAR);
        MapperGenerator gen = new MapperGenerator();
        CodeGenerator.GeneratedSource result = gen.generate(grammar);
        assertEquals("TinyCalcMapper", result.className());
    }

    @Test
    public void testGeneratedSourceContainsPackageDeclaration() {
        GrammarDecl grammar = parseGrammar(TINYCALC_GRAMMAR);
        MapperGenerator gen = new MapperGenerator();
        String source = gen.generate(grammar).source();
        assertTrue("should contain package declaration",
            source.contains("package org.unlaxer.tinycalc.generated;"));
    }

    @Test
    public void testGeneratedSourceContainsClass() {
        GrammarDecl grammar = parseGrammar(TINYCALC_GRAMMAR);
        MapperGenerator gen = new MapperGenerator();
        String source = gen.generate(grammar).source();
        assertTrue("should contain class TinyCalcMapper",
            source.contains("class TinyCalcMapper"));
    }

    @Test
    public void testGeneratedSourceContainsParseMethod() {
        GrammarDecl grammar = parseGrammar(TINYCALC_GRAMMAR);
        MapperGenerator gen = new MapperGenerator();
        String source = gen.generate(grammar).source();
        assertTrue("should contain parse method", source.contains("parse(String source)"));
    }

    @Test
    public void testGeneratedSourceContainsToTinyCalcProgramMethod() {
        GrammarDecl grammar = parseGrammar(TINYCALC_GRAMMAR);
        MapperGenerator gen = new MapperGenerator();
        String source = gen.generate(grammar).source();
        assertTrue("should contain toTinyCalcProgram method",
            source.contains("toTinyCalcProgram(Token token)"));
    }

    @Test
    public void testGeneratedSourceContainsToVarDeclMethod() {
        GrammarDecl grammar = parseGrammar(TINYCALC_GRAMMAR);
        MapperGenerator gen = new MapperGenerator();
        String source = gen.generate(grammar).source();
        assertTrue("should contain toVarDecl method",
            source.contains("toVarDecl(Token token)"));
    }

    @Test
    public void testGeneratedSourceContainsToBinaryExprMethod() {
        GrammarDecl grammar = parseGrammar(TINYCALC_GRAMMAR);
        MapperGenerator gen = new MapperGenerator();
        String source = gen.generate(grammar).source();
        assertTrue("should contain toBinaryExpr method",
            source.contains("toBinaryExpr(Token token)"));
    }

    @Test
    public void testBinaryExprMapperMethodIsDeduplicated() {
        GrammarDecl grammar = parseGrammar(TINYCALC_GRAMMAR);
        MapperGenerator gen = new MapperGenerator();
        String source = gen.generate(grammar).source();
        // BinaryExpr appears twice in grammar but mapper method should appear once
        long occurrences = countOccurrences(source, "toBinaryExpr(Token token)");
        assertEquals("toBinaryExpr should appear exactly once", 1, occurrences);
    }

    @Test
    public void testGeneratedSourceContainsFindDescendants() {
        GrammarDecl grammar = parseGrammar(TINYCALC_GRAMMAR);
        MapperGenerator gen = new MapperGenerator();
        String source = gen.generate(grammar).source();
        assertTrue("should contain findDescendants utility",
            source.contains("findDescendants"));
    }

    @Test
    public void testGeneratedSourceContainsStripQuotes() {
        GrammarDecl grammar = parseGrammar(TINYCALC_GRAMMAR);
        MapperGenerator gen = new MapperGenerator();
        String source = gen.generate(grammar).source();
        assertTrue("should contain stripQuotes utility",
            source.contains("stripQuotes"));
    }

    @Test
    public void testGeneratedSourceContainsImports() {
        GrammarDecl grammar = parseGrammar(TINYCALC_GRAMMAR);
        MapperGenerator gen = new MapperGenerator();
        String source = gen.generate(grammar).source();
        assertTrue("should import Token", source.contains("import org.unlaxer.Token;"));
        assertTrue("should import Parser", source.contains("import org.unlaxer.parser.Parser;"));
        assertTrue("should import List", source.contains("import java.util.List;"));
    }

    @Test
    public void testGeneratedSourceContainsParameterNames() {
        GrammarDecl grammar = parseGrammar(TINYCALC_GRAMMAR);
        MapperGenerator gen = new MapperGenerator();
        String source = gen.generate(grammar).source();
        // VarDecl parameters
        assertTrue("should mention keyword param", source.contains("keyword"));
        assertTrue("should mention name param", source.contains("name"));
        assertTrue("should mention init param", source.contains("init"));
    }

    @Test
    public void testRightAssocMapperContainsFoldMethod() {
        GrammarDecl grammar = parseGrammar(RIGHT_ASSOC_GRAMMAR);
        MapperGenerator gen = new MapperGenerator();
        String source = gen.generate(grammar).source();
        assertTrue("should contain foldRightAssoc method",
            source.contains("foldRightAssocPowNode"));
    }

    @Test
    public void testRightAssocMapperUsesRightFoldInToMethod() {
        GrammarDecl grammar = parseGrammar(RIGHT_ASSOC_GRAMMAR);
        MapperGenerator gen = new MapperGenerator();
        String source = gen.generate(grammar).source();
        assertTrue("to method should call right fold",
            source.contains("foldRightAssocPowNode(left, ops, rights)"));
    }

    // =========================================================================
    // ヘルパー
    // =========================================================================

    private GrammarDecl parseGrammar(String source) {
        return UBNFMapper.parse(source).grammars().get(0);
    }

    private long countOccurrences(String text, String pattern) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(pattern, index)) != -1) {
            count++;
            index += pattern.length();
        }
        return count;
    }
}
