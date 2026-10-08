package org.unlaxer.editor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import org.unlaxer.Parsed;
import org.unlaxer.StringSource;
import org.unlaxer.Token;
import org.unlaxer.context.ParseContext;
import org.unlaxer.context.ParseOptions;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.combinator.RecoveryDiagnostic;

/** Opt-in editor CST. Inserted EOF syntax is observable metadata, never original source text. */
public final class EditorCst {
    public enum Status { COMPLETE, PARTIAL, FAILED }
    public enum Reason { NONE, REPAIRED, SYNTAX, LIMIT, NO_COMPLETION, UNSAFE }
    public enum DefectKind { MISSING, ERROR }
    public record Span(int start, int end) {}
    public record Capture(String name, Span span, boolean synthetic, String text) {}
    public record Node(String rule, Span span, boolean synthetic, List<Capture> captures) {
        public Node { captures = List.copyOf(captures); }
    }
    public record Defect(DefectKind kind, Span span, List<String> candidateRules) {
        public Defect { candidateRules = List.copyOf(candidateRules); }
    }
    public record Options(int maxFragments, int maxAttempts) {
        public Options {
            if (maxFragments < 0 || maxFragments > 8 || maxAttempts < 0 || maxAttempts > 4096) {
                throw new IllegalArgumentException("editor completion limit out of range");
            }
        }
        public static Options defaults() { return new Options(4, 256); }
    }
    private record Attempt(Token token, int farthest) {}
    private record Candidate(String suffix, int fragments) {}
    private final String source;
    private final Status status;
    private final Reason reason;
    private final List<Node> nodes;
    private final List<Defect> defects;
    private EditorCst(String source, Status status, Reason reason, List<Node> nodes, List<Defect> defects) {
        this.source = source;
        this.status = status;
        this.reason = reason;
        this.nodes = List.copyOf(nodes);
        this.defects = List.copyOf(defects);
    }
    public String source() { return source; }
    public Status status() { return status; }
    public Reason reason() { return reason; }
    public List<Node> nodes() { return nodes; }
    public List<Defect> defects() { return defects; }

