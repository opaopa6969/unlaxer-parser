package org.unlaxer.dsl.codegen;

import org.unlaxer.dsl.bootstrap.UBNFAST.AnnotatedElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.AtomicElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.BackrefAnnotation;
import org.unlaxer.dsl.bootstrap.UBNFAST.BoundedRepeatElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.ChoiceBody;
import org.unlaxer.dsl.bootstrap.UBNFAST.DeclaresAnnotation;
import org.unlaxer.dsl.bootstrap.UBNFAST.DocAnnotation;
import org.unlaxer.dsl.bootstrap.UBNFAST.ErrorElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.GroupElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.InterleaveAnnotation;
import org.unlaxer.dsl.bootstrap.UBNFAST.OneOrMoreElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.OptionalElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.RepeatElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.RightAssocAnnotation;
import org.unlaxer.dsl.bootstrap.UBNFAST.RuleBody;
import org.unlaxer.dsl.bootstrap.UBNFAST.RuleDecl;
import org.unlaxer.dsl.bootstrap.UBNFAST.RuleRefElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.ScopeTreeAnnotation;
import org.unlaxer.dsl.bootstrap.UBNFAST.SeparatedElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.SequenceBody;
import org.unlaxer.dsl.bootstrap.UBNFAST.RecoveryAnnotation;
import org.unlaxer.dsl.bootstrap.UBNFAST.RecoveryMode;
import org.unlaxer.dsl.bootstrap.UBNFAST.SkipAnnotation;
import org.unlaxer.dsl.bootstrap.UBNFAST.TerminalElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.WhitespaceAnnotation;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * ルールおよび要素のコード生成を担当する。
 */
class ParserRuleEmitter {

    private ParserRuleEmitter() {}

    /**
     * Lightweight indentation-aware writer for generating inner class code.
     * Replaces raw {@code sb.append(indent).append(...)} chains with
     * structured {@link #line}, {@link #openBlock} / {@link #closeBlock} calls.
     */
    private static final class IndentedWriter {
        private final StringBuilder sb = new StringBuilder();
        private int indent;

        IndentedWriter(int baseIndent) { this.indent = baseIndent; }

        /** Append one indented line (with trailing newline). */
        IndentedWriter line(String text) {
            sb.append("    ".repeat(indent)).append(text).append('\n');
            return this;
        }

        /** Append a blank line (no indentation, just newline). */
        IndentedWriter blankLine() {
            sb.append('\n');
            return this;
        }

        /** Append raw text without any indentation or trailing newline. */
        IndentedWriter raw(String text) {
            sb.append(text);
            return this;
        }

        /** Increase indent level by one. */
        IndentedWriter indent() { indent++; return this; }

        /** Decrease indent level by one. */
        IndentedWriter dedent() { indent--; return this; }

        String build() { return sb.toString(); }
    }

    static void collectHelpers(ParserGenerator.GenContext ctx, RuleDecl rule) {
        collectHelpersInBody(ctx, rule.name(), rule.body());
    }

    static void collectHelpersInBody(ParserGenerator.GenContext ctx, String ruleName, RuleBody body) {
        switch (body) {
            case ChoiceBody choice -> {
                for (SequenceBody alt : choice.alternatives()) {
                    collectHelpersInSequence(ctx, ruleName, alt);
                }
            }
            case SequenceBody seq -> collectHelpersInSequence(ctx, ruleName, seq);
        }
    }

    static void collectHelpersInSequence(ParserGenerator.GenContext ctx, String ruleName, SequenceBody seq) {
        for (AnnotatedElement ae : seq.elements()) {
            collectHelpersInElement(ctx, ruleName, ae.element());
        }
    }

