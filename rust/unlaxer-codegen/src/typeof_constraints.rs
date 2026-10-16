use crate::*;
use std::fmt::Write;

pub(super) fn fields(rule: &Rule) -> Vec<Field> {
    let mut fields = rule
        .mapping
        .as_ref()
        .map(|mapping| mapping.fields.clone())
        .unwrap_or_default();
    for constraint in &rule.type_constraints {
        for value in [&constraint.capture, &constraint.reference] {
            if !fields.iter().any(|field| field.name == value.name) {
                fields.push(value.clone());
            }
        }
    }
    fields
}

pub(super) fn helpers(ir: &GrammarIr) -> String {
    let fields: Vec<_> = ir
        .rules
        .iter()
        .flat_map(|rule| &rule.type_constraints)
        .flat_map(|value| [&value.capture, &value.reference])
        .collect();
    let values = fields
        .iter()
        .any(|field| field.kind == Kind::Value && field.cardinality == Cardinality::One);
    let nested_values = fields.iter().any(|field| matches!(&field.kind,Kind::Nested {leaf, containers} if containers.is_empty() && **leaf==Kind::Value));
    let nodes = values || nested_values
        || fields.iter().any(|field| matches!(&field.kind,Kind::Nested {leaf, containers} if containers.is_empty() && **leaf==Kind::Node))
        || fields.iter().any(|field| {
            matches!(field.kind, Kind::Node | Kind::NullableNode)
                && field.cardinality == Cardinality::One
        });
    let mut out = String::new();
    if nodes {
        out.push_str("\nfn typeof_node(value: &Ast) -> &'static str {\n    match value {\n");
        for mapping in mappings(ir) {
            writeln!(
                out,
                "        Ast::r#{} {{ .. }} => {},",
                mapping.name,
                quote(&format!("node::{}", mapping.name))
            )
            .unwrap();
        }
        out.push_str("    }\n}\n");
    }
    if values {
        out.push_str("\nfn typeof_value(value: &AstValue) -> &'static str {\n    match value {\n        AstValue::Text { .. } => \"String\",\n        AstValue::Node(node) => typeof_node(node),\n");
        for rule in ir
            .rules
            .iter()
            .filter(|rule| !rule.enum_literals.is_empty())
        {
            writeln!(
                out,
                "        AstValue::Enum{} {{ .. }} => {},",
                rule.name,
                quote(&format!("enum::{}", rule.name))
            )
            .unwrap();
        }
        out.push_str("    }\n}\n");
    }
    if nested_values {
        out.push_str("\nfn typeof_nested_value(value: &NestedValue) -> &'static str {\n    match value {\n        NestedValue::Text { .. } => \"String\",\n        NestedValue::Integer { .. } => \"Integer\",\n        NestedValue::Node(node) => typeof_node(node),\n");
        for rule in ir
            .rules
            .iter()
            .filter(|rule| !rule.enum_literals.is_empty())
        {
            writeln!(
                out,
                "        NestedValue::Enum{} {{ .. }} => {},",
                rule.name,
                quote(&format!("enum::{}", rule.name))
            )
            .unwrap();
        }
        out.push_str("    }\n}\n");
    }
    out.push_str("\nfn typeof_span(node: &unlaxer_runtime::Node, name: &str) -> unlaxer_runtime::Span {\n    let mut found: Option<unlaxer_runtime::Span> = None;\n    for capture in node.captures.iter().filter(|capture| capture.name == name) {\n        found = Some(match found {\n            None => capture.span,\n            Some(span) => unlaxer_runtime::Span { start: std::cmp::min(span.start, capture.span.start), end: std::cmp::max(span.end, capture.span.end) },\n        });\n    }\n    found.unwrap_or(node.span)\n}\n");
    out
}

