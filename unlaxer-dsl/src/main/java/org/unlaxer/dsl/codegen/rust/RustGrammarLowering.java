package org.unlaxer.dsl.codegen.rust;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.unlaxer.dsl.bootstrap.UBNFAST.*;
import org.unlaxer.dsl.codegen.rust.GrammarIR.*;

/** Validates the entire input before emitting anything; unsupported syntax never silently degrades. */
public final class RustGrammarLowering {
    private static final int MAX_PREDICTOR_ATOMS = 64;
    private final GrammarDecl grammar;
    private final Map<String, Integer> ruleIds = new LinkedHashMap<>();
    private final Map<String, Expression> tokens = new LinkedHashMap<>();
    private final List<Expression> bodies = new ArrayList<>();
    private final List<MappingAnnotation> mappings = new ArrayList<>();
    private final List<Operator> operators = new ArrayList<>();
    private final List<String> catalogs = new ArrayList<>();
    private final List<Boolean> longestChoices = new ArrayList<>();
    private final List<Boolean> predictiveChoices = new ArrayList<>();
    private final Set<Integer> nullableRules = new HashSet<>();
    private record Shape(Kind kind, Cardinality cardinality) {}

    private RustGrammarLowering(GrammarDecl grammar) { this.grammar = grammar; }

    public static GrammarIR lower(GrammarDecl grammar) {
        return new RustGrammarLowering(grammar).run();
    }

