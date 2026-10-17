package org.unlaxer.dsl.codegen;

import org.unlaxer.dsl.bootstrap.UBNFAST.*;

import java.util.*;

/** Literal enum schema shared by the Java and Rust generators. */
public final class EnumSchema {
    private EnumSchema() {}

    public static boolean isEnum(RuleDecl rule) {
        return rule.annotations().stream().anyMatch(EnumAnnotation.class::isInstance);
    }

    public static List<String> literals(RuleDecl rule) {
        List<SequenceBody> alternatives =
                rule.body() instanceof ChoiceBody c
                        ? c.alternatives()
                        : List.of((SequenceBody) rule.body());
        var result = new ArrayList<String>();
        for (var sequence : alternatives) {
            if (sequence.elements().size() != 1)
                throw invalid(rule, "requires one literal per alternative");
            var element = sequence.elements().get(0);
            if (!(element.element() instanceof TerminalElement literal)
                    || element.captureName().isPresent()
                    || element.typeofConstraint().isPresent()) {
                throw invalid(rule, "requires uncaptured literal alternatives");
            }
            result.add(literal.value());
        }
        return List.copyOf(result);
    }

    /** Stable ASCII camel/kebab conversion, with scalar escapes for other characters. */
    public static String constantName(String literal) {
        StringBuilder out = new StringBuilder();
        boolean previousLower = false;
        for (int cp : literal.codePoints().toArray()) {
            if (cp == '-' || cp == '_' || cp == ' ') {
                out.append('_');
                previousLower = false;
            } else if (!(cp >= 'a' && cp <= 'z'
                    || cp >= 'A' && cp <= 'Z'
                    || cp >= '0' && cp <= '9')) {
                if (!out.isEmpty()) out.append('_');
                out.append('U')
                        .append(Integer.toHexString(cp).toUpperCase(Locale.ROOT))
                        .append('_');
                previousLower = false;
            } else {
                if (cp >= 'A' && cp <= 'Z' && previousLower) out.append('_');
                out.appendCodePoint(cp >= 'a' && cp <= 'z' ? cp - 32 : cp);
                previousLower = cp >= 'a' && cp <= 'z';
            }
        }
        return out.toString();
    }

    private static final Set<String> RESERVED_TYPES =
            Set.of(
                    "java",
                    "abstract",
                    "assert",
                    "boolean",
                    "break",
                    "byte",
                    "case",
                    "catch",
                    "char",
                    "class",
                    "const",
                    "continue",
                    "default",
                    "do",
                    "double",
                    "else",
                    "enum",
                    "extends",
                    "final",
                    "finally",
                    "float",
                    "for",
                    "goto",
                    "if",
                    "implements",
                    "import",
                    "instanceof",
                    "int",
                    "interface",
                    "long",
                    "native",
                    "new",
                    "package",
                    "private",
                    "protected",
                    "public",
                    "return",
                    "short",
                    "static",
                    "strictfp",
                    "super",
                    "switch",
                    "synchronized",
                    "this",
                    "throw",
                    "throws",
                    "transient",
                    "try",
                    "void",
                    "volatile",
                    "while",
                    "true",
                    "false",
                    "null",
                    "_",
                    "as",
                    "async",
                    "await",
                    "crate",
                    "dyn",
                    "extern",
                    "fn",
                    "impl",
                    "in",
                    "let",
                    "loop",
                    "macro",
                    "match",
                    "mod",
                    "move",
                    "mut",
                    "pub",
                    "ref",
                    "self",
                    "Self",
                    "struct",
                    "trait",
                    "type",
                    "unsafe",
                    "use",
                    "where",
                    "yield",
                    "union",
                    "Ast",
                    "AstValue",
                    "EnumValue",
                    "Span",
                    "String",
                    "Tree",
                    "Option",
                    "Vec",
                    "Result",
                    "Semantics",
                    "record",
                    "sealed",
                    "permits",
                    "var",
                    "when",
                    "box",
                    "become",
                    "override",
                    "priv",
                    "typeof",
                    "unsized",
                    "virtual",
                    "gen",
                    "macro_rules",
                    "List",
                    "Optional",
                    "Integer",
                    "Long",
                    "Boolean",
                    "Object",
                    "Double",
                    "Float",
                    "Short",
                    "Byte",
                    "Character",
                    "Number",
                    "Void");

