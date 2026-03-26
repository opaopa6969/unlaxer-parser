package org.unlaxer.dsl.codegen;

import org.unlaxer.dsl.bootstrap.UBNFAST.AnnotatedElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.Annotation;
import org.unlaxer.dsl.bootstrap.UBNFAST.AtomicElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.BackrefAnnotation;
import org.unlaxer.dsl.bootstrap.UBNFAST.CatalogAnnotation;
import org.unlaxer.dsl.bootstrap.UBNFAST.ChoiceBody;
import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.bootstrap.UBNFAST.GroupElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.InterleaveAnnotation;
import org.unlaxer.dsl.bootstrap.UBNFAST.LeftAssocAnnotation;
import org.unlaxer.dsl.bootstrap.UBNFAST.MappingAnnotation;
import org.unlaxer.dsl.bootstrap.UBNFAST.OptionalElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.PrecedenceAnnotation;
import org.unlaxer.dsl.bootstrap.UBNFAST.RepeatElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.RightAssocAnnotation;
import org.unlaxer.dsl.bootstrap.UBNFAST.RootAnnotation;
import org.unlaxer.dsl.bootstrap.UBNFAST.RuleBody;
import org.unlaxer.dsl.bootstrap.UBNFAST.RuleDecl;
import org.unlaxer.dsl.bootstrap.UBNFAST.RuleRefElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.ScopeTreeAnnotation;
import org.unlaxer.dsl.bootstrap.UBNFAST.SequenceBody;
import org.unlaxer.dsl.bootstrap.UBNFAST.StringSettingValue;
import org.unlaxer.dsl.bootstrap.UBNFAST.TokenDecl;
import org.unlaxer.dsl.bootstrap.UBNFAST.TypeofElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.WhitespaceAnnotation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates grammar-level semantic constraints that generators rely on.
 */
public final class GrammarValidator {

    private GrammarValidator() {}

    public record ValidationIssue(String code, String message, String hint, String rule) {
        public ValidationIssue(String code, String message, String hint) {
            this(code, message, hint, null);
        }

        public String severity() {
            if (code != null && code.startsWith("W-")) {
                return "WARNING";
            }
            return "ERROR";
        }

        public String category() {
            if (code == null) {
                return "GENERAL";
            }
            if (code.startsWith("E-MAPPING-")) {
                return "MAPPING";
            }
            if (code.startsWith("E-ASSOC-") || code.startsWith("E-RIGHTASSOC-")) {
                return "ASSOCIATIVITY";
            }
            if (code.startsWith("E-WHITESPACE-")) {
                return "WHITESPACE";
            }
            if (code.startsWith("E-PRECEDENCE-")) {
                return "PRECEDENCE";
            }
            if (code.startsWith("E-ANNOTATION-")) {
                return "ANNOTATION";
            }
            return "GENERAL";
        }

        public String format() {
            return message + " [code: " + code + "] [hint: " + hint + "]";
        }
    }

    public static List<ValidationIssue> validate(GrammarDecl grammar) {
        List<ValidationIssue> errors = new ArrayList<>();

        validateGlobalWhitespace(grammar, errors);
        validateRootPresence(grammar, errors);
        validateTokens(grammar, errors);

        for (RuleDecl rule : grammar.rules()) {
            MappingAnnotation mapping = null;
            boolean hasLeftAssoc = false;
            boolean hasRightAssoc = false;
            List<PrecedenceAnnotation> precedenceAnnotations = new ArrayList<>();
            List<InterleaveAnnotation> interleaveAnnotations = new ArrayList<>();
            List<BackrefAnnotation> backrefAnnotations = new ArrayList<>();
            List<ScopeTreeAnnotation> scopeTreeAnnotations = new ArrayList<>();

            for (Annotation annotation : rule.annotations()) {
                if (annotation instanceof MappingAnnotation m) {
                    mapping = m;
                } else if (annotation instanceof LeftAssocAnnotation) {
                    hasLeftAssoc = true;
                } else if (annotation instanceof RightAssocAnnotation) {
                    hasRightAssoc = true;
                } else if (annotation instanceof PrecedenceAnnotation p) {
                    precedenceAnnotations.add(p);
                } else if (annotation instanceof InterleaveAnnotation i) {
                    interleaveAnnotations.add(i);
                } else if (annotation instanceof BackrefAnnotation b) {
                    backrefAnnotations.add(b);
                } else if (annotation instanceof ScopeTreeAnnotation s) {
                    scopeTreeAnnotations.add(s);
                } else if (annotation instanceof WhitespaceAnnotation w) {
                    validateRuleWhitespace(rule, w, errors);
                }
            }

            if (mapping != null) {
                validateMapping(rule, mapping, errors);
            }
            if (hasLeftAssoc || hasRightAssoc) {
                validateAssoc(rule, mapping, hasLeftAssoc, hasRightAssoc, errors);
            }
            validatePrecedence(rule, hasLeftAssoc, hasRightAssoc, precedenceAnnotations, errors);
            validateAdvancedAnnotations(rule, interleaveAnnotations, backrefAnnotations, scopeTreeAnnotations, errors);
            validateTypeofElements(rule, errors);
            validateCatalogAnnotations(rule, errors);
        }
        validatePrecedenceTopology(grammar, errors);
        validateAssociativityConsistency(grammar, errors);

        return List.copyOf(errors);
    }

