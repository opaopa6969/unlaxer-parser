package org.unlaxer.dsl;

import static org.junit.Assert.*;
import org.junit.Test;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.codegen.rust.GrammarIR;
import org.unlaxer.dsl.codegen.rust.RustBackend;
import org.unlaxer.dsl.codegen.rust.RustGrammarLowering;

public class RustSkipLoweringTest {
    private static GrammarIR lower(String body) {
        return RustGrammarLowering.lower(UBNFMapper.parse("grammar Skip { " + body + " }").grammars().get(0));
    }

    @Test public void manuallyConstructedIrCannotMapASkippedRule() {
        var mapping = new GrammarIR.Mapping("Value", java.util.List.of());
        var literal = new GrammarIR.Literal("x");
        assertFalse(new GrammarIR.Rule("Root", literal, mapping, null, null).skip());
        assertThrows(IllegalArgumentException.class,
            () -> new GrammarIR.Rule("Root", literal, mapping, null, null, true));
    }

    @Test public void skipIsTextToItsCallerAndExcludedFromTheSharedSchema() {
        var ir = lower("""
            @root @mapping(Root, params=[value]) Root ::= Hidden @value;
            @skip @mapping(Leaf, params=[other]) Hidden ::= '(' Leaf ')' @other;
            @mapping(Leaf, params=[value]) Leaf ::= 'x' @value;
            """);
        assertTrue(ir.rules().get(1).skip());
        assertNull(ir.rules().get(1).mapping());
        assertEquals(GrammarIR.Kind.TEXT, ir.rules().get(0).mapping().fields().get(0).kind());
        assertEquals(2, ir.mappings().size());
        assertEquals("value", ir.mappings().get(1).fields().get(0).name());
    }

    @Test public void skippedFieldsDoNotImposeUnusedRustIdentifiers() {
        String body = """
            @root @mapping(Root, params=[value]) Root ::= Hidden @value;
            @skip @mapping(Ghost.Inner, params=[span, semantics]) Hidden ::= 'a' @span 'b' @semantics;
            """;
        assertTrue(lower(body).rules().get(1).skip());
        assertTrue(PortabilityCheck.check("grammar Skip { " + body + " }").portable());
    }

    @Test public void skippedRootAndTransparentRootCanParseWithoutAnAst() {
        for (String body : new String[] {
            "@root @skip Root ::= 'x';",
            "@root Root ::= Alias; Alias ::= (Hidden); @skip Hidden ::= 'x';",
            "@root Root ::= Hidden | Visible; @skip Hidden ::= 'x'; @mapping(Value) Visible ::= 'v';"
        }) {
            var grammar = UBNFMapper.parse("grammar Skip { " + body + " }").grammars().get(0);
            assertEquals(5, new RustBackend().generate(grammar).size());
            assertTrue(PortabilityCheck.check("grammar Skip { " + body + " }").portable());
        }
        assertFalse(PortabilityCheck.check("grammar Skip { @root Root ::= 'x'; }").portable());
    }

    @Test public void skippedRightAssociativeRuleRetainsTheParserRewrite() {
        var ir = lower("""
            @root @skip @rightAssoc @precedence(level=10)
            @mapping(Power, params=[left, op, right])
            Root ::= Base @left { '^' @op Root @right };
            @mapping(Value) Base ::= 'x';
            """);
        assertTrue(ir.rules().get(0).skip());
        assertNull(ir.rules().get(0).mapping());
        assertTrue(ir.rules().get(0).body() instanceof GrammarIR.Choice);
        assertEquals(GrammarIR.Associativity.RIGHT, ir.rules().get(0).operator().associativity());
    }

    @Test public void skipDoesNotHideInvalidSyntaxOrUnknownAnnotations() {
        for (String body : new String[] {
            "@root @skip Root ::= Missing;",
            "@root @skip Root ::= { [ 'x' ] };",
            "@root @skip @unknown Root ::= 'x';",
            "@root @skip @mapping(Ghost, params=[missing]) Root ::= 'x';"
        }) {
            assertFalse(body, PortabilityCheck.check("grammar Skip { " + body + " }").portable());
        }
    }
}
