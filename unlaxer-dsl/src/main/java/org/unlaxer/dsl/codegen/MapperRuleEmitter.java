package org.unlaxer.dsl.codegen;

import org.unlaxer.dsl.bootstrap.UBNFAST.AtomicElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.bootstrap.UBNFAST.MappingAnnotation;
import org.unlaxer.dsl.bootstrap.UBNFAST.RuleDecl;
import org.unlaxer.dsl.bootstrap.UBNFAST.RuleRefElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.TerminalElement;
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
        // Memoized wrapper. mapToken is otherwise re-invoked on the same token objects O(depth) times
        // (findBestMappedToken probes every node, and each mapping method resolves its operands via
        // findBestMappedToken again), so a deeply nested expression re-constructs the same subtrees
        // millions of times. Memoizing by token identity collapses that to one construction per token.
        // (tinyexpression #49)
        w.line("private static " + astClass + " mapToken(Token token) {");
        w.indent();
        w.line("if (token == null) {");
        w.indent();
        w.line("return null;");
        w.dedent();
        w.line("}");
        w.line("if (MAP_MEMO.containsKey(token)) {");
        w.indent();
        w.line("return MAP_MEMO.get(token);");
        w.dedent();
        w.line("}");
        w.line(astClass + " memoized = mapTokenUncached(token);");
        w.line("MAP_MEMO.put(token, memoized);");
        w.line("return memoized;");
        w.dedent();
        w.line("}");
        w.blankLine();
        w.line("private static " + astClass + " mapTokenUncached(Token token) {");
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
     * mapTransparentValue() を生成する。@mapping を持たない透過的 choice ルールを
     * 値として捉えた heterogeneous(Object) フィールド向けに、マッチした選択肢の実 AST
     * ノードを解決する。ノードが見つからなければ firstTokenText にフォールバック。
     * (unlaxer-parser #43 family / tinyexpression #32)
     */
    static String emitMapTransparentValue(String astClass) {
        IndentedWriter w = new IndentedWriter(1);
        w.line("private static Object mapTransparentValue(Token token) {");
        w.indent();
        w.line("if (token == null) {");
        w.indent();
        w.line("return null;");
        w.dedent();
        w.line("}");
        w.line(astClass + " direct = mapToken(token);");
        w.line("if (direct != null) {");
        w.indent();
        w.line("return direct;");
        w.dedent();
        w.line("}");
        w.line("Token best = findBestMappedToken(token, null);");
        w.line("if (best != null) {");
        w.indent();
        w.line(astClass + " mapped = mapToken(best);");
        w.line("if (mapped != null) {");
        w.indent();
        w.line("return mapped;");
        w.dedent();
        w.line("}");
        w.dedent();
        w.line("}");
        w.line("return stripQuotes(firstTokenText(token));");
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

            List<RuleDecl> sumVariants = MappingShape.sumVariants(grammar, rule, mapping);
            if (!sumVariants.isEmpty()) {
                emitSumMappingBody(w, parsersClass, className, sumVariants);
            } else if (leftAssoc || rightAssoc) {
                emitAssocMappingBody(w, grammar, astClass, parsersClass, className, rule, mapping,
                    rightAssoc, allMappingRules, mappedClassByRuleName, tokenDeclByName, ruleByName);
            } else {
                emitPlainMappingBody(w, grammar, astClass, parsersClass, className, rule, mapping,
                    allMappingRules, mappedClassByRuleName, tokenDeclByName, ruleByName);
            }

            w.dedent();
            w.line("}");
            w.blankLine();

            // unlaxer-parser #43: operand-dispatch helper for heterogeneous assoc folds.
            if ((leftAssoc || rightAssoc) && requiresAssocOperandHelper(
                    grammar, astClass, className, rule, allMappingRules, ruleByName, tokenDeclByName)) {
                String operandType = SharedAssocSchema.resolve(grammar, rule)
                    .map(SharedAssocSchema::leftType).orElse(astClass);
                emitAssocOperandHelper(w, astClass, className, operandType);
            }
        }
        return w.build();
    }

    private static void emitSumMappingBody(IndentedWriter w, String parsersClass, String className,
            List<RuleDecl> variants) {
        // Visit every variant at a depth before descending: B may itself contain A.
        w.line("java.util.ArrayDeque<Token> pending = new java.util.ArrayDeque<>();");
        w.line("pending.addAll(token.getOriginalChildren());");
        w.line("while (!pending.isEmpty()) {");
        w.indent();
        w.line("Token candidate = pending.removeFirst();");
        for (RuleDecl variant : variants) {
            String target = MapperElementUtil.getMappingAnnotation(variant).orElseThrow().className();
            w.line("if (candidate.parser.getClass() == " + parsersClass + "." + variant.name() + "Parser.class) {");
            w.indent();
            w.line("return to" + MapperElementUtil.methodNameFor(target) + "(candidate);");
            w.dedent();
            w.line("}");
        }
        w.line("pending.addAll(candidate.getOriginalChildren());");
        w.dedent();
        w.line("}");
        w.line("throw new IllegalArgumentException(\"Mapped variant not found for " + className + "\");");
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

            // unlaxer-parser #43: when an operand can be a non-spine mapped node (e.g. a
            // MathFunction factor), widen operands to the base AST interface and dispatch
            // each operand to its real mapped type via the generated helper, instead of
            // recursing through the assoc mapper (which descends past the function wrapper).
            // Gated on leafFallbackSupported so only number-style folds (operand fields ==
            // the assoc class) are affected; source-string-leaf folds keep their design.
            boolean heterogeneousOperand = leafFallbackSupported && MapperElementUtil.assocClassHasHeterogeneousOperand(
                className, allMappingRules.getOrDefault(className, List.of()), ruleByName, tokenDeclByName);
            Optional<SharedAssocSchema> shared = SharedAssocSchema.resolve(grammar, rule);
            if (shared.isPresent()) {
                leftType = shared.get().leftType();
                rightType = shared.get().rightType();
                leafFallbackSupported = shared.get().spine();
                heterogeneousOperand = shared.get().heterogeneous();
            }
            String operandHelper = "mapAssocOperandTo" + MapperElementUtil.methodNameFor(className);
            if (heterogeneousOperand) {
                leftType = astClass;
                rightType = astClass;
            }

            String ruleParserClass = parsersClass + "." + rule.name() + "Parser.class";
            String repeatParserClass = parsersClass + "." + rule.name() + "Repeat" + assocShape.repeatIndex() + "Parser.class";
            String leftParserClass = MapperElementUtil.parserClassLiteral(assocShape.leftElement(), parsersClass, tokenDeclByName, ruleByName)
                .orElse(ruleParserClass);
            String opParserClass = MapperElementUtil.parserClassLiteral(assocShape.opElement(), parsersClass, tokenDeclByName, ruleByName)
                .orElse("org.unlaxer.parser.elementary.WordParser.class");
            String rightParserClass = MapperElementUtil.parserClassLiteral(assocShape.rightElement(), parsersClass, tokenDeclByName, ruleByName)
                .orElse(ruleParserClass);

            // Use target-type-aware mapping so that Object operands bound to a transparent
            // mapped choice (e.g. StringConcatExpr's StringTerm operands) resolve the real
            // matched node via mapTransparentValue instead of being flattened to source text.
            // For AST-class / String target types this delegates to mapExpressionForElement,
            // preserving existing fold behavior. (tinyexpression #32 string-concat widening)
            String leftMapper = heterogeneousOperand
                ? operandHelper + "(leftToken)"
                : shared.filter(SharedAssocSchema::spine).isPresent()
                ? operandHelper + "(leftToken)"
                : MapperElementUtil.mapExpressionForTargetType(
                    leftType,
                    assocShape.leftElement(),
                    "leftToken",
                    mappedClassByRuleName,
                    tokenDeclByName,
                    ruleByName);
            String rightMapper = heterogeneousOperand
                ? operandHelper + "(rightToken)"
                : shared.filter(SharedAssocSchema::spine).isPresent()
                ? operandHelper + "(rightToken)"
                : MapperElementUtil.mapExpressionForTargetType(
                    rightType,
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
                    heterogeneousOperand || shared.filter(SharedAssocSchema::spine).isPresent(), operandHelper,
                    mappedClassByRuleName, tokenDeclByName, ruleByName);
            }

            if (rightAssoc) {
                emitRightAssocMapping(w, astClass, className, rule, ruleParserClass,
                    leftType, opType, rightType, leftMapper, rightMapper, leafFallbackSupported,
                    MapperElementUtil.parserClassLiteral(assocShape.leftElement(), parsersClass, tokenDeclByName, ruleByName).orElse(null));
                return;
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
            w.line("List<" + MapperTypeResolver.boxedType(rightType) + "> rights = new ArrayList<>();");
            w.line("for (Token repeatToken : findAssocRepetitions(working, " + repeatParserClass + ")) {");
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
            w.line("return registerNodeSourceSpan(new " + astClass + "." + className + "(left, ops, rights), working);");
        } else {
            w.line("throw new IllegalArgumentException(\"Unsupported assoc mapping shape for rule: "
              + rule.name() + "\");");
        }
    }

    private static void emitAdditionalAssocRuleDispatch(IndentedWriter w,
            String astClass, String parsersClass, String className,
            RuleDecl additionalRule, String leftType, String opType, String rightType,
            boolean leafFallbackSupported,
            boolean heterogeneousOperand, String operandHelper,
            Map<String, String> mappedClassByRuleName,
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
            // For rules sharing the same @mapping class, left/right mappers should call
            // the shared mapping method recursively — unless the operand is heterogeneous
            // (#43), in which case dispatch each operand to its real mapped type.
            String addLeftMapper = heterogeneousOperand
                ? operandHelper + "(addLeftToken)"
                : leafFallbackSupported ? "to" + MapperElementUtil.methodNameFor(className) + "(addLeftToken)"
                : MapperElementUtil.mapExpressionForTargetType(leftType, addShape.leftElement(), "addLeftToken",
                    mappedClassByRuleName, tokenDeclByName, ruleByName);
            String addRightMapper = heterogeneousOperand
                ? operandHelper + "(addRightToken)"
                : leafFallbackSupported ? "to" + MapperElementUtil.methodNameFor(className) + "(addRightToken)"
                : MapperElementUtil.mapExpressionForTargetType(rightType, addShape.rightElement(), "addRightToken",
                    mappedClassByRuleName, tokenDeclByName, ruleByName);
            String addRightParserClass = MapperElementUtil.parserClassLiteral(addShape.rightElement(), parsersClass, tokenDeclByName, ruleByName)
                .orElse(addRuleParserClass);

            w.line("// Handle " + additionalRule.name() + " tokens (same @mapping class)");
            w.line("if (token.parser.getClass() == " + addRuleParserClass + ") {");
            w.indent();
            if (MapperElementUtil.isRightAssocRule(additionalRule,
                    MapperElementUtil.getMappingAnnotation(additionalRule).orElse(null))) {
                emitRightAssocMapping(w, astClass, className, additionalRule, addRuleParserClass,
                    leftType, opType, rightType,
                    addLeftMapper.replace("addLeftToken", "leftToken"),
                    addRightMapper.replace("addRightToken", "rightToken"), leafFallbackSupported,
                    MapperElementUtil.parserClassLiteral(addShape.leftElement(), parsersClass, tokenDeclByName, ruleByName).orElse(null));
                w.dedent();
                w.line("}");
                return;
            }
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
            w.line("List<" + MapperTypeResolver.boxedType(rightType) + "> addRights = new ArrayList<>();");
            w.line("for (Token addRepeatToken : findAssocRepetitions(token, " + addRepeatParserClass + ")) {");
            w.indent();
            w.line("Token addOpToken = findFirstDescendant(addRepeatToken, " + addOpParserClass + ");");
            w.line("String addOpValue = firstTokenText(addOpToken == null ? addRepeatToken : addOpToken);");
            w.line("if (addOpValue != null && !addOpValue.isEmpty()) {");
            w.indent();
            w.line("addOps.add(stripQuotes(addOpValue));");
            w.dedent();
            w.line("}");
            w.line("Token addRightToken = findFirstDescendant(addRepeatToken, " + addRightParserClass + ");");
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

    /** The canonical parser already builds Base Op Self | Base; never fold its right subtree again. */
    private static void emitRightAssocMapping(IndentedWriter w, String astClass, String className,
            RuleDecl rule, String ruleParserClass, String leftType, String opType, String rightType,
            String leftMapper, String rightMapper, boolean leafFallbackSupported, String leftParserClass) {
        CaptureBindingPlan bindings = new CaptureBindingPlan(rule);
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
            w.line("return registerNodeSourceSpan(new " + astClass + "." + className
                + "(null, List.of(literal == null ? \"\" : literal), List.of()), token);");
        } else {
            w.line("throw new IllegalArgumentException(\"Mapping token not found for rule "
                + ParserCodegenUtil.escapeString(rule.name()) + "\");");
        }
        w.dedent();
        w.line("}");
        for (String capture : List.of("left", "op", "right")) {
            String ids = bindings.sites(capture).stream()
                .map(site -> "\"" + ParserCodegenUtil.escapeString(site.id()) + "\"")
                .collect(java.util.stream.Collectors.joining(", "));
            w.line("List<Token> " + capture + "Sites = findCaptureSites(working, java.util.Set.of(" + ids + "));");
        }
        w.line("if (leftSites.size() != 1 || opSites.size() > 1 || opSites.size() != rightSites.size()) {");
        w.indent();
        w.line("throw new IllegalArgumentException(\"Invalid right-associative capture shape for rule "
            + ParserCodegenUtil.escapeString(rule.name()) + "\");");
        w.dedent();
        w.line("}");
        w.line("Token leftToken = " + (leftParserClass == null ? "leftSites.get(0)"
            : "findFirstDescendant(leftSites.get(0), " + leftParserClass + ")") + ";");
        w.line(leftType + " left = " + leftMapper + ";");
        w.line("List<" + opType + "> ops = new ArrayList<>();");
        w.line("List<" + MapperTypeResolver.boxedType(rightType) + "> rights = new ArrayList<>();");
        w.line("if (!opSites.isEmpty()) {");
        w.indent();
        w.line("ops.add(stripQuotes(firstTokenText(opSites.get(0))));");
        w.line("Token rightToken = rightSites.get(0);");
        w.line("rights.add(" + rightMapper + ");");
        w.dedent();
        w.line("}");
        w.line("return registerNodeSourceSpan(new " + astClass + "." + className + "(left, ops, rights), working);");
    }

    /**
     * Whether a left/right-assoc rule's operands should be widened to the base AST
     * interface and dispatched per-operand (#43). True only for number-style folds —
     * operand fields nominally equal the assoc class and the operator is a String — that
     * also have a heterogeneous (transparent, multi-class) operand. Source-string-leaf
     * folds (e.g. string concatenation) are excluded, preserving their existing design.
     */
    private static boolean requiresAssocOperandHelper(GrammarDecl grammar, String astClass,
            String className, RuleDecl rule, Map<String, List<RuleDecl>> allMappingRules,
            Map<String, RuleDecl> ruleByName, Map<String, TokenDecl> tokenDeclByName) {
        Optional<SharedAssocSchema> shared = SharedAssocSchema.resolve(grammar, rule);
        if (shared.isPresent()) return shared.get().spine();
        String leftType = MapperTypeResolver.inferType(grammar, rule, "left");
        String opType = MapperTypeResolver.unwrapListType(
            MapperTypeResolver.inferType(grammar, rule, "op")).orElse("String");
        String rightType = MapperTypeResolver.unwrapListType(
            MapperTypeResolver.inferType(grammar, rule, "right")).orElse("Object");
        boolean numberStyleFold = (astClass + "." + className).equals(leftType)
            && "String".equals(opType)
            && (astClass + "." + className).equals(rightType);
        return numberStyleFold && MapperElementUtil.assocClassHasHeterogeneousOperand(
            className, allMappingRules.getOrDefault(className, List.of()), ruleByName, tokenDeclByName);
    }

    /**
     * Emits a helper that maps a single assoc operand token to its real AST node:
     * dispatch the token directly if recognised, else find the shallowest mappable
     * descendant (so a function factor maps to its function node instead of being
     * skipped), else fall back to a numeric/text literal leaf. (unlaxer-parser #43)
     */
    private static void emitAssocOperandHelper(IndentedWriter w, String astClass, String className, String operandType) {
        String helper = "mapAssocOperandTo" + MapperElementUtil.methodNameFor(className);
        String cast = operandType.equals(astClass) ? "" : "(" + operandType + ") ";
        w.line("static " + operandType + " " + helper + "(Token token) {");
        w.indent();
        w.line("if (token == null) {");
        w.indent();
        w.line("return null;");
        w.dedent();
        w.line("}");
        w.line(astClass + " direct = mapToken(token);");
        w.line("if (direct != null) {");
        w.indent();
        w.line("return " + cast + "direct;");
        w.dedent();
        w.line("}");
        w.line("Token best = findBestMappedToken(token, null);");
        w.line("if (best != null) {");
        w.indent();
        w.line(astClass + " mapped = mapToken(best);");
        w.line("if (mapped != null) {");
        w.indent();
        w.line("return " + cast + "mapped;");
        w.dedent();
        w.line("}");
        w.dedent();
        w.line("}");
        w.line("String literal = stripQuotes(firstTokenText(token));");
        w.line("literal = literal == null ? \"\" : literal;");
        w.line("return registerNodeSourceSpan(new " + astClass + "." + className
            + "(null, List.of(literal), List.of()), token);");
        w.dedent();
        w.line("}");
        w.blankLine();
    }

    private static void emitPlainMappingBody(IndentedWriter w, GrammarDecl grammar,
            String astClass, String parsersClass, String className,
            RuleDecl rule, MappingAnnotation mapping,
            Map<String, List<RuleDecl>> allMappingRules,
            Map<String, String> mappedClassByRuleName,
            Map<String, TokenDecl> tokenDeclByName, Map<String, RuleDecl> ruleByName) {

        // Multiple non-assoc rules can map to the same AST class with DIFFERENT capture
        // structures (e.g. SliceExpr: SliceBaseExpression's @value is a SliceBaseReceiver,
        // SliceNestedExpression's @value is a SliceBaseExpression i.e. the mapped class
        // itself). A single resolution body generated from one representative rule mis-resolves
        // tokens of the other rule. Dispatch each ADDITIONAL rule by the token's parser class;
        // the representative rule stays the unguarded fallback. Exactly one rule is guarded and
        // the other falls through, so the result is correct regardless of which is representative.
        // Single-rule classes and structurally-identical rules (e.g. IfExpr's ArgumentTernary)
        // are unaffected in behavior. (tinyexpression #32: nested slice value)
        List<RuleDecl> additionalRules = allMappingRules.getOrDefault(className, List.of()).stream()
            .filter(r -> r != rule)
            .filter(r -> {
                MappingAnnotation m = MapperElementUtil.getMappingAnnotation(r).orElse(null);
                return !MapperElementUtil.isLeftAssocRule(r, m) && !MapperElementUtil.isRightAssocRule(r, m);
            })
            .toList();
        for (RuleDecl additionalRule : additionalRules) {
            w.line("if (token.parser.getClass() == " + parsersClass + "." + additionalRule.name() + "Parser.class) {");
            w.indent();
            emitPlainMappingResolution(w, grammar, astClass, parsersClass, className, additionalRule, mapping,
                mappedClassByRuleName, tokenDeclByName, ruleByName);
            w.dedent();
            w.line("}");
        }
        emitPlainMappingResolution(w, grammar, astClass, parsersClass, className, rule, mapping,
            mappedClassByRuleName, tokenDeclByName, ruleByName);
    }

    private static void emitPlainMappingResolution(IndentedWriter w, GrammarDecl grammar,
            String astClass, String parsersClass, String className,
            RuleDecl rule, MappingAnnotation mapping,
            Map<String, String> mappedClassByRuleName,
            Map<String, TokenDecl> tokenDeclByName, Map<String, RuleDecl> ruleByName) {

        CaptureBindingPlan bindings = new CaptureBindingPlan(rule);
        // Collect @typeof constraints: ownCaptureName -> referencedCaptureName
        Map<String, String> typeofConstraints = MapperElementUtil.collectTypeofConstraints(rule.body());
        for (String param : mapping.paramNames()) {
            String type = SharedPlainSchema.fieldType(grammar, rule, param)
                .orElseGet(() -> MapperTypeResolver.inferType(grammar, rule, param));
            List<AtomicElement> capturedElements = MapperElementUtil.findCapturedElements(rule.body(), param);
            if (capturedElements.isEmpty()) {
                w.line(type + " " + param
                    + " = " + MapperTypeResolver.defaultValueForType(type) + ";");
                continue;
            }

            emitBoundParam(w, parsersClass, grammar, param, type, bindings.sites(param),
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

    private static void emitBoundParam(IndentedWriter w, String parsersClass, GrammarDecl grammar,
            String param, String type, List<CaptureBindingPlan.Site> sites,
            Map<String, String> mappedClassByRuleName,
            Map<String, TokenDecl> tokenDeclByName, Map<String, RuleDecl> ruleByName) {
        SemanticCardinality semantics = new SemanticCardinality(grammar);
        if (sites.stream().anyMatch(site -> semantics.needsCollection(site.element()))) {
            emitSemanticParam(w, param, type, sites, semantics);
            return;
        }
        Optional<String> listType = MapperTypeResolver.unwrapListType(type);
        Optional<String> optionalType = MapperTypeResolver.unwrapOptionalType(type);
        String valueType = listType.or(() -> optionalType).orElse(type);
        String localType = MapperTypeResolver.boxedType(type);
        String initial = listType.isPresent() ? "new ArrayList<>()"
            : optionalType.isPresent() ? "Optional.empty()" : "null";
        w.line(localType + " " + param + " = " + initial + ";");
        String safe = MapperElementUtil.safeName(param);
        String siteToken = "captureSite_" + safe;
        String ids = sites.stream().map(site -> "\"" + ParserCodegenUtil.escapeString(site.id()) + "\"")
            .collect(java.util.stream.Collectors.joining(", "));
        w.line("for (CaptureOccurrence occurrence : findCaptureOccurrences(token, java.util.Set.of(" + ids + "))) {");
        w.indent();
        w.line("Token " + siteToken + " = occurrence.token();");
        for (int i = 0; i < sites.size(); i++) {
            CaptureBindingPlan.Site site = sites.get(i);
            boolean boundText = MapperElementUtil.usesBoundTextCapture(site.element(), ruleByName, tokenDeclByName);
            boolean boundValue = "Object".equals(valueType) && MapperElementUtil.containsMappedValue(site.element(), ruleByName);
            AtomicElement normalized = boundValue ? site.element()
                : MapperElementUtil.normalizeCapturedElement(site.element()).orElse(site.element());
            String parserClass = boundText || boundValue ? null : MapperElementUtil.parserClassLiteral(normalized, parsersClass, tokenDeclByName, ruleByName)
                .orElse(null);
            String candidateType = MapperTypeResolver.inferTypeFromElement(grammar, normalized);
            if (!MapperTypeResolver.isTypeCompatible(valueType, candidateType) && !"String".equals(valueType)) continue;
            String valueToken = "paramToken_" + safe + "_" + i;
            w.line("if (occurrence.binding().equals(\"" + ParserCodegenUtil.escapeString(site.id()) + "\")) {");
            w.indent();
            w.line("Token " + valueToken + " = " + (parserClass == null ? siteToken
                : "findDescendants(" + siteToken + ", " + parserClass + ").stream().findFirst().orElse(null)") + ";");
            w.line("if (" + valueToken + " != null) {");
            w.indent();
            String expression = boundText ? "stripQuotes(firstTokenText(" + valueToken + "))"
                : MapperElementUtil.mapExpressionForTargetType(valueType, normalized, valueToken,
                mappedClassByRuleName, tokenDeclByName, ruleByName);
            if (boundText || "String".equals(valueType)) {
                expression = "registerNodeSourceSpan(new String(" + expression + "), " + valueToken + ")";
            }
            w.line(listType.isPresent() ? param + ".add(" + expression + ");"
                : optionalType.isPresent() ? param + " = Optional.ofNullable(" + expression + ");"
                : param + " = " + expression + ";");
            w.line(listType.isPresent() ? "continue;" : "break;");
            w.dedent();
            w.line("}");
            w.dedent();
            w.line("}");
        }
        w.dedent();
        w.line("}");
        if ("int".equals(type) || "long".equals(type)) {
            w.line("if (" + param + " == null) throw new IllegalArgumentException(\"Required numeric capture not found: "
                + ParserCodegenUtil.escapeString(param) + "\");");
        }
    }

    private static void emitSemanticParam(IndentedWriter w, String param, String type,
            List<CaptureBindingPlan.Site> sites, SemanticCardinality semantics) {
        String values = "semantic_" + MapperElementUtil.safeName(param);
        String siteToken = "semanticSite_" + MapperElementUtil.safeName(param);
        w.line("List<Object> " + values + " = new ArrayList<>();");
        String ids = sites.stream().map(site -> "\"" + ParserCodegenUtil.escapeString(site.id()) + "\"")
            .collect(java.util.stream.Collectors.joining(", "));
        w.line("for (CaptureOccurrence occurrence : findCaptureOccurrences(token, java.util.Set.of(" + ids + "))) {");
        w.indent();
        w.line("Token " + siteToken + " = occurrence.token();");
        for (CaptureBindingPlan.Site site : sites) {
            var shape = semantics.siteShape(site.element());
            w.line("if (occurrence.binding().equals(\"" + ParserCodegenUtil.escapeString(site.id()) + "\")) {");
            w.indent();
            if (shape.kind() == SemanticCardinality.Kind.TEXT) {
                w.line(values + ".add(semanticText(" + siteToken + "));");
            } else {
                w.line(values + ".addAll(semanticValues(" + siteToken + ", "
                    + (shape.kind() == SemanticCardinality.Kind.VALUE && shape.count() != SemanticCardinality.Count.MANY) + "));");
            }
            w.line("continue;");
            w.dedent();
            w.line("}");
        }
        w.dedent();
        w.line("}");
        Optional<String> list = MapperTypeResolver.unwrapListType(type);
        Optional<String> optional = MapperTypeResolver.unwrapOptionalType(type);
        String element = list.or(() -> optional).orElse(type);
        if (list.isPresent()) {
            w.line(type + " " + param + " = " + values + ".stream().map(" + element
                + ".class::cast).collect(java.util.stream.Collectors.toCollection(ArrayList::new));");
        } else {
            String condition = optional.isPresent() ? "> 1" : "!= 1";
            w.line("if (" + values + ".size() " + condition + ") throw new IllegalArgumentException(\"Unexpected semantic cardinality for "
                + ParserCodegenUtil.escapeString(param) + "\");");
            w.line(type + " " + param + " = " + (optional.isPresent()
                ? values + ".isEmpty() ? Optional.empty() : Optional.of((" + element + ") " + values + ".get(0))"
                : "(" + element + ") " + values + ".get(0)") + ";");
        }
    }

    static String emitSemanticUtilities(GrammarDecl grammar, String parsersClass) {
        SemanticCardinality semantics = new SemanticCardinality(grammar);
        List<String> boundaries = grammar.rules().stream()
            .filter(rule -> MapperElementUtil.getMappingAnnotation(rule).isEmpty())
            .filter(rule -> {
                var shape = semantics.shape(new RuleRefElement(rule.name()));
                return shape.kind() == SemanticCardinality.Kind.VALUE && shape.count() != SemanticCardinality.Count.MANY;
            }).map(rule -> parsersClass + "." + rule.name() + "Parser.class").toList();
        return """

                private static final java.util.Set<Class<?>> SEMANTIC_VALUE_BOUNDARIES = java.util.Set.of(%s);

                private static String semanticText(Token token) {
                    // Distinct identities retain distinct spans even for equal or empty text values.
                    return registerNodeSourceSpan(new String(stripQuotes(firstTokenText(token))), token);
                }

                private static List<Object> semanticValues(Token token, boolean boundary) {
                    if (token == null) return List.of();
                    if (hasCaptureBinding(token, "%s")) return List.of(semanticText(token));
                    Object mapped = mapToken(token);
                    if (mapped != null) return List.of(mapped);
                    List<Object> values = new ArrayList<>();
                    for (Token child : token.filteredChildren) values.addAll(semanticValues(child, false));
                    if ((boundary || hasCaptureBinding(token, "%s")
                            || SEMANTIC_VALUE_BOUNDARIES.contains(token.parser.getClass()))
                            && !values.isEmpty() && values.stream().allMatch(String.class::isInstance)) {
                        return List.of(semanticText(token));
                    }
                    return values;
                }

            """.formatted(String.join(", ", boundaries), SemanticCardinality.TEXT_BINDING, SemanticCardinality.BOUNDARY_BINDING);
    }

    /**
     * ユーティリティメソッド群（findDescendants, firstTokenText 等）を生成する。
     */
    static String emitUtilities(String parsersClass, java.util.Set<String> mappedRuleNames,
            java.util.Set<String> allRuleNames) {
        IndentedWriter w = new IndentedWriter(1);
        w.line("// =========================================================================");
        w.line("// Utilities");
        w.line("// =========================================================================");
        w.blankLine();

        // Parser classes of rules that map to their own AST node. A capture's bounded
        // descendant search must NOT cross into one of these, because it is a SEPARATE
        // captured sub-expression — e.g. a nested slice's outer @step search must stop at
        // the inner slice (SliceBaseExpression) rather than leak in and grab its step.
        w.line("private static final java.util.Set<Class<?>> CAPTURE_BOUNDARY_PARSERS = java.util.Set.of(");
        w.indent();
        java.util.List<String> boundaryRules = new java.util.ArrayList<>(mappedRuleNames);
        if (boundaryRules.isEmpty()) {
            // Set.of() with no args is fine, but keep a stable form.
            w.dedent();
            w.line(");");
        } else {
            for (int i = 0; i < boundaryRules.size(); i++) {
                String suffix = i < boundaryRules.size() - 1 ? "," : "";
                w.line(parsersClass + "." + boundaryRules.get(i) + "Parser.class" + suffix);
            }
            w.dedent();
            w.line(");");
        }
        w.blankLine();

        String allBoundaries = allRuleNames.stream().map(name -> parsersClass + "." + name + "Parser.class")
            .collect(java.util.stream.Collectors.joining(", "));
        w.raw("""
                private static final java.util.Set<Class<?>> CAPTURE_RULE_BOUNDARIES = java.util.Set.of(%s);
                private record CaptureOccurrence(Token token, String binding) {}

                private static List<CaptureOccurrence> findCaptureOccurrences(Token token, java.util.Set<String> bindings) {
                    List<CaptureOccurrence> result = new ArrayList<>();
                    collectCaptureOccurrences(token, bindings, result, true);
                    return result;
                }

                private static void collectCaptureOccurrences(Token token, java.util.Set<String> bindings,
                        List<CaptureOccurrence> result, boolean root) {
                    if (token == null || !root && CAPTURE_RULE_BOUNDARIES.contains(token.parser.getClass())) return;
                    for (Token child : token.filteredChildren) collectCaptureOccurrences(child, bindings, result, false);
                    if (token.parser instanceof %s.__CaptureBinding capture) {
                        // Grammar bindings are registered outer-first; captures complete inner-first.
                        List<String> ids = capture.captureBindings();
                        for (int i = ids.size() - 1; i >= 0; i--) {
                            if (bindings.contains(ids.get(i))) result.add(new CaptureOccurrence(token, ids.get(i)));
                        }
                    }
                }

            """.formatted(allBoundaries, parsersClass));

        w.raw("""
                private static boolean hasCaptureBinding(Token token, String binding) {
                    return token.parser instanceof %s.__CaptureBinding capture
                        && capture.captureBindings().contains(binding);
                }

                private static List<Token> findCaptureSites(Token token, java.util.Set<String> bindings) {
                    List<Token> result = new ArrayList<>();
                    collectCaptureSites(token, bindings, result, true);
                    return result;
                }

                private static void collectCaptureSites(Token token, java.util.Set<String> bindings,
                        List<Token> result, boolean root) {
                    if (token == null) return;
                    if (token.parser instanceof %s.__CaptureBinding capture
                            && capture.captureBindings().stream().anyMatch(bindings::contains)) {
                        result.add(token);
                        return;
                    }
                    if (!root && CAPTURE_BOUNDARY_PARSERS.contains(token.parser.getClass())) return;
                    for (Token child : token.filteredChildren) {
                        collectCaptureSites(child, bindings, result, false);
                    }
                }

            """.formatted(parsersClass, parsersClass));

        // Like findDescendants, but stops at capture-boundary tokens (separate mapped nodes),
        // so a capture absent at this level does not leak into a nested sub-expression of the
        // same parser class. Used as findCapturedToken's fallback. (tinyexpression #32)
        w.line("static List<Token> findDescendantsBounded(Token token, Class<? extends Parser> parserClass) {");
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
        w.line("} else if (!CAPTURE_BOUNDARY_PARSERS.contains(child.parser.getClass())) {");
        w.indent();
        w.line("results.addAll(findDescendantsBounded(child, parserClass));");
        w.dedent();
        w.line("}");
        w.dedent();
        w.line("}");
        w.line("return results;");
        w.dedent();
        w.line("}");
        w.blankLine();

        w.line("// Collects rule-level (top-level) occurrences of parserClass: once a child");
        w.line("// matches, we do NOT recurse into it, so a captured operand's own nested");
        w.line("// same-class descendants are not counted. This keeps positional captures");
        w.line("// (findDescendantByIndex) and variadic captures aligned with the grammar");
        w.line("// structure — e.g. in \"toUpperCase('abc')=='ABC'\" the two StringExpression");
        w.line("// operands are indices 0 and 1, not 0 and (the nested 'abc') 1.");
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
        w.line("} else {");
        w.indent();
        w.line("results.addAll(findDescendants(child, parserClass));");
        w.dedent();
        w.line("}");
        w.dedent();
        w.line("}");
        w.line("return results;");
        w.dedent();
        w.line("}");
        w.blankLine();

        // Assoc repeats are direct children in legacy reduced trees, but retain their
        // ZeroOrMore wrapper in the source-preserving CST. Do not recurse into operand
        // rules: parenthesized expressions can contain the very same repeat class.
        w.line("static List<Token> findAssocRepetitions(Token token, Class<? extends Parser> parserClass) {");
        w.indent();
        w.line("List<Token> results = new ArrayList<>();");
        w.line("if (token == null) return results;");
        w.line("for (Token child : token.filteredChildren) {");
        w.indent();
        w.line("if (child.parser.getClass() == parserClass) {");
        w.indent();
        w.line("results.add(child);");
        w.dedent();
        w.line("} else if (child.parser.getClass() == org.unlaxer.parser.combinator.ZeroOrMore.class) {");
        w.indent();
        w.line("results.addAll(findDirectDescendants(child, parserClass));");
        w.dedent();
        w.line("}");
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

        // Resolve a captured token by STRUCTURAL POSITION: prefer the rule token's direct
        // children, falling back to the global descendant index only when no direct child of
        // the parser class exists. A global descendant index miscounts whenever the captured
        // parser class also appears nested inside a SIBLING capture's subtree — e.g.
        // IfExpression's @thenExpr/@elseExpr (Expression) vs the Expressions inside its
        // @condition (min(0,0)==0), or a nested slice's outer index vs the inner slice's.
        // Captures are direct children of the rule's Chain, so direct-first resolution is
        // both correct there and backward-compatible elsewhere. (tinyexpression #32)
        w.line("static Token findCapturedToken(Token token, Class<? extends Parser> parserClass, int index) {");
        w.indent();
        w.line("if (index < 0) {");
        w.indent();
        w.line("return null;");
        w.dedent();
        w.line("}");
        w.line("List<Token> direct = findDirectDescendants(token, parserClass);");
        w.line("if (index < direct.size()) {");
        w.indent();
        w.line("return direct.get(index);");
        w.dedent();
        w.line("}");
        w.line("if (!direct.isEmpty()) {");
        w.indent();
        w.line("return null;");
        w.dedent();
        w.line("}");
        // Fallback for captures nested under anonymous wrappers (Optional/Group/Repeat): a
        // bounded descendant search that does NOT cross capture boundaries, so an absent
        // capture at this level cannot leak into a nested mapped sub-expression.
        w.line("List<Token> bounded = findDescendantsBounded(token, parserClass);");
        w.line("return index < bounded.size() ? bounded.get(index) : null;");
        w.dedent();
        w.line("}");
        w.blankLine();

        // Literal captures share WordParser with every structural literal. Match by
        // parser class and exact token text while preserving findCapturedToken's
        // direct-child and capture-boundary semantics. (unlaxer-parser #42)
        w.line("static Token findCapturedTokenWithText(Token token, Class<? extends Parser> parserClass, String text, int index) {");
        w.indent();
        w.line("if (index < 0) {");
        w.indent();
        w.line("return null;");
        w.dedent();
        w.line("}");
        w.line("List<Token> direct = findDirectDescendants(token, parserClass);");
        w.line("int seen = 0;");
        w.line("for (Token candidate : direct) {");
        w.indent();
        w.line("if (text.equals(firstTokenText(candidate))) {");
        w.indent();
        w.line("if (seen == index) {");
        w.indent();
        w.line("return candidate;");
        w.dedent();
        w.line("}");
        w.line("seen = seen + 1;");
        w.dedent();
        w.line("}");
        w.dedent();
        w.line("}");
        w.line("if (!direct.isEmpty()) {");
        w.indent();
        w.line("return null;");
        w.dedent();
        w.line("}");
        w.line("seen = 0;");
        w.line("for (Token candidate : findDescendantsBounded(token, parserClass)) {");
        w.indent();
        w.line("if (text.equals(firstTokenText(candidate))) {");
        w.indent();
        w.line("if (seen == index) {");
        w.indent();
        w.line("return candidate;");
        w.dedent();
        w.line("}");
        w.line("seen = seen + 1;");
        w.dedent();
        w.line("}");
        w.dedent();
        w.line("}");
        w.line("return null;");
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

        // Token is always org.unlaxer.Token (imported in the generated mapper): getToken()/tokenString/
        // source are public and stable, so call them directly. The previous getClass().getMethod/getField
        // reflection allocated a Method/Field + PublicMethods$MethodList + Class[] on every call; under
        // deeply nested expressions this was, together with the per-token re-mapping, the dominant
        // allocation that drove parse time and GC. (tinyexpression #49)
        w.line("static String tokenTextCompat(Token token) {");
        w.indent();
        w.line("if (token == null) {");
        w.indent();
        w.line("return null;");
        w.dedent();
        w.line("}");
        w.line("Optional<String> tokenValue = token.getToken();");
        w.line("if (tokenValue != null && tokenValue.isPresent()) {");
        w.indent();
        w.line("String v = tokenValue.get();");
        w.line("return v == null ? null : String.valueOf(v);");
        w.dedent();
        w.line("}");
        w.line("if (token.tokenString != null && token.tokenString.isPresent()) {");
        w.indent();
        w.line("String v = token.tokenString.get();");
        w.line("return v == null ? null : String.valueOf(v);");
        w.dedent();
        w.line("}");
        w.line("org.unlaxer.Source src = token.source;");
        w.line("if (src != null) {");
        w.indent();
        w.line("String v = src.sourceAsString();");
        w.line("return v == null ? null : String.valueOf(v);");
        w.dedent();
        w.line("}");
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
        w.line("org.unlaxer.Source source = token.source;");
        w.line("if (source == null) {");
        w.indent();
        w.line("return 0;");
        w.dedent();
        w.line("}");
        w.line("org.unlaxer.CodePointOffset offset = source.offsetFromRoot();");
        w.line("if (offset == null) {");
        w.indent();
        w.line("return 0;");
        w.dedent();
        w.line("}");
        w.line("return offset.value();");
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
        w.line("String text = tokenTextCompat(token);");
        w.line("int length = text == null ? 0 : text.codePointCount(0, text.length());");
        w.line("int end = start + length;");
        w.line("NODE_SOURCE_SPANS.put(node, new int[]{start, end});");
        w.line("return node;");
        w.dedent();
        w.line("}");
        w.blankLine();

        w.line("/** Legacy lookup for the latest mapping; use SourceMappedAst to retain positions. */");
        w.line("public static synchronized Optional<int[]> sourceSpanOf(Object node) {");
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
