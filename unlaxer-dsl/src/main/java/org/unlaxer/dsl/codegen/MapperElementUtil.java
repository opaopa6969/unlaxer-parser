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
        if (body instanceof ChoiceBody choice) {
            choice.alternatives().forEach(seq -> collectRuleRefsFromBody(seq, refs));
        } else if (body instanceof SequenceBody seq) {
            seq.elements().forEach(e -> collectRuleRefsFromElement(e.element(), refs));
        } else {
            throw new IllegalStateException("unhandled " + body);
        }
    }

    private static void collectRuleRefsFromElement(AtomicElement element, List<RuleRefElement> refs) {
        if (element instanceof RuleRefElement ref) {
            refs.add(ref);
        } else if (element instanceof GroupElement g) {
            collectRuleRefsFromBody(g.body(), refs);
        } else if (element instanceof OptionalElement o) {
            collectRuleRefsFromBody(o.body(), refs);
        } else if (element instanceof RepeatElement r) {
            collectRuleRefsFromBody(r.body(), refs);
        } else if (element instanceof UBNFAST.OneOrMoreElement one) {
            collectRuleRefsFromElement(one.body(), refs);
        } else if (element instanceof UBNFAST.BoundedRepeatElement b) {
            collectRuleRefsFromElement(b.body(), refs);
        } else if (element instanceof UBNFAST.SeparatedElement s) {
            collectRuleRefsFromElement(s.element(), refs);
            collectRuleRefsFromElement(s.separator(), refs);
        } else {

        }
    }

    static Optional<SequenceBody> firstSequence(RuleBody body) {
        if (body instanceof SequenceBody sequenceBody) {
            return Optional.of(sequenceBody);
        }
        if (body instanceof ChoiceBody choiceBody) {
            return choiceBody.alternatives().stream().findFirst();
        }
        throw new IllegalStateException("unhandled " + body);
    }

    static Optional<AtomicElement> findCapturedElement(RuleBody body, String captureName) {
        if (body instanceof ChoiceBody choiceBody) {
            return choiceBody.alternatives().stream()
                .flatMap(alt -> findCapturedElement(alt, captureName).stream())
                .findFirst();
        }
        if (body instanceof SequenceBody sequenceBody) {
            for (AnnotatedElement element : sequenceBody.elements()) {
                if (element.captureName().isPresent() && captureName.equals(element.captureName().get())) {
                    return Optional.of(element.element());
                }
                Optional<AtomicElement> nested = findCapturedElementInAtomic(element.element(), captureName);
                if (nested.isPresent()) {
                    return nested;
                }
            }
            return Optional.empty();
        }
        throw new IllegalStateException("unhandled " + body);
    }

    static Optional<AtomicElement> findCapturedElementInAtomic(AtomicElement element, String captureName) {
        if (element instanceof GroupElement groupElement) {
            return findCapturedElement(groupElement.body(), captureName);
        }
        if (element instanceof OptionalElement optionalElement) {
            return findCapturedElement(optionalElement.body(), captureName);
        }
        if (element instanceof RepeatElement repeatElement) {
            return findCapturedElement(repeatElement.body(), captureName);
        }
        if (element instanceof UBNFAST.OneOrMoreElement one) {
            return findCapturedElementInAtomic(one.body(), captureName);
        }
        if (element instanceof UBNFAST.BoundedRepeatElement bounded) {
            return findCapturedElementInAtomic(bounded.body(), captureName);
        }
        if (element instanceof UBNFAST.SeparatedElement separated) {
            return findCapturedElementInAtomic(separated.element(), captureName);
        }
        return Optional.empty();
    }

    static List<AtomicElement> findCapturedElements(RuleBody body, String captureName) {
        if (body instanceof ChoiceBody choiceBody) {
            return choiceBody.alternatives().stream()
                .flatMap(alt -> findCapturedElements(alt, captureName).stream())
                .toList();
        }
        if (body instanceof SequenceBody sequenceBody) {
            List<AtomicElement> elements = new ArrayList<>();
            for (AnnotatedElement element : sequenceBody.elements()) {
                if (element.captureName().isPresent() && captureName.equals(element.captureName().get())) {
                    elements.add(element.element());
                }
                elements.addAll(findCapturedElementsInAtomic(element.element(), captureName));
            }
            return elements;
        }
        throw new IllegalStateException("unhandled " + body);
    }

    static List<AtomicElement> findCapturedElementsInAtomic(AtomicElement element, String captureName) {
        if (element instanceof GroupElement groupElement) {
            return findCapturedElements(groupElement.body(), captureName);
        }
        if (element instanceof OptionalElement optionalElement) {
            return findCapturedElements(optionalElement.body(), captureName);
        }
        if (element instanceof RepeatElement repeatElement) {
            return findCapturedElements(repeatElement.body(), captureName);
        }
        if (element instanceof UBNFAST.OneOrMoreElement one) {
            return findCapturedElementsInAtomic(one.body(), captureName);
        }
        if (element instanceof UBNFAST.BoundedRepeatElement bounded) {
            return findCapturedElementsInAtomic(bounded.body(), captureName);
        }
        if (element instanceof UBNFAST.SeparatedElement separated) {
            return findCapturedElementsInAtomic(separated.element(), captureName);
        }
        return List.of();
    }

    static Optional<String> parserClassLiteral(AtomicElement element, String parsersClass,
        Map<String, TokenDecl> tokenDeclByName, Map<String, RuleDecl> ruleByName) {

        if (element instanceof RuleRefElement ruleRefElement) {
            if (ruleByName.containsKey(ruleRefElement.name())) {
                return Optional.of(parsersClass + "." + ruleRefElement.name() + "Parser.class");
            }
            if (tokenDeclByName.containsKey(ruleRefElement.name())) {
                TokenDecl tokenDecl = tokenDeclByName.get(ruleRefElement.name());
                if (tokenDecl instanceof TokenDecl.Simple simple) {
                    String parserClass = simple.parserClass();
                    String simpleName = parserClass.substring(parserClass.lastIndexOf('.') + 1);
                    if ("NumberParser".equals(simpleName) || "DigitParser".equals(simpleName)) {
                        if (parserClass.contains(".")) {
                            return Optional.of(parsersClass + "." + ParserCodegenUtil.toParserClassName(ruleRefElement.name()) + ".class");
                        }
                        String prefix = "NumberParser".equals(simpleName)
                            ? "org.unlaxer.parser.elementary." : "org.unlaxer.parser.posix.";
                        return Optional.of(prefix + simpleName + ".class");
                    }
                }
                if (isIdentifierToken(tokenDecl)) {
                    // Mirror ParserRuleEmitter.resolveParserClass exactly so the mapper
                    // references the SAME class the parser put in the tree (findDescendants
                    // matches by exact getClass()). A fully-qualified token parser is wrapped
                    // in a generated subclass (ParserTokenEmitter, "Plan S: findDescendants
                    // 互換"); a short-named one is referenced directly via the parser's import.
                    // Previously this always emitted the base clang class, so for FQN tokens
                    // the capture (VariableRef @name, import @alias/@method) silently resolved
                    // to empty and only the source-snippet shadow recovered it.
                    // (tinyexpression #32: empty VariableRefExpr.name on the pure AST path)
                    String parserClass = tokenDecl.parserClass();
                    if (parserClass != null && parserClass.contains(".")) {
                        return Optional.of(parsersClass + "." + ParserCodegenUtil.toParserClassName(ruleRefElement.name()) + ".class");
                    }
                    return Optional.of("org.unlaxer.parser.clang.IdentifierParser.class");
                }
                return Optional.empty();
            }
            return Optional.empty();
        }
        if (element instanceof TerminalElement ignored) {
            return Optional.of("org.unlaxer.parser.elementary.WordParser.class");
        }
        return Optional.empty();
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
        if ("Integer".equals(MapperTypeResolver.boxedType(targetType))
            || "Long".equals(MapperTypeResolver.boxedType(targetType))) {
            String parseMethod = "Integer".equals(MapperTypeResolver.boxedType(targetType))
                ? "Integer.parseInt" : "Long.parseLong";
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
        // A heterogeneous Object @value bound to a transparent choice rule (no @mapping of
        // its own, alternatives mapping to several different AST classes — e.g. a
        // BooleanComparable ::= InMethod | IsPresentFunction | ... rule) was previously
        // flattened to firstTokenText, silently dropping the real node it wraps. Resolve the
        // matched alternative's node instead. Object-typed, so a node or the text fallback
        // both fit the field. (unlaxer-parser #43 family / tinyexpression #32)
        if ("Object".equals(targetType) && containsMappedValue(element, ruleByName)) {
            return "mapTransparentValue(" + tokenVar + ")";
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

    /**
     * True if {@code rule} is a transparent mapped alias/choice used as an {@code Object}
     * capture: it has no {@code @mapping} of its own and can reach mapped
     * values through groups or aliases (including a single mapped target or mixed text). Such a capture's
     * field is inferred as {@code Object}, so the matched alternative's node must be resolved
     * at runtime rather than dropped to {@code firstTokenText}. (unlaxer-parser #43 family)
     */
    static boolean isTransparentMappedChoice(RuleDecl rule, Map<String, RuleDecl> ruleByName) {
        if (rule == null || getMappingAnnotation(rule).isPresent()) {
            return false;
        }
        return containsMappedValue(new GroupElement(rule.body()), ruleByName);
    }

    /** Mapper value predicate; stop at mapped boundaries and guard recursive aliases. */
    static boolean containsMappedValue(AtomicElement element, Map<String, RuleDecl> ruleByName) {
        return containsMappedValue(element, ruleByName, new java.util.HashSet<>());
    }

    private static boolean containsMappedValue(AtomicElement element, Map<String, RuleDecl> ruleByName,
            java.util.Set<String> visited) {
        if (element instanceof RuleRefElement ref) {
            if (!visited.add(ref.name())) return false;
            RuleDecl rule = ruleByName.get(ref.name());
            return rule != null && (getMappingAnnotation(rule).isPresent()
                || containsMappedValue(new GroupElement(rule.body()), ruleByName, visited));
        }
        Object value = captureValueShape(element);
        if (value instanceof RuleRefElement ref) return containsMappedValue(ref, ruleByName, visited);
        RuleBody body = value instanceof GroupElement group ? group.body()
            : value instanceof RuleBody compound ? compound : null;
        return body != null && collectRuleRefs(body).stream().anyMatch(ref -> containsMappedValue(ref, ruleByName, visited));
    }

    static boolean isIdentifierToken(TokenDecl tokenDecl) {
        if (tokenDecl == null || tokenDecl.parserClass() == null) {
            return false;
        }
        return tokenDecl.parserClass().contains("IdentifierParser");
    }

    /**
     * Text-only compound captures denote their entire bound grammar site. A first-leaf
     * lookup would truncate a sequence or miss a different choice branch. Keep mapped
     * AST/enum dispatch and direct token conversions on their existing typed path.
     */
    static boolean usesBoundTextCapture(AtomicElement element, Map<String, RuleDecl> ruleByName,
            Map<String, TokenDecl> tokenDeclByName) {
        Object value = captureValueShape(element);
        final RuleBody body;
        if (value instanceof GroupElement group) {
            body = group.body();
        } else if (value instanceof RuleBody compound) {
            body = compound;
        } else {
            body = null;
        }
        if (body == null) return false;
        var visited = new java.util.HashSet<String>();
        return collectRuleRefs(body).stream().noneMatch(ref ->
            reachesMappedOrEnum(ref.name(), ruleByName, tokenDeclByName, visited));
    }

    // Mirrors CaptureBindingPlan.bindValues: unwrap cardinality, but not a source group.
    private static Object captureValueShape(AtomicElement element) {
        if (element instanceof OptionalElement optional) {
            return captureValueShape(optional.body());
        }
        if (element instanceof RepeatElement repeat) {
            return captureValueShape(repeat.body());
        }
        if (element instanceof UBNFAST.OneOrMoreElement repeat) {
            return captureValueShape(repeat.body());
        }
        if (element instanceof UBNFAST.BoundedRepeatElement repeat) {
            return captureValueShape(repeat.body());
        }
        if (element instanceof UBNFAST.SeparatedElement separated) {
            return captureValueShape(separated.element());
        }
        return element;
    }

    private static Object captureValueShape(RuleBody body) {
        if (body instanceof ChoiceBody choice && choice.alternatives().size() == 1) {
            return captureValueShape(choice.alternatives().get(0));
        }
        if (body instanceof SequenceBody sequence && sequence.elements().size() == 1) {
            return captureValueShape(sequence.elements().get(0).element());
        }
        return body;
    }

    private static boolean reachesMappedOrEnum(String name, Map<String, RuleDecl> ruleByName,
            Map<String, TokenDecl> tokenDeclByName, java.util.Set<String> visited) {
        if (tokenDeclByName.containsKey(name) || !visited.add(name)) return false;
        RuleDecl rule = ruleByName.get(name);
        if (rule == null) return false;
        if (getMappingAnnotation(rule).isPresent()
                || rule.annotations().stream().anyMatch(a -> a instanceof UBNFAST.EnumAnnotation)) return true;
        return collectRuleRefs(rule.body()).stream().anyMatch(ref ->
            reachesMappedOrEnum(ref.name(), ruleByName, tokenDeclByName, visited));
    }

    static Optional<AtomicElement> normalizeCapturedElement(AtomicElement element) {
        if (element instanceof GroupElement groupElement) {
            return firstAtomicElement(groupElement.body());
        }
        if (element instanceof OptionalElement optionalElement) {
            return firstAtomicElement(optionalElement.body());
        }
        if (element instanceof RepeatElement repeatElement) {
            return firstAtomicElement(repeatElement.body());
        }
        if (element instanceof UBNFAST.OneOrMoreElement one) {
            return normalizeCapturedElement(one.body());
        }
        if (element instanceof UBNFAST.BoundedRepeatElement bounded) {
            return normalizeCapturedElement(bounded.body());
        }
        if (element instanceof UBNFAST.SeparatedElement separated) {
            return normalizeCapturedElement(separated.element());
        }
        return Optional.of(element);
    }

    static Optional<AtomicElement> firstAtomicElement(RuleBody body) {
        if (body instanceof SequenceBody sequenceBody) {
            return sequenceBody.elements().stream()
                .findFirst()
                .map(AnnotatedElement::element)
                .flatMap(MapperElementUtil::normalizeCapturedElement);
        }
        if (body instanceof ChoiceBody choiceBody) {
            return choiceBody.alternatives().stream()
                .findFirst()
                .flatMap(MapperElementUtil::firstAtomicElement);
        }
        throw new IllegalStateException("unhandled " + body);
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
        if (body instanceof ChoiceBody choiceBody) {
            for (SequenceBody seq : choiceBody.alternatives()) {
                collectTypeofConstraintsFromSequence(seq, result);
            }
        } else if (body instanceof SequenceBody seq) {
            collectTypeofConstraintsFromSequence(seq, result);
        } else {
            throw new IllegalStateException("unhandled " + body);
        }
    }

    static void collectTypeofConstraintsFromSequence(SequenceBody seq, Map<String, String> result) {
        for (AnnotatedElement ae : seq.elements()) {
            if (ae.typeofConstraint().isPresent() && ae.captureName().isPresent()) {
                TypeofElement te = ae.typeofConstraint().get();
                result.put(ae.captureName().get(), te.captureName());
            } else {
                var switchSubject = ae.element();
                if (switchSubject instanceof GroupElement g) {
                    collectTypeofConstraintsFromBody(g.body(), result);
                } else if (switchSubject instanceof OptionalElement o) {
                    collectTypeofConstraintsFromBody(o.body(), result);
                } else if (switchSubject instanceof RepeatElement r) {
                    collectTypeofConstraintsFromBody(r.body(), result);
                } else {

                }
            }
        }
    }
}