    private GrammarIR run() {
        if (!grammar.imports().isEmpty()) throw unsupported("imports");
        boolean whitespace = false;
        Set<String> settings = new HashSet<>();
        Set<String> memoSafeTokens = new HashSet<>();
        for (var setting : grammar.settings()) {
            if (!setting.key().equals("memoSafeToken") && !settings.add(setting.key())) {
                throw unsupported("duplicate setting " + setting.key());
            }
            if (!(setting.value() instanceof StringSettingValue value)) {
                if (setting.key().equals("memoSafeToken")) {
                    throw unsupported("memoSafeToken requires token alias string");
                }
                throw unsupported("block setting " + setting.key());
            }
            if (setting.key().equals("whitespace")) {
                whitespace = whitespaceStyle(value.value());
            } else if (setting.key().equals("memoSafeToken")) {
                String alias = value.value().trim();
                if (!memoSafeTokens.add(alias)) {
                    throw unsupported("duplicate memoSafeToken alias " + alias);
                }
                TokenDecl token = grammar.tokens().stream()
                    .filter(candidate -> candidate.name().equals(alias))
                    .findFirst().orElseThrow(() -> unsupported("memoSafeToken undefined token alias " + alias));
                if (!(token instanceof TokenDecl.Simple)) {
                    throw unsupported("memoSafeToken alias must name a Simple token " + alias);
                }
            } else if (!setting.key().equals("package")) throw unsupported("setting " + setting.key());
        }
        for (var token : grammar.tokens()) {
            if (tokens.putIfAbsent(token.name(), token(token)) != null) throw unsupported("duplicate token " + token.name());
        }
        int root = -1;
        boolean hasScope = grammar.rules().stream().flatMap(rule -> rule.annotations().stream())
            .anyMatch(annotation -> annotation instanceof ScopeTreeAnnotation);
        List<Effects> ruleEffects = new ArrayList<>();
        List<Boolean> ruleWhitespace = new ArrayList<>();
        boolean hasLocalTrivia = false;
        Map<String, String> methods = new LinkedHashMap<>();
        for (int i = 0; i < grammar.rules().size(); i++) {
            var rule = grammar.rules().get(i);
            if (ruleIds.putIfAbsent(rule.name(), i) != null || tokens.containsKey(rule.name())) {
                throw unsupported("duplicate rule/token " + rule.name());
            }
            MappingAnnotation mapping = null;
            boolean leftAssoc = false;
            boolean rightAssoc = false;
            boolean longestChoice = false;
            boolean predictiveChoice = false;
            Integer precedence = null;
            Boolean localWhitespace = null;
            boolean interleave = false;
            String catalog = null;
            ScopeMode scopeMode = null;
            Declaration declares = null;
            String backref = null;
            for (var annotation : rule.annotations()) {
                if (annotation instanceof RootAnnotation) {
                    if (root != -1) throw unsupported("multiple @root annotations");
                    root = i;
                } else if (annotation instanceof MappingAnnotation value) {
                    if (mapping != null) throw unsupported("multiple @mapping on " + rule.name());
                    identifier(value.className());
                    String previous = methods.putIfAbsent(methodName(value.className()), value.className());
                    if (previous != null && !previous.equals(value.className())) {
                        throw unsupported("mapping method collision " + previous + " / " + value.className());
                    }
                    mapping = value;
                } else if (annotation instanceof LeftAssocAnnotation) {
                    if (leftAssoc || rightAssoc) throw unsupported("duplicate/conflicting associativity on " + rule.name());
                    leftAssoc = true;
                } else if (annotation instanceof RightAssocAnnotation) {
                    if (leftAssoc || rightAssoc) throw unsupported("duplicate/conflicting associativity on " + rule.name());
                    rightAssoc = true;
                } else if (annotation instanceof LongestChoiceAnnotation) {
                    if (longestChoice) throw unsupported("duplicate @longestChoice on " + rule.name());
                    longestChoice = true;
                } else if (annotation instanceof PredictiveChoiceAnnotation) {
                    if (predictiveChoice) throw unsupported("duplicate @predictiveChoice on " + rule.name());
                    predictiveChoice = true;
                } else if (annotation instanceof PrecedenceAnnotation value) {
                    if (precedence != null) throw unsupported("duplicate @precedence on " + rule.name());
                    precedence = value.level();
                } else if (annotation instanceof WhitespaceAnnotation value) {
                    if (localWhitespace != null) throw unsupported("duplicate @whitespace on " + rule.name());
                    localWhitespace = whitespaceStyle(value.style().orElse("javaStyle"));
                } else if (annotation instanceof InterleaveAnnotation value) {
                    if (interleave) throw unsupported("duplicate @interleave on " + rule.name());
                    String profile = value.profile().trim();
                    if (!profile.equals("javaStyle") && !profile.equals("commentsAndSpaces")) {
                        throw unsupported("interleave profile " + value.profile());
                    }
                    interleave = true;
                } else if (annotation instanceof ScopeTreeAnnotation value) {
                    if (scopeMode != null) throw unsupported("duplicate @scopeTree on " + rule.name());
                    scopeMode = switch (value.mode().trim()) {
                        case "lexical" -> ScopeMode.LEXICAL;
                        case "dynamic" -> ScopeMode.DYNAMIC;
                        default -> throw unsupported("scopeTree mode " + value.mode());
                    };
                } else if (annotation instanceof DeclaresAnnotation value) {
                    if (declares != null) throw unsupported("duplicate @declares on " + rule.name());
                    declares = new Declaration(value.symbolCapture(), value.description());
                } else if (annotation instanceof BackrefAnnotation value) {
                    if (!hasScope) throw unsupported("@backref without @scopeTree");
                    if (backref != null) throw unsupported("duplicate @backref on " + rule.name());
                    backref = value.name();
                } else if (annotation instanceof CatalogAnnotation value) {
                    if (catalog != null) throw unsupported("duplicate @catalog on " + rule.name());
                    catalog = value.context();
                } else throw unsupported("annotation " + annotation + " on " + rule.name());
            }
            ruleEffects.add(scopeMode == null && declares == null && backref == null ? null
                : new Effects(scopeMode, declares, backref));
            if (longestChoice && (!(rule.body() instanceof ChoiceBody choice)
                || choice.alternatives().size() < 2)) {
                throw unsupported("@longestChoice requires multiple alternatives on " + rule.name());
            }
            if (longestChoice && (leftAssoc || rightAssoc)) {
                throw unsupported("@longestChoice conflicts with associativity on " + rule.name());
            }
            if (predictiveChoice && (!(rule.body() instanceof ChoiceBody choice)
                || choice.alternatives().size() < 2)) {
                throw unsupported("@predictiveChoice requires multiple alternatives on " + rule.name());
            }
            if (predictiveChoice && (leftAssoc || rightAssoc || longestChoice)) {
                throw unsupported("@predictiveChoice conflicts with associativity/@longestChoice on " + rule.name());
            }
            longestChoices.add(longestChoice);
            predictiveChoices.add(predictiveChoice);
            hasLocalTrivia |= localWhitespace != null || interleave;
            ruleWhitespace.add(localWhitespace == null ? whitespace || interleave : localWhitespace);
            mappings.add(mapping);
            operators.add(leftAssoc || rightAssoc || precedence != null
                ? new Operator(leftAssoc ? Associativity.LEFT : rightAssoc ? Associativity.RIGHT : Associativity.NONE, precedence == null ? -1 : precedence)
                : null);
            catalogs.add(catalog);
        }
        if (root == -1) throw unsupported("exactly one @root is required");
        for (var rule : grammar.rules()) bodies.add(body(rule.body()));
        boolean changed;
        do {
            changed = false;
            for (int i = 0; i < bodies.size(); i++) if (nullable(bodies.get(i))) changed |= nullableRules.add(i);
        } while (changed);
        bodies.forEach(this::checkRepetition);
        for (int i = 0; i < bodies.size(); i++) checkLeftRecursion(i, new HashSet<>(), new HashSet<>());
        if (!shape(root, new HashSet<>()).equals(new Shape(Kind.NODE, Cardinality.ONE))) {
            throw unsupported("root must resolve to exactly one AST node");
        }
        List<Rule> rules = new ArrayList<>();
        Map<String, Mapping> variants = new LinkedHashMap<>();
        for (int i = 0; i < bodies.size(); i++) {
            Map<String, Shape> captures = captures(bodies.get(i));
            Effects effects = ruleEffects.get(i);
            if (effects != null) {
                if (effects.declares() != null && !captures.containsKey(effects.declares().symbolCapture())) {
                    throw unsupported("missing rule-effect capture " + effects.declares().symbolCapture());
                }
                if (effects.backref() != null && !captures.containsKey(effects.backref())) {
                    throw unsupported("missing rule-effect capture " + effects.backref());
                }
            }
            var annotation = mappings.get(i);
            Catalog catalog = null;
            if (catalogs.get(i) != null) {
                if (captures.isEmpty()) throw unsupported("@catalog requires at least one capture on " + grammar.rules().get(i).name());
                catalog = new Catalog(catalogs.get(i), captures.keySet().stream().sorted().toList());
            }
            Mapping mapping = null;
            if (annotation == null) {
                if (!captures.isEmpty() && effects == null && catalog == null) throw unsupported("captures without @mapping on " + grammar.rules().get(i).name());
            } else {
                if (new HashSet<>(annotation.paramNames()).size() != annotation.paramNames().size()
                    || !captures.keySet().equals(new HashSet<>(annotation.paramNames()))) {
                    throw unsupported("@mapping params must match capture names on " + grammar.rules().get(i).name());
                }
                List<Field> fields = new ArrayList<>();
                for (String name : annotation.paramNames()) {
                    identifier(name);
                    if (Set.of("span", "semantics").contains(name)) throw unsupported("capture name " + name + " is reserved");
                    fields.add(new Field(name, captures.get(name).kind(), captures.get(name).cardinality()));
                }
                mapping = new Mapping(annotation.className(), fields);
            }
            if (operators.get(i) != null && operators.get(i).associativity() == Associativity.LEFT) {
                checkLeftAssoc(i, mapping);
            }
            if (operators.get(i) != null && operators.get(i).associativity() == Associativity.RIGHT) {
                lowerRightAssoc(i, mapping);
            }
            if (mapping != null) variants.merge(mapping.name(), mapping, this::mergeMappings);
            Expression ruleBody = bodies.get(i);
            if (longestChoices.get(i)) {
                ruleBody = new LongestChoice(((Choice) ruleBody).alternatives());
            } else if (predictiveChoices.get(i)) {
                List<Expression> alternatives = ((Choice) ruleBody).alternatives();
                Map<Integer, Predictor> predictorCache = new LinkedHashMap<>();
                ruleBody = new PredictiveChoice(alternatives, alternatives.stream()
                    .map(alternative -> firstPredictor(alternative, new HashSet<>(), predictorCache)).toList());
            }
            rules.add(new Rule(grammar.rules().get(i).name(), ruleBody, mapping, operators.get(i), catalog));
        }
        List<Rule> rewritten = new ArrayList<>();
        boolean hasValues = variants.values().stream().flatMap(mapping -> mapping.fields().stream())
            .anyMatch(field -> field.kind() == Kind.VALUE);
        for (int i = 0; i < rules.size(); i++) {
            Rule rule = rules.get(i);
            Mapping mapping = rule.mapping() == null ? null : variants.get(rule.mapping().name());
            Expression expression = hasValues
                ? mapping == null ? retainHelperValue(rule.body()) : retainTextValues(rule.body(), mapping)
                : rule.body();
            // Rewrite only after source-level shapes have been analyzed: this synthetic choice
            // is not a heterogeneous source choice and must not introduce text sentinels.
            if (rule.operator() != null && rule.operator().associativity() == Associativity.RIGHT) {
                expression = rightAssocBody(expression);
            }
            // Every rule resolves against the grammar default, never against its caller.
            if (hasLocalTrivia) expression = new TriviaScope(expression, ruleWhitespace.get(i));
            if (ruleEffects.get(i) != null) expression = new RuleEffects(expression, ruleEffects.get(i));
            rewritten.add(new Rule(rule.name(), expression, mapping, rule.operator(), rule.catalog()));
        }
        return new GrammarIR(rewritten, root, whitespace);
    }