    public static void validateRule(RuleDecl rule) {
        if (!rule.name().matches("[A-Za-z][A-Za-z0-9_]*") || RESERVED_TYPES.contains(rule.name())) {
            throw invalid(rule, "invalid or reserved enum type name");
        }
        var names = new HashSet<String>();
        var texts = new HashSet<String>();
        for (String literal : literals(rule)) {
            String name = constantName(literal);
            if (!name.matches("[A-Z_][A-Z0-9_]*")
                    || name.equals("_")
                    || literal.codePoints().anyMatch(cp -> cp >= 0xd800 && cp <= 0xdfff)) {
                throw invalid(rule, "invalid enum constant name for literal: " + literal);
            }
            if (!texts.add(literal)) throw invalid(rule, "duplicate enum literal: " + literal);
            if (!names.add(name)) throw invalid(rule, "enum constant name collision: " + name);
        }
        if (names.isEmpty()) throw invalid(rule, "requires at least one literal");
        if (rule.annotations().stream().filter(EnumAnnotation.class::isInstance).count() != 1
                || rule.annotations().stream()
                        .anyMatch(
                                a ->
                                        a instanceof SkipAnnotation
                                                || a instanceof MappingAnnotation m
                                                        && !m.paramNames().isEmpty()
                                                || a instanceof EvalAnnotation
                                                || a instanceof CommonFieldAnnotation)) {
            throw invalid(rule, "conflicting or duplicate @enum annotation");
        }
    }

    public static void validate(GrammarDecl grammar) {
        if (grammar.rules().stream().noneMatch(EnumSchema::isEnum)) return;
        var types = new HashSet<String>();
        types.add(grammar.name() + "AST");
        for (var rule : grammar.rules())
            if (!isEnum(rule)) {
                MapperElementUtil.getMappingAnnotation(rule)
                        .ifPresent(
                                m -> {
                                    types.add(m.className());
                                    if (m.className().contains("."))
                                        types.add(
                                                m.className()
                                                        .substring(0, m.className().indexOf('.')));
                                });
            }
        for (var rule : grammar.rules())
            if (isEnum(rule)) {
                validateRule(rule);
                if (!types.add(rule.name()))
                    throw invalid(rule, "enum type name collision: " + rule.name());
            }
        if (types.contains("EnumValue"))
            throw new IllegalArgumentException(
                    "E-ENUM: mapping collides with EnumValue occurrence wrapper");
        var sharedParameters = new HashMap<String, List<String>>();
        for (var rule : grammar.rules())
            if (!isEnum(rule)) {
                MapperElementUtil.getMappingAnnotation(rule)
                        .ifPresent(
                                mapping -> {
                                    var previous =
                                            sharedParameters.putIfAbsent(
                                                    mapping.className(), mapping.paramNames());
                                    if (previous != null && !previous.equals(mapping.paramNames()))
                                        throw new IllegalArgumentException(
                                                "E-ENUM: incompatible shared mapping field schema");
                                });
            }
        var enumRoots = rootEnums(grammar);
        if (!enumRoots.isEmpty()) {
            var root =
                    grammar.rules().stream()
                            .filter(
                                    rule ->
                                            rule.annotations().stream()
                                                    .anyMatch(RootAnnotation.class::isInstance))
                            .findFirst()
                            .orElseThrow();
            var rules = new HashMap<String, RuleDecl>();
            grammar.rules().forEach(rule -> rules.put(rule.name(), rule));
            var shape = new SemanticCardinality(grammar).shape(new RuleRefElement(root.name()));
            if (shape.kind() != SemanticCardinality.Kind.NODE
                    || shape.count() != SemanticCardinality.Count.ONE) {
                throw new IllegalArgumentException(
                        "E-ENUM: root must project exactly one enum value");
            }
        }
        var methods = new HashMap<String, String>();
        for (var rule : grammar.rules()) {
            if (!isEnum(rule)
                    && rule.annotations().stream().noneMatch(SkipAnnotation.class::isInstance)) {
                MapperElementUtil.getMappingAnnotation(rule)
                        .ifPresent(
                                mapping ->
                                        methods.put(
                                                portableMethodName(mapping.className()),
                                                mapping.className()));
            }
        }
        for (var entry : enumRoots.entrySet()) {
            String previous =
                    methods.putIfAbsent(portableMethodName(entry.getValue()), entry.getValue());
            if (previous != null && !previous.equals(entry.getValue())) {
                throw new IllegalArgumentException(
                        "E-ENUM: enum root semantic method collision: " + entry.getValue());
            }
            if (!types.add(entry.getValue()))
                throw new IllegalArgumentException(
                        "E-ENUM: enum root wrapper name collision: " + entry.getValue());
        }
    }

