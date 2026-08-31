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

        // ----- Fold Helpers -----
        sb.append(MapperRuleEmitter.emitFoldHelpers(grammar, astClass, mappingRules));

        // ----- Utilities -----
        sb.append(MapperRuleEmitter.emitUtilities(parsersClass, mappedClassByRuleName.keySet()));

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
        sb.append("    /** Selected parse-tree token and its generated AST mapping. */\n");
        sb.append("    public record MappedAst(Token token, ").append(astClass).append(" ast) {}\n\n");
        sb.append("    /** Maps an already parsed token tree without accessing mapper internals. */\n");
        sb.append("    public static MappedAst mapParsedToken(Token rootToken) {\n");
        sb.append("        return mapParsedToken(rootToken, null);\n");
        sb.append("    }\n\n");
        sb.append("    /**\n");
        sb.append("     * Maps an already parsed token tree, preferring an AST type by simple name.\n");
        sb.append("     * The returned token is the token selected for the returned AST.\n");
        sb.append("     */\n");
        sb.append("    public static MappedAst mapParsedToken(Token rootToken, String preferredAstSimpleName) {\n");
        sb.append("        if (rootToken == null) {\n");
        sb.append("            throw new IllegalArgumentException(\"rootToken must not be null\");\n");
        sb.append("        }\n");
        sb.append("        NODE_SOURCE_SPANS.clear();\n");
        sb.append("        MAP_MEMO.clear();\n");
        sb.append("        Token selectedToken = findBestMappedToken(rootToken, preferredAstSimpleName);\n");
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
        sb.append("    public static ").append(rootClassName).append(" parse(String source, String preferredAstSimpleName) {\n");
        sb.append("        NODE_SOURCE_SPANS.clear();\n");
        sb.append("        MAP_MEMO.clear();\n");
        sb.append("        Parser rootParser = ").append(parsersClass).append(".getRootParser();\n");
        sb.append("        ParseContext context = new ParseContext(createRootSourceCompat(source));\n");
        sb.append("        Parsed parsed;\n");
        sb.append("        try {\n");
        sb.append("            parsed = rootParser.parse(context);\n");
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
        sb.append("        Token rootToken = parsed.getRootToken(true);\n");

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
