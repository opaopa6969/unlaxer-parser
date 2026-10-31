package org.unlaxer.parser.combinator;

import static org.junit.Assert.*;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.junit.Test;
import org.unlaxer.StringSource;
import org.unlaxer.Token;
import org.unlaxer.TokenKind;
import org.unlaxer.context.ParseContext;
import org.unlaxer.context.ParseOptions;
import org.unlaxer.context.SemanticPredicateBindings;
import org.unlaxer.context.SemanticPredicateBindings.Candidate;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.elementary.WordParser;

public class SemanticPredicateParserTest {
    private Parser gate(Parser child) {
        return new SemanticPredicateScope(new SemanticPredicateParser(child, "valid"), List.of("valid"));
    }
    @Test public void falseRollsBackAndAllowsChoiceFallback() {
        for (var diagnostics : ParseOptions.Diagnostics.values()) {
            try (var context = ParseContext.withOptions(StringSource.createRootSource("123"),
                    ParseOptions.defaults().withDiagnostics(diagnostics))) {
                SemanticPredicateBindings.install(context, Map.of("valid", candidate -> false));
                Parser choice = new SemanticPredicateScope(new Choice(
                    new SemanticPredicateParser(new WordParser("123"), "valid"), new WordParser("123")), List.of("valid"));
                var parsed = choice.parse(context);
                assertTrue(parsed.isSucceeded());
                assertEquals(3, context.getConsumedPosition().value());
                assertEquals("123", parsed.getConsumed().source.sourceAsString());
                assertTrue(SemanticPredicateBindings.failure(context).isEmpty());
            }
        }
    }
    @Test public void candidateUsesOriginalUnicodeTextAndCodePointSpan() {
        try (var context = new ParseContext(StringSource.createRootSource("a😀\r\n"))) {
            assertTrue(new WordParser("a").parse(context).isSucceeded());
            SemanticPredicateBindings.install(context, Map.of("valid", candidate -> {
                assertEquals(new Candidate("😀\r\n", 1, 4), candidate);
                return true;
            }));
            assertTrue(gate(new WordParser("😀\r\n")).parse(context).isSucceeded());
            assertEquals(4, context.getConsumedPosition().value());
        }
    }
    @Test public void missingBindingCannotBeHiddenBySuccessfulAlternative() {
        try (var context = new ParseContext(StringSource.createRootSource("x"))) {
            Parser choice = new SemanticPredicateScope(new Choice(
                new WordParser("x"), new SemanticPredicateParser(new WordParser("x"), "valid")), List.of("valid"));
            assertTrue(choice.parse(context).isFailed());
            assertEquals("predicate_missing", SemanticPredicateBindings.failure(context).orElseThrow().kind());
            assertEquals(0, context.getConsumedPosition().value());
            SemanticPredicateBindings.install(context, Map.of("valid", candidate -> true));
            assertTrue(SemanticPredicateBindings.failure(context).isEmpty());
            assertTrue(choice.parse(context).isSucceeded());
            assertTrue(SemanticPredicateBindings.failure(context).isEmpty());
        }
    }
    @Test public void callbackExceptionIsDistinctAndContextCanBeReused() {
        try (var context = new ParseContext(StringSource.createRootSource("x"))) {
            Predicate<Candidate> failing = candidate -> { throw new IllegalStateException("private detail"); };
            SemanticPredicateBindings.install(context, Map.of("valid", failing));
            Parser parser = gate(new WordParser("x"));
            assertTrue(parser.parse(context).isFailed());
            var failure = SemanticPredicateBindings.failure(context).orElseThrow();
            assertEquals("predicate_error", failure.kind());
            assertFalse(failure.expected().contains("private detail"));
            assertEquals(0, context.getConsumedPosition().value());
            SemanticPredicateBindings.install(context, Map.of("valid", candidate -> true));
            // Like Rust install, a new registry does not report the previous parse's failure.
            assertTrue(SemanticPredicateBindings.failure(context).isEmpty());
            assertTrue(parser.parse(context).isSucceeded());
            assertTrue(SemanticPredicateBindings.failure(context).isEmpty());
        }
    }
    @Test public void registryIsCopiedAndNamesAreBounded() {
        try (var context = new ParseContext(StringSource.createRootSource("x"))) {
            var registry = new java.util.HashMap<String, Predicate<Candidate>>();
            registry.put("valid", candidate -> true);
            SemanticPredicateBindings.install(context, registry);
            registry.clear();
            assertTrue(gate(new WordParser("x")).parse(context).isSucceeded());
        }
        for (String invalid : List.of("", "1bad", "a b", "é", "x".repeat(129)))
            assertThrows(IllegalArgumentException.class, () -> SemanticPredicateBindings.requireName(invalid));
    }

