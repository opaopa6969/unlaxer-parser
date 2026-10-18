package org.unlaxer.dsl.codegen.rust;

import org.unlaxer.dsl.codegen.rust.GrammarIR.*;

import java.util.LinkedHashMap;
import java.util.List;

/** Mapping-time checks use semantic identities, never Rust enum discriminants of a wrapper. */
final class TypeofEmitter {
    private TypeofEmitter() {}

    static List<Field> fields(Rule rule) {
        var result = new LinkedHashMap<String, Field>();
        if (rule.mapping() != null)
            for (Field field : rule.mapping().fields()) result.put(field.name(), field);
        for (TypeConstraint constraint : rule.typeConstraints()) {
            result.putIfAbsent(constraint.capture().name(), constraint.capture());
            result.putIfAbsent(constraint.reference().name(), constraint.reference());
        }
        return List.copyOf(result.values());
    }

    static String helpers(GrammarIR ir) {
        var fields =
                ir.rules().stream()
                        .flatMap(rule -> rule.typeConstraints().stream())
                        .flatMap(
                                value ->
                                        java.util.stream.Stream.of(
                                                value.capture(), value.reference()))
                        .toList();
        boolean values =
                fields.stream()
                        .anyMatch(
                                field ->
                                        field.kind() == Kind.VALUE
                                                && field.cardinality() == Cardinality.ONE);
        boolean nestedValues =
                fields.stream()
                        .anyMatch(
                                field ->
                                        field.kind() == Kind.NESTED
                                                && field.nested().containers().isEmpty()
                                                && field.nested().leaf() == Kind.VALUE);
        boolean nodes =
                values
                        || nestedValues
                        || fields.stream()
                                .anyMatch(
                                        field ->
                                                field.kind() == Kind.NESTED
                                                        && field.nested().containers().isEmpty()
                                                        && field.nested().leaf() == Kind.NODE)
                        || fields.stream()
                                .anyMatch(
                                        field ->
                                                (field.kind() == Kind.NODE
                                                                || field.kind()
                                                                        == Kind.NULLABLE_NODE)
                                                        && field.cardinality() == Cardinality.ONE);
        var out = new StringBuilder();
        if (nodes) {
            out.append("\nfn typeof_node(value: &Ast) -> &'static str {\n    match value {\n");
            for (Mapping mapping : ir.mappings())
                out.append("        Ast::r#")
                        .append(mapping.name())
                        .append(" { .. } => ")
                        .append(RustBackend.quote("node::" + mapping.name()))
                        .append(",\n");
            out.append("    }\n}\n");
        }
        if (values) {
            out.append(
                    "\n"
                            + "fn typeof_value(value: &AstValue) -> &'static str {\n"
                            + "    match value {\n"
                            + "        AstValue::Text { .. } => \"String\",\n"
                            + "        AstValue::Node(node) => typeof_node(node),\n");
            for (Rule rule : ir.rules())
                if (!rule.enumLiterals().isEmpty())
                    out.append("        AstValue::Enum")
                            .append(rule.name())
                            .append(" { .. } => ")
                            .append(RustBackend.quote("enum::" + rule.name()))
                            .append(",\n");
            out.append("    }\n}\n");
        }
        if (nestedValues) {
            out.append(
                    "\n"
                        + "fn typeof_nested_value(value: &NestedValue) -> &'static str {\n"
                        + "    match value {\n"
                        + "        NestedValue::Text { .. } => \"String\",\n"
                        + "        NestedValue::Integer { .. } => \"Integer\",\n"
                        + "        NestedValue::Node(node) => typeof_node(node),\n");
            for (Rule rule : ir.rules())
                if (!rule.enumLiterals().isEmpty())
                    out.append("        NestedValue::Enum")
                            .append(rule.name())
                            .append(" { .. } => ")
                            .append(RustBackend.quote("enum::" + rule.name()))
                            .append(",\n");
            out.append("    }\n}\n");
        }
        out.append(
                "\n"
                    + "fn typeof_span(node: &unlaxer_runtime::Node, name: &str) ->"
                    + " unlaxer_runtime::Span {\n"
                    + "    let mut found: Option<unlaxer_runtime::Span> = None;\n"
                    + "    for capture in node.captures.iter().filter(|capture| capture.name =="
                    + " name) {\n"
                    + "        found = Some(match found {\n"
                    + "            None => capture.span,\n"
                    + "            Some(span) => unlaxer_runtime::Span { start:"
                    + " std::cmp::min(span.start, capture.span.start), end: std::cmp::max(span.end,"
                    + " capture.span.end) },\n"
                    + "        });\n"
                    + "    }\n"
                    + "    found.unwrap_or(node.span)\n"
                    + "}\n");
        return out.toString();
    }