    static void collectHelpersInElement(ParserGenerator.GenContext ctx, String ruleName, AtomicElement element) {
        switch (element) {
            case RepeatElement rep -> {
                if (!isSingleRuleRef(rep.body())) {
                    int n = ctx.nextRepeat(ruleName);
                    String helperName = ruleName + "Repeat" + n + "Parser";
                    int[] before = ctx.snapshotCounters(ruleName);
                    collectHelpersInBody(ctx, ruleName, rep.body());
                    int[] after = ctx.snapshotCounters(ruleName);
                    ctx.restoreCounters(ruleName, before);
                    String helperCode = generateHelperCode(ctx, ruleName, helperName, rep.body());
                    ctx.restoreCounters(ruleName, after);
                    ctx.addHelper(ruleName, helperCode);
                }
            }
            case OptionalElement opt -> {
                if (!isSingleAtomicElement(opt.body())) {
                    int n = ctx.nextOpt(ruleName);
                    String helperName = ruleName + "Opt" + n + "Parser";
                    int[] before = ctx.snapshotCounters(ruleName);
                    collectHelpersInBody(ctx, ruleName, opt.body());
                    int[] after = ctx.snapshotCounters(ruleName);
                    ctx.restoreCounters(ruleName, before);
                    String helperCode = generateHelperCode(ctx, ruleName, helperName, opt.body());
                    ctx.restoreCounters(ruleName, after);
                    ctx.addHelper(ruleName, helperCode);
                }
            }
            case OneOrMoreElement one -> {
                if (!isSingleRuleRef(one.body())) {
                    int n = ctx.nextRepeat(ruleName);
                    String helperName = ruleName + "OneOrMore" + n + "Parser";
                    int[] before = ctx.snapshotCounters(ruleName);
                    collectHelpersInBody(ctx, ruleName, one.body());
                    int[] after = ctx.snapshotCounters(ruleName);
                    ctx.restoreCounters(ruleName, before);
                    String helperCode = generateHelperCode(ctx, ruleName, helperName, one.body());
                    ctx.restoreCounters(ruleName, after);
                    ctx.addHelper(ruleName, helperCode);
                }
            }
            case BoundedRepeatElement bounded -> {
                if (!isSingleRuleRef(bounded.body())) {
                    int n = ctx.nextRepeat(ruleName);
                    String helperName = ruleName + "Bounded" + n + "Parser";
                    int[] before = ctx.snapshotCounters(ruleName);
                    collectHelpersInBody(ctx, ruleName, bounded.body());
                    int[] after = ctx.snapshotCounters(ruleName);
                    ctx.restoreCounters(ruleName, before);
                    String helperCode = generateHelperCode(ctx, ruleName, helperName, bounded.body());
                    ctx.restoreCounters(ruleName, after);
                    ctx.addHelper(ruleName, helperCode);
                }
            }
            case GroupElement g -> {
                int n = ctx.nextGroup(ruleName);
                String helperName = ruleName + "Group" + n + "Parser";
                int[] before = ctx.snapshotCounters(ruleName);
                collectHelpersInBody(ctx, ruleName, g.body());
                int[] after = ctx.snapshotCounters(ruleName);
                ctx.restoreCounters(ruleName, before);
                String helperCode = generateHelperCode(ctx, ruleName, helperName, g.body());
                ctx.restoreCounters(ruleName, after);
                ctx.addHelper(ruleName, helperCode);
            }
            case SeparatedElement sep -> {
                int n = ctx.nextSep(ruleName);
                String bodyHelperName = ruleName + "Sep" + n + "BodyParser";
                String outerHelperName = ruleName + "Sep" + n + "Parser";
                int[] before = ctx.snapshotCounters(ruleName);
                collectHelpersInElement(ctx, ruleName, sep.element());
                collectHelpersInElement(ctx, ruleName, sep.separator());
                int[] after = ctx.snapshotCounters(ruleName);
                ctx.restoreCounters(ruleName, before);
                String elemCode = generateElementCode(ctx, ruleName, sep.element());
                String sepCode  = generateElementCode(ctx, ruleName, sep.separator());
                ctx.restoreCounters(ruleName, after);
                String chainClass = getChainClassName(ctx, ruleName);
                IndentedWriter bw = new IndentedWriter(1);
                bw.line("public static class " + bodyHelperName + " extends " + chainClass + " {");
                bw.indent();
                bw.line("private static final long serialVersionUID = 1L;");
                bw.line("@Override");
                bw.line("public Parsers getLazyParsers() {");
                bw.indent();
                bw.line("return new Parsers(");
                bw.indent();
                bw.line(sepCode + ",");
                bw.line(elemCode);
                bw.dedent();
                bw.line(");");
                bw.dedent();
                bw.line("}");
                bw.dedent();
                bw.line("}");
                bw.blankLine();
                ctx.addHelper(ruleName, bw.build());
                IndentedWriter ow = new IndentedWriter(1);
                ow.line("public static class " + outerHelperName + " extends " + chainClass + " {");
                ow.indent();
                ow.line("private static final long serialVersionUID = 1L;");
                ow.line("@Override");
                ow.line("public Parsers getLazyParsers() {");
                ow.indent();
                ow.line("return new Parsers(");
                ow.indent();
                ow.line(elemCode + ",");
                ow.line("new ZeroOrMore(" + bodyHelperName + ".class)");
                ow.dedent();
                ow.line(");");
                ow.dedent();
                ow.line("}");
                ow.dedent();
                ow.line("}");
                ow.blankLine();
                ctx.addHelper(ruleName, ow.build());
            }
            default -> {} // TerminalElement, RuleRefElement, ErrorElement
        }
    }

    /**
     * ヘルパークラスのコードを生成する。
     * body が複数代替 ChoiceBody なら LazyChoice、
     * それ以外なら {GrammarName}LazyChain を継承する。
     */
    static String generateHelperCode(ParserGenerator.GenContext ctx, String ruleName, String helperName, RuleBody body) {
        boolean isChoice = isMultiChoice(body);
        String baseClass = isChoice ? "LazyChoice" : getChainClassName(ctx, ruleName);
        IndentedWriter w = new IndentedWriter(1);

        w.line("public static class " + helperName + " extends " + baseClass + " {");
        w.indent();
        w.line("private static final long serialVersionUID = 1L;");
        w.line("@Override");
        w.line("public Parsers getLazyParsers() {");
        w.indent();
        w.line("return new Parsers(");
        w.raw(generateBodyElements(ctx, ruleName, body, "    ".repeat(4)));
        w.line(");");
        w.dedent();
        w.line("}");
        if (isChoice) {
            w.line("@Override");
            w.line("public java.util.Optional<RecursiveMode> getNotAstNodeSpecifier() { return java.util.Optional.empty(); }");
        }
        w.dedent();
        w.line("}");
        w.blankLine();

        return w.build();
    }

    /**
     * Computes FOLLOW tokens for a rule by scanning the grammar for all references
     * to the rule and collecting terminal elements that appear immediately after.
     */
    static List<String> computeFollowTokens(ParserGenerator.GenContext ctx, String ruleName) {
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        for (RuleDecl rule : ctx.grammar.rules()) {
            collectFollowFromBody(rule.body(), ruleName, tokens);
        }
        return new ArrayList<>(tokens);
    }

    private static void collectFollowFromBody(RuleBody body, String targetRule, LinkedHashSet<String> tokens) {
        switch (body) {
            case ChoiceBody choice -> {
                for (SequenceBody alt : choice.alternatives()) {
                    collectFollowFromSequence(alt, targetRule, tokens);
                }
            }
            case SequenceBody seq -> collectFollowFromSequence(seq, targetRule, tokens);
        }
    }

