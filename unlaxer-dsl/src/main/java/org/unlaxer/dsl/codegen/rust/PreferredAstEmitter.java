package org.unlaxer.dsl.codegen.rust;

import org.unlaxer.dsl.bootstrap.UBNFAST.*;
import org.unlaxer.dsl.codegen.CommonFieldSchema;
import org.unlaxer.dsl.codegen.PreferredAstPlan;
import org.unlaxer.dsl.codegen.rust.GrammarIR.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** The ordinary parser remains unchanged; the alternate CST carries Java token-depth metadata. */
final class PreferredAstEmitter {
    record Output(String parser, String mapper) {}

    static Output emit(
            GrammarDecl grammar,
            GrammarIR ir,
            RustBackend backend,
            String parserSource,
            String astSource) {
        String unsupported = null;
        List<List<PreferredAstPlan.Call>> calls;
        try {
            calls = PreferredAstPlan.calls(grammar);
        } catch (IllegalArgumentException error) {
            calls = List.of();
            unsupported = "unsupported source projection";
        }
        var identity = new StringBuilder();
        for (int i = 0; i < grammar.rules().size(); i++) {
            var sourceRule = grammar.rules().get(i);
            String mapping =
                    sourceRule.annotations().stream()
                            .filter(MappingAnnotation.class::isInstance)
                            .map(MappingAnnotation.class::cast)
                            .map(MappingAnnotation::className)
                            .findFirst()
                            .orElse("");
            identity.append("\nrule:")
                    .append(i)
                    .append(":")
                    .append(ir.rules().get(i).skip())
                    .append(":")
                    .append(
                            sourceRule.annotations().stream()
                                    .anyMatch(RecoveryAnnotation.class::isInstance))
                    .append(":")
                    .append(mapping)
                    .append("\n");
            if (i < calls.size())
                for (var call : calls.get(i))
                    identity.append(call.rule()).append(":").append(call.depth()).append(",");
            identity.append("\n");
            for (var constraint : ir.rules().get(i).typeConstraints())
                identity.append("typeof:").append(constraint.capture().name()).append(":")
                    .append(constraint.reference().name()).append("\n");
        }
        long hash = 0xcbf29ce484222325L;
        for (byte value :
                (parserSource.split("\\n/// Ordered rule documentation;", 2)[0]
                                + astSource
                                + identity)
                        .getBytes(StandardCharsets.UTF_8)) {
            hash ^= value & 255;
            hash *= 0x100000001b3L;
        }
        String marker = "#preferred-schema:" + Long.toUnsignedString(hash, 16);
        var rules = new ArrayList<Rule>();
        try {
            if (unsupported != null
                    || grammar.rules().stream()
                            .flatMap(rule -> rule.annotations().stream())
                            .anyMatch(
                                    annotation ->
                                            annotation instanceof LeftAssocAnnotation
                                                    || annotation instanceof RightAssocAnnotation)
                    || (!CommonFieldSchema.enabled(grammar)
                            && grammar.rules().stream()
                                    .anyMatch(rule -> CommonFieldSchema.isSum(grammar, rule))))
                throw new IllegalArgumentException("sum family pending #569");
            for (int i = 0; i < ir.rules().size(); i++) {
                var rule = ir.rules().get(i);
                var iter = calls.get(i).iterator();
                Expression body = instrument(rule.body(), iter);
                if (iter.hasNext()) throw new IllegalArgumentException("source call sites differ");
                if (body instanceof NameResolutionScope scope)
                    body =
                            new NameResolutionScope(
                                    new Capture(marker, scope.child()), scope.requirements());
                else body = new Capture(marker, body);
                rules.add(
                        new Rule(
                                rule.name(),
                                body,
                                rule.mapping(),
                                rule.operator(),
                                rule.catalog(),
                                rule.skip(),
                                rule.documentation(),
                                rule.enumLiterals(),
                                rule.typeConstraints()));
            }
        } catch (IllegalArgumentException error) {
            unsupported = "source call sites do not match lowered references";
        }
        // Preserve ordinary generation for grammars whose specialized projection is tracked
        // separately.
        String parser =
                template("preferred-parser.rs.txt")
                        .replace("$ROOT", Integer.toString(ir.root()))
                        .replace("$WHITESPACE", Boolean.toString(ir.javaWhitespace()));
        parser +=
                unsupported != null
                        ? "\n"
                              + "/// None when this specialized lowering is not supported by"
                              + " preferred snapshots.\n"
                              + "pub fn mapping_grammar() -> Option<&'static SharedGrammar> { None"
                              + " }\n"
                        : "\n"
                                + "/// Grammar with private candidate metadata, suitable for an"
                                + " explicit alternate entry.\n"
                                + "pub fn mapping_grammar() -> Option<&'static SharedGrammar> {"
                                + " Some(__preferred_cst::grammar()) }\n"
                                + "#[allow(dead_code)]\n"
                                + "mod __preferred_cst {\n"
                                + backend.parser(
                                        new GrammarIR(rules, ir.root(), ir.javaWhitespace()))
                                + "}\n";
        var out =
                new StringBuilder(
                        "\nconst __PREFERRED_ROOT: usize = "
                                + ir.root()
                                + ";\nconst __PREFERRED_RULE_COUNT: usize = "
                                + ir.rules().size()
                                + ";\nconst __PREFERRED_MARKER: &str = "
                                + quote(marker)
                                + ";\n");
        out.append("const __PREFERRED_STRICT_ROOT: bool = ")
                .append(
                        grammar.rules().get(ir.root()).annotations().stream()
                                .anyMatch(MappingAnnotation.class::isInstance))
                .append(";\n");
        var recovered = new ArrayList<String>();
        for (int i = 0; i < grammar.rules().size(); i++)
            if (grammar.rules().get(i).annotations().stream()
                    .anyMatch(RecoveryAnnotation.class::isInstance))
                recovered.add(Integer.toString(i));
        out.append(predicate("__preferred_recovery_wrapper", recovered));
        var eligible = new ArrayList<String>();
        var skipped = new ArrayList<String>();
        for (int i = 0; i < ir.rules().size(); i++) {
            if (ir.rules().get(i).skip()) skipped.add(Integer.toString(i));
            else if (grammar.rules().get(i).annotations().stream()
                            .anyMatch(MappingAnnotation.class::isInstance)
                    || ir.rules().get(i).mapping() != null) eligible.add(Integer.toString(i));
        }
        out.append(predicate("__preferred_candidate", eligible))
                .append(predicate("__preferred_skipped", skipped));
        out.append("fn __preferred_name(ast: &Ast) -> &'static str { match *ast {\n");
        for (var mapping : ir.mappings()) {
            String name =
                    grammar.rules().stream()
                            .flatMap(r -> r.annotations().stream())
                            .filter(MappingAnnotation.class::isInstance)
                            .map(MappingAnnotation.class::cast)
                            .map(MappingAnnotation::className)
                            .filter(n -> n.replace(".", "__").equals(mapping.name()))
                            .findFirst()
                            .orElse(mapping.name());
            name = name.substring(name.lastIndexOf('.') + 1);
            out.append("    Ast::r#")
                    .append(mapping.name())
                    .append(" { .. } => ")
                    .append(quote(name))
                    .append(",\n");
        }
        out.append("} }\n").append(template("preferred-snapshot.rs.txt"));
        return new Output(parser, out.toString());
    }

