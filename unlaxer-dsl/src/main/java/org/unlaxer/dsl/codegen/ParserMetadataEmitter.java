package org.unlaxer.dsl.codegen;

import org.unlaxer.dsl.bootstrap.UBNFAST.BackrefAnnotation;
import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.bootstrap.UBNFAST.InterleaveAnnotation;
import org.unlaxer.dsl.bootstrap.UBNFAST.LeftAssocAnnotation;
import org.unlaxer.dsl.bootstrap.UBNFAST.PrecedenceAnnotation;
import org.unlaxer.dsl.bootstrap.UBNFAST.RightAssocAnnotation;
import org.unlaxer.dsl.bootstrap.UBNFAST.RuleDecl;
import org.unlaxer.dsl.bootstrap.UBNFAST.ScopeTreeAnnotation;

import java.util.ArrayList;
import java.util.List;

/**
 * メタデータ（precedence / operator / annotation）のコード生成を担当する。
 */
class ParserMetadataEmitter {

    private ParserMetadataEmitter() {}

    static String generatePrecedenceConstants(GrammarDecl grammar) {
        IndentedWriter w = new IndentedWriter(1);
        boolean found = false;
        for (RuleDecl rule : grammar.rules()) {
            Integer level = findPrecedenceLevel(rule);
            if (level == null) {
                continue;
            }
            found = true;
            w.line("public static final int PRECEDENCE_"
                + rule.name().toUpperCase()
                + " = "
                + level
                + ";");
        }
        if (found) {
            w.blankLine();
        }
        return w.build();
    }

    static Integer findPrecedenceLevel(RuleDecl rule) {
        return rule.annotations().stream()
            .filter(a -> a instanceof PrecedenceAnnotation)
            .map(a -> (PrecedenceAnnotation) a)
            .reduce((first, second) -> second)
            .map(PrecedenceAnnotation::level)
            .orElse(null);
    }