pub(super) fn rule(ir: &GrammarIr, id: usize) -> String {
    let rule = &ir.rules[id];
    let fields = fields(rule);
    let mut out = format!("\n#[inline(never)]\nfn map_rule_{id}(tree: &Tree, id: usize) -> Result<Vec<Ast>, String> {{\n    let node = &tree.nodes[id];\n");
    if rule.mapping.is_none() {
        out.push_str("    let projected = map_nodes(tree, &node.children)?;\n");
    }
    for (index, field) in fields.iter().enumerate() {
        writeln!(out,"    let _value_{index} = {{\n        let mut values = Vec::new();\n        for capture in node.captures.iter().filter(|capture| capture.name == {}) {{",quote(&field.name)).unwrap();
        if let Some(code) = associative::capture(rule.mapping.as_ref(), field, "            ") {
            out.push_str(&code);
        } else {
            match &field.kind {
            Kind::Enum(name) => {
                let enum_id=ir.rules.iter().position(|rule|&rule.name==name).unwrap();
                writeln!(out,"            values.extend(map_enum_{enum_id}(tree, &capture.nodes)?);").unwrap();
            }
            Kind::Nested { leaf, containers } => {
                let projection = match leaf.as_ref() {
                    Kind::Value => Some("map_nested_values".to_owned()),
                    Kind::Node => Some("map_nodes".to_owned()),
                    Kind::Enum(name) => Some(format!("map_enum_{}", ir.rules.iter().position(|r| &r.name==name).unwrap())),
                    _ => None,
                };
                out.push_str("            let nested = nested_capture(tree, capture)?;\n");
                if projection.is_some() { out.push_str("            let mut nested_sources = nested_source_nodes(tree, capture.nodes[0])?.into_iter();\n"); }
                writeln!(out,"            values.push({}?);", nested::mapped(leaf, containers, "&nested", 0, projection.as_deref())).unwrap();
                if projection.is_some() { out.push_str("            if nested_sources.next().is_some() { return Err(\"unmapped original nested leaf\".into()); }\n"); }
            },
            Kind::NullableNode => unreachable!("validated associative projection"),
            Kind::Node if rule.mapping.is_none() => out.push_str("            if !capture.nodes.is_empty() { values.extend(projected.iter().cloned()); }\n"),
            Kind::Node => out.push_str("            values.extend(map_nodes(tree, &capture.nodes)?);\n"),
            Kind::Value => out.push_str("            values.extend(map_values(tree, &capture.nodes)?);\n"),
            Kind::Integer => out.push_str("            values.push(unlaxer_runtime::java_capture_text(tree.text(capture.span)?).parse::<i32>().map_err(|_| format!(\"invalid i32 capture {} at {}..{}\", capture.name, capture.span.start, capture.span.end))?);\n"),
            Kind::Text if grapheme_capture(ir, &rule.body, &field.name) => out.push_str("            values.push(tree.text(capture.span)?.to_owned());\n"),
            Kind::Text => out.push_str("            values.push(unlaxer_runtime::java_capture_text(tree.text(capture.span)?).to_owned());\n"),
        }
        }
        let value = mapped_value(field);
        writeln!(out, "        }}\n        {value}\n    }};").unwrap();
    }
    for constraint in &rule.type_constraints {
        let own = index(&fields, &constraint.capture.name);
        let reference = index(&fields, &constraint.reference.name);
        let own_type = type_expression(&fields[own], own);
        let ref_type = type_expression(&fields[reference], reference);
        let mut span = format!("typeof_span(node, {})", quote(&constraint.capture.name));
        match outer_cardinality(&fields[own]) {
            Cardinality::Many => {
                span = format!("if _value_{own}.is_empty() {{ node.span }} else {{ {span} }}")
            }
            Cardinality::Optional => {
                span = format!("if _value_{own}.is_none() {{ node.span }} else {{ {span} }}")
            }
            Cardinality::One => (),
        }
        writeln!(out,"    let expected_type = {ref_type};\n    let actual_type = {own_type};\n    if expected_type != actual_type {{\n        let at = {span};").unwrap();
        writeln!(out,"        return Err(format!(\"@typeof constraint violated: {} must be same type as {}, expected {{}} but got {{}} at CP {{}}:{{}}\", expected_type.rsplit(\"::\").next().unwrap().rsplit(\"__\").next().unwrap(), actual_type.rsplit(\"::\").next().unwrap().rsplit(\"__\").next().unwrap(), at.start, at.end));\n    }}",constraint.capture.name,constraint.reference.name).unwrap();
    }
    if rule.mapping.is_none() {
        out.push_str("    Ok(projected)\n}\n");
        return out;
    }
    writeln!(
        out,
        "    Ok(vec![Ast::r#{} {{\n        span: node.span,",
        rule.mapping.as_ref().unwrap().name
    )
    .unwrap();
    for field in &rule.mapping.as_ref().unwrap().fields {
        writeln!(
            out,
            "        r#{}: _value_{},",
            field.name,
            index(&fields, &field.name)
        )
        .unwrap();
    }
    out.push_str("    }])\n}\n");
    out
}

fn index(fields: &[Field], name: &str) -> usize {
    fields.iter().position(|field| field.name == name).unwrap()
}
fn type_expression(field: &Field, index: usize) -> String {
    match outer_cardinality(field) {
        Cardinality::Optional => "\"Optional\"".into(),
        Cardinality::Many => "\"List\"".into(),
        Cardinality::One => match &field.kind {
            Kind::Nested { leaf, .. } => match leaf.as_ref() {
                Kind::Value => format!("typeof_nested_value(&_value_{index})"),
                Kind::Node => format!("typeof_node(&_value_{index})"),
                Kind::Enum(name) => quote(&format!("enum::{name}")),
                Kind::Integer => "\"Integer\"".into(),
                Kind::Text => "\"String\"".into(),
                _ => unreachable!("validated nested leaf"),
            },
            Kind::Integer => "\"Integer\"".into(),
            Kind::Text => "\"String\"".into(),
            Kind::Enum(name) => quote(&format!("enum::{name}")),
            Kind::Node => format!("typeof_node(&_value_{index})"),
            Kind::NullableNode => format!("typeof_node(_value_{index}.as_deref().ok_or(\"missing associative left operand\")?)"),
            Kind::Value => format!("typeof_value(&_value_{index})"),
        },
    }
}

fn outer_cardinality(field: &Field) -> Cardinality {
    if let Kind::Nested { containers, .. } = &field.kind {
        containers.first().copied().unwrap_or(Cardinality::One)
    } else {
        field.cardinality
    }
}