    private static void collectFollowFromSequence(SequenceBody seq, String targetRule, LinkedHashSet<String> tokens) {
        List<AnnotatedElement> elements = seq.elements();
        for (int i = 0; i < elements.size(); i++) {
            AtomicElement elem = elements.get(i).element();
            // Direct reference: look at what follows
            if (elem instanceof RuleRefElement ref && ref.name().equals(targetRule)) {
                if (i + 1 < elements.size()) {
                    collectFirstTerminals(elements.get(i + 1).element(), tokens);
                }
            }
            // Reference inside a repeat: { TargetRule } — look at what follows the repeat
            if (elem instanceof RepeatElement rep) {
                if (containsRuleRef(rep.body(), targetRule)) {
                    if (i + 1 < elements.size()) {
                        collectFirstTerminals(elements.get(i + 1).element(), tokens);
                    }
                }
                collectFollowFromBody(rep.body(), targetRule, tokens);
            }
            if (elem instanceof GroupElement g) {
                collectFollowFromBody(g.body(), targetRule, tokens);
            }
            if (elem instanceof OptionalElement opt) {
                collectFollowFromBody(opt.body(), targetRule, tokens);
            }
        }
    }

    private static void collectFirstTerminals(AtomicElement elem, LinkedHashSet<String> tokens) {
        switch (elem) {
            case TerminalElement t -> tokens.add(t.value());
            case GroupElement g -> collectFirstTerminalsFromBody(g.body(), tokens);
            default -> {}
        }
    }

    private static void collectFirstTerminalsFromBody(RuleBody body, LinkedHashSet<String> tokens) {
        switch (body) {
            case ChoiceBody choice -> {
                for (SequenceBody alt : choice.alternatives()) {
                    if (false == alt.elements().isEmpty()) {
                        collectFirstTerminals(alt.elements().get(0).element(), tokens);
                    }
                }
            }
            case SequenceBody seq -> {
                if (false == seq.elements().isEmpty()) {
                    collectFirstTerminals(seq.elements().get(0).element(), tokens);
                }
            }
        }
    }

    private static boolean containsRuleRef(RuleBody body, String targetRule) {
        return switch (body) {
            case ChoiceBody choice -> choice.alternatives().stream()
                .anyMatch(alt -> alt.elements().stream()
                    .anyMatch(ae -> ae.element() instanceof RuleRefElement ref && ref.name().equals(targetRule)));
            case SequenceBody seq -> seq.elements().stream()
                .anyMatch(ae -> ae.element() instanceof RuleRefElement ref && ref.name().equals(targetRule));
        };
    }

    /**
     * Returns the RecoveryAnnotation for the given rule, if present.
     */
    static Optional<RecoveryAnnotation> findRecoveryAnnotation(RuleDecl rule) {
        return rule.annotations().stream()
            .filter(a -> a instanceof RecoveryAnnotation)
            .map(a -> (RecoveryAnnotation) a)
            .findFirst();
    }

    /**
     * Generates a recovery wrapper class for a rule with @recovery annotation.
     * For SYNC mode, wraps the rule parser with SyncPointRecoveryParser.
     * For AUTO mode, uses ";" as default sync token.
     * For SKIP mode, generates a wrapper that catches failure and returns an error node.
     */
    static String generateRecoveryWrapper(ParserGenerator.GenContext ctx, RuleDecl rule, RecoveryAnnotation recovery) {
        String ruleName = rule.name();
        String className = ruleName + "Parser";
        String wrapperName = ruleName + "RecoveryParser";
        IndentedWriter w = new IndentedWriter(1);

        RecoveryMode mode = recovery.mode();
        if (mode == RecoveryMode.SYNC) {
            String[] tokens = recovery.syncTokens().isEmpty()
                ? new String[]{ ";" }
                : recovery.syncTokens().toArray(new String[0]);
            String syncArgs = java.util.Arrays.stream(tokens)
                .map(t -> "\"" + ParserCodegenUtil.escapeString(t) + "\"")
                .collect(Collectors.joining(", "));
            w.line("public static class " + wrapperName + " extends org.unlaxer.parser.combinator.SyncPointRecoveryParser {");
            w.indent();
            w.line("private static final long serialVersionUID = 1L;");
            w.line("public " + wrapperName + "() {");
            w.indent();
            w.line("super(Parser.get(" + className + ".class), " + syncArgs + ");");
            w.dedent();
            w.line("}");
            w.dedent();
            w.line("}");
            w.blankLine();
        } else if (mode == RecoveryMode.AUTO) {
            // Auto-infer sync tokens from grammar FOLLOW set
            List<String> followTokens = computeFollowTokens(ctx, ruleName);
            if (followTokens.isEmpty()) {
                followTokens = List.of(";"); // fallback
            }
            String syncArgs = followTokens.stream()
                .map(t -> "\"" + ParserCodegenUtil.escapeString(t) + "\"")
                .collect(Collectors.joining(", "));
            w.line("public static class " + wrapperName + " extends org.unlaxer.parser.combinator.SyncPointRecoveryParser {");
            w.indent();
            w.line("private static final long serialVersionUID = 1L;");
            w.line("public " + wrapperName + "() {");
            w.indent();
            w.line("super(Parser.get(" + className + ".class), " + syncArgs + ");");
            w.dedent();
            w.line("}");
            w.dedent();
            w.line("}");
            w.blankLine();
        } else {
            // SKIP mode: on failure, skip one character and return success with error marker.
            // This ensures the AST always has a node (Hejlsberg principle: always produce an AST).
            w.line("public static class " + wrapperName + " extends org.unlaxer.parser.combinator.ConstructedSingleChildParser {");
            w.indent();
            w.line("private static final long serialVersionUID = 1L;");
            w.line("public " + wrapperName + "() {");
            w.indent();
            w.line("super(Parser.get(" + className + ".class));");
            w.dedent();
            w.line("}");
            w.line("@Override");
            w.line("public org.unlaxer.Parsed parse(org.unlaxer.context.ParseContext parseContext, org.unlaxer.TokenKind tokenKind, boolean invertMatch) {");
            w.indent();
            w.line("parseContext.begin(this);");
            w.line("org.unlaxer.Parsed result = getChild().parse(parseContext, tokenKind, invertMatch);");
            w.line("if (result.isSucceeded()) {");
            w.indent();
            w.line("return parseContext.commit(this, tokenKind).toParsed();");
            w.dedent();
            w.line("}");
            w.line("parseContext.rollback(this);");
            w.line("// Skip mode: advance cursor by one character, embed error marker, return success");
            w.line("if (parseContext.source.isEmpty() || parseContext.isAtEnd()) {");
            w.indent();
            w.line("return org.unlaxer.Parsed.FAILED;");
            w.dedent();
            w.line("}");
            w.line("parseContext.begin(this);");
            w.line("parseContext.advanceCursor(1);");
            w.line("return parseContext.commit(this, tokenKind).toParsed();");
            w.dedent();
            w.line("}");
            w.dedent();
            w.line("}");
            w.blankLine();
        }

        return w.build();
    }

