package org.unlaxer.dsl.codegen;

import org.unlaxer.dsl.bootstrap.UBNFAST.AtomicElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.BlockSettingValue;
import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.bootstrap.UBNFAST.StringSettingValue;
import org.unlaxer.dsl.bootstrap.UBNFAST.RootAnnotation;
import org.unlaxer.dsl.bootstrap.UBNFAST.RuleBody;
import org.unlaxer.dsl.bootstrap.UBNFAST.RuleDecl;
import org.unlaxer.dsl.bootstrap.UBNFAST.TokenDecl;
import org.unlaxer.RecursiveMode;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * GrammarDecl から XxxParsers.java を生成する。
 *
 * <p>各ルールに対応するパーサークラスと、スペースデリミタを自動挿入する
 * 基底チェーンクラスを生成する。</p>
 */
public class ParserGenerator implements CodeGenerator {

    record RightAssocShape(AtomicElement base, AtomicElement op, AtomicElement right) {}

    // =========================================================================
    // 内部型
    // =========================================================================

    /** 生成コンテキスト。grammar 全体の情報とヘルパー状態を保持する。 */
    static class GenContext {
        final GrammarDecl grammar;
        final String grammarName;
        final Map<String, String> tokenParserMap;       // token name -> parser class name (Simple tokens only)
        final Map<String, String> tokenUntilMap;        // token name -> terminator string (Until tokens only)
        final Map<String, String> tokenNegationMap;     // token name -> excluded chars (Negation tokens only)
        final Map<String, String> tokenLookaheadMap;    // token name -> pattern (Lookahead tokens only)
        final Map<String, String> tokenNegLookaheadMap; // token name -> pattern (NegativeLookahead tokens only)
        final Set<String> tokenAnySet;                  // token names backed by ANY
        final Set<String> tokenEofSet;                  // token names backed by EOF
        final Set<String> tokenEmptySet;                // token names backed by EMPTY
        final Map<String, int[]> tokenCharRangeMap;     // token name -> [min char, max char]
        final Map<String, String> tokenCIMap;           // token name -> word (CaseInsensitive)
        final Map<String, String> tokenRegexMap;        // token name -> regex pattern (Regex)
        final Set<String> ruleNames;
        final Set<String> explicitlySafeMemoTokens;
        final Map<String, List<String>> helpers = new LinkedHashMap<>(); // rule -> helper codes
        final Map<String, CaptureBindingPlan> captureBindings = new LinkedHashMap<>();
        final Map<String, Boolean> useDelimitedChainByRule = new LinkedHashMap<>();
        final Map<String, Boolean> safeFailureMemoByRule = new LinkedHashMap<>();
        final Map<String, Boolean> safeSuccessMemoByRule = new LinkedHashMap<>();
        boolean hasDelimitedChain = false;
        final Map<String, int[]> helperCounters = new LinkedHashMap<>(); // rule -> [repeat,opt,group,sep]
        // A helper belongs to a grammar site, not to an emission traversal or structurally equal element.
        final Map<String, Map<AtomicElement, String>> helperNames = new LinkedHashMap<>();
        boolean needsCPPComment = false;
        boolean needsBlockComment = false;
        final List<String> delimitorClasses = new ArrayList<>();
        /** rule name -> RecoveryAnnotation (for rules that have @recovery) */
        final Map<String, org.unlaxer.dsl.bootstrap.UBNFAST.RecoveryAnnotation> recoveryRules = new LinkedHashMap<>();

