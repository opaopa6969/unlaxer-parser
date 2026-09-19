package org.unlaxer.dsl.codegen;

import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.bootstrap.UBNFAST.MappingAnnotation;
import org.unlaxer.dsl.bootstrap.UBNFAST.RootAnnotation;
import org.unlaxer.dsl.bootstrap.UBNFAST.RuleDecl;
import org.unlaxer.dsl.bootstrap.UBNFAST.SkipAnnotation;
import org.unlaxer.dsl.bootstrap.UBNFAST.StringSettingValue;
import org.unlaxer.dsl.bootstrap.UBNFAST.TokenDecl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * GrammarDecl から XxxMapper.java を生成する。
 */
public class MapperGenerator implements CodeGenerator {

    @Override
    public GeneratedSource generate(GrammarDecl grammar) {
        String packageName = getPackageName(grammar);
        String grammarName = grammar.name();
        String astClass = grammarName + "AST";
        String mapperClass = grammarName + "Mapper";
        String parsersClass = grammarName + "Parsers";

        Map<String, TokenDecl> tokenDeclByName = grammar.tokens().stream()
            .collect(Collectors.toMap(TokenDecl::name, t -> t, (a, b) -> a, LinkedHashMap::new));

        Map<String, RuleDecl> ruleByName = grammar.rules().stream()
            .collect(Collectors.toMap(RuleDecl::name, r -> r, (a, b) -> a, LinkedHashMap::new));

        Optional<RuleDecl> rootRule = grammar.rules().stream()
            .filter(r -> r.annotations().stream().anyMatch(a -> a instanceof RootAnnotation))
            .findFirst();

        Map<String, RuleDecl> mappingRules = new LinkedHashMap<>();
        Map<String, List<RuleDecl>> allMappingRules = new LinkedHashMap<>();
        Map<String, String> mappedClassByRuleName = new LinkedHashMap<>();
        for (RuleDecl rule : grammar.rules()) {
            boolean isSkip = rule.annotations().stream().anyMatch(a -> a instanceof SkipAnnotation);
            if (!isSkip) {
                MapperElementUtil.getMappingAnnotation(rule).ifPresent(m -> {
                    mappingRules.putIfAbsent(m.className(), rule);
                    allMappingRules.computeIfAbsent(m.className(), k -> new ArrayList<>()).add(rule);
                    mappedClassByRuleName.putIfAbsent(rule.name(), m.className());
                });
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(packageName).append(";\n\n");
        sb.append("import java.util.ArrayList;\n");
        sb.append("import java.util.List;\n");
        sb.append("import java.util.Optional;\n\n");
        sb.append("import org.unlaxer.Parsed;\n");
        sb.append("import org.unlaxer.StringSource;\n");
        sb.append("import org.unlaxer.Token;\n");
        sb.append("import org.unlaxer.context.ParseContext;\n");
        sb.append("import org.unlaxer.parser.Parser;\n\n");

        sb.append(CodeGenerator.generatedAnnotation("org.unlaxer.dsl.codegen.MapperGenerator"));
        sb.append("/**\n");
        sb.append(" * ").append(grammarName).append(" parse tree (Token) -> ")
          .append(astClass).append(" mapper.\n");
        sb.append(" */\n");
        sb.append("public class ").append(mapperClass).append(" {\n\n");
        sb.append("    private ").append(mapperClass).append("() {}\n\n");
        sb.append("    private static final java.util.IdentityHashMap<Object, int[]> NODE_SOURCE_SPANS =\n");
        sb.append("        new java.util.IdentityHashMap<>();\n\n");
        // Per-parse memo of token -> mapped AST node; see mapToken. Cleared at the start of parse().
        // (tinyexpression #49)
        sb.append("    private static final java.util.IdentityHashMap<Token, ").append(astClass).append("> MAP_MEMO =\n");
        sb.append("        new java.util.IdentityHashMap<>();\n\n");

        String rootClassName = rootRule.flatMap(MapperElementUtil::getMappingAnnotation)
            .map(m -> astClass + "." + m.className())
            .orElse(astClass);

        // ----- Entry Point -----
        emitEntryPoint(sb, grammar, astClass, parsersClass, rootClassName, rootRule);

        // ----- mapToken -----
        sb.append(MapperRuleEmitter.emitMapTokenMethod(astClass, parsersClass, allMappingRules));

        // ----- findBestMappedToken -----
        sb.append(MapperRuleEmitter.emitFindBestMappedToken(astClass));

        // ----- mapTransparentValue (heterogeneous @value node resolution) -----
        sb.append(MapperRuleEmitter.emitMapTransparentValue(astClass));

        // ----- Mapping Methods -----
        sb.append(MapperRuleEmitter.emitMappingMethods(grammar, astClass, parsersClass,
            mappingRules, allMappingRules, mappedClassByRuleName, tokenDeclByName, ruleByName));

        // ----- Utilities -----
        sb.append(MapperRuleEmitter.emitUtilities(parsersClass, mappedClassByRuleName.keySet()));
        if (grammar.rules().stream().anyMatch(rule -> !SemanticCardinality.associative(rule)
                && MapperElementUtil.getMappingAnnotation(rule).isPresent())) {
            sb.append(MapperRuleEmitter.emitCaptureOccurrenceUtilities(parsersClass, ruleByName.keySet()));
        }
        if (new SemanticCardinality(grammar).enabled()) {
            sb.append(MapperRuleEmitter.emitSemanticUtilities(grammar, parsersClass));
        }

        sb.append("}\n");

        return new GeneratedSource(packageName, mapperClass, sb.toString());
    }

    /**
     * parse() エントリポイントを生成する。
     */
    private void emitEntryPoint(StringBuilder sb, GrammarDecl grammar,
            String astClass, String parsersClass, String rootClassName,
            Optional<RuleDecl> rootRule) {

        sb.append("    // =========================================================================\n");
        sb.append("    // Entry Point\n");
        sb.append("    // =========================================================================\n\n");
        sb.append("""
                /** Parser diagnostics only: offsets are Unicode code points, not UTF-16 indices. */
                public record ParseDiagnostic(String kind, int offset, List<String> expected,
                        int farthestOffset, List<String> farthestExpected) {
                    public ParseDiagnostic {
                        expected = List.copyOf(expected);
                        farthestExpected = List.copyOf(farthestExpected);
                    }
                }

                /**
                 * Validates full-input parsing without mapping or clearing retained source maps.
                 * Empty means parser acceptance, not successful AST mapping or evaluation.
                 * Native syntax hints are backend-specific; trailing input always expects end of input.
                 */
                public static synchronized Optional<ParseDiagnostic> diagnose(String source) {
            """);
        sb.append("        Parser rootParser = ").append(parsersClass).append(".getRootParser();\n");
        sb.append("""
                    try (ParseContext context = new ParseContext(createRootSourceCompat(source))) {
                        Parsed parsed = rootParser.parse(context);
                        int consumed = consumedLengthCompat(parsed.getConsumed());
                        if (parsed.isSucceeded() && consumed == source.length()) return Optional.empty();
                        var nativeFailure = context.getParseFailureDiagnostics();
                        List<String> hints = nativeFailure.getExpectedTokens().stream().sorted().toList();
                        int farthest = nativeFailure.getFarthestOffset();
                        if (parsed.isSucceeded()) {
                            int offset = source.codePointCount(0, consumed);
                            return Optional.of(new ParseDiagnostic("trailing_input", offset, List.of("end of input"), farthest, hints));
                        }
                        return Optional.of(new ParseDiagnostic("syntax", farthest, hints, farthest, hints));
                    }
                }

            """);
        sb.append("    /** Selected parse-tree token and its generated AST mapping. */\n");
        sb.append("    public record MappedAst(Token token, ").append(astClass).append(" ast) {}\n\n");
        sb.append("    /** Immutable identity-based source map; offsets are code points, end exclusive. */\n");
        sb.append("    public static final class SourceMappedAst<T extends ").append(astClass).append("> {\n");
        sb.append("        private final T ast;\n");
        sb.append("        private final java.util.IdentityHashMap<Object, int[]> spans = new java.util.IdentityHashMap<>();\n\n");
        sb.append("        private SourceMappedAst(T ast) {\n");
        sb.append("            this.ast = ast;\n");
        sb.append("            NODE_SOURCE_SPANS.forEach((node, span) -> spans.put(node, span.clone()));\n");
        sb.append("        }\n\n");
        sb.append("        public T ast() { return ast; }\n\n");
        sb.append("        public Optional<int[]> sourceSpanOf(Object node) {\n");
        sb.append("            int[] span = spans.get(node);\n");
        sb.append("            return span == null ? Optional.empty() : Optional.of(span.clone());\n");
        sb.append("        }\n");
        sb.append("    }\n\n");
        sb.append("    /** Retains source spans across subsequent parses. Synthetic nodes may have no span. */\n");
        sb.append("    public static synchronized SourceMappedAst<").append(rootClassName).append("> parseWithSourceMap(String source) {\n");
        sb.append("        return new SourceMappedAst<>(parse(source));\n");
        sb.append("    }\n\n");
        sb.append("    public static synchronized SourceMappedAst<").append(astClass).append("> mapParsedTokenWithSourceMap(Token token) {\n");
        sb.append("        return new SourceMappedAst<>(mapParsedToken(token).ast());\n");
        sb.append("    }\n\n");
        sb.append("    /** Maps an alternate parser entry and retains its identity-based source snapshot. */\n");
        sb.append("    public static synchronized SourceMappedAst<").append(astClass).append("> mapSubtreeTokenWithSourceMap(Token token) {\n");
        sb.append("        return new SourceMappedAst<>(mapSubtreeToken(token).ast());\n");
        sb.append("    }\n\n");
        sb.append("    /** Selected token and the identity-based source snapshot from the same mapping. */\n");
        sb.append("    public record SourceMappedSelection(Token token, SourceMappedAst<").append(astClass)
            .append("> sourceMap) {}\n\n");
        sb.append("    public static SourceMappedSelection selectParsedTokenWithSourceMap(Token token) {\n");
        sb.append("        return selectParsedTokenWithSourceMap(token, null);\n");
        sb.append("    }\n\n");
        sb.append("    /**\n");
        sb.append("     * Selects once using mapParsedToken's preferred-type and root-validation contract.\n");
        sb.append("     * Mapping and snapshot capture share the mapper lock; later parses cannot replace these spans.\n");
        sb.append("     * The caller may inspect the selected token to enforce whole-source coverage.\n");
        sb.append("     * Retain the actual parser input separately when extracting text from code-point spans.\n");
        sb.append("     */\n");
        sb.append("    public static synchronized SourceMappedSelection selectParsedTokenWithSourceMap(Token token, String preferredAstSimpleName) {\n");
        sb.append("        MappedAst selected = mapParsedToken(token, preferredAstSimpleName);\n");
        sb.append("        return new SourceMappedSelection(selected.token(), new SourceMappedAst<>(selected.ast()));\n");
        sb.append("    }\n\n");
        sb.append("    public static SourceMappedSelection selectSubtreeTokenWithSourceMap(Token token) {\n");
        sb.append("        return selectSubtreeTokenWithSourceMap(token, null);\n");
        sb.append("    }\n\n");
        sb.append("    /**\n");
        sb.append("     * Maps an explicitly selected parser entry without requiring the grammar's @root token.\n");
        sb.append("     * Mapping and snapshot capture share the mapper lock; later parses cannot replace these spans.\n");
        sb.append("     * Use this only for a committed token produced by an intentional alternate parser entry.\n");
        sb.append("     * The token must belong to this generated grammar and contain a mapped node.\n");
        sb.append("     * The caller must verify full-input consumption before calling this method.\n");
        sb.append("     */\n");
        sb.append("    public static synchronized SourceMappedSelection selectSubtreeTokenWithSourceMap(Token token, String preferredAstSimpleName) {\n");
        sb.append("        MappedAst selected = mapSubtreeToken(token, preferredAstSimpleName);\n");
        sb.append("        return new SourceMappedSelection(selected.token(), new SourceMappedAst<>(selected.ast()));\n");
        sb.append("    }\n\n");
        sb.append("    /** Maps an already parsed token tree without accessing mapper internals. */\n");
        sb.append("    public static MappedAst mapParsedToken(Token rootToken) {\n");
        sb.append("        return mapParsedToken(rootToken, null);\n");
        sb.append("    }\n\n");
        sb.append("    /**\n");
        sb.append("     * Maps an already parsed token tree, preferring an AST type by simple name.\n");
        sb.append("     * The returned token is the token selected for the returned AST.\n");
        sb.append("     * A mapped grammar root must be present as the input token itself.\n");
        sb.append("     * Use the committed parser root, not a choice's root-stripped Parsed token.\n");
        sb.append("     */\n");
        sb.append("    public static synchronized MappedAst mapParsedToken(Token rootToken, String preferredAstSimpleName) {\n");
        sb.append("        if (rootToken == null) {\n");
        sb.append("            throw new IllegalArgumentException(\"rootToken must not be null\");\n");
        sb.append("        }\n");
        if (rootRule.isPresent() && MapperElementUtil.getMappingAnnotation(rootRule.get()).isPresent()) {
            String ruleName = rootRule.get().name();
            sb.append("        if (rootToken.parser.getClass() != ").append(parsersClass).append(".")
                .append(ruleName).append("Parser.class) {\n");
            sb.append("            throw new IllegalArgumentException(\"Mapped root token is missing for ")
                .append(ruleName).append("; pass the committed parser root from ParseContext.getCurrent().getTokens(), not a root-stripped token\");\n");
            sb.append("        }\n");
        }
        sb.append("        return mapTokenTree(rootToken, preferredAstSimpleName);\n");
        sb.append("    }\n\n");
        sb.append("    /** Maps a committed token from an intentional alternate parser entry. */\n");
        sb.append("    public static MappedAst mapSubtreeToken(Token subtreeToken) {\n");
        sb.append("        return mapSubtreeToken(subtreeToken, null);\n");
        sb.append("    }\n\n");
        sb.append("    /** Maps a committed alternate-entry token, preferring an AST type by simple name. */\n");
        sb.append("    public static synchronized MappedAst mapSubtreeToken(Token subtreeToken, String preferredAstSimpleName) {\n");
        sb.append("        if (subtreeToken == null) {\n");
        sb.append("            throw new IllegalArgumentException(\"subtreeToken must not be null\");\n");
        sb.append("        }\n");
        sb.append("        if (!isGeneratedRuleToken(subtreeToken)) {\n");
        sb.append("            throw new IllegalArgumentException(\"subtreeToken must be produced by a rule parser from this generated grammar\");\n");
        sb.append("        }\n");
        sb.append("        return mapTokenTree(subtreeToken, preferredAstSimpleName);\n");
        sb.append("    }\n\n");
        sb.append("    private static boolean isGeneratedRuleToken(Token token) {\n");
        sb.append("        if (token.parser == null) return false;\n");
        sb.append("        Class<?> parserClass = token.parser.getClass();\n");
        sb.append("        return ");
        if (grammar.rules().isEmpty()) sb.append("false");
        for (int i = 0; i < grammar.rules().size(); i++) {
            RuleDecl rule = grammar.rules().get(i);
            if (i > 0) sb.append("\n            || ");
            sb.append("parserClass == ").append(parsersClass).append(".")
                .append(rule.name()).append("Parser.class");
        }
        sb.append(";\n");
        sb.append("    }\n\n");
        sb.append("    private static MappedAst mapTokenTree(Token token, String preferredAstSimpleName) {\n");
        sb.append("        NODE_SOURCE_SPANS.clear();\n");
        sb.append("        MAP_MEMO.clear();\n");
        sb.append("        Token selectedToken = findBestMappedToken(token, preferredAstSimpleName);\n");
        sb.append("        if (selectedToken == null) {\n");
        sb.append("            throw new IllegalArgumentException(\"No mapped node found in token tree\");\n");
        sb.append("        }\n");
        sb.append("        ").append(astClass).append(" mapped = mapToken(selectedToken);\n");
        sb.append("        if (mapped == null) {\n");
        sb.append("            throw new IllegalArgumentException(\"Selected token could not be mapped\");\n");
        sb.append("        }\n");
        sb.append("        return new MappedAst(selectedToken, mapped);\n");
        sb.append("    }\n\n");
        sb.append("    public static ").append(rootClassName).append(" parse(String source) {\n");
        sb.append("        return parse(source, null);\n");
        sb.append("    }\n\n");
        sb.append("    public static synchronized ").append(rootClassName).append(" parse(String source, String preferredAstSimpleName) {\n");
        sb.append("        NODE_SOURCE_SPANS.clear();\n");
        sb.append("        MAP_MEMO.clear();\n");
        sb.append("        Parser rootParser = ").append(parsersClass).append(".getRootParser();\n");
        sb.append("        ParseContext context = new ParseContext(createRootSourceCompat(source));\n");
        sb.append("        Parsed parsed;\n");
        sb.append("        Token rootToken = null;\n");
        sb.append("        try {\n");
        sb.append("            parsed = rootParser.parse(context);\n");
        // ChoiceInterface returns its winning child's Parsed, while commit stores the
        // actual mapped choice wrapper in the context. Retain that root before closing.
        sb.append("            if (parsed.isSucceeded()) {\n");
        sb.append("                rootToken = parsed.getRootToken(false);\n");
        sb.append("                for (Token committed : context.getCurrent().getTokens()) {\n");
        sb.append("                    if (committed.parser == rootParser) {\n");
        sb.append("                        rootToken = committed;\n");
        sb.append("                        break;\n");
        sb.append("                    }\n");
        sb.append("                }\n");
        sb.append("            }\n");
        sb.append("        } finally {\n");
        sb.append("            context.close();\n");
        sb.append("        }\n");
        sb.append("        if (!parsed.isSucceeded()) {\n");
        sb.append("            throw new IllegalArgumentException(\"Parse failed: \" + source);\n");
        sb.append("        }\n");
        sb.append("        int consumed = consumedLengthCompat(parsed.getConsumed());\n");
        sb.append("        if (consumed != source.length()) {\n");
        sb.append("            throw new IllegalArgumentException(\"Parse failed at offset \" + consumed + \": \" + source);\n");
        sb.append("        }\n");
        // Capture-site metadata includes successful zero-width matches. The generic reducer
        // drops empty children and mutates the CST, so typed mapping must use the original tree.

        if (rootRule.isPresent() && MapperElementUtil.getMappingAnnotation(rootRule.get()).isPresent()) {
            RuleDecl rr = rootRule.get();
            String rootParserClass = parsersClass + "." + rr.name() + "Parser.class";
            String rootMappingClass = MapperElementUtil.getMappingAnnotation(rr).orElseThrow().className();
            sb.append("        Token mappingRoot = rootToken;\n");
            sb.append("        if (mappingRoot.parser.getClass() != ").append(rootParserClass).append(") {\n");
            sb.append("            mappingRoot = findFirstDescendant(mappingRoot, ").append(rootParserClass).append(");\n");
            sb.append("        }\n");
            sb.append("        if (mappingRoot == null) {\n");
            sb.append("            throw new IllegalArgumentException(\"Root mapping token not found for ").append(rr.name()).append("\");\n");
            sb.append("        }\n");
            sb.append("        return to").append(rootMappingClass).append("(mappingRoot);\n");
        } else {
            sb.append("        Token bestMappedToken = findBestMappedToken(rootToken, preferredAstSimpleName);\n");
            sb.append("        ").append(astClass).append(" mapped = mapToken(bestMappedToken);\n");
            sb.append("        if (mapped == null) {\n");
            sb.append("            throw new IllegalArgumentException(\"No mapped node found in parse tree\");\n");
            sb.append("        }\n");
            sb.append("        return (").append(rootClassName).append(") mapped;\n");
        }
        sb.append("    }\n\n");
    }

    private String getPackageName(GrammarDecl grammar) {
        return grammar.settings().stream()
            .filter(s -> "package".equals(s.key()))
            .map(s -> s.value() instanceof StringSettingValue sv ? sv.value() : "")
            .findFirst()
            .orElse("generated");
    }
}
