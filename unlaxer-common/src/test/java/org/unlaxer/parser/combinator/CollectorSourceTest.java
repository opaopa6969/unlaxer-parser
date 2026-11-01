package org.unlaxer.parser.combinator;

import static org.junit.Assert.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.Test;
import org.unlaxer.CodePointIndex;
import org.unlaxer.CodePointLength;
import org.unlaxer.CodePointOffset;
import org.unlaxer.Parsed;
import org.unlaxer.Source;
import org.unlaxer.StringSource;
import org.unlaxer.Token;
import org.unlaxer.TokenKind;
import org.unlaxer.context.Memoization;
import org.unlaxer.context.ParseContext;
import org.unlaxer.context.ParseOptions;
import org.unlaxer.context.SemanticPredicateBindings;
import org.unlaxer.context.SemanticPredicateBindings.Candidate;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.Parsers;
import org.unlaxer.parser.elementary.EmptyParser;
import org.unlaxer.parser.elementary.ReplayParser;
import org.unlaxer.parser.elementary.WordParser;

/**
 * A collector returns what it collected in the consumed kind, whatever its children returned: a MatchOnly
 * child contributes nothing and a host projection is collected in order. NamedCapture, a predicate and an
 * input capture boundary read that source. Shared oracle with Rust (#1023 A/B),
 * conformance/collector-source/full.tsv, which Rust reads at its full-input and Classic entries.
 */
public class CollectorSourceTest {
    private static final Map<String, String> PREFIXES = Map.of("0", "", "1", "p", "3", "😀\r\n");

    private String observed;

    /** A host projection whose logical start is 0, away from the cursor; it consumes nothing. */
    private static Parser projected(String text) {
        return new EmptyParser() {
            private static final long serialVersionUID = 1L;
            @Override public Parsed parse(ParseContext context, TokenKind kind, boolean invert) {
                Source source = StringSource.createSubSource(text, context.source, new CodePointOffset(0));
                Token token = new Token(kind, source, this);
                context.getCurrent().addToken(token, kind);
                return new Parsed(token);
            }
        };
    }

    /** A host projection "Z" of a fixed kind that consumes {@code consume} code points. */
    private static Parser projected(TokenKind fixed, int consume) {
        return new EmptyParser() {
            private static final long serialVersionUID = 1L;
            @Override public Parsed parse(ParseContext context, TokenKind kind, boolean invert) {
                if (context.getConsumedPosition().value() + consume > context.source.codePointLength().value()) {
                    return Parsed.FAILED;
                }
                Source source = StringSource.createSubSource("Z", context.source, new CodePointOffset(0));
                TokenKind actual = fixed == null ? kind : fixed;
                Token token = new Token(actual, source, this);
                context.getCurrent().addToken(token, actual);
                if (consume > 0) {
                    context.consume(new CodePointLength(consume));
                }
                return new Parsed(token);
            }
        };
    }

    /** 'c' returned in the virtualTokenConsumed kind, whatever the caller's kind. */
    private static Parser virtualC() {
        return new EmptyParser() {
            private static final long serialVersionUID = 1L;
            @Override public Parsed parse(ParseContext context, TokenKind kind, boolean invert) {
                return new WordParser("c").parse(context, TokenKind.virtualTokenConsumed, false);
            }
        };
    }