    private static String predicate(String name, List<String> ids) {
        return "fn "
                + name
                + "(rule: usize) -> bool { "
                + (ids.isEmpty()
                        ? "let _ = rule; false"
                        : "matches!(rule, " + String.join(" | ", ids) + ")")
                + " }\n";
    }

    private static String quote(String value) {
        return RustBackend.quote(value);
    }

    private static String template(String name) {
        try (var input = PreferredAstEmitter.class.getResourceAsStream("/rust/" + name)) {
            if (input == null) throw new IllegalStateException("missing preferred mapper template");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    static boolean containsDepth(Expression value) {
        if (value instanceof Capture capture && capture.name().startsWith("#preferred-depth:"))
            return true;
        try {
            for (var component : value.getClass().getRecordComponents()) {
                Object field = component.getAccessor().invoke(value);
                if (field instanceof Expression child && containsDepth(child)) return true;
                if (field instanceof List<?> list)
                    for (Object element : list)
                        if (element instanceof Expression child && containsDepth(child))
                            return true;
            }
            return false;
        } catch (ReflectiveOperationException error) {
            throw new IllegalArgumentException("cannot inspect expression metadata", error);
        }
    }

    private static Expression instrument(Expression value, Iterator<PreferredAstPlan.Call> calls) {
        if (value instanceof Reference ref) {
            if (!calls.hasNext()) throw new IllegalArgumentException("missing call site");
            var call = calls.next();
            if (call.rule() != ref.rule() || call.depth() < 1 || call.depth() > 4096)
                throw new IllegalArgumentException("call site mismatch");
            return new Capture("#preferred-depth:" + call.depth(), value);
        }
        // Expression records contain child Expressions or Lists; metadata records remain unchanged.
        try {
            var components = value.getClass().getRecordComponents();
            var types = new Class<?>[components.length];
            var args = new Object[components.length];
            for (int i = 0; i < components.length; i++) {
                types[i] = components[i].getType();
                Object field = components[i].getAccessor().invoke(value);
                if (field instanceof Expression child) field = instrument(child, calls);
                else if (field instanceof List<?> list) {
                    var copy = new ArrayList<Object>();
                    for (Object element : list)
                        copy.add(
                                element instanceof Expression child
                                        ? instrument(child, calls)
                                        : element);
                    field = List.copyOf(copy);
                }
                args[i] = field;
            }
            return (Expression) value.getClass().getDeclaredConstructor(types).newInstance(args);
        } catch (ReflectiveOperationException error) {
            throw new IllegalArgumentException("cannot retain expression metadata", error);
        }
    }
}
