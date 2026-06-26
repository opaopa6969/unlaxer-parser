package org.unlaxer.dsl.codegen;

import org.unlaxer.dsl.bootstrap.UBNFAST;
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
 *
 * <p><b>Dotted mapping (Issue #8):</b> {@code @mapping(Outer.Inner)} を許容する。
 * 同じ {@code Outer} を持つ複数のルールが宣言されると、{@code Outer} を sealed
 * interface として暗黙宣言し、各 {@code Inner} を {@code Outer} の inner record
 * として生成する。これにより、ユーザーは flat record スタイル (現状) と
 * sealed inner record スタイル (Hejlsberg 流) のどちらも自由に書ける。</p>
 *
 * <p>例:
 * <pre>{@code
 * @mapping(TokenDecl.Simple, params=[name, parserClass])
 * SimpleToken ::= ... ;
 *
 * @mapping(TokenDecl.Until, params=[name, terminator])
 * UntilToken ::= ... ;
 * }</pre>
 * ↓ 生成される AST:
 * <pre>{@code
 * sealed interface XxxAST permits XxxAST.TokenDecl {
 *   sealed interface TokenDecl extends XxxAST permits TokenDecl.Simple, TokenDecl.Until {
 *     record Simple(String name, String parserClass) implements TokenDecl {}
 *     record Until(String name, String terminator) implements TokenDecl {}
 *   }
 * }
 * }</pre>
 */
public class ASTGenerator implements CodeGenerator {

    @Override
    public GeneratedSource generate(GrammarDecl grammar) {
        String packageName = getPackageName(grammar);
        String grammarName = grammar.name();
        String className = grammarName + "AST";

        // ----- Pass 1: ルールを 3 系統に分類 -----
        // - midSealedRules: @mapping(SealedName) + body が「複数 alt の Choice、各 alt が単一 RuleRef」
        //                   → SealedName を中間 sealed interface として生成
        // - nestedRules:    @mapping(Outer.Inner) → Outer を sealed として inner record を入れる
        // - flatRules:      @mapping(Foo) で params あり → record Foo を直下に出力
        // @skip が付いているルールは AST 生成対象外。
        Map<String, RuleDecl> flatRules = new LinkedHashMap<>();
        Map<String, Map<String, RuleDecl>> nestedRules = new LinkedHashMap<>();
        Map<String, RuleDecl> midSealedRules = new LinkedHashMap<>();
        List<RuleDecl> enumRules = new ArrayList<>();
        for (RuleDecl rule : grammar.rules()) {
            boolean isSkip = rule.annotations().stream().anyMatch(a -> a instanceof SkipAnnotation);
            if (isSkip) {
                continue;
            }
            boolean isEnum = rule.annotations().stream().anyMatch(a -> a instanceof UBNFAST.EnumAnnotation);
            if (isEnum) {
                enumRules.add(rule);
                continue;
            }
            getMappingAnnotation(rule).ifPresent(m -> {
                String name = m.className();
                if (isMidSealedCandidate(rule, m)) {
                    midSealedRules.putIfAbsent(name, rule);
                } else if (name.contains(".")) {
                    int dot = name.indexOf('.');
                    String outer = name.substring(0, dot);
                    String inner = name.substring(dot + 1);
                    nestedRules
                        .computeIfAbsent(outer, k -> new LinkedHashMap<>())
                        .putIfAbsent(inner, rule);
                } else {
                    flatRules.putIfAbsent(name, rule);
                }
            });
        }

        // ----- Pass 2: 中間 sealed interface ごとの permits リストを構築 -----
        // 各 record が「どの中間 sealed の permits に含まれるか」も逆引きで保持する。
        // nested と同名 (= ユーザーが @mapping(Foo) Choice と @mapping(Foo.X) を併記)
        // した場合は nested を優先する (より具体的な inner record 構造を持つため、
        // 中間 sealed の宣言は nested 側に統合される)。
        Map<String, List<String>> midSealedPermits = new LinkedHashMap<>();
        Map<String, String> recordToMidSealed = new LinkedHashMap<>();
        for (Map.Entry<String, RuleDecl> entry : midSealedRules.entrySet()) {
            String midSealedName = entry.getKey();
            if (nestedRules.containsKey(midSealedName)) {
                continue;
            }
            List<String> permits = computeMidSealedPermits(entry.getValue(), grammar);
            midSealedPermits.put(midSealedName, permits);
            for (String permittedRecord : permits) {
                recordToMidSealed.putIfAbsent(permittedRecord, midSealedName);
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(packageName).append(";\n\n");
        sb.append("import java.util.List;\n");
        sb.append("import java.util.Optional;\n");
        sb.append("\n");
        sb.append(CodeGenerator.generatedAnnotation("org.unlaxer.dsl.codegen.ASTGenerator"));

        if (flatRules.isEmpty() && nestedRules.isEmpty() && midSealedRules.isEmpty()) {
            sb.append("public interface ").append(className).append(" {}\n");
            return new GeneratedSource(packageName, className, sb.toString());
        }

        // ----- Pass 3: ルート AST sealed interface の permits 句を構築 -----
        // 中間 sealed 配下の record は AST permit から外し、中間 sealed 名を代わりに入れる。
        java.util.LinkedHashSet<String> astPermits = new java.util.LinkedHashSet<>();
        for (String name : flatRules.keySet()) {
            if (!recordToMidSealed.containsKey(name)) {
                astPermits.add(name);
            }
        }
        for (String outer : nestedRules.keySet()) {
            astPermits.add(outer);
        }
        astPermits.addAll(midSealedRules.keySet());

        String permitsClause = astPermits.stream()
            .map(name -> className + "." + name)
            .collect(Collectors.joining(",\n    "));

        sb.append("public sealed interface ").append(className).append(" permits\n")
          .append("    ").append(permitsClause).append(" {\n\n");

        // ----- Pass 4: 出力 -----
        // 中間 sealed interface 宣言を先に出す (permit する record 群より前である必要はないが
        // 読みやすさのため)。
        for (Map.Entry<String, List<String>> entry : midSealedPermits.entrySet()) {
            emitMidSealed(sb, entry.getKey(), entry.getValue(), className, grammar);
        }

        // flat record (中間 sealed 配下でないもののみ)
        for (Map.Entry<String, RuleDecl> entry : flatRules.entrySet()) {
            String recordName = entry.getKey();
            if (nestedRules.containsKey(recordName)) {
                continue; // nested 優先
            }
            RuleDecl rule = entry.getValue();
            MappingAnnotation mapping = getMappingAnnotation(rule).get();
            String parentType = recordToMidSealed.getOrDefault(recordName, className);
            emitRecord(sb, "    ", recordName, rule, mapping, grammar, parentType);
        }

        // nested sealed (Outer.Inner)
        for (Map.Entry<String, Map<String, RuleDecl>> entry : nestedRules.entrySet()) {
            String outerName = entry.getKey();
            Map<String, RuleDecl> inners = entry.getValue();
            emitNestedSealed(sb, outerName, inners, grammar, className);
        }

        // enum ルール
        for (RuleDecl enumRule : enumRules) {
            sb.append(generateEnumClass(enumRule, "    "));
        }

        sb.append("}\n");

        return new GeneratedSource(packageName, className, sb.toString());
    }

    /**
     * 中間 sealed interface の候補かを判定する。
     * 条件: @mapping(SimpleName) で params 無し、body が Choice、
     *       各 alt が単一 RuleRef のみ (= sum-type の典型形)。
     *
     * <p>1 alternative でも候補とする — その場合は permits 1 つの sealed
     * interface (type-alias 的な使い方) になる。手書き UBNFAST の
     * {@code RuleBody ::= ChoiceBody} のような中間 wrapper 型を表現する
     * ために必要。</p>
     */
    private boolean isMidSealedCandidate(RuleDecl rule, MappingAnnotation mapping) {
        if (!mapping.paramNames().isEmpty()) {
            return false;
        }
        if (mapping.className().contains(".")) {
            return false;
        }
        if (!(rule.body() instanceof ChoiceBody choice)) {
            return false;
        }
        if (choice.alternatives().isEmpty()) {
            return false;
        }
        for (SequenceBody seq : choice.alternatives()) {
            if (seq.elements().size() != 1) {
                return false;
            }
            if (!(seq.elements().get(0).element() instanceof RuleRefElement)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Choice の各 alternative の RuleRef が指すルールの @mapping クラス名を集めて
     * 中間 sealed の permits リストとする。
     *
     * <ul>
     *   <li>flat record (@mapping(Foo))           → permits に "Foo"</li>
     *   <li>nested inner (@mapping(Sealed.Inner)) → outer が当該 sealed と一致するときに
     *                                               permits に "Inner" (= 自分の inner)</li>
     * </ul>
     *
     * @mapping のないルールや別の sealed 配下の record はスキップ。
     */
    private List<String> computeMidSealedPermits(RuleDecl midSealedRule, GrammarDecl grammar) {
        if (!(midSealedRule.body() instanceof ChoiceBody choice)) {
            return List.of();
        }
        String midSealedName = getMappingAnnotation(midSealedRule).map(MappingAnnotation::className).orElse("");
        List<String> permits = new ArrayList<>();
        for (SequenceBody seq : choice.alternatives()) {
            AtomicElement element = seq.elements().get(0).element();
            if (element instanceof RuleRefElement ref) {
                Optional<MappingAnnotation> m = findMappingForRule(grammar, ref.name());
                m.ifPresent(mapping -> {
                    String name = mapping.className();
                    int dot = name.indexOf('.');
                    if (dot < 0) {
                        // flat record → そのまま permit に入れる
                        permits.add(name);
                    } else {
                        String outer = name.substring(0, dot);
                        String inner = name.substring(dot + 1);
                        // 自分の inner のみ permit に入れる (nested sealed の構造内)
                        if (outer.equals(midSealedName)) {
                            permits.add(inner);
                        }
                    }
                });
            }
        }
        return permits;
    }

    private void emitRecord(StringBuilder sb, String indent, String recordName, RuleDecl rule,
            MappingAnnotation mapping, GrammarDecl grammar, String parentTypeName) {
        sb.append(indent).append("record ").append(recordName).append("(\n");
        List<String> fields = new ArrayList<>();
        for (String param : mapping.paramNames()) {
            String type = inferType(grammar, rule, param);
            fields.add(indent + "    " + type + " " + param);
        }
        sb.append(String.join(",\n", fields)).append("\n");
        sb.append(indent).append(") implements ").append(parentTypeName).append(" {}\n\n");
    }

    private void emitNestedSealed(StringBuilder sb, String outerName,
            Map<String, RuleDecl> inners, GrammarDecl grammar, String astClassName) {
        String innerPermits = inners.keySet().stream()
            .map(name -> outerName + "." + name)
            .collect(Collectors.joining(",\n        "));
        sb.append("    sealed interface ").append(outerName)
          .append(" extends ").append(astClassName).append(" permits\n")
          .append("        ").append(innerPermits).append(" {\n\n");
        for (Map.Entry<String, RuleDecl> entry : inners.entrySet()) {
            String recordName = entry.getKey();
            RuleDecl rule = entry.getValue();
            MappingAnnotation mapping = getMappingAnnotation(rule).get();
            emitRecord(sb, "        ", recordName, rule, mapping, grammar, outerName);
        }
        sb.append("    }\n\n");
    }

    private void emitMidSealed(StringBuilder sb, String midSealedName, List<String> permits,
            String astClassName, GrammarDecl grammar) {
        if (permits.isEmpty()) {
            sb.append("    interface ").append(midSealedName)
              .append(" extends ").append(astClassName).append(" {}\n\n");
            return;
        }
        String permitsList = permits.stream().collect(Collectors.joining(",\n        "));
        // @commonField アノテーションで abstract method を生成
        List<String> commonFields = grammar.rules().stream()
            .filter(r -> {
                MappingAnnotation m = getMappingAnnotation(r).orElse(null);
                return m != null && m.className().equals(midSealedName);
            })
            .flatMap(r -> r.annotations().stream())
            .filter(a -> a instanceof UBNFAST.CommonFieldAnnotation)
            .flatMap(a -> ((UBNFAST.CommonFieldAnnotation) a).fieldNames().stream())
            .distinct()
            .toList();

        sb.append("    sealed interface ").append(midSealedName)
          .append(" extends ").append(astClassName).append(" permits\n")
          .append("        ").append(permitsList).append(" {");
        if (commonFields.isEmpty()) {
            sb.append("}\n\n");
        } else {
            sb.append("\n");
            for (String field : commonFields) {
                String type = inferCommonFieldType(grammar, midSealedName, field, astClassName);
                sb.append("        ").append(type).append(" ").append(field).append("();\n");
            }
            sb.append("    }\n\n");
        }
    }

    /**
     * @enum アノテーション付きルールから Java enum クラスを生成する。
     * 'sync' → SYNC, 'camelCase' → CAMEL_CASE, 'kebab-case' → KEBAB_CASE
     */
    private String generateEnumClass(RuleDecl rule, String indent) {
        String enumName = rule.name();
        List<String> literals = collectTerminalLiterals(rule.body());
        if (literals.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(indent).append("public enum ").append(enumName).append(" {\n");
        List<String> constNames = literals.stream()
            .map(ASTGenerator::toEnumConstantName)
            .toList();
        sb.append(indent).append("    ")
          .append(String.join(", ", constNames)).append(";\n\n");
        // fromText(String text) factory
        sb.append(indent).append("    public static ").append(enumName)
          .append(" fromText(String text) {\n");
        sb.append(indent).append("        return switch (text) {\n");
        for (int i = 0; i < literals.size(); i++) {
            sb.append(indent).append("            case \"").append(literals.get(i))
              .append("\" -> ").append(constNames.get(i)).append(";\n");
        }
        sb.append(indent).append("            default -> throw new IllegalArgumentException(")
          .append("\"Unknown ").append(enumName).append(": \" + text);\n");
        sb.append(indent).append("        };\n");
        sb.append(indent).append("    }\n");
        sb.append(indent).append("}\n\n");
        return sb.toString();
    }

    private static String toEnumConstantName(String literal) {
        // camelCase → CAMEL_CASE, kebab-case → KEBAB_CASE, simple → SIMPLE
        StringBuilder sb = new StringBuilder();
        boolean prevLower = false;
        for (char c : literal.toCharArray()) {
            if (c == '-' || c == '_' || c == ' ') {
                sb.append('_');
                prevLower = false;
            } else if (Character.isUpperCase(c) && prevLower) {
                sb.append('_').append(c);
                prevLower = false;
            } else {
                sb.append(Character.toUpperCase(c));
                prevLower = Character.isLowerCase(c);
            }
        }
        return sb.toString();
    }

    private List<String> collectTerminalLiterals(UBNFAST.RuleBody body) {
        return switch (body) {
            case UBNFAST.ChoiceBody choice -> choice.alternatives().stream()
                .flatMap(seq -> seq.elements().stream())
                .map(ae -> ae.element())
                .filter(e -> e instanceof UBNFAST.TerminalElement)
                .map(e -> ((UBNFAST.TerminalElement) e).value())
                .toList();
            case UBNFAST.SequenceBody seq -> seq.elements().stream()
                .map(ae -> ae.element())
                .filter(e -> e instanceof UBNFAST.TerminalElement)
                .map(e -> ((UBNFAST.TerminalElement) e).value())
                .toList();
        };
    }

    private String inferCommonFieldType(GrammarDecl grammar, String sealedName,
            String fieldName, String astClassName) {
        // 最初の permit ルールで型を推論
        return grammar.rules().stream()
            .filter(r -> {
                MappingAnnotation m = getMappingAnnotation(r).orElse(null);
                return m != null && m.className().startsWith(sealedName + ".");
            })
            .map(r -> inferType(grammar, r, fieldName))
            .filter(t -> !"Object".equals(t))
            .findFirst()
            .orElse("String");
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
        // unlaxer-parser #43: widen heterogeneous left/right-assoc operands to the base
        // AST interface so the fold can hold any factor's mapped node (e.g. AbsExpr),
        // not just the assoc's own class. Only number-style folds (operand nominally the
        // assoc class itself) are widened — mirrors MapperRuleEmitter#shouldWidenAssocOperands,
        // so source-string-leaf folds keep their String operands.
        if (("left".equals(fieldName) || "right".equals(fieldName))
            && isWidenedAssocOperand(grammar, rule, innerType)) {
            innerType = grammar.name() + "AST";
        }
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

    /**
     * True if {@code rule} is a left/right-assoc rule whose @mapping class has a
     * heterogeneous operand (see MapperElementUtil#assocClassHasHeterogeneousOperand).
     */
    private boolean isWidenedAssocOperand(GrammarDecl grammar, RuleDecl rule, String narrowInnerType) {
        MappingAnnotation mapping = MapperElementUtil.getMappingAnnotation(rule).orElse(null);
        if (mapping == null) {
            return false;
        }
        if (!MapperElementUtil.isLeftAssocRule(rule, mapping)
            && !MapperElementUtil.isRightAssocRule(rule, mapping)) {
            return false;
        }
        String className = mapping.className();
        // Only number-style folds: the operand nominally resolves to the assoc class
        // itself. Excludes source-string-leaf folds whose operands resolve to String.
        if (!(grammar.name() + "AST." + className).equals(narrowInnerType)) {
            return false;
        }
        Map<String, RuleDecl> ruleByName = new LinkedHashMap<>();
        List<RuleDecl> rulesForClass = new ArrayList<>();
        for (RuleDecl r : grammar.rules()) {
            ruleByName.put(r.name(), r);
            MappingAnnotation m = MapperElementUtil.getMappingAnnotation(r).orElse(null);
            if (m != null && m.className().equals(className)) {
                rulesForClass.add(r);
            }
        }
        Map<String, UBNFAST.TokenDecl> tokenDeclByName = new LinkedHashMap<>();
        for (UBNFAST.TokenDecl t : grammar.tokens()) {
            tokenDeclByName.put(t.name(), t);
        }
        return MapperElementUtil.assocClassHasHeterogeneousOperand(
            className, rulesForClass, ruleByName, tokenDeclByName);
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
                if (mapping.isPresent()) {
                    yield astClassName + "." + mapping.get().className();
                }
                String tokenType = MapperTypeResolver.inferTypeFromTokenName(grammar, r.name());
                if (tokenType != null) {
                    yield tokenType;
                }
                // 透過 mapped choice（異種選択肢）は単一スカラーに収束しない → Object
                if (MapperTypeResolver.isTransparentMappedChoice(grammar, r.name())) {
                    yield "Object";
                }
                yield "String";
            }
            case RepeatElement rep -> {
                String inner = inferTypeFromBody(grammar, rep.body());
                yield "List<" + inner + ">";
            }
            case OneOrMoreElement one -> {
                String inner = inferTypeFromElement(grammar, one.body());
                yield "List<" + inner + ">";
            }
            case BoundedRepeatElement bounded -> {
                String inner = inferTypeFromElement(grammar, bounded.body());
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