    static String generateRuleClass(ParserGenerator.GenContext ctx, RuleDecl rule) {
        String ruleName = rule.name();
        String className = ruleName + "Parser";
        ParserGenerator.RightAssocShape rightAssocShape = getRightAssocShape(rule);
        boolean isChoice = rightAssocShape != null || isMultiChoice(rule.body());

        IndentedWriter w = new IndentedWriter(1);

        // @doc annotation → Javadoc comment
        rule.annotations().stream()
            .filter(a -> a instanceof DocAnnotation)
            .map(a -> ((DocAnnotation) a).text())
            .findFirst()
            .ifPresent(docText -> {
                w.line("/** " + docText + " */");
            });

        boolean hasScopeTreeDecl = rule.annotations().stream().anyMatch(a -> a instanceof ScopeTreeAnnotation);
        boolean hasDeclaresDecl  = rule.annotations().stream().anyMatch(a -> a instanceof DeclaresAnnotation);
        // @backref: 文法内に @scopeTree があればスコープ参照モード → TransactionListener が必要
        boolean hasBackrefDecl = rule.annotations().stream().anyMatch(a -> a instanceof BackrefAnnotation);
        boolean grammarHasScopeTree = ctx.grammar.rules().stream()
            .anyMatch(r -> r.annotations().stream().anyMatch(a -> a instanceof ScopeTreeAnnotation));
        boolean backrefScopeMode    = hasBackrefDecl && grammarHasScopeTree;
        boolean backrefBackrefMode  = hasBackrefDecl && !grammarHasScopeTree;
        boolean needsTransactionListener = hasScopeTreeDecl || hasDeclaresDecl || backrefScopeMode || backrefBackrefMode;
        String implSuffix = needsTransactionListener
            ? " implements org.unlaxer.listener.TransactionListener"
            : "";
        String baseClass = isChoice ? "LazyChoice" : getChainClassName(ctx, ruleName);
        w.line("public static class " + className + " extends " + baseClass + implSuffix + " {");
        w.indent();
        w.line("private static final long serialVersionUID = 1L;");
        w.line("@Override");
        w.line("public Parsers getLazyParsers() {");
        w.indent();
        w.line("return new Parsers(");
        if (rightAssocShape != null) {
            w.raw(generateRightAssocElements(ctx, ruleName, className, rightAssocShape, "    ".repeat(4)));
        } else {
            w.raw(generateBodyElements(ctx, ruleName, rule.body(), "    ".repeat(4)));
        }
        w.line(");");
        w.dedent();
        w.line("}");
        boolean hasSkip = rule.annotations().stream().anyMatch(a -> a instanceof SkipAnnotation);
        if (isChoice || hasSkip) {
            w.line("@Override");
            if (hasSkip) {
                w.line("public java.util.Optional<RecursiveMode> getNotAstNodeSpecifier() { return java.util.Optional.of(RecursiveMode.containsRoot); }");
            } else {
                w.line("public java.util.Optional<RecursiveMode> getNotAstNodeSpecifier() { return java.util.Optional.empty(); }");
            }
        }
        // @scopeTree / @declares / @backref → TransactionListener 実装を生成
        boolean hasScopeTree = rule.annotations().stream().anyMatch(a -> a instanceof ScopeTreeAnnotation);
        boolean hasDeclares  = rule.annotations().stream().anyMatch(a -> a instanceof DeclaresAnnotation);
        if (hasScopeTree || hasDeclares || backrefScopeMode || backrefBackrefMode) {
            w.raw(generateTransactionListenerMethods(ctx, rule, hasScopeTree, hasDeclares, backrefScopeMode, backrefBackrefMode));
        }
        w.dedent();
        w.line("}");
        w.blankLine();

        return w.build();
    }

