package org.unlaxer.dsl.codegen;

import org.unlaxer.dsl.bootstrap.UBNFAST.AtomicElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.bootstrap.UBNFAST.MappingAnnotation;
import org.unlaxer.dsl.bootstrap.UBNFAST.RuleDecl;
import org.unlaxer.dsl.bootstrap.UBNFAST.TokenDecl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 各ルールに対応するマッピングメソッド・ fold ヘルパー・ユーティリティメソッドの
 * ソースコードを生成する。
 */
class MapperRuleEmitter {

    private MapperRuleEmitter() {}

    /**
     * mapToken() メソッドを生成する。
     */
    static void emitMapTokenMethod(StringBuilder sb, String astClass, String parsersClass,
            Map<String, List<RuleDecl>> allMappingRules) {
        sb.append("    private static ").append(astClass).append(" mapToken(Token token) {\n");
        sb.append("        if (token == null) {\n");
        sb.append("            return null;\n");
        sb.append("        }\n");
        // Include ALL parser classes that map to each AST class
        Set<String> emittedParserClasses = new LinkedHashSet<>();
        for (Map.Entry<String, List<RuleDecl>> entry : allMappingRules.entrySet()) {
            String className = entry.getKey();
            for (RuleDecl rule : entry.getValue()) {
                String parserClassKey = rule.name() + "Parser";
                if (emittedParserClasses.add(parserClassKey)) {
                    sb.append("        if (token.parser.getClass() == ").append(parsersClass).append(".")
                        .append(rule.name()).append("Parser.class) {\n");
                    sb.append("            return to").append(className).append("(token);\n");
                    sb.append("        }\n");
                }
            }
        }
        sb.append("        return null;\n");
        sb.append("    }\n\n");
    }

    /**
     * findBestMappedToken 関連メソッドと MappingCandidate 内部クラスを生成する。
     */
    static void emitFindBestMappedToken(StringBuilder sb, String astClass) {
        sb.append("    private static Token findBestMappedToken(Token token, String preferredAstSimpleName) {\n");
        sb.append("        MappingCandidate best = findBestMappedToken(token, 0, null, preferredAstSimpleName);\n");
        sb.append("        return best == null ? null : best.token;\n");
        sb.append("    }\n\n");

        sb.append("    private static MappingCandidate findBestMappedToken(Token token, int depth, MappingCandidate best, String preferredAstSimpleName) {\n");
        sb.append("        if (token == null) {\n");
        sb.append("            return best;\n");
        sb.append("        }\n");
        sb.append("        ").append(astClass).append(" mapped = mapToken(token);\n");
        sb.append("        if (mapped != null) {\n");
        sb.append("            boolean preferred = preferredAstSimpleName == null\n");
        sb.append("                || preferredAstSimpleName.isBlank()\n");
        sb.append("                || mapped.getClass().getSimpleName().equals(preferredAstSimpleName);\n");
        sb.append("            MappingCandidate candidate = new MappingCandidate(token, depth, tokenStartOffsetCompat(token), preferred);\n");
        sb.append("            best = betterCandidate(best, candidate);\n");
        sb.append("        }\n");
        sb.append("        for (Token child : token.filteredChildren) {\n");
        sb.append("            best = findBestMappedToken(child, depth + 1, best, preferredAstSimpleName);\n");
        sb.append("        }\n");
        sb.append("        return best;\n");
        sb.append("    }\n\n");

        sb.append("    private static MappingCandidate betterCandidate(MappingCandidate current, MappingCandidate candidate) {\n");
        sb.append("        if (candidate == null) {\n");
        sb.append("            return current;\n");
        sb.append("        }\n");
        sb.append("        if (current == null) {\n");
        sb.append("            return candidate;\n");
        sb.append("        }\n");
        sb.append("        if (candidate.preferred != current.preferred) {\n");
        sb.append("            return candidate.preferred ? candidate : current;\n");
        sb.append("        }\n");
        sb.append("        if (candidate.depth < current.depth) {\n");
        sb.append("            return candidate;\n");
        sb.append("        }\n");
        sb.append("        if (candidate.depth > current.depth) {\n");
        sb.append("            return current;\n");
        sb.append("        }\n");
        sb.append("        return candidate.startOffset >= current.startOffset ? candidate : current;\n");
        sb.append("    }\n\n");

        sb.append("    private static final class MappingCandidate {\n");
        sb.append("        private final Token token;\n");
        sb.append("        private final int depth;\n");
        sb.append("        private final int startOffset;\n");
        sb.append("        private final boolean preferred;\n\n");
        sb.append("        private MappingCandidate(Token token, int depth, int startOffset, boolean preferred) {\n");
        sb.append("            this.token = token;\n");
        sb.append("            this.depth = depth;\n");
        sb.append("            this.startOffset = startOffset;\n");
        sb.append("            this.preferred = preferred;\n");
        sb.append("        }\n");
        sb.append("    }\n\n");
    }

