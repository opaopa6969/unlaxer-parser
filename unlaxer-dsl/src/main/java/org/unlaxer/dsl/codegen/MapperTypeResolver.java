package org.unlaxer.dsl.codegen;

import java.util.Map;
import org.unlaxer.dsl.bootstrap.UBNFAST;
import org.unlaxer.dsl.bootstrap.UBNFAST.AnnotatedElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.AtomicElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.BoundedRepeatElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.ChoiceBody;
import org.unlaxer.dsl.bootstrap.UBNFAST.ErrorElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.bootstrap.UBNFAST.GroupElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.MappingAnnotation;
import org.unlaxer.dsl.bootstrap.UBNFAST.OneOrMoreElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.OptionalElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.RepeatElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.RuleBody;
import org.unlaxer.dsl.bootstrap.UBNFAST.RuleDecl;
import org.unlaxer.dsl.bootstrap.UBNFAST.RuleRefElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.SeparatedElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.SequenceBody;
import org.unlaxer.dsl.bootstrap.UBNFAST.TerminalElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.TypeofElement;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 型推論・型解決を担当するユーティリティ。
 */
class MapperTypeResolver {

    private MapperTypeResolver() {}

    record CaptureResult(AtomicElement element, boolean inOptional, boolean inRepeat) {}

    // inferType logic is borrowed from ASTGenerator to keep generated constructor argument types compile-safe.
    static String inferType(GrammarDecl grammar, RuleDecl rule, String fieldName) {
        return new SemanticCardinality(grammar).fieldType(rule, fieldName, inferLegacyType(grammar, rule, fieldName));
    }

    private static String inferLegacyType(GrammarDecl grammar, RuleDecl rule, String fieldName) {
        List<CaptureResult> captures = findCapturedTypes(rule.body(), fieldName);
        if (captures.isEmpty()) {
            return "Object";
        }
        String innerType = mergeCapturedTypes(grammar, captures);
        boolean inOptional = captures.stream().anyMatch(CaptureResult::inOptional);
        boolean inRepeat = captures.stream().anyMatch(CaptureResult::inRepeat);
        if (inRepeat) {
            return "List<" + boxedType(innerType) + ">";
        }
        if (inOptional) {
            return "Optional<" + boxedType(innerType) + ">";
        }
        return innerType;
    }

    static String mergeCapturedTypes(GrammarDecl grammar, List<CaptureResult> captures) {
        Set<String> types = new LinkedHashSet<>();
        for (CaptureResult capture : captures) {
            types.add(inferTypeFromElement(grammar, capture.element()));
        }
        if (types.isEmpty()) {
            return "Object";
        }
        if (types.size() == 1) {
            return types.iterator().next();
        }
        return "Object";
    }