    static String generateOperatorMetadata(GrammarDecl grammar) {
        List<RuleDecl> operatorRules = grammar.rules().stream()
            .filter(ParserMetadataEmitter::hasAssocAnnotation)
            .toList();
        if (operatorRules.isEmpty()) {
            return "";
        }

        IndentedWriter w = new IndentedWriter(1);
        w.line("public enum Assoc { LEFT, RIGHT, NONE }");
        w.blankLine();
        w.line("public record OperatorSpec(String ruleName, int precedence, Assoc assoc) {}");
        w.blankLine();

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

        w.line("private static final java.util.List<OperatorSpec> OPERATOR_SPECS = java.util.List.of(");
        for (int i = 0; i < sorted.size(); i++) {
            RuleDecl rule = sorted.get(i);
            int level = findPrecedenceLevel(rule) == null ? -1 : findPrecedenceLevel(rule);
            String suffix = i < sorted.size() - 1 ? "," : "";
            w.raw("            new OperatorSpec(\""
                + rule.name() + "\", "
                + level + ", Assoc."
                + getAssocName(rule) + ")"
                + suffix + "\n");
        }
        w.line(");");
        w.blankLine();

        w.line("public static java.util.List<OperatorSpec> getOperatorSpecs() {");
        w.line("    return OPERATOR_SPECS;");
        w.line("}");
        w.blankLine();

        w.line("public static java.util.Optional<OperatorSpec> getOperatorSpec(String ruleName) {");
        w.line("    return OPERATOR_SPECS.stream()");
        w.line("        .filter(s -> s.ruleName().equals(ruleName))");
        w.line("        .findFirst();");
        w.line("}");
        w.blankLine();

        w.line("public static boolean isOperatorRule(String ruleName) {");
        w.line("    return getOperatorSpec(ruleName).isPresent();");
        w.line("}");
        w.blankLine();

        w.line("public static int getPrecedence(String ruleName) {");
        w.line("    return getOperatorSpec(ruleName)");
        w.line("        .map(OperatorSpec::precedence)");
        w.line("        .orElse(-1);");
        w.line("}");
        w.blankLine();

        w.line("public static Assoc getAssociativity(String ruleName) {");
        w.line("    return getOperatorSpec(ruleName)");
        w.line("        .map(OperatorSpec::assoc)");
        w.line("        .orElse(Assoc.NONE);");
        w.line("}");
        w.blankLine();

        w.line("public static java.util.Optional<OperatorSpec> getNextHigherPrecedence(String ruleName) {");
        w.line("    return getOperatorSpec(ruleName)");
        w.line("        .flatMap(current -> OPERATOR_SPECS.stream()");
        w.line("            .filter(s -> s.precedence() > current.precedence())");
        w.line("            .findFirst());");
        w.line("}");
        w.blankLine();

        w.line("public static java.util.Optional<OperatorSpec> getLowestPrecedenceOperator() {");
        w.line("    return OPERATOR_SPECS.isEmpty()");
        w.line("        ? java.util.Optional.empty()");
        w.line("        : java.util.Optional.of(OPERATOR_SPECS.get(0));");
        w.line("}");
        w.blankLine();

        w.line("public static java.util.List<Integer> getPrecedenceLevels() {");
        w.line("    return OPERATOR_SPECS.stream()");
        w.line("        .map(OperatorSpec::precedence)");
        w.line("        .distinct()");
        w.line("        .toList();");
        w.line("}");
        w.blankLine();

        w.line("public static java.util.List<OperatorSpec> getOperatorsAtPrecedence(int precedence) {");
        w.line("    return OPERATOR_SPECS.stream()");
        w.line("        .filter(s -> s.precedence() == precedence)");
        w.line("        .toList();");
        w.line("}");
        w.blankLine();

        w.line("public static java.util.Optional<Parser> getOperatorParser(String ruleName) {");
        w.line("    return switch (ruleName) {");
        for (RuleDecl rule : sorted) {
            w.line("        case \"" + rule.name() + "\" -> java.util.Optional.of(Parser.get("
                + rule.name() + "Parser.class));");
        }
        w.line("        default -> java.util.Optional.empty();");
        w.line("    };");
        w.line("}");
        w.blankLine();

        w.line("public static java.util.List<Parser> getOperatorParsersAtPrecedence(int precedence) {");
        w.line("    return getOperatorsAtPrecedence(precedence).stream()");
        w.line("        .map(OperatorSpec::ruleName)");
        w.line("        .map(rule -> getOperatorParser(rule).orElse(null))");
        w.line("        .filter(java.util.Objects::nonNull)");
        w.line("        .toList();");
        w.line("}");
        w.blankLine();

        w.line("public static java.util.Optional<Parser> getLowestPrecedenceParser() {");
        w.line("    return getLowestPrecedenceOperator()");
        w.line("        .flatMap(spec -> getOperatorParser(spec.ruleName()));");
        w.line("}");
        w.blankLine();

        return w.build();
    }