        GenContext(GrammarDecl grammar) {
            this.grammar = grammar;
            this.grammarName = grammar.name();
            SemanticCardinality semantics = new SemanticCardinality(grammar);
            boolean semanticCollections = semantics.enabled();
            grammar.rules().forEach(rule -> captureBindings.put(rule.name(), semanticCollections
                ? new CaptureBindingPlan(rule, semantics) : new CaptureBindingPlan(rule)));
            this.tokenParserMap = new LinkedHashMap<>();
            this.tokenUntilMap = new LinkedHashMap<>();
            this.tokenNegationMap = new LinkedHashMap<>();
            this.tokenLookaheadMap = new LinkedHashMap<>();
            this.tokenNegLookaheadMap = new LinkedHashMap<>();
            this.tokenAnySet = new LinkedHashSet<>();
            this.tokenEofSet = new LinkedHashSet<>();
            this.tokenEmptySet = new LinkedHashSet<>();
            this.tokenCharRangeMap = new LinkedHashMap<>();
            this.tokenCIMap = new LinkedHashMap<>();
            this.tokenRegexMap = new LinkedHashMap<>();
            for (TokenDecl token : grammar.tokens()) {
                if (token instanceof TokenDecl.Simple s) {
                    tokenParserMap.put(s.name(), s.parserClass());
                } else if (token instanceof TokenDecl.Until u) {
                    tokenUntilMap.put(u.name(), u.terminator());
                } else if (token instanceof TokenDecl.Negation n) {
                    tokenNegationMap.put(n.name(), n.excludedChars());
                } else if (token instanceof TokenDecl.Lookahead la) {
                    tokenLookaheadMap.put(la.name(), la.pattern());
                } else if (token instanceof TokenDecl.NegativeLookahead nla) {
                    tokenNegLookaheadMap.put(nla.name(), nla.pattern());
                } else if (token instanceof TokenDecl.Any a) {
                    tokenAnySet.add(a.name());
                } else if (token instanceof TokenDecl.Eof e) {
                    tokenEofSet.add(e.name());
                } else if (token instanceof TokenDecl.Empty em) {
                    tokenEmptySet.add(em.name());
                } else if (token instanceof TokenDecl.CharRange cr) {
                    tokenCharRangeMap.put(cr.name(), new int[]{cr.min(), cr.max()});
                } else if (token instanceof TokenDecl.CaseInsensitive ci) {
                    tokenCIMap.put(ci.name(), ci.word());
                } else if (token instanceof TokenDecl.Regex rx) {
                    tokenRegexMap.put(rx.name(), rx.pattern());
                } else {
                    throw new IllegalStateException("unhandled " + token);
                }
            }
            this.ruleNames = grammar.rules().stream()
                .map(RuleDecl::name)
                .collect(Collectors.toCollection(LinkedHashSet::new));
            this.explicitlySafeMemoTokens = grammar.settings().stream()
                .filter(setting -> "memoSafeToken".equals(setting.key()))
                .map(setting -> setting.value() instanceof StringSettingValue value
                    ? value.value().trim() : "")
                .filter(alias -> !alias.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        }

        void resetCounters(String ruleName) {
            helperCounters.put(ruleName, new int[]{0, 0, 0, 0});
        }

        int nextRepeat(String ruleName) {
            return helperCounters.computeIfAbsent(ruleName, k -> new int[]{0,0,0,0})[0]++;
        }

        int nextOpt(String ruleName) {
            return helperCounters.computeIfAbsent(ruleName, k -> new int[]{0,0,0,0})[1]++;
        }

        int nextGroup(String ruleName) {
            return helperCounters.computeIfAbsent(ruleName, k -> new int[]{0,0,0,0})[2]++;
        }

        int nextSep(String ruleName) {
            return helperCounters.computeIfAbsent(ruleName, k -> new int[]{0,0,0,0})[3]++;
        }

        void registerHelper(String ruleName, AtomicElement element, String name) {
            helperNames.computeIfAbsent(ruleName, k -> new IdentityHashMap<>()).put(element, name);
        }

        String helperName(String ruleName, AtomicElement element) {
            String name = helperNames.getOrDefault(ruleName, Map.of()).get(element);
            if (name == null) {
                throw new IllegalStateException("Helper not analyzed for rule " + ruleName + ": " + element);
            }
            return name;
        }

        void addHelper(String ruleName, String code) {
            helpers.computeIfAbsent(ruleName, k -> new ArrayList<>()).add(code);
        }
    }

    // =========================================================================
    // メイン生成
    // =========================================================================

