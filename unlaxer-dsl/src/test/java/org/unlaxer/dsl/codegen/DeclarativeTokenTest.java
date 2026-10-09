package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;
import java.util.List;
import org.junit.Test;
import org.unlaxer.StringSource;
import org.unlaxer.context.ParseContext;
import org.unlaxer.dsl.bootstrap.LexicalCompiler;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.runtime.LexicalTokenParser;

public class DeclarativeTokenTest {
    @Test public void unicodePrimitivesRejectIsolatedSurrogatesButAcceptPairs() {
        // Rust str cannot contain isolated UTF-16 surrogates; Java String can.
        for (String expression : List.of("ANY", "CHAR_RANGE('😀','🙏')", "NEGATION('x')")) {
            var parser = parser("token T ::= " + expression + ";");
            for (String source : List.of(String.valueOf((char) 0xd800), String.valueOf((char) 0xdfff))) {
                try (var context = new ParseContext(StringSource.createRootSource(source))) {
                    assertTrue(expression, parser.parse(context).isFailed());
                    assertEquals(0, context.position());
                    assertEquals(0, context.matchedPosition());
                }
            }
            try (var context = new ParseContext(StringSource.createRootSource("😀"))) {
                assertTrue(expression, parser.parse(context).isSucceeded());
                assertEquals(1, context.position());
                assertEquals(1, context.matchedPosition());
            }
        }
    }

    @Test public void xidRejectsIsolatedSurrogatesAndKeepsOriginalNormalization() {
        for (String declarations : List.of("token T = XID_IDENTIFIER", "token T ::= XID_IDENTIFIER;")) {
            LexicalTokenParser parser = parser(declarations);
            for (String input : List.of(String.valueOf((char) 0xd800), String.valueOf((char) 0xdfff), "_x", "9x", "\u037a")) {
                try (ParseContext context = new ParseContext(StringSource.createRootSource(input))) {
                    assertTrue(input, parser.parse(context).isFailed());
                    assertEquals(0, context.position());
                    assertEquals(0, context.matchedPosition());
                }
            }
            for (String input : List.of("é", "e\u0301", "𐐀x", "名前")) {
                try (ParseContext context = new ParseContext(StringSource.createRootSource(input))) {
                    assertTrue(input, parser.parse(context).isSucceeded());
                    assertEquals(input.codePointCount(0, input.length()), context.position());
                }
            }
        }
    }

    @Test public void commentedSettingsRetainTheirSemanticValues() throws Exception {
        var path = java.nio.file.Path.of("../rust/unlaxer-ubnf/tests/fixtures/positive/settings-comments.ubnf");
        var grammar = UBNFMapper.parse(java.nio.file.Files.readString(path)).grammars().get(0);
        assertEquals("v2", ((org.unlaxer.dsl.bootstrap.UBNFAST.StringSettingValue) grammar.settings().get(0).value()).value());
        assertEquals("lexical.probe", ((org.unlaxer.dsl.bootstrap.UBNFAST.StringSettingValue) grammar.settings().get(1).value()).value());
        assertEquals("javaStyle", ((org.unlaxer.dsl.bootstrap.UBNFAST.StringSettingValue) grammar.settings().get(2).value()).value());
        assertNotNull(LexicalCompiler.compile(grammar).get("NUMBER"));
        assertNotNull(new ParserGenerator().generate(grammar));
    }
    private LexicalTokenParser parser(String declarations) {
        var grammar = UBNFMapper.parse("grammar G { @ubnf: v2 " + declarations
            + " @root @mapping(Value, params=[value]) Root ::= T @value; }").grammars().get(0);
        return new LexicalTokenParser("T", LexicalCompiler.compile(grammar).get("T"));
    }
    @Test public void numberIsAtomicAndIncompleteExponentRetainsPrefix() {
        var parser = parser("token D ::= CHAR_RANGE('0','9'); token T ::= ['+'|'-'] (D+ '.' D* | '.' D+ | D+) [('e'|'E') ['+'|'-'] D+];");
        for (var row : List.of(new String[]{"1e+", "1"}, new String[]{"-.5E-2x", "6"}, new String[]{"12.", "3"}, new String[]{"1 .2", "1"})) {
            try (var context = new ParseContext(StringSource.createRootSource(row[0]))) {
                assertTrue(row[0], parser.parse(context).isSucceeded());
                assertEquals(row[0], Integer.parseInt(row[1]), context.position());
                assertEquals(context.position(), context.matchedPosition());
            }
        }
        for (String input : List.of("", "+", ".", " 1", "a")) {
            try (var context = new ParseContext(StringSource.createRootSource(input))) {
                assertTrue(input, parser.parse(context).isFailed());
                assertEquals(0, context.position()); assertEquals(0, context.matchedPosition());
            }
        }
    }
    @Test public void capturesAndLookaheadRollbackAndNonBmpSpan() {
        var parser = parser("token CHILD ::= CAPTURE(x, 'b') SAME_AS(x); token T ::= CAPTURE(x, '😀') LOOKAHEAD(CAPTURE(x,'a')) (CAPTURE(x,'a') '!' | 'a') CHILD SAME_AS(x);");
        try (var context = new ParseContext(StringSource.createRootSource("😀abb😀"))) {
            assertTrue(parser.parse(context).isSucceeded());
            assertEquals(5, context.position()); assertEquals(5, context.matchedPosition());
        }
        try (var context = new ParseContext(StringSource.createRootSource("😀abba"))) {
            assertTrue(parser.parse(context).isFailed());
            assertEquals(0, context.position()); assertEquals(0, context.matchedPosition());
        }
    }
    @Test public void zeroWidthAndEmptyLiteralSucceedAtEof() {
        for (String expression : List.of("EOF", "''", "LOOKAHEAD(EOF)", "NEGATIVE_LOOKAHEAD(ANY)", "BOF EOL")) {
            try (var context = new ParseContext(StringSource.createRootSource(""))) {
                assertTrue(expression, parser("token T ::= " + expression + ";").parse(context).isSucceeded());
                assertEquals(0, context.position()); assertEquals(0, context.matchedPosition());
            }
        }
    }
    @Test public void invalidBindingsAndProgressAreRejectedBeforeGeneration() {
        for (String declarations : List.of("token T ::= MISSING;", "token T ::= T;", "token T ::= SAME_AS(x);",
                "token T ::= [CAPTURE(x, 'a')] SAME_AS(x);", "token T ::= (CAPTURE(x,'a') | 'b') SAME_AS(x);",
                "token T ::= LOOKAHEAD(CAPTURE(x,'a')) SAME_AS(x);", "token T ::= {EOF};")) {
            assertThrows(declarations, IllegalArgumentException.class, () -> parser(declarations));
        }
    }
    @Test public void assertionsAndFailurePreserveAnIndependentMatchedCursor() {
        for (String expression : List.of("LOOKAHEAD('a')", "NEGATIVE_LOOKAHEAD('z')", "''", "'z'")) {
            try (var context = new ParseContext(StringSource.createRootSource("abc"))) {
                context.matchOnly(new org.unlaxer.CodePointLength(2));
                boolean success = parser("token T ::= " + expression + ";").parse(context).isSucceeded();
                assertEquals(expression, !expression.equals("'z'"), success);
                assertEquals(expression, 0, context.position());
                assertEquals(expression, 2, context.matchedPosition());
            }
        }
    }
}