    /**
     * 各 mapping ルールに対応する toXxx() メソッドを生成する。
     */
    static void emitMappingMethods(StringBuilder sb, GrammarDecl grammar, String astClass, String parsersClass,
            Map<String, RuleDecl> mappingRules, Map<String, List<RuleDecl>> allMappingRules,
            Map<String, String> mappedClassByRuleName,
            Map<String, TokenDecl> tokenDeclByName, Map<String, RuleDecl> ruleByName) {

        sb.append("    // =========================================================================\n");
        sb.append("    // Mapping Methods\n");
        sb.append("    // =========================================================================\n\n");

        for (Map.Entry<String, RuleDecl> entry : mappingRules.entrySet()) {
            String className = entry.getKey();
            RuleDecl rule = entry.getValue();
            MappingAnnotation mapping = MapperElementUtil.getMappingAnnotation(rule).orElseThrow();
            boolean leftAssoc = MapperElementUtil.isLeftAssocRule(rule, mapping);
            boolean rightAssoc = MapperElementUtil.isRightAssocRule(rule, mapping);

            sb.append("    static ").append(astClass).append(".").append(className)
              .append(" to").append(className).append("(Token token) {\n");

            if (leftAssoc || rightAssoc) {
                emitAssocMappingBody(sb, grammar, astClass, parsersClass, className, rule, mapping,
                    rightAssoc, allMappingRules, mappedClassByRuleName, tokenDeclByName, ruleByName);
            } else {
                emitPlainMappingBody(sb, grammar, astClass, parsersClass, className, rule, mapping,
                    mappedClassByRuleName, tokenDeclByName, ruleByName);
            }

            sb.append("    }\n\n");
        }
    }

    private static void emitAssocMappingBody(StringBuilder sb, GrammarDecl grammar,
            String astClass, String parsersClass, String className,
            RuleDecl rule, MappingAnnotation mapping, boolean rightAssoc,
            Map<String, List<RuleDecl>> allMappingRules,
            Map<String, String> mappedClassByRuleName,
            Map<String, TokenDecl> tokenDeclByName, Map<String, RuleDecl> ruleByName) {

        Optional<MapperElementUtil.AssocShape> assocShapeOpt =
            MapperElementUtil.findAssocShape(rule, "left", "op", "right");
        if (assocShapeOpt.isPresent()) {
            MapperElementUtil.AssocShape assocShape = assocShapeOpt.get();

            String leftType = MapperTypeResolver.inferType(grammar, rule, "left");
            String opType = MapperTypeResolver.unwrapListType(MapperTypeResolver.inferType(grammar, rule, "op")).orElse("String");
            String rightType = MapperTypeResolver.unwrapListType(MapperTypeResolver.inferType(grammar, rule, "right")).orElse("Object");
            boolean leafFallbackSupported =
                (astClass + "." + className).equals(leftType)
                && "String".equals(opType)
                && (astClass + "." + className).equals(rightType);

            String ruleParserClass = parsersClass + "." + rule.name() + "Parser.class";
            String repeatParserClass = parsersClass + "." + rule.name() + "Repeat" + assocShape.repeatIndex() + "Parser.class";
            String leftParserClass = MapperElementUtil.parserClassLiteral(assocShape.leftElement(), parsersClass, tokenDeclByName, ruleByName)
                .orElse(ruleParserClass);
            String opParserClass = MapperElementUtil.parserClassLiteral(assocShape.opElement(), parsersClass, tokenDeclByName, ruleByName)
                .orElse("org.unlaxer.parser.elementary.WordParser.class");
            String rightParserClass = MapperElementUtil.parserClassLiteral(assocShape.rightElement(), parsersClass, tokenDeclByName, ruleByName)
                .orElse(ruleParserClass);

            String leftMapper = MapperElementUtil.mapExpressionForElement(
                assocShape.leftElement(),
                "leftToken",
                mappedClassByRuleName,
                tokenDeclByName,
                ruleByName);
            String rightMapper = MapperElementUtil.mapExpressionForElement(
                assocShape.rightElement(),
                "rightToken",
                mappedClassByRuleName,
                tokenDeclByName,
                ruleByName);

            // Check for additional rules mapping to the same AST class
            List<RuleDecl> additionalRules = allMappingRules.getOrDefault(className, List.of())
                .stream().filter(r -> r != rule && (MapperElementUtil.isLeftAssocRule(r, MapperElementUtil.getMappingAnnotation(r).orElse(null))
                    || MapperElementUtil.isRightAssocRule(r, MapperElementUtil.getMappingAnnotation(r).orElse(null))))
                .toList();

            // Generate dispatch for additional rules first
            for (RuleDecl additionalRule : additionalRules) {
                emitAdditionalAssocRuleDispatch(sb, astClass, parsersClass, className,
                    additionalRule, leftType, opType, rightType, leafFallbackSupported,
                    tokenDeclByName, ruleByName);
            }

            sb.append("        Token working = token;\n");
            sb.append("        if (working.parser.getClass() != ").append(ruleParserClass).append(") {\n");
            sb.append("            working = findFirstDescendant(working, ").append(ruleParserClass).append(");\n");
            sb.append("        }\n");
            sb.append("        if (working == null) {\n");
            if (leafFallbackSupported) {
                sb.append("            String literal = stripQuotes(firstTokenText(token));\n");
                sb.append("            literal = literal == null ? \"\" : literal;\n");
                sb.append("            return registerNodeSourceSpan(new ").append(astClass).append(".").append(className)
                    .append("(null, List.of(literal), List.of()), token);\n");
            } else {
                sb.append("            throw new IllegalArgumentException(\"Mapping token not found for rule ").append(rule.name()).append("\");\n");
            }
            sb.append("        }\n");
            sb.append("        Token leftToken = findFirstDescendant(working, ").append(leftParserClass).append(");\n");
            sb.append("        if (leftToken == null) {\n");
            if (leafFallbackSupported) {
                sb.append("            String literal = stripQuotes(firstTokenText(working));\n");
                sb.append("            literal = literal == null ? \"\" : literal;\n");
                sb.append("            return registerNodeSourceSpan(new ").append(astClass).append(".").append(className)
                    .append("(null, List.of(literal), List.of()), working);\n");
            } else {
                sb.append("            throw new IllegalArgumentException(\"Left operand not found for rule ").append(rule.name()).append("\");\n");
            }
            sb.append("        }\n");
            sb.append("        ").append(leftType).append(" left = ").append(leftMapper).append(";\n");
            sb.append("        List<").append(opType).append("> ops = new ArrayList<>();\n");
            sb.append("        List<").append(rightType).append("> rights = new ArrayList<>();\n");
            sb.append("        for (Token repeatToken : findDirectDescendants(working, ").append(repeatParserClass).append(")) {\n");
            sb.append("            Token opToken = findFirstDescendant(repeatToken, ").append(opParserClass).append(");\n");
            sb.append("            String opValue = firstTokenText(opToken == null ? repeatToken : opToken);\n");
            sb.append("            if (opValue != null && !opValue.isEmpty()) {\n");
            sb.append("                ops.add(stripQuotes(opValue));\n");
            sb.append("            }\n");
            sb.append("            Token rightToken = findFirstDescendant(repeatToken, ").append(rightParserClass).append(");\n");
            sb.append("            if (rightToken != null) {\n");
            sb.append("                rights.add(").append(rightMapper).append(");\n");
            sb.append("            }\n");
            sb.append("        }\n");
            if (rightAssoc) {
                sb.append("        return registerNodeSourceSpan(foldRightAssoc").append(className).append("(left, ops, rights), working);\n");
            } else {
                sb.append("        return registerNodeSourceSpan(new ").append(astClass).append(".").append(className).append("(left, ops, rights), working);\n");
            }
        } else {
            sb.append("        throw new IllegalArgumentException(\"Unsupported assoc mapping shape for rule: ")
              .append(rule.name()).append("\");\n");
        }
    }

