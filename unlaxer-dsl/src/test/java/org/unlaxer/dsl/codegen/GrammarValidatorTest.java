package org.unlaxer.dsl.codegen;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.bootstrap.UBNFMapper;

public class GrammarValidatorTest {

    @Test
    public void testValidMappingPasses() {
        GrammarDecl grammar = parseGrammar(
            "grammar G {\n"
                + "  @package: org.example\n"
                + "  @root\n"
                + "  @mapping(Root, params=[value])\n"
                + "  Start ::= 'ok' @value ;\n"
                + "}"
        );

        GrammarValidator.validateOrThrow(grammar);
    }

    @Test
    public void testMissingCaptureFails() {
        GrammarDecl grammar = parseGrammar(
            "grammar G {\n"
                + "  @package: org.example\n"
                + "  @root\n"
                + "  @mapping(Root, params=[value, missing])\n"
                + "  Start ::= 'ok' @value ;\n"
                + "}"
        );

        try {
            GrammarValidator.validateOrThrow(grammar);
            fail("expected validation error");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("param 'missing' has no matching capture"));
        }
    }

    @Test
    public void testUnlistedCaptureFails() {
        GrammarDecl grammar = parseGrammar(
            "grammar G {\n"
                + "  @package: org.example\n"
                + "  @root\n"
                + "  @mapping(Root, params=[left])\n"
                + "  Start ::= 'a' @left 'b' @right ;\n"
                + "}"
        );

        try {
            GrammarValidator.validateOrThrow(grammar);
            fail("expected validation error");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("capture @right not listed"));
        }
    }

    @Test
    public void testDuplicateParamsFails() {
        GrammarDecl grammar = parseGrammar(
            "grammar G {\n"
                + "  @package: org.example\n"
                + "  @root\n"
                + "  @mapping(Root, params=[value, value])\n"
                + "  Start ::= 'ok' @value ;\n"
                + "}"
        );

        try {
            GrammarValidator.validateOrThrow(grammar);
            fail("expected validation error");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("duplicate params"));
        }
    }

    @Test
    public void testLeftAssocWithoutRepeatFails() {
        GrammarDecl grammar = parseGrammar(
            "grammar G {\n"
                + "  @package: org.example\n"
                + "  @root\n"
                + "  @mapping(Bin, params=[left, op, right])\n"
                + "  @leftAssoc\n"
                + "  Expr ::= 'a' @left '+' @op 'b' @right ;\n"
                + "}"
        );

        try {
            GrammarValidator.validateOrThrow(grammar);
            fail("expected validation error");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("has no repeat segment"));
        }
    }

    @Test
    public void testLeftAssocWithoutMappingFails() {
        GrammarDecl grammar = parseGrammar(
            "grammar G {\n"
                + "  @package: org.example\n"
                + "  @root\n"
                + "  @leftAssoc\n"
                + "  Expr ::= Term @left { '+' @op Term @right } ;\n"
                + "  Term ::= 'n' ;\n"
                + "}"
        );

        try {
            GrammarValidator.validateOrThrow(grammar);
            fail("expected validation error");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("uses @leftAssoc but has no @mapping"));
        }
    }

    @Test
    public void testRuleWhitespaceUnknownStyleFails() {
        GrammarDecl grammar = parseGrammar(
            "grammar G {\n"
                + "  @package: org.example\n"
                + "  @root\n"
                + "  @whitespace(custom)\n"
                + "  Start ::= 'ok' ;\n"
                + "}"
        );

        try {
            GrammarValidator.validateOrThrow(grammar);
            fail("expected validation error");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("unsupported @whitespace style"));
        }
    }

    @Test
    public void testGlobalWhitespaceUnknownStyleFails() {
        GrammarDecl grammar = parseGrammar(
            "grammar G {\n"
                + "  @package: org.example\n"
                + "  @whitespace: custom\n"
                + "  @root\n"
                + "  Start ::= 'ok' ;\n"
                + "}"
        );

        try {
            GrammarValidator.validateOrThrow(grammar);
            fail("expected validation error");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("global @whitespace style must be javaStyle"));
        }
    }

    @Test
    public void testPrecedenceWithoutLeftAssocFails() {
        GrammarDecl grammar = parseGrammar(
            "grammar G {\n"
                + "  @package: org.example\n"
                + "  @root\n"
                + "  @mapping(ExprNode, params=[left, op, right])\n"
                + "  @precedence(level=10)\n"
                + "  Expr ::= Term @left { '+' @op Term @right } ;\n"
                + "  Term ::= 'n' ;\n"
                + "}"
        );

        try {
            GrammarValidator.validateOrThrow(grammar);
            fail("expected validation error");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("uses @precedence but has no @leftAssoc/@rightAssoc"));
        }
    }

    @Test
    public void testDuplicatePrecedenceFails() {
        GrammarDecl grammar = parseGrammar(
            "grammar G {\n"
                + "  @package: org.example\n"
                + "  @root\n"
                + "  @mapping(ExprNode, params=[left, op, right])\n"
                + "  @leftAssoc\n"
                + "  @precedence(level=10)\n"
                + "  @precedence(level=20)\n"
                + "  Expr ::= Term @left { '+' @op Term @right } ;\n"
                + "  Term ::= 'n' ;\n"
                + "}"
        );

        try {
            GrammarValidator.validateOrThrow(grammar);
            fail("expected validation error");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("duplicate @precedence annotations"));
        }
    }

    @Test
    public void testRightAssocWithoutMappingFails() {
        GrammarDecl grammar = parseGrammar(
            "grammar G {\n"
                + "  @package: org.example\n"
                + "  @root\n"
                + "  @rightAssoc\n"
                + "  Expr ::= Term @left { '^' @op Term @right } ;\n"
                + "  Term ::= 'n' ;\n"
                + "}"
        );

        try {
            GrammarValidator.validateOrThrow(grammar);
            fail("expected validation error");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("uses @rightAssoc but has no @mapping"));
            assertTrue(e.getMessage().contains("[code: E-ASSOC-NO-MAPPING]"));
        }
    }

    @Test
    public void testValidateReturnsStructuredIssues() {
        GrammarDecl grammar = parseGrammar(
            "grammar G {\n"
                + "  @package: org.example\n"
                + "  @root\n"
                + "  @rightAssoc\n"
                + "  Expr ::= Term @left { '^' @op Term @right } ;\n"
                + "  Term ::= 'n' ;\n"
                + "}"
        );

        var issues = GrammarValidator.validate(grammar);
        assertTrue(!issues.isEmpty());
        assertEquals("E-ASSOC-NO-MAPPING", issues.get(0).code());
        assertEquals("ERROR", issues.get(0).severity());
        assertEquals("ASSOCIATIVITY", issues.get(0).category());
        assertEquals("Expr", issues.get(0).rule());
        assertTrue(issues.get(0).message().contains("uses @rightAssoc but has no @mapping"));
        assertTrue(issues.get(0).hint().contains("@mapping"));
    }

    @Test
    public void testBothAssocFails() {
        GrammarDecl grammar = parseGrammar(
            "grammar G {\n"
                + "  @package: org.example\n"
                + "  @root\n"
                + "  @mapping(ExprNode, params=[left, op, right])\n"
                + "  @leftAssoc\n"
                + "  @rightAssoc\n"
                + "  @precedence(level=10)\n"
                + "  Expr ::= Term @left { '^' @op Term @right } ;\n"
                + "  Term ::= 'n' ;\n"
                + "}"
        );

        try {
            GrammarValidator.validateOrThrow(grammar);
            fail("expected validation error");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("cannot use both @leftAssoc and @rightAssoc"));
        }
    }

    @Test
    public void testPrecedenceTopologyValid() {
        GrammarDecl grammar = parseGrammar(
            "grammar G {\n"
                + "  @package: org.example\n"
                + "  @root\n"
                + "  @mapping(ExprNode, params=[left, op, right])\n"
                + "  @leftAssoc\n"
                + "  @precedence(level=10)\n"
                + "  Expr ::= Term @left { '+' @op Term @right } ;\n"
                + "  @mapping(TermNode, params=[left, op, right])\n"
                + "  @leftAssoc\n"
                + "  @precedence(level=20)\n"
                + "  Term ::= Factor @left { '*' @op Factor @right } ;\n"
                + "  Factor ::= 'n' ;\n"
                + "}"
        );

        GrammarValidator.validateOrThrow(grammar);
    }

    @Test
    public void testPrecedenceTopologyInvalidOrderFails() {
        GrammarDecl grammar = parseGrammar(
            "grammar G {\n"
                + "  @package: org.example\n"
                + "  @root\n"
                + "  @mapping(ExprNode, params=[left, op, right])\n"
                + "  @leftAssoc\n"
                + "  @precedence(level=20)\n"
                + "  Expr ::= Term @left { '+' @op Term @right } ;\n"
                + "  @mapping(TermNode, params=[left, op, right])\n"
                + "  @leftAssoc\n"
                + "  @precedence(level=10)\n"
                + "  Term ::= Factor @left { '*' @op Factor @right } ;\n"
                + "  Factor ::= 'n' ;\n"
                + "}"
        );

        try {
            GrammarValidator.validateOrThrow(grammar);
            fail("expected validation error");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("must be lower than referenced operator rule"));
        }
    }

    @Test
    public void testAssocWithoutPrecedenceFails() {
        GrammarDecl grammar = parseGrammar(
            "grammar G {\n"
                + "  @package: org.example\n"
                + "  @root\n"
                + "  @mapping(ExprNode, params=[left, op, right])\n"
                + "  @leftAssoc\n"
                + "  Expr ::= Term @left { '+' @op Term @right } ;\n"
                + "  Term ::= 'n' ;\n"
                + "}"
        );

        try {
            GrammarValidator.validateOrThrow(grammar);
            fail("expected validation error");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("uses @leftAssoc but has no @precedence"));
        }
    }

    @Test
    public void testMixedAssociativityAtSamePrecedenceFails() {
        GrammarDecl grammar = parseGrammar(
            "grammar G {\n"
                + "  @package: org.example\n"
                + "  @root\n"
                + "  @mapping(ExprNode, params=[left, op, right])\n"
                + "  @leftAssoc\n"
                + "  @precedence(level=10)\n"
                + "  Expr ::= Term @left { '+' @op Term @right } ;\n"
                + "  @mapping(PowNode, params=[left, op, right])\n"
                + "  @rightAssoc\n"
                + "  @precedence(level=10)\n"
                + "  Pow ::= Atom @left { '^' @op Pow @right } ;\n"
                + "  Term ::= 'n' ;\n"
                + "  Atom ::= 'n' ;\n"
                + "}"
        );

        try {
            GrammarValidator.validateOrThrow(grammar);
            fail("expected validation error");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("mixes associativity"));
        }
    }

    @Test
    public void testRightAssocNonCanonicalShapeFails() {
        GrammarDecl grammar = parseGrammar(
            "grammar G {\n"
                + "  @package: org.example\n"
                + "  @root\n"
                + "  @mapping(PowNode, params=[left, op, right])\n"
                + "  @rightAssoc\n"
                + "  @precedence(level=30)\n"
                + "  Expr ::= Atom @left { '^' @op Atom @right } ;\n"
                + "  Atom ::= 'n' ;\n"
                + "}"
        );

        try {
            GrammarValidator.validateOrThrow(grammar);
            fail("expected validation error");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("body is not canonical"));
            assertTrue(e.getMessage().contains("[hint:"));
        }
    }

    @Test
    public void testDuplicateInterleaveFails() {
        GrammarDecl grammar = parseGrammar(
            "grammar G {\n"
                + "  @package: org.example\n"
                + "  @root\n"
                + "  @interleave(profile=javaStyle)\n"
                + "  @interleave(profile=commentsAndSpaces)\n"
                + "  Start ::= 'ok' ;\n"
                + "}"
        );

        try {
            GrammarValidator.validateOrThrow(grammar);
            fail("expected validation error");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("duplicate @interleave"));
            assertTrue(e.getMessage().contains("E-ANNOTATION-DUPLICATE-INTERLEAVE"));
        }
    }

    @Test
    public void testUnsupportedInterleaveProfileFails() {
        GrammarDecl grammar = parseGrammar(
            "grammar G {\n"
                + "  @package: org.example\n"
                + "  @root\n"
                + "  @interleave(profile=custom)\n"
                + "  Start ::= 'ok' ;\n"
                + "}"
        );

        try {
            GrammarValidator.validateOrThrow(grammar);
            fail("expected validation error");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("unsupported @interleave profile"));
            assertTrue(e.getMessage().contains("E-ANNOTATION-INTERLEAVE-PROFILE"));
        }
    }

    @Test
    public void testUnsupportedScopeTreeModeFails() {
        GrammarDecl grammar = parseGrammar(
            "grammar G {\n"
                + "  @package: org.example\n"
                + "  @root\n"
                + "  @scopeTree(mode=custom)\n"
                + "  Start ::= 'ok' ;\n"
                + "}"
        );

        try {
            GrammarValidator.validateOrThrow(grammar);
            fail("expected validation error");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("unsupported @scopeTree mode"));
            assertTrue(e.getMessage().contains("E-ANNOTATION-SCOPETREE-MODE"));
        }
    }

    @Test
    public void testAdvancedAnnotationIssueCategory() {
        GrammarDecl grammar = parseGrammar(
            "grammar G {\n"
                + "  @package: org.example\n"
                + "  @root\n"
                + "  @interleave(profile=custom)\n"
                + "  Start ::= 'ok' ;\n"
                + "}"
        );

        var issues = GrammarValidator.validate(grammar);
        assertTrue(!issues.isEmpty());
        assertEquals("ANNOTATION", issues.get(0).category());
    }

    private GrammarDecl parseGrammar(String source) {
        return UBNFMapper.parse(source).grammars().get(0);
    }
}
