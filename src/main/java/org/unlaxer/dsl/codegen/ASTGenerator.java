package org.unlaxer.dsl.codegen;

import org.unlaxer.dsl.bootstrap.UBNFAST.AnnotatedElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.AtomicElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.ChoiceBody;
import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.bootstrap.UBNFAST.GroupElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.MappingAnnotation;
import org.unlaxer.dsl.bootstrap.UBNFAST.BoundedRepeatElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.ErrorElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.SeparatedElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.SkipAnnotation;
import org.unlaxer.dsl.bootstrap.UBNFAST.OneOrMoreElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.OptionalElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.RepeatElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.RuleBody;
import org.unlaxer.dsl.bootstrap.UBNFAST.RuleDecl;
import org.unlaxer.dsl.bootstrap.UBNFAST.RuleRefElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.SequenceBody;
import org.unlaxer.dsl.bootstrap.UBNFAST.StringSettingValue;
import org.unlaxer.dsl.bootstrap.UBNFAST.TerminalElement;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * GrammarDecl から XxxAST.java を生成する。
 *
 * <p>@mapping アノテーション付きのルールを sealed interface の permits として生成し、
 * 各ルールに対応する record を内部型として生成する。</p>
 */
public class ASTGenerator implements CodeGenerator {