    private static String portableMethodName(String name) {
        return name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(java.util.Locale.ROOT);
    }

    /** Enum nodes projected before the first explicit mapped AST boundary. */
    public static Map<String, String> rootEnums(GrammarDecl grammar) {
        if (grammar.rules().stream().noneMatch(EnumSchema::isEnum)) return Map.of();
        var rules = new LinkedHashMap<String, RuleDecl>();
        grammar.rules().forEach(rule -> rules.put(rule.name(), rule));
        var pending = new ArrayDeque<RuleDecl>();
        grammar.rules().stream()
                .filter(
                        rule ->
                                rule.annotations().stream()
                                        .anyMatch(RootAnnotation.class::isInstance))
                .findFirst()
                .ifPresent(pending::add);
        var visited = new HashSet<String>();
        var result = new LinkedHashMap<String, String>();
        while (!pending.isEmpty()) {
            var rule = pending.removeFirst();
            if (!visited.add(rule.name()) || MapperElementUtil.isSkipped(rule)) continue;
            if (isEnum(rule)) {
                result.put(rule.name(), rule.name() + "Node");
                continue;
            }
            if (MapperElementUtil.getMappingAnnotation(rule).isPresent()) continue;
            var atoms = new ArrayDeque<AtomicElement>();
            addBody(rule.body(), atoms);
            while (!atoms.isEmpty()) {
                var atom = atoms.removeFirst();
                if (atom instanceof RuleRefElement ref && rules.containsKey(ref.name()))
                    pending.add(rules.get(ref.name()));
                else addChildren(atom, atoms);
            }
        }
        return result;
    }

    private static void addBody(RuleBody body, Deque<AtomicElement> pending) {
        var alternatives =
                body instanceof ChoiceBody choice
                        ? choice.alternatives()
                        : List.of((SequenceBody) body);
        for (var alternative : alternatives)
            for (var item : alternative.elements()) pending.add(item.element());
    }

    private static void addChildren(AtomicElement element, Deque<AtomicElement> pending) {
        if (element instanceof GroupElement group) addBody(group.body(), pending);
        else if (element instanceof OptionalElement optional) addBody(optional.body(), pending);
        else if (element instanceof RepeatElement repeat) addBody(repeat.body(), pending);
        else if (element instanceof OneOrMoreElement repeat) pending.add(repeat.body());
        else if (element instanceof BoundedRepeatElement repeat) pending.add(repeat.body());
        else if (element instanceof SeparatedElement separated) pending.add(separated.element());
    }

