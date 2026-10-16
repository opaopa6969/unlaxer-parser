package org.unlaxer.dsl.codegen.rust;

import static org.unlaxer.dsl.codegen.rust.GrammarIR.*;

import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.codegen.AssociativeProjection;

final class AssociativeSpines {
    private AssociativeSpines() {}

    static GrammarIR refine(GrammarDecl grammar, GrammarIR ir) {
        var names = AssociativeProjection.literalSpines(grammar);
        return new GrammarIR(
                ir.rules().stream()
                        .map(
                                rule -> {
                                    if (rule.mapping() == null
                                            || !names.contains(rule.mapping().name())) return rule;
                                    var mapping =
                                            new Mapping(
                                                    rule.mapping().name(),
                                                    rule.mapping().fields().stream()
                                                            .map(AssociativeSpines::field)
                                                            .toList());
                                    return new Rule(
                                            rule.name(),
                                            rule.body(),
                                            mapping,
                                            rule.operator(),
                                            rule.catalog(),
                                            rule.skip(),
                                            rule.documentation(),
                                            rule.enumLiterals(),
                                            rule.typeConstraints().stream()
                                                    .map(
                                                            c ->
                                                                    new TypeConstraint(
                                                                            field(c.capture()),
                                                                            field(c.reference())))
                                                    .toList());
                                })
                        .toList(),
                ir.root(),
                ir.javaWhitespace());
    }

    private static Field field(Field field) {
        return switch (field.name()) {
            case "left" -> new Field("left", Kind.NULLABLE_NODE, Cardinality.ONE);
            case "right" -> new Field("right", Kind.NODE, Cardinality.MANY);
            default -> field;
        };
    }

    static boolean spine(Mapping mapping) {
        return mapping != null
                && mapping.fields().stream().anyMatch(f -> f.kind() == Kind.NULLABLE_NODE);
    }

    static String capture(Mapping mapping, Field field, String indent) {
        return spine(mapping) && (field.name().equals("left") || field.name().equals("right"))
                ? indent + "values.push(map_associative_" + mapping.name() + "(tree, capture)?);\n"
                : null;
    }

    static String helpers(GrammarIR ir) {
        var out = new StringBuilder();
        try (var input =
                AssociativeSpines.class.getResourceAsStream("/rust/associative-spine.rs.txt")) {
            if (input == null) throw new IllegalStateException("missing associative template");
            String source =
                    new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            for (var mapping : ir.mappings())
                if (spine(mapping)) out.append(source.replace("$NAME", mapping.name()));
        } catch (java.io.IOException error) {
            throw new java.io.UncheckedIOException(error);
        }
        return out.toString();
    }
}