    @Override
    public GeneratedSource generate(GrammarDecl grammar) {
        String packageName = ParserCodegenUtil.getPackageName(grammar);
        String grammarName = grammar.name();
        String className = grammarName + "Parsers";

        GenContext ctx = createContext(grammar);

        // Phase 1: 全ルールのヘルパーを分析→生成（analyze/emit 分離）
        for (RuleDecl rule : grammar.rules()) {
            ctx.resetCounters(rule.name());
            java.util.List<ParserRuleEmitter.HelperSpec> specs = ParserRuleEmitter.analyzeHelpers(ctx, rule);
            ParserRuleEmitter.emitAnalyzedHelpers(ctx, rule.name(), specs);
        }

        StringBuilder sb = new StringBuilder();

        // パッケージ宣言
        sb.append("package ").append(packageName).append(";\n\n");

        // インポート
        sb.append("import java.util.function.Supplier;\n");
        sb.append("import org.unlaxer.RecursiveMode;\n");
        sb.append("import org.unlaxer.parser.Parser;\n");
        sb.append("import org.unlaxer.parser.Parsers;\n");
        sb.append("import org.unlaxer.parser.combinator.*;\n");
        sb.append("import org.unlaxer.parser.elementary.WordParser;\n");
        sb.append("import org.unlaxer.parser.posix.SpaceParser;\n");
        if (ctx.needsCPPComment) {
            sb.append("import org.unlaxer.parser.clang.CPPComment;\n");
        }
        if (ctx.needsBlockComment) {
            sb.append("import org.unlaxer.parser.clang.BlockComment;\n");
        }
        sb.append("import org.unlaxer.reducer.TagBasedReducer.NodeKind;\n");
        sb.append("import org.unlaxer.util.cache.SupplierBoundCache;\n");
        for (String tokenImport : ParserTokenEmitter.resolveTokenImports(grammar)) {
            sb.append(tokenImport).append("\n");
        }
        sb.append("\n");
        sb.append(CodeGenerator.generatedAnnotation("org.unlaxer.dsl.codegen.ParserGenerator"));

        // クラス宣言
        sb.append("public class ").append(className).append(" {\n\n");
        sb.append(ParserMetadataEmitter.generatePrecedenceConstants(grammar));
        sb.append(ParserMetadataEmitter.generateOperatorMetadata(grammar));
        sb.append(ParserMetadataEmitter.generateAdvancedAnnotationMetadata(grammar));
        sb.append("""
                // Capture metadata belongs to grammar sites, not shared rule-parser instances.
                public interface __CaptureBinding {
                    java.util.List<String> captureBindings();
                }
                public static final class __CaptureSite extends LazyChain implements __CaptureBinding, org.unlaxer.context.DiagnosticsAgnostic {
                    private static final long serialVersionUID = 1L;
                    private final java.util.List<String> bindings;
                    private final Parser child;
                    public __CaptureSite(Parser child, String... bindings) {
                        this.child = child;
                        this.bindings = java.util.List.of(bindings);
                    }
                    @Override public java.util.List<String> captureBindings() { return bindings; }
                    @Override public Parsers getLazyParsers() { return new Parsers(child); }
                    @Override public java.util.Optional<RecursiveMode> getNotAstNodeSpecifier() {
                        return java.util.Optional.empty();
                    }
                }
                private static java.util.stream.Stream<org.unlaxer.Token> __semanticChildren(org.unlaxer.Token token) {
                    return token.filteredChildren.stream().flatMap(child -> child.parser instanceof __CaptureSite
                        ? __semanticChildren(child) : java.util.stream.Stream.of(child));
                }

            """);

        sb.append(ParserScopeEmitter.helpers(grammar));

        // チェーンクラス
        sb.append(generatePlainChainClass(ctx));
        if (ctx.hasDelimitedChain) {
            sb.append(generateDelimitorClass(ctx));
            sb.append(generateDelimitedChainClass(ctx));
        }

        // Simple / NEGATION / CHAR_RANGE / REGEX トークン用の生成内部クラス
        sb.append(ParserTokenEmitter.generateSimpleTokenWrappers(ctx));
        sb.append(ParserTokenEmitter.generateNegationClasses(ctx));
        sb.append(ParserTokenEmitter.generateCharRangeClasses(ctx));
        sb.append(ParserTokenEmitter.generateRegexClasses(ctx));

        // Phase 2: 各ルールのヘルパー + ルールクラスを出力
        for (RuleDecl rule : grammar.rules()) {
            List<String> ruleHelpers = ctx.helpers.getOrDefault(rule.name(), List.of());
            for (String helper : ruleHelpers) {
                sb.append(helper);
            }
            sb.append(ParserRuleEmitter.generateRuleClass(ctx, rule));
            // @recovery: generate recovery wrapper class after the rule class
            ParserRuleEmitter.findRecoveryAnnotation(rule)
                .ifPresent(ra -> sb.append(ParserRuleEmitter.generateRecoveryWrapper(ctx, rule, ra)));
        }

        // ファクトリメソッド
        String rootRuleName = findRootRuleName(grammar);
        sb.append("    public static Parser getRootParser() {\n");
        sb.append("        return Parser.get(").append(rootRuleName).append("Parser.class);\n");
        sb.append("    }\n");

        sb.append("}\n");

        return new GeneratedSource(packageName, className, sb.toString());
    }

    // =========================================================================
    // コンテキスト初期化
    // =========================================================================