    public static void validateOrThrow(GrammarDecl grammar) {
        List<ValidationIssue> issues = validate(grammar);
        if (!issues.isEmpty()) {
            throw new IllegalArgumentException(
                "Grammar validation failed for " + grammar.name() + ":\n - "
                    + String.join("\n - ", issues.stream().map(ValidationIssue::format).toList())
            );
        }
    }

    private static void validateMapping(RuleDecl rule, MappingAnnotation mapping, List<ValidationIssue> errors) {
        List<String> params = mapping.paramNames();
        Set<String> paramSet = new LinkedHashSet<>();
        Set<String> duplicateParams = new LinkedHashSet<>();
        for (String param : params) {
            if (!paramSet.add(param)) {
                duplicateParams.add(param);
            }
        }

        if (!duplicateParams.isEmpty()) {
            addRuleError(errors, rule.name(),
                "rule " + rule.name() + " @mapping(" + mapping.className()
                    + ") has duplicate params: " + duplicateParams,
                "Remove duplicate parameter names in @mapping params.",
                "E-MAPPING-DUPLICATE-PARAM");
        }

        Set<String> captures = collectCaptureNames(rule.body());

        for (String param : paramSet) {
            if (!captures.contains(param)) {
                addRuleError(errors, rule.name(),
                    "rule " + rule.name() + " @mapping(" + mapping.className()
                        + ") param '" + param + "' has no matching capture",
                    "Add @" + param + " capture in the rule body or remove it from params.",
                    "E-MAPPING-MISSING-CAPTURE");
            }
        }

        for (String capture : captures) {
            if (!paramSet.contains(capture)) {
                addRuleError(errors, rule.name(),
                    "rule " + rule.name() + " has capture @" + capture
                        + " not listed in @mapping(" + mapping.className() + ") params",
                    "Add '" + capture + "' to @mapping params.",
                    "E-MAPPING-UNLISTED-CAPTURE");
            }
        }
    }