    @Test public void matchOnlyAndVirtualKindsStillExposeTheMatchedText() {
        for (var kind : TokenKind.values()) for (boolean invert : List.of(false, true)) {
            String text = invert ? "b" : "a";
            try (var context = new ParseContext(StringSource.createRootSource(text))) {
                SemanticPredicateBindings.install(context, Map.of("valid", candidate -> {
                    assertEquals(new Candidate(text, 0, 1), candidate);
                    return true;
                }));
                assertTrue(kind + "/" + invert, gate(new WordParser("a")).parse(context, kind, invert).isSucceeded());
            }
        }
    }

    @Test public void rejectionRestoresRegisteredChildState() {
        for (var memo : org.unlaxer.context.Memoization.values()) {
            try (var context = ParseContext.withOptions(StringSource.createRootSource("x"), ParseOptions.withMemoization(memo))) {
                int[] state = {0};
                context.registerTransactionalState(() -> {
                    int previous = state[0];
                    return () -> state[0] = previous;
                });
                Parser child = new WordParser("x") {
                    @Override public Token getToken(ParseContext parseContext, TokenKind kind, boolean invert) {
                        state[0]++;
                        return super.getToken(parseContext, kind, invert);
                    }
                };
                Parser parser = new SemanticPredicateScope(new Choice(new SemanticPredicateParser(child, "valid"),
                    new WordParser("x")), List.of("valid"));
                SemanticPredicateBindings.install(context, Map.of("valid", candidate -> false));
                assertTrue(parser.parse(context).isSucceeded());
                assertEquals(0, state[0]);
                assertEquals(1, context.getConsumedPosition().value());
            }
        }
    }
    @Test public void chainAndNullableCandidatesAreInputBacked() {
        try (var context = new ParseContext(StringSource.createRootSource("xa😀b"))) {
            assertTrue(new WordParser("x").parse(context).isSucceeded());
            var seen = new java.util.ArrayList<Candidate>();
            SemanticPredicateBindings.install(context, Map.of("valid", candidate -> seen.add(candidate)));
            Parser sequence = new Chain(new WordParser("a"), new WordParser("😀"), new WordParser("b"));
            Parser nullable = new SemanticPredicateParser(new Optional(new WordParser("z")), "valid");
            assertTrue(new SemanticPredicateScope(new Chain(new SemanticPredicateParser(sequence, "valid"), nullable),
                List.of("valid")).parse(context).isSucceeded());
            assertEquals(List.of(new Candidate("a😀b", 1, 4), new Candidate("", 4, 4)), seen);
        }
    }

    private static Parser generated(String text) {
        return new org.unlaxer.parser.elementary.EmptyParser() {
            private static final long serialVersionUID = 1L;
            @Override public org.unlaxer.Parsed parse(ParseContext parseContext, TokenKind kind, boolean invert) {
                return new org.unlaxer.Parsed(new Token(TokenKind.virtualTokenConsumed,
                    StringSource.createDetachedSource(text), this));
            }
        };
    }
    @Test public void generatedCandidateTextIsAnExplicitEntryFailure() {
        for (String text : List.of("", "g")) for (boolean chosen : List.of(false, true)) {
          for (var memo : org.unlaxer.context.Memoization.values()) for (var diagnostics : ParseOptions.Diagnostics.values()) {
            try (var context = ParseContext.withOptions(StringSource.createRootSource("😀b"),
                    ParseOptions.withMemoization(memo).withDiagnostics(diagnostics))) {
                int[] calls = {0};
                SemanticPredicateBindings.install(context, Map.of("valid", candidate -> { calls[0]++; return true; }));
                // Choice returns the selected child's Parsed, so its generated source is the candidate too.
                Parser child = chosen ? new Choice(generated(text)) : generated(text);
                Parser parser = new SemanticPredicateScope(new Chain(new WordParser("😀b"), new Choice(
                    new SemanticPredicateParser(child, "valid"), new org.unlaxer.parser.elementary.EmptyParser())), List.of("valid"));
                String label = "generated text=" + text + " chosen=" + chosen + " " + memo + "/" + diagnostics;
                assertTrue(label, parser.parse(context).isFailed());
                var failure = SemanticPredicateBindings.failure(context).orElseThrow();
                assertEquals(label, "predicate_source", failure.kind());
                assertEquals(label, 2, failure.start());
                assertEquals(label, 0, context.getConsumedPosition().value());
                assertEquals("origin-less text never reaches the callback: " + label, 0, calls[0]);
            }
          }
        }
    }