    private static void emitAdditionalAssocRuleDispatch(StringBuilder sb,
            String astClass, String parsersClass, String className,
            RuleDecl additionalRule, String leftType, String opType, String rightType,
            boolean leafFallbackSupported,
            Map<String, TokenDecl> tokenDeclByName, Map<String, RuleDecl> ruleByName) {

        String addRuleParserClass = parsersClass + "." + additionalRule.name() + "Parser.class";
        Optional<MapperElementUtil.AssocShape> addAssocShape =
            MapperElementUtil.findAssocShape(additionalRule, "left", "op", "right");
        if (addAssocShape.isPresent()) {
            MapperElementUtil.AssocShape addShape = addAssocShape.get();
            String addRepeatParserClass = parsersClass + "." + additionalRule.name() + "Repeat" + addShape.repeatIndex() + "Parser.class";
            String addLeftParserClass = MapperElementUtil.parserClassLiteral(addShape.leftElement(), parsersClass, tokenDeclByName, ruleByName)
                .orElse(addRuleParserClass);
            String addOpParserClass = MapperElementUtil.parserClassLiteral(addShape.opElement(), parsersClass, tokenDeclByName, ruleByName)
                .orElse("org.unlaxer.parser.elementary.WordParser.class");
            // For rules sharing the same @mapping class, left/right mappers
            // should call the shared mapping method recursively
            String addLeftMapper = "to" + className + "(addLeftToken)";
            String addRightMapper = "to" + className + "(addRightToken)";

            sb.append("        // Handle ").append(additionalRule.name()).append(" tokens (same @mapping class)\n");
            sb.append("        if (token.parser.getClass() == ").append(addRuleParserClass).append(") {\n");
            sb.append("            Token addLeftToken = findFirstDescendant(token, ").append(addLeftParserClass).append(");\n");
            sb.append("            if (addLeftToken == null) {\n");
            if (leafFallbackSupported) {
                sb.append("                String literal = stripQuotes(firstTokenText(token));\n");
                sb.append("                literal = literal == null ? \"\" : literal;\n");
                sb.append("                return registerNodeSourceSpan(new ").append(astClass).append(".").append(className)
                    .append("(null, List.of(literal), List.of()), token);\n");
            } else {
                sb.append("                throw new IllegalArgumentException(\"Left operand not found for rule ").append(additionalRule.name()).append("\");\n");
            }
            sb.append("            }\n");
            sb.append("            ").append(leftType).append(" addLeft = ").append(addLeftMapper).append(";\n");
            sb.append("            List<").append(opType).append("> addOps = new ArrayList<>();\n");
            sb.append("            List<").append(rightType).append("> addRights = new ArrayList<>();\n");
            sb.append("            for (Token addRepeatToken : findDirectDescendants(token, ").append(addRepeatParserClass).append(")) {\n");
            sb.append("                Token addOpToken = findFirstDescendant(addRepeatToken, ").append(addOpParserClass).append(");\n");
            sb.append("                String addOpValue = firstTokenText(addOpToken == null ? addRepeatToken : addOpToken);\n");
            sb.append("                if (addOpValue != null && !addOpValue.isEmpty()) {\n");
            sb.append("                    addOps.add(stripQuotes(addOpValue));\n");
            sb.append("                }\n");
            sb.append("                Token addRightToken = findFirstDescendant(addRepeatToken, ").append(addLeftParserClass).append(");\n");
            sb.append("                if (addRightToken != null) {\n");
            sb.append("                    addRights.add(").append(addRightMapper).append(");\n");
            sb.append("                }\n");
            sb.append("            }\n");
            sb.append("            if (addOps.isEmpty()) {\n");
            sb.append("                return registerNodeSourceSpan(new ").append(astClass).append(".").append(className)
                .append("(addLeft, List.of(), List.of()), token);\n");
            sb.append("            }\n");
            sb.append("            return registerNodeSourceSpan(new ").append(astClass).append(".").append(className)
                .append("(addLeft, addOps, addRights), token);\n");
            sb.append("        }\n");
        }
    }