    private static void validateAssoc(
        RuleDecl rule,
        MappingAnnotation mapping,
        boolean hasLeftAssoc,
        boolean hasRightAssoc,
        List<ValidationIssue> errors
    ) {
        String assocName = hasRightAssoc ? "@rightAssoc" : "@leftAssoc";
        if (hasLeftAssoc && hasRightAssoc) {
            addRuleError(errors, rule.name(),
                "rule " + rule.name() + " cannot use both @leftAssoc and @rightAssoc",
                "Keep exactly one associativity annotation per rule.",
                "E-ASSOC-BOTH");
            return;
        }

        Set<String> captures = collectCaptureNames(rule.body());

        if (mapping == null) {
            addRuleError(errors, rule.name(),
                "rule " + rule.name() + " uses " + assocName + " but has no @mapping",
                "Add @mapping(ClassName, params=[left, op, right]) to this rule.",
                "E-ASSOC-NO-MAPPING");
        } else {
            Set<String> params = new LinkedHashSet<>(mapping.paramNames());
            for (String required : List.of("left", "op", "right")) {
                if (!params.contains(required)) {
                    addRuleError(errors, rule.name(),
                        "rule " + rule.name() + " uses " + assocName + " but @mapping("
                            + mapping.className() + ") params does not contain '" + required + "'",
                        "Include left/op/right in @mapping params.",
                        "E-ASSOC-MAPPING-PARAM");
                }
            }
        }

        for (String required : List.of("left", "op", "right")) {
            if (!captures.contains(required)) {
                addRuleError(errors, rule.name(),
                    "rule " + rule.name() + " uses " + assocName + " but capture @"
                        + required + " is missing",
                    "Add @" + required + " capture in the rule body.",
                    "E-ASSOC-MISSING-CAPTURE");
            }
        }

        if (!containsRepeat(rule.body())) {
            addRuleError(errors, rule.name(),
                "rule " + rule.name() + " uses " + assocName + " but has no repeat segment",
                "Use canonical operator pattern: Base { Op Right }.",
                "E-ASSOC-NO-REPEAT");
        }

        if (hasRightAssoc && !isCanonicalRightAssocShape(rule)) {
            addRuleError(errors, rule.name(),
                "rule " + rule.name()
                    + " uses @rightAssoc but body is not canonical: expected Base { Op "
                    + rule.name() + " }",
                "Rewrite right-assoc rule as Base { op " + rule.name() + " }.",
                "E-RIGHTASSOC-NONCANONICAL");
        }
    }

    private static void validateGlobalWhitespace(GrammarDecl grammar, List<ValidationIssue> errors) {
        grammar.settings().stream()
            .filter(s -> "whitespace".equals(s.key()))
            .forEach(s -> {
                if (s.value() instanceof StringSettingValue sv) {
                    String style = sv.value().trim();
                    if (!style.equalsIgnoreCase("javaStyle")) {
                        addError(errors,
                            "global @whitespace style must be javaStyle: " + style,
                            "Use '@whitespace: javaStyle'.",
                            "E-WHITESPACE-GLOBAL-STYLE");
                    }
                }
            });
    }

    private static void validateRootPresence(GrammarDecl grammar, List<ValidationIssue> errors) {
        boolean hasRootRule = grammar.rules().stream()
            .anyMatch(rule -> rule.annotations().stream().anyMatch(a -> a instanceof RootAnnotation));
        if (!hasRootRule) {
            addError(
                errors,
                "grammar " + grammar.name() + " has no @root rule",
                "Add @root to at least one entry rule.",
                "W-GENERAL-NO-ROOT"
            );
        }
    }

    private static void validateTokens(GrammarDecl grammar, List<ValidationIssue> errors) {
        Set<String> knownParserPackages = Set.of(
            "org.unlaxer.parser.clang",
            "org.unlaxer.parser.elementary",
            "org.unlaxer.parser.posix"
        );

        for (TokenDecl token : grammar.tokens()) {
            if (token instanceof TokenDecl.Simple simple) {
                String parserClass = simple.parserClass();
                if (parserClass == null || parserClass.isEmpty()) {
                    continue;
                }

                // Check if parser class is resolvable
                if (!isResolvableParserClass(parserClass, knownParserPackages)) {
                    addError(
                        errors,
                        "token " + simple.name() + " references unresolved parser class: "
                            + parserClass,
                        "Ensure the parser class is fully qualified or in a known package "
                            + "(org.unlaxer.parser.clang, elementary, posix).",
                        "W-TOKEN-UNRESOLVED"
                    );
                }
            }
        }
    }

    private static boolean isResolvableParserClass(String parserClass, Set<String> knownParserPackages) {
        // Check if it's a fully qualified class name (contains at least one dot)
        if (!parserClass.contains(".")) {
            return false;
        }

        // Check if it's in a known parser package
        for (String knownPackage : knownParserPackages) {
            if (parserClass.startsWith(knownPackage + ".")) {
                return true;
            }
        }

        // Accept fully qualified names from other packages (may be user-defined)
        // A simple heuristic: if it looks like a fully qualified class name, accept it
        // Format: package.path.ClassName (at least 2 parts)
        String[] parts = parserClass.split("\\.");
        if (parts.length >= 2) {
            // Check that all parts except the last are lowercase (package convention)
            // and the last part starts with uppercase (class name convention)
            boolean hasValidPackagePart = true;
            for (int i = 0; i < parts.length - 1; i++) {
                if (parts[i].isEmpty() || !Character.isLowerCase(parts[i].charAt(0))) {
                    hasValidPackagePart = false;
                    break;
                }
            }
            if (hasValidPackagePart && !parts[parts.length - 1].isEmpty()
                && Character.isUpperCase(parts[parts.length - 1].charAt(0))) {
                return true;
            }
        }

        return false;
    }