    /**
     * @scopeTree / @declares アノテーション付きルールの TransactionListener メソッドを生成する。
     */
    static String generateTransactionListenerMethods(
        ParserGenerator.GenContext ctx, RuleDecl rule,
        boolean hasScopeTree, boolean hasDeclares, boolean backrefScopeMode, boolean backrefBackrefMode) {

        IndentedWriter w = new IndentedWriter(2);

        // setLevel (no-op)
        w.line("@Override");
        w.line("public void setLevel(org.unlaxer.listener.OutputLevel level) {}");

        // onOpen (no-op)
        w.line("@Override");
        w.line("public void onOpen(org.unlaxer.context.ParseContext ctx) {}");

        // onBegin
        w.line("@Override");
        w.line("public void onBegin(org.unlaxer.context.ParseContext ctx, org.unlaxer.parser.Parser p) {");
        if (hasScopeTree) {
            w.indent();
            w.line("org.unlaxer.dsl.runtime.ScopeStore.enter(ctx);");
            w.dedent();
        }
        w.line("}");

        // onCommit
        w.line("@Override");
        w.line("public void onCommit(org.unlaxer.context.ParseContext ctx, org.unlaxer.parser.Parser p, org.unlaxer.TokenList tokens) {");
        w.indent();
        if (hasScopeTree) {
            w.line("org.unlaxer.dsl.runtime.ScopeStore.leave(ctx);");
        }
        if (hasDeclares) {
            String symbolCapture = rule.annotations().stream()
                .filter(a -> a instanceof DeclaresAnnotation)
                .map(a -> ((DeclaresAnnotation) a).symbolCapture())
                .findFirst().orElse("");
            // キャプチャ名に対応する要素のパーサークラスを特定する
            String captureParserClass = findCaptureParserClass(ctx, rule, symbolCapture);
            w.line("// @declares(symbol=" + symbolCapture + ")");
            w.line("if (!tokens.isEmpty()) {");
            w.indent();
            w.line("org.unlaxer.Token ruleToken = tokens.get(0);");
            if (captureParserClass != null) {
                // 対応するパーサークラスが特定できた → getChildWithParser で直接取得
                w.line("org.unlaxer.Token captureToken = ruleToken.getChildWithParser(" + captureParserClass + ");");
                w.line("if (captureToken != null && captureToken.source != null) {");
                w.indent();
                w.line("String __symbolName = captureToken.source.sourceAsString().trim();");
                w.line("if (!__symbolName.isEmpty()) {");
                w.indent();
                w.line("int __offset = captureToken.source.offsetFromRoot().value();");
                w.line("org.unlaxer.dsl.runtime.ScopeStore.declare(ctx, __symbolName, __offset);");
                w.dedent();
                w.line("}");
                w.dedent();
                w.line("}");
            } else {
                // フォールバック: filteredChildren を順に走査してnon-keyword を探す
                w.line("for (org.unlaxer.Token child : ruleToken.filteredChildren) {");
                w.indent();
                w.line("if (child.source == null) continue;");
                w.line("String __symbolName = child.source.sourceAsString().trim();");
                w.line("if (!__symbolName.isEmpty()) {");
                w.indent();
                w.line("int __offset = child.source.offsetFromRoot().value();");
                w.line("org.unlaxer.dsl.runtime.ScopeStore.declare(ctx, __symbolName, __offset);");
                w.line("break;");
                w.dedent();
                w.line("}");
                w.dedent();
                w.line("}");
            }
            w.dedent();
            w.line("}");
        }
        if (backrefScopeMode) {
            String backrefCapture = rule.annotations().stream()
                .filter(a -> a instanceof BackrefAnnotation)
                .map(a -> ((BackrefAnnotation) a).name())
                .findFirst().orElse("");
            String captureParserClass = findCaptureParserClass(ctx, rule, backrefCapture);
            w.line("// @backref(name=" + backrefCapture + ") \u2014 scope reference mode");
            w.line("if (!tokens.isEmpty()) {");
            w.indent();
            w.line("org.unlaxer.Token ruleToken = tokens.get(0);");
            if (captureParserClass != null) {
                w.line("org.unlaxer.Token refToken = ruleToken.getChildWithParser(" + captureParserClass + ");");
                w.line("if (refToken != null && refToken.source != null) {");
                w.indent();
                w.line("String __refName = refToken.source.sourceAsString().trim();");
                w.line("if (!__refName.isEmpty()) {");
                w.indent();
                w.line("int __offset = refToken.source.offsetFromRoot().value();");
                w.line("org.unlaxer.dsl.runtime.ScopeStore.addReference(ctx, __refName, __offset, __refName.length());");
                w.line("if (!org.unlaxer.dsl.runtime.ScopeStore.isDeclared(ctx, __refName)) {");
                w.indent();
                w.line("org.unlaxer.dsl.runtime.ScopeStore.addDiagnostic(ctx,");
                w.line("    \"未定義のシンボル: '\" + __refName + \"'\",");
                w.line("    __offset, __refName.length(),");
                w.line("    org.unlaxer.dsl.runtime.ScopeStore.Severity.WARNING);");
                w.dedent();
                w.line("}");
                w.dedent();
                w.line("}");
                w.dedent();
                w.line("}");
            }
            w.dedent();
            w.line("}");
        }
        if (backrefBackrefMode) {
            String backrefCapture = rule.annotations().stream()
                .filter(a -> a instanceof BackrefAnnotation)
                .map(a -> ((BackrefAnnotation) a).name())
                .findFirst().orElse("");
            String captureParserClass = findCaptureParserClass(ctx, rule, backrefCapture);
            w.line("// @backref(name=" + backrefCapture + ") \u2014 back-reference mode (same-rule token match)");
            w.line("if (!tokens.isEmpty()) {");
            w.indent();
            w.line("org.unlaxer.Token ruleToken = tokens.get(0);");
            if (captureParserClass != null) {
                // filteredChildren から同パーサークラスの全トークンを収集し、テキストが一致するか検証
                w.line("java.util.List<org.unlaxer.Token> __backrefTokens =");
                w.line("    (ruleToken.filteredChildren == null)");
                w.line("    ? java.util.Collections.emptyList()");
                w.line("    : ruleToken.filteredChildren.stream()");
                w.line("        .filter(c -> c.getParser() instanceof " + captureParserClass + ")");
                w.line("        .collect(java.util.stream.Collectors.toList());");
                w.line("if (__backrefTokens.size() >= 2) {");
                w.indent();
                w.line("String __expected = __backrefTokens.get(0).source == null ? \"\" : __backrefTokens.get(0).source.sourceAsString().trim();");
                w.line("for (int __bi = 1; __bi < __backrefTokens.size(); __bi++) {");
                w.indent();
                w.line("org.unlaxer.Token __bt = __backrefTokens.get(__bi);");
                w.line("if (__bt.source == null) continue;");
                w.line("String __actual = __bt.source.sourceAsString().trim();");
                w.line("if (!__expected.equals(__actual)) {");
                w.indent();
                w.line("org.unlaxer.dsl.runtime.ScopeStore.addDiagnostic(ctx,");
                w.line("    \"back-reference mismatch: expected '\" + __expected + \"' but got '\" + __actual + \"'\",");
                w.line("    __bt.source.offsetFromRoot().value(), __actual.length(),");
                w.line("    org.unlaxer.dsl.runtime.ScopeStore.Severity.ERROR);");
                w.dedent();
                w.line("}");
                w.dedent();
                w.line("}");
                w.dedent();
                w.line("}");
            }
            w.dedent();
            w.line("}");
        }
        w.dedent();
        w.line("}");

        // onRollback
        w.line("@Override");
        w.line("public void onRollback(org.unlaxer.context.ParseContext ctx, org.unlaxer.parser.Parser p, org.unlaxer.TokenList tokens) {");
        if (hasScopeTree) {
            w.indent();
            w.line("org.unlaxer.dsl.runtime.ScopeStore.leave(ctx);");
            w.dedent();
        }
        w.line("}");

        // onClose (no-op)
        w.line("@Override");
        w.line("public void onClose(org.unlaxer.context.ParseContext ctx) {}");

        return w.build();
    }