    @Override
    public GeneratedSource generate(GrammarDecl grammar) {
        String packageName = getPackageName(grammar);
        String grammarName = grammar.name();
        String className = grammarName + "AST";

        // @mapping アノテーション付きルールを収集（クラス名で重複排除、順序保持）
        // @skip が付いているルールは AST 生成対象外
        Map<String, RuleDecl> mappingRules = new LinkedHashMap<>();
        for (RuleDecl rule : grammar.rules()) {
            boolean isSkip = rule.annotations().stream().anyMatch(a -> a instanceof SkipAnnotation);
            if (!isSkip) {
                getMappingAnnotation(rule).ifPresent(m -> {
                    mappingRules.putIfAbsent(m.className(), rule);
                });
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(packageName).append(";\n\n");
        sb.append("import java.util.List;\n");
        sb.append("import java.util.Optional;\n");
        sb.append("\n");

        if (mappingRules.isEmpty()) {
            sb.append("public interface ").append(className).append(" {}\n");
            return new GeneratedSource(packageName, className, sb.toString());
        }

        String permitsClause = mappingRules.keySet().stream()
            .map(name -> className + "." + name)
            .collect(Collectors.joining(",\n    "));

        sb.append("public sealed interface ").append(className).append(" permits\n")
          .append("    ").append(permitsClause).append(" {\n\n");

        for (Map.Entry<String, RuleDecl> entry : mappingRules.entrySet()) {
            String recordName = entry.getKey();
            RuleDecl rule = entry.getValue();
            MappingAnnotation mapping = getMappingAnnotation(rule).get();

            sb.append("    record ").append(recordName).append("(\n");

            List<String> fields = new ArrayList<>();
            for (String param : mapping.paramNames()) {
                String type = inferType(grammar, rule, param);
                fields.add("        " + type + " " + param);
            }

            sb.append(String.join(",\n", fields)).append("\n");
            sb.append("    ) implements ").append(className).append(" {}\n\n");
        }

        sb.append("}\n");

        return new GeneratedSource(packageName, className, sb.toString());
    }

    // =========================================================================
    // 型推論
    // =========================================================================

    /**
     * ルール内の指定フィールド名に対応する Java 型を推論する。
     */
    String inferType(GrammarDecl grammar, RuleDecl rule, String fieldName) {
        List<CaptureResult> captures = findCapturedElements(rule.body(), fieldName);
        if (captures.isEmpty()) {
            return "Object";
        }
        String innerType = mergeCapturedTypes(grammar, captures);
        boolean inOptional = captures.stream().anyMatch(CaptureResult::inOptional);
        boolean inRepeat = captures.stream().anyMatch(CaptureResult::inRepeat);
        if (inRepeat) {
            return "List<" + innerType + ">";
        }
        if (inOptional) {
            return "Optional<" + innerType + ">";
        }
        return innerType;
    }

    private String mergeCapturedTypes(GrammarDecl grammar, List<CaptureResult> captures) {
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

    private String inferTypeFromElement(GrammarDecl grammar, AtomicElement element) {
        String astClassName = grammar.name() + "AST";
        return switch (element) {
            case TerminalElement t -> "String";
            case RuleRefElement r -> {
                Optional<MappingAnnotation> mapping = findMappingForRule(grammar, r.name());
                yield mapping.map(m -> astClassName + "." + m.className()).orElse("String");
            }
            case RepeatElement rep -> {
                String inner = inferTypeFromBody(grammar, rep.body());
                yield "List<" + inner + ">";
            }
            case OneOrMoreElement one -> {
                String inner = inferTypeFromBody(grammar, one.body());
                yield "List<" + inner + ">";
            }
            case BoundedRepeatElement bounded -> {
                String inner = inferTypeFromBody(grammar, bounded.body());
                yield "List<" + inner + ">";
            }
            case OptionalElement opt -> {
                String inner = inferTypeFromBody(grammar, opt.body());
                yield "Optional<" + inner + ">";
            }
            case SeparatedElement sep -> {
                String inner = inferTypeFromElement(grammar, sep.element());
                yield "List<" + inner + ">";
            }
            case GroupElement g -> "Object";
            case ErrorElement e -> "Object";
        };
    }

    private String inferTypeFromBody(GrammarDecl grammar, RuleBody body) {
        // 単一要素のシーケンスであれば、その要素の型を推論
        AnnotatedElement single = getSingleElement(body);
        if (single != null) {
            return inferTypeFromElement(grammar, single.element());
        }
        return "Object";
    }

    private AnnotatedElement getSingleElement(RuleBody body) {
        return switch (body) {
            case SequenceBody seq when seq.elements().size() == 1 -> seq.elements().get(0);
            case ChoiceBody choice when choice.alternatives().size() == 1 -> {
                SequenceBody seq = choice.alternatives().get(0);
                yield seq.elements().size() == 1 ? seq.elements().get(0) : null;
            }
            default -> null;
        };
    }

    private Optional<MappingAnnotation> findMappingForRule(GrammarDecl grammar, String ruleName) {
        return grammar.rules().stream()
            .filter(r -> r.name().equals(ruleName))
            .flatMap(r -> r.annotations().stream())
            .filter(a -> a instanceof MappingAnnotation)
            .map(a -> (MappingAnnotation) a)
            .findFirst();
    }

    // =========================================================================
    // キャプチャ名の検索
    // =========================================================================

    /**
     * ルール本体からキャプチャ名を持つ要素を再帰的に探す。
     * Optional / Repeat の中に入る場合は inOptional / inRepeat フラグを立てる。
     */
    private List<CaptureResult> findCapturedElements(RuleBody body, String captureName) {
        return findCapturedElementsInBody(body, captureName, false, false);
    }

    private List<CaptureResult> findCapturedElementsInBody(
            RuleBody body, String captureName, boolean inOptional, boolean inRepeat) {
        return switch (body) {
            case ChoiceBody choice -> choice.alternatives().stream()
                .flatMap(seq -> findCapturedElementsInSequence(seq, captureName, inOptional, inRepeat).stream())
                .toList();
            case SequenceBody seq -> findCapturedElementsInSequence(seq, captureName, inOptional, inRepeat);
        };
    }

    private List<CaptureResult> findCapturedElementsInSequence(
            SequenceBody seq, String captureName, boolean inOptional, boolean inRepeat) {
        List<CaptureResult> captures = new ArrayList<>();
        for (AnnotatedElement ae : seq.elements()) {
            if (ae.captureName().isPresent() && ae.captureName().get().equals(captureName)) {
                captures.add(new CaptureResult(ae.element(), inOptional, inRepeat));
            }
            // 入れ子要素（Optional / Repeat / Group）の中も探す
            captures.addAll(findCapturedElementsInAtomic(ae.element(), captureName, inOptional, inRepeat));
        }
        return captures;
    }

    private List<CaptureResult> findCapturedElementsInAtomic(
            AtomicElement element, String captureName, boolean inOptional, boolean inRepeat) {
        return switch (element) {
            case OptionalElement opt ->
                findCapturedElementsInBody(opt.body(), captureName, true, inRepeat);
            case RepeatElement rep ->
                findCapturedElementsInBody(rep.body(), captureName, inOptional, true);
            case GroupElement g ->
                findCapturedElementsInBody(g.body(), captureName, inOptional, inRepeat);
            default -> List.of();
        };
    }

    // =========================================================================
    // ユーティリティ
    // =========================================================================

    Optional<MappingAnnotation> getMappingAnnotation(RuleDecl rule) {
        return rule.annotations().stream()
            .filter(a -> a instanceof MappingAnnotation)
            .map(a -> (MappingAnnotation) a)
            .findFirst();
    }

    String getPackageName(GrammarDecl grammar) {
        return grammar.settings().stream()
            .filter(s -> "package".equals(s.key()))
            .map(s -> s.value() instanceof StringSettingValue sv ? sv.value() : "")
            .findFirst()
            .orElse("generated");
    }

    // キャプチャ検索結果。ネストされた文脈情報を保持する。
    private record CaptureResult(AtomicElement element, boolean inOptional, boolean inRepeat) {}
}
