package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;
import org.junit.Test;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.codegen.rust.GrammarIR;
import org.unlaxer.dsl.codegen.rust.RustBackend;
import org.unlaxer.dsl.codegen.rust.RustGrammarLowering;

public class RuleTriviaPolicyTest {
    private GrammarDecl grammar(String settings, String root, String child) {
        return UBNFMapper.parse("grammar G { " + settings
            + " @root @mapping(Root, params=[value]) " + root
            + " Start ::= Child @value ; " + child + " Child ::= 'a' 'b' ; }").grammars().get(0);
    }

    @Test public void globalNoneIsAcceptedAndDoesNotEnableJavaDelimiters() {
        var none = grammar("@whitespace: NONE", "", "");
        GrammarValidator.validateOrThrow(none);
        String source = new ParserGenerator().generate(none).source();
        assertFalse(source.contains("SpaceParser.class"));
        assertFalse(source.contains("CPPComment.class"));
        assertFalse(RustGrammarLowering.lower(none).javaWhitespace());
        var enabled = grammar("@whitespace: JAVASTYLE", "", "");
        GrammarValidator.validateOrThrow(enabled);
        assertTrue(new ParserGenerator().generate(enabled).source().contains("SpaceParser.class"));
        assertTrue(RustGrammarLowering.lower(enabled).javaWhitespace());
    }

    @Test public void everyRuleResolvesAgainstGrammarInsteadOfCaller() {
        for (var input : new String[][] {
            {"", "@whitespace", "", "true", "false"},
            {"@whitespace: javaStyle", "@whitespace(none)", "", "false", "true"},
            {"@whitespace: none", "", "@interleave(profile=commentsAndSpaces)", "false", "true"},
            {"", "@interleave(profile=javaStyle) @whitespace(none)", "@whitespace(JAVASTYLE)", "false", "true"}
        }) {
            var grammar = grammar(input[0], input[1], input[2]);
            GrammarValidator.validateOrThrow(grammar);
            var ir = RustGrammarLowering.lower(grammar);
            for (int i = 0; i < 2; i++) {
                assertTrue(ir.rules().get(i).body() instanceof GrammarIR.TriviaScope);
                assertEquals(Boolean.parseBoolean(input[i + 3]),
                    ((GrammarIR.TriviaScope) ir.rules().get(i).body()).javaWhitespace());
            }
            new RustBackend().generate(grammar);
        }
        assertFalse(RustGrammarLowering.lower(grammar("", "", "")).rules().get(0).body()
            instanceof GrammarIR.TriviaScope);
    }

    @Test public void duplicateAndInvalidPoliciesAreRejectedByBothJavaPaths() {
        for (String annotation : new String[] {
            "@whitespace(custom)", "@whitespace @whitespace(none)",
            "@interleave(profile=custom)", "@interleave(profile=JAVASTYLE)",
            "@interleave(profile=javaStyle) @interleave(profile=commentsAndSpaces)"
        }) {
            var grammar = grammar("", annotation, "");
            assertThrows(annotation, IllegalArgumentException.class, () -> GrammarValidator.validateOrThrow(grammar));
            assertThrows(annotation, IllegalArgumentException.class, () -> RustGrammarLowering.lower(grammar));
        }
        var duplicate = grammar("@whitespace: none @whitespace: javaStyle", "", "");
        assertThrows(IllegalArgumentException.class, () -> GrammarValidator.validateOrThrow(duplicate));
        assertThrows(IllegalArgumentException.class, () -> RustGrammarLowering.lower(duplicate));
    }
}