    private static boolean whitespaceStyle(String style) {
        String normalized = style.trim();
        if (normalized.equalsIgnoreCase("javaStyle")) return true;
        if (normalized.equalsIgnoreCase("none")) return false;
        throw unsupported("whitespace " + style);
    }

    private Mapping mergeMappings(Mapping left, Mapping right) {
        if (left.fields().size() != right.fields().size()) {
            throw unsupported("incompatible shared mapping schema " + left.name());
        }
        List<Field> fields = new ArrayList<>();
        for (int i = 0; i < left.fields().size(); i++) {
            Field a = left.fields().get(i);
            Field b = right.fields().get(i);
            if (!a.name().equals(b.name()) || a.cardinality() != b.cardinality()) {
                throw unsupported("incompatible shared mapping schema " + left.name());
            }
            fields.add(new Field(a.name(), joinKind(a.kind(), b.kind()), a.cardinality()));
        }
        return new Mapping(left.name(), fields);
    }

    /** Keep scalar helper/item delimiters, without collapsing a many-valued helper. */
    private Expression retainHelperValue(Expression expression) {
        Expression projected = retainTextValues(expression, null);
        Shape shape = shape(expression, new HashSet<>());
        return shape.kind() == Kind.VALUE && shape.cardinality() != Cardinality.MANY
            ? new ValueBoundary(projected) : projected;
    }