    /**
     * vci / mi / vmi: reads 'c' at the consumed cursor, publishes only its own token of that input in a fixed kind
     * (virtualTokenConsumed / matchOnly / virtualTokenMatchOnly), then consumes 1.
     */
    private static Parser inputC(TokenKind fixed) {
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
                Token token = new Token(fixed, peeked, this);
                context.getCurrent().addToken(token, fixed);
                context.consume(new CodePointLength(1));
                return new Parsed(token);
            }
        };
    }

    /**
     * h0 / hx: a host that returns an empty token of the consumed kind over the input at the consumed cursor / at 0;
     * hn: a host that returns no token.
     */
    private static Parser hostEmpty(boolean token, boolean atCursor) {
        return new EmptyParser() {
            private static final long serialVersionUID = 1L;
            @Override public Parsed parse(ParseContext context, TokenKind kind, boolean invert) {
                if (false == token) {
                    return Parsed.SUCCEEDED;
                }
                CodePointIndex at = atCursor ? context.getConsumedPosition() : new CodePointIndex(0);
                Token empty = new Token(TokenKind.consumed, context.peek(at, new CodePointLength(0)), this);
                context.getCurrent().addToken(empty, TokenKind.consumed);
                return new Parsed(empty);
            }
        };
    }

    /** hv: a host that returns an empty token of the virtualTokenConsumed kind over the input at the consumed cursor. */
    private static Parser hostEmptyVirtual() {
        return new EmptyParser() {
            private static final long serialVersionUID = 1L;
            @Override public Parsed parse(ParseContext context, TokenKind kind, boolean invert) {
                Token empty = new Token(TokenKind.virtualTokenConsumed,
                    context.peek(context.getConsumedPosition(), new CodePointLength(0)), this);
                context.getCurrent().addToken(empty, TokenKind.virtualTokenConsumed);
                return new Parsed(empty);
            }
        };
    }

    /**
     * h1 / hc: a host that returns a token of the consumed kind over 'c' at the consumed cursor and consumes it / does not
     * consume it.
     */
    private static Parser hostC(boolean consume) {
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
                if (consume) {
                    context.consume(new CodePointLength(1));
                }
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
                Token empty = new Token(TokenKind.consumed, context.peek(new CodePointIndex(0), new CodePointLength(0)), this);
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

    /** Builds a shape of the table, as Rust builds the same name. */
    static Parser shape(String name) {
        int open = name.indexOf('[');
        if (open < 0) {
            switch (name) {
                case "MO":
                    return new MatchOnly(new WordParser("c"));
                case "&c":
                    return new PegLookahead(new WordParser("c"), true);
                case "!z":
                    return new PegLookahead(new WordParser("z"), false);
                case "E":
                    return new EmptyParser();
                case "proj":
                    return projected("Z");
                case "proj0":
                    return projected("");
                case "projc":
                    return projected(null, 1);
                case "projm":
                    return projected(TokenKind.matchOnly, 0);
                case "vc":
                    return virtualC();
                case "vci":
                    return inputC(TokenKind.virtualTokenConsumed);
                case "mi":
                    return inputC(TokenKind.matchOnly);
                case "vmi":
                    return inputC(TokenKind.virtualTokenMatchOnly);
                case "h0":
                    return hostEmpty(true, true);
                case "hx":
                    return hostEmpty(true, false);
                case "hn":
                    return hostEmpty(false, true);
                case "hv":
                    return hostEmptyVirtual();
                case "hc":
                    return hostC(false);
                case "h1":
                    return hostC(true);
                case "hxc":
                    return hostAtZeroConsuming();
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
            case "Choice":
                return new Choice(children);
            case "Chain":
                return new Chain(children);
            case "Opt":
                return new Optional(children.get(0));
            case "Rep":
                return new ZeroOrMore(children.get(0));
            case "Rec":
                return new SyncPointRecoveryParser(children.get(0), ";");
            case "Ord":
                return new NonOrdered(children);
            case "LC":
                return new LongestChoice(children);
            case "Own":
                return new NamedCapture("o", children.get(0));
            case "Val":
            case "VB":
                return CaptureBoundaryParser.value(children.get(0));
            case "IB":
                return CaptureBoundaryParser.input(CaptureBoundaryParser.Kind.VALUE, children.get(0));
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
            case "RepT":
                return new ZeroOrMore(children.get(0), children.get(1));
            case "Rep1T":
                return new OneOrMore(children.get(0)).newWithTerminator(children.get(1));
            default:
                if (head.length() == 5 && head.startsWith("Occ")) {
                    int min = head.charAt(3) - '0';
                    int max = head.charAt(4) - '0';
                    return new Repeat(children.get(0), min, max).newWithTerminator(children.get(1));
                }
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

    /**
     * {@code Rule[X]}: the class UBNF ParserRuleEmitter generates for a rule with body X, referenced where X stood.
     * A Choice body is a LazyChoice of its alternatives, a Chain body a LazyChain of its elements, any other body
     * a single-element LazyChain (so a rule whose body is only a rule reference, {@code Rule[Rule[X]]}, is too).
     */
    private static Parser rule(String body) {
        int open = body.indexOf('[');
        String head = open < 0 ? body : body.substring(0, open);
        Parsers parsers = head.equals("Choice") || head.equals("Chain")
            ? children(body.substring(open + 1, body.length() - 1))
            : new Parsers(shape(body));
        if (head.equals("Choice")) {
            return new LazyChoice() {
                private static final long serialVersionUID = 1L;
                @Override public Parsers getLazyParsers() {
                    return parsers;
                }
            };
        }
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
        return ParseContext.withOptions(StringSource.createRootSource(input),
            ParseOptions.withMemoization(memo).withDiagnostics(diagnostics));
    }

    @Test public void collectorsReturnTheConsumedCollection() throws java.io.IOException {
        checkTable("full.tsv", 312, false);
    }

    /** A host token of another kind over the input is excluded by its kind (#1023). */
    @Test public void inputBackedTokensOfAnotherKindAreExcluded() throws java.io.IOException {
        checkTable("virtual-input.tsv", 129, false);
    }

    /** Occurs with a terminator collects its bodies and its committed terminator in the consumed kind. */
    @Test public void terminatedRepetitionReturnsTheConsumedCollection() throws java.io.IOException {
        checkTable("terminated.tsv", 177, false);
    }

    /**
     * A collector returns a token of the consumed kind even when it is empty, which starts the collection of its
     * parent there; a PEG assertion returns none, NamedCapture / Commit / Label / InvertMatchPropagationStopper what
     * their child returned (#1023).
     */
    @Test public void emptyTokensStartTheCollection() throws java.io.IOException {
        checkTable("empty-token.tsv", 906, false);
    }

    /** The capture-less origins of the same shapes: value / input boundaries and the whole expression (#1023). */
    @Test public void emptyTokensStartTheCaptureLessOrigins() throws java.io.IOException {
        checkBoundaryTable("empty-token-boundary.tsv", 618, false);
    }

    /**
     * SemanticPredicateParser, SemanticPredicateScope, NameResolutionScope and CaptureScope return what their child
     * returned, as NamedCapture does, and a host what it returned: a later collection collects its consumed tokens over
     * the input at their own positions, no token leaves "" at the consumed cursor (#1023).
     */
    @Test public void wrappersAndHostsReturnWhatTheyReturned() throws java.io.IOException {
        checkTable("returned-token.tsv", 915, true);
    }

    /** The capture-less origins of the shapes of returned-token.tsv (#1023). */
    @Test public void wrappersAndHostsReturnTheCaptureLessOrigins() throws java.io.IOException {
        checkBoundaryTable("returned-token-boundary.tsv", 915, true);
    }

    /**
     * A host returns its Token, whose source is the input it peeked: hx at 0, hc over 'c' it did not consume, hxc at 0
     * after consuming 'c'. NamedCapture, a predicate and CaptureBoundaryParser.input read it, and a collector collects
     * it in order (#1023).
     */
    @Test public void hostsReturnTheSourceOfTheirToken() throws java.io.IOException {
        checkTable("host-token.tsv", 1065, true);
    }

    /** The capture-less origins of the shapes of host-token.tsv (#1023). */
    @Test public void hostsReturnTheCaptureLessOriginsOfTheirToken() throws java.io.IOException {
        checkBoundaryTable("host-token-boundary.tsv", 1062, true);
    }

    /** Under a root SemanticPredicateScope(["t"]) when {@code scoped}, whose predicate t always holds. */
    private static Parser root(Parser parser, boolean scoped) {
        return scoped ? new SemanticPredicateScope(parser, List.of("t")) : parser;
    }

    private static void bind(ParseContext context, boolean scoped, Map<String, Predicate<Candidate>> predicates) {
        Map<String, Predicate<Candidate>> all = new HashMap<>(predicates);
        if (scoped) {
            all.put("t", candidate -> true);
        }
        if (false == all.isEmpty()) {
            SemanticPredicateBindings.install(context, all);
        }
    }

    private void checkBoundaryTable(String table, int expectedRows, boolean scoped) throws java.io.IOException {
        List<String[]> rows = new ArrayList<>();
        for (String row : Files.readAllLines(Path.of("../conformance/collector-source/" + table))) {
            if (false == row.startsWith("#")) {
                rows.add(row.split("\t"));
            }
        }
        assertEquals(expectedRows, rows.size());
        for (String[] row : rows) {
            for (Memoization memo : Memoization.values()) {
                for (ParseOptions.Diagnostics diagnostics : ParseOptions.Diagnostics.values()) {
                    String prefix = PREFIXES.get(row[0]);
                    String label = String.join(" ", row) + " " + memo + "/" + diagnostics;
                    for (boolean input : List.of(false, true)) {
                        try (ParseContext context = context(prefix + "c", memo, diagnostics)) {
                            bind(context, scoped, Map.of());
                            CaptureBoundaryParser boundary = input
                                ? CaptureBoundaryParser.input(CaptureBoundaryParser.Kind.VALUE, shape(row[1]))
                                : CaptureBoundaryParser.value(shape(row[1]));
                            Parsed parsed = root(after(prefix, boundary, new Optional(new WordParser("c"))), scoped)
                                .parse(context);
                            String origin = "fail";
                            if (parsed.isSucceeded()) {
                                for (Token token : parsed.getRootToken().flatten(Token.ScanDirection.Depth,
                                        Token.ChildrenKind.original)) {
                                    if (token.parser == boundary) {
                                        origin = originText(token);
                                    }
                                }
                            }
                            assertEquals(label + " input=" + input, input ? row[3] : row[2], origin);
                        }
                    }
                    try (ParseContext context = context(prefix + "c", memo, diagnostics)) {
                        bind(context, scoped, Map.of());
                        CaptureBoundaryParser boundary = CaptureBoundaryParser.value(
                            after(prefix, shape(row[1]), new Optional(new WordParser("c"))));
                        Parsed parsed = root(boundary, scoped).parse(context);
                        String whole = "fail";
                        if (parsed.isSucceeded() && false == scoped) {
                            whole = originText(parsed.getRootToken());
                        } else if (parsed.isSucceeded()) {
                            for (Token token : parsed.getRootToken().flatten(Token.ScanDirection.Depth,
                                    Token.ChildrenKind.original)) {
                                if (token.parser == boundary) {
                                    whole = originText(token);
                                }
                            }
                        }
                        assertEquals(label + " whole", row[4], whole);
                    }
                }
            }
        }
    }

    private static String originText(Token token) {
        return CaptureBoundaryParser.originOf(token)
            .map(origin -> (origin.generated() ? "G" : "") + origin.start() + "-" + origin.end())
            .orElse("none");
    }

    private void checkTable(String table, int expectedRows, boolean scoped) throws java.io.IOException {
        List<String[]> rows = new ArrayList<>();
        for (String row : Files.readAllLines(Path.of("../conformance/collector-source/" + table))) {
            if (false == row.startsWith("#")) {
                rows.add(row.split("\t"));
            }
        }
        assertEquals(expectedRows, rows.size());
        for (String[] row : rows) {
            for (Memoization memo : Memoization.values()) {
                for (ParseOptions.Diagnostics diagnostics : ParseOptions.Diagnostics.values()) {
                    String prefix = PREFIXES.get(row[0]);
                    String input = prefix + "c";
                    int length = input.codePointCount(0, input.length());
                    String label = String.join(" ", row) + " " + memo + "/" + diagnostics;
                    try (ParseContext context = context(input, memo, diagnostics)) {
                        bind(context, scoped, Map.of());
                        observed = "unreached\t-";
                        Parsed parsed = root(after(prefix, new NamedCapture("w", shape(row[1])), observe(),
                            new ReplayParser("w")), scoped).parse(context);
                        assertEquals(label, row[2] + "\t" + row[3], observed);
                        assertEquals(label, Boolean.parseBoolean(row[4]),
                            parsed.isSucceeded() && context.getConsumedPosition().value() == length);
                    }
                    for (boolean wantsC : List.of(true, false)) {
                        try (ParseContext context = context(input, memo, diagnostics)) {
                            Set<String> seen = new LinkedHashSet<>();
                            bind(context, scoped, Map.of("valid", candidate -> {
                                seen.add((candidate.text().isEmpty() ? "~" : candidate.text()) + "@" + candidate.start());
                                return candidate.text().equals("c") == wantsC;
                            }));
                            Parsed parsed = new SemanticPredicateScope(after(prefix,
                                new SemanticPredicateParser(shape(row[1]), "valid"), new WordParser("c")),
                                scoped ? List.of("valid", "t") : List.of("valid")).parse(context);
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
                        bind(context, scoped, Map.of());
                        CaptureBoundaryParser boundary = CaptureBoundaryParser.input(CaptureBoundaryParser.Kind.VALUE,
                            shape(row[1]));
                        Parsed parsed = root(after(prefix, boundary), scoped).parse(context);
                        String field = "fail";
                        if (parsed.isSucceeded()) {
                            for (Token token : parsed.getRootToken().flatten(Token.ScanDirection.Depth,
                                    Token.ChildrenKind.original)) {
                                if (token.parser == boundary) {
                                    field = CaptureBoundaryParser.originOf(token)
                                        .map(origin -> (origin.generated() ? "G" : "") + origin.start() + "-" + origin.end())
                                        .orElse("none");
                                }
                            }
                        }
                        assertEquals(label, row[9], field);
                    }
                }
            }
        }
    }
}
