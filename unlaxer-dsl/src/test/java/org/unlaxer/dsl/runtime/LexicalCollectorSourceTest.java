package org.unlaxer.dsl.runtime;

import static org.junit.Assert.assertEquals;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;
import org.unlaxer.CodePointIndex;
import org.unlaxer.CodePointLength;
import org.unlaxer.Parsed;
import org.unlaxer.Source;
import org.unlaxer.StringSource;
import org.unlaxer.Token;
import org.unlaxer.TokenKind;
import org.unlaxer.context.Memoization;
import org.unlaxer.context.ParseContext;
import org.unlaxer.context.ParseOptions;
import org.unlaxer.context.SemanticPredicateBindings;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.Parsers;
import org.unlaxer.parser.combinator.CaptureBoundaryParser;
import org.unlaxer.parser.combinator.CaptureScope;
import org.unlaxer.parser.combinator.Chain;
import org.unlaxer.parser.combinator.Commit;
import org.unlaxer.parser.combinator.DoConsumePropagationStopper;
import org.unlaxer.parser.combinator.InvertMatchPropagationStopper;
import org.unlaxer.parser.combinator.Label;
import org.unlaxer.parser.combinator.LazyChain;
import org.unlaxer.parser.combinator.MatchOnly;
import org.unlaxer.parser.combinator.NameResolutionScope;
import org.unlaxer.parser.combinator.NamedCapture;
import org.unlaxer.parser.combinator.NonOrdered;
import org.unlaxer.parser.combinator.Optional;
import org.unlaxer.parser.combinator.SemanticPredicateParser;
import org.unlaxer.parser.combinator.SemanticPredicateScope;
import org.unlaxer.parser.combinator.ZeroOrMore;
import org.unlaxer.parser.elementary.EmptyParser;
import org.unlaxer.parser.elementary.ReplayParser;
import org.unlaxer.parser.elementary.WordParser;

/**
 * A lexical leaf that matches nothing (Lexing.LiteralParser(""), a LexicalTokenParser of z?) returns an empty token
 * of the consumed kind, which starts the collection of its parent at the cursor. Shared oracle with Rust (#1023 A/B),
 * conformance/collector-source/lexical-token.tsv and lexical-token-boundary.tsv, whose shapes are built as
 * unlaxer-common CollectorSourceTest builds them, every column under a root SemanticPredicateScope(["t"]).
 */
public class LexicalCollectorSourceTest {
    private static final Map<String, String> PREFIXES = Map.of("0", "", "1", "p", "3", "😀\r\n");

    private String observed;

    /** 'c' returned in the virtualTokenConsumed kind, whatever the caller's kind. */
    private static Parser virtualC() {
        return new EmptyParser() {
            private static final long serialVersionUID = 1L;
            @Override public Parsed parse(ParseContext context, TokenKind kind, boolean invert) {
                return new WordParser("c").parse(context, TokenKind.virtualTokenConsumed, false);
            }
        };
    }

    /** vci: reads 'c' at the consumed cursor, publishes only its own virtualTokenConsumed token, then consumes 1. */
    private static Parser virtualInputC() {
        return new EmptyParser() {
            private static final long serialVersionUID = 1L;
            @Override public Parsed parse(ParseContext context, TokenKind kind, boolean invert) {
                CodePointIndex at = context.getConsumedPosition();
                if (at.value() + 1 > context.source.codePointLength().value()) {
                    return Parsed.FAILED;
                }
                Source peeked = context.peek(at, new CodePointLength(1));
                if (false == peeked.sourceAsString().equals("c")) {
                    return Parsed.FAILED;
                }
                Token token = new Token(TokenKind.virtualTokenConsumed, peeked, this);
                context.getCurrent().addToken(token, TokenKind.virtualTokenConsumed);
                context.consume(new CodePointLength(1));
                return new Parsed(token);
            }
        };
    }

    /** hn: a host that returns no token. */
    private static Parser hostWithoutToken() {
        return new EmptyParser() {
            private static final long serialVersionUID = 1L;
            @Override public Parsed parse(ParseContext context, TokenKind kind, boolean invert) {
                return Parsed.SUCCEEDED;
            }
        };
    }