    private static void emitPlainMappingBody(StringBuilder sb, GrammarDecl grammar,
            String astClass, String parsersClass, String className,
            RuleDecl rule, MappingAnnotation mapping,
            Map<String, String> mappedClassByRuleName,
            Map<String, TokenDecl> tokenDeclByName, Map<String, RuleDecl> ruleByName) {

        String ruleParserClass = parsersClass + "." + rule.name() + "Parser.class";
        Map<String, Integer> scalarCaptureIndexByParserClass = new LinkedHashMap<>();
        // Collect @typeof constraints: ownCaptureName -> referencedCaptureName
        Map<String, String> typeofConstraints = MapperElementUtil.collectTypeofConstraints(rule.body());
        for (String param : mapping.paramNames()) {
            String type = MapperTypeResolver.inferType(grammar, rule, param);
            List<AtomicElement> capturedElements = MapperElementUtil.findCapturedElements(rule.body(), param);
            if (capturedElements.isEmpty()) {
                sb.append("        ").append(type).append(" ").append(param)
                    .append(" = ").append(MapperTypeResolver.defaultValueForType(type)).append(";\n");
                continue;
            }

            Optional<String> listElementType = MapperTypeResolver.unwrapListType(type);
            if (listElementType.isPresent()) {
                emitListParam(sb, parsersClass, ruleParserClass, grammar, param, type,
                    listElementType.get(), capturedElements, mappedClassByRuleName, tokenDeclByName, ruleByName);
                continue;
            }

            Optional<String> optionalElementType = MapperTypeResolver.unwrapOptionalType(type);
            if (optionalElementType.isPresent()) {
                emitOptionalParam(sb, parsersClass, ruleParserClass, grammar, param, type,
                    optionalElementType.get(), capturedElements, scalarCaptureIndexByParserClass,
                    mappedClassByRuleName, tokenDeclByName, ruleByName);
                continue;
            }

            emitScalarParam(sb, parsersClass, ruleParserClass, grammar, param, type,
                capturedElements, scalarCaptureIndexByParserClass,
                mappedClassByRuleName, tokenDeclByName, ruleByName);
        }
        // Emit @typeof runtime assertions
        for (Map.Entry<String, String> constraint : typeofConstraints.entrySet()) {
            String ownCapture = constraint.getKey();
            String refCapture = constraint.getValue();
            sb.append("        if (").append(refCapture).append(" != null && ")
                .append(ownCapture).append(" != null && !")
                .append(refCapture).append(".getClass().equals(")
                .append(ownCapture).append(".getClass())) {\n");
            sb.append("            throw new IllegalArgumentException(\"@typeof constraint violated: ")
                .append(ownCapture).append(" must be same type as ")
                .append(refCapture).append(", expected \" + ")
                .append(refCapture).append(".getClass().getSimpleName() + \" but got \" + ")
                .append(ownCapture).append(".getClass().getSimpleName());\n");
            sb.append("        }\n");
        }
        sb.append("        ").append(astClass).append(".").append(className).append(" mapped = new ")
            .append(astClass).append(".").append(className).append("(\n");
        for (int i = 0; i < mapping.paramNames().size(); i++) {
            String param = mapping.paramNames().get(i);
            String suffix = i < mapping.paramNames().size() - 1 ? "," : "";
            sb.append("            ").append(param).append(suffix)
                .append(" // ").append(param).append("\n");
        }
        sb.append("        );\n");
        sb.append("        return registerNodeSourceSpan(mapped, token);\n");
    }

    private static void emitListParam(StringBuilder sb, String parsersClass, String ruleParserClass,
            GrammarDecl grammar, String param, String type, String elementType,
            List<AtomicElement> capturedElements,
            Map<String, String> mappedClassByRuleName,
            Map<String, TokenDecl> tokenDeclByName, Map<String, RuleDecl> ruleByName) {

        sb.append("        List<").append(elementType).append("> ").append(param)
            .append(" = new ArrayList<>();\n");
        for (int i = 0; i < capturedElements.size(); i++) {
            AtomicElement element = capturedElements.get(i);
            AtomicElement normalized = MapperElementUtil.normalizeCapturedElement(element).orElse(element);
            String parserClass = MapperElementUtil.parserClassLiteral(normalized, parsersClass, tokenDeclByName, ruleByName)
                .orElse(ruleParserClass);
            String tokenVarName = "paramToken_" + MapperElementUtil.safeName(param) + "_" + i;
            String candidateType = MapperTypeResolver.inferTypeFromElement(grammar, normalized);
            if (!MapperTypeResolver.isTypeCompatible(elementType, candidateType) && !"String".equals(elementType)) {
                continue;
            }
            String mapExpression = MapperElementUtil.mapExpressionForTargetType(
                elementType,
                normalized,
                tokenVarName,
                mappedClassByRuleName,
                tokenDeclByName,
                ruleByName);
            sb.append("        for (Token ").append(tokenVarName)
                .append(" : findDescendants(token, ").append(parserClass).append(")) {\n");
            sb.append("            ").append(param).append(".add(").append(mapExpression).append(");\n");
            sb.append("        }\n");
        }
    }

