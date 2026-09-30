package org.unlaxer.dsl;

import static org.junit.Assert.*;
import org.junit.Test;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.codegen.rust.GrammarIR;
import org.unlaxer.dsl.codegen.rust.RustBackend;
import org.unlaxer.dsl.codegen.rust.RustGrammarLowering;

public class RustCaptureEqualityLoweringTest {
    private static final String RULES = """
        token ID = IdentifierParser
        @root @mapping(Document, params=[tag,noise]) @backref(name=tag)
        Root ::= ID @tag ':' ID @noise '#' ID @tag;
        """;

    private static GrammarIR lower(String rules) {
        return RustGrammarLowering.lower(UBNFMapper.parse("grammar Equality { " + rules + " }")
            .grammars().get(0));
    }

    @Test public void scopeLessComparisonIsDistinctFromScopedReference() {
        var ir = lower(RULES);
        var comparison = (GrammarIR.CaptureEquality) ir.rules().get(0).body();
        assertEquals("tag", comparison.name());
        assertFalse(comparison.child() instanceof GrammarIR.RuleEffects);
        assertEquals(GrammarIR.Cardinality.MANY, ir.rules().get(0).mapping().fields().get(0).cardinality());
        String parser = new RustBackend().generate(UBNFMapper.parse("grammar Equality { " + RULES + " }")
            .grammars().get(0)).stream().filter(file -> file.relativePath().equals("parser.rs"))
            .findFirst().orElseThrow().content();
        assertTrue(parser.contains("Expr::compare_captures(\"tag\", "));
        assertFalse(parser.contains("backref: Some(\"tag\")"));
    }

    @Test public void anyGrammarScopeKeepsLegacyReferenceMode() {
        var ir = lower(RULES + " @scopeTree(mode=lexical) Unused ::= 'x';");
        var effects = ((GrammarIR.RuleEffects) ir.rules().get(0).body()).effects();
        assertEquals("tag", effects.backref());
        assertNull(effects.scopeMode());
    }

    @Test public void comparisonCanCoexistWithDeclarationAndTrivia() {
        var ir = lower("@whitespace: javaStyle " + RULES.replace("@backref(name=tag)",
            "@backref(name=tag) @declares(symbol=noise) @whitespace(none)"));
        var comparison = (GrammarIR.CaptureEquality) ir.rules().get(0).body();
        var effects = (GrammarIR.RuleEffects) comparison.child();
        assertEquals("noise", effects.effects().declares().symbolCapture());
        assertNull(effects.effects().backref());
        assertTrue(effects.child() instanceof GrammarIR.TriviaScope);
    }

    @Test public void syntaxOnlyComparisonRootsAndAliasesCanGenerateEmptyAst() {
        for (String rules : new String[]{
            "@root @backref(name=tag) Root ::= 'x' @tag '#' 'x' @tag;",
            "@root Root ::= Alias; Alias ::= Checked; @backref(name=tag) Checked ::= 'x' @tag;"
        }) {
            var ir = lower(rules);
            assertTrue(ir.mappings().isEmpty());
            var grammar = UBNFMapper.parse("grammar Equality { " + rules + " }").grammars().get(0);
            assertEquals(5, new RustBackend().generate(grammar).size());
            assertTrue(PortabilityCheck.check("grammar Equality { " + rules + " }").portable());
        }
        assertThrows(IllegalArgumentException.class,
            () -> lower("@root Root ::= 'x'; @backref(name=tag) Unused ::= 'y' @tag;"));
    }

    @Test public void skipDoesNotRemoveTheComparisonEffect() {
        var ir = lower(RULES.replace("@root", "@root @skip"));
        assertTrue(ir.rules().get(0).skip());
        assertNull(ir.rules().get(0).mapping());
        assertTrue(ir.rules().get(0).body() instanceof GrammarIR.CaptureEquality);
    }

    @Test public void invalidTargetsAndDuplicatesRemainRejected() {
        for (String rules : new String[]{
            RULES.replace("name=tag", "name=missing"),
            RULES.replace("@backref(name=tag)", "@backref(name=tag) @backref(name=tag)"),
            "@root @mapping(Root) @backref(name=tag) Root ::= Child; Child ::= 'x' @tag;"
        }) {
            assertThrows(rules, IllegalArgumentException.class, () -> lower(rules));
            assertFalse(PortabilityCheck.check("grammar Equality { " + rules + " }").portable());
        }
    }
}
