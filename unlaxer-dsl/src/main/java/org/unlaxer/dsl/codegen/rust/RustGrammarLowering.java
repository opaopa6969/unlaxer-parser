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
    private final GrammarDecl grammar;
    private final Map<String, Integer> ruleIds = new LinkedHashMap<>();
    private final Set<String> numbers = new HashSet<>();
    private final List<Expression> bodies = new ArrayList<>();
    private final List<MappingAnnotation> mappings = new ArrayList<>();
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
        for (var setting : grammar.settings()) {
            if (!settings.add(setting.key())) throw unsupported("duplicate setting " + setting.key());
            if (!(setting.value() instanceof StringSettingValue value)) throw unsupported("block setting " + setting.key());
            if (setting.key().equals("whitespace")) {
                if (!Set.of("javaStyle", "none").contains(value.value())) throw unsupported("whitespace " + value.value());
                whitespace = value.value().equals("javaStyle");
            } else if (!setting.key().equals("package")) throw unsupported("setting " + setting.key());
        }
        for (var token : grammar.tokens()) {
            if (!(token instanceof TokenDecl.Simple simple)
                || !Set.of("NumberParser", "org.unlaxer.parser.elementary.NumberParser").contains(simple.parserClass())) {
                throw unsupported("token " + token.name() + " (only the built-in NumberParser is bound)");
            }
            if (!numbers.add(token.name())) throw unsupported("duplicate token " + token.name());
        }
        int root = -1;
        Set<String> variants = new HashSet<>();
        Set<String> methods = new HashSet<>();
        for (int i = 0; i < grammar.rules().size(); i++) {
            var rule = grammar.rules().get(i);
            if (ruleIds.putIfAbsent(rule.name(), i) != null || numbers.contains(rule.name())) {
                throw unsupported("duplicate rule/token " + rule.name());
            }
            MappingAnnotation mapping = null;
            for (var annotation : rule.annotations()) {
                if (annotation instanceof RootAnnotation) {
                    if (root != -1) throw unsupported("multiple @root annotations");
                    root = i;
                } else if (annotation instanceof MappingAnnotation value) {
                    if (mapping != null) throw unsupported("multiple @mapping on " + rule.name());
                    identifier(value.className());
                    if (!variants.add(value.className()) || !methods.add(methodName(value.className()))) {
                        throw unsupported("duplicate mapping/method " + value.className());
                    }
                    mapping = value;
                } else throw unsupported("annotation " + annotation + " on " + rule.name());
            }
            mappings.add(mapping);
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
        for (int i = 0; i < bodies.size(); i++) {
            Map<String, Shape> captures = captures(bodies.get(i));
            var annotation = mappings.get(i);
            Mapping mapping = null;
            if (annotation == null) {
                if (!captures.isEmpty()) throw unsupported("captures without @mapping on " + grammar.rules().get(i).name());
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
            rules.add(new Rule(grammar.rules().get(i).name(), bodies.get(i), mapping));
        }
        return new GrammarIR(rules, root, whitespace);
    }

    private Expression body(RuleBody body) {
        return switch (body) {
            case ChoiceBody choice -> choice.alternatives().size() == 1 ? body(choice.alternatives().get(0))
                : new Choice(choice.alternatives().stream().map(alt -> {
                    Expression lowered = body(alt);
                    return alt.elements().size() == 1 ? ((Sequence) lowered).elements().get(0) : lowered;
                }).toList());
            case SequenceBody sequence -> new Sequence(sequence.elements().stream().map(element -> {
                if (element.typeofConstraint().isPresent()) throw unsupported("@typeof");
                Expression expression = atomic(element.element());
                if (element.captureName().isEmpty()) return expression;
                return capture(element.captureName().get(), expression);
            }).toList());
        };
    }

    private Expression atomic(AtomicElement element) {
        return switch (element) {
            case TerminalElement terminal -> {
                if (terminal.value().isEmpty()) throw unsupported("empty literal");
                yield new Literal(terminal.value());
            }
            case RuleRefElement reference -> {
                if (reference.namespace().isPresent()) throw unsupported("qualified rule reference");
                if (numbers.contains(reference.name())) yield new NumberToken();
                Integer id = ruleIds.get(reference.name());
                if (id == null) throw unsupported("unknown reference " + reference.name());
                yield new Reference(id);
            }
            case GroupElement group -> body(group.body());
            case OptionalElement optional -> new OptionalExpr(singleBody(optional.body(), false));
            case RepeatElement repeat -> new Repeat(singleBody(repeat.body(), true), 0, null);
            case OneOrMoreElement repeat -> new Repeat(repeatedAtom(repeat.body()), 1, null);
            case BoundedRepeatElement repeat -> {
                if (repeat.min() < 0 || repeat.max() < repeat.min()) throw unsupported("invalid repetition bounds");
                yield new Repeat(repeatedAtom(repeat.body()), repeat.min(),
                    repeat.max() == BoundedRepeatElement.UNBOUNDED ? null : repeat.max());
            }
            case SeparatedElement separated -> new Separated(atomic(separated.element()), atomic(separated.separator()));
            default -> throw unsupported(element.getClass().getSimpleName());
        };
    }

    // Match the Java generator's direct optional/ref-repeat optimization, including trivia boundaries.
    private Expression singleBody(RuleBody body, boolean onlyReference) {
        Expression lowered = body(body);
        if (lowered instanceof Sequence sequence && sequence.elements().size() == 1) {
            Expression child = sequence.elements().get(0);
            Expression bare = child instanceof Capture capture ? capture.expression() : child;
            if (bare instanceof Reference || bare instanceof NumberToken || (!onlyReference && bare instanceof Literal)) return child;
        }
        return lowered;
    }

    private Expression repeatedAtom(AtomicElement atom) {
        Expression child = atomic(atom);
        return atom instanceof RuleRefElement || child instanceof Sequence || child instanceof Choice
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
        return switch (expression) {
            case OptionalExpr optional -> new OptionalExpr(capture(name, optional.child()));
            case Repeat repeat -> new Repeat(capture(name, repeat.child()), repeat.min(), repeat.max());
            case Separated separated -> new Separated(capture(name, separated.child()), separated.separator());
            default -> new Capture(name, expression);
        };
    }

    private boolean nullable(Expression expression) {
        return switch (expression) {
            case Reference reference -> nullableRules.contains(reference.rule());
            case Capture capture -> nullable(capture.expression());
            case Sequence sequence -> sequence.elements().stream().allMatch(this::nullable);
            case Choice choice -> choice.alternatives().stream().anyMatch(this::nullable);
            case OptionalExpr ignored -> true;
            case Repeat repeat -> repeat.min() == 0 || nullable(repeat.child());
            case Separated separated -> nullable(separated.child());
            default -> false;
        };
    }

    private void checkRepetition(Expression expression) {
        switch (expression) {
            case Repeat repeat -> {
                if (repeat.max() == null && nullable(repeat.child())) throw unsupported("nullable unbounded repetition");
                checkRepetition(repeat.child());
            }
            case Separated separated -> {
                if (nullable(separated.child()) && nullable(separated.separator())) throw unsupported("nullable unbounded separation");
                checkRepetition(separated.child()); checkRepetition(separated.separator());
            }
            case OptionalExpr optional -> checkRepetition(optional.child());
            case Capture capture -> checkRepetition(capture.expression());
            case Sequence sequence -> sequence.elements().forEach(this::checkRepetition);
            case Choice choice -> choice.alternatives().forEach(this::checkRepetition);
            default -> { }
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
        return switch (expression) {
            case Reference reference -> Set.of(reference.rule());
            case Capture capture -> leadingRules(capture.expression());
            case OptionalExpr optional -> leadingRules(optional.child());
            case Repeat repeat -> repeat.max() != null && repeat.max() == 0 ? Set.of() : leadingRules(repeat.child());
            case Separated separated -> {
                Set<Integer> result = new HashSet<>(leadingRules(separated.child()));
                if (nullable(separated.child())) result.addAll(leadingRules(separated.separator()));
                yield result;
            }
            case Sequence sequence -> {
                Set<Integer> result = new HashSet<>();
                for (Expression child : sequence.elements()) {
                    result.addAll(leadingRules(child));
                    if (!nullable(child)) break;
                }
                yield result;
            }
            case Choice choice -> {
                if (choice.alternatives().isEmpty()) throw unsupported("empty choice");
                Set<Integer> result = new HashSet<>();
                choice.alternatives().forEach(e -> result.addAll(leadingRules(e)));
                yield result;
            }
            default -> Set.of();
        };
    }

    private Shape shape(int rule, Set<Integer> visiting) {
        if (mappings.get(rule) != null) return new Shape(Kind.NODE, Cardinality.ONE);
        if (!visiting.add(rule)) throw unsupported("recursive unmapped rule " + grammar.rules().get(rule).name());
        Shape result = shape(bodies.get(rule), visiting);
        visiting.remove(rule);
        return result;
    }

    private Shape shape(Expression expression, Set<Integer> visiting) {
        return switch (expression) {
            case Reference reference -> shape(reference.rule(), visiting);
            case Capture capture -> shape(capture.expression(), visiting);
            case OptionalExpr optional -> wrapNode(shape(optional.child(), visiting), Cardinality.OPTIONAL);
            case Repeat repeat -> wrapNode(shape(repeat.child(), visiting), Cardinality.MANY);
            case Separated separated -> {
                if (shape(separated.separator(), visiting).kind() == Kind.NODE) throw unsupported("mapped separator");
                yield wrapNode(shape(separated.child(), visiting), Cardinality.MANY);
            }
            case Sequence sequence -> {
                var nodes = sequence.elements().stream().map(e -> shape(e, visiting)).filter(s -> s.kind() == Kind.NODE).toList();
                yield nodes.isEmpty() ? new Shape(Kind.TEXT, Cardinality.ONE)
                    : nodes.size() == 1 ? nodes.get(0) : new Shape(Kind.NODE, Cardinality.MANY);
            }
            case Choice choice -> {
                var shapes = choice.alternatives().stream().map(e -> shape(e, visiting)).toList();
                Shape result = shapes.get(0);
                for (Shape alternative : shapes) result = merge(result, alternative, false);
                yield result;
            }
            default -> new Shape(Kind.TEXT, Cardinality.ONE);
        };
    }

    private Shape wrapNode(Shape shape, Cardinality cardinality) {
        return shape.kind() == Kind.TEXT ? shape : wrap(shape, cardinality);
    }

    private Shape wrap(Shape shape, Cardinality cardinality) {
        return new Shape(shape.kind(), shape.cardinality() == Cardinality.MANY || cardinality == Cardinality.MANY
            ? Cardinality.MANY : cardinality == Cardinality.OPTIONAL ? Cardinality.OPTIONAL : shape.cardinality());
    }

    private Shape merge(Shape left, Shape right, boolean sequence) {
        if (left.kind() != right.kind()) throw unsupported("mixed text/node choice or capture");
        return new Shape(left.kind(), sequence || left.cardinality() == Cardinality.MANY || right.cardinality() == Cardinality.MANY
            ? Cardinality.MANY : left.cardinality() == Cardinality.OPTIONAL || right.cardinality() == Cardinality.OPTIONAL
            ? Cardinality.OPTIONAL : Cardinality.ONE);
    }

    private Map<String, Shape> wrappedCaptures(Expression child, Cardinality cardinality) {
        Map<String, Shape> result = new LinkedHashMap<>();
        captures(child).forEach((name, shape) -> result.put(name, wrap(shape, cardinality)));
        return result;
    }

    private Map<String, Shape> captures(Expression expression) {
        return switch (expression) {
            case Capture capture -> {
                Map<String, Shape> result = new LinkedHashMap<>(captures(capture.expression()));
                result.merge(capture.name(), shape(capture.expression(), new HashSet<>()), (a, b) -> merge(a, b, true));
                yield result;
            }
            case OptionalExpr optional -> wrappedCaptures(optional.child(), Cardinality.OPTIONAL);
            case Repeat repeat -> wrappedCaptures(repeat.child(), Cardinality.MANY);
            case Separated separated -> {
                if (!captures(separated.separator()).isEmpty()) throw unsupported("captures in separator");
                yield wrappedCaptures(separated.child(), Cardinality.MANY);
            }
            case Sequence sequence -> {
                Map<String, Shape> result = new LinkedHashMap<>();
                for (var element : sequence.elements()) for (var field : captures(element).entrySet()) {
                    result.merge(field.getKey(), field.getValue(), (a, b) -> merge(a, b, true));
                }
                yield result;
            }
            case Choice choice -> {
                var alternatives = choice.alternatives().stream().map(this::captures).toList();
                Map<String, Shape> result = new LinkedHashMap<>();
                alternatives.forEach(fields -> fields.forEach((name, shape) -> result.merge(name, shape, (a, b) -> merge(a, b, false))));
                result.replaceAll((name, shape) -> alternatives.stream().anyMatch(fields -> !fields.containsKey(name))
                    ? wrap(shape, Cardinality.OPTIONAL) : shape);
                yield result;
            }
            default -> Map.of();
        };
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
