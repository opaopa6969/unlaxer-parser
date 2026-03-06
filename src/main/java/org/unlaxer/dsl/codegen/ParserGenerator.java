package org.unlaxer.dsl.codegen;

import org.unlaxer.dsl.bootstrap.UBNFAST.AnnotatedElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.AtomicElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.BackrefAnnotation;
import org.unlaxer.dsl.bootstrap.UBNFAST.BlockSettingValue;
import org.unlaxer.dsl.bootstrap.UBNFAST.ChoiceBody;
import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.bootstrap.UBNFAST.GroupElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.InterleaveAnnotation;
import org.unlaxer.dsl.bootstrap.UBNFAST.LeftAssocAnnotation;
import org.unlaxer.dsl.bootstrap.UBNFAST.OneOrMoreElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.OptionalElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.PrecedenceAnnotation;
import org.unlaxer.dsl.bootstrap.UBNFAST.RepeatElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.RightAssocAnnotation;
import org.unlaxer.dsl.bootstrap.UBNFAST.RootAnnotation;
import org.unlaxer.dsl.bootstrap.UBNFAST.RuleBody;
import org.unlaxer.dsl.bootstrap.UBNFAST.RuleDecl;
import org.unlaxer.dsl.bootstrap.UBNFAST.RuleRefElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.ScopeTreeAnnotation;
import org.unlaxer.dsl.bootstrap.UBNFAST.SequenceBody;
import org.unlaxer.dsl.bootstrap.UBNFAST.StringSettingValue;
import org.unlaxer.dsl.bootstrap.UBNFAST.TerminalElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.TokenDecl;
import org.unlaxer.dsl.bootstrap.UBNFAST.WhitespaceAnnotation;

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

    private record RightAssocShape(AtomicElement base, AtomicElement op) {}

    // =========================================================================
    // 内部型
    // =========================================================================

    /** 生成コンテキスト。grammar 全体の情報とヘルパー状態を保持する。 */
    private static class GenContext {
        final GrammarDecl grammar;
        final String grammarName;
        final Map<String, String> tokenParserMap;       // token name -> parser class name (Simple tokens only)
        final Map<String, String> tokenUntilMap;        // token name -> terminator string (Until tokens only)
        final Map<String, String> tokenNegationMap;     // token name -> excluded chars (Negation tokens only)
        final Map<String, String> tokenLookaheadMap;    // token name -> pattern (Lookahead tokens only)
        final Map<String, String> tokenNegLookaheadMap; // token name -> pattern (NegativeLookahead tokens only)
        final Set<String> ruleNames;
        final Map<String, List<String>> helpers = new LinkedHashMap<>(); // rule -> helper codes
        final Map<String, Boolean> useDelimitedChainByRule = new LinkedHashMap<>();
        boolean hasDelimitedChain = false;
        final Map<String, int[]> helperCounters = new LinkedHashMap<>(); // rule -> [repeat,opt,group]
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
            for (TokenDecl token : grammar.tokens()) {
                switch (token) {
                    case TokenDecl.Simple s           -> tokenParserMap.put(s.name(), s.parserClass());
                    case TokenDecl.Until u            -> tokenUntilMap.put(u.name(), u.terminator());
                    case TokenDecl.Negation n         -> tokenNegationMap.put(n.name(), n.excludedChars());
                    case TokenDecl.Lookahead la       -> tokenLookaheadMap.put(la.name(), la.pattern());
                    case TokenDecl.NegativeLookahead nla -> tokenNegLookaheadMap.put(nla.name(), nla.pattern());
                }
            }
            this.ruleNames = grammar.rules().stream()
                .map(RuleDecl::name)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        }

        void resetCounters(String ruleName) {
            helperCounters.put(ruleName, new int[]{0, 0, 0});
        }

        int nextRepeat(String ruleName) {
            return helperCounters.computeIfAbsent(ruleName, k -> new int[]{0,0,0})[0]++;
        }

        int nextOpt(String ruleName) {
            return helperCounters.computeIfAbsent(ruleName, k -> new int[]{0,0,0})[1]++;
        }

        int nextGroup(String ruleName) {
            return helperCounters.computeIfAbsent(ruleName, k -> new int[]{0,0,0})[2]++;
        }

        int[] snapshotCounters(String ruleName) {
            int[] c = helperCounters.computeIfAbsent(ruleName, k -> new int[]{0, 0, 0});
            return new int[]{c[0], c[1], c[2]};
        }

        void restoreCounters(String ruleName, int[] snapshot) {
            int[] c = helperCounters.computeIfAbsent(ruleName, k -> new int[]{0, 0, 0});
            c[0] = snapshot[0]; c[1] = snapshot[1]; c[2] = snapshot[2];
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
        String packageName = getPackageName(grammar);
        String grammarName = grammar.name();
        String className = grammarName + "Parsers";

        GenContext ctx = createContext(grammar);

        // Phase 1: 全ルールのヘルパーを事前収集
        for (RuleDecl rule : grammar.rules()) {
            ctx.resetCounters(rule.name());
            collectHelpers(ctx, rule);
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
        for (String tokenImport : resolveTokenImports(grammar)) {
            sb.append(tokenImport).append("\n");
        }
        sb.append("\n");

        // クラス宣言
        sb.append("public class ").append(className).append(" {\n\n");
        sb.append(generatePrecedenceConstants(grammar));
        sb.append(generateOperatorMetadata(grammar));
        sb.append(generateAdvancedAnnotationMetadata(grammar));

        // チェーンクラス
        sb.append(generatePlainChainClass(ctx));
        if (ctx.hasDelimitedChain) {
            sb.append(generateDelimitorClass(ctx));
            sb.append(generateDelimitedChainClass(ctx));
        }

        // NEGATION トークン用の生成 SingleCharacterParser 内部クラス
        sb.append(generateNegationClasses(ctx));

        // Phase 2: 各ルールのヘルパー + ルールクラスを出力
        for (RuleDecl rule : grammar.rules()) {
            ctx.resetCounters(rule.name());
            List<String> ruleHelpers = ctx.helpers.getOrDefault(rule.name(), List.of());
            for (String helper : ruleHelpers) {
                sb.append(helper);
            }
            sb.append(generateRuleClass(ctx, rule));
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
    // トークンクラスのインポート解決
    // =========================================================================

    /**
     * grammar の token 宣言に含まれるパーサークラス名を既知パッケージで検索し、
     * import 文のリストを返す。見つからないクラスは無視する。
     */
    private List<String> resolveTokenImports(GrammarDecl grammar) {
        Set<String> alreadyImported = Set.of("WordParser", "SpaceParser", "CPPComment");
        String[] candidatePackages = {
            "org.unlaxer.parser.clang",
            "org.unlaxer.parser.elementary",
            "org.unlaxer.parser.posix",
        };
        List<String> imports = new ArrayList<>();
        for (TokenDecl token : grammar.tokens()) {
            if (!(token instanceof TokenDecl.Simple s)) continue; // UNTIL tokens have no class to import
            String parserClass = s.parserClass();
            if (alreadyImported.contains(parserClass) || parserClass.contains(".")) {
                continue;
            }
            for (String pkg : candidatePackages) {
                try {
                    Class.forName(pkg + "." + parserClass);
                    imports.add("import " + pkg + "." + parserClass + ";");
                    break;
                } catch (ClassNotFoundException ignored) {
                    // 次のパッケージを試す
                }
            }
        }
        return imports;
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
            .map(this::getRuleWhitespaceStyle)
            .anyMatch(style -> style != null && !"none".equals(style));
        boolean anyRuleInterleaveComments = grammar.rules().stream()
            .map(this::getRuleInterleaveProfile)
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
            String style = getRuleWhitespaceStyle(rule); // null => inherit global
            String interleaveProfile = getRuleInterleaveProfile(rule);
            boolean useDelimited = style == null
                ? (hasGlobalWhitespace || hasGlobalComment || "commentsandspaces".equals(interleaveProfile))
                : !"none".equals(style);
            ctx.useDelimitedChainByRule.put(rule.name(), useDelimited);
        }

        return ctx;
    }

    private String getRuleWhitespaceStyle(RuleDecl rule) {
        return rule.annotations().stream()
            .filter(a -> a instanceof WhitespaceAnnotation)
            .map(a -> (WhitespaceAnnotation) a)
            .reduce((first, second) -> second)
            .map(w -> w.style().orElse("javaStyle").trim().toLowerCase())
            .orElse(null);
    }

    private String getRuleInterleaveProfile(RuleDecl rule) {
        return rule.annotations().stream()
            .filter(a -> a instanceof InterleaveAnnotation)
            .map(a -> (InterleaveAnnotation) a)
            .map(InterleaveAnnotation::profile)
            .reduce((first, second) -> second)
            .map(v -> v.trim().toLowerCase())
            .orElse(null);
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

    private String getChainClassName(GenContext ctx, String ruleName) {
        boolean useDelimited = ctx.useDelimitedChainByRule.getOrDefault(ruleName, false);
        if (useDelimited && ctx.hasDelimitedChain) {
            return ctx.grammarName + "LazyChain";
        }
        return ctx.grammarName + "PlainLazyChain";
    }

    // =========================================================================
    // ヘルパー収集
    // =========================================================================

    /**
     * NEGATION トークンごとに SingleCharacterParser 内部クラスを生成する。
     * 例: token NOT_QUOTE = NEGATION('"')
     *   → public static class NotQuoteParser extends SingleCharacterParser { ... }
     */
    private String generateNegationClasses(GenContext ctx) {
        if (ctx.tokenNegationMap.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : ctx.tokenNegationMap.entrySet()) {
            String tokenName = e.getKey();
            String excluded = e.getValue();
            String className = toNegationParserName(tokenName);
            sb.append("    // --- NEGATION parser for token ").append(tokenName).append(" ---\n");
            sb.append("    public static class ").append(className)
              .append(" extends org.unlaxer.parser.elementary.SingleCharacterParser {\n");
            sb.append("        private static final long serialVersionUID = 1L;\n");
            sb.append("        private static final String EXCLUDED = \"")
              .append(escapeString(excluded)).append("\";\n");
            sb.append("        @Override\n");
            sb.append("        public boolean isMatch(char target) {\n");
            sb.append("            return EXCLUDED.indexOf(target) < 0;\n");
            sb.append("        }\n");
            sb.append("    }\n\n");
        }
        return sb.toString();
    }

    private void collectHelpers(GenContext ctx, RuleDecl rule) {
        collectHelpersInBody(ctx, rule.name(), rule.body());
    }

    private void collectHelpersInBody(GenContext ctx, String ruleName, RuleBody body) {
        switch (body) {
            case ChoiceBody choice -> {
                for (SequenceBody alt : choice.alternatives()) {
                    collectHelpersInSequence(ctx, ruleName, alt);
                }
            }
            case SequenceBody seq -> collectHelpersInSequence(ctx, ruleName, seq);
        }
    }

    private void collectHelpersInSequence(GenContext ctx, String ruleName, SequenceBody seq) {
        for (AnnotatedElement ae : seq.elements()) {
            collectHelpersInElement(ctx, ruleName, ae.element());
        }
    }

    private void collectHelpersInElement(GenContext ctx, String ruleName, AtomicElement element) {
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
            default -> {} // TerminalElement, RuleRefElement
        }
    }

    /**
     * ヘルパークラスのコードを生成する。
     * body が複数代替 ChoiceBody なら LazyChoice、
     * それ以外なら {GrammarName}LazyChain を継承する。
     */
    private String generateHelperCode(GenContext ctx, String ruleName, String helperName, RuleBody body) {
        boolean isChoice = isMultiChoice(body);
        StringBuilder sb = new StringBuilder();
        String indent = "    ";

        sb.append(indent).append("public static class ").append(helperName);
        if (isChoice) {
            sb.append(" extends LazyChoice {\n");
        } else {
            sb.append(" extends ").append(getChainClassName(ctx, ruleName)).append(" {\n");
        }
        sb.append(indent).append("    private static final long serialVersionUID = 1L;\n");
        sb.append(indent).append("    @Override\n");
        sb.append(indent).append("    public Parsers getLazyParsers() {\n");
        sb.append(indent).append("        return new Parsers(\n");
        sb.append(generateBodyElements(ctx, ruleName, body, indent + "            "));
        sb.append(indent).append("        );\n");
        sb.append(indent).append("    }\n");
        if (isChoice) {
            sb.append(indent).append("    @Override\n");
            sb.append(indent).append("    public java.util.Optional<RecursiveMode> getNotAstNodeSpecifier() { return java.util.Optional.empty(); }\n");
        }
        sb.append(indent).append("}\n\n");

        return sb.toString();
    }

    // =========================================================================
    // ルールクラス生成
    // =========================================================================

    private String generateRuleClass(GenContext ctx, RuleDecl rule) {
        String ruleName = rule.name();
        String className = ruleName + "Parser";
        RightAssocShape rightAssocShape = getRightAssocShape(rule);
        boolean isChoice = rightAssocShape != null || isMultiChoice(rule.body());

        StringBuilder sb = new StringBuilder();
        String indent = "    ";

        sb.append(indent).append("public static class ").append(className);
        if (isChoice) {
            sb.append(" extends LazyChoice {\n");
        } else {
            sb.append(" extends ").append(getChainClassName(ctx, ruleName)).append(" {\n");
        }
        sb.append(indent).append("    private static final long serialVersionUID = 1L;\n");
        sb.append(indent).append("    @Override\n");
        sb.append(indent).append("    public Parsers getLazyParsers() {\n");
        sb.append(indent).append("        return new Parsers(\n");
        if (rightAssocShape != null) {
            sb.append(generateRightAssocElements(ctx, ruleName, className, rightAssocShape, indent + "            "));
        } else {
            sb.append(generateBodyElements(ctx, ruleName, rule.body(), indent + "            "));
        }
        sb.append(indent).append("        );\n");
        sb.append(indent).append("    }\n");
        if (isChoice) {
            sb.append(indent).append("    @Override\n");
            sb.append(indent).append("    public java.util.Optional<RecursiveMode> getNotAstNodeSpecifier() { return java.util.Optional.empty(); }\n");
        }
        sb.append(indent).append("}\n\n");

        return sb.toString();
    }

    private String generateRightAssocElements(
        GenContext ctx,
        String ruleName,
        String className,
        RightAssocShape shape,
        String indent
    ) {
        String baseCode = generateElementCode(ctx, ruleName, shape.base());
        String opCode = generateElementCode(ctx, ruleName, shape.op());
        String chainClass = getChainClassName(ctx, ruleName);
        StringBuilder sb = new StringBuilder();

        sb.append(indent).append("new ").append(chainClass).append("() {\n");
        sb.append(indent).append("    private static final long serialVersionUID = 1L;\n");
        sb.append(indent).append("    @Override\n");
        sb.append(indent).append("    public Parsers getLazyParsers() {\n");
        sb.append(indent).append("        return new Parsers(\n");
        sb.append(indent).append("            ").append(baseCode).append(",\n");
        sb.append(indent).append("            ").append(opCode).append(",\n");
        sb.append(indent).append("            Parser.get(").append(className).append(".class)\n");
        sb.append(indent).append("        );\n");
        sb.append(indent).append("    }\n");
        sb.append(indent).append("},\n");
        sb.append(indent).append(baseCode).append("\n");

        return sb.toString();
    }

    // =========================================================================
    // ボディ要素コード生成
    // =========================================================================

    /**
     * RuleBody から getLazyParsers() の中身（カンマ区切り要素リスト）を生成する。
     */
    private String generateBodyElements(GenContext ctx, String ruleName, RuleBody body, String indent) {
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
     * - 単一要素: その要素コードをそのまま返す
     * - 複数要素: 匿名 {GrammarName}LazyChain サブクラスを生成
     */
    private String generateAlternativeCode(GenContext ctx, String ruleName, SequenceBody alt, String baseIndent) {
        List<AnnotatedElement> elements = alt.elements();

        if (elements.size() == 1) {
            return generateElementCode(ctx, ruleName, elements.get(0).element());
        }

        // 複数要素 → 匿名 TinyCalcLazyChain
        String chainClass = getChainClassName(ctx, ruleName);
        String innerIndent = baseIndent + "    ";
        StringBuilder sb = new StringBuilder();
        sb.append("new ").append(chainClass).append("() {\n");
        sb.append(innerIndent).append("private static final long serialVersionUID = 1L;\n");
        sb.append(innerIndent).append("@Override\n");
        sb.append(innerIndent).append("public Parsers getLazyParsers() {\n");
        sb.append(innerIndent).append("    return new Parsers(\n");

        List<String> elemCodes = new ArrayList<>();
        for (AnnotatedElement ae : elements) {
            elemCodes.add(generateElementCode(ctx, ruleName, ae.element()));
        }
        String elemsJoined = elemCodes.stream()
            .map(c -> innerIndent + "        " + c)
            .collect(Collectors.joining(",\n"));
        sb.append(elemsJoined).append("\n");

        sb.append(innerIndent).append("    );\n");
        sb.append(innerIndent).append("}\n");
        sb.append(baseIndent).append("}");

        return sb.toString();
    }

    /**
     * 単一の AtomicElement をコードに変換する。
     */
    private String generateElementCode(GenContext ctx, String ruleName, AtomicElement element) {
        return switch (element) {
            case TerminalElement t -> "new WordParser(\"" + escapeString(t.value()) + "\")";

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
                        yield "new Optional(new WordParser(\"" + escapeString(t.value()) + "\"))";
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

            case GroupElement g -> {
                int n = ctx.nextGroup(ruleName);
                String helperName = ruleName + "Group" + n + "Parser";
                yield "Parser.get(" + helperName + ".class)";
            }
        };
    }

    // =========================================================================
    // ユーティリティ
    // =========================================================================

    /** body が複数代替の ChoiceBody かどうか */
    private boolean isMultiChoice(RuleBody body) {
        return body instanceof ChoiceBody choice && choice.alternatives().size() > 1;
    }

    /** body が単一の RuleRefElement だけを含むか */
    private boolean isSingleRuleRef(RuleBody body) {
        AtomicElement single = getSingleAtomicElementFrom(body);
        return single instanceof RuleRefElement;
    }

    /** body が単一の AtomicElement だけを含むか */
    private boolean isSingleAtomicElement(RuleBody body) {
        return getSingleAtomicElementFrom(body) != null;
    }

    /** body から単一の AtomicElement を取り出す（なければ null） */
    private AtomicElement getSingleAtomicElementFrom(RuleBody body) {
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
    private String getSingleRuleRefClass(GenContext ctx, RuleBody body) {
        AtomicElement single = getSingleAtomicElementFrom(body);
        if (single instanceof RuleRefElement ref) {
            return resolveParserClass(ctx, ref.name());
        }
        throw new IllegalStateException("Expected single RuleRef body");
    }

    /**
     * ルール参照名をパーサークラス参照文字列に変換する。
     * - Simple トークン宣言に存在する → token.parserClass() + ".class"
     * - それ以外 → {Name}Parser.class
     * Until トークンには使用しないこと（resolveParserExpression を使う）。
     */
    private String resolveParserClass(GenContext ctx, String name) {
        // Negation tokens → generated inner class name
        if (ctx.tokenNegationMap.containsKey(name)) {
            return toNegationParserName(name) + ".class";
        }
        String tokenClass = ctx.tokenParserMap.get(name);
        if (tokenClass != null) {
            return tokenClass + ".class";
        }
        return name + "Parser.class";
    }

    /**
     * ルール参照名をパーサー生成式（完全な Java 式）に変換する。
     * - Simple / Negation / ルール → Parser.get(X.class)
     * - Until → new WildCardStringTerninatorParser("terminator")
     * - Lookahead → new MatchOnly(new WordParser("pattern"))
     * - NegativeLookahead → new Not(new WordParser("pattern"))
     */
    private String resolveParserExpression(GenContext ctx, String name) {
        String terminator = ctx.tokenUntilMap.get(name);
        if (terminator != null) {
            return "new org.unlaxer.parser.elementary.WildCardStringTerninatorParser(\""
                + escapeString(terminator) + "\")";
        }
        String laPattern = ctx.tokenLookaheadMap.get(name);
        if (laPattern != null) {
            return "new MatchOnly(new WordParser(\"" + escapeString(laPattern) + "\"))";
        }
        String nlaPattern = ctx.tokenNegLookaheadMap.get(name);
        if (nlaPattern != null) {
            return "new Not(new WordParser(\"" + escapeString(nlaPattern) + "\"))";
        }
        return "Parser.get(" + resolveParserClass(ctx, name) + ")";
    }

    /** 指定名がインライン生成式トークン（Until/Lookahead/NegativeLookahead）かどうか */
    private boolean isInlineToken(GenContext ctx, String name) {
        return ctx.tokenUntilMap.containsKey(name)
            || ctx.tokenLookaheadMap.containsKey(name)
            || ctx.tokenNegLookaheadMap.containsKey(name);
    }

    /**
     * NEGATION トークン名を生成クラス名に変換する。
     * 例: NOT_QUOTE → NotQuoteParser
     */
    private String toNegationParserName(String tokenName) {
        StringBuilder sb = new StringBuilder();
        boolean capitalizeNext = true;
        for (char c : tokenName.toCharArray()) {
            if (c == '_') {
                capitalizeNext = true;
            } else if (capitalizeNext) {
                sb.append(Character.toUpperCase(c));
                capitalizeNext = false;
            } else {
                sb.append(Character.toLowerCase(c));
            }
        }
        sb.append("Parser");
        return sb.toString();
    }

    private RightAssocShape getRightAssocShape(RuleDecl rule) {
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
        return new RightAssocShape(base, op);
    }

    private SequenceBody getSingleSequenceFrom(RuleBody body) {
        return switch (body) {
            case SequenceBody seq -> seq;
            case ChoiceBody choice when choice.alternatives().size() == 1 -> choice.alternatives().get(0);
            default -> null;
        };
    }

    private String generatePrecedenceConstants(GrammarDecl grammar) {
        StringBuilder sb = new StringBuilder();
        boolean found = false;
        for (RuleDecl rule : grammar.rules()) {
            Integer level = findPrecedenceLevel(rule);
            if (level == null) {
                continue;
            }
            found = true;
            sb.append("    public static final int PRECEDENCE_")
                .append(rule.name().toUpperCase())
                .append(" = ")
                .append(level)
                .append(";\n");
        }
        if (found) {
            sb.append("\n");
        }
        return sb.toString();
    }

    private Integer findPrecedenceLevel(RuleDecl rule) {
        return rule.annotations().stream()
            .filter(a -> a instanceof PrecedenceAnnotation)
            .map(a -> (PrecedenceAnnotation) a)
            .reduce((first, second) -> second)
            .map(PrecedenceAnnotation::level)
            .orElse(null);
    }

    private String generateOperatorMetadata(GrammarDecl grammar) {
        List<RuleDecl> operatorRules = grammar.rules().stream()
            .filter(this::hasAssocAnnotation)
            .toList();
        if (operatorRules.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("    public enum Assoc { LEFT, RIGHT, NONE }\n\n");
        sb.append("    public record OperatorSpec(String ruleName, int precedence, Assoc assoc) {}\n\n");

        List<RuleDecl> sorted = operatorRules.stream()
            .sorted((a, b) -> {
                int pa = findPrecedenceLevel(a) == null ? -1 : findPrecedenceLevel(a);
                int pb = findPrecedenceLevel(b) == null ? -1 : findPrecedenceLevel(b);
                if (pa != pb) {
                    return Integer.compare(pa, pb);
                }
                return a.name().compareTo(b.name());
            })
            .toList();

        sb.append("    private static final java.util.List<OperatorSpec> OPERATOR_SPECS = java.util.List.of(\n");
        for (int i = 0; i < sorted.size(); i++) {
            RuleDecl rule = sorted.get(i);
            int level = findPrecedenceLevel(rule) == null ? -1 : findPrecedenceLevel(rule);
            String suffix = i < sorted.size() - 1 ? "," : "";
            sb.append("            new OperatorSpec(\"")
                .append(rule.name()).append("\", ")
                .append(level).append(", Assoc.")
                .append(getAssocName(rule)).append(")")
                .append(suffix).append("\n");
        }
        sb.append("    );\n\n");

        sb.append("    public static java.util.List<OperatorSpec> getOperatorSpecs() {\n");
        sb.append("        return OPERATOR_SPECS;\n");
        sb.append("    }\n\n");

        sb.append("    public static java.util.Optional<OperatorSpec> getOperatorSpec(String ruleName) {\n");
        sb.append("        return OPERATOR_SPECS.stream()\n");
        sb.append("            .filter(s -> s.ruleName().equals(ruleName))\n");
        sb.append("            .findFirst();\n");
        sb.append("    }\n\n");

        sb.append("    public static boolean isOperatorRule(String ruleName) {\n");
        sb.append("        return getOperatorSpec(ruleName).isPresent();\n");
        sb.append("    }\n\n");

        sb.append("    public static int getPrecedence(String ruleName) {\n");
        sb.append("        return getOperatorSpec(ruleName)\n");
        sb.append("            .map(OperatorSpec::precedence)\n");
        sb.append("            .orElse(-1);\n");
        sb.append("    }\n\n");

        sb.append("    public static Assoc getAssociativity(String ruleName) {\n");
        sb.append("        return getOperatorSpec(ruleName)\n");
        sb.append("            .map(OperatorSpec::assoc)\n");
        sb.append("            .orElse(Assoc.NONE);\n");
        sb.append("    }\n\n");

        sb.append("    public static java.util.Optional<OperatorSpec> getNextHigherPrecedence(String ruleName) {\n");
        sb.append("        return getOperatorSpec(ruleName)\n");
        sb.append("            .flatMap(current -> OPERATOR_SPECS.stream()\n");
        sb.append("                .filter(s -> s.precedence() > current.precedence())\n");
        sb.append("                .findFirst());\n");
        sb.append("    }\n\n");

        sb.append("    public static java.util.Optional<OperatorSpec> getLowestPrecedenceOperator() {\n");
        sb.append("        return OPERATOR_SPECS.isEmpty()\n");
        sb.append("            ? java.util.Optional.empty()\n");
        sb.append("            : java.util.Optional.of(OPERATOR_SPECS.get(0));\n");
        sb.append("    }\n\n");

        sb.append("    public static java.util.List<Integer> getPrecedenceLevels() {\n");
        sb.append("        return OPERATOR_SPECS.stream()\n");
        sb.append("            .map(OperatorSpec::precedence)\n");
        sb.append("            .distinct()\n");
        sb.append("            .toList();\n");
        sb.append("    }\n\n");

        sb.append("    public static java.util.List<OperatorSpec> getOperatorsAtPrecedence(int precedence) {\n");
        sb.append("        return OPERATOR_SPECS.stream()\n");
        sb.append("            .filter(s -> s.precedence() == precedence)\n");
        sb.append("            .toList();\n");
        sb.append("    }\n\n");

        sb.append("    public static java.util.Optional<Parser> getOperatorParser(String ruleName) {\n");
        sb.append("        return switch (ruleName) {\n");
        for (RuleDecl rule : sorted) {
            sb.append("            case \"").append(rule.name()).append("\" -> java.util.Optional.of(Parser.get(")
                .append(rule.name()).append("Parser.class));\n");
        }
        sb.append("            default -> java.util.Optional.empty();\n");
        sb.append("        };\n");
        sb.append("    }\n\n");

        sb.append("    public static java.util.List<Parser> getOperatorParsersAtPrecedence(int precedence) {\n");
        sb.append("        return getOperatorsAtPrecedence(precedence).stream()\n");
        sb.append("            .map(OperatorSpec::ruleName)\n");
        sb.append("            .map(rule -> getOperatorParser(rule).orElse(null))\n");
        sb.append("            .filter(java.util.Objects::nonNull)\n");
        sb.append("            .toList();\n");
        sb.append("    }\n\n");

        sb.append("    public static java.util.Optional<Parser> getLowestPrecedenceParser() {\n");
        sb.append("        return getLowestPrecedenceOperator()\n");
        sb.append("            .flatMap(spec -> getOperatorParser(spec.ruleName()));\n");
        sb.append("    }\n\n");

        return sb.toString();
    }

    private String generateAdvancedAnnotationMetadata(GrammarDecl grammar) {
        boolean hasInterleave = grammar.rules().stream().anyMatch(this::hasInterleaveAnnotation);
        boolean hasBackref = grammar.rules().stream().anyMatch(this::hasBackrefAnnotation);
        boolean hasScopeTree = grammar.rules().stream().anyMatch(this::hasScopeTreeAnnotation);
        if (!hasInterleave && !hasBackref && !hasScopeTree) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (hasInterleave) {
            sb.append("    public static java.util.Optional<String> getInterleaveProfile(String ruleName) {\n")
                .append("        return switch (ruleName) {\n");
            for (RuleDecl rule : grammar.rules()) {
                String value = findInterleaveProfile(rule);
                if (value != null) {
                    sb.append("            case \"").append(rule.name()).append("\" -> java.util.Optional.of(\"")
                        .append(escapeJava(value)).append("\");\n");
                }
            }
            sb.append("            default -> java.util.Optional.empty();\n")
                .append("        };\n")
                .append("    }\n\n");
        }
        if (hasBackref) {
            sb.append("    public static java.util.Optional<String> getBackrefName(String ruleName) {\n")
                .append("        return switch (ruleName) {\n");
            for (RuleDecl rule : grammar.rules()) {
                String value = findBackrefName(rule);
                if (value != null) {
                    sb.append("            case \"").append(rule.name()).append("\" -> java.util.Optional.of(\"")
                        .append(escapeJava(value)).append("\");\n");
                }
            }
            sb.append("            default -> java.util.Optional.empty();\n")
                .append("        };\n")
                .append("    }\n\n");
        }
        if (hasScopeTree) {
            sb.append("    public enum ScopeMode { LEXICAL, DYNAMIC }\n\n");
            sb.append("    public record ScopeTreeSpec(String ruleName, String scopeId, ScopeMode mode) {}\n\n");
            sb.append("    public static java.util.Optional<String> getScopeTreeMode(String ruleName) {\n")
                .append("        return switch (ruleName) {\n");
            for (RuleDecl rule : grammar.rules()) {
                String value = findScopeTreeMode(rule);
                if (value != null) {
                    sb.append("            case \"").append(rule.name()).append("\" -> java.util.Optional.of(\"")
                        .append(escapeJava(value)).append("\");\n");
                }
            }
            sb.append("            default -> java.util.Optional.empty();\n")
                .append("        };\n")
                .append("    }\n\n");

            sb.append("    public static java.util.Optional<ScopeMode> getScopeTreeModeEnum(String ruleName) {\n")
                .append("        return getScopeTreeMode(ruleName)\n")
                .append("            .map(String::trim)\n")
                .append("            .map(String::toLowerCase)\n")
                .append("            .flatMap(mode -> switch (mode) {\n")
                .append("                case \"lexical\" -> java.util.Optional.of(ScopeMode.LEXICAL);\n")
                .append("                case \"dynamic\" -> java.util.Optional.of(ScopeMode.DYNAMIC);\n")
                .append("                default -> java.util.Optional.empty();\n")
                .append("            });\n")
                .append("    }\n\n");

            sb.append("    public static boolean isLexicalScopeTreeRule(String ruleName) {\n")
                .append("        return getScopeTreeModeEnum(ruleName)\n")
                .append("            .map(mode -> mode == ScopeMode.LEXICAL)\n")
                .append("            .orElse(false);\n")
                .append("    }\n\n");

            sb.append("    public static boolean isDynamicScopeTreeRule(String ruleName) {\n")
                .append("        return getScopeTreeModeEnum(ruleName)\n")
                .append("            .map(mode -> mode == ScopeMode.DYNAMIC)\n")
                .append("            .orElse(false);\n")
                .append("    }\n\n");

            List<String> lexicalRules = new ArrayList<>();
            List<String> dynamicRules = new ArrayList<>();
            for (RuleDecl rule : grammar.rules()) {
                String value = findScopeTreeMode(rule);
                if (value == null) {
                    continue;
                }
                String normalized = value.trim().toLowerCase();
                if ("lexical".equals(normalized)) {
                    lexicalRules.add(rule.name());
                } else if ("dynamic".equals(normalized)) {
                    dynamicRules.add(rule.name());
                }
            }

            sb.append("    public static java.util.List<String> getScopeTreeRules() {\n")
                .append("        java.util.ArrayList<String> out = new java.util.ArrayList<>();\n")
                .append("        out.addAll(getScopeTreeRules(ScopeMode.LEXICAL));\n")
                .append("        out.addAll(getScopeTreeRules(ScopeMode.DYNAMIC));\n")
                .append("        return java.util.List.copyOf(out);\n")
                .append("    }\n\n");

            sb.append("    public static java.util.List<String> getScopeTreeRules(ScopeMode mode) {\n")
                .append("        return switch (mode) {\n")
                .append("            case LEXICAL -> ")
                .append(renderStringListLiteral(lexicalRules))
                .append(";\n")
                .append("            case DYNAMIC -> ")
                .append(renderStringListLiteral(dynamicRules))
                .append(";\n")
                .append("        };\n")
                .append("    }\n\n");

            sb.append("    public static java.util.Map<String, ScopeMode> getScopeTreeModeByRule() {\n")
                .append("        java.util.LinkedHashMap<String, ScopeMode> map = new java.util.LinkedHashMap<>();\n")
                .append("        for (String rule : getScopeTreeRules(ScopeMode.LEXICAL)) {\n")
                .append("            map.put(rule, ScopeMode.LEXICAL);\n")
                .append("        }\n")
                .append("        for (String rule : getScopeTreeRules(ScopeMode.DYNAMIC)) {\n")
                .append("            map.put(rule, ScopeMode.DYNAMIC);\n")
                .append("        }\n")
                .append("        return java.util.Map.copyOf(map);\n")
                .append("    }\n\n");

            sb.append("    public static java.util.Map<String, String> getScopeTreeModeNameByRule() {\n")
                .append("        java.util.LinkedHashMap<String, String> map = new java.util.LinkedHashMap<>();\n")
                .append("        for (java.util.Map.Entry<String, ScopeMode> e : getScopeTreeModeByRule().entrySet()) {\n")
                .append("            map.put(e.getKey(), e.getValue().name().toLowerCase(java.util.Locale.ROOT));\n")
                .append("        }\n")
                .append("        return java.util.Map.copyOf(map);\n")
                .append("    }\n\n");

            sb.append("    public static String getScopeIdForRule(String ruleName) {\n")
                .append("        return \"scope:").append(escapeJava(grammar.name())).append("::\" + ruleName;\n")
                .append("    }\n\n");

            sb.append("    public static java.util.Optional<ScopeTreeSpec> getScopeTreeSpec(String ruleName) {\n")
                .append("        return getScopeTreeModeEnum(ruleName)\n")
                .append("            .map(mode -> new ScopeTreeSpec(ruleName, getScopeIdForRule(ruleName), mode));\n")
                .append("    }\n\n");

            sb.append("    public static java.util.List<ScopeTreeSpec> getScopeTreeSpecs() {\n")
                .append("        return getScopeTreeRules().stream()\n")
                .append("            .map(rule -> getScopeTreeSpec(rule).orElse(null))\n")
                .append("            .filter(java.util.Objects::nonNull)\n")
                .append("            .toList();\n")
                .append("    }\n\n");

            sb.append("    public static java.util.Map<String, ScopeTreeSpec> getScopeTreeSpecByRule() {\n")
                .append("        java.util.LinkedHashMap<String, ScopeTreeSpec> map = new java.util.LinkedHashMap<>();\n")
                .append("        for (ScopeTreeSpec spec : getScopeTreeSpecs()) {\n")
                .append("            map.put(spec.ruleName(), spec);\n")
                .append("        }\n")
                .append("        return java.util.Map.copyOf(map);\n")
                .append("    }\n\n");

            sb.append("    public static java.util.Map<String, ScopeTreeSpec> getScopeTreeSpecByScopeId() {\n")
                .append("        java.util.LinkedHashMap<String, ScopeTreeSpec> map = new java.util.LinkedHashMap<>();\n")
                .append("        for (ScopeTreeSpec spec : getScopeTreeSpecs()) {\n")
                .append("            map.put(spec.scopeId(), spec);\n")
                .append("        }\n")
                .append("        return java.util.Map.copyOf(map);\n")
                .append("    }\n\n");

            sb.append("    public static java.util.Map<String, ScopeMode> getScopeTreeModeByScopeId() {\n")
                .append("        java.util.LinkedHashMap<String, ScopeMode> map = new java.util.LinkedHashMap<>();\n")
                .append("        for (ScopeTreeSpec spec : getScopeTreeSpecs()) {\n")
                .append("            map.put(spec.scopeId(), spec.mode());\n")
                .append("        }\n")
                .append("        return java.util.Map.copyOf(map);\n")
                .append("    }\n\n");

            sb.append("    public static java.util.Map<String, String> getScopeTreeModeNameByScopeId() {\n")
                .append("        java.util.LinkedHashMap<String, String> map = new java.util.LinkedHashMap<>();\n")
                .append("        for (java.util.Map.Entry<String, ScopeMode> e : getScopeTreeModeByScopeId().entrySet()) {\n")
                .append("            map.put(e.getKey(), e.getValue().name().toLowerCase(java.util.Locale.ROOT));\n")
                .append("        }\n")
                .append("        return java.util.Map.copyOf(map);\n")
                .append("    }\n\n");

            sb.append("    public static java.util.List<Object> buildSyntheticScopeEventsForNodes(java.util.List<Object> nodes) {\n")
                .append("        return emitSyntheticScopeEventsForRulesAnyMode(\n")
                .append("            \"").append(escapeJava(grammar.name())).append("\",\n")
                .append("            getScopeTreeModeByRule(),\n")
                .append("            nodes\n")
                .append("        );\n")
                .append("    }\n\n");

            sb.append("    public static java.util.List<Object> buildSyntheticScopeEventsForNodes(\n")
                .append("        java.util.List<Object> nodes,\n")
                .append("        java.util.Map<String, ?> modeOverridesByRule\n")
                .append("    ) {\n")
                .append("        java.util.LinkedHashMap<String, Object> merged = new java.util.LinkedHashMap<>();\n")
                .append("        merged.putAll(getScopeTreeModeByRule());\n")
                .append("        if (modeOverridesByRule != null) {\n")
                .append("            merged.putAll(modeOverridesByRule);\n")
                .append("        }\n")
                .append("        return emitSyntheticScopeEventsForRulesAnyMode(\n")
                .append("            \"").append(escapeJava(grammar.name())).append("\",\n")
                .append("            merged,\n")
                .append("            nodes\n")
                .append("        );\n")
                .append("    }\n\n");

            sb.append("    public static java.util.List<Object> buildSyntheticScopeEventsForNodesByScopeId(java.util.List<Object> nodes) {\n")
                .append("        return emitSyntheticScopeEventsForScopeIdsAnyMode(\n")
                .append("            getScopeTreeModeNameByScopeId(),\n")
                .append("            nodes\n")
                .append("        );\n")
                .append("    }\n\n");

            sb.append("    private static java.util.List<Object> emitSyntheticScopeEventsForRulesAnyMode(\n")
                .append("        String grammarName,\n")
                .append("        java.util.Map<String, ?> modeByRule,\n")
                .append("        java.util.List<Object> nodes\n")
                .append("    ) {\n")
                .append("        return org.unlaxer.dsl.ir.ParserIrScopeEvents.emitSyntheticEnterLeaveEventsForRulesAnyMode(\n")
                .append("            grammarName,\n")
                .append("            modeByRule,\n")
                .append("            nodes\n")
                .append("        );\n")
                .append("    }\n\n");

            sb.append("    private static java.util.List<Object> emitSyntheticScopeEventsForScopeIdsAnyMode(\n")
                .append("        java.util.Map<String, String> modeByScopeId,\n")
                .append("        java.util.List<Object> nodes\n")
                .append("    ) {\n")
                .append("        return org.unlaxer.dsl.ir.ParserIrScopeEvents.emitSyntheticEnterLeaveEventsForScopeIdsAnyMode(\n")
                .append("            modeByScopeId,\n")
                .append("            nodes\n")
                .append("        );\n")
                .append("    }\n\n");

            sb.append("    public static boolean hasScopeTree(String ruleName) {\n")
                .append("        return getScopeTreeModeByRule().containsKey(ruleName);\n")
                .append("    }\n\n");

            sb.append("    public static ScopeMode getScopeTreeModeOrDefault(String ruleName, ScopeMode fallback) {\n")
                .append("        return getScopeTreeModeByRule().getOrDefault(ruleName, fallback);\n")
                .append("    }\n\n");
        }
        return sb.toString();
    }

    private String renderStringListLiteral(List<String> values) {
        if (values.isEmpty()) {
            return "java.util.List.of()";
        }
        return "java.util.List.of(" + values.stream()
            .map(v -> "\"" + escapeJava(v) + "\"")
            .collect(Collectors.joining(", ")) + ")";
    }

    private boolean hasInterleaveAnnotation(RuleDecl rule) {
        return rule.annotations().stream().anyMatch(a -> a instanceof InterleaveAnnotation);
    }

    private boolean hasBackrefAnnotation(RuleDecl rule) {
        return rule.annotations().stream().anyMatch(a -> a instanceof BackrefAnnotation);
    }

    private boolean hasScopeTreeAnnotation(RuleDecl rule) {
        return rule.annotations().stream().anyMatch(a -> a instanceof ScopeTreeAnnotation);
    }

    private String findInterleaveProfile(RuleDecl rule) {
        return rule.annotations().stream()
            .filter(a -> a instanceof InterleaveAnnotation)
            .map(a -> (InterleaveAnnotation) a)
            .map(InterleaveAnnotation::profile)
            .findFirst()
            .orElse(null);
    }

    private String findBackrefName(RuleDecl rule) {
        return rule.annotations().stream()
            .filter(a -> a instanceof BackrefAnnotation)
            .map(a -> (BackrefAnnotation) a)
            .map(BackrefAnnotation::name)
            .findFirst()
            .orElse(null);
    }

    private String findScopeTreeMode(RuleDecl rule) {
        return rule.annotations().stream()
            .filter(a -> a instanceof ScopeTreeAnnotation)
            .map(a -> (ScopeTreeAnnotation) a)
            .map(ScopeTreeAnnotation::mode)
            .findFirst()
            .orElse(null);
    }

    private String escapeJava(String s) {
        return s
            .replace("\\", "\\\\")
            .replace("\"", "\\\"");
    }

    private boolean hasAssocAnnotation(RuleDecl rule) {
        return rule.annotations().stream().anyMatch(a ->
            a instanceof LeftAssocAnnotation || a instanceof RightAssocAnnotation);
    }

    private String getAssocName(RuleDecl rule) {
        boolean right = rule.annotations().stream().anyMatch(a -> a instanceof RightAssocAnnotation);
        return right ? "RIGHT" : "LEFT";
    }

    /** ルートルール名を返す（@root アノテーション付き） */
    private String findRootRuleName(GrammarDecl grammar) {
        return grammar.rules().stream()
            .filter(r -> r.annotations().stream().anyMatch(a -> a instanceof RootAnnotation))
            .map(RuleDecl::name)
            .findFirst()
            .orElse(grammar.rules().isEmpty() ? "Root" : grammar.rules().get(0).name());
    }

    /** 文字列内の特殊文字をエスケープする */
    private String escapeString(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\t", "\\t")
                .replace("\r", "\\r");
    }

    /** @package 設定からパッケージ名を取得する */
    private String getPackageName(GrammarDecl grammar) {
        return grammar.settings().stream()
            .filter(s -> "package".equals(s.key()))
            .map(s -> s.value() instanceof StringSettingValue sv ? sv.value() : "")
            .findFirst()
            .orElse("generated");
    }
}