    static String generateRightAssocElements(
        ParserGenerator.GenContext ctx,
        String ruleName,
        String className,
        ParserGenerator.RightAssocShape shape,
        String indent
    ) {
        String baseCode = generateElementCode(ctx, ruleName, shape.base());
        String opCode = generateElementCode(ctx, ruleName, shape.op());
        String chainClass = getChainClassName(ctx, ruleName);
        int level = indent.length() / 4;
        IndentedWriter w = new IndentedWriter(level);

        w.line("new " + chainClass + "() {");
        w.indent();
        w.line("private static final long serialVersionUID = 1L;");
        w.line("@Override");
        w.line("public Parsers getLazyParsers() {");
        w.indent();
        w.line("return new Parsers(");
        w.indent();
        w.line(baseCode + ",");
        w.line(opCode + ",");
        w.line("Parser.get(" + className + ".class)");
        w.dedent();
        w.line(");");
        w.dedent();
        w.line("}");
        w.dedent();
        w.line("},");
        w.line(baseCode);

        return w.build();
    }

    /**
     * RuleBody から getLazyParsers() の中身（カンマ区切り要素リスト）を生成する。
     */
    static String generateBodyElements(ParserGenerator.GenContext ctx, String ruleName, RuleBody body, String indent) {
        List<String> elementCodes = new ArrayList<>();

        switch (body) {
            case ChoiceBody choice -> {
                if (choice.alternatives().size() == 1) {
                    // 単一代替 → SequenceBody として扱う
                    for (AnnotatedElement ae : choice.alternatives().get(0).elements()) {
                        elementCodes.add(generateElementCode(ctx, ruleName, ae.element()));
                    }
                } else {
                    // 複数代替 → 各代替を1エントリに
                    for (SequenceBody alt : choice.alternatives()) {
                        elementCodes.add(generateAlternativeCode(ctx, ruleName, alt, indent));
                    }
                }
            }
            case SequenceBody seq -> {
                for (AnnotatedElement ae : seq.elements()) {
                    elementCodes.add(generateElementCode(ctx, ruleName, ae.element()));
                }
            }
        }

        return elementCodes.stream()
            .map(c -> indent + c)
            .collect(Collectors.joining(",\n")) + "\n";
    }

    /**
     * ChoiceBody の1つの代替（SequenceBody）をコードに変換する。
     */
    static String generateAlternativeCode(ParserGenerator.GenContext ctx, String ruleName, SequenceBody alt, String baseIndent) {
        List<AnnotatedElement> elements = alt.elements();

        if (elements.size() == 1) {
            return generateElementCode(ctx, ruleName, elements.get(0).element());
        }

        // 複数要素 → 匿名 TinyCalcLazyChain
        String chainClass = getChainClassName(ctx, ruleName);
        int innerLevel = baseIndent.length() / 4 + 1;
        IndentedWriter w = new IndentedWriter(innerLevel);

        List<String> elemCodes = new ArrayList<>();
        for (AnnotatedElement ae : elements) {
            elemCodes.add(generateElementCode(ctx, ruleName, ae.element()));
        }

        // First line has no leading indent (caller adds it via indent + c)
        StringBuilder result = new StringBuilder();
        result.append("new ").append(chainClass).append("() {\n");
        w.line("private static final long serialVersionUID = 1L;");
        w.line("@Override");
        w.line("public Parsers getLazyParsers() {");
        w.indent();
        w.line("return new Parsers(");
        String elemIndent = "    ".repeat(innerLevel + 2);
        String elemsJoined = elemCodes.stream()
            .map(c -> elemIndent + c)
            .collect(Collectors.joining(",\n"));
        w.raw(elemsJoined + "\n");
        w.line(");");
        w.dedent();
        w.line("}");
        result.append(w.build());
        result.append(baseIndent).append("}");

        return result.toString();
    }

