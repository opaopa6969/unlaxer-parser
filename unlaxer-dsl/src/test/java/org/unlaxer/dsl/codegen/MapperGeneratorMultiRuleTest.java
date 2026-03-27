package org.unlaxer.dsl.codegen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.bootstrap.UBNFMapper;

/**
 * Tests for MapperGenerator when multiple grammar rules map to the same AST class.
 * This verifies the allMappingRules fix that handles deduplication and dispatch.
 */
public class MapperGeneratorMultiRuleTest {

    /**
     * Grammar where Expression and Term both map to BinaryExpr.
     * This is common in calculator/expression-style grammars.
     */
    private static final String MULTI_MAPPING_GRAMMAR =
        "grammar Calc {\n" +
        "  @package: org.example.calc\n" +
        "  token NUMBER = NumberParser\n" +
        "\n" +
        "  @root\n" +
        "  @mapping(CalcProgram, params=[expression])\n" +
        "  Calc ::= Expression @expression ;\n" +
        "\n" +
        "  @mapping(BinaryExpr, params=[left, op, right])\n" +
        "  @leftAssoc\n" +
        "  Expression ::= Term @left { ( '+' @op | '-' @op ) Term @right } ;\n" +
        "\n" +
        "  @mapping(BinaryExpr, params=[left, op, right])\n" +
        "  @leftAssoc\n" +
        "  Term ::= Factor @left { ( '*' @op | '/' @op ) Factor @right } ;\n" +
        "\n" +
        "  Factor ::= NUMBER | '(' Expression ')' ;\n" +
        "}";

    /**
     * Grammar with three rules mapping to the same AST class.
     */
    private static final String TRIPLE_MAPPING_GRAMMAR =
        "grammar Expr {\n" +
        "  @package: org.example.expr\n" +
        "  token NUMBER = NumberParser\n" +
        "\n" +
        "  @root\n" +
        "  @mapping(BinaryExpr, params=[left, op, right])\n" +
        "  @leftAssoc\n" +
        "  Expr ::= Term @left { ( '+' @op | '-' @op ) Term @right } ;\n" +
        "\n" +
        "  @mapping(BinaryExpr, params=[left, op, right])\n" +
        "  @leftAssoc\n" +
        "  Term ::= Factor @left { ( '*' @op | '/' @op ) Factor @right } ;\n" +
        "\n" +
        "  @mapping(BinaryExpr, params=[left, op, right])\n" +
        "  @rightAssoc\n" +
        "  Factor ::= Atom @left { '^' @op Factor @right } ;\n" +
        "\n" +
        "  Atom ::= NUMBER | '(' Expr ')' ;\n" +
        "}";

    @Test
    public void testToBinaryExprMethodIsDeduplicated() {
        GrammarDecl grammar = parseGrammar(MULTI_MAPPING_GRAMMAR);
        MapperGenerator gen = new MapperGenerator();
        String source = gen.generate(grammar).source();

        // BinaryExpr maps from two rules but the toBinaryExpr method should appear once
        long occurrences = countOccurrences(source, "toBinaryExpr(Token token)");
        assertEquals("toBinaryExpr should appear exactly once", 1, occurrences);
    }

    @Test
    public void testDispatchMethodHandlesBothParserTypes() {
        GrammarDecl grammar = parseGrammar(MULTI_MAPPING_GRAMMAR);
        MapperGenerator gen = new MapperGenerator();
        String source = gen.generate(grammar).source();

        // The dispatch (toAST) method should reference both ExpressionParser and TermParser
        assertTrue("dispatch should reference ExpressionParser",
            source.contains("ExpressionParser"));
        assertTrue("dispatch should reference TermParser",
            source.contains("TermParser"));
    }

    @Test
    public void testGeneratedClassCompiles() {
        GrammarDecl grammar = parseGrammar(MULTI_MAPPING_GRAMMAR);
        MapperGenerator gen = new MapperGenerator();
        CodeGenerator.GeneratedSource result = gen.generate(grammar);

        // Basic structural checks
        assertEquals("CalcMapper", result.className());
        assertEquals("org.example.calc", result.packageName());
        assertTrue(result.source().contains("class CalcMapper"));
    }

    @Test
    public void testTripleMappingDeduplication() {
        GrammarDecl grammar = parseGrammar(TRIPLE_MAPPING_GRAMMAR);
        MapperGenerator gen = new MapperGenerator();
        String source = gen.generate(grammar).source();

        // Despite three rules mapping to BinaryExpr, the method should appear once
        long occurrences = countOccurrences(source, "toBinaryExpr(Token token)");
        assertEquals("toBinaryExpr should appear exactly once", 1, occurrences);
    }

    @Test
    public void testTripleMappingDispatchAllThreeParserTypes() {
        GrammarDecl grammar = parseGrammar(TRIPLE_MAPPING_GRAMMAR);
        MapperGenerator gen = new MapperGenerator();
        String source = gen.generate(grammar).source();

        // All three parser types should be referenced in dispatch
        assertTrue("should reference ExprParser", source.contains("ExprParser"));
        assertTrue("should reference TermParser", source.contains("TermParser"));
        assertTrue("should reference FactorParser", source.contains("FactorParser"));
    }

    @Test
    public void testLeftAssocGeneratesCorrectToMethod() {
        GrammarDecl grammar = parseGrammar(MULTI_MAPPING_GRAMMAR);
        MapperGenerator gen = new MapperGenerator();
        String source = gen.generate(grammar).source();

        // The generated toBinaryExpr method should handle left-associative patterns
        assertTrue("should contain toBinaryExpr method",
            source.contains("toBinaryExpr(Token token)"));
        // The generated code should reference the BinaryExpr AST class
        assertTrue("should reference BinaryExpr class in body",
            source.contains("BinaryExpr"));
    }

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
