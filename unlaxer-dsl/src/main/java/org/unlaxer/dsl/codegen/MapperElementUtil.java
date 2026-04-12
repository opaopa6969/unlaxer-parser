package org.unlaxer.dsl.codegen;

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
