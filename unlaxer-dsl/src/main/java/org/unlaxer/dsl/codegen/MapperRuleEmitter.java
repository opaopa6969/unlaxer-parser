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
    static String emitMapTokenMethod(String astClass, String parsersClass,
            Map<String, List<RuleDecl>> allMappingRules) {
        IndentedWriter w = new IndentedWriter(1);
        w.line("private static " + astClass + " mapToken(Token token) {");
        w.indent();
        w.line("if (token == null) {");
        w.indent();
        w.line("return null;");
        w.dedent();
        w.line("}");
        // Include ALL parser classes that map to each AST class
        Set<String> emittedParserClasses = new LinkedHashSet<>();
        for (Map.Entry<String, List<RuleDecl>> entry : allMappingRules.entrySet()) {
            String className = entry.getKey();
            for (RuleDecl rule : entry.getValue()) {
                String parserClassKey = rule.name() + "Parser";
                if (emittedParserClasses.add(parserClassKey)) {
                    w.line("if (token.parser.getClass() == " + parsersClass + "."
                        + rule.name() + "Parser.class) {");
                    w.indent();
                    w.line("return to" + MapperElementUtil.methodNameFor(className) + "(token);");
                    w.dedent();
                    w.line("}");
                }
            }
        }
        w.line("return null;");
        w.dedent();
        w.line("}");
        w.blankLine();
        return w.build();
    }

    /**
     * findBestMappedToken 関連メソッドと MappingCandidate 内部クラスを生成する。
     */
    static String emitFindBestMappedToken(String astClass) {
        IndentedWriter w = new IndentedWriter(1);
        w.line("private static Token findBestMappedToken(Token token, String preferredAstSimpleName) {");
        w.indent();
        w.line("MappingCandidate best = findBestMappedToken(token, 0, null, preferredAstSimpleName);");
        w.line("return best == null ? null : best.token;");
        w.dedent();
        w.line("}");
        w.blankLine();

        w.line("private static MappingCandidate findBestMappedToken(Token token, int depth, MappingCandidate best, String preferredAstSimpleName) {");
        w.indent();
        w.line("if (token == null) {");
        w.indent();
        w.line("return best;");
        w.dedent();
        w.line("}");
        w.line(astClass + " mapped = mapToken(token);");
        w.line("if (mapped != null) {");
        w.indent();
        w.line("boolean preferred = preferredAstSimpleName == null");
        w.line("    || preferredAstSimpleName.isBlank()");
        w.line("    || mapped.getClass().getSimpleName().equals(preferredAstSimpleName);");
        w.line("MappingCandidate candidate = new MappingCandidate(token, depth, tokenStartOffsetCompat(token), preferred);");
        w.line("best = betterCandidate(best, candidate);");
        w.dedent();
        w.line("}");
        w.line("for (Token child : token.filteredChildren) {");
        w.indent();
        w.line("best = findBestMappedToken(child, depth + 1, best, preferredAstSimpleName);");
        w.dedent();
        w.line("}");
        w.line("return best;");
        w.dedent();
        w.line("}");
        w.blankLine();

        w.line("private static MappingCandidate betterCandidate(MappingCandidate current, MappingCandidate candidate) {");
        w.indent();
        w.line("if (candidate == null) {");
        w.indent();
        w.line("return current;");
        w.dedent();
        w.line("}");
        w.line("if (current == null) {");
        w.indent();
        w.line("return candidate;");
        w.dedent();
        w.line("}");
        w.line("if (candidate.preferred != current.preferred) {");
        w.indent();
        w.line("return candidate.preferred ? candidate : current;");
        w.dedent();
        w.line("}");
        w.line("if (candidate.depth < current.depth) {");
        w.indent();
        w.line("return candidate;");
        w.dedent();
        w.line("}");
        w.line("if (candidate.depth > current.depth) {");
        w.indent();
        w.line("return current;");
        w.dedent();
        w.line("}");
        w.line("return candidate.startOffset >= current.startOffset ? candidate : current;");
        w.dedent();
        w.line("}");
        w.blankLine();

        w.line("private static final class MappingCandidate {");
        w.indent();
        w.line("private final Token token;");
        w.line("private final int depth;");
        w.line("private final int startOffset;");
        w.line("private final boolean preferred;");
        w.blankLine();
        w.line("private MappingCandidate(Token token, int depth, int startOffset, boolean preferred) {");
        w.indent();
        w.line("this.token = token;");
        w.line("this.depth = depth;");
        w.line("this.startOffset = startOffset;");
        w.line("this.preferred = preferred;");
        w.dedent();
        w.line("}");
        w.dedent();
        w.line("}");
        w.blankLine();
        return w.build();
    }

    /**
     * 各 mapping ルールに対応する toXxx() メソッドを生成する。
     */
    static String emitMappingMethods(GrammarDecl grammar, String astClass, String parsersClass,
            Map<String, RuleDecl> mappingRules, Map<String, List<RuleDecl>> allMappingRules,
            Map<String, String> mappedClassByRuleName,
            Map<String, TokenDecl> tokenDeclByName, Map<String, RuleDecl> ruleByName) {

        IndentedWriter w = new IndentedWriter(1);
        w.line("// =========================================================================");
        w.line("// Mapping Methods");
        w.line("// =========================================================================");
        w.blankLine();

        for (Map.Entry<String, RuleDecl> entry : mappingRules.entrySet()) {
            String className = entry.getKey();
            RuleDecl rule = entry.getValue();
            MappingAnnotation mapping = MapperElementUtil.getMappingAnnotation(rule).orElseThrow();
            boolean leftAssoc = MapperElementUtil.isLeftAssocRule(rule, mapping);
            boolean rightAssoc = MapperElementUtil.isRightAssocRule(rule, mapping);

            w.line("static " + astClass + "." + className
              + " to" + MapperElementUtil.methodNameFor(className) + "(Token token) {");
            w.indent();

            if (leftAssoc || rightAssoc) {
                emitAssocMappingBody(w, grammar, astClass, parsersClass, className, rule, mapping,
                    rightAssoc, allMappingRules, mappedClassByRuleName, tokenDeclByName, ruleByName);
            } else {
                emitPlainMappingBody(w, grammar, astClass, parsersClass, className, rule, mapping,
                    mappedClassByRuleName, tokenDeclByName, ruleByName);
            }

            w.dedent();
            w.line("}");
            w.blankLine();
        }
        return w.build();
    }

    private static void emitAssocMappingBody(IndentedWriter w, GrammarDecl grammar,
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
                emitAdditionalAssocRuleDispatch(w, astClass, parsersClass, className,
                    additionalRule, leftType, opType, rightType, leafFallbackSupported,
                    tokenDeclByName, ruleByName);
            }

            w.line("Token working = token;");
            w.line("if (working.parser.getClass() != " + ruleParserClass + ") {");
            w.indent();
            w.line("working = findFirstDescendant(working, " + ruleParserClass + ");");
            w.dedent();
            w.line("}");
            w.line("if (working == null) {");
            w.indent();
            if (leafFallbackSupported) {
                w.line("String literal = stripQuotes(firstTokenText(token));");
                w.line("literal = literal == null ? \"\" : literal;");
                w.line("return registerNodeSourceSpan(new " + astClass + "." + className
                    + "(null, List.of(literal), List.of()), token);");
            } else {
                w.line("throw new IllegalArgumentException(\"Mapping token not found for rule " + rule.name() + "\");");
            }
            w.dedent();
            w.line("}");
            w.line("Token leftToken = findFirstDescendant(working, " + leftParserClass + ");");
            w.line("if (leftToken == null) {");
            w.indent();
            if (leafFallbackSupported) {
                w.line("String literal = stripQuotes(firstTokenText(working));");
                w.line("literal = literal == null ? \"\" : literal;");
                w.line("return registerNodeSourceSpan(new " + astClass + "." + className
                    + "(null, List.of(literal), List.of()), working);");
            } else {
                w.line("throw new IllegalArgumentException(\"Left operand not found for rule " + rule.name() + "\");");
            }
            w.dedent();
            w.line("}");
            w.line(leftType + " left = " + leftMapper + ";");
            w.line("List<" + opType + "> ops = new ArrayList<>();");
            w.line("List<" + rightType + "> rights = new ArrayList<>();");
            w.line("for (Token repeatToken : findDirectDescendants(working, " + repeatParserClass + ")) {");
            w.indent();
            w.line("Token opToken = findFirstDescendant(repeatToken, " + opParserClass + ");");
            w.line("String opValue = firstTokenText(opToken == null ? repeatToken : opToken);");
            w.line("if (opValue != null && !opValue.isEmpty()) {");
            w.indent();
            w.line("ops.add(stripQuotes(opValue));");
            w.dedent();
            w.line("}");
            w.line("Token rightToken = findFirstDescendant(repeatToken, " + rightParserClass + ");");
            w.line("if (rightToken != null) {");
            w.indent();
            w.line("rights.add(" + rightMapper + ");");
            w.dedent();
            w.line("}");
            w.dedent();
            w.line("}");
            if (rightAssoc) {
                w.line("return registerNodeSourceSpan(foldRightAssoc" + MapperElementUtil.methodNameFor(className) + "(left, ops, rights), working);");
            } else {
                w.line("return registerNodeSourceSpan(new " + astClass + "." + className + "(left, ops, rights), working);");
            }
        } else {
            w.line("throw new IllegalArgumentException(\"Unsupported assoc mapping shape for rule: "
              + rule.name() + "\");");
        }
    }

    private static void emitAdditionalAssocRuleDispatch(IndentedWriter w,
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
            String addLeftMapper = "to" + MapperElementUtil.methodNameFor(className) + "(addLeftToken)";
            String addRightMapper = "to" + MapperElementUtil.methodNameFor(className) + "(addRightToken)";

            w.line("// Handle " + additionalRule.name() + " tokens (same @mapping class)");
            w.line("if (token.parser.getClass() == " + addRuleParserClass + ") {");
            w.indent();
            w.line("Token addLeftToken = findFirstDescendant(token, " + addLeftParserClass + ");");
            w.line("if (addLeftToken == null) {");
            w.indent();
            if (leafFallbackSupported) {
                w.line("String literal = stripQuotes(firstTokenText(token));");
                w.line("literal = literal == null ? \"\" : literal;");
                w.line("return registerNodeSourceSpan(new " + astClass + "." + className
                    + "(null, List.of(literal), List.of()), token);");
            } else {
                w.line("throw new IllegalArgumentException(\"Left operand not found for rule " + additionalRule.name() + "\");");
            }
            w.dedent();
            w.line("}");
            w.line(leftType + " addLeft = " + addLeftMapper + ";");
            w.line("List<" + opType + "> addOps = new ArrayList<>();");
            w.line("List<" + rightType + "> addRights = new ArrayList<>();");
            w.line("for (Token addRepeatToken : findDirectDescendants(token, " + addRepeatParserClass + ")) {");
            w.indent();
            w.line("Token addOpToken = findFirstDescendant(addRepeatToken, " + addOpParserClass + ");");
            w.line("String addOpValue = firstTokenText(addOpToken == null ? addRepeatToken : addOpToken);");
            w.line("if (addOpValue != null && !addOpValue.isEmpty()) {");
            w.indent();
            w.line("addOps.add(stripQuotes(addOpValue));");
            w.dedent();
            w.line("}");
            w.line("Token addRightToken = findFirstDescendant(addRepeatToken, " + addLeftParserClass + ");");
            w.line("if (addRightToken != null) {");
            w.indent();
            w.line("addRights.add(" + addRightMapper + ");");
            w.dedent();
            w.line("}");
            w.dedent();
            w.line("}");
            w.line("if (addOps.isEmpty()) {");
            w.indent();
            w.line("return registerNodeSourceSpan(new " + astClass + "." + className
                + "(addLeft, List.of(), List.of()), token);");
            w.dedent();
            w.line("}");
            w.line("return registerNodeSourceSpan(new " + astClass + "." + className
                + "(addLeft, addOps, addRights), token);");
            w.dedent();
            w.line("}");
        }
    }

    private static void emitPlainMappingBody(IndentedWriter w, GrammarDecl grammar,
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
                w.line(type + " " + param
                    + " = " + MapperTypeResolver.defaultValueForType(type) + ";");
                continue;
            }

            Optional<String> listElementType = MapperTypeResolver.unwrapListType(type);
            if (listElementType.isPresent()) {
                emitListParam(w, parsersClass, ruleParserClass, grammar, param, type,
                    listElementType.get(), capturedElements, mappedClassByRuleName, tokenDeclByName, ruleByName);
                continue;
            }

            Optional<String> optionalElementType = MapperTypeResolver.unwrapOptionalType(type);
            if (optionalElementType.isPresent()) {
                emitOptionalParam(w, parsersClass, ruleParserClass, grammar, param, type,
                    optionalElementType.get(), capturedElements, scalarCaptureIndexByParserClass,
                    mappedClassByRuleName, tokenDeclByName, ruleByName);
                continue;
            }

            emitScalarParam(w, parsersClass, ruleParserClass, grammar, param, type,
                capturedElements, scalarCaptureIndexByParserClass,
                mappedClassByRuleName, tokenDeclByName, ruleByName);
        }
        // Emit @typeof runtime assertions
        for (Map.Entry<String, String> constraint : typeofConstraints.entrySet()) {
            String ownCapture = constraint.getKey();
            String refCapture = constraint.getValue();
            w.line("if (" + refCapture + " != null && "
                + ownCapture + " != null && !"
                + refCapture + ".getClass().equals("
                + ownCapture + ".getClass())) {");
            w.indent();
            w.line("throw new IllegalArgumentException(\"@typeof constraint violated: "
                + ownCapture + " must be same type as "
                + refCapture + ", expected \" + "
                + refCapture + ".getClass().getSimpleName() + \" but got \" + "
                + ownCapture + ".getClass().getSimpleName());");
            w.dedent();
            w.line("}");
        }
        w.line(astClass + "." + className + " mapped = new "
            + astClass + "." + className + "(");
        for (int i = 0; i < mapping.paramNames().size(); i++) {
            String param = mapping.paramNames().get(i);
            String suffix = i < mapping.paramNames().size() - 1 ? "," : "";
            w.line("    " + param + suffix
                + " // " + param);
        }
        w.line(");");
        w.line("return registerNodeSourceSpan(mapped, token);");
    }

    private static void emitListParam(IndentedWriter w, String parsersClass, String ruleParserClass,
            GrammarDecl grammar, String param, String type, String elementType,
            List<AtomicElement> capturedElements,
            Map<String, String> mappedClassByRuleName,
            Map<String, TokenDecl> tokenDeclByName, Map<String, RuleDecl> ruleByName) {

        w.line("List<" + elementType + "> " + param
            + " = new ArrayList<>();");
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
            w.line("for (Token " + tokenVarName
                + " : findDescendants(token, " + parserClass + ")) {");
            w.indent();
            w.line(param + ".add(" + mapExpression + ");");
            w.dedent();
            w.line("}");
        }
    }

    private static void emitOptionalParam(IndentedWriter w, String parsersClass, String ruleParserClass,
            GrammarDecl grammar, String param, String type, String elementType,
            List<AtomicElement> capturedElements,
            Map<String, Integer> scalarCaptureIndexByParserClass,
            Map<String, String> mappedClassByRuleName,
            Map<String, TokenDecl> tokenDeclByName, Map<String, RuleDecl> ruleByName) {

        w.line("Optional<" + elementType + "> " + param
            + " = Optional.empty();");
        w.line("boolean found_" + MapperElementUtil.safeName(param) + " = false;");
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
            w.line("if (!found_" + MapperElementUtil.safeName(param) + ") {");
            w.indent();
            w.line("Token " + tokenVarName
                + " = findDescendantByIndex(token, " + parserClass + ", "
                + parserOccurrenceIndex + ");");
            w.line("if (" + tokenVarName + " != null) {");
            w.indent();
            w.line(param + " = Optional.ofNullable(" + mapExpression + ");");
            w.line("found_" + MapperElementUtil.safeName(param) + " = true;");
            w.dedent();
            w.line("}");
            w.dedent();
            w.line("}");
        }
    }

    private static void emitScalarParam(IndentedWriter w, String parsersClass, String ruleParserClass,
            GrammarDecl grammar, String param, String type,
            List<AtomicElement> capturedElements,
            Map<String, Integer> scalarCaptureIndexByParserClass,
            Map<String, String> mappedClassByRuleName,
            Map<String, TokenDecl> tokenDeclByName, Map<String, RuleDecl> ruleByName) {

        w.line(type + " " + param
            + " = " + MapperTypeResolver.defaultValueForType(type) + ";");
        w.line("boolean assigned_" + MapperElementUtil.safeName(param) + " = false;");
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
            w.line("if (!assigned_" + MapperElementUtil.safeName(param) + ") {");
            w.indent();
            w.line("Token " + tokenVarName
                + " = findDescendantByIndex(token, " + parserClass + ", "
                + parserOccurrenceIndex + ");");
            w.line("if (" + tokenVarName + " != null) {");
            w.indent();
            w.line(param + " = " + mapExpression + ";");
            w.line("assigned_" + MapperElementUtil.safeName(param) + " = true;");
            w.dedent();
            w.line("}");
            w.dedent();
            w.line("}");
        }
    }

    /**
     * 右結合ルール用の fold ヘルパーメソッドを生成する。
     */
    static String emitFoldHelpers(GrammarDecl grammar, String astClass,
            Map<String, RuleDecl> mappingRules) {

        IndentedWriter w = new IndentedWriter(1);
        w.line("// =========================================================================");
        w.line("// Fold Helpers (Right-Associative)");
        w.line("// =========================================================================");
        w.blankLine();

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

                    w.line("static " + astClass + "." + className
                      + " foldRightAssoc" + MapperElementUtil.methodNameFor(className) + "(");
                    w.line("        " + leftType + " left,");
                    w.line("        java.util.List<" + opType + "> ops,");
                    w.line("        java.util.List<" + rightType + "> rights) {");
                    w.indent();
                    w.line("if (ops.isEmpty() || rights.isEmpty()) {");
                    w.indent();
                    w.line("return new " + astClass + "." + className
                      + "(left, ops, rights);");
                    w.dedent();
                    w.line("}");
                    w.line("// Right-associative fold: a op b op c => a op (b op c)");
                    w.line(rightType + " right = rights.get(rights.size() - 1);");
                    w.line(opType + " op = ops.get(ops.size() - 1);");
                    w.line("java.util.List<" + opType + "> restOps = new java.util.ArrayList<>(ops);");
                    w.line("java.util.List<" + rightType + "> restRights = new java.util.ArrayList<>(rights);");
                    w.line("restOps.remove(restOps.size() - 1);");
                    w.line("restRights.remove(restRights.size() - 1);");
                    w.line("if (restRights.size() > 0) {");
                    w.indent();
                    w.line("right = foldRightAssoc" + MapperElementUtil.methodNameFor(className) + "(right, restOps, restRights);");
                    w.dedent();
                    w.line("}");
                    w.line("java.util.List<" + opType + "> singleOp = java.util.List.of(op);");
                    w.line("java.util.List<" + rightType + "> singleRight = java.util.List.of(right);");
                    w.line("return new " + astClass + "." + className
                      + "(left, singleOp, singleRight);");
                    w.dedent();
                    w.line("}");
                    w.blankLine();
                }
            }
        }
        return w.build();
    }

    /**
     * ユーティリティメソッド群（findDescendants, firstTokenText 等）を生成する。
     */
    static String emitUtilities() {
        IndentedWriter w = new IndentedWriter(1);
        w.line("// =========================================================================");
        w.line("// Utilities");
        w.line("// =========================================================================");
        w.blankLine();

        w.line("static List<Token> findDescendants(Token token, Class<? extends Parser> parserClass) {");
        w.indent();
        w.line("List<Token> results = new ArrayList<>();");
        w.line("if (token == null) {");
        w.indent();
        w.line("return results;");
        w.dedent();
        w.line("}");
        w.line("for (Token child : token.filteredChildren) {");
        w.indent();
        w.line("if (child.parser.getClass() == parserClass) {");
        w.indent();
        w.line("results.add(child);");
        w.dedent();
        w.line("}");
        w.line("results.addAll(findDescendants(child, parserClass));");
        w.dedent();
        w.line("}");
        w.line("return results;");
        w.dedent();
        w.line("}");
        w.blankLine();

        w.line("static List<Token> findDirectDescendants(Token token, Class<? extends Parser> parserClass) {");
        w.indent();
        w.line("List<Token> results = new ArrayList<>();");
        w.line("if (token == null) {");
        w.indent();
        w.line("return results;");
        w.dedent();
        w.line("}");
        w.line("for (Token child : token.filteredChildren) {");
        w.indent();
        w.line("if (child.parser.getClass() == parserClass) {");
        w.indent();
        w.line("results.add(child);");
        w.dedent();
        w.line("}");
        w.dedent();
        w.line("}");
        w.line("return results;");
        w.dedent();
        w.line("}");
        w.blankLine();

        w.line("static Token findFirstDescendant(Token token, Class<? extends Parser> parserClass) {");
        w.indent();
        w.line("if (token == null) {");
        w.indent();
        w.line("return null;");
        w.dedent();
        w.line("}");
        w.line("if (token.parser.getClass() == parserClass) {");
        w.indent();
        w.line("return token;");
        w.dedent();
        w.line("}");
        w.line("for (Token child : token.filteredChildren) {");
        w.indent();
        w.line("Token found = findFirstDescendant(child, parserClass);");
        w.line("if (found != null) {");
        w.indent();
        w.line("return found;");
        w.dedent();
        w.line("}");
        w.dedent();
        w.line("}");
        w.line("return null;");
        w.dedent();
        w.line("}");
        w.blankLine();

        w.line("static Token findDescendantByIndex(Token token, Class<? extends Parser> parserClass, int index) {");
        w.indent();
        w.line("if (index < 0) {");
        w.indent();
        w.line("return null;");
        w.dedent();
        w.line("}");
        w.line("if (token != null && token.parser.getClass() == parserClass) {");
        w.indent();
        w.line("if (index == 0) {");
        w.indent();
        w.line("return token;");
        w.dedent();
        w.line("}");
        w.line("index = index - 1;");
        w.dedent();
        w.line("}");
        w.line("List<Token> descendants = findDescendants(token, parserClass);");
        w.line("if (index >= descendants.size()) {");
        w.indent();
        w.line("return null;");
        w.dedent();
        w.line("}");
        w.line("return descendants.get(index);");
        w.dedent();
        w.line("}");
        w.blankLine();

        w.line("static String firstTokenText(Token token) {");
        w.indent();
        w.line("if (token == null) {");
        w.indent();
        w.line("return null;");
        w.dedent();
        w.line("}");
        w.line("String raw = tokenTextCompat(token);");
        w.line("if (raw != null && !raw.isBlank()) {");
        w.indent();
        w.line("return raw.strip();");
        w.dedent();
        w.line("}");
        w.line("for (Token child : token.filteredChildren) {");
        w.indent();
        w.line("String found = firstTokenText(child);");
        w.line("if (found != null && !found.isEmpty()) {");
        w.indent();
        w.line("return found;");
        w.dedent();
        w.line("}");
        w.dedent();
        w.line("}");
        w.line("return raw == null ? null : raw.strip();");
        w.dedent();
        w.line("}");
        w.blankLine();

        w.line("static String tokenTextCompat(Token token) {");
        w.indent();
        w.line("if (token == null) {");
        w.indent();
        w.line("return null;");
        w.dedent();
        w.line("}");
        w.line("try {");
        w.indent();
        w.line("java.lang.reflect.Method m = token.getClass().getMethod(\"getToken\");");
        w.line("Object value = m.invoke(token);");
        w.line("if (value instanceof Optional<?> optional && optional.isPresent()) {");
        w.indent();
        w.line("Object v = optional.get();");
        w.line("return v == null ? null : String.valueOf(v);");
        w.dedent();
        w.line("}");
        w.dedent();
        w.line("} catch (Throwable ignored) {}");
        w.line("try {");
        w.indent();
        w.line("java.lang.reflect.Field f = token.getClass().getField(\"tokenString\");");
        w.line("Object value = f.get(token);");
        w.line("if (value instanceof Optional<?> optional && optional.isPresent()) {");
        w.indent();
        w.line("Object v = optional.get();");
        w.line("return v == null ? null : String.valueOf(v);");
        w.dedent();
        w.line("}");
        w.dedent();
        w.line("} catch (Throwable ignored) {}");
        w.line("try {");
        w.indent();
        w.line("java.lang.reflect.Field f = token.getClass().getField(\"source\");");
        w.line("Object src = f.get(token);");
        w.line("if (src != null) {");
        w.indent();
        w.line("java.lang.reflect.Method m = src.getClass().getMethod(\"sourceAsString\");");
        w.line("Object v = m.invoke(src);");
        w.line("return v == null ? null : String.valueOf(v);");
        w.dedent();
        w.line("}");
        w.dedent();
        w.line("} catch (Throwable ignored) {}");
        w.line("return null;");
        w.dedent();
        w.line("}");
        w.blankLine();

        w.line("static int consumedLengthCompat(Token token) {");
        w.indent();
        w.line("String text = tokenTextCompat(token);");
        w.line("return text == null ? 0 : text.length();");
        w.dedent();
        w.line("}");
        w.blankLine();

        w.line("static int tokenStartOffsetCompat(Token token) {");
        w.indent();
        w.line("if (token == null) {");
        w.indent();
        w.line("return 0;");
        w.dedent();
        w.line("}");
        w.line("try {");
        w.indent();
        w.line("java.lang.reflect.Field sourceField = token.getClass().getField(\"source\");");
        w.line("Object source = sourceField.get(token);");
        w.line("if (source == null) {");
        w.indent();
        w.line("return 0;");
        w.dedent();
        w.line("}");
        w.line("java.lang.reflect.Method offsetMethod = source.getClass().getMethod(\"offsetFromRoot\");");
        w.line("Object offset = offsetMethod.invoke(source);");
        w.line("if (offset == null) {");
        w.indent();
        w.line("return 0;");
        w.dedent();
        w.line("}");
        w.line("java.lang.reflect.Method valueMethod = offset.getClass().getMethod(\"value\");");
        w.line("Object value = valueMethod.invoke(offset);");
        w.line("if (value instanceof Integer i) {");
        w.indent();
        w.line("return i;");
        w.dedent();
        w.line("}");
        w.line("if (value instanceof Number n) {");
        w.indent();
        w.line("return n.intValue();");
        w.dedent();
        w.line("}");
        w.dedent();
        w.line("} catch (Throwable ignored) {}");
        w.line("return 0;");
        w.dedent();
        w.line("}");
        w.blankLine();

        w.line("static <T> T registerNodeSourceSpan(T node, Token token) {");
        w.indent();
        w.line("if (node == null || token == null) {");
        w.indent();
        w.line("return node;");
        w.dedent();
        w.line("}");
        w.line("int start = Math.max(0, tokenStartOffsetCompat(token));");
        w.line("int length = Math.max(0, consumedLengthCompat(token));");
        w.line("int end = start + length;");
        w.line("NODE_SOURCE_SPANS.put(node, new int[]{start, end});");
        w.line("return node;");
        w.dedent();
        w.line("}");
        w.blankLine();

        w.line("public static Optional<int[]> sourceSpanOf(Object node) {");
        w.indent();
        w.line("if (node == null) {");
        w.indent();
        w.line("return Optional.empty();");
        w.dedent();
        w.line("}");
        w.line("int[] span = NODE_SOURCE_SPANS.get(node);");
        w.line("if (span == null || span.length < 2) {");
        w.indent();
        w.line("return Optional.empty();");
        w.dedent();
        w.line("}");
        w.line("return Optional.of(new int[]{span[0], span[1]});");
        w.dedent();
        w.line("}");
        w.blankLine();

        w.line("static StringSource createRootSourceCompat(String source) {");
        w.indent();
        w.line("try {");
        w.indent();
        w.line("java.lang.reflect.Method m = StringSource.class.getMethod(\"createRootSource\", String.class);");
        w.line("Object v = m.invoke(null, source);");
        w.line("if (v instanceof StringSource s) {");
        w.indent();
        w.line("return s;");
        w.dedent();
        w.line("}");
        w.dedent();
        w.line("} catch (Throwable ignored) {}");
        w.line("try {");
        w.indent();
        w.line("for (java.lang.reflect.Constructor<?> c : StringSource.class.getDeclaredConstructors()) {");
        w.indent();
        w.line("Class<?>[] types = c.getParameterTypes();");
        w.line("if (types.length == 0 || types[0] != String.class) {");
        w.indent();
        w.line("continue;");
        w.dedent();
        w.line("}");
        w.line("Object[] args = new Object[types.length];");
        w.line("args[0] = source;");
        w.line("c.setAccessible(true);");
        w.line("Object v = c.newInstance(args);");
        w.line("if (v instanceof StringSource s) {");
        w.indent();
        w.line("return s;");
        w.dedent();
        w.line("}");
        w.dedent();
        w.line("}");
        w.dedent();
        w.line("} catch (Throwable ignored) {}");
        w.line("throw new IllegalStateException(\"No compatible StringSource initializer found\");");
        w.dedent();
        w.line("}");
        w.blankLine();

        w.line("static String stripQuotes(String quoted) {");
        w.indent();
        w.line("if (quoted == null) {");
        w.indent();
        w.line("return null;");
        w.dedent();
        w.line("}");
        w.line("if (quoted.length() >= 2");
        w.line("    && '\\'' == quoted.charAt(0)");
        w.line("    && '\\'' == quoted.charAt(quoted.length() - 1)) {");
        w.indent();
        w.line("return quoted.substring(1, quoted.length() - 1);");
        w.dedent();
        w.line("}");
        w.line("return quoted;");
        w.dedent();
        w.line("}");
        w.blankLine();
        w.line("static String identifierLikeText(Token token) {");
        w.indent();
        w.line("if (token == null) {");
        w.indent();
        w.line("return null;");
        w.dedent();
        w.line("}");
        w.line("String raw = tokenTextCompat(token);");
        w.line("String fromRaw = extractIdentifierLike(raw);");
        w.line("if (fromRaw != null) {");
        w.indent();
        w.line("return fromRaw;");
        w.dedent();
        w.line("}");
        w.line("for (Token child : token.filteredChildren) {");
        w.indent();
        w.line("String fromChild = identifierLikeText(child);");
        w.line("if (fromChild != null) {");
        w.indent();
        w.line("return fromChild;");
        w.dedent();
        w.line("}");
        w.dedent();
        w.line("}");
        w.line("return extractIdentifierLike(firstTokenText(token));");
        w.dedent();
        w.line("}");
        w.blankLine();
        w.line("static String extractIdentifierLike(String raw) {");
        w.indent();
        w.line("if (raw == null) {");
        w.indent();
        w.line("return null;");
        w.dedent();
        w.line("}");
        w.line("String text = raw.strip();");
        w.line("int start = -1;");
        w.line("int end = -1;");
        w.line("for (int i = 0; i < text.length(); i++) {");
        w.indent();
        w.line("char c = text.charAt(i);");
        w.line("if (start < 0) {");
        w.indent();
        w.line("if (Character.isLetter(c) || c == '_') {");
        w.indent();
        w.line("start = i;");
        w.line("end = i + 1;");
        w.dedent();
        w.line("}");
        w.line("continue;");
        w.dedent();
        w.line("}");
        w.line("if (Character.isLetterOrDigit(c) || c == '_') {");
        w.indent();
        w.line("end = i + 1;");
        w.line("continue;");
        w.dedent();
        w.line("}");
        w.line("break;");
        w.dedent();
        w.line("}");
        w.line("if (start < 0 || end <= start) {");
        w.indent();
        w.line("return null;");
        w.dedent();
        w.line("}");
        w.line("return text.substring(start, end);");
        w.dedent();
        w.line("}");
        return w.build();
    }
}
