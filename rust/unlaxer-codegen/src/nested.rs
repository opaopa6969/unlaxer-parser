//! Emission for explicit, typed container captures; legacy flat fields are unchanged.
use crate::{Cardinality, Kind};

pub(crate) fn ty(leaf: &Kind, containers: &[Cardinality]) -> String {
    let mut value = match leaf {
        Kind::Text => "String".into(),
        Kind::Integer => "i32".into(),
        Kind::Node => "Box<Ast>".into(),
        Kind::Enum(name) => name.clone(),
        Kind::Value => "NestedValue".into(),
        _ => unreachable!("validated nested leaf"),
    };
    for container in containers.iter().rev() {
        value = match container {
            Cardinality::Optional => format!("Option<{value}>"),
            Cardinality::Many => format!("Vec<{value}>"),
            Cardinality::One => unreachable!("validated nested container"),
        };
    }
    value
}
pub(crate) fn json(leaf: &Kind, containers: &[Cardinality], value: &str, depth: usize) -> String {
    let Some((container, tail)) = containers.split_first() else {
        return if *leaf == Kind::Integer {
            format!("{value}.to_string()")
        } else if matches!(leaf, Kind::Node | Kind::Enum(_) | Kind::Value) {
            format!("{value}.canonical_json()")
        } else {
            format!("json_string({value})")
        };
    };
    let child = format!("nested_{depth}");
    let inner = json(leaf, tail, &child, depth + 1);
    match container {
        Cardinality::Optional => format!("{value}.as_ref().map_or_else(|| \"null\".to_owned(), |{child}| {inner})"),
        Cardinality::Many => format!("format!(\"[{{}}]\", {value}.iter().map(|{child}| {inner}).collect::<Vec<_>>().join(\",\"))"),
        Cardinality::One => unreachable!("validated nested container"),
    }
}
pub(crate) fn mapped(
    leaf: &Kind,
    containers: &[Cardinality],
    value: &str,
    depth: usize,
    projection: Option<&str>,
) -> String {
    let Some((container, tail)) = containers.split_first() else {
        if let Some(projection) = projection {
            let mapped =
                format!("nested_project(tree, {value}, &mut nested_sources, {projection})");
            return if *leaf == Kind::Node {
                format!("{mapped}.map(Box::new)")
            } else {
                mapped
            };
        }
        return format!(
            "nested_{}({value})",
            if *leaf == Kind::Integer {
                "integer"
            } else {
                "text"
            }
        );
    };
    let child = format!("nested_{depth}");
    let inner = mapped(leaf, tail, &child, depth + 1, projection);
    format!(
        "nested_{}({value}, |{child}| {inner})",
        if *container == Cardinality::Optional {
            "optional"
        } else {
            "list"
        }
    )
}

pub(crate) const HELPERS: &str = r#"
fn nested_capture(tree: &Tree, capture: &unlaxer_runtime::Capture) -> Result<unlaxer_runtime::capture_tree::Element, String> {
    if capture.nodes.len() != 1 { return Err(format!("invalid nested capture {} at {}..{}", capture.name, capture.span.start, capture.span.end)); }
    let snapshot = unlaxer_runtime::capture_tree::snapshot(tree, capture.nodes[0]).map_err(|error| format!("invalid nested capture {} at {}..{}: {error:?}", capture.name, capture.span.start, capture.span.end))?;
    let mut pending = vec![&snapshot];
    while let Some(value) = pending.pop() {
        tree.charge_work(1 + value.children().len())?;
        tree.charge_text_bytes(value.text().len())?;
        pending.extend(value.children());
    }
    Ok(snapshot)
}
fn nested_text(value: &unlaxer_runtime::capture_tree::Element) -> Result<String, String> {
    if value.kind() != unlaxer_runtime::capture_tree::Kind::Value { return Err("expected nested value boundary".into()); }
    Ok(value.text().to_owned())
}
fn nested_integer(value: &unlaxer_runtime::capture_tree::Element) -> Result<i32, String> {
    nested_text(value)?.trim_matches(|ch: char| ch <= '\u{20}').parse().map_err(|_| format!("invalid i32 nested capture at {}..{}", value.span().start, value.span().end))
}
fn nested_optional<T>(value: &unlaxer_runtime::capture_tree::Element, map: impl FnMut(&unlaxer_runtime::capture_tree::Element) -> Result<T, String>) -> Result<Option<T>, String> {
    if value.kind() != unlaxer_runtime::capture_tree::Kind::Optional || value.children().len() > 1 { return Err("expected nested optional boundary".into()); }
    value.children().first().map(map).transpose()
}
fn nested_list<T>(value: &unlaxer_runtime::capture_tree::Element, map: impl FnMut(&unlaxer_runtime::capture_tree::Element) -> Result<T, String>) -> Result<Vec<T>, String> {
    if value.kind() != unlaxer_runtime::capture_tree::Kind::List { return Err("expected nested list boundary".into()); }
    value.children().iter().map(map).collect()
}
"#;

