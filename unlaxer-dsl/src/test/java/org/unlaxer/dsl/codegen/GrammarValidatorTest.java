package org.unlaxer.dsl.codegen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;
import java.util.Optional;
import java.util.Set;

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

    // =========================================================================
    // Levenshtein suggestSimilarName tests
    // =========================================================================

    @Test
    public void testSuggestSimilarNameExactMatch() {
        String result = GrammarValidator.suggestSimilarName("Expr", Set.of("Expr", "Term", "Factor"));
        assertEquals("Expr", result);
    }

    @Test
    public void testSuggestSimilarNameCloseMatch() {
        // "Epxr" is distance 2 from "Expr" (transposition = 2 edits in Levenshtein)
        String result = GrammarValidator.suggestSimilarName("Exprs", Set.of("Expr", "Term", "Factor"));
        assertEquals("Expr", result);
    }

    @Test
    public void testSuggestSimilarNameOneCharDifference() {
        String result = GrammarValidator.suggestSimilarName("Tern", Set.of("Expr", "Term", "Factor"));
        assertEquals("Term", result);
    }

    @Test
    public void testSuggestSimilarNameNoMatch() {
        // "XYZ" is far from all candidates
        String result = GrammarValidator.suggestSimilarName("XYZ", Set.of("Expression", "Statement", "Declaration"));
        assertNull(result);
    }

    @Test
    public void testSuggestSimilarNameEmptyKnown() {
        String result = GrammarValidator.suggestSimilarName("Expr", Set.of());
        assertNull(result);
    }

    @Test
    public void testSuggestSimilarNameNullUnknown() {
        String result = GrammarValidator.suggestSimilarName(null, Set.of("Expr"));
        assertNull(result);
    }

    @Test
    public void testSuggestSimilarNamePicksClosest() {
        // "Fator" is distance 1 from "Factor", distance 2 from "Factr", distance >2 from "Expr"
        String result = GrammarValidator.suggestSimilarName("Fator", Set.of("Expr", "Factor", "Term"));
        assertEquals("Factor", result);
    }

    // =========================================================================
    // E-RULE-UNDEFINED validation tests
    // =========================================================================

    @Test
    public void testUndefinedRuleRefFails() {
        GrammarDecl grammar = parseGrammar(
            "grammar G {\n"
                + "  @package: org.example\n"
                + "  @root\n"
                + "  Start ::= Undefined ;\n"
                + "}"
        );

        List<GrammarValidator.ValidationIssue> issues = GrammarValidator.validate(grammar);
        boolean hasUndefined = issues.stream()
            .anyMatch(issue -> "E-RULE-UNDEFINED".equals(issue.code()));
        assertTrue("expected E-RULE-UNDEFINED error", hasUndefined);

        GrammarValidator.ValidationIssue undefinedIssue = issues.stream()
            .filter(issue -> "E-RULE-UNDEFINED".equals(issue.code()))
            .findFirst()
            .orElseThrow();
        assertTrue(undefinedIssue.message().contains("'Undefined'"));
        assertEquals("RULE", undefinedIssue.category());
    }

    @Test
    public void testUndefinedRuleRefWithSuggestion() {
        GrammarDecl grammar = parseGrammar(
            "grammar G {\n"
                + "  @package: org.example\n"
                + "  @root\n"
                + "  Start ::= Tern ;\n"
                + "  Term ::= 'n' ;\n"
                + "}"
        );

        List<GrammarValidator.ValidationIssue> issues = GrammarValidator.validate(grammar);
        boolean hasUndefined = issues.stream()
            .anyMatch(issue -> "E-RULE-UNDEFINED".equals(issue.code()));
        assertTrue("expected E-RULE-UNDEFINED error", hasUndefined);

        GrammarValidator.ValidationIssue undefinedIssue = issues.stream()
            .filter(issue -> "E-RULE-UNDEFINED".equals(issue.code()))
            .findFirst()
            .orElseThrow();
        assertTrue(undefinedIssue.message().contains("'Tern'"));
        assertTrue(undefinedIssue.hint().contains("Did you mean 'Term'?"));
    }

    @Test
    public void testDefinedRuleRefPasses() {
        GrammarDecl grammar = parseGrammar(
            "grammar G {\n"
                + "  @package: org.example\n"
                + "  @root\n"
                + "  Start ::= Term ;\n"
                + "  Term ::= 'n' ;\n"
                + "}"
        );

        List<GrammarValidator.ValidationIssue> issues = GrammarValidator.validate(grammar);
        boolean hasUndefined = issues.stream()
            .anyMatch(issue -> "E-RULE-UNDEFINED".equals(issue.code()));
        assertTrue("should have no E-RULE-UNDEFINED error", false == hasUndefined);
    }

    @Test
    public void testUndefinedRuleRefNoSuggestionForDistantName() {
        GrammarDecl grammar = parseGrammar(
            "grammar G {\n"
                + "  @package: org.example\n"
                + "  @root\n"
                + "  Start ::= CompletelyWrong ;\n"
                + "  Term ::= 'n' ;\n"
                + "}"
        );

        List<GrammarValidator.ValidationIssue> issues = GrammarValidator.validate(grammar);
        GrammarValidator.ValidationIssue undefinedIssue = issues.stream()
            .filter(issue -> "E-RULE-UNDEFINED".equals(issue.code()))
            .findFirst()
            .orElseThrow();
        // Should NOT contain "Did you mean" since distance is too large
        assertTrue(undefinedIssue.hint().contains("Define a rule or token"));
        assertTrue(false == undefinedIssue.hint().contains("Did you mean"));
    }

    // =========================================================================
    // validateWithWarnings / left-recursion detection tests
    // =========================================================================

    @Test
    public void testValidateWithWarningsNoLeftRecursion() {
        GrammarDecl grammar = parseGrammar(
            "grammar G {\n"
                + "  @package: org.example\n"
                + "  @root\n"
                + "  Start ::= Term ;\n"
                + "  Term ::= 'n' ;\n"
                + "}"
        );

        Optional<List<String>> result = GrammarValidator.validateWithWarnings(grammar);
        assertFalse("no left-recursion expected", result.isPresent());
    }

    @Test
    public void testValidateWithWarningsDirectLeftRecursion() {
        // Direct left-recursion: Expr ::= Expr '+' Term | Term
        GrammarDecl grammar = parseGrammar(
            "grammar G {\n"
                + "  @package: org.example\n"
                + "  @root\n"
                + "  Expr ::= Expr | 'n' ;\n"
                + "}"
        );

        Optional<List<String>> result = GrammarValidator.validateWithWarnings(grammar);
        assertTrue("direct left-recursion should produce warning", result.isPresent());
        List<String> warnings = result.get();
        assertFalse("warnings list must not be empty", warnings.isEmpty());
        assertTrue("warning message should mention Expr -> Expr",
            warnings.stream().anyMatch(w -> w.contains("W-LEFT-RECURSION")));
        assertTrue("warning message should contain rule name",
            warnings.stream().anyMatch(w -> w.contains("Expr")));
    }

    @Test
    public void testValidateWithWarningsIndirectLeftRecursion() {
        // Indirect left-recursion: A ::= B ...; B ::= A ...
        GrammarDecl grammar = parseGrammar(
            "grammar G {\n"
                + "  @package: org.example\n"
                + "  @root\n"
                + "  A ::= B ;\n"
                + "  B ::= A ;\n"
                + "}"
        );

        Optional<List<String>> result = GrammarValidator.validateWithWarnings(grammar);
        assertTrue("indirect left-recursion should produce warning", result.isPresent());
        List<String> warnings = result.get();
        assertFalse("warnings list must not be empty", warnings.isEmpty());
        assertTrue("warning should mention W-LEFT-RECURSION",
            warnings.stream().anyMatch(w -> w.contains("W-LEFT-RECURSION")));
    }

    @Test
    public void testValidateWithWarningsDoesNotThrowOnExistingGrammars() {
        // Verifies that left-recursion detection never throws, even for complex grammars
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

        // Must not throw; the result is unimportant for this well-formed grammar
        Optional<List<String>> result = GrammarValidator.validateWithWarnings(grammar);
        // Well-formed grammar — no left-recursion expected
        assertFalse("well-formed grammar should have no left-recursion warnings", result.isPresent());
    }

    @Test
    public void testValidateWithWarningsReturnsEmptyOptionalForNonRecursiveGrammar() {
        GrammarDecl grammar = parseGrammar(
            "grammar G {\n"
                + "  @package: org.example\n"
                + "  @root\n"
                + "  S ::= A B ;\n"
                + "  A ::= 'a' ;\n"
                + "  B ::= 'b' ;\n"
                + "}"
        );

        Optional<List<String>> result = GrammarValidator.validateWithWarnings(grammar);
        assertFalse("non-recursive grammar should return empty optional", result.isPresent());
    }

    // =========================================================================
    // detectLeftRecursionIssues (structured) tests
    // =========================================================================

    @Test
    public void testDetectLeftRecursionIssuesReturnsStructuredWarning() {
        GrammarDecl grammar = parseGrammar(
            "grammar G {\n"
                + "  @package: org.example\n"
                + "  @root\n"
                + "  Expr ::= Expr | 'n' ;\n"
                + "}"
        );

        List<GrammarValidator.ValidationIssue> issues =
            GrammarValidator.detectLeftRecursionIssues(grammar);
        assertFalse("direct left-recursion should produce an issue", issues.isEmpty());
        GrammarValidator.ValidationIssue issue = issues.get(0);
        assertEquals("W-LEFT-RECURSION", issue.code());
        assertEquals("WARNING", issue.severity());
        assertEquals("Expr", issue.rule());
        assertTrue("message should contain the cycle",
            issue.message().contains("Expr -> Expr"));
    }

    @Test
    public void testDetectLeftRecursionIssuesEmptyForCleanGrammar() {
        GrammarDecl grammar = parseGrammar(
            "grammar G {\n"
                + "  @package: org.example\n"
                + "  @root\n"
                + "  S ::= A ;\n"
                + "  A ::= 'a' ;\n"
                + "}"
        );

        assertTrue(GrammarValidator.detectLeftRecursionIssues(grammar).isEmpty());
    }

    // =========================================================================
    // W-TOKEN-UNRESOLVED hint tests
    // =========================================================================

    @Test
    public void testTokenUnresolvedHintSuggestsFullyQualifiedName() {
        GrammarDecl grammar = parseGrammar(
            "grammar G {\n"
                + "  @package: org.example\n"
                + "  token NUMBER = NumberParser\n"
                + "  @root\n"
                + "  Start ::= NUMBER ;\n"
                + "}"
        );

        List<GrammarValidator.ValidationIssue> issues = GrammarValidator.validate(grammar);
        GrammarValidator.ValidationIssue unresolved = issues.stream()
            .filter(issue -> "W-TOKEN-UNRESOLVED".equals(issue.code()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("expected W-TOKEN-UNRESOLVED issue"));
        assertTrue("hint should suggest the fully qualified candidate: " + unresolved.hint(),
            unresolved.hint().contains("org.unlaxer.parser.elementary.NumberParser"));
    }

    @Test
    public void testTokenUnresolvedHintWithoutCandidateKeepsGenericHint() {
        GrammarDecl grammar = parseGrammar(
            "grammar G {\n"
                + "  @package: org.example\n"
                + "  token X = NoSuchParserClass\n"
                + "  @root\n"
                + "  Start ::= X ;\n"
                + "}"
        );

        List<GrammarValidator.ValidationIssue> issues = GrammarValidator.validate(grammar);
        GrammarValidator.ValidationIssue unresolved = issues.stream()
            .filter(issue -> "W-TOKEN-UNRESOLVED".equals(issue.code()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("expected W-TOKEN-UNRESOLVED issue"));
        assertFalse("hint should not contain a bogus suggestion",
            unresolved.hint().contains("Did you mean"));
    }

    private GrammarDecl parseGrammar(String source) {
        return UBNFMapper.parse(source).grammars().get(0);
    }
}