    /**
     * 単一の AtomicElement をコードに変換する。
     */
    static String generateElementCode(ParserGenerator.GenContext ctx, String ruleName, AtomicElement element) {
        return switch (element) {
            case TerminalElement t -> "new WordParser(\"" + ParserCodegenUtil.escapeString(t.value()) + "\")";

            case RuleRefElement r -> resolveParserExpression(ctx, r.name());

            case RepeatElement rep -> {
                if (isSingleRuleRef(rep.body())) {
                    AtomicElement single = getSingleAtomicElementFrom(rep.body());
                    if (single instanceof RuleRefElement ref && isInlineToken(ctx, ref.name())) {
                        yield "new ZeroOrMore(" + resolveParserExpression(ctx, ref.name()) + ")";
                    }
                    String parserClass = getSingleRuleRefClass(ctx, rep.body());
                    yield "new ZeroOrMore(" + parserClass + ")";
                } else {
                    int n = ctx.nextRepeat(ruleName);
                    String helperName = ruleName + "Repeat" + n + "Parser";
                    yield "new ZeroOrMore(" + helperName + ".class)";
                }
            }

            case OptionalElement opt -> {
                if (isSingleAtomicElement(opt.body())) {
                    AtomicElement inner = getSingleAtomicElementFrom(opt.body());
                    if (inner instanceof RuleRefElement ref) {
                        if (isInlineToken(ctx, ref.name())) {
                            yield "new Optional(" + resolveParserExpression(ctx, ref.name()) + ")";
                        }
                        yield "new Optional(" + resolveParserClass(ctx, ref.name()) + ")";
                    } else if (inner instanceof TerminalElement t) {
                        yield "new Optional(new WordParser(\"" + ParserCodegenUtil.escapeString(t.value()) + "\"))";
                    } else {
                        int n = ctx.nextOpt(ruleName);
                        String helperName = ruleName + "Opt" + n + "Parser";
                        yield "new Optional(" + helperName + ".class)";
                    }
                } else {
                    int n = ctx.nextOpt(ruleName);
                    String helperName = ruleName + "Opt" + n + "Parser";
                    yield "new Optional(" + helperName + ".class)";
                }
            }

            case OneOrMoreElement one -> {
                if (isSingleRuleRef(one.body())) {
                    AtomicElement single = getSingleAtomicElementFrom(one.body());
                    if (single instanceof RuleRefElement ref && isInlineToken(ctx, ref.name())) {
                        yield "new OneOrMore(" + resolveParserExpression(ctx, ref.name()) + ")";
                    }
                    String parserClass = getSingleRuleRefClass(ctx, one.body());
                    yield "new OneOrMore(" + parserClass + ")";
                } else {
                    int n = ctx.nextRepeat(ruleName);
                    String helperName = ruleName + "OneOrMore" + n + "Parser";
                    yield "new OneOrMore(" + helperName + ".class)";
                }
            }

            case BoundedRepeatElement bounded -> {
                String minStr = String.valueOf(bounded.min());
                String maxStr = bounded.max() == BoundedRepeatElement.UNBOUNDED
                    ? "Integer.MAX_VALUE"
                    : String.valueOf(bounded.max());
                if (isSingleRuleRef(bounded.body())) {
                    AtomicElement single = getSingleAtomicElementFrom(bounded.body());
                    if (single instanceof RuleRefElement ref && isInlineToken(ctx, ref.name())) {
                        // inline tokens don't have a .class reference — wrap in helper
                        int n = ctx.nextRepeat(ruleName);
                        String helperName = ruleName + "Bounded" + n + "Parser";
                        yield "new Repeat(" + helperName + ".class, " + minStr + ", " + maxStr + ")";
                    }
                    String parserClass = getSingleRuleRefClass(ctx, bounded.body());
                    yield "new Repeat(" + parserClass + ", " + minStr + ", " + maxStr + ")";
                } else {
                    int n = ctx.nextRepeat(ruleName);
                    String helperName = ruleName + "Bounded" + n + "Parser";
                    yield "new Repeat(" + helperName + ".class, " + minStr + ", " + maxStr + ")";
                }
            }

            case GroupElement g -> {
                int n = ctx.nextGroup(ruleName);
                String helperName = ruleName + "Group" + n + "Parser";
                yield "Parser.get(" + helperName + ".class)";
            }

            case SeparatedElement sep -> {
                int n = ctx.nextSep(ruleName);
                String outerHelperName = ruleName + "Sep" + n + "Parser";
                yield "Parser.get(" + outerHelperName + ".class)";
            }

            case ErrorElement err ->
                "org.unlaxer.parser.ErrorMessageParser.expected(\"" + ParserCodegenUtil.escapeString(err.message()) + "\")";
        };
    }

    /** body が複数代替の ChoiceBody かどうか */
    static boolean isMultiChoice(RuleBody body) {
        return body instanceof ChoiceBody choice && choice.alternatives().size() > 1;
    }

    /** body が単一の RuleRefElement だけを含むか */
    static boolean isSingleRuleRef(RuleBody body) {
        AtomicElement single = getSingleAtomicElementFrom(body);
        return single instanceof RuleRefElement;
    }

    /** body が単一の AtomicElement だけを含むか */
    static boolean isSingleAtomicElement(RuleBody body) {
        return getSingleAtomicElementFrom(body) != null;
    }

    /** body から単一の AtomicElement を取り出す（なければ null） */
    static AtomicElement getSingleAtomicElementFrom(RuleBody body) {
        return switch (body) {
            case SequenceBody seq when seq.elements().size() == 1 ->
                seq.elements().get(0).element();
            case ChoiceBody choice when choice.alternatives().size() == 1 -> {
                SequenceBody seq = choice.alternatives().get(0);
                yield seq.elements().size() == 1 ? seq.elements().get(0).element() : null;
            }
            default -> null;
        };
    }

    /** 単一 RuleRef body からパーサークラス参照を取り出す */
    static String getSingleRuleRefClass(ParserGenerator.GenContext ctx, RuleBody body) {
        AtomicElement single = getSingleAtomicElementFrom(body);
        if (single instanceof RuleRefElement ref) {
            return resolveParserClass(ctx, ref.name());
        }
        throw new IllegalStateException("Expected single RuleRef body");
    }

    /**
     * ルール参照名をパーサークラス参照文字列に変換する。
     * @recovery アノテーション付きルールを参照する場合は RecoveryWrapper クラスを返す。
     */
    static String resolveParserClass(ParserGenerator.GenContext ctx, String name) {
        // Negation / CharRange / Regex tokens → generated inner class name
        if (ctx.tokenNegationMap.containsKey(name)) {
            return ParserCodegenUtil.toParserClassName(name) + ".class";
        }
        if (ctx.tokenCharRangeMap.containsKey(name)) {
            return ParserCodegenUtil.toParserClassName(name) + ".class";
        }
        if (ctx.tokenRegexMap.containsKey(name)) {
            return ParserCodegenUtil.toParserClassName(name) + ".class";
        }
        String tokenClass = ctx.tokenParserMap.get(name);
        if (tokenClass != null) {
            return tokenClass + ".class";
        }
        // @recovery: return recovery wrapper class if the referenced rule has @recovery
        if (ctx.recoveryRules.containsKey(name)) {
            return name + "RecoveryParser.class";
        }
        return name + "Parser.class";
    }

