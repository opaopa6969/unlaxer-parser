package org.unlaxer.dsl.runtime;

import java.util.*;
import java.util.function.Supplier;
import org.unlaxer.*;
import org.unlaxer.context.ParseContext;
import org.unlaxer.context.ParseOptions;
import org.unlaxer.parser.Parser;

/** Optional, parse-local lexical input. Public spans are Unicode code points. */
public final class Lexing {
    private Lexing() {}
    private static final Name KEY = Name.of(Lexing.class);
    public enum Mode { DIRECT, TRIVIA_CACHE, TOKENS_LAZY, TOKENS_EAGER }
    public record Options(Mode mode, boolean preserveTrivia) {
        public Options { Objects.requireNonNull(mode); }
        public static final Options DEFAULT = new Options(Mode.DIRECT, true);
    }
    /** Earlier entries win ties; the longest complete match always wins first. */
    public record Terminal(String name, boolean literal, LexicalExpression expression) {
        public Terminal { Objects.requireNonNull(name); Objects.requireNonNull(expression); }
    }
    public record Lexeme(String kind, String name, int start, int end) {}
    public record Metrics(long terminalEvaluations, long triviaEvaluations, long inventoryEvaluations,
            int retainedEntries, int sourceCodePoints) {}
    public record Outcome(Token root, boolean succeeded, int consumed, int matched, int farthest,
            List<String> expected, Session session) {
        public Outcome { expected = List.copyOf(expected); }
    }
    public static final class Session {
        private final String source;
        private final Options options;
        private final List<Terminal> terminals;
        private final boolean whitespace;
        private final int[] offsets;
        private final int[] codePoints;
        private final Map<Integer, Entry> entries = new LinkedHashMap<>();
        private final Map<Integer, Integer> triviaCache = new HashMap<>();
        private int frontier;
        private long terminalEvaluations, triviaEvaluations, inventoryEvaluations;
        private record Entry(String kind, String name, int terminal, int start, int end) {}

        public Session(String source, Options options, List<Terminal> terminals, boolean whitespace) {
            this.source = Objects.requireNonNull(source);
            this.options = Objects.requireNonNull(options);
            this.terminals = List.copyOf(terminals);
            this.whitespace = whitespace;
            if (source.codePoints().anyMatch(c -> c >= 0xd800 && c <= 0xdfff))
                throw new IllegalArgumentException("E-LEXING-SOURCE: unpaired surrogate");
            offsets = new int[source.codePointCount(0, source.length()) + 1];
            codePoints = new int[source.length() + 1];
            Arrays.fill(codePoints, -1);
            int cp = 0;
            for (int p = 0; p < source.length(); p += Character.charCount(source.codePointAt(p))) {
                offsets[cp] = p; codePoints[p] = cp++;
            }
            offsets[cp] = source.length(); codePoints[source.length()] = cp;
            var names = new HashSet<String>();
            for (Terminal terminal : terminals) {
                if (!names.add(key(terminal.name(), terminal.literal())) || terminal.expression().nullable())
                    throw new IllegalArgumentException("E-LEXING-TOKEN: duplicate or nullable terminal " + terminal.name());
            }
            if (options.mode() == Mode.TOKENS_EAGER) scanThrough(source.length(), false);
        }
        public String source() { return source; }
        public Options options() { return options; }
        public Metrics metrics() {
            return new Metrics(terminalEvaluations, triviaEvaluations, inventoryEvaluations,
                entries.size() + triviaCache.size(), offsets.length - 1);
        }
        private static String key(String name, boolean literal) { return (literal ? "L:" : "T:") + name; }
        private boolean tokenMode() { return options.mode() == Mode.TOKENS_EAGER || options.mode() == Mode.TOKENS_LAZY; }