    private static void emitOptionalParam(StringBuilder sb, String parsersClass, String ruleParserClass,
            GrammarDecl grammar, String param, String type, String elementType,
            List<AtomicElement> capturedElements,
            Map<String, Integer> scalarCaptureIndexByParserClass,
            Map<String, String> mappedClassByRuleName,
            Map<String, TokenDecl> tokenDeclByName, Map<String, RuleDecl> ruleByName) {

        sb.append("        Optional<").append(elementType).append("> ").append(param)
            .append(" = Optional.empty();\n");
        sb.append("        boolean found_").append(MapperElementUtil.safeName(param)).append(" = false;\n");
        for (int i = 0; i < capturedElements.size(); i++) {
            AtomicElement element = capturedElements.get(i);
            AtomicElement normalized = MapperElementUtil.normalizeCapturedElement(element).orElse(element);
            String parserClass = MapperElementUtil.parserClassLiteral(normalized, parsersClass, tokenDeclByName, ruleByName)
                .orElse(ruleParserClass);
            int parserOccurrenceIndex =
                scalarCaptureIndexByParserClass.getOrDefault(parserClass, 0);
            scalarCaptureIndexByParserClass.put(parserClass, parserOccurrenceIndex + 1);
            String tokenVarName = "paramToken_" + MapperElementUtil.safeName(param) + "_" + i;
            String candidateType = MapperTypeResolver.inferTypeFromElement(grammar, normalized);
            if (!MapperTypeResolver.isTypeCompatible(elementType, candidateType) && !"String".equals(elementType)) {
                continue;
            }
            String mapExpression = MapperElementUtil.mapExpressionForTargetType(
                elementType,
                normalized,
                tokenVarName,
                mappedClassByRuleName,
                tokenDeclByName,
                ruleByName);
            sb.append("        if (!found_").append(MapperElementUtil.safeName(param)).append(") {\n");
            sb.append("            Token ").append(tokenVarName)
                .append(" = findDescendantByIndex(token, ").append(parserClass).append(", ")
                .append(parserOccurrenceIndex).append(");\n");
            sb.append("            if (").append(tokenVarName).append(" != null) {\n");
            sb.append("                ").append(param).append(" = Optional.ofNullable(").append(mapExpression).append(");\n");
            sb.append("                found_").append(MapperElementUtil.safeName(param)).append(" = true;\n");
            sb.append("            }\n");
            sb.append("        }\n");
        }
    }

    private static void emitScalarParam(StringBuilder sb, String parsersClass, String ruleParserClass,
            GrammarDecl grammar, String param, String type,
            List<AtomicElement> capturedElements,
            Map<String, Integer> scalarCaptureIndexByParserClass,
            Map<String, String> mappedClassByRuleName,
            Map<String, TokenDecl> tokenDeclByName, Map<String, RuleDecl> ruleByName) {

        sb.append("        ").append(type).append(" ").append(param)
            .append(" = ").append(MapperTypeResolver.defaultValueForType(type)).append(";\n");
        sb.append("        boolean assigned_").append(MapperElementUtil.safeName(param)).append(" = false;\n");
        for (int i = 0; i < capturedElements.size(); i++) {
            AtomicElement element = capturedElements.get(i);
            AtomicElement normalized = MapperElementUtil.normalizeCapturedElement(element).orElse(element);
            String parserClass = MapperElementUtil.parserClassLiteral(normalized, parsersClass, tokenDeclByName, ruleByName)
                .orElse(ruleParserClass);
            int parserOccurrenceIndex =
                scalarCaptureIndexByParserClass.getOrDefault(parserClass, 0);
            scalarCaptureIndexByParserClass.put(parserClass, parserOccurrenceIndex + 1);
            String tokenVarName = "paramToken_" + MapperElementUtil.safeName(param) + "_" + i;
            String candidateType = MapperTypeResolver.inferTypeFromElement(grammar, normalized);
            if (!MapperTypeResolver.isTypeCompatible(type, candidateType) && !"String".equals(type)) {
                continue;
            }
            String mapExpression = MapperElementUtil.mapExpressionForTargetType(
                type,
                normalized,
                tokenVarName,
                mappedClassByRuleName,
                tokenDeclByName,
                ruleByName);
            sb.append("        if (!assigned_").append(MapperElementUtil.safeName(param)).append(") {\n");
            sb.append("            Token ").append(tokenVarName)
                .append(" = findDescendantByIndex(token, ").append(parserClass).append(", ")
                .append(parserOccurrenceIndex).append(");\n");
            sb.append("            if (").append(tokenVarName).append(" != null) {\n");
                sb.append("                ").append(param).append(" = ").append(mapExpression).append(";\n");
            sb.append("                assigned_").append(MapperElementUtil.safeName(param)).append(" = true;\n");
            sb.append("            }\n");
            sb.append("        }\n");
        }
    }