    private Expression retainTextValues(Expression expression, Mapping mapping) {
        if (expression instanceof TextValue ignored) {
            return expression;
        }
        if (expression instanceof Capture capture) {
            Expression child = retainTextValues(capture.expression(), mapping);
            if (mapping != null && mapping.fields().stream().anyMatch(field ->
                    field.name().equals(capture.name()) && field.kind() == Kind.VALUE)) {
                Shape shape = shape(capture.expression(), new HashSet<>());
                if (shape.kind() == Kind.TEXT) child = new TextValue(child);
                else if (shape.kind() == Kind.VALUE && shape.cardinality() != Cardinality.MANY) {
                    child = new ValueBoundary(child);
                }
            }
            return new Capture(capture.name(), child);
        }
        if (expression instanceof Choice choice) {
            boolean mixed = shape(choice, new HashSet<>()).kind() == Kind.VALUE;
            return new Choice(choice.alternatives().stream().map(alternative -> {
                Expression child = retainTextValues(alternative, mapping);
                return mixed && shape(alternative, new HashSet<>()).kind() == Kind.TEXT
                    ? new TextValue(child) : child;
            }).toList());
        }
        if (expression instanceof LongestChoice choice) {
            boolean mixed = shape(new Choice(choice.alternatives()), new HashSet<>()).kind() == Kind.VALUE;
            return new LongestChoice(choice.alternatives().stream().map(alternative -> {
                Expression child = retainTextValues(alternative, mapping);
                return mixed && shape(alternative, new HashSet<>()).kind() == Kind.TEXT
                    ? new TextValue(child) : child;
            }).toList());
        }
        if (expression instanceof PredictiveChoice choice) {
            boolean mixed = shape(new Choice(choice.alternatives()), new HashSet<>()).kind() == Kind.VALUE;
            return new PredictiveChoice(choice.alternatives().stream().map(alternative -> {
                Expression child = retainTextValues(alternative, mapping);
                return mixed && shape(alternative, new HashSet<>()).kind() == Kind.TEXT
                    ? new TextValue(child) : child;
            }).toList(), choice.predictors());
        }
        if (expression instanceof Sequence sequence) {
            return new Sequence(sequence.elements().stream()
                .map(child -> retainTextValues(child, mapping)).toList());
        }
        if (expression instanceof Delimited delimited) {
            return new Delimited(retainTextValues(delimited.child(), mapping));
        }
        if (expression instanceof OptionalExpr optional) {
            return new OptionalExpr(mapping == null
                ? retainHelperValue(optional.child()) : retainTextValues(optional.child(), mapping));
        }
        if (expression instanceof Repeat repeat) {
            return new Repeat(mapping == null
                ? retainHelperValue(repeat.child()) : retainTextValues(repeat.child(), mapping), repeat.min(), repeat.max());
        }
        if (expression instanceof Separated separated) {
            return new Separated(mapping == null
                ? retainHelperValue(separated.child()) : retainTextValues(separated.child(), mapping),
                retainTextValues(separated.separator(), mapping));
        }
        return expression;
    }

    private Expression rightAssocBody(Expression expression) {
        Sequence sequence = (Sequence) expression;
        Expression left = sequence.elements().get(0);
        Sequence tail = (Sequence) ((Repeat) sequence.elements().get(1)).child();
        return new Choice(List.of(new Sequence(List.of(left, tail.elements().get(0), tail.elements().get(1))), left));
    }

    private void checkLeftAssoc(int rule, Mapping mapping) {
        if (mapping == null || !mapping.fields().stream().map(Field::name).toList().equals(List.of("left", "op", "right"))
            || !(bodies.get(rule) instanceof Sequence sequence) || sequence.elements().size() != 2
            || !(sequence.elements().get(0) instanceof Capture left) || !left.name().equals("left")
            || !(sequence.elements().get(1) instanceof Repeat repeat) || repeat.min() != 0 || repeat.max() != null
            || !(repeat.child() instanceof Sequence tail) || tail.elements().size() != 2
            || !(tail.elements().get(0) instanceof Capture op) || !op.name().equals("op")
            || !(tail.elements().get(1) instanceof Capture right) || !right.name().equals("right")
            || !captures(left.expression()).isEmpty() || !captures(op.expression()).isEmpty()
            || !captures(right.expression()).isEmpty()
            || mapping.fields().get(0).cardinality() != Cardinality.ONE
            || !mapping.fields().get(1).equals(new Field("op", Kind.TEXT, Cardinality.MANY))
            || mapping.fields().get(2).cardinality() != Cardinality.MANY
            || mapping.fields().get(0).kind() != mapping.fields().get(2).kind()
            || shape(right.expression(), new HashSet<>()).cardinality() != Cardinality.ONE) {
            throw unsupported("@leftAssoc requires left { op right } with scalar operands and params=[left, op, right] on "
                + grammar.rules().get(rule).name());
        }
    }

    /** Preserve the declared vector fields, but parse at most one recursive right operand. */
    private Expression lowerRightAssoc(int rule, Mapping mapping) {
        // Preserve the syntax boundary used by Java's canonical parser rewrite.
        final SequenceBody declared;
        var switchSubject = grammar.rules().get(rule).body();
        if (switchSubject instanceof SequenceBody sequence) {
            declared = sequence;
        } else if (switchSubject instanceof ChoiceBody choice && choice.alternatives().size() == 1) {
            declared = choice.alternatives().get(0);
        } else {
            declared = null;
        }
        if (declared == null || declared.elements().size() != 2
            || !(declared.elements().get(1).element() instanceof RepeatElement)
            || mapping == null || !mapping.fields().stream().map(Field::name).toList().equals(List.of("left", "op", "right"))
            || !(bodies.get(rule) instanceof Sequence sequence) || sequence.elements().size() != 2
            || !(sequence.elements().get(0) instanceof Capture left) || !left.name().equals("left")
            || !(sequence.elements().get(1) instanceof Repeat repeat) || repeat.min() != 0 || repeat.max() != null
            || !(repeat.child() instanceof Sequence tail) || tail.elements().size() != 2
            || !(tail.elements().get(0) instanceof Capture op) || !op.name().equals("op")
            || !(tail.elements().get(1) instanceof Capture right) || !right.name().equals("right")
            || !(right.expression() instanceof Reference self) || self.rule() != rule
            || !captures(left.expression()).isEmpty() || !captures(op.expression()).isEmpty()
            || mapping.fields().get(0).cardinality() != Cardinality.ONE
            || !mapping.fields().get(1).equals(new Field("op", Kind.TEXT, Cardinality.MANY))
            || !mapping.fields().get(2).equals(new Field("right", Kind.NODE, Cardinality.MANY))) {
            throw unsupported("@rightAssoc requires left { op Self } with scalar base and params=[left, op, right] on "
                + grammar.rules().get(rule).name());
        }
        return new Choice(List.of(new Sequence(List.of(left, op, right)), left));
    }