    private static void validateRuleWhitespace(RuleDecl rule, WhitespaceAnnotation w, List<ValidationIssue> errors) {
        String style = w.style().orElse("javaStyle").trim();
        if (!style.equalsIgnoreCase("javaStyle") && !style.equalsIgnoreCase("none")) {
            addRuleError(errors, rule.name(),
                "rule " + rule.name() + " uses unsupported @whitespace style: " + style
                    + " (allowed: javaStyle, none)",
                "Use @whitespace or @whitespace(none).",
                "E-WHITESPACE-RULE-STYLE");
        }
    }

    private static void validatePrecedence(
        RuleDecl rule,
        boolean hasLeftAssoc,
        boolean hasRightAssoc,
        List<PrecedenceAnnotation> precedenceAnnotations,
        List<ValidationIssue> errors
    ) {
        if (precedenceAnnotations.size() > 1) {
            addRuleError(errors, rule.name(),
                "rule " + rule.name() + " has duplicate @precedence annotations",
                "Keep a single @precedence(level=...) annotation.",
                "E-PRECEDENCE-DUPLICATE");
        }
        for (PrecedenceAnnotation p : precedenceAnnotations) {
            if (p.level() < 0) {
                addRuleError(errors, rule.name(),
                    "rule " + rule.name() + " has invalid @precedence level: " + p.level(),
                    "Use a non-negative integer (e.g. @precedence(level=10)).",
                    "E-PRECEDENCE-NEGATIVE");
            }
        }
        if (hasLeftAssoc && hasRightAssoc) {
            // already reported by validateAssoc, but keep precedence checks deterministic.
            return;
        }
        if (!precedenceAnnotations.isEmpty() && !hasLeftAssoc && !hasRightAssoc) {
            addRuleError(errors, rule.name(),
                "rule " + rule.name() + " uses @precedence but has no @leftAssoc/@rightAssoc",
                "Add one associativity annotation alongside @precedence.",
                "E-PRECEDENCE-NO-ASSOC");
        }
    }

    private static void validateAdvancedAnnotations(
        RuleDecl rule,
        List<InterleaveAnnotation> interleaveAnnotations,
        List<BackrefAnnotation> backrefAnnotations,
        List<ScopeTreeAnnotation> scopeTreeAnnotations,
        List<ValidationIssue> errors
    ) {
        if (interleaveAnnotations.size() > 1) {
            addRuleError(errors, rule.name(),
                "rule " + rule.name() + " has duplicate @interleave annotations",
                "Keep a single @interleave(profile=...) annotation.",
                "E-ANNOTATION-DUPLICATE-INTERLEAVE");
        }
        if (!interleaveAnnotations.isEmpty()) {
            String profile = interleaveAnnotations.get(0).profile().trim();
            if (!"javaStyle".equals(profile) && !"commentsAndSpaces".equals(profile)) {
                addRuleError(errors, rule.name(),
                    "rule " + rule.name() + " uses unsupported @interleave profile: " + profile,
                    "Use @interleave(profile=javaStyle) or @interleave(profile=commentsAndSpaces).",
                    "E-ANNOTATION-INTERLEAVE-PROFILE");
            }
        }

        if (backrefAnnotations.size() > 1) {
            addRuleError(errors, rule.name(),
                "rule " + rule.name() + " has duplicate @backref annotations",
                "Keep a single @backref(name=...) annotation.",
                "E-ANNOTATION-DUPLICATE-BACKREF");
        }

        if (scopeTreeAnnotations.size() > 1) {
            addRuleError(errors, rule.name(),
                "rule " + rule.name() + " has duplicate @scopeTree annotations",
                "Keep a single @scopeTree(mode=...) annotation.",
                "E-ANNOTATION-DUPLICATE-SCOPETREE");
        }
        if (!scopeTreeAnnotations.isEmpty()) {
            String mode = scopeTreeAnnotations.get(0).mode().trim();
            if (!"lexical".equals(mode) && !"dynamic".equals(mode)) {
                addRuleError(errors, rule.name(),
                    "rule " + rule.name() + " uses unsupported @scopeTree mode: " + mode,
                    "Use @scopeTree(mode=lexical) or @scopeTree(mode=dynamic).",
                    "E-ANNOTATION-SCOPETREE-MODE");
            }
        }
    }