    /**
     * A single enum family behind transparent wrappers; mixed joins are a separate contract (#539).
     */
    public static Optional<String> enumType(GrammarDecl grammar, AtomicElement element) {
        if (grammar.rules().stream().noneMatch(EnumSchema::isEnum)) return Optional.empty();
        var rules = new HashMap<String, RuleDecl>();
        grammar.rules().forEach(r -> rules.put(r.name(), r));
        var names = new LinkedHashSet<String>();
        collectEnumTypes(element, rules, new HashSet<>(), names);
        return names.size() == 1 && names.contains(valueType(element, rules, new HashSet<>()))
                ? Optional.of(names.iterator().next())
                : Optional.empty();
    }

    private static void collectEnumTypes(
            AtomicElement element,
            Map<String, RuleDecl> rules,
            Set<String> seen,
            Set<String> names) {
        var pending = new ArrayDeque<AtomicElement>();
        pending.add(element);
        while (!pending.isEmpty()) {
            var atom = pending.removeFirst();
            if (atom instanceof RuleRefElement ref) {
                var rule = rules.get(ref.name());
                if (rule == null || MapperElementUtil.isSkipped(rule) || !seen.add(ref.name()))
                    continue;
                if (isEnum(rule)) names.add(rule.name());
                else if (MapperElementUtil.getMappingAnnotation(rule).isEmpty())
                    addBody(rule.body(), pending);
            } else addChildren(atom, pending);
        }
    }

    /** Whether a capture projects only AST nodes, through transparent helpers and choices. */
    static boolean onlyAstValues(GrammarDecl grammar, AtomicElement element) {
        var rules = new HashMap<String, RuleDecl>();
        grammar.rules().forEach(rule -> rules.put(rule.name(), rule));
        return valueType(element, rules, new HashSet<>()).equals("#node");
    }

    private static String valueType(
            AtomicElement element, Map<String, RuleDecl> rules, Set<String> seen) {
        if (element instanceof RuleRefElement ref) {
            if (seen.size() >= 128)
                throw new IllegalArgumentException("E-ENUM: enum value analysis exceeds 128 rules");
            RuleDecl rule = rules.get(ref.name());
            if (rule == null || MapperElementUtil.isSkipped(rule) || !seen.add(ref.name()))
                return "#text";
            if (isEnum(rule)) return rule.name();
            if (MapperElementUtil.getMappingAnnotation(rule).isPresent()) return "#node";
            return valueType(new GroupElement(rule.body()), rules, seen);
        }
        if (element instanceof GroupElement g) {
            var alternatives =
                    g.body() instanceof ChoiceBody c
                            ? c.alternatives()
                            : List.of((SequenceBody) g.body());
            var choices = new HashSet<String>();
            for (var alternative : alternatives) {
                var nodes = new HashSet<String>();
                for (var child : alternative.elements()) {
                    String kind = valueType(child.element(), rules, new HashSet<>(seen));
                    if (!kind.equals("#text")) nodes.add(kind);
                }
                choices.add(
                        nodes.isEmpty()
                                ? "#text"
                                : nodes.size() == 1 ? nodes.iterator().next() : "#join");
            }
            return choices.size() == 1 ? choices.iterator().next() : "#join";
        }
        if (element instanceof OptionalElement g)
            return valueType(new GroupElement(g.body()), rules, seen);
        if (element instanceof RepeatElement g)
            return valueType(new GroupElement(g.body()), rules, seen);
        if (element instanceof OneOrMoreElement g) return valueType(g.body(), rules, seen);
        if (element instanceof BoundedRepeatElement g) return valueType(g.body(), rules, seen);
        if (element instanceof SeparatedElement g) return valueType(g.element(), rules, seen);
        return "#text";
    }

    static RuleDecl projection(RuleDecl rule, String wrapper) {
        return new RuleDecl(
                List.of(new MappingAnnotation(wrapper, List.of())), rule.name(), rule.body());
    }

    private static IllegalArgumentException invalid(RuleDecl rule, String reason) {
        return new IllegalArgumentException("E-ENUM: " + rule.name() + ": " + reason);
    }
}
