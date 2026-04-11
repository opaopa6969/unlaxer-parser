package org.unlaxer.dsl.codegen;

import org.unlaxer.dsl.bootstrap.UBNFAST.AtomicElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.BlockSettingValue;
import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.bootstrap.UBNFAST.RootAnnotation;
import org.unlaxer.dsl.bootstrap.UBNFAST.RuleBody;
import org.unlaxer.dsl.bootstrap.UBNFAST.RuleDecl;
import org.unlaxer.dsl.bootstrap.UBNFAST.TokenDecl;
import org.unlaxer.RecursiveMode;

import java.util.ArrayList;
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

    record RightAssocShape(AtomicElement base, AtomicElement op) {}

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
        final Map<String, List<String>> helpers = new LinkedHashMap<>(); // rule -> helper codes
        final Map<String, Boolean> useDelimitedChainByRule = new LinkedHashMap<>();
        boolean hasDelimitedChain = false;
        final Map<String, int[]> helperCounters = new LinkedHashMap<>(); // rule -> [repeat,opt,group,sep]
        boolean needsCPPComment = false;
        final List<String> delimitorClasses = new ArrayList<>();

        GenContext(GrammarDecl grammar) {
            this.grammar = grammar;
            this.grammarName = grammar.name();
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
                switch (token) {
                    case TokenDecl.Simple s              -> tokenParserMap.put(s.name(), s.parserClass());
                    case TokenDecl.Until u               -> tokenUntilMap.put(u.name(), u.terminator());
                    case TokenDecl.Negation n            -> tokenNegationMap.put(n.name(), n.excludedChars());
                    case TokenDecl.Lookahead la          -> tokenLookaheadMap.put(la.name(), la.pattern());
                    case TokenDecl.NegativeLookahead nla -> tokenNegLookaheadMap.put(nla.name(), nla.pattern());
                    case TokenDecl.Any a                 -> tokenAnySet.add(a.name());
                    case TokenDecl.Eof e                 -> tokenEofSet.add(e.name());
                    case TokenDecl.Empty em              -> tokenEmptySet.add(em.name());
                    case TokenDecl.CharRange cr          -> tokenCharRangeMap.put(cr.name(), new int[]{cr.min(), cr.max()});
                    case TokenDecl.CaseInsensitive ci    -> tokenCIMap.put(ci.name(), ci.word());
                    case TokenDecl.Regex rx              -> tokenRegexMap.put(rx.name(), rx.pattern());
                }
            }
            this.ruleNames = grammar.rules().stream()
                .map(RuleDecl::name)
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

        int[] snapshotCounters(String ruleName) {
            int[] c = helperCounters.computeIfAbsent(ruleName, k -> new int[]{0, 0, 0, 0});
            return new int[]{c[0], c[1], c[2], c[3]};
        }

        void restoreCounters(String ruleName, int[] snapshot) {
            int[] c = helperCounters.computeIfAbsent(ruleName, k -> new int[]{0, 0, 0, 0});
            c[0] = snapshot[0]; c[1] = snapshot[1]; c[2] = snapshot[2]; c[3] = snapshot[3];
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

        // Phase 1: 全ルールのヘルパーを事前収集
        for (RuleDecl rule : grammar.rules()) {
            ctx.resetCounters(rule.name());
            ParserRuleEmitter.collectHelpers(ctx, rule);
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
        sb.append("import org.unlaxer.reducer.TagBasedReducer.NodeKind;\n");
        sb.append("import org.unlaxer.util.cache.SupplierBoundCache;\n");
        for (String tokenImport : ParserTokenEmitter.resolveTokenImports(grammar)) {
            sb.append(tokenImport).append("\n");
        }
        sb.append("\n");

        // クラス宣言
        sb.append("public class ").append(className).append(" {\n\n");
        sb.append(ParserMetadataEmitter.generatePrecedenceConstants(grammar));
        sb.append(ParserMetadataEmitter.generateOperatorMetadata(grammar));
        sb.append(ParserMetadataEmitter.generateAdvancedAnnotationMetadata(grammar));

        // チェーンクラス
        sb.append(generatePlainChainClass(ctx));
        if (ctx.hasDelimitedChain) {
            sb.append(generateDelimitorClass(ctx));
            sb.append(generateDelimitedChainClass(ctx));
        }

        // NEGATION / CHAR_RANGE / REGEX トークン用の生成内部クラス
        sb.append(ParserTokenEmitter.generateNegationClasses(ctx));
        sb.append(ParserTokenEmitter.generateCharRangeClasses(ctx));
        sb.append(ParserTokenEmitter.generateRegexClasses(ctx));

        // Phase 2: 各ルールのヘルパー + ルールクラスを出力
        for (RuleDecl rule : grammar.rules()) {
            ctx.resetCounters(rule.name());
            List<String> ruleHelpers = ctx.helpers.getOrDefault(rule.name(), List.of());
            for (String helper : ruleHelpers) {
                sb.append(helper);
            }
            sb.append(ParserRuleEmitter.generateRuleClass(ctx, rule));
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

        boolean hasGlobalWhitespace = grammar.settings().stream()
            .anyMatch(s -> "whitespace".equals(s.key()));

        boolean hasGlobalComment = grammar.settings().stream()
            .anyMatch(s -> "comment".equals(s.key()) && s.value() instanceof BlockSettingValue bv
                && bv.entries().stream().anyMatch(kv -> "line".equals(kv.key())));

        boolean anyRuleRequestsDelimited = grammar.rules().stream()
            .map(ParserRuleEmitter::getRuleWhitespaceStyle)
            .anyMatch(style -> style != null && !"none".equals(style));
        boolean anyRuleInterleaveComments = grammar.rules().stream()
            .map(ParserRuleEmitter::getRuleInterleaveProfile)
            .anyMatch(profile -> "commentsandspaces".equals(profile));

        ctx.hasDelimitedChain = hasGlobalWhitespace || hasGlobalComment || anyRuleRequestsDelimited || anyRuleInterleaveComments;

        if (ctx.hasDelimitedChain && (hasGlobalWhitespace || anyRuleRequestsDelimited || anyRuleInterleaveComments)) {
            ctx.delimitorClasses.add("SpaceParser.class");
        }
        if (hasGlobalComment || anyRuleInterleaveComments) {
            ctx.needsCPPComment = true;
            ctx.delimitorClasses.add("CPPComment.class");
        }

        for (RuleDecl rule : grammar.rules()) {
            String style = ParserRuleEmitter.getRuleWhitespaceStyle(rule); // null => inherit global
            String interleaveProfile = ParserRuleEmitter.getRuleInterleaveProfile(rule);
            boolean useDelimited = style == null
                ? (hasGlobalWhitespace || hasGlobalComment || "commentsandspaces".equals(interleaveProfile))
                : !"none".equals(style);
            ctx.useDelimitedChainByRule.put(rule.name(), useDelimited);
        }

        return ctx;
    }

    // =========================================================================
    // デリミタ・基底チェーン生成
    // =========================================================================

    private String generateDelimitorClass(GenContext ctx) {
        String gn = ctx.grammarName;
        String delimitorName = gn + "SpaceDelimitor";
        StringBuilder sb = new StringBuilder();

        sb.append("    // --- Whitespace Delimitor ---\n");
        sb.append("    public static class ").append(delimitorName).append(" extends LazyZeroOrMore {\n");
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
        sb.append("    public static abstract class ").append(chainName).append(" extends LazyChain {\n");
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
        sb.append("    public static abstract class ").append(chainName).append(" extends LazyChain {\n");
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
