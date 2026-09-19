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
        for (int i = 0; i < bodies.size(); i++) checkLeftRecursion(i, new HashSet<>(), new HashSet<>());
        if (kind(root, new HashSet<>()) != Kind.NODE) throw unsupported("root must resolve to an AST node");
        List<Rule> rules = new ArrayList<>();
        for (int i = 0; i < bodies.size(); i++) {
            Map<String, Kind> captures = captures(bodies.get(i));
            var annotation = mappings.get(i);
            Mapping mapping = null;
            if (annotation == null) {
                if (!captures.isEmpty()) throw unsupported("captures without @mapping on " + grammar.rules().get(i).name());
            } else {
                if (new HashSet<>(annotation.paramNames()).size() != annotation.paramNames().size()
                    || !captures.keySet().equals(new HashSet<>(annotation.paramNames()))) {
                    throw unsupported("@mapping params must match one capture per alternative on " + grammar.rules().get(i).name());
                }
                List<Field> fields = new ArrayList<>();
                for (String name : annotation.paramNames()) {
                    identifier(name);
                    if (Set.of("span", "semantics").contains(name)) throw unsupported("capture name " + name + " is reserved");
                    fields.add(new Field(name, captures.get(name)));
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
                if (element.element() instanceof GroupElement) throw unsupported("capture on group; capture its alternatives instead");
                return (Expression) new Capture(element.captureName().get(), expression);
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
            default -> throw unsupported(element.getClass().getSimpleName());
        };
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
            case Sequence sequence -> {
                if (sequence.elements().isEmpty()) throw unsupported("empty sequence");
                yield leadingRules(sequence.elements().get(0));
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

    private Kind kind(int rule, Set<Integer> visiting) {
        if (mappings.get(rule) != null) return Kind.NODE;
        if (!visiting.add(rule)) throw unsupported("recursive unmapped rule " + grammar.rules().get(rule).name());
        Kind result = kind(bodies.get(rule), visiting);
        visiting.remove(rule);
        return result;
    }

    private Kind kind(Expression expression, Set<Integer> visiting) {
        return switch (expression) {
            case Reference reference -> kind(reference.rule(), visiting);
            case Capture capture -> kind(capture.expression(), visiting);
            case Sequence sequence -> {
                var kinds = sequence.elements().stream().map(e -> kind(e, visiting)).toList();
                if (kinds.stream().filter(k -> k == Kind.NODE).count() > 1) throw unsupported("unmapped sequence with multiple AST children");
                yield kinds.contains(Kind.NODE) ? Kind.NODE : Kind.TEXT;
            }
            case Choice choice -> {
                var kinds = choice.alternatives().stream().map(e -> kind(e, visiting)).distinct().toList();
                if (kinds.size() != 1) throw unsupported("mixed text/node choice");
                yield kinds.get(0);
            }
            default -> Kind.TEXT;
        };
    }

    private Map<String, Kind> captures(Expression expression) {
        return switch (expression) {
            case Capture capture -> Map.of(capture.name(), kind(capture.expression(), new HashSet<>()));
            case Sequence sequence -> {
                Map<String, Kind> result = new LinkedHashMap<>();
                for (var element : sequence.elements()) for (var field : captures(element).entrySet()) {
                    if (result.putIfAbsent(field.getKey(), field.getValue()) != null) throw unsupported("repeated capture " + field.getKey());
                }
                yield result;
            }
            case Choice choice -> {
                var alternatives = choice.alternatives().stream().map(this::captures).toList();
                if (alternatives.stream().anyMatch(a -> !a.equals(alternatives.get(0)))) throw unsupported("different captures across alternatives");
                yield alternatives.get(0);
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