    /**
     * 右結合ルール用の fold ヘルパーメソッドを生成する。
     */
    static void emitFoldHelpers(StringBuilder sb, GrammarDecl grammar, String astClass,
            Map<String, RuleDecl> mappingRules) {

        sb.append("    // =========================================================================\n");
        sb.append("    // Fold Helpers (Right-Associative)\n");
        sb.append("    // =========================================================================\n\n");

        for (Map.Entry<String, RuleDecl> entry : mappingRules.entrySet()) {
            String className = entry.getKey();
            RuleDecl rule = entry.getValue();
            MappingAnnotation mapping = MapperElementUtil.getMappingAnnotation(rule).orElseThrow();
            boolean rightAssoc = MapperElementUtil.isRightAssocRule(rule, mapping);

            if (rightAssoc) {
                Optional<MapperElementUtil.AssocShape> assocShapeOpt =
                    MapperElementUtil.findAssocShape(rule, "left", "op", "right");
                if (assocShapeOpt.isPresent()) {
                    String leftType = MapperTypeResolver.inferType(grammar, rule, "left");
                    String opType = MapperTypeResolver.unwrapListType(MapperTypeResolver.inferType(grammar, rule, "op")).orElse("String");
                    String rightType = MapperTypeResolver.unwrapListType(MapperTypeResolver.inferType(grammar, rule, "right")).orElse("Object");

                    sb.append("    static ").append(astClass).append(".").append(className)
                      .append(" foldRightAssoc").append(className).append("(\n");
                    sb.append("            ").append(leftType).append(" left,\n");
                    sb.append("            java.util.List<").append(opType).append("> ops,\n");
                    sb.append("            java.util.List<").append(rightType).append("> rights) {\n");
                    sb.append("        if (ops.isEmpty() || rights.isEmpty()) {\n");
                    sb.append("            return new ").append(astClass).append(".").append(className)
                      .append("(left, ops, rights);\n");
                    sb.append("        }\n");
                    sb.append("        // Right-associative fold: a op b op c => a op (b op c)\n");
                    sb.append("        ").append(rightType).append(" right = rights.get(rights.size() - 1);\n");
                    sb.append("        ").append(opType).append(" op = ops.get(ops.size() - 1);\n");
                    sb.append("        java.util.List<").append(opType).append("> restOps = new java.util.ArrayList<>(ops);\n");
                    sb.append("        java.util.List<").append(rightType).append("> restRights = new java.util.ArrayList<>(rights);\n");
                    sb.append("        restOps.remove(restOps.size() - 1);\n");
                    sb.append("        restRights.remove(restRights.size() - 1);\n");
                    sb.append("        if (restRights.size() > 0) {\n");
                    sb.append("            right = foldRightAssoc").append(className).append("(right, restOps, restRights);\n");
                    sb.append("        }\n");
                    sb.append("        java.util.List<").append(opType).append("> singleOp = java.util.List.of(op);\n");
                    sb.append("        java.util.List<").append(rightType).append("> singleRight = java.util.List.of(right);\n");
                    sb.append("        return new ").append(astClass).append(".").append(className)
                      .append("(left, singleOp, singleRight);\n");
                    sb.append("    }\n\n");
                }
            }
        }
    }