    static String rule(GrammarIR ir, int id) {
        Rule rule = ir.rules().get(id);
        List<Field> fields = fields(rule);
        var out =
                new StringBuilder(
                        "\n#[inline(never)]\nfn map_rule_"
                                + id
                                + "(tree: &Tree, id: usize) -> Result<Vec<Ast>, String> {\n"
                                + "    let node = &tree.nodes[id];\n");
        if (rule.mapping() == null)
            out.append("    let projected = map_nodes(tree, &node.children)?;\n");
        for (int index = 0; index < fields.size(); index++) {
            Field field = fields.get(index);
            out.append("    let _value_")
                    .append(index)
                    .append(
                            " = {\n"
                                    + "        let mut values = Vec::new();\n"
                                    + "        for capture in node.captures.iter().filter(|capture|"
                                    + " capture.name == ")
                    .append(RustBackend.quote(field.name()))
                    .append(") {\n");
            String associative = AssociativeSpines.capture(rule.mapping(), field, "            ");
            if (associative != null) out.append(associative);
            else
                switch (field.kind()) {
                    case ENUM -> {
                        int enumId =
                                java.util.stream.IntStream.range(0, ir.rules().size())
                                        .filter(
                                                i ->
                                                        ir.rules()
                                                                .get(i)
                                                                .name()
                                                                .equals(field.enumName()))
                                        .findFirst()
                                        .orElseThrow();
                        out.append("            values.extend(map_enum_")
                                .append(enumId)
                                .append("(tree, &capture.nodes)?);\n");
                    }
                    case NULLABLE_NODE ->
                            throw new IllegalArgumentException("invalid nullable node projection");
                    case NODE ->
                            out.append(
                                    rule.mapping() == null
                                            ? "            if !capture.nodes.is_empty() {"
                                                  + " tree.charge_work(projected.len())?; values.extend(projected.iter()); }\n"
                                            : "            values.extend(map_nodes(tree,"
                                                    + " &capture.nodes)?);\n");
                    case VALUE ->
                            out.append(
                                    "            values.extend(map_values(tree,"
                                        + " &capture.nodes)?);\n");
                    case NESTED -> {
                        var shape = field.nested();
                        String projection =
                                switch (shape.leaf()) {
                                    case VALUE -> "map_nested_values";
                                    case NODE -> "map_nodes";
                                    case ENUM ->
                                            "map_enum_"
                                                    + java.util.stream.IntStream.range(
                                                                    0, ir.rules().size())
                                                            .filter(
                                                                    i ->
                                                                            ir.rules()
                                                                                    .get(i)
                                                                                    .name()
                                                                                    .equals(
                                                                                            shape
                                                                                                    .enumName()))
                                                            .findFirst()
                                                            .orElseThrow();
                                    default -> null;
                                };
                        out.append("            let nested = nested_capture(tree, capture)?;\n");
                        if (projection != null)
                            out.append(
                                    "            let mut nested_sources = nested_source_nodes(tree,"
                                        + " capture.nodes[0])?.into_iter();\n");
                        out.append("            values.push(")
                                .append(NestedEmitter.mapped(shape, "&nested", 0, projection))
                                .append("?);\n");
                        if (projection != null)
                            out.append(
                                    "            if nested_sources.next().is_some() { return"
                                        + " Err(\"unmapped original nested leaf\".into()); }\n");
                    }
                    case INTEGER ->
                            out.append(
                                    "           "
                                        + " values.push(unlaxer_runtime::java_capture_text(tree.text(capture.span)?).parse::<i32>().map_err(|_|"
                                        + " format!(\"invalid i32 capture {} at {}..{}\","
                                        + " capture.name, capture.span.start,"
                                        + " capture.span.end))?);\n");
                    case TEXT ->
                            out.append(
                                    RustBackend.graphemeCapture(ir, rule.body(), field.name())
                                            ? "           "
                                                  + " values.push(tree.text(capture.span)?.to_owned());\n"
                                            : "           "
                                                  + " values.push(unlaxer_runtime::java_capture_text(tree.text(capture.span)?).to_owned());\n");
                }
            out.append("        }\n        ")
                    .append(RustBackend.mappedValue(field))
                    .append("\n    };\n");
        }
        for (TypeConstraint constraint : rule.typeConstraints()) {
            int own = index(fields, constraint.capture().name()),
                    ref = index(fields, constraint.reference().name());
            String ownType = type(fields.get(own), own), refType = type(fields.get(ref), ref);
            String span =
                    "typeof_span(node, " + RustBackend.quote(constraint.capture().name()) + ")";
            if (outerCardinality(fields.get(own)) != Cardinality.ONE)
                span =
                        "if _value_"
                                + own
                                + (outerCardinality(fields.get(own)) == Cardinality.MANY
                                        ? ".is_empty()"
                                        : ".is_none()")
                                + " { node.span } else { "
                                + span
                                + " }";
            out.append("    let expected_type = ")
                    .append(refType)
                    .append(";\n    let actual_type = ")
                    .append(ownType)
                    .append(";\n    if expected_type != actual_type {\n        let at = ")
                    .append(span)
                    .append(";\n        return Err(format!(\"@typeof constraint violated: ")
                    .append(constraint.capture().name())
                    .append(" must be same type as ")
                    .append(constraint.reference().name())
                    .append(
                            ", expected {} but got {} at CP {}:{}\","
                                + " expected_type.rsplit(\"::\").next().unwrap().rsplit(\"__\").next().unwrap(),"
                                + " actual_type.rsplit(\"::\").next().unwrap().rsplit(\"__\").next().unwrap(),"
                                + " at.start, at.end));\n"
                                + "    }\n");
        }
        if (rule.mapping() == null) return out.append("    Ok(projected)\n}\n").toString();
        out.append("    Ok(vec![Ast::r#")
                .append(rule.mapping().name())
                .append(" {\n        span: node.span,\n");
        for (Field field : rule.mapping().fields())
            out.append("        r#")
                    .append(field.name())
                    .append(": _value_")
                    .append(index(fields, field.name()))
                    .append(",\n");
        return out.append("    }])\n}\n").toString();
    }

