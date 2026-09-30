package org.unlaxer.dsl;

import static org.junit.Assert.*;

import org.junit.Test;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.codegen.rust.GrammarIR;
import org.unlaxer.dsl.codegen.rust.RustApiSchema;
import org.unlaxer.dsl.codegen.rust.RustBackend;
import org.unlaxer.dsl.codegen.rust.RustGrammarLowering;

/** ERROR(message) is a failing expected hint, not an error-recovery node. */
public class RustErrorElementLoweringTest {
    private static final String GRAMMAR = """
        grammar Hints {
          @root @mapping(Value, params=[text])
          Root ::= 'ok' @text | ERROR('missing value');
        }
        """;

    @Test public void lowersAndEmitsErrorHintWithoutChangingApiSchema() {
        var grammar = UBNFMapper.parse(GRAMMAR).grammars().get(0);
        var ir = RustGrammarLowering.lower(grammar);
        var choice = (GrammarIR.Choice) ir.rules().get(0).body();
        assertEquals(new GrammarIR.ErrorExpected("missing value"), choice.alternatives().get(1));
        var files = new RustBackend().generate(grammar);
        assertEquals(5, files.size());
        String parser = files.stream().filter(file -> file.relativePath().equals("parser.rs"))
            .findFirst().orElseThrow().content();
        assertTrue(parser.contains("Expr::Error(\"missing value\")"));
        assertFalse(parser.contains("recovery"));
        assertTrue(PortabilityCheck.check(GRAMMAR).portable());
        assertEquals(1, RustApiSchema.build(UBNFMapper.parseWithSource(GRAMMAR)).nodes().size());
    }

    @Test public void blankHintCanBeLoweredWithoutInventingAnExpectedTerminal() {
        for (String message : new String[]{"", "   "}) {
            String source = GRAMMAR.replace("missing value", message);
            var grammar = UBNFMapper.parse(source).grammars().get(0);
            var choice = (GrammarIR.Choice) RustGrammarLowering.lower(grammar).rules().get(0).body();
            assertEquals(new GrammarIR.ErrorExpected(message), choice.alternatives().get(1));
            assertTrue(PortabilityCheck.check(source).portable());
        }
    }

    @Test public void failingHintIsNotNullableInRecursiveAndRepeatedAnalysis() {
        String unbounded = GRAMMAR.replace("'ok' @text | ERROR('missing value')",
            "{ ERROR('missing value') } 'ok' @text");
        assertEquals(5, new RustBackend().generate(UBNFMapper.parse(unbounded).grammars().get(0)).size());
        String recursion = GRAMMAR.replace("'ok' @text | ERROR('missing value')",
            "ERROR('missing value') Root @text | 'ok' @text");
        assertEquals(5, new RustBackend().generate(UBNFMapper.parse(recursion).grammars().get(0)).size());
    }

    @Test public void predictiveChoiceRetainsErrorAlternative() {
        String source = GRAMMAR.replace("@root @mapping", "@root @predictiveChoice @mapping");
        var choice = (GrammarIR.PredictiveChoice) RustGrammarLowering.lower(
            UBNFMapper.parse(source).grammars().get(0)).rules().get(0).body();
        assertEquals(new GrammarIR.AnyPredictor(), choice.predictors().get(1));
    }
}