    private GenContext createContext(GrammarDecl grammar) {
        GenContext ctx = new GenContext(grammar);

        analyzeSafeFailureMemoization(ctx);

        boolean hasGlobalWhitespace = grammar.settings().stream()
            .anyMatch(s -> "whitespace".equals(s.key()) && s.value() instanceof StringSettingValue value
                && "javaStyle".equalsIgnoreCase(value.value().trim()));

        boolean hasGlobalComment = grammar.settings().stream()
            .anyMatch(s -> "comment".equals(s.key()) && s.value() instanceof BlockSettingValue bv
                && bv.entries().stream().anyMatch(kv -> "line".equals(kv.key())));

        boolean anyRuleRequestsDelimited = grammar.rules().stream()
            .map(ParserRuleEmitter::getRuleWhitespaceStyle)
            .anyMatch(style -> style != null && !"none".equals(style));
        boolean anyRuleInterleaveDelimited = grammar.rules().stream()
            .map(ParserRuleEmitter::getRuleInterleaveProfile)
            .anyMatch(profile -> "javastyle".equals(profile) || "commentsandspaces".equals(profile));

        ctx.hasDelimitedChain = hasGlobalWhitespace || hasGlobalComment
            || anyRuleRequestsDelimited || anyRuleInterleaveDelimited;

        if (ctx.hasDelimitedChain && (hasGlobalWhitespace || anyRuleRequestsDelimited || anyRuleInterleaveDelimited)) {
            ctx.delimitorClasses.add("SpaceParser.class");
        }
        if (hasGlobalComment || hasGlobalWhitespace || anyRuleRequestsDelimited || anyRuleInterleaveDelimited) {
            ctx.needsCPPComment = true;
            ctx.delimitorClasses.add("CPPComment.class");
        }
        if (hasGlobalWhitespace || anyRuleRequestsDelimited || anyRuleInterleaveDelimited) {
            ctx.needsBlockComment = true;
            ctx.delimitorClasses.add("BlockComment.class");
        }

        for (RuleDecl rule : grammar.rules()) {
            String style = ParserRuleEmitter.getRuleWhitespaceStyle(rule); // null => inherit global
            String interleaveProfile = ParserRuleEmitter.getRuleInterleaveProfile(rule);
            boolean useDelimited = style == null
                ? (hasGlobalWhitespace || hasGlobalComment
                    || "javastyle".equals(interleaveProfile)
                    || "commentsandspaces".equals(interleaveProfile))
                : !"none".equals(style);
            ctx.useDelimitedChainByRule.put(rule.name(), useDelimited);
        }

        // Collect @recovery annotations for rules
        for (RuleDecl rule : grammar.rules()) {
            ParserRuleEmitter.findRecoveryAnnotation(rule)
                .ifPresent(ra -> ctx.recoveryRules.put(rule.name(), ra));
        }

        return ctx;
    }

    /**
     * Fail-closed, transitive rule analysis for failure memoization. Simple token classes are
     * treated as custom/unknown even when their names resemble built-ins, unless their token
     * alias is explicitly listed by {@code @memoSafeToken}. Built-in scope annotations use the
     * versioned {@code ScopeStore} contract and therefore do not taint a rule; unknown/custom
     * state remains fail-closed through its token dependency.
     */
    private void analyzeSafeFailureMemoization(GenContext ctx) {
        Map<String, Set<String>> dependencies = new LinkedHashMap<>();
        for (RuleDecl rule : ctx.grammar.rules()) {
            Set<String> refs = new LinkedHashSet<>();
            boolean locallySafe = collectMemoDependencies(ctx, rule.body(), refs);
            dependencies.put(rule.name(), refs);
            ctx.safeFailureMemoByRule.put(rule.name(), locallySafe);
            // Capture metadata itself is immutable. Scope capture actions are emitted by the
            // listener on @declares/@backref, and must never be skipped by a success hit.
            ctx.safeSuccessMemoByRule.put(rule.name(), locallySafe
                && !ParserRuleEmitter.needsTransactionListener(rule)
                // References to this rule select a recovery wrapper with additional effects.
                && ParserRuleEmitter.findRecoveryAnnotation(rule).isEmpty());
        }
        boolean changed;
        do {
            changed = false;
            for (RuleDecl rule : ctx.grammar.rules()) {
                for (Map<String, Boolean> safety : List.of(
                        ctx.safeFailureMemoByRule, ctx.safeSuccessMemoByRule)) {
                    if (!safety.get(rule.name())) continue;
                    boolean safe = dependencies.get(rule.name()).stream()
                        .allMatch(ref -> Boolean.TRUE.equals(safety.get(ref)));
                    if (!safe) {
                        safety.put(rule.name(), false);
                        changed = true;
                    }
                }
            }
        } while (changed);
    }