    private static void validatePrecedenceTopology(GrammarDecl grammar, List<ValidationIssue> errors) {
        var ruleMap = grammar.rules().stream()
            .collect(java.util.stream.Collectors.toMap(RuleDecl::name, r -> r, (a, b) -> a));

        for (RuleDecl rule : grammar.rules()) {
            Integer precedence = findPrecedenceLevel(rule);
            if (precedence == null || !hasAssoc(rule)) {
                continue;
            }
            Set<String> refs = collectReferencedRuleNames(rule.body());
            for (String refName : refs) {
                if (rule.name().equals(refName)) {
                    continue;
                }
                RuleDecl refRule = ruleMap.get(refName);
                if (refRule == null || !hasAssoc(refRule)) {
                    continue;
                }
                Integer refPrecedence = findPrecedenceLevel(refRule);
                if (refPrecedence == null) {
                    continue;
                }
                if (refPrecedence <= precedence) {
                    addRuleError(errors, rule.name(),
                        "rule " + rule.name() + " precedence " + precedence
                            + " must be lower than referenced operator rule "
                            + refName + " precedence " + refPrecedence,
                        "Decrease " + rule.name() + " level or increase " + refName + " level.",
                        "E-PRECEDENCE-ORDER");
                }
            }
        }
    }

    private static void validateAssociativityConsistency(GrammarDecl grammar, List<ValidationIssue> errors) {
        Map<Integer, String> assocByLevel = new LinkedHashMap<>();
        for (RuleDecl rule : grammar.rules()) {
            String assoc = getAssocKind(rule);
            if ("NONE".equals(assoc) || "BOTH".equals(assoc)) {
                continue;
            }
            Integer level = findPrecedenceLevel(rule);
            if (level == null) {
                addRuleError(errors, rule.name(),
                    "rule " + rule.name() + " uses @" + assoc.toLowerCase()
                        + "Assoc but has no @precedence",
                    "Add @precedence(level=...) to this operator rule.",
                    "E-ASSOC-NO-PRECEDENCE");
                continue;
            }
            String existing = assocByLevel.get(level);
            if (existing == null) {
                assocByLevel.put(level, assoc);
                continue;
            }
            if (!existing.equals(assoc)) {
                addRuleError(errors, rule.name(),
                    "precedence level " + level
                        + " mixes associativity: " + existing + " and " + assoc,
                    "Use one associativity per precedence level.",
                    "E-PRECEDENCE-MIXED-ASSOC");
            }
        }
    }

    private static void addRuleError(
        List<ValidationIssue> errors,
        String rule,
        String message,
        String hint,
        String code
    ) {
        errors.add(new ValidationIssue(code, message, hint, rule));
    }

    private static void addError(List<ValidationIssue> errors, String message, String hint, String code) {
        errors.add(new ValidationIssue(code, message, hint));
    }

    private static boolean hasAssoc(RuleDecl rule) {
        boolean left = rule.annotations().stream().anyMatch(a -> a instanceof LeftAssocAnnotation);
        boolean right = rule.annotations().stream().anyMatch(a -> a instanceof RightAssocAnnotation);
        return left || right;
    }

    private static String getAssocKind(RuleDecl rule) {
        boolean left = rule.annotations().stream().anyMatch(a -> a instanceof LeftAssocAnnotation);
        boolean right = rule.annotations().stream().anyMatch(a -> a instanceof RightAssocAnnotation);
        if (left && right) {
            return "BOTH";
        }
        if (left) {
            return "LEFT";
        }
        if (right) {
            return "RIGHT";
        }
        return "NONE";
    }