    private Expression body(RuleBody body) {
        if (body instanceof ChoiceBody choice) {
            return choice.alternatives().size() == 1 ? body(choice.alternatives().get(0))
                : new Choice(choice.alternatives().stream().map(alt -> {
                    Expression lowered = body(alt);
                    return alt.elements().size() == 1 ? ((Sequence) lowered).elements().get(0) : lowered;
                }).toList());
        }
        if (body instanceof SequenceBody sequence) {
            return new Sequence(sequence.elements().stream().map(element -> {
                if (element.typeofConstraint().isPresent()) throw unsupported("@typeof");
                Expression expression = atomic(element.element());
                if (element.captureName().isEmpty()) return expression;
                return capture(element.captureName().get(), expression);
            }).toList());
        }
        throw new IllegalStateException("unhandled " + body);
    }

    private Expression atomic(AtomicElement element) {
        if (element instanceof TerminalElement terminal) {
            if (terminal.value().isEmpty()) throw unsupported("empty literal");
            return new Literal(scalarText(terminal.value()));
        }
        if (element instanceof RuleRefElement reference) {
            if (reference.namespace().isPresent()) throw unsupported("qualified rule reference");
            if (tokens.containsKey(reference.name())) return tokens.get(reference.name());
            Integer id = ruleIds.get(reference.name());
            if (id == null) throw unsupported("unknown reference " + reference.name());
            return new Reference(id);
        }
        if (element instanceof GroupElement group) {
            return body(group.body());
        }
        if (element instanceof OptionalElement optional) {
            return new OptionalExpr(singleBody(optional.body(), false));
        }
        if (element instanceof RepeatElement repeat) {
            return new Repeat(singleBody(repeat.body(), true), 0, null);
        }
        if (element instanceof OneOrMoreElement repeat) {
            return new Repeat(repeatedAtom(repeat.body()), 1, null);
        }
        if (element instanceof BoundedRepeatElement repeat) {
            if (repeat.min() < 0 || repeat.max() < repeat.min()) throw unsupported("invalid repetition bounds");
            return new Repeat(repeatedAtom(repeat.body()), repeat.min(),
                repeat.max() == BoundedRepeatElement.UNBOUNDED ? null : repeat.max());
        }
        if (element instanceof SeparatedElement separated) {
            return new Separated(atomic(separated.element()), atomic(separated.separator()));
        }
        throw unsupported(element.getClass().getSimpleName());
    }

    // Match the Java generator's direct optional/ref-repeat optimization, including trivia boundaries.
    private Expression singleBody(RuleBody body, boolean onlyReference) {
        Expression lowered = body(body);
        if (lowered instanceof Sequence sequence && sequence.elements().size() == 1) {
            Expression child = sequence.elements().get(0);
            Expression bare = child instanceof Capture capture ? capture.expression() : child;
            RuleBody sourceBody = body instanceof ChoiceBody choice && choice.alternatives().size() == 1
                ? choice.alternatives().get(0) : body;
            if (sourceBody instanceof SequenceBody source && source.elements().size() == 1
                && source.elements().get(0).element() instanceof RuleRefElement) return child;
            if (!onlyReference && bare instanceof Literal) return child;
        }
        return lowered;
    }

    private Expression repeatedAtom(AtomicElement atom) {
        Expression child = atomic(atom);
        // Java wraps repeated choices/literals in a DelimitedChain outside their capture site.
        if (child instanceof Choice || child instanceof Literal) return new Delimited(child);
        return atom instanceof RuleRefElement || child instanceof Sequence
            ? child : new Sequence(List.of(child));
    }

    // A capture on a quantifier denotes its elements, not the concatenated text of the wrapper.
    private Expression capture(String name, Expression expression) {
        if (expression instanceof Sequence sequence && sequence.elements().size() == 1) {
            Expression inner = sequence.elements().get(0);
            while (inner instanceof Sequence nested && nested.elements().size() == 1) inner = nested.elements().get(0);
            if (inner instanceof OptionalExpr || inner instanceof Repeat || inner instanceof Separated) {
                throw unsupported("nested container capture; name the inner element or a mapped wrapper rule");
            }
        }
        if (expression instanceof Delimited delimited) {
            return new Delimited(capture(name, delimited.child()));
        }
        if (expression instanceof OptionalExpr optional) {
            return new OptionalExpr(capture(name, optional.child()));
        }
        if (expression instanceof Repeat repeat) {
            return new Repeat(capture(name, repeat.child()), repeat.min(), repeat.max());
        }
        if (expression instanceof Separated separated) {
            return new Separated(capture(name, separated.child()), separated.separator());
        }
        return new Capture(name, expression);
    }

