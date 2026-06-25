package org.unlaxer.dsl.codegen;

import org.unlaxer.dsl.bootstrap.UBNFAST;
import org.unlaxer.dsl.bootstrap.UBNFAST.AnnotatedElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.AtomicElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.ChoiceBody;
import org.unlaxer.dsl.bootstrap.UBNFAST.GroupElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.LeftAssocAnnotation;
import org.unlaxer.dsl.bootstrap.UBNFAST.MappingAnnotation;
import org.unlaxer.dsl.bootstrap.UBNFAST.OptionalElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.RepeatElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.RightAssocAnnotation;
import org.unlaxer.dsl.bootstrap.UBNFAST.RuleBody;
import org.unlaxer.dsl.bootstrap.UBNFAST.RuleDecl;
import org.unlaxer.dsl.bootstrap.UBNFAST.RuleRefElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.SequenceBody;
import org.unlaxer.dsl.bootstrap.UBNFAST.TerminalElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.TokenDecl;
import org.unlaxer.dsl.bootstrap.UBNFAST.TypeofElement;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 要素の探索・マッピング式生成・アソシエイティビティ判定などのユーティリティ。
 */
class MapperElementUtil {

    private MapperElementUtil() {}

    record AssocShape(AtomicElement leftElement, AtomicElement opElement, AtomicElement rightElement,
                      int repeatIndex) {}

    static boolean isLeftAssocRule(RuleDecl rule, MappingAnnotation mapping) {
        boolean hasLeftAssoc = rule.annotations().stream().anyMatch(a -> a instanceof LeftAssocAnnotation);
        if (!hasLeftAssoc) {
            return false;
        }
        List<String> params = mapping.paramNames();
        return params.contains("left") && params.contains("op") && params.contains("right");
    }

    static boolean isRightAssocRule(RuleDecl rule, MappingAnnotation mapping) {
        boolean hasRightAssoc = rule.annotations().stream().anyMatch(a -> a instanceof RightAssocAnnotation);
        if (!hasRightAssoc) {
            return false;
        }
        List<String> params = mapping.paramNames();
        return params.contains("left") && params.contains("op") && params.contains("right");
    }

    static Optional<AssocShape> findAssocShape(RuleDecl rule, String leftCapture, String opCapture, String rightCapture) {
        SequenceBody sequence = firstSequence(rule.body()).orElse(null);
        if (sequence == null) {
            return Optional.empty();
        }

        AtomicElement leftElement = findCapturedElement(rule.body(), leftCapture).orElse(null);
        int repeatIndex = 0;
        for (AnnotatedElement element : sequence.elements()) {
            AtomicElement atomic = element.element();
            if (atomic instanceof RepeatElement repeatElement) {
                Optional<AtomicElement> opElement = findCapturedElement(repeatElement.body(), opCapture);
                Optional<AtomicElement> rightElement = findCapturedElement(repeatElement.body(), rightCapture);
                if (opElement.isPresent() && rightElement.isPresent()) {
                    if (leftElement != null) {
                        return Optional.of(new AssocShape(leftElement, opElement.get(), rightElement.get(), repeatIndex));
                    }
                    return Optional.empty();
                }
                repeatIndex++;
            }
        }
        return Optional.empty();
    }

    // =========================================================================
    // Heterogeneous assoc-operand detection (unlaxer-parser #43)
    //
    // A left/right-assoc rule folds operands that come from a lower-precedence
    // rule (e.g. a Factor). When that operand rule is a *transparent* alternation
    // (no @mapping) whose alternatives map to AST classes other than the assoc's
    // own class — e.g. NumberFactor → MathFunction(AbsExpr) | '(' NumberExpression ')'(BinaryExpr)
    // — the operand can be any of those node types at runtime. The default fold
    // recursed into the operand via the assoc mapper, descending past the function
    // wrapper and dropping it. For such heterogeneous classes we widen the operand
    // field/variable type to the base AST interface and dispatch each operand to its
    // actual mapped type. Homogeneous classes (operand only ever the assoc class or a
    // literal token) keep the previous behaviour, so their generated code is unchanged.
    // =========================================================================