    static String generateAdvancedAnnotationMetadata(GrammarDecl grammar) {
        boolean hasInterleave = grammar.rules().stream().anyMatch(ParserMetadataEmitter::hasInterleaveAnnotation);
        boolean hasBackref = grammar.rules().stream().anyMatch(ParserMetadataEmitter::hasBackrefAnnotation);
        boolean hasScopeTree = grammar.rules().stream().anyMatch(ParserMetadataEmitter::hasScopeTreeAnnotation);
        if (!hasInterleave && !hasBackref && !hasScopeTree) {
            return "";
        }
        IndentedWriter w = new IndentedWriter(1);
        if (hasInterleave) {
            w.line("public static java.util.Optional<String> getInterleaveProfile(String ruleName) {");
            w.line("    return switch (ruleName) {");
            for (RuleDecl rule : grammar.rules()) {
                String value = findInterleaveProfile(rule);
                if (value != null) {
                    w.line("        case \"" + rule.name() + "\" -> java.util.Optional.of(\""
                        + ParserCodegenUtil.escapeJava(value) + "\");");
                }
            }
            w.line("        default -> java.util.Optional.empty();");
            w.line("    };");
            w.line("}");
            w.blankLine();
        }
        if (hasBackref) {
            w.line("public static java.util.Optional<String> getBackrefName(String ruleName) {");
            w.line("    return switch (ruleName) {");
            for (RuleDecl rule : grammar.rules()) {
                String value = findBackrefName(rule);
                if (value != null) {
                    w.line("        case \"" + rule.name() + "\" -> java.util.Optional.of(\""
                        + ParserCodegenUtil.escapeJava(value) + "\");");
                }
            }
            w.line("        default -> java.util.Optional.empty();");
            w.line("    };");
            w.line("}");
            w.blankLine();
        }
        if (hasScopeTree) {
            w.line("public enum ScopeMode { LEXICAL, DYNAMIC }");
            w.blankLine();
            w.line("public record ScopeTreeSpec(String ruleName, String scopeId, ScopeMode mode) {}");
            w.blankLine();
            w.line("public static java.util.Optional<String> getScopeTreeMode(String ruleName) {");
            w.line("    return switch (ruleName) {");
            for (RuleDecl rule : grammar.rules()) {
                String value = findScopeTreeMode(rule);
                if (value != null) {
                    w.line("        case \"" + rule.name() + "\" -> java.util.Optional.of(\""
                        + ParserCodegenUtil.escapeJava(value) + "\");");
                }
            }
            w.line("        default -> java.util.Optional.empty();");
            w.line("    };");
            w.line("}");
            w.blankLine();

            w.line("public static java.util.Optional<ScopeMode> getScopeTreeModeEnum(String ruleName) {");
            w.line("    return getScopeTreeMode(ruleName)");
            w.line("        .map(String::trim)");
            w.line("        .map(String::toLowerCase)");
            w.line("        .flatMap(mode -> switch (mode) {");
            w.line("            case \"lexical\" -> java.util.Optional.of(ScopeMode.LEXICAL);");
            w.line("            case \"dynamic\" -> java.util.Optional.of(ScopeMode.DYNAMIC);");
            w.line("            default -> java.util.Optional.empty();");
            w.line("        });");
            w.line("}");
            w.blankLine();

            w.line("public static boolean isLexicalScopeTreeRule(String ruleName) {");
            w.line("    return getScopeTreeModeEnum(ruleName)");
            w.line("        .map(mode -> mode == ScopeMode.LEXICAL)");
            w.line("        .orElse(false);");
            w.line("}");
            w.blankLine();

            w.line("public static boolean isDynamicScopeTreeRule(String ruleName) {");
            w.line("    return getScopeTreeModeEnum(ruleName)");
            w.line("        .map(mode -> mode == ScopeMode.DYNAMIC)");
            w.line("        .orElse(false);");
            w.line("}");
            w.blankLine();

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

            w.line("public static java.util.List<String> getScopeTreeRules() {");
            w.line("    java.util.ArrayList<String> out = new java.util.ArrayList<>();");
            w.line("    out.addAll(getScopeTreeRules(ScopeMode.LEXICAL));");
            w.line("    out.addAll(getScopeTreeRules(ScopeMode.DYNAMIC));");
            w.line("    return java.util.List.copyOf(out);");
            w.line("}");
            w.blankLine();

            w.line("public static java.util.List<String> getScopeTreeRules(ScopeMode mode) {");
            w.line("    return switch (mode) {");
            w.raw("            case LEXICAL -> "
                + ParserCodegenUtil.renderStringListLiteral(lexicalRules)
                + ";\n");
            w.raw("            case DYNAMIC -> "
                + ParserCodegenUtil.renderStringListLiteral(dynamicRules)
                + ";\n");
            w.line("    };");
            w.line("}");
            w.blankLine();

            w.line("public static java.util.Map<String, ScopeMode> getScopeTreeModeByRule() {");
            w.line("    java.util.LinkedHashMap<String, ScopeMode> map = new java.util.LinkedHashMap<>();");
            w.line("    for (String rule : getScopeTreeRules(ScopeMode.LEXICAL)) {");
            w.line("        map.put(rule, ScopeMode.LEXICAL);");
            w.line("    }");
            w.line("    for (String rule : getScopeTreeRules(ScopeMode.DYNAMIC)) {");
            w.line("        map.put(rule, ScopeMode.DYNAMIC);");
            w.line("    }");
            w.line("    return java.util.Map.copyOf(map);");
            w.line("}");
            w.blankLine();

            w.line("public static java.util.Map<String, String> getScopeTreeModeNameByRule() {");
            w.line("    java.util.LinkedHashMap<String, String> map = new java.util.LinkedHashMap<>();");
            w.line("    for (java.util.Map.Entry<String, ScopeMode> e : getScopeTreeModeByRule().entrySet()) {");
            w.line("        map.put(e.getKey(), e.getValue().name().toLowerCase(java.util.Locale.ROOT));");
            w.line("    }");
            w.line("    return java.util.Map.copyOf(map);");
            w.line("}");
            w.blankLine();

            w.line("public static String getScopeIdForRule(String ruleName) {");
            w.line("    return \"scope:" + ParserCodegenUtil.escapeJava(grammar.name()) + "::\" + ruleName;");
            w.line("}");
            w.blankLine();

            w.line("public static java.util.Optional<ScopeTreeSpec> getScopeTreeSpec(String ruleName) {");
            w.line("    return getScopeTreeModeEnum(ruleName)");
            w.line("        .map(mode -> new ScopeTreeSpec(ruleName, getScopeIdForRule(ruleName), mode));");
            w.line("}");
            w.blankLine();

            w.line("public static java.util.List<ScopeTreeSpec> getScopeTreeSpecs() {");
            w.line("    return getScopeTreeRules().stream()");
            w.line("        .map(rule -> getScopeTreeSpec(rule).orElse(null))");
            w.line("        .filter(java.util.Objects::nonNull)");
            w.line("        .toList();");
            w.line("}");
            w.blankLine();

            w.line("public static java.util.Map<String, ScopeTreeSpec> getScopeTreeSpecByRule() {");
            w.line("    java.util.LinkedHashMap<String, ScopeTreeSpec> map = new java.util.LinkedHashMap<>();");
            w.line("    for (ScopeTreeSpec spec : getScopeTreeSpecs()) {");
            w.line("        map.put(spec.ruleName(), spec);");
            w.line("    }");
            w.line("    return java.util.Map.copyOf(map);");
            w.line("}");
            w.blankLine();

            w.line("public static java.util.Map<String, ScopeTreeSpec> getScopeTreeSpecByScopeId() {");
            w.line("    java.util.LinkedHashMap<String, ScopeTreeSpec> map = new java.util.LinkedHashMap<>();");
            w.line("    for (ScopeTreeSpec spec : getScopeTreeSpecs()) {");
            w.line("        map.put(spec.scopeId(), spec);");
            w.line("    }");
            w.line("    return java.util.Map.copyOf(map);");
            w.line("}");
            w.blankLine();

            w.line("public static java.util.Map<String, ScopeMode> getScopeTreeModeByScopeId() {");
            w.line("    java.util.LinkedHashMap<String, ScopeMode> map = new java.util.LinkedHashMap<>();");
            w.line("    for (ScopeTreeSpec spec : getScopeTreeSpecs()) {");
            w.line("        map.put(spec.scopeId(), spec.mode());");
            w.line("    }");
            w.line("    return java.util.Map.copyOf(map);");
            w.line("}");
            w.blankLine();

            w.line("public static java.util.Map<String, String> getScopeTreeModeNameByScopeId() {");
            w.line("    java.util.LinkedHashMap<String, String> map = new java.util.LinkedHashMap<>();");
            w.line("    for (java.util.Map.Entry<String, ScopeMode> e : getScopeTreeModeByScopeId().entrySet()) {");
            w.line("        map.put(e.getKey(), e.getValue().name().toLowerCase(java.util.Locale.ROOT));");
            w.line("    }");
            w.line("    return java.util.Map.copyOf(map);");
            w.line("}");
            w.blankLine();

            w.line("public static java.util.List<Object> buildSyntheticScopeEventsForNodes(java.util.List<Object> nodes) {");
            w.line("    return emitSyntheticScopeEventsForRulesAnyMode(");
            w.line("        \"" + ParserCodegenUtil.escapeJava(grammar.name()) + "\",");
            w.line("        getScopeTreeModeByRule(),");
            w.line("        nodes");
            w.line("    );");
            w.line("}");
            w.blankLine();

            w.line("public static java.util.List<Object> buildSyntheticScopeEventsForNodes(");
            w.line("    java.util.List<Object> nodes,");
            w.line("    java.util.Map<String, ?> modeOverridesByRule");
            w.line(") {");
            w.line("    java.util.LinkedHashMap<String, Object> merged = new java.util.LinkedHashMap<>();");
            w.line("    merged.putAll(getScopeTreeModeByRule());");
            w.line("    if (modeOverridesByRule != null) {");
            w.line("        merged.putAll(modeOverridesByRule);");
            w.line("    }");
            w.line("    return emitSyntheticScopeEventsForRulesAnyMode(");
            w.line("        \"" + ParserCodegenUtil.escapeJava(grammar.name()) + "\",");
            w.line("        merged,");
            w.line("        nodes");
            w.line("    );");
            w.line("}");
            w.blankLine();

            w.line("public static java.util.List<Object> buildSyntheticScopeEventsForNodesByScopeId(java.util.List<Object> nodes) {");
            w.line("    return emitSyntheticScopeEventsForScopeIdsAnyMode(");
            w.line("        getScopeTreeModeNameByScopeId(),");
            w.line("        nodes");
            w.line("    );");
            w.line("}");
            w.blankLine();

            w.line("private static java.util.List<Object> emitSyntheticScopeEventsForRulesAnyMode(");
            w.line("    String grammarName,");
            w.line("    java.util.Map<String, ?> modeByRule,");
            w.line("    java.util.List<Object> nodes");
            w.line(") {");
            w.line("    return org.unlaxer.dsl.ir.ParserIrScopeEvents.emitSyntheticEnterLeaveEventsForRulesAnyMode(");
            w.line("        grammarName,");
            w.line("        modeByRule,");
            w.line("        nodes");
            w.line("    );");
            w.line("}");
            w.blankLine();

            w.line("private static java.util.List<Object> emitSyntheticScopeEventsForScopeIdsAnyMode(");
            w.line("    java.util.Map<String, String> modeByScopeId,");
            w.line("    java.util.List<Object> nodes");
            w.line(") {");
            w.line("    return org.unlaxer.dsl.ir.ParserIrScopeEvents.emitSyntheticEnterLeaveEventsForScopeIdsAnyMode(");
            w.line("        modeByScopeId,");
            w.line("        nodes");
            w.line("    );");
            w.line("}");
            w.blankLine();

            w.line("public static boolean hasScopeTree(String ruleName) {");
            w.line("    return getScopeTreeModeByRule().containsKey(ruleName);");
            w.line("}");
            w.blankLine();

            w.line("public static ScopeMode getScopeTreeModeOrDefault(String ruleName, ScopeMode fallback) {");
            w.line("    return getScopeTreeModeByRule().getOrDefault(ruleName, fallback);");
            w.line("}");
            w.blankLine();
        }
        return w.build();
    }

