package org.unlaxer.dsl.codegen;

import org.unlaxer.dsl.bootstrap.UBNFAST.TransparentElement;

import org.unlaxer.dsl.bootstrap.UBNFAST;
import org.unlaxer.dsl.bootstrap.UBNFAST.AnnotatedElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.Annotation;
import org.unlaxer.dsl.bootstrap.UBNFAST.AtomicElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.BackrefAnnotation;
import org.unlaxer.dsl.bootstrap.UBNFAST.CatalogAnnotation;
import org.unlaxer.dsl.bootstrap.UBNFAST.ChoiceBody;
import org.unlaxer.dsl.bootstrap.UBNFAST.DeclaresAnnotation;
import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.bootstrap.UBNFAST.GroupElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.InterleaveAnnotation;
import org.unlaxer.dsl.bootstrap.UBNFAST.LeftAssocAnnotation;
import org.unlaxer.dsl.bootstrap.UBNFAST.LongestChoiceAnnotation;
import org.unlaxer.dsl.bootstrap.UBNFAST.UniqueLongestChoiceAnnotation;
import org.unlaxer.dsl.bootstrap.UBNFAST.MappingAnnotation;
import org.unlaxer.dsl.bootstrap.UBNFAST.OptionalElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.PrecedenceAnnotation;
import org.unlaxer.dsl.bootstrap.UBNFAST.PredictiveChoiceAnnotation;
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
import org.unlaxer.dsl.bootstrap.UBNFAST.WhitespaceAnnotation;
import org.unlaxer.dsl.bootstrap.TokenAdapterRegistry;
import org.unlaxer.dsl.bootstrap.TokenContractRegistry;
import org.unlaxer.dsl.bootstrap.UBNFFeatures;

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
            if (code.startsWith("E-MEMO-")) {
                return "MEMOIZATION";
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
        return validate(grammar, true);
    }

    /** Pure validation for tooling that must not resolve Java parser classes. */
    public static List<ValidationIssue> validateWithoutClassLoading(GrammarDecl grammar) {
        return validate(grammar, false);
    }

    private static List<ValidationIssue> validate(GrammarDecl grammar, boolean resolveParserClasses) {
        List<ValidationIssue> errors = new ArrayList<>();
        for (TokenDecl token : grammar.tokens()) {
            if (org.unlaxer.dsl.bootstrap.RegexRetirement.retired(token)) {
                errors.add(new ValidationIssue(org.unlaxer.dsl.bootstrap.RegexRetirement.CODE,
                    org.unlaxer.dsl.bootstrap.RegexRetirement.MESSAGE, "Use token NAME ::= expression;", token.name()));
            }
        }
        try { LeftRecursionAnalysis.requireValid(grammar); }
        catch (IllegalArgumentException error) {
            errors.add(new ValidationIssue(error.getMessage().split(":", 2)[0], error.getMessage(),
                "Rewrite using repetition or input-consuming right recursion."));
        }
        errors.addAll(NestedCaptures.problems(grammar));
        validateScalarRanges(grammar, errors);
        errors.addAll(validateCaseInsensitiveTokens(grammar));

        try { org.unlaxer.dsl.bootstrap.LexicalCompiler.compile(grammar); }
        catch (IllegalArgumentException error) {
            errors.add(new ValidationIssue("E-LEXICAL", error.getMessage(), "Correct the declarative token expression."));
        }

        for (TokenAdapterRegistry.Diagnostic issue : TokenAdapterRegistry.build(grammar, null).diagnostics()) {
            errors.add(new ValidationIssue(issue.code(), issue.code() + ": " + issue.subject(),
                "Correct the token adapter declaration or registration."));
        }
        for (TokenContractRegistry.Diagnostic issue : TokenContractRegistry.build(grammar, null).diagnostics()) {
            errors.add(new ValidationIssue(issue.code(), issue.code() + ": " + issue.subject(),
                "Correct the format-2 token contract declaration."));
        }
        for (UBNFFeatures.Diagnostic issue : UBNFFeatures.validate(grammar)) {
            errors.add(new ValidationIssue(issue.code(), issue.code() + ": " + issue.subject(),
                "Use a supported format-2 @feature declaration."));
        }

        for (var issue : TokenStreamGrammar.problems(grammar)) {
            errors.add(new ValidationIssue(issue.code(), issue.subject(), "Use the supported @tokenStream:enabled profile."));
        }
        for (var issue : GlobalComment.problems(grammar)) {
            errors.add(new ValidationIssue(issue.code(), issue.subject(), issue.code().equals("E-COMMENT-SETTING")
                ? "Use a single @comment: { line: '//' } setting."
                : "Put line comments into the named whitespace token, or use @whitespace: javaStyle."));
        }
        // Kept for the API (#748). The flag CLI (#777) and the UBNF LSP (#798) report
        // W-COMMENT-IGNORED once, from GenerationWarnings with its source span, and drop this row.
        for (var issue : GlobalComment.warnings(grammar)) {
            errors.add(new ValidationIssue(issue.code(), issue.subject(),
                "@whitespace: javaStyle already skips // comments; remove this @comment setting."));
        }
        try { LexicalContexts.requireValid(grammar); }
        catch (IllegalArgumentException error) {
            String code = error.getMessage().startsWith("E-LEXICAL-CONTEXT-") ? error.getMessage().split(":",2)[0] : "E-LEXICAL-CONTEXT";
            errors.add(new ValidationIssue(code, error.getMessage(), "Select non-nullable declarative tokens and nonempty unique literals."));
        }
        try { NamePredicates.requireValid(grammar); }
        catch (IllegalArgumentException error) {
            String message=error.getMessage();
            String code=message!=null && message.startsWith("E-NAME-PREDICATE-") ? message.split(":",2)[0] : "E-NAME-PREDICATE-CAPTURE";
            errors.add(new ValidationIssue(code,message,"Use one versioned name predicate over a required scalar text capture."));
        }
        try { EmbeddedGrammarEmitter.declarations(grammar); }
        catch (IllegalArgumentException error) {
            errors.add(new ValidationIssue("E-EMBEDDING", error.getMessage(), "Use one scalar body capture and exact language identity."));
        }
        for (var problem : RaisingOperators.problems(grammar)) {
            errors.add(new ValidationIssue(problem.code(), problem.message(),
                "Name only labels raised by LABEL(...) and give CATCH at least one label.", problem.rule()));
        }
        validateGlobalWhitespace(grammar, errors);
        validateUbnfFormat(grammar, errors);
        validateMemoSafeTokens(grammar, errors);
        validateRootPresence(grammar, errors);
        if (resolveParserClasses) {
            validateTokens(grammar, errors);
            errors.addAll(validateJavaTokenBindings(grammar));
        }
        validateRuleTokenParserNameCollisions(grammar, errors);
        validateAdapterTokenParserNameCollisions(grammar, errors);
        validateBoundedRepeatElements(grammar, errors);
        validateCommonFields(grammar, errors);
        validateEnumRules(grammar, errors);

        for (RuleDecl rule : grammar.rules()) {
            MappingAnnotation mapping = null;
            boolean hasLeftAssoc = false;
            boolean hasRightAssoc = false;
            int longestChoiceAnnotations = 0;
            int uniqueLongestChoiceAnnotations = 0;
            int predictiveChoiceAnnotations = 0;
            List<PrecedenceAnnotation> precedenceAnnotations = new ArrayList<>();
            List<InterleaveAnnotation> interleaveAnnotations = new ArrayList<>();
            int whitespaceAnnotations = 0;
            List<BackrefAnnotation> backrefAnnotations = new ArrayList<>();
            List<ScopeTreeAnnotation> scopeTreeAnnotations = new ArrayList<>();

            for (Annotation annotation : rule.annotations()) {
                if (annotation instanceof MappingAnnotation m) {
                    mapping = m;
                } else if (annotation instanceof LeftAssocAnnotation) {
                    hasLeftAssoc = true;
                } else if (annotation instanceof RightAssocAnnotation) {
                    hasRightAssoc = true;
                } else if (annotation instanceof LongestChoiceAnnotation) {
                    longestChoiceAnnotations++;
                } else if (annotation instanceof UniqueLongestChoiceAnnotation) {
                    uniqueLongestChoiceAnnotations++;
                } else if (annotation instanceof PredictiveChoiceAnnotation) {
                    predictiveChoiceAnnotations++;
                } else if (annotation instanceof PrecedenceAnnotation p) {
                    precedenceAnnotations.add(p);
                } else if (annotation instanceof InterleaveAnnotation i) {
                    interleaveAnnotations.add(i);
                } else if (annotation instanceof BackrefAnnotation b) {
                    backrefAnnotations.add(b);
                } else if (annotation instanceof ScopeTreeAnnotation s) {
                    scopeTreeAnnotations.add(s);
                } else if (annotation instanceof WhitespaceAnnotation w) {
                    whitespaceAnnotations++;
                    validateRuleWhitespace(grammar, rule, w, errors);
                }
            }
            if (whitespaceAnnotations > 1) {
                addRuleError(errors, rule.name(),
                    "rule " + rule.name() + " has duplicate @whitespace annotations",
                    "Keep a single @whitespace annotation.",
                    "E-WHITESPACE-RULE-DUPLICATE");
            }

            if (mapping != null) {
                validateMapping(rule, mapping, errors);
            }
            if (hasLeftAssoc || hasRightAssoc) {
                validateAssoc(rule, mapping, hasLeftAssoc, hasRightAssoc, errors);
            }
            validateUniqueLongestChoice(rule, hasLeftAssoc || hasRightAssoc || longestChoiceAnnotations > 0
                || predictiveChoiceAnnotations > 0, uniqueLongestChoiceAnnotations, errors);
            validateLongestChoice(rule, hasLeftAssoc, hasRightAssoc, longestChoiceAnnotations, errors);
            validatePredictiveChoice(rule, hasLeftAssoc, hasRightAssoc, longestChoiceAnnotations,
                predictiveChoiceAnnotations, errors);
            validatePrecedence(rule, hasLeftAssoc, hasRightAssoc, precedenceAnnotations, errors);
            validateAdvancedAnnotations(rule, interleaveAnnotations, backrefAnnotations, scopeTreeAnnotations, errors);
            validateScopeCaptures(rule, errors);
            validateTypeofElements(rule, errors);
            validateCatalogAnnotations(rule, errors);
        }
        validatePrecedenceTopology(grammar, errors);
        validateAssociativityConsistency(grammar, errors);
        validateUndefinedRuleRefs(grammar, errors);
        if (grammar.rules().stream().anyMatch(rule -> rule.annotations().stream()
                .anyMatch(UniqueLongestChoiceAnnotation.class::isInstance))) {
            for (var cycle : detectLeftRecursionIssues(grammar)) {
                errors.add(new ValidationIssue("E-UNIQUE-LONGEST-LEFT-RECURSION", cycle.message(),
                    "The unique-longest profile supports acyclic left edges; rewrite the cycle.", cycle.rule()));
            }
        }

        return List.copyOf(errors);
    }

    /**
     * Declaration diagnostics shared with Rust lowering (#833-#835). This is the
     * syntax-only subset that must precede Rust's root requirement; it deliberately
     * does not rerun schema/shape analyses or change their earlier priority (#825).
     * Keep the order identical to validate(): global, per-rule, consistency, refs.
     */
    public static List<ValidationIssue> validateRustDeclarations(GrammarDecl grammar) {
        var errors = new ArrayList<ValidationIssue>();
        validateGlobalWhitespace(grammar, errors);
        for (var rule : grammar.rules()) {
            var whitespace = rule.annotations().stream().filter(WhitespaceAnnotation.class::isInstance)
                .map(WhitespaceAnnotation.class::cast).toList();
            for (var annotation : whitespace) validateRuleWhitespace(grammar, rule, annotation, errors);
            if (whitespace.size() > 1) addRuleError(errors, rule.name(),
                "rule " + rule.name() + " has duplicate @whitespace annotations",
                "Keep a single @whitespace annotation.", "E-WHITESPACE-RULE-DUPLICATE");
            rule.annotations().stream().filter(MappingAnnotation.class::isInstance)
                .map(MappingAnnotation.class::cast).reduce((first, second) -> second)
                .ifPresent(mapping -> validateMapping(rule, mapping, errors));
            boolean left = rule.annotations().stream().anyMatch(LeftAssocAnnotation.class::isInstance);
            boolean right = rule.annotations().stream().anyMatch(RightAssocAnnotation.class::isInstance);
            if (left && right) addRuleError(errors, rule.name(),
                "rule " + rule.name() + " cannot use both @leftAssoc and @rightAssoc",
                "Keep exactly one associativity annotation per rule.", "E-ASSOC-BOTH");
            int longest = (int) rule.annotations().stream().filter(LongestChoiceAnnotation.class::isInstance).count();
            int predictive = (int) rule.annotations().stream().filter(PredictiveChoiceAnnotation.class::isInstance).count();
            int unique = (int) rule.annotations().stream().filter(UniqueLongestChoiceAnnotation.class::isInstance).count();
            validateUniqueLongestChoice(rule, left || right || longest > 0 || predictive > 0, unique, errors);
            validateLongestChoice(rule, left, right, longest, errors);
            validatePredictiveChoice(rule, left, right, longest, predictive, errors);
            validatePrecedence(rule, left, right, rule.annotations().stream()
                .filter(PrecedenceAnnotation.class::isInstance).map(PrecedenceAnnotation.class::cast).toList(), errors);
            validateAdvancedAnnotations(rule,
                rule.annotations().stream().filter(InterleaveAnnotation.class::isInstance).map(InterleaveAnnotation.class::cast).toList(),
                List.of(),
                rule.annotations().stream().filter(ScopeTreeAnnotation.class::isInstance).map(ScopeTreeAnnotation.class::cast).toList(), errors);
        }
        validateAssociativityConsistency(grammar, errors);
        validateUndefinedRuleRefs(grammar, errors, true);
        // Other declaration/shape errors retain their existing lowering paths.
        var codes = Set.of("E-WHITESPACE-GLOBAL-DUPLICATE", "E-WHITESPACE-GLOBAL-STYLE",
            "E-WHITESPACE-RULE-STYLE", "E-WHITESPACE-RULE-DUPLICATE",
            "E-MAPPING-DUPLICATE-PARAM", "E-MAPPING-MISSING-CAPTURE", "E-MAPPING-UNLISTED-CAPTURE",
            "E-ASSOC-BOTH", "E-PRECEDENCE-DUPLICATE", "E-PRECEDENCE-NO-ASSOC",
            "E-ANNOTATION-INTERLEAVE-PROFILE", "E-ANNOTATION-SCOPETREE-MODE",
            "E-ASSOC-NO-PRECEDENCE", "E-RULE-UNDEFINED");
        // A choice-profile error occurs before precedence in Java. Let the existing
        // lowering path report it, preserving its established explanation/code.
        return errors.stream().filter(issue -> codes.contains(issue.code())
                || issue.code().startsWith("E-LONGEST-CHOICE-")
                || issue.code().startsWith("E-PREDICTIVE-CHOICE-")
                || issue.code().startsWith("E-UNIQUE-LONGEST-"))
            .findFirst().filter(issue -> codes.contains(issue.code())).stream().toList();
    }

    private static void validateUniqueLongestChoice(RuleDecl rule, boolean conflict, int count,
            List<ValidationIssue> errors) {
        if (count == 0) return;
        if (count > 1) addRuleError(errors, rule.name(), "duplicate @uniqueLongestChoice",
            "Keep a single profile.", "E-UNIQUE-LONGEST-DUPLICATE");
        if (!(rule.body() instanceof ChoiceBody choice) || choice.alternatives().size() < 2)
            addRuleError(errors, rule.name(), "@uniqueLongestChoice requires multiple alternatives",
                "Use 2 to 64 alternatives.", "E-UNIQUE-LONGEST-SHAPE");
        else if (choice.alternatives().size() > 64)
            addRuleError(errors, rule.name(), "@uniqueLongestChoice exceeds 64 alternatives",
                "Split the dispatch rule.", "E-UNIQUE-LONGEST-LIMIT");
        if (conflict) addRuleError(errors, rule.name(), "conflicting @uniqueLongestChoice profiles",
            "Use one choice profile and no associativity rewriting.", "E-UNIQUE-LONGEST-CONFLICT");
    }

    private static void validateLongestChoice(
        RuleDecl rule,
        boolean hasLeftAssoc,
        boolean hasRightAssoc,
        int count,
        List<ValidationIssue> errors
    ) {
        if (count == 0) return;
        if (count > 1) {
            addRuleError(errors, rule.name(),
                "rule " + rule.name() + " has duplicate @longestChoice annotations",
                "Keep a single @longestChoice annotation.",
                "E-LONGEST-CHOICE-DUPLICATE");
        }
        if (!(rule.body() instanceof ChoiceBody choice) || choice.alternatives().size() < 2) {
            addRuleError(errors, rule.name(),
                "rule " + rule.name() + " uses @longestChoice without multiple alternatives",
                "Use @longestChoice only on a rule with at least two '|' alternatives.",
                "E-LONGEST-CHOICE-SHAPE");
        }
        if (hasLeftAssoc || hasRightAssoc) {
            addRuleError(errors, rule.name(),
                "rule " + rule.name() + " combines @longestChoice with associativity rewriting",
                "Move longest matching to a separate dispatch rule.",
                "E-LONGEST-CHOICE-ASSOC");
        }
    }

    private static void validatePredictiveChoice(
        RuleDecl rule,
        boolean hasLeftAssoc,
        boolean hasRightAssoc,
        int longestChoiceCount,
        int count,
        List<ValidationIssue> errors
    ) {
        if (count == 0) return;
        if (count > 1) {
            addRuleError(errors, rule.name(),
                "rule " + rule.name() + " has duplicate @predictiveChoice annotations",
                "Keep a single @predictiveChoice annotation.",
                "E-PREDICTIVE-CHOICE-DUPLICATE");
        }
        if (!(rule.body() instanceof ChoiceBody choice) || choice.alternatives().size() < 2) {
            addRuleError(errors, rule.name(),
                "rule " + rule.name() + " uses @predictiveChoice without multiple alternatives",
                "Use @predictiveChoice only on a rule with at least two '|' alternatives.",
                "E-PREDICTIVE-CHOICE-SHAPE");
        }
        if (hasLeftAssoc || hasRightAssoc) {
            addRuleError(errors, rule.name(),
                "rule " + rule.name() + " combines @predictiveChoice with associativity rewriting",
                "Move predictive dispatch to a separate rule.",
                "E-PREDICTIVE-CHOICE-ASSOC");
        }
        if (longestChoiceCount > 0) {
            addRuleError(errors, rule.name(),
                "rule " + rule.name() + " combines @predictiveChoice with @longestChoice",
                "Choose ordered predictive dispatch or longest-match dispatch.",
                "E-PREDICTIVE-CHOICE-CONFLICT");
        }
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
     * Generation and strict validation reject cycles; this legacy query remains advisory.</p>
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
    // Legacy advisory view of the bounded generation-time analysis
    // =========================================================================

    /**
     * Returns warning messages for every left-recursive cycle found in the
     * grammar. Both direct ({@code A ::= A ...}) and indirect
     * ({@code A ::= B ...; B ::= A ...}) cycles are reported.
     */
    static List<String> detectLeftRecursion(GrammarDecl grammar) {
        List<String> warnings = new ArrayList<>();
        for (String cycleKey : detectLeftRecursionCycles(grammar)) {
            warnings.add(cycleKey.startsWith("analysis limit:")
                ? "W-LEFT-RECURSION-LIMIT: " + cycleKey
                : "W-LEFT-RECURSION: left-recursive cycle detected: " + cycleKey);
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
                cycleKey.startsWith("analysis limit:") ? "W-LEFT-RECURSION-LIMIT" : "W-LEFT-RECURSION",
                cycleKey.startsWith("analysis limit:") ? cycleKey : "left-recursive cycle detected: " + cycleKey,
                "Rewrite using repetition (e.g. A ::= B { Op B }) or right recursion.",
                firstRule
            ));
        }
        return List.copyOf(issues);
    }

    private static List<String> detectLeftRecursionCycles(GrammarDecl grammar) {
        try { return LeftRecursionAnalysis.cycles(grammar); }
        catch (IllegalArgumentException limit) {
            // Legacy advisory API remains nonthrowing; generation rejects the same limit.
            return List.of("analysis limit: " + limit.getMessage());
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

        Set<String> constraintCaptures = TypeofConstraints.captures(rule);
        for (String capture : captures) {
            if (!paramSet.contains(capture) && !constraintCaptures.contains(capture)) {
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
        if (grammar.settings().stream().filter(s -> "whitespace".equals(s.key())).count() > 1) {
            addError(errors, "duplicate global @whitespace settings",
                "Keep a single global @whitespace setting.", "E-WHITESPACE-GLOBAL-DUPLICATE");
        }
        grammar.settings().stream()
            .filter(s -> "whitespace".equals(s.key()))
            .forEach(s -> {
                if (s.value() instanceof StringSettingValue sv) {
                    String style = sv.value().trim();
                    if (!validWhitespace(grammar, style)) {
                        addError(errors,
                            "global @whitespace style must be javaStyle, none, or a non-nullable declarative token: " + style,
                            "Use javaStyle, none, or the exact name of a non-nullable declarative token.",
                            "E-WHITESPACE-GLOBAL-STYLE");
                    }
                } else {
                    addError(errors, "global @whitespace requires a style name",
                        "Use javaStyle, none, or the exact name of a non-nullable declarative token.",
                        "E-WHITESPACE-GLOBAL-STYLE");
                }
            });
    }

    /**
     * UBNF 2 makes external token contracts part of the grammar. Older grammars
     * remain valid and are interpreted as format 1 for source compatibility.
     */
    private static void validateUbnfFormat(GrammarDecl grammar, List<ValidationIssue> errors) {
        List<org.unlaxer.dsl.bootstrap.UBNFAST.GlobalSetting> versions = grammar.settings().stream()
            .filter(setting -> "ubnf".equals(setting.key())).toList();
        if (versions.size() > 1) {
            addError(errors, "duplicate global @ubnf setting",
                "Keep exactly one '@ubnf: v2' declaration.", "E-UBNF-VERSION-DUPLICATE");
            return;
        }
        if (versions.isEmpty()) return;
        if (!(versions.get(0).value() instanceof StringSettingValue value)
            || !(value.value().equals("v1") || value.value().equals("v2"))) {
            addError(errors, "@ubnf must be format version v1 or v2",
                "Use '@ubnf: v2' for context-aware external token contracts.", "E-UBNF-VERSION");
        }
    }

    private static void validateMemoSafeTokens(GrammarDecl grammar, List<ValidationIssue> errors) {
        Map<String, TokenDecl> tokens = new LinkedHashMap<>();
        for (TokenDecl token : grammar.tokens()) {
            tokens.putIfAbsent(token.name(), token);
        }
        Set<String> seen = new LinkedHashSet<>();
        grammar.settings().stream()
            .filter(setting -> "memoSafeToken".equals(setting.key()))
            .forEach(setting -> {
                if (!(setting.value() instanceof StringSettingValue value)) {
                    addError(errors,
                        "global @memoSafeToken requires a token alias string",
                        "Use one setting per alias, for example '@memoSafeToken: IDENTIFIER'.",
                        "E-MEMO-SAFE-TOKEN-VALUE");
                    return;
                }
                String alias = value.value().trim();
                if (!seen.add(alias)) {
                    addError(errors,
                        "duplicate global @memoSafeToken alias: " + alias,
                        "Keep a single @memoSafeToken setting for alias " + alias + ".",
                        "E-MEMO-SAFE-TOKEN-DUPLICATE");
                    return;
                }
                TokenDecl token = tokens.get(alias);
                if (token == null) {
                    addError(errors,
                        "global @memoSafeToken references undefined token alias: " + alias,
                        "Declare token " + alias + " or remove this @memoSafeToken setting.",
                        "E-MEMO-SAFE-TOKEN-UNDEFINED");
                } else if (!(token instanceof TokenDecl.Simple)
                    && !(token instanceof TokenDecl.Declarative)
                    && !(token instanceof TokenDecl.Adapter adapter && builtinAdapter(adapter))) {
                    addError(errors,
                        "global @memoSafeToken alias must name a Simple or built-in Adapter token: " + alias,
                        "Remove the setting or use a Simple or built-in Adapter token.",
                        "E-MEMO-SAFE-TOKEN-KIND");
                }
            });
    }

    private static boolean builtinAdapter(TokenDecl.Adapter adapter) {
        try {
            return TokenAdapterRegistry.isBuiltin(adapter.id(), Integer.parseInt(adapter.version()));
        } catch (NumberFormatException error) {
            return false;
        }
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

    /** Shared with direct parser generation, which otherwise retains its caller-validation contract. */
    static List<ValidationIssue> validateCaseInsensitiveTokens(GrammarDecl grammar) {
        List<ValidationIssue> errors = new ArrayList<>();
        for (TokenDecl token : grammar.tokens()) {
            if (token instanceof TokenDecl.CaseInsensitive ci && ci.word().isEmpty()) {
                addError(errors, "CI token " + ci.name() + " must not be empty",
                    "Use at least one Unicode code point in CI(...).", "E-CI-EMPTY");
            } else if (token instanceof TokenDecl.CaseInsensitive ci
                    && ci.word().codePoints().anyMatch(cp -> cp >= 0xd800 && cp <= 0xdfff)) {
                addError(errors, "CI token " + ci.name() + " contains an unpaired surrogate",
                    "Use Unicode scalar values in CI(...).", "E-CI-SCALAR");
            }
        }
        return errors;
    }

    private static void validateScalarRanges(GrammarDecl grammar, List<ValidationIssue> errors) {
        boolean formatTwo = grammar.settings().stream().anyMatch(setting -> setting.key().equals("ubnf")
            && setting.value() instanceof StringSettingValue version && version.value().equals("v2"));
        for (TokenDecl token : grammar.tokens()) {
            if (formatTwo && token instanceof TokenDecl.CharRange range
                    && range.min() <= 0xdfff && range.max() >= 0xd800) {
                addError(errors, "range must not contain surrogates", "Split the scalar range around the surrogate block.",
                    "E-TOKEN-RANGE-SURROGATE");
            }
        }
    }

    /**
     * Java alias subclasses cannot extend a known final class. This target-specific
     * probe never initializes classes; absence/linkage restrictions remain deployment
     * compile concerns. Pure validation and the Rust target do not run this probe.
     */
    public static List<ValidationIssue> validateJavaTokenBindings(GrammarDecl grammar) {
        var registry = TokenAdapterRegistry.build(grammar, null).registry();
        List<ValidationIssue> issues = new ArrayList<>();
        for (TokenDecl token : grammar.tokens()) {
            String className = null;
            if (token instanceof TokenDecl.Simple simple) className = simple.parserClass();
            else if (token instanceof TokenDecl.Adapter adapter) {
                try {
                    className = registry.find(adapter.id(), Integer.parseInt(adapter.version()))
                        .map(TokenAdapterRegistry.Descriptor::javaClass).orElse(null);
                } catch (NumberFormatException invalidVersion) {
                    continue; // Registry validation reports malformed declarations separately.
                }
            }
            // Short Simple names are used directly; no subclass alias is emitted.
            if (className == null || !className.contains(".")) continue;
            Class<?> target = resolveJavaBinding(className);
            if (target != null && java.lang.reflect.Modifier.isFinal(target.getModifiers())) {
                issues.add(new ValidationIssue("E-JAVA-TOKEN-FINAL",
                    "token " + token.name() + " cannot generate a Java alias extending final class " + className,
                    "Use a public nonfinal Parser with an accessible no-argument constructor for the Java target. Rust bindings are checked separately."));
            }
        }
        return List.copyOf(issues);
    }

    private static Class<?> resolveJavaBinding(String sourceName) {
        // Registry declarations use Java source names, including canonical nested names.
        String binaryName = sourceName;
        while (true) {
            try {
                Class<?> target = Class.forName(binaryName, false, GrammarValidator.class.getClassLoader());
                // A top-level class may literally contain '$'; it is not that nested source name.
                if (sourceName.equals(target.getCanonicalName())) return target;
            } catch (ClassNotFoundException unavailable) {
                // Try the next canonical member-class boundary below.
            } catch (LinkageError | SecurityException unavailable) {
                return null;
            }
            int lastDot = binaryName.lastIndexOf('.');
            if (lastDot < 0) return null;
            binaryName = binaryName.substring(0, lastDot) + "$" + binaryName.substring(lastDot + 1);
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
     * Detects names that make a token reference resolve to its surrounding rule parser.
     * Without this check the generated parser compiles, but recursively calls itself at
     * runtime instead of the declared token parser.
     */
    private static void validateRuleTokenParserNameCollisions(
        GrammarDecl grammar,
        List<ValidationIssue> errors
    ) {
        Map<String, String> ruleByParserName = new LinkedHashMap<>();
        for (RuleDecl rule : grammar.rules()) {
            ruleByParserName.put(rule.name() + "Parser", rule.name());
        }

        for (TokenDecl token : grammar.tokens()) {
            String generatedReference = generatedTokenParserClassName(token);
            if (generatedReference == null) {
                continue;
            }
            String collidingRule = ruleByParserName.get(generatedReference);
            if (collidingRule == null) {
                continue;
            }
            addRuleError(
                errors,
                collidingRule,
                "rule " + collidingRule + " and token " + token.name()
                    + " both resolve to generated parser class " + generatedReference,
                "Rename the rule or token so their generated parser class names differ.",
                "E-RULE-TOKEN-NAME-COLLISION"
            );
        }
    }

    /** An adapter wrapper can shadow another token's parser class or duplicate its wrapper. */
    private static void validateAdapterTokenParserNameCollisions(
        GrammarDecl grammar,
        List<ValidationIssue> errors
    ) {
        Map<String, TokenDecl> firstByParserName = new LinkedHashMap<>();
        for (TokenDecl token : grammar.tokens()) {
            String parserName = tokenParserClassReference(token);
            if (parserName == null) continue;
            TokenDecl first = firstByParserName.putIfAbsent(parserName, token);
            if (first == null || !(first instanceof TokenDecl.Adapter || token instanceof TokenDecl.Adapter
                    || first instanceof TokenDecl.Declarative || token instanceof TokenDecl.Declarative)) {
                continue;
            }
            addError(errors,
                "token " + first.name() + " and token " + token.name()
                    + " both resolve to parser class " + parserName,
                "Rename one token or use a distinct parser class so the adapter wrapper cannot shadow it.",
                "E-TOKEN-ADAPTER-NAME-COLLISION");
        }
    }

    private static String tokenParserClassReference(TokenDecl token) {
        if (token instanceof TokenDecl.Declarative || token instanceof TokenDecl.Adapter || token instanceof TokenDecl.Simple
            || token instanceof TokenDecl.Negation || token instanceof TokenDecl.CharRange
            || token instanceof TokenDecl.Regex) {
            return generatedTokenParserClassName(token);
        }
        return null;
    }

    /**
     * Returns the generated parser class name a token declaration resolves to,
     * or {@code null} when the token does not produce a generated wrapper class
     * (e.g. {@code Any}, {@code Eof}, {@code Empty}) or the reference is blank.
     *
     * <p>For {@link TokenDecl.Simple} the user-supplied short class name is kept
     * when present (mirroring {@link ParserTokenEmitter}); all other kinds —
     * {@code Negation}, {@code CharRange}, {@code Regex}, {@code Until},
     * {@code CaseInsensitive}, … — always derive their class name from the
     * token name via {@link ParserCodegenUtil#toParserClassName}.
     */
    private static String generatedTokenParserClassName(TokenDecl token) {
        if (token instanceof TokenDecl.Adapter || token instanceof TokenDecl.Declarative)
            return ParserCodegenUtil.toParserClassName(token.name());
        if (token instanceof TokenDecl.Simple simple) {
            String parserClass = simple.parserClass();
            if (parserClass == null || parserClass.isBlank()) {
                return null;
            }
            return parserClass.contains(".")
                ? ParserCodegenUtil.toParserClassName(simple.name())
                : parserClass;
        }
        // Non-Simple tokens that emit a generated class all use the token-name-derived
        // class name. Until / CaseInsensitive / Negation / CharRange / Regex fall here.
        if (token instanceof TokenDecl.Until ignored) {
            return ParserCodegenUtil.toParserClassName(token.name());
        }
        if (token instanceof TokenDecl.Negation ignored) {
            return ParserCodegenUtil.toParserClassName(token.name());
        }
        if (token instanceof TokenDecl.CharRange ignored) {
            return ParserCodegenUtil.toParserClassName(token.name());
        }
        if (token instanceof TokenDecl.Regex ignored) {
            return ParserCodegenUtil.toParserClassName(token.name());
        }
        if (token instanceof TokenDecl.CaseInsensitive ignored) {
            return ParserCodegenUtil.toParserClassName(token.name());
        }
        return null;
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

    private static boolean validWhitespace(GrammarDecl grammar, String style) {
        try {
            org.unlaxer.dsl.bootstrap.WhitespaceDefinitions.resolve(style, org.unlaxer.dsl.bootstrap.LexicalCompiler.compile(grammar));
            return true;
        } catch (IllegalArgumentException exception) { return false; }
    }

    private static void validateRuleWhitespace(GrammarDecl grammar, RuleDecl rule, WhitespaceAnnotation w, List<ValidationIssue> errors) {
        String style = w.style().orElse("javaStyle").trim();
        if (!validWhitespace(grammar, style)) {
            addRuleError(errors, rule.name(),
                "rule " + rule.name() + " uses unsupported @whitespace style: " + style
                    + " (allowed: javaStyle, none, non-nullable declarative token)",
                "Use @whitespace, @whitespace(none), or @whitespace(TOKEN_NAME).",
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
            if (org.unlaxer.dsl.bootstrap.PrecedenceLevels.overflow(p)) {
                addRuleError(errors, rule.name(), org.unlaxer.dsl.bootstrap.PrecedenceLevels.message(p),
                    "Use a level between 0 and 2147483647.", org.unlaxer.dsl.bootstrap.PrecedenceLevels.CODE);
            } else if (p.level() < 0) {
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

    private static void validateScopeCaptures(RuleDecl rule, List<ValidationIssue> errors) {
        var declarations = rule.annotations().stream().filter(DeclaresAnnotation.class::isInstance)
            .map(DeclaresAnnotation.class::cast).toList();
        if (declarations.size() > 1) {
            addRuleError(errors, rule.name(), "rule " + rule.name() + " has duplicate @declares annotations",
                "Keep a single @declares(symbol=...) annotation.", "E-ANNOTATION-DUPLICATE-DECLARES");
        }
        Set<String> captures = collectCaptureNames(rule.body());
        for (Annotation annotation : rule.annotations()) {
            String target;
            String code;
            if (annotation instanceof DeclaresAnnotation declaration) {
                target = declaration.symbolCapture();
                code = "E-ANNOTATION-DECLARES-CAPTURE";
            } else if (annotation instanceof BackrefAnnotation reference) {
                target = reference.name();
                code = "E-ANNOTATION-BACKREF-CAPTURE";
            } else {
                continue;
            }
            if (!captures.contains(target)) {
                addRuleError(errors, rule.name(), "rule " + rule.name() + " has no scope capture: " + target,
                    "Capture the target in this rule's body using @" + target + ".", code);
            }
        }
    }

    private static void validatePrecedenceTopology(GrammarDecl grammar, List<ValidationIssue> errors) {
        var ruleMap = grammar.rules().stream()
            .collect(java.util.stream.Collectors.toMap(RuleDecl::name, r -> r, (a, b) -> a));

        for (RuleDecl rule : grammar.rules()) {
            Integer precedence = findPrecedenceLevel(rule);
            // E-PRECEDENCE-OVERFLOW owns an out-of-range level; never compare the sentinel (#802).
            if (precedence == null || org.unlaxer.dsl.bootstrap.PrecedenceLevels.overflow(precedence) || !hasAssoc(rule)) {
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
                if (refPrecedence == null || org.unlaxer.dsl.bootstrap.PrecedenceLevels.overflow(refPrecedence)) {
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
            if (org.unlaxer.dsl.bootstrap.PrecedenceLevels.overflow(level)) {
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
        try { EnumSchema.validate(grammar); }
        catch (IllegalArgumentException error) {
            addError(errors, error.getMessage(), "Use distinct nonempty literal alternatives and valid enum names.", "E-ENUM");
        }
    }

    private static void validateCommonFields(GrammarDecl grammar, List<ValidationIssue> errors) {
        try {
            CommonFieldSchema.build(grammar);
        } catch (CommonFieldSchema.SchemaException error) {
            String message = error.getMessage();
            String rule = error.rule;
            addRuleError(
                    errors,
                    rule,
                    message,
                    "Align family fields and cardinalities.",
                    "E-COMMONFIELD-MISMATCH");
        }
    }

    /** Validate repeat definitions even when the repeated child will never execute. */
    public static void requireValidBoundedRepeatElements(GrammarDecl grammar) {
        var errors = new ArrayList<ValidationIssue>();
        validateBoundedRepeatElements(grammar, errors);
        if (!errors.isEmpty()) {
            var error = errors.get(0);
            throw new IllegalArgumentException(error.code() + ": " + error.message());
        }
    }

    private static void validateBoundedRepeatElements(GrammarDecl grammar, List<ValidationIssue> errors) {
        for (RuleDecl rule : grammar.rules()) {
            collectBoundedElements(rule.body()).forEach(bounded -> {
                if (org.unlaxer.dsl.bootstrap.BoundedQuantifiers.overflow(bounded)) {
                    addRuleError(errors, rule.name(),
                        org.unlaxer.dsl.bootstrap.BoundedQuantifiers.message(bounded),
                        "Use bounds between 0 and 2147483647.", "E-BOUNDED-OVERFLOW");
                } else if (bounded.min() < 0 || bounded.max() < bounded.min()) {
                    addRuleError(errors, rule.name(),
                        "rule " + rule.name() + " has BoundedRepeatElement with min=" + bounded.min()
                            + ", max=" + bounded.max(),
                        "Ensure 0 <= min <= max in {min,max} quantifier.",
                        "E-BOUNDED-INVALID");
                }
            });
        }
    }

    private static java.util.stream.Stream<UBNFAST.BoundedRepeatElement> collectBoundedElements(UBNFAST.RuleBody body) {
        var pending = new java.util.ArrayDeque<UBNFAST>();
        var bounded = new ArrayList<UBNFAST.BoundedRepeatElement>();
        pending.push(body);
        // Iterative traversal also validates nested definitions under dead zero-count bodies.
        while (!pending.isEmpty()) {
            UBNFAST current = pending.pop();
            if (current instanceof UBNFAST.ChoiceBody choice) {
                var alternatives = choice.alternatives();
                for (int i = alternatives.size() - 1; i >= 0; i--) pending.push(alternatives.get(i));
            } else if (current instanceof UBNFAST.SequenceBody sequence) {
                var elements = sequence.elements();
                for (int i = elements.size() - 1; i >= 0; i--) pending.push(elements.get(i).element());
            } else if (current instanceof UBNFAST.BoundedRepeatElement repeat) {
                bounded.add(repeat);
                pending.push(repeat.body());
            } else if (current instanceof UBNFAST.TransparentElement group) {
                pending.push(group.body());
            } else if (current instanceof UBNFAST.OptionalElement optional) {
                pending.push(optional.body());
            } else if (current instanceof UBNFAST.RepeatElement repeat) {
                pending.push(repeat.body());
            } else if (current instanceof UBNFAST.OneOrMoreElement repeat) {
                pending.push(repeat.body());
            } else if (current instanceof UBNFAST.SeparatedElement separated) {
                pending.push(separated.separator());
                pending.push(separated.element());
            }
        }
        return bounded.stream();
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
        if (body instanceof ChoiceBody choice) {
            for (SequenceBody seq : choice.alternatives()) {
                collectCaptureNamesFromSequence(seq, captures);
            }
        } else if (body instanceof SequenceBody seq) {
            collectCaptureNamesFromSequence(seq, captures);
        } else {
            throw new IllegalStateException("unhandled " + body);
        }
    }

    private static void collectCaptureNamesFromSequence(SequenceBody seq, Set<String> captures) {
        for (AnnotatedElement ae : seq.elements()) {
            ae.captureName().ifPresent(captures::add);
            collectCaptureNamesFromAtomic(ae.element(), captures);
        }
    }

    private static void collectCaptureNamesFromAtomic(AtomicElement element, Set<String> captures) {
        if (element instanceof TransparentElement group) {
            collectCaptureNamesFromBody(group.body(), captures);
        } else if (element instanceof OptionalElement opt) {
            collectCaptureNamesFromBody(opt.body(), captures);
        } else if (element instanceof RepeatElement rep) {
            collectCaptureNamesFromBody(rep.body(), captures);
        } else if (element instanceof UBNFAST.OneOrMoreElement rep) {
            collectCaptureNamesFromAtomic(rep.body(), captures);
        } else if (element instanceof UBNFAST.BoundedRepeatElement rep) {
            collectCaptureNamesFromAtomic(rep.body(), captures);
        } else if (element instanceof UBNFAST.SeparatedElement separated) {
            collectCaptureNamesFromAtomic(separated.element(), captures);
            collectCaptureNamesFromAtomic(separated.separator(), captures);
        } else {
            // TerminalElement / RuleRefElement have no nested bodies.
        }
    }

    private static Set<String> collectReferencedRuleNames(RuleBody body) {
        Set<String> refs = new LinkedHashSet<>();
        collectReferencedRuleNamesFromBody(body, refs);
        return refs;
    }

    private static void collectReferencedRuleNamesFromBody(RuleBody body, Set<String> refs) {
        if (body instanceof ChoiceBody choice) {
            for (SequenceBody seq : choice.alternatives()) {
                collectReferencedRuleNamesFromSequence(seq, refs);
            }
        } else if (body instanceof SequenceBody seq) {
            collectReferencedRuleNamesFromSequence(seq, refs);
        } else {
            throw new IllegalStateException("unhandled " + body);
        }
    }

    private static void collectReferencedRuleNamesFromSequence(SequenceBody seq, Set<String> refs) {
        for (AnnotatedElement ae : seq.elements()) {
            collectReferencedRuleNamesFromAtomic(ae.element(), refs);
        }
    }

    private static void collectReferencedRuleNamesFromAtomic(AtomicElement element, Set<String> refs) {
        if (element instanceof RuleRefElement ref) {
            // 名前空間付き参照 (alias.RuleName) はそのまま追加
            String fullName = ref.namespace()
                .map(ns -> ns + "." + ref.name())
                .orElse(ref.name());
            refs.add(fullName);
        } else if (element instanceof TransparentElement group) {
            collectReferencedRuleNamesFromBody(group.body(), refs);
        } else if (element instanceof OptionalElement opt) {
            collectReferencedRuleNamesFromBody(opt.body(), refs);
        } else if (element instanceof RepeatElement rep) {
            collectReferencedRuleNamesFromBody(rep.body(), refs);
        } else {
            // TerminalElement has no nested refs.
        }
    }

    private static boolean containsRepeat(RuleBody body) {
        if (body instanceof ChoiceBody choice) {
            return choice.alternatives().stream()
                .anyMatch(GrammarValidator::containsRepeatInSequence);
        }
        if (body instanceof SequenceBody seq) {
            return containsRepeatInSequence(seq);
        }
        throw new IllegalStateException("unhandled " + body);
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
        if (element instanceof RepeatElement rep) {
            return true;
        }
        if (element instanceof TransparentElement group) {
            return containsRepeat(group.body());
        }
        if (element instanceof OptionalElement opt) {
            return containsRepeat(opt.body());
        }
        return false;
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
        if (body instanceof SequenceBody seq) {
            return seq;
        }
        if (body instanceof ChoiceBody choice && choice.alternatives().size() == 1) {
            return choice.alternatives().get(0);
        }
        return null;
    }

    // =========================================================================
    // @typeof バリデーション
    // =========================================================================

    private static void validateTypeofElements(RuleDecl rule, List<ValidationIssue> errors) {
        for (var problem : TypeofConstraints.problems(rule)) {
            addRuleError(errors, rule.name(), problem.message(),
                "Use named captures in the same mapped rule; forward references are allowed.", problem.code());
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
        validateUndefinedRuleRefs(grammar, errors, false);
    }

    private static void validateUndefinedRuleRefs(GrammarDecl grammar, List<ValidationIssue> errors, boolean rustDescription) {
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
                        rustDescription ? "unknown reference " + refName : "rule " + rule.name() + " references undefined rule or token '"
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
