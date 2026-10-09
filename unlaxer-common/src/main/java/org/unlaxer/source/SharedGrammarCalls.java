package org.unlaxer.source;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.unlaxer.CodePointIndex;
import org.unlaxer.CodePointLength;
import org.unlaxer.Name;
import org.unlaxer.Token;
import org.unlaxer.TokenKind;
import org.unlaxer.context.ParseContext;
import org.unlaxer.context.ParseOptions;
import org.unlaxer.context.TransactionalState;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.TerminalSymbol;
import org.unlaxer.parser.combinator.RecoveryDiagnostic;
import org.unlaxer.parser.elementary.AbstractTokenParser;
import org.unlaxer.source.DocumentSnapshot.Span;
import org.unlaxer.source.LanguageRegions.Language;

/** Explicit same-context prefix calls; child CST is owned separately from parent rule tokens. */
public final class SharedGrammarCalls {
    private SharedGrammarCalls() {}
    private static final Name METADATA = Name.of("unlaxer.shared-call.metadata");
    private static final Name DEPTH = Name.of("unlaxer.shared-call.depth");
    private static final Name BUDGET = Name.of("unlaxer.shared-call.budget");
    private static final long MAX_RETAINED_CP = 4_194_304;
    private static final class Budget implements TransactionalState {
        long retained;
        public Runnable checkpoint() { long old = retained; return () -> retained = old; }
    }
    private static Budget budget(ParseContext context) {
        Budget budget = (Budget) context.getGlobalScopeTreeMap().computeIfAbsent(BUDGET, ignored -> new Budget());
        context.registerTransactionalState(budget);
        return budget;
    }
    public enum FailureKind { UNAVAILABLE, UNSUPPORTED, SYNTAX, RECOVERY, LIMIT }
    public record Failure(FailureKind kind, int offset, List<String> expected) {
        public Failure { expected = List.copyOf(expected); }
    }
    public static final class Call {
        private final Language language;
        private final Span span;
        private final Token tree;
        private final long retained;
        private Call(Language language, Span span, Token tree, long retained) { this.language = language; this.span = span; this.tree = tree; this.retained = retained; }
        public Language language() { return language; }
        public Span span() { return span; }
        public Token tree() { return tree.deepCopy(); }
    }
    public record Result(Call call, Failure failure) {
        public boolean succeeded() { return call != null; }
    }
    public static Optional<Call> metadata(Token token) { return token.getExtraObject(METADATA); }
    public static final class Registry {
        private final Map<Language, CstGrammar> entries;
        public Registry(Map<Language, CstGrammar> entries) {
            this.entries = Map.copyOf(entries);
            if (entries.size() > 64) throw new IllegalArgumentException("shared call registry exceeds 64 entries");
            entries.forEach((language, grammar) -> {
                for (String value : List.of(language.id(), language.packageId(), language.version(), language.grammar(), language.entry())) {
                    if (value.isEmpty() || value.codePointCount(0, value.length()) > 256) throw new IllegalArgumentException("invalid shared call identity");
                }
                if (!language.grammar().equals(grammar.name()) || grammar.sharedEntry(language.entry()) == null) throw new IllegalArgumentException("shared call grammar/entry mismatch");
            });
        }
        /** Runs the real entry on this context; restores caller cursors/user state before returning. */
        public Result probe(ParseContext context, Language language, Parser boundary, TokenKind kind) {
            int start = context.getPosition(kind).value();
            CstGrammar grammar = entries.get(language);
            if (grammar == null) return failed(FailureKind.UNAVAILABLE, start, "registered shared language entry");
            Parser parser = grammar.sharedEntry(language.entry());
            if (parser == null) return failed(FailureKind.UNSUPPORTED, start, "public shared grammar entry");
            if (context.getOptions().diagnostics() != ParseOptions.Diagnostics.DETAILED)
                return failed(FailureKind.UNSUPPORTED, start, "detailed diagnostics for shared entry");
            int sourceLength = context.sourceText().codePointCount(0, context.sourceText().length());
            Budget budget = budget(context); long before = budget.retained;
            int depth = (Integer) context.getGlobalScopeTreeMap().getOrDefault(DEPTH, 0);
            if (depth >= 32 || sourceLength > 1_048_576 || before + sourceLength > MAX_RETAINED_CP)
                return failed(FailureKind.LIMIT, start, "shared call input/depth limit");
            context.disableSpeculativeOptimizations();
            context.begin(boundary);
            var global = new HashMap<>(context.getGlobalScopeTreeMap());
            var local = new HashMap<>(context.getParserContextScopeTreeMap());
            context.getGlobalScopeTreeMap().clear(); context.getParserContextScopeTreeMap().clear();
            context.getGlobalScopeTreeMap().put(DEPTH, depth + 1);
            context.getGlobalScopeTreeMap().put(BUDGET, budget);
            budget.retained += sourceLength;
            context.getCurrent().getParserCursor().getCursor(TokenKind.consumed).setPosition(new CodePointIndex(start));
            context.getCurrent().getParserCursor().getCursor(TokenKind.matchOnly).setPosition(new CodePointIndex(start));
            var callerDiagnostic = context.beginDiagnosticSpeculation();
            try {
                var diagnostic = context.withLocalDiagnostic(() -> parser.parse(context));
                if (diagnostic.value().isFailed()) return new Result(null, new Failure(FailureKind.SYNTAX, diagnostic.offset(), diagnostic.expected()));
                int end = context.getPosition(TokenKind.consumed).value();
                Token token = diagnostic.value().getRootToken(false);
                if (!RecoveryDiagnostic.from(token).isEmpty()) return failed(FailureKind.RECOVERY, RecoveryDiagnostic.from(token).get(0).start(), "strict shared entry without recovery");
                if (end <= start) return failed(FailureKind.LIMIT, start, "nonempty shared entry");
                Call call = new Call(language, new Span(start, end), token.deepCopy(), budget.retained - before);
                context.discardDiagnosticSpeculation(callerDiagnostic);
                return new Result(call, null);
            } finally {
                context.rollback(boundary);
                context.getGlobalScopeTreeMap().clear(); context.getGlobalScopeTreeMap().putAll(global);
                context.getParserContextScopeTreeMap().clear(); context.getParserContextScopeTreeMap().putAll(local);
            }
        }
    }
    private static Result failed(FailureKind kind, int offset, String expected) {
        return new Result(null, new Failure(kind, offset, List.of(expected)));
    }
    /** Subclass with explicit immutable registry/identity and bind it using the existing UBNF ADAPTER. */
    public abstract static class Adapter extends AbstractTokenParser implements TerminalSymbol {
        private static final long serialVersionUID = 1L;
        protected abstract Registry registry();
        protected abstract Language language();
        @Override public Token getToken(ParseContext context, TokenKind kind, boolean invert) {
            Result result = registry().probe(context, language(), this, kind);
            if (!result.succeeded() || invert) return Token.empty(kind, context.getCursor(kind), this);
            Call call = result.call();
            if (kind.isConsumed()) budget(context).retained += call.retained;
            Token token = new Token(kind, context.peek(new CodePointIndex(call.span().start()), new CodePointLength(call.span().end() - call.span().start())), this);
            // A match-only probe never publishes a committed child-language region.
            if (kind.isConsumed()) token.putExtraObject(METADATA, call);
            return token;
        }
    }
}