    static boolean hasInterleaveAnnotation(RuleDecl rule) {
        return rule.annotations().stream().anyMatch(a -> a instanceof InterleaveAnnotation);
    }

    static boolean hasBackrefAnnotation(RuleDecl rule) {
        return rule.annotations().stream().anyMatch(a -> a instanceof BackrefAnnotation);
    }

    static boolean hasScopeTreeAnnotation(RuleDecl rule) {
        return rule.annotations().stream().anyMatch(a -> a instanceof ScopeTreeAnnotation);
    }

    static String findInterleaveProfile(RuleDecl rule) {
        return rule.annotations().stream()
            .filter(a -> a instanceof InterleaveAnnotation)
            .map(a -> (InterleaveAnnotation) a)
            .map(InterleaveAnnotation::profile)
            .findFirst()
            .orElse(null);
    }

    static String findBackrefName(RuleDecl rule) {
        return rule.annotations().stream()
            .filter(a -> a instanceof BackrefAnnotation)
            .map(a -> (BackrefAnnotation) a)
            .map(BackrefAnnotation::name)
            .findFirst()
            .orElse(null);
    }

    static String findScopeTreeMode(RuleDecl rule) {
        return rule.annotations().stream()
            .filter(a -> a instanceof ScopeTreeAnnotation)
            .map(a -> (ScopeTreeAnnotation) a)
            .map(ScopeTreeAnnotation::mode)
            .findFirst()
            .orElse(null);
    }

    static boolean hasAssocAnnotation(RuleDecl rule) {
        return rule.annotations().stream().anyMatch(a ->
            a instanceof LeftAssocAnnotation || a instanceof RightAssocAnnotation);
    }

    static String getAssocName(RuleDecl rule) {
        boolean right = rule.annotations().stream().anyMatch(a -> a instanceof RightAssocAnnotation);
        return right ? "RIGHT" : "LEFT";
    }
}