    /**
     * ルール参照名をパーサー生成式（完全な Java 式）に変換する。
     */
    static String resolveParserExpression(ParserGenerator.GenContext ctx, String name) {
        String terminator = ctx.tokenUntilMap.get(name);
        if (terminator != null) {
            return "new org.unlaxer.parser.elementary.WildCardStringTerninatorParser(\""
                + ParserCodegenUtil.escapeString(terminator) + "\")";
        }
        String laPattern = ctx.tokenLookaheadMap.get(name);
        if (laPattern != null) {
            return "new MatchOnly(new WordParser(\"" + ParserCodegenUtil.escapeString(laPattern) + "\"))";
        }
        String nlaPattern = ctx.tokenNegLookaheadMap.get(name);
        if (nlaPattern != null) {
            return "new Not(new WordParser(\"" + ParserCodegenUtil.escapeString(nlaPattern) + "\"))";
        }
        if (ctx.tokenAnySet.contains(name)) {
            return "new org.unlaxer.parser.elementary.WildCardCharacterParser()";
        }
        if (ctx.tokenEofSet.contains(name)) {
            return "new org.unlaxer.parser.elementary.EndOfSourceParser()";
        }
        if (ctx.tokenEmptySet.contains(name)) {
            return "new org.unlaxer.parser.elementary.EmptyParser()";
        }
        String ciWord = ctx.tokenCIMap.get(name);
        if (ciWord != null) {
            return "new org.unlaxer.parser.elementary.IgnoreCaseWordParser(\""
                + ParserCodegenUtil.escapeString(ciWord) + "\")";
        }
        return "Parser.get(" + resolveParserClass(ctx, name) + ")";
    }

    /** 指定名がインライン生成式トークン（クラス参照を持たない）かどうか */
    static boolean isInlineToken(ParserGenerator.GenContext ctx, String name) {
        return ctx.tokenUntilMap.containsKey(name)
            || ctx.tokenLookaheadMap.containsKey(name)
            || ctx.tokenNegLookaheadMap.containsKey(name)
            || ctx.tokenAnySet.contains(name)
            || ctx.tokenEofSet.contains(name)
            || ctx.tokenEmptySet.contains(name)
            || ctx.tokenCIMap.containsKey(name);
        // Note: Regex / Negation / CharRange tokens generate named inner classes — NOT inline
    }

    static String getChainClassName(ParserGenerator.GenContext ctx, String ruleName) {
        boolean useDelimited = ctx.useDelimitedChainByRule.getOrDefault(ruleName, false);
        if (useDelimited && ctx.hasDelimitedChain) {
            return ctx.grammarName + "LazyChain";
        }
        return ctx.grammarName + "PlainLazyChain";
    }

    static String getRuleWhitespaceStyle(RuleDecl rule) {
        return rule.annotations().stream()
            .filter(a -> a instanceof WhitespaceAnnotation)
            .map(a -> (WhitespaceAnnotation) a)
            .reduce((first, second) -> second)
            .map(w -> w.style().orElse("javaStyle").trim().toLowerCase())
            .orElse(null);
    }

    static String getRuleInterleaveProfile(RuleDecl rule) {
        return rule.annotations().stream()
            .filter(a -> a instanceof InterleaveAnnotation)
            .map(a -> (InterleaveAnnotation) a)
            .map(InterleaveAnnotation::profile)
            .reduce((first, second) -> second)
            .map(v -> v.trim().toLowerCase())
            .orElse(null);
    }

    static ParserGenerator.RightAssocShape getRightAssocShape(RuleDecl rule) {
        boolean rightAssoc = rule.annotations().stream().anyMatch(a -> a instanceof RightAssocAnnotation);
        if (!rightAssoc) {
            return null;
        }
        SequenceBody seq = getSingleSequenceFrom(rule.body());
        if (seq == null || seq.elements().size() != 2) {
            return null;
        }
        AtomicElement base = seq.elements().get(0).element();
        AtomicElement second = seq.elements().get(1).element();
        if (!(second instanceof RepeatElement repeat)) {
            return null;
        }
        SequenceBody repeatSeq = getSingleSequenceFrom(repeat.body());
        if (repeatSeq == null || repeatSeq.elements().size() != 2) {
            return null;
        }
        AtomicElement op = repeatSeq.elements().get(0).element();
        AtomicElement right = repeatSeq.elements().get(1).element();
        if (!(right instanceof RuleRefElement rightRef) || !rule.name().equals(rightRef.name())) {
            // Canonical right-assoc shape only: Base { Op Self }.
            return null;
        }
        return new ParserGenerator.RightAssocShape(base, op);
    }

    static SequenceBody getSingleSequenceFrom(RuleBody body) {
        return switch (body) {
            case SequenceBody seq -> seq;
            case ChoiceBody choice when choice.alternatives().size() == 1 -> choice.alternatives().get(0);
            default -> null;
        };
    }

    /**
     * @declares(symbol=captureName) に対して、そのキャプチャ要素のパーサークラス式を返す。
     */
    static String findCaptureParserClass(ParserGenerator.GenContext ctx, RuleDecl rule, String captureName) {
        RuleBody body = rule.body();
        if (!(body instanceof ChoiceBody choice)) return null;
        for (SequenceBody seq : choice.alternatives()) {
            for (AnnotatedElement ae : seq.elements()) {
                if (ae.captureName().isPresent() && captureName.equals(ae.captureName().get())) {
                    if (ae.element() instanceof RuleRefElement ref) {
                        String parserClass = resolveParserClass(ctx, ref.name());
                        return parserClass != null ? parserClass : null;
                    }
                }
            }
        }
        return null;
    }
}