    static String inferTypeFromElement(GrammarDecl grammar, AtomicElement element) {
        String astClassName = grammar.name() + "AST";
        if (element instanceof TerminalElement ignored) {
            return "String";
        }
        if (element instanceof RuleRefElement ruleRefElement) {
            Optional<MappingAnnotation> mapping = grammar.rules().stream()
                .filter(r -> r.name().equals(ruleRefElement.name()))
                .flatMap(r -> r.annotations().stream())
                .filter(a -> a instanceof MappingAnnotation)
                .map(a -> (MappingAnnotation) a)
                .findFirst();
            if (mapping.isPresent()) {
                return astClassName + "." + mapping.get().className();
            }
            // @enum ルール参照 → enum 型として推論
            boolean isEnum = grammar.rules().stream()
                .filter(r -> r.name().equals(ruleRefElement.name()))
                .flatMap(r -> r.annotations().stream())
                .anyMatch(a -> a instanceof UBNFAST.EnumAnnotation);
            if (isEnum) {
                return astClassName + "." + ruleRefElement.name();
            }
            // token 型推論: parser class 名から Java 型を導出
            String tokenType = inferTypeFromTokenName(grammar, ruleRefElement.name());
            if (tokenType != null) {
                return tokenType;
            }
            // 透過 mapped alias/choice は Object 経由で実ノードを保持する。
            if (isTransparentMappedChoice(grammar, ruleRefElement.name())) {
                return "Object";
            }
            return "String";
        }
        if (element instanceof RepeatElement repeatElement) {
            String inner = inferTypeFromBody(grammar, repeatElement.body());
            return "List<" + boxedType(inner) + ">";
        }
        if (element instanceof OneOrMoreElement oneOrMoreElement) {
            String inner = inferTypeFromElement(grammar, oneOrMoreElement.body());
            return "List<" + boxedType(inner) + ">";
        }
        if (element instanceof BoundedRepeatElement boundedRepeatElement) {
            String inner = inferTypeFromElement(grammar, boundedRepeatElement.body());
            return "List<" + boxedType(inner) + ">";
        }
        if (element instanceof OptionalElement optionalElement) {
            String inner = inferTypeFromBody(grammar, optionalElement.body());
            return "Optional<" + boxedType(inner) + ">";
        }
        if (element instanceof SeparatedElement sep) {
            String inner = inferTypeFromElement(grammar, sep.element());
            return "List<" + boxedType(inner) + ">";
        }
        if (element instanceof GroupElement ignored) {
            return "Object";
        }
        if (element instanceof ErrorElement ignored) {
            return "Object";
        }
        throw new IllegalStateException("unhandled " + element);
    }

    private static final Map<String, String> NUMERIC_PARSER_TYPES = Map.of(
        "NumberParser", "int",
        "DigitParser", "int"
    );

    /**
     * token 名から Java 型を推論する。
     * token UNSIGNED_INTEGER = NumberParser → "int"
     * @return Java 型文字列、推論できない場合は null
     */
    static String inferTypeFromTokenName(GrammarDecl grammar, String tokenName) {
        return grammar.tokens().stream()
            .filter(t -> t.name().equals(tokenName) && t instanceof UBNFAST.TokenDecl.Simple)
            .map(t -> (UBNFAST.TokenDecl.Simple) t)
            .map(t -> {
                String pc = t.parserClass();
                String simpleName = pc.contains(".") ? pc.substring(pc.lastIndexOf('.') + 1) : pc;
                return NUMERIC_PARSER_TYPES.get(simpleName);
            })
            .filter(type -> type != null)
            .findFirst()
            .orElse(null);
    }

    /**
     * 参照先ルールが「透過 mapped choice」かどうか判定する。
     * 自身に @mapping を持たず、alias/group を含む本体から mapped node に到達する場合 true。
     * 単一 target の純 alias も Node を保持し、暗黙に String へ変換しない。
     * 例: StringTerm（StringMatchExpression | SliceExpression | VariableRef | ... の透過 choice）。
     *
     * 判定基準は {@link MapperElementUtil#isTransparentMappedChoice} と揃えており、
     * 型推論（フィールド型）と mapper 生成式（mapTransparentValue）が一致するようにしている。
     */
    static boolean isTransparentMappedChoice(GrammarDecl grammar, String ruleName) {
        Map<String, RuleDecl> rules = new java.util.LinkedHashMap<>();
        grammar.rules().forEach(rule -> rules.put(rule.name(), rule));
        return MapperElementUtil.isTransparentMappedChoice(rules.get(ruleName), rules);
    }

    /** TypeofElement を参照するキャプチャ名から実際の AtomicElement に解決する */
    static AtomicElement resolveTypeofElement(TypeofElement typeofElement, RuleDecl rule) {
        return MapperElementUtil.findCapturedElement(rule.body(), typeofElement.captureName())
            .orElse(new RuleRefElement("?"));
    }