    /** A PEG assertion returns no token and a container an empty input view: both are "" at the cursor. */
    @Test public void sourceLessZeroWidthSuccessIsAnInputCandidateAtTheCursor() {
        Map<String, java.util.function.Supplier<Parser>> children = new java.util.LinkedHashMap<>();
        children.put("&c", () -> new PegLookahead(new WordParser("c"), true));
        children.put("!z", () -> new PegLookahead(new WordParser("z"), false));
        children.put("Choice(!z)", () -> new Choice(new PegLookahead(new WordParser("z"), false)));
        children.put("ErrorMessageParser", () -> new org.unlaxer.parser.ErrorMessageParser("m"));
        children.put("Chain(ErrorMessageParser)", () -> new Chain(new org.unlaxer.parser.ErrorMessageParser("m")));
        children.put("SuggestsCollectorParser", org.unlaxer.parser.SuggestsCollectorParser::new);
        children.put("EmptyParser", org.unlaxer.parser.elementary.EmptyParser::new);
        children.put("Optional(z)", () -> new Optional(new WordParser("z")));
        children.put("Chain(z?, y?)", () -> new Chain(new Optional(new WordParser("z")), new Optional(new WordParser("y"))));
        for (var child : children.entrySet()) for (boolean verdict : List.of(true, false)) {
          for (var memo : org.unlaxer.context.Memoization.values()) {
            for (var diagnostics : ParseOptions.Diagnostics.values()) {
              try (var context = ParseContext.withOptions(StringSource.createRootSource("😀bc"),
                      ParseOptions.withMemoization(memo).withDiagnostics(diagnostics))) {
                  var seen = new java.util.LinkedHashSet<Candidate>();
                  SemanticPredicateBindings.install(context, Map.of("valid", candidate -> {
                      seen.add(candidate);
                      return verdict;
                  }));
                  Parser parser = new SemanticPredicateScope(new Chain(new WordParser("😀b"), new Choice(
                      new SemanticPredicateParser(child.getValue().get(), "valid"), new WordParser("c"))), List.of("valid"));
                  String label = child.getKey() + " verdict=" + verdict + " " + memo + "/" + diagnostics;
                  assertTrue(label, parser.parse(context).isSucceeded());
                  assertEquals(label, java.util.Set.of(new Candidate("", 2, 2)), seen);
                  // true keeps the zero-width child; false rolls it back and the fallback reads "c".
                  assertEquals(label, verdict ? 2 : 3, context.getConsumedPosition().value());
                  assertTrue(label, SemanticPredicateBindings.failure(context).isEmpty());
              }
            }
          }
        }
    }
    @Test public void falseInsideOptionalLeavesNoCursorTokenOrState() {
        for (var memo : org.unlaxer.context.Memoization.values()) {
            try (var context = ParseContext.withOptions(StringSource.createRootSource("x"), ParseOptions.withMemoization(memo))) {
                int[] state = {0};
                context.registerTransactionalState(() -> {
                    int previous = state[0];
                    return () -> state[0] = previous;
                });
                Parser child = new WordParser("x") {
                    @Override public Token getToken(ParseContext parseContext, TokenKind kind, boolean invert) {
                        state[0]++;
                        return super.getToken(parseContext, kind, invert);
                    }
                };
                // No enclosing Choice: only the predicate's own transaction can discard the candidate.
                Parser parser = new SemanticPredicateScope(new Chain(new Optional(new SemanticPredicateParser(child, "valid")),
                    new WordParser("x")), List.of("valid"));
                SemanticPredicateBindings.install(context, Map.of("valid", candidate -> false));
                var parsed = parser.parse(context);
                assertTrue(parsed.isSucceeded());
                assertEquals(0, state[0]);
                assertEquals(1, context.getConsumedPosition().value());
                assertEquals("x", parsed.getConsumed().source.sourceAsString());
            }
        }
    }
}