    private Predictor firstPredictor(
        Expression expression, Set<Integer> visiting, Map<Integer, Predictor> cache
    ) {
        if (nullable(expression)) return new AnyPredictor();
        if (expression instanceof Literal literal && !literal.text().isEmpty()) {
            return new LiteralPredictor(literal.text());
        }
        if (expression instanceof NumberToken ignored) {
            return new NumberPredictor();
        }
        if (expression instanceof IdentifierToken ignored) {
            return new IdentifierPredictor();
        }
        if (expression instanceof QuotedToken quoted) {
            return new QuotedPredictor(quoted.quote());
        }
        if (expression instanceof Reference reference) {
            if (nullableRules.contains(reference.rule()) || !visiting.add(reference.rule())) {
                return new AnyPredictor();
            }
            Predictor result = cache.get(reference.rule());
            if (result == null) {
                result = firstPredictor(bodies.get(reference.rule()), visiting, cache);
                cache.put(reference.rule(), result);
            }
            visiting.remove(reference.rule());
            return result;
        }
        if (expression instanceof Sequence sequence) {
            return sequence.elements().isEmpty() || nullable(sequence.elements().get(0))
                ? new AnyPredictor() : firstPredictor(sequence.elements().get(0), visiting, cache);
        }
        if (expression instanceof Choice choice) {
            return combinePredictors(choice.alternatives(), visiting, cache);
        }
        if (expression instanceof LongestChoice choice) {
            return combinePredictors(choice.alternatives(), visiting, cache);
        }
        if (expression instanceof Capture capture) {
            return firstPredictor(capture.expression(), visiting, cache);
        }
        if (expression instanceof Delimited delimited) {
            return firstPredictor(delimited.child(), visiting, cache);
        }
        if (expression instanceof TextValue text) {
            return firstPredictor(text.child(), visiting, cache);
        }
        if (expression instanceof ValueBoundary boundary) {
            return firstPredictor(boundary.child(), visiting, cache);
        }
        if (expression instanceof RuleEffects effects) {
            return firstPredictor(effects.child(), visiting, cache);
        }
        if (expression instanceof Repeat repeat && repeat.min() > 0) {
            return firstPredictor(repeat.child(), visiting, cache);
        }
        if (expression instanceof Separated separated) {
            return firstPredictor(separated.child(), visiting, cache);
        }
        return new AnyPredictor();
    }

    private Predictor combinePredictors(
        List<Expression> alternatives, Set<Integer> visiting, Map<Integer, Predictor> cache
    ) {
        List<Predictor> predictors = new ArrayList<>();
        for (Expression alternative : alternatives) {
            Predictor predictor = firstPredictor(alternative, new HashSet<>(visiting), cache);
            if (!appendPredictorAtoms(predictors, predictor)) return new AnyPredictor();
        }
        return predictors.size() == 1 ? predictors.get(0) : new AnyOfPredictor(predictors);
    }

    private boolean appendPredictorAtoms(List<Predictor> predictors, Predictor predictor) {
        if (predictor instanceof AnyPredictor) return false;
        if (predictor instanceof AnyOfPredictor anyOf) {
            for (Predictor nested : anyOf.alternatives()) {
                if (!appendPredictorAtoms(predictors, nested)) return false;
            }
            return true;
        }
        if (!predictors.contains(predictor)) {
            if (predictors.size() == MAX_PREDICTOR_ATOMS) return false;
            predictors.add(predictor);
        }
        return true;
    }

    private boolean nullable(Expression expression) {
        if (expression instanceof EmptyToken ignored) {
            return true;
        }
        if (expression instanceof EofToken ignored) {
            return true;
        }
        if (expression instanceof LookaheadToken ignored) {
            return true;
        }
        if (expression instanceof UntilToken ignored) {
            return true;
        }
        if (expression instanceof Reference reference) {
            return nullableRules.contains(reference.rule());
        }
        if (expression instanceof Capture capture) {
            return nullable(capture.expression());
        }
        if (expression instanceof Delimited delimited) {
            return nullable(delimited.child());
        }
        if (expression instanceof Sequence sequence) {
            return sequence.elements().stream().allMatch(this::nullable);
        }
        if (expression instanceof Choice choice) {
            return choice.alternatives().stream().anyMatch(this::nullable);
        }
        if (expression instanceof PredictiveChoice choice) {
            return choice.alternatives().stream().anyMatch(this::nullable);
        }
        if (expression instanceof OptionalExpr ignored) {
            return true;
        }
        if (expression instanceof Repeat repeat) {
            return repeat.min() == 0 || nullable(repeat.child());
        }
        if (expression instanceof Separated separated) {
            return nullable(separated.child());
        }
        return false;
    }

    private Expression token(TokenDecl token) {
        if (token instanceof TokenDecl.Simple simple) {
            return switch (simple.parserClass()) {
                case "NumberParser", "org.unlaxer.parser.elementary.NumberParser" -> new NumberToken();
                case "IdentifierParser", "org.unlaxer.parser.clang.IdentifierParser" -> new IdentifierToken();
                case "SingleQuotedParser", "org.unlaxer.parser.elementary.SingleQuotedParser" -> new QuotedToken('\'');
                case "DoubleQuotedParser", "org.unlaxer.parser.elementary.DoubleQuotedParser" -> new QuotedToken('"');
                case "org.unlaxer.tinyexpression.parser.StringLiteralParser" ->
                    new Choice(List.of(new QuotedToken('"'), new QuotedToken('\'')));
                case "org.unlaxer.tinyexpression.parser.javalang.CodeStartParser" -> new CodeStartToken();
                case "org.unlaxer.tinyexpression.parser.javalang.CodeEndParser" -> new CodeEndToken();
                case "EndOfSourceParser", "org.unlaxer.parser.elementary.EndOfSourceParser" -> new EofToken();
                default -> throw unsupported("external token " + simple.parserClass());
            };
        }
        if (token instanceof TokenDecl.Any ignored) {
            return new AnyToken();
        }
        if (token instanceof TokenDecl.Eof ignored) {
            return new EofToken();
        }
        if (token instanceof TokenDecl.Empty ignored) {
            return new EmptyToken();
        }
        if (token instanceof TokenDecl.CharRange range) {
            if (range.min() > range.max() || Character.isSurrogate(range.min()) || Character.isSurrogate(range.max())) {
                throw unsupported("invalid character range " + range.name());
            }
            return new CharRangeToken(range.min(), range.max());
        }
        if (token instanceof TokenDecl.Negation negation) {
            return new ExceptToken(scalarText(negation.excludedChars()));
        }
        if (token instanceof TokenDecl.Until until) {
            return new UntilToken(scalarText(until.terminator()));
        }
        if (token instanceof TokenDecl.Lookahead lookahead) {
            return new LookaheadToken(scalarText(lookahead.pattern()), true);
        }
        if (token instanceof TokenDecl.NegativeLookahead lookahead) {
            return new LookaheadToken(scalarText(lookahead.pattern()), false);
        }
        throw unsupported("token " + token.name() + " (" + token.getClass().getSimpleName() + ")");
    }