    private static int index(List<Field> fields, String name) {
        for (int i = 0; i < fields.size(); i++) if (fields.get(i).name().equals(name)) return i;
        throw new IllegalArgumentException("unknown @typeof capture " + name);
    }

    private static Cardinality outerCardinality(Field field) {
        return field.kind() == Kind.NESTED
                ? field.nested().containers().stream().findFirst().orElse(Cardinality.ONE)
                : field.cardinality();
    }

    private static String type(Field field, int index) {
        return switch (outerCardinality(field)) {
            case OPTIONAL -> "\"Optional\"";
            case MANY -> "\"List\"";
            case ONE ->
                    switch (field.kind()) {
                        case NESTED ->
                                switch (field.nested().leaf()) {
                                    case VALUE -> "typeof_nested_value(&_value_" + index + ")";
                                    case NODE -> "typeof_node(&_value_" + index + ")";
                                    case ENUM ->
                                            RustBackend.quote("enum::" + field.nested().enumName());
                                    case INTEGER -> "\"Integer\"";
                                    case TEXT -> "\"String\"";
                                    default ->
                                            throw new IllegalArgumentException(
                                                    "invalid nested leaf");
                                };
                        case INTEGER -> "\"Integer\"";
                        case TEXT -> "\"String\"";
                        case ENUM -> RustBackend.quote("enum::" + field.enumName());
                        case NODE -> "typeof_node(&_value_" + index + ")";
                        case NULLABLE_NODE ->
                                "typeof_node(_value_"
                                        + index
                                        + ".as_deref().ok_or(\"missing associative left"
                                        + " operand\")?)";
                        case VALUE -> "typeof_value(&_value_" + index + ")";
                    };
        };
    }
}