    /** hx: a host that returns an empty token of the consumed kind over the input at 0, away from the cursor. */
    private static Parser hostAtZero() {
        return new EmptyParser() {
            private static final long serialVersionUID = 1L;
            @Override public Parsed parse(ParseContext context, TokenKind kind, boolean invert) {
                Token empty = new Token(TokenKind.consumed,
                    context.peek(new CodePointIndex(0), new CodePointLength(0)), this);
                context.getCurrent().addToken(empty, TokenKind.consumed);
                return new Parsed(empty);
            }
        };
    }

    /** hc: a host that returns a token of the consumed kind over 'c' at the consumed cursor without consuming it. */
    private static Parser hostPeekC() {
        return new EmptyParser() {
            private static final long serialVersionUID = 1L;
            @Override public Parsed parse(ParseContext context, TokenKind kind, boolean invert) {
                CodePointIndex at = context.getConsumedPosition();
                if (at.value() + 1 > context.source.codePointLength().value()) {
                    return Parsed.FAILED;
                }
                Source peeked = context.peek(at, new CodePointLength(1));
                if (false == peeked.sourceAsString().equals("c")) {
                    return Parsed.FAILED;
                }
                Token token = new Token(TokenKind.consumed, peeked, this);
                context.getCurrent().addToken(token, TokenKind.consumed);
                return new Parsed(token);
            }
        };
    }

    /** hxc: a host that consumes 'c' at the consumed cursor and returns an empty consumed token over the input at 0. */
    private static Parser hostAtZeroConsuming() {
        return new EmptyParser() {
            private static final long serialVersionUID = 1L;
            @Override public Parsed parse(ParseContext context, TokenKind kind, boolean invert) {
                CodePointIndex at = context.getConsumedPosition();
                if (at.value() + 1 > context.source.codePointLength().value()) {
                    return Parsed.FAILED;
                }
                if (false == context.peek(at, new CodePointLength(1)).sourceAsString().equals("c")) {
                    return Parsed.FAILED;
                }
                Token empty = new Token(TokenKind.consumed,
                    context.peek(new CodePointIndex(0), new CodePointLength(0)), this);
                context.getCurrent().addToken(empty, TokenKind.consumed);
                context.consume(new CodePointLength(1));
                return new Parsed(empty);
            }
        };
    }

    private Parser observe() {
        return new EmptyParser() {
            private static final long serialVersionUID = 1L;
            @Override public Parsed parse(ParseContext context, TokenKind kind, boolean invert) {
                observed = context.captured("w")
                    .map(value -> value.provenance() + "@" + value.start() + "-" + value.end() + "'" + value.text() + "'")
                    .orElse("none")
                    + "\t" + context.getConsumedPosition().value() + "/" + context.getMatchedPosition().value();
                return super.parse(context, kind, invert);
            }
        };
    }

    /** Builds a shape of the table, as CollectorSourceTest and Rust build the same name. */
    static Parser shape(String name) {
        int open = name.indexOf('[');
        if (open < 0) {
            switch (name) {
                case "MO":
                    return new MatchOnly(new WordParser("c"));
                case "EL":
                    return new Lexing.LiteralParser("");
                case "LX":
                    return new LexicalTokenParser("LX", LexicalExpression.repeat(
                        LexicalExpression.leaf(LexicalExpression.Op.LITERAL, "z"), 0, 1));
                case "hn":
                    return hostWithoutToken();
                case "hx":
                    return hostAtZero();
                case "hc":
                    return hostPeekC();
                case "hxc":
                    return hostAtZeroConsuming();
                case "vc":
                    return virtualC();
                case "vci":
                    return virtualInputC();
                default:
                    return new WordParser(name);
            }
        }
        String head = name.substring(0, open);
        String body = name.substring(open + 1, name.length() - 1);
        if (head.equals("Rule")) {
            return rule(body);
        }
        Parsers children = children(body);
        switch (head) {
            case "Chain":
                return new Chain(children);
            case "Ord":
                return new NonOrdered(children);
            case "Opt":
                return new Optional(children.get(0));
            case "RepT":
                return new ZeroOrMore(children.get(0), children.get(1));
            case "Own":
                return new NamedCapture("o", children.get(0));
            case "Cmt":
                return new Commit(children.get(0));
            case "Lbl":
                return new Label("l", children.get(0));
            case "SI":
                return new InvertMatchPropagationStopper(children.get(0));
            case "SP":
                return new SemanticPredicateParser(children.get(0), "t");
            case "SPS":
                return new SemanticPredicateScope(children.get(0), List.of());
            case "CS":
                return new CaptureScope(children.get(0));
            case "NRS":
                return new NameResolutionScope(children.get(0), List.of());
            case "SC":
                return new DoConsumePropagationStopper(children.get(0));
            default:
                throw new IllegalArgumentException(name);
        }
    }