    private String scalarText(String text) {
        if (text.codePoints().anyMatch(c -> c >= Character.MIN_SURROGATE && c <= Character.MAX_SURROGATE)) {
            throw unsupported("unpaired surrogate in token/literal text");
        }
        return text;
    }

    private void checkRepetition(Expression expression) {
        if (expression instanceof Repeat repeat) {
            if (repeat.max() == null && nullable(repeat.child())) throw unsupported("nullable unbounded repetition");
            checkRepetition(repeat.child());
        } else if (expression instanceof Separated separated) {
            if (nullable(separated.child()) && nullable(separated.separator())) throw unsupported("nullable unbounded separation");
            if (shape(separated.separator(), new HashSet<>()).kind() != Kind.TEXT) throw unsupported("mapped separator");
            checkRepetition(separated.child()); checkRepetition(separated.separator());
        } else if (expression instanceof OptionalExpr optional) {
            checkRepetition(optional.child());
        } else if (expression instanceof Capture capture) {
            checkRepetition(capture.expression());
        } else if (expression instanceof Delimited delimited) {
            checkRepetition(delimited.child());
        } else if (expression instanceof Sequence sequence) {
            sequence.elements().forEach(this::checkRepetition);
        } else if (expression instanceof Choice choice) {
            choice.alternatives().forEach(this::checkRepetition);
        } else {

        }
    }

    private void checkLeftRecursion(int rule, Set<Integer> visiting, Set<Integer> done) {
        if (done.contains(rule)) return;
        if (!visiting.add(rule)) throw unsupported("left recursion at " + grammar.rules().get(rule).name());
        for (int child : leadingRules(bodies.get(rule))) checkLeftRecursion(child, visiting, done);
        visiting.remove(rule);
        done.add(rule);
    }

    private Set<Integer> leadingRules(Expression expression) {
        if (expression instanceof Reference reference) {
            return Set.of(reference.rule());
        }
        if (expression instanceof Capture capture) {
            return leadingRules(capture.expression());
        }
        if (expression instanceof Delimited delimited) {
            return leadingRules(delimited.child());
        }
        if (expression instanceof OptionalExpr optional) {
            return leadingRules(optional.child());
        }
        if (expression instanceof Repeat repeat) {
            return leadingRules(repeat.child());
        }
        if (expression instanceof Separated separated) {
            Set<Integer> result = new HashSet<>(leadingRules(separated.child()));
            if (nullable(separated.child())) result.addAll(leadingRules(separated.separator()));
            return result;
        }
        if (expression instanceof Sequence sequence) {
            Set<Integer> result = new HashSet<>();
            for (Expression child : sequence.elements()) {
                result.addAll(leadingRules(child));
                if (!nullable(child)) break;
            }
            return result;
        }
        if (expression instanceof Choice choice) {
            if (choice.alternatives().isEmpty()) throw unsupported("empty choice");
            Set<Integer> result = new HashSet<>();
            choice.alternatives().forEach(e -> result.addAll(leadingRules(e)));
            return result;
        }
        if (expression instanceof PredictiveChoice choice) {
            if (choice.alternatives().isEmpty()) throw unsupported("empty choice");
            Set<Integer> result = new HashSet<>();
            choice.alternatives().forEach(e -> result.addAll(leadingRules(e)));
            return result;
        }
        return Set.of();
    }

    private Shape shape(int rule, Set<Integer> visiting) {
        if (mappings.get(rule) != null) return new Shape(Kind.NODE, Cardinality.ONE);
        if (!visiting.add(rule)) throw unsupported("recursive unmapped rule " + grammar.rules().get(rule).name());
        Shape result = shape(bodies.get(rule), visiting);
        visiting.remove(rule);
        return result;
    }