    /**
     * Returns true if any left/right-assoc rule mapping to {@code className} has an
     * operand that can resolve to an AST class other than {@code className}.
     */
    static boolean assocClassHasHeterogeneousOperand(String className, List<RuleDecl> rulesForClass,
        Map<String, RuleDecl> ruleByName, Map<String, TokenDecl> tokenDeclByName) {
        for (RuleDecl rule : rulesForClass) {
            MappingAnnotation mapping = getMappingAnnotation(rule).orElse(null);
            if (mapping == null) {
                continue;
            }
            if (!isLeftAssocRule(rule, mapping) && !isRightAssocRule(rule, mapping)) {
                continue;
            }
            Optional<AssocShape> shape = findAssocShape(rule, "left", "op", "right");
            if (shape.isEmpty()) {
                continue;
            }
            for (AtomicElement operand : List.of(shape.get().leftElement(), shape.get().rightElement())) {
                if (operandReachesForeignMappedClass(operand, className, ruleByName, tokenDeclByName)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * True if {@code operand} references a <em>transparent</em> rule (no {@code @mapping})
     * that can resolve to a mapped AST class <em>other than</em> {@code assocClassName}.
     * That is the problem case: {@link #mapExpressionForElement} degrades a transparent
     * rule reference to {@code firstTokenText}, silently dropping the foreign node it wraps
     * (e.g. a MathFunction factor mapping to AbsExpr). A transparent operand that only ever
     * reaches the assoc class itself (e.g. a parenthesised sub-expression) is fine and is
     * NOT widened, keeping pure-arithmetic grammars byte-identical. Operands referencing a
     * @mapping'd rule already dispatch to that rule's mapper and never trigger widening.
     */
    private static boolean operandReachesForeignMappedClass(AtomicElement operand,
        String assocClassName, Map<String, RuleDecl> ruleByName, Map<String, TokenDecl> tokenDeclByName) {
        if (!(operand instanceof RuleRefElement ref)) {
            return false;
        }
        RuleDecl rule = ruleByName.get(ref.name());
        if (rule == null || getMappingAnnotation(rule).isPresent()) {
            return false;
        }
        for (String reachable : reachableMappedClasses(operand, ruleByName, tokenDeclByName)) {
            if (!reachable.equals(assocClassName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The set of @mapping class names a captured operand element can resolve to at
     * runtime. A reference to a @mapping'd rule contributes that class and stops; a
     * reference to a transparent rule (no @mapping) descends into all its rule
     * references; token/terminal references contribute nothing (they become literals).
     */
    static java.util.Set<String> reachableMappedClasses(AtomicElement element,
        Map<String, RuleDecl> ruleByName, Map<String, TokenDecl> tokenDeclByName) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        if (element instanceof RuleRefElement ref) {
            collectReachableMappedClasses(ref.name(), ruleByName, tokenDeclByName, out, new java.util.HashSet<>());
        }
        return out;
    }

    private static void collectReachableMappedClasses(String name, Map<String, RuleDecl> ruleByName,
        Map<String, TokenDecl> tokenDeclByName, java.util.Set<String> out, java.util.Set<String> visited) {
        if (name == null || !visited.add(name)) {
            return;
        }
        if (tokenDeclByName.containsKey(name)) {
            return;
        }
        RuleDecl rule = ruleByName.get(name);
        if (rule == null) {
            return;
        }
        Optional<MappingAnnotation> mapping = getMappingAnnotation(rule);
        if (mapping.isPresent()) {
            out.add(mapping.get().className());
            return;
        }
        for (RuleRefElement ref : collectRuleRefs(rule.body())) {
            collectReachableMappedClasses(ref.name(), ruleByName, tokenDeclByName, out, visited);
        }
    }

    /** All rule references appearing anywhere in a rule body (recursively). */
    static List<RuleRefElement> collectRuleRefs(RuleBody body) {
        List<RuleRefElement> refs = new ArrayList<>();
        collectRuleRefsFromBody(body, refs);
        return refs;
    }

    private static void collectRuleRefsFromBody(RuleBody body, List<RuleRefElement> refs) {
        switch (body) {
            case ChoiceBody choice -> choice.alternatives().forEach(seq -> collectRuleRefsFromBody(seq, refs));
            case SequenceBody seq -> seq.elements().forEach(e -> collectRuleRefsFromElement(e.element(), refs));
        }
    }

    private static void collectRuleRefsFromElement(AtomicElement element, List<RuleRefElement> refs) {
        switch (element) {
            case RuleRefElement ref -> refs.add(ref);
            case GroupElement g -> collectRuleRefsFromBody(g.body(), refs);
            case OptionalElement o -> collectRuleRefsFromBody(o.body(), refs);
            case RepeatElement r -> collectRuleRefsFromBody(r.body(), refs);
            case UBNFAST.OneOrMoreElement one -> collectRuleRefsFromElement(one.body(), refs);
            case UBNFAST.BoundedRepeatElement b -> collectRuleRefsFromElement(b.body(), refs);
            case UBNFAST.SeparatedElement s -> {
                collectRuleRefsFromElement(s.element(), refs);
                collectRuleRefsFromElement(s.separator(), refs);
            }
            default -> { }
        }
    }

    static Optional<SequenceBody> firstSequence(RuleBody body) {
        return switch (body) {
            case SequenceBody sequenceBody -> Optional.of(sequenceBody);
            case ChoiceBody choiceBody -> choiceBody.alternatives().stream().findFirst();
        };
    }

    static Optional<AtomicElement> findCapturedElement(RuleBody body, String captureName) {
        return switch (body) {
            case ChoiceBody choiceBody -> choiceBody.alternatives().stream()
                .flatMap(alt -> findCapturedElement(alt, captureName).stream())
                .findFirst();
            case SequenceBody sequenceBody -> {
                for (AnnotatedElement element : sequenceBody.elements()) {
                    if (element.captureName().isPresent() && captureName.equals(element.captureName().get())) {
                        yield Optional.of(element.element());
                    }
                    Optional<AtomicElement> nested = findCapturedElementInAtomic(element.element(), captureName);
                    if (nested.isPresent()) {
                        yield nested;
                    }
                }
                yield Optional.empty();
            }
        };
    }

    static Optional<AtomicElement> findCapturedElementInAtomic(AtomicElement element, String captureName) {
        return switch (element) {
            case GroupElement groupElement -> findCapturedElement(groupElement.body(), captureName);
            case OptionalElement optionalElement -> findCapturedElement(optionalElement.body(), captureName);
            case RepeatElement repeatElement -> findCapturedElement(repeatElement.body(), captureName);
            default -> Optional.empty();
        };
    }

    static List<AtomicElement> findCapturedElements(RuleBody body, String captureName) {
        return switch (body) {
            case ChoiceBody choiceBody -> choiceBody.alternatives().stream()
                .flatMap(alt -> findCapturedElements(alt, captureName).stream())
                .toList();
            case SequenceBody sequenceBody -> {
                List<AtomicElement> elements = new ArrayList<>();
                for (AnnotatedElement element : sequenceBody.elements()) {
                    if (element.captureName().isPresent() && captureName.equals(element.captureName().get())) {
                        elements.add(element.element());
                    }
                    elements.addAll(findCapturedElementsInAtomic(element.element(), captureName));
                }
                yield elements;
            }
        };
    }

    static List<AtomicElement> findCapturedElementsInAtomic(AtomicElement element, String captureName) {
        return switch (element) {
            case GroupElement groupElement -> findCapturedElements(groupElement.body(), captureName);
            case OptionalElement optionalElement -> findCapturedElements(optionalElement.body(), captureName);
            case RepeatElement repeatElement -> findCapturedElements(repeatElement.body(), captureName);
            default -> List.of();
        };
    }

    static Optional<String> parserClassLiteral(AtomicElement element, String parsersClass,
        Map<String, TokenDecl> tokenDeclByName, Map<String, RuleDecl> ruleByName) {

        return switch (element) {
            case RuleRefElement ruleRefElement -> {
                if (ruleByName.containsKey(ruleRefElement.name())) {
                    yield Optional.of(parsersClass + "." + ruleRefElement.name() + "Parser.class");
                }
                if (tokenDeclByName.containsKey(ruleRefElement.name())) {
                    TokenDecl tokenDecl = tokenDeclByName.get(ruleRefElement.name());
                    if (isIdentifierToken(tokenDecl)) {
                        yield Optional.of("org.unlaxer.parser.clang.IdentifierParser.class");
                    }
                    yield Optional.empty();
                }
                yield Optional.empty();
            }
            case TerminalElement ignored -> Optional.of("org.unlaxer.parser.elementary.WordParser.class");
            default -> Optional.empty();
        };
    }

    /**
     * Convert a (possibly dotted) AST class name like {@code "Outer.Inner"}
     * into a Java identifier suffix usable in method names like {@code "toOuterInner"}.
     * For non-dotted names this is the identity. Used by all sites that emit
     * helper method names tied to a {@code @mapping} class name.
     */
    static String methodNameFor(String className) {
        if (className == null || className.indexOf('.') < 0) {
            return className;
        }
        return className.replace(".", "");
    }

    static String mapExpressionForElement(AtomicElement element, String tokenVar,
        Map<String, String> mappedClassByRuleName,
        Map<String, TokenDecl> tokenDeclByName,
        Map<String, RuleDecl> ruleByName) {

        if (element instanceof RuleRefElement ruleRefElement) {
            String name = ruleRefElement.name();
            if (mappedClassByRuleName.containsKey(name)) {
                return "to" + methodNameFor(mappedClassByRuleName.get(name)) + "(" + tokenVar + ")";
            }
            if (tokenDeclByName.containsKey(name)) {
                TokenDecl tokenDecl = tokenDeclByName.get(name);
                if (isIdentifierToken(tokenDecl)) {
                    return "identifierLikeText(" + tokenVar + ")";
                }
                return "stripQuotes(firstTokenText(" + tokenVar + "))";
            }
            if (ruleByName.containsKey(name)) {
                return "stripQuotes(firstTokenText(" + tokenVar + "))";
            }
        }
        if (element instanceof TerminalElement) {
            return "stripQuotes(firstTokenText(" + tokenVar + "))";
        }
        return "stripQuotes(firstTokenText(" + tokenVar + "))";
    }

    static String mapExpressionForTargetType(String targetType, AtomicElement element, String tokenVar,
        Map<String, String> mappedClassByRuleName,
        Map<String, TokenDecl> tokenDeclByName,
        Map<String, RuleDecl> ruleByName) {
        if ("int".equals(targetType) || "long".equals(targetType)) {
            String parseMethod = "int".equals(targetType) ? "Integer.parseInt" : "Long.parseLong";
            return parseMethod + "(firstTokenText(" + tokenVar + "))";
        }
        // enum 型: ASTClass.EnumName 形式 → fromText 生成
        // enum ルール名はドット後がルール名と一致する（例: MyAST.RecoveryMode）
        if (targetType.contains(".") && !targetType.startsWith("List<") && !targetType.startsWith("Optional<")) {
            // enum 型かどうかの確認: ruleByName に @enum ルールがあれば
            String simpleName = targetType.substring(targetType.lastIndexOf('.') + 1);
            boolean isEnum = ruleByName.containsKey(simpleName) &&
                ruleByName.get(simpleName).annotations().stream()
                    .anyMatch(a -> a instanceof UBNFAST.EnumAnnotation);
            if (isEnum) {
                return targetType + ".fromText(stripQuotes(firstTokenText(" + tokenVar + ")))";
            }
        }
        if (!"String".equals(targetType)) {
            return mapExpressionForElement(element, tokenVar, mappedClassByRuleName, tokenDeclByName, ruleByName);
        }
        if (element instanceof RuleRefElement ruleRefElement) {
            TokenDecl tokenDecl = tokenDeclByName.get(ruleRefElement.name());
            if (isIdentifierToken(tokenDecl)) {
                return "identifierLikeText(" + tokenVar + ")";
            }
        }
        return "stripQuotes(firstTokenText(" + tokenVar + "))";
    }

    static boolean isIdentifierToken(TokenDecl tokenDecl) {
        if (tokenDecl == null || tokenDecl.parserClass() == null) {
            return false;
        }
        return tokenDecl.parserClass().contains("IdentifierParser");
    }

    static Optional<AtomicElement> normalizeCapturedElement(AtomicElement element) {
        return switch (element) {
            case GroupElement groupElement -> firstAtomicElement(groupElement.body());
            case OptionalElement optionalElement -> firstAtomicElement(optionalElement.body());
            case RepeatElement repeatElement -> firstAtomicElement(repeatElement.body());
            default -> Optional.of(element);
        };
    }

    static Optional<AtomicElement> firstAtomicElement(RuleBody body) {
        return switch (body) {
            case SequenceBody sequenceBody -> sequenceBody.elements().stream()
                .findFirst()
                .map(AnnotatedElement::element)
                .flatMap(MapperElementUtil::normalizeCapturedElement);
            case ChoiceBody choiceBody -> choiceBody.alternatives().stream()
                .findFirst()
                .flatMap(MapperElementUtil::firstAtomicElement);
        };
    }

    static String safeName(String name) {
        return name.replaceAll("[^A-Za-z0-9_]", "_");
    }

    static Optional<MappingAnnotation> getMappingAnnotation(RuleDecl rule) {
        return rule.annotations().stream()
            .filter(a -> a instanceof MappingAnnotation)
            .map(a -> (MappingAnnotation) a)
            .findFirst();
    }

    /** ルール本体から @typeof(x) @param の関係を収集する: paramName -> referencedCaptureName */
    static Map<String, String> collectTypeofConstraints(RuleBody body) {
        Map<String, String> result = new LinkedHashMap<>();
        collectTypeofConstraintsFromBody(body, result);
        return result;
    }

    static void collectTypeofConstraintsFromBody(RuleBody body, Map<String, String> result) {
        switch (body) {
            case ChoiceBody choiceBody -> {
                for (SequenceBody seq : choiceBody.alternatives()) {
                    collectTypeofConstraintsFromSequence(seq, result);
                }
            }
            case SequenceBody seq -> collectTypeofConstraintsFromSequence(seq, result);
        }
    }

    static void collectTypeofConstraintsFromSequence(SequenceBody seq, Map<String, String> result) {
        for (AnnotatedElement ae : seq.elements()) {
            if (ae.typeofConstraint().isPresent() && ae.captureName().isPresent()) {
                TypeofElement te = ae.typeofConstraint().get();
                result.put(ae.captureName().get(), te.captureName());
            } else {
                switch (ae.element()) {
                    case GroupElement g -> collectTypeofConstraintsFromBody(g.body(), result);
                    case OptionalElement o -> collectTypeofConstraintsFromBody(o.body(), result);
                    case RepeatElement r -> collectTypeofConstraintsFromBody(r.body(), result);
                    default -> {}
                }
            }
        }
    }
}