    /**
     * ユーティリティメソッド群（findDescendants, firstTokenText 等）を生成する。
     */
    static void emitUtilities(StringBuilder sb) {
        sb.append("    // =========================================================================\n");
        sb.append("    // Utilities\n");
        sb.append("    // =========================================================================\n\n");

        sb.append("    static List<Token> findDescendants(Token token, Class<? extends Parser> parserClass) {\n");
        sb.append("        List<Token> results = new ArrayList<>();\n");
        sb.append("        if (token == null) {\n");
        sb.append("            return results;\n");
        sb.append("        }\n");
        sb.append("        for (Token child : token.filteredChildren) {\n");
        sb.append("            if (child.parser.getClass() == parserClass) {\n");
        sb.append("                results.add(child);\n");
        sb.append("            }\n");
        sb.append("            results.addAll(findDescendants(child, parserClass));\n");
        sb.append("        }\n");
        sb.append("        return results;\n");
        sb.append("    }\n\n");

        sb.append("    static List<Token> findDirectDescendants(Token token, Class<? extends Parser> parserClass) {\n");
        sb.append("        List<Token> results = new ArrayList<>();\n");
        sb.append("        if (token == null) {\n");
        sb.append("            return results;\n");
        sb.append("        }\n");
        sb.append("        for (Token child : token.filteredChildren) {\n");
        sb.append("            if (child.parser.getClass() == parserClass) {\n");
        sb.append("                results.add(child);\n");
        sb.append("            }\n");
        sb.append("        }\n");
        sb.append("        return results;\n");
        sb.append("    }\n\n");

        sb.append("    static Token findFirstDescendant(Token token, Class<? extends Parser> parserClass) {\n");
        sb.append("        if (token == null) {\n");
        sb.append("            return null;\n");
        sb.append("        }\n");
        sb.append("        if (token.parser.getClass() == parserClass) {\n");
        sb.append("            return token;\n");
        sb.append("        }\n");
        sb.append("        for (Token child : token.filteredChildren) {\n");
        sb.append("            Token found = findFirstDescendant(child, parserClass);\n");
        sb.append("            if (found != null) {\n");
        sb.append("                return found;\n");
        sb.append("            }\n");
        sb.append("        }\n");
        sb.append("        return null;\n");
        sb.append("    }\n\n");

        sb.append("    static Token findDescendantByIndex(Token token, Class<? extends Parser> parserClass, int index) {\n");
        sb.append("        if (index < 0) {\n");
        sb.append("            return null;\n");
        sb.append("        }\n");
        sb.append("        if (token != null && token.parser.getClass() == parserClass) {\n");
        sb.append("            if (index == 0) {\n");
        sb.append("                return token;\n");
        sb.append("            }\n");
        sb.append("            index = index - 1;\n");
        sb.append("        }\n");
        sb.append("        List<Token> descendants = findDescendants(token, parserClass);\n");
        sb.append("        if (index >= descendants.size()) {\n");
        sb.append("            return null;\n");
        sb.append("        }\n");
        sb.append("        return descendants.get(index);\n");
        sb.append("    }\n\n");

        sb.append("    static String firstTokenText(Token token) {\n");
        sb.append("        if (token == null) {\n");
        sb.append("            return null;\n");
        sb.append("        }\n");
        sb.append("        String raw = tokenTextCompat(token);\n");
        sb.append("        if (raw != null && !raw.isBlank()) {\n");
        sb.append("            return raw.strip();\n");
        sb.append("        }\n");
        sb.append("        for (Token child : token.filteredChildren) {\n");
        sb.append("            String found = firstTokenText(child);\n");
        sb.append("            if (found != null && !found.isEmpty()) {\n");
        sb.append("                return found;\n");
        sb.append("            }\n");
        sb.append("        }\n");
        sb.append("        return raw == null ? null : raw.strip();\n");
        sb.append("    }\n\n");

        sb.append("    static String tokenTextCompat(Token token) {\n");
        sb.append("        if (token == null) {\n");
        sb.append("            return null;\n");
        sb.append("        }\n");
        sb.append("        try {\n");
        sb.append("            java.lang.reflect.Method m = token.getClass().getMethod(\"getToken\");\n");
        sb.append("            Object value = m.invoke(token);\n");
        sb.append("            if (value instanceof Optional<?> optional && optional.isPresent()) {\n");
        sb.append("                Object v = optional.get();\n");
        sb.append("                return v == null ? null : String.valueOf(v);\n");
        sb.append("            }\n");
        sb.append("        } catch (Throwable ignored) {}\n");
        sb.append("        try {\n");
        sb.append("            java.lang.reflect.Field f = token.getClass().getField(\"tokenString\");\n");
        sb.append("            Object value = f.get(token);\n");
        sb.append("            if (value instanceof Optional<?> optional && optional.isPresent()) {\n");
        sb.append("                Object v = optional.get();\n");
        sb.append("                return v == null ? null : String.valueOf(v);\n");
        sb.append("            }\n");
        sb.append("        } catch (Throwable ignored) {}\n");
        sb.append("        try {\n");
        sb.append("            java.lang.reflect.Field f = token.getClass().getField(\"source\");\n");
        sb.append("            Object src = f.get(token);\n");
        sb.append("            if (src != null) {\n");
        sb.append("                java.lang.reflect.Method m = src.getClass().getMethod(\"sourceAsString\");\n");
        sb.append("                Object v = m.invoke(src);\n");
        sb.append("                return v == null ? null : String.valueOf(v);\n");
        sb.append("            }\n");
        sb.append("        } catch (Throwable ignored) {}\n");
        sb.append("        return null;\n");
        sb.append("    }\n\n");

        sb.append("    static int consumedLengthCompat(Token token) {\n");
        sb.append("        String text = tokenTextCompat(token);\n");
        sb.append("        return text == null ? 0 : text.length();\n");
        sb.append("    }\n\n");

        sb.append("    static int tokenStartOffsetCompat(Token token) {\n");
        sb.append("        if (token == null) {\n");
        sb.append("            return 0;\n");
        sb.append("        }\n");
        sb.append("        try {\n");
        sb.append("            java.lang.reflect.Field sourceField = token.getClass().getField(\"source\");\n");
        sb.append("            Object source = sourceField.get(token);\n");
        sb.append("            if (source == null) {\n");
        sb.append("                return 0;\n");
        sb.append("            }\n");
        sb.append("            java.lang.reflect.Method offsetMethod = source.getClass().getMethod(\"offsetFromRoot\");\n");
        sb.append("            Object offset = offsetMethod.invoke(source);\n");
        sb.append("            if (offset == null) {\n");
        sb.append("                return 0;\n");
        sb.append("            }\n");
        sb.append("            java.lang.reflect.Method valueMethod = offset.getClass().getMethod(\"value\");\n");
        sb.append("            Object value = valueMethod.invoke(offset);\n");
        sb.append("            if (value instanceof Integer i) {\n");
        sb.append("                return i;\n");
        sb.append("            }\n");
        sb.append("            if (value instanceof Number n) {\n");
        sb.append("                return n.intValue();\n");
        sb.append("            }\n");
        sb.append("        } catch (Throwable ignored) {}\n");
        sb.append("        return 0;\n");
        sb.append("    }\n\n");

        sb.append("    static <T> T registerNodeSourceSpan(T node, Token token) {\n");
        sb.append("        if (node == null || token == null) {\n");
        sb.append("            return node;\n");
        sb.append("        }\n");
        sb.append("        int start = Math.max(0, tokenStartOffsetCompat(token));\n");
        sb.append("        int length = Math.max(0, consumedLengthCompat(token));\n");
        sb.append("        int end = start + length;\n");
        sb.append("        NODE_SOURCE_SPANS.put(node, new int[]{start, end});\n");
        sb.append("        return node;\n");
        sb.append("    }\n\n");

        sb.append("    public static Optional<int[]> sourceSpanOf(Object node) {\n");
        sb.append("        if (node == null) {\n");
        sb.append("            return Optional.empty();\n");
        sb.append("        }\n");
        sb.append("        int[] span = NODE_SOURCE_SPANS.get(node);\n");
        sb.append("        if (span == null || span.length < 2) {\n");
        sb.append("            return Optional.empty();\n");
        sb.append("        }\n");
        sb.append("        return Optional.of(new int[]{span[0], span[1]});\n");
        sb.append("    }\n\n");

        sb.append("    static StringSource createRootSourceCompat(String source) {\n");
        sb.append("        try {\n");
        sb.append("            java.lang.reflect.Method m = StringSource.class.getMethod(\"createRootSource\", String.class);\n");
        sb.append("            Object v = m.invoke(null, source);\n");
        sb.append("            if (v instanceof StringSource s) {\n");
        sb.append("                return s;\n");
        sb.append("            }\n");
        sb.append("        } catch (Throwable ignored) {}\n");
        sb.append("        try {\n");
        sb.append("            for (java.lang.reflect.Constructor<?> c : StringSource.class.getDeclaredConstructors()) {\n");
        sb.append("                Class<?>[] types = c.getParameterTypes();\n");
        sb.append("                if (types.length == 0 || types[0] != String.class) {\n");
        sb.append("                    continue;\n");
        sb.append("                }\n");
        sb.append("                Object[] args = new Object[types.length];\n");
        sb.append("                args[0] = source;\n");
        sb.append("                c.setAccessible(true);\n");
        sb.append("                Object v = c.newInstance(args);\n");
        sb.append("                if (v instanceof StringSource s) {\n");
        sb.append("                    return s;\n");
        sb.append("                }\n");
        sb.append("            }\n");
        sb.append("        } catch (Throwable ignored) {}\n");
        sb.append("        throw new IllegalStateException(\"No compatible StringSource initializer found\");\n");
        sb.append("    }\n\n");

        sb.append("    static String stripQuotes(String quoted) {\n");
        sb.append("        if (quoted == null) {\n");
        sb.append("            return null;\n");
        sb.append("        }\n");
        sb.append("        if (quoted.length() >= 2\n");
        sb.append("            && '\\'' == quoted.charAt(0)\n");
        sb.append("            && '\\'' == quoted.charAt(quoted.length() - 1)) {\n");
        sb.append("            return quoted.substring(1, quoted.length() - 1);\n");
        sb.append("        }\n");
        sb.append("        return quoted;\n");
        sb.append("    }\n");
        sb.append("\n");
        sb.append("    static String identifierLikeText(Token token) {\n");
        sb.append("        if (token == null) {\n");
        sb.append("            return null;\n");
        sb.append("        }\n");
        sb.append("        String raw = tokenTextCompat(token);\n");
        sb.append("        String fromRaw = extractIdentifierLike(raw);\n");
        sb.append("        if (fromRaw != null) {\n");
        sb.append("            return fromRaw;\n");
        sb.append("        }\n");
        sb.append("        for (Token child : token.filteredChildren) {\n");
        sb.append("            String fromChild = identifierLikeText(child);\n");
        sb.append("            if (fromChild != null) {\n");
        sb.append("                return fromChild;\n");
        sb.append("            }\n");
        sb.append("        }\n");
        sb.append("        return extractIdentifierLike(firstTokenText(token));\n");
        sb.append("    }\n");
        sb.append("\n");
        sb.append("    static String extractIdentifierLike(String raw) {\n");
        sb.append("        if (raw == null) {\n");
        sb.append("            return null;\n");
        sb.append("        }\n");
        sb.append("        String text = raw.strip();\n");
        sb.append("        int start = -1;\n");
        sb.append("        int end = -1;\n");
        sb.append("        for (int i = 0; i < text.length(); i++) {\n");
        sb.append("            char c = text.charAt(i);\n");
        sb.append("            if (start < 0) {\n");
        sb.append("                if (Character.isLetter(c) || c == '_') {\n");
        sb.append("                    start = i;\n");
        sb.append("                    end = i + 1;\n");
        sb.append("                }\n");
        sb.append("                continue;\n");
        sb.append("            }\n");
        sb.append("            if (Character.isLetterOrDigit(c) || c == '_') {\n");
        sb.append("                end = i + 1;\n");
        sb.append("                continue;\n");
        sb.append("            }\n");
        sb.append("            break;\n");
        sb.append("        }\n");
        sb.append("        if (start < 0 || end <= start) {\n");
        sb.append("            return null;\n");
        sb.append("        }\n");
        sb.append("        return text.substring(start, end);\n");
        sb.append("    }\n");
    }
}
