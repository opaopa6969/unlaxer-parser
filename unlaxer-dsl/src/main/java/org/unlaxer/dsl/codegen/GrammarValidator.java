package org.unlaxer.dsl.codegen;

import org.unlaxer.dsl.bootstrap.UBNFAST;
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
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
            if (code.startsWith("E-RULE-")) {
                return "RULE";
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
        validateBoundedRepeatElements(grammar, errors);
        validateCommonFields(grammar, errors);
        validateEnumRules(grammar, errors);

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
        validateUndefinedRuleRefs(grammar, errors);

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

    /**
     * Detects left-recursive patterns in the grammar and returns warning messages.
     *
     * <p>Left recursion is detected when a rule's first possible element (after
     * stripping optional and repeat wrappers) is a reference back to the rule
     * itself (direct) or to another rule that in turn reaches back to the original
     * rule (indirect). This method <b>never throws</b> — it is purely advisory.
     * Existing grammars continue to parse normally.</p>
     *
     * @param grammar the grammar to inspect
     * @return {@link Optional#empty()} if no left-recursion is found, or an
     *         {@link Optional} containing a non-empty list of human-readable
     *         warning strings (one per cycle detected)
     */
    public static Optional<List<String>> validateWithWarnings(GrammarDecl grammar) {
        List<String> warnings = detectLeftRecursion(grammar);
        if (warnings.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(List.copyOf(warnings));
    }

    // =========================================================================
    // Left-recursion detection (warning only)
    // =========================================================================

    /**
     * Returns warning messages for every left-recursive cycle found in the
     * grammar. Both direct ({@code A ::= A ...}) and indirect
     * ({@code A ::= B ...; B ::= A ...}) cycles are reported.
     */
    static List<String> detectLeftRecursion(GrammarDecl grammar) {
        List<String> warnings = new ArrayList<>();
        for (String cycleKey : detectLeftRecursionCycles(grammar)) {
            warnings.add("W-LEFT-RECURSION: left-recursive cycle detected: " + cycleKey);
        }
        return warnings;
    }

    /**
     * Structured variant of {@link #validateWithWarnings(GrammarDecl)}. Returns
     * one {@code W-LEFT-RECURSION} issue per detected cycle so that left-recursion
     * warnings flow through the standard validation report machinery
     * ({@code --fail-on}, {@code --strict}, report files). Never returns errors.
     */
    public static List<ValidationIssue> detectLeftRecursionIssues(GrammarDecl grammar) {
        List<ValidationIssue> issues = new ArrayList<>();
        for (String cycleKey : detectLeftRecursionCycles(grammar)) {
            int firstArrow = cycleKey.indexOf(" ->");
            String firstRule = firstArrow < 0 ? cycleKey : cycleKey.substring(0, firstArrow);
            issues.add(new ValidationIssue(
                "W-LEFT-RECURSION",
                "left-recursive cycle detected: " + cycleKey,
                "Rewrite using repetition (e.g. A ::= B { Op B }) or right recursion.",
                firstRule
            ));
        }
        return List.copyOf(issues);
    }

    private static List<String> detectLeftRecursionCycles(GrammarDecl grammar) {
        // Build rule map
        Map<String, RuleDecl> ruleMap = new LinkedHashMap<>();
        for (RuleDecl rule : grammar.rules()) {
            ruleMap.put(rule.name(), rule);
        }

        List<String> cycles = new ArrayList<>();
        Set<String> reported = new LinkedHashSet<>();

        for (String startName : ruleMap.keySet()) {
            // DFS: track current path for cycle detection
            List<String> path = new ArrayList<>();
            Set<String> onStack = new LinkedHashSet<>();
            findLeftRecursion(startName, ruleMap, path, onStack, reported, cycles);
        }
        return cycles;
    }

    /**
     * Collects the set of rule names that can appear as the <em>leftmost</em>
     * symbol of the given rule body — i.e. rule references reachable without
     * consuming any input first.
     *
     * <ul>
     *   <li>For a {@link SequenceBody}, only the first element is relevant.</li>
     *   <li>For a {@link ChoiceBody}, all alternatives contribute.</li>
     *   <li>{@link OptionalElement} and {@link RepeatElement} bodies are
     *       transparent because they can match zero tokens.</li>
     *   <li>{@link GroupElement} is transparent (no input consumed by the group
     *       wrapper itself).</li>
     *   <li>A {@link UBNFAST.TerminalElement} terminates the leftmost scan —
     *       it consumes input so no rule reference can be leftmost after it.</li>
     * </ul>
     */
    private static Set<String> leftmostRuleRefs(RuleBody body) {
        Set<String> refs = new LinkedHashSet<>();
        collectLeftmostRefs(body, refs);
        return refs;
    }

    private static void collectLeftmostRefs(RuleBody body, Set<String> out) {
        switch (body) {
            case ChoiceBody choice -> {
                for (SequenceBody seq : choice.alternatives()) {
                    collectLeftmostRefsFromSequence(seq, out);
                }
            }
            case SequenceBody seq -> collectLeftmostRefsFromSequence(seq, out);
        }
    }

    private static void collectLeftmostRefsFromSequence(SequenceBody seq, Set<String> out) {
        for (AnnotatedElement ae : seq.elements()) {
            boolean canBeEmpty = collectLeftmostRefsFromAtomic(ae.element(), out);
            if (!canBeEmpty) {
                // This element always consumes input → later elements are not leftmost
                return;
            }
            // Element can match empty → continue to next element
        }
    }

    /**
     * Inspects one atomic element.
     *
     * @return {@code true} if the element can match the empty string
     *         (allowing the next element to potentially be leftmost)
     */
    private static boolean collectLeftmostRefsFromAtomic(AtomicElement element, Set<String> out) {
        return switch (element) {
            case RuleRefElement ref -> {
                String fullName = ref.namespace()
                    .map(ns -> ns + "." + ref.name())
                    .orElse(ref.name());
                out.add(fullName);
                yield false; // conservative: assume the referenced rule consumes input
            }
            case UBNFAST.TerminalElement t -> false; // always consumes input
            case OptionalElement opt -> {
                collectLeftmostRefs(opt.body(), out);
                yield true; // optional always can be empty
            }
            case RepeatElement rep -> {
                collectLeftmostRefs(rep.body(), out);
                yield true; // zero-or-more can be empty
            }
            case GroupElement group -> {
                collectLeftmostRefs(group.body(), out);
                yield false; // conservative: treat group as non-empty
            }
            default -> false;
        };
    }

    private static void findLeftRecursion(
        String current,
        Map<String, RuleDecl> ruleMap,
        List<String> path,
        Set<String> onStack,
        Set<String> reported,
        List<String> cycles
    ) {
        if (onStack.contains(current)) {
            // Cycle detected — the cycle starts where current first appears in path
            int cycleStart = path.indexOf(current);
            if (cycleStart < 0) {
                return;
            }
            List<String> cycle = new ArrayList<>(path.subList(cycleStart, path.size()));
            cycle.add(current); // close the loop

            // Build a canonical key to avoid duplicate reports
            String cycleKey = String.join(" -> ", cycle);
            if (!reported.contains(cycleKey)) {
                reported.add(cycleKey);
                cycles.add(cycleKey);
            }
            return;
        }

        RuleDecl rule = ruleMap.get(current);
        if (rule == null) {
            return; // external / imported rule — skip
        }

        path.add(current);
        onStack.add(current);

        Set<String> nextRefs = leftmostRuleRefs(rule.body());
        for (String next : nextRefs) {
            findLeftRecursion(next, ruleMap, path, onStack, reported, cycles);
        }

        path.remove(path.size() - 1);
        onStack.remove(current);
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
        // ライブラリ grammar (@import を持つ grammar からインポートされる側) は @root 不要
        // import 宣言を持つ grammar 自身はエントリーポイントを持つので通常通り検証する
        // ただし、ルールが全て alias.Name 形式（import 展開後）の場合はスキップ
        boolean allRulesAreImported = !grammar.rules().isEmpty() &&
            grammar.rules().stream().allMatch(r -> r.name().contains("."));
        if (allRulesAreImported) {
            return;
        }
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
                    String hint = "Ensure the parser class is fully qualified or in a known package "
                        + "(org.unlaxer.parser.clang, elementary, posix).";
                    List<String> candidates = fullyQualifiedCandidates(parserClass);
                    if (!candidates.isEmpty()) {
                        hint = "Did you mean '" + String.join("' or '", candidates) + "'? " + hint;
                    }
                    addError(
                        errors,
                        "token " + simple.name() + " references unresolved parser class: "
                            + parserClass,
                        hint,
                        "W-TOKEN-UNRESOLVED"
                    );
                }
            }
        }
    }

    /**
     * Probes well-known parser packages for a class with the given simple name
     * and returns the fully qualified candidates, sorted for deterministic
     * output. Returns an empty list when the name is already qualified.
     */
    private static List<String> fullyQualifiedCandidates(String parserClass) {
        if (parserClass.contains(".")) {
            return List.of();
        }
        List<String> candidatePackages = List.of(
            "org.unlaxer.parser.elementary",
            "org.unlaxer.parser.posix",
            "org.unlaxer.parser.clang",
            "org.unlaxer.parser.combinator",
            "org.unlaxer.parser.ascii"
        );
        List<String> candidates = new ArrayList<>();
        for (String candidatePackage : candidatePackages) {
            String candidate = candidatePackage + "." + parserClass;
            try {
                Class.forName(candidate, false, GrammarValidator.class.getClassLoader());
                candidates.add(candidate);
            } catch (ClassNotFoundException e) {
                // not present in this package
            }
        }
        return candidates;
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

    private static void validateEnumRules(GrammarDecl grammar, List<ValidationIssue> errors) {
        for (RuleDecl rule : grammar.rules()) {
            boolean isEnum = rule.annotations().stream()
                .anyMatch(a -> a instanceof UBNFAST.EnumAnnotation);
            if (!isEnum) {
                continue;
            }
            // @enum ルールは Choice+Terminal のみ許容
            boolean allTerminals = switch (rule.body()) {
                case UBNFAST.ChoiceBody choice -> choice.alternatives().stream()
                    .allMatch(seq -> seq.elements().size() == 1
                        && seq.elements().get(0).element() instanceof UBNFAST.TerminalElement);
                case UBNFAST.SequenceBody seq -> seq.elements().size() == 1
                    && seq.elements().get(0).element() instanceof UBNFAST.TerminalElement;
            };
            if (!allTerminals) {
                addRuleError(errors, rule.name(),
                    "@enum rule " + rule.name() + " must consist only of terminal literals (e.g. 'a' | 'b')",
                    "Use only quoted string literals as alternatives in an @enum rule.",
                    "E-ENUM-NON-TERMINAL");
            }
        }
    }

    private static void validateCommonFields(GrammarDecl grammar, List<ValidationIssue> errors) {
        // @commonField(field) が付いたルールの全 permit に該当フィールドが存在するか確認
        for (RuleDecl rule : grammar.rules()) {
            rule.annotations().stream()
                .filter(a -> a instanceof UBNFAST.CommonFieldAnnotation)
                .map(a -> (UBNFAST.CommonFieldAnnotation) a)
                .forEach(commonField -> {
                    MappingAnnotation mapping = rule.annotations().stream()
                        .filter(a -> a instanceof MappingAnnotation)
                        .map(a -> (MappingAnnotation) a)
                        .findFirst().orElse(null);
                    if (mapping == null) {
                        return;
                    }
                    String sealedName = mapping.className();
                    List<RuleDecl> permits = grammar.rules().stream()
                        .filter(r -> {
                            MappingAnnotation m = r.annotations().stream()
                                .filter(a -> a instanceof MappingAnnotation)
                                .map(a -> (MappingAnnotation) a)
                                .findFirst().orElse(null);
                            return m != null && m.className().startsWith(sealedName + ".");
                        })
                        .toList();
                    for (String field : commonField.fieldNames()) {
                        for (RuleDecl permit : permits) {
                            MappingAnnotation pm = permit.annotations().stream()
                                .filter(a -> a instanceof MappingAnnotation)
                                .map(a -> (MappingAnnotation) a)
                                .findFirst().orElse(null);
                            if (pm != null && !pm.paramNames().contains(field)) {
                                addRuleError(errors, rule.name(),
                                    "@commonField(" + field + ") is declared on " + rule.name()
                                        + " but permit " + pm.className() + " does not have param '" + field + "'",
                                    "Add @" + field + " capture to " + pm.className() + " rule.",
                                    "E-COMMONFIELD-MISMATCH");
                            }
                        }
                    }
                });
        }
    }

    private static void validateBoundedRepeatElements(GrammarDecl grammar, List<ValidationIssue> errors) {
        for (RuleDecl rule : grammar.rules()) {
            collectBoundedElements(rule.body()).forEach(bounded -> {
                if (bounded.max() != UBNFAST.BoundedRepeatElement.UNBOUNDED && bounded.min() > bounded.max()) {
                    addRuleError(errors, rule.name(),
                        "rule " + rule.name() + " has BoundedRepeatElement with min=" + bounded.min()
                            + " > max=" + bounded.max(),
                        "Ensure min <= max in {min,max} quantifier.",
                        "E-BOUNDED-INVALID");
                }
            });
        }
    }

    private static java.util.stream.Stream<UBNFAST.BoundedRepeatElement> collectBoundedElements(UBNFAST.RuleBody body) {
        return switch (body) {
            case UBNFAST.ChoiceBody choice -> choice.alternatives().stream()
                .flatMap(seq -> seq.elements().stream())
                .flatMap(ae -> collectBoundedFromAtomic(ae.element()));
            case UBNFAST.SequenceBody seq -> seq.elements().stream()
                .flatMap(ae -> collectBoundedFromAtomic(ae.element()));
        };
    }

    private static java.util.stream.Stream<UBNFAST.BoundedRepeatElement> collectBoundedFromAtomic(UBNFAST.AtomicElement element) {
        return switch (element) {
            case UBNFAST.BoundedRepeatElement b -> java.util.stream.Stream.of(b);
            case UBNFAST.GroupElement g -> collectBoundedElements(g.body());
            case UBNFAST.OptionalElement o -> collectBoundedElements(o.body());
            case UBNFAST.RepeatElement r -> collectBoundedElements(r.body());
            default -> java.util.stream.Stream.empty();
        };
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
            case RuleRefElement ref -> {
                // 名前空間付き参照 (alias.RuleName) はそのまま追加
                String fullName = ref.namespace()
                    .map(ns -> ns + "." + ref.name())
                    .orElse(ref.name());
                refs.add(fullName);
            }
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
        if (false == hasCatalog) {
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

    // =========================================================================
    // E-RULE-UNDEFINED: 未定義ルール参照の検出 + Levenshtein による候補提示
    // =========================================================================

    /**
     * Validates that all RuleRefElement references in rule bodies point to
     * defined rules or tokens. When an undefined reference is found, suggests
     * the closest known name using Levenshtein distance.
     */
    private static void validateUndefinedRuleRefs(GrammarDecl grammar, List<ValidationIssue> errors) {
        Set<String> definedNames = new LinkedHashSet<>();
        for (RuleDecl rule : grammar.rules()) {
            definedNames.add(rule.name());
        }
        for (TokenDecl token : grammar.tokens()) {
            definedNames.add(token.name());
        }
        // import エイリアスを収集: alias.* 形式の参照はバリデーションをスキップ
        Set<String> importAliases = new LinkedHashSet<>();
        for (UBNFAST.ImportDecl imp : grammar.imports()) {
            importAliases.add(imp.alias());
        }

        for (RuleDecl rule : grammar.rules()) {
            Set<String> refs = collectReferencedRuleNames(rule.body());
            for (String refName : refs) {
                // alias.RuleName 形式の参照は import エイリアスがあれば有効とみなす
                int dot = refName.indexOf('.');
                if (dot > 0 && importAliases.contains(refName.substring(0, dot))) {
                    continue;
                }
                if (false == definedNames.contains(refName)) {
                    String suggestion = suggestSimilarName(refName, definedNames);
                    String hint;
                    if (suggestion != null) {
                        hint = "Did you mean '" + suggestion + "'?";
                    } else {
                        hint = "Define a rule or token named '" + refName + "'.";
                    }
                    addRuleError(errors, rule.name(),
                        "rule " + rule.name() + " references undefined rule or token '"
                            + refName + "'",
                        hint,
                        "E-RULE-UNDEFINED");
                }
            }
        }
    }

    /**
     * Suggests the closest matching name from a collection of known names
     * using Levenshtein distance. Returns null if no name is within the
     * threshold (distance <= 2).
     *
     * @param unknown the unknown name to find a match for
     * @param known   the collection of known valid names
     * @return the closest match, or null if none within threshold
     */
    static String suggestSimilarName(String unknown, Collection<String> known) {
        if (unknown == null || known == null || known.isEmpty()) {
            return null;
        }

        int threshold = 2;
        String bestMatch = null;
        int bestDistance = threshold + 1;

        for (String candidate : known) {
            int distance = levenshteinDistance(unknown, candidate);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestMatch = candidate;
            }
        }

        if (bestDistance <= threshold) {
            return bestMatch;
        }
        return null;
    }

    /**
     * Computes the Levenshtein edit distance between two strings.
     *
     * @param a first string
     * @param b second string
     * @return the minimum number of single-character edits (insert, delete, substitute)
     */
    private static int levenshteinDistance(String a, String b) {
        if (a == null) {
            return (b == null) ? 0 : b.length();
        }
        if (b == null) {
            return a.length();
        }

        int lengthA = a.length();
        int lengthB = b.length();

        // previous and current row of the DP matrix
        int[] previousRow = new int[lengthB + 1];
        int[] currentRow = new int[lengthB + 1];

        for (int j = 0; j <= lengthB; j++) {
            previousRow[j] = j;
        }

        for (int i = 1; i <= lengthA; i++) {
            currentRow[0] = i;
            for (int j = 1; j <= lengthB; j++) {
                int cost = (a.charAt(i - 1) == b.charAt(j - 1)) ? 0 : 1;
                int insertion = currentRow[j - 1] + 1;
                int deletion = previousRow[j] + 1;
                int substitution = previousRow[j - 1] + cost;
                currentRow[j] = Math.min(Math.min(insertion, deletion), substitution);
            }
            // swap rows
            int[] temp = previousRow;
            previousRow = currentRow;
            currentRow = temp;
        }

        return previousRow[lengthB];
    }
}