pub(crate) const PROJECT_HELPERS: &str = r#"
// A leaf is identified by its original CST node, never by text or source position.
fn nested_source_nodes(tree: &Tree, root: usize) -> Result<Vec<usize>, String> {
    let mut pending = vec![(root, 0usize)];
    let mut values = Vec::new();
    let mut visits = 0usize;
    while let Some((id, depth)) = pending.pop() {
        tree.visit(id)?;
        visits += 1;
        if visits > 65536 || depth >= 256 { return Err("nested projection traversal limit".into()); }
        let node = tree.nodes.get(id).ok_or("invalid nested projection node")?;
        if unlaxer_runtime::capture_tree::Kind::of_rule(node.rule) == Some(unlaxer_runtime::capture_tree::Kind::Separator) { continue; }
        if unlaxer_runtime::capture_tree::Kind::of_rule(node.rule) == Some(unlaxer_runtime::capture_tree::Kind::Value) {
            values.push(id);
        } else {
            for &child in node.children.iter().rev() {
                if visits + pending.len() >= 65536 { return Err("nested projection traversal limit".into()); }
                pending.push((child, depth + 1));
            }
        }
    }
    Ok(values)
}
fn nested_project<T>(tree: &Tree, value: &unlaxer_runtime::capture_tree::Element, sources: &mut impl Iterator<Item=usize>, map: fn(&Tree, &[usize]) -> Result<Vec<T>, String>) -> Result<T, String> {
    if value.kind() != unlaxer_runtime::capture_tree::Kind::Value { return Err("expected nested mapped value boundary".into()); }
    let id = sources.next().ok_or("missing original nested leaf")?;
    let node = tree.nodes.get(id).ok_or("invalid original nested leaf")?;
    let mut values = map(tree, &node.children)?;
    if values.len() != 1 { return Err(format!("expected one nested mapped value at {}..{}, got {}", value.span().start, value.span().end, values.len())); }
    Ok(values.remove(0))
}
"#;

pub(crate) fn mixed_mapper(ir: &crate::GrammarIr) -> String {
    use std::fmt::Write;
    let mut out=String::from("\nfn map_nested_values(tree: &Tree, ids: &[usize]) -> Result<Vec<NestedValue>, String> {\n    let mut found = Vec::new();\n    for &id in ids {\n        tree.visit(id)?;\n        let node = &tree.nodes[id];\n        match node.rule {\n            unlaxer_runtime::TEXT_VALUE_RULE => found.push(NestedValue::Text { text: tree.text(node.span)?.to_owned(), span: node.span }),\n            unlaxer_runtime::VALUE_BOUNDARY_RULE => found.push(NestedValue::Integer { value: tree.text(node.span)?.trim_matches(|ch: char| ch <= '\\u{20}').parse().map_err(|_| format!(\"invalid i32 nested mixed capture at {}..{}\", node.span.start, node.span.end))?, span: node.span }),\n");
    for (id, rule) in ir
        .rules
        .iter()
        .enumerate()
        .filter(|(_, r)| !r.enum_literals.is_empty())
    {
        let name = &rule.name;
        writeln!(out,"            {id} => found.push(NestedValue::Enum{name} {{ value: {name}::from_text(tree.text(node.span)?)?, span: node.span }}),").unwrap();
    }
    let ids = ir
        .rules
        .iter()
        .enumerate()
        .filter(|(_, r)| {
            (r.mapping.is_some() || !r.type_constraints.is_empty()) && r.enum_literals.is_empty()
        })
        .map(|(id, _)| id.to_string())
        .collect::<Vec<_>>();
    writeln!(out,"            {} => found.extend(map_node(tree, id)?.into_iter().map(|node| NestedValue::Node(Box::new(node)))),",ids.join(" | ")).unwrap();
    out.push_str("            _ => found.extend(map_nested_values(tree, &node.children)?),\n        }\n    }\n    Ok(found)\n}\n");
    out
}

pub(crate) fn dap_node(field: &crate::Field) -> bool {
    matches!(&field.kind,Kind::Nested{leaf,..} if matches!(leaf.as_ref(),Kind::Node|Kind::Value))
}
pub(crate) fn dap_walk(
    leaf: &Kind,
    containers: &[Cardinality],
    value: &str,
    depth: usize,
) -> String {
    let Some((container, tail)) = containers.split_first() else {
        return if *leaf == Kind::Node {
            format!("collect({value}, out);")
        } else {
            format!(
                "if let super::ast::NestedValue::Node(child) = {value} {{ collect(child, out); }}"
            )
        };
    };
    let child = format!("nested_{depth}");
    let inner = dap_walk(leaf, tail, &child, depth + 1);
    if *container == Cardinality::Optional {
        format!("if let Some({child}) = {value} {{ {inner} }}")
    } else {
        format!("for {child} in {value} {{ {inner} }}")
    }
}