    /** The shapes of a comma-separated list at bracket depth 0. */
    private static Parsers children(String list) {
        List<Parser> children = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int index = 0; index < list.length(); index++) {
            char character = list.charAt(index);
            if (character == '[') {
                depth++;
            }
            if (character == ']') {
                depth--;
            }
            if (character == ',' && depth == 0) {
                children.add(shape(list.substring(start, index)));
                start = index + 1;
            }
        }
        children.add(shape(list.substring(start)));
        return new Parsers(children.toArray(Parser[]::new));
    }

    /** {@code Rule[X]}: a LazyChain of the elements of a Chain body X, or of X itself (the tables have no Choice body). */
    private static Parser rule(String body) {
        Parsers parsers = body.startsWith("Chain[")
            ? children(body.substring("Chain[".length(), body.length() - 1))
            : new Parsers(shape(body));
        return new LazyChain() {
            private static final long serialVersionUID = 1L;
            @Override public Parsers getLazyParsers() {
                return parsers;
            }
        };
    }

    private static Parser after(String prefix, Parser... rest) {
        List<Parser> all = new ArrayList<>();
        if (false == prefix.isEmpty()) {
            all.add(new WordParser(prefix));
        }
        all.addAll(List.of(rest));
        return new Chain(new Parsers(all.toArray(Parser[]::new)));
    }

    private static ParseContext context(String input, Memoization memo, ParseOptions.Diagnostics diagnostics) {
        ParseContext context = ParseContext.withOptions(StringSource.createRootSource(input),
            ParseOptions.withMemoization(memo).withDiagnostics(diagnostics));
        SemanticPredicateBindings.install(context, Map.of("t", candidate -> true));
        return context;
    }

    private static Parser scoped(Parser parser) {
        return new SemanticPredicateScope(parser, List.of("t"));
    }

    private static List<String[]> rows(String table, int expectedRows) throws java.io.IOException {
        List<String[]> rows = new ArrayList<>();
        for (String row : Files.readAllLines(Path.of("../conformance/collector-source/" + table))) {
            if (false == row.startsWith("#")) {
                rows.add(row.split("\t"));
            }
        }
        assertEquals(expectedRows, rows.size());
        return rows;
    }

    private static String origin(Parsed parsed, Parser boundary) {
        String origin = "fail";
        if (parsed.isSucceeded()) {
            for (Token token : parsed.getRootToken().flatten(Token.ScanDirection.Depth, Token.ChildrenKind.original)) {
                if (token.parser == boundary) {
                    origin = CaptureBoundaryParser.originOf(token)
                        .map(value -> (value.generated() ? "G" : "") + value.start() + "-" + value.end())
                        .orElse("none");
                }
            }
        }
        return origin;
    }

    @Test public void emptyLexicalTokensStartTheCollection() throws java.io.IOException {
        checkTable("lexical-token.tsv", 189);
    }

    @Test public void emptyLexicalTokensStartTheCaptureLessOrigins() throws java.io.IOException {
        checkBoundaryTable("lexical-token-boundary.tsv", 189);
    }

    /**
     * A host returns its Token, whose source is the input it peeked, also next to a lexical leaf: hx at 0, hc over 'c'
     * it did not consume, hxc at 0 after consuming 'c' (conformance/collector-source/host-token-lexical.tsv, #1023).
     */
    @Test public void hostsReturnTheSourceOfTheirTokenNextToLexicalLeaves() throws java.io.IOException {
        checkTable("host-token-lexical.tsv", 72);
    }

    /** The capture-less origins of the shapes of host-token-lexical.tsv (#1023). */
    @Test public void hostsReturnTheCaptureLessOriginsNextToLexicalLeaves() throws java.io.IOException {
        checkBoundaryTable("host-token-lexical-boundary.tsv", 72);
    }

    private void checkTable(String table, int expectedRows) throws java.io.IOException {
        for (String[] row : rows(table, expectedRows)) {
            for (Memoization memo : Memoization.values()) {
                for (ParseOptions.Diagnostics diagnostics : ParseOptions.Diagnostics.values()) {
                    String prefix = PREFIXES.get(row[0]);
                    String input = prefix + "c";
                    int length = input.codePointCount(0, input.length());
                    String label = String.join(" ", row) + " " + memo + "/" + diagnostics;
                    try (ParseContext context = context(input, memo, diagnostics)) {
                        observed = "unreached\t-";
                        Parsed parsed = scoped(after(prefix, new NamedCapture("w", shape(row[1])), observe(),
                            new ReplayParser("w"))).parse(context);
                        assertEquals(label, row[2] + "\t" + row[3], observed);
                        assertEquals(label, Boolean.parseBoolean(row[4]),
                            parsed.isSucceeded() && context.getConsumedPosition().value() == length);
                    }
                    for (boolean wantsC : List.of(true, false)) {
                        try (ParseContext context = ParseContext.withOptions(StringSource.createRootSource(input),
                                ParseOptions.withMemoization(memo).withDiagnostics(diagnostics))) {
                            Set<String> seen = new LinkedHashSet<>();
                            SemanticPredicateBindings.install(context, Map.of("t", candidate -> true, "valid",
                                candidate -> {
                                    seen.add((candidate.text().isEmpty() ? "~" : candidate.text()) + "@"
                                        + candidate.start());
                                    return candidate.text().equals("c") == wantsC;
                                }));
                            Parsed parsed = new SemanticPredicateScope(after(prefix,
                                new SemanticPredicateParser(shape(row[1]), "valid"), new WordParser("c")),
                                List.of("valid", "t")).parse(context);
                            String failure = SemanticPredicateBindings.failure(context)
                                .map(value -> value.kind() + "@" + value.start())
                                .orElse("-");
                            assertEquals(label, row[5], seen.isEmpty() ? "-" : String.join(",", seen));
                            assertEquals(label, row[6], failure);
                            assertEquals(label + " wantsC=" + wantsC, Boolean.parseBoolean(wantsC ? row[7] : row[8]),
                                parsed.isSucceeded() && context.getConsumedPosition().value() == length);
                        }
                    }
                    try (ParseContext context = context(input, memo, diagnostics)) {
                        CaptureBoundaryParser boundary = CaptureBoundaryParser.input(CaptureBoundaryParser.Kind.VALUE,
                            shape(row[1]));
                        assertEquals(label, row[9], origin(scoped(after(prefix, boundary)).parse(context), boundary));
                    }
                }
            }
        }
    }

    private void checkBoundaryTable(String table, int expectedRows) throws java.io.IOException {
        for (String[] row : rows(table, expectedRows)) {
            for (Memoization memo : Memoization.values()) {
                for (ParseOptions.Diagnostics diagnostics : ParseOptions.Diagnostics.values()) {
                    String prefix = PREFIXES.get(row[0]);
                    String label = String.join(" ", row) + " " + memo + "/" + diagnostics;
                    for (boolean input : List.of(false, true)) {
                        try (ParseContext context = context(prefix + "c", memo, diagnostics)) {
                            CaptureBoundaryParser boundary = input
                                ? CaptureBoundaryParser.input(CaptureBoundaryParser.Kind.VALUE, shape(row[1]))
                                : CaptureBoundaryParser.value(shape(row[1]));
                            Parsed parsed = scoped(after(prefix, boundary, new Optional(new WordParser("c"))))
                                .parse(context);
                            assertEquals(label + " input=" + input, input ? row[3] : row[2], origin(parsed, boundary));
                        }
                    }
                    try (ParseContext context = context(prefix + "c", memo, diagnostics)) {
                        CaptureBoundaryParser boundary = CaptureBoundaryParser.value(
                            after(prefix, shape(row[1]), new Optional(new WordParser("c"))));
                        assertEquals(label + " whole", row[4], origin(scoped(boundary).parse(context), boundary));
                    }
                }
            }
        }
    }
}
