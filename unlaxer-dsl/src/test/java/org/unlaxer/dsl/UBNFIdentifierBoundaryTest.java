package org.unlaxer.dsl;

import static org.junit.Assert.*;

import java.util.List;
import java.util.Optional;
import org.junit.Test;
import org.unlaxer.StringSource;
import org.unlaxer.TokenKind;
import org.unlaxer.context.ParseContext;
import org.unlaxer.dsl.bootstrap.UBNFAST.*;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.bootstrap.UBNFParsers;
import org.unlaxer.dsl.codegen.rust.RustBackend;
import org.unlaxer.parser.Parser;

/** #131: lexical identifiers must not use grammar-level trivia between characters. */
public class UBNFIdentifierBoundaryTest {
    private static final List<String> SEPARATORS = List.of(" ", "  ", "\t", "\n", "\r", "\r\n", "// comment\n");

    private GrammarDecl grammar(String declarations) {
        return UBNFMapper.parse("grammar Example { " + declarations + " }").grammars().get(0);
    }

    private List<AnnotatedElement> elements(String body) {
        RuleDecl root = grammar("Root ::= " + body + ";").rules().get(0);
        return ((ChoiceBody) root.body()).alternatives().get(0).elements();
    }

    private void ref(AnnotatedElement element, String namespace, String name, String capture) {
        assertEquals(new RuleRefElement(Optional.ofNullable(namespace), name), element.element());
        assertEquals(Optional.ofNullable(capture), element.captureName());
    }

    @Test public void lexicalIdentifierStopsBeforeEveryTriviaBoundary() {
        for (String separator : SEPARATORS) {
            try (var context = new ParseContext(StringSource.createRootSource("T" + separator + "Root"))) {
                var result = Parser.get(UBNFParsers.IdentifierParser.class).parse(context);
                assertTrue(result.isSucceeded());
                assertEquals(separator, 1, context.getPosition(TokenKind.consumed).value());
                assertEquals("T", result.getRootToken().source.sourceAsString());
            }
        }
        for (String name : List.of("T", "Root", "_", "_name42", "a9")) {
            try (var context = new ParseContext(StringSource.createRootSource(name))) {
                assertTrue(Parser.get(UBNFParsers.IdentifierParser.class).parse(context).isSucceeded());
                assertTrue(context.allConsumed());
            }
        }
        try (var context = new ParseContext(StringSource.createRootSource("T/* comment */Root"))) {
            assertTrue(Parser.get(UBNFParsers.IdentifierParser.class).parse(context).isSucceeded());
            assertEquals(1, context.getPosition(TokenKind.consumed).value());
        }
        // UBNF has // comments, not block comments; fixing identifier boundaries
        // must not silently extend the grammar's comment syntax.
        assertThrows(IllegalArgumentException.class, () -> elements("T/* comment */Root"));
    }

    @Test public void adjacentBareReferencesRemainOrderedDistinctElements() {
        for (String separator : SEPARATORS) {
            var elements = elements("T" + separator + "Root");
            assertEquals(separator, 2, elements.size());
            ref(elements.get(0), null, "T", null);
            ref(elements.get(1), null, "Root", null);
        }
        var elements = elements("A B C");
        assertEquals(3, elements.size());
        ref(elements.get(0), null, "A", null);
        ref(elements.get(1), null, "B", null);
        ref(elements.get(2), null, "C", null);
    }

    @Test public void capturesAndNamespaceReferencesDoNotConsumeFollowingNames() {
        var elements = elements("T @x Root @value");
        assertEquals(2, elements.size());
        ref(elements.get(0), null, "T", "x");
        ref(elements.get(1), null, "Root", "value");
        elements = elements("a.T @x b.Root @value");
        assertEquals(2, elements.size());
        ref(elements.get(0), "a", "T", "x");
        ref(elements.get(1), "b", "Root", "value");
        elements = elements("a.T b.Root");
        assertEquals(2, elements.size());
        ref(elements.get(0), "a", "T", null);
        ref(elements.get(1), "b", "Root", null);
    }

    @Test public void groupingAndQuantifiersKeepNextReferenceOutside() {
        for (String prefix : List.of("(T)", "[T]", "{T}", "T+", "T?", "T*", "T{2}", "T{1,3}", "T % ','")) {
            var elements = elements(prefix + " Root @value");
            assertEquals(prefix, 2, elements.size());
            assertFalse(prefix, elements.get(0).element() instanceof RuleRefElement);
            ref(elements.get(1), null, "Root", "value");
        }
    }

    @Test public void declarationAndFqnBoundariesRetainSingleLetterNames() {
        var parsed = grammar("@package: a.b token T=org.unlaxer.parser.elementary.EndOfSourceParser "
            + "@root @mapping(Value, params=[value]) Root ::= T Root @value;");
        assertEquals("T", parsed.tokens().get(0).name());
        assertEquals("org.unlaxer.parser.elementary.EndOfSourceParser", parsed.tokens().get(0).parserClass());
        var elements = ((ChoiceBody) parsed.rules().get(0).body()).alternatives().get(0).elements();
        assertEquals(2, elements.size());
        ref(elements.get(0), null, "T", null);
        ref(elements.get(1), null, "Root", "value");
    }

    @Test public void backendValidationReceivesRealReferencesRatherThanFusedNames() {
        var recursive = grammar("token T=EOF @root @mapping(Value, params=[value]) Root ::= T Root @value;");
        var error = assertThrows(IllegalArgumentException.class, () -> new RustBackend().generate(recursive));
        assertTrue(error.toString(), error.getMessage().contains("left recursion at Root"));
        var missing = grammar("token A=ANY @root @mapping(Value, params=[value]) Root ::= A Missing @value;");
        error = assertThrows(IllegalArgumentException.class, () -> new RustBackend().generate(missing));
        assertTrue(error.toString(), error.getMessage().contains("unknown reference Missing"));
        assertFalse(error.toString(), error.getMessage().contains("A Missing"));
    }
}
