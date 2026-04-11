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

    static String generateAdvancedAnnotationMetadata(GrammarDecl grammar) {
        boolean hasInterleave = grammar.rules().stream().anyMatch(ParserMetadataEmitter::hasInterleaveAnnotation);
        boolean hasBackref = grammar.rules().stream().anyMatch(ParserMetadataEmitter::hasBackrefAnnotation);
        boolean hasScopeTree = grammar.rules().stream().anyMatch(ParserMetadataEmitter::hasScopeTreeAnnotation);
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
                        .append(ParserCodegenUtil.escapeJava(value)).append("\");\n");
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
                        .append(ParserCodegenUtil.escapeJava(value)).append("\");\n");
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
                        .append(ParserCodegenUtil.escapeJava(value)).append("\");\n");
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
                .append(ParserCodegenUtil.renderStringListLiteral(lexicalRules))
                .append(";\n")
                .append("            case DYNAMIC -> ")
                .append(ParserCodegenUtil.renderStringListLiteral(dynamicRules))
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
                .append("        return \"scope:").append(ParserCodegenUtil.escapeJava(grammar.name())).append("::\" + ruleName;\n")
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
                .append("            \"").append(ParserCodegenUtil.escapeJava(grammar.name())).append("\",\n")
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
                .append("            \"").append(ParserCodegenUtil.escapeJava(grammar.name())).append("\",\n")
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