        int match(String name, boolean literal, LexicalExpression expression, int position) {
            if (expression.op() == LexicalExpression.Op.EOF) return position == source.length() ? position : -1;
            if (!tokenMode()) { terminalEvaluations++; return expression.match(source, position); }
            if (literal && name.isEmpty()) return position;
            scanThrough(position, false);
            Entry entry = entries.get(position);
            if (entry == null || entry.terminal() < 0) return -1;
            Terminal terminal = terminals.get(entry.terminal());
            return terminal.literal() == literal && terminal.name().equals(name) ? entry.end() : -1;
        }
        int skip(int position) {
            if (!whitespace) return position;
            if (tokenMode()) {
                int end = position;
                while (end < source.length()) {
                    scanThrough(end, false);
                    Entry entry = entries.get(end);
                    if (entry == null || !isTrivia(entry.kind())) break;
                    end = entry.end();
                }
                return end;
            }
            if (options.mode() == Mode.TRIVIA_CACHE && triviaCache.containsKey(position)) return triviaCache.get(position);
            int end = position;
            while (end < source.length()) {
                triviaEvaluations++;
                Entry entry = trivia(end);
                if (entry == null) break;
                end = entry.end();
            }
            if (options.mode() == Mode.TRIVIA_CACHE) triviaCache.put(position, end);
            return end;
        }
        private void scanThrough(int position, boolean inventory) {
            while (frontier <= position && frontier < source.length()) {
                Entry entry = null;
                if (whitespace) {
                    if (inventory) inventoryEvaluations++; else triviaEvaluations++;
                    entry = trivia(frontier);
                }
                if (entry == null) {
                    int best = -1, end = frontier;
                    for (int i = 0; i < terminals.size(); i++) {
                        if (inventory) inventoryEvaluations++; else terminalEvaluations++;
                        int next = terminals.get(i).expression().match(source, frontier);
                        if (next > end) { best = i; end = next; }
                    }
                    entry = best < 0
                        ? new Entry("error", "", -1, frontier, frontier + Character.charCount(source.codePointAt(frontier)))
                        : new Entry("token", terminals.get(best).name(), best, frontier, end);
                }
                entries.put(frontier, entry); frontier = entry.end();
            }
        }
        private Entry trivia(int p) {
            int end = p;
            while (end < source.length() && " \t\r\n\u000b\f".indexOf(source.charAt(end)) >= 0) end++;
            if (end > p) return new Entry("space", "", -1, p, end);
            if (source.startsWith("//", p)) {
                end = p + 2;
                while (end < source.length() && source.charAt(end) != '\r' && source.charAt(end) != '\n') end++;
                return new Entry("lineComment", "", -1, p, end);
            }
            if (source.startsWith("/*", p)) {
                end = source.indexOf("*/", p + 2);
                if (end >= 0) return new Entry("blockComment", "", -1, p, end + 2);
            }
            return null;
        }
        private static boolean isTrivia(String kind) { return !kind.equals("token") && !kind.equals("error"); }
        /** Materializes the remaining inventory, also on failure. Its extra work is counted separately. */
        public List<Lexeme> lexemes() {
            scanThrough(source.length(), true);
            return entries.values().stream().filter(e -> options.preserveTrivia() || !isTrivia(e.kind()))
                .map(e -> new Lexeme(e.kind(), e.name(), codePoints[e.start()], codePoints[e.end()])).toList();
        }
        public String text(Lexeme lexeme) { return source.substring(offsets[lexeme.start()], offsets[lexeme.end()]); }
    }
    static Session attached(ParseContext context) { return (Session) context.getGlobalScopeTreeMap().get(KEY); }
    static int charOffset(ParseContext context, String source, int cp) {
        Session session = attached(context);
        return session == null ? source.offsetByCodePoints(0, cp) : session.offsets[cp];
    }
    static int match(ParseContext context, String name, boolean literal, LexicalExpression expression, String source, int start) {
        Session session = attached(context);
        return session == null ? expression.match(source, start) : session.match(name, literal, expression, start);
    }
    public static final class LiteralParser extends LexicalTokenParser {
        private static final long serialVersionUID = 1L;
        public LiteralParser(String literal) { super(literal, LexicalExpression.leaf(LexicalExpression.Op.LITERAL, literal), true); }
    }
    public static final class EofParser extends LexicalTokenParser {
        private static final long serialVersionUID = 1L;
        public EofParser() { super("end of input", LexicalExpression.leaf(LexicalExpression.Op.EOF, "")); }
    }
    public static Parsed trivia(Parser parser, ParseContext context, TokenKind kind, boolean invert, Supplier<Parsed> fallback) {
        Session session = attached(context);
        if (session == null) return fallback.get();
        context.startParse(parser, context, kind, invert);
        if (invert) { context.endParse(parser, Parsed.FAILED, context, kind, true); return Parsed.FAILED; }
        int cp = context.getPosition(kind).value();
        int end = session.skip(session.offsets[cp]);
        var length = new CodePointLength(session.codePoints[end] - cp);
        var token = new Token(kind, context.peek(context.getPosition(kind), length), parser);
        context.getCurrent().addToken(token, kind);
        if (length.value() > 0) { if (kind.isConsumed()) context.consume(length); else context.matchOnly(length); }
        var parsed = new Parsed(token);
        context.endParse(parser, parsed, context, kind, false);
        return parsed;
    }
    public static Outcome parse(Parser root, String source, Options options, List<Terminal> terminals, boolean whitespace) {
        Session session = new Session(source, options, terminals, whitespace);
        try (ParseContext context = ParseContext.withOptions(StringSource.createRootSource(source),
                ParseOptions.DEFAULT.withDiagnostics(ParseOptions.Diagnostics.DETAILED))) {
            context.getGlobalScopeTreeMap().put(KEY, session);
            Parsed parsed = root.parse(context);
            Token token = parsed.isSucceeded() ? parsed.getRootToken(false) : null;
            if (token != null) for (Token committed : context.getCurrent().getTokens()) {
                if (committed.parser == root) { token = committed; break; }
            }
            int consumed = context.getPosition(TokenKind.consumed).value();
            int matched = context.getPosition(TokenKind.matchOnly).value();
            var diagnostic = context.getParseFailureDiagnostics();
            boolean complete = parsed.isSucceeded() && consumed == session.offsets.length - 1;
            int farthest = parsed.isSucceeded() && !complete ? consumed : diagnostic.getFarthestOffset();
            List<String> expected = parsed.isSucceeded() && !complete ? List.of("end of input")
                : diagnostic.getExpectedTokens().stream().sorted().toList();
            return new Outcome(token, complete, consumed, matched, farthest, expected, session);
        }
    }
}