    public static EditorCst parse(String source, Parser parser, List<String> completions,
            Function<Parser, String> ruleName, Function<Parser, List<String>> bindings, Options options) {
        Objects.requireNonNull(source);
        Objects.requireNonNull(parser);
        Objects.requireNonNull(completions);
        Objects.requireNonNull(ruleName);
        Objects.requireNonNull(bindings);
        Objects.requireNonNull(options);
        int length = source.codePointCount(0, source.length());
        if (length > 65536 || source.codePoints().anyMatch(value -> value >= 0xd800 && value <= 0xdfff)) {
            throw new IllegalArgumentException("invalid editor source");
        }
        Attempt original = attempt(source, parser);
        if (original.token() != null) {
            return snapshot(source, original.token(), false, ruleName, bindings);
        }
        if (false == org.unlaxer.context.DiagnosticsSafety.isDeferredDiagnosticsSafe(parser)) {
            return new EditorCst(source, Status.FAILED, Reason.UNSAFE, List.of(), List.of());
        }
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        for (String completion : completions) {
            if (!completion.isEmpty() && completion.codePointCount(0, completion.length()) <= 64) {
                candidates.add(completion);
            }
        }
        ArrayDeque<Candidate> pending = new ArrayDeque<>();
        pending.add(new Candidate("", 0));
        int attempts = 0;
        boolean depthLimit = false;
        while (!pending.isEmpty()) {
            Candidate parent = pending.removeFirst();
            if (parent.fragments() >= options.maxFragments()) {
                depthLimit = true;
                continue;
            }
            for (String completion : candidates) {
                if (attempts++ >= options.maxAttempts()) {
                    return new EditorCst(source, Status.FAILED, Reason.LIMIT, List.of(), List.of());
                }
                Candidate candidate = new Candidate(parent.suffix() + completion, parent.fragments() + 1);
                String completedSource = source + candidate.suffix();
                Attempt attempt = attempt(completedSource, parser);
                if (attempt.token() != null) {
                    return snapshot(source, attempt.token(), true, ruleName, bindings);
                }
                if (attempt.farthest() >= completedSource.codePointCount(0, completedSource.length())) {
                    pending.addLast(candidate);
                }
            }
        }
        return new EditorCst(source, Status.FAILED, depthLimit ? Reason.LIMIT : original.farthest() < length ? Reason.SYNTAX : Reason.NO_COMPLETION, List.of(), List.of());
    }
    private static Attempt attempt(String source, Parser parser) {
        ParseOptions options = ParseOptions.defaults().withDiagnostics(ParseOptions.Diagnostics.DETAILED);
        try (ParseContext context = ParseContext.withOptions(StringSource.createRootSource(source), options)) {
            Parsed parsed = parser.parse(context);
            if (parsed.isSucceeded() && parsed.getConsumed() != null && parsed.getConsumed().source != null
                    && parsed.getConsumed().source.toString().length() == source.length()) {
                return new Attempt(parsed.getRootToken(false), source.codePointCount(0, source.length()));
            }
            return new Attempt(null, context.getParseFailureDiagnostics().getFarthestOffset());
        }
    }
    private static Span span(Token token, int length) {
        int start = token.source.offsetFromRoot().value();
        return new Span(Math.min(start, length), Math.min(start + token.source.codePointLength().value(), length));
    }
    private static boolean synthetic(Token token, int length) {
        return token.source.offsetFromRoot().value() + token.source.codePointLength().value() > length;
    }
    private static String text(String source, Span span) {
        return source.substring(source.offsetByCodePoints(0, span.start()), source.offsetByCodePoints(0, span.end()));
    }
    private static EditorCst snapshot(String source, Token tree, boolean repaired,
            Function<Parser, String> ruleName, Function<Parser, List<String>> bindings) {
        int length = source.codePointCount(0, source.length());
        List<Node> nodes = new ArrayList<>();
        for (Token token : tree.flatten(Token.ScanDirection.Depth, Token.ChildrenKind.original)) {
            String name = ruleName.apply(token.parser);
            if (name == null || token.source == null || token.parser instanceof org.unlaxer.parser.combinator.SyncPointRecoveryParser
                    && token.getOriginalChildren().stream().anyMatch(child -> name.equals(ruleName.apply(child.parser)))) {
                continue;
            }
            List<Capture> captures = new ArrayList<>();
            collectCaptures(token, true, source, length, ruleName, bindings, captures);
            nodes.add(new Node(name, span(token, length), synthetic(token, length), captures));
        }
        List<Defect> defects = new ArrayList<>();
        for (RecoveryDiagnostic diagnostic : RecoveryDiagnostic.from(tree)) {
            if (diagnostic.start() >= length || diagnostic.end() > length) {
                continue;
            }
            List<String> candidates = nodes.stream().filter(node -> node.span().start() <= diagnostic.start()
                    && diagnostic.end() <= node.span().end()).sorted(java.util.Comparator.comparingInt(
                        (Node node) -> node.span().end() - node.span().start()).thenComparing(Node::rule)).map(Node::rule).distinct().toList();
            defects.add(new Defect(DefectKind.ERROR, new Span(diagnostic.start(), diagnostic.end()), candidates));
        }
        if (repaired) {
            List<String> candidates = nodes.stream().filter(Node::synthetic)
                .sorted(java.util.Comparator.comparingInt((Node node) -> node.span().end() - node.span().start()).thenComparing(Node::rule))
                .map(Node::rule).distinct().toList();
            defects.add(new Defect(DefectKind.MISSING, new Span(length, length), candidates));
        }
        return new EditorCst(source, defects.isEmpty() ? Status.COMPLETE : Status.PARTIAL,
            repaired ? Reason.REPAIRED : Reason.NONE, nodes, defects);
    }
    private static void collectCaptures(Token token, boolean root, String source, int length,
            Function<Parser, String> ruleName, Function<Parser, List<String>> bindings, List<Capture> captures) {
        if (token.source == null) {
            return;
        }
        for (String binding : bindings.apply(token.parser)) {
            Span span = span(token, length);
            captures.add(new Capture(binding, span, synthetic(token, length), text(source, span)));
        }
        if (!root && ruleName.apply(token.parser) != null) {
            return;
        }
        for (Token child : token.getOriginalChildren()) {
            collectCaptures(child, false, source, length, ruleName, bindings, captures);
        }
    }
}