    private static Integer findPrecedenceLevel(RuleDecl rule) {
        return rule.annotations().stream()
            .filter(a -> a instanceof PrecedenceAnnotation)
            .map(a -> (PrecedenceAnnotation) a)
            .reduce((first, second) -> second)
            .map(PrecedenceAnnotation::level)
            .orElse(null);
    }

    private static Set<String> collectCaptureNames(RuleBody body) {
        Set<String> captures = new LinkedHashSet<>();
        collectCaptureNamesFromBody(body, captures);
        return captures;
    }

    private static void collectCaptureNamesFromBody(RuleBody body, Set<String> captures) {
        switch (body) {
            case ChoiceBody choice -> {
                for (SequenceBody seq : choice.alternatives()) {
                    collectCaptureNamesFromSequence(seq, captures);
                }
            }
            case SequenceBody seq -> collectCaptureNamesFromSequence(seq, captures);
        }
    }

    private static void collectCaptureNamesFromSequence(SequenceBody seq, Set<String> captures) {
        for (AnnotatedElement ae : seq.elements()) {
            ae.captureName().ifPresent(captures::add);
            collectCaptureNamesFromAtomic(ae.element(), captures);
        }
    }

    private static void collectCaptureNamesFromAtomic(AtomicElement element, Set<String> captures) {
        switch (element) {
            case GroupElement group -> collectCaptureNamesFromBody(group.body(), captures);
            case OptionalElement opt -> collectCaptureNamesFromBody(opt.body(), captures);
            case RepeatElement rep -> collectCaptureNamesFromBody(rep.body(), captures);
            default -> {
                // TerminalElement / RuleRefElement have no nested bodies.
            }
        }
    }

    private static Set<String> collectReferencedRuleNames(RuleBody body) {
        Set<String> refs = new LinkedHashSet<>();
        collectReferencedRuleNamesFromBody(body, refs);
        return refs;
    }

    private static void collectReferencedRuleNamesFromBody(RuleBody body, Set<String> refs) {
        switch (body) {
            case ChoiceBody choice -> {
                for (SequenceBody seq : choice.alternatives()) {
                    collectReferencedRuleNamesFromSequence(seq, refs);
                }
            }
            case SequenceBody seq -> collectReferencedRuleNamesFromSequence(seq, refs);
        }
    }

    private static void collectReferencedRuleNamesFromSequence(SequenceBody seq, Set<String> refs) {
        for (AnnotatedElement ae : seq.elements()) {
            collectReferencedRuleNamesFromAtomic(ae.element(), refs);
        }
    }

    private static void collectReferencedRuleNamesFromAtomic(AtomicElement element, Set<String> refs) {
        switch (element) {
            case RuleRefElement ref -> refs.add(ref.name());
            case GroupElement group -> collectReferencedRuleNamesFromBody(group.body(), refs);
            case OptionalElement opt -> collectReferencedRuleNamesFromBody(opt.body(), refs);
            case RepeatElement rep -> collectReferencedRuleNamesFromBody(rep.body(), refs);
            default -> {
                // TerminalElement has no nested refs.
            }
        }
    }

    private static boolean containsRepeat(RuleBody body) {
        return switch (body) {
            case ChoiceBody choice -> choice.alternatives().stream()
                .anyMatch(GrammarValidator::containsRepeatInSequence);
            case SequenceBody seq -> containsRepeatInSequence(seq);
        };
    }