    static String inferTypeFromBody(GrammarDecl grammar, RuleBody body) {
        final AnnotatedElement single;
        if (body instanceof SequenceBody sequenceBody && sequenceBody.elements().size() == 1) {
            single = sequenceBody.elements().get(0);
        } else if (body instanceof ChoiceBody choiceBody && choiceBody.alternatives().size() == 1) {
            SequenceBody sequenceBody = choiceBody.alternatives().get(0);
            single = sequenceBody.elements().size() == 1 ? sequenceBody.elements().get(0) : null;
        } else {
            single = null;
        }
        if (single == null) {
            return "Object";
        }
        return inferTypeFromElement(grammar, single.element());
    }

    static List<CaptureResult> findCapturedTypes(RuleBody body, String captureName) {
        return findCapturedTypesInBody(body, captureName, false, false);
    }

    static List<CaptureResult> findCapturedTypesInBody(
        RuleBody body, String captureName, boolean inOptional, boolean inRepeat) {

        if (body instanceof ChoiceBody choiceBody) {
            return choiceBody.alternatives().stream()
                .flatMap(sequenceBody -> findCapturedTypesInSequence(sequenceBody, captureName, inOptional, inRepeat).stream())
                .toList();
        }
        if (body instanceof SequenceBody sequenceBody) {
            return findCapturedTypesInSequence(sequenceBody, captureName, inOptional, inRepeat);
        }
        throw new IllegalStateException("unhandled " + body);
    }

    static List<CaptureResult> findCapturedTypesInSequence(
        SequenceBody sequenceBody, String captureName, boolean inOptional, boolean inRepeat) {

        List<CaptureResult> captures = new ArrayList<>();
        for (AnnotatedElement element : sequenceBody.elements()) {
            if (element.captureName().isPresent() && element.captureName().get().equals(captureName)) {
                captures.add(new CaptureResult(element.element(), inOptional, inRepeat));
            }
            captures.addAll(findCapturedTypesInAtomic(element.element(), captureName, inOptional, inRepeat));
        }
        return captures;
    }

    static List<CaptureResult> findCapturedTypesInAtomic(
        AtomicElement element, String captureName, boolean inOptional, boolean inRepeat) {

        if (element instanceof OptionalElement optionalElement) {
            return findCapturedTypesInBody(optionalElement.body(), captureName, true, inRepeat);
        }
        if (element instanceof RepeatElement repeatElement) {
            return findCapturedTypesInBody(repeatElement.body(), captureName, inOptional, true);
        }
        if (element instanceof OneOrMoreElement one) {
            return findCapturedTypesInAtomic(one.body(), captureName, inOptional, true);
        }
        if (element instanceof BoundedRepeatElement bounded) {
            return findCapturedTypesInAtomic(bounded.body(), captureName, inOptional, true);
        }
        if (element instanceof UBNFAST.SeparatedElement separated) {
            return findCapturedTypesInAtomic(separated.element(), captureName, inOptional, true);
        }
        if (element instanceof GroupElement groupElement) {
            return findCapturedTypesInBody(groupElement.body(), captureName, inOptional, inRepeat);
        }
        return List.of();
    }

    static Optional<String> unwrapListType(String type) {
        if (type.startsWith("List<") && type.endsWith(">")) {
            return Optional.of(type.substring("List<".length(), type.length() - 1));
        }
        return Optional.empty();
    }

    static Optional<String> unwrapOptionalType(String type) {
        if (type.startsWith("Optional<") && type.endsWith(">")) {
            return Optional.of(type.substring("Optional<".length(), type.length() - 1));
        }
        return Optional.empty();
    }

    static String defaultValueForType(String type) {
        if (type.startsWith("List<")) {
            return "List.of()";
        }
        if (type.startsWith("Optional<")) {
            return "Optional.empty()";
        }
        if ("String".equals(type)) {
            return "\"\"";
        }
        return "null";
    }

    static boolean isTypeCompatible(String targetType, String candidateType) {
        if ("Object".equals(targetType)) {
            return true;
        }
        return boxedType(targetType).equals(boxedType(candidateType));
    }

    /** Java generics cannot contain primitives; scalar record APIs remain unchanged. */
    static String boxedType(String type) {
        return switch (type) {
            case "int" -> "Integer";
            case "long" -> "Long";
            default -> type;
        };
    }
}