    private Shape shape(Expression expression, Set<Integer> visiting) {
        if (expression instanceof Reference reference) {
            return shape(reference.rule(), visiting);
        }
        if (expression instanceof Capture capture) {
            return shape(capture.expression(), visiting);
        }
        if (expression instanceof Delimited delimited) {
            return shape(delimited.child(), visiting);
        }
        if (expression instanceof OptionalExpr optional) {
            return wrapNode(shape(optional.child(), visiting), Cardinality.OPTIONAL);
        }
        if (expression instanceof Repeat repeat) {
            return wrapNode(shape(repeat.child(), visiting), Cardinality.MANY);
        }
        if (expression instanceof Separated separated) {
            if (shape(separated.separator(), visiting).kind() != Kind.TEXT) throw unsupported("mapped separator");
            return wrapNode(shape(separated.child(), visiting), Cardinality.MANY);
        }
        if (expression instanceof Sequence sequence) {
            var nodes = sequence.elements().stream().map(e -> shape(e, visiting)).filter(s -> s.kind() != Kind.TEXT).toList();
            Shape result = nodes.isEmpty() ? new Shape(Kind.TEXT, Cardinality.ONE) : nodes.get(0);
            for (int i = 1; i < nodes.size(); i++) result = merge(result, nodes.get(i), true);
            return result;
        }
        if (expression instanceof Choice choice) {
            var shapes = choice.alternatives().stream().map(e -> shape(e, visiting)).toList();
            Shape result = shapes.get(0);
            for (Shape alternative : shapes) result = merge(result, alternative, false);
            return result;
        }
        if (expression instanceof PredictiveChoice choice) {
            var shapes = choice.alternatives().stream().map(e -> shape(e, visiting)).toList();
            Shape result = shapes.get(0);
            for (Shape alternative : shapes) result = merge(result, alternative, false);
            return result;
        }
        return new Shape(Kind.TEXT, Cardinality.ONE);
    }

    private Shape wrapNode(Shape shape, Cardinality cardinality) {
        return shape.kind() == Kind.TEXT ? shape : wrap(shape, cardinality);
    }

    private Shape wrap(Shape shape, Cardinality cardinality) {
        return new Shape(shape.kind(), shape.cardinality() == Cardinality.MANY || cardinality == Cardinality.MANY
            ? Cardinality.MANY : cardinality == Cardinality.OPTIONAL ? Cardinality.OPTIONAL : shape.cardinality());
    }

    private Shape merge(Shape left, Shape right, boolean sequence) {
        return new Shape(joinKind(left.kind(), right.kind()), sequence || left.cardinality() == Cardinality.MANY || right.cardinality() == Cardinality.MANY
            ? Cardinality.MANY : left.cardinality() == Cardinality.OPTIONAL || right.cardinality() == Cardinality.OPTIONAL
            ? Cardinality.OPTIONAL : Cardinality.ONE);
    }

    private Kind joinKind(Kind left, Kind right) {
        return left == right ? left : Kind.VALUE;
    }

    private Map<String, Shape> wrappedCaptures(Expression child, Cardinality cardinality) {
        Map<String, Shape> result = new LinkedHashMap<>();
        captures(child).forEach((name, shape) -> result.put(name, wrap(shape, cardinality)));
        return result;
    }

    private Map<String, Shape> captures(Expression expression) {
        if (expression instanceof Capture capture) {
            Map<String, Shape> result = new LinkedHashMap<>(captures(capture.expression()));
            result.merge(capture.name(), shape(capture.expression(), new HashSet<>()), (a, b) -> merge(a, b, true));
            return result;
        }
        if (expression instanceof OptionalExpr optional) {
            return wrappedCaptures(optional.child(), Cardinality.OPTIONAL);
        }
        if (expression instanceof Delimited delimited) {
            return captures(delimited.child());
        }
        if (expression instanceof Repeat repeat) {
            return wrappedCaptures(repeat.child(), Cardinality.MANY);
        }
        if (expression instanceof Separated separated) {
            if (!captures(separated.separator()).isEmpty()) throw unsupported("captures in separator");
            return wrappedCaptures(separated.child(), Cardinality.MANY);
        }
        if (expression instanceof Sequence sequence) {
            Map<String, Shape> result = new LinkedHashMap<>();
            for (var element : sequence.elements()) for (var field : captures(element).entrySet()) {
                result.merge(field.getKey(), field.getValue(), (a, b) -> merge(a, b, true));
            }
            return result;
        }
        if (expression instanceof Choice choice) {
            var alternatives = choice.alternatives().stream().map(this::captures).toList();
            Map<String, Shape> result = new LinkedHashMap<>();
            alternatives.forEach(fields -> fields.forEach((name, shape) -> result.merge(name, shape, (a, b) -> merge(a, b, false))));
            result.replaceAll((name, shape) -> alternatives.stream().anyMatch(fields -> !fields.containsKey(name))
                ? wrap(shape, Cardinality.OPTIONAL) : shape);
            return result;
        }
        if (expression instanceof PredictiveChoice choice) {
            var alternatives = choice.alternatives().stream().map(this::captures).toList();
            Map<String, Shape> result = new LinkedHashMap<>();
            alternatives.forEach(fields -> fields.forEach((name, shape) -> result.merge(name, shape, (a, b) -> merge(a, b, false))));
            result.replaceAll((name, shape) -> alternatives.stream().anyMatch(fields -> !fields.containsKey(name))
                ? wrap(shape, Cardinality.OPTIONAL) : shape);
            return result;
        }
        return Map.of();
    }

    private static void identifier(String value) {
        if (!value.matches("[A-Za-z][A-Za-z0-9_]*") || Set.of("Self", "self", "super", "crate").contains(value)) {
            throw unsupported("identifier " + value);
        }
    }

    static String methodName(String name) {
        return "eval_" + name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(java.util.Locale.ROOT);
    }

    private static IllegalArgumentException unsupported(String detail) {
        return new IllegalArgumentException("Rust subset: " + detail);
    }
}