    /** Helpers have no listeners of their own; prove their actual body, not their enclosing rule. */
    static boolean isSafeSuccessBody(GenContext ctx, RuleBody body) {
        Set<String> refs = new LinkedHashSet<>();
        return collectMemoDependencies(ctx, body, refs) && refs.stream()
            .allMatch(ref -> Boolean.TRUE.equals(ctx.safeSuccessMemoByRule.get(ref)));
    }

    static boolean isSafeSuccessElements(GenContext ctx, AtomicElement... elements) {
        Set<String> refs = new LinkedHashSet<>();
        for (AtomicElement element : elements) {
            if (!collectMemoDependencies(ctx, element, refs)) return false;
        }
        return refs.stream().allMatch(ref -> Boolean.TRUE.equals(ctx.safeSuccessMemoByRule.get(ref)));
    }

    private static boolean collectMemoDependencies(GenContext ctx, RuleBody body, Set<String> refs) {
        if (body instanceof org.unlaxer.dsl.bootstrap.UBNFAST.ChoiceBody choice) {
            return choice.alternatives().stream()
                .allMatch(sequence -> collectMemoDependencies(ctx, sequence, refs));
        }
        if (body instanceof org.unlaxer.dsl.bootstrap.UBNFAST.SequenceBody sequence) {
            return collectMemoDependencies(ctx, sequence, refs);
        }
        throw new IllegalStateException("unhandled " + body);
    }

    private static boolean collectMemoDependencies(GenContext ctx,
            org.unlaxer.dsl.bootstrap.UBNFAST.SequenceBody sequence, Set<String> refs) {
        boolean safe = true;
        for (var annotated : sequence.elements()) {
            safe &= collectMemoDependencies(ctx, annotated.element(), refs);
        }
        return safe;
    }

    private static boolean collectMemoDependencies(GenContext ctx, AtomicElement element, Set<String> refs) {
        if (element instanceof org.unlaxer.dsl.bootstrap.UBNFAST.RuleRefElement ref) {
            if (ref.namespace().isPresent()) return false;
            if (ctx.ruleNames.contains(ref.name())) {
                refs.add(ref.name());
                return true;
            }
            return ctx.grammar.tokens().stream()
                .filter(token -> token.name().equals(ref.name()))
                .findFirst().map(token -> !(token instanceof TokenDecl.Simple)
                    || ctx.explicitlySafeMemoTokens.contains(ref.name())).orElse(false);
        }
        if (element instanceof org.unlaxer.dsl.bootstrap.UBNFAST.TerminalElement ignored) {
            return true;
        }
        if (element instanceof org.unlaxer.dsl.bootstrap.UBNFAST.RepeatElement repeat) {
            return collectMemoDependencies(ctx, repeat.body(), refs);
        }
        if (element instanceof org.unlaxer.dsl.bootstrap.UBNFAST.OptionalElement optional) {
            return collectMemoDependencies(ctx, optional.body(), refs);
        }
        if (element instanceof org.unlaxer.dsl.bootstrap.UBNFAST.OneOrMoreElement one) {
            return collectMemoDependencies(ctx, one.body(), refs);
        }
        if (element instanceof org.unlaxer.dsl.bootstrap.UBNFAST.BoundedRepeatElement bounded) {
            return collectMemoDependencies(ctx, bounded.body(), refs);
        }
        if (element instanceof org.unlaxer.dsl.bootstrap.UBNFAST.GroupElement group) {
            return collectMemoDependencies(ctx, group.body(), refs);
        }
        if (element instanceof org.unlaxer.dsl.bootstrap.UBNFAST.SeparatedElement separated) {
            return collectMemoDependencies(ctx, separated.element(), refs)
                    && collectMemoDependencies(ctx, separated.separator(), refs);
        }
        if (element instanceof org.unlaxer.dsl.bootstrap.UBNFAST.ErrorElement ignored) {
            return true;
        }
        throw new IllegalStateException("unhandled " + element);
    }

    // =========================================================================
    // デリミタ・基底チェーン生成
    // =========================================================================