    private static boolean containsRepeatInSequence(SequenceBody seq) {
        for (AnnotatedElement ae : seq.elements()) {
            if (containsRepeatInAtomic(ae.element())) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsRepeatInAtomic(AtomicElement element) {
        return switch (element) {
            case RepeatElement rep -> true;
            case GroupElement group -> containsRepeat(group.body());
            case OptionalElement opt -> containsRepeat(opt.body());
            default -> false;
        };
    }

    private static boolean isCanonicalRightAssocShape(RuleDecl rule) {
        SequenceBody top = getSingleSequence(rule.body());
        if (top == null || top.elements().size() != 2) {
            return false;
        }
        AtomicElement second = top.elements().get(1).element();
        if (!(second instanceof RepeatElement repeat)) {
            return false;
        }
        SequenceBody repSeq = getSingleSequence(repeat.body());
        if (repSeq == null || repSeq.elements().size() != 2) {
            return false;
        }
        AtomicElement repRight = repSeq.elements().get(1).element();
        return repRight instanceof RuleRefElement ref && rule.name().equals(ref.name());
    }

    private static SequenceBody getSingleSequence(RuleBody body) {
        return switch (body) {
            case SequenceBody seq -> seq;
            case ChoiceBody choice when choice.alternatives().size() == 1 -> choice.alternatives().get(0);
            default -> null;
        };
    }

    // =========================================================================
    // @typeof バリデーション
    // =========================================================================

    /** (TypeofElement.captureName → parent capture name) のペアを収集する */
    private record TypeofUsage(String referencedCapture, String ownCapture) {}

    private static void validateTypeofElements(RuleDecl rule, List<ValidationIssue> errors) {
        List<TypeofUsage> usages = new ArrayList<>();
        collectTypeofUsagesFromBody(rule.body(), usages);
        if (usages.isEmpty()) {
            return;
        }

        Set<String> captures = collectCaptureNames(rule.body());

        for (TypeofUsage usage : usages) {
            if (!captures.contains(usage.referencedCapture())) {
                addRuleError(errors, rule.name(),
                    "rule " + rule.name() + ": @typeof(" + usage.referencedCapture()
                        + ") refers to unknown capture '" + usage.referencedCapture() + "'",
                    "Use a capture name defined in the same rule (e.g. @captureRef).",
                    "E-TYPEOF-UNKNOWN-CAPTURE");
            }
            if (usage.ownCapture() == null || usage.ownCapture().isEmpty()) {
                addRuleError(errors, rule.name(),
                    "rule " + rule.name() + ": @typeof(" + usage.referencedCapture()
                        + ") must be paired with a capture name (e.g. @typeof(x) @myCapture)",
                    "Add a capture name after @typeof(x).",
                    "E-TYPEOF-MISSING-CAPTURE");
            }
        }
    }

    private static void collectTypeofUsagesFromBody(RuleBody body, List<TypeofUsage> usages) {
        switch (body) {
            case ChoiceBody choice -> {
                for (SequenceBody seq : choice.alternatives()) {
                    collectTypeofUsagesFromSequence(seq, usages);
                }
            }
            case SequenceBody seq -> collectTypeofUsagesFromSequence(seq, usages);
        }
    }

    private static void collectTypeofUsagesFromSequence(SequenceBody seq, List<TypeofUsage> usages) {
        for (AnnotatedElement ae : seq.elements()) {
            if (ae.typeofConstraint().isPresent()) {
                TypeofElement te = ae.typeofConstraint().get();
                usages.add(new TypeofUsage(te.captureName(), ae.captureName().orElse(null)));
            } else {
                collectTypeofUsagesFromAtomic(ae.element(), usages);
            }
        }
    }

    private static void collectTypeofUsagesFromAtomic(AtomicElement element, List<TypeofUsage> usages) {
        switch (element) {
            case GroupElement group -> collectTypeofUsagesFromBody(group.body(), usages);
            case OptionalElement opt -> collectTypeofUsagesFromBody(opt.body(), usages);
            case RepeatElement rep -> collectTypeofUsagesFromBody(rep.body(), usages);
            default -> {}
        }
    }

    // =========================================================================
    // @catalog バリデーション
    // =========================================================================

    private static void validateCatalogAnnotations(RuleDecl rule, List<ValidationIssue> errors) {
        boolean hasCatalog = rule.annotations().stream().anyMatch(a -> a instanceof CatalogAnnotation);
        if (!hasCatalog) {
            return;
        }
        Set<String> captures = collectCaptureNames(rule.body());
        if (captures.isEmpty()) {
            addRuleError(errors, rule.name(),
                "rule " + rule.name() + " has @catalog but no capture names in its body",
                "Add at least one @captureName in the rule body for catalog completion to work.",
                "E-ANNOTATION-CATALOG-NO-CAPTURE");
        }
    }
}