    private String generateDelimitorClass(GenContext ctx) {
        String gn = ctx.grammarName;
        String delimitorName = gn + "SpaceDelimitor";
        StringBuilder sb = new StringBuilder();

        sb.append("    // --- Whitespace Delimitor ---\n");
        sb.append("    public static class ").append(delimitorName).append(" extends LazyZeroOrMore implements org.unlaxer.context.DiagnosticsAgnostic, org.unlaxer.context.SafeSuccessMemoizable {\n");
        sb.append("        private static final long serialVersionUID = 1L;\n");
        sb.append("        @Override\n");
        sb.append("        public Supplier<Parser> getLazyParser() {\n");

        if (ctx.delimitorClasses.isEmpty()) {
            sb.append("            return new SupplierBoundCache<>(() -> Parser.get(SpaceParser.class));\n");
        } else if (ctx.delimitorClasses.size() == 1) {
            sb.append("            return new SupplierBoundCache<>(() -> Parser.get(")
              .append(ctx.delimitorClasses.get(0)).append("));\n");
        } else {
            String args = String.join(", ", ctx.delimitorClasses);
            sb.append("            return new SupplierBoundCache<>(() -> new Choice(").append(args).append("));\n");
        }

        sb.append("        }\n");
        sb.append("        @Override\n");
        sb.append("        public java.util.Optional<Parser> getLazyTerminatorParser() { return java.util.Optional.empty(); }\n");
        sb.append("    }\n\n");

        return sb.toString();
    }

    private String generatePlainChainClass(GenContext ctx) {
        String gn = ctx.grammarName;
        String chainName = gn + "PlainLazyChain";
        StringBuilder sb = new StringBuilder();

        sb.append("    // --- Base Chain (No Auto Delimiter) ---\n");
        sb.append("    public static abstract class ").append(chainName).append(" extends LazyChain implements org.unlaxer.context.DiagnosticsAgnostic {\n");
        sb.append("        private static final long serialVersionUID = 1L;\n");
        sb.append("        @Override\n");
        sb.append("        public void prepareChildren(Parsers c) {\n");
        sb.append("            if (!c.isEmpty()) return;\n");
        sb.append("            for (Parser p : getLazyParsers()) { c.add(p); }\n");
        sb.append("        }\n");
        sb.append("        public abstract Parsers getLazyParsers();\n");
        sb.append("        @Override\n");
        sb.append("        public java.util.Optional<RecursiveMode> getNotAstNodeSpecifier() { return java.util.Optional.empty(); }\n");
        sb.append("    }\n\n");

        return sb.toString();
    }

    private String generateDelimitedChainClass(GenContext ctx) {
        String gn = ctx.grammarName;
        String delimitorName = gn + "SpaceDelimitor";
        String chainName = gn + "LazyChain";
        StringBuilder sb = new StringBuilder();

        sb.append("    // --- Base Chain (Auto Delimiter) ---\n");
        sb.append("    public static abstract class ").append(chainName).append(" extends LazyChain implements org.unlaxer.context.DiagnosticsAgnostic {\n");
        sb.append("        private static final long serialVersionUID = 1L;\n");
        sb.append("        private static final ").append(delimitorName).append(" SPACE = createSpace();\n");
        sb.append("        private static ").append(delimitorName).append(" createSpace() {\n");
        sb.append("            ").append(delimitorName).append(" s = new ").append(delimitorName).append("();\n");
        sb.append("            s.addTag(NodeKind.notNode.getTag());\n");
        sb.append("            return s;\n");
        sb.append("        }\n");
        sb.append("        @Override\n");
        sb.append("        public void prepareChildren(Parsers c) {\n");
        sb.append("            if (!c.isEmpty()) return;\n");
        sb.append("            c.add(SPACE);\n");
        sb.append("            for (Parser p : getLazyParsers()) { c.add(p); c.add(SPACE); }\n");
        sb.append("        }\n");
        sb.append("        public abstract Parsers getLazyParsers();\n");
        sb.append("        @Override\n");
        sb.append("        public java.util.Optional<RecursiveMode> getNotAstNodeSpecifier() { return java.util.Optional.empty(); }\n");
        sb.append("    }\n\n");

        return sb.toString();
    }

    /** ルートルール名を返す（@root アノテーション付き） */
    private String findRootRuleName(GrammarDecl grammar) {
        return grammar.rules().stream()
            .filter(r -> r.annotations().stream().anyMatch(a -> a instanceof RootAnnotation))
            .map(RuleDecl::name)
            .findFirst()
            .orElse(grammar.rules().isEmpty() ? "Root" : grammar.rules().get(0).name());
    }
}
